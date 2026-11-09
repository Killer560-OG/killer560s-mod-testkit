package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.until;

/**
 * {@code 1109-boss-tpto} (boss-tpto-1109, killer560 2026-11-09): in the sim's F7 boss {@code /tpto <spot>} lands within one
 * block of ss, s1, hee2, ee3, outcore, storm, maxor, p5 and necron; the names are offered only in the boss; the F7 Boss
 * menu's Section 2/3/4 buttons land on hee2/ee3/outcore; every weapon assumes 100% Attack Speed; a P5 dragon is one pool
 * of 15 units (arrow 1, beam 3). Over the captured room, like his game.
 */
final class SimBossTpto1109 {

    static final String NAME = "1109-boss-tpto";
    private static final String B = "com.killer560.hub.bosssim.";
    private static final String FIGHT = B + "BossFight";
    private static final String CMDS = "com.killer560.hub.roomsim.SimTeleportCommands";
    /** name, x, y, z (Posmsg presets and BossFight start spots; s1 is a ground scan, so its y is not checked). */
    private static final Object[][] SPOTS = {
            {"ss", 107.878, 120.0, 93.917}, {"s1", 100.5, Double.NaN, 40.5}, {"hee2", 60.5, 132.0, 139.0},
            {"ee3", 2.5, 109.0, 105.5}, {"outcore", 54.5, 115.0, 50.5}, {"storm", 73.5, Double.NaN, 53.5},
            {"maxor", 73.5, 221.0, 14.5}, {"p5", 54.5, 5.0, 76.5}, {"necron", 54.5, 65.0, 76.5}};

    private SimBossTpto1109() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        SimBossTests.withCapture(f, () -> {
            try {
                tpto(ctx, f);
                menu(ctx, f);
                attackSpeed(f);
                dragon(ctx, f);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Vec3 pos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.position());
    }

    @SuppressWarnings("unchecked")
    private static List<String> roomNames(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (List<String>) call(CMDS, "roomNames"));
    }

    private static boolean near(Vec3 p, double x, double y, double z) {
        return Math.abs(p.x - x) <= 1 && Math.abs(p.z - z) <= 1 && (Double.isNaN(y) || Math.abs(p.y - y) <= 1);
    }

    private static void stop(ClientGameTestContext ctx) {
        serverRun(ctx, sv -> ModUnderTest.staticCall(FIGHT, "stop", new Class<?>[]{String.class}, new Object[]{"test"}));
    }

    private static void tpto(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P1", true);
        List<String> in = roomNames(ctx);
        for (Object[] s : SPOTS) {
            expect(f, "/tpto offers " + s[0] + " in the boss", in.contains((String) s[0]), true);
        }
        for (Object[] s : SPOTS) {
            String name = (String) s[0];
            // Away first, so a landing that did nothing cannot pass by standing where it started.
            SimBossTests.tp(ctx, 73.5, 221, 30.5, 0, 0);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(CMDS, "goTo", new Class<?>[]{Minecraft.class, String.class},
                    new Object[]{mc, name}));
            boolean ok = until(ctx, "landing at " + name, mc -> near(mc.player.position(), (Double) s[1], (Double) s[2], (Double) s[3]), 200);
            println(NAME, "/tpto " + name + " -> " + pos(ctx) + " (want " + s[1] + " " + s[2] + " " + s[3] + ")");
            expect(f, "/tpto " + name + " lands within 1 block", ok, true);
        }
        // Not suggested outside the boss: with the fight over the list is the floor's rooms, none of the spots.
        stop(ctx);
        ctx.runOnClient(mc -> call("com.killer560.hub.secrets.DungeonState", "simBossEnds"));
        ctx.waitTicks(5);
        List<String> out = roomNames(ctx);
        for (Object[] s : SPOTS) {
            expect(f, "/tpto offers " + s[0] + " outside the boss", out.contains((String) s[0]), false);
        }
    }

    private static void clickSection(ClientGameTestContext ctx, List<String> f, String label) throws Exception {
        Class<?> screen = Class.forName("com.killer560.hub.bosssim.BossStartScreen");
        ctx.runOnClient(mc -> {
            try {
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) screen
                        .getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(3);
        String pressed = ctx.computeOnClient(mc -> {
            var sc = McCompat.screen(mc);
            for (var child : sc.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals(label)) {
                    var ev = new net.minecraft.client.input.MouseButtonEvent(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0,
                            new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                    sc.mouseClicked(ev, false);
                    if (McCompat.screen(mc) == sc) {
                        sc.mouseReleased(ev);
                    }
                    return "ok";
                }
            }
            return "no " + label + " button";
        });
        expect(f, "the menu's " + label + " button", pressed, "ok");
    }

    private static void menu(ClientGameTestContext ctx, List<String> f) throws Exception {
        Object[][] want = {{"Section 2", 60.5, 132.0, 139.0}, {"Section 3", 2.5, 109.0, 105.5}, {"Section 4", 54.5, 115.0, 50.5}};
        for (Object[] w : want) {
            stop(ctx);
            start(ctx, "P1", true);
            clickSection(ctx, f, (String) w[0]);
            boolean ok = until(ctx, w[0] + " landing", mc -> mc.player != null && (Boolean) call(FIGHT, "running")
                    && near(mc.player.position(), (Double) w[1], (Double) w[2], (Double) w[3]), 1200);
            println(NAME, w[0] + " -> " + pos(ctx));
            expect(f, "the menu's " + w[0] + " lands on its spot", ok, true);
        }
    }

    private static void attackSpeed(List<String> f) throws Exception {
        Class<?> as = Class.forName("com.killer560.hub.roomsim.SimAttackSpeed");
        expect(f, "sim Attack Speed", as.getField("ATTACK_SPEED").getInt(null), 100);
        expect(f, "sim melee interval", as.getField("BASE_ATTACK_INTERVAL_TICKS").getInt(null), 5);
        expect(f, "sim shortbow interval", as.getField("SHORTBOW_INTERVAL_TICKS").getInt(null), 5);
        for (String[] c : new String[][]{{"com.killer560.hub.roomsim.SimTerminator", "SHOT_COOLDOWN_TICKS"},
                {"com.killer560.hub.roomsim.SimClass", "BEAM_COOLDOWN_TICKS"}}) {
            var fld = Class.forName(c[0]).getDeclaredField(c[1]);
            fld.setAccessible(true);
            expect(f, c[1] + " (fastest shot or beam interval)", fld.getInt(null), 5);
        }
        String ac = "com.killer560.hub.util.AttackCooldown";
        expect(f, "AttackCooldown at 100: melee", ModUnderTest.staticCall(ac, "hypixelTicks",
                new Class<?>[]{int.class, boolean.class}, new Object[]{100, false}), 5);
        expect(f, "AttackCooldown at 100: shortbow", ModUnderTest.staticCall(ac, "hypixelTicks",
                new Class<?>[]{int.class, boolean.class}, new Object[]{100, true}), 5);
    }

    private static boolean hit(ClientGameTestContext ctx, boolean beam) {
        boolean[] ok = new boolean[1];
        serverRun(ctx, sv -> ok[0] = (Boolean) ModUnderTest.staticCall(FIGHT, "testWeaponHit",
                new Class<?>[]{String.class, boolean.class}, new Object[]{"Dragon", beam}));
        return ok[0];
    }

    private static int units() {
        return (Integer) call(FIGHT, "dragonDamage");
    }

    /** Each case on its own fresh dragon: the hits (A arrow, B beam); under 15 units it must still be alive at that count. */
    private static void dragon(ClientGameTestContext ctx, List<String> f) {
        stop(ctx);
        start(ctx, "P5", true);
        String[][] cases = {{"15 arrows", "AAAAAAAAAAAAAAA"}, {"5 beams", "BBBBB"}, {"10 arrows + 2 beams", "AAAAAAAAAABB"},
                {"14 arrows", "AAAAAAAAAAAAAA"}};
        for (String[] c : cases) {
            if (!until(ctx, "a fresh dragon for " + c[0], mc -> units() == 0, 800)) {
                f.add("no fresh dragon for " + c[0]);
                return;
            }
            long mark = LogTap.mark();
            int sum = 0;
            for (char h : c[1].toCharArray()) {
                expect(f, c[0] + ": a hit landed", hit(ctx, h == 'B'), true);
                sum += h == 'B' ? 3 : 1;
                if (sum < 15) {
                    expect(f, c[0] + ": units after " + sum, units(), sum);
                }
            }
            ctx.waitTicks(3);
            boolean down = LogTap.since(mark).stream().anyMatch(l -> l.contains("dragon down at P5"));
            expect(f, c[0] + " (" + sum + "/15): dragon down", down, sum >= 15);
        }
    }
}
