package dev.testkit.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 1027-zpewc-* - the Zero Ping Etherwarp campaign (killer560, 2026-10-27: "Do lots of tests on inventory walk, zpew, and
 * blink. Make sure nothing flags; test them under every circumstance imaginable."). ZPEW is camera only, so the cases
 * measure three things: that it sends NOTHING (packet for packet the same as a ZPEW-off run, and no packet ever sent from
 * a zpew frame), how well its prediction matches the server's real etherwarp (hx/dungeon/HxAbilities) over every target
 * shape, motion, tuner count, chain and latency, and that the camera always comes back to the player - after a hit, a
 * miss, a correction, a refused warp - never stuck. Every shot proves it acted (a prediction made, camera frames drawn
 * from it, a server warp counted) before its numbers are quoted.
 *
 * <p>The server's etherwarp is the testkit's model of Hypixel's, the same voxel walk the mod aims with, but its
 * "solid" test is the vanilla collision shape, where the mod's is its own Hypixel-tuned list (skulls, ladders, flower
 * pots, lanterns, candles, comparators, every snow layer pass through). Shots on those blocks are reported as a
 * model difference, not as ZPEW's error.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ZpewCampaignTests implements FabricClientGameTest {

    static final String ZP = "zpew.ZeroPingEtherwarp";
    static final String ZP_CFG = "zpew.ZeroPingEtherwarpConfig";
    static final String ST = "smoothtp.SmoothTeleport";
    static final String ST_CFG = "smoothtp.SmoothTeleportConfig";
    static final String EO = "etherwarpoverlay.EtherwarpOverlayFeature";
    static final String EO_CFG = "etherwarpoverlay.EtherwarpOverlayConfig";
    static final String LC_CFG = "leftclickew.LeftClickEtherwarpConfig";
    /** Feet level on every station; the floor is Y - 1. */
    static final int Y = 151;
    static final int X0 = -7000;
    static final int Z0 = -7000;
    static final int[] LATENCIES = {0, 50, 150, 300};

    static String aotv(int tuners) {
        return "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,tuned_transmission:" + tuners
                + "},unbreakable={}]";
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        List<Consumer<ClientGameTestContext>> cases = new ArrayList<>();
        for (int lat : LATENCIES) {
            cases.add(c -> shapes(c, lat));
        }
        for (int lat : LATENCIES) {
            cases.add(c -> motion(c, lat));
        }
        for (int lat : LATENCIES) {
            cases.add(c -> chains(c, lat));
        }
        for (int lat : LATENCIES) {
            cases.add(c -> refused(c, lat));
        }
        for (int lat : LATENCIES) {
            cases.add(c -> identical(c, lat));
        }
        cases.add(ZpewCampaignTests::sources);
        cases.add(ZpewCampaignTests::lcew);
        cases.add(ZpewCampaignTests::overlay);
        cases.add(ZpewCampaignTests::smoothTp);
        cases.add(ZpewCampaignTests::cost);
        for (Consumer<ClientGameTestContext> c : cases) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ==== shared helpers ===========================================================================================

    static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    static String fmt(Vec3 p) {
        return p == null ? "-" : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", p.x, p.y, p.z);
    }

    static long num(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, getter)).longValue());
    }

    static long numBy(ClientGameTestContext ctx, String getter, String source) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(ZP, getter, source)).longValue());
    }

    static Vec3 camera(Minecraft mc) {
        for (String name : new String[]{"getMainCamera", "mainCamera"}) {
            try {
                Object cam = mc.gameRenderer.getClass().getMethod(name).invoke(mc.gameRenderer);
                return (Vec3) cam.getClass().getMethod("position").invoke(cam);
            } catch (NoSuchMethodException ignored) {
                // the other version's name
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("no camera accessor");
    }

    /** Where vanilla would draw the first-person camera right now (no partial tick: read at rest). */
    static Vec3 playerEye(Minecraft mc) {
        return mc.player.position().add(0, mc.player.getEyeHeight(), 0);
    }

    /** Every prediction ZPEW holds right now, oldest first (its private queue, read on the client thread). */
    static List<Object> pendingRecords() {
        ArrayDeque<?> d = (ArrayDeque<?>) Mod.field(ZP, "PENDING");
        synchronized (d) {
            return new ArrayList<>(d);
        }
    }

    static Vec3 landingOf(Object prediction) {
        try {
            Method m = prediction.getClass().getDeclaredMethod("landing");
            m.setAccessible(true);
            return (Vec3) m.invoke(prediction);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- capture: every prediction made (END of the tick that sent it) and every server position packet ---------

    static volatile boolean capturing;
    static final Map<Object, Boolean> SEEN_PRED = Collections.synchronizedMap(new IdentityHashMap<>());
    static final List<Vec3> PREDS = Collections.synchronizedList(new ArrayList<>());
    static final List<Vec3> TELEPORTS = Collections.synchronizedList(new ArrayList<>());
    static final List<long[]> TELEPORT_TIMES = Collections.synchronizedList(new ArrayList<>());
    /** Runs at START of the next client tick, inside it (a use sent here is inside the tick, as vanilla's is). */
    static volatile Runnable atTickStart;
    static boolean hooked;

    static void hook(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (hooked) {
                return;
            }
            hooked = true;
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (!capturing) {
                    return;
                }
                try {
                    for (Object p : pendingRecords()) {
                        if (SEEN_PRED.put(p, Boolean.TRUE) == null) {
                            PREDS.add(landingOf(p));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // no mod field: nothing to capture
                }
            });
            ClientTickEvents.START_CLIENT_TICK.register(client -> {
                Runnable r = atTickStart;
                if (r != null) {
                    atTickStart = null;
                    r.run();
                }
            });
        });
    }

    static void captureStart(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            SEEN_PRED.clear();
            PREDS.clear();
            TELEPORTS.clear();
            PacketTrace.inTap = p -> {
                if (p instanceof ClientboundPlayerPositionPacket pp && capturing) {
                    TELEPORTS.add(pp.change().position());
                }
            };
            capturing = true;
        });
    }

    static void captureStop(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            capturing = false;
            PacketTrace.inTap = null;
        });
    }

    static String name(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.player.getName().getString());
    }

    static JsonObject stats(Hx hx) {
        return hx.call("dungeon.stats").getAsJsonObject();
    }

    static Vec3 lastLanding(JsonObject s) {
        if (!s.has("lastLanding")) {
            return null;
        }
        JsonArray a = s.getAsJsonArray("lastLanding");
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    static float[] aim(Vec3 eye, Vec3 at) {
        Vec3 d = at.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        return new float[]{yaw, pitch};
    }

    static void look(ClientGameTestContext ctx, float yaw, float pitch) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
        });
    }

    /** Server teleport to a station start; waits until the client stands there. */
    static void tpTo(ClientGameTestContext ctx, TestServer server, String who, double x, double y, double z, boolean ground) {
        server.command(String.format(Locale.ROOT, "tp %s %.4f %.4f %.4f -90 0", who, x, y, z));
        for (int i = 0; i < 100; i++) {
            ctx.waitTicks(1);
            boolean there = ctx.computeOnClient(mc -> Math.hypot(mc.player.getX() - x, mc.player.getZ() - z) < 0.001
                    && (!ground || mc.player.onGround()));
            if (there) {
                break;
            }
        }
        ctx.waitTicks(ground ? 6 : 1);
    }

    static Hx setUp(ClientGameTestContext ctx, int tuners) {
        hook(ctx);
        Hx hx = Hx.connect();
        hx.call("dungeon.abilities", "enabled", true);
        hx.give(0, aotv(tuners));
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Mod.set(ZP_CFG, "setEnabled", true);
            Mod.set(ZP_CFG, "setChain", true);
            Mod.set(ZP_CFG, "setOverlayFromPrediction", true);
            Mod.set(ZP_CFG, "setTimeoutMs", 1000);
            for (Object s : Mod.cls("zpew.ZpewSource").getEnumConstants()) {
                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", ((Enum<?>) s).name(), true);
            }
        });
        ctx.waitTicks(10);
        return hx;
    }

    static void tearDown(ClientGameTestContext ctx, Hx hx) {
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.getInput().releaseKey(o -> o.keyUp);
        captureStop(ctx);
        ctx.runOnClient(mc -> {
            Latency.remove();
            PacketTrace.tap = null;
            PacketTrace.inTap = null;
            Mod.set(ZP_CFG, "setEnabled", false);
            Mod.set(ZP_CFG, "setChain", true);
            Mod.set(ZP_CFG, "setTimeoutMs", 1000);
            for (Object s : Mod.cls("zpew.ZpewSource").getEnumConstants()) {
                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", ((Enum<?>) s).name(), true);
            }
        });
        if (hx != null) {
            hx.call("dungeon.abilities", "enabled", true);
            hx.call("dungeon.abilities", "enabled", false);
            hx.close();
        }
        ctx.waitTicks(20);
    }

    static void latency(ClientGameTestContext ctx, int ms) {
        ctx.runOnClient(mc -> {
            if (ms > 0) {
                Latency.install(ms);
            } else {
                Latency.remove();
            }
        });
        ctx.waitTicks(20);
    }

    // ==== one shot =================================================================================================

    /** What one etherwarp did: ZPEW's side and the server's. */
    record Shot(String what, Vec3 pred, Vec3 server, long preds, long hits, long misses, long timeouts, long noLanding,
                long skipped, long camFrames, double camAtPred, double endCam, int warps, int emuMisses, int transmissions,
                int pendingTicks) {

        double err() {
            return pred == null || server == null ? -1 : pred.distanceTo(server);
        }

        String verdict() {
            if (pred != null && server != null) {
                double e = err();
                return e <= 0.01 ? "HIT" : e <= 1.0 ? "HIT~" : "MISPREDICT";
            }
            if (pred != null) {
                return "PREDICTED-BUT-NO-WARP";
            }
            if (server != null) {
                return "WARP-NOT-PREDICTED";
            }
            return "BOTH-NONE";
        }

        String line() {
            return String.format(Locale.ROOT, "%-22s %-22s pred %s server %s err %.3f | zpew p%d h%d m%d t%d nl%d sk%d, "
                            + "%d cam frames, cam-vs-pred-eye %.3f, pending %d tick(s), end cam-vs-eye %.3f | server warps %d"
                            + " emu-misses %d transmissions %d", what, verdict(), fmt(pred), fmt(server), err(), preds, hits,
                    misses, timeouts, noLanding, skipped, camFrames, camAtPred, pendingTicks, endCam, warps, emuMisses,
                    transmissions);
        }
    }

    /** A snapshot of every counter a shot is judged by. */
    record Counters(long preds, long hits, long misses, long timeouts, long noLanding, long frames, long skipped, int warps,
                    int emuMisses, int transmissions) {
    }

    static Counters counters(ClientGameTestContext ctx, Hx hx) {
        long[] z = ctx.computeOnClient(mc -> {
            long sk = 0;
            for (Object s : Mod.cls("zpew.ZpewSource").getEnumConstants()) {
                sk += ((Number) Mod.staticCall(ZP, "skippedBy", ((Enum<?>) s).name())).longValue();
            }
            return new long[]{((Number) Mod.staticCall(ZP, "predictions")).longValue(),
                    ((Number) Mod.staticCall(ZP, "hits")).longValue(), ((Number) Mod.staticCall(ZP, "misses")).longValue(),
                    ((Number) Mod.staticCall(ZP, "timeouts")).longValue(),
                    ((Number) Mod.staticCall(ZP, "noLanding")).longValue(),
                    ((Number) Mod.staticCall(ZP, "cameraFrames")).longValue(), sk};
        });
        JsonObject s = stats(hx);
        return new Counters(z[0], z[1], z[2], z[3], z[4], z[5], z[6], s.get("etherwarps").getAsInt(),
                s.get("etherwarpMisses").getAsInt(), s.get("transmissions").getAsInt());
    }

    /**
     * Sends one use (whatever {@code fire} does, in the next tick) and follows it until ZPEW holds nothing and the server
     * has answered: the prediction, the camera while it was pending, the server's landing, and the camera at the end.
     */
    static Shot shot(ClientGameTestContext ctx, Hx hx, String what, Runnable fire, int latencyMs) {
        Counters c0 = counters(ctx, hx);
        captureStart(ctx);
        fire.run();
        double camAtPred = -1;
        int pendingTicks = 0;
        int minTicks = latencyMs / 50 + 4;
        Counters c1 = null;
        int quiet = 0;
        for (int t = 0; t < 80; t++) {
            ctx.waitTicks(1);
            double[] snap = ctx.computeOnClient(mc -> {
                List<Object> pend = pendingRecords();
                if (pend.isEmpty()) {
                    return new double[]{0, -1};
                }
                Vec3 l = landingOf(pend.get(pend.size() - 1));
                Vec3 eye = new Vec3(l.x, l.y + mc.player.getEyeHeight(), l.z);
                return new double[]{pend.size(), camera(mc).distanceTo(eye)};
            });
            if (snap[0] > 0) {
                pendingTicks++;
                camAtPred = Math.max(camAtPred, snap[1]);
                quiet = 0;
                continue;
            }
            if (t < minTicks) {
                continue;
            }
            Counters now = counters(ctx, hx);
            boolean answered = now.warps != c0.warps || now.emuMisses != c0.emuMisses
                    || now.transmissions != c0.transmissions;
            if (answered || t > 45) {
                if (++quiet >= 3 || t > 45) {
                    c1 = now;
                    break;
                }
            }
        }
        ctx.waitTicks(8);
        captureStop(ctx);
        if (c1 == null) {
            c1 = counters(ctx, hx);
        }
        Counters c2 = counters(ctx, hx);
        double endCam = ctx.computeOnClient(mc -> camera(mc).distanceTo(playerEye(mc)));
        Vec3 pred;
        synchronized (PREDS) {
            pred = PREDS.isEmpty() ? null : PREDS.get(0);
        }
        Vec3 server;
        synchronized (TELEPORTS) {
            server = c2.warps != c0.warps && !TELEPORTS.isEmpty() ? TELEPORTS.get(0) : null;
        }
        if (server == null && c2.warps != c0.warps) {
            server = lastLanding(stats(hx));
        }
        return new Shot(what, pred, server, c2.preds - c0.preds, c2.hits - c0.hits, c2.misses - c0.misses,
                c2.timeouts - c0.timeouts, c2.noLanding - c0.noLanding, c2.skipped - c0.skipped, c2.frames - c0.frames,
                camAtPred, endCam, c2.warps - c0.warps, c2.emuMisses - c0.emuMisses, c2.transmissions - c0.transmissions,
                pendingTicks);
    }

    /** A right click by hand at a look. */
    static Runnable press(ClientGameTestContext ctx, float yaw, float pitch) {
        return () -> {
            look(ctx, yaw, pitch);
            ctx.getInput().pressKey(o -> o.keyUse);
        };
    }

    /** A right click aimed from where he stands now at {@code at}. */
    static Runnable pressAt(ClientGameTestContext ctx, Vec3 at) {
        return () -> {
            float[] a = ctx.computeOnClient(mc -> aim(mc.player.position().add(0, 1.27, 0), at));
            look(ctx, a[0], a[1]);
            ctx.getInput().pressKey(o -> o.keyUse);
        };
    }

    // ==== stations =================================================================================================

    /** A target layout on its own lane: the player starts at (X0, Y, z) looking east; targets are east of him. */
    record Station(String name, int tuners, List<String> setup, Vec3 aimAt, float yaw, float pitch, boolean modelsAgree,
                   String note) {
    }

    static String sb(int x, int y, int z, String block) {
        return String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", x, y, z, block);
    }

    static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:%s", x1, y1, z1, x2, y2, z2, block);
    }

    static Station at(String name, int lane, List<String> setup, double ax, double ay, String note, boolean agree) {
        int z = Z0 + lane * 10;
        return new Station(name, 4, setup, new Vec3(ax, ay, z + 0.5), Float.NaN, Float.NaN, agree, note);
    }

    /** The stations, lane by lane. Target x is X0 + 12 unless said. */
    static List<Station> stations() {
        List<Station> s = new ArrayList<>();
        int tx = X0 + 12;
        int lane = 0;
        int z;
        z = Z0 + 10 * lane;
        s.add(at("full-stone", lane++, List.of(sb(tx, Y, z, "stone")), tx + 0.5, Y + 1, "", true));
        z = Z0 + 10 * lane;
        s.add(at("slab-bottom", lane++, List.of(sb(tx, Y, z, "stone_slab[type=bottom]")), tx + 0.5, Y + 0.5, "", true));
        z = Z0 + 10 * lane;
        s.add(at("slab-top", lane++, List.of(sb(tx, Y, z, "stone_slab[type=top]")), tx + 0.5, Y + 1, "", true));
        z = Z0 + 10 * lane;
        s.add(at("stairs", lane++, List.of(sb(tx, Y, z, "stone_stairs[facing=east,half=bottom]")), tx + 0.75, Y + 1, "",
                true));
        z = Z0 + 10 * lane;
        s.add(at("stairs-upside", lane++, List.of(sb(tx, Y, z, "stone_stairs[facing=east,half=top]")), tx + 0.5, Y + 1, "",
                true));
        z = Z0 + 10 * lane;
        s.add(at("carpet", lane++, List.of(sb(tx, Y, z, "white_carpet")), tx + 0.5, Y + 0.0625, "", true));
        z = Z0 + 10 * lane;
        s.add(at("fence-voxel", lane++, List.of(sb(tx, Y, z, "oak_fence")), tx + 0.5, Y + 0.9, "", true));
        z = Z0 + 10 * lane;
        s.add(at("fence-over-voxel", lane++, List.of(sb(tx, Y, z, "oak_fence")), tx + 0.5, Y + 1.4,
                "aimed at the post above its own cell: the voxel walk passes over", true));
        z = Z0 + 10 * lane;
        s.add(at("glass", lane++, List.of(sb(tx, Y, z, "glass")), tx + 0.5, Y + 1, "", true));
        z = Z0 + 10 * lane;
        s.add(at("leaves", lane++, List.of(sb(tx, Y, z, "oak_leaves[persistent=true]")), tx + 0.5, Y + 1, "", true));
        z = Z0 + 10 * lane;
        s.add(at("lever-floor", lane++, List.of(sb(tx, Y, z, "lever[face=floor,facing=east]")), tx + 0.5, Y, "", true));
        z = Z0 + 10 * lane;
        s.add(at("lever-wall", lane++, List.of(sb(tx, Y, z, "stone"), sb(tx - 1, Y, z, "lever[face=wall,facing=west]")),
                tx - 0.2, Y + 0.5, "", true));
        z = Z0 + 10 * lane;
        s.add(at("skull-floor", lane++, List.of(sb(tx, Y, z, "skeleton_skull")), tx + 0.5, Y + 0.25,
                "mod: heads pass through and block feet; emulator: a skull is solid", false));
        z = Z0 + 10 * lane;
        s.add(at("skull-wall", lane++, List.of(sb(tx, Y, z, "stone"), sb(tx, Y + 1, z, "stone"),
                sb(tx - 1, Y + 1, z, "skeleton_wall_skull[facing=west]")), tx - 0.25, Y + 1.25,
                "mod: heads pass through; emulator: a skull is solid", false));
        z = Z0 + 10 * lane;
        s.add(at("snow-1", lane++, List.of(sb(tx, Y, z, "snow[layers=1]")), tx + 0.5, Y + 0.1, "", true));
        z = Z0 + 10 * lane;
        s.add(at("snow-4", lane++, List.of(sb(tx, Y, z, "snow[layers=4]")), tx + 0.5, Y + 0.4,
                "mod: every snow layer passes; emulator: layers 2+ are solid", false));
        z = Z0 + 10 * lane;
        s.add(at("snow-8", lane++, List.of(sb(tx, Y, z, "snow[layers=8]")), tx + 0.5, Y + 0.9,
                "mod: every snow layer passes; emulator: layers 2+ are solid", false));
        z = Z0 + 10 * lane;
        s.add(at("water-bottom", lane++, List.of(fill(X0 + 5, Y - 2, z, X0 + 10, Y - 2, z, "stone"),
                fill(X0 + 5, Y - 1, z, X0 + 10, Y - 1, z, "water")), X0 + 9.5, Y - 1, "the bottom of a 1-deep pool", true));
        z = Z0 + 10 * lane;
        s.add(at("water-edge", lane++, List.of(fill(X0 + 5, Y - 2, z, X0 + 10, Y - 2, z, "stone"),
                fill(X0 + 5, Y - 1, z, X0 + 10, Y - 1, z, "water")), X0 + 11.0, Y - 0.5,
                "the pool's far edge tile, reached through the water", true));
        z = Z0 + 10 * lane;
        s.add(at("lava-edge", lane++, List.of(fill(X0 + 5, Y - 2, z, X0 + 10, Y - 2, z, "stone"),
                fill(X0 + 5, Y - 1, z, X0 + 10, Y - 1, z, "lava")), X0 + 11.0, Y - 0.5,
                "the pool's far edge tile, reached through the lava", true));
        z = Z0 + 10 * lane;
        s.add(at("gap-1wide", lane++, List.of(fill(tx - 4, Y, z - 1, tx + 1, Y + 2, z - 1, "stone"),
                fill(tx - 4, Y, z + 1, tx + 1, Y + 2, z + 1, "stone")), tx + 0.5, Y, "the floor of a 1-wide slot", true));
        z = Z0 + 10 * lane;
        s.add(at("window-1x1", lane++, List.of(fill(tx - 3, Y, z - 1, tx - 3, Y + 3, z + 1, "stone"),
                sb(tx - 3, Y + 1, z, "air"), sb(tx, Y, z, "stone")), tx + 0.5, Y + 1, "through a 1x1 hole in a wall", true));
        z = Z0 + 10 * lane;
        s.add(at("ceiling-low", lane++, List.of(sb(tx, Y, z, "stone"), sb(tx, Y + 2, z, "stone")), tx + 0.5, Y + 1,
                "head room 1: no landing", true));
        z = Z0 + 10 * lane;
        s.add(at("ceiling-ok", lane++, List.of(sb(tx, Y, z, "stone"), sb(tx, Y + 3, z, "stone")), tx + 0.5, Y + 1,
                "head room 2: lands", true));
        z = Z0 + 10 * lane;
        s.add(at("diagonal", lane++, List.of(sb(X0 + 9, Y, z + 4, "stone")), X0 + 9.5, Y + 1, "off the lane's axis", true));
        z = Z0 + 10 * lane;
        s.add(new Station("no-target-sky", 4, List.of(), null, -90f, -30f, true, "nothing to hit"));
        lane++;
        // Max range: a floating block on the eye's row, pitch 0 due east. The eye is at x X0 + 0.5, the walk reaches the
        // cell under x0 + range, so the block at X0 + (57 + t) is the last one in range and X0 + 58 + t the first out.
        for (int t = 0; t <= 4; t++) {
            for (int out = 0; out <= 1; out++) {
                z = Z0 + 10 * lane;
                int d = 57 + t + out;
                s.add(new Station("range-t" + t + (out == 0 ? "-in" : "-out"), t, List.of(sb(X0 + d, Y + 1, z, "stone")),
                        null, -90f, 0f, true, (out == 0 ? "last block in range " : "first block out of range ") + d));
                lane++;
            }
        }
        return s;
    }

    static void buildStations(TestServer server, List<Station> list) {
        TestMap.on(server).platform(X0, Y - 1, Z0, 4).catchFloor(120).survival().clearInventory()
                .spawn(X0 + 0.5, Z0 + 0.5, -90f).build();
        // Vanilla fill/setblock refuse an unloaded chunk ("That position is not loaded": the first run's lanes were never
        // built and he fell through every one). Keep the whole strip loaded, load it with testkit fill (which loads
        // what it touches), and only then place the blocks that need a state.
        server.command(String.format(Locale.ROOT, "forceload add %d %d %d %d", X0 - 3, Z0 - 3, X0 + 70,
                Z0 + 10 * list.size() + 3));
        for (int lane = 0; lane < list.size(); lane++) {
            int z = Z0 + 10 * lane;
            server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:air", X0 - 3, Y, z - 3,
                    X0 + 70, Y + 6, z + 3));
            server.command(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d minecraft:stone", X0 - 3, Y - 1,
                    z - 2, X0 + 30, Y - 1, z + 2));
        }
        server.sync(120);
        for (int lane = 0; lane < list.size(); lane++) {
            for (String cmd : list.get(lane).setup) {
                server.command(cmd);
            }
        }
        server.command("effect give @a minecraft:resistance infinite 255 true");
        server.command("effect give @a minecraft:fire_resistance infinite 0 true");
        server.sync(120);
    }

    // ==== 1027-zpewc-shapes-<latency> ===============================================================================

    static void shapes(ClientGameTestContext ctx, int lat) {
        List<Station> list = stations();
        Scenario.run(ctx, "1027-zpewc-shapes-" + lat, (server, scenario) -> buildStations(server, list), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<Shot> shots = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                latency(ctx, lat);
                int tunersHeld = 4;
                for (int lane = 0; lane < list.size(); lane++) {
                    Station st = list.get(lane);
                    if (st.tuners != tunersHeld) {
                        hx.give(0, aotv(st.tuners));
                        tunersHeld = st.tuners;
                        ctx.waitTicks(5);
                    }
                    int z = Z0 + 10 * lane;
                    tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                    Runnable fire = st.aimAt != null ? pressAt(ctx, st.aimAt) : press(ctx, st.yaw, st.pitch);
                    Shot sh = shot(ctx, hx, st.name, fire, lat);
                    shots.add(sh);
                    scenario.log("shot " + sh.line() + (st.note.isEmpty() ? "" : " | " + st.note));
                    if (sh.endCam > 0.05) {
                        problems.add(st.name + ": camera left " + String.format(Locale.ROOT, "%.3f", sh.endCam)
                                + " off his eye at the end (stuck)");
                    }
                    if (sh.pred != null && sh.camFrames == 0 && lat >= 50) {
                        problems.add(st.name + ": predicted but no camera frame was drawn from it");
                    }
                    if (sh.pred != null && sh.camAtPred > 0.05) {
                        problems.add(st.name + ": while pending the camera was " + sh.camAtPred + " off the predicted eye");
                    }
                    String v = sh.verdict();
                    if (st.modelsAgree && !(v.equals("HIT") || v.equals("BOTH-NONE"))) {
                        problems.add(st.name + ": " + v + " (err " + String.format(Locale.ROOT, "%.3f", sh.err()) + ")");
                    }
                }
            } finally {
                tearDown(ctx, hx);
            }
            summarize(scenario, "shapes " + lat + " ms", shots);
            check(problems.isEmpty(), "shapes " + lat + " ms: " + problems);
        });
    }

    static void summarize(Scenario scenario, String label, List<Shot> shots) {
        Map<String, Integer> by = new LinkedHashMap<>();
        double maxErr = 0;
        double sumErr = 0;
        int hit = 0;
        for (Shot s : shots) {
            by.merge(s.verdict(), 1, Integer::sum);
            if (s.verdict().startsWith("HIT")) {
                hit++;
                sumErr += s.err();
                maxErr = Math.max(maxErr, s.err());
            }
        }
        scenario.log(String.format(Locale.ROOT, "SUMMARY %s: %d shot(s) %s; hits: mean error %.4f, max %.4f", label,
                shots.size(), by, hit == 0 ? 0 : sumErr / hit, maxErr));
    }

    // ==== 1027-zpewc-motion-<latency> ===============================================================================

    static void motion(ClientGameTestContext ctx, int lat) {
        List<Station> lanes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            lanes.add(new Station("motion" + i, 4, List.of(sb(X0 + 12, Y, Z0 + 10 * i, "stone")), null, 0, 0, true, ""));
        }
        Scenario.run(ctx, "1027-zpewc-motion-" + lat, (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<Shot> shots = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                latency(ctx, lat);
                Vec3[] target = new Vec3[8];
                for (int i = 0; i < 8; i++) {
                    target[i] = new Vec3(X0 + 12.5, Y + 1, Z0 + 10 * i + 0.5);
                }
                for (int rep = 0; rep < 3; rep++) {
                    // standing, sneaking
                    ctx.getInput().holdKey(o -> o.keyShift);
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                    shots.add(shot(ctx, hx, "stand-sneak", pressAt(ctx, target[0]), lat));
                    // walking forward while sneaking
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 10.5, true);
                    look(ctx, -90f, 0f);
                    ctx.getInput().holdKey(o -> o.keyUp);
                    ctx.waitTicks(6);
                    shots.add(shot(ctx, hx, "walk-sneak", pressAt(ctx, target[1]), lat));
                    ctx.getInput().releaseKey(o -> o.keyUp);
                    // jump, rising
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 20.5, true);
                    ctx.getInput().pressKey(o -> o.keyJump);
                    ctx.waitTicks(2);
                    shots.add(shot(ctx, hx, "jump-rising", pressAt(ctx, target[2]), lat));
                    // jump, falling back
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 30.5, true);
                    ctx.getInput().pressKey(o -> o.keyJump);
                    ctx.waitTicks(7);
                    shots.add(shot(ctx, hx, "jump-falling", pressAt(ctx, target[3]), lat));
                    // falling from 8 up
                    tpTo(ctx, server, who, X0 + 0.5, Y + 8, Z0 + 40.5, false);
                    ctx.waitTicks(6);
                    shots.add(shot(ctx, hx, "falling", pressAt(ctx, target[4]), lat));
                    ctx.waitTicks(20);
                    // not sneaking: Instant Transmission, never predicted
                    ctx.getInput().releaseKey(o -> o.keyShift);
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 50.5, true);
                    shots.add(shot(ctx, hx, "no-sneak", pressAt(ctx, target[5]), lat));
                    // sneak pressed on the use's own tick: the server has no sneak yet - Transmission, not predicted
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 60.5, true);
                    shots.add(shot(ctx, hx, "sneak-same-tick", () -> {
                        float[] a = ctx.computeOnClient(mc -> aim(mc.player.position().add(0, 1.27, 0), target[6]));
                        look(ctx, a[0], a[1]);
                        ctx.getInput().holdKey(o -> o.keyShift);
                        ctx.getInput().pressKey(o -> o.keyUse);
                    }, lat));
                    // sneak released on the use's own tick: the server still has sneak - an etherwarp, predicted
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 70.5, true);
                    ctx.waitTicks(4);
                    shots.add(shot(ctx, hx, "unsneak-same-tick", () -> {
                        float[] a = ctx.computeOnClient(mc -> aim(mc.player.position().add(0, 1.27, 0), target[7]));
                        look(ctx, a[0], a[1]);
                        ctx.getInput().releaseKey(o -> o.keyShift);
                        ctx.getInput().pressKey(o -> o.keyUse);
                    }, lat));
                    ctx.waitTicks(5);
                }
                for (Shot sh : shots) {
                    scenario.log("shot " + sh.line());
                    if (sh.endCam > 0.05) {
                        problems.add(sh.what + ": camera stuck " + sh.endCam);
                    }
                    String v = sh.verdict();
                    if (sh.what.endsWith("same-tick")) {
                        // Whichever way vanilla's order put it, ZPEW and the server must agree: an etherwarp predicted
                        // and hit, or a Transmission with no prediction and no camera move.
                        boolean agree = v.equals("HIT") || (sh.preds == 0 && sh.camFrames == 0 && sh.transmissions == 1);
                        if (!agree) {
                            problems.add(sh.what + ": ZPEW and the server disagree: " + sh.line());
                        }
                        continue;
                    }
                    boolean transmission = sh.what.equals("no-sneak");
                    if (transmission) {
                        if (sh.preds != 0 || sh.camFrames != 0 || sh.transmissions != 1) {
                            problems.add(sh.what + ": expected a transmission with no prediction and no camera move, got "
                                    + sh.preds + " prediction(s), " + sh.camFrames + " frame(s), " + sh.transmissions
                                    + " transmission(s)");
                        }
                    } else if (!v.equals("HIT")) {
                        problems.add(sh.what + ": " + v + " err " + sh.err());
                    }
                }
            } finally {
                tearDown(ctx, hx);
            }
            summarize(scenario, "motion " + lat + " ms", shots);
            check(problems.isEmpty(), "motion " + lat + " ms: " + problems);
        });
    }

    // ==== 1027-zpewc-chain-<latency> ================================================================================

    /** Presses use at the look from {@code feet}'s sneaking eye to {@code at}; returns the landing it aims for. */
    static void pressFrom(ClientGameTestContext ctx, Vec3 feet, Vec3 at) {
        float[] a = aim(feet.add(0, 1.27, 0), at);
        look(ctx, a[0], a[1]);
        ctx.getInput().pressKey(o -> o.keyUse);
    }

    static void chains(ClientGameTestContext ctx, int lat) {
        List<Station> lanes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lanes.add(new Station("chain" + i, 4, List.of(), null, 0, 0, true, ""));
        }
        Scenario.run(ctx, "1027-zpewc-chain-" + lat, (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                latency(ctx, lat);
                for (int rep = 0; rep < 3; rep++) {
                    // five warps one tick apart, each aimed from the last one's landing (Chain From Prediction on)
                    {
                        int z = Z0;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        Counters c0 = counters(ctx, hx);
                        captureStart(ctx);
                        Vec3 from = new Vec3(X0 + 0.5, Y, z + 0.5);
                        int maxPending = 0;
                        for (int k = 1; k <= 5; k++) {
                            Vec3 tgt = new Vec3(X0 + 5 * k + 0.5, Y, z + 0.5);
                            pressFrom(ctx, from, tgt);
                            ctx.waitTicks(1);
                            from = new Vec3(X0 + 5 * k + 0.5, Y + 0.05, z + 0.5);
                            maxPending = Math.max(maxPending, ctx.computeOnClient(mc -> pendingRecords().size()));
                        }
                        int[] stuck = {0};
                        ctx.waitTicks(30 + lat / 50);
                        captureStop(ctx);
                        Counters c1 = counters(ctx, hx);
                        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
                        double endCam = ctx.computeOnClient(mc -> camera(mc).distanceTo(playerEye(mc)));
                        String line = String.format(Locale.ROOT, "chain5 rep %d: %d prediction(s) %d hit(s) %d miss(es) %d "
                                        + "timeout(s), %d server warp(s), up to %d pending, end %s, end cam-vs-eye %.3f; "
                                        + "predicted %s; server %s", rep, c1.preds - c0.preds, c1.hits - c0.hits,
                                c1.misses - c0.misses, c1.timeouts - c0.timeouts, c1.warps - c0.warps, maxPending, fmt(end),
                                endCam, PREDS, TELEPORTS);
                        scenario.log(line);
                        if (c1.warps - c0.warps != 5 || c1.hits - c0.hits != 5 || c1.misses != c0.misses
                                || endCam > 0.05 || Math.abs(end.x - (X0 + 25.5)) > 0.01) {
                            problems.add(line);
                        }
                        if (lat >= 150 && maxPending < 2) {
                            problems.add("chain5 rep " + rep + ": never more than one pending at " + lat + " ms");
                        }
                        stuck[0] = 0;
                    }
                    // two uses in ONE tick at the same look: the second is cast from the first's landing
                    {
                        int z = Z0 + 10;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        Counters c0 = counters(ctx, hx);
                        captureStart(ctx);
                        float[] a = aim(new Vec3(X0 + 0.5, Y + 1.27, z + 0.5), new Vec3(X0 + 8.5, Y, z + 0.5));
                        look(ctx, a[0], a[1]);
                        ctx.getInput().pressKey(o -> o.keyUse);
                        ctx.getInput().pressKey(o -> o.keyUse);
                        ctx.waitTicks(30 + lat / 50);
                        captureStop(ctx);
                        Counters c1 = counters(ctx, hx);
                        double endCam = ctx.computeOnClient(mc -> camera(mc).distanceTo(playerEye(mc)));
                        String line = String.format(Locale.ROOT, "double-press rep %d: %d prediction(s) %d hit(s) %d miss(es),"
                                        + " %d server warp(s), end cam-vs-eye %.3f; predicted %s; server %s", rep,
                                c1.preds - c0.preds, c1.hits - c0.hits, c1.misses - c0.misses, c1.warps - c0.warps, endCam,
                                PREDS, TELEPORTS);
                        scenario.log(line);
                        if (c1.hits - c0.hits != c1.warps - c0.warps || c1.preds - c0.preds != c1.warps - c0.warps
                                || c1.misses != c0.misses || endCam > 0.05) {
                            problems.add(line);
                        }
                    }
                    // a no-landing use in the middle of a chain: not predicted, and the next is still cast right
                    {
                        int z = Z0 + 20;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        Counters c0 = counters(ctx, hx);
                        captureStart(ctx);
                        Vec3 from = new Vec3(X0 + 0.5, Y, z + 0.5);
                        pressFrom(ctx, from, new Vec3(X0 + 8.5, Y, z + 0.5));
                        ctx.waitTicks(1);
                        look(ctx, -90f, -60f);
                        ctx.getInput().pressKey(o -> o.keyUse);
                        ctx.waitTicks(1);
                        pressFrom(ctx, new Vec3(X0 + 8.5, Y + 0.05, z + 0.5), new Vec3(X0 + 16.5, Y, z + 0.5));
                        ctx.waitTicks(30 + lat / 50);
                        captureStop(ctx);
                        Counters c1 = counters(ctx, hx);
                        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
                        double endCam = ctx.computeOnClient(mc -> camera(mc).distanceTo(playerEye(mc)));
                        String line = String.format(Locale.ROOT, "chain-with-sky rep %d: %d prediction(s) %d hit(s) %d "
                                        + "miss(es) %d no-landing, %d server warp(s) %d emu miss(es), end %s cam-vs-eye %.3f",
                                rep, c1.preds - c0.preds, c1.hits - c0.hits, c1.misses - c0.misses,
                                c1.noLanding - c0.noLanding, c1.warps - c0.warps, c1.emuMisses - c0.emuMisses, fmt(end),
                                endCam);
                        scenario.log(line);
                        if (c1.preds - c0.preds != 2 || c1.hits - c0.hits != 2 || c1.noLanding - c0.noLanding != 1
                                || c1.warps - c0.warps != 2 || endCam > 0.05 || Math.abs(end.x - (X0 + 16.5)) > 0.01) {
                            problems.add(line);
                        }
                    }
                    // Chain From Prediction OFF: later uses are cast from where he stands - by design wrong while one is
                    // pending. Recorded; the camera must still end on him.
                    {
                        int z = Z0 + 30;
                        ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setChain", false));
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        Counters c0 = counters(ctx, hx);
                        captureStart(ctx);
                        Vec3 from = new Vec3(X0 + 0.5, Y, z + 0.5);
                        for (int k = 1; k <= 3; k++) {
                            pressFrom(ctx, from, new Vec3(X0 + 6 * k + 0.5, Y, z + 0.5));
                            ctx.waitTicks(1);
                            from = new Vec3(X0 + 6 * k + 0.5, Y + 0.05, z + 0.5);
                        }
                        ctx.waitTicks(30 + lat / 50);
                        captureStop(ctx);
                        ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setChain", true));
                        Counters c1 = counters(ctx, hx);
                        double endCam = ctx.computeOnClient(mc -> camera(mc).distanceTo(playerEye(mc)));
                        String line = String.format(Locale.ROOT, "chain-off rep %d: %d prediction(s) %d hit(s) %d miss(es) "
                                        + "%d timeout(s), %d server warp(s), end cam-vs-eye %.3f; predicted %s; server %s",
                                rep, c1.preds - c0.preds, c1.hits - c0.hits, c1.misses - c0.misses,
                                c1.timeouts - c0.timeouts, c1.warps - c0.warps, endCam, PREDS, TELEPORTS);
                        scenario.log(line);
                        if (endCam > 0.05 || c1.warps - c0.warps != 3) {
                            problems.add(line);
                        }
                    }
                }
            } finally {
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "chain " + lat + " ms: " + problems);
        });
    }

    // ==== 1027-zpewc-refused-<latency> ==============================================================================

    static void refused(ClientGameTestContext ctx, int lat) {
        List<Station> lanes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lanes.add(new Station("refused" + i, 4, List.of(sb(X0 + 12, Y, Z0 + 10 * i, "stone")), null, 0, 0, true, ""));
        }
        Scenario.run(ctx, "1027-zpewc-refused-" + lat, (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                latency(ctx, lat);
                for (int rep = 0; rep < 3; rep++) {
                    // 1. the server does nothing with the use: the camera waits out Give Up After (1000 ms), then he
                    {
                        int z = Z0;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        hx.call("dungeon.abilities", "enabled", false);
                        long t0 = System.nanoTime();
                        Shot sh = shot(ctx, hx, "ignored", pressAt(ctx, new Vec3(X0 + 12.5, Y + 1, z + 0.5)), lat);
                        hx.call("dungeon.abilities", "enabled", true);
                        scenario.log("refused rep " + rep + " " + sh.line() + String.format(Locale.ROOT, " (%.0f ms)",
                                (System.nanoTime() - t0) / 1e6));
                        if (sh.preds != 1 || sh.timeouts != 1 || sh.endCam > 0.05 || sh.camFrames == 0
                                || sh.pendingTicks < 17 || sh.pendingTicks > 24) {
                            problems.add("ignored rep " + rep + ": " + sh.line());
                        }
                    }
                    // 2. a correction while it is pending: the server moves him 3 blocks back; the camera is on him on the
                    //    frame that position lands
                    {
                        int z = Z0 + 10;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        hx.call("dungeon.abilities", "enabled", false);
                        TestServer srv = server;
                        Shot sh = shot(ctx, hx, "corrected", () -> {
                            pressAt(ctx, new Vec3(X0 + 12.5, Y + 1, z + 0.5)).run();
                            ctx.waitTicks(2);
                            srv.command(String.format(Locale.ROOT, "tp %s %.4f %d %.4f", who, X0 - 1.5, Y, z + 0.5));
                        }, lat);
                        hx.call("dungeon.abilities", "enabled", true);
                        scenario.log("refused rep " + rep + " " + sh.line());
                        if (sh.preds != 1 || sh.misses != 1 || sh.timeouts != 0 || sh.endCam > 0.05
                                || sh.pendingTicks > 2 + lat / 50 + 3) {
                            problems.add("corrected rep " + rep + ": " + sh.line());
                        }
                    }
                    // 3. the world changed under the client: a wall the client has not heard about yet takes the warp
                    {
                        int z = Z0 + 20;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        server.command(fill(X0 + 6, Y, z - 1, X0 + 6, Y + 1, z + 1, "stone"));
                        server.sync(10);
                        Shot sh = shot(ctx, hx, "wall-unseen", () -> {
                            look(ctx, aim(new Vec3(X0 + 0.5, Y + 1.27, z + 0.5), new Vec3(X0 + 12.5, Y + 1, z + 0.5))[0],
                                    aim(new Vec3(X0 + 0.5, Y + 1.27, z + 0.5), new Vec3(X0 + 12.5, Y + 1, z + 0.5))[1]);
                            ctx.getInput().pressKey(o -> o.keyUse);
                        }, lat);
                        scenario.log("refused rep " + rep + " " + sh.line());
                        if (sh.endCam > 0.05 || sh.timeouts != 0) {
                            problems.add("wall-unseen rep " + rep + ": " + sh.line());
                        }
                        server.command(fill(X0 + 6, Y, z - 1, X0 + 6, Y + 1, z + 1, "air"));
                        server.sync(10);
                        ctx.waitTicks(10);
                    }
                    // 4. a warp that lands, then a second one the server ignores (chained from the first's landing)
                    {
                        int z = Z0 + 30;
                        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
                        Hx h = hx;
                        Shot sh = shot(ctx, hx, "land-then-ignored", () -> {
                            pressFrom(ctx, new Vec3(X0 + 0.5, Y, z + 0.5), new Vec3(X0 + 8.5, Y, z + 0.5));
                            ctx.waitTicks(2);
                            h.call("dungeon.abilities", "enabled", false);
                            pressFrom(ctx, new Vec3(X0 + 8.5, Y + 0.05, z + 0.5), new Vec3(X0 + 16.5, Y, z + 0.5));
                        }, lat);
                        ctx.waitTicks(25);
                        hx.call("dungeon.abilities", "enabled", true);
                        double endCam = ctx.computeOnClient(mc -> camera(mc).distanceTo(playerEye(mc)));
                        scenario.log("refused rep " + rep + " " + sh.line() + String.format(Locale.ROOT,
                                " | after the give-up cam-vs-eye %.3f", endCam));
                        if (endCam > 0.05) {
                            problems.add("land-then-ignored rep " + rep + ": camera stuck " + endCam);
                        }
                    }
                }
            } finally {
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "refused " + lat + " ms: " + problems);
        });
    }

    // ==== 1027-zpewc-identical-<latency> ============================================================================

    /** One serverbound packet as compared between runs: its type, and the fields an anticheat reads. */
    static String describe(Packet<?> p) {
        String id = p.type().id().getPath();
        if (p instanceof ServerboundUseItemPacket u) {
            return String.format(Locale.ROOT, "use_item %s %.4f/%.4f", u.getHand(), u.getYRot(), u.getXRot());
        }
        if (p instanceof ServerboundPlayerInputPacket in) {
            return "player_input " + in.input();
        }
        if (p instanceof ServerboundMovePlayerPacket m) {
            return String.format(Locale.ROOT, "%s %.3f %.3f %.3f %.2f/%.2f %s", id, m.getX(0), m.getY(0), m.getZ(0),
                    m.getYRot(0), m.getXRot(0), m.isOnGround());
        }
        return id;
    }

    static final Set<String> REACTIVE = Set.of("pong", "keep_alive", "accept_teleportation", "chunk_batch_received",
            "client_tick_end", "client_information");

    /** The scripted session, the same every run: 7 uses (a plain warp, a jump warp, a no-sneak Transmission, a chain of
     *  two, a sky shot, a refused one). */
    static void script(ClientGameTestContext ctx, Hx hx, TestServer server, String who, int lat) {
        int z = Z0;
        tpTo(ctx, server, who, X0 + 0.5, Y, z + 0.5, true);
        ctx.getInput().holdKey(o -> o.keyShift);
        ctx.waitTicks(4);
        pressFrom(ctx, new Vec3(X0 + 0.5, Y, z + 0.5), new Vec3(X0 + 8.5, Y, z + 0.5));
        ctx.waitTicks(20 + lat / 50);
        ctx.getInput().pressKey(o -> o.keyJump);
        ctx.waitTicks(2);
        pressFrom(ctx, ctx.computeOnClient(mc -> mc.player.position()), new Vec3(X0 + 16.5, Y, z + 0.5));
        ctx.waitTicks(25 + lat / 50);
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.waitTicks(4);
        look(ctx, -90f, 10f);
        ctx.getInput().pressKey(o -> o.keyUse);
        ctx.waitTicks(20 + lat / 50);
        ctx.getInput().holdKey(o -> o.keyShift);
        ctx.waitTicks(4);
        Vec3 here = ctx.computeOnClient(mc -> mc.player.position());
        pressFrom(ctx, here, new Vec3(here.x - 6, Y, z + 0.5));
        ctx.waitTicks(1);
        pressFrom(ctx, new Vec3(Math.floor(here.x - 6) + 0.5, Y + 0.05, z + 0.5), new Vec3(here.x - 12, Y, z + 0.5));
        ctx.waitTicks(25 + lat / 50);
        look(ctx, -90f, -60f);
        ctx.getInput().pressKey(o -> o.keyUse);
        ctx.waitTicks(10 + lat / 50);
        hx.call("dungeon.abilities", "enabled", false);
        here = ctx.computeOnClient(mc -> mc.player.position());
        pressFrom(ctx, here, new Vec3(here.x + 8, Y, z + 0.5));
        ctx.waitTicks(30 + lat / 50);
        hx.call("dungeon.abilities", "enabled", true);
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.waitTicks(10);
    }

    static void identical(ClientGameTestContext ctx, int lat) {
        List<Station> lanes = List.of(new Station("identical", 4, List.of(), null, 0, 0, true, ""));
        Scenario.run(ctx, "1027-zpewc-identical-" + lat, (server, scenario) -> buildStations(server, lanes),
                (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                latency(ctx, lat);
                boolean[] modes = {false, true, false, true};
                List<List<String>> actions = new ArrayList<>();
                List<Map<String, Integer>> counts = new ArrayList<>();
                int[] fromZpew = {0};
                List<String> zpewFrames = Collections.synchronizedList(new ArrayList<>());
                for (int run = 0; run < modes.length; run++) {
                    boolean on = modes[run];
                    ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setEnabled", on));
                    long p0 = num(ctx, "predictions");
                    long f0 = num(ctx, "cameraFrames");
                    List<String> act = Collections.synchronizedList(new ArrayList<>());
                    Map<String, Integer> cnt = Collections.synchronizedMap(new LinkedHashMap<>());
                    ctx.runOnClient(mc -> PacketTrace.tap = p -> {
                        String d = describe(p);
                        String id = p.type().id().getPath();
                        if (!REACTIVE.contains(id)) {
                            cnt.merge(id.startsWith("move_player") ? "move_player*" : id, 1, Integer::sum);
                            if (!id.startsWith("move_player")) {
                                act.add(d);
                            }
                        }
                        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                            if (e.getClassName().contains(".hub.zpew.")) {
                                fromZpew[0]++;
                                zpewFrames.add(id + " from " + e);
                                break;
                            }
                        }
                    });
                    script(ctx, hx, server, who, lat);
                    ctx.runOnClient(mc -> PacketTrace.tap = null);
                    long preds = num(ctx, "predictions") - p0;
                    long frames = num(ctx, "cameraFrames") - f0;
                    scenario.log(String.format(Locale.ROOT, "run %d (zpew %s): %d prediction(s), %d camera frame(s); counts %s",
                            run, on ? "ON" : "off", preds, frames, cnt));
                    scenario.log("run " + run + " actions: " + act);
                    if (on && (preds < 4 || frames == 0)) {
                        problems.add("run " + run + ": ZPEW on but it predicted " + preds + " / drew " + frames
                                + " frame(s): nothing was tested");
                    }
                    if (!on && preds != 0) {
                        problems.add("run " + run + ": ZPEW off yet " + preds + " prediction(s)");
                    }
                    actions.add(new ArrayList<>(act));
                    counts.add(new LinkedHashMap<>(cnt));
                }
                for (int run = 1; run < modes.length; run++) {
                    if (!actions.get(run).equals(actions.get(0))) {
                        problems.add("run " + run + " (" + (modes[run] ? "on" : "off") + ") sent a different action "
                                + "sequence from run 0 (off): " + actions.get(run) + " vs " + actions.get(0));
                    }
                    Map<String, Integer> a = new LinkedHashMap<>(counts.get(run));
                    Map<String, Integer> b = new LinkedHashMap<>(counts.get(0));
                    a.remove("move_player*");
                    b.remove("move_player*");
                    if (!a.equals(b)) {
                        problems.add("run " + run + " packet counts " + counts.get(run) + " vs run 0 " + counts.get(0));
                    }
                }
                scenario.log("packets sent from a zpew frame: " + fromZpew[0] + " " + zpewFrames);
                if (fromZpew[0] != 0) {
                    problems.add(fromZpew[0] + " packet(s) were sent from ZPEW code: " + zpewFrames);
                }
            } finally {
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "identical " + lat + " ms: " + problems);
        });
    }

    // ==== 1027-zpewc-sources: every Works For switch gates its own source =========================================

    static void sources(ClientGameTestContext ctx) {
        List<Station> lanes = List.of(new Station("sources", 4, List.of(), null, 0, 0, true, ""));
        Scenario.run(ctx, "1027-zpewc-sources", (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            boolean cheat = Mod.isCheat();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                latency(ctx, 100);
                Object[] all = Mod.cls("zpew.ZpewSource").getEnumConstants();
                for (Object srcObj : all) {
                    String src = ((Enum<?>) srcObj).name();
                    boolean cheatOnly = (Boolean) Mod.call(srcObj, "cheatOnly");
                    for (boolean on : new boolean[]{false, true}) {
                        tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                        ctx.runOnClient(mc -> {
                            for (Object s : all) {
                                Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", ((Enum<?>) s).name(),
                                        s != srcObj || on);
                            }
                        });
                        float[] a = aim(new Vec3(X0 + 0.5, Y + 1.27, Z0 + 0.5), new Vec3(X0 + 10.5, Y, Z0 + 0.5));
                        look(ctx, a[0], a[1]);
                        ctx.waitTicks(2);
                        long p0 = numBy(ctx, "predictedBy", src);
                        long s0 = numBy(ctx, "skippedBy", src);
                        long h0 = numBy(ctx, "hitsBy", src);
                        long seen0 = numBy(ctx, "seenBy", src);
                        long allP0 = num(ctx, "predictions");
                        int w0 = stats(hx).get("etherwarps").getAsInt();
                        atTickStart = () -> {
                            Minecraft mc = Minecraft.getInstance();
                            try {
                                Class<?> z = Mod.cls(ZP);
                                Class<?> e = Mod.cls("zpew.ZpewSource");
                                Object prev = z.getMethod("sending", e).invoke(null, srcObj);
                                try {
                                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                                } finally {
                                    z.getMethod("done", e).invoke(null, prev);
                                }
                            } catch (ReflectiveOperationException ex) {
                                throw new AssertionError(ex);
                            }
                        };
                        ctx.waitTicks(20);
                        long p = numBy(ctx, "predictedBy", src) - p0;
                        long sk = numBy(ctx, "skippedBy", src) - s0;
                        long h = numBy(ctx, "hitsBy", src) - h0;
                        long seen = numBy(ctx, "seenBy", src) - seen0;
                        long allP = num(ctx, "predictions") - allP0;
                        int w = stats(hx).get("etherwarps").getAsInt() - w0;
                        boolean expectPredicted = on && (cheat || !cheatOnly);
                        String line = String.format(Locale.ROOT, "%s switch %s (%s jar%s): seen %d predicted %d hit %d "
                                        + "skipped %d, all predictions %d, server warps %d -> %s", src, on ? "ON" : "off",
                                cheat ? "cheat" : "legit", cheatOnly ? ", cheat source" : "", seen, p, h, sk, allP, w,
                                expectPredicted ? "expected predicted" : "expected skipped");
                        scenario.log(line);
                        boolean ok = w == 1 && seen == 1 && (expectPredicted ? (p == 1 && h == 1 && sk == 0 && allP == 1)
                                : (p == 0 && sk == 1 && allP == 0));
                        if (!ok) {
                            problems.add(line);
                        }
                    }
                }
            } finally {
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "sources: " + problems);
        });
    }

    // ==== 1027-zpewc-lcew: Left Click Etherwarp with ZPEW, and its switch =========================================

    static void lcew(ClientGameTestContext ctx) {
        if (!Mod.isCheat()) {
            Scenario.skipped("1027-zpewc-lcew", "Left Click Etherwarp is cheat-only");
            return;
        }
        List<Station> lanes = List.of(new Station("lcew", 4, List.of(), null, 0, 0, true, ""));
        Scenario.run(ctx, "1027-zpewc-lcew", (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.runOnClient(mc -> Mod.set(LC_CFG, "setEnabled", true));
                latency(ctx, 150);
                String[][] modes = {{"LEFT_CLICK on", "true", "true"}, {"LEFT_CLICK off", "false", "true"},
                        {"MANUAL off, LEFT_CLICK on", "true", "false"}};
                for (String[] m : modes) {
                    for (int rep = 0; rep < 3; rep++) {
                        boolean sneakHeld = rep != 1;
                        if (sneakHeld) {
                            ctx.getInput().holdKey(o -> o.keyShift);
                        } else {
                            ctx.getInput().releaseKey(o -> o.keyShift);
                        }
                        tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                        ctx.runOnClient(mc -> {
                            Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "LEFT_CLICK", Boolean.parseBoolean(m[1]));
                            Mod.call(Mod.cfg(ZP_CFG), "setWorksForByName", "MANUAL", Boolean.parseBoolean(m[2]));
                        });
                        long lp0 = numBy(ctx, "predictedBy", "LEFT_CLICK");
                        long ls0 = numBy(ctx, "skippedBy", "LEFT_CLICK");
                        long mp0 = numBy(ctx, "seenBy", "MANUAL");
                        Shot sh = shot(ctx, hx, "lcew " + m[0], () -> {
                            float[] a = aim(new Vec3(X0 + 0.5, Y + 1.27, Z0 + 0.5), new Vec3(X0 + 16.5, Y, Z0 + 0.5));
                            look(ctx, a[0], a[1]);
                            ctx.getInput().pressKey(o -> o.keyAttack);
                        }, 150);
                        long lp = numBy(ctx, "predictedBy", "LEFT_CLICK") - lp0;
                        long ls = numBy(ctx, "skippedBy", "LEFT_CLICK") - ls0;
                        long mp = numBy(ctx, "seenBy", "MANUAL") - mp0;
                        String line = sh.line() + String.format(Locale.ROOT, " | sneak held %s; LEFT_CLICK predicted %d "
                                + "skipped %d, MANUAL uses seen %d", sneakHeld, lp, ls, mp);
                        scenario.log(line);
                        boolean wantPred = Boolean.parseBoolean(m[1]);
                        boolean ok = sh.warps == 1 && mp == 0 && sh.endCam <= 0.05
                                && (wantPred ? lp == 1 && sh.verdict().equals("HIT") : lp == 0 && ls == 1 && sh.preds == 0);
                        if (!ok) {
                            problems.add(line);
                        }
                    }
                }
            } finally {
                ctx.runOnClient(mc -> Mod.set(LC_CFG, "setEnabled", false));
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "lcew: " + problems);
        });
    }

    // ==== 1027-zpewc-overlay: Overlay From Prediction ===============================================================

    static int outcomeX(String outcome) {
        // "drawn x, y, z ok"
        if (outcome == null || !outcome.startsWith("drawn ")) {
            return Integer.MIN_VALUE;
        }
        String[] parts = outcome.substring(6).split("[ ,]+");
        return Integer.parseInt(parts[0]);
    }

    static void overlay(ClientGameTestContext ctx) {
        List<Station> lanes = List.of(new Station("overlay", 4, List.of(), null, 0, 0, true, ""));
        Scenario.run(ctx, "1027-zpewc-overlay", (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.runOnClient(mc -> {
                    Mod.set(EO_CFG, "setEnabled", true);
                    Mod.set(ZP_CFG, "setTimeoutMs", 3000);
                });
                ctx.getInput().holdKey(o -> o.keyShift);
                latency(ctx, 150);
                for (boolean opt : new boolean[]{true, false, true, false}) {
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                    ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setOverlayFromPrediction", opt));
                    float[] a = aim(new Vec3(X0 + 0.5, Y + 1.27, Z0 + 0.5), new Vec3(X0 + 10.5, Y, Z0 + 0.5));
                    look(ctx, a[0], a[1]);
                    ctx.waitTicks(5);
                    String before = ctx.computeOnClient(mc -> (String) Mod.staticCall(EO, "lastOutcome"));
                    hx.call("dungeon.abilities", "enabled", false);
                    ctx.getInput().pressKey(o -> o.keyUse);
                    ctx.waitTicks(10);
                    String during = ctx.computeOnClient(mc -> (String) Mod.staticCall(EO, "lastOutcome"));
                    int pending = ctx.computeOnClient(mc -> pendingRecords().size());
                    Vec3 pred = ctx.computeOnClient(mc -> {
                        List<Object> p = pendingRecords();
                        return p.isEmpty() ? null : landingOf(p.get(p.size() - 1));
                    });
                    ctx.waitTicks(60);
                    String after = ctx.computeOnClient(mc -> (String) Mod.staticCall(EO, "lastOutcome"));
                    hx.call("dungeon.abilities", "enabled", true);
                    int xb = outcomeX(before);
                    int xd = outcomeX(during);
                    int xa = outcomeX(after);
                    String line = String.format(Locale.ROOT, "Overlay From Prediction %s: before '%s', while pending (%d, "
                            + "predicted %s) '%s', after the give-up '%s'", opt ? "ON" : "off", before, pending, fmt(pred),
                            during, after);
                    scenario.log(line);
                    boolean ok = pending == 1 && pred != null && xb > X0 && xb == xa
                            && (opt ? xd > xb + 5 : xd == xb);
                    if (!ok) {
                        problems.add(line);
                    }
                }
            } finally {
                ctx.runOnClient(mc -> Mod.set(EO_CFG, "setEnabled", false));
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "overlay: " + problems);
        });
    }

    // ==== 1027-zpewc-smoothtp: Smooth Teleport never glides a warp ZPEW already drew ===============================

    static void smoothTp(ClientGameTestContext ctx) {
        List<Station> lanes = List.of(new Station("smoothtp", 4, List.of(), null, 0, 0, true, ""));
        Scenario.run(ctx, "1027-zpewc-smoothtp", (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            scenario.assertDetectorWorks();
            Hx hx = null;
            List<String> problems = new ArrayList<>();
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.runOnClient(mc -> Mod.set(ST_CFG, "setEnabled", true));
                ctx.getInput().holdKey(o -> o.keyShift);
                latency(ctx, 150);
                for (boolean zp : new boolean[]{false, true, false, true}) {
                    tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                    ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setEnabled", zp));
                    float[] a = aim(new Vec3(X0 + 0.5, Y + 1.27, Z0 + 0.5), new Vec3(X0 + 12.5, Y, Z0 + 0.5));
                    look(ctx, a[0], a[1]);
                    ctx.waitTicks(3);
                    ctx.getInput().pressKey(o -> o.keyUse);
                    // per tick after the landing: how far behind his eye the camera is drawn
                    double worstAfter = 0;
                    boolean landed = false;
                    int afterTicks = 0;
                    for (int t = 0; t < 40 && afterTicks < 8; t++) {
                        ctx.waitTicks(1);
                        double[] s = ctx.computeOnClient(mc -> new double[]{mc.player.getX(),
                                camera(mc).distanceTo(playerEye(mc))});
                        if (s[0] > X0 + 10) {
                            landed = true;
                        }
                        if (landed) {
                            afterTicks++;
                            worstAfter = Math.max(worstAfter, s[1]);
                        }
                    }
                    String decision = ctx.computeOnClient(mc -> (String) Mod.staticCall(ST, "lastDecision"));
                    String line = String.format(Locale.ROOT, "Smooth Teleport on, ZPEW %s: landed %s, Smooth Teleport "
                            + "decided '%s', camera up to %.3f behind his eye over the 8 ticks after the landing",
                            zp ? "ON" : "off", landed, decision, worstAfter);
                    scenario.log(line);
                    boolean ok = landed && (zp ? decision.startsWith("zero ping") && worstAfter < 0.05
                            : decision.startsWith("glide"));
                    if (!ok) {
                        problems.add(line);
                    }
                }
            } finally {
                ctx.runOnClient(mc -> Mod.set(ST_CFG, "setEnabled", false));
                tearDown(ctx, hx);
            }
            check(problems.isEmpty(), "smoothtp: " + problems);
        });
    }

    // ==== 1027-zpewc-cost: what ZPEW costs per frame and per prediction ===========================================

    static void cost(ClientGameTestContext ctx) {
        List<Station> lanes = List.of(new Station("cost", 4, List.of(), null, 0, 0, true, ""));
        Scenario.run(ctx, "1027-zpewc-cost", (server, scenario) -> buildStations(server, lanes), (server, scenario) -> {
            Hx hx = null;
            try {
                hx = setUp(ctx, 4);
                String who = name(ctx);
                ctx.getInput().holdKey(o -> o.keyShift);
                tpTo(ctx, server, who, X0 + 0.5, Y, Z0 + 0.5, true);
                String idle = ctx.computeOnClient(mc -> bench(mc));
                hx.call("dungeon.abilities", "enabled", false);
                ctx.runOnClient(mc -> Mod.set(ZP_CFG, "setTimeoutMs", 3000));
                float[] a = aim(new Vec3(X0 + 0.5, Y + 1.27, Z0 + 0.5), new Vec3(X0 + 12.5, Y, Z0 + 0.5));
                look(ctx, a[0], a[1]);
                ctx.waitTicks(2);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(3);
                int pending = ctx.computeOnClient(mc -> pendingRecords().size());
                String busy = ctx.computeOnClient(mc -> bench(mc));
                ctx.waitTicks(70);
                hx.call("dungeon.abilities", "enabled", true);
                String ray = ctx.computeOnClient(mc -> {
                    try {
                        Class<?> tu = Mod.cls("livemap.autoclear.TeleportUtils");
                        Method m = tu.getMethod("getEtherPos", Vec3.class, float.class, float.class, double.class);
                        Vec3 eye = playerEye(mc);
                        int n = 20000;
                        for (int i = 0; i < 2000; i++) {
                            m.invoke(null, eye, -90f, 6f, 61.0);
                        }
                        long t0 = System.nanoTime();
                        for (int i = 0; i < n; i++) {
                            m.invoke(null, eye, -90f + (i % 7) * 0.1f, 6f, 61.0);
                        }
                        long t1 = System.nanoTime();
                        for (int i = 0; i < n; i++) {
                            m.invoke(null, eye, -90f, -10f, 61.0);
                        }
                        long t2 = System.nanoTime();
                        return String.format(Locale.ROOT, "prediction raycast: %.2f us onto the floor 12 ahead, %.2f us for "
                                + "a full 61-block miss", (t1 - t0) / 1e3 / n, (t2 - t1) / 1e3 / n);
                    } catch (ReflectiveOperationException e) {
                        return "raycast bench failed: " + e;
                    }
                });
                scenario.log("cost, nothing pending: " + idle);
                scenario.log("cost, one pending (" + pending + "): " + busy);
                scenario.log("cost, " + ray);
                check(pending == 1, "the bench never had a prediction pending");
            } finally {
                tearDown(ctx, hx);
            }
        });
    }

    /** ns per call of the frame hook {@code cameraPosition} (via a reflective handle: the overhead is included). */
    static String bench(Minecraft mc) {
        try {
            Class<?> z = Mod.cls(ZP);
            Method cam = z.getMethod("cameraPosition", net.minecraft.world.entity.Entity.class, boolean.class, Vec3.class);
            Method noop = ZpewCampaignTests.class.getDeclaredMethod("noop", net.minecraft.world.entity.Entity.class,
                    boolean.class, Vec3.class);
            Vec3 v = playerEye(mc);
            int n = 200_000;
            for (int i = 0; i < 20_000; i++) {
                cam.invoke(null, mc.player, false, v);
                noop.invoke(null, mc.player, false, v);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                cam.invoke(null, mc.player, false, v);
            }
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                noop.invoke(null, mc.player, false, v);
            }
            long t2 = System.nanoTime();
            return String.format(Locale.ROOT, "cameraPosition %.1f ns/call, an empty reflective call %.1f ns - ZPEW's own "
                    + "per-frame cost about %.1f ns", (t1 - t0) / (double) n, (t2 - t1) / (double) n,
                    ((t1 - t0) - (t2 - t1)) / (double) n);
        } catch (ReflectiveOperationException e) {
            return "bench failed: " + e;
        }
    }

    static Vec3 noop(net.minecraft.world.entity.Entity e, boolean detached, Vec3 v) {
        return v;
    }
}
