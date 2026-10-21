package dev.testkit.gametest.ui;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.VideoSim;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1915-video-autosecret: promo footage of Auto Secret secreting a sim F7 at full speed, recorded with {@link VideoRecorder}
 * (1920x1080 PNG frames, three per game tick, each at its own partial tick - {@link VideoRecorder#partialTicks} - so
 * encoded at 60 fps the clip plays at the game's real speed with motion smooth at 60). The floor, his routes and
 * settings are 1016-sim-fullclear's ({@code -PseedConfig=<his config>}, floor planned with his designer filters, his
 * hotbar); Auto Secret is started at GO and the clip runs until it stops on its own. The camera is his own first-person
 * view with the HUD on (the mod's HUD from his config), no free camera. Night Vision on the sim's server player, for the
 * camera only (the sim's rooms are much darker than Hypixel's). For the owner's 2026-10-20 notes: Auto Creeper Beams in
 * Melee mode, and the Dungeon Map HUD in the top-left corner (its Edit HUD position, 0, 0).
 *
 * <p>Asserts that the run happened: frames recorded, Auto Secret started routes, the sim counted secrets, he travelled,
 * and Auto Secret ended on its own ("nothing left to do") rather than at the frame limit. GrimAC does not run inside the
 * sim's integrated server. Only runs when named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class VideoAutoSecretSuite implements FabricClientGameTest {

    static final String NAME = "1915-video-autosecret";

    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String DUNGEON_INFO = "com.killer560.hub.dungeoninfo.DungeonInfoFeature";

    private static final String AUTO_PUZZLES = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final String HUD_CONFIG = "com.killer560.hub.hud.HudConfig";
    /** The Dungeon Map HUD's Edit HUD position: the top-left corner. */
    private static final int MAP_X = 0;
    private static final int MAP_Y = 0;
    private static final Pattern BEAMS_MODE = Pattern.compile("\\[AutoPuzzles\\] Beams: entered with the auto on - mode (\\w+)");

    private static Class<?> beamsModeClass() {
        try {
            return Class.forName(AUTO_PUZZLES + "$BeamsMode", false,
                    ModUnderTest.staticCall(AUTO_PUZZLES, "getInstance").getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("this jar has no Melee mode for Auto Creeper Beams", e);
        }
    }

    /** Game time the clip may run before it gives up (a full clear takes 70-90 s in 1016's runs). */
    private static final double LIMIT_MS = 180_000;
    private static final Pattern COUNTED = Pattern.compile("Sim secret counted: (-?\\d+, -?\\d+, -?\\d+) in (.+?) \\(");
    private static final Pattern STARTED = Pattern.compile("\\[AutoSecret\\] route (.+?) started");
    private static final Pattern FINISHED = Pattern.compile("\\[AutoSecret\\] route (.+?) finished");
    private static final Pattern AS_STOP = Pattern.compile("\\[AutoSecret\\] stopped: (.*)$");

    /** The mod's own client tick counter (Auto Secret's "finished (tick N)" lines). */
    private static long clientTick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(
                "com.killer560.hub.livemap.autoclear.ClearExecutor", "clientTick")).longValue());
    }

    private static boolean named() {
        String filter = System.getProperty("testkit.scenario", "");
        return !filter.isBlank() && filter.contains("video") && UiCase.selected(NAME);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            dev.testkit.gametest.Scenario.skip(NAME);
            return;
        }
        if (!named()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            mc.options.bobView().set(false);
            mc.options.gamma().set(1.0);
            mc.options.guiScale().set(3);
            mc.options.fov().set(80);
            mc.options.renderDistance().set(10);
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            // Auto Creeper Beams in Melee mode, and the Dungeon Map HUD in the top-left corner (killer560, 2026-10-20).
            Object auto = ModUnderTest.staticCall(AUTO_PUZZLES, "getInstance");
            ModUnderTest.call(auto, "setAutoBeamsMode", new Class<?>[]{beamsModeClass()},
                    new Object[]{ModUnderTest.enumValue(AUTO_PUZZLES + "$BeamsMode", "MELEE")});
            ModUnderTest.call(auto, "save", new Class<?>[0], new Object[0]);
            Object hud = ModUnderTest.staticCall(HUD_CONFIG, "getInstance");
            ModUnderTest.call(hud, "setPosition", new Class<?>[]{String.class, int.class, int.class},
                    new Object[]{"live_map", MAP_X, MAP_Y});
            ModUnderTest.call(hud, "save", new Class<?>[0], new Object[0]);
            return null;
        });
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.resizeGui());
        ctx.waitTicks(3);
        if (!VideoSim.prepare(ctx)) {
            dev.testkit.gametest.Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        int routes = VideoSim.routedRooms(ctx).size();
        System.out.println("[" + NAME + "] routes file holds " + routes + " room(s)");
        try {
            // Built inside the case: UiCase's SafeWorld (flying creative) only applies to a world that is already up.
            UiCase.run(ctx, NAME, c -> body(c, routes));
        } finally {
            ctx.runOnClient(mc -> {
                if (mc.level != null && (Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"video over", true});
                }
                Mod.staticCall("gui.MenuAnim", "setClockForTest", -1.0);
            });
            if (ctx.computeOnClient(mc -> mc.level != null)) {
                VideoSim.leave(ctx);
            }
        }
    }

    private static void body(UiCase c, int routes) throws Exception {
        ClientGameTestContext ctx = c.ctx();
        c.check(routes >= 20, "only " + routes + " routed room(s) - run with -PseedConfig=<his config>");
        String code = System.getProperty("testkit.fullclearCode", "");
        String map = VideoSim.fullClearFloor(ctx, code.isBlank() ? null : code);
        c.check(map != null, "no floor could be planned");
        c.note("map code: " + map);
        VideoSim.nightVision(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 600 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.runOnClient(mc -> dev.testkit.compat.McCompat.chat(mc).clearMessages(false));
        ctx.waitTicks(20);

        // Three frames per game tick at partial ticks 1/3, 2/3, 1 (see VideoRecorder.partialTicks): encoded at 60 fps the
        // world plays at real speed and moves smoothly at 60.
        int[] mapPos = c.onClient(mc -> (int[]) ModUnderTest.call(ModUnderTest.staticCall(HUD_CONFIG, "getInstance"),
                "getPosition", new Class<?>[]{String.class, int.class, int.class}, new Object[]{"live_map", -1, -1}));
        String beamsMode = c.onClient(mc -> String.valueOf(ModUnderTest.call(ModUnderTest.staticCall(AUTO_PUZZLES,
                "getInstance"), "getAutoBeamsMode", new Class<?>[0], new Object[0])));
        c.note("Dungeon Map HUD at " + mapPos[0] + ", " + mapPos[1] + "; Auto Creeper Beams mode " + beamsMode);
        System.out.println("[" + NAME + "] Dungeon Map HUD at " + mapPos[0] + ", " + mapPos[1] + "; Auto Creeper Beams mode "
                + beamsMode);
        c.check(mapPos[0] == MAP_X && mapPos[1] == MAP_Y, "the Dungeon Map HUD is not at the top left");
        c.check("MELEE".equals(beamsMode), "Auto Creeper Beams is in " + beamsMode + " mode, not Melee");
        VideoRecorder r = new VideoRecorder(c, "autosecret-60fps").partialTicks();
        long mark = LogTap.mark();
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        double travelled = 0;
        boolean started = false;
        boolean ended = false;
        int startFrame = -1;
        int endFrame = -1;
        long tick0 = clientTick(ctx);
        try {
            r.hideCursor();
            int lead = 60;
            int limit = (int) Math.round(LIMIT_MS / VideoRecorder.FRAME_MS);
            int tail = -1;
            for (int i = 0; i < limit; i++) {
                if (i == lead) {
                    r.mark("autosecret-start");
                    Boolean ok = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
                    started = Boolean.TRUE.equals(ok);
                    startFrame = i;
                    if (!started) {
                        c.note("Auto Secret refused to start: " + String.join(" | ", LogTap.since(mark)));
                        break;
                    }
                }
                r.frame();
                if (i % 15 == 0) {
                    boolean world = ctx.computeOnClient(mc -> mc.level != null && mc.player != null);
                    if (!world) {
                        c.note("the world went away " + i / 60 + " s in");
                        break;
                    }
                    Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
                    travelled += at.distanceTo(last);
                    last = at;
                    if (started && tail < 0 && i > lead
                            && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"))) {
                        ended = true;
                        endFrame = i;
                        r.mark("autosecret-stopped");
                        tail = i + 150;
                    }
                }
                if (tail >= 0 && i >= tail) {
                    break;
                }
            }
        } finally {
            r.close();
        }
        long ticks = clientTick(ctx) - tick0;
        c.note("client ticks over the recording: " + ticks + " (recorder advanced " + r.ticksAdvanced() + ") for "
                + r.frames() + " frames");
        System.out.println("[" + NAME + "] client ticks over the recording: " + ticks + " (recorder advanced "
                + r.ticksAdvanced() + ") for " + r.frames() + " frames");
        // ---- what happened -------------------------------------------------------------------------------------
        Map<String, Integer> counted = new LinkedHashMap<>();
        Set<String> startedRoutes = new java.util.LinkedHashSet<>();
        Set<String> finishedRoutes = new java.util.LinkedHashSet<>();
        String stop = "(still running)";
        int corrections = 0;
        int grim = 0;
        Set<String> beamsVisits = new java.util.LinkedHashSet<>();
        for (String l : LogTap.since(mark)) {
            Matcher bm = BEAMS_MODE.matcher(l);
            if (bm.find()) {
                beamsVisits.add(bm.group(1));
            }
            Matcher m = COUNTED.matcher(l);
            if (m.find()) {
                counted.merge(m.group(2), 1, Integer::sum);
            }
            m = STARTED.matcher(l);
            if (m.find()) {
                startedRoutes.add(m.group(1));
            }
            m = FINISHED.matcher(l);
            if (m.find()) {
                finishedRoutes.add(m.group(1));
            }
            m = AS_STOP.matcher(l);
            if (m.find()) {
                stop = m.group(1).trim();
            }
            if (l.contains("[Correction]")) {
                corrections++;
            }
            if (l.contains("GrimAC") || l.contains("[Grim")) {
                grim++;
            }
        }
        int total = counted.values().stream().mapToInt(Integer::intValue).sum();
        int[] score = c.onClient(mc -> new int[]{(Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsFound"),
                (Integer) ModUnderTest.staticCall(SIM_SCORE, "secretsTotal")});
        double pct = c.onClient(mc -> (Double) ModUnderTest.staticCall(DUNGEON_INFO, "secretsFoundPercent"));
        double runSeconds = (endFrame >= 0 ? endFrame - startFrame : r.frames() - startFrame) / 60.0;
        c.note(String.format(Locale.ROOT, "%d frames (%.1f s at 60 fps); Auto Secret ran %.1f s of game time, ended \"%s\"; "
                        + "travelled %.0f blocks; sim secrets %d/%d (%d counted lines), tab %.1f%%; routes started %d, finished "
                        + "%d; corrections %d; Grim lines %d", r.frames(), r.frames() / 60.0, runSeconds, stop, travelled,
                score[0], score[1], total, pct, startedRoutes.size(), finishedRoutes.size(), corrections, grim));
        c.check(Math.abs(ticks * 3 - r.frames()) <= r.frames() / 50 + 6, "the clip is not three frames per tick: " + ticks
                + " client ticks for " + r.frames() + " frames - it would not play at real speed at 60 fps");
        c.note("Creeper Beams visits: modes " + beamsVisits);
        System.out.println("[" + NAME + "] Creeper Beams entered in mode(s) " + beamsVisits);
        c.check(beamsVisits.isEmpty() || beamsVisits.equals(Set.of("melee")), "Auto Creeper Beams ran in " + beamsVisits);
        c.note("secrets per room: " + counted);
        System.out.println("[" + NAME + "] secrets per room: " + counted);
        System.out.println(String.format(Locale.ROOT, "[%s] RESULT frames %d, run %.1f s, ended \"%s\", travelled %.0f, "
                        + "secrets %d/%d, routes %d started / %d finished, corrections %d", NAME, r.frames(), runSeconds, stop,
                travelled, score[0], score[1], startedRoutes.size(), finishedRoutes.size(), corrections));
        c.check(started, "Auto Secret never started");
        c.check(r.frames() > 200, "only " + r.frames() + " frames recorded");
        c.check(travelled > 20, "he barely moved (" + travelled + " blocks)");
        c.check(score[0] > 0 && total > 0, "the sim counted no secret");
        c.check(!startedRoutes.isEmpty(), "Auto Secret started no route");
        c.check(ended, "Auto Secret was still running at the frame limit");
        c.check(stop.startsWith("nothing left to do"), "Auto Secret ended with \"" + stop + "\", not \"nothing left to do\"");
    }
}
