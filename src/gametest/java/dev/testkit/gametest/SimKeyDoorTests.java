package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 1996-sim-key-door (mod dungeonmap-1103, commit 43f7cb865): killer560, 2026-11-03: "the wither door highlight once i have a key
 * goes off of closest door to me by distance not the actual door i need to open." With a Wither Key held, Wither Doors
 * gives the key color to {@code BloodRush.nextDoor} - the first locked door on the map's room path toward Blood - not the
 * wither door nearest in a straight line.
 *
 * <p>One generated sim F7. This test's own search over the published layout (breadth-first from the Blood room, by door
 * hops) finds a room A with exactly one door on a shortest way to Blood (the door to open, D) and a second door (the decoy,
 * X) that leads away from Blood. Both are made shut wither doors ({@code SimDoors.makeWitherDoor}) and he stands in A four
 * blocks from X, so X is the nearest by straight line (premise, measured) and D is the one to open. With the Wither Key
 * held (the mod's own flag), Ready color green and Locked color red:
 * <ul>
 *   <li>1996 map path (Show All Doors off): {@code keyDoorIdx} is D, {@code lastPick} is "path", the box on D is green and
 *       the box on X red, and {@code BloodRush.nextDoor} itself answers D;</li>
 *   <li>1997 scanner path (Show All Doors on, cheat): the same boxes and colors;</li>
 *   <li>1998 no locked door on the way: D opened; {@code nextDoor} answers a valid door (the Blood door, which is locked), no
 *       exception, and the key color then falls back to the nearest wither door by distance (X) with {@code lastPick}
 *       "distance" - the only wither door left;</li>
 *   <li>1999 not vacuous: with no key held, neither box is green (the highlight really is the key color).</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimKeyDoorTests implements FabricClientGameTest {

    private static final String NAME = "1996-sim-key-door";
    private static final String R_MAP = "1996-key-door-map-path";
    private static final String R_SCAN = "1997-key-door-scanner-path";
    private static final String R_FALLBACK = "1998-key-door-no-locked-on-way";
    private static final String R_NOKEY = "1999-key-door-no-key-no-green";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String HELD = "com.killer560.hub.livemap.HeldMap";
    private static final String RUSH = "com.killer560.hub.livemap.autoclear.BloodRush";
    private static final String FEATURE = "com.killer560.hub.witherdoors.WitherDoorsFeature";
    private static final String CFG = "com.killer560.hub.witherdoors.WitherDoorsConfig";
    private static final int GREEN = 0xFF00FF00;
    private static final int RED = 0xFFFF0000;

    private final List<String> all = new ArrayList<>();

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
        try {
            body(ctx);
        } catch (Throwable t) {
            all.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    Object c = ModUnderTest.config(CFG);
                    ModUnderTest.set(c, "setEnabled", false);
                    ModUnderTest.set(c, "setShowAllDoors", false);
                    inv(c, "save");
                } catch (Throwable ignored) {
                    // cleanup never replaces a verdict
                }
            });
            leave(ctx);
        }
        if (!all.isEmpty()) {
            throw new AssertionError(String.join(" | ", all));
        }
        println("PASS - every key door case passed");
    }

    private void body(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setShowAllDoors", false);
            ModUnderTest.set(cfg, "setFairyDoor", false);
            ModUnderTest.set(cfg, "setCustomFillColor", false);
            ModUnderTest.set(cfg, "setWitherReadyColor", GREEN);
            ModUnderTest.set(cfg, "setWitherLockedColor", RED);
            ModUnderTest.set(cfg, "setRenderDistance", 256);
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
            ModUnderTest.staticCall(FLOOR_GEN, "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 1200);
        ctx.waitFor(mc -> mc.player.onGround() && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir(), 2400);
        ctx.waitTicks(40);
        Plan p = ctx.computeOnClient(SimKeyDoorTests::plan);
        println("plan: " + p);
        if (p.problem != null) {
            row(R_MAP, false, "premise: " + p.problem);
            return;
        }
        creativeFlying(ctx);
        // Go there first: a door in an unloaded chunk is not carved until the chunk loads.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(HELD, "resetSimVisits"));
        double[] at = ctx.computeOnClient(mc -> standPoint(p));
        place(ctx, at);
        ctx.waitTicks(40);
        // The two shut wither doors.
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(SIM_DOORS, "makeWitherDoor", new Class<?>[]{Minecraft.class, int.class},
                    new Object[]{mc, p.openDoor});
            ModUnderTest.staticCall(SIM_DOORS, "makeWitherDoor", new Class<?>[]{Minecraft.class, int.class},
                    new Object[]{mc, p.decoyDoor});
        });
        BlockPos dBlock = ctx.computeOnClient(mc -> doorBlock(p.openDoor));
        BlockPos xBlock = ctx.computeOnClient(mc -> doorBlock(p.decoyDoor));
        ctx.waitFor(mc -> !mc.level.getBlockState(dBlock).isAir() && !mc.level.getBlockState(xBlock).isAir(), 400);
        // He stands in A, four blocks in from the decoy door.
        ctx.waitTicks(40);

        // ---- the premise: nearest by straight line is the decoy, and the layout sees both locked ------------------
        double[] dist = ctx.computeOnClient(mc -> new double[]{
                mc.player.getEyePosition().distanceToSqr(dBlock.getX() + 0.5, dBlock.getY() + 1.0, dBlock.getZ() + 0.5),
                mc.player.getEyePosition().distanceToSqr(xBlock.getX() + 0.5, xBlock.getY() + 1.0, xBlock.getZ() + 0.5)});
        boolean[] locked = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "current");
            return new boolean[]{(Boolean) inv(layout, "isLocked", p.openDoor), (Boolean) inv(layout, "isLocked", p.decoyDoor)};
        });
        int roomNow = ctx.computeOnClient(mc -> (Integer) inv(ModUnderTest.staticCall(LAYOUT, "current"), "currentRoom"));
        String premise = "he stands in room " + roomNow + " (A is " + p.roomA + " " + p.nameA + "), door to open " + p.openDoor
                + " at squared distance " + Math.round(dist[0]) + ", decoy " + p.decoyDoor + " at " + Math.round(dist[1])
                + "; locked per the layout: " + Arrays.toString(locked);
        println(premise);
        boolean premiseOk = dist[1] < dist[0] && locked[0] && locked[1] && roomNow == p.roomA;
        if (!premiseOk) {
            row(R_MAP, false, "premise failed: " + premise);
            return;
        }

        // ---- 1999 first: no key, no green --------------------------------------------------------------------------
        setKey(ctx, false);
        ctx.waitTicks(30);
        List<double[]> noKey = ctx.computeOnClient(mc -> boxes());
        boolean greenNoKey = colorAt(noKey, dBlock, GREEN) || colorAt(noKey, xBlock, GREEN);
        boolean boxesThere = boxAt(noKey, dBlock) != null && boxAt(noKey, xBlock) != null;
        row(R_NOKEY, boxesThere && !greenNoKey, "no key held: " + noKey.size() + " box(es), boxes on both doors " + boxesThere
                + ", any green " + greenNoKey);

        // ---- 1996: the map path --------------------------------------------------------------------------------------
        setKey(ctx, true);
        ctx.waitTicks(30);
        int next = ctx.computeOnClient(mc -> nextDoor());
        int keyIdx = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(FEATURE, "keyDoorIdx"));
        String pick = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(FEATURE, "lastPick"));
        List<double[]> map = ctx.computeOnClient(mc -> boxes());
        boolean dGreen = colorAt(map, dBlock, GREEN);
        boolean xRed = colorAt(map, xBlock, RED);
        row(R_MAP, next == p.openDoor && keyIdx == p.openDoor && "path".equals(pick) && dGreen && xRed,
                "key held: BloodRush.nextDoor " + next + ", keyDoorIdx " + keyIdx + ", lastPick '" + pick + "' (want door "
                        + p.openDoor + ", path); box on it green " + dGreen + ", box on the nearer decoy " + p.decoyDoor
                        + " red " + xRed + "; " + describe(map));

        // ---- 1997: the scanner path (Show All Doors) -----------------------------------------------------------------
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(CFG), "setShowAllDoors", true);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
        ctx.waitTicks(40);
        List<double[]> scan = ctx.computeOnClient(mc -> boxes());
        boolean showAll = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.call(ModUnderTest.config(CFG), "isShowAllDoors",
                new Class<?>[]{}, new Object[]{}));
        boolean dGreen2 = colorAt(scan, dBlock, GREEN);
        boolean xRed2 = colorAt(scan, xBlock, RED);
        row(R_SCAN, dGreen2 && xRed2, "Show All Doors " + (showAll ? "on" : "off (legit jar: not available, the map path again)")
                + ": box on the door to open green " + dGreen2 + ", on the decoy red " + xRed2 + "; " + describe(scan));
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config(CFG), "setShowAllDoors", false);
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });

        // ---- 1998: no locked door on the way -------------------------------------------------------------------------
        boolean opened = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_DOORS, "openDoorAtCell",
                new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, p.openDoor}));
        ctx.waitFor(mc -> mc.level.getBlockState(dBlock).isAir(), 400);
        ctx.waitTicks(30);
        int bloodDoor = ctx.computeOnClient(mc -> (Integer) inv(ModUnderTest.staticCall(LAYOUT, "current"), "bloodDoor"));
        boolean bloodLocked = ctx.computeOnClient(mc -> bloodDoor >= 0
                && (Boolean) inv(ModUnderTest.staticCall(LAYOUT, "current"), "isLocked", bloodDoor));
        String err = null;
        int next2 = -2;
        try {
            next2 = ctx.computeOnClient(mc -> nextDoor());
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(FEATURE, "invalidateCache"));
        ctx.waitTicks(30);
        int keyIdx2 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(FEATURE, "keyDoorIdx"));
        String pick2 = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(FEATURE, "lastPick"));
        List<double[]> fb = ctx.computeOnClient(mc -> boxes());
        boolean xGreen = colorAt(fb, xBlock, GREEN);
        // The Blood door answers while it is locked (not a wither door: the key color then falls back to the nearest wither
        // door by distance); a sim Blood door that is not shut yet leaves BloodRush its own fallback, the nearest wither
        // door by walking path - here the decoy, the only wither door left ("path").
        boolean sane = bloodLocked ? next2 == bloodDoor && "distance".equals(pick2) : next2 == p.decoyDoor && "path".equals(pick2);
        row(R_FALLBACK, err == null && opened && sane && keyIdx2 == p.decoyDoor && xGreen,
                "door to open opened " + opened + "; nextDoor " + next2 + " (the Blood door is " + bloodDoor + ", locked "
                        + bloodLocked + "), error " + err + "; keyDoorIdx " + keyIdx2 + " (decoy " + p.decoyDoor
                        + "), lastPick '" + pick2 + "', its box green " + xGreen);
    }

    // ------------------------------------------------------------------------------------------------- reading

    private static int nextDoor() {
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        return (Integer) ModUnderTest.staticCall(RUSH, "nextDoor", new Class<?>[]{layout.getClass()}, new Object[]{layout});
    }

    private static List<double[]> boxes() {
        List<double[]> out = new ArrayList<>();
        for (Object o : (List<?>) ModUnderTest.staticCall(FEATURE, "cachedBoxes")) {
            out.add((double[]) o);
        }
        return out;
    }

    private static double[] boxAt(List<double[]> boxes, BlockPos door) {
        for (double[] b : boxes) {
            if (Math.abs((b[0] + b[3]) / 2 - (door.getX() + 0.5)) < 0.6 && Math.abs((b[2] + b[5]) / 2 - (door.getZ() + 0.5)) < 0.6) {
                return b;
            }
        }
        return null;
    }

    private static boolean colorAt(List<double[]> boxes, BlockPos door, int argb) {
        double[] b = boxAt(boxes, door);
        return b != null && b.length >= 9 && Math.abs(b[6] - ((argb >> 16) & 0xFF) / 255.0) < 0.05
                && Math.abs(b[7] - ((argb >> 8) & 0xFF) / 255.0) < 0.05 && Math.abs(b[8] - (argb & 0xFF) / 255.0) < 0.05;
    }

    private static String describe(List<double[]> boxes) {
        StringBuilder sb = new StringBuilder("boxes [");
        for (double[] b : boxes) {
            sb.append(String.format(Locale.ROOT, "(%.0f,%.0f rgb %.1f/%.1f/%.1f)", (b[0] + b[3]) / 2, (b[2] + b[5]) / 2,
                    b.length > 6 ? b[6] : -1, b.length > 7 ? b[7] : -1, b.length > 8 ? b[8] : -1));
        }
        return sb.append("]").toString();
    }

    private static BlockPos doorBlock(int idx) {
        return (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock", new Class<?>[]{int.class}, new Object[]{idx});
    }

    private static void setKey(ClientGameTestContext ctx, boolean held) {
        ctx.runOnClient(mc -> {
            try {
                Field f = Class.forName(FEATURE).getDeclaredField("witherKeyHeld");
                f.setAccessible(true);
                f.setBoolean(null, held);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not set the Wither Key flag", e);
            }
            ModUnderTest.staticCall(FEATURE, "invalidateCache");
        });
    }

    // ------------------------------------------------------------------------------------------------- the plan

    /** The room A, its door to open D, and the decoy X, from this test's own breadth-first search toward the Blood room. */
    private static Plan plan(Minecraft mc) {
        Plan p = new Plan();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int rooms = (Integer) inv(layout, "roomCount");
        int blood = -1;
        for (int r = 0; r < rooms; r++) {
            Object entry = inv(layout, "entry", r);
            String type = "";
            try {
                if (entry != null) {
                    Object t = entry.getClass().getField("type").get(entry);
                    type = t == null ? "" : t.toString().toUpperCase(Locale.ROOT);
                }
            } catch (ReflectiveOperationException e) {
                // no type
            }
            if ("BLOOD".equals(type) || "Blood".equalsIgnoreCase((String) inv(layout, "name", r))) {
                blood = r;
            }
        }
        if (blood < 0) {
            p.problem = "no Blood room among " + rooms;
            return p;
        }
        // Edges: per room, {neighbor, door}.
        List<List<int[]>> adj = new ArrayList<>();
        for (int r = 0; r < rooms; r++) {
            adj.add(new ArrayList<>());
        }
        int[][] dirs = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
        for (int r = 0; r < rooms; r++) {
            for (int tile : (int[]) inv(layout, "tiles", r)) {
                int x = tile % 11, z = tile / 11;
                for (int[] d : dirs) {
                    int nx = x + 2 * d[0], nz = z + 2 * d[1];
                    if (nx < 0 || nx > 10 || nz < 0 || nz > 10) continue;
                    int door = (z + d[1]) * 11 + (x + d[0]);
                    int next = (Integer) inv(layout, "roomOfCell", nz * 11 + nx);
                    if ((Boolean) inv(layout, "isDoor", door) && next >= 0 && next != r) {
                        adj.get(r).add(new int[]{next, door});
                    }
                }
            }
        }
        int[] dist = new int[rooms];
        Arrays.fill(dist, -1);
        dist[blood] = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>(List.of(blood));
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int[] e : adj.get(r)) {
                if (dist[e[0]] < 0) {
                    dist[e[0]] = dist[r] + 1;
                    q.add(e[0]);
                }
            }
        }
        // A: not Blood, exactly one door on a shortest way (to a room one closer), and a door away from Blood.
        for (int a = 0; a < rooms; a++) {
            if (a == blood || dist[a] < 2) continue; // dist 1 is the room before Blood: its way on is the blood door
            int open = -1, openCount = 0, decoy = -1;
            for (int[] e : adj.get(a)) {
                if (dist[e[0]] == dist[a] - 1) {
                    open = e[1];
                    openCount++;
                } else if (dist[e[0]] >= dist[a] && e[0] != blood && decoy < 0) {
                    decoy = e[1];
                }
            }
            if (openCount == 1 && decoy >= 0) {
                p.roomA = a;
                p.nameA = (String) inv(layout, "name", a);
                p.openDoor = open;
                p.decoyDoor = decoy;
                p.tilesA = (int[]) inv(layout, "tiles", a);
                p.bloodRoom = blood;
                return p;
            }
        }
        p.problem = "no room with one door on the way to Blood and a decoy door away from it among " + rooms + " rooms";
        return p;
    }

    /** Four blocks from the decoy door, inside A, at the door's floor height plus half a block. */
    private static double[] standPoint(Plan p) {
        BlockPos x = doorBlock(p.decoyDoor);
        double cx = 0, cz = 0;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int t : p.tilesA) {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{t});
            double d = c.distSqr(x);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        cx = best.getX() - x.getX();
        cz = best.getZ() - x.getZ();
        double len = Math.max(1.0, Math.sqrt(cx * cx + cz * cz));
        return new double[]{x.getX() + 0.5 + cx / len * 4, x.getY() + 0.5, x.getZ() + 0.5 + cz / len * 4};
    }

    // ------------------------------------------------------------------------------------------------- acting

    private static void creativeFlying(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.setGameMode(GameType.CREATIVE);
                    sp.getAbilities().mayfly = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player.getAbilities().flying, 200);
    }

    private static void place(ClientGameTestContext ctx, double[] at) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(at[0], at[1], at[2]);
                }
            });
        });
        ctx.waitFor(mc -> mc.player.position().distanceToSqr(at[0], at[1], at[2]) < 0.01, 200);
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        });
    }

    private static Object inv(Object target, String method, Object... args) {
        try {
            for (Method m : target.getClass().getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                }
            }
            throw new AssertionError("no method " + method + "/" + args.length + " on " + target.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + method, e);
        }
    }

    private void row(String name, boolean pass, String detail) {
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
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

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }

    private static final class Plan {
        String problem;
        int roomA = -1;
        String nameA;
        int openDoor = -1;
        int decoyDoor = -1;
        int bloodRoom = -1;
        int[] tilesA;

        @Override
        public String toString() {
            return problem != null ? "PROBLEM " + problem : "room " + roomA + " " + nameA + ", door to open " + openDoor
                    + ", decoy " + decoyDoor + ", Blood room " + bloodRoom;
        }
    }
}
