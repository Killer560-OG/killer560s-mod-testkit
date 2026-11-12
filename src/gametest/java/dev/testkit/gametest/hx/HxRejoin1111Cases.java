package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;

import java.lang.reflect.Field;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Leaving and rejoining a dungeon run (mod rejoin-1111, killer560 2026-11-11: "look into stuff not working if i leave and
 * rejoin mid dungeon. i want them to still work"). A rejoin is a new client world with the same sidebar server id and a
 * later Time Elapsed; the mod's {@code secrets.DungeonRun} then keeps every feature's per-run state. Here the world change
 * is a trip to the Nether and back (a new ClientLevel each way) with the sidebar cleared while away, as in limbo.
 * Each case proves the state was really there before (split armed, mimic counted, map generation) and after.
 */
final class HxRejoin1111Cases {

    private static final String RUN = "secrets.DungeonRun";
    private static final String DS = "secrets.DungeonState";
    private static final String MAP = "livemap.LiveMapFeature";
    private static final String SPLITS = "splittimers.SplitTimersFeature";
    private static final String SPLIT_CFG = "splittimers.SplitTimersConfig";
    private static final String SCORE = "scorecalc.ScoreCalculatorFeature";
    private static final String SCORE_CFG = "scorecalc.ScoreCalculatorConfig";
    private static final String SERVER_LINE = "§710/04/26 §8m12AB";

    private HxRejoin1111Cases() {
    }

    static void register(Session s) {
        s.test("1111-hx-rejoin-same", HxRejoin1111Cases::same);
        s.test("1111-hx-rejoin-other", HxRejoin1111Cases::other);
        s.test("1111-hx-rejoin-noserver", HxRejoin1111Cases::noServer);
    }

    /** The F7 dungeon sidebar with {@code serverLine} as its top line ({@code null}: no server line) at {@code time}. */
    private static void board(Session c, String serverLine, String time) {
        JsonObject r = c.hx().call("surface.sidebar", Hx.args("template", "dungeon", "floor", "F7", "time", time)).getAsJsonObject();
        JsonArray lines = r.getAsJsonArray("lines");
        JsonArray out = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).getAsString();
            if (i == 0) {
                if (serverLine == null) continue;
                l = serverLine;
            }
            out.add(l);
        }
        r.add("lines", out);
        c.hx().call("sidebar.set", r);
    }

    private static String dim(Session c) {
        return c.onClient(mc -> mc.level == null ? "" : mc.level.dimension().toString());
    }

    /** Leaves the world for the Nether with the sidebar gone, waits, then comes back to the overworld at x, y, z. */
    private static void awayAndBack(Session c, double x, double y, double z) {
        String me = self(c);
        String home = dim(c);
        c.server().command("execute in minecraft:the_nether run tp " + me + " 0.5 101 0.5");
        c.waitUntil("in the Nether", mc -> mc.level != null && !mc.level.dimension().toString().equals(home), 200);
        c.hx().sidebarClear(); // as on Hypixel, the sidebar changes after the world does
        c.ctx().waitTicks(40);
        c.server().command(String.format(java.util.Locale.ROOT, "execute in minecraft:overworld run tp %s %.1f %.1f %.1f", me, x, y, z));
        c.waitUntil("back in the overworld", mc -> mc.level != null && mc.level.dimension().toString().equals(home), 200);
    }

    private static long armedAt(Session c) {
        return c.onClient(mc -> {
            try {
                Object run = Mod.field(SPLITS, "run");
                Field f = run.getClass().getDeclaredField("armedAtMs");
                f.setAccessible(true);
                return f.getLong(run);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String describe(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(RUN, "describe"));
    }

    private static boolean flag(Session c, String cls, String name) {
        return c.onClient(mc -> (Boolean) Mod.field(cls, name));
    }

    /** Arms a run on the standard dungeon with a server line: splits armed, mimic counted, map generation read. */
    private static int[] armRun(Session c) {
        run(c, () -> Mod.staticCall(RUN, "resetForTest"));
        dungeon(c);
        board(c, SERVER_LINE, "01m 23s");
        c.waitUntil("DungeonRun has the run's server and time", mc -> {
            String d = (String) Mod.staticCall(RUN, "describe");
            return d.contains("server=m12AB") && d.contains("elapsed=83") && d.contains("live=true");
        }, 100);
        c.hx().chat("Starting in 1 second.");
        c.waitUntil("Split Timers armed", mc -> armedAtLocal() > 0L, 60);
        run(c, () -> Mod.setField(SCORE, "mimicKilled", true));
        c.ctx().waitTicks(5);
        int gen = c.onClient(mc -> (Integer) Mod.staticCall(MAP, "resetGeneration"));
        int runGen = c.onClient(mc -> (Integer) Mod.staticCall(RUN, "generation"));
        return new int[]{gen, runGen};
    }

    private static long armedAtLocal() {
        try {
            Object run = Mod.field(SPLITS, "run");
            Field f = run.getClass().getDeclaredField("armedAtMs");
            f.setAccessible(true);
            return f.getLong(run);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Settings settings(Session c) {
        return new Settings(c).with(SPLIT_CFG, "Enabled", true).with(SCORE_CFG, "Enabled", true);
    }

    /** A stone pad west of the boss arena (x, z < 0) to come back to, so Grim sees a normal landing. */
    private static void pad(Session c) {
        c.server().command("forceload add -70 -70 -50 -50");
        c.ctx().waitTicks(10);
        c.server().command("fill -64 99 -64 -56 99 -56 minecraft:stone");
    }

    private static void cleanup(Session c) {
        pad(c);
        c.server().command("tp @a -59.5 100 -59.5");
        hub(c);
        run(c, () -> Mod.staticCall(RUN, "resetForTest"));
    }

    /** Same server, later time: the run is resumed, nothing is reset, and the boss phase arms in the arena. */
    private static void same(Session c) {
        c.server().command("forceload add 40 40 70 70");
        c.ctx().waitTicks(10);
        c.server().command("fill 50 114 54 58 114 62 minecraft:stone");
        try (Settings set = settings(c)) {
            int[] before = armRun(c);
            long armed = armedAt(c);
            c.check(flag(c, SCORE, "mimicKilled"), "mimic flag did not take");
            awayAndBack(c, 54.5, 115, 58.5);
            c.ctx().waitTicks(20);
            boolean heldAway = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "holding"));
            board(c, SERVER_LINE, "01m 35s");
            c.waitUntil("DungeonRun resumed the run", mc -> (Boolean) Mod.staticCall(RUN, "resumed"), 100);
            c.ctx().waitTicks(20);
            int gen = c.onClient(mc -> (Integer) Mod.staticCall(MAP, "resetGeneration"));
            int runGen = c.onClient(mc -> (Integer) Mod.staticCall(RUN, "generation"));
            long armedAfter = armedAt(c);
            boolean mimic = flag(c, SCORE, "mimicKilled");
            boolean boss = c.onClient(mc -> (Boolean) Mod.staticCall(DS, "isBossPhaseActive"));
            c.check(heldAway, "DungeonRun was not holding the run while the sidebar was blank: " + describe(c));
            c.check(armedAfter == armed, "Split Timers lost the run: armed " + armed + " -> " + armedAfter);
            c.check(mimic, "Score Calculator forgot the mimic");
            c.check(gen == before[0], "map reset across the rejoin: generation " + before[0] + " -> " + gen);
            c.check(runGen == before[1], "DungeonRun dropped the run: " + before[1] + " -> " + runGen);
            c.check(boss, "boss phase did not arm after rejoining inside the boss arena: " + describe(c));
            c.note("held away, resumed; splits armed " + armed + " kept, mimic kept, map gen " + gen + ", boss phase on; " + describe(c));
        } finally {
            cleanup(c);
        }
    }

    /** A different server id after the trip: a new run, so every feature starts over. */
    private static void other(Session c) {
        try (Settings set = settings(c)) {
            pad(c);
            int[] before = armRun(c);
            awayAndBack(c, -59.5, 100, -59.5);
            board(c, "§710/04/26 §8m99ZZ", "00m 05s");
            c.waitUntil("DungeonRun dropped the held run", mc -> (Integer) Mod.staticCall(RUN, "generation") != before[1], 100);
            c.waitUntil("Split Timers reset", mc -> armedAtLocal() == 0L, 60);
            c.waitUntil("Score Calculator reset", mc -> !(Boolean) Mod.field(SCORE, "mimicKilled"), 60);
            int gen = c.onClient(mc -> (Integer) Mod.staticCall(MAP, "resetGeneration"));
            boolean resumed = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "resumed"));
            c.check(!resumed, "another server's run was taken as a rejoin: " + describe(c));
            c.check(gen != before[0], "map kept across a different run: generation " + gen);
            c.note("other server: dropped, splits/score/map reset (map gen " + before[0] + " -> " + gen + "); " + describe(c));
        } finally {
            cleanup(c);
        }
    }

    /** No server line at all (never placeable): the old behavior, every feature resets on the world change. */
    private static void noServer(Session c) {
        try (Settings set = settings(c)) {
            run(c, () -> Mod.staticCall(RUN, "resetForTest"));
            dungeon(c);
            board(c, null, "01m 23s");
            c.ctx().waitTicks(10);
            // dungeon() showed the template's server line first; forget it so this run never had one
            run(c, () -> Mod.staticCall(RUN, "resetForTest"));
            c.waitUntil("DungeonRun live with no server", mc -> {
                String d = (String) Mod.staticCall(RUN, "describe");
                return d.contains("server=null") && d.contains("live=true");
            }, 40);
            c.hx().chat("Starting in 1 second.");
            c.waitUntil("Split Timers armed", mc -> armedAtLocal() > 0L, 60);
            run(c, () -> Mod.setField(SCORE, "mimicKilled", true));
            pad(c);
            awayAndBack(c, -59.5, 100, -59.5);
            boolean held = c.onClient(mc -> (Boolean) Mod.staticCall(RUN, "holding"));
            board(c, null, "01m 30s");
            c.waitUntil("Split Timers reset", mc -> armedAtLocal() == 0L, 60);
            c.waitUntil("Score Calculator reset", mc -> !(Boolean) Mod.field(SCORE, "mimicKilled"), 60);
            c.check(!held, "a run with no server line was held: " + describe(c));
            c.note("no server line: not held, splits and score reset; " + describe(c));
        } finally {
            cleanup(c);
        }
    }
}
