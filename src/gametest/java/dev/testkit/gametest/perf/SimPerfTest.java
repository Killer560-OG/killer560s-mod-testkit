package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 95-perf-sim (fps-sim-1015, ~4 min, only when named): what the dungeon sim itself costs, and what the mod costs at
 * startup and in heap. Two parts.
 *
 * <p><b>Startup</b>, measured before anything else touches the client: JVM uptime when the first gametest runs (the
 * title screen is up), loaded class count, the heap left after a full GC, and the heap the room library's block arrays
 * hold. Reported, not asserted, except where a number is the mod's own and deterministic (the library's arrays).
 *
 * <p><b>Sim</b>: {@link PerfBench}'s seeded sim F7 with every mod switch OFF, so what remains is the sim's own client
 * ticks and its integrated-server handlers (secret/puzzle judges, mobs, abilities). Per roomsim probe key: us and bytes
 * per tick, client and server thread; plus the integrated server thread's whole CPU and allocation per tick (vanilla
 * entity ticking included). Each budget below is asserted only after its key was seen running (a key that never ran
 * fails as "nothing was measured", never as a pass).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimPerfTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-sim";
    private static final int TICKS = 600;

    /** Client-thread bytes per call allowed for the sim's per-tick handlers (main measured these, 2026-10-15). */
    private static final Map<String, Integer> CLIENT_BYTES = new LinkedHashMap<>();
    /** Server-thread bytes per call allowed for the sim's integrated-server tick handlers. */
    private static final Map<String, Integer> SERVER_BYTES = new LinkedHashMap<>();

    static {
        // mod main 69505e45 measured 10,485 and 5,913 B per call (2026-10-15): both rebuilt every line every tick, and
        // the sidebar's room name decoded the whole floor's map code each time.
        CLIENT_BYTES.put("roomsim.SimTabList ClientTickEvents.StartTick", 600);
        CLIENT_BYTES.put("roomsim.SimSidebar ClientTickEvents.StartTick", 600);
    }

    /** The room library's block arrays, MB: main held 25.7 MB of shorts; one byte a block where the palette allows. */
    private static final double LIBRARY_BUDGET_MB = 20.0;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        // ---- startup: first thing, before PerfBench builds anything ----
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        int classes = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        ctx.waitFor(mc -> (Boolean) Mod.staticCall("roomsim.RoomLibrary", "isReady")
                || !Boolean.TRUE.equals(Mod.field("roomsim.RoomLibrary", "loading")), 2400);
        long heap = heapAfterGc();
        report.add(String.format(Locale.ROOT, "startup: first gametest at JVM uptime %d ms, %d classes loaded, heap after GC %.1f MB",
                uptime, classes, heap / 1048576.0));
        report.add("startup: room library loaded at title screen: " + ctx.computeOnClient(mc ->
                Mod.staticCall("roomsim.RoomLibrary", "isReady")) + ", " + libraryLine());

        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off: run with -PperfProbe and a dev jar");
            }
            String lib = libraryLine();
            report.add("library after the sim scene: " + lib);
            double libMb = libraryMb();
            if (libMb > LIBRARY_BUDGET_MB) {
                failures.add(String.format(Locale.ROOT, "room library block arrays %.1f MB (budget %.1f MB)", libMb,
                        LIBRARY_BUDGET_MB));
            }
            // What the build put in the world, as one number: the same seeded floor must hash the same on any jar
            // whose paste only got cheaper (compare the line between runs; fps-sim-1015 changed the paste loop).
            // On the TEST thread, not the client's: the walk takes a moment, and a client thread busy that long reads
            // as "Not Responding" and run-scenario's freeze watcher closes it. The built floor no longer changes.
            report.add("floor block hash: " + floorHash(net.minecraft.client.Minecraft.getInstance()));
            failures.addAll(rotationChecks()); // pure arithmetic, test thread
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.waitTicks(60);

            Thread server = serverThread();
            com.sun.management.ThreadMXBean tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long sCpu0 = server == null ? 0 : tmx.getThreadCpuTime(server.threadId());
            long sB0 = server == null ? 0 : tmx.getThreadAllocatedBytes(server.threadId());
            long gc0 = gcMillis();
            Phase p = PerfBench.measure(ctx, "sim F7, every switch OFF", TICKS, cal);
            long sCpu1 = server == null ? 0 : tmx.getThreadCpuTime(server.threadId());
            long sB1 = server == null ? 0 : tmx.getThreadAllocatedBytes(server.threadId());
            report.add(p.line());
            report.add(String.format(Locale.ROOT, "integrated server thread: %.1f us CPU/tick, %.1f KB/tick allocated; GC %d ms over the phase",
                    (sCpu1 - sCpu0) / 1e3 / p.ticks(), (sB1 - sB0) / 1024.0 / p.ticks(), gcMillis() - gc0));

            double simClientUs = 0;
            double simClientB = 0;
            double simServerUs = 0;
            double simServerB = 0;
            List<Key> sim = new ArrayList<>();
            for (Key k : p.keys().values()) {
                if (k.key().startsWith("roomsim.")) {
                    sim.add(k);
                }
            }
            sim.sort((a, b) -> Double.compare(b.ns(), a.ns()));
            for (Key k : sim) {
                boolean srv = k.kind().equals("server");
                double us = k.ns() / 1e3 / p.ticks();
                double b = k.bytes() / p.ticks();
                if (srv) {
                    simServerUs += us;
                    simServerB += b;
                } else if (!k.kind().equals("frame")) {
                    simClientUs += us;
                    simClientB += b;
                }
                report.add(String.format(Locale.ROOT, "  %-80s %-6s %7d calls %8.2f us/tick %9.0f B/tick %8.0f B/call",
                        k.key(), k.kind(), k.calls(), us, b, k.bytes() / Math.max(1, k.calls())));
            }
            report.add(String.format(Locale.ROOT, "roomsim total: client %.1f us/tick %.0f B/tick, server %.1f us/tick %.0f B/tick",
                    simClientUs, simClientB, simServerUs, simServerB));
            check(p, CLIENT_BYTES, false, report, failures);
            check(p, SERVER_BYTES, true, report, failures);
            report.add("heap after GC in the sim: " + String.format(Locale.ROOT, "%.1f MB", heapAfterGc() / 1048576.0));
        } finally {
            try {
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Files.createDirectories(PerfBench.outDir());
                Files.write(PerfBench.outDir().resolve("perf-sim.txt"), report);
            } catch (Exception ignored) {
                // the log has it
            }
            PerfBench.close(ctx);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures.size() + " failure(s):\n  " + String.join("\n  ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    private static void check(Phase p, Map<String, Integer> budgets, boolean server, List<String> report, List<String> failures) {
        for (Map.Entry<String, Integer> b : budgets.entrySet()) {
            Key k = null;
            for (Key c : p.keys().values()) {
                if (c.key().startsWith(b.getKey()) && c.kind().equals("server") == server) {
                    k = c;
                }
            }
            if (k == null || k.calls() < p.ticks() / 2) {
                failures.add(b.getKey() + (server ? " (server)" : "") + " did not run every tick ("
                        + (k == null ? 0 : k.calls()) + " calls in " + p.ticks() + " ticks) - nothing was measured");
                continue;
            }
            double perCall = k.bytes() / k.calls();
            String line = String.format(Locale.ROOT, "%-70s %6.0f B/call (budget %d)", k.key(), perCall, b.getValue());
            report.add(line);
            if (perCall > b.getValue()) {
                failures.add(line);
            }
        }
    }

    private static double libraryMb() {
        String line = libraryLine();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\d.]+) MB of block arrays").matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.MAX_VALUE;
    }

    /** Bytes held by the room library's block arrays, and how many rooms. Any thread (the map is swapped in whole). */
    private static String libraryLine() {
        Object rooms = Mod.field("roomsim.RoomLibrary", "ROOMS");
        long bytes = 0;
        int n = 0;
        int resident = 0;
        synchronized (Mod.cls("roomsim.RoomLibrary")) {
            for (Object r : ((Map<?, ?>) rooms).values()) {
                n++;
                Object blocks;
                try {
                    blocks = Mod.field(r, "blocks");
                } catch (AssertionError e) {
                    // mod fps-sim-1015 on: one byte per block where the palette allows (Room.blockBytes)
                    bytes += (Long) Mod.call(r, "blockBytes");
                    resident++;
                    continue;
                }
                if (blocks instanceof short[] s) {
                    bytes += 2L * s.length;
                    resident++;
                } else if (blocks instanceof byte[] a) {
                    bytes += a.length;
                    resident++;
                }
            }
        }
        return String.format(Locale.ROOT, "%d room(s), %d with blocks in memory, %.1f MB of block arrays", n, resident,
                bytes / 1048576.0);
    }

    /**
     * Every block state id in the last build's area, full height, hashed in a fixed order (chunk by chunk, section by
     * section; an all-air section hashes as one constant). Read only, from the test thread.
     */
    private static String floorHash(net.minecraft.client.Minecraft mc) {
        int[] b = (int[]) Mod.staticCall("roomsim.SimBuildQueue", "touchedBounds");
        var server = mc.getSingleplayerServer();
        if (b == null || server == null) {
            return "no build bounds";
        }
        var level = server.overworld();
        long h = 1;
        long nonAir = 0;
        var registry = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY;
        for (int cx = b[0] >> 4; cx <= b[2] >> 4; cx++) {
            for (int cz = b[1] >> 4; cz <= b[3] >> 4; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    h = h * 31 + 7;
                    continue;
                }
                for (var section : chunk.getSections()) {
                    if (section == null || section.hasOnlyAir()) {
                        h = h * 31 + 1;
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                var state = section.getBlockState(x, y, z);
                                if (!state.isAir()) {
                                    nonAir++;
                                }
                                h = h * 1_000_003L + registry.getId(state);
                            }
                        }
                    }
                }
            }
        }
        return String.format(Locale.ROOT, "%016x over x %d..%d z %d..%d, %d non-air", h, b[0], b[2], b[1], b[3], nonAir);
    }

    /** The paste loop's array-free rotation (fps-sim-1015) against rotateLocal, every cell of the room sizes in use. */
    private static List<String> rotationChecks() {
        List<String> out = new ArrayList<>();
        Class<?> c;
        try {
            c = Mod.cls("roomsim.RoomPlacer");
            c.getDeclaredMethod("rotatedX", int.class, int.class, int.class, int.class, int.class);
        } catch (Throwable t) {
            return out; // an older jar: nothing to compare
        }
        for (int[] size : new int[][]{{33, 33}, {65, 33}, {33, 129}, {65, 65}}) {
            for (int deg = 0; deg < 360; deg += 90) {
                for (int x = 0; x < size[0]; x++) {
                    for (int z = 0; z < size[1]; z++) {
                        int[] r = (int[]) Mod.staticCall("roomsim.RoomPlacer", "rotateLocal", x, z, size[0], size[1], deg);
                        int rx = (Integer) Mod.staticCall("roomsim.RoomPlacer", "rotatedX", x, z, size[0], size[1], deg);
                        int rz = (Integer) Mod.staticCall("roomsim.RoomPlacer", "rotatedZ", x, z, size[0], size[1], deg);
                        if (r[0] != rx || r[1] != rz) {
                            out.add("rotatedX/Z disagree with rotateLocal at " + x + "," + z + " size " + size[0] + "x"
                                    + size[1] + " " + deg + " deg");
                            return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static long heapAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        MemoryUsage u = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return u.getUsed();
    }

    private static long gcMillis() {
        long t = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            t += Math.max(0, gc.getCollectionTime());
        }
        return t;
    }

    private static Thread serverThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Server thread".equals(t.getName())) {
                return t;
            }
        }
        return null;
    }
}
