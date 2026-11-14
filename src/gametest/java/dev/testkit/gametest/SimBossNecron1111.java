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
        // The loop it chose at spawn, clear of the arena's blocks (his "never inside blocks"): center and radius.
        String loopLine = LogTap.since(mark).stream().filter(l -> l.contains("Red dragon loop: center")).findFirst().orElse("");
        println(DRAGON_HP, loopLine.trim());
        expect(f, "Red's loop was chosen clear of blocks", loopLine.isEmpty(), false);
        java.util.regex.Matcher lm = java.util.regex.Pattern.compile("center \\(([-0-9.]+), [-0-9.]+, ([-0-9.]+)\\), radius ([0-9.]+)").matcher(loopLine);
        boolean found = lm.find();
        expect(f, "loop line parsed", found, true);
        Vec3 c = found ? new Vec3(Double.parseDouble(lm.group(1)), 0, Double.parseDouble(lm.group(2))) : box.getCenter();
        double radius = found ? Double.parseDouble(lm.group(3)) : 0;
        ctx.waitTicks(80);
        double swept = 0, worstR = 0, last = Double.NaN;
        boolean inside = box.inflate(8.5, 64, 8.5).contains(new Vec3(c.x, box.getCenter().y, c.z)); // its own area, or just past it
        int[] inBlocks = new int[1];
        for (int i = 0; i < 60; i++) {
            ctx.waitTicks(2);
            serverRun(ctx, srv -> {
                ServerLevel lvl = (ServerLevel) srv.getPlayerList().getPlayers().get(0).level();
                for (var part : dragonOf(lvl).getSubEntities()) {
                    if (!lvl.noCollision(part.getBoundingBox())) {
                        inBlocks[0]++;
                        if (inBlocks[0] <= 3) { println(DRAGON_HP, "in blocks: " + part.name + " " + part.getBoundingBox()); }
                    }
                }
            });
            Vec3 p = ctx.computeOnClient(mc -> e[0].position());
            worstR = Math.max(worstR, Math.abs(Math.hypot(p.x - c.x, p.z - c.z) - radius));
            double a = Math.atan2(p.z - c.z, p.x - c.x);
            if (!Double.isNaN(last)) {
                swept += Math.atan2(Math.sin(a - last), Math.cos(a - last));
            }
            last = a;
        }
        println(DRAGON_HP, String.format(Locale.ROOT, "loop: swept %.0f deg, worst %.1f off radius %.1f, inside %s",
                Math.toDegrees(swept), worstR, radius, inside));
        expect(f, "its loop is centered in its area or at most 8 past it", inside, true);
        println(DRAGON_HP, "part samples inside blocks over 120 ticks of loop: " + inBlocks[0]);
        expect(f, "no part ever inside the arena's blocks", inBlocks[0], 0);
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

        // Real Terminator volleys aimed the way he aims: at the body his client DRAWS (1-1.5 blocks behind the server's
        // box), from an ordinary spot 6 toward the area's middle. Every arrow must count (the sim strikes the boxes of the
        // last ticks too; before that, 3 of 6 and 4 of 9 counted, and shots from inside the server box none).
        int u2 = units(), fired0 = (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired");
        for (int v = 0; v < VOLLEYS; v++) {
            Vec3 b = ctx.computeOnClient(mc -> e[0].getSubEntities()[2].getBoundingBox().getCenter());
            Vec3 in = new Vec3(c.x - b.x, 0, c.z - b.z);
            in = in.lengthSqr() < 1e-6 ? new Vec3(6, 0, 0) : in.normalize().scale(6);
            tp(ctx, b.x + in.x, b.y - 1.0, b.z + in.z, 0, 0);
            ctx.waitTicks(3); // the teleport reaches his client before the shot
            ctx.runOnClient(mc -> {
                Vec3 to = e[0].getSubEntities()[2].getBoundingBox().getCenter().subtract(mc.player.getEyePosition());
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTerminator", "use",
                        new Class<?>[]{net.minecraft.client.Minecraft.class, boolean.class}, new Object[]{mc, false});
            });
            ctx.waitTicks(10);
        }
        int fired = (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired") - fired0;
        int hits = units() - u2;
        println(DRAGON_HP, VOLLEYS + " Terminator volleys at the drawn body: " + fired + " arrows fired, " + hits + " counted");
        expect(f, VOLLEYS + " volleys fired", fired, 3 * VOLLEYS);
        expect(f, "every arrow aimed at the drawn body counted (one unit each)", hits, fired);
    }
}
