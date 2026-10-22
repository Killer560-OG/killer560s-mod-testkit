package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketWatch;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>1020-sim-ac-keyroom</b> (mod autoclear-keys-1020; killer560, 2026-10-20: "for any auto clear it needs to be very
 * careful about etherwarping into the room before any unopened wither door too early or too fast. Any room before a wither
 * door is insta cleared, then you can no longer get a wither key. Once the wither door is opened, it is no issue at all.").
 *
 * <p>A generated sim F7 with clear-on-entry off. A leaf mob room X (one door, an ordinary one) gets its door turned into a
 * shut wither door ({@code SimDoors.witherDoorsAround}), which makes the room on its other side, K, the room in front of
 * the wither door: the key room. The sim is told K holds the Wither Key ({@code SimKeyRoom.armForTest}): judged on the sim
 * SERVER from the player's own positions, a first entry into K by a teleport insta clears it and loses the key (what the
 * insta-clear tracker's evidence has Hypixel do: 13 of 15 etherwarps into an undiscovered room, 15 of 74 into an unopened
 * one); a first entry on foot leaves it to clear when its starred mob dies, and its Wither Key then drops there. One more
 * mob room O, reachable without crossing K, is a room nothing guards. Every other mob room is marked cleared. A starred
 * zombie stands in each of K, O and X.
 *
 * <ol>
 *   <li><b>control</b> - the key-room rule switched off ({@code KeyRooms.testSetEnabled(false)}): Auto Clear etherwarps into
 *       K as into any room, the sim insta clears it and the key is lost, and no key is ever picked up. This case FAILS the
 *       scenario if it does not happen: without it, a pass of the next case would prove nothing.</li>
 *   <li><b>keyroom</b> - the rule on (a fresh floor): (a) K is entered on foot, never by a teleport, and not insta cleared;
 *       (b) K is cleared by its mob's death; (c) its Wither Key drops and is picked up ("has obtained Wither Key!"), the
 *       wither door is clicked open with it and X behind it is cleared; (d) O is still reached by an etherwarp trip ("trip
 *       to room O"), with no key-room walk for it - every room the rule does not cover goes on as before. The counters
 *       ({@code keyRoomWalkIns}, {@code keyRoomApproachTrips}) must move.</li>
 * </ol>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimKeyRoomTests implements FabricClientGameTest {

    private static final String NAME = "1020-sim-ac-keyroom";
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
    private static final int CASE_TICKS = 3600;

    /** The rooms of one case: the leaf behind the wither door, the key room in front of it, an unguarded one. */
    record Setup(String leaf, String key, String other, int door) {
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
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
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"));
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
        List<String> failures = new ArrayList<>();
        try {
            withRetry(ctx, failures, "control", false);
            withRetry(ctx, failures, "keyroom", true);
        } catch (Throwable t) {
            failures.add("scenario threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(KEY_ROOMS, "testSetEnabled", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEY_ROOM, "disarm"));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_MOBS, "setClearRoomsOnEntry", new Class<?>[]{boolean.class},
                    new Object[]{true}));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false));
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
        if (!failures.isEmpty()) {
            System.out.println("[" + NAME + "] FAIL - " + String.join("; ", failures));
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        System.out.println("[" + NAME + "] PASS");
    }

    /**
     * A case whose floor turned out not to fit (the premise: one wither door, K the only key room besides ones nothing
     * here goes to) is run again on a fresh floor, up to three floors; any other failure counts at once.
     */
    private static void withRetry(ClientGameTestContext ctx, List<String> failures, String label, boolean rule) {
        for (int i = 1; i <= 3; i++) {
            List<String> mine = new ArrayList<>();
            oneCase(ctx, mine, label, rule);
            boolean premise = !mine.isEmpty() && mine.stream().allMatch(f -> f.contains("the premise failed"));
            if (!premise || i == 3) {
                failures.addAll(mine);
                return;
            }
            println(label + ": " + mine + " - a fresh floor (" + (i + 1) + " of 3)");
        }
    }

    private static void oneCase(ClientGameTestContext ctx, List<String> failures, String label, boolean rule) {
        // ---- a floor with a leaf mob room whose one door leads to another mob room, and a third mob room off that way ----
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
            failures.add(label + ": no generated floor in 8 had a leaf mob room off another mob room and a third mob room");
            return;
        }
        final Setup setup = s;

        // ---- loadout: AOTV for the trips, Hyperion; no key of any kind ----
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
                String[] ids = {"ASPECT_OF_THE_VOID", "HYPERION"};
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
        // On for the premise check (the control turns it off only after it, and a case before may have left it off).
        ctx.runOnClient(mc -> ModUnderTest.staticCall(KEY_ROOMS, "testSetEnabled", new Class<?>[]{boolean.class},
                new Object[]{true}));

        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(20);

        // ---- every other mob room cleared ----
        List<String> keep = List.of(setup.leaf(), setup.key(), setup.other());
        for (SimAutoClearTests.Room r : rooms) {
            if (r.mob() && !keep.contains(r.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
            }
        }
        ctx.waitTicks(5);

        // ---- the wither door, the mobs, the key room ----
        int doors = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "witherDoorsAround",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, setup.leaf()}));
        ctx.waitTicks(40);
        List<SimAutoClearTests.Placed> placed = new ArrayList<>();
        for (String name : keep) {
            SimAutoClearTests.Room r = rooms.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            placed.addAll(SimAutoClearTests.spawnIn(ctx, r, 1, false));
        }
        ctx.waitTicks(30);
        @SuppressWarnings("unchecked")
        List<String> guards = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(KEY_ROOMS, "testGuards"));
        boolean armed = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_KEY_ROOM, "armForTest",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, setup.key()}));
        println(label + ": " + doors + " wither door(s) round " + setup.leaf() + "; key room " + setup.key() + ", unguarded "
                + setup.other() + "; guards " + guards + "; sim key room armed " + armed + "; placed " + placed);
        String keyGuard = setup.key() + "|" + setup.door() + "|wither|false";
        if (doors != 1 || !guards.contains(keyGuard) || !armed || placed.size() != 3) {
            failures.add(label + ": the premise failed (" + doors + " wither door(s), guards " + guards + ", wanted "
                    + keyGuard + ", armed " + armed + ", " + placed.size() + " mob(s) placed)");
            return;
        }
        for (String g : guards) {
            if (g.startsWith(setup.other() + "|")) {
                failures.add(label + ": the premise failed - " + setup.other() + " is a key room too (" + g + ")");
                return;
            }
        }
        if (!rule) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(KEY_ROOMS, "testSetEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
        }

        // ---- Auto Clear: Any Mob Room, Hyperion ----
        int doors0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "doorsRemaining"));
        int walkIns0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "keyRoomWalkIns"));
        int trips0 = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "keyRoomApproachTrips"));
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            ModUnderTest.set(cfg, "setPickUpKeys", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "HYPERION");
        long mark = LogTap.mark();
        PacketWatch.start();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        boolean finished = false;
        int ticks = 0;
        int waitingSince = -1;
        for (int t = 0; t < CASE_TICKS; t++) {
            ctx.waitTicks(1);
            ticks = t + 1;
            if (!(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "isBusy"))) {
                finished = true;
                break;
            }
            if (!rule && waitingSince < 0) {
                for (String l : LogTap.since(mark)) {
                    if (l.contains("with no key - waiting")) {
                        waitingSince = t;
                        break;
                    }
                }
            }
            if (waitingSince >= 0 && t - waitingSince > 60) {
                break;   // control: at the door with no key, as without the rule; nothing more will happen
            }
            if (t % 200 == 199) {
                println(label + " t=" + (t + 1) + ": " + ctx.computeOnClient(mc -> ModUnderTest.staticCall(AUTO, "status")));
            }
        }
        if (!finished) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        }
        PacketWatch.stop();
        int uses = PacketWatch.itemUses();
        int interacts = PacketWatch.entityInteracts();
        int walkIns = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "keyRoomWalkIns")) - walkIns0;
        int trips = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(AUTO, "keyRoomApproachTrips")) - trips0;
        String state = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(SIM_KEY_ROOM, "testState"));
        int doorsLeft = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_DOORS, "doorsRemaining"));
        Map<UUID, String> alive = SimAutoClearTests.aliveReport(ctx, placed);
        List<SimAutoClearTests.Room> after = SimAutoClearTests.floor(ctx);

        String lastLine = null;
        boolean keyObtained = false;
        boolean keyPicked = false;
        boolean doorClicked = false;
        boolean doorOpened = false;
        boolean otherTrip = false;
        boolean otherWalk = false;
        boolean keyWalk = false;
        boolean insta = false;
        boolean onFoot = false;
        boolean dropped = false;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[AutoClear]") || l.contains("Sim key room") || l.contains("Wither Key") || l.contains("WITHER door")
                    || l.contains("[Sim] room cleared")) {
                println("  log: " + l.replaceAll("^.*?(\\[AutoClear\\]|\\[Sim\\]|Sim key room|\\[CHAT\\])", "$1"));
            }
            keyObtained |= l.matches(".*\\b[A-Za-z0-9_]{1,16} has obtained Wither Key!.*");
            keyPicked |= l.contains("[AutoClear] key: Wither Key #") && l.contains("picked up");
            doorClicked |= l.contains("clicked wither door");
            doorOpened |= l.contains("opened a WITHER door!");
            // Reached by an etherwarp: a room trip, or the room's clock line counting a trip with warp uses in it.
            otherTrip |= l.contains("[AutoClear] trip to room " + setup.other() + " ")
                    || l.contains("[AutoClear] trip to room " + setup.other() + ":")
                    || l.matches(".*\\[AutoClear\\] room time: " + java.util.regex.Pattern.quote(setup.other())
                    + " .*trips [1-9][0-9]* \\([0-9]+ ticks, [1-9][0-9]* warp use.*");
            otherWalk |= l.contains("[AutoClear] key room: walking into " + setup.other() + " ");
            keyWalk |= l.contains("[AutoClear] key room: walking into " + setup.key() + " ");
            insta |= l.contains("Sim key room: " + setup.key() + " insta cleared");
            onFoot |= l.contains("Sim key room: " + setup.key() + " entered on foot");
            dropped |= l.contains("Sim key room: " + setup.key() + "'s last starred mob died");
            if (l.contains("[AutoClear] done:") || l.contains("[AutoClear] stopped:")) {
                lastLine = l.replaceAll("^.*\\[AutoClear\\] ", "");
            }
        }
        println(String.format(Locale.ROOT, "%s: %s after %d ticks; uses %d, entity interactions %d; key room state %s; "
                        + "walk-ins %d, door trips %d; sim: insta %s, on foot %s, key dropped %s; key obtained %s, picked %s;"
                        + " door clicked %s, opened %s, wither doors left %d; %s by trip %s, by key walk %s; mobs %s; last %s",
                label, finished ? "finished" : "stopped", ticks, uses, interacts, state, walkIns, trips, insta, onFoot, dropped,
                keyObtained, keyPicked, doorClicked, doorOpened, doorsLeft, setup.other(), otherTrip, otherWalk, alive,
                lastLine));
        String[] st = state == null ? new String[0] : state.split("\\|");
        String enteredBy = st.length > 2 ? st[2] : null;
        boolean lost = st.length > 3 && st[3].equals("true");
        boolean keyDropped = st.length > 4 && st[4].equals("true");
        if (interacts > 0) {
            failures.add(label + ": " + interacts + " entity interaction packet(s)");
        }
        if (uses == 0) {
            failures.add(label + ": no use-item packet at all - nothing ran");
        }
        if (!rule) {
            // The control must show the loss the rule is there to prevent, or the next case proves nothing.
            if (!"teleport".equals(enteredBy) || !lost || !insta) {
                failures.add("control: without the rule " + setup.key() + " was not insta cleared by an etherwarp (entered by "
                        + enteredBy + ", lost " + lost + ") - the scenario cannot tell the rule's work apart");
            }
            if (keyObtained || keyDropped) {
                failures.add("control: a Wither Key dropped or was obtained with the key room insta cleared");
            }
            if (walkIns != 0) {
                failures.add("control: " + walkIns + " key-room walk(s) with the rule off");
            }
            return;
        }
        // (a) never warped into, not insta cleared
        if (!"walk".equals(enteredBy) || lost || insta) {
            failures.add("keyroom: " + setup.key() + " was entered by " + enteredBy + (lost || insta ? " and insta cleared -"
                    + " the key is lost" : ""));
        }
        if (!onFoot || !keyWalk || walkIns < 1) {
            failures.add("keyroom: no walk into " + setup.key() + " (sim on foot " + onFoot + ", walk line " + keyWalk
                    + ", walk-ins " + walkIns + ")");
        }
        // (b) cleared by its mob
        for (SimAutoClearTests.Placed p : placed) {
            if (!"dead".equals(alive.get(p.id()))) {
                failures.add("keyroom: the starred mob in " + p.room() + " is " + alive.get(p.id()));
            }
        }
        for (SimAutoClearTests.Room r : after) {
            if (keep.contains(r.name()) && !r.cleared()) {
                failures.add("keyroom: " + r.name() + " never read cleared");
            }
        }
        // (c) the key: dropped, obtained, the door opened with it, the room behind it cleared
        if (!keyDropped || !dropped) {
            failures.add("keyroom: " + setup.key() + "'s Wither Key never dropped");
        }
        // The obtained line is the server's; Auto Clear's own pickup trip is only needed when the key drops out of reach.
        if (!keyObtained) {
            failures.add("keyroom: the Wither Key was not picked up (no 'has obtained Wither Key!' line; Auto Clear's"
                    + " pickup trip " + keyPicked + ")");
        }
        // The server's own line (SimDoors.doorsRemaining keeps a door registered after it opens).
        if (!doorClicked || !doorOpened) {
            failures.add("keyroom: the wither door was not opened with the key (clicked " + doorClicked + ", opened line "
                    + doorOpened + "; " + doorsLeft + " of " + doors0 + " sim doors registered)");
        }
        // (d) the unguarded room still by an etherwarp trip, no key-room walk for it
        if (!otherTrip || otherWalk) {
            failures.add("keyroom: " + setup.other() + " (no key in it) was not reached as before (trip line " + otherTrip
                    + ", key-room walk " + otherWalk + ")");
        }
        if (!finished || lastLine == null || !lastLine.contains("nothing left")) {
            failures.add("keyroom: did not finish with 'nothing left' (" + lastLine + ")");
        }
    }

    /**
     * A leaf mob room X whose one (ordinary) door leads to a mob room K, neither the room he stands in nor the Entrance,
     * and a third mob room O whose room path from him does not cross K or X. Null when the floor has none.
     */
    @SuppressWarnings("unchecked")
    private static Setup pick(ClientGameTestContext ctx, List<SimAutoClearTests.Room> rooms) {
        List<String> pairs = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(KEY_ROOMS, "testLeafPairs"));
        String here = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int cur = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
            return (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{cur});
        });
        List<String> guarded = new ArrayList<>();
        for (String g : ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(KEY_ROOMS, "testGuards"))) {
            guarded.add(g.split("\\|")[0]);   // already a key room (the blood door's, say): not the unguarded one
        }
        List<String> mob = new ArrayList<>();
        for (SimAutoClearTests.Room r : rooms) {
            // Trap rooms are left out of tests until the owner says otherwise (CLAUDE.md).
            if (r.mob() && !r.cleared() && !r.name().contains("Trap") && !"TRAP".equalsIgnoreCase(r.type())) {
                mob.add(r.name());
            }
        }
        for (String p : pairs) {
            String[] f = p.split("\\|");
            String leaf = f[0];
            String key = f[1];
            if (!mob.contains(leaf) || !mob.contains(key) || leaf.equals(here) || key.equals(here)) {
                continue;
            }
            for (String other : mob) {
                if (other.equals(leaf) || other.equals(key) || other.equals(here) || guarded.contains(other)) {
                    continue;
                }
                List<String> way = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(KEY_ROOMS,
                        "testRoomPath", new Class<?>[]{String.class, String.class}, new Object[]{here, other}));
                if (!way.isEmpty() && !way.contains(key) && !way.contains(leaf)) {
                    return new Setup(leaf, key, other, Integer.parseInt(f[2]));
                }
            }
        }
        return null;
    }

    static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
