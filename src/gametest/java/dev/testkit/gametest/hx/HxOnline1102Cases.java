package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.mod.Mod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Online Count (mod onlinecount-1102, killer560 2026-11-02: "/online ... shows how many people using the mod are online
 * ... and what island they are on"; then "i want no specific player data from them just if they are on an island").
 * The mod's {@code onlinecount.OnlinePresence} sends {@code POST /presence} once a minute while on Hypixel (the Session
 * labels its connection {@code mc.hypixel.net}). Here the heartbeat is turned on ({@code killer560.test.presence}, off in
 * every other gametest), its interval cut to {@link #INTERVAL} ms ({@code killer560.test.presenceIntervalMs}) and its
 * {@code presence-relay} service pointed at an in-process stub relay on 32400-32499; every case asserts what the STUB
 * received, so a heartbeat that never left cannot pass.
 * <ul>
 *   <li>1102-online-payload: on the Hub fixture three beats arrive, each EXACTLY {session, location}, location "Hub", one
 *       session id (32 hex, not the player's UUID or name, and neither appears anywhere in a body), 3-4.5 s apart; the
 *       shipped interval constant is 60 s; moving to the Dwarven Mines tab sends that island; the location rule on its
 *       own (floor first, Area line, "Skyblock", "Hypixel (not Skyblock)", formatting codes and odd characters removed).</li>
 *   <li>1102-online-dungeon: the F7 dungeon fixture sends "Catacombs F7"; a sim dungeon (DungeonState's room-sim flag, what
 *       the sim sets) sends "Catacombs M7"; off Skyblock (a non-SKYBLOCK sidebar, no Area line) sends
 *       "Hypixel (not Skyblock)".</li>
 *   <li>1102-online-down: with the stub gone the mod keeps trying on schedule, each send ends in a connect failure, at
 *       most ONE WARN line and no chat line is shown, and nothing threw (FeatureGuard, ChatObserver, ERROR log).</li>
 *   <li>1102-online-gate: turning the harness switch off sends exactly one "Offline" (the leave beat) and then
 *       nothing for 5 s.</li>
 * </ul>
 * Nothing here moves the player or clicks; the Session's GrimAC check covers the rest.
 */
final class HxOnline1102Cases {

    private static final String P = "onlinecount.OnlinePresence";
    private static final long INTERVAL = 3000;

    private HxOnline1102Cases() {
    }

    static void register(Session s) {
        s.test("1102-online-payload", HxOnline1102Cases::payload);
        s.test("1102-online-dungeon", HxOnline1102Cases::dungeonLocations);
        s.test("1102-online-down", HxOnline1102Cases::down);
        s.test("1102-online-gate", HxOnline1102Cases::gate);
        s.test("1104-online-menu", HxOnline1102Cases::menu);
    }

    // ---- 1104-online-menu (presmenu-1104: "/online doesnt work if someone is just on the start screen") -------------------

    /** Title screen sends "Main Menu"; joining Hypixel switches to the island; leaving sends Offline then Main Menu. */
    private static void menu(Session c) throws Exception {
        Mod.Mark mark = Mod.mark();
        try (Stub stub = new Stub()) {
            hub(c);
            on(stub);
            awaitLocation(c, stub, 0, "Hub", 300);
            int from = stub.beats.size();
            c.scenario().toTitle();
            c.check(c.onClient(mc -> mc.level == null && mc.getConnection() == null), "not on the title screen");
            Beat menu = awaitLocation(c, stub, from, "Main Menu", 600);
            c.check(json(menu).keySet().equals(Set.of("session", "location")), "menu body keys: " + menu.body());
            c.check(json(menu).get("session").getAsString().equals(Mod.staticCall(P, "sessionForTest")), "menu session differs");
            List<Beat> left = stub.since(from);
            c.check(left.stream().anyMatch(b -> "Offline".equals(location(b))), "no Offline on leaving: " + left);
            c.check(left.indexOf(menu) > left.stream().filter(b -> "Offline".equals(location(b))).mapToInt(left::indexOf).findFirst().orElse(99),
                    "Main Menu did not follow Offline");
            c.note("menu beat: " + menu.body());
            // Joining Hypixel again switches to the island, and the 10 s relay floor holds across the switch.
            from = stub.beats.size();
            c.scenario().join();
            hub(c);
            Beat back = awaitLocation(c, stub, from, "Hub", 800);
            Beat lastMenu = stub.since(from).stream().filter(b -> "Main Menu".equals(location(b))).reduce((x, y) -> y).orElse(null);
            c.check(lastMenu == null || back.at() - lastMenu.at() >= 2500, "Hub beat came too soon after a menu beat");
            // Leaving again: Offline then Main Menu.
            from = stub.beats.size();
            c.scenario().toTitle();
            awaitLocation(c, stub, from, "Main Menu", 600);
            c.check(stub.since(from).stream().anyMatch(b -> "Offline".equals(location(b))), "no Offline on the second leave");
            c.check(stub.wrong.isEmpty(), "the stub saw other requests: " + stub.wrong);
            c.scenario().join();
        } finally {
            off();
            hub(c);
        }
        Mod.assertHealthy(mark);
    }

    // ---- the stub relay ---------------------------------------------------------------------------------------------

    record Beat(long at, String body) {
    }

    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final int port;
        final List<Beat> beats = new CopyOnWriteArrayList<>();
        final List<String> wrong = new CopyOnWriteArrayList<>();

        Stub() throws IOException {
            HttpServer s = null;
            int p = 0;
            for (int candidate = 32400; candidate <= 32499 && s == null; candidate++) {
                try {
                    s = HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
                    p = candidate;
                } catch (IOException taken) {
                    // next
                }
            }
            if (s == null) {
                throw new IOException("no free port in 32400-32499");
            }
            server = s;
            port = p;
            server.createContext("/", ex -> {
                try {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!"POST".equals(ex.getRequestMethod()) || !"/presence".equals(ex.getRequestURI().getPath())) {
                        wrong.add(ex.getRequestMethod() + " " + ex.getRequestURI());
                    } else {
                        beats.add(new Beat(System.currentTimeMillis(), body));
                    }
                    ex.sendResponseHeaders(204, -1);
                } catch (Throwable t) {
                    // never throw in the stub
                } finally {
                    ex.close();
                }
            });
            server.start();
        }

        List<Beat> since(int from) {
            List<Beat> all = new ArrayList<>(beats);
            return all.subList(Math.min(from, all.size()), all.size());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Points the mod at {@code stub} and turns the heartbeat on; {@link #off} undoes it. */
    private static void on(Stub stub) {
        Mod.staticCall(P, "clearForTest");
        System.setProperty("killer560.net.presence-relay", "http://127.0.0.1:" + stub.port);
        System.setProperty("killer560.test.presenceIntervalMs", String.valueOf(INTERVAL));
        System.setProperty("killer560.test.presence", "true");
    }

    private static void off() {
        System.clearProperty("killer560.test.presence");
        System.clearProperty("killer560.test.presenceIntervalMs");
        System.clearProperty("killer560.net.presence-relay");
    }

    private static JsonObject json(Beat b) {
        return JsonParser.parseString(b.body()).getAsJsonObject();
    }

    private static String location(Beat b) {
        return json(b).get("location").getAsString();
    }

    /** Wait until the stub holds a beat after index {@code from} whose location is {@code loc}; returns it. */
    private static Beat awaitLocation(Session c, Stub stub, int from, String loc, int ticks) {
        c.waitUntil("a heartbeat with location " + loc, mc -> stub.since(from).stream().anyMatch(b -> loc.equals(location(b))), ticks);
        return stub.since(from).stream().filter(b -> loc.equals(location(b))).findFirst().orElseThrow();
    }

    // ---- 1102-online-payload ----------------------------------------------------------------------------------------

    private static void payload(Session c) throws Exception {
        Mod.Mark mark = Mod.mark();
        try (Stub stub = new Stub()) {
            hub(c);
            on(stub);
            c.waitUntil("three Hub heartbeats at the stub", mc -> stub.beats.stream().filter(b -> "Hub".equals(location(b))).count() >= 3,
                    400);
            List<Beat> hubs = stub.beats.stream().filter(b -> "Hub".equals(location(b))).toList();
            String name = self(c);
            String uuid = c.onClient(mc -> mc.player.getUUID().toString());
            String session = null;
            for (Beat b : hubs) {
                JsonObject o = json(b);
                c.check(o.keySet().equals(Set.of("session", "location")), "body keys are " + o.keySet() + ": " + b.body());
                String sid = o.get("session").getAsString();
                c.check(sid.matches("[0-9a-f]{32}"), "session is not 32 hex: " + sid);
                c.check(session == null || session.equals(sid), "the session id changed: " + session + " -> " + sid);
                session = sid;
                String lower = b.body().toLowerCase();
                c.check(!lower.contains(name.toLowerCase()), "the body carries the player's name: " + b.body());
                c.check(!lower.contains(uuid.toLowerCase()) && !lower.contains(uuid.replace("-", "").toLowerCase()),
                        "the body carries the player's UUID: " + b.body());
            }
            c.check(session != null && !session.equals(uuid.replace("-", "")), "the session id is the UUID");
            c.check(session != null && session.equals(Mod.staticCall(P, "sessionForTest")), "the stub's session is not the mod's");
            for (int i = 1; i < hubs.size(); i++) {
                long gap = hubs.get(i).at() - hubs.get(i - 1).at();
                c.check(gap >= INTERVAL - 100 && gap <= INTERVAL + 1500, "beat " + i + " came " + gap + " ms after the last (interval " + INTERVAL + ")");
            }
            long shipped = Mod.cls(P).getField("INTERVAL_MS").getLong(null);
            c.check(shipped == 60_000L, "the shipped interval is " + shipped + " ms, not 60 s");
            c.check(stub.wrong.isEmpty(), "the stub saw other requests: " + stub.wrong);
            c.note("bodies: " + hubs.get(0).body() + "; gaps " + (hubs.size() > 1 ? hubs.get(1).at() - hubs.get(0).at() : -1) + " ms");

            // Another island: the tab's Area line is what is sent.
            int from = stub.beats.size();
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Lava Springs", "Purse: 0");
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test"), null, null);
            Beat mines = awaitLocation(c, stub, from, "Dwarven Mines", 200);
            c.note("Dwarven Mines beat: " + mines.body());

            // The rule on its own.
            String[][] rule = {
                    {"true", "Hub", "F7", "Catacombs F7"},
                    {"true", "Catacombs", "M3", "Catacombs M3"},
                    {"true", "Hub", null, "Hub"},
                    {"true", "§bSpider’s Den", null, "Spider's Den"},
                    {"true", "<@everyone> x", null, "everyone x"},
                    {"true", "", null, "Skyblock"},
                    {"false", "", null, "Hypixel (not Skyblock)"},
                    {"false", "Hub", null, "Hypixel (not Skyblock)"},
            };
            for (String[] r : rule) {
                Object got = Mod.staticCall(P, "locationFor", Boolean.parseBoolean(r[0]), r[1], r[2]);
                c.check(r[3].equals(got), "locationFor(" + r[0] + ", " + r[1] + ", " + r[2] + ") = " + got + ", not " + r[3]);
            }
        } finally {
            off();
            hub(c);
        }
        Mod.assertHealthy(mark);
    }

    // ---- 1102-online-dungeon ----------------------------------------------------------------------------------------

    private static void dungeonLocations(Session c) throws Exception {
        Mod.Mark mark = Mod.mark();
        try (Stub stub = new Stub()) {
            on(stub);
            dungeon(c);
            Beat f7 = awaitLocation(c, stub, 0, "Catacombs F7", 300);
            c.note("F7 beat: " + f7.body());
            hub(c);
            int from = stub.beats.size();
            run(c, () -> Mod.staticCall("secrets.DungeonState", "setRoomSim", true));
            try {
                Beat sim = awaitLocation(c, stub, from, "Catacombs M7", 300);
                c.note("sim beat: " + sim.body());
            } finally {
                run(c, () -> Mod.staticCall("secrets.DungeonState", "setRoomSim", false));
            }
            from = stub.beats.size();
            c.hx().sidebar("BED WARS", "Players: 4", "Mode: Solo");
            c.hx().tab(List.of("Bob", "Alice"), null, null);
            Beat other = awaitLocation(c, stub, from, "Hypixel (not Skyblock)", 400);
            c.note("off Skyblock beat: " + other.body());
            for (Beat b : stub.beats) {
                c.check(json(b).keySet().equals(Set.of("session", "location")), "body keys are " + json(b).keySet());
            }
        } finally {
            off();
            hub(c);
        }
        Mod.assertHealthy(mark);
    }

    // ---- 1102-online-down -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void down(Session c) throws Exception {
        Mod.Mark mark = Mod.mark();
        long logMark = dev.testkit.gametest.LogTap.mark();
        Stub stub = new Stub();
        int port = stub.port;
        stub.close();
        try {
            hub(c);
            Mod.staticCall(P, "clearForTest");
            System.setProperty("killer560.net.presence-relay", "http://127.0.0.1:" + port);
            System.setProperty("killer560.test.presenceIntervalMs", String.valueOf(INTERVAL));
            System.setProperty("killer560.test.presence", "true");
            c.waitUntil("three failed sends", mc -> ((List<String>) Mod.staticCall(P, "resultsForTest")).size() >= 3, 400);
            List<String> results = (List<String>) Mod.staticCall(P, "resultsForTest");
            List<String> sent = (List<String>) Mod.staticCall(P, "sentForTest");
            c.note("results: " + results + "; sent " + sent.size());
            c.check(results.stream().noneMatch(r -> r.startsWith("HTTP")), "a send reached something: " + results);
            c.check(sent.size() >= 3, "the mod stopped trying after a failure: " + sent.size() + " sends");
            List<String> warns = logLines(logMark, "[OnlineCount]");
            c.check(warns.size() <= 1, "more than one OnlineCount log line: " + warns);
            List<String> chat = logLines(logMark, "[CHAT]").stream()
                    .filter(l -> l.toLowerCase().contains("online") || l.toLowerCase().contains("relay") || l.toLowerCase().contains("presence"))
                    .toList();
            c.check(chat.isEmpty(), "a chat line was shown: " + chat);
        } finally {
            off();
        }
        Mod.assertHealthy(mark);
    }

    // ---- 1102-online-gate -------------------------------------------------------------------------------------------

    private static void gate(Session c) throws Exception {
        Mod.Mark mark = Mod.mark();
        try (Stub stub = new Stub()) {
            hub(c);
            on(stub);
            awaitLocation(c, stub, 0, "Hub", 300);
            int from = stub.beats.size();
            System.clearProperty("killer560.test.presence");
            Beat leave = awaitLocation(c, stub, from, "Offline", 100);
            c.note("leave beat: " + leave.body());
            int after = stub.beats.size();
            c.ctx().waitTicks(100);
            List<Beat> later = stub.since(after);
            c.check(later.isEmpty(), "beats after the switch went off: " + later);
            c.check(stub.since(from).stream().filter(b -> "Offline".equals(location(b))).count() == 1, "not exactly one Offline");
        } finally {
            off();
        }
        Mod.assertHealthy(mark);
    }
}
