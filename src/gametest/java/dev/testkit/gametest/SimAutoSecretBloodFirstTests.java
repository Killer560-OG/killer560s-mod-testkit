package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonObject;

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
 *             nothing is handed to Auto Clear (R's zombie is left); afterwards both routes run; Blood First ends "done".</li>
 *         <li><b>full blood rush</b>: R is handed to Auto Clear ("Blood First (Full Blood Rush): clearing R") and is
 *             cleared on the map, then the blood door is opened, all before any secret target; then both routes run.</li>
 *         <li><b>off</b>: the first thing it does is a secret target, it never goes to the blood door and the door stays
 *             shut; both routes run.</li>
 *       </ol></li>
 *   <li><b>366-logic-secret-bloodfirst</b>: the setting round-trips save + load for each value, steps Off > Door Camp >
 *       Full Blood Rush > Off (and back the other way), and the Auto Clear tab has it as a cycling button.</li>
 * </ul>
 * The sim case only when named (several minutes).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoSecretBloodFirstTests implements FabricClientGameTest {

    private static final String NAME = "148-sim-autosecret-bloodfirst";
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
    private static final String MODE = AS_CONFIG + "$SecretBloodFirst";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final int CASE_TICKS = 20 * 240;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Scenario.skip(LOGIC)) {
            ModUnderTest.require("killer560smod");
            logicCase(ctx);
        }
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
            modeBefore[0] = ModUnderTest.call(as, "getSecretBloodFirst", new Class<?>[]{}, new Object[]{});
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
        List<String> failures = new ArrayList<>();
        try {
            for (String mode : List.of("DOOR_CAMP", "FULL_BLOOD_RUSH", "OFF")) {
                try {
                    oneMode(ctx, mode, failures);
                } catch (Throwable t) {
                    failures.add(mode + ": threw " + t);
                    t.printStackTrace(System.out);
                } finally {
                    stopAll(ctx);
                }
            }
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMobs", "setClearRoomsOnEntry",
                    new Class<?>[]{boolean.class}, new Object[]{true}));
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
                setMode(ModUnderTest.config(AS_CONFIG), modeBefore[0]);
            });
            SimAutopilotTests.writeRoutes(ctx, Map.of());
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            leaveSim(ctx);
        }
        for (String f : failures) {
            println("FAIL: " + f);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
        println("PASS");
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
            setMode(ModUnderTest.config(AS_CONFIG), ModUnderTest.enumValue(MODE, mode));
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

        // ---- the run ---------------------------------------------------------------------------------------------
        long mark = LogTap.mark();
        Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
        if (!Boolean.TRUE.equals(started)) {
            failures.add(tag + ": Auto Secret refused to start");
            return;
        }
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        double travelled = 0;
        int ticks = 0;
        boolean ended = false;
        for (; ticks < CASE_TICKS; ticks += 5) {
            ctx.waitTicks(5);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            travelled += now.distanceTo(last);
            last = now;
            List<String> log = LogTap.since(mark);
            boolean allRouted = routes.keySet().stream().allMatch(n -> log.stream().anyMatch(
                    l -> l.contains("[AutoSecret] route " + n + " finished")));
            if (allRouted || !(Boolean) ctx.computeOnClient(mc -> ModUnderTest.staticCall(AS, "isRunning"))) {
                ended = true;
                break;
            }
        }
        String state = ctx.computeOnClient(mc -> (String) ModUnderTest.staticCall(AS, "bloodFirstState"));
        stopAll(ctx);
        ctx.waitTicks(5);
        List<String> log = LogTap.since(mark);
        for (String l : log) {
            if (l.contains("[AutoSecret]") || l.contains("[AutoClear] done") || l.contains("[AutoClear] stopped")
                    || l.contains("[Autopilot]")) {
                String s = l.replaceAll("^.*?(\\[Autopilot\\]|\\[AutoSecret\\]|\\[AutoClear\\])", "$1");
                println("  " + (s.length() > 400 ? s.substring(0, 400) + "..." : s));
            }
        }
        boolean bloodOpen = ctx.computeOnClient(mc -> mc.level.getBlockState(bloodLock).isAir());
        boolean rCleared = Boolean.TRUE.equals(SimAutopilotTests.cleared(ctx).get(roomR.name()));
        int firstTarget = indexOf(log, "[AutoSecret] target ");
        int bloodTrip = indexOf(log, "[AutoSecret] going to the blood door");
        int bloodOpened = indexOf(log, "blood door at cell " + bloodCell + " is open");
        int clearR = indexOf(log, "Blood First (Full Blood Rush): clearing " + roomR.name());
        int handed = indexOf(log, "[AutoSecret] handing ");
        int rFinished = 0;
        for (String n : routes.keySet()) {
            int f = indexOf(log, "[AutoSecret] route " + n + " finished");
            if (f < 0) {
                failures.add(tag + ": the route in " + n + " never finished");
            } else if (bloodOpened >= 0 && f < bloodOpened) {
                failures.add(tag + ": the route in " + n + " ran before the blood door opened");
            } else {
                rFinished++;
            }
        }
        println(String.format(Locale.US, "%s: %.1f s, travelled %.1f blocks, ended %s; first target line %d, blood trip %d,"
                        + " blood open line %d (lock air %s), clear R line %d, first hand-over %d, R cleared %s, routes done %d,"
                        + " Blood First state %s", tag, ticks / 20.0, travelled, ended, firstTarget, bloodTrip, bloodOpened,
                bloodOpen, clearR, handed, rCleared, rFinished, state));
        if (travelled < 10) {
            failures.add(tag + ": he moved only " + String.format(Locale.US, "%.1f", travelled) + " blocks - nothing ran");
        }
        switch (mode) {
            case "DOOR_CAMP" -> {
                if (bloodTrip < 0 || bloodOpened < 0 || !bloodOpen) {
                    failures.add(tag + ": the blood door was not opened (trip " + bloodTrip + ", open line " + bloodOpened
                            + ", lock air " + bloodOpen + ")");
                }
                if (firstTarget >= 0 && (bloodOpened < 0 || firstTarget < bloodOpened)) {
                    failures.add(tag + ": a secret room was targeted before the blood door opened");
                }
                if (handed >= 0 || rCleared) {
                    failures.add(tag + ": it cleared a room (hand-over line " + handed + ", R cleared " + rCleared
                            + ") - Door Camp only opens doors");
                }
                if (!"DOOR_CAMP done".equals(state)) {
                    failures.add(tag + ": Blood First state " + state + ", want DOOR_CAMP done");
                }
            }
            case "FULL_BLOOD_RUSH" -> {
                if (clearR < 0 || !rCleared) {
                    failures.add(tag + ": R (" + roomR.name() + ") was not cleared first (line " + clearR + ", cleared "
                            + rCleared + ")");
                }
                if (bloodTrip < 0 || bloodOpened < 0 || !bloodOpen) {
                    failures.add(tag + ": the blood door was not opened (trip " + bloodTrip + ", open line " + bloodOpened
                            + ", lock air " + bloodOpen + ")");
                }
                if (clearR >= 0 && bloodTrip >= 0 && clearR > bloodTrip) {
                    failures.add(tag + ": it went to the blood door before clearing R");
                }
                if (firstTarget >= 0 && (bloodOpened < 0 || firstTarget < bloodOpened)) {
                    failures.add(tag + ": a secret room was targeted before the blood rush was done");
                }
                if (!"FULL_BLOOD_RUSH done".equals(state)) {
                    failures.add(tag + ": Blood First state " + state + ", want FULL_BLOOD_RUSH done");
                }
            }
            default -> {
                if (firstTarget < 0) {
                    failures.add(tag + ": no secret target at all");
                }
                if (bloodTrip >= 0 || bloodOpen) {
                    failures.add(tag + ": it went to / opened the blood door with Blood First off");
                }
                if (log.stream().anyMatch(l -> l.contains("[AutoSecret] Blood First"))) {
                    failures.add(tag + ": a Blood First line with it off");
                }
                if (!"OFF".equals(state)) {
                    failures.add(tag + ": Blood First state " + state + ", want OFF");
                }
            }
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
        ModUnderTest.call(cfg, "setSecretBloodFirst", new Class<?>[]{mode.getClass()}, new Object[]{mode});
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
            Object before = ModUnderTest.call(ModUnderTest.config(AS_CONFIG), "getSecretBloodFirst", new Class<?>[]{},
                    new Object[]{});
            try {
                for (String v : List.of("DOOR_CAMP", "FULL_BLOOD_RUSH", "OFF")) {
                    Object cfg = ModUnderTest.config(AS_CONFIG);
                    setMode(cfg, ModUnderTest.enumValue(MODE, v));
                    ModUnderTest.call(cfg, "save", new Class<?>[]{}, new Object[]{});
                    ModUnderTest.staticCall(AS_CONFIG, "load");
                    Object back = ModUnderTest.call(ModUnderTest.config(AS_CONFIG), "getSecretBloodFirst",
                            new Class<?>[]{}, new Object[]{});
                    System.out.println("[" + LOGIC + "] saved " + v + ", loaded " + back);
                    if (!v.equals(String.valueOf(back))) {
                        problems.add("saved " + v + " and loaded " + back);
                    }
                }
                String[][] steps = {{"OFF", "1", "DOOR_CAMP"}, {"DOOR_CAMP", "1", "FULL_BLOOD_RUSH"},
                        {"FULL_BLOOD_RUSH", "1", "OFF"}, {"OFF", "-1", "FULL_BLOOD_RUSH"}, {"DOOR_CAMP", "-1", "OFF"}};
                for (String[] s : steps) {
                    Object got = ModUnderTest.staticCall("com.killer560.hub.gui.SettingsButtonWidget", "step",
                            new Class<?>[]{Enum.class, int.class},
                            new Object[]{ModUnderTest.enumValue(MODE, s[0]), Integer.parseInt(s[1])});
                    System.out.println("[" + LOGIC + "] step " + s[0] + " " + s[1] + " -> " + got);
                    if (!s[2].equals(String.valueOf(got))) {
                        problems.add("step " + s[0] + " by " + s[1] + " gave " + got + ", want " + s[2]);
                    }
                }
                Object tab = Class.forName("com.killer560.hub.gui.tab.AutoClearTab").getConstructor().newInstance();
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) ModUnderTest.call(tab, "buildWidgets",
                        new Class<?>[]{int.class, int.class, int.class, Runnable.class},
                        new Object[]{0, 0, 300, (Runnable) () -> { }});
                AbstractWidget row = null;
                for (AbstractWidget w : ws) {
                    if (w.getMessage().getString().startsWith("Secret Blood First: ")) {
                        row = w;
                    }
                }
                boolean cycling = row != null && (Boolean) ModUnderTest.call(row, "isCycling", new Class<?>[]{},
                        new Object[]{});
                System.out.println("[" + LOGIC + "] tab row: " + (row == null ? "none" : row.getMessage().getString())
                        + ", cycling " + cycling + " (" + ws.size() + " widget(s))");
                if (!cycling) {
                    problems.add("the Auto Clear tab has no cycling \"Secret Blood First\" button");
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
