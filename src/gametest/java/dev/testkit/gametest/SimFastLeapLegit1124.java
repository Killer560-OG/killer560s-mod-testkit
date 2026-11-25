package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimLeap1116.chatSince;

/**
 * {@code 1124-sim-fastleap-legit} (legit-noleap-1124): Fast Leap and Auto Leap are cheat-build only, so a LEGIT jar never
 * leaps. Same sim setup as 1120-sim-fastleap (in S2's box, the leap item held, a Healer in the tab), every leap setting
 * forced ON, then every way to leap is tried: the feature's P3 leap, a Fast Mode left click, and LeapManager.leap itself
 * for a name and a class. None may send a use or click packet, count a leap or print a teleport line. SKIP on cheat. Not
 * vacuous: the row settings really read back ON, the leap is held, the Healer is found and WireCapture saw the wire.
 */
final class SimFastLeapLegit1124 {

    static final String NAME = "1124-sim-fastleap-legit";
    private static final String CFG = "com.killer560.hub.fastleap.FastLeapConfig";
    private static final String FEATURE = "com.killer560.hub.fastleap.FastLeapFeature";
    private static final String MGR = "com.killer560.hub.fastleap.LeapManager";
    private static final String TARGET = CFG + "$LeapTarget";
    private static final String MODE = CFG + "$LeapMode";
    private static final String STAGE = "com.killer560.hub.fastleap.Floor7Tracker$Stage";
    private static final String CLASS = "com.killer560.hub.dungeonclass.DungeonClass";
    private static final String LEAP = "com.killer560.hub.roomsim.SimLeap";
    private static final String[] ROWS = {"I3", "S1", "S2", "S3", "S4"};

    private SimFastLeapLegit1124() {
    }

    private static Class<?> classOf(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static int clicks() {
        int n = 0;
        for (dev.testkit.harness.WireCapture.Sent s : dev.testkit.harness.WireCapture.snapshot()) {
            if (s.className().contains("UseItem") || s.className().contains("ContainerClick")) {
                n++;
            }
        }
        return n;
    }

    private static void rowOn(Object cfg, boolean on) {
        for (String t : ROWS) {
            ModUnderTest.call(cfg, "setRowOn", new Class<?>[]{classOf(TARGET), boolean.class}, new Object[]{ModUnderTest.enumValue(TARGET, t), on});
        }
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        if (dev.testkit.gametest.mod.Mod.isCheat()) {
            Scenario.skipped(NAME, "legit build only");
            return;
        }
        Object cfg = ModUnderTest.config(CFG);
        SimLeapSpots1117.pick(ctx, "MAGE");
        SimLeap1116.setTeammates(ctx, 0);
        start(ctx, "S3", true);
        try {
            ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{true});
            ModUnderTest.call(cfg, "setUseClassTargets", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.call(cfg, "setFastMode", new Class<?>[]{boolean.class}, new Object[]{true});
            rowOn(cfg, true);
            for (String t : ROWS) {
                ModUnderTest.call(cfg, "setRowMode", new Class<?>[]{classOf(TARGET), classOf(MODE)}, new Object[]{ModUnderTest.enumValue(TARGET, t), ModUnderTest.enumValue(MODE, "AUTO")});
                ModUnderTest.call(cfg, "setTargetMode", new Class<?>[]{classOf(TARGET), classOf(CFG + "$TargetMode")},
                        new Object[]{ModUnderTest.enumValue(TARGET, t), ModUnderTest.enumValue(CFG + "$TargetMode", "PER_LEAP")});
                ModUnderTest.call(cfg, "setTargetPickClass", new Class<?>[]{classOf(TARGET), classOf(CLASS)},
                        new Object[]{ModUnderTest.enumValue(TARGET, t), ModUnderTest.enumValue(CLASS, "HEALER")});
            }
            SimLeap1116.give(ctx, "INFINITE_SPIRIT_LEAP", 1);
            SimBossTests.tp(ctx, 60, 115, 130, 0f, 0f);
            ctx.waitTicks(60);

            // The setup reached the leap condition: rows ON, leap held, a Healer to leap to, in the dungeon.
            expect(f, "the S2 row reads ON", ModUnderTest.call(cfg, "isRowOn", new Class<?>[]{classOf(TARGET)}, new Object[]{ModUnderTest.enumValue(TARGET, "S2")}), true);
            expect(f, "the leap is held", ctx.computeOnClient(mc -> ModUnderTest.staticCall(FEATURE, "isHoldingLeap", new Class<?>[]{classOf("net.minecraft.client.player.LocalPlayer")}, new Object[]{mc.player})), true);
            expect(f, "a Healer is in the tab", ctx.computeOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.fastleap.Teammates", "byName", new Class<?>[]{String.class}, new Object[]{"Healer"}) != null), true);
            expect(f, "in the dungeon", ctx.computeOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "isInDungeon")), true);

            ctx.runOnClient(mc -> dev.testkit.harness.WireCapture.start());
            long mark = LogTap.mark();
            int l0 = (Integer) ctx.computeOnClient(mc -> call(LEAP, "leapsForTest"));
            int c0 = clicks();
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(FEATURE, "handleP3Leap", new Class<?>[]{classOf(STAGE), boolean.class, classOf(MODE)},
                        new Object[]{ModUnderTest.enumValue(STAGE, "S3"), false, ModUnderTest.enumValue(MODE, "AUTO")});
                ModUnderTest.staticCall(FEATURE, "handleP3Leap", new Class<?>[]{classOf(STAGE), boolean.class, classOf(MODE)},
                        new Object[]{ModUnderTest.enumValue(STAGE, "S3"), true, ModUnderTest.enumValue(MODE, "FAST")});
                ModUnderTest.staticCall(FEATURE, "onLeftClick");
                ModUnderTest.staticCall(MGR, "leap", new Class<?>[]{String.class, boolean.class, boolean.class, boolean.class}, new Object[]{"Healer", false, true, true});
                ModUnderTest.staticCall(MGR, "leap", new Class<?>[]{classOf(CLASS), boolean.class, boolean.class, boolean.class}, new Object[]{ModUnderTest.enumValue(CLASS, "HEALER"), false, true, true});
            });
            ctx.waitTicks(80);
            expect(f, "no leap in flight", ctx.computeOnClient(mc -> ModUnderTest.staticCall(MGR, "isBusy")), false);
            expect(f, "no leap counted", (Integer) ctx.computeOnClient(mc -> call(LEAP, "leapsForTest")) - l0, 0);
            expect(f, "no teleport line", chatSince(mark, "You have teleported to"), false);
            expect(f, "no use or container click on the wire", clicks() - c0, 0);
            expect(f, "the wire was captured (non-vacuous)", !dev.testkit.harness.WireCapture.snapshot().isEmpty(), true);
            println(NAME, "done");
        } finally {
            ctx.runOnClient(mc -> {
                dev.testkit.harness.WireCapture.stop();
                mc.player.closeContainer();
            });
            rowOn(cfg, false);
            ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
        }
    }
}
