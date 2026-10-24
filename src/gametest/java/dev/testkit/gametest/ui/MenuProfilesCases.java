package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.mod.Quiet;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Profiles page of the Option A menu (mod menu-profiles-ctrlf; killer560, 2026-10-13: "I dont like how this profile
 * screen is set up"). Every press is a REAL click on the screen at the button's center, so a painted card under a button
 * that took the click would fail it, and every action is proved on disk:
 * <ul>
 *   <li>948 the page: an Active Profile card naming the loaded profile, a card per saved profile (one per
 *       {@code listProfiles} entry, each holding its Export and Delete, and Load unless it is the active one); typing a
 *       name and pressing Save Current As New writes that profile's folder with files; New Blank Profile writes another;
 *       Load on a card writes the active marker and the card then has no Load (the Active badge's card); Delete once
 *       leaves the folder and turns the button into a red Confirm, Confirm removes the folder; Ctrl F with the name box
 *       focused moves the cursor to the search without touching the name; screenshots in all five themes.</li>
 * </ul>
 */
final class MenuProfilesCases {

    private MenuProfilesCases() {
    }

    private static final String PM = "profiles.ProfileManager";

    private static List<AbstractWidget> rows(Screen s) {
        return MenuACases.paneRows(s);
    }

    private static boolean isPanel(AbstractWidget w) {
        return w.getClass().getSimpleName().equals("PaintedPanel");
    }

    private static AbstractWidget panel(Screen s, String label) {
        for (AbstractWidget w : rows(s)) {
            if (isPanel(w) && ModScreenDriver.label(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    /** The real button labeled {@code label} inside {@code card}'s box, or null. */
    private static AbstractWidget buttonIn(Screen s, AbstractWidget card, String label) {
        for (AbstractWidget w : rows(s)) {
            if (!isPanel(w) && ModScreenDriver.label(w).equals(label) && w.getX() >= card.getX()
                    && w.getX() + w.getWidth() <= card.getX() + card.getWidth() && w.getY() >= card.getY()
                    && w.getY() + w.getHeight() <= card.getY() + card.getHeight()) {
                return w;
            }
        }
        return null;
    }

    private static AbstractWidget row(Screen s, String label) {
        for (AbstractWidget w : rows(s)) {
            if (!isPanel(w) && ModScreenDriver.label(w).equals(label)) {
                return w;
            }
        }
        return null;
    }

    /**
     * A real left click at the widget's center, through the screen (as a mouse press arrives). A widget outside the
     * visible pane (the narrow drawer of a testing build's Untested category) is scrolled into view first, as he would.
     */
    private static void click(Screen s, AbstractWidget w) {
        int top = (Integer) Mod.field(s, "contentY");
        int view = (Integer) Mod.field(s, "visibleContentHeight");
        int scroll = (Integer) Mod.field(s, "scrollOffset");
        int delta = 0;
        if (w.getY() < top) {
            delta = w.getY() - top - 4;
        } else if (w.getY() + w.getHeight() > top + view) {
            delta = w.getY() + w.getHeight() - (top + view) + 4;
        }
        if (delta != 0) {
            String label = ModScreenDriver.label(w);
            int x = w.getX();
            int y = w.getY();
            Mod.setField(Mod.ROOT + MenuACases.SCREEN, "scrollOffset", Math.max(0, scroll + delta));
            Mod.call(s, "refresh");
            int moved = scroll - (Integer) Mod.field(s, "scrollOffset");
            AbstractWidget again = null;
            for (AbstractWidget r : rows(s)) {
                if (!isPanel(r) && ModScreenDriver.label(r).equals(label) && r.getX() == x && r.getY() == y + moved) {
                    again = r;
                }
            }
            if (again == null) {
                throw new AssertionError("lost '" + label + "' scrolling it into view");
            }
            w = again;
        }
        MenuACases.press(s, w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0);
    }

    /** Shows the Profiles tab wherever it lives (its page; a drawer in a testing build's Untested category). */
    private static void showProfiles(Screen s) {
        Mod.call(s, "openFeature", MenuACases.leaf("ProfilesTab"));
    }

    private static Path dir(String name) {
        return ((Path) R.getStatic(R.cls(PM), "PROFILES_DIR")).resolve(name);
    }

    private static int files(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return -1;
        }
        try (java.util.stream.Stream<Path> st = Files.list(dir)) {
            return (int) st.count();
        }
    }

    private static String marker() throws Exception {
        Path m = (Path) R.getStatic(R.cls(PM), "ACTIVE_MARKER");
        return Files.exists(m) ? Files.readString(m, StandardCharsets.UTF_8).trim() : null;
    }

    static void page(UiCase c) throws Exception {
        String pre = "tk-948-pre";
        String alpha = "tk-948-alpha";
        String blank = "tk-948-blank";
        String[] extra = {"Dungeons", "Legit", "Mining"};
        Path markerPath = (Path) R.getStatic(R.cls(PM), "ACTIVE_MARKER");
        String activeBefore = marker();
        Object menuCfg = Mod.cfg("gui.MenuConfig");
        String oldTheme = ((Enum<?>) Mod.call(menuCfg, "getTheme")).name();
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        Object hud = Mod.cfg("hud.HudConfig");
        boolean oldAuto = (Boolean) Mod.call(hud, "isAutoScale");
        @SuppressWarnings("unchecked")
        List<String> existing = (List<String>) Mod.staticCall(PM, "listProfiles");
        List<String> made = new ArrayList<>();
        boolean loaded = false;
        Screen[] s = new Screen[1];
        try {
            c.onClient(mc -> {
                HudMenuProfilesCases.resultText(Mod.staticCall(PM, "saveCurrentAsProfile", pre));
                made.add(pre);
                for (String e : extra) {
                    if (!existing.contains(e)) {
                        Mod.staticCall(PM, "saveCurrentAsProfile", e);
                        made.add(e);
                    }
                }
                Mod.call(menuCfg, "setTheme", Mod.enumValue("gui.MenuTheme", "AMBER"));
                try {
                    s[0] = MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                Mod.call(s[0], "selectCategory", "Home");
                return null;
            });
            c.ticks(3);
            // Profiles, wherever it lives (its own page; in a testing build a drawer in Untested). Home's Profiles button
            // went on 2026-10-13 (mod doc-menu, killer560: the sidebar has Profiles), so the leaf is opened directly.
            String reached = c.onClient(mc -> {
                if (Mod.call(s[0], "homeButtonBounds", "Profiles") != null) {
                    return "Home still has a Profiles button";
                }
                Mod.call(s[0], "openFeature", MenuACases.leaf("ProfilesTab"));
                Object open = Mod.call(s[0], "openFeature");
                return row(s[0], "Profile name") != null ? null : "opening the Profiles tab left the menu on "
                        + (open == null ? "no drawer" : open.getClass().getSimpleName()) + " with no Profile name box";
            });
            c.check(reached == null, reached);
            boolean drawer = c.onClient(mc -> Mod.call(s[0], "openFeature") != null);
            c.note("the Profiles tab opened " + (drawer ? "in a drawer (testing build: Untested)" : "as its page")
                    + "; Home has no Profiles button");

            // Save Current As New: the name typed into the real box (a real click focuses it).
            String why = c.onClient(mc -> {
                AbstractWidget box = row(s[0], "Profile name");
                if (!(box instanceof EditBox eb)) {
                    return "no Profile name box on the page";
                }
                click(s[0], box);
                if (!eb.isFocused()) {
                    return "a click on the name box did not focus it";
                }
                MenuACases.type(s[0], alpha);
                if (!alpha.equals(eb.getValue())) {
                    return "the name box holds '" + eb.getValue() + "' after typing";
                }
                // Ctrl F while the name box has focus: the search takes the cursor, the name stays as typed.
                MenuACases.key(s[0], InputConstants.KEY_F, 2 /* GLFW_MOD_CONTROL */);
                EditBox search = (EditBox) Mod.field(s[0], "searchField");
                if (!search.isFocused() || eb.isFocused()) {
                    return "Ctrl F with the name box focused: search focused " + search.isFocused() + ", name box "
                            + eb.isFocused();
                }
                if (!alpha.equals(eb.getValue()) || !search.getValue().isEmpty()) {
                    return "Ctrl F changed the text: name '" + eb.getValue() + "', search '" + search.getValue() + "'";
                }
                MenuACases.key(s[0], InputConstants.KEY_ESCAPE, 0); // clears and leaves the search
                c.note("Ctrl F with the name box focused moved the cursor to the search; the name kept '" + alpha + "'");
                AbstractWidget save = row(s[0], "Save Current As New");
                if (save == null) {
                    return "no Save Current As New button";
                }
                click(s[0], save);
                return null;
            });
            c.check(why == null, why);
            made.add(alpha);
            int alphaFiles = files(dir(alpha));
            c.note("Save Current As New wrote " + dir(alpha) + " with " + alphaFiles + " file(s)");
            c.check(alphaFiles >= 5, "Save Current As New wrote " + alphaFiles + " file(s) for " + alpha);

            // New Blank Profile, named from the box.
            why = c.onClient(mc -> {
                AbstractWidget box = row(s[0], "Profile name");
                click(s[0], box);
                MenuACases.type(s[0], blank);
                AbstractWidget b = row(s[0], "New Blank Profile");
                if (b == null) {
                    return "no New Blank Profile button";
                }
                click(s[0], b);
                return null;
            });
            c.check(why == null, why);
            made.add(blank);
            int blankFiles = files(dir(blank));
            c.note("New Blank Profile wrote " + dir(blank) + " with " + blankFiles + " file(s)");
            c.check(blankFiles >= 5, "New Blank Profile wrote " + blankFiles + " file(s) for " + blank);

            // A card per profile, each with its own buttons.
            why = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<String> listed = (List<String>) Mod.staticCall(PM, "listProfiles");
                String active = (String) Mod.staticCall(PM, "getActiveProfile");
                List<String> cards = new ArrayList<>();
                for (AbstractWidget w : rows(s[0])) {
                    if (isPanel(w) && listed.contains(ModScreenDriver.label(w))) {
                        cards.add(ModScreenDriver.label(w));
                    }
                }
                if (!cards.equals(listed)) {
                    return "profile cards " + cards + " are not the saved profiles " + listed;
                }
                for (String p : listed) {
                    AbstractWidget card = panel(s[0], p);
                    boolean act = p.equals(active);
                    if (buttonIn(s[0], card, "Export") == null || buttonIn(s[0], card, "Delete") == null
                            || (buttonIn(s[0], card, "Load") == null) != act) {
                        return "card '" + p + "' (active " + act + ") lacks its buttons";
                    }
                }
                c.note(cards.size() + " profile cards " + cards + ", each with its Export and Delete (and Load unless active)");
                return null;
            });
            c.check(why == null, why);

            // Load: a real click on the alpha card's Load.
            why = c.onClient(mc -> {
                AbstractWidget card = panel(s[0], alpha);
                AbstractWidget load = card == null ? null : buttonIn(s[0], card, "Load");
                if (load == null) {
                    return "no Load on the " + alpha + " card";
                }
                click(s[0], load);
                return null;
            });
            c.check(why == null, why);
            loaded = true;
            c.ticks(2);
            String now = marker();
            c.note("after Load: the active marker on disk reads '" + now + "'");
            c.check(alpha.equals(now), "Load did not make " + alpha + " active on disk (marker '" + now + "')");
            why = c.onClient(mc -> {
                showProfiles(s[0]);
                AbstractWidget head = null;
                for (AbstractWidget w : rows(s[0])) {
                    if (isPanel(w) && ModScreenDriver.label(w).startsWith("Active profile: ")) {
                        head = w;
                    }
                }
                if (head == null || !ModScreenDriver.label(head).equals("Active profile: " + alpha)) {
                    return "the Active Profile card reads " + (head == null ? "nothing" : "'" + ModScreenDriver.label(head) + "'");
                }
                AbstractWidget card = panel(s[0], alpha);
                if (card == null || buttonIn(s[0], card, "Load") != null || buttonIn(s[0], card, "Export") == null) {
                    return "the active card still offers Load (or lost Export)";
                }
                c.note("the Active Profile card reads '" + ModScreenDriver.label(head) + "'; the active card has no Load");
                return null;
            });
            c.check(why == null, why);

            // Delete asks for a second click.
            why = c.onClient(mc -> {
                AbstractWidget card = panel(s[0], blank);
                AbstractWidget del = card == null ? null : buttonIn(s[0], card, "Delete");
                if (del == null) {
                    return "no Delete on the " + blank + " card";
                }
                click(s[0], del);
                return null;
            });
            c.check(why == null, why);
            int afterOne = files(dir(blank));
            c.note("after one Delete click the folder holds " + afterOne + " file(s)");
            c.check(afterOne == blankFiles, "one Delete click already removed " + blank + " (" + afterOne + " files)");
            why = c.onClient(mc -> {
                AbstractWidget card = panel(s[0], blank);
                AbstractWidget confirm = card == null ? null : buttonIn(s[0], card, "Confirm");
                if (confirm == null) {
                    return "the Delete button did not turn into Confirm";
                }
                click(s[0], confirm);
                return null;
            });
            c.check(why == null, why);
            boolean gone = !Files.exists(dir(blank));
            c.note("after Confirm the folder exists: " + !gone);
            c.check(gone, "Confirm did not delete " + dir(blank));
            boolean cardGone = c.onClient(mc -> panel(s[0], blank) == null);
            c.check(cardGone, "the deleted profile still has a card");
            made.remove(blank);

            // Screenshots at 1920x1080 GUI 2 (Auto Scale on, as he plays), every theme.
            c.onClient(mc -> {
                Mod.call(Mod.cfg("hud.HudConfig"), "setAutoScale", true);
                return null;
            });
            c.ctx().getInput().resizeWindow(1920, 1080);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            c.ticks(3);
            List<String> shots = new ArrayList<>();
            for (String theme : new String[]{"AMBER", "LIGHT", "DARK", "OCEAN"}) {
                c.onClient(mc -> {
                    // re-read: a profile Load replaces the config objects
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setTheme", Mod.enumValue("gui.MenuTheme", theme));
                    showProfiles(s[0]);
                    return null;
                });
                shots.add(MenuACases.shot(c, s[0], "profiles-" + theme.toLowerCase(java.util.Locale.ROOT), null));
            }
            int[] customSet = {0xFF2EC4B6, 0xFF101820, 0xFF1B2733, 0xFFE8F1F2, 0xFF2EC4B6};
            c.onClient(mc -> {
                Object[] slots = Mod.cls("gui.MenuTheme$Slot").getEnumConstants();
                for (int k = 0; k < slots.length; k++) {
                    Mod.call(Mod.cfg("gui.MenuConfig"), "setCustomColour", slots[k], customSet[k]);
                }
                showProfiles(s[0]);
                return null;
            });
            shots.add(MenuACases.shot(c, s[0], "profiles-custom", null));
            c.note("shots: " + shots);
        } finally {
            boolean wasLoaded = loaded;
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                if (wasLoaded) {
                    // alpha was a snapshot of the live settings; pre is the same snapshot - put it back from disk
                    Mod.staticCall(PM, "applyProfile", pre);
                }
                for (String p : made) {
                    Mod.staticCall(PM, "deleteProfile", p);
                }
                Mod.staticCall(PM, "deleteProfile", blank);
                try {
                    if (activeBefore == null) {
                        Files.deleteIfExists(markerPath);
                    } else {
                        Files.writeString(markerPath, activeBefore);
                    }
                } catch (Exception ignored) {
                    // nothing later reads it
                }
                Object cfg = Mod.cfg("gui.MenuConfig");
                Mod.call(cfg, "resetCustom");
                Mod.call(cfg, "setTheme", Mod.enumValue("gui.MenuTheme", oldTheme));
                Mod.call(cfg, "save");
                Mod.call(Mod.cfg("hud.HudConfig"), "setAutoScale", oldAuto);
                mc.options.guiScale().set(oldGui);
                // A profile load re-reads every config from disk: the suite's quiet settings and Skyblock Only off.
                if (Quiet.enabled()) {
                    Quiet.apply();
                }
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(3);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    /**
     * 1553: Rename in place, no swap message, and the removed lines (mod names-profiles-1015; killer560, 2026-10-15). A real
     * click on a card's Rename turns its name into a box, typing and Save moves the folder (the marker follows an active
     * profile), a taken or empty name is refused; a real click on Load leaves the on-screen message empty; and
     * ProfilesTab no longer holds the "Setting changes save into it", "Save copies..." or "N files" texts.
     */
    static void renameAndQuiet(UiCase c) throws Exception {
        String a = "tk-1553-a";
        String b = "tk-1553-b";
        String other = "tk-1553-other";
        Path markerPath = (Path) R.getStatic(R.cls(PM), "ACTIVE_MARKER");
        String activeBefore = marker();
        List<String> made = new ArrayList<>();
        Screen[] s = new Screen[1];
        try {
            // The removed lines: read the compiled class's own text (a control string proves the right bytes were read).
            Class<?> tab = Mod.cls("gui.tab.ProfilesTab");
            byte[] bytes;
            try (java.io.InputStream in = tab.getResourceAsStream(tab.getSimpleName() + ".class")) {
                bytes = in.readAllBytes();
            }
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            c.check(text.contains("ACTIVE PROFILE"), "control: ProfilesTab.class was not read (no 'ACTIVE PROFILE')");
            for (String gone : new String[]{"Setting changes save into it", "Save copies your current settings", " files",
                    "last saved", "Never saved"}) {
                c.check(!text.contains(gone), "ProfilesTab still holds the text '" + gone + "'");
            }
            c.note("ProfilesTab.class has none of: Setting changes save into it / Save copies your current settings / files / Never saved");

            c.onClient(mc -> {
                Mod.staticCall(PM, "saveCurrentAsProfile", a);
                Mod.staticCall(PM, "saveCurrentAsProfile", other);
                made.add(a);
                made.add(other);
                try {
                    s[0] = MenuACases.open(mc);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                showProfiles(s[0]);
                return null;
            });
            c.ticks(3);
            int filesBefore = files(dir(a));
            c.check(filesBefore > 0, "the saved profile has no files");

            // Rename by a real click: Rename, type, Save.
            String why = c.onClient(mc -> {
                AbstractWidget card = panel(s[0], a);
                AbstractWidget rename = card == null ? null : buttonIn(s[0], card, "Rename");
                if (rename == null) {
                    return "no Rename button on the " + a + " card";
                }
                click(s[0], rename);
                Mod.call(s[0], "refresh");
                card = panel(s[0], a);
                AbstractWidget box = row(s[0], "New name");
                if (!(box instanceof EditBox eb)) {
                    return "Rename did not turn the card's name into a box";
                }
                if (!a.equals(eb.getValue())) {
                    return "the rename box starts with '" + eb.getValue() + "', not the current name";
                }
                click(s[0], box);
                eb.setValue("");
                MenuACases.type(s[0], b);
                if (!b.equals(eb.getValue())) {
                    return "the rename box holds '" + eb.getValue() + "' after typing";
                }
                AbstractWidget save = buttonIn(s[0], card, "Save");
                if (save == null || buttonIn(s[0], card, "Cancel") == null) {
                    return "the renaming card lacks Save/Cancel";
                }
                click(s[0], save);
                return null;
            });
            c.check(why == null, why);
            made.remove(a);
            made.add(b);
            c.check(Files.isDirectory(dir(b)) && !Files.exists(dir(a)), "the folder did not move from " + a + " to " + b);
            c.check(files(dir(b)) == filesBefore, "the renamed folder holds " + files(dir(b)) + " files, not " + filesBefore);
            c.ticks(2);
            boolean cards = c.onClient(mc -> {
                Mod.call(s[0], "refresh");
                return panel(s[0], b) != null && panel(s[0], a) == null;
            });
            c.check(cards, "the page does not show a card for " + b + " and none for " + a);
            c.note("Rename: a real click, typing and Save moved " + a + " to " + b + " with all " + filesBefore + " files");

            // Refusals and the active marker.
            Object taken = Mod.staticCall(PM, "renameProfile", b, other);
            c.check(!(Boolean) Mod.call(taken, "success"), "renaming onto an existing profile was allowed");
            Object empty = Mod.staticCall(PM, "renameProfile", b, "   ");
            c.check(!(Boolean) Mod.call(empty, "success"), "renaming to a blank name was allowed");
            c.check(Files.isDirectory(dir(b)), "a refused rename lost the folder");
            c.onClient(mc -> Mod.staticCall(PM, "applyProfile", b));
            c.check(b.equals(marker()), "control: loading " + b + " did not make it active (marker '" + marker() + "')");
            Object moved = Mod.staticCall(PM, "renameProfile", b, "tk-1553-c");
            made.remove(b);
            made.add("tk-1553-c");
            c.check((Boolean) Mod.call(moved, "success"), "renaming the active profile failed: " + Mod.call(moved, "message"));
            c.check("tk-1553-c".equals(marker()), "the active marker reads '" + marker() + "' after renaming the active profile");
            c.check("tk-1553-c".equals(Mod.staticCall(PM, "getActiveProfile")), "getActiveProfile did not follow the rename");
            c.note("a taken and a blank name are refused; renaming the active profile moved the marker");

            // No swap text on screen: a real click on Load.
            why = c.onClient(mc -> {
                Mod.staticCall("notify.ModOverlayMessage", "show", "", 0L);
                showProfiles(s[0]);
                Mod.call(s[0], "refresh");
                AbstractWidget card = panel(s[0], other);
                AbstractWidget load = card == null ? null : buttonIn(s[0], card, "Load");
                if (load == null) {
                    return "no Load on the " + other + " card";
                }
                click(s[0], load);
                return null;
            });
            c.check(why == null, why);
            c.check(other.equals(marker()), "Load did not make " + other + " active (marker '" + marker() + "')");
            Object shown = Mod.staticCall("notify.ModOverlayMessage", "current");
            c.check(shown == null, "Load put '" + shown + "' on screen");
            c.note("Load made " + other + " active and left the on-screen message empty");
            c.ticks(2);
            MenuACases.shot(c, s[0], "profiles-renamed", null);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                for (String p : made) {
                    Mod.staticCall(PM, "deleteProfile", p);
                }
                try {
                    if (activeBefore == null) {
                        Files.deleteIfExists(markerPath);
                    } else {
                        Files.writeString(markerPath, activeBefore);
                    }
                } catch (Exception ignored) {
                    // nothing later reads it
                }
                if (Quiet.enabled()) {
                    Quiet.apply();
                }
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                return null;
            });
        }
    }
}
