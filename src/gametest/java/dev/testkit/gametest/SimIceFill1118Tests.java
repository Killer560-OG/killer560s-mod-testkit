package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Ice Fill on killer560's 2026-11-18 Hypixel run (mod icefillbugs-1118, incident table
 * killer560s-mod-logs/icefillbugs-1118.md). Every verdict is HYPIXEL's rule read from outside the mod, so the case fails
 * on a jar whose sim still judged every landing on its own:
 * <ul>
 *   <li>{@code 93-solve-icefill-1118-bunched}: his settings (Delay 1, Adaptive off) and a 200 ms server stall on a hop,
 *   six times. Hypixel judges where he stands once per server tick, so two hops handled in one of its ticks are one
 *   two-tile move ("Don't move diagonally! Bad!", pos ids 11 and 12 in one tick at 5:17:33 PM). Fails on any server tick
 *   with two or more sim landings, or on any section break.</li>
 *   <li>{@code 93-solve-icefill-1118-recovery}: section 2 broken by a forced mistake, then he is put where the old aim
 *   onto the section's first tile lands from the 1.27 eye but on another block from 1.54 (5:17:42 PM: the warp back landed
 *   on the section's second tile). Fails when a recovery etherwarp's look does not land on one block from 1.27, 1.54 and
 *   1.62 alike, when a tile of the section other than its first is that block, or when more than one recovery warp went
 *   while the first had landed where it was aimed (5:17:44 PM: a second, straight-down warp because a landing 0.05
 *   over the tile did not count as on it). With the sim casting from 1.54 the warp must also land where it was aimed.</li>
 *   <li>{@code 93-solve-icefill-1118-sweep}: every rotation of the room from three start spots (on the start, 0.05 over it
 *   as an etherwarp leaves him, and on the floor below it) at Delay 1 / Adaptive off: solved, no break, no bunched tick.
 *   Run with {@code -PsolveRepeat=12}.</li>
 * </ul>
 */
public final class SimIceFill1118Tests {

    private static final String P = "com.killer560.hub.roomsim.puzzles.SimIceFillPuzzle";
    private static final String SOLVER = "com.killer560.hub.puzzlesolvers.IceFillSolverFeature";
    private static final String TP = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String ABILITIES = "com.killer560.hub.roomsim.SimAbilities";
    private static final double[] EYES = {1.27, 1.54, 1.62};

    private SimIceFill1118Tests() {
    }

    // ------------------------------------------------------------------ Hypixel's per-tick judge, read from outside

    private static boolean hooked;
    private static volatile int lastLandings = -1;
    private static volatile int bunched;
    private static volatile int ticks;
    private static final List<String> BUNCHED = new ArrayList<>();

    /** Counts server ticks in which two or more sim landings happened. Client thread, once per case. */
    static void watchTicks() {
        lastLandings = -1;
        bunched = 0;
        ticks = 0;
        synchronized (BUNCHED) {
            BUNCHED.clear();
        }
        if (hooked) {
            return;
        }
        hooked = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int now;
            try {
                now = (Integer) ModUnderTest.staticCall(P, "landings", new Class<?>[]{}, new Object[]{});
            } catch (AssertionError e) {
                return;
            }
            ticks++;
            int before = lastLandings;
            lastLandings = now;
            if (before >= 0 && now - before >= 2) {
                bunched++;
                synchronized (BUNCHED) {
                    if (BUNCHED.size() < 5) {
                        BUNCHED.add((now - before) + " landings in server tick " + ticks);
                    }
                }
            }
        });
    }

    static String bunchedVerdict() {
        if (ticks == 0) {
            return "the server-tick watch never ran - nothing measured";
        }
        synchronized (BUNCHED) {
            return bunched == 0 ? null : bunched + " server tick(s) handled two or more hops - Hypixel reads each as one "
                    + "two-tile move (\"Don't move diagonally! Bad!\"): " + String.join("; ", BUNCHED);
        }
    }

    private static int sim(ClientGameTestContext ctx, String method) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(P, method, new Class<?>[]{}, new Object[]{}));
    }

    private static void serverTp(ClientGameTestContext ctx, double x, double y, double z, boolean judge) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    return;
                }
                sp.teleportTo(x, y, z);
                if (judge) {
                    try {
                        ModUnderTest.staticCall(P, "judgeNow",
                                new Class<?>[]{net.minecraft.server.level.ServerPlayer.class}, new Object[]{sp});
                    } catch (AssertionError old) {
                        ModUnderTest.staticCall(P, "onTeleport",
                                new Class<?>[]{net.minecraft.server.level.ServerPlayer.class}, new Object[]{sp});
                    }
                }
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Vec3> solverPath() {
        return (List<Vec3>) ModUnderTest.staticCall(SOLVER, "getCurrentPath", new Class<?>[]{}, new Object[]{});
    }

    /** Section {@code s}'s tiles (the solver's corners filled in one block at a time), as SimPuzzleSolveTests reads them. */
    static List<BlockPos> sectionTiles(int s) {
        List<Vec3> path = solverPath();
        List<Long> heights = new ArrayList<>();
        List<Vec3> mine = new ArrayList<>();
        for (Vec3 p : path) {
            long key = Math.round(p.y * 10);
            if (!heights.contains(key)) {
                heights.add(key);
            }
            if (heights.indexOf(key) == s) {
                mine.add(p);
            }
        }
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < mine.size(); i++) {
            Vec3 a = mine.get(i);
            Vec3 b = i + 1 < mine.size() ? mine.get(i + 1) : a;
            int steps = (int) Math.round(Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z)));
            for (int k = 0; k < Math.max(1, steps); k++) {
                double r = steps == 0 ? 0 : (double) k / steps;
                BlockPos tile = BlockPos.containing(a.x + (b.x - a.x) * r, a.y, a.z + (b.z - a.z) * r).below();
                if (out.isEmpty() || !out.get(out.size() - 1).equals(tile)) {
                    out.add(tile);
                }
            }
        }
        return out;
    }

    /** The block an etherwarp with this look lands on from {@code feet} + {@code eye}, or null. */
    static BlockPos lands(Minecraft mc, Vec3 feet, double eye, float yaw, float pitch) {
        Vec3 from = feet.add(0, eye, 0);
        Vec3 look = (Vec3) ModUnderTest.staticCall(TP, "getLook", new Class<?>[]{float.class, float.class},
                new Object[]{yaw, pitch});
        Vec3 to = from.add(look.scale(57));
        Object hit = ModUnderTest.staticCall(TP, "traverseVoxels", new Class<?>[]{net.minecraft.world.level.Level.class,
                double.class, double.class, double.class, double.class, double.class, double.class, boolean.class},
                new Object[]{mc.level, from.x, from.y, from.z, to.x, to.y, to.z, true});
        try {
            boolean ok = (Boolean) hit.getClass().getMethod("succeeded").invoke(hit);
            return ok ? (BlockPos) hit.getClass().getMethod("pos").invoke(hit) : null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The aim the jar before icefillbugs-1118 used onto {@code tile} (TeleportUtils from the 1.27 eye), or null. */
    static float[] oldAim(Vec3 feet, BlockPos tile) {
        Object r = ModUnderTest.staticCall(TP, "getEtherwarpDirection", new Class<?>[]{Vec3.class, BlockPos.class,
                double.class}, new Object[]{feet.add(0, 1.27, 0), tile, 57.0});
        if (r == null) {
            return null;
        }
        try {
            return new float[]{(Float) r.getClass().getMethod("yaw").invoke(r), (Float) r.getClass().getMethod("pitch")
                    .invoke(r)};
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Whether some face point of {@code tile} gives a look that lands on it from every eye height. */
    static boolean firmAimExists(Minecraft mc, Vec3 feet, BlockPos tile) {
        double[] g = {0.5, 0.3, 0.7, 0.15, 0.85};
        for (Direction d : Direction.values()) {
            for (double u : g) {
                for (double v : g) {
                    Vec3 c = Vec3.atCenterOf(tile);
                    Vec3 p = switch (d.getAxis()) {
                        case X -> new Vec3(c.x + d.getStepX() * 0.5, tile.getY() + u, tile.getZ() + v);
                        case Y -> new Vec3(tile.getX() + u, c.y + d.getStepY() * 0.5, tile.getZ() + v);
                        case Z -> new Vec3(tile.getX() + u, tile.getY() + v, c.z + d.getStepZ() * 0.5);
                    };
                    Vec3 eye = feet.add(0, 1.27, 0);
                    Vec3 dv = p.subtract(eye);
                    float yaw = (float) Math.toDegrees(Math.atan2(-dv.x, dv.z));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dv.y, Math.sqrt(dv.x * dv.x + dv.z * dv.z)));
                    boolean all = true;
                    for (double e : EYES) {
                        if (!tile.equals(lands(mc, feet, e, yaw, pitch))) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 93-solve-icefill-1118-bunched

    public static final class Bunched extends SimPuzzleSolveTests.Base {
        public Bunched() {
            super(new SimPuzzleSolveTests.Spec("icefill-1118-bunched", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    false, 90, SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                int breaks0 = -1;
                int spikes;
                int waitUntil = 40;
                boolean armed;

                @Override
                void configure(Object auto, Object cheat) {
                    ModUnderTest.set(auto, "setIceFillAdaptive", false);
                    ModUnderTest.set(auto, "setIceFillDelayTicks", 1);
                }

                @Override
                void beforeAutoOn(ClientGameTestContext ctx) {
                    ctx.runOnClient(mc -> watchTicks());
                    breaks0 = sim(ctx, "breaks");
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    if (solvedAt >= 0 || spikes >= 6) {
                        return;
                    }
                    if (armed) {
                        if (dev.testkit.harness.LagSpike.releasedAt() != 0L) {
                            armed = false;
                            spikes++;
                            println(name, String.format("t=%.1fs stall %d released, %d packet(s) held", t / 20.0, spikes,
                                    dev.testkit.harness.LagSpike.held()));
                            waitUntil = t + 30;
                        }
                        return;
                    }
                    if (t >= waitUntil && hopping(ctx)) {
                        dev.testkit.harness.LagSpike.armOnNextUse(200);
                        armed = true;
                    }
                }

                private boolean hopping(ClientGameTestContext ctx) {
                    return ctx.computeOnClient(mc -> mc.player != null && mc.player.getY() > 60
                            && !(Boolean) ModUnderTest.staticCall(P, "isBroken", new Class<?>[]{}, new Object[]{}));
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    dev.testkit.harness.LagSpike.disarm();
                    if (spikes < 3) {
                        return "only " + spikes + " server stall(s) landed on a hop - nothing measured";
                    }
                    int broke = sim(ctx, "breaks") - breaks0;
                    String b = bunchedVerdict();
                    if (b != null) {
                        return b;
                    }
                    return broke == 0 ? null : "the sim broke a section " + broke + " time(s)";
                }

                @Override
                String summary() {
                    return spikes + " 200 ms stall(s) on a hop, " + bunched + " bunched server tick(s) in " + ticks;
                }
            };
        }
    }

    // ------------------------------------------------------------------ 93-solve-icefill-1118-recovery

    public static final class Recovery extends SimPuzzleSolveTests.Base {
        public Recovery() {
            super(new SimPuzzleSolveTests.Spec("icefill-1118-recovery", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    false, 120, SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                Vec3 spot;
                BlockPos entry;
                List<BlockPos> tiles = List.of();
                boolean forced;
                int brokenAt = -1;
                int breaks0;
                int regenAt = -1;
                boolean done;
                boolean eyeSet;
                final List<String> problems = new ArrayList<>();
                final List<String> warps = new ArrayList<>();
                int recoveryWarps;
                int aimedLandings;
                volatile boolean capture;
                final List<Object[]> uses = new ArrayList<>();
                java.util.function.Consumer<Packet<?>> prevTap;

                @Override
                void configure(Object auto, Object cheat) {
                    ModUnderTest.set(auto, "setIceFillAdaptive", false);
                    ModUnderTest.set(auto, "setIceFillDelayTicks", 1);
                }

                @Override
                void beforeAutoOn(ClientGameTestContext ctx) {
                    ctx.runOnClient(mc -> watchTicks());
                    breaks0 = sim(ctx, "breaks");
                    // The adversarial spot under section 2, picked while its ice is all there.
                    Object[] pick = ctx.computeOnClient(mc -> pickSpot(mc));
                    if (pick == null) {
                        problems.add("no spot under section 2 where the old aim lands elsewhere from 1.54 - not replayable");
                        return;
                    }
                    spot = (Vec3) pick[0];
                    entry = (BlockPos) pick[1];
                    println(name, "replay spot " + spot + " under section 2's first tile " + entry.toShortString()
                            + ": old aim lands on " + pick[2] + " from 1.54");
                    prevTap = dev.testkit.harness.PacketTrace.tap;
                    dev.testkit.harness.PacketTrace.tap = p -> {
                        if (prevTap != null) {
                            prevTap.accept(p);
                        }
                        if (capture && p instanceof ServerboundUseItemPacket use) {
                            Minecraft mc = Minecraft.getInstance();
                            synchronized (uses) {
                                uses.add(new Object[]{mc.player.position(), mc.player.isShiftKeyDown()
                                        || mc.options.keyShift.isDown(),
                                        dev.testkit.compat.McPackets.useItemYRot(use),
                                        dev.testkit.compat.McPackets.useItemXRot(use)});
                            }
                        }
                    };
                }

                private Object[] pickSpot(Minecraft mc) {
                    tiles = sectionTiles(1);
                    if (tiles.isEmpty()) {
                        return null;
                    }
                    BlockPos e = tiles.get(0);
                    for (int dy = 2; dy <= 8; dy++) {
                        for (int r = 1; r <= 5; r++) {
                            for (int dx = -r; dx <= r; dx++) {
                                for (int dz = -r; dz <= r; dz++) {
                                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                                        continue;
                                    }
                                    BlockPos feet = e.offset(dx, -dy + 1, dz);
                                    if (!mc.level.getBlockState(feet.below()).isSolidRender()
                                            || !mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                                            || !mc.level.getBlockState(feet.above()).getCollisionShape(mc.level,
                                            feet.above()).isEmpty()) {
                                        continue;
                                    }
                                    Vec3 f = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                                    float[] aim = oldAim(f, e);
                                    if (aim == null || !e.equals(lands(mc, f, 1.27, aim[0], aim[1]))) {
                                        continue;
                                    }
                                    BlockPos other = lands(mc, f, 1.54, aim[0], aim[1]);
                                    if (other == null || other.equals(e) || !tiles.contains(other)) {
                                        continue;
                                    }
                                    if (!firmAimExists(mc, f, e)) {
                                        continue;
                                    }
                                    return new Object[]{f, e, other.toShortString()};
                                }
                            }
                        }
                    }
                    return null;
                }

                @Override
                void tick(ClientGameTestContext ctx, int t, int solvedAt, boolean container) {
                    if (spot == null || done) {
                        return;
                    }
                    if (!forced) {
                        // Half way across section 2: back onto the used tile before (the classic mistake).
                        double[] back = ctx.computeOnClient(mc -> {
                            if ((Integer) ModUnderTest.staticCall(P, "activeSection", new Class<?>[]{},
                                    new Object[]{}) != 1 || !mc.player.onGround()) {
                                return null;
                            }
                            int i = tiles.indexOf(mc.player.blockPosition().below());
                            if (i < Math.max(2, tiles.size() / 2) || i >= tiles.size() - 1) {
                                return null;
                            }
                            BlockPos prev = tiles.get(i - 1);
                            return new double[]{prev.getX() + 0.5, prev.getY() + 1, prev.getZ() + 0.5};
                        });
                        if (back != null) {
                            forced = true;
                            println(name, String.format("t=%.1fs forcing a break in section 2", t / 20.0));
                            serverTp(ctx, back[0], back[1], back[2], true);
                        }
                        return;
                    }
                    if (brokenAt < 0) {
                        if (sim(ctx, "breaks") > breaks0) {
                            brokenAt = t;
                            // Where Hypixel's reset put him in the replayed run: below the section, on a spot the old
                            // aim does not hold from. The sim casts from 1.54 from now on, as Hypixel did at 5:17:42.
                            serverTp(ctx, spot.x, spot.y, spot.z, false);
                            ctx.runOnClient(mc -> {
                                try {
                                    Class.forName(ABILITIES).getField("etherwarpEyeForTest").setDouble(null, 1.54);
                                    eyeSet = true;
                                } catch (ReflectiveOperationException old) {
                                    eyeSet = false;
                                }
                            });
                            capture = true;
                            println(name, String.format("t=%.1fs section 2 broke; put on the replay spot (sim eye 1.54: %s)",
                                    t / 20.0, eyeSet));
                        } else if (t > 2400) {
                            problems.add("the forced mistake never broke section 2");
                            done = true;
                        }
                        return;
                    }
                    if (regenAt < 0) {
                        if (!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(P, "isBroken",
                                new Class<?>[]{}, new Object[]{}))) {
                            regenAt = t;
                        }
                        return;
                    }
                    // Judge every sneaking use since the break until he is back hopping (a plain use), or 15 s.
                    List<Object[]> got;
                    synchronized (uses) {
                        got = new ArrayList<>(uses);
                    }
                    boolean hopSeen = false;
                    for (Object[] u : got) {
                        if (!(Boolean) u[1]) {
                            hopSeen = true;
                        }
                    }
                    if (!hopSeen && t - regenAt < 300) {
                        return;
                    }
                    capture = false;
                    done = true;
                    ctx.runOnClient(mc -> {
                        resetEye();
                        for (Object[] u : got) {
                            if (!(Boolean) u[1]) {
                                break;
                            }
                            recoveryWarps++;
                            Vec3 feet = (Vec3) u[0];
                            float yaw = (Float) u[2];
                            float pitch = (Float) u[3];
                            List<BlockPos> hits = new ArrayList<>();
                            for (double e : EYES) {
                                hits.add(lands(mc, feet, e, yaw, pitch));
                            }
                            String line = String.format("recovery warp %d from (%.2f, %.2f, %.2f) yaw %.2f pitch %.2f lands "
                                    + "on %s / %s / %s from 1.27 / 1.54 / 1.62", recoveryWarps, feet.x, feet.y, feet.z, yaw,
                                    pitch, s(hits.get(0)), s(hits.get(1)), s(hits.get(2)));
                            warps.add(line);
                            println(name, line);
                            BlockPos h = hits.get(0);
                            if (h == null || !h.equals(hits.get(1)) || !h.equals(hits.get(2))) {
                                problems.add("recovery warp " + recoveryWarps + " lands on different blocks by eye height ("
                                        + s(hits.get(0)) + " / " + s(hits.get(1)) + " / " + s(hits.get(2)) + ")");
                            } else if (tiles.contains(h) && !h.equals(entry)) {
                                problems.add("recovery warp " + recoveryWarps + " aims at " + s(h)
                                        + ", a tile of section 2 that is not its first");
                            }
                            if (h != null && BlockPos.containing(feet.x, feet.y - 0.1, feet.z).equals(h)) {
                                aimedLandings++;
                            }
                        }
                    });
                    if (recoveryWarps == 0) {
                        problems.add("no recovery etherwarp went after section 2 regenerated - nothing measured");
                    }
                    if (aimedLandings > 0) {
                        problems.add(aimedLandings + " recovery warp(s) went from the block a previous warp had already "
                                + "landed him on (a landing 0.05 over the tile not counted as on it)");
                    }
                }

                private static String s(BlockPos p) {
                    return p == null ? "nothing" : p.toShortString();
                }

                private void resetEye() {
                    try {
                        Class.forName(ABILITIES).getField("etherwarpEyeForTest").setDouble(null, Double.NaN);
                    } catch (ReflectiveOperationException ignored) {
                        // a jar without the hook
                    }
                }

                @Override
                boolean satisfied() {
                    return done || spot == null;
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    ctx.runOnClient(mc -> resetEye());
                    if (prevTap != null || dev.testkit.harness.PacketTrace.tap != null) {
                        dev.testkit.harness.PacketTrace.tap = prevTap;
                    }
                    if (!done && spot != null) {
                        problems.add("the replay did not finish (forced " + forced + ", broke " + (brokenAt >= 0)
                                + ", regenerated " + (regenAt >= 0) + ")");
                    }
                    return problems.isEmpty() ? null : String.join("; ", problems);
                }

                @Override
                String summary() {
                    return recoveryWarps + " recovery warp(s) judged from three eye heights" + (eyeSet
                            ? ", sim cast from 1.54" : ", sim eye fixed (old jar)");
                }
            };
        }
    }

    // ------------------------------------------------------------------ 93-solve-icefill-1118-sweep

    public static final class Sweep extends SimPuzzleSolveTests.Base {
        public Sweep() {
            super(new SimPuzzleSolveTests.Spec("icefill-1118-sweep", "Ice Fill", "setAutoIceFillEnabled",
                    "isAutoIceFillEnabled", "IceFillSolverConfig", "SimIceFillPuzzle", SimPuzzleSolveTests.AOTV_SLOT,
                    false, 90, SimPuzzleSolveTests.Approach.NONE, "IceFillSolverFeature.getCurrentPath"));
        }

        @Override
        int rotation() {
            return (runNo - 1) % 4;
        }

        /** 0: where the room puts him, 1: 0.05 over the start (an etherwarp's landing), 2: on the floor below it. */
        private int startSpot() {
            return ((runNo - 1) / 4) % 3;
        }

        @Override
        SolveExtra extra() {
            return new SolveExtra() {
                int breaks0;
                int landings0;
                String where = "the room's own start";

                @Override
                void configure(Object auto, Object cheat) {
                    ModUnderTest.set(auto, "setIceFillAdaptive", false);
                    ModUnderTest.set(auto, "setIceFillDelayTicks", 1);
                }

                @Override
                void beforeAutoOn(ClientGameTestContext ctx) {
                    ctx.runOnClient(mc -> watchTicks());
                    breaks0 = sim(ctx, "breaks");
                    landings0 = sim(ctx, "landings");
                    int k = startSpot();
                    if (k == 0) {
                        println(name, "rotation " + rotation() + ", start: " + where);
                        return;
                    }
                    double[] at = ctx.computeOnClient(mc -> {
                        List<Vec3> path = solverPath();
                        if (path.isEmpty()) {
                            return null;
                        }
                        Vec3 s = path.get(0);
                        if (k == 1) {
                            return new double[]{s.x, s.y - 0.1 + 0.05, s.z};
                        }
                        BlockPos first = BlockPos.containing(s).below();
                        for (int dy = 2; dy <= 8; dy++) {
                            for (int dx = -3; dx <= 3; dx++) {
                                for (int dz = -3; dz <= 3; dz++) {
                                    BlockPos feet = first.offset(dx, -dy + 1, dz);
                                    if (mc.level.getBlockState(feet.below()).isSolidRender()
                                            && mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                                            && mc.level.getBlockState(feet.above()).getCollisionShape(mc.level,
                                            feet.above()).isEmpty()) {
                                        return new double[]{feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5};
                                    }
                                }
                            }
                        }
                        return null;
                    });
                    if (at == null) {
                        println(name, "rotation " + rotation() + ", no spot for start " + k + " - the room's own start");
                        return;
                    }
                    where = (k == 1 ? "0.05 over the start " : "the floor below the start ") + String.format(
                            "(%.2f, %.2f, %.2f)", at[0], at[1], at[2]);
                    serverTp(ctx, at[0], at[1], at[2], false);
                    for (int i = 0; i < 10; i++) {
                        ctx.waitTick();
                    }
                    println(name, "rotation " + rotation() + ", start: " + where);
                }

                @Override
                String verdict(ClientGameTestContext ctx, int solvedAt) {
                    int hops = sim(ctx, "landings") - landings0;
                    if (hops < 20) {
                        return "only " + hops + " sim landing(s) - the auto did not play";
                    }
                    String b = bunchedVerdict();
                    if (b != null) {
                        return b;
                    }
                    int broke = sim(ctx, "breaks") - breaks0;
                    return broke == 0 ? null : "the sim broke a section " + broke + " time(s)";
                }

                @Override
                String summary() {
                    return "rotation " + rotation() + ", start " + where + ", " + bunched + " bunched tick(s)";
                }
            };
        }
    }

    private static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
