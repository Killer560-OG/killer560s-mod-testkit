package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.gametest.hx.Hx;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * 1105-grim-bonzo / -jerry / -springboots (mod simitems-1105, killer560 2026-11-05: "add spring boots to the boss sim as
 * well and jerry chine gun" / "and bonzo staff"): the three launch items on the GrimAC server, answered by
 * {@code hx/dungeon/HxLaunchItems} the way Hypixel's server answers them (and the way the mod's sim does): a server motion
 * packet. Each case proves the server fired and launched him and that he flew as far as Hypixel's push takes him, with
 * GrimAC clean (run once on default checks and once in a checkout with {@code experimental-checks: true}).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimLaunchItemsTests implements FabricClientGameTest {

    private static final int SURFACE_Y = 151;
    private static final int Z = -12500;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Runnable r : new Runnable[]{() -> bonzo(ctx), () -> jerry(ctx), () -> spring(ctx)}) {
            try {
                r.run();
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    private static void arena(TestServer server, int x) {
        TestMap.on(server)
                .platform(x, SURFACE_Y - 1, Z, 30)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .spawn(x + 0.5, Z + 0.5, 0f)
                .build();
    }

    private static int stat(Hx hx, String key) {
        JsonObject o = hx.call("dungeon.stats").getAsJsonObject();
        return o.has(key) ? o.get(key).getAsInt() : 0;
    }

    private static Hx setUp(ClientGameTestContext ctx, int slot, String stack) {
        Hx hx = Hx.connect();
        hx.call("dungeon.abilities", "enabled", true);
        hx.call("dungeon.reset");
        hx.give(slot, stack);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(10);
        return hx;
    }

    private static void tearDown(ClientGameTestContext ctx, Hx hx) {
        ctx.getInput().releaseKey(o -> o.keyShift);
        if (hx != null) {
            hx.call("dungeon.abilities", "enabled", false);
            hx.close();
        }
        ctx.waitTicks(20);
    }

    private static void look(ClientGameTestContext ctx, float yaw, float pitch) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(5);
    }

    /** Waits {@code ticks}, returning {highest y, flat distance from {@code from}} over them. */
    private static double[] fly(ClientGameTestContext ctx, Vec3 from, int ticks) {
        double top = from.y, far = 0;
        for (int i = 0; i < ticks; i++) {
            ctx.waitTick();
            Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
            top = Math.max(top, p.y);
            far = Math.max(far, Math.hypot(p.x - from.x, p.z - from.z));
        }
        return new double[]{top - from.y, far};
    }

    private static void bonzo(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1105-grim-bonzo", (server, scenario) -> arena(server, -12500), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            try {
                hx = setUp(ctx, 0, "minecraft:blaze_rod[custom_data={id:\"BONZO_STAFF\"}]");
                // Facing +z, the balloon aimed at the floor 1-2 blocks ahead: Hypixel's "Bonzo jump" back and up.
                look(ctx, 0f, 70f);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                for (int i = 0; i < 3; i++) {
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.waitTick();
                }
                double[] f = fly(ctx, start, 40);
                Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
                int shots = stat(hx, "bonzoShots"), launches = stat(hx, "launches");
                scenario.log(String.format(Locale.ROOT, "bonzo: %d shot(s) from 3 clicks in 3 ticks, %d launch(es), "
                        + "rose %.2f, flew %.2f flat, z %.2f -> %.2f", shots, launches, f[0], f[1], start.z, end.z));
                check(shots == 1, "the 4-tick gate let " + shots + " balloons out of 3 clicks in 3 ticks");
                check(launches == 1, "the balloon never launched him");
                check(end.z < start.z - 2.0, "he was not pushed back (away from the burst)");
                check(f[0] > 1.0 && f[0] < 2.6, "rise " + f[0] + " (Hypixel: about 1.7)");
                check(f[1] > 3.0 && f[1] < 14.0, "flat distance " + f[1] + " (Hypixel: up to about 8-11)");
            } finally {
                tearDown(ctx, hx);
            }
        });
    }

    private static void jerry(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1105-grim-jerry", (server, scenario) -> arena(server, -12600), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            try {
                hx = setUp(ctx, 0, "minecraft:golden_horse_armor[custom_data={id:\"JERRY_STAFF\"}]");
                look(ctx, 0f, 90f);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                double top = 0;
                for (int i = 0; i < 10; i++) {
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.waitTick();
                    top = Math.max(top, ctx.computeOnClient(mc -> mc.player.getY()) - start.y);
                }
                double[] f = fly(ctx, start, 40);
                top = Math.max(top, f[0]);
                int shots = stat(hx, "jerryShots"), launches = stat(hx, "launches");
                scenario.log(String.format(Locale.ROOT, "jerry: %d shot(s), %d launch(es), rose %.2f, drifted %.2f",
                        shots, launches, top, f[1]));
                check(shots == 10, "the server fired " + shots + " bullets for 10 clicks");
                check(launches >= 2, "only " + launches + " bullet(s) launched him");
                check(top > 1.5 && top < 8.0, "rise " + top + " (Hypixel: about 2.4 a shot)");
                check(f[1] < 1.0, "looking straight down he drifted " + f[1]);
            } finally {
                tearDown(ctx, hx);
            }
        });
    }

    private static void spring(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1105-grim-springboots", (server, scenario) -> arena(server, -12700), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            try {
                hx = setUp(ctx, 36, "minecraft:chainmail_boots[custom_data={id:\"SPRING_BOOTS\"}]");
                check(ctx.computeOnClient(mc -> mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)
                        .is(net.minecraft.world.item.Items.CHAINMAIL_BOOTS)), "the boots are not on his feet");
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                ctx.getInput().holdKey(o -> o.keyShift);
                ctx.waitTicks(32);
                ctx.getInput().releaseKey(o -> o.keyShift);
                double[] f = fly(ctx, start, 60);
                int launches = stat(hx, "springLaunches");
                scenario.log(String.format(Locale.ROOT, "spring boots: %d launch(es) after 32 ticks of sneak, rose %.2f",
                        launches, f[0]));
                check(launches == 1, "letting go of sneak never launched him");
                // 32 ticks is 10 notes: 22.5 blocks on the Spring Boots table.
                check(Math.abs(f[0] - 22.5) < 3.5, "rose " + f[0] + ", the table says 22.5 for 10 notes");
                ctx.waitTicks(80);
            } finally {
                tearDown(ctx, hx);
            }
        });
    }
}
