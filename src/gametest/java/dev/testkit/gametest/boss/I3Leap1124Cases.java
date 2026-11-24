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
 * I3 Leap and the P3 label renames (leap-p3-labels-i3-1124, killer560 2026-11-24). In section 3's area while section 1 is
 * not finished, a Fast Leap press goes to the I3 Leap target, not to S3 to S4's; once section 1 is finished, or outside
 * section 3, the normal row applies. With P3 on Auto, his OWN "completed a device!" line in section 3 (section 1
 * unfinished) leaps to I3 Leap's target by itself, once; another player's line, a finished section 1, or Fast mode do
 * not. Each case sets the I3 pick to the one head in the menu and the row that would otherwise apply to a name that is
 * not there, so a leap that reaches the menu click proves which target was chosen. The same-tick cases put his own
 * use / attack / hotbar swap / container click into the tick the device line starts the leap and assert both on the wire
 * (the session's GrimAC check judges the rest; run on default and experimental Grim).
 */
final class I3Leap1124Cases {

    private static final String TARGET = FLC + "$LeapTarget";
    private static final String NOBODY = "NoSuchLeapMate";
    private static final String TRACKER = "leveraura.P3SectionTracker";
    private static final int X = 10;
    private static final int Y = 120;
    private static final int Z = 100;

    private I3Leap1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-i3leap-fast-early", c -> fast(c, X, Z, false, true));
        s.test("1124-i3leap-fast-s1-done", c -> fast(c, X, Z, true, false));
        s.test("1124-i3leap-fast-outside-s3", c -> fast(c, 60, 130, false, false));
        s.test("1124-i3leap-auto-own-device", c -> auto(c, "AUTO", true, false, true));
        s.test("1124-i3leap-auto-s1-done", c -> auto(c, "AUTO", true, true, false));
        s.test("1124-i3leap-auto-other-player", c -> auto(c, "AUTO", false, false, false));
        s.test("1124-i3leap-fast-mode-no-auto", c -> auto(c, "FAST", true, false, false));
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
    private static AutoCloseable setUp(Session c, String mode, double x, double z, boolean s1Done, boolean foe) {
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
                pick(cfg, t, t.equals("I3") ? MATE : NOBODY);
            }
            Mod.call(cfg, "setLeapMode", Mod.enumValue(FLC + "$LeapGroup", "P3"), Mod.enumValue(FLC + "$LeapMode", mode));
            Mod.staticCall(TRACKER, "resetRun");
            if (s1Done) {
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

    private static void tearDown(Session c, AutoCloseable on) throws Exception {
        c.ctx().runOnClient(mc -> {
            Mod.call(Mod.cfg(FLC), "setLeapMode", Mod.enumValue(FLC + "$LeapGroup", "P3"), Mod.enumValue(FLC + "$LeapMode", "OFF"));
            Mod.staticCall(TRACKER, "resetRun");
        });
        CrystalLeap1112Cases.tearDown(c, on);
    }

    private static String device(String name) {
        return name + " completed a device! (1/7)";
    }

    private static void fast(Session c, double x, double z, boolean s1Done, boolean expectLeap) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "FAST", x, z, s1Done, false);
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

    private static void auto(Session c, String mode, boolean own, boolean s1Done, boolean expectLeap) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, mode, X, Z, s1Done, false);
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
            } else if (s1Done) {
                // the S3 to S4 row (nobody) applies: the leap may open the menu but never clicks a head
                c.check(count("ContainerClick") == 0, "S1 finished: the I3 target was used: " + ticks());
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
        AutoCloseable on = setUp(c, "AUTO", X, Z, false, act.equals("attack"));
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
