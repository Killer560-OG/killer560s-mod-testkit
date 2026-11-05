package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 1104-sim-spawnload: every way the sim puts him somewhere, several times, judged at the moment the loading screen comes
 * down (mod simspawn-1104; killer560 2026-11-04: "sometimes spawning into an area i am put into walls or blocks and it
 * takes forever to load and the loading bar never updates").
 *
 * <p>Per load: the loading screen's bar (SimLoadingScreen.lastDrawn) is sampled every tick it is up and must RISE (at
 * least two increases, never back, reaching 50%) - a bar stuck at 0 fails; and when he is let in, his box must collide
 * with nothing on the server and on his client, his client must hold the chunk he stands in, and 20 ticks later he must
 * be on the ground, still clear, and not have dropped more than 2 blocks. Loads: a floor from the title screen (a fresh
 * world open), a new floor from inside, a single room from inside (the menu's path), /goto three rooms, the boss from
 * inside at P1, S1, P4 and P5, and the boss from the title screen at P2. Repeated {@code -Dtestkit.spawnloadReps}
 * times (default 3). Each load's ticks and the mod's "[SimPhase]" timing lines are printed.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimSpawnLoadTests implements FabricClientGameTest {

    private static final String NAME = "1104-sim-spawnload";
    private static final String R = "com.killer560.hub.roomsim.";
    private static final String B = "com.killer560.hub.bosssim.";
    private static final int MAX_TICKS = 20 * 180;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(R + "RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(R + "RoomLibrary", "isReady"), 2400);
        int reps = Integer.getInteger("testkit.spawnloadReps", 3);
        List<String> f = new ArrayList<>();
        long mark = LogTap.mark();
        int loads = 0;
        try {
            for (int rep = 1; rep <= reps; rep++) {
                if (rep % 2 == 0) {
                    loads += load(ctx, f, "boss P2 from the title #" + rep, mc -> boss(mc, "P2"), true, true) ? 1 : 0;
                }
                String code = planF7(ctx);
                if (code == null) {
                    f.add("no F7 could be planned");
                    break;
                }
                loads += load(ctx, f, (rep % 2 == 0 ? "floor from inside #" : "floor from the title #") + rep,
                        mc -> floor(mc, code), true, false) ? 1 : 0;
                String code2 = planF7(ctx);
                loads += load(ctx, f, "new floor from inside #" + rep, mc -> floor(mc, code2), true, false) ? 1 : 0;
                List<String> rooms = ctx.computeOnClient(mc -> roomNames());
                for (int i = 0; i < 3 && i < rooms.size(); i++) {
                    String room = rooms.get((rep * 7 + i * 5) % rooms.size());
                    loads += load(ctx, f, "/goto " + room + " #" + rep, mc -> ModUnderTest.staticCall(R + "SimTeleportCommands",
                            "goTo", new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, room}), false, false) ? 1 : 0;
                }
                String single = rooms.isEmpty() ? "Entrance" : rooms.get((rep * 3) % rooms.size());
                loads += load(ctx, f, "room " + single + " from inside #" + rep, mc -> ModUnderTest.staticCall(R + "SimBuilder",
                        "buildSingleRoom", new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, single}), true, false) ? 1 : 0;
                for (String phase : new String[]{"P1", "S1", "P4", "P5"}) {
                    loads += load(ctx, f, "boss " + phase + " from inside #" + rep, mc -> boss(mc, phase), true, true) ? 1 : 0;
                }
                SimPuzzleSolveTests.teardown(ctx);
            }
            for (String l : LogTap.since(mark)) {
                if (l.contains("[SimPhase]") && (l.contains("stages") || l.contains("world loaded") || l.contains("not clear")
                        || l.contains("no clear") || l.contains("still had") || l.contains("re-tracking"))) {
                    System.out.println("[" + NAME + "] mod: " + l.substring(Math.max(0, l.indexOf("[SimPhase]"))));
                }
            }
            for (String e : LogTap.modErrorsSince(mark)) {
                f.add("mod ERROR: " + e);
            }
            int want = reps * 9 + reps / 2;
            if (loads < want) {
                f.add("only " + loads + " of " + want + " loads were judged");
            }
        } catch (Throwable t) {
            f.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            if (ctx.computeOnClient(mc -> mc.level != null)) {
                SimPuzzleSolveTests.teardown(ctx);
            }
        }
        for (String s : f) {
            System.out.println("[" + NAME + "] FAIL: " + s);
        }
        if (!f.isEmpty()) {
            throw new AssertionError(f.size() + " problem(s): " + (f.size() > 30 ? f.subList(0, 30) + " ..." : f));
        }
        System.out.println("[" + NAME + "] PASS (" + loads + " loads)");
    }

    /** One load: asks for it, samples the bar while the screen is up, then judges where he was let in. */
    private static boolean load(ClientGameTestContext ctx, List<String> f, String label, Consumer<Minecraft> ask,
                                boolean wantBar, boolean boss) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ask.accept(mc)));
        List<Float> bar = new ArrayList<>();
        boolean screen = false;
        int calm = 0;
        int t = 0;
        for (; t < MAX_TICKS && calm < 3; t++) {
            ctx.waitTick();
            Object[] s = ctx.computeOnClient(mc -> new Object[]{
                    McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(R + "SimLoadingScreen"),
                    ModUnderTest.staticCall(R + "SimLoadingScreen", "lastDrawn", new Class<?>[]{}, new Object[]{}),
                    mc.level != null && mc.player != null && mc.getSingleplayerServer() != null,
                    ModUnderTest.staticCall(R + "SimWorld", "isBuilding", new Class<?>[]{}, new Object[]{}),
                    ModUnderTest.staticCall(R + "SimArrival", "holding", new Class<?>[]{}, new Object[]{}),
                    ModUnderTest.staticCall(R + "SimBuildQueue", "buildsFinished", new Class<?>[]{}, new Object[]{}),
                    !boss || (Boolean) ModUnderTest.staticCall(B + "BossFight", "running", new Class<?>[]{}, new Object[]{})});
            boolean up = (Boolean) s[0];
            if (up) {
                screen = true;
                bar.add((Float) s[1]);
            }
            boolean built = !wantBar || (Long) s[5] > before;
            boolean settled = !up && (Boolean) s[2] && !(Boolean) s[3] && !(Boolean) s[4] && built && (Boolean) s[6];
            // A /goto never builds: give its teleport a few ticks to land before judging.
            calm = settled && (wantBar || t >= 5) ? calm + 1 : 0;
        }
        if (calm < 3) {
            f.add(label + ": never let in within " + MAX_TICKS + " ticks (bar " + summary(bar) + ")");
            return false;
        }
        int rises = 0;
        float last = -1f;
        boolean back = false;
        for (float v : bar) {
            if (last >= 0 && v > last + 1e-4f) rises++;
            if (v < last - 1e-4f) back = true;
            last = v;
        }
        float max = bar.isEmpty() ? 0f : java.util.Collections.max(bar);
        if (wantBar) {
            if (!screen) {
                f.add(label + ": the loading screen never came up");
            } else if (rises < 2 || max < 0.5f) {
                f.add(label + ": the bar did not rise (" + rises + " rise(s), max " + pct(max) + ", " + summary(bar) + ")");
            }
            if (back) {
                f.add(label + ": the bar went backward (" + summary(bar) + ")");
            }
        }
        String first = judge(ctx, f, label, null);
        ctx.waitTicks(20);
        String later = judge(ctx, f, label + " (20 ticks on)", first);
        System.out.println("[" + NAME + "] " + label + ": let in after " + t + " ticks, bar " + (screen ? summary(bar)
                + " (" + rises + " rises)" : "not shown") + ", at " + first + " -> " + later);
        return true;
    }

    /** Where he stands, failing if his box hits a block (server and client) or his chunk is missing; null on error. */
    private static String judge(ClientGameTestContext ctx, List<String> f, String label, String firstAt) {
        AtomicReference<String> server = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            MinecraftServer s = mc.getSingleplayerServer();
            java.util.UUID id = mc.player.getUUID();
            s.execute(() -> {
                ServerPlayer sp = s.getPlayerList().getPlayer(id);
                if (sp == null) {
                    server.set("no server player");
                    return;
                }
                boolean clear = sp.level().noCollision(sp, sp.getBoundingBox().deflate(0.01));
                server.set(clear ? "ok" : "inside " + blocksAt(sp));
            });
        });
        for (int i = 0; i < 40 && server.get() == null; i++) {
            ctx.waitTick();
        }
        String[] out = ctx.computeOnClient(mc -> {
            var p = mc.player;
            boolean chunk = mc.level.getChunkSource().hasChunk(p.getBlockX() >> 4, p.getBlockZ() >> 4);
            boolean clear = mc.level.noCollision(p, p.getBoundingBox().deflate(0.01));
            return new String[]{String.format(Locale.ROOT, "%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ()),
                    chunk ? "ok" : "no chunk", clear ? "ok" : "inside a block", p.onGround() ? "ok" : "in the air",
                    String.valueOf(p.getY())};
        });
        if (!"ok".equals(server.get())) f.add(label + ": server says " + server.get() + " at " + out[0]);
        if (!"ok".equals(out[1])) f.add(label + ": his client has no chunk at " + out[0]);
        if (!"ok".equals(out[2])) f.add(label + ": his client has him inside a block at " + out[0]);
        if (firstAt != null) {
            if (!"ok".equals(out[3])) f.add(label + ": not standing on anything at " + out[0]);
            double y0 = Double.parseDouble(firstAt.split(" ")[1]);
            if (Double.parseDouble(out[4]) < y0 - 2) f.add(label + ": fell from " + firstAt + " to " + out[0]);
        }
        return out[0];
    }

    private static String blocksAt(ServerPlayer sp) {
        BlockPos feet = sp.blockPosition();
        return sp.level().getBlockState(feet).getBlock() + " / " + sp.level().getBlockState(feet.above()).getBlock();
    }

    private static void floor(Minecraft mc, String code) {
        Consumer<Minecraft> build = c -> ModUnderTest.staticCall(R + "SimBuilder", "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
        ModUnderTest.staticCall(R + "SimWorld", "open", new Class<?>[]{Minecraft.class, String.class, Consumer.class,
                String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
    }

    private static void boss(Minecraft mc, String phase) {
        Object start = ModUnderTest.enumValue(B + "BossFight$Start", phase);
        ModUnderTest.staticCall(B + "BossSim", "start", new Class<?>[]{Minecraft.class, start.getClass(), boolean.class},
                new Object[]{mc, start, false});
    }

    private static List<String> roomNames() {
        List<String> out = new ArrayList<>();
        Object placed = ModUnderTest.staticCall(R + "SimRoomIndex", "placed", new Class<?>[]{}, new Object[]{});
        for (Object p : (List<?>) placed) {
            String n = (String) ModUnderTest.call(p, "name", new Class<?>[0], new Object[0]);
            if (!out.contains(n) && !n.contains("Trap")) out.add(n);   // trap rooms are not functional yet
        }
        return out;
    }

    private static String planF7(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object floor = ModUnderTest.enumValue(R + "SimFloorGen$Floor", "F7");
            Object planned = ModUnderTest.staticCall(R + "SimFloorGen", "plan",
                    new Class<?>[]{floor.getClass(), int.class, int.class}, new Object[]{floor, 4, 5});
            return planned == null ? null : (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
        });
    }

    private static String summary(List<Float> bar) {
        if (bar.isEmpty()) return "no samples";
        StringBuilder b = new StringBuilder();
        float last = -1f;
        int shown = 0;
        for (float v : bar) {
            if (Math.abs(v - last) > 1e-4f && shown++ < 12) {
                b.append(b.isEmpty() ? "" : " ").append(pct(v));
            }
            last = v;
        }
        return b + (shown > 12 ? " ..." : "") + " over " + bar.size() + " ticks";
    }

    private static String pct(float v) {
        return Math.round(v * 100) + "%";
    }
}
