package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * The themed title screen's three sections (mod whatsstyle-1113, killer560 2026-11-13): Singleplayer, Multiplayer,
 * Dungeon Sim / rule / Mod Settings ... Swap Accounts / rule / Options... | Quit Game. Both breaks keep the same spacing,
 * every column row is full width, the logo's drawn bottom clears the panel above Singleplayer, and Options/Quit stay on
 * screen. Used by 341 (every window size and Mod Menu style) and 1111.
 */
final class MainMenuSections {

    private static final String LAYOUT = "mainmenu.MainMenuTitleLayout";

    private MainMenuSections() {
    }

    private static AbstractWidget find(Screen s, String labelOrKey) {
        for (AbstractWidget w : Screens.getWidgets(s)) {
            if (!w.visible || w.getMessage() == null) {
                continue;
            }
            String key = w.getMessage().getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
            if (labelOrKey.equals(key) || labelOrKey.equals(w.getMessage().getString())) {
                return w;
            }
        }
        return null;
    }

    /** Null when the layout holds, else what is wrong. */
    static String check(Screen s) {
        AbstractWidget sp = find(s, "menu.singleplayer");
        AbstractWidget sim = find(s, "Dungeon Sim");
        AbstractWidget set = find(s, "Mod Settings");
        AbstractWidget swap = find(s, "Swap Accounts");
        AbstractWidget opt = find(s, "menu.options");
        AbstractWidget quit = find(s, "menu.quit");
        if (sp == null || sim == null || set == null || swap == null || opt == null || quit == null) {
            return "missing a button: sp " + sp + " sim " + sim + " settings " + set + " swap " + swap + " options " + opt
                    + " quit " + quit;
        }
        for (AbstractWidget w : new AbstractWidget[]{sp, sim, set, swap}) {
            if (w.getWidth() != 200) {
                return w.getMessage().getString() + " is " + w.getWidth() + " wide, want the full 200";
            }
        }
        int section = (Integer) Mod.staticCall(LAYOUT, "sectionRuleY", s);
        int rule = (Integer) Mod.staticCall(LAYOUT, "ruleY", s);
        if (!(section >= sim.getY() + sim.getHeight() && section + 2 <= set.getY())) {
            return "no rule between Dungeon Sim (" + sim.getY() + ") and Mod Settings (" + set.getY() + "): " + section;
        }
        if (!(rule >= swap.getY() + swap.getHeight() && rule + 2 <= opt.getY())) {
            return "no rule between Swap Accounts (" + swap.getY() + ") and Options (" + opt.getY() + "): " + rule;
        }
        int gap1 = set.getY() - sim.getY();
        int gap2 = opt.getY() - swap.getY();
        if (gap1 != gap2 || gap1 < 30) {
            return "section breaks differ or are not breaks: Sim->Mod Settings " + gap1 + ", Swap->Options " + gap2;
        }
        if (section - sim.getY() != rule - swap.getY()) {
            return "the two rules sit differently in their gaps";
        }
        int logo = (Integer) Mod.staticCall(LAYOUT, "logoBottom", s);
        if (logo + 4 > sp.getY()) {
            return "the logo's bottom (" + logo + ") runs into the panel above Singleplayer (" + sp.getY() + ")";
        }
        if (opt.getY() + opt.getHeight() > s.height || quit.getY() != opt.getY()) {
            return "Options/Quit run off the screen or apart: " + opt.getY() + " / " + quit.getY() + " of " + s.height;
        }
        return null;
    }
}
