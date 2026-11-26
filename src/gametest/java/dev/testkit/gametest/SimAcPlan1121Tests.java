package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon Autopilot's room plan (acplan-1121, killer560 2026-11-21: Solo 300 "plan clear vs secrets per room to reach score
 * 300 fastest" instead of clearing the whole map and then touring it again for secrets; Party Secrets the same planning;
 * "add an option to clear room before secreting it").
 * <ul>
 *   <li>1121-logic-acplan-fastest: the planner on made-up rooms on a line: Solo visits each room once (clear, then its
 *   route) and takes only the secrets the need asks for; Party goes for its secrets in the least-travel order with no
 *   clears, or with Clear Room Before Secreting each secret room's clear right before its route; the two fixed orders.</li>
 *   <li>1121-sim-acplan-solo: a whole Solo run on 1118-sim-acbloodfirst's fixed F7 with a one-node route in every secret
 *   room (each route's secrets credited when it is picked). Since autopilot-menu-1126 (Clear First folded into Blood
 *   First off) the default Solo 300 run clears every mob room before any route, each room cleared once and secreted once.
 *   The run's ticks to the finish are printed for the base-vs-branch comparison.</li>
 *   <li>1126-sim-autopilot-instaclear (autopilot-menu-1126): the same floor twice with one mob room (no key, off the blood
 *   rush path) armed as an insta room the map has not shown: with Allow Insta Clearing ON the decide line credits that
 *   room's insta clear and the tour differs from the OFF run, no blood rush room is ever credited, and the ON run is not
 *   slower to the finish (ticks of both printed).</li>
 *   <li>1121-sim-acplan-party-clearfirst: the same floor in Party with Clear Room Before Secreting on: every secret pick in
 *   a mob room comes right after that room's clear, at least two such visits, none secreted before its clear.</li>
 *   <li>1121-sim-acplan-party-secrets: Party with it off: no clear is picked before the last secret route.</li>
 * </ul>
 */
public class SimAcPlan1121Tests implements FabricClientGameTest {
    private static final String LOGIC = "1121-logic-acplan-fastest";
    private static final String SOLO = "1121-sim-acplan-solo";
    private static final String PARTY_ON = "1121-sim-acplan-party-clearfirst";
    private static final String PARTY_OFF = "1121-sim-acplan-party-secrets";
    private static final String CLEAR_THEN = "1121-sim-acplan-clearthensecret";
    private static final String CLEAR_FIRST = "1121-sim-acplan-clearfirst";
    private static final String SKIP_BLOOD = "1121-sim-acplan-skipblood";
    /** Insta Clear First (killer560 2026-11-21): a known insta entry whose warp tile is a room next to the target with
     *  no door between them (his 11-21 log: every known entry read "none matched", the from-room filter wanted a door
     *  path of 1-2 rooms). Plain Auto Secret with Insta Clear on must take that entry before anything else. */
    private static final String INSTA = "1121-sim-acplan-insta-warptile";
    private static final String INSTA_AP = "1126-sim-autopilot-instaclear";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String PATHFINDER = "com.killer560.hub.livemap.autoclear.DungeonMapPathfinder";
    private static final String TELEPORT_UTILS = "com.killer560.hub.livemap.autoclear.TeleportUtils";
    private static final String INSTA_TRACKER = "com.killer560.hub.autosecret.InstaClearTracker";
    private static final String KEY_ROOMS = "com.killer560.hub.autoclear.KeyRooms";
    /** The sim's floor line: its wither key rooms (a Party clear of one opens the door to more routes). */
    private static final Pattern SIM_KEY_ROOMS = Pattern.compile("\\[Sim\\] floor with mobs: .*?; key rooms (.+?); fairy door");
    private static void readKeyRooms(List<String> lines, Set<String> out) {
        for (String l : lines) {
            Matcher kr = SIM_KEY_ROOMS.matcher(l);
            if (kr.find()) {
                for (String k : kr.group(1).split(", ")) {
                    if (k.endsWith(" (wither)")) {
                        out.add(k.substring(0, k.length() - " (wither)".length()));
                    }
                }
            }
        }
    }

    private static final Pattern SKIPPED = Pattern.compile("\\[AutoSecret\\] (.+?) is the room in front of the blood door - its"
            + " secrets are skipped");
    private static final String PLANNER = "com.killer560.hub.autosecret.AutopilotPlanner";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AC_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String SCORE_CONFIG = "com.killer560.hub.scorecalc.ScoreCalculatorConfig";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String STORE = "com.killer560.hub.autoroutes.RouteStore";
    private static final Pattern PICK = Pattern.compile("\\| pick (SECRET|CLEAR|PUZZLE|EXPLORE) (.+?) -?[\\d.]+/[\\d.]+s=");
    private static final Pattern SCORE = Pattern.compile("decide \\([A-Z_+]+\\): score (\\d+) ");
    private final List<String> all = new ArrayList<>();
    // 1126-sim-autopilot-instaclear: the run's Allow Insta Clearing, the armed room, and what one() saw.
    private boolean instaFor;
    private String armFor;
    private List<String> rushFor = List.of();
    private List<String[]> lastPicks = List.of();
    private int lastTick;
    private final List<String> instaLines = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Scenario.skip(LOGIC)) {
            ModUnderTest.require("killer560smod");
            logic(ctx);
        }
        List<String> sims = new ArrayList<>();
        for (String n : new String[]{SOLO, PARTY_ON, PARTY_OFF, CLEAR_THEN, CLEAR_FIRST, SKIP_BLOOD, INSTA, INSTA_AP}) {
            if (!System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(n)) {
                sims.add(n);
            }
        }
        if (!sims.isEmpty()) {
            if (!dev.testkit.gametest.mod.Mod.isCheat()) {
                sims.forEach(n -> Scenario.skipped(n, "Auto Clear is cheat build only"));
            } else {
                sim(ctx, sims);
            }
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    // ============================================================================================== logic

    private void logic(ClientGameTestContext ctx) {
        List<String> problems = new ArrayList<>();
        try {
            ctx.runOnClient(mc -> {
                // Rooms on a line: A at 1 s, B 2, C 3, D 10; a trip costs the gap.
                Map<String, Double> at = Map.of("A", 1.0, "B", 2.0, "C", 3.0, "D", 10.0);
                Object travel = java.lang.reflect.Proxy.newProxyInstance(SimAcPlan1121Tests.class.getClassLoader(),
                        new Class<?>[]{cls(PLANNER + "$Travel")}, (proxy, m, args) -> switch (m.getName()) {
                            case "fromHere" -> at.get((String) args[0]);
                            case "between" -> Math.abs(at.get((String) args[0]) - at.get((String) args[1]));
                            default -> null;
                        });
                List<Object> cands = new ArrayList<>();
                for (String r : List.of("A", "B", "C", "D")) {
                    cands.add(cand("CLEAR", r, 5.0, at.get(r) + 3));
                }
                // A and C: 2 score in 1 s of route; D: 3 score in 6 s (2 s a point, the dearest).
                cands.add(cand("SECRET", "A", 2.0, 1 + 1));
                cands.add(cand("SECRET", "C", 2.0, 3 + 1));
                cands.add(cand("SECRET", "D", 3.0, 10 + 6));
                // Solo needing 4 of secret score: A's and C's routes cover it; D's is never needed. One visit per room,
                // clear before route, in line order.
                check(problems, "solo, need 4", tour(fastest(false, cands, travel, 4.0, false)),
                        "CLEAR A,SECRET A,CLEAR B,CLEAR C,SECRET C,CLEAR D");
                // Party, need met nowhere (100): every secret, least travel, no clears.
                check(problems, "party, clears off", tour(fastest(true, cands, travel, 100.0, false)),
                        "SECRET A,SECRET C,SECRET D");
                check(problems, "party, clear before secreting", tour(fastest(true, cands, travel, 100.0, true)),
                        "CLEAR A,SECRET A,CLEAR C,SECRET C,CLEAR D,SECRET D");
                check(problems, "clear then secret", tour(ordered(true, cands, travel, false)),
                        "CLEAR A,CLEAR B,CLEAR C,CLEAR D");
                check(problems, "secret then clear", tour(ordered(false, cands, travel, false)),
                        "SECRET A,SECRET C,SECRET D");
                check(problems, "secret then clear, clear before secreting", tour(ordered(false, cands, travel, true)),
                        "CLEAR A,SECRET A,CLEAR C,SECRET C,CLEAR D,SECRET D");
                // Clear First holds the clears phase while a clear is left, then hands back to the mode.
                Class<?>[] sig = {List.class};
                check(problems, "clear first, clears left", String.valueOf(ModUnderTest.staticCall(PLANNER, "clearsLeft", sig,
                        new Object[]{cands})), "true");
                check(problems, "clear first, only routes left", String.valueOf(ModUnderTest.staticCall(PLANNER,
                        "clearsLeft", sig, new Object[]{cands.subList(4, 7)})), "false");
            });
        } catch (Throwable t) {
            problems.add("threw: " + t);
        }
        row(LOGIC, problems.isEmpty(), problems.isEmpty() ? "every plan as expected" : String.join("; ", problems));
    }

    private static Object fastest(boolean party, List<Object> cands, Object travel, double need, boolean clearFirst) {
        return ModUnderTest.staticCall(PLANNER, "chooseFastest", new Class<?>[]{boolean.class, List.class,
                cls(PLANNER + "$Travel"), double.class, boolean.class, long.class},
                new Object[]{party, cands, travel, need, clearFirst, 50_000_000L});
    }

    private static Object ordered(boolean clearsFirst, List<Object> cands, Object travel, boolean clearFirst) {
        return ModUnderTest.staticCall(PLANNER, "chooseOrdered", new Class<?>[]{boolean.class, List.class,
                cls(PLANNER + "$Travel"), boolean.class, long.class},
                new Object[]{clearsFirst, cands, travel, clearFirst, 50_000_000L});
    }

    private static String tour(Object choice) {
        if (choice == null) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        for (Object k : (List<?>) ModUnderTest.call(choice, "tour", new Class<?>[]{}, new Object[]{})) {
            out.add(ModUnderTest.call(k, "kind", new Class<?>[]{}, new Object[]{}) + " "
                    + ModUnderTest.call(k, "room", new Class<?>[]{}, new Object[]{}));
        }
        return String.join(",", out);
    }

    private static void check(List<String> problems, String what, String got, String want) {
        System.out.println("[" + LOGIC + "] " + what + ": " + got + (got.equals(want) ? "" : " (want " + want + ")"));
        if (!got.equals(want)) {
            problems.add(what + ": got " + got + ", want " + want);
        }
    }

    private static Object cand(String kind, String room, double gain, double seconds) {
        try {
            return cls(PLANNER + "$Candidate").getConstructor(cls(PLANNER + "$Kind"), String.class, double.class,
                    double.class, boolean.class, int.class).newInstance(ModUnderTest.enumValue(PLANNER + "$Kind", kind),
                    room, gain, seconds, false, -1);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    // ============================================================================================== sim runs

    private void sim(ClientGameTestContext ctx, List<String> cases) {
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            cases.forEach(n -> Scenario.skipped(n, "needs real room captures and room database"));
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        boolean scoreWasOn = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(ModUnderTest.config(SCORE_CONFIG), "isEnabled"));
        Path routesFile = ctx.computeOnClient(mc -> (Path) ModUnderTest.staticCall(STORE, "routesFile"));
        String routesBefore = read(routesFile);
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(ModUnderTest.config(SCORE_CONFIG), "setEnabled", true);
        });
        String floorBefore = System.getProperty("testkit.mobfloorCode", "");
        try {
            if (floorBefore.isBlank()) {
                System.setProperty("testkit.mobfloorCode", SimAcBloodFirst1118Tests.FIXED_FLOOR);
            }
            for (String c : cases) {
                try {
                    if (c.equals(INSTA)) {
                        insta(ctx);
                    } else if (c.equals(INSTA_AP)) {
                        instaAp(ctx);
                    } else {
                        one(ctx, c);
                    }
                } catch (Throwable t) {
                    t.printStackTrace(System.out);
                    row(c, false, "threw: " + t);
                } finally {
                    stop(ctx);
                    SimMobFloorTests.leave(ctx);
                }
            }
        } finally {
            if (floorBefore.isBlank()) {
                System.clearProperty("testkit.mobfloorCode");
            }
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.set(ModUnderTest.config(AC_CONFIG), "setEnabled", false);
                    Object as = ModUnderTest.config(AS_CONFIG);
                    ModUnderTest.set(as, "setClearBeforeSecret", false);
                    setEnum(as, "setMode", "Mode", "SOLO_300");
                    setEnum(as, "setRoomBeforeBlood", "RoomBeforeBlood", "SAVE_FOR_LAST");
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
                ModUnderTest.set(ModUnderTest.config(SCORE_CONFIG), "setEnabled", scoreWasOn);
                ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
                mc.options.renderDistance().set(renderBefore);
                write(routesFile, routesBefore);
                ModUnderTest.staticCall(STORE, "reload");
            });
        }
    }

    private static void stop(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true});
            } catch (Throwable ignored) {
                // cleanup
            }
        });
    }

    private void one(ClientGameTestContext ctx, String name) {
        List<String> problems = new ArrayList<>();
        SimMobFloorTests.buildMobFloor(ctx, problems, new ArrayList<>());
        if (ctx.computeOnClient(mc -> mc.level == null)) {
            row(name, false, "the floor did not load: " + problems);
            return;
        }
        // A one-node route in every room with secrets (the run picks them like real routes).
        Map<String, JsonObject> routes = new HashMap<>();
        Map<String, Integer> unfound = new HashMap<>();
        for (SimAutopilotTests.Status s : ctx.computeOnClient(SimAutopilotTests::readStatus)) {
            if (s.unfound() <= 0 || SimAutopilotTests.strands(s.name())) {
                continue;
            }
            JsonObject route = SimAutopilotTests.routeFor(ctx, s);
            if (route != null) {
                routes.put(s.name(), route);
                unfound.put(s.name(), s.unfound());
            }
        }
        SimAutopilotTests.writeRoutes(ctx, routes);
        boolean party = name.equals(PARTY_ON) || name.equals(PARTY_OFF);
        ctx.runOnClient(mc -> {
            // The Dungeons Autopilot settings (apmenu-1121). A jar from before them runs Solo and Party Secrets on the
            // old Run Mode (the before numbers); the cases that need a new setting fail here on it.
            Object as = ModUnderTest.config(AS_CONFIG);
            boolean menu = java.util.Arrays.stream(as.getClass().getMethods()).anyMatch(m -> m.getName().equals("setMode"));
            if (menu) {
                setEnum(as, "setMode", "Mode", party ? "PARTY_SECRETS" : name.equals(CLEAR_THEN) ? "CLEAR_THEN_SECRET" : "SOLO_300");
                setEnum(as, "setBloodRush", "BloodRush", "NONE");
                setEnum(as, "setRoomBeforeBlood", "RoomBeforeBlood", name.equals(SKIP_BLOOD) ? "SKIP" : "SAVE_FOR_LAST");
                if (name.equals(INSTA_AP)) {
                    ModUnderTest.set(as, "setInstaClear", instaFor);
                }
            } else if (name.equals(SOLO) || name.equals(PARTY_OFF)) {
                setEnum(as, "setRunMode", "RunMode", party ? "PARTY" : "SOLO");
            } else {
                throw new IllegalStateException("this jar has no Dungeons Autopilot Mode / Clear First / Room Before Blood");
            }
            ModUnderTest.set(as, "setBloodFirst", false);
            ModUnderTest.set(as, "setChatFeedback", true);
            // The routes written above run only with Auto Routes on (first run: "Auto Routes is off - no secret routes this
            // run", every case 14 clears and 0 secrets).
            Object ar = ModUnderTest.config("com.killer560.hub.autoroutes.AutoRoutesConfig");
            ModUnderTest.set(ar, "setEnabled", true);
            ModUnderTest.set(ar, "setLegitMode", false);
            ModUnderTest.set(ar, "setStartFromStartNodeOnly", true);
            if (menu || name.equals(PARTY_ON)) {
                ModUnderTest.set(as, "setClearBeforeSecret", name.equals(PARTY_ON));
            }
            ModUnderTest.set(ModUnderTest.config(AC_CONFIG), "setEnabled", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "hasStarted"), 400);
        instaLines.clear();
        if (name.equals(INSTA_AP)) {
            String armed = ctx.computeOnClient(mc -> armInsta(armFor));
            if (armed == null) {
                row(name, false, "no mob room off the blood rush path and without a key to arm (the case could not act)");
                lastPicks = null;
                return;
            }
            armFor = armed;
        }
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "startAutopilot"));
        List<String[]> picks = new ArrayList<>();   // {kind, room, tick}
        Set<String> skipped = new HashSet<>();
        Set<String> keyRooms = new HashSet<>();
        Set<Integer> keyClears = new HashSet<>();   // CLEAR picks it said go first for want of a Wither Key
        boolean keyNote = false;
        readKeyRooms(LogTap.between(-1, mark), keyRooms);   // the floor line came before the start
        int score = -1;
        int score300 = -1;
        int tick = 0;
        int minutes = Integer.getInteger("testkit.acplanMinutes", 7);
        boolean started = false;
        for (; tick < minutes * 1200; tick++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                break;
            }
            long upTo = LogTap.mark();
            for (String l : LogTap.between(mark, upTo)) {
                Matcher sm = SCORE.matcher(l);
                if (sm.find()) {
                    score = Integer.parseInt(sm.group(1));
                    if (score >= 300 && score300 < 0) {
                        score300 = tick;
                    }
                }
                Matcher sk = SKIPPED.matcher(l);
                if (sk.find()) {
                    skipped.add(sk.group(1));
                }
                if (l.contains("insta clear") || l.contains("Sim insta room")) {
                    instaLines.add(l);
                }
                if (l.contains("[AutoSecret] autopilot: no Wither Key for the door")) {
                    keyNote = true;
                }
                Matcher m = PICK.matcher(l);
                if (m.find()) {
                    String kind = m.group(1);
                    String room = m.group(2);
                    if (keyNote && kind.equals("CLEAR")) {
                        keyClears.add(picks.size());
                    }
                    keyNote = false;
                    picks.add(new String[]{kind, room, String.valueOf(tick)});
                    Integer n = kind.equals("SECRET") ? unfound.remove(room) : null;
                    if (n != null) {
                        // The route stands in for finding them: the room's secrets count from now on.
                        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_SCORE, "creditRoomSecrets",
                                new Class<?>[]{String.class, int.class}, new Object[]{room, n}));
                    }
                }
            }
            mark = upTo;
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
            started |= running;
            if (started && !running) {
                break;
            }
            if (name.equals(PARTY_OFF) && !picks.isEmpty() && !picks.get(picks.size() - 1)[0].equals("SECRET")
                    && unfound.isEmpty()) {
                break;   // every route taken; the clears after them are not this case
            }
        }
        stop(ctx);
        // Visits: runs of consecutive picks in one room; a room with two visits was toured twice.
        List<String> visits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> twice = new ArrayList<>();
        String prev = null;
        for (String[] p : picks) {
            if (!p[1].equals(prev)) {
                if (!seen.add(p[1])) {
                    twice.add(p[1] + "@" + p[2]);
                }
                visits.add(p[1]);
                prev = p[1];
            }
        }
        int secrets = (int) picks.stream().filter(p -> p[0].equals("SECRET")).count();
        int clears = (int) picks.stream().filter(p -> p[0].equals("CLEAR")).count();
        String seq = String.join(" > ", picks.stream().map(p -> p[0].charAt(0) + ":" + p[1]).toList());
        String did = String.format(Locale.US, "%d pick(s) (%d clear, %d secret) in %d visit(s), %.1f s, score %d, 300 at %s",
                picks.size(), clears, secrets, visits.size(), tick / 20.0, score, score300 < 0 ? "-" : (score300 / 20.0) + " s");
        System.out.println("[" + name + "] RESULT: " + did + "; routes " + routes.size() + "; picks " + seq);
        boolean acted = secrets >= 3;
        lastPicks = picks;
        lastTick = tick;
        switch (name) {
            case INSTA_AP -> {
                // judged by instaAp over two runs
            }
            case CLEAR_THEN, CLEAR_FIRST, SOLO -> {
                int lastClear = -1;
                int firstSecret = Integer.MAX_VALUE;
                for (int i = 0; i < picks.size(); i++) {
                    if (picks.get(i)[0].equals("CLEAR")) {
                        lastClear = i;
                    } else if (picks.get(i)[0].equals("SECRET")) {
                        firstSecret = Math.min(firstSecret, i);
                    }
                }
                // Acted: several clears and then routes; no route before the last clear; no room cleared or secreted twice.
                List<String> again = new ArrayList<>();
                Set<String> once = new HashSet<>();
                for (String[] p : picks) {
                    if (!p[0].equals("EXPLORE") && !once.add(p[0] + " " + p[1])) {
                        again.add(p[0] + " " + p[1]);
                    }
                }
                row(name, clears >= 3 && secrets >= 1 && lastClear < firstSecret && again.isEmpty(), (lastClear < firstSecret
                        ? "no route" : "a route") + " before the last clear, picked twice " + again + "; " + did);
            }
            case SKIP_BLOOD -> {
                List<String> bad = picks.stream().filter(p -> p[0].equals("SECRET") && skipped.contains(p[1]))
                        .map(p -> p[1]).toList();
                // Acted: the skip was logged for a room that had secrets, other routes ran, and that room got none.
                row(name, acted && !skipped.isEmpty() && bad.isEmpty(), "skipped " + skipped + ", secreted anyway " + bad
                        + "; " + did);
            }
            case PARTY_ON -> {
                Set<String> cleared = new HashSet<>();
                int pairs = 0;
                List<String> early = new ArrayList<>();
                Set<String> secreted = new HashSet<>();
                for (int i = 0; i < picks.size(); i++) {
                    String[] p = picks.get(i);
                    if (p[0].equals("CLEAR")) {
                        cleared.add(p[1]);
                        if (secreted.contains(p[1])) {
                            early.add(p[1]);
                        }
                    } else if (p[0].equals("SECRET")) {
                        secreted.add(p[1]);
                        if (i > 0 && picks.get(i - 1)[0].equals("CLEAR") && picks.get(i - 1)[1].equals(p[1])) {
                            pairs++;
                        }
                    }
                }
                row(name, acted && pairs >= 2 && early.isEmpty(), pairs + " clear-then-route visit(s), rooms secreted"
                        + " before their clear " + early + "; " + did);
            }
            default -> {
                int lastSecret = -1;
                int firstClear = Integer.MAX_VALUE;
                for (int i = 0; i < picks.size(); i++) {
                    if (picks.get(i)[0].equals("SECRET")) {
                        lastSecret = i;
                    } else if (picks.get(i)[0].equals("CLEAR") && !keyRooms.contains(picks.get(i)[1])
                            && !keyClears.contains(i)) {
                        // A wither key room's clear is the key to more routes (no teammate opens the door in the sim).
                        firstClear = Math.min(firstClear, i);
                    }
                }
                row(name, acted && firstClear > lastSecret, (firstClear > lastSecret ? "no clear" : "a clear")
                        + " before the last route (key room clears allowed: " + keyRooms + ", key-wanted clears " + keyClears.size() + "); " + did);
            }
        }
    }

    /** 1126-sim-autopilot-instaclear: the fixed floor with Allow Insta Clearing OFF, then ON, one insta room armed. */
    private void instaAp(ClientGameTestContext ctx) {
        armFor = null;
        instaFor = false;
        one(ctx, INSTA_AP);
        if (lastPicks == null) {
            return;
        }
        List<String[]> off = lastPicks;
        int offTick = lastTick;
        stop(ctx);
        SimMobFloorTests.leave(ctx);
        instaFor = true;
        one(ctx, INSTA_AP);
        if (lastPicks == null) {
            return;
        }
        List<String[]> on = lastPicks;
        int onTick = lastTick;
        String offSeq = String.join(">", off.stream().map(p -> p[0].charAt(0) + ":" + p[1]).toList());
        String onSeq = String.join(">", on.stream().map(p -> p[0].charAt(0) + ":" + p[1]).toList());
        boolean credited = instaLines.stream().anyMatch(l -> l.contains("insta clear " + armFor + " ("));
        List<String> rushCredited = new ArrayList<>();
        for (String l : instaLines) {
            for (String r : rushFor) {
                if (l.contains("insta clear " + r + " (")) {
                    rushCredited.add(r);
                }
            }
        }
        String said = instaLines.stream().filter(l -> l.contains("Sim insta room")).findFirst().orElse("no sim insta clear");
        System.out.println("[" + INSTA_AP + "] armed " + armFor + "; rush " + rushFor + "; off " + offTick + " ticks: "
                + offSeq + "; on " + onTick + " ticks: " + onSeq + "; " + said);
        // Acted: ON credited the armed room's insta clear and toured differently; never a rush room; not slower (5 s slack
        // for sim noise between two runs of the same floor).
        row(INSTA_AP, credited && !onSeq.equals(offSeq) && rushCredited.isEmpty() && onTick <= offTick + 100,
                "armed " + armFor + (credited ? " credited" : " NOT credited") + ", tour " + (onSeq.equals(offSeq) ? "UNCHANGED"
                        : "changed") + ", rush rooms credited " + rushCredited + ", ticks off " + offTick + " on " + onTick);
    }

    /** Client thread: arm {@code want} (or, when null, the largest mob room with no key, off the blood rush path and not
     *  the room the player stands in) as an insta room; also fills {@link #rushFor}. @return its name, or null. */
    private String armInsta(String want) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        List<?> rush = (List<?>) ModUnderTest.staticCall("com.killer560.hub.autoclear.AutoClearFeature", "bloodRushRooms",
                new Class<?>[]{layout.getClass()}, new Object[]{layout});
        List<SimAutopilotTests.Status> rooms = SimAutopilotTests.readStatus(net.minecraft.client.Minecraft.getInstance());
        List<String> rushNames = new ArrayList<>();
        String best = want;
        int bestTiles = 0;
        for (SimAutopilotTests.Status r : rooms) {
            if (rush != null && rush.contains(r.id())) {
                rushNames.add(r.name());
                continue;
            }
            if (want != null || r.id() == here || r.name() == null || r.name().equals("Unknown")
                    || SimAutopilotTests.strands(r.name())
                    || java.util.Set.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE", "FAIRY").contains(String.valueOf(r.type()).toUpperCase(Locale.ROOT))
                    || ModUnderTest.staticCall(KEY_ROOMS, "guardOf", new Class<?>[]{layout.getClass(), int.class},
                    new Object[]{layout, r.id()}) != null) {
                continue;
            }
            int tiles = ((int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r.id()})).length;
            if (tiles > bestTiles) {
                best = r.name();
                bestTiles = tiles;
            }
        }
        rushFor = rushNames;
        if (best == null) {
            return null;
        }
        boolean ok = (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimKeyRoom", "armInstaForTest",
                new Class<?>[]{net.minecraft.client.Minecraft.class, String.class},
                new Object[]{net.minecraft.client.Minecraft.getInstance(), best});
        return ok ? best : null;
    }

    private void insta(ClientGameTestContext ctx) {
        List<String> problems = new ArrayList<>();
        SimMobFloorTests.buildMobFloor(ctx, problems, new ArrayList<>());
        if (ctx.computeOnClient(mc -> mc.level == null)) {
            row(INSTA, false, "the floor did not load: " + problems);
            return;
        }
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "hasStarted"), 400);
        String[] seeded = ctx.computeOnClient(mc -> seedWarpTile());
        if (seeded == null) {
            row(INSTA, false, "no target room with a non-door neighbor on this floor (the case could not act)");
            return;
        }
        System.out.println("[" + INSTA + "] seeded " + seeded[0] + " key " + seeded[1] + " from " + seeded[2]);
        boolean[] was = new boolean[1];
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config(AS_CONFIG);
            was[0] = ModUnderTest.getBoolean(as, "isInstaClear");
            ModUnderTest.set(as, "setInstaClear", true);
            ModUnderTest.set(as, "setBloodFirst", false);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(ModUnderTest.config(AC_CONFIG), "setEnabled", false);
        });
        long mark = LogTap.mark();
        String took = null;
        String miss = null;
        try {
            Boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "start"));
            for (int t = 0; t < 400 && took == null && Boolean.TRUE.equals(started); t++) {
                ctx.waitTicks(1);
                for (String l : LogTap.since(mark)) {
                    if (l.contains("[AutoSecret] insta clear " + seeded[0] + ": entry " + seeded[1])) {
                        took = l;
                    } else if (l.contains("[AutoSecret] insta clear " + seeded[0] + ":")) {
                        miss = l;
                    }
                }
            }
        } finally {
            stop(ctx);
            ctx.runOnClient(mc -> {
                ModUnderTest.set(ModUnderTest.config(AS_CONFIG), "setInstaClear", was[0]);
                ModUnderTest.staticCall(INSTA_TRACKER, "testUseFile", new Class<?>[]{String.class}, new Object[]{null});
            });
        }
        // Acted: the run chose the seeded entry, warping in from the neighbor across the wall.
        row(INSTA, took != null, took != null ? "took " + seeded[0] + " from " + seeded[2]
                : "never took the entry" + (miss != null ? "; said: " + miss.substring(miss.indexOf("[AutoSecret]")) : ""));
    }

    /** Client thread: a reachable target room with a reachable neighbor (tiles side by side) that the door map does not
     *  join within two rooms, seeded as a known insta entry from that neighbor. @return {room, key, from} or null. */
    private static String[] seedWarpTile() {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int here = (Integer) ModUnderTest.call(layout, "currentRoom", new Class<?>[]{}, new Object[]{});
        List<SimAutopilotTests.Status> rooms = SimAutopilotTests.readStatus(net.minecraft.client.Minecraft.getInstance());
        for (SimAutopilotTests.Status r : rooms) {
            if (r.id() == here || r.name() == null || r.name().equals("Unknown") || SimAutopilotTests.strands(r.name())
                    || java.util.Set.of("PUZZLE", "TRAP", "BLOOD", "ENTRANCE", "FAIRY").contains(String.valueOf(r.type()).toUpperCase(Locale.ROOT))
                    || path(layout, here, r.id()) < 0
                    || ModUnderTest.staticCall(KEY_ROOMS, "guardOf", new Class<?>[]{layout.getClass(), int.class},
                    new Object[]{layout, r.id()}) != null) {
                continue;
            }
            int[] rt = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r.id()});
            for (SimAutopilotTests.Status f : rooms) {
                if (f.id() == r.id() || f.name() == null || f.name().equals("Unknown") || path(layout, here, f.id()) < 0) {
                    continue;
                }
                int steps = path(layout, f.id(), r.id());
                if (steps >= 1 && steps <= 2) {
                    continue;
                }
                int[] ft = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{f.id()});
                boolean side = false;
                for (int a : ft) {
                    for (int b : rt) {
                        int dx = a % 11 - b % 11;
                        int dz = a / 11 - b / 11;
                        side |= dx * dx + dz * dz <= 4 && dx * dx + dz * dz > 0;
                    }
                }
                if (!side) {
                    continue;
                }
                net.minecraft.core.BlockPos centre = (net.minecraft.core.BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter",
                        new Class<?>[]{int.class}, new Object[]{r.mainTile()});
                net.minecraft.core.BlockPos land = (net.minecraft.core.BlockPos) ModUnderTest.staticCall(TELEPORT_UTILS, "etherwarpableInTile",
                        new Class<?>[]{net.minecraft.core.BlockPos.class, net.minecraft.world.phys.Vec3.class},
                        new Object[]{centre, net.minecraft.world.phys.Vec3.atCenterOf(centre)});
                if (land == null) {
                    continue;
                }
                String key = (String) ModUnderTest.staticCall(INSTA_TRACKER, "entryKeyFor",
                        new Class<?>[]{String.class, net.minecraft.core.BlockPos.class, String.class},
                        new Object[]{r.name(), land, f.name()});
                if (key == null || key.contains("from=?")) {
                    continue;
                }
                try {
                    Path scratch = Files.createTempFile("acplan-insta", ".json");
                    Files.deleteIfExists(scratch);
                    ModUnderTest.staticCall(INSTA_TRACKER, "testUseFile", new Class<?>[]{String.class},
                            new Object[]{scratch.toString()});
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
                ModUnderTest.staticCall(INSTA_TRACKER, "testClearAll");
                for (int i = 0; i < 2; i++) {
                    ModUnderTest.staticCall(INSTA_TRACKER, "testAdd", new Class<?>[]{String.class, String.class, String.class,
                            long.class}, new Object[]{r.name(), key, "INSTA", 500L});
                }
                boolean known = (Boolean) ModUnderTest.staticCall(INSTA_TRACKER, "knownToInstaClear",
                        new Class<?>[]{String.class, String.class}, new Object[]{r.name(), key});
                return known ? new String[]{r.name(), key, f.name() + " (door steps " + steps + ")"} : null;
            }
        }
        return null;
    }

    /** Door-map room steps from a to b (locked doors count as open), or -1 with no path. */
    private static int path(Object layout, int a, int b) {
        List<?> p = (List<?>) ModUnderTest.staticCall(PATHFINDER, "findPath",
                new Class<?>[]{layout.getClass(), int.class, int.class, boolean.class}, new Object[]{layout, a, b, true});
        return p == null ? -1 : p.size() - 1;
    }

    private static void setEnum(Object cfg, String setter, String type, String value) {
        Object v = ModUnderTest.enumValue(AS_CONFIG + "$" + type, value);
        ModUnderTest.call(cfg, setter, new Class<?>[]{v.getClass()}, new Object[]{v});
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Path p, String text) {
        try {
            if (text == null) {
                Files.deleteIfExists(p);
            } else {
                Files.writeString(p, text, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // the sandbox is emptied next start anyway
        }
    }

    private void row(String name, boolean pass, String detail) {
        if (!Scenario.named(name)) {
            return;
        }
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }
}
