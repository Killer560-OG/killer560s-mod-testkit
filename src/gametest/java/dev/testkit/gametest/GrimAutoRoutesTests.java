package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.harness.PacketTrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 62-argrim-*: Auto Routes on the DEDICATED server with GrimAC - every node type and the timing behaviours, judged on
 * what happened (where he landed, what the server's ability emulation did, which blocks broke, the packets per tick)
 * and on GrimAC's verbose output, one verdict per case, with the session's positive control at both ends.
 *
 * <h2>How a dungeon room exists on a vanilla server</h2>
 * Auto Routes only runs inside a room the live map has identified (a {@code RouteCoords.Frame}). So the session pastes
 * one of his real captured rooms ({@code RoomLibrary}, sent through Hx {@code dungeon.paste}) at dungeon grid cell
 * (4,4) with its whole column cleared, puts up a Catacombs sidebar, and lets the mod's REAL world scan find it: the core
 * hash of the centre column and the blue-terracotta roof marker, exactly as on Hypixel. Only once the room is
 * identified is the 96-ar arena carved into it (relative x,z 3..27, floor at relative y {@link #F}).
 *
 * <h2>Abilities</h2>
 * Vanilla has no etherwarp. The testmod's {@code HxAbilities} answers the use packet the way Hypixel does - sneaking
 * with an ethermerged AOTV: voxel walk from the eye along the PACKET's rotation, server teleport onto the block; not
 * sneaking: Instant Transmission; Superboom on START_DESTROY_BLOCK - so GrimAC sees what it would see on Hypixel: the
 * client's packets and a server teleport it must confirm. The Dungeon Breaker is scenario 51's model: snow broken in
 * one packet by an Efficiency V diamond shovel under Haste V.
 *
 * <p>His settings as in his instances: obvious mode (one legit-mode case), start node only, node height 1.0, interact
 * delay 2, Breaker Aura's Multi Break on.
 */
public class GrimAutoRoutesTests implements FabricClientGameTest {

    private static final String SESSION = "62-argrim-session";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String FEATURE = "com.killer560.hub.autoroutes.AutoRoutesFeature";
    private static final String EXECUTOR = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String PLANNER = "com.killer560.hub.autoroutes.RoutePathPlanner";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String DX_CONFIG = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";

    /** Grid cell the room is pasted at (centre -57, -57). */
    private static final int CELL = 4;
    private static final int CENTRE = -185 + CELL * 32;
    /** Feet height of the arena floor: relative y is world y outside the sim, and 69 is a Catacombs floor. */
    private static final int F = 69;

    private static String room;
    private static Session session;

    // ============================================================================================ per-tick sampler

    /** One client tick, sampled at its END (see 96-ar's Sample: {@code shift} is the sneak the server had for every use
     *  in {@code out}). */
    private record Sample(int tick, boolean shift, boolean sprint, Vec3 pos, List<String> out) {
    }

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static volatile boolean sampling;
    private static boolean hooked;

    private static void startSampling(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (!hooked) {
                hooked = true;
                ClientTickEvents.END_CLIENT_TICK.register(c -> {
                    if (!sampling || c.player == null) {
                        return;
                    }
                    synchronized (SAMPLES) {
                        SAMPLES.add(new Sample(PacketTrace.tickCount() - 1, c.player.getLastSentInput().shift(),
                                c.player.isSprinting(), c.player.position(), List.of()));
                    }
                });
            }
            synchronized (SAMPLES) {
                SAMPLES.clear();
            }
            PacketTrace.start();
            synchronized (SAMPLES) {
                SAMPLES.add(new Sample(0, mc.player.getLastSentInput().shift(), mc.player.isSprinting(),
                        mc.player.position(), List.of()));
            }
            sampling = true;
        });
    }

    private static List<Sample> stopSampling(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            sampling = false;
            PacketTrace.stop();
        });
        List<Sample> out = new ArrayList<>();
        synchronized (SAMPLES) {
            for (Sample x : SAMPLES) {
                out.add(new Sample(x.tick(), x.shift(), x.sprint(), x.pos(), PacketTrace.sentOn(x.tick())));
            }
        }
        return out;
    }

    private static List<Sample> useTicks(List<Sample> s) {
        List<Sample> out = new ArrayList<>();
        for (Sample x : s) {
            if (x.out().contains("use_item")) {
                out.add(x);
            }
        }
        return out;
    }

    private static int count(List<Sample> s, String packet) {
        int n = 0;
        for (Sample x : s) {
            n += (int) x.out().stream().filter(packet::equals).count();
        }
        return n;
    }

    /** The trace of a sampled stretch, for the log: every tick that sent something other than a plain move. */
    private static void printTrace(String label, List<Sample> s) {
        for (Sample x : s) {
            List<String> interesting = x.out().stream()
                    .filter(p -> !p.startsWith("move_player") && !p.equals("client_tick_end") && !p.equals("pong")
                            && !p.equals("keep_alive")).toList();
            if (!interesting.isEmpty()) {
                println(String.format(Locale.ROOT, "  %s t%d shift=%s sprint=%s %s", label, x.tick(), x.shift(),
                        x.sprint(), x.out()));
            }
        }
    }

    // ============================================================================================ test

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ModUnderTest.require("killer560smod");
        Session.run(ctx, SESSION, (server, s) -> setup(ctx, s), s -> {
            session = s;
            s.test("62-argrim-ew-add", GrimAutoRoutesTests::caseEwAdd);
            s.test("62-argrim-walk", GrimAutoRoutesTests::caseWalk);
            s.test("62-argrim-boom", GrimAutoRoutesTests::caseBoom);
            s.test("62-argrim-breaker", GrimAutoRoutesTests::caseBreaker);
            s.test("62-argrim-use", GrimAutoRoutesTests::caseUse);
            s.test("62-argrim-play", GrimAutoRoutesTests::casePlay);
            s.test("62-argrim-pingpong", GrimAutoRoutesTests::casePingPong);
            s.test("62-argrim-chain", GrimAutoRoutesTests::caseChain);
            s.test("62-argrim-path", GrimAutoRoutesTests::casePath);
            s.test("62-argrim-legit", GrimAutoRoutesTests::caseLegit);
            s.test("62-argrim-hand", GrimAutoRoutesTests::caseHand);
            s.test("62-argrim-crypt", GrimAutoRoutesTests::caseCrypt);
            s.test("62-argrim-mimic", GrimAutoRoutesTests::caseMimic);
            // The Interactive Map's own executor (livemap/autoclear/ClearExecutor), last: a map warp or a Go To sets the
            // map-arrival interlock (only a START node arms afterwards) and Go To leaves edit mode on.
            // Auto Secret (mod auto-secret): drives the same map warp and route; before imwarp, after the AR cases.
            s.test("62-argrim-autosecret", GrimAutoRoutesTests::caseAutoSecret);
            s.test("62-argrim-imwarp", c -> caseMapWarp(c, false));
            s.test("62-argrim-imwarp-run", c -> caseMapWarp(c, true));
            s.test("62-argrim-goto", GrimAutoRoutesTests::caseGoTo);
            teardown(ctx);
        });
    }

    private static void setup(ClientGameTestContext ctx, Session s) {
        session = s;
        // Grim is live BEFORE anything runs (the session proves it again at the end).
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
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.set(ar, "setInteractDelayTicks", 2);
            ModUnderTest.set(ar, "setChatFeedback", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.cheatutils.CheatUtilsConfig"),
                    "setSecretAuraEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraEnabled", false);
            ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true);
        });
        Hx hx = s.hx();
        hx.call("dungeon.abilities", "enabled", true);
        s.server().command("gamemode survival @p");
        s.server().command("effect give @p minecraft:haste 99999 4 true");

        // ---- a real room the live map identifies by itself ----
        List<String> candidates = candidateRooms(ctx);
        String found = null;
        for (String name : candidates.subList(0, Math.min(8, candidates.size()))) {
            JsonObject result = paste(ctx, hx, name);
            println("pasted \"" + name + "\": " + result);
            // A 3x3 pad beside the centre column (never on it: that column IS the room's identity) to stand on.
            List<String> pad = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = 2; dz <= 4; dz++) {
                    pad.add((CENTRE + dx) + " " + (F - 1) + " " + (CENTRE + dz) + " minecraft:stone");
                    pad.add((CENTRE + dx) + " " + F + " " + (CENTRE + dz) + " minecraft:air");
                    pad.add((CENTRE + dx) + " " + (F + 1) + " " + (CENTRE + dz) + " minecraft:air");
                }
            }
            hx.call("dungeon.blocks", "blocks", pad);
            hx.call("dungeon.tp", "x", CENTRE + 0.5, "y", (double) F, "z", CENTRE + 3.5, "yaw", 0f, "pitch", 0f);
            if (!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState",
                    "isInDungeon"))) {
                // The Catacombs sidebar only once he stands in the room: the world spawn (about 0,68,0) is inside F7's
                // boss box, and entering the dungeon there latches "boss room" and switches room matching off - which
                // is how run 6 failed to identify any of eight rooms.
                ctx.waitTicks(10);
                hx.sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
                ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.secrets.DungeonState",
                        "isInDungeon"), 400);
            }
            boolean known = waitFor(ctx, 300, () -> name.equals(ctx.computeOnClient(mc -> {
                Object f = ModUnderTest.staticCall(FRAME, "current");
                return f == null ? null : (String) ModUnderTest.call(f, "roomName", new Class<?>[]{}, new Object[]{});
            })));
            println("live map on \"" + name + "\": " + (known ? "identified, frame " + frameString(ctx)
                    : "NOT identified (frame " + frameString(ctx) + ")"));
            if (known) {
                found = name;
                break;
            }
        }
        if (found == null) {
            throw new AssertionError("no pasted room was identified by the live map's world scan - Auto Routes cannot "
                    + "run without a room frame, so nothing below would mean anything");
        }
        room = found;
        giveHotbar(ctx, hx, s);
        println("room under test: " + room + ", frame " + frameString(ctx) + ", arena feet y " + F);
    }

    /** Every 1x1 room of his library with secrets that is not a puzzle/trap/blood/entrance/fairy room, in order. */
    @SuppressWarnings("unchecked")
    private static List<String> candidateRooms(ClientGameTestContext ctx) {
        Map<String, JsonObject> db = new HashMap<>();
        try {
            Path file = ModUnderTest.modConfig("killer560smod-roomdata").resolve("rooms-modern.json");
            for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                db.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the room database: " + e);
        }
        List<String> names = ctx.computeOnClient(mc -> new ArrayList<>((List<String>) ModUnderTest.staticCall(ROOM_LIBRARY,
                "names")));
        List<String> out = new ArrayList<>();
        for (String n : names) {
            JsonObject o = db.get(n);
            if (o == null) {
                continue;
            }
            String type = o.has("type") ? o.get("type").getAsString().toUpperCase(Locale.ROOT) : "";
            int secrets = o.has("secrets") ? o.get("secrets").getAsInt() : 0;
            if (secrets < 1 || type.equals("PUZZLE") || type.equals("TRAP") || type.equals("BLOOD")
                    || type.equals("ENTRANCE") || type.equals("FAIRY") || n.contains("Maze") || n.contains("Trap")) {
                continue;
            }
            int[] size = ctx.computeOnClient(mc -> {
                Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{n});
                try {
                    return new int[]{r.getClass().getField("sizeX").getInt(r), r.getClass().getField("sizeZ").getInt(r)};
                } catch (ReflectiveOperationException e) {
                    return new int[]{999, 999};
                }
            });
            if (size[0] <= 33 && size[1] <= 33) {
                out.add(n);
            }
        }
        println(out.size() + " candidate 1x1 room(s): " + out.subList(0, Math.min(10, out.size())));
        return out;
    }

    /** Ships one captured room to the server's {@code dungeon.paste}. */
    @SuppressWarnings("unchecked")
    private static JsonObject paste(ClientGameTestContext ctx, Hx hx, String name) {
        JsonObject args = ctx.computeOnClient(mc -> {
            Object r = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{name});
            try {
                Class<?> c = r.getClass();
                short[] blocks = (short[]) c.getField("blocks").get(r);
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
                    palette.add(p);
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

    /** AOTV slot 0 (ethermerged, 4 tuners), Superboom slot 1, Dungeon Breaker slot 2 (Efficiency V), empty slot 3. */
    private static void giveHotbar(ClientGameTestContext ctx, Hx hx, Session s) {
        hx.give(0, "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,tuned_transmission:4},"
                + "unbreakable={}]");
        hx.call("give", "slot", 1, "count", 64, "stack", "minecraft:tnt[custom_data={id:\"SUPERBOOM_TNT\"}]");
        hx.give(2, "minecraft:diamond_shovel[custom_data={id:\"DUNGEONBREAKER\"},unbreakable={},"
                + "lore=[{text:\"Charges: 20/20\",italic:false}]]");
        hx.give(4, "minecraft:iron_sword[custom_data={id:\"HYPERION\"},unbreakable={}]");
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
        ctx.waitTicks(5);
        s.server().command("enchant @p minecraft:efficiency 5");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        ctx.waitTicks(5);
        String hotbar = ctx.computeOnClient(mc -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(i).append('=').append(mc.player.getInventory().getItem(i).getComponents()).append("; ");
            }
            return sb.toString();
        });
        println("hotbar: " + hotbar);
        check(hotbar.contains("ASPECT_OF_THE_VOID") && hotbar.contains("SUPERBOOM_TNT") && hotbar.contains("DUNGEONBREAKER")
                && hotbar.contains("efficiency"), "the hotbar was not given: " + hotbar);
    }

    // ============================================================================================ cases

    /** /ar add ew start fires on add: one use, a server-made etherwarp, landing on (14,6). */
    private static void caseEwAdd(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        JsonObject before = stats();
        tpRel(ctx, 6.5, 6.5, relYawTo(6, 6, 14, 6), pitchTo(6, 6, 14, 6));
        startSampling(ctx);
        cmd(ctx, "/ar add ew start");
        Vec3 landed = waitLanded(ctx, 14, 6, 60);
        List<Sample> s = stopSampling(ctx);
        printTrace("ew-add", s);
        check(landed != null, "/ar add ew start did not warp him to (14,6) - fire on add; he is at " + relPos(ctx));
        check(useTicks(s).size() == 1, "fire on add sent " + useTicks(s).size() + " use_item packet(s), expected 1");
        check(useTicks(s).get(0).shift(), "the etherwarp's use went out without the server having the sneak");
        check(delta(before, "etherwarps") == 1, "the server made " + delta(before, "etherwarps") + " etherwarp(s)");
        JsonArray nodes = fileNodes(ctx);
        check(nodes.size() == 1 && "ETHERWARP".equals(nodes.get(0).getAsJsonObject().get("type").getAsString()),
                "the file does not hold one ETHERWARP node: " + nodes);
        c.note("fire-on-add etherwarp landed at " + landed + ", 1 use, server etherwarp count +1");
        noteFlags(c, s);
    }

    /** /ar add walk sprints along the look until the wall, shift up. */
    private static void caseWalk(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        tpRel(ctx, 14.5, 6.5, 0f, 0f);
        ctx.waitTicks(5);
        Vec3 from = relPos(ctx);
        startSampling(ctx);
        cmd(ctx, "/ar add walk");
        ctx.waitTicks(80);
        List<Sample> s = stopSampling(ctx);
        Vec3 after = relPos(ctx);
        check(after.z - from.z > 15, "/ar add walk did not sprint him toward +z (from " + from + " to " + after + ")");
        check(s.stream().anyMatch(Sample::sprint), "the walk never sprinted");
        check(s.stream().noneMatch(Sample::shift), "the walk sent shift down");
        c.note(String.format(Locale.ROOT, "walk: %.2f blocks along +z, sprinting", after.z - from.z));
        noteFlags(c, s);
    }

    /** /ar add boom at cracked bricks: START/ABORT + swing, the server's Superboom clears them. */
    private static void caseBoom(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        setBlocks(ctx, Map.of(new int[]{22, F, 20}, "minecraft:cracked_stone_bricks",
                new int[]{22, F + 1, 20}, "minecraft:cracked_stone_bricks"));
        JsonObject before = stats();
        tpRel(ctx, 20.5, 20.5, -90f, 0f);
        ctx.waitTicks(5);
        startSampling(ctx);
        cmd(ctx, "/ar add boom");
        boolean broke = waitFor(ctx, 60, () -> blockAir(ctx, 22, F + 1, 20) && blockAir(ctx, 22, F, 20));
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        printTrace("boom", s);
        check(broke, "/ar add boom did not blow the cracked bricks in front of him");
        check(delta(before, "superbooms") == 1, "the server ran " + delta(before, "superbooms") + " superboom(s)");
        c.note("boom: " + count(s, "player_action") + " player_action, " + count(s, "swing") + " swing; bricks gone");
        noteFlags(c, s);
    }

    /** A breaker node of six snow blocks: Multi Break ON (six STARTs on the firing tick, one swing), then OFF. */
    private static void caseBreaker(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        int[][] six = {{12, 0}, {13, 0}, {14, 0}, {12, 1}, {13, 1}, {14, 1}};
        StringBuilder notes = new StringBuilder();
        try {
            for (boolean multi : new boolean[]{true, false}) {
                ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", multi));
                Map<int[], String> put = new LinkedHashMap<>();
                JsonArray blocks = new JsonArray();
                for (int[] b : six) {
                    put.put(new int[]{b[0], F + b[1], 12}, "minecraft:snow_block");
                    blocks.add(b[0] + " " + (F + b[1]) + " 12");
                }
                setBlocks(ctx, put);
                JsonObject br = node("DUNGEON_BREAKER", 13, 10, 0f, 0f);
                br.add("blocks", blocks);
                br.addProperty("start", true);
                writeRoute(ctx, List.of(br));
                long m = LogTap.mark();
                startSampling(ctx);
                walkOnto(ctx, 13.5, 7.5, 13, 10);
                boolean gone = waitFor(ctx, 60, () -> {
                    for (int[] b : six) {
                        if (!blockAir(ctx, b[0], F + b[1], 12)) {
                            return false;
                        }
                    }
                    return true;
                });
                ctx.waitTicks(5);
                List<Sample> s = stopSampling(ctx);
                printTrace("breaker " + (multi ? "multi" : "single"), s);
                int most = 0;
                for (Sample x : s) {
                    most = Math.max(most, (int) x.out().stream().filter("player_action"::equals).count());
                }
                int total = count(s, "player_action");
                notes.append(String.format(Locale.ROOT, "multi %s: %d digs, most %d on a tick, %d swing(s), all gone %s; ",
                        multi ? "ON" : "OFF", total, most, count(s, "swing"), gone));
                check(gone, "multi break " + multi + ": not all six snow blocks broke");
                check(total == 6, "multi break " + multi + ": " + total + " player_action packets for six blocks");
                check(multi ? most == 6 : most == 1, "multi break " + multi + ": " + most + " digs on one tick");
                check(logHas(m, "DUNGEON_BREAKER acted 0 tick(s)"), "the breaker did not act on its firing tick");
                tpRel(ctx, 13.5, 6.5, 0f, 0f);
                ctx.waitTicks(5);
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true));
        }
        c.note(notes.toString());
        noteFlags(c, List.of());
    }

    /** A use node with the AOTV: Instant Transmission, shift up, from the packet's rotation. */
    private static void caseUse(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        JsonObject before = stats();
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        tpRel(ctx, 10.5, 22.5, 180f, 0f);
        ctx.waitTicks(5);
        startSampling(ctx);
        cmd(ctx, "/ar add use");
        ctx.waitTicks(30);
        List<Sample> s = stopSampling(ctx);
        printTrace("use", s);
        Vec3 u = relPos(ctx);
        check(u.z < 16, "/ar add use did not transmit him toward -z (at " + u + ")");
        List<Sample> uses = useTicks(s);
        check(uses.size() == 1 && !uses.get(0).shift(), "expected one use with shift up, saw " + uses.size());
        check(delta(before, "transmissions") == 1, "the server made " + delta(before, "transmissions")
                + " transmission(s)");
        c.note("use: AOTV Instant Transmission to " + u + "; 1 use, shift up (empty-hand use: 62-argrim-hand)");
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
        noteFlags(c, s);
    }

    /**
     * 96-ar's playback route on Grim: S --ew--> T, where a stack of boom / ew / breaker must fire breaker, boom, ew with
     * the sneak held from #1 into #3 (0-tick warp); T --ew--> U; walk at U sprints; an await:1 etherwarp waits for a
     * chest secret (sneak released, screen does not stop it), then warps; a use node uses with shift up.
     */
    private static void casePlay(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        Hx hx = c.hx();
        setBlocks(ctx, Map.of(new int[]{14, F, 4}, "minecraft:cracked_stone_bricks",
                new int[]{14, F + 1, 4}, "minecraft:cracked_stone_bricks",
                new int[]{15, F, 8}, "minecraft:snow_block",
                new int[]{16, F, 8}, "minecraft:snow_block",
                new int[]{24, F, 24}, "minecraft:chest"));
        List<JsonObject> n = new ArrayList<>();
        n.add(ew(6, 6, 14, 6, true));                        // #1
        n.add(node("BOOM", 14, 6, 180f, 0f));                 // #2
        n.add(ew(14, 6, 22, 6, false));                      // #3
        JsonObject br = node("DUNGEON_BREAKER", 14, 6, 0f, 0f); // #4
        JsonArray blocks = new JsonArray();
        blocks.add("15 " + F + " 8");
        blocks.add("16 " + F + " 8");
        br.add("blocks", blocks);
        n.add(br);
        n.add(node("WALK", 22, 6, 0f, 0f));                  // #5
        JsonObject w = ew(22, 26, 14, 26, false);             // #6
        w.addProperty("awaitEnabled", true);
        w.addProperty("await", "SECRET");
        w.addProperty("amount", 1);
        n.add(w);
        JsonObject use = node("USE_ITEM", 14, 26, 180f, 0f);  // #7
        use.addProperty("item", "ASPECT_OF_THE_VOID");
        n.add(use);
        writeRoute(ctx, n);
        JsonObject before = stats();
        long mark = LogTap.mark();
        startSampling(ctx);
        warpOnto(ctx, 6.5, 10.5, 6, 6);
        boolean atAwait = waitFor(ctx, 200, () -> logHas(mark, "Node #6 ETHERWARP begins"));
        check(atAwait, "the route never reached the await node #6 (at " + relPos(ctx) + ")");
        // Hypixel repeats the room's "x/y Secrets" action bar every second; a route forgets the count when it starts,
        // so the first line it sees after that is the await's baseline.
        hx.overlay("§7     §70/5 Secrets");
        ctx.waitTicks(40);
        List<Sample> first = stopSampling(ctx);
        printTrace("play to await", first);
        int i4 = logIndex(mark, "Node #4 DUNGEON_BREAKER acted");
        int i2 = logIndex(mark, "Node #2 BOOM acted");
        int i3 = logIndex(mark, "Node #3 ETHERWARP acted");
        check(i4 >= 0 && i2 > i4 && i3 > i2, "T's stack did not fire breaker, boom, ew (log order " + i4 + ", " + i2
                + ", " + i3 + ")");
        check(blockAir(ctx, 14, F + 1, 4), "the stacked boom broke nothing");
        check(blockAir(ctx, 15, F, 8) && blockAir(ctx, 16, F, 8), "the stacked breaker did not break both blocks");
        check(logHasAll(mark, "Node #3 ETHERWARP acted 0 tick(s)", "sneak already held"),
                "the sneak was not held from #1 through the stack into #3 (0-tick warp)");
        List<Sample> uses = useTicks(first);
        // His own hand etherwarp onto the start node may or may not fall inside the sampled ticks; the route's two are
        // the last two before the await.
        check(uses.size() >= 2, "expected the two route etherwarps before the await, saw " + uses.size());
        uses = uses.subList(uses.size() - 2, uses.size());
        for (Sample x : first) {
            if (uses.size() >= 2 && x.tick() >= uses.get(0).tick() && x.tick() <= uses.get(1).tick()) {
                check(x.shift(), "shift was up on tick " + x.tick() + " between the #1 and #3 etherwarps");
            }
        }
        check(logHas(mark, "Sneak released after node #3: next is #5 WALK"), "the sneak was not released for the walk");
        check(first.stream().anyMatch(Sample::sprint), "the walk never sprinted");
        check(!first.get(first.size() - 1).shift(), "sneak was held while #6 awaits its secret");
        // ---- the chest and the secret ----
        startSampling(ctx);
        rightClick(ctx, 24, F, 24, Direction.UP);
        hx.overlay("§7     §71/5 Secrets");
        boolean met = waitFor(ctx, 60, () -> logHas(mark, "await met under a screen") || logHas(mark, "await held it"));
        check(met, "the await never saw the secret");
        check(logHas(mark, "await secret 1: chest at"), "the await was not met by the chest click");
        // The click meets the await (his rule; Hypixel credits the chest on the click), so the chest's window and #6's
        // warp race: window first, the route waits under it; warp first, a late window is waited under (mod 98b457be)
        // or the server closes the far chest before it shows. Both orders came up here (2026-10-05).
        check(waitFor(ctx, 40, () -> logHas(mark, "Node #6 ETHERWARP acted")
                || ctx.computeOnClient(mc -> McCompat.screen(mc) != null)), "after the click neither a window nor #6");
        boolean windowFirst = !logHas(mark, "Node #6 ETHERWARP acted");
        println("play: " + (windowFirst ? "the chest window came before the warp" : "the warp went out before any window"));
        Vec3 l6;
        if (windowFirst) {
            check(useTicks(stopSampling(ctx)).isEmpty(), "the etherwarp went out while the chest screen was open");
            startSampling(ctx);
            ctx.runOnClient(mc -> mc.player.closeContainer());
            l6 = waitLanded(ctx, 14, 26, 60);
        } else {
            check(!logHas(mark, "Stopped: a screen opened"), "a late chest window stopped the route");
            l6 = waitLanded(ctx, 14, 26, 60);
            // A late window, if the server sends one, arrives within a few ticks; #7 waits under it until it is closed.
            waitFor(ctx, 10, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null));
            ctx.runOnClient(mc -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
            });
        }
        ctx.waitTicks(30);
        List<Sample> second = stopSampling(ctx);
        printTrace("play after chest", second);
        check(l6 != null, "#6 did not warp after the screen closed (at " + relPos(ctx) + ")");
        List<Sample> uses2 = useTicks(second);
        check(uses2.size() == 2 && uses2.get(0).shift() && !uses2.get(1).shift(),
                "expected #6's etherwarp (shift) and #7's use (no shift), saw " + uses2.size());
        check(relPos(ctx).z < 22, "#7's Instant Transmission did not move him toward -z (" + relPos(ctx) + ")");
        check(logHas(mark, "complete"), "the route did not complete");
        check(delta(before, "etherwarps") == 4 && delta(before, "superbooms") == 1 && delta(before, "transmissions") == 1,
                "server abilities: " + stats() + " vs before " + before);
        c.note("play: stack breaker->boom->ew with sneak held (0-tick), walk sprint, await:1 under a chest screen, "
                + "warp, use; server: 3 route etherwarps (+1 by hand onto the start), 1 superboom, 1 transmission");
        noteFlags(c, second);
    }

    /** Two etherwarps aimed at each other bounce until /ar stop, sneak held throughout. */
    private static void casePingPong(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        writeRoute(ctx, List.of(ew(6, 14, 14, 14, true), ew(14, 14, 6, 14, false)));
        JsonObject before = stats();
        startSampling(ctx);
        warpOnto(ctx, 6.5, 18.5, 6, 14);
        ctx.waitTicks(80);
        cmd(ctx, "/ar stop");
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        List<Sample> all = useTicks(s);
        // The first use is his own hand etherwarp onto the start node.
        List<Sample> uses = all.isEmpty() ? all : all.subList(1, all.size());
        printTrace("pingpong", s.subList(0, Math.min(s.size(), 40)));
        check(uses.size() >= 6, "only " + uses.size() + " warps in 80 ticks of ping-pong");
        for (Sample x : s) {
            if (!uses.isEmpty() && x.tick() >= uses.get(0).tick() && x.tick() <= uses.get(uses.size() - 1).tick()) {
                check(x.shift(), "shift released on tick " + x.tick() + " between two etherwarps");
            }
        }
        check(!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")),
                "/ar stop did not stop the ping-pong");
        // Counted over the whole trace: the sampled ticks can end a tick before the trace does.
        int sentUses = PacketTrace.sent("use_item");
        check(delta(before, "etherwarps") == sentUses, "client sent " + sentUses + " warps, server made "
                + delta(before, "etherwarps"));
        int gap = uses.size() < 2 ? -1 : (uses.get(uses.size() - 1).tick() - uses.get(0).tick()) / (uses.size() - 1);
        c.note("ping-pong: " + uses.size() + " warps in 80 ticks (about one every " + gap + " ticks), shift held");
        noteFlags(c, s);
    }

    /**
     * A long unconditional etherwarp chain under GrimAC: twelve warps round the arena (96-ar-chain's), reached by a hand
     * etherwarp onto the first. Ticks per warp, every use after the previous landing's teleport accept, the server's
     * etherwarp count, and what GrimAC says about warping as fast as the server answers.
     */
    private static void caseChain(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        int[][] pts = ArChainMeasure.CHAIN;
        int[] end = ArChainMeasure.END;
        List<JsonObject> n = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            int[] to = i + 1 < pts.length ? pts[i + 1] : end;
            n.add(ew(pts[i][0], pts[i][1], to[0], to[1], i == 0));
        }
        writeRoute(ctx, n);
        List<Sample> all = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        try {
            // Etherwarps Per Second (killer560, 2026-10-06): a pace of 20/rate ticks between warps, never ahead of the
            // landing - 20/s is the server's round trip here (one tick), 10/s two ticks, 4/s five.
            for (int rate : new int[]{20, 10, 4}) {
                ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setEtherwarpsPerSecond",
                        new Class<?>[]{int.class}, new Object[]{rate}));
                ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                        new Object[]{"test"}));
                JsonObject before = stats();
                long m = LogTap.mark();
                startSampling(ctx);
                warpOnto(ctx, 6.5, 10.5, 6, 6);
                Vec3 landed = waitLanded(ctx, end[0], end[1], 400);
                ctx.waitTicks(10);
                List<Sample> s = stopSampling(ctx);
                all.addAll(s);
                // The first use is his own hand etherwarp onto the start node.
                ArChainMeasure.Chain chain = ArChainMeasure.chain(1);
                int acted = 0;
                for (String l : LogTap.since(m)) {
                    acted += l.contains("ETHERWARP acted") ? 1 : 0;
                }
                int serverWarps = delta(before, "etherwarps");
                println("chain at " + rate + "/s: " + chain.describe() + "; server made " + serverWarps + " etherwarp(s)");
                notes.append(String.format(Locale.ROOT, "%d/s %.2f ticks/warp (gaps %s); ", rate, chain.ticksPerWarp(),
                        chain.gaps()));
                check(landed != null, rate + "/s: the chain did not reach its end at " + end[0] + "," + end[1] + " (at "
                        + relPos(ctx) + ")");
                check(acted == pts.length && chain.warps() == pts.length, rate + "/s: expected " + pts.length
                        + " route etherwarps, the log says " + acted + " acted and the trace has " + chain.warps());
                check(serverWarps == pts.length + 1, rate + "/s: the server made " + serverWarps + " etherwarps, expected "
                        + (pts.length + 1));
                check(chain.usesBeforeLanding() == 0, rate + "/s: " + chain.usesBeforeLanding() + " warp(s) went out "
                        + "before the previous landing was accepted");
                if (rate == 20) {
                    check(chain.firesOnLanding(), "20/s: a warp waited after its landing arrived: ticks from each landing "
                            + "to the next use " + chain.landingToUse());
                }
                double want = 20.0 / rate;
                check(Math.abs(chain.ticksPerWarp() - want) <= 0.3, String.format(Locale.ROOT, "%d/s: %.2f ticks per "
                        + "warp (gaps %s), expected %.2f", rate, chain.ticksPerWarp(), chain.gaps(), want));
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setEtherwarpsPerSecond",
                    new Class<?>[]{int.class}, new Object[]{20}));
        }
        c.note("chain: " + notes);
        noteFlags(c, all);
    }

    /** Path nodes across a wall: planned once by the floor planner, the saved warps flown twice. */
    private static void casePath(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, true);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", false));
        StringBuilder notes = new StringBuilder();
        try {
            int plans0 = plans(ctx);
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(4);
            long m = LogTap.mark();
            cmd(ctx, "/ar add path");
            ctx.waitTicks(5);
            tpRel(ctx, 24.5, 10.5, 0f, 0f);
            ctx.waitTicks(4);
            cmd(ctx, "/ar add path");
            boolean planned = waitFor(ctx, 400, () -> logHas(m, "planned by the floor planner"));
            check(planned, "adding the second path node did not plan the pair");
            check(plans(ctx) == plans0 + 1, "planned " + (plans(ctx) - plans0) + " times, expected once");
            JsonObject p1 = fileNodes(ctx).get(0).getAsJsonObject();
            int hops = p1.has("hops") ? p1.getAsJsonArray("hops").size() : 0;
            check(hops >= 2, "the saved path has " + hops + " warp(s) across the wall: " + p1);
            for (int run = 1; run <= 2; run++) {
                JsonObject before = stats();
                startSampling(ctx);
                long mr = LogTap.mark();
                walkOnto(ctx, 4.5, 20.5, 6, 20);
                Vec3 l = waitLanded(ctx, 24, 10, 200);
                ctx.waitTicks(5);
                List<Sample> s = stopSampling(ctx);
                printTrace("path run " + run, s);
                check(l != null, "replay " + run + " did not land on path #2's block (at " + relPos(ctx) + ")");
                check(useTicks(s).size() == hops, "replay " + run + " sent " + useTicks(s).size() + " warps, saved " + hops);
                check(!logHas(mr, "planning with the Interactive Map"), "replay " + run + " planned again");
                for (Sample x : s) {
                    if (!useTicks(s).isEmpty() && x.tick() >= useTicks(s).get(0).tick()
                            && x.tick() <= useTicks(s).get(useTicks(s).size() - 1).tick()) {
                        check(x.shift(), "shift up between two path warps on tick " + x.tick());
                    }
                }
                check(delta(before, "etherwarps") == hops, "server made " + delta(before, "etherwarps") + " of " + hops);
                notes.append("run ").append(run).append(": ").append(hops).append(" warps; ");
                waitFor(ctx, 60, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")));
            }
            check(plans(ctx) == plans0 + 1, "a replay ran the planner");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setStartFromStartNodeOnly", true));
        }
        c.note("path: " + notes);
        noteFlags(c, List.of());
    }

    /** Legit mode: the camera turns for each action. An etherwarp, then a use node clicking a lever with the AOTV. */
    private static void caseLegit(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        setBlocks(ctx, Map.of(new int[]{13, F, 6}, "minecraft:lever[face=floor,facing=east,powered=false]"));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", true));
        try {
            // A short, steep warp: legit mode fires once the humanized turn is within 1 degree, and on a long shallow
            // warp 1 degree of pitch is a block of landing (measured: 8 blocks at 9 degrees landed one short).
            JsonObject use = node("USE_ITEM", 10, 6, -90f, (float) Math.toDegrees(Math.atan2(1.62 - 0.15, 3.0)));
            use.addProperty("item", "ASPECT_OF_THE_VOID");
            writeRoute(ctx, List.of(ew(6, 6, 10, 6, true), use));
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            warpOnto(ctx, 6.5, 10.5, 6, 6);
            boolean done = waitFor(ctx, 200, () -> logHas(m, "complete") || logHas(m, "Stopped"));
            ctx.waitTicks(10);
            List<Sample> s = stopSampling(ctx);
            printTrace("legit", s);
            String lever = String.valueOf(blockState(ctx, 13, F, 6));
            check(done && logHas(m, "complete"), "the legit route did not complete");
            check(waitLanded(ctx, 10, 6, 5) != null, "the legit etherwarp did not land on (10,6), at " + relPos(ctx));
            check(lever.contains("powered=true"), "the use node did not flip the lever: " + lever);
            check(delta(before, "etherwarps") == 2 && delta(before, "transmissions") == 0,
                    "server abilities: " + stats() + " vs " + before + " (the lever click must not transmit)");
            c.note("legit: camera-turned etherwarp landed, use node with the AOTV flipped the lever (block won), "
                    + count(s, "use_item_on") + " use_item_on, " + count(s, "use_item") + " use_item");
            noteFlags(c, s);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setLegitMode", false));
        }
    }

    /** Empty-hand use nodes (no item): a lever and a chest clicked by hand along the node's look, body aimed. */
    private static void caseHand(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        // A lever on a wall at eye height and a chest hit on its side: aims that land on them from anywhere in the
        // node's ring along the approach (walked in, a node fires at the ring's edge, not its centre).
        setBlocks(ctx, Map.of(new int[]{14, F + 1, 14}, "minecraft:stone",
                new int[]{13, F + 1, 14}, "minecraft:lever[face=wall,facing=west,powered=false]",
                new int[]{13, F, 18}, "minecraft:chest"));
        JsonObject lever = node("USE_ITEM", 10, 14, -90f, 0f);
        lever.addProperty("start", true);
        writeRoute(ctx, List.of(lever));
        long m = LogTap.mark();
        startSampling(ctx);
        walkOnto(ctx, 7.5, 14.5, 10, 14);
        boolean flipped = waitFor(ctx, 40, () -> String.valueOf(blockState(ctx, 13, F + 1, 14)).contains("powered=true"));
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        printTrace("hand lever", s);
        check(flipped, "the empty-hand node did not flip the lever: " + blockState(ctx, 13, F + 1, 14));
        check(logHas(m, "empty hand: clicked"), "no empty-hand click in the log");
        check(count(s, "use_item_on") == 1 && count(s, "use_item") == 0, "lever: " + count(s, "use_item_on")
                + " use_item_on / " + count(s, "use_item") + " use_item, expected 1 / 0");
        // ---- a chest the same way ----
        JsonObject chest = node("USE_ITEM", 10, 18, -90f, 20f);
        chest.addProperty("start", true);
        writeRoute(ctx, List.of(chest));
        startSampling(ctx);
        walkOnto(ctx, 7.5, 18.5, 10, 18);
        boolean opened = waitFor(ctx, 40, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null));
        ctx.runOnClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        });
        ctx.waitTicks(5);
        List<Sample> s2 = stopSampling(ctx);
        printTrace("hand chest", s2);
        check(opened, "the empty-hand node did not open the chest");
        c.note("hand: lever flipped and chest opened by empty-hand use nodes, one use_item_on each, no use_item");
        noteFlags(c, s2);
    }

    /**
     * A crypt node: the Crypt Weapon (Hyperion) used straight down until a prince/crypt kill is counted. It HOLDS the use
     * key (killer560, 2026-10-06: "hold right click ... do not have it spam click"), so its uses must come at the cadence
     * of a held right click, packet for packet: the control first holds the real use key with the Hyperion, looking
     * straight down, and the node's use ticks are compared with it.
     */
    private static void caseCrypt(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setCryptWeapon",
                new Class<?>[]{enumClass("CryptWeapon")}, new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$CryptWeapon",
                        "HYPERION")}));
        // ---- control: vanilla's held right click, Hyperion, straight down ----
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(4));
        tpRel(ctx, 20.5, 20.5, 0f, 90f);
        ctx.waitTicks(10);
        startSampling(ctx);
        ctx.getInput().holdKey(o -> o.keyUse);
        ctx.waitTicks(30);
        ctx.getInput().releaseKey(o -> o.keyUse);
        ctx.waitTicks(3);
        stopSampling(ctx);
        ArChainMeasure.Held control = ArChainMeasure.held(0);
        println("crypt: vanilla held-use control: " + control.describe());
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));

        JsonObject crypt = node("CRYPT", 14, 14, 0f, 10f);
        crypt.addProperty("start", true);
        writeRoute(ctx, List.of(crypt));
        long m = LogTap.mark();
        startSampling(ctx);
        walkOnto(ctx, 14.5, 11.5, 14, 14);
        boolean acted = waitFor(ctx, 40, () -> logHas(m, "CRYPT acted"));
        ctx.waitTicks(24);   // several held uses
        // Hypixel's line for a prince kill - one of the crypt await's two readers (a crypt raises the tab's Crypts).
        c.hx().chat("A Prince falls. +1 Bonus Score");
        boolean done = waitFor(ctx, 40, () -> logHas(m, "CRYPT: 1 kill(s)"));
        ctx.waitTicks(20);
        List<Sample> s = stopSampling(ctx);
        ArChainMeasure.Held node = ArChainMeasure.held(0);
        boolean keyUp = ctx.computeOnClient(mc -> !mc.options.keyUse.isDown());
        printTrace("crypt", s);
        check(acted, "the crypt node never used its weapon");
        check(done, "the crypt node did not finish on the prince kill");
        check(control.every(4), "the vanilla control did not use every 4 ticks: " + control.describe());
        check(node.ticks().size() >= 4, "the crypt node used its weapon on " + node.ticks().size() + " tick(s)");
        check(node.every(4), "the crypt node's uses are not a held right click's cadence: " + node.describe());
        check(node.perUse().stream().distinct().toList().equals(control.perUse().stream().distinct().toList()),
                "the crypt node's packets per use " + node.perUse() + " differ from the held key's " + control.perUse());
        check(keyUp, "the use key was left held after the crypt node");
        c.note("crypt: held use, " + node.describe() + " - vanilla control " + control.describe()
                + " (the server emulates no Wither Impact - only the client's packets are under test)");
        noteFlags(c, s);
    }

    /** Kill Mimic (Hyperion): an empty-hand node clicks a trapped chest, the blade is used straight down. */
    private static void caseMimic(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setKillMimic",
                new Class<?>[]{enumClass("KillMimic")}, new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$KillMimic",
                        "HYPERION")}));
        try {
            setBlocks(ctx, Map.of(new int[]{13, F, 22}, "minecraft:trapped_chest"));
            JsonObject chest = node("USE_ITEM", 10, 22, -90f, 20f);
            chest.addProperty("start", true);
            writeRoute(ctx, List.of(chest));
            long m = LogTap.mark();
            startSampling(ctx);
            walkOnto(ctx, 7.5, 22.5, 10, 22);
            boolean opened = waitFor(ctx, 40, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null));
            ctx.runOnClient(mc -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
            });
            boolean killed = waitFor(ctx, 120, () -> logHas(m, "Kill Mimic done"));
            ctx.waitTicks(5);
            List<Sample> s = stopSampling(ctx);
            printTrace("mimic", s);
            check(opened, "the empty-hand node did not open the trapped chest");
            check(logHas(m, "used straight down"), "Kill Mimic never used the Hyperion");
            check(killed, "Kill Mimic did not finish");
            c.note("mimic: trapped chest clicked by an empty-hand node, Hyperion used straight down ("
                    + count(s, "use_item") + " use_item)");
            noteFlags(c, s);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setKillMimic",
                    new Class<?>[]{enumClass("KillMimic")}, new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$KillMimic",
                            "OFF")}));
        }
    }

    // ============================================================================================ Interactive Map

    private static final String IM_FEATURE = "com.killer560.hub.livemap.InteractiveMapFeature";
    private static final String IM_SCREEN = "com.killer560.hub.livemap.InteractiveMapScreen";
    private static final String LIVE_MAP = "com.killer560.hub.livemap.LiveMapFeature";
    private static final String EDIT_SCREEN = "com.killer560.hub.autoroutes.AutoRoutesEditScreen";
    private static final java.util.regex.Pattern RUNNING = java.util.regex.Pattern.compile("\\[Path\\] running (\\d+) warp");

    /**
     * A press on the Interactive Map, made with its screen OPEN: the Go + Secret bind on this room's cell
     * ({@code InteractiveMapFeature.onMapSecretPress}, the map's own entry point), which warps to the room's START node -
     * across the 4-high wall, so the floor planner needs several etherwarps - through {@code ClearExecutor}. With
     * {@code runWhileOpen} Auto Routes' Run While Map Open is on, so on arrival the START node (an etherwarp) fires under
     * the still-open map: the hand-over from the map's executor to the route's.
     */
    private static void caseMapWarp(Session c, boolean runWhileOpen) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, true);
        writeRoute(ctx, List.of(ew(24, 10, 24, 22, true)));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", runWhileOpen));
        List<Sample> s = List.of();
        try {
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(5);
            openMap(ctx);
            float[] look0 = look(ctx);
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            int cell = ctx.computeOnClient(mc -> {
                int[] g = (int[]) ModUnderTest.staticCall(LIVE_MAP, "gridCellFor", new Class<?>[]{Vec3.class},
                        new Object[]{mc.player.position()});
                return g[0] + g[1] * 11;
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(IM_FEATURE, "onMapSecretPress", new Class<?>[]{int.class},
                    new Object[]{cell}));
            Vec3 landed = waitLanded(ctx, 24, 10, 300);
            Vec3 landed2 = runWhileOpen ? waitLanded(ctx, 24, 22, 100) : null;
            ctx.waitTicks(15);
            s = stopSampling(ctx);
            printTrace(runWhileOpen ? "imwarp-run" : "imwarp", s);
            float[] look1 = look(ctx);
            String screen = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
            int planned = plannedWarps(m);
            List<Sample> uses = useTicks(s);
            int mapUses = uses.size() - (runWhileOpen ? 1 : 0);
            check(logHas(m, "Warping to the start node"), "the map press did not take the START-node warp (cell " + cell
                    + ")");
            check(landed != null, "the map warp did not bring him to the start node (24,10) - at " + relPos(ctx));
            check(planned >= 2, "the map planned " + planned + " warp(s) across the wall, expected 2 or more");
            check(mapUses == planned, "the map sent " + mapUses + " use(s) for " + planned + " planned warp(s)");
            check(delta(before, "etherwarps") == uses.size(), "client sent " + uses.size() + " use(s), the server made "
                    + delta(before, "etherwarps") + " etherwarp(s)");
            for (Sample x : uses) {
                check(x.shift(), "a warp's use went out on tick " + x.tick() + " without the server having the sneak");
            }
            check(screen.contains("InteractiveMapScreen"), "the map screen did not stay open: " + screen);
            if (runWhileOpen) {
                check(landed2 != null, "Run While Map Open: the START node's etherwarp did not fire under the map (at "
                        + relPos(ctx) + ")");
                check(logHas(m, "Node #1 ETHERWARP acted"), "the route's START node never acted");
            } else {
                check(!logHas(m, "Node #1 ETHERWARP acted"), "the route fired under the open map with the setting off");
                // The camera never moved, and the body was given back to it once the last warp went out.
                check(Math.abs(net.minecraft.util.Mth.wrapDegrees(look1[0] - look0[0])) < 0.01f
                                && Math.abs(look1[1] - look0[1]) < 0.01f,
                        "his rotation was not given back after the warps: " + look0[0] + "/" + look0[1] + " -> " + look1[0]
                                + "/" + look1[1]);
            }
            c.note((runWhileOpen ? "map warp then route under the open map: " : "map warp, map open: ") + planned
                    + " planned, " + uses.size() + " use(s), server etherwarps +" + delta(before, "etherwarps")
                    + ", landed " + landed + (runWhileOpen ? ", route ew landed " + landed2 : "") + ", body "
                    + look0[0] + "/" + look0[1] + " -> " + look1[0] + "/" + look1[1]);
        } finally {
            writeRoute(ctx, null);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setRunWhileMapOpen", false));
            ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
            ctx.waitTicks(3);
        }
        noteFlags(c, s);
    }

    /**
     * Auto Secret on the one pasted room: it must pick the room (it has secrets and a route), warp across the wall to the
     * route's START node with the map's executor, let the route's etherwarp fire, see the route finish, and - nothing
     * else being left - stop and hand back. Judged on where he went, the uses against the server's etherwarps, and
     * GrimAC.
     */
    private static void caseAutoSecret(Session c) {
        ClientGameTestContext ctx = c.ctx();
        String as = "com.killer560.hub.autosecret.AutoSecretFeature";
        resetRoutes(ctx);
        arena(ctx, true);
        writeRoute(ctx, List.of(ew(24, 10, 24, 22, true)));
        List<Sample> s = List.of();
        try {
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(5);
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as, "start"));
            check(Boolean.TRUE.equals(started), "Auto Secret refused to start");
            Vec3 atStart = waitLanded(ctx, 24, 10, 300);
            Vec3 atEnd = waitLanded(ctx, 24, 22, 200);
            boolean stopped = waitFor(ctx, 200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as,
                    "isRunning")));
            ctx.waitTicks(10);
            s = stopSampling(ctx);
            printTrace("autosecret", s);
            for (String l : LogTap.since(m)) {
                if (l.contains("[AutoSecret]")) {
                    println("  " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]"));
                }
            }
            int planned = plannedWarps(m);
            List<Sample> uses = useTicks(s);
            check(logHas(m, "[AutoSecret] target " + room), "Auto Secret did not target " + room);
            check(atStart != null, "Auto Secret's map warp did not bring him to the start node (24,10) - at " + relPos(ctx));
            check(atEnd != null, "the route's etherwarp did not land on (24,22) - at " + relPos(ctx));
            check(logHas(m, "[AutoSecret] route " + room + " finished"), "Auto Secret never saw the route finish");
            check(stopped, "Auto Secret was still running with nothing left to do");
            check(planned >= 2, "the map planned " + planned + " warp(s) across the wall, expected 2 or more");
            check(uses.size() == planned + 1, "sent " + uses.size() + " use(s) for " + planned + " map warp(s) + 1 route warp");
            check(delta(before, "etherwarps") == uses.size(), "client sent " + uses.size() + " use(s), the server made "
                    + delta(before, "etherwarps") + " etherwarp(s)");
            for (Sample x : uses) {
                check(x.shift(), "a warp's use went out on tick " + x.tick() + " without the server having the sneak");
            }
            c.note("auto secret: " + planned + " map warp(s) + route warp, " + uses.size() + " use(s), server etherwarps +"
                    + delta(before, "etherwarps") + ", landed " + atStart + " then " + atEnd);
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(as, "isRunning")) {
                    ModUnderTest.staticCall(as, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            writeRoute(ctx, null);
            ctx.waitTicks(3);
        }
        noteFlags(c, s);
    }

    /** /ar edit 2, Go To: node #2 across the wall, reached by the Interactive Map's warp, then edit mode. */
    private static void caseGoTo(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, true);
        writeRoute(ctx, List.of(ew(6, 6, 14, 6, true), node("BOOM", 24, 10, 0f, 0f)));
        List<Sample> s = List.of();
        try {
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(5);
            float[] look0 = look(ctx);
            openEditor(ctx, 2);
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            press(ctx, "Go To");
            boolean edit = waitFor(ctx, 400, () -> ctx.computeOnClient(mc ->
                    (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode")));
            ctx.waitTicks(10);
            s = stopSampling(ctx);
            printTrace("goto", s);
            Vec3 to = relPos(ctx);
            float[] look1 = look(ctx);
            int planned = plannedWarps(m);
            List<Sample> uses = useTicks(s);
            check(edit, "Go To never turned edit mode on (at " + to + ")");
            check(to.distanceTo(new Vec3(24.5, F, 10.5)) < 1.5, "Go To did not bring him to node #2: " + to);
            check(planned >= 2, "Go To planned " + planned + " warp(s) across the wall, expected 2 or more");
            check(uses.size() == planned, "Go To sent " + uses.size() + " use(s) for " + planned + " planned warp(s)");
            check(delta(before, "etherwarps") == uses.size(), "client sent " + uses.size() + " use(s), the server made "
                    + delta(before, "etherwarps"));
            for (Sample x : uses) {
                check(x.shift(), "a Go To warp's use went out on tick " + x.tick() + " without the sneak");
            }
            check(!logHas(m, "Node #2 BOOM acted"), "node #2 fired under him on arrival");
            check(Math.abs(net.minecraft.util.Mth.wrapDegrees(look1[0] - look0[0])) < 0.01f
                    && Math.abs(look1[1] - look0[1]) < 0.01f, "his rotation was not given back after the warps: "
                    + look0[0] + "/" + look0[1] + " -> " + look1[0] + "/" + look1[1]);
            c.note("Go To #2: " + planned + " planned, " + uses.size() + " use(s), server etherwarps +"
                    + delta(before, "etherwarps") + ", at " + to + ", edit mode on");
        } finally {
            if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(FEATURE, "isEditMode"))) {
                cmd(ctx, "/ar edit db");
            }
            writeRoute(ctx, null);
        }
        noteFlags(c, s);
    }

    private static int plannedWarps(long mark) {
        int n = -1;
        for (String l : LogTap.since(mark)) {
            java.util.regex.Matcher mm = RUNNING.matcher(l);
            if (mm.find()) {
                n = Integer.parseInt(mm.group(1));
            }
        }
        return n;
    }

    private static float[] look(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
    }

    private static void openMap(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) Class.forName(IM_SCREEN)
                        .getConstructor(boolean.class).newInstance(false));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        ctx.waitTicks(3);
        String screen = ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc)));
        check(screen.contains("InteractiveMapScreen"), "the Interactive Map did not open: " + screen);
    }

    private static void openEditor(ClientGameTestContext ctx, int n) {
        cmd(ctx, "/ar edit " + n);
        boolean open = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getName().equals(EDIT_SCREEN)));
        check(open, "/ar edit " + n + " did not open the node editor (screen "
                + ctx.computeOnClient(mc -> String.valueOf(McCompat.screen(mc))) + ")");
        ctx.waitTicks(3);
    }

    /** A click at the widget's DRAWN centre, through the screen's own mouseClicked (96-ar's helper). */
    private static void press(ClientGameTestContext ctx, String label) {
        String result = ctx.computeOnClient(mc -> {
            net.minecraft.client.gui.screens.Screen sc = McCompat.screen(mc);
            if (sc == null) {
                return "no screen";
            }
            net.minecraft.client.gui.components.AbstractWidget found = null;
            for (var child : sc.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                        && !(w instanceof net.minecraft.client.gui.components.EditBox)) {
                    String l = strip(w.getMessage().getString());
                    if (l.equals(label)) {
                        found = w;
                        break;
                    }
                    if (found == null && l.startsWith(label)) {
                        found = w;
                    }
                }
            }
            if (found == null) {
                return "no button \"" + label + "\"";
            }
            double x = found.getX() + found.getWidth() / 2.0;
            double y = found.getY() + found.getHeight() / 2.0;
            boolean took = sc.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y,
                    new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
            sc.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y,
                    new net.minecraft.client.input.MouseButtonInfo(0, 0)));
            return took ? "ok" : "click at " + x + "," + y + " on \"" + label + "\" was not taken";
        });
        check("ok".equals(result), result);
        ctx.waitTicks(1);
    }

    private static String strip(String s) {
        String t = net.minecraft.ChatFormatting.stripFormatting(s);
        return t == null ? s : t;
    }

    private static Class<?> enumClass(String inner) {
        return ModUnderTest.enumValue(AR_CONFIG + "$" + inner, "HYPERION").getClass();
    }

    // ============================================================================================ world

    /** The 96-ar arena: relative x,z 3..27 cleared from F to F+8 on a stone floor at F-1, stone walls round it, and
     *  with {@code wall} a 4-high wall across x 15 the path has to go over. */
    private static void arena(ClientGameTestContext ctx, boolean wall) {
        List<String> set = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            List<String> out = new ArrayList<>();
            for (int x = 3; x <= 27; x++) {
                for (int z = 3; z <= 27; z++) {
                    boolean edge = x == 3 || x == 27 || z == 3 || z == 27;
                    out.add(spec(real(frame, x, F - 1, z), "minecraft:stone"));
                    for (int y = F; y <= F + 8; y++) {
                        boolean solid = (edge && y <= F + 3) || (wall && !edge && x == 15 && y <= F + 3);
                        out.add(spec(real(frame, x, y, z), solid ? "minecraft:stone" : "minecraft:air"));
                    }
                }
            }
            return out;
        });
        session.hx().call("dungeon.blocks", "blocks", set);
        ctx.waitTicks(10);
    }

    private static String spec(BlockPos p, String state) {
        return p.getX() + " " + p.getY() + " " + p.getZ() + " " + state;
    }

    private static BlockPos real(Object frame, int x, int y, int z) {
        return (BlockPos) ModUnderTest.staticCall(COORDS, "toRealBlock", new Class<?>[]{frame.getClass(), BlockPos.class},
                new Object[]{frame, new BlockPos(x, y, z)});
    }

    private static void setBlocks(ClientGameTestContext ctx, Map<int[], String> blocks) {
        List<String> set = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            List<String> out = new ArrayList<>();
            for (var e : blocks.entrySet()) {
                out.add(spec(real(frame, e.getKey()[0], e.getKey()[1], e.getKey()[2]), e.getValue()));
            }
            return out;
        });
        session.hx().call("dungeon.blocks", "blocks", set);
        ctx.waitTicks(4);
    }

    private static BlockState blockState(ClientGameTestContext ctx, int x, int y, int z) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(real(ModUnderTest.staticCall(FRAME, "current"), x, y, z)));
    }

    private static boolean blockAir(ClientGameTestContext ctx, int x, int y, int z) {
        return blockState(ctx, x, y, z).isAir();
    }

    private static JsonObject stats() {
        return session.hx().call("dungeon.stats").getAsJsonObject();
    }

    private static int delta(JsonObject before, String key) {
        return stats().get(key).getAsInt() - before.get(key).getAsInt();
    }

    private static float lookYaw;
    private static float lookPitch;

    /** Server teleport to relative feet (x, F, z), looking along a RELATIVE yaw. */
    private static void tpRel(ClientGameTestContext ctx, double x, double z, float relYaw, float pitch) {
        double[] p = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 v = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, x, (double) F, z});
            float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(), float.class},
                    new Object[]{frame, relYaw});
            return new double[]{v.x, v.y, v.z, yaw};
        });
        lookYaw = (float) p[3];
        lookPitch = pitch;
        session.hx().call("dungeon.tp", "x", p[0], "y", p[1], "z", p[2], "yaw", (float) p[3], "pitch", pitch);
        Vec3 target = new Vec3(p[0], p[1], p[2]);
        waitFor(ctx, 40, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(target) < 0.05));
        ctx.waitTicks(2);
    }

    /**
     * Arrives on a node the way he does: placed two-odd blocks short of it, facing it, and WALKED on with the forward
     * key until the route starts (or 40 ticks), then the key is let go.
     */
    private static void walkOnto(ClientGameTestContext ctx, double fx, double fz, int tx, int tz) {
        // Facing the node's centre exactly from (fx, fz), both relative.
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx + 0.5 - fx), tz + 0.5 - fz));
        tpRel(ctx, fx, fz, yaw, 0f);
        ctx.waitTicks(10);
        ctx.getInput().holdKey(o -> o.keyUp);
        try {
            waitFor(ctx, 40, () -> ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")));
        } finally {
            ctx.getInput().releaseKey(o -> o.keyUp);
        }
    }

    /**
     * Arrives on a node by etherwarping onto it BY HAND, as he would: from (sx, sz) looking at the floor of (tx, tz),
     * the sneak key held, the use key pressed once (a vanilla click: use-on-block, then use), the sneak let go once
     * he has landed. The server's etherwarp puts him on the block centre, so an etherwarp start node fires from
     * exactly where it was written - walking in fires it from the ring's edge, which on a long warp is a block short.
     */
    private static void warpOnto(ClientGameTestContext ctx, double sx, double sz, int tx, int tz) {
        int fx = (int) Math.floor(sx);
        int fz = (int) Math.floor(sz);
        tpRel(ctx, sx, sz, relYawTo(fx, fz, tx, tz), pitchTo(fx, fz, tx, tz));
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.getInput().holdKey(o -> o.keyShift);
        try {
            ctx.waitTicks(4);
            ctx.getInput().pressKey(o -> o.keyUse);
            waitFor(ctx, 40, () -> ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")));
        } finally {
            ctx.getInput().releaseKey(o -> o.keyShift);
        }
    }

    private static Vec3 relPos(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            if (frame == null) {
                return new Vec3(Double.NaN, Double.NaN, Double.NaN);
            }
            return (Vec3) ModUnderTest.staticCall(COORDS, "toRelative", new Class<?>[]{frame.getClass(), Vec3.class},
                    new Object[]{frame, mc.player.position()});
        });
    }

    /** Waits until he stands in relative block (x, z) at the arena floor; his position then, or null. */
    private static Vec3 waitLanded(ClientGameTestContext ctx, int x, int z, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Vec3 p = relPos(ctx);
            if (Math.floor(p.x) == x && Math.floor(p.z) == z && Math.abs(p.y - F) < 0.2) {
                return p;
            }
            ctx.waitTicks(1);
        }
        return null;
    }

    private static String frameString(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(FRAME, "current")));
    }

    // ============================================================================================ routes

    private static float relYawTo(int fx, int fz, int tx, int tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    /** Pitch from a sneaking eye over block (fx,fz) down onto the top centre of the floor block at (tx,tz). */
    private static float pitchTo(int fx, int fz, int tx, int tz) {
        double horiz = Math.hypot(tx - fx, tz - fz);
        return (float) Math.toDegrees(Math.atan2(1.27, horiz));
    }

    private static JsonObject node(String type, int x, int z, float yaw, float pitch) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("x", x + 0.5);
        o.addProperty("y", (double) F);
        o.addProperty("z", z + 0.5);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        return o;
    }

    private static JsonObject ew(int fx, int fz, int tx, int tz, boolean start) {
        JsonObject o = node("ETHERWARP", fx, fz, relYawTo(fx, fz, tx, tz), pitchTo(fx, fz, tx, tz));
        o.addProperty("landing", String.format(Locale.US, "%.3f %.3f %.3f", tx + 0.5, F + 0.05, tz + 0.5));
        if (start) {
            o.addProperty("start", true);
        }
        return o;
    }

    private static void writeRoute(ClientGameTestContext ctx, List<JsonObject> nodes) {
        ctx.runOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                JsonObject routes = new JsonObject();
                if (nodes != null) {
                    JsonArray arr = new JsonArray();
                    nodes.forEach(arr::add);
                    JsonObject r = new JsonObject();
                    r.add("nodes", arr);
                    routes.add(room, r);
                }
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
        ctx.waitTicks(2);
    }

    private static void resetRoutes(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test reset"});
            mc.player.getInventory().setSelectedSlot(3);
        });
        writeRoute(ctx, null);
    }

    private static JsonArray fileNodes(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Path file = (Path) ModUnderTest.staticCall(STORE, "routesFile");
                if (!Files.exists(file)) {
                    return new JsonArray();
                }
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject routes = root.getAsJsonObject("routes");
                if (routes == null || !routes.has(room)) {
                    return new JsonArray();
                }
                return routes.getAsJsonObject(room).getAsJsonArray("nodes");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static int plans(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(PLANNER, "plansStarted"));
    }

    // ============================================================================================ input

    /** Types a line into a real ChatScreen and submits it. An /ar add is typed facing the way the last tpRel asked. */
    private static void cmd(ClientGameTestContext ctx, String line) {
        if (line.startsWith("/ar add")) {
            waitFor(ctx, 200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")));
        }
        ctx.runOnClient(mc -> {
            if (line.startsWith("/ar add")) {
                mc.player.setYRot(lookYaw);
                mc.player.setXRot(lookPitch);
            }
            net.minecraft.client.gui.screens.ChatScreen screen = new net.minecraft.client.gui.screens.ChatScreen("", false);
            McCompat.setScreen(mc, screen);
            screen.handleChatInput(line, true);
            if (McCompat.screen(mc) == screen) {
                McCompat.setScreen(mc, null);
            }
        });
        ctx.waitTicks(2);
    }

    /** A real right click on a relative block, through the game mode (his click). */
    private static void rightClick(ClientGameTestContext ctx, int x, int y, int z, Direction face) {
        // He LOOKS at it first (a turn, reported by the next movement packet), then clicks the point his ray strikes.
        // Clicking a block's centre while facing elsewhere is not a click a hand makes: GrimAC RotationPlace flagged
        // exactly that on the first version of this helper (2026-10-05).
        ctx.runOnClient(mc -> {
            BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), x, y, z);
            Vec3 eye = mc.player.getEyePosition();
            Vec3 c = Vec3.atCenterOf(pos);
            double dx = c.x - eye.x;
            double dy = c.y - eye.y;
            double dz = c.z - eye.z;
            float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(pitch);
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            BlockPos pos = real(ModUnderTest.staticCall(FRAME, "current"), x, y, z);
            Vec3 eye = mc.player.getEyePosition();
            var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
            Vec3 c = Vec3.atCenterOf(pos);
            BlockHitResult hit = shape.isEmpty() ? null
                    : shape.clip(eye, eye.add(c.subtract(eye).normalize().scale(eye.distanceTo(c) + 1.5)), pos);
            if (hit == null) {
                hit = new BlockHitResult(c, face, pos, false);
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        });
        ctx.waitTicks(2);
    }

    // ============================================================================================ helpers

    /** Prints whatever Grim said during the case (the session then fails it) next to the packets that earned it. */
    private static void noteFlags(Session c, List<Sample> s) {
        c.ctx().waitTicks(10);
        List<String> flags = c.scenario().flags();
        if (flags.isEmpty()) {
            println(c.name() + ": GrimAC silent for this case");
            return;
        }
        println(c.name() + ": GrimAC said " + flags.size() + " line(s):");
        for (String f : flags) {
            println("    " + f);
        }
        printTrace(c.name() + " (flagged)", s);
    }

    private static boolean waitFor(ClientGameTestContext ctx, int ticks, Supplier<Boolean> cond) {
        for (int i = 0; i < ticks; i++) {
            if (cond.get()) {
                return true;
            }
            ctx.waitTicks(1);
        }
        return cond.get();
    }

    private static boolean logHas(long mark, String text) {
        return logIndex(mark, text) >= 0;
    }

    private static boolean logHasAll(long mark, String a, String b) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(a) && l.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static int logIndex(long mark, String text) {
        List<String> lines = LogTap.since(mark);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void println(String s) {
        System.out.println("[62-argrim] " + s);
    }

    private static void teardown(ClientGameTestContext ctx) {
        try {
            resetRoutes(ctx);
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setEnabled", false));
        } catch (Throwable ignored) {
            // never replaces the verdict
        }
    }
}
