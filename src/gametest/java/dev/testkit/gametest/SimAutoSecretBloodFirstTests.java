package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonObject;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auto Secret's Secret Blood First (mod autosecret-bloodfirst-1017). killer560, 2026-10-17: "also for auto secret add a
 * blood first where you can select door camp or full blood rush first then it does only secrets."
 *
 * <ul>
 *   <li><b>148-sim-autosecret-bloodfirst</b>: one generated sim F7 per mode, in 141's setup: every mob room marked cleared
 *       but R (an uncleared mob room on the Blood Rush Split path, one starred zombie), one-node routes seeded into two
 *       secret rooms OFF the rush path, Auto Clear switched on (so Door Camp could use it and must not).
 *       <ol>
 *         <li><b>door camp</b>: it goes to the blood door and opens it (the lock block is air) before any secret target;
 *             nothing is handed to Auto Clear before the door is open (afterwards the Room Plan may clear R as a tour stop);
 *             both routes run; Blood First ends "done".</li>
 *         <li><b>full blood rush</b>: R is handed to Auto Clear ("Blood First (Full Blood Rush): clearing R") and is
 *             cleared on the map, then the blood door is opened, all before any secret target; then both routes run.</li>
 *         <li><b>off</b>: the first thing it does is a secret target, it never goes to the blood door and the door stays
 *             shut; both routes run.</li>
 *       </ol></li>
 *   <li><b>366-logic-secret-bloodfirst</b>: the setting round-trips save + load for each value, steps Off > Door Camp >
 *       Full Blood Rush > Off (and back the other way), and the Auto Clear tab has it as a cycling button.</li>
 * </ul>
 * The sim case only when named (several minutes).
 * <p>apmenu-1121 (killer560 2026-11-21): Auto Secret's Blood First became Dungeons Autopilot's Blood Rush, so the floor run
 * is the card's now, one row per part, started through the card (DungeonsAutopilot), Mode Solo 300:
 * <ul>
 *   <li><b>1121-sim-apmenu-rush-keystop</b>: Blood Rush Door started by the one Toggle Keybind; pressed again mid-rush
 *       (Auto Clear's Door Only running, Blood door still shut) it switches everything off and nothing more happens.</li>
 *   <li><b>1121-sim-apmenu-rush-door</b>: Door + Disable After Blood Rush: the Blood door opens (lock block air), R stays
 *       uncleared, no mob is killed, then the card switches itself off and the secrets never start.</li>
 *   <li><b>1121-sim-apmenu-rush-clear</b>: Clear + Disable After Blood Rush: R (on the rush path) is cleared before the
 *       Blood door opens, then the door part runs, the door opens and the card switches itself off.</li>
 *   <li><b>1121-sim-apmenu-rush-none</b>: None: no rush at all (Auto Clear never started for one), the secrets start at
 *       once and both routes run; the Blood door stays shut.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoSecretBloodFirstTests implements FabricClientGameTest {

    private static final String NAME = "148-sim-autosecret-bloodfirst";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String KEYBINDS = "com.killer560.hub.gui.ModuleKeybinds";
    private static final int RUSH_KEY = 326;   // KP_6, free in the test client
    /** The rows: mode -> case name. */
    private static final Map<String, String> ROWS = new LinkedHashMap<>(Map.of());
    static {
        ROWS.put("KEYSTOP", "1121-sim-apmenu-rush-keystop");
        ROWS.put("DOOR", "1121-sim-apmenu-rush-door");
        ROWS.put("CLEAR", "1121-sim-apmenu-rush-clear");
        ROWS.put("NONE", "1121-sim-apmenu-rush-none");
    }
    private static final List<String> rowFailures = new ArrayList<>();
    private static final String LOGIC = "366-logic-secret-bloodfirst";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_ROOM_STATE = "com.killer560.hub.roomsim.SimRoomState";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AR_CONFIG = "com.killer560.hub.autoroutes.AutoRoutesConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String MODE = AS_CONFIG + "$BloodRush"; // apmenu-1121: Secret Blood First folded into Blood Rush
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final int CASE_TICKS = 20 * 240;

    private static boolean cheatBuild() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Scenario.skip(LOGIC)) {
            ModUnderTest.require("killer560smod");
            logicCase(ctx);
        }
        List<String> modes = new ArrayList<>();
        if (!System.getProperty("testkit.scenario", "").isBlank()) {
            ROWS.forEach((m, n) -> {
                if (Scenario.named(n)) {
                    modes.add(m);
                }
            });
        }
        if (modes.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            modes.forEach(m -> Scenario.skipped(ROWS.get(m), "legit jar: no Dungeons Autopilot"));
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            modes.forEach(m -> Scenario.skipped(ROWS.get(m), "needs real room captures and room database"));
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        Object[] modeBefore = new Object[1];
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object ar = ModUnderTest.config(AR_CONFIG);
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            ModUnderTest.call(ar, "setHeight", new Class<?>[]{float.class}, new Object[]{1.0f});
            Object as = ModUnderTest.config(AS_CONFIG);
            modeBefore[0] = ModUnderTest.call(as, "getBloodRush", new Class<?>[]{}, new Object[]{});
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(as, "setRouteStallSeconds", 10);
            ModUnderTest.set(as, "setPuzzleWaitSeconds", 0);
            ModUnderTest.set(as, "setInstaClear", false);
            ModUnderTest.set(as, "setIceFillFirst", false);
            ModUnderTest.set(as, "setAutoClearRooms", false);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        // R is cleared by killing its zombie, and which rooms got cleared is judged: no clear on entry (as 141).
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobs", "setClearRoomsOnEntry",
                new Class<?>[]{boolean.class}, new Object[]{false}));
        try {
            for (String mode : modes) {
                List<String> failures = new ArrayList<>();
                // A trip the map planned across a room it had not identified yet can land in the floor's trap room, where
                // no etherwarp works and Auto Secret stops by design (preexisting-1026: Museum -> Overgrown landed in New
                // Trap). Trap rooms are not functional yet (killer560): that floor measured nothing about Blood First, so
                // the mode runs again on a new floor, three floors at most.
                for (int floorTry = 1; floorTry <= 3; floorTry++) {
                    try {
                        oneMode(ctx, mode, failures);
                        break;
                    } catch (TrapStop t) {
                        println(t.getMessage() + (floorTry < 3 ? " - a new floor" : ""));
                        if (floorTry == 3) {
                            failures.add(t.getMessage() + " on three floors running");
                        }
                    } catch (Throwable t) {
                        failures.add(mode + ": threw " + t);
                        t.printStackTrace(System.out);
                        break;
                    } finally {
                        stopAll(ctx);
                    }
                }
                row(ROWS.get(mode), failures);
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobs", "setClearRoomsOnEntry",
                    new Class<?>[]{boolean.class}, new Object[]{true}));
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
                setMode(ModUnderTest.config(AS_CONFIG), modeBefore[0]);
                ModUnderTest.set(ModUnderTest.config(AS_CONFIG), "setDisableAfterBloodRush", false);
                ModUnderTest.staticCall(KEYBINDS, "set", new Class<?>[]{String.class, int.class}, new Object[]{"AutoClearTab", -1});
            });
            SimAutopilotTests.writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            leaveSim(ctx);
        }
        if (!rowFailures.isEmpty()) {
            throw new AssertionError(rowFailures.size() + " row(s) failed: " + String.join("; ", rowFailures));
        }
    }

    private static void row(String name, List<String> failures) {
        boolean pass = failures.isEmpty();
        String detail = pass ? "acted as asserted" : String.join("; ", failures);
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            rowFailures.add(name + ": " + detail);
        }
    }

    // ================================================================================================ one mode

    private static void oneMode(ClientGameTestContext ctx, String mode, List<String> failures) {
        String tag = mode.toLowerCase(Locale.ROOT).replace('_', ' ');
        // ---- a floor with an uncleared mob room on the blood rush and two routable secret rooms off it ----------
        List<SimAutopilotTests.Room> rooms = null;
        SimAutopilotTests.Room rush = null;
        List<SimAutopilotTests.Status> secretRooms = new ArrayList<>();
        Map<String, JsonObject> routes = new LinkedHashMap<>();
        for (int attempt = 1; attempt <= 5; attempt++) {
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
            SimAutopilotTests.awaitFloorChunks(ctx);
            rooms = SimAutopilotTests.floor(ctx);
            rush = null;
            List<String> onRush = new ArrayList<>();
            for (SimAutopilotTests.Room r : rooms) {
                if (r.rush()) {
                    onRush.add(r.name());
                }
                if (rush == null && r.rush() && r.mob() && !r.cleared() && !SimAutopilotTests.strands(r.name())) {
                    rush = r;
                }
            }
            secretRooms.clear();
            routes.clear();
            for (SimAutopilotTests.Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
                if (secretRooms.size() >= 2) {
                    break;
                }
                if (s.unfound() <= 0 || onRush.contains(s.name()) || "Unknown".equals(s.name())
                        || SimAutopilotTests.strands(s.name())
                        || List.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE", "FAIRY").contains(s.type())) {
                    continue;
                }
                JsonObject route = SimAutopilotTests.routeFor(ctx, s);
                // Only a stand some warp lands ON: a start node's warp is exact, and a stand only reached by a near
                // landing never arms ("landed, but its start node did not arm - skipped"), which is the planner's
                // business, not Blood First's (147's rule; Silver Sword in preexisting-1026's final run).
                if (route != null) {
                    net.minecraft.core.BlockPos stand = SimAutopilotTests.standOf(ctx, s);
                    Vec3 here = ctx.computeOnClient(mc -> mc.player.position());
                    if (stand == null || !SimAutoSecretOptimizeTests.landsOn(ctx, here, stand)) {
                        println(tag + ": " + s.name() + " left out - no warp lands exactly on its stand " + stand);
                        route = null;
                    }
                }
                if (route != null) {
                    secretRooms.add(s);
                    routes.put(s.name(), route);
                }
            }
            println(tag + ": floor " + attempt + ": rush path " + onRush + "; R " + (rush == null ? "none" : rush.name())
                    + "; routable secret rooms off it " + routes.keySet());
            if (rush != null && routes.size() == 2) {
                break;
            }
            rush = null;
        }
        if (rush == null) {
            failures.add(tag + ": no generated floor in 5 had an uncleared rush mob room and two routable secret rooms off"
                    + " the rush path");
            return;
        }
        SimAutopilotTests.Room roomR = rush;

        // ---- loadout, the run begins, every other mob room cleared, R's zombie, routes ------------------------
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
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(40);
        SimAutopilotTests.ground(ctx);
        for (SimAutopilotTests.Room r : rooms) {
            if (r.mob() && !r.name().equals(roomR.name())) {
                ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_ROOM_STATE, "markCleared",
                        new Class<?>[]{String.class}, new Object[]{r.name()}));
            }
        }
        SimAutopilotTests.writeRoutes(ctx, routes);
        UUID zombie = SimAutopilotTests.spawnOne(ctx, roomR);
        if (zombie == null) {
            failures.add(tag + ": could not place a starred zombie in " + roomR.name());
            return;
        }
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            Object w = ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{w.getClass()}, new Object[]{w});
            Object as = ModUnderTest.config(AS_CONFIG);
            setMode(as, ModUnderTest.enumValue(MODE, "KEYSTOP".equals(mode) ? "DOOR" : mode));
            Object solo = ModUnderTest.enumValue(AS_CONFIG + "$Mode", "SOLO_300");
            ModUnderTest.call(as, "setMode", new Class<?>[]{solo.getClass()}, new Object[]{solo});
            ModUnderTest.set(as, "setDisableAfterBloodRush", "DOOR".equals(mode) || "CLEAR".equals(mode));
            ModUnderTest.set(as, "setBloodFirst", false);
            ModUnderTest.staticCall(KEYBINDS, "set", new Class<?>[]{String.class, int.class}, new Object[]{"AutoClearTab", RUSH_KEY});
        });
        int bloodCell = ctx.computeOnClient(mc -> (Integer) ModUnderTest.call(ModUnderTest.staticCall(LAYOUT, "capture"),
                "bloodDoor", new Class<?>[]{}, new Object[]{}));
        if (bloodCell < 0) {
            failures.add(tag + ": the floor has no blood door on the map");
            return;
        }
        BlockPos bloodLock = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(LAYOUT, "doorBlock",
                new Class<?>[]{int.class}, new Object[]{bloodCell}));
        boolean shutAtStart = ctx.computeOnClient(mc -> !mc.level.getBlockState(bloodLock).isAir());
        println(tag + ": R=" + roomR.name() + ", routes " + routes.keySet() + ", blood door cell " + bloodCell + " lock "
                + bloodLock.toShortString() + " shut " + shutAtStart);
        if (!shutAtStart) {
            failures.add(tag + ": the blood door was already open at the start");
            return;
        }
        SimAutopilotTests.ground(ctx);

        // ---- the run: started through the card ------------------------------------------------------------------
        long mark = LogTap.mark();
        boolean keystop = "KEYSTOP".equals(mode);
        if (keystop) {
            ctx.getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(RUSH_KEY), 3);
        } else {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        }
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        double travelled = 0;
        int ticks = 0;
        boolean ended = false;
        boolean pressedOff = false;
        boolean openWhenPressed = false;
        int doorSeen = -1;
        for (; ticks < CASE_TICKS; ticks += 5) {
            ctx.waitTicks(5);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            travelled += now.distanceTo(last);
            last = now;
            List<String> log = LogTap.since(mark);
            boolean on = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PILOT, "isOn"));
            if (keystop) {
                // mid-rush: Door Only has run 2 s and the Blood door is still shut
                if (doorSeen < 0 && indexOf(log, "[AutoClear] started: Door Only") >= 0) {
                    doorSeen = ticks;
                }
                if (!pressedOff && doorSeen >= 0 && ticks >= doorSeen + 40) {
                    openWhenPressed = ctx.computeOnClient(mc -> mc.level.getBlockState(bloodLock).isAir());
                    ctx.getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(RUSH_KEY), 3);
                    pressedOff = true;
                    ctx.waitTicks(20);
                    ended = true;
                    break;
                }
                if (!on && ticks > 40) {
                    break;
                }
                continue;
            }
            if ("NONE".equals(mode)) {
                boolean allRouted = routes.keySet().stream().allMatch(n -> log.stream().anyMatch(
                        l -> l.contains("[AutoSecret] route " + n + " finished")));
                if (allRouted || !on) {
                    ended = true;
                    break;
                }
            } else if (!on) {
                ended = true;
                break;
            }
        }
        boolean onAfter = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PILOT, "isOn"));
        boolean clearBusy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO_CLEAR, "isBusy"));
        boolean asRunning = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        if (keystop) {
            ctx.waitTicks(60);   // nothing more may happen after the key
        }
        boolean shutLater = ctx.computeOnClient(mc -> !mc.level.getBlockState(bloodLock).isAir());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false}));
        stopAll(ctx);
        ctx.waitTicks(5);
        List<String> log = LogTap.since(mark);
        for (String l : log) {
            if (l.contains("[AutoSecret]") || l.contains("[AutoClear]") || l.contains("[Autopilot]")
                    || l.contains("[DungeonsAutopilot]") || l.contains("BLOOD DOOR")) {
                String s = l.replaceAll("^.*?(\\[Autopilot\\]|\\[AutoSecret\\]|\\[AutoClear\\]|\\[DungeonsAutopilot\\])", "$1");
                println("  " + (s.length() > 300 ? s.substring(0, 300) + "..." : s));
            }
        }
        for (String l : log) {
            if (l.contains("[AutoSecret] stopped: in New Trap") || l.contains("[AutoSecret] stopped: in Old Trap")) {
                throw new TrapStop(tag + ": the trip landed in a trap room and Auto Secret stopped there");
            }
        }
        boolean bloodOpen = ctx.computeOnClient(mc -> mc.level.getBlockState(bloodLock).isAir());
        boolean rCleared = Boolean.TRUE.equals(SimAutopilotTests.cleared(ctx).get(roomR.name()));
        int onLine = indexOf(log, "[DungeonsAutopilot] on: mode SOLO_300, blood rush " + ("KEYSTOP".equals(mode) ? "DOOR" : mode));
        int doorRun = indexOf(log, "[AutoClear] started: Door Only");
        int clearRun = indexOf(log, "[AutoClear] started: Blood Rush");
        int anyClearRun = indexOf(log, "[AutoClear] started: ");
        int rClearedLine = indexOf(log, "room cleared: " + roomR.name());
        int kills = (int) log.stream().filter(l -> l.contains("starred kill(s) this run")).count();
        int secretsStart = indexOf(log, "[AutoSecret] started");
        int firstTarget = indexOf(log, "[AutoSecret] target ");
        int disabledLine = indexOf(log, "[DungeonsAutopilot] off: Disable After Blood Rush");
        int keyOffLine = indexOf(log, "[DungeonsAutopilot] off: you switched it off");
        println(String.format(Locale.US, "%s: %.1f s, traveled %.1f blocks, ended %s; on line %d, door run %d, clear run %d, R cleared"
                        + " %s (line %d), kills %d, secrets started %d, first target %d, Disable off %d, key off %d, blood open %s,"
                        + " on after %s, Auto Clear busy %s, Auto Secret running %s", tag, ticks / 20.0, travelled, ended, onLine,
                doorRun, clearRun, rCleared, rClearedLine, kills, secretsStart, firstTarget, disabledLine, keyOffLine, bloodOpen,
                onAfter, clearBusy, asRunning));
        if (onLine < 0) {
            failures.add(tag + ": the card never switched on with this Blood Rush");
        }
        switch (mode) {
            case "KEYSTOP" -> {
                if (!pressedOff || doorRun < 0) {
                    failures.add(tag + ": the Door rush never got going (door run line " + doorRun + "), nothing to stop");
                }
                if (openWhenPressed) {
                    failures.add(tag + ": the Blood door was already open when the key was pressed - not mid-rush");
                }
                if (keyOffLine < 0 || onAfter || clearBusy || asRunning) {
                    failures.add(tag + ": the key did not stop it (off line " + keyOffLine + ", on " + onAfter + ", Auto Clear busy "
                            + clearBusy + ", Auto Secret running " + asRunning + ")");
                }
                if (!shutLater || secretsStart >= 0) {
                    failures.add(tag + ": something ran after the key (Blood door shut " + shutLater + ", secrets line " + secretsStart + ")");
                }
            }
            case "DOOR" -> {
                if (doorRun < 0 || !bloodOpen) {
                    failures.add(tag + ": the Door rush did not open the Blood door (door run " + doorRun + ", lock air " + bloodOpen + ")");
                }
                if (clearRun >= 0 || rCleared || kills > 0) {
                    failures.add(tag + ": Door cleared a room (Blood Rush clear run " + clearRun + ", R cleared " + rCleared
                            + ", starred kills " + kills + ")");
                }
                if (disabledLine < 0 || onAfter) {
                    failures.add(tag + ": Disable After Blood Rush did not switch it off (line " + disabledLine + ", on " + onAfter + ")");
                }
                if (secretsStart >= 0 || firstTarget >= 0) {
                    failures.add(tag + ": the secrets started after the rush with Disable After Blood Rush on");
                }
                if (travelled < 10) {
                    failures.add(tag + ": he moved only " + String.format(Locale.US, "%.1f", travelled) + " blocks - nothing ran");
                }
            }
            case "CLEAR" -> {
                if (clearRun < 0 || !rCleared || rClearedLine < 0 || kills < 1) {
                    failures.add(tag + ": R (" + roomR.name() + ", on the rush path) was not cleared by the rush (clear run "
                            + clearRun + ", cleared " + rCleared + ", line " + rClearedLine + ", kills " + kills + ")");
                }
                if (doorRun < 0 || doorRun < clearRun || !bloodOpen) {
                    failures.add(tag + ": the door part did not follow the clear and open the Blood door (door run " + doorRun
                            + ", clear run " + clearRun + ", lock air " + bloodOpen + ")");
                }
                if (disabledLine < 0 || onAfter) {
                    failures.add(tag + ": Disable After Blood Rush did not switch it off (line " + disabledLine + ", on " + onAfter + ")");
                }
                if (secretsStart >= 0) {
                    failures.add(tag + ": the secrets started after the rush with Disable After Blood Rush on");
                }
            }
            default -> {
                if (anyClearRun >= 0 && (secretsStart < 0 || anyClearRun < secretsStart)) {
                    failures.add(tag + ": an Auto Clear rush ran before the secrets with Blood Rush None: " + strip(log.get(anyClearRun)));
                }
                if (secretsStart < 0 || firstTarget < 0) {
                    failures.add(tag + ": the secrets never started (line " + secretsStart + ", first target " + firstTarget + ")");
                }
                for (String n : routes.keySet()) {
                    if (indexOf(log, "[AutoSecret] route " + n + " finished") < 0) {
                        failures.add(tag + ": the route in " + n + " never finished");
                    }
                }
                if (bloodOpen) {
                    failures.add(tag + ": the Blood door opened with Blood Rush None");
                }
            }
        }
    }

    private static String strip(String l) {
        return l.replaceAll("^.*?\\] ", "");
    }

    /** The run stopped inside the floor's trap room: nothing about Blood First was measured. */
    private static final class TrapStop extends RuntimeException {
        TrapStop(String message) {
            super(message);
        }
    }

    private static int indexOf(List<String> log, String needle) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static void setMode(Object cfg, Object mode) {
        if (mode == null) {
            return;
        }
        ModUnderTest.call(cfg, "setBloodRush", new Class<?>[]{mode.getClass()}, new Object[]{mode});
    }

    private static void stopAll(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                        new Object[]{"test over", true});
            }
            ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
        });
    }

    private static void leaveSim(ClientGameTestContext ctx) {
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

    // ================================================================================================ logic

    /** The setting: save + load for each value, the cycling steps, and the tab's cycling button. */
    private static void logicCase(ClientGameTestContext ctx) {
        List<String> problems = new ArrayList<>();
        ctx.runOnClient(mc -> {
            Object before = ModUnderTest.call(ModUnderTest.config(AS_CONFIG), "getBloodRush", new Class<?>[]{},
                    new Object[]{});
            try {
                for (String v : List.of("DOOR", "CLEAR", "NONE")) {
                    Object cfg = ModUnderTest.config(AS_CONFIG);
                    setMode(cfg, ModUnderTest.enumValue(MODE, v));
                    ModUnderTest.call(cfg, "save", new Class<?>[]{}, new Object[]{});
                    ModUnderTest.staticCall(AS_CONFIG, "load");
                    Object back = ModUnderTest.call(ModUnderTest.config(AS_CONFIG), "getBloodRush",
                            new Class<?>[]{}, new Object[]{});
                    System.out.println("[" + LOGIC + "] saved " + v + ", loaded " + back);
                    if (!v.equals(String.valueOf(back))) {
                        problems.add("saved " + v + " and loaded " + back);
                    }
                }
                String[][] steps = {{"NONE", "1", "CLEAR"}, {"CLEAR", "1", "DOOR"},
                        {"DOOR", "1", "NONE"}, {"NONE", "-1", "DOOR"}, {"CLEAR", "-1", "NONE"}};
                for (String[] s : steps) {
                    Object got = ModUnderTest.staticCall("com.killer560.hub.gui.SettingsButtonWidget", "step",
                            new Class<?>[]{Enum.class, int.class},
                            new Object[]{ModUnderTest.enumValue(MODE, s[0]), Integer.parseInt(s[1])});
                    System.out.println("[" + LOGIC + "] step " + s[0] + " " + s[1] + " -> " + got);
                    if (!s[2].equals(String.valueOf(got))) {
                        problems.add("step " + s[0] + " by " + s[1] + " gave " + got + ", want " + s[2]);
                    }
                }
                if (cheatBuild()) { // the Auto Clear tab is cheat-only: a legit jar has no such row
                    Object tab = Class.forName("com.killer560.hub.gui.tab.AutoClearTab").getConstructor().newInstance();
                    @SuppressWarnings("unchecked")
                    List<AbstractWidget> ws = (List<AbstractWidget>) ModUnderTest.call(tab, "buildWidgets",
                            new Class<?>[]{int.class, int.class, int.class, Runnable.class},
                            new Object[]{0, 0, 300, (Runnable) () -> { }});
                    AbstractWidget row = null;
                    for (AbstractWidget w : ws) {
                        if (w.getMessage().getString().startsWith("Blood Rush: ")) {
                            row = w;
                        }
                    }
                    boolean cycling = row != null && (Boolean) ModUnderTest.call(row, "isCycling", new Class<?>[]{},
                            new Object[]{});
                    System.out.println("[" + LOGIC + "] tab row: " + (row == null ? "none" : row.getMessage().getString())
                            + ", cycling " + cycling + " (" + ws.size() + " widget(s))");
                    if (!cycling) {
                        problems.add("the Dungeons Autopilot tab has no cycling \"Blood Rush\" button");
                    }
                } else {
                    System.out.println("[" + LOGIC + "] legit jar: tab row skipped (cheat-only tab)");
                }
            } catch (ReflectiveOperationException e) {
                problems.add("threw " + e);
            } finally {
                Object cfg = ModUnderTest.config(AS_CONFIG);
                setMode(cfg, before);
                ModUnderTest.call(cfg, "save", new Class<?>[]{}, new Object[]{});
            }
        });
        for (String p : problems) {
            System.out.println("[" + LOGIC + "] FAIL: " + p);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        System.out.println("[" + LOGIC + "] PASS");
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
