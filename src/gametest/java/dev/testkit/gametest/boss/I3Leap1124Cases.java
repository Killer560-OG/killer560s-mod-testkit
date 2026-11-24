package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;

import java.util.Locale;

import static dev.testkit.gametest.boss.CrystalLeap1112Cases.FF;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.FLC;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.LM;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.MATE;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.cheat;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.cooldown;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.count;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.firstTick;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.self;
import static dev.testkit.gametest.boss.CrystalLeap1112Cases.ticks;

/**
 * I3 Leap and the P3 label renames (leap-p3-labels-i3-1124, killer560 2026-11-24). In section 3's area while section 1 is ACTIVE (Goldor's
 * line seen, section 1 not finished), a Fast Leap press goes to the I3 Leap target, not to S3 to S4's; once section 1 is
 * finished, before P3 starts, or outside section 3, the normal row applies. With P3 on Auto, his OWN "completed a
 * device!" line in section 3 while section 1 is active leaps to I3 Leap's target by itself, once; another player's line,
 * a finished section 1, a P3 that has not started, or Fast mode do not. Each case sets the I3 pick to the one head in the menu and the row that would otherwise apply to a name that is
 * not there, so a leap that reaches the menu click proves which target was chosen. The same-tick cases put his own
 * use / attack / hotbar swap / container click into the tick the device line starts the leap and assert both on the wire
 * (the session's GrimAC check judges the rest; run on default and experimental Grim).
 */
final class I3Leap1124Cases {

    private static final String TARGET = FLC + "$LeapTarget";
    private static final String NOBODY = "NoSuchLeapMate";
    private static final String TRACKER = "leveraura.P3SectionTracker";
    /** P3 state for a case: Goldor's line seen and section 1 active; section 1 finished; P3 not started. */
    private static final String ACTIVE = "active", DONE = "done", BEFORE = "before";
    private static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final int X = 10;
    private static final int Y = 120;
    private static final int Z = 100;

    private I3Leap1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-i3leap-fast-early", c -> fast(c, X, Z, ACTIVE, true));
        s.test("1124-i3leap-fast-s1-done", c -> fast(c, X, Z, DONE, false));
        s.test("1124-i3leap-fast-before-p3", c -> fast(c, X, Z, BEFORE, false));
        s.test("1124-i3leap-fast-outside-s3", c -> fast(c, 60, 130, ACTIVE, false));
        s.test("1124-i3leap-auto-own-device", c -> auto(c, "AUTO", true, ACTIVE, true));
        s.test("1124-i3leap-auto-s1-done", c -> auto(c, "AUTO", true, DONE, false));
        s.test("1124-i3leap-auto-before-p3", c -> auto(c, "AUTO", true, BEFORE, false));
        s.test("1124-i3leap-auto-other-player", c -> auto(c, "AUTO", false, ACTIVE, false));
        s.test("1124-i3leap-fast-mode-no-auto", c -> auto(c, "FAST", true, ACTIVE, false));
        // leap-p3-rowswitch-1124: each P3 row has its own switch and its own Fast/Auto
        double[][] at = {{100, 60}, {50, 130}, {10, 100}, {40, 40}};
        for (int i = 0; i < 4; i++) {
            String sec = "S" + (i + 1);
            double x = at[i][0], z = at[i][1];
            s.test("1124-rowsw-fast-" + sec.toLowerCase(Locale.ROOT) + "-alone", c -> rowFast(c, sec + ":FAST", x, z, DONE, sec, true));
            s.test("1124-rowsw-fast-" + sec.toLowerCase(Locale.ROOT) + "-off", c -> rowFast(c,
                    all("FAST").replace(sec + ":FAST", "").replace(",,", ",").replaceAll("^,|,$", ""), x, z, DONE, sec, false));
        }
        s.test("1124-rowsw-fast-all-off", c -> rowFast(c, "", X, Z, ACTIVE, "I3", false));
        s.test("1124-rowsw-fast-i3-alone", c -> rowFast(c, "I3:FAST", X, Z, ACTIVE, "I3", true));
        s.test("1124-rowsw-fast-i3-off-s3-on", c -> rowFast(c, "S3:FAST", X, Z, ACTIVE, "I3", false));
        s.test("1124-rowsw-fast-i3-on-no-pick-s3-off", c -> rowFast(c, "I3:FAST", X, Z, ACTIVE, "S3-noi3", true));
        s.test("1124-rowsw-fast-i3-auto-s3-fast", c -> rowFast(c, "I3:AUTO,S3:FAST", X, Z, ACTIVE, "I3", false));
        s.test("1124-rowsw-auto-i3-auto-s3-fast", c -> rowAuto(c, "I3:AUTO,S3:FAST", true));
        s.test("1124-rowsw-auto-i3-fast-s3-auto", c -> rowAuto(c, "I3:FAST,S3:AUTO", false));
        s.test("1124-rowsw-auto-all-off", c -> rowAuto(c, "", false));
        s.test("1124-rowsw-migrate-old-p3", I3Leap1124Cases::migrate);
        for (String act : new String[] {"use-key", "attack", "swap", "click"}) {
            s.test("1124-same-i3leap-" + act, c -> sameTick(c, act));
        }
    }

    private static Object target(String name) {
        return Mod.enumValue(TARGET, name);
    }

    private static void pick(Object cfg, String t, String name) {
        Mod.call(cfg, "setTargetPickName", target(t), name);
    }

    /** F7 boss in section 3's area (x/z), the leap in slot 0 with a menu offering MATE; I3 -> MATE, S3 and S2 -> nobody. */
    private static AutoCloseable setUp(Session c, String mode, double x, double z, String p3, boolean foe) {
        return setUp(c, all(mode), x, z, p3, foe, "I3");
    }

    /** Every P3 row ON in {@code mode} (FAST / AUTO), or every row OFF for "OFF". */
    private static String all(String mode) {
        return mode.equals("OFF") ? "" : "I3:" + mode + ",S1:" + mode + ",S2:" + mode + ",S3:" + mode + ",S4:" + mode;
    }

    /** {@code rows}: the rows that are ON, as "ROW:MODE" pairs (rest OFF). {@code mate}: the row whose pick is MATE ("S3-noi3": S3's, with I3 unset). */
    private static AutoCloseable setUp(Session c, String rows, double x, double z, String p3, boolean foe, String mate) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        DungeonFixBossCases.floor(c, (int) x, Y, (int) z);
        TerminalAuraCases.tp(c, x + 0.5, Y, z + 0.5, 270f);
        c.waitUntil("Floor7Tracker.inF7Boss", mc -> Boolean.TRUE.equals(Mod.staticCall("fastleap.Floor7Tracker", "inF7Boss")), 100);
        c.hx().give(0, Fixtures.byId("items.spirit-leap").payload().getAsJsonObject().get("stack").getAsString());
        JsonObject spec = Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
        spec.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + MATE);
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", spec);
        if (foe) {
            c.server().command(String.format(Locale.ROOT, "testkit spawn SicoKalebFoe %.1f %d %.1f", x + 1.5, Y, z + 0.5));
            c.server().sync(20);
        }
        AutoCloseable on = c.onClient(mc -> Mod.with(FLC, "Enabled", true));
        Object cfg = c.onClient(mc -> Mod.cfg(FLC));
        c.onClient(mc -> {
            Mod.call(cfg, "setUseClassTargets", false);
            for (String t : new String[] {"I3", "S1", "S2", "S3", "S4"}) {
                Mod.call(cfg, "setTargetMode", target(t), Mod.enumValue(FLC + "$TargetMode", "PER_LEAP"));
                pick(cfg, t, t.equals(mate.substring(0, 2)) ? MATE : mate.endsWith("-noi3") && t.equals("I3") ? "" : NOBODY);
            }
            setRows(cfg, rows);
            Mod.staticCall(TRACKER, "resetRun");
            if (!p3.equals(BEFORE)) {
                Mod.staticCall(TRACKER, "onChat", GOLDOR);
            }
            if (p3.equals(DONE)) {
                Mod.staticCall(TRACKER, "setSection", 2);
            }
            mc.player.getInventory().setSelectedSlot(0);
            Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
            return null;
        });
        c.waitUntil("the leap in slot 0", mc -> "SPIRIT_LEAP".equals(
                Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 60);
        c.ctx().waitTicks(10);
        return on;
    }

    private static void setRows(Object cfg, String rows) {
        for (String t : new String[] {"I3", "S1", "S2", "S3", "S4"}) {
            String mode = null;
            for (String r : rows.split(",")) {
                if (r.startsWith(t + ":")) {
                    mode = r.substring(3);
                }
            }
            Mod.call(cfg, "setRowOn", target(t), mode != null);
            Mod.call(cfg, "setRowMode", target(t), Mod.enumValue(FLC + "$LeapMode", mode == null ? "FAST" : mode));
        }
    }

    private static void tearDown(Session c, AutoCloseable on) throws Exception {
        c.ctx().runOnClient(mc -> {
            setRows(Mod.cfg(FLC), "");
            Mod.staticCall(TRACKER, "resetRun");
        });
        CrystalLeap1112Cases.tearDown(c, on);
    }

    private static String device(String name) {
        return name + " completed a device! (1/7)";
    }

    private static void fast(Session c, double x, double z, String p3, boolean expectLeap) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "FAST", x, z, p3, false);
        try {
            WireCapture.start();
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ctx().waitTicks(expectLeap ? 40 : 60);
            WireCapture.stop();
            c.note("wire: " + ticks());
            c.check(count("UseItem") >= 1, "the Fast Leap press never used the leap (the feature did not act): " + ticks());
            c.check(count("ContainerClick") == (expectLeap ? 1 : 0), "menu clicks " + count("ContainerClick")
                    + (expectLeap ? ", want 1: I3 Leap's target was not used" : ", want 0: the I3 target was used where it must not be"));
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    /** A Fast Leap press in {@code sec}'s area with {@code rows} ON; the pick MATE is on {@code mate} (the row that must be used). */
    private static void rowFast(Session c, String rows, double x, double z, String p3, String mate, boolean expectLeap) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, rows, x, z, p3, false, mate);
        try {
            WireCapture.start();
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ctx().waitTicks(expectLeap ? 40 : 60);
            WireCapture.stop();
            c.note("rows [" + rows + "] wire: " + ticks());
            if (expectLeap) {
                c.check(count("UseItem") >= 1 && count("ContainerClick") == 1,
                        "rows [" + rows + "]: " + count("UseItem") + " uses, " + count("ContainerClick") + " menu clicks, want a leap (1 click): " + ticks());
            } else {
                c.check(count("UseItem") == 0 && count("ContainerClick") == 0,
                        "rows [" + rows + "]: a row that is OFF (or Auto) leaped on a Fast press: " + ticks());
            }
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    /** His own device line in section 3 while section 1 is active, with {@code rows} ON. */
    private static void rowAuto(Session c, String rows, boolean expectLeap) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, rows, X, Z, ACTIVE, false, "I3");
        try {
            String me = self(c);
            WireCapture.start();
            c.ctx().runOnClient(mc -> Mod.staticCall(TRACKER, "onChat", device(me)));
            c.ctx().waitTicks(expectLeap ? 40 : 60);
            WireCapture.stop();
            c.note("rows [" + rows + "] wire: " + ticks());
            if (expectLeap) {
                c.check(count("UseItem") == 1 && count("ContainerClick") == 1,
                        "rows [" + rows + "]: " + count("UseItem") + " uses, " + count("ContainerClick") + " clicks, want 1 and 1");
            } else {
                c.check(count("UseItem") == 0 && count("ContainerClick") == 0,
                        "rows [" + rows + "]: a row that is OFF (or Fast) leaped on the device line: " + ticks());
            }
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    /** An old config's one P3 value (Off / Fast / Auto) becomes every row OFF / ON Fast / ON Auto; a row's own keys override and survive a save and reload. */
    private static void migrate(Session c) throws Exception {
        java.nio.file.Path file = (java.nio.file.Path) c.onClient(mc -> Mod.field(FLC, "CONFIG_PATH"));
        String[][] old = {{"{\"fast\":false,\"auto\":false}", "OFF"}, {"{\"fast\":true,\"auto\":false}", "FAST"},
                {"{\"fast\":false,\"auto\":true}", "AUTO"}, {"{\"fast\":true,\"auto\":true}", "AUTO"}};
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String[] k : old) {
            java.nio.file.Files.writeString(file, "{\"enabled\":true,\"groups\":{\"P3\":" + k[0] + "}}");
            String got = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.cfg(FLC);
                Mod.call(cfg, "save");
                Mod.staticCall(FLC, "load");
                Object cfg2 = Mod.cfg(FLC);
                StringBuilder sb = new StringBuilder();
                for (String t : new String[] {"I3", "S1", "S2", "S3", "S4"}) {
                    sb.append(Mod.call(cfg2, "isRowOn", target(t))).append('/').append(Mod.call(cfg2, "getRowMode", target(t))).append(' ');
                }
                return sb.toString().trim();
            });
            String m = k[1].equals("AUTO") ? "AUTO" : "FAST";
            String want = k[1].equals("OFF") ? "false/FAST" : "true/" + m;
            String all = (want + " ").repeat(5).trim();
            c.note("old P3 " + k[0] + " -> " + got);
            if (!got.equals(all)) {
                bad.add(k[0] + " -> " + got + ", want " + all);
            }
        }
        // new keys: one row on in Auto, the rest off, kept over a save and reload
        java.nio.file.Files.writeString(file, "{\"enabled\":true,\"groups\":{\"P3\":{\"fast\":false,\"auto\":true}},"
                + "\"targets\":{\"S2\":{\"leapOn\":true,\"leapMode\":\"AUTO\"},\"S1\":{\"leapOn\":false,\"leapMode\":\"AUTO\"}}}");
        String got = c.onClient(mc -> {
            Mod.staticCall(FLC, "load");
            Mod.call(Mod.cfg(FLC), "setRowOn", target("S4"), false);
            Mod.call(Mod.cfg(FLC), "save");
            Mod.staticCall(FLC, "load");
            Object cfg = Mod.cfg(FLC);
            StringBuilder sb = new StringBuilder();
            for (String t : new String[] {"I3", "S1", "S2", "S3", "S4"}) {
                sb.append(Mod.call(cfg, "isRowOn", target(t))).append('/').append(Mod.call(cfg, "getRowMode", target(t))).append(' ');
            }
            return sb.toString().trim();
        });
        c.note("row keys -> " + got);
        if (!got.equals("true/AUTO false/AUTO true/AUTO true/AUTO false/AUTO")) {
            bad.add("row keys -> " + got);
        }
        java.nio.file.Files.writeString(file, "{\"enabled\":false}");
        c.onClient(mc -> Mod.staticCall(FLC, "load"));
        c.check(bad.isEmpty(), "P3 row migration / persistence wrong: " + bad);
    }

    private static void auto(Session c, String mode, boolean own, String p3, boolean expectLeap) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, mode, X, Z, p3, false);
        try {
            String who = own ? self(c) : MATE;
            WireCapture.start();
            c.ctx().runOnClient(mc -> Mod.staticCall(TRACKER, "onChat", device(who)));
            c.ctx().waitTicks(expectLeap ? 40 : 60);
            WireCapture.stop();
            c.note("wire: " + ticks());
            if (expectLeap) {
                c.check(count("UseItem") == 1 && count("ContainerClick") == 1,
                        "own device line: " + count("UseItem") + " uses, " + count("ContainerClick") + " clicks, want 1 and 1");
            } else if (!p3.equals(ACTIVE) && own && mode.equals("AUTO")) {
                // the S3 to S4 row (nobody) applies: the leap may open the menu but never clicks a head
                c.check(count("ContainerClick") == 0, "S1 finished or P3 not started: the I3 target was used: " + ticks());
            } else {
                c.check(count("UseItem") == 0 && count("ContainerClick") == 0, "no leap expected, wire " + ticks());
            }
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    /** His own action lands in the very tick the device line starts the leap; both must be on the wire in one tick. */
    private static void sameTick(Session c, String act) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "AUTO", X, Z, ACTIVE, act.equals("attack"));
        try {
            String me = self(c);
            if (act.equals("attack")) {
                c.onClient(mc -> {
                    mc.player.setXRot(22f);
                    return null;
                });
                c.ctx().waitTicks(4);
            }
            if (act.equals("click")) {
                JsonObject shown = Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
                shown.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + MATE);
                c.hx().call("menu.show", shown);
                c.waitUntil("a menu", mc -> mc.player.containerMenu != mc.player.inventoryMenu, 60);
                c.ctx().waitTicks(5);
            }
            WireCapture.start();
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(TRACKER, "onChat", device(me));
                switch (act) {
                    case "use-key" -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyUse, "key"));
                    case "attack" -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyAttack, "key"));
                    case "swap" -> mc.player.getInventory().setSelectedSlot(1);
                    default -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 20, 0,
                            ContainerInput.PICKUP, mc.player);
                }
            });
            c.waitUntil("the leap finished (cooldown starts)", mc -> cooldown(c) > 0, 80);
            c.ctx().waitTicks(8);
            WireCapture.stop();
            c.note(act + " wire by client tick: " + ticks());
            int leapTick = act.equals("click") ? firstTick("ContainerClick") : firstTick("UseItem");
            c.check(leapTick >= 0, "no leap packet went out");
            int clicks = count("ContainerClick");
            switch (act) {
                case "use-key" -> c.check(count("UseItem") >= 1 && clicks == 1, "use-key: " + count("UseItem") + " uses, " + clicks + " menu clicks");
                case "attack" -> c.check(firstTick("AttackPacket") == leapTick || firstTick("InteractPacket") == leapTick,
                        "his attack was not in the leap's tick " + leapTick + ": " + ticks());
                case "swap" -> c.check(firstTick("SetCarriedItem") >= 0, "his hotbar swap never went out: " + ticks());
                default -> c.check(clicks >= 2, "his container click and the leap's click: " + clicks + " clicks, want 2");
            }
            c.check(clicks >= 1 && cooldown(c) > 0, "the leap did not complete");
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }
}
