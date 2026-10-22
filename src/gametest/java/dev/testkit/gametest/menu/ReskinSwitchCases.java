package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 768-769 (bz-reskin, 2026-10-11). killer560: "have reskin work at the same time" (the Bazaar reskin keeps drawing
 * while the flipper runs) and "for the bazaar and ah overlay make it so the toggle to the mod is also the toggle to the
 * reskin not a separate toggle".
 * <ul>
 *   <li>768: with the reskin drawing the flipper's product page, a key press stops the flipper (the reskin's key
 *       listener runs first, so it must do the stopping); no click reaches the server after it, and the key is not a
 *       hotbar swap on the hidden slot. Since 2026-10-21 the flipper is the autopilot (FlipMarket): it reads Manage
 *       Orders first, then walks the same pages for its Insta Buy.</li>
 *   <li>769: the one-switch migration on the real config files - an old reskin key that was on turns the feature on,
 *       both old keys leave the files, an "off" saved afterwards survives a reload; the files are put back after.</li>
 * </ul>
 */
final class ReskinSwitchCases {

    private ReskinSwitchCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "768-menu-bzflip-key-stops-on-reskin", ReskinSwitchCases::keyStops);
        MenuSuite.test(s, "769-menu-bz-ah-one-switch-migration", ReskinSwitchCases::migration);
    }

    static void keyStops(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: no flipper (761 checks it never starts)");
            return;
        }
        try (AutoCloseable w = FlipCroesusCases.flipWorld(c, true, true, 50.0)) {
            c.ctx().runOnClient(mc -> Mod.set("bazaarflip.BazaarFlipConfig", "setSpeed", 1)); // ~1 s per step
            c.hx().call("menu.onCommand", Hx.args("name", "bz", "menu",
                    FlipMarket.withManageOrders(FlipCroesusCases.bazaar(35.0))));
            String refusal = FlipCroesusCases.start(c);
            c.check(refusal == null, "start refused: " + refusal);
            c.waitUntil("the reskin to draw the product page the flipper reached",
                    mc -> "PRODUCT".equals(Mod.staticCall("bazaar.BazaarReskin", "kindForTest")), 300);
            int before = c.events("menu.click").size();
            c.ctx().getInput().pressKey(options -> options.keyHotbarSlots[0]);
            c.waitUntil("the flipper to stop on the key",
                    mc -> !(Boolean) Mod.staticCall("bazaarflip.BazaarFlipFeature", "isRunning"), 20);
            c.ctx().waitTicks(60);
            int after = c.events("menu.click").size();
            c.check(after == before, "clicks after the key: " + FlipCroesusCases.allClicks(c));
            c.check(FlipCroesusCases.clicksOn(c, FlipCroesusCases.INSTANT_TITLE).isEmpty(), "reached Instant Buy anyway");
            long swaps = c.events("container.click").stream().filter(e -> e.get("input").getAsString().equals("SWAP")).count();
            c.check(swaps == 0, "the key went to the hidden menu as " + swaps + " hotbar swap(s)");
            c.note("reskin drawing PRODUCT, pressed 1: flipper stopped, " + before + " click(s) before and none after ("
                    + FlipCroesusCases.allClicks(c) + ")");
        }
    }

    static void migration(Session c) throws Exception {
        Path bz = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-auction.json"));
        Path ah = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-auction-house.json"));
        byte[] bzWas = Files.exists(bz) ? Files.readAllBytes(bz) : null;
        byte[] ahWas = Files.exists(ah) ? Files.readAllBytes(ah) : null;
        try {
            Files.createDirectories(bz.getParent());
            Files.createDirectories(ah.getParent());
            // (a) both features off, both old reskins on: on if either was on.
            write(bz, "{\"ahEnabled\": false, \"bazaarEnabled\": false, \"reskinRealBazaar\": true, \"bazaarVanillaKeyCode\": 342}");
            write(ah, "{\"reskinRealAh\": true, \"vanillaKeyCode\": 77, \"trackRecents\": false}");
            boolean[] on = load(c);
            c.check(on[0] && on[1], "reskin on, feature off migrated to bazaar " + on[0] + ", ah " + on[1]);
            JsonObject bzNow = read(bz);
            JsonObject ahNow = read(ah);
            c.check(!bzNow.has("reskinRealBazaar") && bzNow.get("bazaarEnabled").getAsBoolean(),
                    "auction.json after the migration: " + bzNow);
            c.check(!ahNow.has("reskinRealAh") && ahNow.get("vanillaKeyCode").getAsInt() == 77
                    && !ahNow.get("trackRecents").getAsBoolean(), "auction-house.json after the migration: " + ahNow);
            // (b) an "off" saved after the migration survives a reload (the old key is not read twice).
            c.ctx().runOnClient(mc -> {
                Mod.set("auction.AuctionConfig", "setBazaarEnabled", false);
                Mod.set("auction.AuctionConfig", "setAhEnabled", false);
                Mod.call(Mod.cfg("auction.AuctionConfig"), "save");
            });
            on = load(c);
            c.check(!on[0] && !on[1], "turned off after the migration, reloaded as bazaar " + on[0] + ", ah " + on[1]);
            // (c) both old reskins off and both features off: stays off.
            write(bz, "{\"ahEnabled\": false, \"bazaarEnabled\": false, \"reskinRealBazaar\": false}");
            write(ah, "{\"reskinRealAh\": false}");
            on = load(c);
            c.check(!on[0] && !on[1], "everything off migrated to bazaar " + on[0] + ", ah " + on[1]);
            // (d) feature on, reskin off: the one switch is on (it was on).
            write(bz, "{\"ahEnabled\": true, \"bazaarEnabled\": true, \"reskinRealBazaar\": false}");
            write(ah, "{\"reskinRealAh\": false}");
            on = load(c);
            c.check(on[0] && on[1], "feature on, reskin off migrated to bazaar " + on[0] + ", ah " + on[1]);
            c.note("migration: reskin-only on -> on (keys removed, other AH settings kept); off after it stays off;"
                    + " all off stays off; feature on stays on");
        } finally {
            restore(bz, bzWas);
            restore(ah, ahWas);
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("auction.AuctionConfig", "load");
                Mod.staticCall("auction.AuctionHouseConfig", "load");
            });
        }
    }

    private static boolean[] load(Session c) {
        return c.onClient(mc -> {
            Mod.staticCall("auction.AuctionConfig", "load");
            Mod.staticCall("auction.AuctionHouseConfig", "load");
            Object cfg = Mod.cfg("auction.AuctionConfig");
            return new boolean[]{(Boolean) Mod.call(cfg, "isBazaarEnabledRaw"), (Boolean) Mod.call(cfg, "isAhEnabledRaw")};
        });
    }

    private static void write(Path p, String json) throws Exception {
        Files.writeString(p, json, StandardCharsets.UTF_8);
    }

    private static JsonObject read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void restore(Path p, byte[] was) throws Exception {
        if (was == null) {
            Files.deleteIfExists(p);
        } else {
            Files.write(p, was);
        }
    }
}
