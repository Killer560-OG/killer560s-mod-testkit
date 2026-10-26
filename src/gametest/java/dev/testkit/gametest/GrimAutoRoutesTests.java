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
 * 62-argrim-*: Auto Routes on the DEDICATED server with GrimAC - every node type and the timing behaviors, judged on
 * what happened (where he landed, what the server's ability emulation did, which blocks broke, the packets per tick)
 * and on GrimAC's verbose output, one verdict per case, with the session's positive control at both ends.
 *
 * <h2>How a dungeon room exists on a vanilla server</h2>
 * Auto Routes only runs inside a room the live map has identified (a {@code RouteCoords.Frame}). So the session pastes
 * one of his real captured rooms ({@code RoomLibrary}, sent through Hx {@code dungeon.paste}) at dungeon grid cell
 * (4,4) with its whole column cleared, puts up a Catacombs sidebar, and lets the mod's REAL world scan find it: the core
 * hash of the center column and the blue-terracotta roof marker, exactly as on Hypixel. Only once the room is
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
@dev.testkit.harness.RequiresMod("killer560smod")
public class GrimAutoRoutesTests implements FabricClientGameTest {

    private static final String SESSION = "62-argrim-session";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final String COORDS = "com.killer560.hub.autoroutes.RouteCoords";
    private static final String FRAME = "com.killer560.hub.autoroutes.RouteCoords$Frame";
    private static final String FEATURE = "com.killer560.hub.autoroutes.AutoRoutesFeature";
    private static final String EXECUTOR = "com.killer560.hub.autoroutes.RouteExecutor";
    private static final String SOUNDS = "com.killer560.hub.util.ModSounds";
    private static final String CORRECTIONS = "com.killer560.hub.util.ServerCorrections";
    private static final String PLANNER = "com.killer560.hub.autoroutes.RoutePathPlanner";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String DX_CONFIG = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";

    /** Grid cell the room is pasted at (center -57, -57). */
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
            dev.testkit.harness.UseRotationWatch.start();
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
            dev.testkit.harness.UseRotationWatch.stop();
        });
        for (String v : dev.testkit.harness.UseRotationWatch.violations()) {
            println("  BadPacketsJ (replayed): " + v);
        }
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
            s.test("62-argrim-870-jump", GrimAutoRoutesTests::caseJumpCount); // ar-jump-n (2026-10-12)
            s.test("62-argrim-1014-jumpcd", GrimAutoRoutesTests::caseJumpCooldown); // ar-jump-cooldown (2026-10-14)
            s.testExpectingFlags("62-argrim-correction", GrimAutoRoutesTests::caseCorrection);
            s.test("62-argrim-boom", GrimAutoRoutesTests::caseBoom);
            s.test("62-argrim-1020-boomsneak", GrimAutoRoutesTests::caseBoomSneak);
            s.test("62-argrim-breaker", GrimAutoRoutesTests::caseBreaker);
            s.test("62-argrim-1013-breakerfall", GrimAutoRoutesTests::caseBreakerFall); // ar breaker linger (2026-10-13)
            s.test("62-argrim-1020-breakerstand", GrimAutoRoutesTests::caseBreakerStand); // stall-1020 (his Wizard #1)
            s.test("62-argrim-use", GrimAutoRoutesTests::caseUse);
            s.test("62-argrim-play", GrimAutoRoutesTests::casePlay);
            s.test("62-argrim-awaitskip", GrimAutoRoutesTests::caseAwaitSkip);
            s.test("62-argrim-1015-clickwarp", GrimAutoRoutesTests::caseClickWarp); // ar-nexttick-1015
            s.test("62-argrim-1017-breakerwarp", GrimAutoRoutesTests::caseBreakerWarpPaired); // ar-bridges-bat-1017
            s.test("62-argrim-pingpong", GrimAutoRoutesTests::casePingPong);
            s.test("62-argrim-chain", GrimAutoRoutesTests::caseChain);
            s.test("62-argrim-1025-zpchain", GrimAutoRoutesTests::caseZeroPingChain); // mod blink-1025
            s.test("62-argrim-1025-chargechain", GrimAutoRoutesTests::caseChargedChain); // mod blink-charge-1025
            s.test("62-argrim-path", GrimAutoRoutesTests::casePath);
            // mod ar-obvious-only (2026-10-11): legit mode exists only in -PtestingBuild jars.
            if (SimAutoRoutesTests.legitSelectable()) {
                s.test("62-argrim-legit", GrimAutoRoutesTests::caseLegit);
            } else {
                s.shelved("62-argrim-legit", SimAutoRoutesTests.LEGIT_ONLY_TESTING, GrimAutoRoutesTests::caseLegit);
            }
            s.test("62-argrim-hand", GrimAutoRoutesTests::caseHand);
            s.test("62-argrim-1016-auraawait", GrimAutoRoutesTests::caseAuraAwait); // aura-await-only-1016
            s.test("62-argrim-crypt", GrimAutoRoutesTests::caseCrypt);
            s.test("62-argrim-1015-cryptaim", GrimAutoRoutesTests::caseCryptAim); // crypt-aim-1015
            s.test("62-argrim-mimic", GrimAutoRoutesTests::caseMimic);
            s.test("62-argrim-1017-mimicaim", GrimAutoRoutesTests::caseMimicAim); // ar-bridges-bat-1017
            s.test("62-argrim-autoclear", GrimAutoRoutesTests::caseAutoClear);
            s.test("62-argrim-1020-autoclearaura", GrimAutoRoutesTests::caseAutoClearAura); // mod ac-aura-memory-1020
            s.test("62-argrim-1020-acbat", GrimAutoRoutesTests::caseAutoClearBat); // ac-1020
            s.test("62-argrim-1020-battest", GrimAutoRoutesTests::caseBatTest); // mod battest-1020: /test
            // The Interactive Map's own executor (livemap/autoclear/ClearExecutor), last: a map warp or a Go To sets the
            // map-arrival interlock (only a START node arms afterwards) and Go To leaves edit mode on.
            // Auto Secret (mod auto-secret): drives the same map warp and route; before imwarp, after the AR cases.
            s.test("62-argrim-autosecret", c -> caseAutoSecret(c, false));
            // Dungeon Autopilot (mod dungeon-autopilot): the same room through the planner - Solo, nothing but the route.
            s.test("62-argrim-autopilot", c -> caseAutoSecret(c, true));
            // grim-post-autopilot-1016: started by his key (END_CLIENT_TICK) with a non-AOTV slot held and the first trip
            // already in TripCache, so the trip runs precomputed and its AOTV swap is the one under test.
            s.test("62-argrim-1016-autosecret-key", c -> caseAutoSecret(c, false, true));
            s.test("62-argrim-1016-autopilot-key", c -> caseAutoSecret(c, true, true));
            s.test("62-argrim-1016-oneuse", GrimAutoRoutesTests::caseOneUsePerTick); // grim-autopilot-flag-1016
            // autosecret-bloodfirst-1017: Auto Secret with Secret Blood First = Door Camp on the arena (no blood door on
            // its map): it says so, secrets meanwhile, and sends exactly what the plain run does.
            s.test("62-argrim-1017-autosecret-bloodfirst", GrimAutoRoutesTests::caseAutoSecretBloodFirst);
            // acplan-1020: Auto Secret's Fastest Total - the route, then the room's clear in the same visit.
            s.test("62-argrim-1020-clearhere", GrimAutoRoutesTests::caseClearHere);
            s.test("62-argrim-imwarp", c -> caseMapWarp(c, false));
            s.test("62-argrim-imwarp-run", c -> caseMapWarp(c, true));
            s.test("62-argrim-goto", GrimAutoRoutesTests::caseGoTo);
            // midas-1020: pastes King Midas over the session's room, so it goes last.
            s.test("62-argrim-1020-midas", GrimAutoRoutesTests::caseMidas);
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
            // Every other feature that clicks a secret on its own, whatever an earlier scenario in this client left on:
            // a Secret Triggerbot left on by 62-full-block-reach clicked these cases' levers as the legit route turned
            // the crosshair onto them, so each lever was flipped twice (sharded run, 2026-10-07).
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.secrettrigger.SecretTriggerbotConfig"),
                    "setEnabled", false);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.leveraura.LeverAuraConfig"), "setEnabled", false);
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
            // A 3x3 pad beside the center column (never on it: that column IS the room's identity) to stand on.
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
                // Since mod fps-sim-1015 a room may hold one byte per block; blockArray() is the short form either way.
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

    /** 870 on GrimAC: /ar add jump 3 takes off three times, then the route is done; GrimAC says nothing. */
    private static void caseJumpCount(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        tpRel(ctx, 14.5, 6.5, 0f, 0f);
        ctx.waitTicks(10);
        long m = LogTap.mark();
        boolean last = ctx.computeOnClient(mc -> mc.player.onGround());
        double y0 = relPos(ctx).y; // before the command: the first jump can start inside cmd's own wait
        startSampling(ctx);
        cmd(ctx, "/ar add jump 3");
        int offs = 0;
        double maxRise = 0;
        for (int t = 0; t < 80; t++) {
            boolean g = ctx.computeOnClient(mc -> mc.player.onGround());
            if (last && !g) {
                offs++;
            }
            last = g;
            maxRise = Math.max(maxRise, relPos(ctx).y - y0);
            ctx.waitTicks(1);
        }
        List<Sample> s = stopSampling(ctx);
        boolean done = LogTap.since(m).stream().anyMatch(l -> l.contains("JUMP: jump 3 of 3"));
        c.note(String.format(Locale.ROOT, "jump 3: %d take-off(s), rose %.2f, third jump logged %s", offs, maxRise, done));
        check(offs == 3, "/ar add jump 3 took off " + offs + " time(s)");
        check(maxRise > 1.0, "the jumps rose only " + maxRise);
        check(done, "no third jump logged");
        noteFlags(c, s);
    }

    /**
     * 1014 on GrimAC (killer560, 2026-10-14): a written jump-1 start node, standing on it: one take-off, none for the 3 s
     * cooldown, then the next; and a jump -> etherwarp route goes on to the etherwarp at once. GrimAC says nothing.
     */
    private static void caseJumpCooldown(Session c) {
        ClientGameTestContext ctx = c.ctx();
        long cd = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall(EXECUTOR, "jumpCooldownMs")).longValue());
        resetRoutes(ctx);
        arena(ctx, false);
        tpRel(ctx, 14.5, 10.5, 0f, 0f);
        ctx.waitTicks(10);
        JsonObject j = node("JUMP", 14, 6, 0f, 0f);
        j.addProperty("jumps", 1);
        j.addProperty("start", true);
        writeRoute(ctx, List.of(j));
        ctx.waitTicks(5);
        startSampling(ctx);
        tpRel(ctx, 14.5, 6.5, 0f, 0f);
        List<Long> offs = new ArrayList<>();
        boolean last = true;
        long t0 = System.currentTimeMillis();
        for (int t = 0; t < 600 && System.currentTimeMillis() - t0 < cd + 3500; t++) {
            boolean g = ctx.computeOnClient(mc -> mc.player.onGround());
            if (last && !g) {
                offs.add(System.currentTimeMillis());
            }
            last = g;
            ctx.waitTicks(1);
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"}));
        long gap = offs.size() >= 2 ? offs.get(1) - offs.get(0) : -1;
        String part1 = "jump 1: " + offs.size() + " take-off(s), first to second " + gap + " ms";

        // jump 1 (start) at (14,6) then an etherwarp at (14,7) to (14,11); rings 1.0 wide, he stands at z 6.95 in both.
        resetRoutes(ctx);
        tpRel(ctx, 14.5, 10.5, 0f, 0f);
        ctx.waitTicks(10);
        j.addProperty("radius", 1.0);
        JsonObject e = ew(14, 7, 14, 11, false);
        e.addProperty("radius", 1.0);
        writeRoute(ctx, List.of(j, e));
        ctx.waitTicks(5);
        tpRel(ctx, 14.5, 6.95, 0f, 0f);
        long t1 = System.currentTimeMillis();
        Vec3 landed = waitLanded(ctx, 14, 11, 100);
        long took = System.currentTimeMillis() - t1;
        List<Sample> s = stopSampling(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"}));
        c.note(part1 + "; jump -> etherwarp landed " + landed + " " + took + " ms after stepping on");
        check(offs.size() >= 2, "jump 1 took off " + offs.size() + " time(s): expected one, then one after the cooldown");
        check(gap >= cd - 50, "jump 1 jumped again " + gap + " ms later, inside the " + cd + " ms cooldown");
        check(gap <= cd + 1500, "jump 1 did not fire again " + gap + " ms after (cooldown " + cd + ")");
        check(landed != null, "jump -> etherwarp did not reach (14,11); he is at " + relPos(ctx));
        check(took < cd - 500, "jump -> etherwarp took " + took + " ms (held by the cooldown?)");
        noteFlags(c, s);
        resetRoutes(ctx);
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

    /**
     * Mod rule (killer560, 2026-10-06): a server correction never stops a route - chat line, the correction alarm, and the
     * route carries on from where the server put him. Two setbacks (a server {@code tp} a block back, the same position
     * packet a setback is) 8 ticks apart while {@code /ar add walk} sprints him along +z: the route must still be running
     * after both, he must still get down the arena, and each must be reported. Expecting flags: the tp itself is the
     * server's doing, and GrimAC may well have an opinion on a sprint that was moved under it.
     */
    private static void caseCorrection(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        tpRel(ctx, 14.5, 6.5, 0f, 0f);
        ctx.waitTicks(5);
        Vec3 from = relPos(ctx);
        long mark = LogTap.mark();
        int alarmsBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"));
        int reportsBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(CORRECTIONS, "reports"));
        startSampling(ctx);
        cmd(ctx, "/ar add walk");
        ctx.waitTicks(8);
        Vec3 beforeFirst = relPos(ctx);
        c.server().command("execute as @p at @s run tp @s ~ ~ ~-1");
        ctx.waitTicks(8);
        boolean runningBetween = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
        c.server().command("execute as @p at @s run tp @s ~ ~ ~-1");
        ctx.waitTicks(6);
        boolean runningAfter = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
        ctx.waitTicks(60);
        List<Sample> s = stopSampling(ctx);
        Vec3 after = relPos(ctx);
        int alarms = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"))
                - alarmsBefore;
        int reports = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(CORRECTIONS, "reports")) - reportsBefore;
        long reportLines = LogTap.since(mark).stream().filter(l -> l.contains("[Correction] Auto Routes:")).count();
        boolean chatLine = logHasAll(mark, "[CHAT]", "Server correction:");
        // "Stopped: null" is the walk completing at the far wall; any other reason is a real stop.
        boolean stopLine = LogTap.since(mark).stream().anyMatch(l -> l.contains("[AutoRoutes] Stopped: ")
                && !l.contains("[AutoRoutes] Stopped: null"));
        c.note(String.format(Locale.ROOT, "two setbacks: z %.2f at the first, %.2f -> %.2f overall; running between=%s,"
                        + " after=%s; reports %d (%d log line(s)), chat line=%s, alarms %d, stop line=%s",
                beforeFirst.z, from.z, after.z, runningBetween, runningAfter, reports, reportLines, chatLine, alarms,
                stopLine));
        check(beforeFirst.z - from.z > 1.0, "the walk never moved him before the first setback - the case proves nothing");
        check(runningBetween && runningAfter, "a correction stopped the route (running between=" + runningBetween
                + ", after=" + runningAfter + ")");
        check(!stopLine, "the route logged a stop (other than completing) after the setbacks");
        check(after.z - from.z > 12, "the walk did not carry on after the setbacks (from " + from + " to " + after + ")");
        check(reports >= 2 && reportLines >= 2 && chatLine, "each correction must be reported with a chat line (reports "
                + reports + ", log lines " + reportLines + ", chat line " + chatLine + ")");
        check(alarms >= 1, "the correction alarm never played");
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

    /**
     * boom-eye-1020: an etherwarp onto a tile (its sneak still held), then two booms recorded standing at 30 degrees down,
     * east and west, each onto a cracked stone brick in the floor three blocks out. From the sneaking eye the recorded
     * look meets the floor a block short (plain stone: the server's Superboom blows nothing there), so the boom must aim
     * at the recorded block with a look of its own - and GrimAC must say nothing about that turn and dig.
     */
    private static void caseBoomSneak(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        setBlocks(ctx, Map.of(new int[]{13, F - 1, 14}, "minecraft:cracked_stone_bricks",
                new int[]{7, F - 1, 14}, "minecraft:cracked_stone_bricks"));
        writeRoute(ctx, List.of(ew(6, 14, 10, 14, true), node("BOOM", 10, 14, relYawTo(10, 14, 13, 14), 30f),
                node("BOOM", 10, 14, relYawTo(10, 14, 7, 14), 30f)));
        JsonObject before = stats();
        long m = LogTap.mark();
        startSampling(ctx);
        warpOnto(ctx, 6.5, 18.5, 6, 14);
        boolean gone = waitFor(ctx, 80, () -> blockAir(ctx, 13, F - 1, 14) && blockAir(ctx, 7, F - 1, 14));
        ctx.waitTicks(10);
        cmd(ctx, "/ar stop");
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        printTrace("boomsneak", s);
        for (String l : LogTap.since(m)) {
            if (l.contains("BOOM") || l.contains("Boom")) {
                System.out.println("[62-argrim] boomsneak log: " + l);
            }
        }
        long booms = delta(before, "superbooms");
        check(logHas(m, "Node #2 BOOM acted") && logHas(m, "Node #3 BOOM acted"), "the two booms did not both act");
        check(gone, "the cracked bricks the booms were recorded on were not both blown (east "
                + blockAir(ctx, 13, F - 1, 14) + ", west " + blockAir(ctx, 7, F - 1, 14) + ")");
        c.note("boomsneak: " + booms + " superboom(s) on the server, " + count(s, "player_action") + " player_action; both "
                + "recorded bricks gone " + gone);
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

    /**
     * killer560 (2026-10-13): a breaker node keeps mining its blocks as he falls through the hole it made. A seven-high
     * snow pillar, every block the node's; he walks onto its top from a stone column beside it, and the rest go as he
     * falls past them - against GrimAC.
     */
    private static void caseBreakerFall(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        int top = 7;
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true));
        Map<int[], String> put = new LinkedHashMap<>();
        JsonArray blocks = new JsonArray();
        for (int y = 0; y < top; y++) {
            put.put(new int[]{13, F + y, 12}, "minecraft:snow_block");
            put.put(new int[]{13, F + y, 11}, "minecraft:stone");
            blocks.add("13 " + (F + y) + " 12");
        }
        setBlocks(ctx, put);
        JsonObject br = node("DUNGEON_BREAKER", 13, 12, 0f, 0f);
        br.addProperty("y", (double) (F + top));
        br.add("blocks", blocks);
        br.addProperty("start", true);
        writeRoute(ctx, List.of(br));
        double[] p = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            Vec3 v = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, 13.5, (double) (F + top), 11.3});
            float yaw = (Float) ModUnderTest.staticCall(COORDS, "toRealYaw", new Class<?>[]{frame.getClass(), float.class},
                    new Object[]{frame, 0f});
            return new double[]{v.x, v.y, v.z, yaw};
        });
        lookYaw = (float) p[3];
        lookPitch = 30f;
        session.hx().call("dungeon.tp", "x", p[0], "y", p[1], "z", p[2], "yaw", (float) p[3], "pitch", 30f);
        Vec3 target = new Vec3(p[0], p[1], p[2]);
        waitFor(ctx, 40, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(target) < 0.05));
        ctx.waitTicks(20);
        long m = LogTap.mark();
        startSampling(ctx);
        ctx.getInput().holdKey(o -> o.keyUp);
        boolean onPillar;
        try {
            // Walked on until he is over the pillar or already falling, not just until the route runs: the start node's
            // ring reaches the stone column, so the route arms while he still stands beside the pillar, and letting go
            // then left him on the column with the hole beside him - he never fell, so nothing was there to linger for
            // (2026-10-15, "nothing was mined after the node finished").
            onPillar = waitFor(ctx, 60, () -> {
                Vec3 r = relPos(ctx);
                return r.z >= 12.2 || r.y < F + top - 0.5;
            });
        } finally {
            ctx.getInput().releaseKey(o -> o.keyUp);
        }
        check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"))
                || logHas(m, "DUNGEON_BREAKER acted"), "the breaker route never started under him");
        check(onPillar, "he never got over the pillar (at " + relPos(ctx) + ")");
        boolean gone = waitFor(ctx, 160, () -> {
            for (int y = 0; y < top; y++) {
                if (!blockAir(ctx, 13, F + y, 12)) {
                    return false;
                }
            }
            return true;
        });
        ctx.waitTicks(20);
        List<Sample> s = stopSampling(ctx);
        printTrace("breaker fall", s);
        Vec3 at = relPos(ctx);
        int digs = count(s, "player_action");
        c.note(String.format(Locale.ROOT, "breaker fall: %d dig(s), linger lines %s, all gone %s, ended at y %.2f (floor %d)",
                digs, logHas(m, "Breaker linger:"), gone, at.y, F));
        check(logHas(m, "DUNGEON_BREAKER acted"), "the breaker node never fired");
        check(logHas(m, "block(s) broken") && logHas(m, "Breaker linger"), "nothing was mined after the node finished");
        check(gone, "the pillar was not all broken");
        noteFlags(c, s);
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
    /**
     * killer560, 2026-10-06: a LEFT click while a node waits on its await fires the node now, and the click is swallowed.
     * Control: the same real left click (Fabric TestInput through the attack key) with no route running sends a swing,
     * so the counter can see one. Then an await:1 etherwarp start node, reached by a hand etherwarp, waits with no
     * secret; he looks down at the floor (where a vanilla left click would swing and start a dig) and left-clicks: the
     * warp must go, the server must make exactly one etherwarp, nothing but the use may go out for that click (no swing,
     * no player_action before or after it), and GrimAC must stay silent.
     */
    /**
     * mod ar-bridges-bat-1017 (killer560's Bridges: "after the second dungeon breaker I cannot get it to make that
     * etherwarp"). A lever clicked with the Aspect of the Void in hand stays unpaired; a breaker node then fires, and the
     * etherwarp stacked after it looks past a stone plate at head height that the 1.8 server's 1.54 ray meets and this
     * client's 1.27 look passes under (the hole rim in Bridges). The route pairs the lever click first with a bare use of
     * the Dungeon Breaker at the look the server has heard, then swaps back and warps the next tick. Against GrimAC: the
     * breaker's digs, the breaker use, the swap and the warp must draw nothing, and the warp must land on the target.
     */
    private static void caseBreakerWarpPaired(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteRunMemory", "reset", new Class<?>[]{},
                        new Object[]{});
            } catch (AssertionError ignored) {
                // an older jar: no run memory
            }
        });
        Map<int[], String> put = new LinkedHashMap<>();
        put.put(new int[]{7, F + 2, 20}, "minecraft:stone");
        put.put(new int[]{16, F + 5, 20}, "minecraft:stone");
        put.put(new int[]{5, F, 20}, "minecraft:snow_block");
        put.put(new int[]{8, F, 22}, "minecraft:lever[face=floor,facing=east,powered=false]");
        setBlocks(ctx, put);
        JsonObject br = node("DUNGEON_BREAKER", 6, 20, 0f, 0f);
        JsonArray blocks = new JsonArray();
        blocks.add("5 " + F + " 20");
        br.add("blocks", blocks);
        br.addProperty("start", true);
        br.addProperty("awaitEnabled", true);
        br.addProperty("await", "SECRET");
        br.addProperty("amount", 1);
        float yaw = relYawTo(6, 20, 16, 20);
        float pitch = (float) -Math.toDegrees(Math.atan2(5.5 - 1.27, 16.0 - 6.5));
        JsonObject w = node("ETHERWARP", 6, 20, yaw, pitch);
        w.addProperty("landing", String.format(Locale.US, "%.3f %.3f %.3f", 16.5, F + 6.05, 20.5));
        writeRoute(ctx, List.of(br, w));
        long mark = LogTap.mark();
        warpOnto(ctx, 6.5, 10.5, 6, 20);
        check(waitFor(ctx, 60, () -> logHas(mark, "Stack of 2")), "the breaker/etherwarp stack never fired (at "
                + relPos(ctx) + ")");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            BlockPos lever = real(ModUnderTest.staticCall(FRAME, "current"), 8, F, 22);
            Vec3 eye = mc.player.getEyePosition();
            Vec3 at = new Vec3(lever.getX() + 0.5, lever.getY() + 0.1, lever.getZ() + 0.5);
            double dx = at.x - eye.x;
            double dy = at.y - eye.y;
            double dz = at.z - eye.z;
            float ly = (float) -Math.toDegrees(Math.atan2(dx, dz));
            mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(ly - mc.player.getYRot()));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        });
        ctx.waitTicks(3);
        JsonObject before = stats();
        startSampling(ctx);
        long m = LogTap.mark();
        ctx.runOnClient(mc -> {
            BlockPos lever = real(ModUnderTest.staticCall(FRAME, "current"), 8, F, 22);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(
                    new Vec3(lever.getX() + 0.5, lever.getY() + 0.1, lever.getZ() + 0.5), Direction.UP, lever, false));
            mc.player.swing(InteractionHand.MAIN_HAND);
        });
        Vec3 top = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, 16.5, (double) (F + 6), 20.5});
        });
        boolean landed = waitFor(ctx, 60, () -> ctx.computeOnClient(mc -> mc.player.position().distanceTo(top) < 0.4));
        ctx.waitTicks(10);
        List<Sample> s = stopSampling(ctx);
        printTrace("breakerwarp", s);
        for (String l : LogTap.since(m)) {
            if (l.contains("[RightClick]") || l.contains("ETHERWARP") || l.contains("Etherwarp") || l.contains("Breaker")
                    || l.contains("DUNGEON_BREAKER")) {
                println("  " + l);
            }
        }
        boolean paired = logHas(m, "paired the block click at");
        boolean broke = blockAir(ctx, 5, F, 20);
        c.note(String.format(Locale.ROOT, "breakerwarp: lever click paired by a breaker use %s, snow broken %s, landed on "
                + "the target %s, server etherwarps %d", paired, broke, landed, delta(before, "etherwarps")));
        check(logHas(m, "lever at") && logHas(m, "secrets met"), "the lever click did not meet the breaker's await");
        check(broke, "the breaker node did not break its snow block");
        check(paired, "no Dungeon Breaker use paired the lever click before the warp - the case never happened");
        check(landed, "the warp after the breaker did not land on the target (at " + relPos(ctx) + ")");
        check(delta(before, "etherwarps") == 1, "the server made " + delta(before, "etherwarps")
                + " etherwarp(s), expected 1");
        noteFlags(c, s);
    }

    /**
     * mod ar-nexttick-1015 (util/RightClick). An await met by a lever click made as Secret Aura makes it - the body turned
     * to the lever first (so the movement packets report it), then the click with the Aspect of the Void in hand - and the
     * warp the next tick: the route turns the body to the warp on the firing tick and sends the use after that tick's
     * movement packet has told the server; for a warp whose block is within reach it also sends the click on that block
     * first, as a vanilla right click does. Far (8 blocks) and near (3 blocks). Each must land, the server make exactly one
     * etherwarp, the warp go at most a tick after firing, and GrimAC say nothing (the session's verdict).
     */
    private static void caseClickWarp(Session c) {
        ClientGameTestContext ctx = c.ctx();
        for (boolean near : new boolean[]{false, true}) {
            String label = near ? "clickwarp near" : "clickwarp far";
            int tx = near ? 9 : 14;
            resetRoutes(ctx);
            arena(ctx, false);
            // Each run its own: the mod remembers a lever got earlier this run (RouteRunMemory) and would meet the await.
            ctx.runOnClient(mc -> {
                try {
                    Class.forName("com.killer560.hub.autoroutes.RouteRunMemory").getDeclaredMethod("reset").setAccessible(true);
                    ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteRunMemory", "reset", new Class<?>[]{},
                            new Object[]{});
                } catch (ReflectiveOperationException | AssertionError ignored) {
                    // an older jar: no run memory
                }
            });
            setBlocks(ctx, Map.of(new int[]{8, F, 8}, "minecraft:lever[face=floor,facing=east,powered=false]"));
            JsonObject w = ew(6, 6, tx, 6, true);
            w.addProperty("awaitEnabled", true);
            w.addProperty("await", "SECRET");
            w.addProperty("amount", 1);
            writeRoute(ctx, List.of(w));
            long mark = LogTap.mark();
            warpOnto(ctx, 6.5, 10.5, 6, 6);
            check(waitFor(ctx, 60, () -> logHas(mark, "Node #1 ETHERWARP begins")), label + ": the await node never began (at "
                    + relPos(ctx) + ")");
            ctx.waitTicks(10);
            // Secret Aura's turn to the lever: the body faces it, a few ticks of movement packets report it.
            ctx.runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                BlockPos lever = real(ModUnderTest.staticCall(FRAME, "current"), 8, F, 8);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 at = new Vec3(lever.getX() + 0.5, lever.getY() + 0.1, lever.getZ() + 0.5);
                double dx = at.x - eye.x;
                double dy = at.y - eye.y;
                double dz = at.z - eye.z;
                float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
                mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(yaw - mc.player.getYRot()));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            });
            ctx.waitTicks(3);
            check(!logHas(mark, "secrets met"), label + ": the await was met before the click");
            JsonObject before = stats();
            startSampling(ctx);
            long m = LogTap.mark();
            ctx.runOnClient(mc -> {
                BlockPos lever = real(ModUnderTest.staticCall(FRAME, "current"), 8, F, 8);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(
                        new Vec3(lever.getX() + 0.5, lever.getY() + 0.1, lever.getZ() + 0.5), Direction.UP, lever, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
            Vec3 landed = waitLanded(ctx, tx, 6, 40);
            ctx.waitTicks(5);
            List<Sample> s = stopSampling(ctx);
            printTrace(label, s);
            String acted = null;
            for (String l : LogTap.since(m)) {
                if (l.contains("[RightClick]") || l.contains("Node #1 ETHERWARP") || l.contains("Etherwarp: landed")) {
                    println("  " + l);
                }
                if (acted == null && l.contains("Node #1 ETHERWARP acted")) {
                    acted = l;
                }
            }
            check(logHas(m, "lever at") && logHas(m, "secrets met"), label + ": the lever click did not meet the await");
            check(landed != null, label + ": the warp after the lever click did not land (at " + relPos(ctx) + ")");
            check(delta(before, "etherwarps") == 1, label + ": the server made " + delta(before, "etherwarps")
                    + " etherwarp(s), expected 1");
            // 2026-10-21: the warp is held one tick after a block click with the same item, because Hypixel gave no
            // teleport one tick after such a click in 45 of 69 measured warps (2026-10-19; AutoRoutes' click-gap hold).
            // So the warp goes 0 to 2 ticks after the click; more than that is the failure.
            check(acted != null && (acted.contains("acted 0 tick(s)") || acted.contains("acted 1 tick(s)")
                    || acted.contains("acted 2 tick(s)")),
                    label + ": the warp went more than 2 ticks after the click: " + acted);
            if (near) {
                check(logHas(m, "sent the block half of the right click first"), label + ": the near warp did not send the "
                        + "click on its block first (vanilla's two-packet right click)");
            }
        }
    }

    private static void caseAwaitSkip(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        // An item an earlier case left lying here, picked up 7 ticks before the route started (inside
        // AwaitEvents.START_LOOKBACK), met the await before it ever waited (2026-10-15 run on mod ac-optimize-1015).
        c.server().command("kill @e[type=minecraft:item]");
        ctx.waitTicks(15);
        // ---- control: a vanilla left click, no route ----
        tpRel(ctx, 10.5, 10.5, 0f, 70f);
        ctx.waitTicks(5);
        startSampling(ctx);
        ctx.getInput().holdKey(o -> o.keyAttack);
        ctx.waitTicks(3);
        ctx.getInput().releaseKey(o -> o.keyAttack);
        ctx.waitTicks(5);
        List<Sample> control = stopSampling(ctx);
        printTrace("awaitskip control", control);
        check(count(control, "swing") >= 1, "the control left click sent no swing - the click never reached the game, so a "
                + "clean window below would prove nothing");

        JsonObject w = ew(6, 6, 14, 6, true);
        w.addProperty("awaitEnabled", true);
        w.addProperty("await", "SECRET");
        w.addProperty("amount", 1);
        writeRoute(ctx, List.of(w));
        long mark = LogTap.mark();
        warpOnto(ctx, 6.5, 10.5, 6, 6);
        check(waitFor(ctx, 60, () -> logHas(mark, "Node #1 ETHERWARP begins")), "the await node never began (at "
                + relPos(ctx) + ")");
        // His own mouse turn down at the floor while it waits (the route never stops for a turn during an await).
        ctx.runOnClient(mc -> mc.player.setXRot(70f));
        ctx.waitTicks(20);
        check(ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning")),
                "the route is not running while it waits on its await");
        check(!logHas(mark, "secrets met"), "the await was met with no secret");
        JsonObject before = stats();
        startSampling(ctx);
        ctx.getInput().holdKey(o -> o.keyAttack);
        ctx.waitTicks(3);
        ctx.getInput().releaseKey(o -> o.keyAttack);
        Vec3 landed = waitLanded(ctx, 14, 6, 60);
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        printTrace("awaitskip", s);
        check(logHas(mark, "left click skipped the await"), "the left click did not skip the await (log)");
        check(landed != null, "the left click did not fire the etherwarp (at " + relPos(ctx) + ")");
        check(useTicks(s).size() == 1, "the skip sent " + useTicks(s).size() + " use_item packet(s), expected 1");
        check(delta(before, "etherwarps") == 1, "the server made " + delta(before, "etherwarps") + " etherwarp(s)");
        // A use that succeeds client-side swings the hand in its own tick; any other swing is the click getting through.
        int stray = 0;
        for (Sample x : s) {
            stray += (int) Math.max(0, x.out().stream().filter("swing"::equals).count()
                    - x.out().stream().filter("use_item"::equals).count());
        }
        check(stray == 0, "the swallowed left click still sent " + stray + " swing(s) of its own");
        check(count(s, "player_action") == 0, "the swallowed left click still sent " + count(s, "player_action")
                + " player_action packet(s)");
        // ---- a crypt node's wait for its kill: his left click ends it (killer560, 2026-10-06) ----
        Object arCfg = ctx.computeOnClient(mc -> ModUnderTest.config(AR_CONFIG));
        int ticksBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(arCfg, "getCryptAttackTicks",
                new Class<?>[]{}, new Object[]{}));
        ctx.runOnClient(mc -> {
            ModUnderTest.call(arCfg, "setCryptWeapon", new Class<?>[]{enumClass("CryptWeapon")},
                    new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$CryptWeapon", "HYPERION")});
            ModUnderTest.call(arCfg, "setCryptAttackTicks", new Class<?>[]{int.class}, new Object[]{200});
        });
        List<Sample> cs;
        try {
            resetRoutes(ctx);
            JsonObject crypt = node("CRYPT", 20, 14, 0f, 10f);
            crypt.addProperty("start", true);
            writeRoute(ctx, List.of(crypt));
            long cm = LogTap.mark();
            startSampling(ctx);
            walkOnto(ctx, 20.5, 11.5, 20, 14);
            check(waitFor(ctx, 60, () -> logHas(cm, "holding use")), "the crypt node never started holding use");
            ctx.waitTicks(12);
            ctx.getInput().holdKey(o -> o.keyAttack);
            ctx.waitTicks(3);
            ctx.getInput().releaseKey(o -> o.keyAttack);
            boolean skipped = waitFor(ctx, 10, () -> logHas(cm, "left click skipped the kill wait"));
            ctx.waitTicks(10);
            cs = stopSampling(ctx);
            printTrace("awaitskip crypt", cs);
            check(skipped, "his left click did not end the crypt node's wait for a kill");
            check(!logHas(cm, "up, moving on"), "the crypt node ran out its attack time instead of being skipped");
            check(ctx.computeOnClient(mc -> !mc.options.keyUse.isDown()), "the use key was left held after the skip");
            check(count(cs, "player_action") == 0, "the crypt skip sent " + count(cs, "player_action")
                    + " player_action packet(s) - the swallowed click dug");
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.call(arCfg, "setCryptAttackTicks", new Class<?>[]{int.class},
                    new Object[]{ticksBefore}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                    new Object[]{"test"}));
        }
        c.note("awaitskip: control click " + count(control, "swing") + " swing / " + count(control, "player_action")
                + " player_action; await skipped by a left click, 1 use, 0 stray swing, 0 player_action, landed " + landed
                + "; crypt kill wait skipped by a left click, 0 player_action");
        List<Sample> all = new ArrayList<>(s);
        all.addAll(cs);
        noteFlags(c, all);
    }

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
                // At 20/s the pace is the server's answer time, not the route's: every warp went on its landing's tick
                // (firesOnLanding), so a warp only waits for the landing. Judged against the round trip measured on
                // this run: the client must add nothing to it (the same jar read 1.00 and 1.27 an hour apart on a busy
                // machine, the slow runs' gaps exactly their round trips - fullclear-1016).
                double want = rate == 20 ? Math.max(1.0, chain.meanRoundTrip()) : 20.0 / rate;
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

    /**
     * 62-argrim-1025-zpchain (killer560, 2026-10-25: zero ping etherwarp "for auto routes and such"): the twelve-warp chain
     * at 150 ms of latency, first as it is (the control: each warp waits for the previous landing, a round trip), then with
     * Zero Ping Chain on (each warp aimed from the previous one's PREDICTED landing, one a tick). Both must reach the end
     * with every server etherwarp made; with the chain every prediction must be confirmed by the server, none broken, and
     * the warps must come faster than the control's. GrimAC must say nothing.
     */
    private static void caseZeroPingChain(Session c) {
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
        double controlTpw = -1;
        try {
            ctx.runOnClient(mc -> dev.testkit.harness.Latency.install(150));
            ctx.waitTicks(40);
            for (int pass = 0; pass < 3; pass++) {
                boolean zp = pass >= 1;
                boolean blinkChain = pass == 2;
                ctx.runOnClient(mc -> {
                    ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setZeroPingChain", new Class<?>[]{boolean.class},
                            new Object[]{zp});
                    ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setBlinkChain", new Class<?>[]{boolean.class},
                            new Object[]{blinkChain});
                    ModUnderTest.call(ModUnderTest.config("com.killer560.hub.blink.BlinkConfig"), "setEnabled",
                            new Class<?>[]{boolean.class}, new Object[]{blinkChain});
                });
                ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                        new Object[]{"test"}));
                int chained0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingChained"));
                int confirmed0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingConfirmed"));
                int broken0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingBroken"));
                JsonObject before = stats();
                long m = LogTap.mark();
                startSampling(ctx);
                warpOnto(ctx, 6.5, 10.5, 6, 6);
                Vec3 landed = waitLanded(ctx, end[0], end[1], 600);
                ctx.waitTicks(20);
                List<Sample> smp = stopSampling(ctx);
                all.addAll(smp);
                ArChainMeasure.Chain chain = ArChainMeasure.chain(1);
                int acted = 0;
                int corrections = 0;
                for (String l : LogTap.since(m)) {
                    acted += l.contains("ETHERWARP acted") ? 1 : 0;
                    corrections += l.contains("[Correction] Auto Routes") ? 1 : 0;
                }
                int serverWarps = delta(before, "etherwarps");
                int chained = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingChained")) - chained0;
                int confirmed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingConfirmed"))
                        - confirmed0;
                int broken = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingBroken")) - broken0;
                String label = blinkChain ? "zero ping chain + blink" : zp ? "zero ping chain" : "control";
                int burstUses = 0;
                for (String l : LogTap.since(m)) {
                    if (l.contains("[Blink] released (") && l.contains("owner Auto Routes chain")) {
                        java.util.regex.Matcher bm = java.util.regex.Pattern.compile("UseItem=(\\d+)").matcher(l);
                        if (bm.find()) {
                            burstUses = Math.max(burstUses, Integer.parseInt(bm.group(1)));
                        }
                    }
                }
                if (blinkChain) {
                    println("zero ping chain + blink: the largest burst carried " + burstUses + " etherwarp use(s)");
                }
                println(String.format(Locale.ROOT, "%s at 150 ms: %s; server made %d etherwarp(s); %d acted; chained %d, "
                                + "confirmed %d, broken %d; %d correction line(s)", label, chain.describe(), serverWarps, acted,
                        chained, confirmed, broken, corrections));
                notes.append(String.format(Locale.ROOT, "%s %.2f ticks/warp (gaps %s); ", label, chain.ticksPerWarp(),
                        chain.gaps()));
                check(landed != null, label + ": the chain did not reach its end at " + end[0] + "," + end[1] + " (at "
                        + relPos(ctx) + ")");
                // PacketTrace hooks Connection.send, as Blink does: with Blink holding it counts a held packet when it is
                // made and again when the burst releases it, so the blinked pass is judged by the log and the server.
                check(acted == pts.length && (blinkChain || chain.warps() == pts.length), label + ": expected " + pts.length
                        + " route etherwarps, the log says " + acted + " acted and the trace has " + chain.warps());
                check(serverWarps == pts.length + 1, label + ": the server made " + serverWarps + " etherwarps, expected "
                        + (pts.length + 1));
                check(corrections == 0, label + ": " + corrections + " correction line(s) - a landing read as a correction");
                if (!zp) {
                    controlTpw = chain.ticksPerWarp();
                    check(chained == 0, "control: Zero Ping Chain chained " + chained + " warp(s) while switched off");
                } else {
                    check(chained >= pts.length - 2, "zero ping: only " + chained + " warp(s) were chained");
                    check(confirmed == chained && broken == 0, "zero ping: " + confirmed + " of " + chained
                            + " predicted landings confirmed, " + broken + " broken");
                    check(chain.usesBeforeLanding() > 0, "zero ping: no warp went out before the previous landing - "
                            + "nothing was chained on the wire");
                    if (!blinkChain) {
                        check(chain.ticksPerWarp() < controlTpw - 0.5, String.format(Locale.ROOT, "zero ping: %.2f ticks per "
                                + "warp is not faster than the control's %.2f", chain.ticksPerWarp(), controlTpw));
                    } else {
                        check(burstUses >= pts.length - 1, "blink chain: the largest released burst carried " + burstUses
                                + " etherwarp use(s), expected the whole chain's");
                        check(logHas(m, "[Blink] released (Auto Routes chain sent)"), "blink chain: the hold was never "
                                + "released by the chain's last warp (Blink's own limit or something else ended it)");
                    }
                }
            }
        } finally {
            ctx.runOnClient(mc -> {
                dev.testkit.harness.Latency.remove();
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setZeroPingChain", new Class<?>[]{boolean.class},
                        new Object[]{false});
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setBlinkChain", new Class<?>[]{boolean.class},
                        new Object[]{false});
                ModUnderTest.call(ModUnderTest.config("com.killer560.hub.blink.BlinkConfig"), "setEnabled",
                        new Class<?>[]{boolean.class}, new Object[]{false});
            });
        }
        c.note("zero ping chain: " + notes);
        noteFlags(c, all);
    }

    /**
     * 62-argrim-1025-chargechain (killer560, 2026-10-25: "multiple etherwarps in a tick for 0tick routes"): Blink Charge
     * banks 20 ticks (Charge Ticks) while he stands on the start node with a menu open (nothing arms under a screen - his terminal); the
     * menu closes, the route fires its first warp and Charged Chain spends the bank on the rest, so the chain's warps all
     * leave within a tick or two. Every server etherwarp made, every prediction confirmed, none broken, GrimAC silent.
     */
    private static void caseChargedChain(Session c) {
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
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setZeroPingChain", new Class<?>[]{boolean.class},
                        new Object[]{true});
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setChargedChain", new Class<?>[]{boolean.class},
                        new Object[]{true});
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setEnabled", true);
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setChargeMode",
                        dev.testkit.gametest.mod.Mod.enumValue("blink.BlinkConfig$Mode", "TOGGLE"));
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setChargeTicks", 20);
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setChatMessages", false);
                ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test"});
            });
            // Hypixel-like latency: without it every landing is back before the next tick and there is no chain to fire.
            ctx.runOnClient(mc -> dev.testkit.harness.Latency.install(150));
            ctx.waitTicks(40);
            // A menu open (his terminal), then onto the start node: nothing arms under a screen.
            ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
            ctx.waitTicks(3);
            tpRel(ctx, pts[0][0] + 0.5, pts[0][1] + 0.5, relYawTo(pts[0][0], pts[0][1], pts[1][0], pts[1][1]), 0f);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            ctx.waitTicks(10);
            // Charge on, through the test hook the Blink tick reads.
            ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.TRUE));
            ctx.waitTicks(1);
            ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.FALSE));
            ctx.waitTicks(35);
            int bank = ctx.computeOnClient(mc -> ((Number) ModUnderTest.staticCall("com.killer560.hub.blink.BlinkCharge", "banked")).intValue());
            int chained0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingChained"));
            int confirmed0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingConfirmed"));
            int broken0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingBroken"));
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            ctx.runOnClient(mc -> dev.testkit.compat.McCompat.setScreen(mc, null));
            Vec3 landed = waitLanded(ctx, end[0], end[1], 600);
            ctx.waitTicks(20);
            all.addAll(stopSampling(ctx));
            int acted = 0;
            int corrections = 0;
            int bestBurst = 0;
            java.util.regex.Pattern bp = java.util.regex.Pattern.compile("Charged Chain: (\\d+) warp\\(s\\) fired in one tick");
            for (String l : LogTap.since(m)) {
                acted += l.contains("ETHERWARP acted") ? 1 : 0;
                corrections += l.contains("[Correction] Auto Routes") ? 1 : 0;
                java.util.regex.Matcher bm = bp.matcher(l);
                if (bm.find()) {
                    bestBurst = Math.max(bestBurst, Integer.parseInt(bm.group(1)));
                }
            }
            int serverWarps = delta(before, "etherwarps");
            int chained = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingChained")) - chained0;
            int confirmed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingConfirmed")) - confirmed0;
            int broken = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(EXECUTOR, "zeroPingBroken")) - broken0;
            String line = String.format(Locale.ROOT, "charged chain: bank %d; largest burst %d warp(s) in one tick; server made %d "
                            + "etherwarp(s); %d acted; chained %d, confirmed %d, broken %d; %d correction line(s)", bank, bestBurst,
                    serverWarps, acted, chained, confirmed, broken, corrections);
            println(line);
            c.note(line);
            check(bank >= 20, "the bank did not fill on the start node: " + bank);
            check(landed != null, "the chain did not reach its end at " + end[0] + "," + end[1] + " (at " + relPos(ctx) + ")");
            check(acted == pts.length, "expected " + pts.length + " route etherwarps, the log says " + acted);
            check(serverWarps == pts.length, "the server made " + serverWarps + " etherwarps, expected " + pts.length);
            check(bestBurst >= pts.length - 2, "the largest charged burst fired " + bestBurst + " warp(s), expected the chain's");
            check(confirmed == chained && broken == 0, confirmed + " of " + chained + " predictions confirmed, " + broken
                    + " broken");
            check(corrections == 0, corrections + " correction line(s)");
        } finally {
            ctx.runOnClient(mc -> {
                dev.testkit.compat.McCompat.setScreen(mc, null);
                dev.testkit.harness.Latency.remove();
                dev.testkit.gametest.mod.Mod.setField("blink.BlinkFeature", "keyHeldForTest", null);
                dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", null);
                dev.testkit.gametest.mod.Mod.staticCall("blink.BlinkCharge", "reset", "test teardown");
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setZeroPingChain", new Class<?>[]{boolean.class},
                        new Object[]{false});
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setChargedChain", new Class<?>[]{boolean.class},
                        new Object[]{false});
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setEnabled", false);
            });
        }
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
        // node's ring along the approach (walked in, a node fires at the ring's edge, not its center).
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
     * 62-argrim-1016-auraawait (killer560, 2026-10-16: "never have secret aura grab secrets while it is auto routing unless
     * it is on an await node"). Secret Aura on. Pass 1: a WALK start node sprints him past a floor lever 1.5 blocks to the
     * side, onto an UNSNEAK node out of the lever's reach: no use_item_on, the lever stays off. Pass 2: a WALK start node
     * with await:1 beside another lever, the aura switched on once the node waits: it flips the lever, the await is met,
     * the walk goes on and the route completes. GrimAC must say nothing about either.
     */
    private static void caseAuraAwait(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        String cheat = "com.killer560.hub.cheatutils.CheatUtilsConfig";
        setBlocks(ctx, Map.of(new int[]{16, F, 12}, "minecraft:lever[face=floor,facing=north,powered=false]",
                new int[]{6, F, 11}, "minecraft:lever[face=floor,facing=north,powered=false]"));
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(cheat);
            ModUnderTest.set(cfg, "setAuraLevers", true);
            ModUnderTest.set(cfg, "setAuraPauseWhileSneaking", false);
            ModUnderTest.set(cfg, "setSecretAuraEnabled", true);
        });
        try {
            // ---- pass 1: a non-await stretch ----
            JsonObject w1 = node("WALK", 14, 6, 0f, 0f);
            w1.addProperty("start", true);
            writeRoute(ctx, List.of(w1, node("UNSNEAK", 14, 22, 0f, 0f)));
            long m1 = LogTap.mark();
            startSampling(ctx);
            walkOnto(ctx, 14.5, 4.5, 14, 6);
            boolean done1 = waitFor(ctx, 100, () -> logHas(m1, "\" complete") || logHas(m1, "Stopped"));
            ctx.waitTicks(10);
            List<Sample> s1 = stopSampling(ctx);
            boolean leverA = String.valueOf(blockState(ctx, 16, F, 12)).contains("powered=true");
            int clicks1 = count(s1, "use_item_on");
            Vec3 end1 = relPos(ctx);
            println(String.format(Locale.ROOT, "auraawait pass 1: complete %s, end z %.2f, use_item_on %d, lever on %s",
                    logHas(m1, "\" complete"), end1.z, clicks1, leverA));
            check(done1 && logHas(m1, "\" complete"), "pass 1: the walk route did not run to its end");
            check(end1.z > 20, "pass 1: the walk did not take him past the lever (ended at " + end1 + ")");
            check(clicks1 == 0 && !leverA, "pass 1: Secret Aura clicked during a non-await node (" + clicks1
                    + " use_item_on, lever on " + leverA + ")");
            noteFlags(c, s1);

            // ---- pass 2: an await node ----
            ctx.runOnClient(mc -> ModUnderTest.turnOff(cheat, "setSecretAuraEnabled"));
            JsonObject w2 = node("WALK", 8, 10, 0f, 0f);
            w2.addProperty("start", true);
            w2.addProperty("awaitEnabled", true);
            w2.addProperty("await", "SECRET");
            w2.addProperty("amount", 1);
            writeRoute(ctx, List.of(w2, node("UNSNEAK", 8, 22, 0f, 0f)));
            long m2 = LogTap.mark();
            startSampling(ctx);
            walkOnto(ctx, 8.5, 7.5, 8, 10);
            ctx.waitTicks(5);
            boolean waiting = !logHas(m2, "secrets met")
                    && ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isRunning"));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(cheat), "setSecretAuraEnabled", true));
            boolean met = waitFor(ctx, 80, () -> logHas(m2, "secrets met"));
            boolean done2 = waitFor(ctx, 100, () -> logHas(m2, "\" complete") || logHas(m2, "Stopped"));
            ctx.waitTicks(5);
            List<Sample> s2 = stopSampling(ctx);
            boolean leverB = String.valueOf(blockState(ctx, 6, F, 11)).contains("powered=true");
            int clicks2 = count(s2, "use_item_on");
            println("auraawait pass 2: waiting " + waiting + ", met " + met + ", complete " + logHas(m2, "\" complete")
                    + ", use_item_on " + clicks2 + ", lever on " + leverB);
            check(waiting, "pass 2: the await node was not waiting before the aura went on");
            check(leverB && clicks2 >= 1, "pass 2: Secret Aura did not flip the lever on the await node (" + clicks2
                    + " use_item_on)");
            check(met, "pass 2: the await was not met by the aura's lever");
            check(done2 && logHas(m2, "\" complete"), "pass 2: the route did not go on to its end after the await");
            c.note("auraawait: non-await walk past a lever 0 use_item_on; await node " + clicks2
                    + " use_item_on, lever flipped, await met, route complete");
            noteFlags(c, s2);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.turnOff(cheat, "setSecretAuraEnabled"));
            resetRoutes(ctx);
        }
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

    /**
     * 62-argrim-1015-cryptaim (killer560, 2026-10-15: "make the crypt node follow the same logic as bat where it will try to
     * look at a crypt as well. If there are two or more that spawned have it do 2 bats at each before moving on"). Two
     * unstarred crypt mobs as Hypixel shows them - a zombie with a "(sword) Crypt Undead 512.6k(heart)" name-tag stand over it (his run screenshot)
     * (dungeon.starred with a name) - stand 9 blocks either side of a Spirit Sceptre crypt node. The server emulates no
     * sceptre, so nothing dies: the node must send its two held uses down, then exactly two plain use_item packets aimed
     * at each mob, move on, and draw nothing from GrimAC. Only the client's packets are under test.
     */
    private static void caseCryptAim(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        c.hx().give(5, "minecraft:iron_sword[custom_data={id:\"BAT_WAND\"},unbreakable={}]");
        ctx.runOnClient(mc -> {
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.call(ar, "setCryptWeapon", new Class<?>[]{enumClass("CryptWeapon")},
                    new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$CryptWeapon", "SPIRIT_SCEPTRE")});
            ModUnderTest.set(ar, "setCryptAttackTicks", 200);
        });
        double[][] spots = {{23.5, 14.5}, {5.5, 14.5}};
        List<JsonObject> mobs = new ArrayList<>();
        try {
            for (double[] sp : spots) {
                Vec3 at = ctx.computeOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                            double.class, double.class}, new Object[]{frame, sp[0], (double) F, sp[1]});
                });
                mobs.add(c.hx().call("dungeon.starred", "x", at.x, "y", at.y, "z", at.z, "name",
                        "⚔ Crypt Undead 512.6k❤").getAsJsonObject());
            }
            ctx.waitTicks(20);
            JsonObject crypt = node("CRYPT", 14, 14, 0f, 10f);
            crypt.addProperty("start", true);
            writeRoute(ctx, List.of(crypt));
            long m = LogTap.mark();
            startSampling(ctx);
            walkOnto(ctx, 14.5, 11.5, 14, 14);
            boolean done = waitFor(ctx, 200, () -> logHas(m, "every crypt mob dead or given its"));
            ctx.waitTicks(10);
            List<Sample> s = stopSampling(ctx);
            printTrace("cryptaim", s);
            int aimed = 0;
            for (String l : LogTap.since(m)) {
                if (l.contains("CRYPT")) {
                    println("  " + l.replaceAll("^.*?\\[AutoRoutes\\]", "[AutoRoutes]"));
                }
                if (l.contains("CRYPT: Spirit Sceptre use ")) {
                    aimed++;
                }
            }
            List<String> states = new ArrayList<>();
            for (JsonObject mob : mobs) {
                states.add(c.hx().call("dungeon.alive", "id", mob.get("mob").getAsString()).getAsString());
            }
            boolean keyUp = ctx.computeOnClient(mc -> !mc.options.keyUse.isDown());
            println("cryptaim: node done " + done + ", aimed uses " + aimed + ", use_item " + count(s, "use_item")
                    + ", use_item_on " + count(s, "use_item_on") + ", mobs " + states + ", use key up " + keyUp);
            check(logHas(m, "aiming at crypt mob"), "the crypt node never aimed at a crypt mob");
            check(done, "the crypt node did not move on after its uses");
            check(aimed == 4, "the crypt node aimed " + aimed + " use(s), expected 2 at each of 2 mobs");
            for (JsonObject mob : mobs) {
                int id = mob.get("mobId").getAsInt();
                check(logHas(m, "use 1/2 at crypt mob " + id) && logHas(m, "use 2/2 at crypt mob " + id),
                        "the crypt mob " + id + " did not get its two uses");
            }
            check(keyUp, "the use key was left held after the crypt node");
            c.note("cryptaim: 2 held uses down, then " + aimed + " aimed sceptre uses at 2 crypt mobs (use_item "
                    + count(s, "use_item") + ")");
            noteFlags(c, s);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class},
                    new Object[]{"test"}));
            for (JsonObject mob : mobs) {
                c.hx().call("dungeon.alive", "id", mob.get("mob").getAsString(), "stand", mob.get("stand").getAsString());
            }
            c.server().command("kill @e[type=minecraft:zombie]");
            ctx.runOnClient(mc -> {
                Object ar = ModUnderTest.config(AR_CONFIG);
                ModUnderTest.call(ar, "setCryptWeapon", new Class<?>[]{enumClass("CryptWeapon")},
                        new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$CryptWeapon", "HYPERION")});
                ModUnderTest.set(ar, "setCryptAttackTicks", 100);
            });
        }
    }

    /**
     * mod ar-bridges-bat-1017 (killer560, 2026-10-17: Kill Mimic's Spirit Sceptre, two uses straight down, then the rest
     * aimed at the mimic, in every room). A trapped chest five blocks up and a block across (within the server's block
     * reach - the first version stood it 2.5 across and the harness's own click drew FarPlace and RotationPlace),
     * clicked by hand with the body turned to it; a baby zombie (NoAI, invulnerable: the uses are what is measured) stands on it as the mimic. The
     * first two uses go at pitch 90.0, then the body turns to the mimic and the uses go along the look the server has
     * heard, until the Mimic Timeout. Against GrimAC: nothing.
     */
    private static void caseMimicAim(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        c.hx().give(5, "minecraft:iron_sword[custom_data={id:\"BAT_WAND\"},unbreakable={}]");
        int timeout0 = ctx.computeOnClient(mc -> ModUnderTest.getInt(ModUnderTest.config(AR_CONFIG), "getMimicTimeoutTicks"));
        ctx.runOnClient(mc -> {
            ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setKillMimic", new Class<?>[]{enumClass("KillMimic")},
                    new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$KillMimic", "SPIRIT_SCEPTRE")});
            ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setMimicTimeoutTicks", 40);
        });
        try {
            setBlocks(ctx, Map.of(new int[]{13, F + 5, 22}, "minecraft:trapped_chest"));
            tpRel(ctx, 14.5, 23.5, 0f, -50f);
            ctx.waitTicks(10);
            ctx.runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(3);
                BlockPos chest = real(ModUnderTest.staticCall(FRAME, "current"), 13, F + 5, 22);
                Vec3 eye = mc.player.getEyePosition();
                Vec3 at = new Vec3(chest.getX() + 0.5, chest.getY() + 0.05, chest.getZ() + 0.5);
                double dx = at.x - eye.x;
                double dy = at.y - eye.y;
                double dz = at.z - eye.z;
                float ly = (float) -Math.toDegrees(Math.atan2(dx, dz));
                mc.player.setYRot(mc.player.getYRot() + net.minecraft.util.Mth.wrapDegrees(ly - mc.player.getYRot()));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            });
            ctx.waitTicks(3);
            long m = LogTap.mark();
            startSampling(ctx);
            ctx.runOnClient(mc -> {
                BlockPos chest = real(ModUnderTest.staticCall(FRAME, "current"), 13, F + 5, 22);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(
                        new Vec3(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5), Direction.DOWN, chest, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
            });
            BlockPos top = ctx.computeOnClient(mc -> real(ModUnderTest.staticCall(FRAME, "current"), 13, F + 6, 22));
            c.server().command(String.format(Locale.ROOT, "summon minecraft:zombie %d %d %d {IsBaby:1b,NoAI:1b,NoGravity:1b,"
                    + "Invulnerable:1b,PersistenceRequired:1b}", top.getX(), top.getY(), top.getZ()));
            waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null));
            ctx.runOnClient(mc -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
            });
            boolean done = waitFor(ctx, 160, () -> logHas(m, "Kill Mimic done"));
            ctx.waitTicks(10);
            List<Sample> s = stopSampling(ctx);
            printTrace("mimicaim", s);
            int down = 0;
            int aimed = 0;
            for (String l : LogTap.since(m)) {
                if (l.contains("Kill Mimic")) {
                    println("  " + l);
                }
                if (l.contains("Spirit Sceptre used straight down")) {
                    down++;
                }
                if (l.contains("Spirit Sceptre used at the mimic")) {
                    aimed++;
                }
            }
            c.note(String.format(Locale.ROOT, "mimicaim: %d use(s) straight down, %d at the mimic, %d use_item, done %s",
                    down, aimed, count(s, "use_item"), done));
            check(logHas(m, "watching for its mimic"), "the trapped-chest click was not seen");
            check(down == 2, down + " use(s) went straight down, expected 2");
            check(aimed >= 2, "only " + aimed + " use(s) went at the mimic");
            check(done, "Kill Mimic did not finish (Mimic Timeout)");
            noteFlags(c, s);
        } finally {
            c.server().command("kill @e[type=minecraft:zombie]");
            ctx.runOnClient(mc -> {
                ModUnderTest.call(ModUnderTest.config(AR_CONFIG), "setKillMimic", new Class<?>[]{enumClass("KillMimic")},
                        new Object[]{ModUnderTest.enumValue(AR_CONFIG + "$KillMimic", "OFF")});
                ModUnderTest.set(ModUnderTest.config(AR_CONFIG), "setMimicTimeoutTicks", timeout0);
            });
        }
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

    /**
     * Auto Clear (mod 2026-10-06) on GrimAC: three starred mobs ({@code dungeon.starred}: a zombie and its marker star
     * stand, as Hypixel shows one) in the arena with its middle wall up - one in the open 7 blocks away, one standing
     * INSIDE the middle wall, one behind it - and {@code AutoClearFeature.clearRoom(room)} with the Hyperion. The server
     * emulates Wither Impact ({@code HxAbilities.witherImpact}: 10-block dash, 6-block blast). All three must die to the
     * blasts (no entity packet at all), clearRoom must call onDone (no dungeon map here, so the room is done once no
     * starred mob is left in sight), and GrimAC is read for the whole stretch.
     */
    private static void caseAutoClear(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, true);
        String auto = "com.killer560.hub.autoclear.AutoClearFeature";
        String autoCfg = "com.killer560.hub.autoclear.AutoClearConfig";
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(autoCfg);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")
                    .getClass()}, new Object[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")});
        });
        tpRel(ctx, 5.5, 15.5, -90f, 0f);
        ctx.waitTicks(10);
        // Every two mobs more than 10.6 blocks apart (mod ac-optimize-1015): no one standing block reaches two of them with
        // the 6-block blast, so Auto Clear's fewest-repositions plan is one block a mob and the open one is a hop.
        double[][] spots = {{11.5, 8.5}, {15.5, 21.5}, {22.5, 9.5}};
        String[] what = {"in the open, 9 blocks away", "inside the middle wall", "behind the middle wall"};
        List<JsonObject> mobs = new ArrayList<>();
        for (double[] sp : spots) {
            Vec3 at = ctx.computeOnClient(mc -> {
                Object frame = ModUnderTest.staticCall(FRAME, "current");
                return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                        double.class, double.class}, new Object[]{frame, sp[0], (double) F, sp[1]});
            });
            mobs.add(c.hx().call("dungeon.starred", "x", at.x, "y", at.y, "z", at.z).getAsJsonObject());
        }
        ctx.waitTicks(20);
        int seen = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall("com.killer560.hub.mobesp.MobEspFeature",
                "starredMobs", new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc})).size());
        println("autoclear: " + mobs.size() + " starred mob(s) placed, " + seen + " resolved by the client: " + mobs);
        JsonObject before = stats();
        java.util.concurrent.atomic.AtomicReference<String> done = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> gaveUp = new java.util.concurrent.atomic.AtomicReference<>();
        long m = LogTap.mark();
        Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
        startSampling(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "clearRoom", new Class<?>[]{String.class, Runnable.class,
                java.util.function.Consumer.class}, new Object[]{room, (Runnable) () -> done.set("done"),
                (java.util.function.Consumer<String>) gaveUp::set}));
        boolean finished = waitFor(ctx, 900, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(auto,
                "isBusy")));
        if (!finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "cancel"));
        }
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(autoCfg), "setEnabled", false));
        List<String> states = new ArrayList<>();
        for (JsonObject mob : mobs) {
            states.add(c.hx().call("dungeon.alive", "id", mob.get("mob").getAsString(), "stand",
                    mob.get("stand").getAsString()).getAsString());
        }
        int interacts = 0;
        for (Sample x : s) {
            interacts += (int) x.out().stream().filter(p -> p.contains("interact")).count();
        }
        for (String l : LogTap.since(m)) {
            if (l.contains("[AutoClear]")) {
                println("  " + l.replaceAll("^.*?\\[AutoClear\\]", "[AutoClear]"));
            }
        }
        printTrace("autoclear", s);
        println("autoclear: " + (finished ? "finished" : "STILL RUNNING") + ", onDone=" + done.get() + " onGiveUp="
                + gaveUp.get() + ", mobs " + states + ", Wither Impacts " + delta(before, "witherImpacts") + " (hits "
                + delta(before, "witherHits") + "), etherwarps " + delta(before, "etherwarps") + ", use_item "
                + count(s, "use_item") + ", entity packets " + interacts + String.format(Locale.ROOT,
                ", moved %.1f blocks", start.distanceTo(end)));
        for (int i = 0; i < mobs.size(); i++) {
            check(!"alive".equals(states.get(i)), "the starred mob " + what[i] + " is still alive");
        }
        check(seen >= 3, "the client resolved only " + seen + " of the 3 starred mobs - nothing to clear");
        check(interacts == 0, interacts + " entity packet(s): a melee hit or an entity click");
        check(delta(before, "witherImpacts") > 0, "the server saw no Wither Impact");
        boolean hopped = logHas(m, "[AutoClear] hop toward");
        boolean hopLanded = hopped && !logHas(m, "never landed");
        // A standing block that reaches two mobs is fewer repositions than a hop toward one (mod ac-optimize-1015): then
        // the plan takes it and no hop is owed; a hop that went must still have landed.
        boolean shared = false;
        for (String l : LogTap.since(m)) {
            shared |= l.contains("[AutoClear] reposition plan") && java.util.regex.Pattern.compile(" x[2-9]").matcher(l).find();
        }
        println("autoclear: Hyperion hop tried " + hopped + ", every hop landed " + hopLanded + ", a planned block"
                + " reaching two or more mobs " + shared);
        check(hopped ? hopLanded : shared, "no Hyperion hop, or one that never landed (the server's dash did not move him)");
        check(finished && done.get() != null && gaveUp.get() == null, "clearRoom ended onDone=" + done.get()
                + " onGiveUp=" + gaveUp.get());
        c.note("autoclear: 3 starred mobs (open, in a wall, behind a wall) killed by " + delta(before, "witherImpacts")
                + " Wither Impact(s) and " + delta(before, "etherwarps") + " etherwarp(s), no entity packet");
        noteFlags(c, s);
    }

    /**
     * Auto Clear with the Spirit Sceptre on GrimAC (mod ac-1020, 2026-10-20): three starred zombies 12-15 blocks off in
     * the open arena, one walked back and forth by the server ({@code dungeon.move}, 0.25 blocks a tick) so the bats are
     * LED, and a mana figure on the action bar (1000 max: the one-tap Intelligence floor is 4,480, the tags say 20) so
     * every first bat is trusted and Auto Clear moves on to the next mob the tick it leaves. The server has no bat, so
     * nothing dies: each mob is shot, left, shot again when its bat is late, and skipped at Mob Timeout. What is read is
     * the client's traffic under that - fast turns between mobs, a use per turn - and GrimAC for the whole stretch.
     */
    private static void caseAutoClearBat(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        c.hx().give(5, "minecraft:iron_sword[custom_data={id:\"BAT_WAND\"},unbreakable={}]");
        String auto = "com.killer560.hub.autoclear.AutoClearFeature";
        String autoCfg = "com.killer560.hub.autoclear.AutoClearConfig";
        boolean isNew = ctx.computeOnClient(mc -> {
            Object cfg = ModUnderTest.config(autoCfg);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", false);
            ModUnderTest.call(cfg, "setMobTimeoutTicks", new Class<?>[]{int.class}, new Object[]{80});
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(autoCfg + "$Weapon",
                    "SPIRIT_SCEPTRE").getClass()}, new Object[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "SPIRIT_SCEPTRE")});
            try {
                ModUnderTest.set(cfg, "setPredictiveBats", true);
                Object at = ModUnderTest.enumValue(autoCfg + "$OneTap", "AT_ONCE");
                ModUnderTest.call(cfg, "setOneTap", new Class<?>[]{at.getClass()}, new Object[]{at});
                return true;
            } catch (RuntimeException | Error e) {
                return false;
            }
        });
        c.hx().call("actionbar.packet", "text", "§c1000/1000❤     §b1000/1000✎ Mana");
        tpRel(ctx, 5.5, 15.5, -90f, 0f);
        ctx.waitTicks(10);
        double[][] spots = {{19.5, 8.5}, {20.5, 15.5}, {19.5, 22.5}};
        List<JsonObject> mobs = new ArrayList<>();
        List<Vec3> at = new ArrayList<>();
        for (double[] sp : spots) {
            Vec3 v = ctx.computeOnClient(mc -> {
                Object frame = ModUnderTest.staticCall(FRAME, "current");
                return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                        double.class, double.class}, new Object[]{frame, sp[0], (double) F, sp[1]});
            });
            at.add(v);
            mobs.add(c.hx().call("dungeon.starred", "x", v.x, "y", v.y, "z", v.z).getAsJsonObject());
        }
        ctx.waitTicks(20);
        long m = LogTap.mark();
        startSampling(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "clearRoom", new Class<?>[]{String.class, Runnable.class,
                java.util.function.Consumer.class}, new Object[]{room, (Runnable) () -> { }, (java.util.function.Consumer<String>)
                why -> { }}));
        // The middle mob walks along the arena's z axis, 0.25 a tick, 4 blocks each way.
        Vec3 base = at.get(1);
        double off = 0;
        double dir = 0.5;
        int ticks = 0;
        for (; ticks < 600; ticks += 2) {
            ctx.waitTicks(2);
            off += dir;
            if (Math.abs(off) >= 4) {
                dir = -dir;
            }
            Vec3 p = ctx.computeOnClient(mc -> {
                Object frame = ModUnderTest.staticCall(FRAME, "current");
                return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                        double.class, double.class}, new Object[]{frame, 20.5, (double) F, 15.5});
            });
            Vec3 alongZ = ctx.computeOnClient(mc -> {
                Object frame = ModUnderTest.staticCall(FRAME, "current");
                return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                        double.class, double.class}, new Object[]{frame, 20.5, (double) F, 16.5});
            });
            Vec3 step = alongZ.subtract(p);
            Vec3 to = base.add(step.scale(off));
            c.hx().call("dungeon.move", "id", mobs.get(1).get("mob").getAsString(), "stand",
                    mobs.get(1).get("stand").getAsString(), "x", to.x, "y", to.y, "z", to.z);
            if (!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(auto, "isBusy"))) {
                break;
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "cancel"));
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(autoCfg), "setEnabled", false));
        int trusted = 0;
        int led = 0;
        int interacts = 0;
        for (String l : LogTap.since(m)) {
            if (l.contains("[AutoClear]")) {
                println("  " + l.replaceAll("^.*?\\[AutoClear\\]", "[AutoClear]"));
            }
            trusted += l.contains("one-tap:") && l.contains("trusting the bat") ? 1 : 0;
            led += l.contains("leading the bat") ? 1 : 0;
        }
        for (Sample x : s) {
            interacts += (int) x.out().stream().filter(p -> p.contains("interact")).count();
        }
        printTrace("acbat", s);
        println("acbat: jar " + (isNew ? "NEW" : "OLD") + ", " + ticks + " ticks, use_item " + count(s, "use_item")
                + ", one-tap trusted " + trusted + ", lead lines " + led + ", entity packets " + interacts);
        check(count(s, "use_item") > 0, "no use_item at all - Auto Clear never fired a bat, so nothing was measured");
        check(interacts == 0, interacts + " entity packet(s)");
        if (isNew) {
            check(trusted > 0, "the new jar never trusted a bat (the one-tap path did not run)");
            check(led > 0, "the new jar never led the walking mob (the predictive path did not run)");
        }
        c.note("acbat: " + count(s, "use_item") + " sceptre uses at 3 mobs (one walking), one-tap trusted " + trusted
                + ", led " + led);
        noteFlags(c, s);
    }

    /**
     * {@code /test} (mod battest-1020, killer560 2026-10-20): the Spirit Sceptre bat measurement as typed, on GrimAC. One
     * starred zombie ({@code dungeon.starred}, NoAI) at rel (24.5, 15.5) of the open arena, him at (13.5, 15.5) facing away
     * from it; the server emulates no sceptre, so no bat ever flies and every fired shot ends "no bat seen" - what is under
     * test is everything the client SENDS: the moves to ~10/20/30 blocks (ShortWalk or a map etherwarp), the hotbar swap,
     * each turn a tick before its use, and exactly one {@code use_item} per fired shot (at most five; 30 blocks does not
     * fit in this arena, so that shot is skipped). Requires the test to start and end with its summary, at least three
     * shots fired, one use per fired shot with the [Bat] use line for it, the use key up, and GrimAC silent.
     */
    private static void caseBatTest(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        String batTest = "com.killer560.hub.autoclear.BatTest";
        c.hx().give(5, "minecraft:iron_sword[custom_data={id:\"BAT_WAND\"},unbreakable={}]");
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"),
                "setEnabled", false));
        tpRel(ctx, 13.5, 15.5, 90f, 0f);
        ctx.waitTicks(10);
        Vec3 at = ctx.computeOnClient(mc -> {
            Object frame = ModUnderTest.staticCall(FRAME, "current");
            return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                    double.class, double.class}, new Object[]{frame, 24.5, (double) F, 15.5});
        });
        JsonObject mob = c.hx().call("dungeon.starred", "x", at.x, "y", at.y, "z", at.z).getAsJsonObject();
        ctx.waitTicks(20);
        List<Sample> s = List.of();
        try {
            int seen = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall("com.killer560.hub.mobesp.MobEspFeature",
                    "starredMobs", new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc})).size());
            println("battest: starred zombie at " + at + ", resolved by the client: " + seen + "; him at " + relPos(ctx));
            check(seen >= 1, "the client resolved no starred mob - /test would have nothing to shoot");
            JsonObject before = stats();
            long m = LogTap.mark();
            Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
            String batLog = "com.killer560.hub.autoclear.BatLog";
            int shotsBefore = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(batLog, "shots"));
            startSampling(ctx);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("test"));
            boolean started = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(batTest,
                    "isRunning")));
            boolean ended = started && waitFor(ctx, 2600, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    batTest, "isRunning")));
            ctx.waitTicks(5);
            s = stopSampling(ctx);
            Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
            int fired = 0;
            int skipped = 0;
            int useLines = 0;
            boolean summary = false;
            for (String l : LogTap.since(m)) {
                if (l.contains("[BatTest]") || l.contains("[Bat] #") && !l.contains(" fly ")) {
                    println("  " + l.replaceAll("^.*?(\\[Bat(Test)?\\])", "$1"));
                }
                fired += l.contains("[BatTest] shot") && l.contains(" done: bat #") ? 1 : 0;
                skipped += l.contains("[BatTest] shot") && l.contains(" skipped: ") ? 1 : 0;
                useLines += l.contains("[Bat] #") && l.contains(" use tick ") ? 1 : 0;
                summary |= l.contains("[BatTest] summary:") && l.contains("bat(s) seen");
            }
            boolean keyUp = ctx.computeOnClient(mc -> !mc.options.keyUse.isDown());
            int uses = count(s, "use_item");
            // BatLog logs every Spirit Sceptre use packet as it leaves: these are the shots; the rest are trip etherwarps.
            int sceptreUses = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(batLog, "shots")) - shotsBefore;
            printTrace("battest", s);
            println(String.format(Locale.ROOT, "battest: started %s, ended %s, fired %d, skipped %d, use_item %d (Spirit"
                            + " Sceptre %d), [Bat] use lines %d, etherwarps %d, moved %.1f blocks, summary %s, use key up %s",
                    started, ended, fired, skipped, uses, sceptreUses, useLines, delta(before, "etherwarps"),
                    start.distanceTo(end), summary, keyUp));
            check(started, "/test did not start");
            check(ended, "/test was still running");
            check(summary, "no [BatTest] summary line");
            check(fired >= 3, "only " + fired + " shot(s) fired");
            check(fired + skipped == 5, fired + " fired + " + skipped + " skipped is not the set of 5");
            check(sceptreUses == fired && useLines == fired && uses >= fired, "sceptre uses " + sceptreUses + " (use_item "
                    + uses + "), [Bat] use lines " + useLines + " for " + fired + " fired shot(s) - one use per shot expected");
            // It goes out for the 20-block shot and back for the 10-block ones, so where it ends says nothing: its moves do.
            boolean moved = logHas(m, "[BatTest] shot 2/5: the trip ended") || logHas(m, "[BatTest] shot 2/5: the walk ended ARRIVED");
            check(moved, "it never moved for the 20-block shot");
            check(keyUp, "the use key was left down");
            c.note(String.format(Locale.ROOT, "battest: %d shot(s) fired with %d sceptre use(s), %d skipped, moved %.1f blocks",
                    fired, sceptreUses, skipped, start.distanceTo(end)));
            noteFlags(c, s);
        } finally {
            if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(batTest, "isRunning"))) {
                ctx.runOnClient(mc -> mc.player.connection.sendCommand("test"));
            }
            c.hx().call("dungeon.alive", "id", mob.get("mob").getAsString(), "stand", mob.get("stand").getAsString());
            c.server().command("kill @e[type=minecraft:zombie]");
        }
    }

    /**
     * Auto Clear in King Midas on GrimAC (mod midas-1020, killer560 2026-10-19/20: the gold altar in the middle has to be
     * superboomed, and only then does the room's mob spawn and have to be killed). His captured King Midas is pasted over
     * the session's room and identified by the live map's own scan; there is no starred mob in it. {@code clearRoom} must
     * aim a Superboom at the altar's lid (the server's {@code HxAbilities} blows a smooth stone slab lid whole); the test
     * plays Hypixel's part and puts a starred mob on the altar only once the lid is gone, and Auto Clear must then kill it
     * with the Hyperion and call onDone. Order and GrimAC are judged on the whole stretch.
     */
    private static void caseMidas(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        Hx hx = c.hx();
        String midas = "King Midas";
        JsonObject pasted = paste(ctx, hx, midas);
        println("midas: pasted: " + pasted);
        // On the red carpet in front of the altar (capture x 16, z 20 - the paste puts capture x/z 16 on the cell center).
        hx.call("dungeon.tp", "x", CENTRE + 0.5, "y", 67.0625, "z", CENTRE + 4.5, "yaw", 180f, "pitch", 0f);
        ctx.waitTicks(10);
        // The live map keeps the cell's identification for the run: leave the dungeon (sidebar) and enter it again, so
        // its "Dungeon entered" grid reset makes it scan the cell afresh.
        hx.sidebar("SKYBLOCK", "§7Hub");
        boolean left = waitFor(ctx, 200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.secrets.DungeonState", "isInDungeon")));
        hx.sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        boolean back = waitFor(ctx, 400, () -> ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.secrets.DungeonState", "isInDungeon")));
        println("midas: left the dungeon " + left + ", back in " + back);
        boolean known = waitFor(ctx, 300, () -> midas.equals(ctx.computeOnClient(mc -> {
            Object f = ModUnderTest.staticCall(FRAME, "current");
            return f == null ? null : (String) ModUnderTest.call(f, "roomName", new Class<?>[]{}, new Object[]{});
        })));
        println("midas: live map " + (known ? "identified King Midas, frame " : "did NOT identify King Midas, frame ")
                + frameString(ctx));
        check(known, "the pasted King Midas was not identified by the live map - Auto Clear cannot find the room");
        ctx.waitTicks(20);
        // The lid, read from the client's world: every smooth stone slab within 12 blocks of the cell center.
        Supplier<List<BlockPos>> lid = () -> ctx.computeOnClient(mc -> {
            List<BlockPos> out = new ArrayList<>();
            for (int dx = -12; dx <= 12; dx++) {
                for (int dz = -12; dz <= 12; dz++) {
                    for (int y = 60; y <= 100; y++) {
                        BlockPos p = new BlockPos(CENTRE + dx, y, CENTRE + dz);
                        if (mc.level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)) {
                            out.add(p);
                        }
                    }
                }
            }
            return out;
        });
        List<BlockPos> lidBefore = lid.get();
        int seenBefore = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall(
                "com.killer560.hub.mobesp.MobEspFeature", "starredMobs", new Class<?>[]{net.minecraft.client.Minecraft.class},
                new Object[]{mc})).size());
        println("midas: " + lidBefore.size() + " lid slab(s) standing, " + seenBefore + " starred mob(s) in sight");
        check(lidBefore.size() >= 30, "the King Midas altar's lid is not standing (" + lidBefore.size() + " slabs)");
        check(seenBefore == 0, seenBefore + " starred mob(s) already in sight - the boom would prove nothing");
        double sx = 0;
        double sz = 0;
        int ly = lidBefore.get(0).getY();
        for (BlockPos p : lidBefore) {
            sx += p.getX() + 0.5;
            sz += p.getZ() + 0.5;
        }
        double mobX = sx / lidBefore.size();
        double mobZ = sz / lidBefore.size();

        String auto = "com.killer560.hub.autoclear.AutoClearFeature";
        String autoCfg = "com.killer560.hub.autoclear.AutoClearConfig";
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(autoCfg);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")
                    .getClass()}, new Object[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")});
        });
        JsonObject before = stats();
        java.util.concurrent.atomic.AtomicReference<String> done = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> gaveUp = new java.util.concurrent.atomic.AtomicReference<>();
        long m = LogTap.mark();
        startSampling(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "clearRoom", new Class<?>[]{String.class, Runnable.class,
                java.util.function.Consumer.class}, new Object[]{midas, (Runnable) () -> done.set("done"),
                (java.util.function.Consumer<String>) gaveUp::set}));
        JsonObject mob = null;
        int openedTick = -1;
        boolean finished = false;
        for (int t = 0; t < 900; t++) {
            ctx.waitTicks(1);
            if (mob == null && lid.get().size() < lidBefore.size()) {
                // Hypixel's part: the room's mob appears once the altar is open.
                openedTick = t;
                mob = hx.call("dungeon.starred", "x", mobX, "y", (double) ly, "z", mobZ).getAsJsonObject();
                println("midas: altar open at t" + t + " - starred mob placed at " + String.format(Locale.ROOT,
                        "%.1f %d %.1f", mobX, ly, mobZ) + ": " + mob);
            }
            if (!ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(auto, "isBusy"))) {
                finished = true;
                break;
            }
        }
        if (!finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "cancel"));
        }
        ctx.waitTicks(5);
        List<Sample> s = stopSampling(ctx);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(autoCfg), "setEnabled", false));
        String state = mob == null ? "never placed" : hx.call("dungeon.alive", "id", mob.get("mob").getAsString(), "stand",
                mob.get("stand").getAsString()).getAsString();
        for (String l : LogTap.since(m)) {
            if (l.contains("[AutoClear]") || l.contains("[testkit] superboom")) {
                println("  " + l.replaceAll("^.*?(\\[AutoClear\\]|\\[testkit\\])", "$1"));
            }
        }
        printTrace("midas", s);
        int boomAt = logIndex(m, "[AutoClear] King Midas: Superboom 1 on the altar");
        int openedAt = logIndex(m, "[AutoClear] King Midas: altar opened");
        int deadAt = logIndex(m, " in King Midas is dead");
        int interacts = 0;
        for (Sample x : s) {
            interacts += (int) x.out().stream().filter(p -> p.contains("interact")).count();
        }
        println("midas: " + (finished ? "finished" : "STILL RUNNING") + ", onDone=" + done.get() + " onGiveUp="
                + gaveUp.get() + ", superbooms " + delta(before, "superbooms") + " (blocks " + delta(before, "boomBlocks")
                + "), lid " + lidBefore.size() + " -> " + lid.get().size() + ", opened at t" + openedTick + ", mob " + state
                + ", Wither Impacts " + delta(before, "witherImpacts") + ", etherwarps " + delta(before, "etherwarps")
                + ", player_action " + count(s, "player_action") + ", entity packets " + interacts + "; log: boom "
                + boomAt + ", opened " + openedAt + ", dead " + deadAt);
        check(boomAt >= 0, "Auto Clear never superboomed the King Midas altar");
        check(delta(before, "superbooms") >= 1, "the server ran no Superboom");
        check(mob != null, "the altar's lid never broke in the client's world");
        check(openedAt > boomAt, "Auto Clear did not see the altar open after its boom");
        check(!"alive".equals(state), "the starred mob that came out of the altar is " + state);
        check(deadAt > openedAt, "the kill was not logged after the altar opened (opened " + openedAt + ", dead " + deadAt
                + ")");
        check(interacts == 0, interacts + " entity packet(s): a melee hit or an entity click");
        check(finished && done.get() != null && gaveUp.get() == null, "clearRoom ended onDone=" + done.get() + " onGiveUp="
                + gaveUp.get());
        c.note("midas: altar boomed (" + delta(before, "superbooms") + " superboom, " + delta(before, "boomBlocks")
                + " blocks), mob placed when it opened and killed by " + delta(before, "witherImpacts")
                + " Wither Impact(s); onDone");
        noteFlags(c, s);
    }

    /**
     * 62-argrim-1020-autoclearaura (killer560, 2026-10-20: Auto Clear "grabs a secret (a chest, lever or skull) while
     * killing. Then when Auto Routes comes through the same room, it is missing that secret and waits for it").
     * <p>
     * Pass 1: caseAutoClear's three starred mobs, with chests and levers beside the start and beside each mob (and one
     * chest at eye height on the line toward the first mob), Secret Aura on for chests and levers. While Auto Clear kills,
     * no secret may be clicked - not by the aura, not by the held Hyperion - while the aura must have SEEN a candidate and
     * been held by Auto Clear (so the pass is not vacuous). An item dropped at his feet mid-kill is picked up: Auto Routes'
     * run memory must hold it with its room's name. Pass 2: with no route running he opens chest A by hand. Pass 3: a WALK
     * start node with await:1 beside chest A, Secret Aura off: the await must be met at once from the run memory (chest A
     * already got) and the route run to its end - without the memory it would wait forever. GrimAC must say nothing.
     */
    private static void caseAutoClearAura(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, true);
        String auto = "com.killer560.hub.autoclear.AutoClearFeature";
        String autoCfg = "com.killer560.hub.autoclear.AutoClearConfig";
        String cheat = "com.killer560.hub.cheatutils.CheatUtilsConfig";
        String memory = "com.killer560.hub.autoroutes.RouteRunMemory";
        // chest A beside the start (the one taken by hand in pass 2), a floor lever beside the start, one chest at eye
        // height on the start-to-first-mob line, a chest and a lever beside the mobs.
        int[] chestA = {6, F, 15};
        int[] leverA = {4, F, 13};
        int[] chestEye = {7, F + 1, 13};
        int[] chestB = {12, F, 11};
        int[] leverB = {21, F, 11};
        int[] chestC = {13, F, 22};
        Map<int[], String> secrets = new LinkedHashMap<>();
        secrets.put(chestA, "minecraft:chest");
        secrets.put(leverA, "minecraft:lever[face=floor,facing=north,powered=false]");
        secrets.put(chestEye, "minecraft:chest");
        secrets.put(chestB, "minecraft:chest");
        secrets.put(leverB, "minecraft:lever[face=floor,facing=north,powered=false]");
        secrets.put(chestC, "minecraft:chest");
        // A ring of floor levers two blocks round each mob (not in the middle wall): Auto Clear hops off the start in a
        // few ticks, inside the aura's 150 ms first-seen wait, so these are what keep a secret in reach of where it
        // stands to kill and of its end-of-room wait (the first run saw no candidate past the wait: a vacuous pass).
        List<int[]> ring = new ArrayList<>();
        for (int[] mob : new int[][]{{11, 8}, {15, 21}, {22, 9}}) {
            for (int dx = -2; dx <= 2; dx += 2) {
                for (int dz = -2; dz <= 2; dz += 2) {
                    int x = mob[0] + dx;
                    int z = mob[1] + dz;
                    if ((dx != 0 || dz != 0) && x != 15 && x > 3 && x < 27 && z > 3 && z < 27) {
                        ring.add(new int[]{x, F, z});
                    }
                }
            }
        }
        for (int[] r : ring) {
            secrets.put(r, "minecraft:lever[face=floor,facing=north,powered=false]");
        }
        setBlocks(ctx, secrets);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(autoCfg);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")
                    .getClass()}, new Object[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")});
            Object aura = ModUnderTest.config(cheat);
            ModUnderTest.set(aura, "setAuraChests", true);
            ModUnderTest.set(aura, "setAuraLevers", true);
            ModUnderTest.set(aura, "setAuraPauseWhileSneaking", false);
            ModUnderTest.call(aura, "setAuraPauseHolding", new Class<?>[]{String.class}, new Object[]{""});
        });
        List<JsonObject> mobs = new ArrayList<>();
        try {
            tpRel(ctx, 5.5, 15.5, -90f, 0f);
            ctx.waitTicks(10);
            double[][] spots = {{11.5, 8.5}, {15.5, 21.5}, {22.5, 9.5}};
            for (double[] sp : spots) {
                Vec3 at = ctx.computeOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                            double.class, double.class}, new Object[]{frame, sp[0], (double) F, sp[1]});
                });
                mobs.add(c.hx().call("dungeon.starred", "x", at.x, "y", at.y, "z", at.z).getAsJsonObject());
            }
            ctx.waitTicks(20);

            // ---- pass 1: Auto Clear kills with Secret Aura on ----
            java.util.concurrent.atomic.AtomicReference<String> done = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> gaveUp = new java.util.concurrent.atomic.AtomicReference<>();
            long m = LogTap.mark();
            startSampling(ctx);
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(cheat), "setSecretAuraEnabled", true);
                // The aura goes off in the same client tick the run ends (onDone / onGiveUp run inside Auto Clear's tick),
                // so a click right after the kill cannot be mistaken for one during it.
                ModUnderTest.staticCall(auto, "clearRoom", new Class<?>[]{String.class, Runnable.class,
                        java.util.function.Consumer.class}, new Object[]{room, (Runnable) () -> {
                            ModUnderTest.turnOff(cheat, "setSecretAuraEnabled");
                            done.set("done");
                        }, (java.util.function.Consumer<String>) why -> {
                            ModUnderTest.turnOff(cheat, "setSecretAuraEnabled");
                            gaveUp.set(why);
                        }});
            });
            // An item dropped at his feet mid-kill: picked up while Auto Clear runs, as an item secret on his way would be.
            ctx.waitTicks(15);
            Vec3 dropAt = relPos(ctx);
            boolean busyAtDrop = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(auto, "isBusy"));
            c.server().command("execute at @p run summon minecraft:item ~ ~ ~ {Item:{id:\"minecraft:bone\",count:1},"
                    + "PickupDelay:0s}");
            boolean finished = waitFor(ctx, 900, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(auto,
                    "isBusy")));
            if (!finished) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(auto, "cancel"));
            }
            ctx.runOnClient(mc -> ModUnderTest.turnOff(cheat, "setSecretAuraEnabled"));
            ctx.waitTicks(5);
            List<Sample> s1 = stopSampling(ctx);
            List<String> states = new ArrayList<>();
            for (JsonObject mob : mobs) {
                states.add(c.hx().call("dungeon.alive", "id", mob.get("mob").getAsString(), "stand",
                        mob.get("stand").getAsString()).getAsString());
            }
            int auraClicks = 0;
            boolean auraHeld = false;
            boolean useTurned = false;
            for (String l : LogTap.since(m)) {
                if (l.contains("[SecretAura] click sent")) {
                    auraClicks++;
                    println("  " + l.replaceAll("^.*?\\[SecretAura\\]", "[SecretAura]"));
                }
                auraHeld |= l.contains("Auto Clear is killing mobs");
                useTurned |= l.contains("held use: the look meets the secret");
                if (l.contains("[AutoClear]") || l.contains("[RouteMemory] remembered")) {
                    println("  " + l.replaceAll("^.*?\\[(AutoClear|RouteMemory)\\]", "[$1]"));
                }
            }
            int useOn = count(s1, "use_item_on");
            // use_item_on alone is not a secret click: an etherwarp sends the block half of its right click on the stone
            // it lands on first (util/RightClick). Every click on a chest, lever or skull through the game mode is logged
            // by Auto Routes' await hook ("secret click sent"), whoever made it.
            int secretClicks = 0;
            for (String l : LogTap.since(m)) {
                if (l.contains("[AutoRoutes] secret click sent")) {
                    secretClicks++;
                    println("  " + l.replaceAll("^.*?\\[AutoRoutes\\]", "[AutoRoutes]"));
                }
            }
            boolean leversOff = true;
            for (var e : secrets.entrySet()) {
                if (e.getValue().contains("lever")) {
                    int[] p = e.getKey();
                    leversOff &= !String.valueOf(blockState(ctx, p[0], p[1], p[2])).contains("powered=true");
                }
            }
            String inRoom = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(memory, "gotInRoom",
                    new Class<?>[]{String.class}, new Object[]{room})));
            boolean itemRemembered = false;
            for (String l : LogTap.since(m)) {
                itemRemembered |= l.contains("[RouteMemory] remembered secret item picked up") && l.contains("in room " + room)
                        && l.contains("Auto Clear running=true");
            }
            printTrace("autoclearaura", s1);
            println(String.format(Locale.ROOT, "autoclearaura pass 1: %s, onDone=%s onGiveUp=%s, mobs %s, aura clicks %d, "
                            + "aura held by Auto Clear %s, held use turned off a secret %s, secret clicks %d, use_item_on %d "
                            + "(etherwarp block halves), levers off %s (%d), item dropped at %.1f,%.1f (Auto Clear busy %s), "
                            + "memory in %s: %s",
                    finished ? "finished" : "STILL RUNNING", done.get(), gaveUp.get(), states, auraClicks, auraHeld,
                    useTurned, secretClicks, useOn, leversOff, ring.size() + 2, dropAt.x, dropAt.z, busyAtDrop, room,
                    inRoom));
            for (int i = 0; i < mobs.size(); i++) {
                check(!"alive".equals(states.get(i)), "pass 1: starred mob " + i + " is still alive");
            }
            check(finished && done.get() != null && gaveUp.get() == null, "pass 1: clearRoom ended onDone=" + done.get()
                    + " onGiveUp=" + gaveUp.get());
            check(auraHeld, "pass 1: Secret Aura never had a secret in reach to hold back - the pass proves nothing");
            check(auraClicks == 0, "pass 1: Secret Aura clicked " + auraClicks + " secret(s) while Auto Clear was killing");
            check(secretClicks == 0, "pass 1: " + secretClicks + " click(s) on a chest, lever or skull while Auto Clear "
                    + "was killing");
            check(!inRoom.contains("chest at") && !inRoom.contains("lever at"), "pass 1: the run memory holds a chest or "
                    + "lever taken while Auto Clear was killing: " + inRoom);
            check(leversOff, "pass 1: a lever was flipped while Auto Clear was killing");
            check(busyAtDrop, "pass 1: Auto Clear had already finished when the item was dropped");
            check(itemRemembered, "pass 1: the item picked up during the kill was not remembered with room " + room);
            noteFlags(c, s1);

            // ---- pass 2: chest A taken by hand, no route running ----
            tpRel(ctx, 8.5, 16.5, 90f, 0f);
            ctx.waitTicks(5);
            long m2 = LogTap.mark();
            startSampling(ctx);
            rightClick(ctx, chestA[0], chestA[1], chestA[2], Direction.UP);
            boolean opened = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> McCompat.screen(mc) != null));
            ctx.runOnClient(mc -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
            });
            ctx.waitTicks(5);
            List<Sample> s2 = stopSampling(ctx);
            boolean chestRemembered = logHasAll(m2, "[RouteMemory] remembered secret chest at", "in room " + room);
            println("autoclearaura pass 2: chest A opened " + opened + ", remembered with its room " + chestRemembered
                    + ", use_item_on " + count(s2, "use_item_on"));
            // (Auto Close Chest, when on, cancels the screen: the click itself is what is checked.)
            check(count(s2, "use_item_on") == 1, "pass 2: the hand click on chest A sent " + count(s2, "use_item_on")
                    + " use_item_on");
            check(chestRemembered, "pass 2: chest A was not remembered with room " + room);
            noteFlags(c, s2);

            // ---- pass 3: the route's await beside chest A is met from memory ----
            JsonObject w = node("WALK", 8, 19, 0f, 0f);
            w.addProperty("start", true);
            w.addProperty("awaitEnabled", true);
            w.addProperty("await", "SECRET");
            w.addProperty("amount", 1);
            writeRoute(ctx, List.of(w, node("UNSNEAK", 8, 26, 0f, 0f)));
            long m3 = LogTap.mark();
            startSampling(ctx);
            walkOnto(ctx, 8.5, 16.5, 8, 19);
            boolean complete = waitFor(ctx, 100, () -> logHas(m3, "\" complete") || logHas(m3, "Stopped"));
            ctx.waitTicks(5);
            List<Sample> s3 = stopSampling(ctx);
            boolean counted = logHasAll(m3, "await counts chest at", "already got this run");
            boolean atOnce = logHas(m3, "await satisfied at once");
            for (String l : LogTap.since(m3)) {
                if (l.contains("Node #1") || l.contains("complete")) {
                    println("  " + l.replaceAll("^.*?\\[AutoRoutes\\]", "[AutoRoutes]"));
                }
            }
            println("autoclearaura pass 3: chest A counted from memory " + counted + ", met at once " + atOnce
                    + ", route complete " + logHas(m3, "\" complete") + ", use_item_on " + count(s3, "use_item_on"));
            check(counted && atOnce, "pass 3: the await beside chest A did not count it from the run memory");
            check(complete && logHas(m3, "\" complete"), "pass 3: the route did not run to its end (it waited for chest A)");
            check(count(s3, "use_item_on") == 0, "pass 3: the route clicked something (" + count(s3, "use_item_on") + ")");
            c.note("autoclearaura: Auto Clear killed 3 mobs beside 4 chests and 2 levers with Secret Aura on - 0 secret "
                    + "clicks (aura held by Auto Clear" + (useTurned ? ", held use turned off a secret" : "")
                    + "); item picked up mid-kill remembered in " + room + "; chest A by hand then met the route's await "
                    + "at once from memory, route complete");
            noteFlags(c, s3);
        } finally {
            ctx.runOnClient(mc -> {
                ModUnderTest.staticCall(auto, "cancel");
                ModUnderTest.turnOff(cheat, "setSecretAuraEnabled");
                ModUnderTest.set(ModUnderTest.config(autoCfg), "setEnabled", false);
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                }
            });
            c.server().command("kill @e[type=minecraft:zombie]");
            c.server().command("kill @e[type=minecraft:item]");
            resetRoutes(ctx);
        }
    }

    /**
     * stall-1020 (his Wizard #1, fullclear acplan-1020): a breaker node fired the tick an etherwarp landed, the sneak still
     * at the server, and its block 6 over his feet - 4.73 from the crouched eye (1.27), 4.38 from the standing one - was
     * left out of reach for good. The node must wait for the stand to reach the server, then dig it, against GrimAC: his
     * own sneaking etherwarp onto (14,14), the start node there a breaker of one snow block at F+6.
     */
    private static void caseBreakerStand(Session c) {
        ClientGameTestContext ctx = c.ctx();
        resetRoutes(ctx);
        arena(ctx, false);
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(DX_CONFIG), "setBreakerAuraMultiBreak", true));
        int high = F + 6;
        setBlocks(ctx, Map.of(new int[]{14, high, 14}, "minecraft:snow_block"));
        JsonObject br = node("DUNGEON_BREAKER", 14, 14, 0f, -90f);
        JsonArray blocks = new JsonArray();
        blocks.add("14 " + high + " 14");
        br.add("blocks", blocks);
        br.addProperty("start", true);
        writeRoute(ctx, List.of(br));
        long m = LogTap.mark();
        startSampling(ctx);
        warpOnto(ctx, 6.5, 18.5, 14, 14);
        boolean gone = waitFor(ctx, 80, () -> blockAir(ctx, 14, high, 14));
        ctx.waitTicks(10);
        List<Sample> s = stopSampling(ctx);
        printTrace("breaker stand", s);
        boolean held = logHas(m, "in reach only from the standing eye");
        boolean stood = logHas(m, "Breaker: standing after");
        c.note("breaker stand: block gone " + gone + ", held for the stand " + held + ", stood " + stood + ", "
                + count(s, "player_action") + " player_action");
        check(logHas(m, "DUNGEON_BREAKER begins") || logHas(m, "DUNGEON_BREAKER acted"),
                "the breaker node never fired after his etherwarp - the case proves nothing");
        check(held, "the breaker did not wait for the stand (the sneak was not at the server, or the block was in reach "
                + "crouched) - the case proves nothing");
        check(gone, "the block 6 over his feet was never broken");
        noteFlags(c, s);
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
    private static void caseAutoSecret(Session c, boolean pilot) {
        caseAutoSecret(c, pilot, false);
    }

    /**
     * autosecret-bloodfirst-1017: {@link #caseAutoSecret} with Secret Blood First on Door Camp. The arena's map has no blood
     * door and no wither door, so Blood First has no step: it must log that and run the route exactly as Off does, with
     * GrimAC silent. (The door trip itself is the Interactive Map's door pathing, already in this suite's map cases; the
     * blood-door-first order is 148-sim-autosecret-bloodfirst's.)
     */
    private static void caseAutoSecretBloodFirst(Session c) {
        ClientGameTestContext ctx = c.ctx();
        String cfgClass = "com.killer560.hub.autosecret.AutoSecretConfig";
        Object[] before = new Object[1];
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(cfgClass);
            before[0] = ModUnderTest.call(cfg, "getSecretBloodFirst", new Class<?>[]{}, new Object[]{});
            Object camp = ModUnderTest.enumValue(cfgClass + "$SecretBloodFirst", "DOOR_CAMP");
            ModUnderTest.call(cfg, "setSecretBloodFirst", new Class<?>[]{camp.getClass()}, new Object[]{camp});
        });
        long m = LogTap.mark();
        try {
            caseAutoSecret(c, false, false);
            check(logHas(m, "Blood First (Door Camp): the blood door is not on the map yet - secrets meanwhile"),
                    "Blood First never said it had no step on the arena - was it on at all?");
            check(!logHas(m, "[AutoSecret] going to the blood door"), "it went for a blood door the arena does not have");
            c.note("Door Camp with no blood door on the map: secrets meanwhile, logged");
        } finally {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config(cfgClass);
                ModUnderTest.call(cfg, "setSecretBloodFirst", new Class<?>[]{before[0].getClass()}, new Object[]{before[0]});
            });
        }
    }

    /**
     * grim-autopilot-flag-1016: the map trip's first hop held behind an unpaired block click (a Dungeon Breaker click on the
     * floor, as an earlier case's aura click left one in 62-argrim-autopilot), standing a block and a half from the middle
     * wall so that hop goes onto the wall's top within reach - its ray meets the wall, so it goes with its block half and
     * the click is paired. On mod main 098b6c92 the NEXT hop was then queued and sent from the same tick's second slot
     * (ClearExecutor.onTickStart's sendNow doInteract): two uses with two looks before one movement packet, GrimAC
     * BadPacketsJ, two teleports answered together. Requires the hop to have gone with its block half (else nothing was
     * tested), the trip and the route to land, and the use-look replay and GrimAC to be silent.
     */
    private static void caseOneUsePerTick(Session c) {
        ClientGameTestContext ctx = c.ctx();
        String as = "com.killer560.hub.autosecret.AutoSecretFeature";
        resetRoutes(ctx);
        arena(ctx, true);
        writeRoute(ctx, List.of(ew(24, 10, 24, 22, true)));
        List<Sample> s = List.of();
        try {
            tpRel(ctx, 13.5, 11.5, 0f, 0f);
            ctx.waitTicks(5);
            // The unpaired click: the Dungeon Breaker (slot 2) on the floor in front of him - nothing answers it.
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(2));
            ctx.waitTicks(3);
            rightClick(ctx, 12, F - 1, 11, Direction.UP);
            ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
            ctx.waitTicks(3);
            boolean unpaired = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                    "com.killer560.hub.util.RightClick", "unpaired"));
            check(unpaired, "the Dungeon Breaker click on the floor left no unpaired click - nothing to test");
            // A precomputed trip (TripCache) is the one that sends each hop on the tick it is queued (sendNow), the slot
            // the second use went from; a searched trip never uses it.
            BlockPos goal = ctx.computeOnClient(mc -> {
                Object frame = ModUnderTest.staticCall(FRAME, "current");
                Vec3 v = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(),
                        double.class, double.class, double.class}, new Object[]{frame, 24.5, (double) F, 10.5});
                return BlockPos.containing(v.x, v.y - 0.5, v.z);
            });
            boolean cached = waitFor(ctx, 600, () -> ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                    "com.killer560.hub.livemap.autoclear.TripCache", "find", new Class<?>[]{Vec3.class, BlockPos.class,
                            int.class, boolean.class}, new Object[]{mc.player.position(), goal, -1, true}) != null));
            check(cached, "TripCache never precomputed the trip from his spot to the start node " + goal);
            ctx.waitTicks(2);
            long m = LogTap.mark();
            startSampling(ctx);
            Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as, "start"));
            check(Boolean.TRUE.equals(started), "Auto Secret refused to start");
            Vec3 atStart = waitLanded(ctx, 24, 10, 300);
            Vec3 atEnd = waitLanded(ctx, 24, 22, 200);
            waitFor(ctx, 200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as, "isRunning")));
            ctx.waitTicks(10);
            s = stopSampling(ctx);
            printTrace("oneuse", s);
            for (String l : LogTap.since(m)) {
                if (l.contains("[RightClick]") || l.contains("[Path] Warp") || l.contains("[Path] running")) {
                    println("  " + l);
                }
            }
            List<String> twoLooks = dev.testkit.harness.UseRotationWatch.violations();
            int uses = dev.testkit.harness.UseRotationWatch.uses();
            check(logHas(m, "- no search, precomputed"), "the trip was not run precomputed - nothing tested");
            check(logHas(m, "so the use pairs with it"), "no hop went with its block half while the click was unpaired - "
                    + "the path under test never ran (" + uses + " use(s))");
            check(atStart != null, "the trip did not bring him to the start node (24,10) - at " + relPos(ctx));
            check(atEnd != null, "the route's etherwarp did not land on (24,22) - at " + relPos(ctx));
            check(uses >= 3, "only " + uses + " use(s) went out - the trip needs 2 hops and the route 1");
            check(twoLooks.isEmpty(), "two uses with different looks before one movement packet (GrimAC BadPacketsJ): "
                    + twoLooks);
            c.note("a hop went with its block half after an unpaired click; " + uses + " use(s), one look per tick");
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(as, "isRunning")) {
                    ModUnderTest.staticCall(as, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            writeRoute(ctx, null);
        }
    }

    /**
     * acplan-1020 (mod: Auto Secret's Room Plan, Fastest Total): Auto Secret with the arena room's route and two starred mobs
     * in the room out of the weapon's reach from where the route ends, Auto Clear on (Hyperion, hops). The room's clear must
     * go in the same visit as its route - "clear here" after the route, or "clear first" before it when that prices
     * cheaper - so the hand-over between the route's packets and Auto Clear's runs under GrimAC. Requires the route to
     * finish, one of those two lines, both mobs dead, no entity packet; GrimAC silent.
     */
    private static void caseClearHere(Session c) {
        ClientGameTestContext ctx = c.ctx();
        String as = "com.killer560.hub.autosecret.AutoSecretFeature";
        String asCfg = "com.killer560.hub.autosecret.AutoSecretConfig";
        String autoCfg = "com.killer560.hub.autoclear.AutoClearConfig";
        boolean hasPlan = ctx.computeOnClient(mc -> {
            try {
                Class.forName(asCfg + "$RoomPlan");
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        });
        check(hasPlan, "this jar has no Auto Secret Room Plan - nothing to test");
        Object[] planBefore = new Object[1];
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(asCfg);
            planBefore[0] = ModUnderTest.call(cfg, "getRoomPlan", new Class<?>[]{}, new Object[]{});
            Object ft = ModUnderTest.enumValue(asCfg + "$RoomPlan", "FASTEST_TOTAL");
            ModUnderTest.call(cfg, "setRoomPlan", new Class<?>[]{ft.getClass()}, new Object[]{ft});
            Object ac = ModUnderTest.config(autoCfg);
            ModUnderTest.set(ac, "setEnabled", true);
            ModUnderTest.set(ac, "setHyperionHops", true);
            ModUnderTest.call(ac, "setWeapon", new Class<?>[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")
                    .getClass()}, new Object[]{ModUnderTest.enumValue(autoCfg + "$Weapon", "HYPERION")});
        });
        resetRoutes(ctx);
        arena(ctx, true);
        writeRoute(ctx, List.of(ew(24, 10, 24, 22, true)));
        List<Sample> s = List.of();
        try {
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(5);
            // Both well over the Hyperion's reach from the route's end (24, 22) and from each other.
            double[][] spots = {{11.5, 8.5}, {22.5, 9.5}};
            List<JsonObject> mobs = new ArrayList<>();
            for (double[] sp : spots) {
                Vec3 at = ctx.computeOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    return (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(), double.class,
                            double.class, double.class}, new Object[]{frame, sp[0], (double) F, sp[1]});
                });
                mobs.add(c.hx().call("dungeon.starred", "x", at.x, "y", at.y, "z", at.z).getAsJsonObject());
            }
            ctx.waitTicks(20);
            int seen = ctx.computeOnClient(mc -> ((List<?>) ModUnderTest.staticCall("com.killer560.hub.mobesp.MobEspFeature",
                    "starredMobs", new Class<?>[]{net.minecraft.client.Minecraft.class}, new Object[]{mc})).size());
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as, "start"));
            check(Boolean.TRUE.equals(started), "Auto Secret refused to start");
            boolean stopped = waitFor(ctx, 1200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as,
                    "isRunning")));
            ctx.waitTicks(10);
            s = stopSampling(ctx);
            printTrace("clearhere", s);
            for (String l : LogTap.since(m)) {
                if (l.contains("[AutoSecret]") || l.contains("[AutoClear]")) {
                    println("  " + l.replaceAll("^.*?\\[(AutoSecret|AutoClear)\\]", "[$1]"));
                }
            }
            List<String> states = new ArrayList<>();
            for (JsonObject mob : mobs) {
                states.add(c.hx().call("dungeon.alive", "id", mob.get("mob").getAsString(), "stand",
                        mob.get("stand").getAsString()).getAsString());
            }
            int interacts = 0;
            for (Sample x : s) {
                interacts += (int) x.out().stream().filter(p -> p.contains("interact")).count();
            }
            boolean here = logHas(m, "[AutoSecret] clear here:");
            boolean first = logHas(m, "[AutoSecret] clear first:");
            println("clearhere: " + seen + " starred mob(s) resolved, mobs " + states + ", clear here " + here + ", clear first "
                    + first + ", Wither Impacts " + delta(before, "witherImpacts") + ", etherwarps " + delta(before, "etherwarps")
                    + ", entity packets " + interacts + ", stopped " + stopped);
            check(seen >= 2, "the client resolved only " + seen + " of the 2 starred mobs - nothing to clear");
            check(logHas(m, "[AutoSecret] route " + room + " finished"), "Auto Secret never saw the route finish");
            check(here || first, "the room's clear did not go in the same visit as its route (no clear here / clear first)");
            for (int i = 0; i < states.size(); i++) {
                check(!"alive".equals(states.get(i)), "starred mob " + i + " is still alive");
            }
            check(interacts == 0, interacts + " entity packet(s): a melee hit or an entity click");
            check(stopped, "Auto Secret was still running after the clear");
            List<String> twoLooks = dev.testkit.harness.UseRotationWatch.violations();
            check(twoLooks.isEmpty(), "a use's look was not the one its tick reported (GrimAC BadPacketsJ): " + twoLooks);
            List<String> outside = PacketTrace.sentOutsideTick();
            check(outside.isEmpty(), "sent outside a tick (after its client_tick_end, before the next tick): " + outside);
            c.note("route then " + (here ? "clear here" : "clear first") + ": " + delta(before, "witherImpacts")
                    + " Wither Impact(s), " + delta(before, "etherwarps") + " etherwarp(s), mobs " + states);
            noteFlags(c, s);
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(as, "isRunning")) {
                    ModUnderTest.staticCall(as, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
                Object cfg = ModUnderTest.config(asCfg);
                ModUnderTest.call(cfg, "setRoomPlan", new Class<?>[]{planBefore[0].getClass()}, new Object[]{planBefore[0]});
                ModUnderTest.set(ModUnderTest.config(autoCfg), "setEnabled", false);
            });
            writeRoute(ctx, null);
        }
    }

    /** GLFW_KEY_KP_3 / GLFW_KEY_KP_6: bound to nothing in vanilla or the mod's defaults. */
    private static final int AS_KEY = 323;
    private static final int PILOT_KEY = 326;

    /**
     * {@code byKey} (grim-post-autopilot-1016): started the way he starts it - his toggle key, read by Auto Secret's
     * END_CLIENT_TICK poll - holding the empty slot 3, once TripCache has the trip to the start node from his spot. The
     * first trip then runs precomputed (no search), and on main its AOTV swap went out from that END tick, after the
     * tick's client_tick_end: GrimAC "Post held item change" on the next transaction. Requires the precomputed run, one
     * set_carried_item, and the usual warps; GrimAC must stay silent.
     */
    private static void caseAutoSecret(Session c, boolean pilot, boolean byKey) {
        ClientGameTestContext ctx = c.ctx();
        String as = "com.killer560.hub.autosecret.AutoSecretFeature";
        if (byKey) {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig");
                ModUnderTest.call(cfg, "setToggleKey", new Class<?>[]{int.class}, new Object[]{AS_KEY});
                ModUnderTest.call(cfg, "setAutopilotKey", new Class<?>[]{int.class}, new Object[]{PILOT_KEY});
            });
        }
        if (pilot) {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig");
                Object solo = ModUnderTest.enumValue("com.killer560.hub.autosecret.AutoSecretConfig$RunMode", "SOLO");
                ModUnderTest.call(cfg, "setRunMode", new Class<?>[]{solo.getClass()}, new Object[]{solo});
                ModUnderTest.set(cfg, "setBloodFirst", false);
            });
        }
        resetRoutes(ctx);
        arena(ctx, true);
        writeRoute(ctx, List.of(ew(24, 10, 24, 22, true)));
        List<Sample> s = List.of();
        try {
            tpRel(ctx, 6.5, 20.5, 0f, 0f);
            ctx.waitTicks(5);
            if (byKey) {
                // Slot 3 is empty: the trip's first warp must swap to the AOTV (slot 0).
                ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(3));
                BlockPos goal = ctx.computeOnClient(mc -> {
                    Object frame = ModUnderTest.staticCall(FRAME, "current");
                    Vec3 v = (Vec3) ModUnderTest.staticCall(COORDS, "toReal", new Class<?>[]{frame.getClass(),
                            double.class, double.class, double.class}, new Object[]{frame, 24.5, (double) F, 10.5});
                    return BlockPos.containing(v.x, v.y - 0.5, v.z);
                });
                boolean cached = waitFor(ctx, 600, () -> ctx.computeOnClient(mc -> ModUnderTest.staticCall(
                        "com.killer560.hub.livemap.autoclear.TripCache", "find", new Class<?>[]{Vec3.class, BlockPos.class,
                                int.class, boolean.class}, new Object[]{mc.player.position(), goal, -1, true}) != null));
                check(cached, "TripCache never precomputed the trip from his spot to the start node " + goal
                        + " - the case cannot test a precomputed first trip");
                ctx.waitTicks(2);
            }
            JsonObject before = stats();
            long m = LogTap.mark();
            startSampling(ctx);
            Boolean started;
            if (byKey) {
                ctx.getInput().holdKeyFor(pilot ? PILOT_KEY : AS_KEY, 3);
                started = waitFor(ctx, 20, () -> ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as,
                        "isRunning")));
            } else {
                started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as,
                        pilot ? "startAutopilot" : "start"));
            }
            check(Boolean.TRUE.equals(started), (pilot ? "Dungeon Autopilot" : "Auto Secret") + " refused to start");
            Vec3 atStart = waitLanded(ctx, 24, 10, 300);
            Vec3 atEnd = waitLanded(ctx, 24, 22, 200);
            boolean stopped = waitFor(ctx, 200, () -> !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(as,
                    "isRunning")));
            ctx.waitTicks(10);
            s = stopSampling(ctx);
            printTrace("autosecret", s);
            for (String l : LogTap.since(m)) {
                if (l.contains("[AutoSecret]") || l.contains("[Autopilot]")) {
                    println("  " + l.replaceAll("^.*?\\[AutoSecret\\]", "[AutoSecret]"));
                }
            }
            // The autopilot plans more trips after the route (it explores the arena's empty cells), so its START-node trip
            // is the FIRST plan, not the last.
            int planned = pilot ? firstPlannedWarps(m) : plannedWarps(m);
            List<Sample> uses = useTicks(s);
            check(logHas(m, "[AutoSecret] target " + room), "Auto Secret did not target " + room);
            if (pilot) {
                check(logHas(m, "| pick SECRET " + room), "the autopilot never picked the route in " + room);
            }
            check(atStart != null, "Auto Secret's map warp did not bring him to the start node (24,10) - at " + relPos(ctx));
            check(atEnd != null, "the route's etherwarp did not land on (24,22) - at " + relPos(ctx));
            check(logHas(m, "[AutoSecret] route " + room + " finished"), "Auto Secret never saw the route finish");
            check(stopped, "Auto Secret was still running with nothing left to do");
            check(planned >= 2, "the map planned " + planned + " warp(s) across the wall, expected 2 or more");
            if (pilot) {
                // The arena's map shows its other cells as unidentified rooms, and the autopilot goes to look at them
                // after the route (EXPLORE), so it warps more than Auto Secret; every use must still be a server warp.
                check(uses.size() >= planned + 1, "sent " + uses.size() + " use(s) for " + planned
                        + " map warp(s) + 1 route warp - fewer than the route needed");
                c.note("autopilot explored " + LogTap.since(m).stream().filter(l -> l.contains("| pick EXPLORE")).count()
                        + " unidentified cell(s) after the route");
                // Those cells are empty arena: the harness's ability server leaves some warps toward them unanswered
                // (2026-10-06, "warp 1 never landed"), so here only the route's own warps must all have been server warps.
                check(delta(before, "etherwarps") >= planned + 1, "the server made " + delta(before, "etherwarps")
                        + " etherwarp(s), fewer than the " + (planned + 1) + " the trip and the route needed");
            } else {
                check(uses.size() == planned + 1, "sent " + uses.size() + " use(s) for " + planned + " map warp(s) + 1 route warp");
                check(delta(before, "etherwarps") == uses.size(), "client sent " + uses.size() + " use(s), the server made "
                        + delta(before, "etherwarps") + " etherwarp(s)");
            }
            for (Sample x : uses) {
                check(x.shift(), "a warp's use went out on tick " + x.tick() + " without the server having the sneak");
            }
            // GrimAC Post flags these only when a transaction answer happens to follow them before the next tick packet;
            // the trace's record of what went out between two ticks is the same fault without the luck.
            List<String> outside = PacketTrace.sentOutsideTick();
            check(outside.isEmpty(), "sent outside a tick (after its client_tick_end, before the next tick): " + outside);
            // GrimAC BadPacketsJ flags two uses with different looks before one movement packet, which only some explore
            // trips here happen to send; the replay names the tick whether or not Grim's log is read (grim-autopilot-flag-1016).
            List<String> twoLooks = dev.testkit.harness.UseRotationWatch.violations();
            check(twoLooks.isEmpty(), "a use's look was not the one its tick reported (GrimAC BadPacketsJ): " + twoLooks);
            if (byKey) {
                // The code under test must have run: the first trip precomputed, and a swap to the AOTV sent for it.
                check(logHas(m, "- no search, precomputed"), "the first trip was not run precomputed - nothing tested");
                long swaps = s.stream().flatMap(x -> x.out().stream()).filter(p -> p.equals("set_carried_item")).count();
                check(swaps >= 1, "no set_carried_item went out - he already held the AOTV, nothing tested");
                c.note("first trip precomputed, " + swaps + " set_carried_item(s)");
            }
            c.note((pilot ? "autopilot: " : "auto secret: ") + planned + " map warp(s) + route warp, " + uses.size() + " use(s), server etherwarps +"
                    + delta(before, "etherwarps") + ", landed " + atStart + " then " + atEnd);
        } finally {
            ctx.runOnClient(mc -> {
                if ((Boolean) ModUnderTest.staticCall(as, "isRunning")) {
                    ModUnderTest.staticCall(as, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
                if (byKey) {
                    Object cfg = ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig");
                    ModUnderTest.call(cfg, "setToggleKey", new Class<?>[]{int.class}, new Object[]{-1});
                    ModUnderTest.call(cfg, "setAutopilotKey", new Class<?>[]{int.class}, new Object[]{-1});
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

    private static int firstPlannedWarps(long mark) {
        for (String l : LogTap.since(mark)) {
            java.util.regex.Matcher mm = RUNNING.matcher(l);
            if (mm.find()) {
                return Integer.parseInt(mm.group(1));
            }
        }
        return -1;
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

    /** A click at the widget's DRAWN center, through the screen's own mouseClicked (96-ar's helper). */
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
        // Loose items are picked up as secrets. The world persists between runs, and the rebuild above knocks the last
        // case's chests and levers (1020-autoclearaura's, an earlier run's) out as drops: 1016-auraawait's await was
        // met by three of them with no click at all (2026-10-20). Every arena starts with none.
        session.server().command("kill @e[type=minecraft:item]");
        ctx.waitTicks(2);
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
        // Facing the node's center exactly from (fx, fz), both relative.
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
     * he has landed. The server's etherwarp puts him on the block center, so an etherwarp start node fires from
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

    /**
     * Waits until he stands in relative block (x, z) at the arena floor; his position then, or null. Checks now, then after
     * each of {@code ticks} ticks (so {@code ticks == 0} is a single look at where he is now - it used to check nothing and
     * always answered null, which made every "has not landed yet" assertion through it vacuous, 2026-10-15).
     */
    private static Vec3 waitLanded(ClientGameTestContext ctx, int x, int z, int ticks) {
        for (int i = 0; i <= ticks; i++) {
            Vec3 p = relPos(ctx);
            if (Math.floor(p.x) == x && Math.floor(p.z) == z && Math.abs(p.y - F) < 0.2) {
                return p;
            }
            if (i < ticks) {
                ctx.waitTicks(1);
            }
        }
        return null;
    }

    /**
     * Puts him back in the pasted room if an earlier case left him elsewhere. The Dungeon Autopilot case walks him on
     * to explore the arena's other (unidentified) map cells and stops there, where {@code RouteCoords.Frame.current()}
     * is null (no room under his feet), so every later case that needs the frame threw "frame is null". A real player
     * standing in an unidentified room has no frame either, so this is the harness's state, not the mod's.
     */
    private static void ensureInRoom(ClientGameTestContext ctx) {
        if (frameIsRoom(ctx)) {
            return;
        }
        println("a case before this one left him outside " + room + " (frame " + frameString(ctx) + "): placing him back");
        session.hx().call("dungeon.tp", "x", CENTRE + 0.5, "y", (double) F, "z", CENTRE + 3.5, "yaw", 0f, "pitch", 0f);
        if (!waitFor(ctx, 300, () -> frameIsRoom(ctx))) {
            throw new AssertionError("could not put him back in " + room + ": frame " + frameString(ctx));
        }
        ctx.waitTicks(5);
    }

    private static boolean frameIsRoom(ClientGameTestContext ctx) {
        return room.equals(ctx.computeOnClient(mc -> {
            Object f = ModUnderTest.staticCall(FRAME, "current");
            return f == null ? null : (String) ModUnderTest.call(f, "roomName", new Class<?>[]{}, new Object[]{});
        }));
    }

    private static String frameString(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(FRAME, "current")));
    }

    // ============================================================================================ routes

    private static float relYawTo(int fx, int fz, int tx, int tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
    }

    /** Pitch from a sneaking eye over block (fx,fz) down onto the top center of the floor block at (tx,tz). */
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
        ensureInRoom(ctx);
        ctx.runOnClient(mc -> {
            McCompat.setScreen(mc, null);
            ModUnderTest.staticCall(EXECUTOR, "stop", new Class<?>[]{String.class}, new Object[]{"test reset"});
            mc.player.getInventory().setSelectedSlot(3);
        });
        forgetRunMemory(ctx);
        writeRoute(ctx, null);
    }

    /**
     * Every case starts a fresh run, as the sim cases do: Auto Routes remembers for the run (RouteRunMemory) the secrets,
     * bats and routes already done, and with the session's earlier cases all in the same room, 62-argrim-autosecret and
     * -autopilot found "Andesite: its route already ran to the end this run" and stopped at once (2026-10-15) - so they
     * tested nothing. A jar from before the class existed has nothing to forget.
     */
    private static void forgetRunMemory(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                Class.forName("com.killer560.hub.autoroutes.RouteRunMemory");
            } catch (ClassNotFoundException e) {
                return;
            }
            ModUnderTest.staticCall("com.killer560.hub.autoroutes.RouteRunMemory", "reset", new Class<?>[]{},
                    new Object[]{});
        });
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
        // Clicking a block's center while facing elsewhere is not a click a hand makes: GrimAC RotationPlace flagged
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
