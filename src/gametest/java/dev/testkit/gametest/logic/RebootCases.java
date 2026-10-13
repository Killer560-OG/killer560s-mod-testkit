package dev.testkit.gametest.logic;

import dev.testkit.gametest.ModUnderTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 991-993: the mod's /reboot (killer560, 2026-10-13: "make the command /reboot that closes the game and will reboot it
 * once it is fully closed"). Nothing here starts Prism or touches a Prism instance: detection is fed made-up parent
 * paths and temp folders, and the relauncher is pointed at a stand-in launcher (this JVM's java running a one-file
 * source program that appends its arguments to a file) and a dummy process playing the game.
 *
 * <pre>
 * 991-logic-reboot-detect          Prism / PolyMC / MultiMC parents with an instance folder (spaces and parentheses
 *                                  in its name, minecraft and .minecraft) give that folder's name as the instance id;
 *                                  java, the official launcher, CurseForge, a dead parent, a javaw inside a Prism
 *                                  folder, and a folder without instance.cfg are all refused; this client is refused
 * 992-logic-reboot-helper          the real relauncher waits for the dummy game to exit (and for an Auto Update
 *                                  installer started after it), then runs the launcher with --launch "<id>" exactly once
 * 993-logic-reboot-helper-timeout  a game that outlives the timeout: the relauncher exits 2 and never runs the launcher
 * </pre>
 */
final class RebootCases {

    private RebootCases() {
    }

    static final String FEAT = "com.killer560.hub.reboot.RebootFeature";
    static final String ID = "26.1.2 (Mod Only Test)";

    static String detect(String parent, Path gameDir) {
        return (String) ModUnderTest.staticCall(FEAT, "detectForTest", new Class<?>[]{String.class, String.class},
                new Object[]{parent, gameDir == null ? null : gameDir.toString()});
    }

    static void detect(LogicCase c) throws Exception {
        Path root = AutoUpdateCases.tempDir("reboot-detect");
        try {
            Path inst = Files.createDirectories(root.resolve("instances").resolve(ID));
            Files.writeString(inst.resolve("instance.cfg"), "InstanceType=OneSix\n");
            Path mc = Files.createDirectories(inst.resolve("minecraft"));
            Path inst2 = Files.createDirectories(root.resolve("instances").resolve("Old Style (1.8.9) x"));
            Files.writeString(inst2.resolve("instance.cfg"), "InstanceType=OneSix\n");
            Path dotMc = Files.createDirectories(inst2.resolve(".minecraft"));
            Path noCfg = Files.createDirectories(root.resolve("instances").resolve("No Cfg").resolve("minecraft"));
            String prism = "C:\\Users\\Someone\\AppData\\Local\\Programs\\PrismLauncher\\prismlauncher.exe";

            c.eq("Prism parent, instance with spaces and parentheses", "OK|Prism Launcher|" + ID, detect(prism, mc));
            c.eq("Prism parent, a trailing separator on the game dir", "OK|Prism Launcher|" + ID,
                    detect(prism, Path.of(mc + java.io.File.separator)));
            c.eq("Prism parent, the older .minecraft folder", "OK|Prism Launcher|Old Style (1.8.9) x",
                    detect(prism, dotMc));
            c.eq("upper-case PrismLauncher.EXE", "OK|Prism Launcher|" + ID,
                    detect("D:\\Portable\\PrismLauncher.EXE", mc));
            c.eq("PolyMC", "OK|PolyMC|" + ID, detect("C:\\PolyMC\\polymc.exe", mc));
            c.eq("MultiMC", "OK|MultiMC|" + ID, detect("C:\\MultiMC\\MultiMC.exe", mc));
            c.eq("Linux prismlauncher", "OK|Prism Launcher|" + ID, detect("/usr/bin/prismlauncher", mc));

            String[][] refused = {
                    {"the gradle / testkit java", "C:\\Program Files\\Eclipse Adoptium\\jdk-25\\bin\\java.exe"},
                    {"the official launcher", "C:\\XboxGames\\Minecraft Launcher\\Content\\Minecraft.exe"},
                    {"the old official launcher", "C:\\Program Files (x86)\\Minecraft Launcher\\MinecraftLauncher.exe"},
                    {"CurseForge", "C:\\Users\\Someone\\AppData\\Local\\Programs\\CurseForge Windows\\CurseForge.exe"},
                    {"a javaw inside a Prism folder", "C:\\PrismLauncher\\java\\bin\\javaw.exe"},
                    {"a launcher whose name only contains prism", "C:\\prismlauncher-updater.exe"}};
            for (String[] r : refused) {
                String out = detect(r[1], mc);
                c.check(r[0] + " is refused as not Prism", out.startsWith("NO|this game was not started by Prism"),
                        out);
            }
            String dead = detect(null, mc);
            c.check("a parent that has exited is refused", dead.startsWith("NO|the program that started this game"),
                    dead);
            String cfg = detect(prism, noCfg);
            c.check("a folder with no instance.cfg is refused", cfg.startsWith("NO|") && cfg.contains("instance"), cfg);
            String mods = detect(prism, inst.resolve("mods"));
            c.check("a game dir that is not minecraft/.minecraft is refused", mods.startsWith("NO|"), mods);
            String bare = detect(prism, inst);
            c.check("the instance folder itself is refused", bare.startsWith("NO|"), bare);

            // This client: started by gradle's java, never by Prism.
            Object cur = ModUnderTest.staticCall(FEAT, "detectCurrent");
            boolean ok = (Boolean) ModUnderTest.call(cur, "ok", new Class<?>[0], new Object[0]);
            String reason = (String) ModUnderTest.call(cur, "reason", new Class<?>[0], new Object[0]);
            c.note("this client: " + reason);
            c.check("this test client is not taken for a Prism game", !ok, String.valueOf(cur));
        } finally {
            AutoUpdateCases.deleteTree(root);
        }
    }

    // ---- the relauncher process -------------------------------------------------------------------------------------

    /** The stand-in launcher: appends its arguments after the first (the output file), one invocation per line. */
    static List<String> fakeLauncher(Path dir, Path out) throws Exception {
        Path src = dir.resolve("FakePrism.java");
        Files.writeString(src, "import java.nio.file.*; public class FakePrism { public static void main(String[] a)"
                + " throws Exception { Files.writeString(Path.of(a[0]), String.join(\"|\", java.util.Arrays.copyOfRange(a,"
                + " 1, a.length)) + \"\\n\", StandardOpenOption.CREATE, StandardOpenOption.APPEND); } }\n");
        return List.of(src.toString(), out.toString());
    }

    static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    static long spawn(long gamePid, List<String> prefix, Path work, Path pidFile, long timeoutS) {
        return (Long) ModUnderTest.staticCall(FEAT, "spawnHelper",
                new Class<?>[]{long.class, String.class, List.class, String.class, Path.class, Path.class, long.class},
                new Object[]{gamePid, java(), prefix, ID, work, pidFile, timeoutS});
    }

    static List<String> lines(Path p) throws Exception {
        return Files.exists(p) ? Files.readAllLines(p, StandardCharsets.UTF_8) : List.of();
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Waits for the relauncher to exit (60 s), killing it and failing with its log if it does not. */
    static String waitHelper(long pid, Path work) {
        if (!AutoUpdateCases.waitUntil(() -> !alive(pid), 60)) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            throw new AssertionError("relauncher " + pid + " still running after 60 s; reboot.log: "
                    + AutoUpdateCases.read(work.resolve("reboot.log")));
        }
        return AutoUpdateCases.read(work.resolve("reboot.log"));
    }

    static void helper(LogicCase c) throws Exception {
        Path root = AutoUpdateCases.tempDir("reboot-helper");
        Process game = null;
        Process installer = null;
        try {
            Path work = root.resolve("work");
            Path out = root.resolve("launches.txt");
            List<String> prefix = fakeLauncher(root, out);

            game = AutoUpdateCases.dummyParent(root, 5);
            long helperPid = spawn(game.pid(), prefix, work, root.resolve("installer.pid"), 120);
            c.check("relauncher started", helperPid > 0, "pid " + helperPid);
            // Auto Update's installer starts at CLIENT_STOPPING, AFTER the relauncher; it lives past the game.
            installer = AutoUpdateCases.dummyParent(root, 10);
            Files.writeString(root.resolve("installer.pid"), Long.toString(installer.pid()));

            long t0 = System.nanoTime();
            AutoUpdateCases.waitUntil(() -> System.nanoTime() - t0 > 3_000_000_000L, 10);
            c.check("dummy game still running at +3 s (the wait is real)", game.isAlive());
            c.check("relauncher still waiting at +3 s", alive(helperPid));
            c.eq("launcher not run while the game runs", List.of(), lines(out));

            Process g = game;
            c.check("dummy game exits", AutoUpdateCases.waitUntil(() -> !g.isAlive(), 30));
            c.check("installer still running after the game exited", installer.isAlive());
            c.eq("launcher not run while the installer runs", List.of(), lines(out));
            Process inst = installer;
            c.check("dummy installer exits", AutoUpdateCases.waitUntil(() -> !inst.isAlive(), 30));

            String log = waitHelper(helperPid, work);
            c.note("reboot.log:\n" + log);
            c.check("log: waited for the game", log.contains("waiting for game " + game.pid()), log);
            int exited = log.indexOf("game " + game.pid() + " has exited");
            int waitedInstaller = log.indexOf("waiting for the Auto Update installer (pid " + installer.pid() + ")");
            int ran = log.indexOf("running [");
            c.check("log: game exit, then the installer wait, then the launcher", exited >= 0
                    && waitedInstaller > exited && ran > waitedInstaller, log);
            c.check("log shows exit 0", log.contains("exit 0"), log);
            c.check("the launcher ran", AutoUpdateCases.waitUntil(() -> {
                try {
                    return !lines(out).isEmpty();
                } catch (Exception e) {
                    return false;
                }
            }, 30));
            // Give a second launch every chance to show up before counting.
            long t1 = System.nanoTime();
            AutoUpdateCases.waitUntil(() -> System.nanoTime() - t1 > 3_000_000_000L, 10);
            c.eq("launcher run exactly once, with --launch and the instance id as ONE argument",
                    List.of("--launch|" + ID), lines(out));
        } finally {
            if (game != null) {
                game.destroyForcibly();
            }
            if (installer != null) {
                installer.destroyForcibly();
            }
            AutoUpdateCases.deleteTree(root);
        }
    }

    static void timeout(LogicCase c) throws Exception {
        Path root = AutoUpdateCases.tempDir("reboot-timeout");
        Process game = null;
        try {
            Path work = root.resolve("work");
            Path out = root.resolve("launches.txt");
            List<String> prefix = fakeLauncher(root, out);
            game = AutoUpdateCases.dummyParent(root, 60);
            long t0 = System.nanoTime();
            long helperPid = spawn(game.pid(), prefix, work, null, 3);
            String log = waitHelper(helperPid, work);
            double took = (System.nanoTime() - t0) / 1e9;
            c.note(String.format(java.util.Locale.ROOT, "relauncher gave up after %.1f s; reboot.log:%n%s", took, log));
            c.check("the dummy game is still running (the timeout, not an exit, ended the wait)", game.isAlive());
            c.check("gave up within 3-20 s", took >= 3 && took < 20, String.valueOf(took));
            c.check("log says it timed out", log.contains("game still running after 3 s - not relaunching"), log);
            c.check("log shows exit 2", log.contains("exit 2"), log);
            long t1 = System.nanoTime();
            AutoUpdateCases.waitUntil(() -> System.nanoTime() - t1 > 2_000_000_000L, 10);
            c.eq("launcher never run", List.of(), lines(out));
        } finally {
            if (game != null) {
                game.destroyForcibly();
            }
            AutoUpdateCases.deleteTree(root);
        }
    }
}
