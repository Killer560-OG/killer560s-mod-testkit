package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimLeap1116.chatSince;

/**
 * {@code 1120-sim-fastleap} (fastleap-sim-1120, killer560 2026-11-20: "Fast leap in sim isnt working. also have the leaps
 * be off of where i am not what section it activley is"). Alone in the F7 boss sim with NO Sim Teammate placed (the
 * default), the tab listed nobody, so a Fast Leap to a class said "Healer not found" although the Spirit Leap menu offers
 * all four classes; the tab now lists them as Hypixel's would. And a completed P3 section's auto leap goes to the target
 * of the section he STANDS in: S2 = Healer, S3 = Tank here. Stood in S2 while S3 completes: Healer. Stood in S3 while S2
 * completes: Tank. Outside S1-S4 (S5's box): no leap. Each leap is read off the sim's own teleport line and counter.
 */
final class SimFastLeap1120 {

    static final String NAME = "1120-sim-fastleap";
    private static final String CFG = "com.killer560.hub.fastleap.FastLeapConfig";
    private static final String FEATURE = "com.killer560.hub.fastleap.FastLeapFeature";
    private static final String TARGET = CFG + "$LeapTarget";
    private static final String MODE = CFG + "$TargetMode";
    private static final String STAGE = "com.killer560.hub.fastleap.Floor7Tracker$Stage";
    private static final String CLASS = "com.killer560.hub.dungeonclass.DungeonClass";
    private static final String LEAP = "com.killer560.hub.roomsim.SimLeap";

    private SimFastLeap1120() {
    }

    private static int leaps(ClientGameTestContext ctx) {
        return (Integer) ctx.computeOnClient(mc -> call(LEAP, "leapsForTest"));
    }

    /** Completes {@code stage} in the tracker's own listener path: the P3 leap the feature makes for it. */
    private static void completed(ClientGameTestContext ctx, String stage) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "handleP3Leap", new Class<?>[]{classOf(STAGE), boolean.class, classOf(CFG + "$LeapMode")},
                new Object[]{ModUnderTest.enumValue(STAGE, stage), false, ModUnderTest.enumValue(CFG + "$LeapMode", "AUTO")}));
        ctx.waitTicks(30);
    }

    private static Class<?> classOf(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static void pick(Object cfg, String target, String cls) {
        ModUnderTest.call(cfg, "setTargetMode", new Class<?>[]{classOf(TARGET), classOf(MODE)},
                new Object[]{ModUnderTest.enumValue(TARGET, target), ModUnderTest.enumValue(MODE, "PER_LEAP")});
        ModUnderTest.call(cfg, "setTargetPickClass", new Class<?>[]{classOf(TARGET), classOf(CLASS)},
                new Object[]{ModUnderTest.enumValue(TARGET, target), ModUnderTest.enumValue(CLASS, cls)});
    }

    /** Every P3 row ON in Auto, or OFF (each row has its own switch since leap-p3-rowswitch-1124). */
    private static void rows(Object cfg, boolean on) {
        for (String t : new String[]{"I3", "S1", "S2", "S3", "S4"}) {
            ModUnderTest.call(cfg, "setRowOn", new Class<?>[]{classOf(TARGET), boolean.class}, new Object[]{ModUnderTest.enumValue(TARGET, t), on});
            ModUnderTest.call(cfg, "setRowMode", new Class<?>[]{classOf(TARGET), classOf(CFG + "$LeapMode")},
                    new Object[]{ModUnderTest.enumValue(TARGET, t), ModUnderTest.enumValue(CFG + "$LeapMode", "AUTO")});
        }
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        Object cfg = ModUnderTest.config(CFG);
        boolean wasOn = (Boolean) ModUnderTest.call(cfg, "isEnabledSetting", new Class<?>[]{}, new Object[]{});
        boolean wasClassTargets = (Boolean) ModUnderTest.call(cfg, "isUseClassTargets", new Class<?>[]{}, new Object[]{});
        SimLeapSpots1117.pick(ctx, "MAGE");
        SimLeap1116.setTeammates(ctx, 0);
        start(ctx, "S3", true);
        try {
            ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{true});
            ModUnderTest.call(cfg, "setUseClassTargets", new Class<?>[]{boolean.class}, new Object[]{false});
            pick(cfg, "S2", "HEALER");
            pick(cfg, "S3", "TANK");
            rows(cfg, true);
            SimLeap1116.give(ctx, "INFINITE_SPIRIT_LEAP", 1);
            ctx.waitTicks(45);
            expect(f, "no Sim Teammate placed", SimLeap1116.mates(ctx).size(), 0);
            expect(f, "Fast Leap finds the Healer in the tab with nobody placed", ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                    "com.killer560.hub.fastleap.Teammates", "byName", new Class<?>[]{String.class}, new Object[]{"Healer"}) != null), true);

            // In S2's box while S3 completes (S3 is the section the sim is in): the S2 target.
            SimBossTests.tp(ctx, 60, 115, 130, 0f, 0f);
            ctx.waitTicks(45);
            long mark = LogTap.mark();
            int l0 = leaps(ctx);
            completed(ctx, "S3");
            expect(f, "standing in S2: the S2 target (Healer) was leapt to", chatSince(mark, "You have teleported to Healer!"), true);
            expect(f, "standing in S2: not the completed section's target (Tank)", chatSince(mark, "You have teleported to Tank!"), false);
            expect(f, "standing in S2: one leap counted", leaps(ctx) - l0, 1);

            // In S3's box while S2 completes: the S3 target.
            SimBossTests.tp(ctx, 10, 115, 100, 0f, 0f);
            ctx.waitTicks(60);
            long mark2 = LogTap.mark();
            int l1 = leaps(ctx);
            completed(ctx, "S2");
            expect(f, "standing in S3: the S3 target (Tank) was leapt to", chatSince(mark2, "You have teleported to Tank!"), true);
            expect(f, "standing in S3: not the completed section's target (Healer)", chatSince(mark2, "You have teleported to Healer!"), false);
            expect(f, "standing in S3: one leap counted", leaps(ctx) - l1, 1);

            // Outside S1-S4 (S5's box): no leap.
            SimBossTests.tp(ctx, 54.5, 115, 80, 0f, 0f);
            ctx.waitTicks(60);
            long mark3 = LogTap.mark();
            int l2 = leaps(ctx);
            completed(ctx, "S3");
            expect(f, "outside S1-S4: no leap", leaps(ctx) - l2, 0);
            expect(f, "outside S1-S4: no teleport line", chatSince(mark3, "You have teleported to"), false);
            println(NAME, "done");
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            rows(cfg, false);
            ModUnderTest.call(cfg, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{wasOn});
            ModUnderTest.call(cfg, "setUseClassTargets", new Class<?>[]{boolean.class}, new Object[]{wasClassTargets});
        }
    }
}
