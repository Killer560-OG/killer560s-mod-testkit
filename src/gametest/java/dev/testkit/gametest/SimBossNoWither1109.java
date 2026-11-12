package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.entity.boss.wither.WitherBoss;

import java.util.List;

/**
 * {@code 1109-boss-nowither} (bossnowither-1109, killer560 2026-11-09: "make an option for the boss sim to not spawn withers
 * so you can just make configs on it in peace"). The Spawn Withers switch: ON (default) a boss wither stands in P1 and in
 * P3; OFF none does in either phase while the phase itself still ran (its opening line came, Maxor's tag stand and the
 * boss are there) and the mod logged no error; the switch survives a reload from its file.
 */
final class SimBossNoWither1109 {

    private static final String SCREEN = "com.killer560.hub.bosssim.BossStartScreen";
    private static final String FIGHT = "com.killer560.hub.bosssim.BossFight";

    private SimBossNoWither1109() {
    }

    private static void set(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SCREEN, "setSpawnWithers", new Class<?>[]{boolean.class}, new Object[]{on}));
    }

    private static int withers(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            int n = 0;
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof WitherBoss) {
                    n++;
                }
            }
            return n;
        });
    }

    /** Boss name or speech stands (named armor stands, invisible or not) in the client world. */
    private static int stands(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            int n = 0;
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof net.minecraft.world.entity.decoration.ArmorStand a && a.hasCustomName()) {
                    String nm = a.getCustomName().getString();
                    if (nm.contains("Maxor") || nm.contains("Goldor") || nm.contains("Storm") || nm.contains("Necron")) {
                        n++;
                    }
                }
            }
            return n;
        });
    }

    private static boolean reloaded(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Class<?> c = Class.forName(SCREEN);
                var fld = c.getDeclaredField("withers");
                fld.setAccessible(true);
                fld.set(null, null);
                return (Boolean) ModUnderTest.staticCall(SCREEN, "spawnWithers", new Class<?>[]{}, new Object[]{});
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        set(ctx, false);
        if (reloaded(ctx)) {
            f.add("OFF did not survive a reload from the file");
        }
        for (String[] ph : new String[][]{{"P1", "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"},
                {"S1", "[BOSS] Goldor: Who dares trespass into my domain?"}}) {
            long mark = SimBossTests.start(ctx, ph[0], true);
            if (!SimBossTests.waitChat(ctx, mark, ph[1], 40)) {
                f.add(ph[0] + ": the phase's opening line never came, so nothing ran");
                continue;
            }
            ctx.waitTicks(100);
            int n = withers(ctx);
            Object pos = ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class},
                    new Object[]{ph[0].equals("P1") ? "Maxor" : "Goldor"});
            System.out.println("[1109-boss-nowither] " + ph[0] + " OFF: withers " + n + ", boss mob present " + (pos != null));
            if (n != 0) {
                f.add(ph[0] + ": " + n + " wither(s) with Spawn Withers OFF");
            }
            int st = stands(ctx);
            System.out.println("[1109-boss-nowither] " + ph[0] + " OFF: name stands " + st);
            if (st != 0) {
                f.add(ph[0] + ": " + st + " boss name stand(s) with Spawn Withers OFF");
            }
            if (pos == null) {
                f.add(ph[0] + ": the boss itself is missing (it should exist without its wither)");
            }
            List<String> errs = dev.testkit.gametest.LogTap.modErrorsSince(mark);
            if (!errs.isEmpty()) {
                f.add(ph[0] + ": mod error lines: " + errs.get(0));
            }
        }
        set(ctx, true);
        if (!reloaded(ctx)) {
            f.add("ON did not survive a reload from the file");
        }
        for (String ph : new String[]{"P1", "S1"}) {
            SimBossTests.start(ctx, ph, true);
            ctx.waitTicks(60);
            int n = withers(ctx);
            System.out.println("[1109-boss-nowither] " + ph + " ON: withers " + n);
            if (n < 1) {
                f.add(ph + ": no wither with Spawn Withers ON");
            }
            int st = stands(ctx);
            System.out.println("[1109-boss-nowither] " + ph + " ON: name stands " + st);
            if (st < 1) {
                f.add(ph + ": no boss name stand with Spawn Withers ON (control)");
            }
            if (ph.equals("P1")) {
                set(ctx, false);
                ctx.waitTicks(10);
                int w2 = withers(ctx);
                int s2 = stands(ctx);
                System.out.println("[1109-boss-nowither] P1 switched OFF mid-phase: withers " + w2 + ", stands " + s2);
                if (w2 != 0 || s2 != 0) {
                    f.add("P1: switching OFF mid-phase left " + w2 + " wither(s) and " + s2 + " stand(s)");
                }
                set(ctx, true);
            }
        }
    }
}
