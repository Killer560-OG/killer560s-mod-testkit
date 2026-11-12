package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.dragonIn;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.field;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.spec;
import static dev.testkit.gametest.SimBossTests.start;
import static dev.testkit.gametest.SimBossTests.tp;
import static dev.testkit.gametest.SimBossTests.until;
import static dev.testkit.gametest.SimBossTests.waitChat;

/**
 * necronpull-1111 (killer560 2026-11-11).
 * <ul>
 * <li>{@code 1111-boss-necronpull}: Goldor dies with him in the core but off the plug; as the floor goes Necron pulls him
 * into the shaft and down, holds him hovering over the platform through his intro, and lets go at "Goodbye.".</li>
 * <li>{@code 1111-boss-dragonhp}: a P5 dragon rises out of its spawn point then loops its own area; it sends Hypixel's
 * 1,000,000,000 health and loses 1/15 of it per ARROW (three arrows landing in one server tick are three hits), which the
 * Wither Dragons readout follows; a Terminator volley counts more than one arrow.</li>
 * </ul>
 */
final class SimBossNecron1111 {

    static final String PULL = "1111-boss-necronpull";
    static final String DRAGON_HP = "1111-boss-dragonhp";
    private static final String FIGHT = "com.killer560.hub.bosssim.BossFight";
    private static final String WD = "com.killer560.hub.witherdragons.";
    private static final float HP = 1_000_000_000f;
    private static final int VOLLEYS = 3; // 3 + 9 units: the dragon stays alive (15)

    private SimBossNecron1111() {
    }

    static void pull(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "CORE", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        tp(ctx, 54.5, 107, 90.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: You have done it, you destroyed the factory...", 400)) {
            f.add("walking into the core never brought Goldor's arrival lines");
            return;
        }
        serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Goldor"}));
        // In the core room but OFF the plug (x 51-57, z 111-117): only the pull can bring him into the shaft.
        tp(ctx, 54.5, 115, 100.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Necron: You went further than any human before, congratulations.", 140)) {
            f.add("Necron's first line never came");
            return;
        }
        String pullLine = LogTap.since(mark).stream().filter(l -> l.contains("Necron pulls everyone down")).findFirst().orElse(null);
        if (pullLine == null) {
            f.add("no \"Necron pulls everyone down\" line: the pull never started");
            return;
        }
        double hover = Double.parseDouble(pullLine.replaceAll(".*hover y ([0-9.]+).*", "$1"));
        println(PULL, pullLine.trim());
        if (!until(ctx, "him at the hover height", mc -> Math.abs(mc.player.getY() - hover) < 0.6, 70)) {
            f.add("never reached the hover height " + hover + " (y " + ctx.computeOnClient(mc -> mc.player.getY()) + ")");
            return;
        }
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
        println(PULL, "hovering at " + at);
        expect(f, "pulled into the shaft square (x 51-58, z 111-118)",
                at.x >= 51 && at.x <= 58 && at.z >= 111 && at.z <= 118, true);
        double worst = 0;
        ctx.waitTicks(2); // the tick he arrives overshoots by up to one push (0.6); the hold teleport settles it
        for (int i = 0; i < 15; i++) {
            ctx.waitTicks(1);
            double cy = ctx.computeOnClient(mc -> mc.player.getY());
            double sy = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getPlayerList().getPlayers().get(0).getY());
            worst = Math.max(worst, Math.max(Math.abs(cy - hover), Math.abs(sy - hover)));
        }
        println(PULL, String.format(Locale.ROOT, "held 15 ticks, worst %.3f off the hover height (client and server)", worst));
        expect(f, "held at the hover height through the intro (within 0.1)", worst < 0.1, true);
        expect(f, "still hovering before \"Goodbye.\"", chatSeen(mark, "[BOSS] Necron: Goodbye."), false);
        if (!waitChat(ctx, mark, "[BOSS] Necron: Goodbye.", 120)) {
            f.add("\"Goodbye.\" never came");
            return;
        }
        boolean landed = until(ctx, "him on the platform", mc -> mc.player.onGround() && mc.player.getY() < hover - 2, 40);
        double y = ctx.computeOnClient(mc -> mc.player.getY());
        println(PULL, "after \"Goodbye.\": y " + y + ", on ground " + landed);
        expect(f, "let go at \"Goodbye.\" and landed on the platform 3 below", landed && Math.abs(y - (hover - 3)) < 0.6, true);
        expect(f, "\"Necron lets go\" logged", LogTap.since(mark).stream().anyMatch(l -> l.contains("Necron lets go")), true);
    }

    private static boolean chatSeen(long mark, String text) {
        return LogTap.since(mark).stream().anyMatch(l -> l.contains(text));
    }

    static void dragonHp(ClientGameTestContext ctx, List<String> f) {
        Object cfg = ModUnderTest.config(WD + "WitherDragonsConfig");
        ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setEnabled", true));
        try {
            dragonRun(ctx, f);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setEnabled", false));
        }
    }

    /** A spot 6 from the server's dragon body where he fits and all three lanes reach it with no block first. */
    private static Vec3 clearSpot(ServerLevel lvl) {
        Vec3 b = serverBody(lvl);
        for (double h : new double[]{0, 2, -2, 4}) {
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(k * 30);
                Vec3 eye = b.add(Math.cos(a) * 6, h, Math.sin(a) * 6);
                if (!lvl.noCollision(new AABB(eye.x - 0.3, eye.y - 1.62, eye.z - 0.3, eye.x + 0.3, eye.y + 0.2, eye.z + 0.3))) {
                    continue;
                }
                Vec3 to = b.subtract(eye);
                if (clearLanes(lvl, eye, (float) Math.toDegrees(Math.atan2(-to.x, to.z)),
                        (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))))) {
                    return eye;
                }
            }
        }
        return null;
    }

    /** All three lanes (yaw -5, 0, +5) strike the body box shrunk by 0.6, with no block until 3 past where they enter. */
    private static boolean clearLanes(ServerLevel lvl, Vec3 eye, float yaw, float pitch) {
        AABB body = dragonOf(lvl).getSubEntities()[2].getBoundingBox().deflate(0.6);
        Vec3 from = eye.subtract(0, 0.1, 0);
        for (int lane = -1; lane <= 1; lane++) {
            Vec3 dir = Vec3.directionFromRotation(pitch, yaw + lane * 5f);
            var in = body.clip(from, from.add(dir.scale(14)));
            if (in.isEmpty()) {
                return false;
            }
            var block = lvl.clip(new net.minecraft.world.level.ClipContext(from, from.add(dir.scale(in.get().distanceTo(from) + 3)),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                    net.minecraft.world.phys.shapes.CollisionContext.empty()));
            if (block.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                return false;
            }
        }
        return true;
    }

    // Server-thread only (serverRun): reading the server's world from the client thread froze the client (02:28 run).
    private static EnderDragon dragonOf(ServerLevel lvl) {
        ServerPlayer sp = lvl.getServer().getPlayerList().getPlayers().get(0);
        return lvl.getEntitiesOfClass(EnderDragon.class, sp.getBoundingBox().inflate(60), x -> x.getHealth() > 0).get(0);
    }

    private static Vec3 serverBody(ServerLevel lvl) {
        return dragonOf(lvl).getSubEntities()[2].getBoundingBox().getCenter();
    }

    private static int units() {
        return (Integer) call(FIGHT, "dragonDamage");
    }

    private static void dragonRun(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "P5", true);
        if (!waitChat(ctx, mark, "[BOSS] The Wither King: You... again?", 160)) {
            f.add("the Wither King's line never came");
            return;
        }
        Object red = ModUnderTest.enumValue(WD + "WitherDragon", "RED");
        AABB box = (AABB) field(red, "box");
        tp(ctx, box.getCenter().x - 10, 20, box.getCenter().z, -90, 0);
        EnderDragon[] e = new EnderDragon[1];
        if (!until(ctx, "Red's dragon", mc -> (e[0] = dragonIn(mc, red)) != null, 260)) {
            f.add("Red's dragon never spawned in its area");
            return;
        }
        // Flight: straight up out of its spawn point, then a loop round its area.
        Vec3 p0 = ctx.computeOnClient(mc -> e[0].position());
        ctx.waitTicks(20);
        Vec3 p1 = ctx.computeOnClient(mc -> e[0].position());
        println(DRAGON_HP, "rise: " + p0 + " -> " + p1);
        expect(f, "rises out of its spawn point (up, not sideways)",
                p1.y - p0.y > 1.5 && Math.hypot(p1.x - p0.x, p1.z - p0.z) < 0.5, true);
        Vec3 c = box.getCenter();
        double radius = Math.max(3.0, Math.min(box.getXsize(), box.getZsize()) / 2 - 5.0);
        ctx.waitTicks(80);
        double swept = 0, worstR = 0, last = Double.NaN;
        boolean inside = true;
        for (int i = 0; i < 60; i++) {
            ctx.waitTicks(2);
            Vec3 p = ctx.computeOnClient(mc -> e[0].position());
            inside &= box.contains(p);
            worstR = Math.max(worstR, Math.abs(Math.hypot(p.x - c.x, p.z - c.z) - radius));
            double a = Math.atan2(p.z - c.z, p.x - c.x);
            if (!Double.isNaN(last)) {
                swept += Math.atan2(Math.sin(a - last), Math.cos(a - last));
            }
            last = a;
        }
        println(DRAGON_HP, String.format(Locale.ROOT, "loop: swept %.0f deg, worst %.1f off radius %.1f, inside %s",
                Math.toDegrees(swept), worstR, radius, inside));
        expect(f, "stays inside its area", inside, true);
        expect(f, "flies a loop round its area (within 1.5 of the radius)", worstR < 1.5, true);
        expect(f, "goes round one way (over 60 degrees in 120 ticks)", Math.abs(swept) > Math.toRadians(60), true);

        // Health: Hypixel's 1,000,000,000 at spawn, read by the client and by Wither Dragons.
        // The client entity clamps to its max a tick after each packet (vanilla EnderDragon.aiStep), so the readout is the
        // packet's value, which Wither Dragons keeps (and now draws).
        float h0 = (Float) spec(red, "health");
        println(DRAGON_HP, "health at start: Wither Dragons reads " + h0 + " (client entity " + ctx.computeOnClient(mc -> e[0].getHealth()) + ")");
        expect(f, "starts at Hypixel's 1,000,000,000 health", Math.abs(h0 - HP) < 1000, true);

        // Three arrows landing in ONE server tick are three hits (onHurt's one-per-tick rule ate two of them).
        int u0 = units();
        List<Arrow> arrows = new ArrayList<>();
        serverRun(ctx, s -> {
            ServerPlayer sp = s.getPlayerList().getPlayers().get(0);
            ServerLevel lvl = (ServerLevel) sp.level();
            EnderDragon d = lvl.getEntitiesOfClass(EnderDragon.class, box, x -> x.getHealth() > 0).get(0);
            Vec3 body = d.getSubEntities()[2].getBoundingBox().getCenter();
            for (int i = -1; i <= 1; i++) {
                Arrow a = new Arrow(lvl, sp, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                // Just outside the body box (a clip that starts inside a box misses it), aimed at its center: lands next tick.
                Vec3 from = new Vec3(d.getSubEntities()[2].getBoundingBox().minX - 0.8, body.y + 0.3 * i, body.z + 0.3 * i);
                Vec3 dir = body.subtract(from);
                a.setPos(from.x, from.y, from.z);
                a.shoot(dir.x, dir.y, dir.z, 3.0f, 0f);
                a.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
                lvl.addFreshEntity(a);
                arrows.add(a);
            }
        });
        ctx.waitTicks(6);
        long landed = arrows.stream().filter(a -> a.isRemoved()).count();
        int u1 = units();
        println(DRAGON_HP, "3 arrows in one tick: " + landed + " landed, units " + u0 + " -> " + u1);
        expect(f, "all three arrows landed", landed, 3L);
        expect(f, "every landed arrow is a hit (units up by arrows landed)", (long) (u1 - u0), landed);
        float want = HP * (15 - u1) / 15f;
        boolean seen = until(ctx, "Wither Dragons' health readout", mc -> Math.abs((Float) spec(red, "health") - want) < 1000, 20);
        println(DRAGON_HP, "Wither Dragons reads " + spec(red, "health") + ", want " + want);
        expect(f, "Wither Dragons' health readout drops to " + want, seen, true);

        // Real Terminator volleys: every arrow that reaches the dragon counts, one unit each. The lost arrows of the
        // first tries (3 of 6, 4 of 9, 7 of 9) were none of them dropped hits: shots taken from inside the server's body
        // box (aimed by the client's dragon, drawn 1-1.5 blocks behind it, as on Hypixel) clip nothing, and the rest met
        // the arena's blocks first (the wall, the statue the body overlapped, the ceiling). So each volley is taken from
        // a spot whose three lanes all strike the body with no block before it, aimed at the SERVER's body.
        int u2 = units(), fired0 = (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired");
        int volleys = 0;
        for (int tries = 0; volleys < VOLLEYS && tries < 40; tries++) {
            Vec3[] at = new Vec3[1];
            serverRun(ctx, srv -> at[0] = clearSpot((ServerLevel) srv.getPlayerList().getPlayers().get(0).level()));
            Vec3 spot = at[0];
            if (spot == null) {
                ctx.waitTicks(5);
                continue;
            }
            tp(ctx, spot.x, spot.y - 1.62, spot.z, 0, 0);
            ctx.waitTicks(3); // the teleport reaches his client before the shot
            float[] aim = new float[2];
            boolean[] clear = new boolean[1];
            serverRun(ctx, srv -> {
                ServerPlayer sp = srv.getPlayerList().getPlayers().get(0);
                ServerLevel lvl = (ServerLevel) sp.level();
                Vec3 eye = sp.getEyePosition();
                Vec3 to = serverBody(lvl).subtract(eye);
                aim[0] = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                aim[1] = (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z)));
                clear[0] = clearLanes(lvl, eye, aim[0], aim[1]);
            });
            boolean shot = clear[0];
            if (shot) {
                ctx.runOnClient(mc -> {
                    mc.player.setYRot(aim[0]);
                    mc.player.setXRot(aim[1]);
                    ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTerminator", "use",
                            new Class<?>[]{net.minecraft.client.Minecraft.class, boolean.class}, new Object[]{mc, false});
                });
            }
            if (shot) {
                volleys++;
                ctx.waitTicks(10);
            }
        }
        int fired = (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired") - fired0;
        int hits = units() - u2;
        println(DRAGON_HP, volleys + " clear Terminator volleys: " + fired + " arrows fired, " + hits + " counted");
        expect(f, VOLLEYS + " clear volleys fired", volleys, VOLLEYS);
        expect(f, "every arrow of every clear volley counted (one unit each)", hits, fired);
    }
}
