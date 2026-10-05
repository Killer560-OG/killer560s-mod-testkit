package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.gametest.ui.JarIndex;
import dev.testkit.harness.FrameClock;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 95-fps-bench: what the mod costs per frame and per tick, inside a generated F7 sim floor (so DungeonState says
 * "in a dungeon" and the map, secret waypoints, ESP, solvers and dungeon HUDs all run as on Hypixel).
 *
 * <p>Two configurations, alternated ON/OFF/ON/OFF... so drift (JIT, chunk loading, GC) lands on both equally:
 * <ul>
 *   <li><b>ON</b> - the mod's defaults plus {@link #ON} switched on: the HUDs, overlays, ESP, map, solvers and render
 *       features a dungeon player runs. No automation, nothing that moves the player or sends a packet.</li>
 *   <li><b>OFF</b> - every config in the jar with {@code getInstance/isEnabled/setEnabled(boolean)} switched off
 *       (found by listing the jar, so it is not a list kept here). The mod is still loaded; what remains in OFF is
 *       the cost of handlers that run while their feature is off.</li>
 * </ul>
 * Frame time is {@link FrameClock}: CPU time of {@code GameRenderer.extract + render} per frame and of
 * {@code Minecraft.tick} per tick, swap and vsync excluded. In a gametest client every frame carries exactly one
 * tick; in his game a tick comes every 50 ms, so tick and frame numbers are reported apart.
 *
 * <p>After the timed rounds, one ON and one OFF phase are run again under a JFR recording (1 ms execution sampling,
 * allocation sampling) and dumped beside the report as {@code fps-on.jfr} / {@code fps-off.jfr}, for attributing
 * the difference to code. Timed rounds run without JFR so its overhead is not in the numbers.
 *
 * <p>Only runs when named ({@code -Pscenario=95-fps}); it takes several minutes and measures, it does not test.
 * Frames per phase: {@code -Dtestkit.bench.frames} (default 1200), rounds: {@code testkit.bench.rounds} (3).
 */
public class FpsBenchTest implements FabricClientGameTest {

    private static final String NAME = "95-fps-bench";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";

    /** {config class, boolean setter[, getter]} switched ON for the ON phases. Missing ones are reported, not fatal. */
    static final String[][] ON = {
            // client visuals (the 360-ui-visuals set)
            {"trail.TrailConfig", "setEnabled"}, {"realtime.RealTimeConfig", "setEnabled"},
            {"lagdisplay.LagDisplayConfig", "setEnabled"}, {"position.PositionConfig", "setEnabled"},
            {"trajectories.TrajectoriesConfig", "setEnabled"}, {"helditem.HeldItemConfig", "setEnabled"},
            {"nofire.NoFireConfig", "setEnabled"}, {"diorite.DioriteGlassConfig", "setEnabled"},
            {"armourdye.ArmourDyeConfig", "setEnabled"}, {"itemrarity.ItemRarityConfig", "setEnabled"},
            {"enchantcolors.EnchantColorsConfig", "setEnabled"}, {"fullbright.FullbrightConfig", "setEnabled"},
            {"inventoryhud.InventoryHudConfig", "setEnabled"}, {"playerstats.PlayerStatsConfig", "setEnabled"},
            {"quiver.QuiverDisplayConfig", "setEnabled"}, {"etherwarpoverlay.EtherwarpOverlayConfig", "setEnabled"},
            {"livemap.LiveMapConfig", "setEnabled"},
            // dungeon HUDs, timers, map and highlights
            {"abilitycooldown.AbilityCooldownConfig", "setEnabled"}, {"abilitytimers.AbilityTimersConfig", "setEnabled"},
            {"bloodcamp.BloodCampConfig", "setEnabled"}, {"doorkeys.DoorKeysConfig", "setEnabled"},
            {"etherwarp.EtherwarpWaypointsConfig", "setEnabled"}, {"leapcounter.LeapCounterConfig", "setEnabled"},
            {"maskinvincibility.MaskInvincibilityConfig", "setEnabled"}, {"p4platform.P4PlatformHighlightConfig", "setEnabled"},
            {"posmsg.PosmsgConfig", "setEnabled"}, {"ragaxe.RagAxeConfig", "setEnabled"},
            {"revertmasterstars.RevertMasterStarsConfig", "setEnabled"}, {"runstats.RunStatsConfig", "setEnabled"},
            {"scoreboard.CustomScoreboardConfig", "setEnabled"}, {"scorecalc.ScoreCalculatorConfig", "setEnabled"},
            {"spiritleap.SpiritLeapOverlayConfig", "setEnabled"}, {"splittimers.SplitTimersConfig", "setEnabled"},
            {"splittimers.TerminalTimersConfig", "setEnabled"}, {"teammates.TeammatesConfig", "setEnabled"},
            {"ticktimers.TickTimersConfig", "setEnabled"}, {"witherdoors.WitherDoorsConfig", "setEnabled"},
            {"witherdragons.WitherDragonsConfig", "setEnabled"}, {"secretwaypoints.SecretWaypointsConfig", "setEnabled"},
            {"routes.WaypointRoutesConfig", "setEnabled"}, {"dungeoninfo.DungeonInfoConfig", "setSecretsHudEnabled"},
            {"mobesp.MobEspConfig", "setStarredMobs", "isStarredMobsEnabled"},
            {"mobesp.MobEspConfig", "setBats", "isBatsEnabled"}, {"mobesp.MobEspConfig", "setWithers", "isWithersEnabled"},
            // solvers (read-only: they draw, they do not click)
            {"puzzlesolvers.BeamsSolverConfig", "setEnabled"}, {"puzzlesolvers.BlazeSolverConfig", "setEnabled"},
            {"puzzlesolvers.BoulderSolverConfig", "setEnabled"}, {"puzzlesolvers.IceFillSolverConfig", "setEnabled"},
            {"puzzlesolvers.IcePathSolverConfig", "setEnabled"}, {"puzzlesolvers.QuizSolverConfig", "setEnabled"},
            {"puzzlesolvers.TeleportMazeSolverConfig", "setEnabled"}, {"puzzlesolvers.TicTacToeSolverConfig", "setEnabled"},
            {"puzzlesolvers.WaterSolverConfig", "setEnabled"}, {"puzzlesolvers.WeirdosSolverConfig", "setEnabled"},
            {"boss.LividSolverConfig", "setEnabled"}, {"terminals.TerminalSolverConfig", "setEnabled"},
    };

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        int frames = Integer.getInteger("testkit.bench.frames", 1200);
        int rounds = Integer.getInteger("testkit.bench.rounds", 3);
        Path out = Path.of(System.getProperty("testkit.reportDir", "build/testkit-report"));
        List<String> report = new ArrayList<>();

        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
        });
        if (copyRealRooms() < 20) {
            throw new AssertionError("[" + NAME + "] needs his real rooms to build a floor");
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 6000);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter",
                new Class<?>[]{String.class}, new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 3});
        }));
        ctx.waitFor(mc -> mc.level != null, 6000);
        Map<String, Boolean> onBefore = new LinkedHashMap<>();
        Map<Object[], Boolean> offBefore = new LinkedHashMap<>();
        try {
            Scenario.awaitSimBuild(ctx, before);
            // The first sim world's chunks take ~27 s to reach the client (testkit CLAUDE.md); stand on a block first.
            ctx.waitFor(mc -> mc.player != null && mc.player.onGround(), 2400);
            ctx.waitTicks(100);
            boolean inDungeon = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.secrets.DungeonState", "isInDungeon"));
            report.add("inDungeon=" + inDungeon);
            if (!inDungeon) {
                throw new AssertionError("[" + NAME + "] the mod does not think the sim floor is a dungeon");
            }

            ctx.runOnClient(mc -> {
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(260);
                McCompat.setScreen(mc, null);
            });

            List<String> missing = new ArrayList<>();
            ctx.runOnClient(mc -> {
                for (String[] s : ON) {
                    String key = s[0] + "#" + s[1];
                    try {
                        String getter = s.length > 2 ? s[2] : s[1].replaceFirst("^set", "is");
                        Object cfg = Mod.cfg(s[0]);
                        onBefore.put(key, (Boolean) Mod.call(cfg, getter));
                    } catch (Throwable t) {
                        missing.add(key);
                    }
                }
            });
            report.add("ON set: " + (ON.length - missing.size()) + " switch(es); missing " + missing);
            List<Object[]> offTargets = ctx.computeOnClient(mc -> offTargets());
            report.add("OFF set: " + offTargets.size() + " config(s) with isEnabled/setEnabled");
            for (Object[] t : offTargets) {
                offBefore.put(t, ctx.computeOnClient(mc -> invokeIs(t)));
            }

            // Warm-up in each configuration, so class loading and first-use caches are not in round 1.
            applyOn(ctx, onBefore, offBefore, true);
            ctx.waitTicks(300);
            applyOn(ctx, onBefore, offBefore, false);
            ctx.waitTicks(300);

            long[][] onFrames = new long[rounds][];
            long[][] onTicks = new long[rounds][];
            long[][] offFrames = new long[rounds][];
            long[][] offTicks = new long[rounds][];
            for (int r = 0; r < rounds; r++) {
                applyOn(ctx, onBefore, offBefore, true);
                ctx.waitTicks(40);
                long[][] on = phase(ctx, frames);
                onFrames[r] = on[0];
                onTicks[r] = on[1];
                report.add(String.format(Locale.ROOT, "round %d ON  frame %s | tick %s", r + 1,
                        FrameClock.stats(on[0], on[0].length).line(), FrameClock.stats(on[1], on[1].length).line()));
                applyOn(ctx, onBefore, offBefore, false);
                ctx.waitTicks(40);
                long[][] off = phase(ctx, frames);
                offFrames[r] = off[0];
                offTicks[r] = off[1];
                report.add(String.format(Locale.ROOT, "round %d OFF frame %s | tick %s", r + 1,
                        FrameClock.stats(off[0], off[0].length).line(), FrameClock.stats(off[1], off[1].length).line()));
                System.out.println("[" + NAME + "] " + report.get(report.size() - 2));
                System.out.println("[" + NAME + "] " + report.get(report.size() - 1));
            }
            long[] onF = concat(onFrames), onT = concat(onTicks), offF = concat(offFrames), offT = concat(offTicks);
            FrameClock.Stats sOnF = FrameClock.stats(onF, onF.length), sOffF = FrameClock.stats(offF, offF.length);
            FrameClock.Stats sOnT = FrameClock.stats(onT, onT.length), sOffT = FrameClock.stats(offT, offT.length);
            report.add("ALL ON  frame " + sOnF.line());
            report.add("ALL OFF frame " + sOffF.line());
            report.add("ALL ON  tick  " + sOnT.line());
            report.add("ALL OFF tick  " + sOffT.line());
            report.add(String.format(Locale.ROOT, "DELTA frame mean %+.3f ms p95 %+.3f p99 %+.3f | tick mean %+.3f ms "
                            + "p95 %+.3f p99 %+.3f", sOnF.mean() - sOffF.mean(), sOnF.p95() - sOffF.p95(),
                    sOnF.p99() - sOffF.p99(), sOnT.mean() - sOffT.mean(), sOnT.p95() - sOffT.p95(),
                    sOnT.p99() - sOffT.p99()));

            if (!Boolean.getBoolean("testkit.bench.noJfr")) {
                applyOn(ctx, onBefore, offBefore, true);
                ctx.waitTicks(40);
                jfrPhase(ctx, frames, out.resolve("fps-on.jfr"));
                applyOn(ctx, onBefore, offBefore, false);
                ctx.waitTicks(40);
                jfrPhase(ctx, frames, out.resolve("fps-off.jfr"));
                report.add("JFR: " + out.resolve("fps-on.jfr") + ", " + out.resolve("fps-off.jfr"));
            }
            // The run must have measured something: a bench whose clock never ran reports zeros that read as "free".
            if (sOnF.n() < frames * rounds / 2 || sOnT.n() < frames * rounds / 2) {
                throw new AssertionError("[" + NAME + "] the frame clock recorded " + sOnF.n() + " frames / "
                        + sOnT.n() + " ticks for " + frames * rounds + " requested - the mixins did not run");
            }
        } finally {
            try {
                ctx.runOnClient(mc -> {
                    onBefore.forEach((k, v) -> {
                        String[] p = k.split("#");
                        Mod.call(Mod.cfg(p[0]), p[1], v);
                    });
                    offBefore.forEach(FpsBenchTest::invokeSet);
                });
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("bench over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 600);
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Files.createDirectories(out);
                Files.writeString(out.resolve("fps-bench.txt"), String.join(System.lineSeparator(), report)
                        + System.lineSeparator());
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write fps-bench.txt: " + e);
            }
        }
    }

    // ---- phases -------------------------------------------------------------------------------------------------

    private static long[][] phase(ClientGameTestContext ctx, int frames) {
        ctx.runOnClient(mc -> FrameClock.start(frames * 3));
        ctx.waitTicks(frames);
        return ctx.computeOnClient(mc -> {
            FrameClock.stop();
            if (FrameClock.dropped() > 0) {
                System.out.println("[" + NAME + "] WARNING: " + FrameClock.dropped() + " frame(s) past capacity dropped");
            }
            return new long[][]{FrameClock.frameSamples(), FrameClock.tickSamples()};
        });
    }

    private static void jfrPhase(ClientGameTestContext ctx, int frames, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Recording rec = new Recording(Configuration.getConfiguration("profile"));
            rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            rec.start();
            ctx.waitTicks(frames);
            rec.stop();
            rec.dump(file);
            rec.close();
        } catch (Exception e) {
            System.out.println("[" + NAME + "] JFR failed: " + e);
        }
    }

    private static void applyOn(ClientGameTestContext ctx, Map<String, Boolean> onBefore,
                                Map<Object[], Boolean> offBefore, boolean on) {
        ctx.runOnClient(mc -> {
            if (on) {
                // restore what OFF switched off, then switch the ON set on
                offBefore.forEach(FpsBenchTest::invokeSet);
                for (String k : onBefore.keySet()) {
                    String[] p = k.split("#");
                    Mod.call(Mod.cfg(p[0]), p[1], true);
                }
            } else {
                for (Object[] t : offBefore.keySet()) {
                    invokeSet(t, false);
                }
            }
        });
    }

    // ---- the OFF set: every config in the jar with getInstance + isEnabled + setEnabled(boolean) ----------------

    private static List<Object[]> offTargets() {
        List<Object[]> out = new ArrayList<>();
        for (String fq : JarIndex.classNames(s -> s.endsWith("Config"))) {
            if (fq.contains(".mixin.")) {
                continue;
            }
            Class<?> c = JarIndex.peek(fq);
            if (c == null) {
                continue;
            }
            try {
                Method gi = c.getMethod("getInstance");
                Method is = c.getMethod("isEnabled");
                Method set = c.getMethod("setEnabled", boolean.class);
                if (!Modifier.isStatic(gi.getModifiers()) || Modifier.isStatic(is.getModifiers())) {
                    continue;
                }
                out.add(new Object[]{gi.invoke(null), is, set});
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // not a plain on/off config
            }
        }
        return out;
    }

    private static boolean invokeIs(Object[] t) {
        try {
            return (Boolean) ((Method) t[1]).invoke(t[0]);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invokeSet(Object[] t, Boolean v) {
        try {
            ((Method) t[2]).invoke(t[0], v);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long[] concat(long[][] parts) {
        int n = 0;
        for (long[] p : parts) {
            n += p.length;
        }
        long[] all = new long[n];
        int i = 0;
        for (long[] p : parts) {
            System.arraycopy(p, 0, all, i, p.length);
            i += p.length;
        }
        return all;
    }

    private static final String SOURCE_ROOMS = ModUnderTest.instanceConfig(
            "C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)/minecraft/config",
            "killer560smod-rooms");

    private static int copyRealRooms() {
        try {
            Path source = Path.of(SOURCE_ROOMS);
            if (!Files.isDirectory(source)) {
                return 0;
            }
            Path target = ModUnderTest.modConfig("killer560smod-rooms");
            Files.createDirectories(target);
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(source)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            for (Path f : files) {
                Files.copy(f, target.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return files.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
