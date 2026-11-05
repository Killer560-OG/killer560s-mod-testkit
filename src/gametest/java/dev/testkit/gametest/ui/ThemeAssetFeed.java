package dev.testkit.gametest.ui;

import com.sun.net.httpserver.HttpServer;
import dev.testkit.gametest.Machine;
import dev.testkit.gametest.mod.Mod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The GitHub release the mod downloads its theme pictures and music from (themedl-1104), played by the testkit: serves
 * {@code <mod checkout>/theme-assets/<name>} for any path ending in a release asset's name, through the mod's own
 * {@code killer560.net.theme-assets} override (the run is offline; an override host is still allowed). One server for
 * the client's life. Without a mod checkout ({@link Machine#modSource}) holding theme-assets/ it is not
 * {@link #available}, and the cases that need the full files check the in-jar previews instead.
 */
public final class ThemeAssetFeed {

    static final String ASSETS = "mainmenu.ThemeAssets";

    private static HttpServer server;
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final java.util.Map<String, AtomicInteger> BY_NAME = new ConcurrentHashMap<>();
    /** Names served with one byte flipped (a bad sha256). */
    public static final Set<String> CORRUPT = ConcurrentHashMap.newKeySet();

    private ThemeAssetFeed() {
    }

    /** The folder served, or null when no mod checkout with theme-assets/ is configured. */
    public static Path folder() {
        Path src = Machine.modSource();
        Path dir = src == null ? null : src.resolve("theme-assets");
        return dir != null && Files.isDirectory(dir) ? dir : null;
    }

    public static boolean available() {
        return folder() != null;
    }

    /** Requests served so far. */
    public static int hits() {
        return HITS.get();
    }

    /** Requests served for one asset name (the title screen asks for its own look's files meanwhile). */
    public static int hits(String name) {
        AtomicInteger n = BY_NAME.get(name);
        return n == null ? 0 : n.get();
    }

    /** Starts the server once and points the mod at it. */
    public static synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            try {
                HITS.incrementAndGet();
                String path = ex.getRequestURI().getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                BY_NAME.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                Path dir = folder();
                Path f = dir == null || name.isEmpty() || name.contains("..") ? null : dir.resolve(name);
                if (f == null || !Files.isRegularFile(f)) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] out = Files.readAllBytes(f);
                if (CORRUPT.contains(name)) {
                    out[out.length / 2] ^= 0x5A;
                }
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
            } catch (IOException | RuntimeException ignored) {
                // never throw inside the stub
            } finally {
                ex.close();
            }
        });
        s.start();
        server = s;
        System.setProperty("killer560.net.theme-assets", "http://127.0.0.1:" + s.getAddress().getPort());
    }

    /** Client thread: the registered size of picture {@code name}'s texture, {w, h}, or {-1, -1} when not registered. */
    public static int[] textureSize(net.minecraft.client.Minecraft mc, String name) {
        Object tex = PaintedScenesSuite.registered(mc, net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "killer560smod", "textures/gui/" + name + ".webp"));
        if (tex == null) {
            return new int[]{-1, -1};
        }
        try {
            Object gpu = tex.getClass().getMethod("getTexture").invoke(tex);
            return new int[]{(Integer) gpu.getClass().getMethod("getWidth", int.class).invoke(gpu, 0),
                    (Integer) gpu.getClass().getMethod("getHeight", int.class).invoke(gpu, 0)};
        } catch (ReflectiveOperationException e) {
            return new int[]{-1, -1};
        }
    }

    /**
     * Client thread: builds Menu Theme's main menu rows, clicks "Delete Unused Downloaded Themes" and then {@code yes} or
     * No on the question it opens. Returns null when both clicks landed, else what went wrong.
     */
    public static String pressDelete(net.minecraft.client.Minecraft mc, boolean yes) {
        List<net.minecraft.client.gui.components.AbstractWidget> rows = new java.util.ArrayList<>();
        Mod.staticCall("mainmenu.MainMenuSettings", "build", rows, 0, 0, 300, (Runnable) () -> { });
        net.minecraft.client.gui.components.AbstractWidget button = null;
        for (net.minecraft.client.gui.components.AbstractWidget w : rows) {
            if (ModScreenDriver.label(w).equals("Delete Unused Downloaded Themes")) {
                button = w;
            }
        }
        if (button == null) {
            return "no Delete Unused Downloaded Themes row";
        }
        if (!ModScreenDriver.press(button)) {
            return "the row did not take the click";
        }
        net.minecraft.client.gui.screens.Screen s = dev.testkit.compat.McCompat.screen(mc);
        if (!(s instanceof net.minecraft.client.gui.screens.ConfirmScreen)) {
            return "the row opened " + s + ", not a question";
        }
        String want = yes ? net.minecraft.network.chat.CommonComponents.GUI_YES.getString()
                : net.minecraft.network.chat.CommonComponents.GUI_NO.getString();
        for (Object o : s.children()) {
            if (o instanceof net.minecraft.client.gui.components.AbstractWidget w && ModScreenDriver.label(w).equals(want)) {
                return ModScreenDriver.press(w) ? null : "the " + want + " button did not take the click";
            }
        }
        return "the question has no " + want + " button";
    }

    /** Every asset's state now, "name=STATE ...". */
    @SuppressWarnings("unchecked")
    public static String states() {
        StringBuilder sb = new StringBuilder();
        for (String n : (List<String>) Mod.staticCall(ASSETS, "namesForTest")) {
            sb.append(n).append('=').append(Mod.staticCall(ASSETS, "stateForTest", n)).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Has the mod download {@code names} (all assets when empty) from this server and waits until each is READY. Forgets
     * earlier failures first (the boot-time request was refused offline before the server existed). Returns null when
     * every one is ready, else what is not. Needs the custom title screen on (downloads are off without it).
     */
    @SuppressWarnings("unchecked")
    public static String fetch(long timeoutMs, Runnable tick, String... names) throws Exception {
        if (!available()) {
            return "no mod checkout with theme-assets/ (testkit.modSource)";
        }
        start();
        Mod.staticCall(ASSETS, "resetForTest");
        List<String> want = names.length == 0 ? (List<String>) Mod.staticCall(ASSETS, "namesForTest") : List.of(names);
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            String missing = null;
            for (String n : want) {
                Mod.staticCall(ASSETS, "request", n);
                if (!"READY".equals(Mod.staticCall(ASSETS, "stateForTest", n))) {
                    missing = n + " is " + Mod.staticCall(ASSETS, "stateForTest", n) + " ("
                            + Mod.staticCall(ASSETS, "lastResultForTest") + ")";
                }
            }
            if (missing == null || System.currentTimeMillis() > end) {
                return missing;
            }
            tick.run(); // the client only runs while the test thread waits ticks
        }
    }
}
