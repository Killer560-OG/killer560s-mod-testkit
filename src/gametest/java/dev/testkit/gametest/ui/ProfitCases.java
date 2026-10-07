package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import com.mojang.blaze3d.platform.Window;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 307: {@code /profit}, the profit tracker hub (gui/profit/ProfitCommands, ProfitHubScreen, ProfitTracker).
 *
 * <p>Everything goes through the real mod: the command is executed on the real client dispatcher, the hub is
 * screenshotted and its cards found by their widget class and checked against the pixels (the card's orange stripe
 * must be on the screenshot where layout x Auto Scale factor says), each card is clicked through the real input
 * layer at its DRAWN centre, and Back is clicked the same way. Direct subcommands, aliases, the unknown-name chat
 * line and tab completion are checked against the same dispatcher.
 */
final class ProfitCases {

    private static final String HUB = "gui.profit.ProfitHubScreen";
    private static final int ORANGE = 0xCC6600;
    private static final int GLFW_ESCAPE = 256;

    /** word, card label, screen class (relative to the mod root). */
    private record Exp(String word, String label, String screen) {
    }

    private static final List<Exp> TRACKERS = List.of(
            new Exp("croesus", "Dungeon Profit", "croesus.CroesusTrackerScreen"),
            new Exp("etable", "Experimentation Table", "gui.profit.ExperimentsProfitScreen"));
    // Mining Profit (/profit mining, gui.profit.MiningProfitScreen) and Nucleus Runs (/profit nucleus,
    // gui.profit.NucleusProfitScreen) are shelved until after 2.0 with the rest of mining (mod shelved/mining/;
    // 411-ui-mining-shelved checks they are gone). Put both rows back here, in WORDS and in direct() on restore.

    /** Every word the command accepts: the names above plus their aliases. */
    private static final List<String> WORDS = List.of("croesus", "dungeon", "etable", "experiments");

    private ProfitCases() {
    }

    static void profit(UiCase c) throws Exception {
        // The mod's own list must be exactly what this case expects, so a new tracker cannot go untested.
        @SuppressWarnings("unchecked")
        List<String> words = (List<String>) Mod.staticCall("gui.profit.ProfitTracker", "allWords");
        c.note("ProfitTracker.allWords() = " + words);
        c.check(words.equals(WORDS), "ProfitTracker.allWords() is " + words + ", this case expects " + WORDS
                + " - a tracker was added or renamed: extend ProfitCases");

        hubDrawn(c);
        cardsOpenAndBack(c);
        escapeReturnsToHub(c);
        direct(c);
        unknown(c);
        completion(c);
        croesusAlias(c);
    }

    // ---- /profit: the hub -----------------------------------------------------------------------------------

    private static void hubDrawn(UiCase c) throws Exception {
        run(c, "profit");
        Screen hub = expectScreen(c, HUB, "/profit");
        List<AbstractWidget> cards = cards(hub);
        c.note("hub: " + cards.size() + " card widget(s): " + labels(cards));
        c.check(cards.size() == TRACKERS.size(), "the hub has " + cards.size() + " cards, expected " + TRACKERS.size());
        for (int i = 0; i < TRACKERS.size(); i++) {
            c.check(label(cards.get(i)).equals(TRACKERS.get(i).label()), "card " + i + " is '" + label(cards.get(i))
                    + "', expected '" + TRACKERS.get(i).label() + "'");
        }
        AbstractWidget close = widget(hub, "Close");
        c.check(close != null, "the hub has no Close button");

        // A summary line for every tracker (read straight off the enum the cards draw from).
        for (Object tracker : (Object[]) Mod.cls("gui.profit.ProfitTracker").getEnumConstants()) {
            Object summary = Mod.call(tracker, "summary");
            String text = (String) Mod.call(summary, "text");
            c.note("summary " + tracker + ": \"" + text + "\"");
            c.check(text != null && !text.isBlank(), "the " + tracker + " card has an empty summary line");
        }

        Frames.Drawn drawn = c.onClient(mc -> Frames.extract(mc, hub, -1, -1));
        c.note("hub extract: " + drawn);
        c.check(drawn.texts() >= 4 * TRACKERS.size(), "the hub drew only " + drawn.texts() + " text(s); each card draws 4");

        Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-hub"));
        Report.screenshot(c.name(), shot);
        BufferedImage img = javax.imageio.ImageIO.read(shot.toFile());
        int[] gui = guiSize(c);
        float f = factor(c, hub);
        for (AbstractWidget card : cards) {
            // The 2-wide stripe on the card's left edge is the theme orange: it must be on the screenshot where
            // layout x factor puts it (a card laid out but not drawn there would fail this).
            boolean seen = orangeNear(img, gui, (card.getX() + 1) * f, (card.getY() + card.getHeight() / 2) * f);
            c.note(String.format(Locale.ROOT, "card '%s' at layout %d,%d %dx%d, factor %.3f: orange stripe on screenshot %s",
                    label(card), card.getX(), card.getY(), card.getWidth(), card.getHeight(), f, seen));
            if (!seen) {
                c.problem("card '" + label(card) + "' is not drawn at its laid-out position (no orange stripe there)");
            }
            c.check(card.visible && card.getWidth() > 20 && card.getHeight() > 20, "card '" + label(card)
                    + "' is not visible or has no size");
            c.check(card.getX() >= 0 && card.getY() >= 0 && card.getX() + card.getWidth() <= hub.width
                    && card.getY() + card.getHeight() <= hub.height, "card '" + label(card) + "' is outside the screen");
        }
        close(c);
    }

    // ---- every card opens its tracker, Back returns ---------------------------------------------------------

    private static void cardsOpenAndBack(UiCase c) throws Exception {
        for (Exp e : TRACKERS) {
            run(c, "profit");
            Screen hub = expectScreen(c, HUB, "/profit");
            AbstractWidget card = cardLabelled(c, hub, e.label());
            double[] centre = drawnCentre(c, hub, card);
            clickAt(c, centre[0], centre[1]);
            Screen tracker = expectScreen(c, e.screen(), "clicking the '" + e.label() + "' card at its drawn centre");
            Frames.Drawn drawn = c.onClient(mc -> Frames.extract(mc, tracker, -1, -1));
            c.note(e.word() + " screen " + tracker.getClass().getSimpleName() + " drew " + drawn);
            c.check(drawn.total() > 0, e.screen() + " drew nothing");
            Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-" + e.word()));
            Report.screenshot(c.name(), shot);

            AbstractWidget back = widget(tracker, "< Back");
            c.check(back != null, e.screen() + " opened from the hub has no '< Back' button");
            double[] b = drawnCentre(c, tracker, back);
            clickAt(c, b[0], b[1]);
            Screen after = c.onClient(mc -> McCompat.screen(mc));
            c.note(e.word() + ": Back -> " + (after == null ? "none" : after.getClass().getSimpleName()));
            c.check(after == hub, "Back from " + e.screen() + " did not return to the same hub instance (screen is "
                    + (after == null ? "none" : after.getClass().getName().replace(Mod.ROOT, "")) + ")");
            close(c);
        }
    }

    private static void escapeReturnsToHub(UiCase c) {
        Exp e = TRACKERS.get(1);
        run(c, "profit");
        Screen hub = expectScreen(c, HUB, "/profit");
        double[] centre = drawnCentre(c, hub, cardLabelled(c, hub, e.label()));
        clickAt(c, centre[0], centre[1]);
        expectScreen(c, e.screen(), "the etable card");
        c.ctx().getInput().pressKey(GLFW_ESCAPE);
        c.ticks(3);
        Screen after = c.onClient(mc -> McCompat.screen(mc));
        c.check(after == hub, "Escape on " + e.screen() + " opened from the hub did not return to it (screen is "
                + (after == null ? "none" : after.getClass().getName().replace(Mod.ROOT, "")) + ")");
        c.ctx().getInput().pressKey(GLFW_ESCAPE);
        c.ticks(3);
        Screen gone = c.onClient(mc -> McCompat.screen(mc));
        c.check(gone == null, "Escape on the hub itself left screen " + (gone == null ? "none" : gone.getClass().getName()));
        c.note("Escape: tracker -> hub -> game");
    }

    // ---- /profit <name> -------------------------------------------------------------------------------------

    private static void direct(UiCase c) throws Exception {
        String[][] cases = {
                {"profit croesus", "croesus.CroesusTrackerScreen"}, {"profit dungeon", "croesus.CroesusTrackerScreen"},
                {"profit etable", "gui.profit.ExperimentsProfitScreen"},
                {"profit experiments", "gui.profit.ExperimentsProfitScreen"},
                {"profit ETABLE", "gui.profit.ExperimentsProfitScreen"}};
        for (String[] t : cases) {
            run(c, t[0]);
            Screen s = expectScreen(c, t[1], "/" + t[0]);
            Frames.Drawn drawn = c.onClient(mc -> Frames.extract(mc, s, -1, -1));
            c.check(drawn.total() > 0, "/" + t[0] + " opened " + t[1] + " but it drew nothing");
            c.check(widget(s, "< Back") == null, "/" + t[0] + " opened directly but shows a Back button (nowhere to go)");
            if (t[0].equals("profit etable")) {
                Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-direct-" + t[0].substring(7)));
                Report.screenshot(c.name(), shot);
            }
            c.ctx().getInput().pressKey(GLFW_ESCAPE);
            c.ticks(3);
            Screen gone = c.onClient(mc -> McCompat.screen(mc));
            c.check(gone == null, "Escape on /" + t[0] + " went to " + (gone == null ? "none" : gone.getClass().getName()));
        }
        c.note("direct opens (names, aliases, any case): " + cases.length + " commands, Escape closes to the game");
    }

    private static void unknown(UiCase c) {
        long mark = LogTap.mark();
        String err = CommandSweep.execute(c, "profit bogus");
        c.ticks(3);
        List<String> lines = LogTap.since(mark);
        String chat = null;
        for (String l : lines) {
            if (l.contains("[CHAT]") && l.contains("Unknown tracker")) {
                chat = l;
            }
        }
        c.note("/profit bogus: error=" + err + ", chat line: " + chat);
        c.check(chat != null, "/profit bogus printed no 'Unknown tracker' chat line; log since: " + lines);
        for (String w : WORDS) {
            c.check(chat.contains(w), "the unknown-tracker line does not list '" + w + "': " + chat);
        }
        Screen s = c.onClient(mc -> McCompat.screen(mc));
        c.check(s == null, "/profit bogus opened a screen: " + (s == null ? "" : s.getClass().getName()));
    }

    // ---- tab completion -------------------------------------------------------------------------------------

    private static void completion(UiCase c) throws Exception {
        List<String> all = suggest(c, "profit ");
        c.note("completions after '/profit ': " + all);
        c.check(new java.util.TreeSet<>(all).equals(new java.util.TreeSet<>(WORDS)), "'/profit ' offers " + all
                + ", expected " + WORDS);
        List<String> e = suggest(c, "profit e");
        c.note("completions after '/profit e': " + e);
        c.check(new java.util.TreeSet<>(e).equals(new java.util.TreeSet<>(List.of("etable", "experiments"))),
                "'/profit e' offers " + e);
        List<String> n = suggest(c, "profit d");
        c.check(n.equals(List.of("dungeon")), "'/profit d' offers " + n);
        List<String> none = suggest(c, "profit zzz");
        c.check(none.isEmpty(), "'/profit zzz' offers " + none);
        c.note("'/profit d' -> " + n + ", '/profit zzz' -> " + none);
    }

    /** /croesus profit is kept as an alias: it still opens the dungeon tracker (with no parent). */
    private static void croesusAlias(UiCase c) {
        run(c, "croesus profit");
        expectScreen(c, "croesus.CroesusTrackerScreen", "/croesus profit");
        close(c);
        run(c, "croesus");
        expectScreen(c, "croesus.CroesusTrackerScreen", "/croesus");
        close(c);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static void run(UiCase c, String input) {
        String err = CommandSweep.execute(c, input);
        c.check(err == null, "'/" + input + "' threw: " + err);
        c.ticks(4);
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(1);
    }

    private static Screen expectScreen(UiCase c, String rel, String what) {
        Screen s = c.onClient(mc -> McCompat.screen(mc));
        String got = s == null ? "none" : s.getClass().getName();
        c.check(got.equals(Mod.ROOT + rel), what + " should open " + rel + " but the screen is "
                + got.replace(Mod.ROOT, ""));
        return s;
    }

    private static List<AbstractWidget> cards(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : Frames.widgets(s)) {
            if (w.getClass().getSimpleName().equals("ProfitCardWidget")) {
                out.add(w);
            }
        }
        return out;
    }

    private static AbstractWidget cardLabelled(UiCase c, Screen s, String label) {
        for (AbstractWidget w : cards(s)) {
            if (label(w).equals(label)) {
                return w;
            }
        }
        c.check(false, "no card labelled '" + label + "' on the hub");
        return null;
    }

    private static AbstractWidget widget(Screen s, String label) {
        for (AbstractWidget w : Frames.widgets(s)) {
            if (label(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    private static String label(AbstractWidget w) {
        return w.getMessage().getString();
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(label(w));
        }
        return out;
    }

    private static float factor(UiCase c, Screen s) {
        return c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue());
    }

    private static int[] guiSize(UiCase c) {
        return c.onClient(mc -> new int[]{mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
    }

    /** Centre of {@code w} in GUI pixels: layout position times the screen's Auto Scale factor. */
    private static double[] drawnCentre(UiCase c, Screen s, AbstractWidget w) {
        float f = factor(c, s);
        return new double[]{(w.getX() + w.getWidth() / 2.0) * f, (w.getY() + w.getHeight() / 2.0) * f};
    }

    /** A real click at GUI coordinates through the input layer. */
    private static void clickAt(UiCase c, double gx, double gy) {
        double[] win = c.onClient(mc -> {
            Window w = mc.getWindow();
            return new double[]{gx * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    gy * w.getScreenHeight() / (double) w.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(win[0], win[1]);
        c.ticks(1);
        c.ctx().getInput().pressMouse(0);
        c.ticks(4);
    }

    /** Whether a pixel within 3 screenshot pixels of GUI point (gx, gy) is the theme orange. */
    private static boolean orangeNear(BufferedImage img, int[] gui, double gx, double gy) {
        double px = gx * img.getWidth() / gui[0];
        double py = gy * img.getHeight() / gui[1];
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                int x = (int) Math.floor(px) + dx;
                int y = (int) Math.floor(py) + dy;
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (Math.abs(r - ((ORANGE >> 16) & 0xFF)) <= 12 && Math.abs(g - ((ORANGE >> 8) & 0xFF)) <= 12
                        && b <= 12) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> suggest(UiCase c, String input) throws Exception {
        java.util.concurrent.CompletableFuture<Suggestions> future = c.onClient(mc -> {
            CommandDispatcher<Object> d = CommandSweep.dispatcher();
            Object src = CommandSweep.source(mc);
            return d.getCompletionSuggestions(d.parse(input, src));
        });
        List<String> out = new ArrayList<>();
        for (Suggestion s : future.get(3, TimeUnit.SECONDS).getList()) {
            out.add(s.getText());
        }
        return out;
    }
}
