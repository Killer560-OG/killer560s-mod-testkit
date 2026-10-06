package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The sim must do NOTHING while connected to somebody else's server.
 *
 * <p>killer560 (2026-09-29): "be sure that SIM commands do not fill or work on any server besides being in the
 * sim."
 *
 * <p>This is the single safety boundary of the {@code roomsim} package. Everything in there writes the
 * player's position and pastes blocks by the million, which is correct in a world we own and is the exact
 * behaviour that gets an account banned on Hypixel. The package gates on {@code SimState.canAct}, which
 * requires a singleplayer server AND no connected server - but a gate is only worth what it has been tested
 * against, and reading the code found a real hole: {@code SimBuilder.build(code)} had no gate at all, and
 * since {@code getSingleplayerServer()} is null on a real server it read that as "no world yet" and called
 * {@code SimWorld.open}, which would have torn him out of Hypixel into a sim world.
 *
 * <p>So this connects to a REAL dedicated server and fires every sim command at it. Nothing may open a world,
 * nothing may set the sim active, and nothing may change a block. The arena is checked block for block before
 * and after, because "it did not crash" is not the claim being made.
 */
public class SimServerSafetyTests implements FabricClientGameTest {

    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";

    /** Its own coordinates, like every other scenario - a shared arena silently finds nothing to do. */
    private static final int X0 = -880;
    private static final int Z0 = -880;
    private static final int Y = 100;

    /** Every sim command, exactly as he would type it. */
    private static final String[] COMMANDS = {
        "simbuild flat",
        "simbuild code AAAAAAAA",
        "simbuild run",
        "simpuzzle blaze",
        "simpuzzle boulder",
        "simpuzzle reset",
        "simitem",
        "simloadout apply",
        "simscore paul",
    };

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String name = "88-sim-server-safety";
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.runOnClient(mc -> ModUnderTest.turnOff(
                "com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));

        Scenario.run(ctx, name,
                (server, scenario) -> TestMap.on(server)
                        // platform(), not floor(). floor() takes two CORNERS, so floor(x-8, y, z-8, 16, 16)
                        // asked for a slab from -888 to +16 on both axes - about 820,000 blocks - and froze
                        // the client for the deadline watcher to shoot.
                        .platform(X0, Y - 1, Z0, 12)
                        .catchFloor(Y - 40)
                        .survival()
                        // spawn(x, z, yaw) - no y. Passing Y + 0.5 here put him 900 blocks off the platform
                        // on the Z axis, and the fall is what GrimAC flagged as Simulation and GroundSpoof.
                        .spawn(X0 + 0.5, Z0 + 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    // Pretend to be Hypixel, because several features check the address. It changes nothing
                    // the server sees; it only makes the client believe it is somewhere real.
                    Scenario.labelServerAs("hypixel.net");
                    ctx.waitTicks(20);

                    List<String> problems = new ArrayList<>();

                    boolean[] activeBefore = new boolean[1];
                    ctx.runOnClient(mc ->
                            activeBefore[0] = (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive"));
                    if (activeBefore[0]) {
                        throw new AssertionError("the sim was already active before anything was typed, so "
                                + "this scenario would prove nothing");
                    }

                    // A fingerprint of the arena before, so "no block changed" is a measurement - taken only once
                    // the client HAS the arena: on 26.2 the first read came while chunks were still arriving
                    // (void_air), and the later read then "changed" with no command involved.
                    for (int wait = 0; wait < 200 && fingerprint(ctx).contains("void_air"); wait += 10) {
                        ctx.waitTicks(10);
                    }
                    String before = fingerprint(ctx);
                    String[] beforeBlocks = blocks(ctx);

                    // Every command must be REGISTERED on Fabric's client dispatcher, or this scenario would
                    // "pass" without having typed anything. Fabric API 0.155 has no api ClientCommandManager;
                    // the dispatcher comes from ClientCommandInternals (see CLAUDE.md). A missing class or a
                    // missing command is a FAILURE here, never a pass.
                    for (String command : COMMANDS) {
                        String root = command.split(" ")[0];
                        String[] registered = new String[1];
                        ctx.runOnClient(mc -> {
                            try {
                                Class<?> internals = Class.forName(
                                        "net.fabricmc.fabric.impl.command.client.ClientCommandInternals");
                                com.mojang.brigadier.CommandDispatcher<?> d =
                                        (com.mojang.brigadier.CommandDispatcher<?>) internals
                                                .getMethod("getActiveDispatcher").invoke(null);
                                registered[0] = d == null ? "no active dispatcher"
                                        : d.getRoot().getChild(root) == null ? "not registered" : "ok";
                            } catch (ReflectiveOperationException | RuntimeException e) {
                                registered[0] = "dispatcher lookup failed: " + e;
                            }
                        });
                        if (!"ok".equals(registered[0])) {
                            throw new AssertionError("/" + root + " cannot be typed (" + registered[0]
                                    + "), so this scenario would prove nothing about it");
                        }
                    }
                    long chatMark = LogTap.mark();
                    int[] chatLines = new int[1];
                    for (String command : COMMANDS) {
                        long commandMark = LogTap.mark();
                        // Typed as he would type it: sendCommand is intercepted by Fabric's client command API,
                        // which runs the mod's client command (sending it as chat would only test that the
                        // server does not know what /simbuild is).
                        ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
                        ctx.waitTicks(25);
                        List<String> said = new ArrayList<>();
                        for (String line : LogTap.since(commandMark)) {
                            if (line.contains("[CHAT]")) {
                                said.add(line);
                            }
                        }
                        chatLines[0] += said.size();
                        System.out.println("[" + name + "] /" + command + " ran; the client said "
                                + said.size() + " line(s): " + (said.isEmpty() ? "-" : said.get(0)));

                        boolean[] state = new boolean[2];
                        ctx.runOnClient(mc -> {
                            state[0] = (Boolean) ModUnderTest.staticCall(SIM_STATE, "isActive");
                            state[1] = mc.getSingleplayerServer() != null;
                        });
                        if (state[0]) {
                            problems.add("/" + command + " turned the sim ON while connected to a server");
                        }
                        if (state[1]) {
                            problems.add("/" + command + " opened a singleplayer world out from under the "
                                    + "server connection");
                        }
                    }

                    // Give anything that queued work a generous chance to run before looking.
                    ctx.waitTicks(60);
                    String after = fingerprint(ctx);
                    String[] afterBlocks = blocks(ctx);
                    // Compared POSITION by position, not by hash. The box holds the server's natural terrain
                    // round the platform, and vanilla's random tick turns grass_block to dirt (and back) on its
                    // own: on 2026-10-05 (nt-crash run B1) the one difference in 5,733 blocks was a single
                    // grass_block that had become dirt, with no command anywhere near it. That is the server's
                    // own tick, not a paste - a sim build changes thousands of blocks - so a grass/dirt swap at
                    // one position is named in the log and not counted. Any other change still fails.
                    List<String> changed = new ArrayList<>();
                    List<String> natural = new ArrayList<>();
                    for (int i = 0; i < Math.min(beforeBlocks.length, afterBlocks.length); i++) {
                        if (beforeBlocks[i].equals(afterBlocks[i])) {
                            continue;
                        }
                        String at = describe(i) + " " + beforeBlocks[i] + " -> " + afterBlocks[i];
                        if (isGrassTick(beforeBlocks[i], afterBlocks[i])) {
                            natural.add(at);
                        } else {
                            changed.add(at);
                        }
                    }
                    if (!natural.isEmpty()) {
                        scenario.log("ignored " + natural.size() + " grass/dirt random tick(s): " + natural);
                    }
                    if (beforeBlocks.length != afterBlocks.length || !changed.isEmpty()) {
                        problems.add("the world CHANGED around him - a sim command placed or removed blocks "
                                + "on a server: " + changed.size() + " position(s) " + changed.subList(0,
                                Math.min(10, changed.size())) + "; before " + before + ", after " + after);
                    }

                    boolean[] stillThere = new boolean[1];
                    ctx.runOnClient(mc -> stillThere[0] = mc.level != null && mc.player != null
                            && mc.getSingleplayerServer() == null);
                    if (!stillThere[0]) {
                        problems.add("he is no longer connected to the server after running the sim commands");
                    }

                    if (chatLines[0] == 0) {
                        problems.add("no command produced a single chat line, so none of them can be shown to "
                                + "have run (" + LogTap.since(chatMark).size() + " log line(s) captured)");
                    }
                    scenario.log("ran " + COMMANDS.length + " sim command(s) on a real server; "
                            + problems.size() + " problem(s)");
                    if (!problems.isEmpty()) {
                        StringBuilder sb = new StringBuilder("the sim acted on a server:");
                        for (String p : problems) {
                            sb.append("\n    ").append(p);
                        }
                        throw new AssertionError(sb.toString());
                    }
                    System.out.println("[" + name + "] PASS - every sim command refused, nothing was built, "
                            + "and he stayed on the server");
                });
    }

    /** Grass dying to dirt under a block, or dirt greening next to grass: vanilla's random tick, both ways. */
    private static boolean isGrassTick(String a, String b) {
        return (a.equals("grass_block") && b.equals("dirt")) || (a.equals("dirt") && b.equals("grass_block"));
    }

    /** The x,y,z of index i in {@link #blocks}' order (x, then z, then y innermost). */
    private static String describe(int i) {
        int ys = 13;   // Y - 4 .. Y + 8
        int zs = 21;   // Z0 - 10 .. Z0 + 10
        int y = Y - 4 + i % ys;
        int z = Z0 - 10 + (i / ys) % zs;
        int x = X0 - 10 + i / (ys * zs);
        return x + "," + y + "," + z;
    }

    /** Every block id in the box, in a fixed order, so two reads can be compared position by position. */
    private static String[] blocks(ClientGameTestContext ctx) {
        String[][] got = new String[1][];
        ctx.runOnClient(mc -> {
            List<String> out = new ArrayList<>();
            if (mc.level != null) {
                var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int x = X0 - 10; x <= X0 + 10; x++) {
                    for (int z = Z0 - 10; z <= Z0 + 10; z++) {
                        for (int y = Y - 4; y <= Y + 8; y++) {
                            pos.set(x, y, z);
                            out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(mc.level.getBlockState(pos).getBlock()).getPath());
                        }
                    }
                }
            }
            got[0] = out.toArray(new String[0]);
        });
        return got[0];
    }

    /**
     * A cheap but real fingerprint of the arena: every block id around him, joined.
     *
     * <p>Counting non-air would miss a swap, and checking a handful of positions would miss a paste that
     * landed slightly off. This reads the whole box.
     */
    private static String fingerprint(ClientGameTestContext ctx) {
        String[] got = new String[1];
        // Read off the CLIENT's own level. The test server runs in another process, so there is no
        // MinecraftServer to ask here - and the client's view is the right one anyway: anything the sim wrote
        // locally, which is the dangerous case, shows up there whether or not a server ever agreed to it.
        ctx.runOnClient(mc -> {
            StringBuilder sb = new StringBuilder();
            if (mc.level != null) {
                var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int x = X0 - 10; x <= X0 + 10; x++) {
                    for (int z = Z0 - 10; z <= Z0 + 10; z++) {
                        for (int y = Y - 4; y <= Y + 8; y++) {
                            pos.set(x, y, z);
                            sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(mc.level.getBlockState(pos).getBlock()).getPath()).append(',');
                        }
                    }
                }
            }
            // Counts per block, sorted, so a difference says WHAT changed, not just that a hash moved.
            java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
            for (String b : sb.toString().split(",")) {
                if (!b.isEmpty()) {
                    counts.merge(b, 1, Integer::sum);
                }
            }
            got[0] = Integer.toHexString(sb.toString().hashCode()) + " " + counts;
        });
        return got[0];
    }
}
