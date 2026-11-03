package dev.testkit.gametest.hx;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Kicked To Hub (mod branch kickhub-1103, killer560 2026-11-03: "look at skyhanni's kicked from server stuff if you get
 * sent to the hub and add that"), 1103-kickhub-*. Every case drives the mod with Hypixel's own lines (system chat) and
 * the tab list's "Area:" line, and judges by the commands the SERVER received:
 * <ul>
 *   <li>evacuate: on Garden, "Evacuating to Hub...", then a real world change (into the nether) onto an "Area: Hub" tab:
 *       the Hub title, the [Warp Back] line whose click runs /warp garden, and with Auto Warp Back OFF no command at all.</li>
 *   <li>auto: on Private Island, moved to the Hub (world change), then "A kick occurred in your connection, so you were
 *       put in the SkyBlock lobby!": exactly one /is, not before the 3 s delay, and nothing more after it.</li>
 *   <li>retry: a "Couldn't warp you! Try again later. (PLAYER_TRANSFER_COOLDOWN)" reply retries once 10 s later, and a
 *       command typed by the player cancels a pending warp.</li>
 *   <li>forged: the same lines typed by players (all chat, party, a whisper) do nothing at all; the real restart line
 *       titles.</li>
 *   <li>rejoin: SkyHanni's Kick Duration - kicked while joining outside SkyBlock, the HUD counts and the rejoin title
 *       comes after the warn time; reaching SkyBlock clears it.</li>
 *   <li>countdown: the sidebar's "Server closing: 00:30" titles "Server Restart in 0:30".</li>
 *   <li>off / reload: with the switch off nothing reacts; every setting survives a save and a fresh load.</li>
 * </ul>
 */
final class HxKickHub1103Cases {

    private static final String CFG = "kickhub.KickHubConfig";
    private static final String F = "kickhub.KickHubFeature";

    private HxKickHub1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-kickhub-evacuate", HxKickHub1103Cases::evacuate);
        s.test("1103-kickhub-auto", HxKickHub1103Cases::auto);
        s.test("1103-kickhub-retry", HxKickHub1103Cases::retry);
        s.test("1103-kickhub-forged", HxKickHub1103Cases::forged);
        s.test("1103-kickhub-rejoin", HxKickHub1103Cases::rejoin);
        s.test("1103-kickhub-countdown", HxKickHub1103Cases::countdown);
        s.test("1103-kickhub-off", HxKickHub1103Cases::off);
        s.test("1103-kickhub-reload", HxKickHub1103Cases::reload);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static Settings settings(Session c, boolean enabled, boolean auto, int delay) {
        return new Settings(c).with(CFG, "Enabled", enabled)
                .with(CFG, "HubAlert", true)
                .with(CFG, "AlertSound", true)
                .with(CFG, "WarpBackLine", true)
                .with(CFG, "AutoWarpBack", auto)
                .with(CFG, "AutoDelaySeconds", delay)
                .with(CFG, "RestartAlert", true)
                .with(CFG, "RestartCountdown", true)
                .with(CFG, "RejoinTimer", true);
    }

    /** On Skyblock (the hub sidebar) on {@code area}, read by the feature. */
    private static void onIsland(Session c, String area) {
        sidebar(c, "template", "hub");
        c.hx().tab(List.of("Area: " + area, "Profile: Test", ""), null, null);
        c.waitUntil("the feature reads Area: " + area, mc -> area.equals(Mod.staticCall(F, "area")), 100);
        run(c, () -> Mod.staticCall(F, "testReset"));
    }

    /** Hypixel's server switch, as close as one server gets: a real world change (into the nether), then the Hub tab. */
    private static void worldChangeToHub(Session c) {
        Object before = c.onClient(mc -> (Object) mc.level);
        c.server().command("execute in minecraft:the_nether run forceload add 0 0");
        c.server().command("execute in minecraft:the_nether run fill -3 99 -3 3 99 3 minecraft:obsidian");
        c.server().command("execute in minecraft:the_nether run fill -3 100 -3 3 104 3 minecraft:air");
        c.server().command("execute in minecraft:the_nether run tp @p 0.5 100 0.5 0 0");
        c.waitUntil("the world change", mc -> mc.level != null && mc.level != before && mc.player != null
                && Math.abs(mc.player.getX() - 0.5) < 0.1, 400);
        c.hx().tab(List.of("Area: Hub", "Profile: Test", ""), null, null);
    }

    private static void backToPlatform(Session c) {
        c.server().command("execute in minecraft:overworld run tp @p -419.5 151 -419.5 0 0");
        c.waitUntil("back on the platform", mc -> mc.player != null && Math.abs(mc.player.getX() + 419.5) < 0.1
                && Math.abs(mc.player.getY() - 151) < 0.1, 400);
        c.ctx().waitTicks(20);
    }

    /** Commands at the server since {@code head} that warp ("warp ...", "is", "hub"...). */
    private static List<String> warps(Session c, long head) {
        return c.hx().commands(head).stream()
                .filter(x -> x.matches("(?i)^(warp( .*)?|is|hub|skyblock|lobby)$")).toList();
    }

    private static String runCommandOf(Session c) {
        Component link = c.onClient(mc -> (Component) Mod.staticCall(F, "lastWarpLink"));
        ClickEvent click = link == null ? null : link.getStyle().getClickEvent();
        return click instanceof ClickEvent.RunCommand rc ? rc.command() : String.valueOf(click);
    }

    private static String title(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(F, "lastTitle"));
    }

    // ---- cases ------------------------------------------------------------------------------------------------------

    private static void evacuate(Session c) {
        try (Settings set = settings(c, true, false, 3)) {
            onIsland(c, "Garden");
            int soundsBefore = c.onClient(mc -> (Integer) Mod.staticCall(F, "sounds"));
            long head = c.hx().head();
            long mark = LogTap.mark();
            c.hx().chat("§cEvacuating to Hub...");
            c.waitUntil("the evacuation to be remembered", mc -> (Boolean) Mod.staticCall(F, "awaitingHub"), 60);
            c.check(title(c) == null, "a title before reaching the Hub: " + title(c));
            worldChangeToHub(c);
            c.waitUntil("the warp back offered", mc -> Mod.staticCall(F, "lastOffer") != null, 100);
            String offer = c.onClient(mc -> (String) Mod.staticCall(F, "lastOffer"));
            c.check("warp garden".equals(offer), "offered /" + offer + ", want /warp garden");
            c.check("Garden".equals(c.onClient(mc -> Mod.staticCall(F, "lastOrigin"))), "origin "
                    + c.onClient(mc -> Mod.staticCall(F, "lastOrigin")));
            String cmd = runCommandOf(c);
            c.check("/warp garden".equals(cmd), "[Warp Back] runs '" + cmd + "'");
            c.check(title(c) != null && title(c).contains("Sent to the Hub"), "title " + title(c));
            int sounds = c.onClient(mc -> (Integer) Mod.staticCall(F, "sounds")) - soundsBefore;
            c.check(sounds == 1, "sounds played: " + sounds);
            c.ctx().waitTicks(10);
            c.check(LogTap.since(mark).stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("[Warp Back]")
                    && l.contains("Garden")), "no chat line with [Warp Back] and Garden");
            // Auto Warp Back is OFF: nothing goes out, however long he stays.
            c.ctx().waitTicks(160);
            c.check(warps(c, head).isEmpty(), "Auto Warp Back OFF sent " + warps(c, head));
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(F, "autoPending")), "an automatic warp is pending");
            c.note("Garden -> Evacuating -> world change -> Hub: title '" + title(c) + "', [Warp Back] " + cmd
                    + ", 1 sound, no command in 8 s with Auto OFF");
        } finally {
            backToPlatform(c);
        }
    }

    private static void auto(Session c) {
        try (Settings set = settings(c, true, true, 3)) {
            onIsland(c, "Private Island");
            worldChangeToHub(c);
            c.waitUntil("the feature reads Area: Hub", mc -> "Hub".equals(Mod.staticCall(F, "area")), 100);
            long head = c.hx().head();
            long t0 = System.currentTimeMillis();
            c.hx().chat("§cA kick occurred in your connection, so you were put in the SkyBlock lobby!");
            c.waitUntil("the warp back offered", mc -> Mod.staticCall(F, "lastOffer") != null, 100);
            String offer = c.onClient(mc -> (String) Mod.staticCall(F, "lastOffer"));
            c.check("is".equals(offer), "offered /" + offer + ", want /is (the island before the Hub)");
            c.waitUntil("the automatic warp at the server", mc -> !warps(c, head).isEmpty(), 200);
            long after = System.currentTimeMillis() - t0;
            c.check(after >= 2500, "the warp went after " + after + " ms, before the 3 s delay");
            c.ctx().waitTicks(120);
            List<String> got = warps(c, head);
            c.check(got.equals(List.of("is")), "commands " + got + ", want exactly [is]");
            c.check((Integer) c.onClient(mc -> Mod.staticCall(F, "autoSent")) >= 1, "autoSent counter");
            c.note("Private Island -> world change -> Hub -> kick line: one /is after " + after + " ms, nothing more in 6 s");
        } finally {
            backToPlatform(c);
        }
    }

    private static void retry(Session c) {
        try (Settings set = settings(c, true, true, 3)) {
            // A cooldown reply: one retry 10 s later.
            onIsland(c, "Crimson Isle");
            long head = c.hx().head();
            c.hx().chat("§cEvacuating to Hub...");
            c.hx().tab(List.of("Area: Hub", "Profile: Test", ""), null, null);
            c.waitUntil("the first automatic warp", mc -> !warps(c, head).isEmpty(), 240);
            c.check(warps(c, head).equals(List.of("warp isle")), "first " + warps(c, head));
            long t1 = System.currentTimeMillis();
            c.hx().chat("§cCouldn't warp you! Try again later. (PLAYER_TRANSFER_COOLDOWN)");
            c.waitUntil("the retry", mc -> warps(c, head).size() >= 2, 300);
            long gap = System.currentTimeMillis() - t1;
            c.check(gap >= 9000, "the retry came " + gap + " ms after the reply, want ~10 s");
            c.ctx().waitTicks(100);
            c.check(warps(c, head).equals(List.of("warp isle", "warp isle")), "after retry " + warps(c, head));
            String first = "warp isle x2 (retry " + gap + " ms after the cooldown reply)";

            // A command he sends himself cancels a pending one.
            onIsland(c, "The End");
            long head2 = c.hx().head();
            c.hx().chat("§cEvacuating to Hub...");
            c.hx().tab(List.of("Area: Hub", "Profile: Test", ""), null, null);
            c.waitUntil("the automatic warp pending", mc -> (Boolean) Mod.staticCall(F, "autoPending"), 100);
            type(c, "/hxkickhubnoop");
            c.ctx().waitTicks(120);
            List<String> got = warps(c, head2);
            c.check(got.isEmpty(), "a typed command did not cancel: " + got);
            c.check(c.hx().commands(head2).contains("hxkickhubnoop"), "the typed command never reached the server: "
                    + c.hx().commands(head2));
            c.note(first + "; a typed /hxkickhubnoop canceled the pending /warp end (none sent in 6 s)");
        }
    }

    private static void forged(Session c) {
        try (Settings set = settings(c, true, true, 3)) {
            onIsland(c, "Garden");
            long head = c.hx().head();
            String[] typed = {
                    "§7[312] §b[MVP§c+§b] HxMateA§f: Evacuating to Hub...",
                    "§9Party §8> §b[MVP§c+§b] HxMateA§f: A kick occurred in your connection, so you were put in the SkyBlock lobby!",
                    "§dFrom §b[MVP§c+§b] HxMateB§7: You were kicked while joining that server!",
                    "§a[VIP] HxMateA§f: [Important] This server will restart soon: Scheduled Reboot",
                    "§2Guild > §b[MVP§c+§b] HxMateA §e[Member]§f: There was a problem joining SkyBlock, try again in a moment!",
            };
            for (String line : typed) {
                c.hx().chat(line);
                c.ctx().waitTicks(6);
            }
            c.hx().tab(List.of("Area: Hub", "Profile: Test", ""), null, null);
            c.ctx().waitTicks(120);
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(F, "awaitingHub")), "a typed line armed the Hub wait");
            c.check(c.onClient(mc -> Mod.staticCall(F, "lastOffer")) == null, "a typed line offered a warp");
            c.check(title(c) == null, "a typed line showed a title: " + title(c));
            c.check(warps(c, head).isEmpty(), "typed lines sent " + warps(c, head));
            c.check(c.onClient(mc -> Mod.staticCall(F, "lastHudText")) == null, "the kick timer started");

            // The real restart line titles (on SkyBlock, out of a dungeon).
            onIsland(c, "Garden");
            c.hx().chat("§c[Important] §eThis server will restart soon: §bScheduled Reboot");
            c.waitUntil("the restart title", mc -> Mod.staticCall(F, "lastTitle") != null, 60);
            c.check(title(c).contains("restarting soon"), "restart title " + title(c));
            c.note("5 player-typed copies: nothing armed, offered, titled or sent; the real restart line titles '"
                    + title(c) + "'");
            run(c, () -> Mod.staticCall(F, "testReset"));
        }
    }

    private static void rejoin(Session c) {
        try (Settings set = settings(c, true, false, 3).with(CFG, "RejoinWarnSeconds", 60)) {
            run(c, () -> Mod.staticCall(F, "testReset"));
            // A Hypixel lobby: no SKYBLOCK in the sidebar title.
            c.hx().sidebar("§e§lHYPIXEL", "§fRank: §aVIP", "§ewww.hypixel.net");
            c.waitUntil("off SkyBlock", mc -> !(Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 200);
            c.hx().chat("§cYou were kicked while joining that server!");
            c.waitUntil("the Kick Duration HUD drawn", mc -> Mod.staticCall(F, "lastHudText") != null, 60);
            String hud = c.onClient(mc -> (String) Mod.staticCall(F, "lastHudText"));
            c.check(hud.startsWith("Kicked from SkyBlock") && hud.endsWith("ago"), "HUD '" + hud + "'");
            c.check(!(Boolean) c.onClient(mc -> Mod.staticCall(F, "rejoinWarned")), "warned before the warn time");
            run(c, () -> Mod.staticCall(F, "testAgeKick", 61_000L));
            c.waitUntil("the rejoin title", mc -> (Boolean) Mod.staticCall(F, "rejoinWarned"), 40);
            c.check(title(c) != null && title(c).contains("Try rejoining SkyBlock now!"), "title " + title(c));
            String later = c.onClient(mc -> (String) Mod.staticCall(F, "lastHudText"));
            // Back on SkyBlock: the timer clears.
            sidebar(c, "template", "hub");
            c.waitUntil("on SkyBlock", mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 200);
            c.ctx().waitTicks(10);
            run(c, () -> Mod.setField(F, "lastHudText", null));
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> Mod.staticCall(F, "lastHudText")) == null, "the HUD still draws on SkyBlock");
            c.note("lobby kick: HUD '" + hud + "' -> '" + later + "', title after the warn time, cleared on SkyBlock");
            run(c, () -> Mod.staticCall(F, "testReset"));
        }
    }

    private static void countdown(Session c) {
        try (Settings set = settings(c, true, false, 3)) {
            onIsland(c, "Gold Mine");
            c.hx().sidebar("§e§lSKYBLOCK", "§7 ⏣ §6Gold Mine", "§cServer closing: 00:30 §8m77A", "§ewww.hypixel.net");
            c.waitUntil("the countdown title", mc -> Mod.staticCall(F, "lastTitle") != null, 80);
            c.check(title(c).contains("Server Restart in") && title(c).contains("0:30"), "title " + title(c));
            c.check((Boolean) c.onClient(mc -> Mod.staticCall(F, "awaitingHub")), "the countdown did not arm the Hub wait");
            c.note("sidebar 'Server closing: 00:30' -> '" + title(c) + "'");
            sidebar(c, "template", "hub");
            run(c, () -> Mod.staticCall(F, "testReset"));
        }
    }

    private static void off(Session c) {
        try (Settings set = settings(c, false, true, 3)) {
            // Off, the feature reads nothing at all (not even the tab), so there is no "reads Garden" to wait for.
            sidebar(c, "template", "hub");
            c.hx().tab(List.of("Area: Garden", "Profile: Test", ""), null, null);
            run(c, () -> Mod.staticCall(F, "testReset"));
            c.ctx().waitTicks(30);
            long head = c.hx().head();
            c.hx().chat("§cEvacuating to Hub...");
            c.hx().chat("§c[Important] §eThis server will restart soon: §bScheduled Reboot");
            c.hx().tab(List.of("Area: Hub", "Profile: Test", ""), null, null);
            c.ctx().waitTicks(120);
            c.check(c.onClient(mc -> Mod.staticCall(F, "lastOffer")) == null, "off: offered a warp");
            c.check(title(c) == null, "off: title " + title(c));
            c.check(warps(c, head).isEmpty(), "off: sent " + warps(c, head));
            c.note("switch off: evacuation + restart + Hub -> nothing");
        }
    }

    private static void reload(Session c) {
        Object cfg = c.onClient(mc -> Mod.cfg(CFG));
        Object[] saved = c.onClient(mc -> new Object[]{Mod.call(cfg, "isEnabled"), Mod.call(cfg, "isAutoWarpBack"),
                Mod.call(cfg, "getAutoDelaySeconds"), Mod.call(cfg, "isHubAlert"), Mod.call(cfg, "getRejoinWarnSeconds"),
                Mod.call(cfg, "isRestartCountdown"), Mod.call(cfg, "isWarpBackLine")});
        try {
            run(c, () -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setAutoWarpBack", true);
                Mod.call(cfg, "setAutoDelaySeconds", 17);
                Mod.call(cfg, "setHubAlert", false);
                Mod.call(cfg, "setRejoinWarnSeconds", 123);
                Mod.call(cfg, "setRestartCountdown", false);
                Mod.call(cfg, "setWarpBackLine", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CFG, "load");
            });
            Object fresh = c.onClient(mc -> Mod.cfg(CFG));
            String got = c.onClient(mc -> String.format(Locale.ROOT, "%s %s %s %s %s %s %s", Mod.call(fresh, "isEnabled"),
                    Mod.call(fresh, "isAutoWarpBack"), Mod.call(fresh, "getAutoDelaySeconds"), Mod.call(fresh, "isHubAlert"),
                    Mod.call(fresh, "getRejoinWarnSeconds"), Mod.call(fresh, "isRestartCountdown"),
                    Mod.call(fresh, "isWarpBackLine")));
            c.check("true true 17 false 123 false false".equals(got), "after reload: " + got);
            run(c, () -> {
                Mod.call(fresh, "setAutoDelaySeconds", 999);
                Mod.call(fresh, "setRejoinWarnSeconds", 1);
            });
            String clamped = c.onClient(mc -> Mod.call(fresh, "getAutoDelaySeconds") + " "
                    + Mod.call(fresh, "getRejoinWarnSeconds"));
            c.check("60 5".equals(clamped), "clamped " + clamped);
            c.note("save + load: " + got + "; clamped 999/1 -> " + clamped);
        } finally {
            run(c, () -> {
                Object f = Mod.cfg(CFG);
                Mod.call(f, "setEnabled", saved[0]);
                Mod.call(f, "setAutoWarpBack", saved[1]);
                Mod.call(f, "setAutoDelaySeconds", saved[2]);
                Mod.call(f, "setHubAlert", saved[3]);
                Mod.call(f, "setRejoinWarnSeconds", saved[4]);
                Mod.call(f, "setRestartCountdown", saved[5]);
                Mod.call(f, "setWarpBackLine", saved[6]);
                Mod.call(f, "save");
            });
        }
    }
}
