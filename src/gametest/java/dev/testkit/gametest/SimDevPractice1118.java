package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import dev.testkit.compat.McCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.command;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.until;
import static dev.testkit.gametest.SimBossTests.useBlock;
import static dev.testkit.gametest.SimBossTests.waitChat;

/**
 * {@code 1118-sim-devpractice} (simdevpractice-1118, killer560 2026-11-18: "a practice option for the sim boss for ss and i4 ...
 * a button besides you that you press to restart the dev"). Simon Says: started from the chooser's button, a clean run reads
 * 0 misses and 10 clicks, a run with two wrong presses reads 2 misses, the Restart button mid-attempt puts the device and the
 * timer back, the personal best is saved and survives a reload. The Target device (i4): arrows put in the lit targets, one
 * deliberate miss, a restart mid-attempt. Every number is asserted against what the device did (state, blocks, chat).
 */
final class SimDevPractice1118 {

    static final String NAME = "1118-sim-devpractice";
    private static final String B = "com.killer560.hub.bosssim.";
    private static final String PRACTICE = B + "BossPractice";
    private static final String DEVICES = B + "BossDevices";
    private static final String SIM = B + "BossSim";
    private static final String FIGHT = B + "BossFight";
    private static final BlockPos SS_START = new BlockPos(110, 121, 91);

    private SimDevPractice1118() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        ss(ctx, f);
        SimBossTests.cleanup(ctx);
        i4(ctx, f);
    }

    private static Map<String, String> stats() {
        Map<String, String> m = new HashMap<>();
        for (String kv : String.valueOf(call(PRACTICE, "stats")).split(" ")) {
            int i = kv.indexOf('=');
            m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    private static int num(String key) {
        return Integer.parseInt(stats().get(key));
    }

    private static boolean flag(String key) {
        return Boolean.parseBoolean(stats().get(key));
    }

    private static int[] buttonAt() {
        return (int[]) call(PRACTICE, "buttonAt");
    }

    private static void waitRunning(ClientGameTestContext ctx, int device) {
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        if (!until(ctx, "the practice built and running", mc -> !(Boolean) call(SIM, "isBuilding")
                && (Boolean) call(FIGHT, "running") && num("dev") == device, 2400)) {
            throw new AssertionError("practice " + device + " never started (" + call(PRACTICE, "stats") + ")");
        }
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 600);
        ctx.waitTicks(10);
    }

    /** The Restart button: block, label and reach (checked on the client) and a press of it. */
    private static void checkButton(ClientGameTestContext ctx, List<String> f, String what) {
        int[] b = buttonAt();
        BlockPos pos = new BlockPos(b[0], b[1], b[2]);
        expect(f, what + ": Restart button is a stone button on the client",
                ctx.computeOnClient(mc -> mc.level.getBlockState(pos).is(Blocks.STONE_BUTTON)), true);
        boolean label = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(1.5)).stream()
                .anyMatch(s -> s.getCustomName() != null && s.getCustomName().getString().contains("Restart")));
        expect(f, what + ": a 'Restart' label floats over it", label, true);
        double d = ctx.computeOnClient(mc -> mc.player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)));
        expect(f, what + ": the button is within reach (" + String.format(Locale.ROOT, "%.1f", d) + ")", d < 3.5, true);
    }

    private static void pressRestart(ClientGameTestContext ctx) {
        int[] b = buttonAt();
        useBlock(ctx, new BlockPos(b[0], b[1], b[2]), Direction.UP);
        ctx.waitTicks(6);
    }

    // ---- Simon Says ---------------------------------------------------------------------------------------------------

    /** Plays Simon Says to its completion; {@code wrongWindows} of the first input windows get one wrong press. */
    private static boolean playSs(ClientGameTestContext ctx, int wrongWindows) {
        useBlock(ctx, SS_START, Direction.EAST);
        int wrongLeft = wrongWindows;
        for (int guard = 0; guard < 40 && !flag("finished"); guard++) {
            if (!until(ctx, "SS input", mc -> (Boolean) call(DEVICES, "ssInput") || flag("finished"), 500) || flag("finished")) {
                break;
            }
            ctx.waitTicks(3);
            @SuppressWarnings("unchecked")
            List<int[]> seq = (List<int[]>) call(DEVICES, "ssSequence");
            if (wrongLeft > 0) {
                wrongLeft--;
                int[] right = seq.get(0);
                int y = right[1] == 120 ? 121 : 120;
                useBlock(ctx, new BlockPos(110, y, 92), Direction.EAST);
                ctx.waitTicks(4);
                continue;
            }
            for (int[] lantern : seq) {
                useBlock(ctx, new BlockPos(110, lantern[1], lantern[2]), Direction.EAST);
                ctx.waitTicks(3);
            }
        }
        ctx.waitTicks(3);
        return flag("finished");
    }

    private static void ss(ClientGameTestContext ctx, List<String> f) {
        // From the title screen through the chooser's own "Simon Says" button.
        ctx.runOnClient(mc -> {
            try {
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) Class.forName(B + "BossStartScreen")
                        .getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(3);
        expect(f, "the chooser has a Fourth Device (i4) button", ctx.computeOnClient(mc -> McCompat.screen(mc).children().stream()
                .anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
                        && w.getMessage().getString().equals("Fourth Device (i4)"))), true);
        String pressed = ctx.computeOnClient(mc -> {
            var sc = McCompat.screen(mc);
            for (var child : sc.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals("Simon Says")) {
                    var ev = new net.minecraft.client.input.MouseButtonEvent(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0,
                            new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                    sc.mouseClicked(ev, false);
                    if (McCompat.screen(mc) == sc) {
                        sc.mouseReleased(ev);
                    }
                    return "ok";
                }
            }
            return "no Simon Says button on " + sc;
        });
        expect(f, "the chooser's Simon Says button", pressed, "ok");
        waitRunning(ctx, 1);
        double[] at = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        expect(f, "SS practice: he stands at the device (" + String.format(Locale.ROOT, "%.1f %.1f %.1f", at[0], at[1], at[2]) + ")",
                Math.abs(at[0] - 107.9) < 3 && Math.abs(at[2] - 93.9) < 3, true);
        checkButton(ctx, f, "SS");
        expect(f, "SS practice: the device is armed (IDLE)", call(DEVICES, "ssState"), "IDLE");
        int best = Integer.MAX_VALUE;

        // 1. a clean run: 0 misses, 10 clicks (1+2+3+4), 4 round times
        long mark = LogTap.mark();
        boolean done = playSs(ctx, 0);
        expect(f, "SS clean run finished", done, true);
        expect(f, "SS clean run: device DONE", call(DEVICES, "ssState"), "DONE");
        expect(f, "SS clean run: 0 misses", num("wrong") + "/" + num("lastMisses"), "0/0");
        expect(f, "SS clean run: 10 clicks", num("clicks"), 10);
        expect(f, "SS clean run: 4 round times", num("rounds"), 4);
        int ms1 = num("lastMs");
        best = Math.min(best, ms1);
        expect(f, "SS clean run: a plausible time (" + ms1 + " ms, four shows alone take over 4 s)", ms1 > 4000 && ms1 < 120000, true);
        expect(f, "SS result line: the time " + String.format(Locale.ROOT, "%.2fs", ms1 / 1000.0),
                waitChat(ctx, mark, "Simon Says: " + String.format(Locale.ROOT, "%.2fs", ms1 / 1000.0), 40), true);
        expect(f, "SS result line: 0 misses and 10 clicks", waitChat(ctx, mark, "Misses: 0   Clicks: 10", 20), true);
        expect(f, "SS result line: round times", waitChat(ctx, mark, "Rounds: 1 ", 20), true);
        expect(f, "SS result line: personal best", waitChat(ctx, mark, "Personal best: ", 20), true);
        SharedFlush.flush();
        expect(f, "SS personal best saved", call2(PRACTICE, 1), ms1);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PRACTICE, "reloadForTest", new Class<?>[]{}, new Object[]{}));
        expect(f, "SS personal best survives a reload", call2(PRACTICE, 1), ms1);

        // 2. the Restart button after a finished run: back to its start, timer at zero
        pressRestart(ctx);
        expect(f, "SS restart after a run: device IDLE", call(DEVICES, "ssState"), "IDLE");
        expect(f, "SS restart after a run: timer and counts cleared", stats().get("started") + " " + stats().get("clicks") + " " + stats().get("finished"),
                "false 0 false");

        // 3. two wrong presses: 2 misses, still finishes
        mark = LogTap.mark();
        done = playSs(ctx, 2);
        expect(f, "SS run with two wrong presses finished", done, true);
        expect(f, "SS two wrong presses: 2 misses", num("wrong") + "/" + num("lastMisses"), "2/2");
        expect(f, "SS two-miss result line", waitChat(ctx, mark, "Misses: 2", 40), true);
        best = Math.min(best, num("lastMs"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PRACTICE, "reloadForTest", new Class<?>[]{}, new Object[]{}));
        SharedFlush.flush();
        expect(f, "SS personal best is the fastest of the runs", call2(PRACTICE, 1), best);

        // 4. Restart mid-attempt: the device is back at its start and the next attempt times from zero
        pressRestart(ctx);
        useBlock(ctx, SS_START, Direction.EAST);
        until(ctx, "SS input", mc -> (Boolean) call(DEVICES, "ssInput"), 200);
        expect(f, "SS mid-attempt: the timer is running", flag("started"), true);
        pressRestart(ctx);
        expect(f, "SS mid-attempt restart: device IDLE", call(DEVICES, "ssState"), "IDLE");
        expect(f, "SS mid-attempt restart: timer cleared (started=" + flag("started") + ")", flag("started"), false);
        expect(f, "SS mid-attempt restart: no input window left", call(DEVICES, "ssInput"), false);
        boolean redo = playSs(ctx, 0);
        expect(f, "SS after the restart finished again, timed from its own start (" + num("lastMs") + " ms)", redo && num("lastMs") < 120000, true);
        expect(f, "SS after the restart: 0 misses, 10 clicks", num("lastMisses") + "/" + num("clicks"), "0/10");
    }

    private static Object call2(String cls, int device) {
        return ModUnderTest.staticCall(cls, "pbMs", new Class<?>[]{int.class}, new Object[]{device});
    }

    // ---- the Target device (i4) ---------------------------------------------------------------------------------------

    private static void arrowAt(ClientGameTestContext ctx, double x, double y, double z) {
        command(ctx, String.format(Locale.ROOT, "summon minecraft:arrow %.2f %.2f %.2f {NoGravity:1b,Motion:[0d,0d,0d]}", x, y, z));
    }

    /** Puts an arrow in the lit target and waits for the device to count it. */
    private static boolean hitLit(ClientGameTestContext ctx, int hitsBefore) {
        if (!until(ctx, "a lit target", mc -> call(DEVICES, "litTarget") != null, 200)) {
            return false;
        }
        int[] t = (int[]) call(DEVICES, "litTarget");
        arrowAt(ctx, t[0] + 0.5, t[1] + 0.5, t[2] - 0.2);
        return until(ctx, "hit " + (hitsBefore + 1), mc -> num("arrowHits") > hitsBefore, 60);
    }

    private static void i4(ClientGameTestContext ctx, List<String> f) {
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(SIM, "startPractice",
                new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, 4})));
        waitRunning(ctx, 4);
        double[] at = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        expect(f, "i4 practice: he stands on the plate (" + String.format(Locale.ROOT, "%.1f %.1f %.1f", at[0], at[1], at[2]) + ")",
                Math.abs(at[0] - 63.5) < 1.5 && Math.abs(at[2] - 35.5) < 1.5 && Math.abs(at[1] - 127) < 1.5, true);
        checkButton(ctx, f, "i4");

        // the device runs on its own once he is on the plate: the first target lights, the attempt is timed
        expect(f, "i4: the first target lit", until(ctx, "first target", mc -> call(DEVICES, "litTarget") != null, 200), true);
        expect(f, "i4: the attempt is timed from the first lit target", flag("started"), true);
        long mark = LogTap.mark();
        for (int i = 0; i < 9; i++) {
            if (i == 4) {
                arrowAt(ctx, 58.5, 128.5, 40.5);   // the deliberate miss: an arrow in no target
                ctx.waitTicks(4);
            }
            if (!hitLit(ctx, i)) {
                f.add("i4 target " + (i + 1) + " never counted (" + call(DEVICES, "describe") + " / " + call(PRACTICE, "stats") + ")");
                return;
            }
        }
        ctx.waitTicks(4);
        expect(f, "i4 finished", flag("finished"), true);
        expect(f, "i4: 9/9 targets on the device", String.valueOf(call(DEVICES, "describe")).contains("target=9/9"), true);
        expect(f, "i4: 10 arrows, 9 hit, 1 miss", num("shots") + "/" + num("arrowHits") + "/" + num("lastMisses"), "10/9/1");
        int ms = num("lastMs");
        expect(f, "i4 time is real (" + ms + " ms; nine targets take over 1 s)", ms > 1000 && ms < 120000, true);
        expect(f, "i4 result line: time", waitChat(ctx, mark, "Fourth Device: " + String.format(Locale.ROOT, "%.2fs", ms / 1000.0), 40), true);
        expect(f, "i4 result line: 1 miss, 10 arrows", waitChat(ctx, mark, "Misses: 1   Arrows: 10", 20), true);
        expect(f, "i4 result line: targets and accuracy", waitChat(ctx, mark, "Targets: 9/9   Accuracy: 90%", 20), true);
        SharedFlush.flush();
        expect(f, "i4 personal best saved", call2(PRACTICE, 4), ms);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PRACTICE, "reloadForTest", new Class<?>[]{}, new Object[]{}));
        expect(f, "i4 personal best survives a reload", call2(PRACTICE, 4), ms);

        // Restart after a finished run, then mid-attempt (two targets in, then the button)
        pressRestart(ctx);
        expect(f, "i4 restart: the device is back at 0/9", String.valueOf(call(DEVICES, "describe")).contains("target=0/9"), true);
        expect(f, "i4 restart: the miss arrow is gone and the counts are cleared", stats().get("finished") + " " + num("arrowHits"), "false 0");
        expect(f, "i4 restart: a new attempt lights its first target", until(ctx, "first target", mc -> call(DEVICES, "litTarget") != null, 200), true);
        expect(f, "i4 restart: timed again from zero", num("shots") + "/" + num("arrowHits") + "/" + flag("started"), "0/0/true");
        expect(f, "i4 mid-attempt: target 1", hitLit(ctx, 0), true);
        expect(f, "i4 mid-attempt: target 2", hitLit(ctx, 1), true);
        expect(f, "i4 mid-attempt: two hits counted", String.valueOf(call(DEVICES, "describe")).contains("target=2/9"), true);
        pressRestart(ctx);
        expect(f, "i4 mid-attempt restart: back to 0/9", String.valueOf(call(DEVICES, "describe")).contains("target=0/9"), true);
        expect(f, "i4 mid-attempt restart: counts cleared", num("arrowHits") + "/" + num("targets"), "0/0");
        expect(f, "i4 mid-attempt restart: the attempt starts over", until(ctx, "first target", mc -> call(DEVICES, "litTarget") != null, 200), true);
        println(NAME, "i4 and SS practice done");
    }

    /** The record is written on the shared writer thread; wait for it. */
    private static final class SharedFlush {
        static void flush() {
            ModUnderTest.staticCall("com.killer560.hub.shared.SharedWriter", "drain", new Class<?>[]{long.class}, new Object[]{5000L});
        }
    }
}
