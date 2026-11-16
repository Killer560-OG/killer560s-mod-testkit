package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.command;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.tp;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1116-sim-i4terror} (i4terror-1116, killer560 2026-11-16: "the auto i4 needs to auto detect if i am using terror or
 * not ... and if i have the stacks. if i do then it should aim differently than normal if using terminator so it hits more
 * stuff", and "same with machine gun shortbow"). Auto i4 runs the sim's real Target device (S4, master mode, the plate at
 * 63,127,35) with each bow: Sim Armor Terror with Terror Aim OFF, the same with it ON (same device order, the device's RNG
 * seeded before the plate is pressed), and no armor with Terror Aim ON. Every number comes from what the sim did: its own
 * shot lines (yaw and pitch of each volley), its 9/9 count, and the mod's Terror Aim log line and counter.
 */
final class SimI4Terror1116 {

    static final String NAME = "1116-sim-i4terror";
    private static final String CFG = "com.killer560.hub.i4sensors.I4SensorsConfig";
    private static final String AIM = "com.killer560.hub.i4sensors.TerrorAim";
    private static final String DEVICES = "com.killer560.hub.bosssim.BossDevices";
    private static final String LOADOUT = "com.killer560.hub.roomsim.SimLoadout";
    private static final String ARMOR = "com.killer560.hub.roomsim.SimArmor";
    private static final Pattern SHOT = Pattern.compile("Sim Terminator: shot #\\d+ yaw (-?[\\d.]+) pitch (-?[\\d.]+)");
    private static final long SEED = 1116L;

    private SimI4Terror1116() {
    }

    /** One device run: volleys until 9/9, the volleys' looks, whether Terror Aim said ACTIVE, aims it chose. */
    private record Run(int volleys, boolean done, boolean active, int aimsChosen, List<float[]> looks, Vec3 eye) {
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        Object cfg = ModUnderTest.config(CFG);
        boolean wasOn = ModUnderTest.getBoolean(cfg, "isAutoI4TerrorAim");
        try {
            for (String[] bow : new String[][]{{"TERMINATOR", "TERMINATOR", "3"}, {"MACHINE_GUN_BOW", "MACHINE_GUN_SHORTBOW", "1"}}) {
                int base = Integer.parseInt(bow[2]);
                Run off = device(ctx, f, bow, "TERROR", false);
                Run on = device(ctx, f, bow, "TERROR", true);
                Run none = device(ctx, f, bow, "NONE", true);
                String w = bow[1];
                println(NAME, String.format(Locale.ROOT, "%s: Terror, aim off %d volleys (%.2f real-block targets a volley); "
                                + "Terror, aim on %d volleys (%.2f); no armor, aim on %d volleys (%.2f)", w,
                        off.volleys, coverage(off, base + 2), on.volleys, coverage(on, base + 2), none.volleys, coverage(none, base)));
                expect(f, w + ": Terror, aim off finished the device", off.done, true);
                expect(f, w + ": Terror, aim on finished the device", on.done, true);
                expect(f, w + ": no armor finished the device", none.done, true);
                expect(f, w + ": Terror Aim never ACTIVE while switched off", off.active, false);
                expect(f, w + ": Terror Aim ACTIVE with Terror and full stacks", on.active, true);
                expect(f, w + ": Terror Aim chose its own aims (acted)", on.aimsChosen > 0, true);
                expect(f, w + ": Terror Aim never ACTIVE without Terror", none.active, false);
                expect(f, w + ": no Terror Aim point chosen without Terror", none.aimsChosen, 0);
                expect(f, w + ": without Terror every volley goes at the old aim points", offOldAims(none, w), List.of());
                expect(f, w + ": with Terror Aim off every volley goes at the old aim points", offOldAims(off, w), List.of());
                expect(f, w + ": Terror Aim takes no more volleys than the old aim (" + on.volleys + " vs " + off.volleys + ")",
                        on.volleys <= off.volleys, true);
                expect(f, w + ": Terror Aim's volleys cover at least as many real target blocks",
                        coverage(on, base + 2) + 1e-9 >= coverage(off, base + 2), true);
            }
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.set(cfg, "setAutoI4Enabled", false);
                ModUnderTest.set(cfg, "setAutoI4TerrorAim", wasOn);
            });
            SimArmor1115.selectNone(ctx);
        }
    }

    private static Run device(ClientGameTestContext ctx, List<String> f, String[] bow, String armor, boolean terrorAim) {
        start(ctx, "S4", true);
        Object set = ModUnderTest.enumValue(ARMOR, armor);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(LOADOUT, "setArmor", new Class<?>[]{set.getClass()}, new Object[]{set}));
        command(ctx, "item replace entity @p hotbar.0 with bow[custom_data={id:\"" + bow[0] + "\"}]");
        command(ctx, "give @p arrow 64");
        Object cfg = ModUnderTest.config(CFG);
        Object weapon = ModUnderTest.enumValue(CFG + "$Weapon", bow[1]);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            ModUnderTest.call(cfg, "setAutoI4Weapon", new Class<?>[]{weapon.getClass()}, new Object[]{weapon});
            ModUnderTest.call(cfg, "setCpsRange", new Class<?>[]{int.class, int.class}, new Object[]{8, 8});
            ModUnderTest.call(cfg, "setShotAccuracyPercent", new Class<?>[]{int.class}, new Object[]{100});
            ModUnderTest.set(cfg, "setAutoI4Rotate", true);
            ModUnderTest.set(cfg, "setAutoI4Predictions", false);
            ModUnderTest.set(cfg, "setAutoSwapToBow", false);
            ModUnderTest.set(cfg, "setAutoMask", false);
            ModUnderTest.set(cfg, "setAutoI4TerrorAim", terrorAim);
        });
        // The same device order for every run: the device shuffles its nine on the first pressed tick.
        serverRun(ctx, s -> {
            try {
                var fld = Class.forName(DEVICES).getDeclaredField("RNG");
                fld.setAccessible(true);
                ((java.util.Random) fld.get(null)).setSeed(SEED);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot seed BossDevices.RNG", e);
            }
        });
        int aims0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AIM, "aimsChosenForTest"));
        long mark = LogTap.mark();
        tp(ctx, 63.5, 127, 35.5, 0, 0);
        ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setAutoI4Enabled", true));
        boolean done = until(ctx, "the Target device at 9/9", mc -> String.valueOf(call(DEVICES, "describe")).contains("target=9/9"), 1200);
        ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setAutoI4Enabled", false));
        Vec3 eye = ctx.computeOnClient(mc -> mc.player.getEyePosition());
        int aims = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AIM, "aimsChosenForTest")) - aims0;
        List<float[]> looks = new ArrayList<>();
        boolean active = false;
        for (String line : LogTap.since(mark)) {
            Matcher m = SHOT.matcher(line);
            if (m.find()) {
                looks.add(new float[]{Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2))});
            }
            if (line.contains("[AutoI4] Terror Aim ACTIVE")) {
                active = true;
            }
        }
        if (!done) {
            f.add(bow[1] + " " + armor + " aim " + terrorAim + ": device not done after 60 s (" + call(DEVICES, "describe") + ")");
        }
        println(NAME, String.format(Locale.ROOT, "%s %s aim %s: %d volleys, done %s, ACTIVE %s, %d Terror Aim points, %s",
                bow[1], armor, terrorAim, looks.size(), done, active, aims, call(DEVICES, "describe")));
        return new Run(looks.size(), done, active, aims, looks, eye);
    }

    /** Volleys whose look is not one of the old aim points (Terminator between columns, Machine Gun block centers). */
    private static List<String> offOldAims(Run r, String weapon) {
        double[] xs = "TERMINATOR".equals(weapon) ? new double[]{65.5, 67.5} : new double[]{64.5, 66.5, 68.5};
        List<String> bad = new ArrayList<>();
        for (float[] look : r.looks) {
            boolean ok = false;
            for (double x : xs) {
                for (int row = 0; row < 3; row++) {
                    Vec3 d = new Vec3(x, 131 - 2.0 * row, 50).subtract(r.eye);
                    float yaw = (float) Math.toDegrees(Math.atan2(d.z, d.x)) - 90f;
                    float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                    if (Math.abs(look[0] - yaw) < 0.3 && Math.abs(look[1] - pitch) < 0.3) {
                        ok = true;
                    }
                }
            }
            if (!ok) {
                bad.add(String.format(Locale.ROOT, "%.1f/%.1f", look[0], look[1]));
            }
        }
        return bad;
    }

    /** Mean count of target columns a volley's fan crosses inside the real block (x 64/66/68, one wide) at z 50. */
    private static double coverage(Run r, int arrows) {
        if (r.looks.isEmpty()) {
            return 0;
        }
        double dz = 50 - r.eye.z;
        int sum = 0;
        for (float[] look : r.looks) {
            int mask = 0;
            for (int i = 0; i < arrows; i++) {
                double yaw = look[0] + (i - (arrows - 1) / 2.0) * 5.0;
                double x = r.eye.x - dz * Math.tan(Math.toRadians(yaw));
                for (int col = 0; col < 3; col++) {
                    double left = 64 + 2 * col;
                    if (x > left && x < left + 1) {
                        mask |= 1 << col;
                    }
                }
            }
            sum += Integer.bitCount(mask);
        }
        return sum / (double) r.looks.size();
    }
}
