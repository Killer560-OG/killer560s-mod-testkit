package dev.testkit.gametest.ui;

import com.sun.net.httpserver.HttpServer;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 1122-ui-bugreport-menu (bugreportmenu-1122): /bugreport opens the Bug Report screen; a typed title and description and
 * Save write a zip holding note.txt with both, contents.txt, the in-memory mod log (a line logged during this case is in
 * it, a Mod Chat line is not), automation.txt, features-on.txt and the old entries; Save and Post against a loopback relay
 * stub sends the title, the description and that zip (Bearer test token) and the screen shows the returned link; a relay
 * refusal shows its reason; a too-short title is refused before anything is sent.
 */
final class BugReportMenu1122Cases {
    private static final String SCREEN = "com.killer560.hub.bugreport.BugReportScreen";

    private BugReportMenu1122Cases() {
    }

    /** The loopback relay: every POST body to /bug-report and its Authorization header; answers {@code reply}. */
    static final class Stub implements AutoCloseable {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> auth = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String reply = "{\"ok\":true,\"url\":\"https://discord.com/channels/1546000000000000001/1560000000000000099\","
                + "\"session\":\"0123456789abcdef0123456789abcdef\"}";
        /** The later parts (POST /bug-report/part): query and raw bytes, in arrival order; each answers {"ok":true}. */
        final List<String> partQueries = new CopyOnWriteArrayList<>();
        final List<byte[]> partBodies = new CopyOnWriteArrayList<>();
        final Map<Integer, Integer> attempts = new java.util.concurrent.ConcurrentHashMap<>();
        volatile int failPart = -1;
        volatile int failOncePart = -1;
        volatile int partDelayMs = 0;
        final HttpServer server;

        Stub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                try {
                    byte[] in = ex.getRequestBody().readAllBytes();
                    if (ex.getRequestURI().getPath().equals("/bug-report") && ex.getRequestMethod().equals("POST")) {
                        bodies.add(new String(in, StandardCharsets.UTF_8));
                        auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                    }
                    int code = status;
                    String answer = reply;
                    if (ex.getRequestURI().getPath().equals("/bug-report/part")) {
                        String q = ex.getRequestURI().getQuery();
                        int n = Integer.parseInt(q.substring(q.lastIndexOf("n=") + 2));
                        int attempt = attempts.merge(n, 1, Integer::sum);
                        Thread.sleep(partDelayMs);
                        if (n == failPart || (n == failOncePart && attempt == 1)) {
                            code = 500;
                            answer = "{\"error\":\"boom\"}";
                        } else {
                            partQueries.add(q);
                            partBodies.add(in);
                            answer = "{\"ok\":true}";
                        }
                    }
                    byte[] out = answer.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(code, out.length);
                    ex.getResponseBody().write(out);
                } catch (RuntimeException | InterruptedException ignored) {
                    // never throw inside the stub
                } finally {
                    ex.close();
                }
            });
            server.start();
            System.setProperty("killer560.net.bugreport-relay", "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            System.clearProperty("killer560.net.bugreport-relay");
            server.stop(0);
        }
    }

    static void menu(UiCase c) throws Exception {
        String probe = "[BugReportProbe] line logged by testkit " + System.nanoTime();
        String chatProbe = "[ModChat] SomeOtherPlayer: bug report chat probe " + System.nanoTime();
        logger("killer560smod-corrections").info(probe);
        logger("killer560smod-modchat").info(chatProbe);

        Screen s = open(c, "bugreport");
        if (s == null) {
            return;
        }
        String title = "Test title " + (System.currentTimeMillis() % 100000);
        String desc = "Steps: open the menu and type this description";
        typeInto(c, s, titleBox(c, s), title);
        typeInto(c, s, descBox(c, s), desc);
        c.note("typed: title='" + field(c, s, "titleText") + "' description='" + field(c, s, "descText") + "'");
        c.check(title.equals(field(c, s, "titleText")), "the Title box did not take the typed text: " + field(c, s, "titleText"));
        c.check(desc.equals(field(c, s, "descText")), "the Description box did not take the typed text: " + field(c, s, "descText"));

        Path dir = (Path) Mod.staticCall("util.ModPaths", "config", "bug-reports");
        long start = System.currentTimeMillis();
        Thread.sleep(1100); // the zip name carries a timestamp
        InvBatchCases.clickWidget(c, s, button(c, s, "Save"));
        String status = waitDone(c, s);
        c.note("Save status: " + status);
        c.check(status.startsWith("Saved:"), "Save did not end with Saved: " + status);
        Path zip = newest(dir, start);
        c.check(zip != null, "Save produced no new zip in " + dir);
        if (zip != null) {
            Map<String, String> z = unzip(Files.readAllBytes(zip));
            checkZip(c, z, title, desc, probe, chatProbe);
        }

        // ---- Save and Post against the loopback relay ----
        try (Stub stub = new Stub()) {
            String token = "testkit-bugreport-token-" + System.nanoTime();
            Mod.staticCall("bugreport.BugReportPost", "setTokenForTest", token);
            InvBatchCases.clickWidget(c, s, button(c, s, "Save and Post"));
            status = waitDone(c, s);
            c.note("Save and Post status: " + status);
            c.check(stub.bodies.size() == 1, "Save and Post sent " + stub.bodies.size() + " request(s) to the relay");
            c.check(status.startsWith("Posted: https://discord.com/channels/1546000000000000001/1560000000000000099"),
                    "the screen does not show the posted link: " + status);
            if (!stub.bodies.isEmpty()) {
                c.check(("Bearer " + token).equals(stub.auth.get(0)), "the post did not carry the bearer token: " + stub.auth.get(0));
                com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(stub.bodies.get(0)).getAsJsonObject();
                c.check(title.equals(body.get("title").getAsString()), "the post's title is " + body.get("title"));
                c.check(desc.equals(body.get("description").getAsString()), "the post's description is " + body.get("description"));
                c.check(!body.has("parts") && !body.has("names"), "a small report was sent as a multi-part upload: " + body.keySet());
                c.check(body.has("modVersion") && body.has("minecraft") && body.has("variant") && body.has("commit"),
                        "the post lacks the build fields: " + body.keySet());
                byte[] zipBytes = Base64.getDecoder().decode(body.get("zip").getAsString());
                c.note("posted zip: " + zipBytes.length + " bytes");
                Map<String, String> pz = unzip(zipBytes);
                c.check(pz.getOrDefault("note.txt", "").contains(title) && pz.containsKey("mod-log.txt") && pz.containsKey("report.txt"),
                        "the posted zip lacks note.txt/mod-log.txt/report.txt: " + pz.keySet());
                c.check(pz.getOrDefault("mod-log.txt", "").contains(probe), "the posted zip's mod-log.txt lacks the probe line");
            }

            // ---- a refusal is shown with its reason ----
            stub.status = 429;
            stub.reply = "{\"error\":\"one post per 2 minutes; it is saved, post it in a bit\"}";
            InvBatchCases.clickWidget(c, s, button(c, s, "Save and Post"));
            status = waitDone(c, s);
            c.note("refused status: " + status);
            c.check(status.startsWith("Saved, but not posted: one post per 2 minutes"), "a 429 is not shown with its reason: " + status);

            // ---- a title too short to post is refused before anything is sent ----
            int sent = stub.bodies.size();
            c.onClient(mc -> {
                ((EditBox) titleBox(c, s)).setValue("ab");
                return null;
            });
            InvBatchCases.clickWidget(c, s, button(c, s, "Save and Post"));
            c.ticks(5);
            status = status(c, s);
            c.check(status.startsWith("Type a title") && stub.bodies.size() == sent, "a 2-letter title was not refused before sending: "
                    + status + ", sent " + (stub.bodies.size() - sent));
        } finally {
            Mod.staticCall("bugreport.BugReportPost", "setTokenForTest", (Object) null);
            close(c);
        }
    }

    static void checkZip(UiCase c, Map<String, String> z, String title, String desc, String probe, String chatProbe) {
        c.note("zip entries: " + z.keySet().stream().filter(n -> !n.startsWith("configs/")).toList() + " + "
                + z.keySet().stream().filter(n -> n.startsWith("configs/")).count() + " configs");
        String note = z.getOrDefault("note.txt", "<missing>");
        c.check(note.contains("Title: " + title) && note.contains(desc), "note.txt lacks the title or description: " + note);
        String log = z.getOrDefault("mod-log.txt", "<missing>");
        c.note("mod-log.txt: " + log.lines().count() + " lines");
        c.check(log.contains(probe), "mod-log.txt lacks the line logged during the case");
        c.check(log.lines().count() > 20, "mod-log.txt holds only " + log.lines().count() + " lines; the mod logs far more at boot");
        c.check(!log.contains("bug report chat probe"), "mod-log.txt carries a Mod Chat line (other players' chat): " + chatProbe);
        String index = z.getOrDefault("contents.txt", "<missing>");
        c.check(index.contains("mod-log.txt") && index.contains("note.txt"), "contents.txt does not list the files: " + index);
        String auto = z.getOrDefault("automation.txt", "<missing>");
        c.check(auto.contains("Dungeon floor:") && auto.contains("Ping measured") && auto.contains("Last server correction:"),
                "automation.txt lacks its fields: " + auto);
        boolean cheat = Mod.isCheat();
        c.check(auto.contains("Auto Routes running:") == cheat, "automation.txt's Auto Routes line does not match the build (cheat="
                + cheat + "): " + auto);
        c.check(z.containsKey("features-on.txt"), "no features-on.txt");
        for (String old : List.of("report.txt", "state.txt", "sidebar.txt", "readers.txt", "hud.txt")) {
            c.check(z.containsKey(old), "the zip lost " + old);
        }
        c.check(z.keySet().stream().anyMatch(n -> n.startsWith("configs/")), "the zip has no configs/");
        c.check(z.getOrDefault("report.txt", "").contains("Loaded mods:"), "report.txt lacks the mod list");
    }

    /** Runs {@code /command} and waits for the Bug Report screen. */
    static Screen open(UiCase c, String command) {
        c.onClient(mc -> {
            mc.player.connection.sendCommand(command);
            return null;
        });
        Screen s = null;
        for (int i = 0; i < 40 && s == null; i++) {
            c.ticks(2);
            s = c.onClient(mc -> {
                Screen cur = McCompat.screen(mc);
                return cur != null && cur.getClass().getName().equals(SCREEN) ? cur : null;
            });
        }
        c.check(s != null, "/" + command + " did not open the Bug Report screen (open: "
                + c.onClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getClass().getName()) + ")");
        return s;
    }

    static void close(UiCase c) {
        c.onClient(mc -> {
            if (McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getName().equals(SCREEN)) {
                McCompat.screen(mc).onClose();
            }
            return null;
        });
        c.ticks(2);
    }

    static AbstractWidget button(UiCase c, Screen s, String label) {
        return c.onClient(mc -> {
            for (GuiEventListener l : s.children()) {
                if (l instanceof AbstractWidget w && ModScreenDriver.label(w).equals(label)) {
                    return w;
                }
            }
            return null;
        });
    }

    static AbstractWidget titleBox(UiCase c, Screen s) {
        return c.onClient(mc -> {
            for (GuiEventListener l : s.children()) {
                if (l instanceof EditBox e) {
                    return e;
                }
            }
            return null;
        });
    }

    static AbstractWidget descBox(UiCase c, Screen s) {
        return c.onClient(mc -> {
            for (GuiEventListener l : s.children()) {
                if (l instanceof net.minecraft.client.gui.components.MultiLineEditBox m) {
                    return m;
                }
            }
            return null;
        });
    }

    /** A real click into the box, then real typed characters. */
    static void typeInto(UiCase c, Screen s, AbstractWidget box, String text) {
        c.check(box != null, "no text box to type into");
        if (box == null) {
            return;
        }
        InvBatchCases.clickWidget(c, s, box);
        c.ctx().getInput().typeChars(text);
        c.ticks(3);
    }

    static String field(UiCase c, Screen s, String name) {
        return c.onClient(mc -> {
            try {
                var f = s.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return String.valueOf(f.get(s));
            } catch (ReflectiveOperationException e) {
                return "<" + e + ">";
            }
        });
    }

    static String status(UiCase c, Screen s) {
        return c.onClient(mc -> String.valueOf(Mod.call(s, "status")));
    }

    /** Waits (up to 30 s) until the screen is no longer busy and returns its status line. */
    static String waitDone(UiCase c, Screen s) {
        c.ticks(3);
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline && Boolean.TRUE.equals(c.onClient(mc -> Mod.call(s, "busy")))) {
            c.ticks(5);
        }
        return status(c, s);
    }

    @SuppressWarnings("unchecked")
    static List<String> contents(UiCase c) {
        return new ArrayList<>((List<String>) Mod.field("bugreport.BugReportFeature", "CONTENTS"));
    }

    static Path newest(Path dir, long since) throws Exception {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> st = Files.list(dir)) {
            return st.filter(p -> p.toString().endsWith(".zip") && p.toFile().lastModified() >= since)
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified())).orElse(null);
        }
    }

    static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static org.slf4j.Logger logger(String name) {
        return (org.slf4j.Logger) Mod.staticCall("util.ModLog", "get", name);
    }
}
