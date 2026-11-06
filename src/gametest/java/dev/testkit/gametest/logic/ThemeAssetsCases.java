package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.ThemeAssetFeed;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Theme assets on demand (mod themedl-1104, killer560 2026-11-04): the jar carries only previews of the main menu
 * pictures; the full pictures and the theme loops download from a GitHub release when a theme is picked, sha256-checked,
 * cached once per PC. The release is {@link ThemeAssetFeed} (a local server through the mod's own
 * {@code killer560.net.theme-assets} override), and every case asserts that a download really happened (or really did
 * not) through the feed's hit count, the mod's own download counter and the files on disk.
 * <ul>
 *   <li>offline: no release reachable, the preview is drawn and nothing breaks; the failure is not retried every frame.</li>
 *   <li>download: fetched from the feed, kept under its hash, the next look finds it cached (no second download), and the
 *       drawn texture swaps from the preview to the full picture.</li>
 *   <li>badsha: a file that does not match its sha256 is thrown away (no file, no .part) and a good one then lands.</li>
 *   <li>switch: picking Dark downloads Dark's picture and loop (not Amber's), and Dark plays that loop; switching back
 *       to a cached theme downloads nothing.</li>
 *   <li>oldconfig: an install that chose a theme before the update downloads it at its first start (the boot hook alone,
 *       no drawing).</li>
 *   <li>notheme: with "no custom title screen" nothing is ever requested from the network (ModNet's attempt log).</li>
 *   <li>delete: Menu Theme's Delete Unused Downloaded Themes asks first (No keeps everything), Yes deletes all but the
 *       current theme's files, and a deleted theme downloads again when picked.</li>
 * </ul>
 */
final class ThemeAssetsCases {

    static ClientGameTestContext ctx;

    private static final String A = "mainmenu.ThemeAssets";
    private static final String PICS = "mainmenu.WebpPictures";
    private static final String SCENES = "mainmenu.MainMenuScenes";
    private static final String MM = "mainmenu.MainMenuThemeConfig";
    private static final String MENU = "gui.MenuConfig";

    private ThemeAssetsCases() {
    }

    // ---- helpers

    private static <T> T on(java.util.function.Function<net.minecraft.client.Minecraft, T> f) {
        return ctx.computeOnClient(f::apply);
    }

    private static Path cacheDir() {
        return (Path) Mod.staticCall(A, "dir");
    }

    /** Empties the cache folder and forgets every state and counter. */
    private static void clean() throws Exception {
        Path dir = cacheDir();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path f : files) {
                    Files.deleteIfExists(f);
                }
            }
        }
        Mod.staticCall(A, "resetForTest");
    }

    private static List<String> cached() throws Exception {
        List<String> out = new java.util.ArrayList<>();
        Path dir = cacheDir();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path f : files) {
                    out.add(f.getFileName().toString());
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String state(String name) {
        return String.valueOf(Mod.staticCall(A, "stateForTest", name));
    }

    /** ModNet attempts so far whose URL ends with {@code suffix}. */
    @SuppressWarnings("unchecked")
    private static long attempts(String suffix) {
        return ((List<String>) Mod.staticCall("util.ModNet", "attemptsForTest")).stream().filter(u -> u.endsWith(suffix)).count();
    }

    private static int downloads() {
        return (Integer) Mod.staticCall(A, "downloadsForTest");
    }

    private static boolean waitFor(long ms, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (ok.getAsBoolean()) {
                return true;
            }
            ctx.waitTicks(2);
        }
        return ok.getAsBoolean();
    }

    private static String sha256(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    /** The drawing path's own per-frame call for {@code picture} (the scene's ensure), on the client thread. */
    private static boolean drawPicture(String picture) {
        return on(mc -> (Boolean) Mod.staticCall(PICS, "ensure", Mod.staticCall(PICS, "id", picture)));
    }

    /** The current look's picture and loop, as the title screen asks for them each frame and tick. */
    private static void drawLook() {
        on(mc -> {
            Object look = Mod.staticCall("mainmenu.MainMenuLook", "current");
            Object id = Mod.staticCall(SCENES, "pictureFor", look);
            if (id != null) {
                Mod.staticCall(PICS, "ensure", id);
            }
            Mod.staticCall("menuthemes.ThemeMusic", "tick", mc);
            return null;
        });
    }

    private static Object menuTheme() {
        return on(mc -> Mod.call(Mod.cfg(MENU), "getTheme"));
    }

    private static void setMenuTheme(Object theme) {
        on(mc -> {
            Mod.call(Mod.cfg(MENU), "setTheme", theme);
            return null;
        });
    }

    private static Object theme(String name) {
        return Mod.enumValue("gui.MenuTheme", name);
    }

    /** Runs {@code body} with the main menu following the mod menu's theme {@code theme}, Fancy, custom title screen on and
     *  Theme Music on; puts all of it back after. */
    private static void withTheme(String theme, LogicCase.Body body, LogicCase c) throws Exception {
        Object oldTheme = menuTheme();
        Object[] old = on(mc -> {
            Object m = Mod.cfg(MM);
            Object extras = Mod.cfg("menuthemes.ThemeExtrasConfig");
            Object[] o = {Mod.call(m, "isEnabled"), Mod.call(m, "getStyle"), Mod.call(m, "getScene"), Mod.call(m, "getSceneMode"),
                    Mod.call(extras, "isMusicOn")};
            Mod.call(m, "setThemed", true);
            Mod.call(m, "setStyle", Mod.enumValue(MM + "$Style", "MATCH"));
            Mod.call(m, "setScene", Mod.enumValue(MM + "$Scene", "AUTO"));
            Mod.call(m, "setSceneMode", Mod.enumValue(MM + "$SceneMode", "FANCY"));
            Mod.call(extras, "setMusicOn", true);
            return o;
        });
        setMenuTheme(theme(theme));
        try {
            body.run(c);
        } finally {
            setMenuTheme(oldTheme);
            on(mc -> {
                Object m = Mod.cfg(MM);
                Mod.call(m, "setThemed", (Boolean) old[0]);
                Mod.call(m, "setStyle", old[1]);
                Mod.call(m, "setScene", old[2]);
                Mod.call(m, "setSceneMode", old[3]);
                Mod.call(Mod.cfg("menuthemes.ThemeExtrasConfig"), "setMusicOn", old[4]);
                return null;
            });
        }
    }

    private static boolean needFeed(LogicCase c) throws Exception {
        if (!ThemeAssetFeed.available()) {
            c.skip("the theme asset feed", "no mod checkout with theme-assets/ (testkit.modSource / TESTKIT_MOD_SOURCE)");
            return false;
        }
        ThemeAssetFeed.start();
        return true;
    }

    // ---- cases

    /** No release reachable: the preview is drawn, the asset FAILS once and is not retried every frame. */
    static void offline(LogicCase c) throws Exception {
        String prop = System.getProperty("killer560.net.theme-assets");
        System.clearProperty("killer560.net.theme-assets");
        try {
            clean();
            boolean wasFull = on(mc -> (Boolean) Mod.staticCall(PICS, "fullForTest", "ocean_falls"));
            long tried0 = attempts("/ocean_falls.webp");
            Mod.staticCall(A, "request", "ocean_falls.webp");
            boolean failed = waitFor(30_000, () -> state("ocean_falls.webp").equals("FAILED"));
            c.check("offline the download fails (state FAILED)", failed,
                    state("ocean_falls.webp") + " " + Mod.staticCall(A, "lastResultForTest"));
            c.eq("one download tried (refused: offline)", 1L, attempts("/ocean_falls.webp") - tried0);
            c.check("nothing cached", cached().isEmpty(), String.valueOf(cached()));
            on(mc -> {
                Mod.staticCall(PICS, "preload", Mod.staticCall(PICS, "id", "ocean_falls"));
                return null;
            });
            boolean drawn = waitFor(60_000, () -> drawPicture("ocean_falls"));
            c.check("the picture is drawn offline (the preview)", drawn);
            // Amber's in-jar picture is the full-size medium-quality one (method 6 once wrote alpha the decoder threw on).
            on(mc -> {
                Mod.staticCall(PICS, "preload", Mod.staticCall(PICS, "id", "amber_aurora"));
                return null;
            });
            c.check("Amber's in-jar picture decodes and is drawn", waitFor(60_000, () -> drawPicture("amber_aurora")));
            c.check("the themed main menu did not switch itself off after an error",
                    on(mc -> (Boolean) Mod.staticCall("mainmenu.MainMenuTheme", "active")));
            for (int i = 0; i < 40; i++) {
                drawPicture("ocean_falls");
            }
            c.eq("40 more frames do not retry the download", 1L, attempts("/ocean_falls.webp") - tried0);
            // An earlier case can leave the full picture decoding in the background (WebpPictures' preload reads a cached
            // file before clean() deleted it); no download was tried above, so a full texture here came from that.
            if (wasFull || on(mc -> (Boolean) Mod.staticCall(PICS, "fullForTest", "ocean_falls"))) {
                c.skip("the preview's size", "ocean_falls was already swapped to the full picture earlier in this client");
            } else {
                int[] size = on(mc -> ThemeAssetFeed.textureSize(mc, "ocean_falls"));
                c.check("the drawn texture is the in-jar preview, 640x720", size[0] == 640 && size[1] == 720,
                        size[0] + "x" + size[1]);
                c.check("not marked full", !on(mc -> (Boolean) Mod.staticCall(PICS, "fullForTest", "ocean_falls")));
            }
        } finally {
            if (prop != null) {
                System.setProperty("killer560.net.theme-assets", prop);
            }
        }
    }

    /** Downloaded from the feed, cached under its hash, found cached next time, and the texture swaps to the full one. */
    static void download(LogicCase c) throws Exception {
        if (!needFeed(c)) {
            return;
        }
        withTheme("OCEAN", cc -> {
            clean();
            int hits0 = ThemeAssetFeed.hits("ocean_falls.webp");
            Mod.staticCall(A, "request", "ocean_falls.webp");
            c.check("ocean_falls.webp downloads", waitFor(60_000, () -> state("ocean_falls.webp").equals("READY")),
                    state("ocean_falls.webp") + " " + Mod.staticCall(A, "lastResultForTest"));
            c.eq("one download, from the feed", 1, ThemeAssetFeed.hits("ocean_falls.webp") - hits0);
            Path f = (Path) Mod.staticCall(A, "fileForTest", "ocean_falls.webp");
            Path src = ThemeAssetFeed.folder().resolve("ocean_falls.webp");
            c.check("cached in the shared store under its hash", Files.isRegularFile(f) && f.startsWith(cacheDir())
                    && f.getFileName().toString().startsWith("ocean_falls-"), String.valueOf(f));
            c.eq("the cached file is the release file (sha256)", sha256(src), sha256(f));
            c.check("no .part left", waitFor(10_000, () -> cached().stream().noneMatch(n -> n.endsWith(".part"))), String.valueOf(cached()));

            // Next start: a scan finds it without the network.
            Mod.staticCall(A, "resetForTest");
            int hits1 = ThemeAssetFeed.hits("ocean_falls.webp");
            Mod.staticCall(A, "scan");
            Mod.staticCall(A, "awaitIdleForTest");
            Mod.staticCall(A, "request", "ocean_falls.webp");
            Mod.staticCall(A, "awaitIdleForTest");
            c.eq("cached: READY with no download", "READY", state("ocean_falls.webp"));
            c.eq("the feed was not asked again", hits1, ThemeAssetFeed.hits("ocean_falls.webp"));

            // The drawn texture swaps from the preview to the full picture.
            boolean full = waitFor(90_000, () -> drawPicture("ocean_falls")
                    && on(mc -> (Boolean) Mod.staticCall(PICS, "fullForTest", "ocean_falls")));
            int[] size = on(mc -> ThemeAssetFeed.textureSize(mc, "ocean_falls"));
            c.check("the picture swapped to the downloaded one", full);
            c.check("the drawn texture is the full 2560x2880", size[0] == 2560 && size[1] == 2880, size[0] + "x" + size[1]);
        }, c);
    }

    /** A file that does not match its sha256 is rejected and leaves nothing behind; the good one then lands. */
    static void badSha(LogicCase c) throws Exception {
        if (!needFeed(c)) {
            return;
        }
        withTheme("DARK", cc -> {
            clean();
            ThemeAssetFeed.CORRUPT.add("milkyway.webp");
            try {
                Mod.staticCall(A, "request", "milkyway.webp");
                c.check("a corrupted download FAILS", waitFor(60_000, () -> state("milkyway.webp").equals("FAILED")),
                        state("milkyway.webp"));
                c.eq("rejected for its hash", 1, (Integer) Mod.staticCall(A, "rejectedForTest"));
                // rejectedForTest counts only checksum mismatches; lastResultForTest is not checked, the title screen's own
                // request for Dark's loop lands meanwhile and overwrites it.
                c.check("nothing kept (no file, no .part)", cached().stream().noneMatch(f -> f.startsWith("milkyway")),
                        String.valueOf(cached()));
                c.check("not ready", Mod.staticCall(A, "ready", "milkyway.webp") == null);
            } finally {
                ThemeAssetFeed.CORRUPT.remove("milkyway.webp");
            }
            Mod.staticCall(A, "resetForTest");
            Mod.staticCall(A, "request", "milkyway.webp");
            c.check("served correctly it lands", waitFor(60_000, () -> state("milkyway.webp").equals("READY")),
                    state("milkyway.webp"));
            c.eq("nothing rejected the second time", 0, (Integer) Mod.staticCall(A, "rejectedForTest"));
        }, c);
    }

    /** Picking Viridian downloads its picture and loop (not Amber's) through the title screen's own calls, and Dark then
     *  plays that loop; going back to a cached theme downloads nothing. */
    static void themeSwitch(LogicCase c) throws Exception {
        if (!needFeed(c)) {
            return;
        }
        withTheme("AMBER", cc -> {
            clean();
            int amber0 = ThemeAssetFeed.hits("amber_aurora.webp");
            int pic0 = ThemeAssetFeed.hits("green_boss_target.webp");
            int loop0 = ThemeAssetFeed.hits("theme_green.ogg");
            // A picture this session already shows in full keeps its texture and is not fetched again (an earlier suite).
            boolean picFull = on(mc -> (Boolean) Mod.staticCall(PICS, "fullForTest", "green_boss_target"));
            if (picFull) {
                c.skip("Viridian's picture download", "this session already shows the full picture");
            }
            setMenuTheme(theme("GREEN"));
            boolean both = waitFor(60_000, () -> {
                drawLook();
                return (picFull || state("green_boss_target.webp").equals("READY")) && state("theme_green.ogg").equals("READY");
            });
            c.check("Viridian's picture and loop download", both, ThemeAssetFeed.states());
            c.eq("one download each, from the feed", (picFull ? 0 : 1) + 1,
                    ThemeAssetFeed.hits("green_boss_target.webp") - pic0 + ThemeAssetFeed.hits("theme_green.ogg") - loop0);
            c.eq("Amber's picture was not downloaded after the switch", amber0, ThemeAssetFeed.hits("amber_aurora.webp"));
            boolean title = on(mc -> dev.testkit.compat.McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
            if (title) {
                boolean plays = waitFor(10_000, () -> {
                    drawLook();
                    return "killer560smod:thememusic/theme_green".equals(Mod.staticCall("menuthemes.ThemeMusic", "currentKey"));
                });
                c.check("the title screen plays Viridian's downloaded loop", plays,
                        String.valueOf(Mod.staticCall("menuthemes.ThemeMusic", "currentKey")));
            } else {
                c.skip("Viridian's loop playing", "not on the title screen");
            }
            setMenuTheme(theme("AMBER"));
            setMenuTheme(theme("GREEN"));
            int green0 = ThemeAssetFeed.hits("theme_green.ogg");
            Mod.staticCall(A, "resetForTest");
            Mod.staticCall(A, "scan");
            Mod.staticCall(A, "awaitIdleForTest");
            for (int i = 0; i < 20; i++) {
                drawLook();
            }
            Mod.staticCall(A, "awaitIdleForTest");
            c.eq("back on cached Viridian: its loop is READY", "READY", state("theme_green.ogg"));
            c.eq("back on cached Viridian: no download", green0, ThemeAssetFeed.hits("theme_green.ogg"));
        }, c);
    }

    /** An install that chose Ocean before the update: the boot hook alone (no title screen drawn) downloads its picture
     *  and loop. */
    static void oldConfig(LogicCase c) throws Exception {
        if (!needFeed(c)) {
            return;
        }
        withTheme("OCEAN", cc -> {
            on(mc -> {
                Mod.call(Mod.cfg(MENU), "save");
                Mod.call(Mod.cfg(MM), "save");
                Mod.staticCall(MENU, "load");
                Mod.staticCall(MM, "load");
                return null;
            });
            c.eq("the saved theme reloads as Ocean", "OCEAN", String.valueOf(menuTheme()));
            clean();
            on(mc -> {
                Mod.staticCall(SCENES, "preloadPictures");
                return null;
            });
            boolean both = waitFor(60_000, () -> state("ocean_falls.webp").equals("READY") && state("theme_ocean.ogg").equals("READY"));
            c.check("the first start downloads the chosen theme's picture and loop", both, ThemeAssetFeed.states());
            c.eq("exactly those two", 2, downloads());
        }, c);
    }

    /** "No custom title screen": nothing is ever asked of the network, whatever asks for an asset. Control: on again,
     *  the same request downloads. */
    @SuppressWarnings("unchecked")
    static void noTheme(LogicCase c) throws Exception {
        if (!needFeed(c)) {
            return;
        }
        withTheme("OCEAN", cc -> {
            clean();
            on(mc -> {
                Mod.call(Mod.cfg(MM), "setThemed", false);
                return null;
            });
            int hits0 = ThemeAssetFeed.hits();
            long tried0 = ((List<String>) Mod.staticCall("util.ModNet", "attemptsForTest")).stream()
                    .filter(u -> u.contains("theme-assets")).count();
            on(mc -> {
                Mod.staticCall(SCENES, "preloadPictures");
                Mod.staticCall(A, "requestLook", Mod.staticCall("mainmenu.MainMenuLook", "current"));
                return null;
            });
            for (String n : (List<String>) Mod.staticCall(A, "namesForTest")) {
                Mod.staticCall(A, "request", n);
            }
            for (int i = 0; i < 40; i++) {
                drawLook();
                drawPicture("ocean_falls");
            }
            ctx.waitTicks(40);
            Mod.staticCall(A, "awaitIdleForTest");
            long tried = ((List<String>) Mod.staticCall("util.ModNet", "attemptsForTest")).stream()
                    .filter(u -> u.contains("theme-assets")).count();
            c.eq("no download started", 0, downloads());
            c.eq("ModNet was never asked for a theme asset", tried0, tried);
            c.eq("the feed was never hit", hits0, ThemeAssetFeed.hits());
            c.check("nothing cached", cached().isEmpty(), String.valueOf(cached()));

            on(mc -> {
                Mod.call(Mod.cfg(MM), "setThemed", true);
                return null;
            });
            int control0 = ThemeAssetFeed.hits("ocean_falls.webp");
            Mod.staticCall(A, "request", "ocean_falls.webp");
            c.check("control: switched on, the same request downloads",
                    waitFor(60_000, () -> state("ocean_falls.webp").equals("READY")), state("ocean_falls.webp"));
            c.eq("control: one download, from the feed", 1, ThemeAssetFeed.hits("ocean_falls.webp") - control0);
        }, c);
    }

    /** The Delete Unused Downloaded Themes row: No keeps everything, Yes deletes all but Amber's (the current theme's)
     *  files, and a deleted theme downloads again when picked. */
    @SuppressWarnings("unchecked")
    static void delete(LogicCase c) throws Exception {
        if (!needFeed(c)) {
            return;
        }
        withTheme("AMBER", cc -> {
            clean();
            String why = ThemeAssetFeed.fetch(120_000L, () -> ctx.waitTicks(2));
            c.check("every asset downloaded first", why == null, why);
            List<String> all = cached();
            int n = ((List<String>) Mod.staticCall(A, "namesForTest")).size();
            c.eq("every asset is cached", n, all.size());

            String no = on(mc -> ThemeAssetFeed.pressDelete(mc, false));
            c.check("the row asks, and No is pressed", no == null, no);
            ctx.waitTicks(20);
            Mod.staticCall(A, "awaitIdleForTest");
            c.eq("No deletes nothing", all, cached());

            String yes = on(mc -> ThemeAssetFeed.pressDelete(mc, true));
            c.check("the row asks, and Yes is pressed", yes == null, yes);
            boolean gone = waitFor(10_000, () -> {
                try {
                    return cached().size() == 2;
                } catch (Exception e) {
                    return false;
                }
            });
            List<String> left = cached();
            c.check("only the current theme's two files are kept", gone
                    && left.stream().anyMatch(s -> s.startsWith("amber_aurora-"))
                    && left.stream().anyMatch(s -> s.startsWith("theme_amber-")), String.valueOf(left));
            c.eq("their states are kept", "READY", state("amber_aurora.webp"));
            c.eq("a deleted one is forgotten", "MISSING", state("milkyway.webp"));

            int d0 = ThemeAssetFeed.hits("theme_dark.ogg");
            setMenuTheme(theme("DARK"));
            boolean again = waitFor(60_000, () -> {
                drawLook();
                return state("theme_dark.ogg").equals("READY");
            });
            c.check("a deleted theme downloads again when picked", again, ThemeAssetFeed.states());
            c.eq("it was a real download, from the feed", d0 + 1, ThemeAssetFeed.hits("theme_dark.ogg"));
        }, c);
    }
}
