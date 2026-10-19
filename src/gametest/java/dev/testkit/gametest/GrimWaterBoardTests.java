package dev.testkit.gametest;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code 1018-waterboard-grim}: Auto Water Board itself, the whole board, against a real GrimAC (killer560, 2026-10-18: "my
 * auto water board failed to flick a lever during the test I just did").
 *
 * <p>The session pastes his captured Water Board (his {@code RoomLibrary}, water left out - a vanilla server would let it
 * flow) through Hx {@code dungeon.paste} at grid cell (4,4), puts up a Catacombs sidebar and lets the mod's real world scan
 * identify it, as 62-argrim does. Hypixel's three pushed-out colour columns are set (relative (15, 56, 17..19)), so Water
 * Board Solver reads board 0 / "012" off the room exactly as on Hypixel, and the auto plays it with Etherwarp Reposition
 * against {@code HxAbilities}' etherwarp. Like his run, the first warp after the third lever click gets no landing
 * ({@code dungeon.dropUseAfterLevers}); on main the auto then re-sent it 40 ticks later and clicked the next lever on the
 * tick after the landing with the server still holding the warp's look - RotationPlace.
 *
 * <p>Not vacuous: the solver must count every click of its solution (6+), every counted click must have flipped its lever
 * (the client's world, from the server's block updates), the drop must have happened, and GrimAC must say nothing.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimWaterBoardTests implements FabricClientGameTest {

    private static final String SESSION = "1018-waterboard-grim-session";
    private static final String CASE = "1018-waterboard-grim";
    private static final String ROOM = "Water Board";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String SOLVER = "com.killer560.hub.puzzlesolvers.WaterSolverFeature";
    private static final String COORDS = "com.killer560.hub.puzzlesolvers.PuzzleCoords";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autopuzzles.AutoPuzzlesConfig";
    private static final int CELL = 4;
    private static final int CENTRE = -185 + CELL * 32;
    private static final int F = 69;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ModUnderTest.require("killer560smod");
        Session.run(ctx, SESSION, (server, s) -> setup(ctx, s), s -> {
            s.test(CASE, GrimWaterBoardTests::caseAuto);
            teardown(ctx);
        });
    }

    // ============================================================================================ setup

    private static void setup(ClientGameTestContext ctx, Session s) {
        s.scenario().assertDetectorWorks();
        LogTap.install();
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            throw new AssertionError("needs his real rooms and room database (Mod Only Test instance)");
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            Object auto = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(auto, "setAutoWaterEnabled", false);
            ModUnderTest.set(auto, "setEtherwarpReposition", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.cheatutils.CheatUtilsConfig"), "setSecretAuraEnabled",
                    false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.secrettrigger.SecretTriggerbotConfig"), "setEnabled",
                    false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.leveraura.LeverAuraConfig"), "setEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoroutes.AutoRoutesConfig"), "setEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.puzzlesolvers.WaterSolverConfig"), "setEnabled", true);
        });
        Hx hx = s.hx();
        hx.call("dungeon.abilities", "enabled", true);
        s.server().command("gamemode survival @p");
        JsonObject result = paste(ctx, hx, ROOM);
        println("pasted \"" + ROOM + "\": " + result);
        List<String> pad = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 2; dz <= 4; dz++) {
                pad.add((CENTRE + dx) + " " + (F - 1) + " " + (CENTRE + dz) + " minecraft:stone");
            }
        }
        hx.call("dungeon.blocks", "blocks", pad);
        hx.call("dungeon.tp", "x", CENTRE + 0.5, "y", (double) F, "z", CENTRE + 3.5, "yaw", 0f, "pitch", 0f);
        ctx.waitTicks(10);
        hx.sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState", "isInDungeon"), 400);
        boolean known = false;
        for (int t = 0; t < 300 && !known; t++) {
            ctx.waitTicks(1);
            known = ROOM.equals(ctx.computeOnClient(GrimWaterBoardTests::liveRoom));
        }
        println("live map: " + ctx.computeOnClient(GrimWaterBoardTests::liveRoom));
        if (!known) {
            throw new AssertionError("the pasted Water Board was not identified by the live map's world scan");
        }
        hx.give(0, "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,tuned_transmission:4},"
                + "unbreakable={}]");
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(5);
    }

    // ============================================================================================ the case

    private static final Map<String, Boolean> LAST = new LinkedHashMap<>();
    private static final Map<String, Integer> FLIPS = new LinkedHashMap<>();
    private static final List<String> COUNTED = new ArrayList<>();
    private static volatile boolean watching;
    private static boolean hooked;
    private static int prevCount;
    private static Object prevNext;
    private static List<BlockPos> levers = List.of();

    private static void onEnd(net.minecraft.client.Minecraft mc) {
        if (!watching || mc.level == null) {
            return;
        }
        for (BlockPos p : levers) {
            BlockState st = mc.level.getBlockState(p);
            if (!(st.getBlock() instanceof LeverBlock)) {
                continue;
            }
            boolean on = st.getValue(LeverBlock.POWERED);
            Boolean was = LAST.put(p.toShortString(), on);
            if (was != null && was != on) {
                FLIPS.merge(p.toShortString(), 1, Integer::sum);
            }
        }
        try {
            int count = (Integer) ModUnderTest.staticCall(SOLVER, "getCountedClicks");
            Object next = ModUnderTest.staticCall(SOLVER, "nextClick");
            if (count > prevCount && prevNext != null) {
                COUNTED.add(((BlockPos) ModUnderTest.call(prevNext, "pos", new Class<?>[0], new Object[0])).toShortString());
            }
            prevCount = count;
            prevNext = next;
        } catch (Throwable ignored) {
            // the solver not up yet
        }
    }

    private static void caseAuto(Session c) {
        ClientGameTestContext ctx = c.ctx();
        Hx hx = c.hx();
        int[] cr = ctx.computeOnClient(mc -> (int[]) ModUnderTest.staticCall(LIVE_MAP, "currentRoomClayAndRotation"));
        if (cr == null) {
            throw new AssertionError("no room frame for Water Board");
        }
        // Hypixel's pushed-out colours: PURPLE (19), ORANGE (18), BLUE (17) - "012" - at relative (15, 56, z).
        List<String> wool = new ArrayList<>();
        String[] colour = {"purple_wool", "orange_wool", "blue_wool"};
        for (int i = 0; i < 3; i++) {
            BlockPos p = rel(ctx, 15, 56, 19 - i, cr);
            wool.add(p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:" + colour[i]);
        }
        hx.call("dungeon.blocks", "blocks", wool);
        // The seven levers, through the solver's own coordinates.
        List<BlockPos> ls = new ArrayList<>();
        int[][] rels = {{20, 61, 10}, {20, 61, 15}, {20, 61, 20}, {10, 61, 20}, {10, 61, 15}, {10, 61, 10}, {15, 60, 5}};
        for (int[] r : rels) {
            ls.add(rel(ctx, r[0], r[1], r[2], cr));
        }
        levers = ls;
        String leverStates = ctx.computeOnClient(mc -> {
            StringBuilder sb = new StringBuilder();
            for (BlockPos p : ls) {
                sb.append(p.toShortString()).append('=').append(mc.level.getBlockState(p).getBlock().getName().getString())
                        .append("; ");
            }
            return sb.toString();
        });
        println("levers: " + leverStates);
        // On the lever floor: standing on the walkway glass at relative (15, 58, 16).
        BlockPos stand = rel(ctx, 15, 58, 16, cr);
        hx.call("dungeon.tp", "x", stand.getX() + 0.5, "y", stand.getY() + 1.0, "z", stand.getZ() + 0.5, "yaw", 0f,
                "pitch", 0f);
        ctx.waitTicks(10);
        // The pad was only somewhere to stand while the scan ran; taken away once he is off it (a block removed under
        // his feet is a harness Simulation flag, 2026-10-18 first run).
        List<String> unpad = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 2; dz <= 4; dz++) {
                unpad.add((CENTRE + dx) + " " + (F - 1) + " " + (CENTRE + dz) + " minecraft:air");
            }
        }
        hx.call("dungeon.blocks", "blocks", unpad);
        ctx.waitTicks(10);
        int pattern = ctx.computeOnClient(mc -> {
            try {
                java.lang.reflect.Field f = Class.forName(SOLVER).getDeclaredField("patternIdentifier");
                f.setAccessible(true);
                return f.getInt(null);
            } catch (ReflectiveOperationException e) {
                return -2;
            }
        });
        Object first = ctx.computeOnClient(mc -> ModUnderTest.staticCall(SOLVER, "nextClick"));
        println("solver: board " + pattern + ", first click " + first + "; standing at "
                + ctx.computeOnClient(mc -> mc.player.position().toString()));
        if (pattern < 0 || first == null) {
            throw new AssertionError("Water Board Solver read no board (identifier " + pattern + ") - nothing to auto");
        }
        hx.call("dungeon.dropUseAfterLevers", "n", 3);
        JsonObject before = hx.call("dungeon.stats").getAsJsonObject();
        ctx.runOnClient(mc -> {
            if (!hooked) {
                hooked = true;
                ClientTickEvents.END_CLIENT_TICK.register(GrimWaterBoardTests::onEnd);
            }
            LAST.clear();
            FLIPS.clear();
            COUNTED.clear();
            prevCount = (Integer) ModUnderTest.staticCall(SOLVER, "getCountedClicks");
            prevNext = ModUnderTest.staticCall(SOLVER, "nextClick");
            watching = true;
            Object auto = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", true);
            ModUnderTest.set(auto, "setAutoWaterEnabled", true);
        });
        long mark = LogTap.mark();
        boolean done = false;
        int t = 0;
        for (; t < 60 * 20 && !done; t++) {
            ctx.waitTicks(1);
            done = LogTap.since(mark).stream().anyMatch(l -> l.contains("Water Board: done"));
        }
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            watching = false;
            Object auto = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(auto, "setAutoWaterEnabled", false);
            ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
        });
        // Read before disarming: arming resets the counts.
        JsonObject after = hx.call("dungeon.stats").getAsJsonObject();
        hx.call("dungeon.dropUseAfterLevers", "n", -1);
        List<String> lines = LogTap.since(mark);
        for (String l : lines) {
            if (l.contains("[AutoPuzzles] Water") || l.contains("LeverClick") || l.contains("dropped the ability")) {
                println("  " + l.trim());
            }
        }
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (String p : COUNTED) {
            wanted.merge(p, 1, Integer::sum);
        }
        int dropped = after.get("usesDropped").getAsInt();
        int warps = after.get("etherwarps").getAsInt() - before.get("etherwarps").getAsInt();
        println(String.format(Locale.US, "done %s after %.1f s; counted clicks %s; lever flips seen %s; etherwarps %d, uses "
                + "dropped %d, lever clicks at the server %d", done, t / 20.0, wanted, FLIPS, warps, dropped,
                after.get("leverClicks").getAsInt()));
        List<String> bad = new ArrayList<>();
        if (!done) {
            bad.add("the auto never said done in 60 s");
        }
        if (COUNTED.size() < 6) {
            bad.add("only " + COUNTED.size() + " click(s) counted");
        }
        if (dropped != 1) {
            bad.add("premise: " + dropped + " use(s) dropped (wanted the warp after the third lever click)");
        }
        if (warps < 2) {
            bad.add("only " + warps + " etherwarp(s) - Etherwarp Reposition never moved him");
        }
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            int seen = FLIPS.getOrDefault(e.getKey(), 0);
            if (seen != e.getValue()) {
                bad.add("lever " + e.getKey() + " clicked " + e.getValue() + " time(s), flipped " + seen);
            }
        }
        if (!bad.isEmpty()) {
            throw new AssertionError(String.join("; ", bad));
        }
    }

    // ============================================================================================ helpers

    private static BlockPos rel(ClientGameTestContext ctx, int x, int y, int z, int[] cr) {
        return ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(COORDS, "real",
                new Class<?>[]{int.class, int.class, int.class, int[].class}, new Object[]{x, y, z, cr}));
    }

    private static String liveRoom(net.minecraft.client.Minecraft mc) {
        Object entry = ModUnderTest.staticCall(LIVE_MAP, "currentRoomEntry");
        if (entry == null) {
            return null;
        }
        try {
            return (String) entry.getClass().getField("name").get(entry);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Ships one captured room to the server's {@code dungeon.paste}, its water as air. */
    @SuppressWarnings("unchecked")
    private static JsonObject paste(ClientGameTestContext ctx, Hx hx, String name) {
        JsonObject args = ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{name});
            try {
                Class<?> c = r.getClass();
                short[] blocks;
                try {
                    blocks = (short[]) c.getMethod("blockArray").invoke(r);
                } catch (NoSuchMethodException old) {
                    blocks = (short[]) c.getField("blocks").get(r);
                }
                byte[] raw = new byte[blocks.length * 2];
                for (int i = 0; i < blocks.length; i++) {
                    raw[i * 2] = (byte) (blocks[i] >> 8);
                    raw[i * 2 + 1] = (byte) blocks[i];
                }
                JsonObject a = new JsonObject();
                a.addProperty("sizeX", c.getField("sizeX").getInt(r));
                a.addProperty("sizeZ", c.getField("sizeZ").getInt(r));
                a.addProperty("minY", c.getField("minY").getInt(r));
                a.addProperty("maxY", c.getField("maxY").getInt(r));
                a.addProperty("margin", c.getField("margin").getInt(r));
                a.addProperty("cellX", CELL);
                a.addProperty("cellZ", CELL);
                JsonArray palette = new JsonArray();
                for (String p : (List<String>) c.getField("palette").get(r)) {
                    palette.add(p.startsWith("minecraft:water") ? "minecraft:air" : p);
                }
                a.add("palette", palette);
                a.addProperty("blocks", java.util.Base64.getEncoder().encodeToString(raw));
                return a;
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        return hx.call("dungeon.paste", args).getAsJsonObject();
    }

    private static void teardown(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> {
                watching = false;
                Object auto = ModUnderTest.config(AUTO_CONFIG);
                ModUnderTest.set(auto, "setAutoWaterEnabled", false);
                ModUnderTest.set(auto, "setAutoPuzzlesMasterEnabled", false);
            });
        } catch (Throwable ignored) {
            // never replaces the verdict
        }
    }

    private static void println(String line) {
        System.out.println("[" + CASE + "] " + line);
    }
}
