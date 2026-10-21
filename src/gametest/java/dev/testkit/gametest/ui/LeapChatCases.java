package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1076: the Leap Order editor's chat panel (mod branch leap-chat-1021; killer560, 2026-10-21: players type their class in
 * chat, he reads it and answers himself, no auto-fill). On each stage - page 1 (class cards), the class wizard and the
 * layout page - the panel is opened or kept open, shows a chat line seeded through the real chat listener, and a line
 * typed into its input with Enter reaches the integrated server, which echoes it back as player chat (the external proof
 * it was sent, not the panel's own counter). Also: closed by default, the editor stays open after each send, Escape
 * leaves the box without closing the editor, the panel never reaches down to a bottom-row button and the stage's own
 * controls stay left of it, the wheel scrolls it back and forward, and Hide Chat closes it.
 */
final class LeapChatCases {

    private static final int KEY_ENTER = 257;
    private static final int KEY_ESCAPE = 256;

    private LeapChatCases() {
    }

    static void chatPanel(UiCase c) throws Exception {
        LeapOrderCases.State st = LeapOrderCases.setUp(c);
        try {
            String tok = Long.toString(System.nanoTime() % 1_000_000L, 36);
            String seed = "LoAnna: im mage " + tok;
            seed(c, seed);
            List<String> trail = new ArrayList<>();

            // ---- page 1 ----
            Screen s = LeapWizardCases.openPage1(c);
            c.check(!open(c, s), "the chat panel is open before the Chat button was pressed");
            LeapWizardCases.press(c, s, "Chat");
            c.check(open(c, s), "the Chat button did not open the panel");
            c.check(typing(c, s), "opening the panel did not give its input the keyboard");
            stageChecks(c, s, "page 1", seed, List.of("Reset All", "Done"), List.of("Mage", "Berserker", "Archer"));
            sendTyped(c, s, "page1 " + tok, false);
            trail.add("page 1: seeded line shown, typed line echoed by the server");

            // Escape leaves the box, the editor stays.
            focusInput(c, s);
            c.ctx().getInput().pressKey(KEY_ESCAPE);
            c.ticks(3);
            c.check(current(c) == s, "Escape in the chat box closed the editor");
            c.check(!typing(c, s), "Escape left the chat box focused");
            trail.add("Escape in the box keeps the editor open");

            // ---- the class wizard ----
            LeapWizardCases.clickArcher(c, s);
            c.check(LeapWizardCases.wizard(c, s) != null, "picking Archer with unknown classes did not start the wizard");
            c.check(open(c, s), "the panel closed when the wizard started");
            stageChecks(c, s, "wizard", seed, List.of("Back", "Skip", "Next"),
                    List.of("Class:", "Mage", "Tank", "Healer", "Archer", "Berserker"));
            sendTyped(c, s, "wizard " + tok, true);
            c.check(LeapWizardCases.wizard(c, s) != null && LeapWizardCases.index(c, s) == 0,
                    "sending from the wizard moved the wizard");
            trail.add("wizard: seeded line shown, click-to-focus, typed line echoed");

            // ---- the layout page ----
            LeapWizardCases.press(c, s, "Skip");
            c.check(LeapWizardCases.wizard(c, s) == null, "Skip left the wizard running");
            c.check(open(c, s), "the panel closed on the layout page");
            stageChecks(c, s, "layout", seed, List.of("Back", "Reset Classes", "Reset Positions", "Done"),
                    List.of("Default Order", "Class:"));
            int[] panel = panelBounds(c, s);
            for (int spot = 0; spot < 4; spot++) {
                final int sp = spot;
                int[] b = c.onClient(mc -> (int[]) Mod.call(s, "spotBounds", sp));
                c.check(b[0] + b[2] <= panel[0], "layout spot " + spot + " reaches into the chat panel: " + b[0] + "+" + b[2]
                        + " vs panel x " + panel[0]);
            }
            sendTyped(c, s, "layout " + tok, true);
            trail.add("layout: seeded line shown, spots left of the panel, typed line echoed");

            // The wheel: back through older lines and forward again.
            // More than any panel holds, and more than the 300 messages the panel wraps, so the oldest drop off its end
            // while it is scrolled back (that once moved the view).
            c.onClient(mc -> {
                for (int i = 0; i < 320; i++) {
                    McCompat.chatListener(mc).handleSystemMessage(Component.literal("filler " + i + " " + tok), false);
                }
                return null;
            });
            c.ticks(3);
            List<String> bottom = lines(c, s);
            c.check(String.join("|", bottom).contains("filler 319 " + tok), "the newest line is not at the bottom: " + bottom);
            c.onClient(mc -> s.mouseScrolled(panel[0] + 10, panel[1] + 20, 0, 1));
            c.ticks(3);
            List<String> back = lines(c, s);
            c.check(!String.join("|", back).contains("filler 319 " + tok), "scrolling up still shows the newest line: " + back);
            seed(c, "while scrolled " + tok);
            c.ticks(3);
            c.check(lines(c, s).equals(back), "a new line moved a scrolled-back view: " + lines(c, s) + " vs " + back);
            for (int i = 0; i < 60; i++) {
                c.onClient(mc -> s.mouseScrolled(panel[0] + 10, panel[1] + 20, 0, -1));
            }
            c.ticks(3);
            c.check(String.join("|", lines(c, s)).contains("while scrolled " + tok), "scrolling down did not return to the newest");
            trail.add("wheel scrolls back, a scrolled view holds still, scrolling down follows again");
            shot(c, s, "layout");

            LeapWizardCases.press(c, s, "Hide Chat");
            c.check(!open(c, s), "Hide Chat did not close the panel");
            c.check(InvBatchCases.widget(c, s, "Chat") != null, "after Hide Chat there is no Chat button to open it again");
            c.note(String.join("; ", trail));
        } finally {
            LeapOrderCases.restore(c, st);
        }
    }

    // ---- per stage --------------------------------------------------------------------------------------------------

    private static void stageChecks(UiCase c, Screen s, String stage, String seed, List<String> bottomRow,
                                    List<String> stageControls) throws Exception {
        c.ticks(3);
        List<String> shown = lines(c, s);
        c.check(shown.stream().anyMatch(l -> l.contains(seed)), stage + ": the panel does not show the seeded line: " + shown);
        int[] p = panelBounds(c, s);
        for (String l : bottomRow) {
            AbstractWidget w = InvBatchCases.widget(c, s, l);
            c.check(w != null, stage + ": no '" + l + "' button with the panel open");
            c.check(w.getY() >= p[1] + p[3], stage + ": the panel (to y " + (p[1] + p[3]) + ") covers '" + l + "' at y "
                    + w.getY());
        }
        for (String l : stageControls) {
            AbstractWidget w = InvBatchCases.widget(c, s, l);
            c.check(w != null, stage + ": no '" + l + "' control with the panel open");
            boolean header = w.getY() + w.getHeight() <= p[1];
            c.check(header || w.getX() + w.getWidth() <= p[0], stage + ": '" + l + "' (" + w.getX() + "+" + w.getWidth()
                    + ") reaches under the panel at x " + p[0]);
        }
        shot(c, s, stage.replace(' ', '-'));
    }

    /** Types a line and presses Enter; the editor must stay, the box empty, and the server echo the line back. */
    private static void sendTyped(UiCase c, Screen s, String text, boolean clickFirst) {
        if (clickFirst) {
            c.check(!typing(c, s), "control: the input already had the keyboard before it was clicked");
            focusInput(c, s);
            c.check(typing(c, s), "clicking the chat input did not focus it");
        }
        int before = (Integer) c.onClient(mc -> Mod.staticCall("leapmenu.LeapOrderScreen", "chatPanelSent"));
        c.ctx().getInput().typeChars(text);
        c.ticks(2);
        String typed = c.onClient(mc -> ((net.minecraft.client.gui.components.EditBox) Mod.field(chat(s), "input")).getValue());
        c.check(typed.equals(text), "the box holds '" + typed + "' after typing '" + text + "'");
        c.ctx().getInput().pressKey(KEY_ENTER);
        c.ticks(2);
        c.check(current(c) == s, "sending closed the editor");
        int after = (Integer) c.onClient(mc -> Mod.staticCall("leapmenu.LeapOrderScreen", "chatPanelSent"));
        c.check(after == before + 1, "the panel sent " + (after - before) + " lines for one Enter");
        String left = c.onClient(mc -> ((net.minecraft.client.gui.components.EditBox) Mod.field(chat(s), "input")).getValue());
        c.check(left.isEmpty(), "the box still holds '" + left + "' after Enter");
        boolean echoed = false;
        for (int i = 0; i < 60 && !echoed; i++) {
            echoed = c.onClient(mc -> {
                for (GuiMessage m : allMessages(mc)) {
                    String line = m.content().getString();
                    if (line.contains(text) && !line.startsWith("LoAnna")) {
                        return true;
                    }
                }
                return false;
            });
            if (!echoed) {
                c.ticks(1);
            }
        }
        c.check(echoed, "the server never echoed '" + text + "' back as chat");
        c.ticks(2);
        c.check(lines(c, s).stream().anyMatch(l -> l.contains(text)), "the panel does not show the sent line: " + lines(c, s));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static void seed(UiCase c, String text) {
        c.onClient(mc -> {
            McCompat.chatListener(mc).handleSystemMessage(Component.literal(text), false);
            return null;
        });
        c.ticks(1);
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> allMessages(net.minecraft.client.Minecraft mc) {
        return (List<GuiMessage>) Mod.field(McCompat.chat(mc), "allMessages");
    }

    private static Object chat(Screen s) {
        return Mod.field(s, "chat");
    }

    private static int[] panelBounds(UiCase c, Screen s) {
        return c.onClient(mc -> ((int[]) Mod.field(chat(s), "bounds")).clone());
    }

    private static void focusInput(UiCase c, Screen s) {
        AbstractWidget box = c.onClient(mc -> (AbstractWidget) Mod.field(chat(s), "input"));
        InvBatchCases.clickWidget(c, s, box);
    }

    private static boolean open(UiCase c, Screen s) {
        return c.onClient(mc -> (Boolean) Mod.call(s, "chatPanelOpen"));
    }

    private static boolean typing(UiCase c, Screen s) {
        return c.onClient(mc -> (Boolean) Mod.call(s, "chatPanelTyping"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(UiCase c, Screen s) {
        return c.onClient(mc -> List.copyOf((List<String>) Mod.call(s, "chatPanelLines")));
    }

    private static Screen current(UiCase c) {
        return c.onClient(mc -> McCompat.screen(mc));
    }

    private static void shot(UiCase c, Screen s, String suffix) throws Exception {
        InvBatchCases.cursor(c, s, 2, 2);
        c.ticks(2);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        dev.testkit.harness.Report.screenshot(name, taken);
        String dir = System.getenv("TESTKIT_LEAPCHAT_SHOTS");
        if (dir != null && !dir.isBlank()) {
            Path out = Path.of(dir).resolve(name + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
