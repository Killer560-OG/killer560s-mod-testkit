package dev.testkit.gametest.ui;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.VideoSim;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;
import dev.testkit.harness.VideoCamera;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1912 / 1913: promo footage of Auto Routes in the Dungeon Sim, recorded with {@link VideoRecorder} at REAL speed: 1920x1080
 * PNG frames, {@link #PER_TICK} per game tick at their own partial ticks ({@link VideoRecorder#partialTicks(int)}; 6 = 120
 * fps), and each clip checks its client and server ticks against its frames. His Museum route (the one 96-ar-museum / 96-ar-398-regrow run, as his Map Logger
 * instance had it on 2026-10-06) in the sim's Museum, built alone, played from its start node to the end.
 *
 * <ul>
 *   <li>1912-video-autoroutes-firstperson: his own view, HUD on.</li>
 *   <li>1913-video-autoroutes-spectator: a free camera ({@link VideoCamera}, render-side only) orbiting slowly out and
 *       above him, gliding after each warp, HUD on - watching someone else route.</li>
 * </ul>
 *
 * The route's SECRET awaits become short DELAY awaits (Secret Aura is off, so nothing opens a chest window over the
 * shot), and node #5's breaker keeps only the blocks #6's and #7's warps fly through (as 96-ar-398-regrow does), so
 * the sim breaker's 20 charges cover the whole route. Only runs when named. Asserts that the route ran (nodes acted,
 * the route completed) and, for 1913, that the camera was really away from him.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class VideoAutoRoutesSuite implements FabricClientGameTest {

    static final String FIRST = "1912-video-autoroutes-firstperson";
    static final String SPECTATOR = "1913-video-autoroutes-spectator";

    private static final String ROOM = "Museum";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String EXECUTOR = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String CHEAT_CFG = "com.killer560.hub.cheatutils.CheatUtilsConfig";

    /** Copy of SimAutoRoutesTests.MUSEUM_ROUTE. */
    private static final String MUSEUM_ROUTE = """
            [
            {"type":"ETHERWARP","x":31.5,"y":69.0,"z":56.5,"yaw":-122.669197,"pitch":-48.907085,"at":0,"start":true,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"39.500000 82.050000 51.500000"},
            {"type":"ETHERWARP","x":39.5,"y":82.0,"z":51.5,"yaw":-96.989906,"pitch":-59.229813,"at":0,"landing":"44.500000 93.050000 50.500000"},
            {"type":"ETHERWARP","x":44.5,"y":93.0,"z":50.5,"yaw":159.467621,"pitch":65.926178,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"42.500000 82.050000 45.500000"},
            {"type":"ETHERWARP","x":42.5,"y":82.0,"z":45.5,"yaw":157.720352,"pitch":2.248079,"at":0,"landing":"30.500000 82.050000 15.500000"},
            {"type":"DUNGEON_BREAKER","x":30.5,"y":82.0,"z":15.5,"yaw":157.332077,"pitch":2.118652,"at":0,"blocks":["30 83 14","29 83 14","28 83 14","29 83 13","28 83 12","28 83 13","33 84 12","33 83 12","32 83 12","32 84 12","31 84 12","30 84 12","28 83 11","28 84 11","29 84 11","29 83 11","29 83 12","29 84 12","31 83 12","30 83 12"]},
            {"type":"ETHERWARP","x":30.5,"y":82.0,"z":15.5,"yaw":140.424835,"pitch":3.21848,"at":0,"landing":"27.500000 83.050000 11.500000"},
            {"type":"ETHERWARP","x":27.5,"y":83.0,"z":11.5,"yaw":-78.251343,"pitch":10.466819,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"34.500000 83.050000 12.500000"},
            {"type":"DUNGEON_BREAKER","x":34.5,"y":83.0,"z":12.5,"yaw":78.483276,"pitch":74.015701,"at":0,"blocks":["33 82 12","33 81 12"]},
            {"type":"ETHERWARP","x":34.5,"y":83.0,"z":12.5,"yaw":108.252403,"pitch":66.509209,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1,"landing":"28.500000 69.050000 10.500000"},
            {"type":"ETHERWARP","x":28.5,"y":69.0,"z":10.5,"yaw":-150.342102,"pitch":12.861895,"at":0,"landing":"31.500000 69.050000 5.500000"},
            {"type":"BOOM","x":31.5,"y":69.0,"z":5.5,"yaw":88.836853,"pitch":26.840017,"at":0},
            {"type":"BOOM","x":31.5,"y":69.0,"z":5.5,"yaw":-89.771271,"pitch":28.004892,"at":0},
            {"type":"CRYPT","x":31.5,"y":69.0,"z":5.5,"yaw":-80.972778,"pitch":25.027544,"at":0,"awaitEnabled":true,"await":"SECRET","amount":2},
            {"type":"ETHERWARP","x":31.5,"y":69.0,"z":5.5,"yaw":-82.851318,"pitch":8.849249,"at":0,"landing":"39.500000 69.050000 6.500000"},
            {"type":"DUNGEON_BREAKER","x":39.5,"y":69.0,"z":6.5,"yaw":-96.437561,"pitch":60.490627,"at":0,"blocks":["40 69 6","40 68 6","41 68 6","41 67 6","41 66 6","43 68 8"]},
            {"type":"ETHERWARP","x":39.5,"y":69.0,"z":6.5,"yaw":-88.535522,"pitch":53.340508,"at":0,"landing":"46.500000 61.050000 6.500000"},
            {"type":"ETHERWARP","x":46.5,"y":61.0,"z":6.5,"yaw":54.934692,"pitch":-53.372124,"at":0,"awaitEnabled":true,"await":"SECRET","amount":1}
            ]
            """;

    /**
     * Game time each secret await is held before his left click skips it (his own rule, 96-ar-awaitskip). A DELAY await
     * cannot pace the clip: it counts wall-clock ms, and with a screenshot per frame 900 ms is about two frames.
     */
    private static final double AWAIT_HOLD_MS = 3200;
    /** The nodes (1-based) that hold for a secret in the clip. */
    private static final Set<Integer> PAUSED = Set.of(1, 3, 10, 14);
    private static final java.util.regex.Pattern BEGINS = java.util.regex.Pattern.compile("Node #(\\d+) [A-Z_]+ begins");

    /** Frames per game tick ({@code -PvideoPerTick}, default 6 = 120 fps at real speed). */
    private static final int PER_TICK = Integer.getInteger("testkit.videoPerTick", 6);

    private static String name(boolean spectator) {
        return spectator ? SPECTATOR : FIRST;
    }

    /** The mod's own client tick counter (as 1915 reads it). */
    private static long clientTick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(
                "com.killer560.hub.livemap.autoclear.ClearExecutor", "clientTick")).longValue());
    }

    /** The integrated server's tick count. */
    private static long serverTick(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (long) mc.getSingleplayerServer().getTickCount());
    }

    private static boolean named(String n) {
        String filter = System.getProperty("testkit.scenario", "");
        return !filter.isBlank() && filter.contains("video") && UiCase.selected(n);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (dev.testkit.harness.ScenarioList.active() || dev.testkit.harness.ModGate.missing() != null) {
            dev.testkit.gametest.Scenario.skip(FIRST);
            dev.testkit.gametest.Scenario.skip(SPECTATOR);
            return;
        }
        if (!named(FIRST) && !named(SPECTATOR)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.computeOnClient(mc -> {
            if (Quiet.enabled()) {
                Quiet.apply();
            }
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled");
            mc.options.bobView().set(false);
            mc.options.gamma().set(1.0);
            mc.options.guiScale().set(3);
            mc.options.fov().set(80);
            return null;
        });
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> mc.resizeGui());
        ctx.waitTicks(3);
        if (!VideoSim.prepare(ctx)) {
            dev.testkit.gametest.Scenario.skipped(FIRST, "needs real room captures and room database");
            return;
        }
        ctx.runOnClient(mc -> {
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", false);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.set(ar, "setInteractDelayTicks", 2);
            ModUnderTest.set(ar, "setChatFeedback", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            ModUnderTest.call(ar, "setCryptAttackTicks", new Class<?>[]{int.class}, new Object[]{100});
            Object weapon = ModUnderTest.enumValue(AR_CONFIG + "$CryptWeapon", "SPIRIT_SCEPTRE");
            ModUnderTest.call(ar, "setCryptWeapon", new Class<?>[]{weapon.getClass()}, new Object[]{weapon});
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.dungeonextras.DungeonExtrasConfig"),
                    "setBreakerAuraEnabled", false);
            ModUnderTest.turnOff(CHEAT_CFG, "setSecretAuraEnabled");
        });
        try {
            if (named(FIRST)) {
                clip(ctx, FIRST, false);
            }
            if (named(SPECTATOR)) {
                clip(ctx, SPECTATOR, true);
            }
        } finally {
            ctx.runOnClient(mc -> {
                VideoCamera.clear();
                mc.options.hideGui = false;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false);
                Mod.staticCall("gui.MenuAnim", "setClockForTest", -1.0);
            });
            VideoSim.leave(ctx);
        }
    }

    private static void clip(ClientGameTestContext ctx, String name, boolean spectator) {
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
        VideoSim.singleRoom(ctx, ROOM);
        ctx.waitFor(mc -> {
            Object f = ModUnderTest.staticCall(FRAME, "current");
            return f != null && String.valueOf(f).contains(ROOM) && mc.player != null && mc.player.onGround();
        }, 1200);
        VideoSim.nightVision(ctx);
        GameType mode = ctx.computeOnClient(mc -> mc.gameMode.getPlayerMode());
        UiCase.run(ctx, name, c -> body(c, spectator, mode));
    }

    private static void body(UiCase c, boolean spectator, GameType mode) throws Exception {
        ClientGameTestContext ctx = c.ctx();
        // UiCase's SafeWorld made him a flying creative player; a route is run as the sim has him.
        serverRun(ctx, sp -> {
            sp.setGameMode(mode == null ? GameType.ADVENTURE : mode);
            sp.getAbilities().flying = false;
            sp.getAbilities().invulnerable = true;
            sp.onUpdateAbilities();
        });
        giveHotbar(ctx);
        List<JsonObject> nodes = videoNodes(c);
        writeRoute(ctx, nodes);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(3);
            McChat.clear(mc);
        });
        JsonObject n1 = nodes.get(0);
        // On the start node with Auto Routes off; turning it on arms the start node where he stands.
        tpRelY(ctx, n1.get("x").getAsDouble(), n1.get("y").getAsDouble(), n1.get("z").getAsDouble(), n1.get("yaw").getAsFloat(), -12f);
        ctx.waitFor(mc -> mc.player.onGround(), 200);
        ctx.waitTicks(40);
        // Real speed (killer560 2026-10-20: "normal speed, high quality, at least 60 fps, higher if you can"): PER_TICK
        // frames per game tick, each at its own partial tick, so encoded at 20 * PER_TICK fps the world plays at 1x.
        VideoRecorder r = new VideoRecorder(c, spectator ? "autoroutes-spectator" : "autoroutes-firstperson")
                .partialTicks(PER_TICK);
        Orbit orbit = null;
        if (spectator) {
            // The mod's HUD stays on in the flyover too (his rule for this footage); third person so his model is drawn.
            ctx.runOnClient(mc -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.options.hideGui = false;
            });
            orbit = c.onClient(Orbit::start);
            orbit.s = 60.0 / r.fps();
            Orbit o = orbit;
            c.onClient(mc -> {
                o.step(mc, true);
                return null;
            });
            ctx.waitTicks(40); // chunks around the camera
        }
        long mark = LogTap.mark();
        long tick0 = clientTick(ctx);
        long serverTick0 = serverTick(ctx);
        long wall0 = System.nanoTime();
        double maxAway = 0;
        int done = -1;
        // the flyover camera's pose per frame, for the smoothness check (camera.csv beside the frames)
        StringBuilder camLog = new StringBuilder("frame,x,y,z,yaw,pitch\n");
        try {
            r.hideCursor();
            int lead = (int) Math.round(1200 / r.frameMs());
            int limit = (int) Math.round(70000 / r.frameMs());
            int tail = -1;
            Set<Integer> awaitNodes = new java.util.HashSet<>();
            for (int k = 0; k < nodes.size(); k++) {
                if (nodes.get(k).has("awaitEnabled")) {
                    awaitNodes.add(k + 1);
                }
            }
            Set<Integer> seen = new java.util.HashSet<>();
            int hold = (int) Math.round(AWAIT_HOLD_MS / r.frameMs());
            int clickAt = -1;
            for (int i = 0; i < limit; i++) {
                if (i == lead) {
                    r.mark("autoroutes-on");
                    ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", true));
                }
                if (i > lead && i % 3 == 0 && clickAt < 0) {
                    for (String l : LogTap.since(mark)) {
                        var bm = BEGINS.matcher(l);
                        if (bm.find()) {
                            int nn = Integer.parseInt(bm.group(1));
                            if (awaitNodes.contains(nn) && seen.add(nn)) {
                                clickAt = i + hold;
                                r.mark("await-" + nn);
                            }
                        }
                    }
                }
                if (clickAt >= 0 && i == clickAt) {
                    ctx.getInput().holdKey(o -> o.keyAttack);
                }
                // held two game ticks whatever the frame rate, so a client tick sees it down
                if (clickAt >= 0 && i == clickAt + 2 * PER_TICK) {
                    ctx.getInput().releaseKey(o -> o.keyAttack);
                    clickAt = -1;
                }
                if (orbit != null) {
                    Orbit o = orbit;
                    double away = c.onClient(mc -> o.step(mc, false));
                    maxAway = Math.max(maxAway, away);
                    camLog.append(String.format(Locale.ROOT, "%d,%.4f,%.4f,%.4f,%.3f,%.3f\n", r.frames(), o.cx, o.cy,
                            o.cz, o.yaw, o.pitch));
                }
                r.frame();
                if (tail < 0 && i > lead && (i % 3 == 0)) {
                    if (logHas(mark, "Route \"" + ROOM + "\" complete")) {
                        done = 0;
                        tail = i + (int) Math.round(1500 / r.frameMs());
                        r.mark("route-complete");
                    } else if (logHas(mark, "Stopped")) {
                        done = 1;
                        tail = i + (int) Math.round(800 / r.frameMs());
                        r.mark("route-stopped");
                    }
                }
                if (tail >= 0 && i >= tail) {
                    break;
                }
            }
        } finally {
            r.close();
            if (spectator) {
                Files.writeString(r.dir.resolve("camera.csv"), camLog, StandardCharsets.UTF_8);
            }
            ctx.runOnClient(mc -> {
                VideoCamera.clear();
                mc.options.hideGui = false;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false);
                ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"video over"});
            });
        }
        // ---- speed: game ticks against frames. 1x means PER_TICK frames per client tick (and per server tick), so the
        // clip's game time (ticks / 20) equals its length at r.fps().
        long ticks = clientTick(ctx) - tick0;
        long serverTicks = serverTick(ctx) - serverTick0;
        double wallS = (System.nanoTime() - wall0) / 1e9;
        double videoS = r.frames() / r.fps();
        String speed = String.format(Locale.ROOT, "speed: %d frames at %d fps = %.2f s of video; client ticks %d (recorder %d), "
                        + "server ticks %d = %.2f s of game time; %.3f frames per client tick, %.2f game ticks per video second;"
                        + " %.0f s wall", r.frames(), (int) r.fps(), videoS, ticks, r.ticksAdvanced(), serverTicks,
                serverTicks / 20.0, r.frames() / (double) Math.max(1, ticks), ticks / videoS, wallS);
        c.note(speed);
        System.out.println("[" + name(spectator) + "] " + speed);
        c.check(Math.abs(ticks * PER_TICK - r.frames()) <= PER_TICK + 2,
                "not 1x: " + ticks + " client ticks for " + r.frames() + " frames (want " + PER_TICK + " per tick)");
        c.check(Math.abs(serverTicks * PER_TICK - r.frames()) <= 3 * PER_TICK,
                "not 1x on the server: " + serverTicks + " server ticks for " + r.frames() + " frames");
        int skips = 0;
        Set<String> acted = new LinkedHashSet<>();
        for (String l : LogTap.since(mark)) {
            var m = java.util.regex.Pattern.compile("Node #(\\d+) [A-Z_]+ acted").matcher(l);
            if (m.find()) {
                acted.add(m.group(1));
            }
            if (l.contains("skipped the await")) {
                skips++;
            }
            if (l.contains("Node #") || l.contains("Route \"") || l.contains("Stopped") || l.contains("Correction")) {
                c.note("log: " + l);
            }
        }
        c.note("nodes acted: " + acted + " (" + acted.size() + " of " + nodes.size() + "), end "
                + (done == 0 ? "complete" : done == 1 ? "stopped" : "neither") + ", awaits skipped by left click " + skips
                + ", " + r.frames() + " frames");
        c.check(r.frames() > 600, "only " + r.frames() + " frames recorded");
        c.check(skips >= 4, "only " + skips + " await(s) were skipped by the left click - the pacing did not happen");
        c.check(acted.size() >= 10,"the route barely ran: nodes acted " + acted);
        c.check(done == 0, "the route did not complete (" + (done == 1 ? "it stopped" : "still going at the limit") + ")");
        if (spectator) {
            c.note(String.format(Locale.ROOT, "camera up to %.1f blocks from his eyes", maxAway));
            c.check(maxAway > 3, "the spectator camera never left him (max " + maxAway + ")");
        }
    }

    /**
     * A slow sweep round him, out and above, that eases after him (never snaps to a warp) and eases in rather than sit
     * inside a wall. See the TAU_ constants for the smoothing.
     */
    private static final class Orbit {
        double tx;
        double ty;
        double tz;
        double angle;
        double dist;
        static final double RADIUS = 9.0;
        static final double HEIGHT = 5.0;
        /** 60 / the recorder's fps: the per-frame rates below were tuned at 60 fps. */
        double s = 1.0;

        static Orbit start(net.minecraft.client.Minecraft mc) {
            Orbit o = new Orbit();
            Vec3 p = mc.player.position();
            o.tx = p.x;
            o.ty = p.y;
            o.tz = p.z;
            // the open side: of 16 directions, the one whose ray reaches furthest
            double best = -1;
            for (int i = 0; i < 16; i++) {
                double a = i * 22.5;
                double d = o.free(mc, a);
                if (d > best + 0.5) {
                    best = d;
                    o.angle = a;
                }
            }
            o.dist = Math.min(best, Math.hypot(RADIUS, HEIGHT));
            return o;
        }

        private Vec3 eye() {
            return new Vec3(tx, ty + 1.3, tz);
        }

        private static Vec3 dir(double angleDeg) {
            double a = Math.toRadians(angleDeg);
            double len = Math.hypot(RADIUS, HEIGHT);
            return new Vec3(-Math.sin(a) * RADIUS / len, HEIGHT / len, Math.cos(a) * RADIUS / len);
        }

        /** How far the camera can sit along {@code angleDeg} before a block (minus a margin). */
        private double free(net.minecraft.client.Minecraft mc, double angleDeg) {
            Vec3 e = eye();
            double want = Math.hypot(RADIUS, HEIGHT);
            Vec3 to = e.add(dir(angleDeg).scale(want + 0.6));
            var hit = mc.level.clip(new ClipContext(e, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                return want;
            }
            return Math.max(1.2, Math.min(want, hit.getLocation().distanceTo(e) - 0.8));
        }

        /** Air in the block at {@code p} and in the six around it at 0.35. */
        private static boolean clearAround(net.minecraft.client.Minecraft mc, Vec3 p) {
            double[][] off = {{0, 0, 0}, {0.35, 0, 0}, {-0.35, 0, 0}, {0, 0.35, 0}, {0, -0.35, 0}, {0, 0, 0.35}, {0, 0, -0.35}};
            for (double[] o : off) {
                var pos = net.minecraft.core.BlockPos.containing(p.x + o[0], p.y + o[1], p.z + o[2]);
                if (!mc.level.getBlockState(pos).isAir()) {
                    return false;
                }
            }
            return true;
        }

        // ---- smoothing (killer560 2026-10-20 on the first real-speed flyover: "way too choppy trying to keep up with
        // them; it doesn't have to perfectly track them, it should be smooth"). Every quantity eases toward its goal
        // with a time constant in SECONDS (frame-rate independent): a = 1 - exp(-dt / tau) per frame. He may leave the
        // frame for a moment after a long warp; the camera never jumps after him.
        /** Followed point: two cascaded exponential filters (a critically damped 2nd-order follow, about 0.8 s overall). */
        static final double TAU_FOLLOW = 0.4;
        /** The camera's offset from the followed eye, two stages: removes any step the wall rays make. */
        static final double TAU_CAM = 0.25;
        double ox1;
        double oy1;
        double oz1;
        double ox2;
        double oy2;
        double oz2;
        /** Orbit distance: pulled in toward a wall faster than it eases back out. */
        static final double TAU_DIST_IN = 0.35;
        static final double TAU_DIST_OUT = 1.5;
        /** Turn rate of the drift away from a wall, eased. */
        static final double TAU_DRIFT = 0.8;
        /** The slow sweep round him, degrees per second of game time. */
        static final double SWEEP_DEG_PER_S = 8.0;
        double fx;
        double fy;
        double fz;
        double driftVel;
        double cx;
        double cy;
        double cz;
        float yaw;
        float pitch;
        boolean placed;

        private double ease(double tau) {
            double dt = s / 60.0;
            return 1 - Math.exp(-dt / tau);
        }

        /** One frame: follow him, turn, place the camera; returns its distance from his eyes. Client thread. */
        double step(net.minecraft.client.Minecraft mc, boolean snap) {
            Vec3 p = mc.player.position();
            if (snap) {
                fx = tx = p.x;
                fy = ty = p.y;
                fz = tz = p.z;
            } else {
                double a = ease(TAU_FOLLOW);
                fx += (p.x - fx) * a;
                fy += (p.y - fy) * a;
                fz += (p.z - fz) * a;
                tx += (fx - tx) * a;
                ty += (fy - ty) * a;
                tz += (fz - tz) * a;
            }
            double dt = s / 60.0;
            double allowed = free(mc, angle);
            double want = Math.hypot(RADIUS, HEIGHT);
            double driftGoal = 0;
            if (!snap && allowed < want - 1.0) {
                // boxed in on this side: drift round toward the more open side, faster the tighter it is
                double left = Math.max(free(mc, angle - 40), free(mc, angle - 75));
                double right = Math.max(free(mc, angle + 40), free(mc, angle + 75));
                if (Math.abs(right - left) > 0.5) {
                    driftGoal = Math.signum(right - left) * (allowed < 4.0 ? 60.0 : 25.0);
                }
            }
            driftVel += (driftGoal - driftVel) * (snap ? 1.0 : ease(TAU_DRIFT));
            angle += snap ? 0 : (SWEEP_DEG_PER_S + driftVel) * dt;
            allowed = free(mc, angle);
            if (snap) {
                dist = allowed;
            } else {
                dist += (allowed - dist) * ease(allowed < dist ? TAU_DIST_IN : TAU_DIST_OUT);
            }
            Vec3 e = eye();
            double d = dist;
            Vec3 goal = e.add(dir(angle).scale(d));
            // a block without collision (fire, plants, a ladder) is no wall to the ray: aim off it (the goal only; the
            // eased camera position below takes it there smoothly)
            for (int guard = 0; guard < 40 && d > 1.2 && !clearAround(mc, goal); guard++) {
                d -= 0.2;
                goal = e.add(dir(angle).scale(d));
            }
            // The camera is the eased eye plus an eased OFFSET (not an eased position): it moves rigidly with the
            // followed point, so his drop off #9 does not leave it hanging above him looking straight down (run 1: the
            // yaw spun at 1100 degrees/s near pitch 62), and two filter stages keep the offset's velocity continuous
            // when a wall pulls the orbit in (run 1, frame 2035: a one-frame jump from 16 to 30 blocks/s).
            Vec3 off = goal.subtract(e);
            if (snap || !placed) {
                ox1 = ox2 = off.x;
                oy1 = oy2 = off.y;
                oz1 = oz2 = off.z;
                placed = true;
            } else {
                double a = ease(TAU_CAM);
                ox1 += (off.x - ox1) * a;
                oy1 += (off.y - oy1) * a;
                oz1 += (off.z - oz1) * a;
                ox2 += (ox1 - ox2) * a;
                oy2 += (oy1 - oy2) * a;
                oz2 += (oz1 - oz2) * a;
            }
            cx = e.x + ox2;
            cy = e.y + oy2;
            cz = e.z + oz2;
            Vec3 cam = new Vec3(cx, cy, cz);
            Vec3 look = e.subtract(cam);
            yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
            VideoCamera.set(cam.x, cam.y, cam.z, yaw, pitch);
            return cam.distanceTo(mc.player.getEyePosition());
        }
    }

    /** His route with the SECRET awaits as short DELAY ones and #5's breaker cut to the blocks #6/#7 fly through. */
    private static List<JsonObject> videoNodes(UiCase c) {
        List<JsonObject> nodes = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(MUSEUM_ROUTE).getAsJsonArray()) {
            JsonObject n = e.getAsJsonObject();
            // Pauses only where the next warp does not fly through blocks a breaker just opened: a held frame costs
            // about 13x its game time in wall time, and the sim regrows broken blocks on the wall clock, so #7's and
            // #9's holds (after breakers #5 and #8) found their holes closed again (run 2, node #9: "no ray lands").
            // The crypt node's own await would be skipped by a left click mid-kill; it just kills its crypt.
            n.remove("awaitEnabled");
            n.remove("await");
            n.remove("amount");
            if (PAUSED.contains(nodes.size() + 1)) {
                n.addProperty("awaitEnabled", true);
                n.addProperty("await", "SECRET");
                n.addProperty("amount", 1);
            }
            nodes.add(n);
        }
        // #17 (a warp up off the drop, no landing recorded) never lands in the sim's Museum (run 3: "no landing after
        // 503 ticks"), so the clip's route ends on #16's drop.
        nodes.remove(nodes.size() - 1);
        JsonObject n5 = nodes.get(4);
        Set<String> listed = new LinkedHashSet<>();
        for (JsonElement e : n5.getAsJsonArray("blocks")) {
            listed.add(e.getAsString());
        }
        Set<String> crossed = new LinkedHashSet<>();
        for (JsonObject w : List.of(nodes.get(5), nodes.get(6))) {
            double x0 = w.get("x").getAsDouble();
            double y0 = w.get("y").getAsDouble() + 1.27;
            double z0 = w.get("z").getAsDouble();
            double yr = Math.toRadians(w.get("yaw").getAsDouble());
            double pr = Math.toRadians(w.get("pitch").getAsDouble());
            double dx = -Math.sin(yr) * Math.cos(pr);
            double dy = -Math.sin(pr);
            double dz = Math.cos(yr) * Math.cos(pr);
            String[] land = w.get("landing").getAsString().split(" ");
            double lx = Double.parseDouble(land[0]) - x0;
            double ly = Double.parseDouble(land[1]) - y0;
            double lz = Double.parseDouble(land[2]) - z0;
            double len = Math.sqrt(lx * lx + ly * ly + lz * lz) + 2.0;
            for (double t = 0; t <= len; t += 0.05) {
                String b = (int) Math.floor(x0 + dx * t) + " " + (int) Math.floor(y0 + dy * t) + " "
                        + (int) Math.floor(z0 + dz * t);
                if (listed.contains(b)) {
                    crossed.add(b);
                }
            }
        }
        c.note("node #5's blocks on #6's and #7's rays: " + crossed);
        if (!crossed.isEmpty()) {
            JsonArray blocks = new JsonArray();
            crossed.forEach(blocks::add);
            n5.add("blocks", blocks);
        }
        return nodes;
    }

    // ------------------------------------------------------------------------------------------ world helpers

    private static final class McChat {
        static void clear(net.minecraft.client.Minecraft mc) {
            dev.testkit.compat.McCompat.chat(mc).clearMessages(false);
        }
    }

    private static boolean logHas(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void serverRun(ClientGameTestContext ctx, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> task) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    task.accept(server.getPlayerList().getPlayer(uuid));
                } finally {
                    done.set(true);
                }
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(3);
    }

    private static void giveHotbar(ClientGameTestContext ctx) {
        serverRun(ctx, sp -> {
            var inv = sp.getInventory();
            for (int i = 0; i < 36; i++) {
                inv.setItem(i, ItemStack.EMPTY);
            }
            String[] ids = {"ASPECT_OF_THE_VOID", "SUPERBOOM_TNT", "DUNGEONBREAKER", null, "BAT_WAND"};
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != null) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
            }
        });
        ctx.waitTicks(10);
    }

    private static void writeRoute(ClientGameTestContext ctx, List<JsonObject> nodes) {
        long loadMark = LogTap.mark();
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonArray arr = new JsonArray();
                nodes.forEach(arr::add);
                JsonObject r = new JsonObject();
                r.add("nodes", arr);
                JsonObject routes = new JsonObject();
                routes.add(ROOM, r);
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                root.add("routes", routes);
                Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
                ModUnderTest.staticCall(STORE, "reload");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        for (int i = 0; i < 100 && !logHas(loadMark, "Loaded 1 route"); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(2);
    }

    /** Server teleport to relative feet (x, y, z) looking along a relative yaw. */
    private static void tpRelY(ClientGameTestContext ctx, double x, double y, double z, float relYaw, float pitch) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 p = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, y, z});
            float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(), float.class},
                    new Object[]{frame, relYaw});
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.teleportTo(server.overworld(), p.x, p.y, p.z, java.util.Set.<net.minecraft.world.entity.Relative>of(),
                        yaw, pitch, false);
                done.set(true);
            });
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        });
        ctx.waitFor(mc -> done.get() != null, 100);
        ctx.waitTicks(2);
    }
}
