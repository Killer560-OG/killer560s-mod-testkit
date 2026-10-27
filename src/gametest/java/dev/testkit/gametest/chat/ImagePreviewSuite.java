package dev.testkit.gametest.chat;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.TestMap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;

import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static dev.testkit.gametest.hx.HxKit.Settings;

/**
 * Image Preview (mod imagepreview-1027): hovering a picture link in the open chat shows the picture beside the cursor.
 * One server ({@code 1070-imgprev-session}); every chat line arrives as a real system-chat packet; the pictures come
 * from an HTTP server in this JVM on an ephemeral 127.0.0.1 port, which the mod's test hook
 * {@code killer560.test.imagePreviewHosts} names as trusted, and which stands in for imgur through the mod's
 * {@code imgur-api} / {@code imgur-i} network services. The mouse is the real cursor ({@code setCursorPos}); where a
 * link's letters are on screen comes from vanilla's own chat layout ({@code ChatComponent.captureClickableText}), not
 * from the mod. Every pass is judged on what was drawn (the mod's record of the box AND the pixels in a screenshot),
 * the decoded pixels, and the requests the server saw.
 *
 * <ul>
 *   <li>1071-imgprev-png: a clickable (open_url) link to a 64x32 PNG, left half red, right half blue: the preview
 *       draws at 2:1 with red on the left and blue on the right, in the decoded pixels and in a screenshot; a real left
 *       click still opens vanilla's link prompt.</li>
 *   <li>1072-imgprev-plain-wrap: a plain-text link long enough to wrap over two chat rows: hovering its SECOND row
 *       finds the whole link; hovering a word after it finds none.</li>
 *   <li>1073-imgprev-gif: a 4-frame GIF (100 ms a frame) changes frame over a second of wall time; with Play GIFs off
 *       it holds one frame.</li>
 *   <li>1074-imgprev-refuse: an 11 MB file (with a Content-Length), the same without one (chunked), and a PNG whose
 *       header claims 10000x10000: each shows its refusal, and no texture is made.</li>
 *   <li>1075-imgprev-not-image: a .png link answering text/plain, and a .txt page: fetched once, nothing drawn.</li>
 *   <li>1076-imgprev-cache: after every in-memory picture is dropped, hovering again draws it with no new request
 *       (from the disk cache), and the cache folder holds the file.</li>
 *   <li>1077-imgprev-imgur: imgur.com/a/&lt;id&gt; resolves through the (faked) imgur API to 3 pictures: "1/3", the
 *       wheel steps to "2/3" (a different picture), a middle click opens the viewer, the right arrow steps, Escape is
 *       back in chat; a scheme-less imgur.com/&lt;id&gt; shows its one picture.</li>
 *   <li>1078-imgprev-webp: lossless and lossy WebP decode with the right colors.</li>
 *   <li>1079-imgprev-hosts: without the test hook, Trusted Hosts Only says "Any Host is off" and sends nothing; Any Host
 *       refuses the local address and sends nothing.</li>
 *   <li>1080-imgprev-off: with the switch off, nothing draws and nothing is requested.</li>
 *   <li>1081-imgprev-cost: the feature's own work per chat frame, hovering a shown picture and hovering nothing.</li>
 * </ul>
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class ImagePreviewSuite implements FabricClientGameTest {

    static final String SESSION = "1070-imgprev-session";
    static final String FEATURE = "imagepreview.ImagePreviewFeature";
    static final String CFG = "imagepreview.ImagePreviewConfig";
    static final String HOSTS_PROP = "killer560.test.imagePreviewHosts";
    static final int RED = 0xFFFF0000;
    static final int BLUE = 0xFF0000FF;
    static final int GREEN = 0xFF00FF00;

    static Fake fake;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Session.run(ctx, SESSION,
                (server, s) -> TestMap.on(server)
                        .platform(-10710, 100, 10710, 6)
                        .catchFloor(90)
                        .survival()
                        .spawn(-10709.5, 10710.5, 0f)
                        .build(),
                s -> {
                    ModUnderTest.require("killer560smod");
                    try (Fake f = new Fake()) {
                        fake = f;
                        s.test("1071-imgprev-png", ImagePreviewSuite::png);
                        s.test("1072-imgprev-plain-wrap", ImagePreviewSuite::plainWrap);
                        s.test("1073-imgprev-gif", ImagePreviewSuite::gif);
                        s.test("1074-imgprev-refuse", ImagePreviewSuite::refuse);
                        s.test("1075-imgprev-not-image", ImagePreviewSuite::notImage);
                        s.test("1076-imgprev-cache", ImagePreviewSuite::cache);
                        s.test("1077-imgprev-imgur", ImagePreviewSuite::imgur);
                        s.test("1078-imgprev-webp", ImagePreviewSuite::webp);
                        s.test("1079-imgprev-hosts", ImagePreviewSuite::hosts);
                        s.test("1080-imgprev-off", ImagePreviewSuite::off);
                        s.test("1081-imgprev-cost", ImagePreviewSuite::cost);
                    } catch (IOException e) {
                        throw new AssertionError("the picture server did not start", e);
                    }
                });
    }

    // =================================================================================================================
    // the picture server
    // =================================================================================================================

    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final int port;
        final String base;
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final Map<String, String> types = new ConcurrentHashMap<>();
        final Set<String> chunked = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        private final String oldApi;
        private final String oldI;
        private final String oldHosts;

        Fake() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            base = "http://127.0.0.1:" + port;
            server.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                byte[] body = files.get(path);
                try {
                    if (body == null) {
                        byte[] nf = "not found".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(404, nf.length);
                        ex.getResponseBody().write(nf);
                        return;
                    }
                    ex.getResponseHeaders().set("Content-Type", types.getOrDefault(path, "application/octet-stream"));
                    ex.sendResponseHeaders(200, chunked.contains(path) ? 0 : body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        for (int at = 0; at < body.length; at += 65536) {
                            os.write(body, at, Math.min(65536, body.length - at));
                        }
                    }
                } catch (IOException ignored) {
                    // the client hung up (a refused body): expected
                } finally {
                    ex.close();
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "imgprev-fake");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            oldApi = System.getProperty("killer560.net.imgur-api");
            oldI = System.getProperty("killer560.net.imgur-i");
            oldHosts = System.getProperty(HOSTS_PROP);
            System.setProperty("killer560.net.imgur-api", base + "/imgurapi");
            System.setProperty("killer560.net.imgur-i", base + "/imgur-i");
            System.setProperty(HOSTS_PROP, "127.0.0.1:" + port);
            build();
        }

        void put(String path, byte[] body, String type) {
            files.put(path, body);
            types.put(path, type);
        }

        int hits(String path) {
            AtomicInteger a = hits.get(path);
            return a == null ? 0 : a.get();
        }

        private void build() throws IOException {
            put("/red.png", halves(64, 32, RED, BLUE, "png"), "image/png");
            put("/cache.png", halves(40, 40, RED, BLUE, "png"), "image/png");
            put("/cost.png", halves(80, 40, RED, BLUE, "png"), "image/png");
            put("/wrap.png", halves(30, 30, GREEN, RED, "png"), "image/png");
            put("/off.png", halves(30, 30, GREEN, RED, "png"), "image/png");
            put("/anim.gif", gif(32, 32, new int[]{RED, GREEN, BLUE, 0xFFFFFF00}, 10), "image/gif");
            put("/still.gif", gif(32, 32, new int[]{RED, GREEN, BLUE, 0xFFFFFF00}, 10), "image/gif");
            byte[] big = new byte[11 * 1024 * 1024];
            byte[] png = halves(4, 4, RED, BLUE, "png");
            System.arraycopy(png, 0, big, 0, png.length);
            put("/big.png", big, "image/png");
            put("/bigchunked.png", big, "image/png");
            chunked.add("/bigchunked.png");
            put("/huge.png", pngHeaderOnly(10000, 10000), "image/png");
            put("/text.png", "hello, this is not a picture at all".getBytes(StandardCharsets.UTF_8), "text/plain");
            put("/page.txt", "just some words".getBytes(StandardCharsets.UTF_8), "text/plain");
            put("/hosts.png", halves(30, 30, RED, BLUE, "png"), "image/png");
            put("/lossless.webp", Base64.getDecoder().decode(
                    "UklGRiYAAABXRUJQVlA4TBkAAAAvD8ABAA8Q87//8x8OxILJ/KU7U4jof+QAAA=="), "image/webp");
            put("/lossy.webp", Base64.getDecoder().decode(
                    "UklGRkQAAABXRUJQVlA4IDgAAAAwAgCdASoQAAgAAMASJaACdLoB+AH4gARoAAD+/Suf/9+gm/93m/10S8z/60c//rRlcH9aOb+gAA=="),
                    "image/webp");
            // imgur: the post API (albums and single media) and i.imgur.com's files.
            put("/imgurapi/post/v1/albums/AlbUm01", album("AAAAA01.png", "AAAAA02.png", "AAAAA03.png"), "application/json");
            put("/imgurapi/post/v1/media/PiCcc01", album("PiCcc01.jpeg"), "application/json");
            put("/imgur-i/AAAAA01.png", halves(20, 20, RED, RED, "png"), "image/png");
            put("/imgur-i/AAAAA02.png", halves(20, 20, GREEN, GREEN, "png"), "image/png");
            put("/imgur-i/AAAAA03.png", halves(20, 20, BLUE, BLUE, "png"), "image/png");
            put("/imgur-i/PiCcc01.jpeg", halves(48, 24, BLUE, BLUE, "jpg"), "image/jpeg");
        }

        @Override
        public void close() {
            server.stop(0);
            restore("killer560.net.imgur-api", oldApi);
            restore("killer560.net.imgur-i", oldI);
            restore(HOSTS_PROP, oldHosts);
        }

        private static void restore(String key, String old) {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }

    static byte[] album(String... files) {
        StringBuilder sb = new StringBuilder("{\"id\":\"x\",\"media\":[");
        for (int i = 0; i < files.length; i++) {
            String id = files[i].substring(0, files[i].indexOf('.'));
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"").append(id).append("\",\"type\":\"image\",\"url\":\"https://i.imgur.com/")
                    .append(files[i]).append("\"}");
        }
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] halves(int w, int h, int left, int right, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, format.equals("jpg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, x < w / 2 ? left : right);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    static byte[] gif(int w, int h, int[] colors, int delayCs) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (int c : colors) {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        img.setRGB(x, y, c);
                    }
                }
                ImageWriteParam p = writer.getDefaultWriteParam();
                IIOMetadata md = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), p);
                String fmt = md.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
                IIOMetadataNode gce = null;
                for (int i = 0; i < root.getLength(); i++) {
                    if (root.item(i).getNodeName().equals("GraphicControlExtension")) {
                        gce = (IIOMetadataNode) root.item(i);
                    }
                }
                if (gce == null) {
                    gce = new IIOMetadataNode("GraphicControlExtension");
                    root.appendChild(gce);
                }
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", String.valueOf(delayCs));
                gce.setAttribute("transparentColorIndex", "0");
                md.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(img, null, md), p);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** A PNG signature and an IHDR claiming w x h (8-bit RGB), then IEND: no pixel data at all. */
    static byte[] pngHeaderOnly(int w, int h) {
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        java.nio.ByteBuffer len = java.nio.ByteBuffer.allocate(4).putInt(data.length);
        out.writeBytes(len.array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    static Settings base(Session c) {
        return new Settings(c)
                .custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false), () -> { })
                .with(CFG, "Enabled", true)
                .with(CFG, "AnyHost", false)
                .with(CFG, "PlayGifs", true)
                .with(CFG, "MaxPreviewSize", 160)
                .with("copychat.CopyChatConfig", "Enabled", false)
                .with("clicktranslate.ClickTranslateConfig", "Enabled", false)
                .custom(() -> { }, () -> McCompat.setScreen(Minecraft.getInstance(), null));
    }

    static void reset(Session c) {
        c.ctx().runOnClient(mc -> Mod.staticCall(FEATURE, "resetForTest"));
    }

    static void say(Session c, String text) {
        c.hx().chat(text);
        c.ctx().waitTicks(4);
    }

    static void sayLink(Session c, String text, String url) {
        JsonObject j = new JsonObject();
        j.addProperty("text", text);
        JsonObject click = new JsonObject();
        click.addProperty("action", "open_url");
        click.addProperty("url", url);
        j.add("click_event", click);
        c.hx().call("chat", "textJson", j, "overlay", false);
        c.ctx().waitTicks(4);
    }

    static void openChat(Session c) {
        // Off every chat row first, so the cursor left over from the last case cannot hover an older link.
        moveTo(c, new double[]{2, 2});
        c.ctx().runOnClient(mc -> {
            ((ChatComponent) Mod.staticCall("compat.McCompat", "chat", mc)).resetChatScroll();
            McCompat.setScreen(mc, new ChatScreen("", false));
        });
        c.ctx().waitTicks(3);
    }

    /** One chat row as vanilla lays it out: its plain text, its pose, its text origin, its characters. */
    record Placed(String text, Matrix3x2f pose, int x, int y, FormattedCharSequence seq) {
    }

    static List<Placed> placed(Minecraft mc) {
        ChatComponent chat = (ChatComponent) Mod.staticCall("compat.McCompat", "chat", mc);
        List<Placed> out = new ArrayList<>();
        ActiveTextCollector.ClickableStyleFinder rec = new ActiveTextCollector.ClickableStyleFinder(mc.font, 0, 0) {
            @Override
            public void accept(TextAlignment a, int x, int y, ActiveTextCollector.Parameters p, FormattedCharSequence t) {
                out.add(new Placed(plain(t), new Matrix3x2f(p.pose()), x, y, t));
            }
        };
        chat.captureClickableText(rec, mc.getWindow().getGuiScaledHeight(), 0, ChatComponent.DisplayMode.FOREGROUND);
        return out;
    }

    static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((i, s, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    /**
     * GUI point over the middle letter of {@code sub} in the LOWEST (newest) chat row containing it, or null.
     */
    static double[] pointOf(Session c, String sub) {
        return c.onClient(mc -> {
            for (Placed p : placed(mc)) {
                int idx = p.text().indexOf(sub);
                if (idx < 0) {
                    continue;
                }
                int k = idx + sub.length() / 2;
                float[] before = {0};
                float[] own = {0};
                int[] n = {0};
                p.seq().accept((i, st, cp) -> {
                    float w = mc.font.width(FormattedCharSequence.codepoint(cp, st));
                    if (n[0] < k) {
                        before[0] += w;
                    } else if (n[0] == k) {
                        own[0] = w;
                    }
                    n[0] += Character.charCount(cp);
                    return true;
                });
                Vector2f v = p.pose().transformPosition(new Vector2f(p.x() + before[0] + own[0] / 2f, p.y() + 4f));
                return new double[]{v.x, v.y};
            }
            return null;
        });
    }

    /** Puts the real cursor on GUI point {@code at} (the chat screen has no scale of its own). */
    static void moveTo(Session c, double[] at) {
        double[] w = c.onClient(mc -> new double[]{
                at[0] * mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                at[1] * mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()});
        c.ctx().getInput().setCursorPos(w[0], w[1]);
        c.ctx().waitTicks(2);
    }

    static boolean hover(Session c, String sub) {
        double[] at = pointOf(c, sub);
        c.check(at != null, "premise: \"" + sub + "\" is on screen in the chat");
        if (at == null) {
            return false;
        }
        moveTo(c, at);
        return true;
    }

    static Object drawn(Session c) {
        return c.onClient(mc -> Mod.staticCall(FEATURE, "lastDrawnForTest"));
    }

    static Object get(Object rec, String field) {
        return Mod.call(rec, field);
    }

    static String caption(Object d) {
        return d == null ? null : (String) get(d, "caption");
    }

    static String state(Session c, String link) {
        return c.onClient(mc -> (String) Mod.staticCall(FEATURE, "entryStateForTest", link));
    }

    /** Waits (wall clock, up to {@code ms}) until the drawn record for {@code link} satisfies {@code ok}. */
    static Object waitDrawn(Session c, String link, java.util.function.Predicate<Object> ok, long ms) {
        long end = System.currentTimeMillis() + ms;
        Object d = null;
        while (System.currentTimeMillis() < end) {
            d = drawn(c);
            if (d != null && link.equals(get(d, "link")) && ok.test(d)) {
                return d;
            }
            c.ctx().waitTicks(1);
        }
        return d;
    }

    static Object waitPicture(Session c, String link) {
        return waitDrawn(c, link, d -> (Integer) get(d, "textureW") > 0, 8000);
    }

    static int sample(Session c, String link, double fx, double fy) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "sampleForTest", link, fx, fy));
    }

    static int requests(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "requestsForTest"));
    }

    static String hex(int argb) {
        return String.format("%08X", argb);
    }

    static boolean near(int a, int b, int tol) {
        for (int s = 0; s <= 16; s += 8) {
            if (Math.abs(((a >> s) & 0xFF) - ((b >> s) & 0xFF)) > tol) {
                return false;
            }
        }
        return true;
    }

    static String screenName(Session c) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            return s == null ? "none" : s.getClass().getSimpleName();
        });
    }

    // =================================================================================================================
    // cases
    // =================================================================================================================

    static void png(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String url = fake.base + "/red.png";
            sayLink(c, "PngProbe see the picture here", url);
            openChat(c);
            hover(c, "picture here");
            String hovered = c.onClient(mc -> (String) Mod.staticCall(FEATURE, "hoveredForTest"));
            c.check(url.equals(hovered), "hovering the clickable link found " + hovered + ", want " + url);
            Object d = waitPicture(c, url);
            c.check(d != null && (Integer) get(d, "textureW") == 64 && (Integer) get(d, "textureH") == 32,
                    "the preview did not draw the 64x32 picture: " + d);
            if (d == null || (Integer) get(d, "textureW") != 64) {
                return;
            }
            int w = (Integer) get(d, "w");
            int h = (Integer) get(d, "h");
            c.check(Math.abs(w - 2 * h) <= 1, "the preview is " + w + "x" + h + ", not 2:1");
            int l = sample(c, url, 0.25, 0.5);
            int r = sample(c, url, 0.75, 0.5);
            c.check(l == RED && r == BLUE, "decoded pixels left " + hex(l) + " right " + hex(r) + ", want red / blue");
            // The screen itself: a screenshot, read where the mod says it drew.
            int x = (Integer) get(d, "x");
            int y = (Integer) get(d, "y");
            Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-preview"));
            dev.testkit.harness.Report.screenshot(c.name() + "-preview", shot);
            try {
                BufferedImage img = ImageIO.read(shot.toFile());
                double f = img.getWidth() / (double) c.onClient(mc -> mc.getWindow().getGuiScaledWidth());
                int sl = img.getRGB((int) ((x + w * 0.25) * f), (int) ((y + h * 0.5) * f));
                int sr = img.getRGB((int) ((x + w * 0.75) * f), (int) ((y + h * 0.5) * f));
                c.note("screenshot " + img.getWidth() + "x" + img.getHeight() + ", preview at " + x + "," + y + " " + w + "x"
                        + h + " GUI px: left " + hex(sl) + " right " + hex(sr));
                c.check(near(sl, RED, 40) && near(sr, BLUE, 40), "on screen the preview is left " + hex(sl) + " right "
                        + hex(sr) + ", want red / blue");
            } catch (IOException e) {
                c.check(false, "could not read the screenshot: " + e);
            }
            // A real left click on the link: vanilla's open-link prompt, untouched.
            c.ctx().getInput().pressMouse(0);
            c.ctx().waitTicks(4);
            String scr = screenName(c);
            c.check(scr.contains("ConfirmLink"), "a left click on the link opened " + scr + ", want vanilla's ConfirmLinkScreen");
            c.note("PASS 64x32 PNG drawn " + w + "x" + h + ", red left / blue right decoded and on screen; left click -> " + scr);
        }
    }

    static void plainWrap(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String url = fake.base + "/wrap.png?padding=" + "abcdefghij".repeat(6) + "&end=WRAPTAIL";
            say(c, "WrapProbe look " + url + " afterword");
            openChat(c);
            List<String> rows = c.onClient(mc -> placed(mc).stream().map(Placed::text).filter(t -> t.contains("WRAPTAIL")
                    || t.contains("WrapProbe")).toList());
            c.note("rows: " + rows);
            boolean wrapped = c.onClient(mc -> placed(mc).stream().noneMatch(p -> p.text().contains("WrapProbe")
                    && p.text().contains("WRAPTAIL")));
            c.check(wrapped, "premise: the link wraps over two rows");
            hover(c, "WRAPTAIL");
            String hovered = c.onClient(mc -> (String) Mod.staticCall(FEATURE, "hoveredForTest"));
            c.check(url.equals(hovered), "hovering the link's second row found " + hovered + ", want the whole " + url);
            Object d = waitPicture(c, url);
            c.check(d != null && (Integer) get(d, "textureW") == 30, "no picture for the wrapped link: " + d);
            hover(c, "afterword");
            c.ctx().waitTicks(3);
            String after = c.onClient(mc -> (String) Mod.staticCall(FEATURE, "hoveredForTest"));
            c.check(after == null, "hovering the word after the link found a link: " + after);
            c.note("PASS plain-text link over two rows found whole from its second row; the next word is not a link");
        }
    }

    static void gif(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String url = fake.base + "/anim.gif";
            sayLink(c, "GifProbe animated", url);
            openChat(c);
            hover(c, "animated");
            Object d = waitPicture(c, url);
            c.check(d != null && (Integer) get(d, "frames") == 4, "the GIF did not draw with 4 frames: " + d);
            Set<Integer> seen = new LinkedHashSet<>();
            Set<String> colors = new LinkedHashSet<>();
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                Object now = drawn(c);
                if (now != null && url.equals(get(now, "link"))) {
                    seen.add((Integer) get(now, "frame"));
                    colors.add(hex(sample(c, url, 0.5, 0.5)));
                }
                c.ctx().waitTicks(1);
            }
            c.note("frames seen over 1.5 s: " + seen + ", colors " + colors);
            c.check(seen.size() >= 3 && colors.size() >= 3, "the GIF did not animate: frames " + seen + " colors " + colors);
            // Play GIFs off: one frame held.
            try (AutoCloseable off = c.onClient(mc -> Mod.with(CFG, "PlayGifs", false))) {
                c.ctx().waitTicks(2);
                Set<Integer> held = new LinkedHashSet<>();
                long end2 = System.currentTimeMillis() + 800;
                while (System.currentTimeMillis() < end2) {
                    Object now = drawn(c);
                    if (now != null) {
                        held.add((Integer) get(now, "frame"));
                    }
                    c.ctx().waitTicks(1);
                }
                c.check(held.size() == 1, "with Play GIFs off the frame still changed: " + held);
                c.note("PASS animates (" + seen.size() + " frames in 1.5 s); Play GIFs off holds frame " + held);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    static void refuse(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String[][] cases = {
                    {"/big.png", "BigProbe", "Too large"},
                    {"/bigchunked.png", "ChunkProbe", "Too large"},
                    {"/huge.png", "HugeProbe", "Too many pixels"}};
            for (String[] k : cases) {
                String url = fake.base + k[0];
                sayLink(c, k[1] + " refused file", url);
                openChat(c);
                hover(c, k[1]);
                Object d = waitDrawn(c, url, x -> caption(x) != null && caption(x).contains(k[2]), 15000);
                c.check(d != null && caption(d) != null && caption(d).contains(k[2]),
                        k[0] + " shows \"" + caption(d) + "\", want \"" + k[2] + "\"");
                c.note(k[0] + " -> \"" + caption(d) + "\" (" + fake.hits(k[0]) + " request(s))");
                c.check(fake.hits(k[0]) >= 1, "premise: " + k[0] + " was requested");
            }
            int textures = c.onClient(mc -> (Integer) Mod.staticCall(FEATURE, "texturesForTest"));
            c.check(textures == 0, textures + " texture(s) were made for refused files");
            c.note("PASS 11 MB with and without Content-Length refused, 10000x10000 refused, no texture");
        }
    }

    static void notImage(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            for (String path : new String[]{"/text.png", "/page.txt"}) {
                String url = fake.base + path;
                sayLink(c, "NotImageProbe" + path.length() + " words", url);
                openChat(c);
                hover(c, "NotImageProbe" + path.length());
                long end = System.currentTimeMillis() + 8000;
                String st = null;
                while (System.currentTimeMillis() < end) {
                    st = state(c, url);
                    if (st != null && (st.startsWith("IGNORED") || st.endsWith("ignored"))) {
                        break;
                    }
                    c.ctx().waitTicks(1);
                }
                c.check(st != null && (st.startsWith("IGNORED") || st.endsWith("ignored")), path + " ended as " + st);
                c.ctx().runOnClient(mc -> Mod.staticCall(FEATURE, "clearLastDrawnForTest"));
                c.ctx().waitTicks(10);
                Object d = drawn(c);
                c.check(d == null, path + ": something still drew: " + d);
                c.check(fake.hits(path) == 1, path + " was requested " + fake.hits(path) + " times, want 1");
                c.note(path + " -> " + st + ", nothing drawn, " + fake.hits(path) + " request");
            }
            c.note("PASS a non-picture answer and a plain page draw nothing");
        }
    }

    static void cache(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String url = fake.base + "/cache.png";
            sayLink(c, "CacheProbe cached", url);
            openChat(c);
            hover(c, "cached");
            Object d = waitPicture(c, url);
            c.check(d != null && (Integer) get(d, "textureW") == 40, "first hover did not draw: " + d);
            int hits = fake.hits("/cache.png");
            int req = requests(c);
            c.check(hits == 1, "premise: the first hover requested it once, saw " + hits);
            // Drop every in-memory picture and texture, then hover again: the disk cache must answer.
            reset(c);
            moveTo(c, new double[]{2, 2});
            hover(c, "cached");
            Object d2 = waitPicture(c, url);
            c.check(d2 != null && (Integer) get(d2, "textureW") == 40, "second hover did not draw: " + d2);
            c.check(fake.hits("/cache.png") == 1, "the second hover sent a request (" + fake.hits("/cache.png") + " total)");
            c.check(requests(c) == req, "the mod counted " + (requests(c) - req) + " new request(s) for a cache hit");
            Path dir = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-imagepreview-cache"));
            long files;
            try (var s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().startsWith("img-")).count();
            } catch (IOException e) {
                files = -1;
            }
            c.check(files >= 1, "no cached picture in " + dir);
            c.note("PASS second hover drawn from " + dir + " (" + files + " picture file(s)), still 1 request");
        }
    }

    static void imgur(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String album = "https://imgur.com/a/AlbUm01";
            say(c, "AlbumProbe " + album + " three");
            openChat(c);
            hover(c, "AlbUm01");
            Object d = waitPicture(c, album);
            c.check(d != null && "1/3".equals(caption(d)), "the album did not show \"1/3\": " + d);
            c.check(sample(c, album, 0.5, 0.5) == RED, "picture 1 is " + hex(sample(c, album, 0.5, 0.5)) + ", want red");
            c.check(fake.hits("/imgurapi/post/v1/albums/AlbUm01") == 1, "the album API was asked "
                    + fake.hits("/imgurapi/post/v1/albums/AlbUm01") + " times");
            c.ctx().getInput().scroll(-1);
            Object d2 = waitDrawn(c, album, x -> "2/3".equals(caption(x)) && (Integer) get(x, "textureW") > 0, 8000);
            c.check(d2 != null && "2/3".equals(caption(d2)), "the wheel did not step to \"2/3\": " + d2);
            c.check(sample(c, album, 0.5, 0.5) == GREEN, "picture 2 is " + hex(sample(c, album, 0.5, 0.5)) + ", want green");
            // Middle click: the viewer; the right arrow steps; Escape is back in the chat.
            c.ctx().getInput().pressMouse(2);
            c.ctx().waitTicks(3);
            String scr = screenName(c);
            c.check(scr.equals("ImageViewerScreen"), "a middle click opened " + scr);
            c.ctx().getInput().pressKey(GLFW.GLFW_KEY_RIGHT);
            c.ctx().waitTicks(3);
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end && sample(c, album, 0.5, 0.5) != BLUE) {
                c.ctx().waitTicks(1);
            }
            c.check(sample(c, album, 0.5, 0.5) == BLUE, "the right arrow did not reach picture 3 (blue): "
                    + hex(sample(c, album, 0.5, 0.5)));
            Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-viewer"));
            dev.testkit.harness.Report.screenshot(c.name() + "-viewer", shot);
            c.ctx().getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            c.ctx().waitTicks(3);
            String back = screenName(c);
            c.check(back.equals("ChatScreen"), "Escape in the viewer went to " + back + ", want the chat");
            // A scheme-less single picture page.
            String single = "imgur.com/PiCcc01";
            say(c, "SingleProbe " + single + " one");
            openChat(c);
            hover(c, "PiCcc01");
            Object d3 = waitPicture(c, "https://" + single);
            c.check(d3 != null && (Integer) get(d3, "textureW") == 48 && "".equals(caption(d3)),
                    "the scheme-less imgur page did not show its 48x24 picture: " + d3);
            c.note("PASS album 1/3 red -> wheel 2/3 green -> viewer -> right arrow blue -> Escape to chat; imgur.com/<id> "
                    + "without a scheme shows its picture");
        }
    }

    static void webp(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            for (String path : new String[]{"/lossless.webp", "/lossy.webp"}) {
                String url = fake.base + path;
                String probe = "WebpProbe" + path.length();
                sayLink(c, probe + " webp", url);
                openChat(c);
                hover(c, probe);
                Object d = waitPicture(c, url);
                c.check(d != null && (Integer) get(d, "textureW") == 16 && (Integer) get(d, "textureH") == 8,
                        path + " did not draw at 16x8: " + d + " (" + state(c, url) + ")");
                int l = sample(c, url, 0.2, 0.5);
                int r = sample(c, url, 0.8, 0.5);
                c.check(near(l, RED, 40) && near(r, BLUE, 40), path + " decoded left " + hex(l) + " right " + hex(r));
                c.note(path + " -> 16x8, left " + hex(l) + " right " + hex(r));
            }
            c.note("PASS lossless and lossy WebP");
        }
    }

    static void hosts(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String url = fake.base + "/hosts.png";
            String old = System.getProperty(HOSTS_PROP);
            System.clearProperty(HOSTS_PROP);
            try {
                sayLink(c, "HostsProbe local", url);
                openChat(c);
                hover(c, "HostsProbe");
                Object d = waitDrawn(c, url, x -> caption(x) != null && caption(x).contains("Any Host is off"), 6000);
                c.check(d != null && caption(d).contains("Any Host is off"), "Trusted Hosts Only showed " + caption(d));
                c.ctx().runOnClient(mc -> Mod.with(CFG, "AnyHost", true));
                reset(c);
                moveTo(c, new double[]{2, 2});
                hover(c, "HostsProbe");
                Object d2 = waitDrawn(c, url, x -> caption(x) != null && caption(x).contains("Local addresses"), 6000);
                c.check(d2 != null && caption(d2).contains("Local addresses"), "Any Host on a local address showed "
                        + caption(d2));
                c.check(fake.hits("/hosts.png") == 0, "the local address was requested " + fake.hits("/hosts.png") + " times");
                c.note("PASS Trusted Only: \"" + caption(d) + "\"; Any Host: \"" + caption(d2) + "\"; 0 requests");
            } finally {
                System.setProperty(HOSTS_PROP, old);
            }
        }
    }

    static void off(Session c) {
        try (Settings set = base(c).with(CFG, "Enabled", false)) {
            reset(c);
            String url = fake.base + "/off.png";
            sayLink(c, "OffProbe switched off", url);
            openChat(c);
            int req = requests(c);
            hover(c, "switched off");
            c.ctx().waitTicks(20);
            Object d = drawn(c);
            String hovered = c.onClient(mc -> (String) Mod.staticCall(FEATURE, "hoveredForTest"));
            c.check(d == null, "with the switch off something drew: " + d);
            c.check(hovered == null, "with the switch off a link was looked up: " + hovered);
            c.check(fake.hits("/off.png") == 0 && requests(c) == req, "with the switch off a request was sent");
            c.note("PASS switch off: nothing drawn, nothing looked up, nothing requested");
        }
    }

    static void cost(Session c) {
        try (Settings set = base(c)) {
            reset(c);
            String url = fake.base + "/cost.png";
            sayLink(c, "CostProbe measured", url);
            openChat(c);
            hover(c, "measured");
            c.check(waitPicture(c, url) != null, "premise: the picture is shown");
            long[] hovering = measure(c, 2000);
            moveTo(c, new double[]{2, 2});
            c.ctx().waitTicks(2);
            long[] idle = measure(c, 2000);
            c.note(String.format("feature work per chat frame: hovering a shown picture %.1f us over %d frames, "
                    + "hovering nothing %.1f us over %d frames", hovering[0] / 1000.0, hovering[1], idle[0] / 1000.0, idle[1]));
            c.check(hovering[1] > 10 && idle[1] > 10, "premise: frames were measured");
            c.check(hovering[0] < 300_000 && idle[0] < 300_000, "the feature costs over 0.3 ms a frame");
        }
    }

    static long[] measure(Session c, long ms) {
        c.ctx().runOnClient(mc -> Mod.staticCall(FEATURE, "resetWorkForTest"));
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            c.ctx().waitTicks(1);
        }
        return c.onClient(mc -> (long[]) Mod.staticCall(FEATURE, "workForTest"));
    }
}
