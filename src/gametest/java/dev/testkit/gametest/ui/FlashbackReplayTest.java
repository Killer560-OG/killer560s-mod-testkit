package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 1914-flashback-replay: opens a Flashback replay (a .zip from Flashback's replays folder) in this client, through
 * Flashback's own {@code com.moulberry.flashback.Flashback.openReplayWorld(Path)} - the call its Select Replay screen and
 * file browser end in - lets it play, and reports what it shows: the replay's length, whether the replay tick advances,
 * the mod's dungeon state and HUD flags, the sidebar, and a few 1920x1080 frames ({@link VideoRecorder}) to look at.
 *
 * <p>Needs Flashback loaded ({@code -PextraMods=<folder with Flashback-*.jar>}) and the replay named with
 * {@code -PflashbackReplay=<zip, no spaces>}. Without either it is a SKIP row. Only runs when named.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class FlashbackReplayTest implements FabricClientGameTest {

    static final String NAME = "1914-flashback-replay";
    private static final String FB = "com.moulberry.flashback.Flashback";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            dev.testkit.gametest.Scenario.skip(NAME);
            return;
        }
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || !filter.contains("flashback")) {
            return;
        }
        String replay = System.getProperty("testkit.flashbackReplay", "");
        if (replay.isBlank() || !Files.isRegularFile(Path.of(replay))) {
            dev.testkit.gametest.Scenario.skipped(NAME, "no replay: pass -PflashbackReplay=<zip>");
            return;
        }
        Class<?> fb;
        try {
            fb = Class.forName(FB);
        } catch (ClassNotFoundException e) {
            dev.testkit.gametest.Scenario.skipped(NAME, "Flashback is not loaded: pass -PextraMods=<folder>");
            return;
        }
        LogTap.install();
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.resizeGui());
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> Mod.set("livemap.LiveMapConfig", "setEnabled", true));
        try {
            UiCase.run(ctx, NAME, c -> body(c, fb, Path.of(replay).toAbsolutePath()));
        } finally {
            // a case that fails inside the replay must still leave it, or the gametest ends "while a server is running"
            boolean inWorld = ctx.computeOnClient(mc -> mc.level != null);
            if (inWorld) {
                ctx.runOnClient(FlashbackReplayTest::leave);
                ctx.waitTicks(40);
            }
        }
    }

    private static void leave(net.minecraft.client.Minecraft mc) {
        if (mc.level != null) {
            mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
        }
        mc.disconnectWithSavingScreen();
        McCompat.setScreen(mc, new TitleScreen());
    }

    private static Object stat(Class<?> fb, String method) {
        try {
            Method m = fb.getMethod(method);
            return m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Flashback." + method + " failed: " + UiCase.describe(e), e);
        }
    }

    private static Object server(UiCase c, Class<?> fb) {
        return c.onClient(mc -> stat(fb, "getReplayServer"));
    }

    private static int tick(UiCase c, Object server) {
        return c.onClient(mc -> (Integer) Mod.call(server, "getReplayTick"));
    }

    private static boolean paused(Object server) {
        return (Boolean) Mod.field(server, "replayPaused");
    }

    private static void setPaused(Object server, boolean value) {
        try {
            Field f = server.getClass().getField("replayPaused");
            f.setBoolean(server, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set replayPaused: " + e, e);
        }
    }

    /** The mod's own view of the world: dungeon state, map, gate, and the sidebar the replay is showing. */
    private static String modState(UiCase c) {
        return c.onClient(mc -> {
            StringBuilder b = new StringBuilder();
            b.append("inDungeon=").append(Mod.staticCall("secrets.DungeonState", "isInDungeon"));
            b.append(" roomSim=").append(Mod.staticCall("secrets.DungeonState", "isRoomSim"));
            b.append(" floor=").append(Mod.staticCall("secrets.DungeonState", "getFloor"));
            b.append(" skyblockGate.allows=").append(Mod.staticCall("util.SkyblockGate", "allows"));
            b.append(" liveMap.enabled=").append(Mod.get("livemap.LiveMapConfig", "isEnabled"));
            b.append(" map.hasRoomStates=").append(Mod.staticCall("livemap.LiveMapFeature", "hasRoomStates"));
            b.append(" map.currentRoomType=").append(Mod.staticCall("livemap.LiveMapFeature", "currentRoomType"));
            b.append(" hideGui=").append(mc.options.hideGui);
            b.append(" screen=").append(McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getName());
            if (mc.player != null) {
                b.append(String.format(Locale.ROOT, " camera=%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(),
                        mc.player.getZ()));
            }
            if (mc.level != null) {
                var sb = mc.level.getScoreboard();
                var o = sb.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
                b.append(" sidebar=").append(o == null ? "none" : "'" + o.getDisplayName().getString() + "' "
                        + sb.listPlayerScores(o).size() + " lines");
                b.append(" players=").append(mc.level.players().size());
            }
            return b.toString();
        });
    }

    private static void body(UiCase c, Class<?> fb, Path zip) throws Exception {
        c.note("opening " + zip + " with " + FB + ".openReplayWorld(Path)");
        long t0 = System.nanoTime();
        // Not inside runOnClient: Fabric's gametest MinecraftMixin.deferStartIntegratedServer cancels a doWorldLoad made
        // while a test task runs (ThreadingImpl.taskToRun != null) and re-runs it after the task, by which time
        // Flashback's MixinMinecraft has cleared the ThreadLocal StartReplayServerInfo its doWorldLoad_spin wrap reads,
        // so a PLAIN IntegratedServer starts on an empty "Replay" world (run 1, 2026-10-20). Queued on the client's own
        // task queue instead, the call runs in runAllTasks with no test task active and is not deferred.
        String[] openError = {null};
        // execute() from the TEST thread: on the render thread it would run the task at once, inside the test task.
        net.minecraft.client.Minecraft client = c.onClient(mc -> mc);
        client.execute(() -> {
            try {
                fb.getMethod("openReplayWorld", Path.class).invoke(null, zip);
            } catch (Throwable e) {
                openError[0] = UiCase.describe(e);
            }
        });
        c.ticks(2);
        c.check(openError[0] == null, "openReplayWorld threw: " + openError[0]);
        Object server = null;
        boolean ready = false;
        for (int i = 0; i < 1200 && !ready; i++) {
            c.ticks(1);
            server = server(c, fb);
            Object s = server;
            ready = s != null && c.onClient(mc -> (Boolean) stat(fb, "isInReplay") && (Boolean) Mod.call(s, "isReady")
                    && mc.level != null && mc.player != null);
        }
        double openS = (System.nanoTime() - t0) / 1e9;
        c.check(ready, "the replay did not open within 1200 ticks (server=" + server + ")");
        Object srv = server;
        int total = c.onClient(mc -> (Integer) Mod.call(srv, "getTotalReplayTicks"));
        String meta = c.onClient(mc -> String.valueOf(Mod.call(srv, "getMetadata")));
        c.note(String.format(Locale.ROOT, "replay opened in %.1f s: %s; total %d ticks (%.1f s); metadata %s", openS,
                srv.getClass().getName(), total, total / 20.0, meta));
        System.out.println("[" + c.name() + "] replay opened, total ticks " + total);
        c.ticks(40); // chunks
        int t1 = tick(c, srv);
        boolean p1 = paused(srv);
        c.note("after open: tick " + t1 + ", paused " + p1 + "; " + modState(c));
        if (p1) {
            c.onClient(mc -> {
                setPaused(srv, false);
                return null;
            });
            c.note("unpaused the replay (replayPaused=false)");
        }
        long w0 = System.nanoTime();
        c.ticks(200);
        double wall = (System.nanoTime() - w0) / 1e9;
        int t2 = tick(c, srv);
        c.note(String.format(Locale.ROOT, "after 200 client ticks (%.1f s wall): tick %d -> %d, paused %s", wall, t1, t2,
                paused(srv)));
        c.check(t2 > t1, "playback did not advance: tick stayed " + t1 + " -> " + t2);

        // frames: where playback is now, then mid-replay, then near the end
        VideoRecorder r = new VideoRecorder(c, "flashback-replay");
        try {
            r.hideCursor();
            r.mark("t" + t2);
            r.hold(500);
            c.note("frames 0-29 at replay tick ~" + t2 + "; " + modState(c));
            int[] jumps = {total / 2, Math.max(0, total - 200)};
            for (int j : jumps) {
                c.onClient(mc -> {
                    Mod.call(srv, "goToReplayTick", j);
                    return null;
                });
                c.ticks(60);
                if (paused(srv)) {
                    c.onClient(mc -> {
                        setPaused(srv, false);
                        return null;
                    });
                }
                int at = tick(c, srv);
                r.mark("t" + at);
                c.note("frames from " + r.frames() + " at replay tick " + at + " (asked " + j + "); " + modState(c));
                r.hold(500);
            }
            // Flashback's editor (ReplayUI.isActiveInternal) is up only while options.hideGui is false and then draws the
            // game in a small viewport; F1 (hideGui) hides it and the game fills the window. Film that too, from the
            // recorded player's eyes (camera entity = the replayed player, as Flashback's own spectate does).
            c.onClient(mc -> {
                Mod.call(srv, "goToReplayTick", total / 3);
                return null;
            });
            c.ticks(60);
            if (paused(srv)) {
                c.onClient(mc -> {
                    setPaused(srv, false);
                    return null;
                });
            }
            int mid = tick(c, srv);
            r.mark("editor-t" + mid);
            c.note("frames from " + r.frames() + " at replay tick " + mid + ", hideGui=false (editor up); " + modState(c));
            r.hold(500);
            String who = c.onClient(mc -> {
                mc.options.hideGui = true;
                for (var p : mc.level.players()) {
                    if (p != mc.player) {
                        mc.setCameraEntity(p);
                        return p.getName().getString() + " at " + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", p.getX(),
                                p.getY(), p.getZ());
                    }
                }
                return "nobody (only the viewer)";
            });
            c.ticks(10);
            int at = tick(c, srv);
            r.mark("pov-t" + at);
            c.note("frames from " + r.frames() + " at replay tick " + at + ", hideGui=true, camera on " + who + "; "
                    + modState(c));
            r.hold(500);
            c.onClient(mc -> {
                mc.options.hideGui = false;
                mc.setCameraEntity(mc.player);
                return null;
            });
        } finally {
            r.close();
        }
        int end = tick(c, srv);
        c.note("final tick " + end + " of " + total);
        c.onClient(mc -> {
            leave(mc);
            return null;
        });
        c.ticks(40);
        c.note("left the replay: inReplay=" + c.onClient(mc -> stat(fb, "isInReplay")));
    }
}
