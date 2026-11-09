package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 1105-ui-boss-chooser (mod simmenu-1105, ship7): the F7 Boss chooser ({@code bosssim.BossStartScreen}) shows its five
 * phase sections (P1, P2, the P3 sections + Core, P4, P5) and every label fits its button. Takes a picture for the owner.
 */
final class BossChooser1105Cases {

    private static final List<String> WANT = List.of("Section 1", "Section 2", "Section 3", "Section 4", "Core", "Back");

    private BossChooser1105Cases() {
    }

    static void run(UiCase c) {
        if (!Mod.isCheat() || !Mod.has("bosssim.BossStartScreen")) {
            c.skipCase("the boss sim is cheat-only");
            return;
        }
        c.onClient(mc -> {
            try {
                McCompat.setScreen(mc, (Screen) Mod.cls("bosssim.BossStartScreen").getConstructor(Screen.class).newInstance((Screen) null));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        c.ticks(10);
        try {
            List<String> labels = new ArrayList<>();
            List<String> tooWide = c.onClient(mc -> {
                List<String> bad = new ArrayList<>();
                c.check(McCompat.screen(mc) != null && McCompat.screen(mc).getClass().getSimpleName().equals("BossStartScreen"),
                        "the chooser did not open: " + McCompat.screen(mc));
                for (var w : McCompat.screen(mc).children()) {
                    if (!(w instanceof AbstractWidget a)) {
                        continue;
                    }
                    String s = net.minecraft.ChatFormatting.stripFormatting(a.getMessage().getString());
                    labels.add(s);
                    int tw = mc.font.width(s);
                    if (tw > a.getWidth() - 4) {
                        bad.add(s + " (" + tw + " px in a " + a.getWidth() + " px button)");
                    }
                }
                return bad;
            });
            c.note("buttons: " + labels);
            for (String w : WANT) {
                c.check(labels.contains(w), "no '" + w + "' button");
            }
            c.check(labels.stream().filter("Start"::equals).count() == 4, "expected 4 Start buttons (P1, P2, P4, P5)");
            c.check(tooWide.isEmpty(), "labels wider than their buttons: " + tooWide);
            HudEditorCases.screenshot(c, "chooser");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }
}
