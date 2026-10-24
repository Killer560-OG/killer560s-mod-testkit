package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * 1790-sim-map-leave-secrets: a secret registered on the tick he leaves its room still counts on the map (mod
 * sim-map-secrets-1016).
 *
 * <p>killer560, 2026-10-16: "It looks like the map does not register secrets I get if I leave the room too fast or
 * something. This is a sim issue not a hypixel issue." His run (Auto Secret, Secret Aura, M7 sim floor) left ten rooms
 * whose every secret the sim counted short on the map: Dip 0/2, Mushroom 0/1, Doors 1/5... The sim sent the room's
 * "x/y Secrets" action bar line only for the room he stood in, every ten server ticks, and painted no green checkmark
 * for a room with every secret found, so a secret registered as he warped or walked out never reached the map.
 *
 * <p>Per secret type (chest, wither essence, item, bat, Redstone Key), per way out (DOOR: moved one step through the
 * room's door into the next room, on the server; ETHERWARP: the sim's own server etherwarp from inside the room, along
 * the doorway into the next room) and per timing (the SAME server tick the secret registered, before any action bar of
 * that tick could see him in the room, or ONE tick later), on a two-room floor built from a map code (room R with the
 * secret, a NORMAL door, a 1x1 neighbor B, both unrotated):
 * <ol>
 *   <li>he stands in R (so it is entered and cleared) and the server is told every secret of R but one is found - the
 *       map must then read (N-1)/N, the premise that the map reads the sim at all;</li>
 *   <li>the last one is collected FOR REAL (a chest, essence or key node: the client's own use_item_on packet, which the
 *       server must be seen to handle; an item: walked onto and picked up; a bat: spawned by standing near it, then
 *       killed), and a server tick hook moves him out the moment the server's own per-room count
 *       ({@code SimScore.foundInRoom}) reaches N;</li>
 *   <li>after 40 ticks in B the verdict needs: the count reached N (the collection happened), he left on exactly the
 *       tick asked for and the server has him in B, and the map (the mod's {@code RoomStatus}, which is the map label's
 *       own count) reads N/N.</li>
 * </ol>
 * On mod main fcba4045 every SAME-tick case reads (N-1)/N; a one-tick case can pass there by luck when the ten-tick
 * action bar falls on that one tick. Sim only, so no GrimAC.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMapLeaveSecretsTests implements FabricClientGameTest {

    static final String NAME = "1790-sim-map-leave-secrets";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String ROOM_DOORS = "com.killer560.hub.roomsim.RoomDoors";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimSecretItems";
    private static final String SIM_KEY = "com.killer560.hub.roomsim.SimRedstoneKey";
    private static final String SIM_ABILITIES = "com.killer560.hub.roomsim.SimAbilities";
    private static final String SIM_ALTITUDE = "com.killer560.hub.roomsim.SimAltitude";
    private static final String MAP_CODE = "com.killer560.hub.roomsim.MapCode";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String ROOM_STATUS = "com.killer560.hub.livemap.RoomStatus";
    private static final String DATABASE = "com.killer560.hub.roomdatabase.RoomDatabase";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final int GRID = 11;
    private static final int EAST = 1;
    private static final int SOUTH = 2;
    private static final int WEST = 3;
    private static final int NORTH = 0;

    private enum Kind {
        CHEST("chest"), ESSENCE("wither"), ITEM("item"), BAT("bat"), KEY("redstoneKey");
        final String field;

        Kind(String field) {
            this.field = field;
        }

        boolean clicked() {
            return this == CHEST || this == ESSENCE || this == KEY;
        }
    }

    private enum Mode { DOOR, ETHERWARP }

    /** Block clicks the integrated server handled, "x,y,z" - fed by a server-side UseBlockCallback. */
    private static final Set<String> SERVER_USES = ConcurrentHashMap.newKeySet();
    private static volatile Leaver leaver;
    private static boolean hooked;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        boolean mapWasOn = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(ModUnderTest.config(LIVE_MAP_CONFIG),
                "isEnabledRaw"));
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setEnabled", true));
        hook();
        String only = System.getProperty("testkit.leaveSecrets.kinds", "");
        List<String> verdicts = new ArrayList<>();
        // Finds a room's chest secrets as blocks from anywhere: they go in at the build here (mod sim-secrets-1018).
        SimChestHook.placeAtBuild(ctx);
        try {
            for (Kind kind : Kind.values()) {
                if (!only.isBlank() && !only.toLowerCase(Locale.ROOT).contains(kind.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String[] pick = ctx.computeOnClient(mc -> pickRooms(kind));
                if (pick == null) {
                    verdicts.add(kind + ": SKIP - no usable room with a " + kind.field + " secret and a door to pair");
                    continue;
                }
                for (Mode mode : Mode.values()) {
                    for (int delay = 0; delay <= 1; delay++) {
                        String v;
                        try {
                            v = runCase(ctx, kind, pick, mode, delay);
                        } catch (Throwable t) {
                            t.printStackTrace();
                            v = kind + "/" + mode + "/+" + delay + " in " + pick[0] + ": FAIL - threw " + t;
                        }
                        println(v);
                        verdicts.add(v);
                    }
                }
            }
        } finally {
            SimChestHook.restore(ctx);
            leaver = null;
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(LIVE_MAP_CONFIG), "setEnabled", mapWasOn));
            leave(ctx);
        }
        long pass = verdicts.stream().filter(v -> v.contains(": PASS")).count();
        long fail = verdicts.stream().filter(v -> v.contains(": FAIL")).count();
        println("summary: " + pass + " pass, " + fail + " fail, " + (verdicts.size() - pass - fail) + " skip");
        verdicts.forEach(SimMapLeaveSecretsTests::println);
        if (pass == 0) {
            throw new AssertionError("no case ran to a verdict - nothing was measured: " + String.join(" | ", verdicts));
        }
        if (fail > 0) {
            throw new AssertionError(fail + " case(s) failed: " + String.join(" | ",
                    verdicts.stream().filter(v -> v.contains(": FAIL")).toList()));
        }
        println("PASS - every secret registered on the tick he left, or one tick before, counts on the map");
    }

    // ------------------------------------------------------------------------------------------------ rooms

    /**
     * {R, side (EAST or SOUTH), B, tilesX, tilesZ}: the first usable room (sorted by name) with a secret of this kind and
     * a doorway on its east (or south) side at tile 0, and a 1x1 neighbor with the matching doorway. Client thread.
     */
    @SuppressWarnings("unchecked")
    private static String[] pickRooms(Kind kind) {
        List<String> names = new ArrayList<>((List<String>) ModUnderTest.staticCall(ROOM_LIBRARY, "names"));
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            Object entry = entry(name);
            if (entry == null || !usable(name) || badType(entry) || secretsOf(entry, kind) < 1
                    || intField(entry, "secrets") < 1) {
                continue;
            }
            int[] tiles = tiles(name);
            if (tiles == null || (kind != Kind.KEY && (tiles[0] != 1 || tiles[1] != 1))
                    || !rectangular(entry, tiles)) {
                continue;
            }
            int side = hasDoorway(name, EAST, 0) ? EAST : hasDoorway(name, SOUTH, 0) ? SOUTH : -1;
            if (side < 0) {
                continue;
            }
            String b = pickNeighbour(names, name, side == EAST ? WEST : NORTH);
            if (b != null) {
                return new String[]{name, String.valueOf(side), b, String.valueOf(tiles[0]), String.valueOf(tiles[1])};
            }
        }
        return null;
    }

    private static String pickNeighbour(List<String> names, String not, int side) {
        for (String name : names) {
            Object entry = entry(name);
            if (name.equals(not) || entry == null || !usable(name) || badType(entry)) {
                continue;
            }
            int[] tiles = tiles(name);
            if (tiles != null && tiles[0] == 1 && tiles[1] == 1 && hasDoorway(name, side, 0)) {
                return name;
            }
        }
        return null;
    }

    private static Object entry(String name) {
        return ModUnderTest.staticCall(DATABASE, "lookupByName", new Class<?>[]{String.class}, new Object[]{name});
    }

    private static boolean usable(String name) {
        return (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isUsable", new Class<?>[]{String.class},
                new Object[]{name});
    }

    /** Puzzle, trap (abilities locked), blood, entrance and fairy rooms are not where this is about. */
    private static boolean badType(Object entry) {
        String type = String.valueOf(field(entry, "type")).toLowerCase(Locale.ROOT);
        return type.contains("puzzle") || type.contains("trap") || type.contains("blood") || type.contains("spawn")
                || type.contains("entrance") || type.contains("fairy");
    }

    private static boolean rectangular(Object entry, int[] tiles) {
        String shape = String.valueOf(field(entry, "shape")).toLowerCase(Locale.ROOT);
        return !shape.contains("l") || tiles[0] * tiles[1] == 1;
    }

    private static int[] tiles(String name) {
        Object room = ModUnderTest.staticCall(ROOM_LIBRARY, "get", new Class<?>[]{String.class}, new Object[]{name});
        if (room == null) {
            return null;
        }
        int sx = (Integer) field(room, "sizeX");
        int sz = (Integer) field(room, "sizeZ");
        return new int[]{Math.max(1, (sx - 1) / 32), Math.max(1, (sz - 1) / 32)};
    }

    private static int secretsOf(Object entry, Kind kind) {
        Object coords = field(entry, "secretCoords");
        Object list = coords == null ? null : field(coords, kind.field);
        return list instanceof Collection<?> c ? c.size() : 0;
    }

    private static boolean hasDoorway(String room, int side, int index) {
        Object mask = ModUnderTest.staticCall(ROOM_DOORS, "of", new Class<?>[]{String.class}, new Object[]{room});
        return mask != null && (Boolean) ModUnderTest.call(mask, "has", new Class<?>[]{int.class, int.class},
                new Object[]{side, index});
    }

    private static Object field(Object o, String name) {
        try {
            var f = o.getClass().getField(name);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static int intField(Object o, String name) {
        Object v = field(o, name);
        return v instanceof Integer i ? i : 0;
    }

    // ------------------------------------------------------------------------------------------------ one case

    private static String runCase(ClientGameTestContext ctx, Kind kind, String[] pick, Mode mode, int delay) {
        String r = pick[0];
        int side = Integer.parseInt(pick[1]);
        String b = pick[2];
        int w = Integer.parseInt(pick[3]);
        int h = Integer.parseInt(pick[4]);
        String tag = kind + "/" + mode + "/+" + delay + " in " + r + " (" + w + "x" + h + ", door " + (side == EAST
                ? "east" : "south") + " to " + b + ")";
        int doorIdx = build(ctx, r, side, b, w, h);
        int[] dir = side == EAST ? new int[]{1, 0} : new int[]{0, 1};
        Vec3 door = ctx.computeOnClient(mc -> (Vec3) ModUnderTest.staticCall(LAYOUT, "doorCentre",
                new Class<?>[]{int.class}, new Object[]{doorIdx}));
        BlockPos rMin = cellCenter(ctx, 0, 0).offset(-15, 0, -15);
        BlockPos rMax = cellCenter(ctx, 2 * w - 2, 2 * h - 2).offset(15, 0, 15);
        Plan plan = onServer(ctx, (level, sp) -> plan(level, kind, r, b, door, dir, rMin, rMax));
        if (plan.why != null) {
            return tag + ": FAIL premise - " + plan.why;
        }
        String where = "secret at " + plan.secret.toShortString() + (plan.key != null ? " (key at "
                + plan.key.toShortString() + ")" : "") + ", launch " + fmt(plan.launch) + ", door step "
                + fmt(plan.doorStep) + ", etherwarp aim " + String.format(Locale.ROOT, "%.2f/%.2f", plan.yaw, plan.pitch)
                + " onto " + plan.etherTarget.toShortString();
        println(tag + ": " + where);

        // ---- 1. in R, and every secret of R but one found on the server
        teleport(ctx, plan.launch);
        waitInRoom(ctx, r, 200);
        ctx.waitTicks(25);
        int[] pre = onServer(ctx, (level, sp) -> {
            int n = intField(entry(r), "secrets");
            int before = foundIn(r);
            for (int i = before; i < n - 1; i++) {
                ModUnderTest.staticCall(SIM_SCORE, "secretFound", new Class<?>[]{BlockPos.class},
                        new Object[]{plan.secret});
            }
            return new int[]{n, before, foundIn(r)};
        });
        int n = pre[0];
        ctx.waitTicks(25);
        int[] mapBefore = ctx.computeOnClient(mc -> mapRoom(r));
        String before = mapBefore == null ? "not on the map" : mapBefore[0] + "/" + mapBefore[1] + (mapBefore[2] == 1
                ? " cleared" : " not cleared");
        println(tag + ": " + n + " secret(s), server count " + pre[1] + " -> " + pre[2] + " before the last; map reads "
                + before);
        if (pre[2] != n - 1) {
            return tag + ": FAIL premise - the server count is " + pre[2] + ", not " + (n - 1);
        }
        if (mapBefore == null || mapBefore[0] != n - 1 || mapBefore[1] != n || mapBefore[2] != 1) {
            return tag + ": FAIL premise - the map does not read the sim's " + (n - 1) + "/" + n + " for the cleared room "
                    + "he stands in (" + before + ")";
        }

        // ---- 2. the last secret, for real, with the leave armed on the server's own count
        if (kind == Kind.KEY) {
            teleport(ctx, plan.keyStand);
            waitClientHas(ctx, plan.key);
            use(ctx, plan.key);
            ctx.waitTicks(10);
            boolean taken = onServer(ctx, (level, sp) -> level.getBlockState(plan.key).isAir());
            if (!taken) {
                return tag + ": FAIL premise - the Redstone Key was not taken by the click";
            }
        }
        if (kind.clicked()) {
            teleport(ctx, plan.stand);
            emptyHand(ctx);
            waitClientHas(ctx, plan.secret);
        } else if (kind == Kind.BAT) {
            teleport(ctx, plan.stand);
            boolean spawned = false;
            for (int t = 0; t < 80 && !spawned; t++) {
                ctx.waitTicks(1);
                spawned = onServer(ctx, (level, sp) -> batNear(level, plan.secret) != null);
            }
            if (!spawned) {
                return tag + ": FAIL premise - the secret bat never appeared with him beside it";
            }
        }
        String inRoom = ctx.computeOnClient(mc -> clientRoom());
        if (!r.equals(inRoom)) {
            return tag + ": FAIL premise - he is not in " + r + " before the collection (client says " + inRoom + ")";
        }
        SERVER_USES.clear();
        UUID uuid = ctx.computeOnClient(mc -> mc.player.getUUID());
        Leaver l = new Leaver(r, n, delay, mode, uuid, plan);
        leaver = l;
        int itemsLiveBefore = onServer(ctx, (level, sp) -> (Integer) ModUnderTest.staticCall(SIM_ITEMS, "liveCount"));
        switch (kind) {
            case CHEST, ESSENCE, KEY -> use(ctx, plan.secret);
            case ITEM -> teleport(ctx, plan.stand);
            case BAT -> onServer(ctx, (level, sp) -> {
                var bat = batNear(level, plan.secret);
                if (bat != null) {
                    bat.hurtServer(level, level.damageSources().playerAttack(sp), 1000f);
                }
                return bat != null;
            });
        }
        for (int t = 0; t < 120 && !l.left; t++) {
            ctx.waitTicks(1);
        }
        leaver = null;
        ctx.runOnClient(mc -> {
            if (mc.player != null && McCompat.screen(mc) != null) {
                mc.player.closeContainer();
            }
        });
        ctx.waitTicks(40);

        // ---- 3. the verdict
        int found = onServer(ctx, (level, sp) -> foundIn(r));
        String serverRoom = onServer(ctx, (level, sp) -> roomAt(sp.blockPosition()));
        String clientNow = ctx.computeOnClient(mc -> clientRoom());
        int[] mapAfter = ctx.computeOnClient(mc -> mapRoom(r));
        String after = mapAfter == null ? "not on the map" : mapAfter[0] + "/" + mapAfter[1];
        String proof = switch (kind) {
            case CHEST, ESSENCE, KEY -> "server handled the click on it " + SERVER_USES.contains(key(plan.secret));
            case ITEM -> "items lying " + itemsLiveBefore + " -> " + onServer(ctx, (level, sp) ->
                    (Integer) ModUnderTest.staticCall(SIM_ITEMS, "liveCount")) + ", still pending "
                    + onServer(ctx, (level, sp) -> pendingItems().contains(plan.secret));
            case BAT -> "bat still alive " + onServer(ctx, (level, sp) -> batNear(level, plan.secret) != null);
        };
        String facts = "count " + (n - 1) + " -> " + found + " (registered server tick " + l.countTick + ", "
                + (l.countEarly ? "before" : "after") + " the tick's action bar), left tick " + l.leaveTick + " by "
                + mode + (l.etherFired != null ? " (etherwarp fired " + l.etherFired + ")" : "") + " into " + l.landed
                + "; now server " + serverRoom + ", client " + clientNow + "; map " + before + " -> " + after + "; "
                + proof;
        List<String> wrong = new ArrayList<>();
        if (l.countTick < 0) {
            wrong.add("the collection never registered on the server (count " + found + "/" + n + ") - nothing was "
                    + "measured");
        } else if (!l.left) {
            wrong.add("the hook never moved him out");
        } else {
            if (l.leaveTick - l.countTick != delay) {
                wrong.add("left " + (l.leaveTick - l.countTick) + " tick(s) after the secret, not " + delay);
            }
            if (!b.equals(l.landed) || !b.equals(serverRoom)) {
                wrong.add("he did not end up in " + b + " (landed " + l.landed + ", server " + serverRoom + ")");
            }
            if (kind.clicked() && !SERVER_USES.contains(key(plan.secret))) {
                wrong.add("the server never handled a click on the secret");
            }
            if (mapAfter == null || mapAfter[0] != n) {
                wrong.add("the map reads " + after + " for " + r + ", every one of its " + n + " secret(s) found");
            }
        }
        return tag + (wrong.isEmpty() ? ": PASS - " : ": FAIL - " + String.join("; ", wrong) + " - ") + facts;
    }

    /** Where everything happens, worked out on the server from the built floor. */
    private record Plan(String why, BlockPos secret, double[] stand, BlockPos key, double[] keyStand, double[] launch,
                        double[] doorStep, float yaw, float pitch, BlockPos etherTarget) {
        static Plan no(String why) {
            return new Plan(why, null, null, null, null, null, null, 0f, 0f, null);
        }
    }

    private static Plan plan(ServerLevel level, Kind kind, String r, String b, Vec3 door, int[] dir, BlockPos rMin,
                             BlockPos rMax) {
        int doorY = (int) Math.floor(door.y);
        double[] launch = standable(level, (int) Math.floor(door.x) - dir[0] * 4, doorY, (int) Math.floor(door.z)
                - dir[1] * 4);
        if (launch == null || !r.equals(roomAt(BlockPos.containing(launch[0], launch[1], launch[2])))) {
            return Plan.no("nowhere to stand 4 blocks inside " + r + "'s door (door " + door + ")");
        }
        double[] step = null;
        for (int k = 2; k <= 4 && step == null; k++) {
            double[] s = standable(level, (int) Math.floor(door.x) + dir[0] * k, doorY, (int) Math.floor(door.z)
                    + dir[1] * k);
            if (s != null && b.equals(roomAt(BlockPos.containing(s[0], s[1], s[2])))) {
                step = s;
            }
        }
        if (step == null) {
            return Plan.no("nowhere to stand just through the door in " + b);
        }
        // An etherwarp from the launch spot, along the doorway, onto a block of B the sim's own ray lands on.
        Object[] aim = etherAim(level, launch, door, dir, b);
        if (aim == null) {
            return Plan.no("no etherwarp from " + fmt(launch) + " through the door lands in " + b);
        }
        List<BlockPos> secrets = secretsIn(level, kind, r, rMin, rMax);
        if (secrets.isEmpty()) {
            return Plan.no("the sim placed no " + kind.field + " secret in " + r);
        }
        BlockPos keyPos = null;
        double[] keyStand = null;
        if (kind == Kind.KEY) {
            List<BlockPos> keys = keysIn(r);
            if (keys.isEmpty()) {
                return Plan.no("no Redstone Key head lying in " + r);
            }
            keyPos = keys.get(0);
            keyStand = reachSpot(level, keyPos, r);
            if (keyStand == null) {
                return Plan.no("nowhere in reach of the key at " + keyPos.toShortString());
            }
        }
        // An item: the one furthest from the launch spot, so it has not appeared (and been picked up) while he waited.
        if (kind == Kind.ITEM) {
            secrets.sort(Comparator.comparingDouble((BlockPos p) -> -p.distToCenterSqr(launch[0], launch[1], launch[2])));
        }
        for (BlockPos s : secrets) {
            double[] stand = switch (kind) {
                case CHEST, ESSENCE, KEY -> reachSpot(level, s, r);
                case ITEM -> nearSpot(level, s, r, 1.5);
                case BAT -> nearSpot(level, s, r, 5.0);
            };
            if (stand != null) {
                if (kind == Kind.ITEM && Math.sqrt(s.distToCenterSqr(launch[0], launch[1], launch[2])) < 6) {
                    continue;
                }
                return new Plan(null, s, stand, keyPos, keyStand, launch, step, (Float) aim[0], (Float) aim[1],
                        (BlockPos) aim[2]);
            }
        }
        return Plan.no(secrets.size() + " " + kind.field + " secret(s) in " + r + ", none with a place to stand by it");
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> secretsIn(ServerLevel level, Kind kind, String r, BlockPos min, BlockPos max) {
        List<BlockPos> out = new ArrayList<>();
        switch (kind) {
            case CHEST -> {
                int lo = (Integer) ModUnderTest.staticCall(SIM_ALTITUDE, "minWorldY");
                int hi = (Integer) ModUnderTest.staticCall(SIM_ALTITUDE, "maxWorldY");
                for (int x = min.getX(); x <= max.getX(); x++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        for (int y = lo; y <= hi; y++) {
                            BlockPos p = new BlockPos(x, y, z);
                            var st = level.getBlockState(p);
                            if (st.is(net.minecraft.world.level.block.Blocks.CHEST)
                                    || st.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
                                out.add(p);
                            }
                        }
                    }
                }
            }
            case ESSENCE -> {
                try {
                    out.addAll((Collection<BlockPos>) Class.forName(SIM_SECRETS).getField("PLACED_WITHER").get(null));
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            case ITEM -> out.addAll(pendingItems());
            case BAT -> out.addAll((List<BlockPos>) ModUnderTest.staticCall(SIM_ITEMS, "pendingBats"));
            case KEY -> {
                Map<BlockPos, String> nodes = (Map<BlockPos, String>) staticField(SIM_KEY, "NODE_ROOM");
                nodes.forEach((p, room) -> {
                    if (r.equalsIgnoreCase(room)) {
                        out.add(p);
                    }
                });
            }
        }
        out.removeIf(p -> !r.equals(roomAt(p)));
        out.sort(Comparator.comparingLong(BlockPos::asLong));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> keysIn(String r) {
        Map<BlockPos, String> keys = (Map<BlockPos, String>) staticField(SIM_KEY, "KEYS");
        List<BlockPos> out = new ArrayList<>();
        keys.forEach((p, room) -> {
            if (r.equalsIgnoreCase(room)) {
                out.add(p);
            }
        });
        out.sort(Comparator.comparingLong(BlockPos::asLong));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> pendingItems() {
        return (List<BlockPos>) ModUnderTest.staticCall(SIM_ITEMS, "pendingPositions");
    }

    private static Object staticField(String cls, String name) {
        try {
            var f = Class.forName(cls).getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("no field " + cls + "." + name, e);
        }
    }

    /** {yaw, pitch, target} of a sneaking etherwarp from {@code launch} that the sim's own ray lands in room b. */
    private static Object[] etherAim(ServerLevel level, double[] launch, Vec3 door, int[] dir, String b) {
        double eye = (Double) ModUnderTest.staticCall(TELEPORT_UTILS, "eyeHeight", new Class<?>[]{boolean.class},
                new Object[]{true});
        Vec3 from = new Vec3(launch[0], launch[1] + eye, launch[2]);
        int doorY = (int) Math.floor(door.y);
        for (int k = 5; k <= 20; k++) {
            for (int lateral : new int[]{0, 1, -1, 2, -2}) {
                int x = (int) Math.floor(door.x) + dir[0] * k + dir[1] * lateral;
                int z = (int) Math.floor(door.z) + dir[1] * k + dir[0] * lateral;
                for (int y = doorY + 1; y >= doorY - 6; y--) {
                    BlockPos t = new BlockPos(x, y, z);
                    if (level.getBlockState(t).getCollisionShape(level, t).isEmpty()
                            || !level.getBlockState(t.above()).getCollisionShape(level, t.above()).isEmpty()
                            || !level.getBlockState(t.above(2)).getCollisionShape(level, t.above(2)).isEmpty()) {
                        continue;
                    }
                    double dx = x + 0.5 - from.x;
                    double dy = y + 0.95 - from.y;
                    double dz = z + 0.5 - from.z;
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    Vec3 look = (Vec3) ModUnderTest.staticCall(TELEPORT_UTILS, "getLook",
                            new Class<?>[]{float.class, float.class}, new Object[]{yaw, pitch});
                    Vec3 to = from.add(look.scale(57.0));
                    Object hit = ModUnderTest.staticCall(TELEPORT_UTILS, "traverseVoxels", new Class<?>[]{
                            net.minecraft.world.level.Level.class, double.class, double.class, double.class,
                            double.class, double.class, double.class, boolean.class}, new Object[]{level, from.x,
                            from.y, from.z, to.x, to.y, to.z, true});
                    boolean ok = (Boolean) ModUnderTest.call(hit, "succeeded", new Class<?>[]{}, new Object[]{});
                    BlockPos at = (BlockPos) ModUnderTest.call(hit, "pos", new Class<?>[]{}, new Object[]{});
                    if (ok && at != null && b.equals(roomAt(at))) {
                        return new Object[]{yaw, pitch, at};
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** Feet on a standable block in the column, searching down from {@code fromY + 1} (12 blocks). */
    private static double[] standable(ServerLevel level, int x, int fromY, int z) {
        for (int y = fromY + 1; y >= fromY - 12; y--) {
            BlockPos floor = new BlockPos(x, y, z);
            if (stand(level, floor)) {
                return new double[]{x + 0.5, y + 1, z + 0.5};
            }
        }
        return null;
    }

    private static boolean stand(ServerLevel level, BlockPos floor) {
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
                && level.getBlockState(floor.above()).getCollisionShape(level, floor.above()).isEmpty()
                && level.getBlockState(floor.above(2)).getCollisionShape(level, floor.above(2)).isEmpty();
    }

    /** A standable spot in room r whose eye is 1.5..3.8 from the block's center - a click in reach. */
    private static double[] reachSpot(ServerLevel level, BlockPos s, String r) {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -4; dy <= 1; dy++) {
                    BlockPos floor = s.offset(dx, dy, dz);
                    BlockPos feet = floor.above();
                    if (feet.equals(s) || feet.above().equals(s) || !stand(level, floor) || !r.equals(roomAt(feet))) {
                        continue;
                    }
                    double d = Math.sqrt(Vec3.atCenterOf(s).distanceToSqr(feet.getX() + 0.5, feet.getY() + 1.62,
                            feet.getZ() + 0.5));
                    if (d >= 1.5 && d <= 3.8 && d < bestD) {
                        bestD = d;
                        best = new double[]{feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5};
                    }
                }
            }
        }
        return best;
    }

    /** The nearest standable spot in room r within {@code max} of the position (feet to its center). */
    private static double[] nearSpot(ServerLevel level, BlockPos s, String r, double max) {
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -7; dy <= 2; dy++) {
                    BlockPos floor = s.offset(dx, dy, dz);
                    BlockPos feet = floor.above();
                    if (!stand(level, floor) || !r.equals(roomAt(feet))) {
                        continue;
                    }
                    double d = Math.sqrt(Vec3.atCenterOf(s).distanceToSqr(feet.getX() + 0.5, feet.getY() + 0.5,
                            feet.getZ() + 0.5));
                    if (d <= max && d < bestD) {
                        bestD = d;
                        best = new double[]{feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5};
                    }
                }
            }
        }
        return best;
    }

    private static net.minecraft.world.entity.ambient.Bat batNear(ServerLevel level, BlockPos at) {
        var box = new net.minecraft.world.phys.AABB(at).inflate(12);
        List<net.minecraft.world.entity.ambient.Bat> bats = level.getEntitiesOfClass(
                net.minecraft.world.entity.ambient.Bat.class, box, e -> e.isAlive());
        return bats.isEmpty() ? null : bats.get(0);
    }

    // ------------------------------------------------------------------------------------------------ the leave

    /** Armed per case; ticked on the server in a phase BEFORE the mod's END_SERVER_TICK listeners and one after. */
    private static final class Leaver {
        final String room;
        final int target;
        final int delay;
        final Mode mode;
        final UUID uuid;
        final Plan plan;
        volatile int countTick = -1;
        volatile boolean countEarly;
        volatile int leaveTick = -1;
        volatile boolean left;
        volatile String landed;
        volatile Boolean etherFired;

        Leaver(String room, int target, int delay, Mode mode, UUID uuid, Plan plan) {
            this.room = room;
            this.target = target;
            this.delay = delay;
            this.mode = mode;
            this.uuid = uuid;
            this.plan = plan;
        }
    }

    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        Identifier early = Identifier.fromNamespaceAndPath("testkit", "leave_secrets_early");
        Identifier late = Identifier.fromNamespaceAndPath("testkit", "leave_secrets_late");
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(early, Event.DEFAULT_PHASE);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, late);
        ServerTickEvents.END_SERVER_TICK.register(early, server -> leaveTick(server, true));
        ServerTickEvents.END_SERVER_TICK.register(late, server -> leaveTick(server, false));
        Identifier use = Identifier.fromNamespaceAndPath("testkit", "leave_secrets_use");
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(use, Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(use, (player, level, hand, hit) -> {
            if (!level.isClientSide() && player instanceof ServerPlayer && hit != null) {
                SERVER_USES.add(key(hit.getBlockPos()));
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * The moment the server's own count for the room reaches the target, he leaves: in the SAME pass for delay 0 - the
     * early pass for a secret counted while the tick's packets were handled (chest, essence, key, bat), so the mod's own
     * listeners of this tick (the action bar) already see him out; the late pass for one the mod counted in its own
     * END_SERVER_TICK (an item) - or in that pass one tick later for delay 1.
     */
    private static void leaveTick(MinecraftServer server, boolean early) {
        Leaver l = leaver;
        if (l == null || l.left) {
            return;
        }
        try {
            int now = server.getTickCount();
            if (l.countTick < 0) {
                if (foundIn(l.room) < l.target) {
                    return;
                }
                l.countTick = now;
                l.countEarly = early;
            }
            if (early != l.countEarly || now - l.countTick != l.delay) {
                return;
            }
            ServerPlayer sp = server.getPlayerList().getPlayer(l.uuid);
            if (sp == null) {
                return;
            }
            ServerLevel level = (ServerLevel) sp.level();
            Plan p = l.plan;
            if (l.mode == Mode.DOOR) {
                sp.teleportTo(level, p.doorStep[0], p.doorStep[1], p.doorStep[2], Set.of(), sp.getYRot(), sp.getXRot(),
                        false);
            } else {
                sp.teleportTo(level, p.launch[0], p.launch[1], p.launch[2], Set.of(), p.yaw, p.pitch, false);
                sp.setYRot(p.yaw);
                sp.setXRot(p.pitch);
                ItemStack aotv = (ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "build",
                        new Class<?>[]{String.class}, new Object[]{"ASPECT_OF_THE_VOID"});
                l.etherFired = (Boolean) ModUnderTest.staticCall(SIM_ABILITIES, "etherwarp",
                        new Class<?>[]{ServerPlayer.class, ItemStack.class}, new Object[]{sp, aotv});
            }
            l.leaveTick = now;
            l.landed = roomAt(sp.blockPosition());
            l.left = true;
        } catch (Throwable t) {
            t.printStackTrace();
            l.landed = "threw " + t;
            l.left = true;
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static int foundIn(String room) {
        return (Integer) ModUnderTest.staticCall(SIM_SCORE, "foundInRoom", new Class<?>[]{String.class},
                new Object[]{room});
    }

    private static String roomAt(BlockPos p) {
        return (String) ModUnderTest.staticCall(SIM_SCORE, "roomAt", new Class<?>[]{BlockPos.class}, new Object[]{p});
    }

    private static String key(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** {found, secrets, cleared 0/1} as the mod's RoomStatus (the map label's own count) has room r, or null. */
    @SuppressWarnings("unchecked")
    private static int[] mapRoom(String r) {
        for (Object o : (List<Object>) ModUnderTest.staticCall(ROOM_STATUS, "rooms")) {
            if (r.equals(ModUnderTest.call(o, "name", new Class<?>[]{}, new Object[]{}))) {
                int secrets = (Integer) ModUnderTest.call(o, "secrets", new Class<?>[]{}, new Object[]{});
                int unfound = (Integer) ModUnderTest.call(o, "unfound", new Class<?>[]{}, new Object[]{});
                boolean cleared = (Boolean) ModUnderTest.call(o, "cleared", new Class<?>[]{}, new Object[]{});
                return new int[]{secrets - unfound, secrets, cleared ? 1 : 0};
            }
        }
        return null;
    }

    private static String clientRoom() {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        return (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{here});
    }

    private static BlockPos cellCenter(ClientGameTestContext ctx, int gx, int gz) {
        return ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                new Class<?>[]{int.class, int.class}, new Object[]{gx, gz}));
    }

    /** R on cells (0..2w-2, 0..2h-2), a NORMAL door east (or south) of its first tile, B beyond it; all unrotated. */
    private static int build(ClientGameTestContext ctx, String r, int side, String b, int w, int h) {
        int[] cellRoom = new int[GRID * GRID];
        java.util.Arrays.fill(cellRoom, -1);
        int[] cellDoor = new int[GRID * GRID];
        int[] cellRotation = new int[GRID * GRID];
        for (int gz = 0; gz <= 2 * h - 2; gz++) {
            for (int gx = 0; gx <= 2 * w - 2; gx++) {
                cellRoom[gz * GRID + gx] = 0;
            }
        }
        int door;
        if (side == EAST) {
            door = 2 * w - 1;
            cellRoom[2 * w] = 1;
        } else {
            door = (2 * h - 1) * GRID;
            cellRoom[(2 * h) * GRID] = 1;
        }
        cellDoor[door] = 1; // DungeonLayout.DOOR_NORMAL
        String code = ctx.computeOnClient(mc -> {
            try {
                Class<?> dec = Class.forName(MAP_CODE + "$Decoded");
                Object d = dec.getConstructor(String[].class, int[].class, int[].class, int[].class)
                        .newInstance(new String[]{r, b}, cellRoom, cellDoor, cellRotation);
                return (String) ModUnderTest.staticCall(MAP_CODE, "encodeDecoded", new Class<?>[]{dec}, new Object[]{d});
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(BUILDER, "build",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, code})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitTicks(40);
        return door;
    }

    private static void teleport(ClientGameTestContext ctx, double[] at) {
        onServer(ctx, (level, sp) -> {
            sp.teleportTo(at[0], at[1], at[2]);
            return true;
        });
        ctx.waitTicks(3);
        for (int i = 0; i < 60 && !ctx.computeOnClient(mc -> mc.player.onGround()
                && mc.player.position().distanceToSqr(at[0], at[1], at[2]) < 4.0); i++) {
            ctx.waitTicks(1);
        }
    }

    private static void waitInRoom(ClientGameTestContext ctx, String r, int ticks) {
        for (int i = 0; i < ticks && !r.equals(ctx.computeOnClient(mc -> clientRoom())); i++) {
            ctx.waitTicks(1);
        }
    }

    private static void waitClientHas(ClientGameTestContext ctx, BlockPos p) {
        for (int i = 0; i < 200 && ctx.computeOnClient(mc -> mc.level.getBlockState(p).isAir()); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(5);
    }

    /** Slot 8 emptied on the server and held: a plain right click, no ability to pair with the block click. */
    private static void emptyHand(ClientGameTestContext ctx) {
        onServer(ctx, (level, sp) -> {
            sp.getInventory().setItem(8, ItemStack.EMPTY);
            return true;
        });
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(8));
        ctx.waitTicks(5);
    }

    /** A plain right click on a block, as the player sends it. */
    private static void use(ClientGameTestContext ctx, BlockPos p) {
        ctx.runOnClient(mc -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(p), Direction.UP, p, false)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T onServer(ClientGameTestContext ctx, BiFunction<ServerLevel, ServerPlayer, T> f) {
        AtomicReference<Object[]> box = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID id = mc.player.getUUID();
            server.execute(() -> {
                try {
                    box.set(new Object[]{f.apply(server.overworld(), server.getPlayerList().getPlayer(id)), null});
                } catch (Throwable t) {
                    box.set(new Object[]{null, t});
                }
            });
        });
        ctx.waitFor(mc -> box.get() != null, 400);
        if (box.get()[1] != null) {
            throw new AssertionError("server task threw", (Throwable) box.get()[1]);
        }
        return (T) box.get()[0];
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 400);
    }

    private static String fmt(double[] p) {
        return p == null ? "-" : String.format(Locale.ROOT, "%.1f %.1f %.1f", p[0], p[1], p[2]);
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}
