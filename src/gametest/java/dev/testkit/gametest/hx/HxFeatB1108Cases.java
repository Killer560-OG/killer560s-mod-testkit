package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * featB-1108 dungeon alerts (mod branch featB-1108): Rare Room, Rare Drop, Duplicate Class, Wither King Statue Box and
 * Dimensional Slash. Every case feeds the real signal (the map's room identity, a name-tagged stand, the tab list, block
 * packets from the server), asserts the alert fired and the title is on screen, and that a near miss and an OFF switch stay
 * silent.
 */
final class HxFeatB1108Cases {

    static final String CFG = "dungeonalerts.DungeonAlertsConfig";
    static final String FEATURE = "dungeonalerts.DungeonAlertsFeature";
    static final String LIVEMAP = "livemap.LiveMapFeature";
    static final String KING = "dungeonalerts.WitherKingAlerts";

    private HxFeatB1108Cases() {
    }

    static void register(Session s) {
        s.test("1108-alerts-rareroom", HxFeatB1108Cases::rareRoom);
        s.test("1108-alerts-raredrop", HxFeatB1108Cases::rareDrop);
        s.test("1108-alerts-dupclass", HxFeatB1108Cases::dupClass);
        s.test("1108-alerts-statuebox", HxFeatB1108Cases::statueBox);
        s.test("1108-alerts-slash", HxFeatB1108Cases::slash);
        s.test("1108-alerts-persist", HxFeatB1108Cases::persist);
    }

    private static int count(Session c, String key) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", key));
    }

    private static String last(Session c, String key) {
        return c.onClient(mc -> (String) Mod.staticCall(FEATURE, "lastAlert", key));
    }

    private static void reset(Session c, String cls) {
        run(c, () -> Mod.staticCall("dungeonalerts." + cls, "onWorldChange"));
    }

    private static void loadRoom(Session c, String name) {
        run(c, () -> Mod.staticCall(LIVEMAP, "testLoadFloor", new String[]{"a"}, new String[]{name + "|1x1|NORMAL"}));
    }

    // ---- Rare Room Alerts -------------------------------------------------------------------------------------------

    private static void rareRoom(Session c) {
        try (Settings set = new Settings(c).field(CFG, "rareRoomAlert", false)) {
            dungeon(c);
            reset(c, "RareRoomAlert");
            int base = count(c, "rareroom");
            // The switch is OFF: a Trinity on the map says nothing.
            loadRoom(c, "Trinity");
            c.ctx().waitTicks(45);
            c.check(count(c, "rareroom") == base, "Rare Room Alerts fired with its switch OFF");
            set.field(CFG, "rareRoomAlert", true);
            c.waitUntil("the Trinity alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "rareroom") == base + 1, 80);
            String title = last(c, "rareroom");
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(title != null && title.contains("Trinity"), "alert was '" + title + "'");
            c.check(shown != null && net.minecraft.ChatFormatting.stripFormatting(shown).contains("Trinity found"), "on-screen title '" + shown + "'");
            c.ctx().waitTicks(45);
            c.check(count(c, "rareroom") == base + 1, "Trinity alerted twice");
            // A near miss: an ordinary room (and a name that only contains a rare one) stays silent.
            loadRoom(c, "Mage Hall");
            c.ctx().waitTicks(45);
            loadRoom(c, "Duncan's Nephew");
            c.ctx().waitTicks(45);
            c.check(count(c, "rareroom") == base + 1, "a non-rare room alerted (" + count(c, "rareroom") + ")");
            loadRoom(c, "Duncan");
            c.waitUntil("the Duncan alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "rareroom") == base + 2, 80);
            c.check(last(c, "rareroom").contains("Duncan"), "second alert was '" + last(c, "rareroom") + "'");
            c.note("OFF: none; Trinity -> '" + title + "' (Gui '" + shown + "') once; Mage Hall, Duncan's Nephew: none; Duncan -> alert");
        } finally {
            run(c, () -> Mod.staticCall(LIVEMAP, "testLoadFloor", new String[]{"a"}, new String[]{"Mage Hall|1x1|NORMAL"}));
        }
    }

    // ---- Rare Drop Alerts -------------------------------------------------------------------------------------------

    private static void rareDrop(Session c) {
        try (Settings set = new Settings(c).field(CFG, "rareDropAlert", true)) {
            dungeon(c);
            c.hx().removeStands();
            reset(c, "RareDropAlert");
            int base = count(c, "raredrop");
            c.hx().stand(-416.5, 151, -416.5, "§7Bob's Ice Spray Wand");
            c.hx().stand(-417.5, 151, -416.5, "Skeleton Master Boots");
            c.ctx().waitTicks(30);
            c.check(count(c, "raredrop") == base, "a near-miss name alerted");
            c.hx().stand(-418.5, 151, -416.5, "§5Ice Spray Wand");
            c.waitUntil("the Ice Spray Wand alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "raredrop") == base + 1, 60);
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(last(c, "raredrop").contains("Ice Spray Wand"), "alert was '" + last(c, "raredrop") + "'");
            c.check(shown != null && net.minecraft.ChatFormatting.stripFormatting(shown).contains("Ice Spray Wand dropped"), "on-screen title '" + shown + "'");
            c.ctx().waitTicks(30);
            c.check(count(c, "raredrop") == base + 1, "the same stand alerted again");
            c.hx().stand(-419.5, 151, -416.5, "Skeleton Master Chestplate");
            c.waitUntil("the chestplate alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "raredrop") == base + 2, 60);
            c.check(last(c, "raredrop").contains("Skeleton Master Chestplate"), "second alert was '" + last(c, "raredrop") + "'");
            // OFF: another wand says nothing.
            set.field(CFG, "rareDropAlert", false);
            c.hx().stand(-420.5, 151, -416.5, "Ice Spray Wand");
            c.ctx().waitTicks(30);
            c.check(count(c, "raredrop") == base + 2, "alerted with the switch OFF");
            c.note("near misses none; Ice Spray Wand and Skeleton Master Chestplate once each; OFF silent");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- Duplicate Class Alert --------------------------------------------------------------------------------------

    private static void dupClass(Session c) {
        try (Settings set = new Settings(c).field(CFG, "duplicateClassAlert", true).field(CFG, "duplicateClassParty", false)) {
            dungeon(c);
            reset(c, "DuplicateClassAlert");
            c.ctx().waitTicks(30);
            int base = count(c, "dupclass");
            // HxMateA Mage, HxMateB Berserk, self Archer: no class twice, so nothing.
            c.ctx().waitTicks(30);
            c.check(count(c, "dupclass") == base, "an all-different party alerted");
            String me = self(c);
            tab(c, "template", "dungeon", "players", List.of(player("HxMateA", "Mage", "[MVP+]"), player("HxMateB", "Mage", null),
                    player(me, "Archer", null)));
            c.waitUntil("the duplicate Mage alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "dupclass") == base + 1, 80);
            String title = last(c, "dupclass");
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(title.contains("Mage"), "alert was '" + title + "'");
            c.check(shown != null && net.minecraft.ChatFormatting.stripFormatting(shown).contains("Duplicate Mage"), "on-screen title '" + shown + "'");
            c.ctx().waitTicks(30);
            c.check(count(c, "dupclass") == base + 1, "the same duplicate alerted twice");
            c.check(c.commands().stream().noneMatch(s -> s.startsWith("pc Duplicate class")), "the party line went with its switch OFF");
            // The party line, on: a second run of the same tab.
            set.field(CFG, "duplicateClassParty", true);
            reset(c, "DuplicateClassAlert");
            c.waitUntil("the second alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "dupclass") == base + 2, 80);
            c.ctx().waitTicks(30);
            c.check(c.commands().stream().anyMatch(s -> s.startsWith("pc Duplicate class - Mage: HxMateA, HxMateB")),
                    "no 'pc Duplicate class' line went out; sent " + c.commands());
            // OFF: nothing.
            set.field(CFG, "duplicateClassAlert", false);
            reset(c, "DuplicateClassAlert");
            c.ctx().waitTicks(40);
            c.check(count(c, "dupclass") == base + 2, "alerted with the switch OFF");
            c.note("distinct classes none; Mage twice -> '" + title + "' (Gui '" + shown + "'); party line only when on; OFF silent");
        } finally {
            dungeon(c);
        }
    }

    // ---- Wither King (M7 phase 5) -----------------------------------------------------------------------------------

    private static final int[][] STATUES = {{32, 22, 59}, {80, 23, 56}, {32, 23, 94}, {79, 23, 94}, {56, 22, 120}};

    private static void inP5(Session c) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(M7)");
        c.waitUntil("DungeonState M7", mc -> "M7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
        c.server().command("forceload add 24 40 100 130");
        c.server().sync(20);
        c.server().command("fill 40 29 70 70 29 90 stone");
        c.server().command("fill 40 30 70 70 40 90 air");
        c.hx().call("dungeon.tp", "x", 56.5, "y", 30.0, "z", 80.5, "yaw", 0f, "pitch", 0f);
        c.waitUntil("in P5", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 30.0) < 0.01
                && (Boolean) Mod.staticCall("witherdragons.P5State", "inP5"), 200);
    }

    private static void statueBox(Session c) {
        try (Settings set = new Settings(c).field(CFG, "witherKingStatueBox", false)) {
            inP5(c);
            for (int[] s : STATUES) {
                c.server().command("setblock " + s[0] + " " + s[1] + " " + s[2] + " minecraft:stone");
            }
            reset(c, "WitherKingAlerts");
            c.ctx().waitTicks(30);
            int off = c.onClient(mc -> (Integer) Mod.staticCall(KING, "drawnFrames"));
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(KING, "drawnFrames")) == off, "boxes drawn with the switch OFF");
            set.field(CFG, "witherKingStatueBox", true);
            c.waitUntil("the box gate opens", mc -> (Boolean) Mod.staticCall(KING, "boxActive"), 60);
            c.ctx().waitTicks(20);
            int f1 = c.onClient(mc -> (Integer) Mod.staticCall(KING, "drawnFrames"));
            c.ctx().waitTicks(20);
            int f2 = c.onClient(mc -> (Integer) Mod.staticCall(KING, "drawnFrames"));
            c.check(f2 > f1, "no statue box was drawn (" + f1 + " -> " + f2 + " frames)");
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(KING, "boxesDrawable")) == 5, "boxes before a statue fell: "
                    + c.onClient(mc -> (Integer) Mod.staticCall(KING, "boxesDrawable")));
            // A statue's block goes to air: its box stops.
            c.server().command("setblock 32 22 59 minecraft:air");
            c.waitUntil("one box fewer", mc -> (Integer) Mod.staticCall(KING, "boxesDrawable") == 4, 60);
            // Outside phase 5 (up at his normal height) the boxes are not drawn at all.
            c.hx().call("dungeon.tp", "x", 56.5, "y", 151.0, "z", 80.5, "yaw", 0f, "pitch", 0f);
            c.waitUntil("the gate closes above P5", mc -> !(Boolean) Mod.staticCall(KING, "boxActive"), 80);
            int g1 = c.onClient(mc -> (Integer) Mod.staticCall(KING, "drawnFrames"));
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(KING, "drawnFrames")) == g1, "boxes drawn outside phase 5");
            c.note("OFF no frames; ON " + (f2 - f1) + " frames in 20 ticks, 5 boxes -> 4 after a statue fell; none outside P5");
        } finally {
            for (int[] s : STATUES) {
                c.server().command("setblock " + s[0] + " " + s[1] + " " + s[2] + " minecraft:air");
            }
        }
    }

    private static void slash(Session c) {
        try (Settings set = new Settings(c).field(CFG, "dimensionalSlashAlert", false)) {
            inP5(c);
            reset(c, "WitherKingAlerts");
            int base = count(c, "slash");
            // OFF: glowstone beside him is not even recorded.
            c.server().command("setblock 58 31 80 minecraft:glowstone");
            c.ctx().waitTicks(30);
            c.check(count(c, "slash") == base && (Integer) c.onClient(mc -> Mod.staticCall(KING, "zoneCount")) == 0,
                    "slash zone recorded or alerted with the switch OFF");
            c.server().command("setblock 58 31 80 minecraft:air");
            set.field(CFG, "dimensionalSlashAlert", true);
            c.waitUntil("the P5 gate", mc -> (Boolean) Mod.staticCall(KING, "boxActive"), 60);
            // Glowstone far from him (more than 5 blocks): a zone, but no alert; one over packed ice is no zone.
            c.server().command("setblock 56 30 100 minecraft:glowstone");
            c.server().command("setblock 66 31 80 minecraft:packed_ice");
            c.ctx().waitTicks(5);
            c.server().command("setblock 66 31 80 minecraft:glowstone");
            c.waitUntil("the far zone recorded", mc -> (Integer) Mod.staticCall(KING, "zoneCount") == 1, 60);
            c.ctx().waitTicks(40);
            c.check(count(c, "slash") == base, "a far glowstone alerted");
            c.check((Integer) c.onClient(mc -> Mod.staticCall(KING, "zoneCount")) == 1, "glowstone over packed ice made a zone");
            // Glowstone next to him: Dimensional Slash.
            c.server().command("setblock 58 31 80 minecraft:glowstone");
            c.waitUntil("the Dimensional Slash alert", mc -> (Integer) Mod.staticCall(FEATURE, "alertCount", "slash") > base, 60);
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(shown != null && net.minecraft.ChatFormatting.stripFormatting(shown).contains("Dimensional Slash"), "on-screen title '" + shown + "'");
            // Glowstone leaving clears the zones: no more alerts.
            c.server().command("setblock 58 31 80 minecraft:air");
            c.waitUntil("zones cleared", mc -> (Integer) Mod.staticCall(KING, "zoneCount") == 0, 60);
            int after = count(c, "slash");
            c.ctx().waitTicks(40);
            c.check(count(c, "slash") == after, "alerted after the glowstone went");
            c.note("OFF none; far glowstone none; packed ice none; near -> '" + shown + "'; cleared on removal");
        } finally {
            c.server().command("setblock 58 31 80 minecraft:air");
            c.server().command("setblock 56 30 100 minecraft:air");
            c.server().command("setblock 66 31 80 minecraft:air");
        }
    }

    private static final String[] FIELDS = {"rareRoomAlert", "rareDropAlert", "duplicateClassAlert", "duplicateClassParty",
            "witherKingStatueBox", "dimensionalSlashAlert"};

    /** All six switches survive a save and a load (the profile reload calls the same load). */
    private static void persist(Session c) {
        Settings set = new Settings(c);
        try {
            for (String f : FIELDS) {
                set.field(CFG, f, true);
            }
            run(c, () -> {
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(CFG, "load");
            });
            for (String f : FIELDS) {
                c.check(Boolean.TRUE.equals(c.onClient(mc -> Mod.field(Mod.cfg(CFG), f))), f + " did not survive save and load");
            }
        } finally {
            set.close();
            // load() made a new singleton: put the switches back OFF on it and on disk.
            run(c, () -> {
                for (String f : FIELDS) {
                    setInstanceField(Mod.cfg(CFG), f, false);
                }
                Mod.call(Mod.cfg(CFG), "save");
            });
        }
    }
}
