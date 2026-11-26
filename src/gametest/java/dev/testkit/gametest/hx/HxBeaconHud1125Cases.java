package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * 1125-hx-beacon-timer: Beacon Ready's timer is text over the beacon block, not a HUD element. The HUD element is gone and
 * an old saved position for it leaves the HUD config usable; with a beacon block in the loaded world the mod finds it,
 * and the real render path draws the countdown, which falls by a second between tab updates and is corrected by a new
 * read; with the block gone nothing draws. The tab is fed again on every poll because the feature's own once-a-second tab
 * read (no beacon widget in a test world) would reset the state.
 */
final class HxBeaconHud1125Cases {

    private static final String F = "foraging.BeaconReadyAlert";
    private static final String ID = "beacon_ready";

    private HxBeaconHud1125Cases() {
    }

    static void register(Session s) {
        s.test("1125-hx-beacon-timer", HxBeaconHud1125Cases::timer);
    }

    private static List<String> tab(String cooldown) {
        return List.of("Area: Moonglade Marsh", "Moonglade Beacon: 2 Stacks", " +32 Fig Fortune", " Cooldown: " + cooldown);
    }

    /** Feeds the tab at {@code at} and looks for the beacon, as the once-a-second tick does. */
    private static void feed(String cooldown, long at) {
        Mod.staticCall(F, "onTab", tab(cooldown), at);
        Mod.staticCall(F, "findBeacon", net.minecraft.client.Minecraft.getInstance());
    }

    private static void set(Session c, boolean on) {
        c.onClient(mc -> {
            Object cfg = Mod.field(F, "CFG");
            Mod.call(cfg, "set", "enabled", on);
            Mod.call(cfg, "set", "keep", false);
            Mod.call(cfg, "set", "cooldownHud", on);
            return null;
        });
    }

    private static void timer(Session c) {
        c.check((Boolean) c.onClient(mc -> Mod.staticCall("hud.HudElementRegistry", "byId", ID) == null),
                "the Beacon Ready HUD element is still registered");
        c.check((Boolean) c.onClient(mc -> {
            Object hud = Mod.staticCall("hud.HudConfig", "getInstance");
            Mod.call(hud, "setPosition", ID, 33, 44);
            Mod.call(hud, "save");
            Mod.staticCall("hud.HudConfig", "load");
            Object again = Mod.staticCall("hud.HudConfig", "getInstance");
            return Mod.call(again, "getPosition", "room_name", 7, 8) != null && Mod.staticCall("hud.HudElementRegistry", "byId", ID) == null;
        }), "an old Beacon Ready position broke the HUD config");
        int[] p = c.onClient(mc -> new int[]{mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ()});
        String at = (p[0] + 5) + " " + (p[1] + 1) + " " + p[2];
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(p[0] + 5, p[1] + 1, p[2]);
        c.server().command("setblock " + at + " minecraft:beacon");
        try {
            set(c, true);
            long t = System.currentTimeMillis();
            c.waitUntil("the mod finding the beacon block", mc -> {
                feed("7m", t);
                return pos.equals(Mod.staticCall(F, "beaconPos"));
            }, 200);
            // The minute drops from 7m to 6m one second before base: zero is 359 s after base's 1 s, so the text starts at
            // 5m 59s or 5m 58s and then falls with no new read (each poll feeds the same moment, as one tab read would).
            long base = System.currentTimeMillis() - 1_000;
            c.waitUntil("the countdown drawn over the beacon", mc -> {
                feed("7m", base - 69_000);
                feed("6m", base);
                String d = (String) Mod.staticCall(F, "lastDrawn");
                return d != null && d.startsWith("Beacon: 5m ");
            }, 200);
            String first = (String) c.onClient(mc -> Mod.staticCall(F, "lastDrawn"));
            c.waitUntil("the drawn countdown falling a second with no new tab read", mc -> {
                feed("7m", base - 69_000);
                feed("6m", base);
                String d = (String) Mod.staticCall(F, "lastDrawn");
                return d != null && !d.equals(first) && d.startsWith("Beacon: 5m ");
            }, 200);
            c.waitUntil("a real update correcting the countdown", mc -> {
                long now = System.currentTimeMillis();
                feed("4m 12s", now);
                String d = (String) Mod.staticCall(F, "lastDrawn");
                return d != null && (d.equals("Beacon: 4m 12s") || d.equals("Beacon: 4m 11s"));
            }, 100);
            c.server().command("setblock " + at + " minecraft:air");
            c.waitUntil("nothing drawn once the beacon block is gone", mc -> {
                feed("4m 12s", System.currentTimeMillis());
                return Mod.staticCall(F, "beaconPos") == null && Mod.staticCall(F, "lastDrawn") == null;
            }, 200);
            c.note("found the beacon, drew " + first + ", counted down, corrected, cleared");
        } finally {
            c.server().command("setblock " + at + " minecraft:air");
            set(c, false);
            c.onClient(mc -> {
                Mod.call(Mod.staticCall("hud.HudConfig", "getInstance"), "clearPosition", ID);
                return null;
            });
        }
    }
}
