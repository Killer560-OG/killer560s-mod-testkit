package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>1122-sim-ac-witherkey-unseen</b> and <b>1122-sim-ap-witherkey-unseen</b> (mod ap-witherkey-1122; killer560,
 * 2026-11-22: "it keeps insta clearing rooms on the way to blood which means i cannot get the wither key and bricks the
 * run"). His log: the autopilot etherwarped to a wither door and clicked it ~110 ms after landing, before Hypixel had put
 * the room he stood in on the map (392-756 ms late). The room BEHIND the door was still undiscovered, and a door opened
 * into an undiscovered room makes Hypixel count it cleared at once: its mobs keep standing and its key never drops
 * (map lines {@code undiscovered->cleared} at five door opens, sidebar +3/+4 tiles at the click).
 *
 * <p>A generated F7: a leaf mob room X behind one door D from a mob room K; D is made a shut wither door, every mob room but
 * X (K included) is marked cleared, one starred zombie stands in X, and the sim arms X as a key room entered through D from
 * K ({@code SimKeyRoom.armForTest(mc, X, K, D)}): opening D before he has stood in K for {@code SEEN_TICKS} insta clears X.
 * He is given a Wither Key. The run (Auto Clear Any Mob Room, or Dungeons Autopilot Solo 300 / Blood Rush None / Insta Clear
 * on) must open D only once he has stood in K a while (client ticks in K at the open line >= 6; an origin jar clicks about
 * two ticks after landing), and X must not be lost: its mob dies, X reads cleared, its key drops and is picked up.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimWitherKeyUnseenTests implements FabricClientGameTest {

    private static final String AC = "1122-sim-ac-witherkey-unseen";
    private static final String AP = "1122-sim-ap-witherkey-unseen";
    private static final String TAG = "1122-witherkey-unseen";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String SIM_KEY_ROOM = "com.killer560.hub.roomsim.SimKeyRoom";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String SIM_KEYS = "com.killer560.hub.roomsim.SimKeys";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String KEY_ROOMS = "com.killer560.hub.autoclear.KeyRooms";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final int CASE_TICKS = 2400;
    private static final int MIN_TICKS_IN_K = 6;

    record Setup(String leaf, String key, int door) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Map<String, Boolean> rows = new LinkedHashMap<>();
        if (!System.getProperty("testkit.scenario", "").isBlank()) {
            if (Scenario.named(AC)) rows.put(AC, false);
            if (Scenario.named(AP)) rows.put(AP, true);
        }
        if (rows.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            rows.keySet().forEach(n -> Scenario.skipped(n, "legit jar: no Auto Clear"));
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            rows.keySet().forEach(n -> Scenario.skipped(n, "needs real room captures and room database"));
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                new Object[]{false}));
        List<String> failed = new ArrayList<>();
        try {
            for (Map.Entry<String, Boolean> e : rows.entrySet()) {
                List<String> failures = new ArrayList<>();
                try {
                    for (int i = 1; i <= 3; i++) {
                        List<String> mine = new ArrayList<>();
                        oneCase(ctx, mine, e.getKey(), e.getValue());
                        boolean premise = !mine.isEmpty() && mine.stream().allMatch(f -> f.contains("the premise failed"));
                        if (!premise || i == 3) {
                            failures.addAll(mine);
                            break;
                        }
                        println(e.getKey() + ": " + mine + " - a fresh floor (" + (i + 1) + " of 3)");
                    }
                } catch (Throwable t) {
                    failures.add("threw " + t);
                    t.printStackTrace(System.out);
                } finally {
                    stopAll(ctx);
                }
                boolean pass = failures.isEmpty();
                String detail = pass ? "acted as asserted" : String.join("; ", failures);
                System.out.println("[" + e.getKey() + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
                Report.caseStarted(e.getKey());
                Report.caseFinished(e.getKey(), pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
                SuiteVerdict.finished(e.getKey());
                if (!pass) {
                    failed.add(e.getKey() + ": " + detail);
                }
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEY_ROOM, "disarm"));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false);
                Object as = ModUnderTest.config(AS_CONFIG);
                ModUnderTest.set(as, "setInstaClear", false);
                ModUnderTest.set(as, "setAutoClearRooms", false);
            });
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        if (!failed.isEmpty()) {
            throw new AssertionError(failed.size() + " row(s) failed: " + String.join("; ", failed));
        }
    }

    private static void oneCase(ClientGameTestContext ctx, List<String> failures, String label, boolean pilot) {
        // ---- a floor with a leaf mob room X off a mob room K that the run reaches through open doors ----
        Setup s = null;
        List<SimAutoClearTests.Room> rooms = null;
        for (int attempt = 1; attempt <= 8 && s == null; attempt++) {
            long before = Scenario.simBuildCount(ctx);
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                        new Object[]{mc, floor, 3, 4});
            }));
            ctx.waitFor(mc -> mc.level != null);
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(80);
            SimAutoClearTests.awaitFloorChunks(ctx);
            rooms = SimAutoClearTests.floor(ctx);
            s = pick(ctx, rooms);
            println(label + ": floor " + attempt + " - " + rooms.size() + " rooms; setup " + s);
        }
        if (s == null) {
            failures.add(label + ": no generated floor in 8 had a leaf mob room off a reachable mob room");
            return;
        }
        final Setup setup = s;

        // ---- loadout ----
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION", "BAT_WAND"};
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEYS, "clear"));
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);

        // ---- every mob room but X cleared (K too, so nothing walks into K for its own key) ----
        for (SimAutoClearTests.Room r : rooms) {
            if (r.mob() && !r.name().equals(setup.leaf())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
            }
        }
        ctx.waitTicks(5);
        int doors = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, setup.leaf()}));
        ctx.waitTicks(40);
        SimAutoClearTests.Room roomX = rooms.stream().filter(x -> x.name().equals(setup.leaf())).findFirst().orElseThrow();
        List<SimAutoClearTests.Placed> placed = SimAutoClearTests.spawnIn(ctx, roomX, 1, false);
        ctx.waitTicks(30);
        // The 4-argument hook (X entered through D from K) is this branch's; an origin jar has only the 1-room one.
        boolean newHook = ctx.computeOnClient(mc -> {
            try {
                Class.forName(SIM_KEY_ROOM).getMethod("armForTest", Minecraft.class, String.class, String.class, int.class);
                return true;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        });
        boolean armed = ctx.computeOnClient(mc -> newHook
                ? (Boolean) ModUnderTest.staticCall(SIM_KEY_ROOM, "armForTest",
                        new Class<?>[]{Minecraft.class, String.class, String.class, int.class},
                        new Object[]{mc, setup.leaf(), setup.key(), setup.door()})
                : (Boolean) ModUnderTest.staticCall(SIM_KEY_ROOM, "armForTest",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, setup.leaf()}));
        println(label + ": " + doors + " wither door(s) round " + setup.leaf() + " (door " + setup.door() + " from "
                + setup.key() + "); armed " + armed + " (4-arg hook " + newHook + "); placed " + placed);
        if (doors != 1 || !armed || placed.size() != 1) {
            failures.add(label + ": the premise failed (" + doors + " wither door(s), armed " + armed + ", " + placed.size()
                    + " mob(s) placed)");
            return;
        }

        // ---- a Wither Key for the team ----
        long keyMark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEYS, "drop", new Class<?>[]{Minecraft.class, Vec3.class,
                boolean.class}, new Object[]{mc, mc.player.position(), false}));
        boolean haveKey = false;
        for (int i = 0; i < 100 && !haveKey; i++) {
            ctx.waitTicks(1);
            haveKey = LogTap.since(keyMark).stream().anyMatch(l -> l.contains("has obtained Wither Key!"));
        }
        if (!haveKey) {
            failures.add(label + ": the premise failed (the Wither Key dropped at his feet was not picked up)");
            return;
        }

        // ---- the run ----
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.set(cfg, "setPickUpKeys", true);
            ModUnderTest.set(cfg, "setWalkWhenFaster", false);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        if (pilot) {
            SimAutopilotTests.writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> {
                Object as = ModUnderTest.config(AS_CONFIG);
                Object solo = ModUnderTest.enumValue(AS_CONFIG + "$Mode", "SOLO_300");
                ModUnderTest.call(as, "setMode", new Class<?>[]{solo.getClass()}, new Object[]{solo});
                Object none = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "NONE");
                ModUnderTest.call(as, "setBloodRush", new Class<?>[]{none.getClass()}, new Object[]{none});
                ModUnderTest.set(as, "setInstaClear", true);
                ModUnderTest.set(as, "setAutoClearRooms", true);
                ModUnderTest.set(as, "setBloodFirst", false);
                ModUnderTest.set(as, "setChatFeedback", true);
            });
        }
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> {
            if (pilot) {
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true});
            } else {
                ModUnderTest.staticCall(AUTO, "start");
            }
        });
        int inK = 0;
        int inKAtOpen = -1;
        int openTick = -1;
        int keysAfter = 0;
        int ticks = 0;
        for (int t = 0; t < CASE_TICKS; t++) {
            ctx.waitTicks(1);
            ticks = t + 1;
            String here = ctx.computeOnClient(mc -> roomNameAt(mc.player.getX(), mc.player.getZ()));
            inK = setup.key().equals(here) ? inK + 1 : 0;
            if (openTick < 0 && LogTap.since(mark).stream().anyMatch(l -> l.contains("opened a WITHER door!"))) {
                openTick = t;
                inKAtOpen = inK;
            }
            keysAfter = (int) LogTap.since(mark).stream().filter(l -> l.contains("has obtained Wither Key!")).count();
            if (keysAfter > 0 && openTick >= 0) {
                ctx.waitTicks(10);
                break;
            }
            boolean busy = ctx.computeOnClient(mc -> pilot ? (Boolean) ModUnderTest.staticCall(PILOT, "isOn")
                    : (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
            if (!busy && t > 40) {
                break;
            }
            if (t % 200 == 199) {
                println(label + " t=" + (t + 1) + ": in " + here + ", " + ctx.computeOnClient(mc ->
                        ModUnderTest.staticCall(AUTO, "status")));
            }
        }
        String state = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_KEY_ROOM, "testState"));
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
        List<SimAutoClearTests.Room> after = SimAutoClearTests.floor(ctx);
        boolean waitLine = false;
        boolean insta = false;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoClear]") || l.contains("[AutoSecret]") || l.contains("Sim key room")
                    || l.contains("Wither Key") || l.contains("WITHER door") || l.contains("[Sim] room cleared")) {
                println("  log: " + l.replaceAll("^.*?(\\[AutoClear\\]|\\[AutoSecret\\]|\\[Sim\\]|Sim key room|\\[CHAT\\])",
                        "$1"));
            }
            waitLine |= l.contains("is not on the map yet");
            insta |= l.contains("Sim key room: " + setup.leaf() + " insta cleared");
        }
        String[] st = state == null ? new String[0] : state.split("\\|");
        boolean lost = st.length > 3 && st[3].equals("true");
        boolean keyDropped = st.length > 4 && st[4].equals("true");
        boolean xCleared = after.stream().anyMatch(r -> r.name().equals(setup.leaf()) && r.cleared());
        println(label + ": " + ticks + " ticks; door opened at t=" + openTick + " after " + inKAtOpen + " tick(s) in "
                + setup.key() + "; wait line " + waitLine + "; sim state " + state + ", insta " + insta + "; mobs " + alive
                + "; " + setup.leaf() + " cleared " + xCleared + "; keys obtained after the start " + keysAfter);
        if (openTick < 0) {
            failures.add(label + ": the wither door into " + setup.leaf() + " never opened");
            return;
        }
        if (inKAtOpen < MIN_TICKS_IN_K) {
            failures.add(label + ": the wither door opened after only " + inKAtOpen + " tick(s) in " + setup.key()
                    + " - before Hypixel's map shows " + setup.leaf() + ", which insta clears it");
        }
        if (lost || insta) {
            failures.add(label + ": " + setup.leaf() + " was insta cleared - its key is lost");
        }
        for (SimAutoClearTests.Placed p : placed) {
            if (!"dead".equals(alive.get(p.id()))) {
                failures.add(label + ": the starred mob in " + p.room() + " is " + alive.get(p.id()));
            }
        }
        if (!xCleared) {
            failures.add(label + ": " + setup.leaf() + " never read cleared");
        }
        if (!keyDropped || keysAfter < 1) {
            failures.add(label + ": " + setup.leaf() + "'s Wither Key was not dropped and picked up (dropped " + keyDropped
                    + ", obtained " + keysAfter + ")");
        }
    }

    /** A leaf mob room whose one ordinary door leads to a mob room the run reaches through open doors. */
    @SuppressWarnings("unchecked")
    private static Setup pick(ClientGameTestContext ctx, List<SimAutoClearTests.Room> rooms) {
        List<String> pairs = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(KEY_ROOMS, "testLeafPairs"));
        String start = ctx.computeOnClient(mc -> roomNameAt(mc.player.getX(), mc.player.getZ()));
        for (String p : pairs) {
            String[] f = p.split("\\|");
            SimAutoClearTests.Room x = find(rooms, f[0]);
            SimAutoClearTests.Room k = find(rooms, f[1]);
            if (x == null || k == null || !x.mob() || !k.mob() || x.rush() || f[1].equals(start)) {
                continue;
            }
            List<String> path = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(KEY_ROOMS, "testRoomPath",
                    new Class<?>[]{String.class, String.class}, new Object[]{start, f[1]}));
            if (start != null && !path.isEmpty() && !path.contains(f[0])) {
                return new Setup(f[0], f[1], Integer.parseInt(f[2]));
            }
        }
        return null;
    }

    private static SimAutoClearTests.Room find(List<SimAutoClearTests.Room> rooms, String name) {
        return rooms.stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
    }

    private static String roomNameAt(double x, double z) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                new Object[]{x, z});
        return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
    }

    private static void stopAll(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
            if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true});
            }
            ModUnderTest.staticCall(AUTO, "cancel");
        });
    }

    static void println(String s) {
        System.out.println("[" + TAG + "] " + s);
    }
}
