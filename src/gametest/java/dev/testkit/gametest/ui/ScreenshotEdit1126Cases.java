package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 1126-ui-screenshot-edit (screenshot-edit-1126, killer560 11-26: click the screenshot or copy message to open it, mark it up,
 * crop it). Part 1: a real vanilla screenshot (Screenshot.grab, the call F2 makes) with Auto Copy Screenshot on: both chat
 * lines carry a run-command click on /k560screenshot edit &lt;file&gt; (the saved line keeps the open-file click on the file
 * name and has an [Edit] piece), the fake clipboard file got the screenshot, the command opens the editor, which loads the
 * picture at its real size. A path-traversal name is refused, with the feature off the line is left as vanilla wrote it.
 * Part 2, on a synthetic 320x200 noise PNG so pixels can be checked exactly: a real mouse drag crops (Apply Crop button), a
 * real mouse drag draws a red pen stroke, a pixelate box and a text are added, Undo / Redo move the counts, Save As Copy
 * writes name-edited.png at the cropped size with the drawn pixels changed and the original byte-identical, a second Save As
 * Copy takes the next name and leaves the first alone, Copy puts the cropped PNG on the (fake) clipboard, Esc with unsaved
 * edits asks once, and closing releases the preview texture.
 */
final class ScreenshotEdit1126Cases {

    private static final String CFG = "screenshotcopy.ScreenshotCopyConfig";
    private static final String FEATURE = "screenshotcopy.ScreenshotCopyFeature";
    private static final String EDIT = "screenshotcopy.ScreenshotEdit";
    private static final String SCREEN = "ScreenshotEditorScreen";
    private static final String CLIP = "killer560smod.test.clipboardfile";
    private static final String SYNTH = "k560-test-1126.png";

    private ScreenshotEdit1126Cases() {
    }

    private static boolean isEditor(Screen s) {
        return s != null && s.getClass().getSimpleName().equals(SCREEN);
    }

    private static Screen current(UiCase c) {
        return c.onClient(McCompat::screen);
    }

    private static void waitUntil(UiCase c, Supplier<Boolean> cond, int maxTicks, String what) {
        for (int i = 0; i < maxTicks; i++) {
            if (c.onClient(mc -> cond.get())) {
                return;
            }
            c.ticks(2);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> all(Minecraft mc) {
        return (List<GuiMessage>) Mod.field(McCompat.chat(mc), "allMessages");
    }

    /** The run-command a message carries on its root, or null. */
    private static String rootCommand(Component m) {
        return m.getStyle().getClickEvent() instanceof ClickEvent.RunCommand rc ? rc.command() : null;
    }

    private static boolean hasOpenFile(Component m) {
        if (m.getStyle().getClickEvent() instanceof ClickEvent.OpenFile) {
            return true;
        }
        if (m.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            for (Object a : t.getArgs()) {
                if (a instanceof Component inner && hasOpenFile(inner)) {
                    return true;
                }
            }
        }
        return m.getSiblings().stream().anyMatch(ScreenshotEdit1126Cases::hasOpenFile);
    }

    private static Object op(String kind, int color, int size, int[] pts, String text) throws Exception {
        return Mod.cls(EDIT + "$Op").getConstructors()[0].newInstance(Mod.enumValue(EDIT + "$Kind", kind), color, size, pts, text);
    }

    private static Path shots(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR);
    }

    private static String sha(Path p) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static MouseButtonEvent ev(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(McKeys.mouse(0), 0));
    }

    /** A real left drag through the screen: press, a few moves, release. Image points are mapped to the screen by the editor. */
    private static void drag(UiCase c, Screen s, double ix0, double iy0, double ix1, double iy1) {
        double[] a = (double[]) Mod.call(s, "toScreen", ix0, iy0);
        double[] b = (double[]) Mod.call(s, "toScreen", ix1, iy1);
        c.onClient(mc -> {
            s.mouseClicked(ev(a[0], a[1]), false);
            for (int i = 1; i <= 4; i++) {
                double x = a[0] + (b[0] - a[0]) * i / 4;
                double y = a[1] + (b[1] - a[1]) * i / 4;
                s.mouseDragged(ev(x, y), (b[0] - a[0]) / 4, (b[1] - a[1]) / 4);
            }
            s.mouseReleased(ev(b[0], b[1]));
            return null;
        });
    }

    private static void pressButton(UiCase c, Screen s, String label) {
        AbstractWidget hit = null;
        for (var child : s.children()) {
            if (child instanceof AbstractWidget w && ModScreenDriver.label(w).equals(label)) {
                hit = w;
            }
        }
        AbstractWidget w = hit;
        c.check(w != null, "no \"" + label + "\" button on the editor");
        c.onClient(mc -> ModScreenDriver.press(w));
    }

    private static void command(UiCase c, String cmd) {
        c.onClient(mc -> {
            mc.player.connection.sendCommand(cmd);
            return null;
        });
        c.ticks(4);
    }

    static void edit(UiCase c) throws Exception {
        Object cfg = Mod.cfg(CFG);
        boolean was = (Boolean) Mod.call(cfg, "isEnabled");
        Path clip = Files.createTempFile("k560-clip-1126-", ".png");
        Files.deleteIfExists(clip);
        List<Path> made = new ArrayList<>();
        try {
            System.setProperty(CLIP, clip.toString());
            Mod.call(cfg, "setEnabled", true);
            Path dir = c.onClient(ScreenshotEdit1126Cases::shots);
            Files.createDirectories(dir);

            // ---- part 1: a real vanilla screenshot -------------------------------------------------------------------
            int before = c.onClient(mc -> all(mc).size());
            c.onClient(mc -> {
                Screenshot.grab(mc, false); // what F2 runs: saves and prints vanilla's line
                return null;
            });
            waitUntil(c, () -> {
                List<GuiMessage> m = all(Minecraft.getInstance());
                boolean saved = false;
                boolean copied = false;
                for (int i = 0; i < m.size() - before; i++) {
                    String t = m.get(i).content().getString();
                    saved |= t.contains("Saved screenshot as");
                    copied |= t.contains("Screenshot copied to clipboard");
                }
                return saved && copied;
            }, 400, "the saved and copied chat lines");
            List<Component> lines = c.onClient(mc -> {
                List<Component> out = new ArrayList<>();
                List<GuiMessage> m = all(mc);
                for (int i = 0; i < m.size() - before; i++) {
                    out.add(m.get(i).content());
                }
                return out;
            });
            Component saved = lines.stream().filter(l -> l.getString().contains("Saved screenshot as")).findFirst().orElseThrow();
            Component copied = lines.stream().filter(l -> l.getString().contains("Screenshot copied to clipboard")).findFirst().orElseThrow();
            String cmdSaved = rootCommand(saved);
            String cmdCopied = rootCommand(copied);
            c.note("saved line click -> " + cmdSaved + "; copied line click -> " + cmdCopied);
            c.check(cmdSaved != null && cmdSaved.startsWith("/k560screenshot edit ") && cmdSaved.endsWith(".png"), "saved line click is " + cmdSaved);
            c.check(cmdSaved != null && cmdSaved.equals(cmdCopied), "the two lines should run the same command: " + cmdSaved + " vs " + cmdCopied);
            c.check(hasOpenFile(saved), "the saved line lost vanilla's open-file click on the file name");
            c.check(saved.getString().contains("[Edit]"), "the saved line has no [Edit] piece: " + saved.getString());
            c.check(saved.getStyle().getHoverEvent() != null && copied.getStyle().getHoverEvent() != null, "both lines should have a hover");
            c.check(Files.isRegularFile(clip) && Files.size(clip) > 0, "the fake clipboard file was not written by the auto copy");
            String name = cmdSaved.substring("/k560screenshot edit ".length());
            Path real = dir.resolve(name);
            c.check(Files.isRegularFile(real), "the screenshot file " + real + " is missing");
            BufferedImage realImg = ImageIO.read(real.toFile());
            String realSha = sha(real);
            int liveBefore = (Integer) ((java.util.concurrent.atomic.AtomicInteger) Mod.field("screenshotcopy.ScreenshotEditorScreen", "LIVE_TEXTURES")).get();

            command(c, cmdSaved.substring(1));
            Screen ed = current(c);
            c.check(isEditor(ed), "running the click's command opened " + ed + ", not the editor");
            waitUntil(c, () -> (Boolean) Mod.call(ed, "isLoaded") && !(Boolean) Mod.call(ed, "isBusy"), 300, "the editor to load the screenshot");
            c.check((Integer) Mod.call(ed, "imageWidth") == realImg.getWidth() && (Integer) Mod.call(ed, "imageHeight") == realImg.getHeight(),
                    "editor size " + Mod.call(ed, "imageWidth") + "x" + Mod.call(ed, "imageHeight") + " != file " + realImg.getWidth() + "x" + realImg.getHeight());
            c.check((Boolean) Mod.call(ed, "textureLive"), "no preview texture after loading");
            c.onClient(mc -> {
                ((Screen) ed).onClose();
                return null;
            });
            c.ticks(4);
            c.check(!isEditor(current(c)), "a clean editor did not close on Esc/Cancel");
            c.check(!(Boolean) Mod.call(ed, "textureLive")
                    && (Integer) ((java.util.concurrent.atomic.AtomicInteger) Mod.field("screenshotcopy.ScreenshotEditorScreen", "LIVE_TEXTURES")).get() == liveBefore,
                    "closing the editor did not release its texture");
            c.check(sha(real).equals(realSha), "opening and closing the editor changed the screenshot file");

            // a name that leaves the folder, or is not a .png, is refused and opens nothing
            int chatBefore = c.onClient(mc -> all(mc).size());
            for (String bad : new String[]{"../options.txt", "..\\options.txt", "a/../../x.png", "options.txt", "C:\\x.png", "missing-" + SYNTH}) {
                command(c, "k560screenshot edit " + bad);
                c.check(!isEditor(current(c)), "the editor opened for the refused name \"" + bad + "\"");
                c.check(Mod.staticCall(EDIT, "resolve", bad) == null, "resolve accepted \"" + bad + "\"");
            }
            c.check(c.onClient(mc -> all(mc).stream().limit(Math.max(0, all(mc).size() - chatBefore)).anyMatch(m -> m.content().getString().contains("No screenshot called"))),
                    "a refused name gave no chat line");

            // feature off: vanilla's line is left exactly as it was
            Mod.call(cfg, "setEnabled", false);
            Component plain = Component.literal("Saved screenshot as x.png");
            c.check(Mod.staticCall(FEATURE, "withEdit", plain) == plain, "withEdit changed a line while Auto Copy Screenshot is off");
            Mod.call(cfg, "setEnabled", true);

            // ---- part 2: a synthetic picture, exact pixels -----------------------------------------------------------------
            BufferedImage orig = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
            java.util.Random rnd = new java.util.Random(1126);
            for (int y = 0; y < 200; y++) {
                for (int x = 0; x < 320; x++) {
                    orig.setRGB(x, y, rnd.nextInt(0x1000000));
                }
            }
            Path synth = dir.resolve(SYNTH);
            made.add(synth);
            ImageIO.write(orig, "png", synth.toFile());
            String synthSha = sha(synth);
            command(c, "k560screenshot edit " + SYNTH);
            Screen e2 = current(c);
            c.check(isEditor(e2), "the command did not open the editor for " + SYNTH);
            waitUntil(c, () -> (Boolean) Mod.call(e2, "isLoaded") && !(Boolean) Mod.call(e2, "isBusy"), 300, "the synthetic picture to load");
            c.check((Integer) Mod.call(e2, "imageWidth") == 320 && (Integer) Mod.call(e2, "imageHeight") == 200, "synthetic picture size read wrong");

            // Crop with a real drag, then the Apply Crop button
            Object cropKind = Mod.enumValue(EDIT + "$Kind", "CROP");
            Object penKind = Mod.enumValue(EDIT + "$Kind", "PEN");
            c.onClient(mc -> Mod.call(e2, "selectTool", cropKind));
            drag(c, e2, 20, 10, 300, 190);
            int[] cr = (int[]) Mod.field(e2, "crop");
            c.note("crop rectangle after the drag -> " + java.util.Arrays.toString(cr));
            c.check(cr != null && Math.abs(cr[0] - 20) <= 1 && Math.abs(cr[1] - 10) <= 1 && Math.abs(cr[2] - 300) <= 1 && Math.abs(cr[3] - 190) <= 1,
                    "crop rectangle is " + java.util.Arrays.toString(cr));
            pressButton(c, e2, "Apply Crop");
            c.check((Integer) Mod.call(e2, "opCount") == 1, "Apply Crop did not add one edit");
            int[] applied = (int[]) Mod.field(e2, "appliedCrop");
            c.check(applied != null && applied[2] == cr[2] - cr[0] && applied[3] == cr[3] - cr[1], "applied crop " + java.util.Arrays.toString(applied));
            int cx = applied[0];
            int cy = applied[1];
            int cw = applied[2];
            int ch = applied[3];

            // Pen: pick red with a real press on its swatch, drag a stroke
            c.onClient(mc -> Mod.call(e2, "selectTool", penKind));
            AbstractWidget[] swatches = (AbstractWidget[]) Mod.field(e2, "colorButtons");
            c.onClient(mc -> ModScreenDriver.press(swatches[1]));
            c.check((Integer) Mod.field(e2, "colorIdx") == 1, "the red swatch press did not select red");
            drag(c, e2, 60, 60, 160, 60);
            c.check((Integer) Mod.call(e2, "opCount") == 2, "the pen drag did not add an edit");

            // Pixelate and text through the same op API the tools use
            Object pixelOp = op("PIXELATE", 0, 1, new int[]{200, 100, 280, 160}, null);
            Object textOp = op("TEXT", 0xFFFFFFFF, 2, new int[]{30, 150}, "Hi");
            c.onClient(mc -> Mod.call(e2, "addOp", pixelOp));
            c.onClient(mc -> Mod.call(e2, "addOp", textOp));
            c.check((Integer) Mod.call(e2, "opCount") == 4 && (Integer) Mod.call(e2, "redoCount") == 0, "four edits expected");

            // Undo / Redo counts, then back to the same four edits
            c.onClient(mc -> Mod.call(e2, "undo"));
            c.check((Integer) Mod.call(e2, "opCount") == 3 && (Integer) Mod.call(e2, "redoCount") == 1, "after Undo: " + Mod.call(e2, "opCount") + "/" + Mod.call(e2, "redoCount"));
            c.onClient(mc -> Mod.call(e2, "redo"));
            c.check((Integer) Mod.call(e2, "opCount") == 4 && (Integer) Mod.call(e2, "redoCount") == 0, "after Redo: " + Mod.call(e2, "opCount") + "/" + Mod.call(e2, "redoCount"));
            // Ctrl+Z / Ctrl+Y through the keyboard
            c.onClient(mc -> {
                MenuACases.key(e2, InputConstants.KEY_Z, 2);
                return null;
            });
            c.check((Integer) Mod.call(e2, "opCount") == 3, "Ctrl+Z did not undo");
            c.onClient(mc -> {
                MenuACases.key(e2, InputConstants.KEY_Y, 2);
                return null;
            });
            c.check((Integer) Mod.call(e2, "opCount") == 4, "Ctrl+Y did not redo");
            c.check((Boolean) Mod.call(e2, "isDirty"), "edits should read as unsaved");

            // Save As Copy twice: name-edited.png then name-edited-2.png
            waitUntil(c, () -> !(Boolean) Mod.call(e2, "isBusy"), 300, "the preview to catch up");
            c.onClient(mc -> Mod.call(e2, "saveAsCopy"));
            waitUntil(c, () -> !(Boolean) Mod.call(e2, "isBusy") && Mod.call(e2, "lastSaved") != null, 300, "Save As Copy");
            Path first = (Path) Mod.call(e2, "lastSaved");
            made.add(first);
            c.check(first.getFileName().toString().equals("k560-test-1126-edited.png"), "first copy is named " + first.getFileName());
            BufferedImage out = ImageIO.read(first.toFile());
            c.check(out.getWidth() == cw && out.getHeight() == ch, "edited copy is " + out.getWidth() + "x" + out.getHeight() + ", expected " + cw + "x" + ch);
            c.check(sha(synth).equals(synthSha), "Save As Copy changed the original file");
            c.check(!(Boolean) Mod.call(e2, "isDirty"), "still dirty after saving");

            // pen: red-dominant pixels along the stroke row (the original is noise, so a red run of 40 is the stroke)
            int red = 0;
            for (int x = 70 - cx; x < 150 - cx; x++) {
                for (int y = 58 - cy; y <= 61 - cy; y++) {
                    int p = out.getRGB(x, y);
                    if (((p >> 16) & 0xFF) > 200 && ((p >> 8) & 0xFF) < 90 && (p & 0xFF) < 90) {
                        red++;
                    }
                }
            }
            c.note("red pen pixels in the stroke band -> " + red);
            c.check(red >= 120, "the pen stroke left only " + red + " red pixels");
            // pixelate: one 4x4 block (aligned to the box corner) is a single color and differs from the original there
            int bx = 200 - cx;
            int by = 100 - cy;
            int first4 = out.getRGB(bx, by);
            boolean flat = true;
            boolean changed = false;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    flat &= out.getRGB(bx + x, by + y) == first4;
                    changed |= out.getRGB(bx + x, by + y) != orig.getRGB(200 + x, 100 + y);
                }
            }
            c.check(flat && changed, "pixelate block flat=" + flat + " changed=" + changed);
            // text: pixels near the text origin moved from the original
            int textChanged = 0;
            for (int y = 150; y < 168; y++) {
                for (int x = 30; x < 50; x++) {
                    if (out.getRGB(x - cx, y - cy) != orig.getRGB(x, y)) {
                        textChanged++;
                    }
                }
            }
            c.check(textChanged > 20, "the text changed only " + textChanged + " pixels");
            // outside every edit the copy is the original, shifted by the crop
            c.check(out.getRGB(5, 5) == orig.getRGB(cx + 5, cy + 5), "an untouched pixel differs from the original");

            c.onClient(mc -> Mod.call(e2, "saveAsCopy"));
            waitUntil(c, () -> !(Boolean) Mod.call(e2, "isBusy") && !first.equals(Mod.call(e2, "lastSaved")), 300, "the second Save As Copy");
            Path second = (Path) Mod.call(e2, "lastSaved");
            made.add(second);
            c.check(second.getFileName().toString().equals("k560-test-1126-edited-2.png"), "second copy is named " + second.getFileName());
            c.check(ImageIO.read(first.toFile()).getWidth() == cw, "the first edited copy was overwritten");

            // Copy -> the fake clipboard holds the cropped picture
            Files.deleteIfExists(clip);
            c.onClient(mc -> Mod.call(e2, "copyToClipboard"));
            waitUntil(c, () -> !(Boolean) Mod.call(e2, "isBusy"), 300, "Copy");
            c.check(Files.isRegularFile(clip), "Copy wrote nothing to the clipboard file");
            BufferedImage clipImg = ImageIO.read(clip.toFile());
            c.check(clipImg.getWidth() == cw && clipImg.getHeight() == ch, "clipboard picture is " + clipImg.getWidth() + "x" + clipImg.getHeight());

            // Esc with unsaved edits asks once, the second Esc closes
            c.onClient(mc -> Mod.call(e2, "addOp", op("LINE", 0xFF000000, 0, new int[]{10, 10, 60, 60}, null)));
            c.onClient(mc -> {
                MenuACases.key(e2, InputConstants.KEY_ESCAPE, 0);
                return null;
            });
            c.ticks(2);
            c.check(isEditor(current(c)), "Esc with unsaved edits closed the editor at once");
            c.check(((String) Mod.call(e2, "statusText")).contains("Unsaved"), "no unsaved warning: " + Mod.call(e2, "statusText"));
            c.onClient(mc -> {
                MenuACases.key(e2, InputConstants.KEY_ESCAPE, 0);
                return null;
            });
            c.ticks(4);
            c.check(!isEditor(current(c)), "the second Esc did not close the editor");
            c.check(!(Boolean) Mod.call(e2, "textureLive")
                    && (Integer) ((java.util.concurrent.atomic.AtomicInteger) Mod.field("screenshotcopy.ScreenshotEditorScreen", "LIVE_TEXTURES")).get() == liveBefore,
                    "the texture was not released after closing the second editor");
            c.check(sha(synth).equals(synthSha), "the original synthetic file changed");
        } finally {
            System.clearProperty(CLIP);
            Mod.call(cfg, "setEnabled", was);
            Files.deleteIfExists(clip);
            for (Path p : made) {
                Files.deleteIfExists(p);
            }
        }
    }
}
