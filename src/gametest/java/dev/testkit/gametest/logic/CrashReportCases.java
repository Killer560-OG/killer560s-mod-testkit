package dev.testkit.gametest.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1104-logic-crashreport-*: Auto Crash Reports (mod crashrep-1104, killer560 2026-11-04). Opt-in, OFF by default: after a
 * crash, ONE scrubbed report goes to the relay's {@code POST /crash-report}. The relay is an in-process stub on a free
 * loopback port ({@code killer560.net.crashreport-relay}); the bearer token is a test one
 * ({@code CrashReportFeature.setTokenForTest}, honored only inside a gametest), since a dev account has no Mojang chat key.
 * Every case asserts what the STUB received, so a report that never left cannot pass.
 *
 * <pre>
 * 1104-logic-crashreport-live  a forced crash (a real CrashReport saved by vanilla's saveToFile, so the mod's mixin runs)
 *                              sends exactly one report as it crashes, scrubbed (tokens, IP, server, home folder, other
 *                              players, his own name and UUID); the next boot's scan sends nothing more (same crash file)
 * 1104-logic-crashreport-boot  a JVM crash (an hs_err_pid log the game never saw) is sent by the next boot's scan, once;
 *                              with Include My Minecraft Name on, his name and UUID stay and other players still go
 * 1104-logic-crashreport-off   switch OFF (the default): a forced crash and a boot scan send nothing and build nothing;
 *                              ON again, a crash from before the switch went on is not sent either
 * </pre>
 */
final class CrashReportCases {

    static final String CFG = "crashreport.CrashReportConfig";
    static final String FEATURE = "crashreport.CrashReportFeature";
    static final String TOKEN = "testkit-crash-token";
    static final String OTHER = "Bob_Otherguy";
    static final String OTHER_UUID = "0123abcd-4567-89ab-cdef-0123456789ab";
    static final String ACCESS = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0a2l0In0.c2lnbmF0dXJlLXRlc3RraXQ";
    static final String SECRET_IP = "203.0.113.9";
    static final String SERVER = "play.example-server.net:25565";
    static final String HOME_USER = "SomeWinUser";
    static final String GH_TOKEN = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";

    private CrashReportCases() {
    }

    /** The stub relay: every POST body to /crash-report, and its Authorization header. */
    static final class Stub implements AutoCloseable {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<String> auth = new CopyOnWriteArrayList<>();
        final HttpServer server;

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                try {
                    byte[] in = ex.getRequestBody().readAllBytes();
                    if (ex.getRequestURI().getPath().equals("/crash-report") && ex.getRequestMethod().equals("POST")) {
                        bodies.add(new String(in, StandardCharsets.UTF_8));
                        auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                    }
                    byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                } catch (RuntimeException ignored) {
                    // never throw inside the stub
                } finally {
                    ex.close();
                }
            });
            server.start();
            System.setProperty("killer560.net.crashreport-relay", "http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            System.clearProperty("killer560.net.crashreport-relay");
            server.stop(0);
        }
    }

    /** Switch on or off, history cleared, the switch counted as on since a minute ago, the test token set. */
    private static void reset(boolean on, boolean shareName) {
        Object cfg = Mod.cfg(CFG);
        Mod.call(cfg, "setEnabled", on);
        Mod.call(cfg, "setShareName", shareName);
        Mod.staticCall(FEATURE, "clearForTest", System.currentTimeMillis() - 60_000L);
        Mod.staticCall(FEATURE, "setTokenForTest", TOKEN);
    }

    private static void restore(boolean wasOn, boolean wasShare) {
        Object cfg = Mod.cfg(CFG);
        Mod.call(cfg, "setShareDiscord", true);
        Mod.call(cfg, "setEnabled", wasOn);
        Mod.call(cfg, "setShareName", wasShare);
        Mod.staticCall(FEATURE, "clearForTest", 0L);
        Mod.staticCall(FEATURE, "setTokenForTest", (Object) null);
        Mod.call(cfg, "save");
    }

    @SuppressWarnings("unchecked")
    private static List<String> attempts() {
        return (List<String>) Mod.staticCall(FEATURE, "attemptsForTest");
    }

    @SuppressWarnings("unchecked")
    private static List<String> decisions() {
        return (List<String>) Mod.staticCall(FEATURE, "decisionsForTest");
    }

    private static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    /** A real crash report, saved by vanilla into crash-reports/ (the mixin on saveToFile runs here). */
    private static Path forcedCrash(String self, String selfUuid, List<Path> made) throws IOException {
        CrashReport report = CrashReport.forThrowable(new IllegalStateException("testkit forced crash"), "Testkit forced crash");
        CrashReportCategory cat = report.addCategory("Testkit secrets");
        cat.setDetail("Launch", "--username " + self + " --uuid " + selfUuid.replace("-", "") + " --accessToken " + ACCESS
                + " --server " + SERVER);
        cat.setDetail("Players", "[LocalPlayer['" + self + "'/1, l='ClientLevel'], RemotePlayer['" + OTHER + "'/2, l='ClientLevel']]");
        cat.setDetail("Other", OTHER + " " + OTHER_UUID + " at " + SECRET_IP + " via " + SERVER + " key " + GH_TOKEN);
        cat.setDetail("Path", "C:\\Users\\" + HOME_USER + "\\AppData\\Roaming\\x");
        cat.setDetail("Self", self + " (" + selfUuid + ")");
        Path file = gameDir().resolve("crash-reports").resolve("crash-testkit-" + System.nanoTime() + "-client.txt");
        Files.createDirectories(file.getParent());
        made.add(file);
        report.saveToFile(file, ReportType.CRASH);
        return file;
    }

    /** What the scrub must have removed from any body. */
    private static void scrubbed(LogicCase c, String body, String self, String selfUuid, boolean identify) {
        c.check("the launcher's access token is gone", !body.contains(ACCESS) && !body.contains("eyJhbGci"), "");
        c.check("a GitHub token is gone", !body.contains(GH_TOKEN), "");
        c.check("an IP address is gone", !body.contains(SECRET_IP), "");
        c.check("a server address is gone", !body.contains("example-server"), "");
        c.check("the home folder is gone", !body.contains(HOME_USER) && body.contains("<home>"), "");
        c.check("another player's name is gone", !body.contains(OTHER) && body.contains("<player>"), "");
        c.check("another player's UUID is gone", !body.contains(OTHER_UUID) && body.contains("<uuid>"), "");
        String dashless = selfUuid.replace("-", "");
        if (identify) {
            c.check("with Include My Minecraft Name his name stays", body.contains(self), "");
            c.check("and his UUID stays", body.contains(selfUuid), "");
        } else {
            c.check("his own name is gone", !body.matches("(?s).*(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(self)
                    + "(?![A-Za-z0-9_]).*"), "");
            c.check("his own UUID is gone (dashed and dashless)", !body.contains(selfUuid) && !body.contains(dashless), "");
        }
    }

    static void live(LogicCase c) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        boolean wasShare = (Boolean) Mod.get(CFG, "isShareName");
        List<Path> made = new ArrayList<>();
        try (Stub stub = new Stub()) {
            reset(true, false);
            String self = mc.getUser().getName();
            String selfUuid = mc.getUser().getProfileId().toString();
            forcedCrash(self, selfUuid, made);
            if (!c.eq("a forced crash sends exactly one report as it crashes", 1, stub.bodies.size())) {
                c.note("decisions: " + decisions() + ", last result " + Mod.staticCall(FEATURE, "lastResultForTest"));
                return;
            }
            c.eq("with the bearer token", "Bearer " + TOKEN, stub.auth.get(0));
            c.eq("and it is the body the mod built", attempts().get(0), stub.bodies.get(0));
            String body = stub.bodies.get(0);
            JsonObject r = JsonParser.parseString(body).getAsJsonObject();
            String crash = r.get("crash").getAsString();
            c.check("the crash text is Minecraft's report", crash.contains("---- Minecraft Crash Report ----")
                    && crash.contains("Description: Testkit forced crash"), crash.substring(0, Math.min(200, crash.length())));
            c.check("with the exception", crash.contains("java.lang.IllegalStateException: testkit forced crash"));
            c.check("and its stack frames, file:line intact", crash.matches("(?s).*\\tat [^\\n]+\\([A-Za-z0-9_$]+\\.java:\\d+\\).*"),
                    "");
            c.check("the summary names the crash", r.get("summary").getAsString().contains("testkit forced crash"),
                    r.get("summary").getAsString());
            c.eq("kind java", "java", r.get("kind").getAsString());
            c.eq("sent live", "live", r.get("source").getAsString());
            c.eq("not identified with the name switch off", false, r.get("identify").getAsBoolean());
            c.eq("and no Discord asked for without the name", false, r.get("shareDiscord").getAsBoolean());
            c.eq("the crash key is a 64-hex hash", true, r.get("crashKey").getAsString().matches("[0-9a-f]{64}"));
            c.eq("the variant is the jar's", Mod.isCheat() ? "cheat" : "legit", r.get("variant").getAsString());
            c.check("the Minecraft version is set", r.get("minecraft").getAsString().matches("26\\.\\d+(\\.\\d+)?"),
                    r.get("minecraft").getAsString());
            c.check("the mod version and commit are set", !r.get("modVersion").getAsString().isEmpty()
                    && !r.get("commit").getAsString().isEmpty());
            String mods = r.getAsJsonArray("mods").toString();
            c.check("the other mods are listed (Fabric API), not the mod itself", mods.contains("fabric-api ")
                    && !mods.contains("killer560smod"), mods.length() > 300 ? mods.substring(0, 300) : mods);
            c.check("the features list is an array", r.getAsJsonArray("features") != null);
            c.check("the body is under the relay's 450 KB", body.getBytes(StandardCharsets.UTF_8).length < 450_000);
            scrubbed(c, body, self, selfUuid, false);

            // The next start: the same crash file is never sent twice.
            Mod.staticCall(FEATURE, "bootScanForTest");
            c.eq("the next boot's scan sends nothing more for that crash", 1, stub.bodies.size());
            List<String> d = decisions();
            c.check("because it was already reported", !d.isEmpty() && d.get(d.size() - 1).equals("already reported"), d.toString());
        } finally {
            restore(wasOn, wasShare);
            for (Path p : made) {
                Files.deleteIfExists(p);
            }
        }
    }

    static void boot(LogicCase c) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        boolean wasShare = (Boolean) Mod.get(CFG, "isShareName");
        Path hsErr = gameDir().resolve("hs_err_pid" + (900_000 + (System.nanoTime() % 99_999)) + ".log");
        try (Stub stub = new Stub()) {
            reset(true, true);
            String self = mc.getUser().getName();
            String selfUuid = mc.getUser().getProfileId().toString();
            Files.writeString(hsErr, "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n"
                    + "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffb1a2b3c4d, pid=4242, tid=17\n#\n"
                    + "# Problematic frame:\n# C  [testkit_gpu.dll+0x1234]\n#\n\n"
                    + "---------------  S U M M A R Y ------------\n\n"
                    + "Command Line: -Xmx4G net.fabricmc.loader.impl.launch.knot.KnotClient --username " + self
                    + " --uuid " + selfUuid.replace("-", "") + " --accessToken " + ACCESS + " --quickPlayMultiplayer " + SERVER
                    + "\n\nHost: " + SECRET_IP + ", other " + OTHER + " " + OTHER_UUID + " RemotePlayer['" + OTHER + "'/2]\n"
                    + "USERPROFILE=C:\\Users\\" + HOME_USER + "\nself " + self + " " + selfUuid + "\n", StandardCharsets.UTF_8);
            Mod.staticCall(FEATURE, "bootScanForTest");
            if (!c.eq("the next boot sends exactly one report of the JVM crash", 1, stub.bodies.size())) {
                c.note("decisions: " + decisions() + ", last result " + Mod.staticCall(FEATURE, "lastResultForTest"));
                return;
            }
            String body = stub.bodies.get(0);
            JsonObject r = JsonParser.parseString(body).getAsJsonObject();
            c.eq("kind native", "native", r.get("kind").getAsString());
            c.eq("sent at boot", "boot", r.get("source").getAsString());
            c.eq("identified (Include My Minecraft Name is on)", true, r.get("identify").getAsBoolean());
            c.eq("and asks for his linked Discord (Include My Discord defaults ON)", true, r.get("shareDiscord").getAsBoolean());
            c.check("the summary is the hs_err header", r.get("summary").getAsString().contains("EXCEPTION_ACCESS_VIOLATION"),
                    r.get("summary").getAsString());
            c.check("the crash text is the hs_err log", r.get("crash").getAsString().contains("testkit_gpu.dll"));
            scrubbed(c, body, self, selfUuid, true);

            Mod.staticCall(FEATURE, "bootScanForTest");
            c.eq("scanned again, the same crash is not sent twice", 1, stub.bodies.size());
        } finally {
            restore(wasOn, wasShare);
            Files.deleteIfExists(hsErr);
        }
    }

    /** 1105-discordlink-payload: shareDiscord rides only with BOTH name switches on (the relay then adds his Discord). */
    static void discordPayload(LogicCase c) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        boolean wasShare = (Boolean) Mod.get(CFG, "isShareName");
        List<Path> made = new ArrayList<>();
        try (Stub stub = new Stub()) {
            String self = mc.getUser().getName();
            String selfUuid = mc.getUser().getProfileId().toString();
            boolean[][] combos = {{true, true}, {true, false}, {false, true}};
            for (boolean[] k : combos) {
                reset(true, k[0]);
                Mod.call(Mod.cfg(CFG), "setShareDiscord", k[1]);
                int n = stub.bodies.size();
                forcedCrash(self, selfUuid, made);
                String label = "name " + (k[0] ? "on" : "off") + ", Discord " + (k[1] ? "on" : "off");
                if (!c.eq(label + ": one report sent", n + 1, stub.bodies.size())) {
                    c.note("decisions: " + decisions());
                    return;
                }
                JsonObject r = JsonParser.parseString(stub.bodies.get(n)).getAsJsonObject();
                c.eq(label + ": identify", k[0], r.get("identify").getAsBoolean());
                c.eq(label + ": shareDiscord", k[0] && k[1], r.get("shareDiscord").getAsBoolean());
            }
            Object fresh;
            try {
                var ctor = Mod.cls(CFG).getDeclaredConstructor();
                ctor.setAccessible(true);
                fresh = ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            c.eq("a fresh config shares the name", true, Mod.call(fresh, "isShareName"));
            c.eq("and the Discord", true, Mod.call(fresh, "isShareDiscord"));
            c.eq("while Auto Crash Reports itself stays OFF", false, Mod.call(fresh, "isEnabled"));
        } finally {
            restore(wasOn, wasShare);
            for (Path p : made) {
                Files.deleteIfExists(p);
            }
        }
    }

    static void off(LogicCase c) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        boolean wasOn = (Boolean) Mod.get(CFG, "isEnabled");
        boolean wasShare = (Boolean) Mod.get(CFG, "isShareName");
        List<Path> made = new ArrayList<>();
        try (Stub stub = new Stub()) {
            Object fresh;
            try {
                var k = Mod.cls(CFG).getDeclaredConstructor();
                k.setAccessible(true);
                fresh = k.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            c.eq("Auto Crash Reports is OFF by default in a fresh config", false, Mod.call(fresh, "isEnabled"));
            // killer560, 2026-11-05: "Have share name on by default" (the switch itself stays OFF).
            c.eq("and Include My Minecraft Name is ON by default", true, Mod.call(fresh, "isShareName"));
            c.eq("and Include My Discord is ON by default", true, Mod.call(fresh, "isShareDiscord"));

            reset(false, false);
            String self = mc.getUser().getName();
            String selfUuid = mc.getUser().getProfileId().toString();
            forcedCrash(self, selfUuid, made);
            Mod.staticCall(FEATURE, "bootScanForTest");
            c.eq("with the switch off a forced crash and a boot scan send nothing", 0, stub.bodies.size());
            c.eq("and build nothing", 0, attempts().size());
            c.eq("and do not even look", 0, decisions().size());

            // ON now: the crash from while it was off is older than the switch, so it is never sent.
            Mod.call(Mod.cfg(CFG), "setEnabled", true);
            Mod.staticCall(FEATURE, "clearForTest", System.currentTimeMillis() + 1000L);
            Mod.staticCall(FEATURE, "bootScanForTest");
            c.eq("a crash from before the switch went on is not sent", 0, stub.bodies.size());
            List<String> d = decisions();
            c.check("and the mod says why", d.size() == 1 && d.get(0).startsWith("crash from before the switch"), d.toString());

            // Control: the same client, switch on, a new crash - it does send (so the zeros above are not a dead feature).
            Mod.staticCall(FEATURE, "clearForTest", System.currentTimeMillis() - 60_000L);
            forcedCrash(self, selfUuid, made);
            c.eq("control: switched on, a forced crash does send one", 1, stub.bodies.size());
        } finally {
            restore(wasOn, wasShare);
            for (Path p : made) {
                Files.deleteIfExists(p);
            }
        }
    }

    /**
     * 1110-logic-crashguard-shutdown: a stray non-daemon thread (a fake package, like SecretRoutes' HttpClient.send) is
     * named by the shutdown guard's WARN, and a "Client shutdown from post-main" report text says what held it open.
     * The real close-then-exit path cannot run inside a gametest (it would end the client), so the classification and the
     * summary are tested directly.
     */
    static void crashguardShutdown(LogicCase c) throws Exception {
        String guard = "crashreport.ShutdownGuard";
        // Loading the target runs the mixin transform; the mod config says require 1, so a missed target would throw here.
        Class<?> wd = Class.forName("com.mojang.blaze3d.platform.ClientShutdownWatchdog", false, Minecraft.class.getClassLoader());
        c.check("vanilla's shutdown watchdog loads with the guard mixin applied", wd != null);
        long main = Thread.currentThread().threadId();
        java.util.concurrent.CountDownLatch stop = new java.util.concurrent.CountDownLatch(1);
        Thread stray = new Thread(() -> {
            try {
                stop.await();
            } catch (InterruptedException ignored) {
                // done
            }
        }, "Thread-fake-http");
        stray.setDaemon(false);
        stray.start();
        try {
            Object msg = Mod.staticCall(guard, "check", main);
            c.check("the guard sees the stray non-daemon thread and names it", msg != null && msg.toString().contains("Thread-fake-http"),
                    String.valueOf(msg));
            c.check("and a mod or package owner", msg != null && msg.toString().contains("dev.testkit"), String.valueOf(msg));
        } finally {
            stop.countDown();
            stray.join(2000);
        }
        Object after = Mod.staticCall(guard, "check", main);
        c.check("once it ends the guard no longer names it", after == null || !after.toString().contains("Thread-fake-http"), String.valueOf(after));
        String dump = "Description: Watchdog

java.lang.Error: Watchdog (Client shutdown from post-main)

"
                + "\"DestroyJavaVM\" prio=5 Id=1184 RUNNABLE

"
                + "\"Thread-100\" prio=10 Id=1128 WAITING on x
"
                + "	at java.base@25.0.1/jdk.internal.misc.Unsafe.park(Native Method)
"
                + "	at platform/java.net.http@25.0.1/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:133)
"
                + "	at knot//xyz.yourboykyle.secretroutes.utils.APIUtils.addMember(APIUtils.java:60)

"
                + "\"AWT-Windows\" daemon prio=6 Id=257 RUNNABLE
"
                + "	at knot//com.other.Thing.run(Thing.java:1)
";
        Object held = Mod.staticCall(guard, "heldBy", dump);
        c.eq("the report names the holder", "Held open by thread \"Thread-100\" (xyz.yourboykyle.secretroutes.utils)", held);
        c.eq("another report names nothing", null, Mod.staticCall(guard, "heldBy", dump.replace("post-main", "elsewhere")));
        Object sum = Mod.staticCall(FEATURE, "summary", dump, false);
        c.check("the summary starts with it", sum.toString().startsWith("Held open by thread \"Thread-100\""), String.valueOf(sum));
    }
}
