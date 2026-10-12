package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Report;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 862-865: killer560's 2026-10-12 batch (mod hud-key-2).
 * <ul>
 *   <li>862 the Edit HUD key (real held key) does nothing while he types: chat, a sign, a book, a command block, an anvil
 *       rename with an item in the slot, a focused text box on another mod's screen, the mod menu's focused search, the
 *       mod menu waiting for a key, the inventory search bar (Ctrl+F) over a chest, and the creative search. Each one
 *       that can stop being "typing" is the control: the same screen with the field unfocused (or the anvil empty) opens
 *       the editor, so the gate is the field, not the screen.</li>
 *   <li>863 over a chest the key opens Edit HUD with the chest as its parent and draws the chest behind it (screenshot);
 *       the Croesus Chest Profit panel is listed there, a real drag saves its position, the panel's own draw call then
 *       draws at the saved spot (before: at the spot the feature asked for); Esc returns to the same chest screen.</li>
 *   <li>864 the Croesus split: Dungeon's "Croesus" tab builds Chest Profit / Price Mode / Profit Logger rows and none of
 *       Auto Croesus's; on a cheat jar "Auto Croesus" is its own cheat-only tab after every legit Dungeon tab, its toggle
 *       flips CroesusConfig and persists, and its rows show their tooltips under the new scope; a legit jar has no such
 *       tab.</li>
 *   <li>865 the container overlays are Edit HUD elements: the Croesus panel, the Chocolate Factory info line and the two
 *       Start buttons are registered, follow their settings, and the info line's draw call honours a saved position.</li>
 * </ul>
 */
final class HudKey2Cases {

    private static final String HUD = "hud.HudConfig";
    private static final String EDITOR = "hud.HudEditorScreen";
    private static final int KEY = 326; // GLFW_KEY_KP_6
    private static final String PANEL = "croesus_profit_panel";
    private static final String INFO = "chocolate_info_line";

    private HudKey2Cases() {
    }

    // ---- 862 ------------------------------------------------------------------------------------------------------

    /** One typing context: how to open it, and how to make it stop taking text (null: it always does). */
    private record Ctx(String name, Supplier<Screen> open, Consumer<Screen> typingOn, Consumer<Screen> typingOff) {
    }

    static void typing(UiCase c) {
        Object hud = Mod.cfg(HUD);
        int oldKey = (Integer) Mod.call(hud, "getEditKeyCode");
        List<Ctx> ctxs = new ArrayList<>();
        ctxs.add(new Ctx("chat", () -> new ChatScreen("", false), s -> { }, null));
        ctxs.add(new Ctx("sign edit", () -> {
            // At his feet, in his level, editable by him: the screen closes itself (or NPEs) otherwise.
            var player = Minecraft.getInstance().player;
            SignBlockEntity sign = new SignBlockEntity(player.blockPosition(), Blocks.OAK_SIGN.defaultBlockState());
            sign.setLevel(Minecraft.getInstance().level);
            sign.setAllowedPlayerEditor(player.getUUID());
            return new SignEditScreen(sign, true, false);
        }, s -> { }, null));
        ctxs.add(new Ctx("book edit", () -> new BookEditScreen(Minecraft.getInstance().player,
                new ItemStack(Items.WRITABLE_BOOK), InteractionHand.MAIN_HAND, WritableBookContent.EMPTY), s -> { }, null));
        ctxs.add(new Ctx("command block", () -> new CommandBlockEditScreen(
                new CommandBlockEntity(BlockPos.ZERO, Blocks.COMMAND_BLOCK.defaultBlockState())), s -> { },
                s -> unfocusAll(s)));
        ctxs.add(new Ctx("anvil rename", () -> {
            var inv = Minecraft.getInstance().player.getInventory();
            return new AnvilScreen(new AnvilMenu(0, inv), inv, Component.literal("Repair & Name"));
        }, s -> {
            // The rename box takes text once a piece is in the slot (AnvilScreen.slotChanged makes it editable).
            ((AnvilScreen) s).slotChanged(((AnvilScreen) s).getMenu(), 0, new ItemStack(Items.DIAMOND_SWORD));
            focusFirstBox(s);
        }, s -> ((AnvilScreen) s).slotChanged(((AnvilScreen) s).getMenu(), 0, ItemStack.EMPTY)));
        ctxs.add(new Ctx("another mod's text box", HudKey2Cases::boxScreen, HudKey2Cases::focusFirstBox,
                HudKey2Cases::unfocusAll));
        ctxs.add(new Ctx("the mod menu's search", HudKey2Cases::modScreen, s -> {
            EditBox f = (EditBox) R.get(s, "searchField");
            s.setFocused(f);
            f.setFocused(true);
        }, HudKey2Cases::unfocusAll));
        ctxs.add(new Ctx("the mod menu waiting for a key", HudKey2Cases::modScreen,
                s -> setHomeListening(true), s -> setHomeListening(false)));
        ctxs.add(new Ctx("inventory search bar over a chest", HudKey2Cases::chestScreen,
                s -> Mod.staticCall("inventorysearch.InventorySearchFeature", "setListening", true),
                s -> Mod.staticCall("inventorysearch.InventorySearchFeature", "setListening", false)));
        ctxs.add(new Ctx("creative search", () -> new InventoryScreen(Minecraft.getInstance().player), s -> {
            EditBox box = (EditBox) R.get(s, "searchBox");
            box.setVisible(true);
            box.setEditable(true);
            box.setFocused(true);
        }, s -> ((EditBox) R.get(s, "searchBox")).setFocused(false)));
        int blocked = 0;
        int opened = 0;
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setEditKeyCode", KEY);
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(2);
            String control = press(c);
            c.check(control.contains("Edit HUD"), "with no screen the key opened " + control + " - nothing below would mean anything");
            for (Ctx k : ctxs) {
                Screen s = open(c, k.open());
                if (k.name().equals("creative search") && !s.getClass().getSimpleName().startsWith("CreativeMode")) {
                    c.note("creative search: the world is not creative (" + s.getClass().getSimpleName() + "), skipped");
                    continue;
                }
                c.onClient(mc -> {
                    k.typingOn().accept(s);
                    return null;
                });
                // A person starts typing (clicks the box, the keybind button) at least a tick before pressing a key.
                c.ticks(2);
                boolean detected = c.onClient(mc -> (Boolean) Mod.staticCall("util.TypingDetector", "isTyping", s));
                String after = press(c);
                boolean same = c.onClient(mc -> McCompat.screen(mc) == s);
                c.note(k.name() + " (" + s.getClass().getSimpleName() + "), typing: detector " + detected
                        + ", after the key " + after);
                if (!same || !detected) {
                    c.problem("while typing in " + k.name() + " the key opened " + after + " (detector " + detected + ")");
                } else {
                    blocked++;
                }
                if (k.typingOff() != null) {
                    c.onClient(mc -> {
                        k.typingOff().accept(s);
                        return null;
                    });
                    boolean still = c.onClient(mc -> (Boolean) Mod.staticCall("util.TypingDetector", "isTyping", s));
                    // The key poll also skips a press made on the first tick after typing ends (the press that closes a
                    // keybind capture lands before the poll), so let one poll see "not typing" first.
                    c.ticks(2);
                    String ctl = press(c);
                    Object parent = c.onClient(mc -> {
                        Screen now = McCompat.screen(mc);
                        return now != null && now.getClass().getName().equals(Mod.ROOT + EDITOR)
                                ? Mod.call(now, "parentScreen") : null;
                    });
                    c.note(k.name() + ", not typing: detector " + still + ", after the key " + ctl);
                    if (still || parent != s) {
                        c.problem("in " + k.name() + " with nothing taking text the key opened " + ctl
                                + " (detector " + still + ") - the control failed");
                    } else {
                        opened++;
                    }
                }
                close(c);
            }
        } finally {
            c.ctx().getInput().releaseKey(KEY);
            c.onClient(mc -> {
                Mod.staticCall("inventorysearch.InventorySearchFeature", "setListening", false);
                setHomeListening(false);
                Mod.call(hud, "setEditKeyCode", oldKey);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
        c.note(blocked + " typing context(s) blocked the key, " + opened + " control(s) opened the editor once typing stopped");
        c.check(blocked >= 9 && opened >= 6, "only " + blocked + " blocked / " + opened + " controls");
    }

    private static void setHomeListening(boolean on) {
        Object home = null;
        List<Object> stack = new ArrayList<>((List<?>) R.getStatic(R.cls(ModScreenDriver.MOD_SCREEN), "tabs"));
        while (!stack.isEmpty()) {
            Object t = stack.remove(stack.size() - 1);
            if (t.getClass().getSimpleName().equals("HomeMainTab")) {
                home = t;
            }
            if (R.cls(ModScreenDriver.FOLDER_TAB).isInstance(t)) {
                stack.addAll((List<?>) R.get(t, "subTabs"));
            }
        }
        if (home != null) {
            R.set(home, "listening", on);
        }
    }

    // ---- 863 ------------------------------------------------------------------------------------------------------

    static void overContainer(UiCase c) throws Exception {
        Object hud = Mod.cfg(HUD);
        int oldKey = (Integer) Mod.call(hud, "getEditKeyCode");
        boolean oldShowAll = (Boolean) Mod.call(hud, "isEditorShowAll");
        boolean oldSnap = (Boolean) Mod.call(hud, "isEditorSnap");
        Object croesus = Mod.cfg("croesus.CroesusConfig");
        boolean oldProfit = (Boolean) Mod.call(croesus, "isChestProfitEnabled");
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setEditKeyCode", KEY);
                Mod.call(hud, "setEditorShowAll", true);
                Mod.call(hud, "setEditorSnap", false);
                Mod.call(croesus, "setChestProfitEnabled", true);
                removePosition(PANEL);
                return null;
            });
            Screen chest = open(c, HudKey2Cases::chestScreen);
            // The panel draws over its menu (its own draw call, as ChestProfitFeature makes it each frame): since mod
            // hud-key-3 a container overlay is listed only when it drew in the last 10 s (866).
            c.onClient(mc -> drawAt(mc, PANEL, 40, 50, 60, 30));
            String after = press(c);
            Screen ed = c.onClient(McCompat::screen);
            Object parent = ed != null && ed.getClass().getName().equals(Mod.ROOT + EDITOR) ? Mod.call(ed, "parentScreen") : null;
            c.note("over a chest the key opened " + after + "; its parent is the chest: " + (parent == chest));
            c.check(parent == chest, "over a chest the key opened " + after + " with parent " + parent);
            Path shot = c.ctx().takeScreenshot(Report.fileName(c.name() + "-editor-over-chest"));
            Report.screenshot(c.name(), shot);
            Map<String, Object> r = c.onClient(mc -> {
                Map<String, Object> out = new LinkedHashMap<>();
                @SuppressWarnings("unchecked")
                List<Object> shown = (List<Object>) R.get(ed, "shown");
                List<String> ids = new ArrayList<>();
                for (Object e : shown) {
                    ids.add((String) Mod.call(e, "id"));
                }
                out.put("listed", ids);
                // The chest is drawn behind: the editor's frame holds the chest's 63 slots' items/labels at least as text
                // or elements beyond what the editor alone draws.
                out.put("withChest", titleDrawn(mc, ed));
                Object saveParent = R.get(ed, "parent");
                R.set(ed, "parent", null);
                out.put("withoutChest", titleDrawn(mc, ed));
                R.set(ed, "parent", saveParent);
                return out;
            });
            @SuppressWarnings("unchecked")
            List<String> listed = (List<String>) r.get("listed");
            c.note("Edit HUD over the chest lists " + listed + "; the chest's title is drawn in its frame: "
                    + r.get("withChest") + " (and with no parent: " + r.get("withoutChest") + ")");
            c.check(listed.contains(PANEL), "the Croesus Chest Profit panel is not listed in Edit HUD");
            c.check(Boolean.TRUE.equals(r.get("withChest")) && Boolean.FALSE.equals(r.get("withoutChest")),
                    "the chest is not drawn behind the editor");

            // Before any drag: the panel's draw call puts it where the feature asked (its auto spot).
            int[] auto = drawPanelAt(c, 40, 50);
            c.note("unsaved: the panel's draw at auto 40,50 filled from " + auto[0] + "," + auto[1]);
            c.check(auto[0] == 40 && auto[1] == 50, "an unplaced panel did not draw at the feature's own spot: " + auto[0]
                    + "," + auto[1]);
            // Re-open the editor (the draw above set the panel's auto spot), then a real drag.
            String drag = c.onClient(mc -> {
                try {
                    R.call0(ed, "rebuildList");
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
                int[] p = livePos(ed, PANEL);
                float scale = ((Number) Mod.staticCall("hud.HudElementRegistry", "resolveScale",
                        Mod.staticCall("hud.HudElementRegistry", "byId", PANEL))).floatValue();
                int w = (Integer) Mod.call(Mod.staticCall("hud.HudElementRegistry", "byId", PANEL), "width");
                int h = (Integer) Mod.call(Mod.staticCall("hud.HudElementRegistry", "byId", PANEL), "height");
                double gx = p[0] + w * scale / 2.0;
                double gy = p[1] + h * scale / 2.0;
                var info = new MouseButtonInfo(0, 0);
                ed.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
                Object grabbed = R.get(ed, "draggingId");
                ed.mouseDragged(new MouseButtonEvent(gx + 23, gy + 17, info), 23, 17);
                ed.mouseReleased(new MouseButtonEvent(gx + 23, gy + 17, info));
                int[] want = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", p[0] + 23, p[1] + 17);
                int[] saved = (int[]) Mod.call(Mod.cfg(HUD), "getPosition", PANEL, -999, -999);
                return grabbed + " from " + p[0] + "," + p[1] + " saved " + saved[0] + "," + saved[1] + " want " + want[0]
                        + "," + want[1] + ((PANEL.equals(grabbed) && saved[0] == want[0] && saved[1] == want[1]) ? " OK" : " WRONG");
            });
            c.note("drag: " + drag);
            c.check(drag.endsWith(" OK"), "dragging the panel in Edit HUD did not save it: " + drag);
            int[] resolved = c.onClient(mc -> (int[]) Mod.staticCall("hud.HudElementRegistry", "resolvePosition",
                    Mod.staticCall("hud.HudElementRegistry", "byId", PANEL)));
            int[] placed = drawPanelAt(c, 40, 50);
            c.note("saved: the panel's draw at auto 40,50 filled from " + placed[0] + "," + placed[1] + " (resolved "
                    + resolved[0] + "," + resolved[1] + ")");
            c.check(placed[0] == resolved[0] && placed[1] == resolved[1] && (placed[0] != 40 || placed[1] != 50),
                    "a placed panel did not draw at its saved spot");
            // Esc goes back to the very same chest screen.
            c.onClient(mc -> {
                McCompat.screen(mc).onClose();
                return null;
            });
            c.ticks(2);
            boolean back = c.onClient(mc -> McCompat.screen(mc) == chest);
            c.note("Esc from the editor returned to the chest: " + back);
            c.check(back, "closing the editor did not return to the chest");
        } finally {
            c.ctx().getInput().releaseKey(KEY);
            c.onClient(mc -> {
                removePosition(PANEL);
                Mod.call(hud, "setEditKeyCode", oldKey);
                Mod.call(hud, "setEditorShowAll", oldShowAll);
                Mod.call(hud, "setEditorSnap", oldSnap);
                Mod.call(hud, "save");
                Mod.call(croesus, "setChestProfitEnabled", oldProfit);
                Mod.call(croesus, "save");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** Whether one frame of {@code screen} draws the test chest's title (its label, drawn by the chest's contents). */
    private static boolean titleDrawn(Minecraft mc, Screen screen) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        screen.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0.5f);
        boolean[] seen = {false};
        state.forEachText(t -> seen[0] |= dev.testkit.gametest.TextRuns.string(t).contains("Testkit Chest"));
        return seen[0];
    }

    /** Calls the panel element's own draw (what ChestProfitFeature calls each frame) with a probe that fills a 10x10 at
     *  its origin, and returns where that fill landed. */
    private static int[] drawPanelAt(UiCase c, int autoX, int autoY) {
        return c.onClient(mc -> drawAt(mc, PANEL, autoX, autoY, 60, 30));
    }

    private static int[] drawAt(Minecraft mc, String id, int autoX, int autoY, int w, int h) {
        Object el = Mod.staticCall("hud.HudElementRegistry", "byId", id);
        Class<?> drawer = Mod.cls("hud.ContainerOverlayElement$Drawer");
        Object probe = Proxy.newProxyInstance(drawer.getClassLoader(), new Class<?>[]{drawer}, (self, m, args) -> {
            if (m.getName().equals("draw")) {
                GuiGraphicsExtractor g = (GuiGraphicsExtractor) args[0];
                int x = (Integer) args[1];
                int y = (Integer) args[2];
                g.fill(x, y, x + 10, y + 10, 0xFFFF00FF);
            }
            return null;
        });
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        Mod.call(el, "draw", g, autoX, autoY, w, h, probe);
        int[] at = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        state.forEachElement(e -> {
            if (e.bounds() != null && at[0] == Integer.MIN_VALUE) {
                at[0] = e.bounds().left();
                at[1] = e.bounds().top();
            }
        }, GuiRenderState.TraverseRange.ALL);
        return at;
    }

    // ---- 864 ------------------------------------------------------------------------------------------------------

    static void croesusSplit(UiCase c) throws Exception {
        boolean cheat = Mod.isCheat();
        ModScreenDriver d = c.onClient(mc -> {
            try {
                ModScreenDriver dr = new ModScreenDriver(mc);
                McCompat.setScreen(mc, dr.screen);
                return dr;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        c.ticks(2);
        Map<String, Object> r = c.onClient(mc -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Object dungeon = null;
            int top = -1;
            for (int i = 0; i < d.topTabs().size(); i++) {
                if ("Dungeon".equals(R.get(d.topTabs().get(i), "name"))) {
                    dungeon = d.topTabs().get(i);
                    top = i;
                }
            }
            List<Object> subs = d.subTabs(dungeon);
            int croesus = -1;
            int auto = -1;
            int lastLegit = -1;
            int firstCheat = Integer.MAX_VALUE;
            for (int i = 0; i < subs.size(); i++) {
                String cls = subs.get(i).getClass().getSimpleName();
                boolean cheatOnly = (Boolean) Mod.call(subs.get(i), "isCheatOnly");
                if (cheatOnly) {
                    firstCheat = Math.min(firstCheat, i);
                } else {
                    lastLegit = i;
                }
                if (cls.equals("CroesusTab")) {
                    croesus = i;
                    out.put("croesusName", R.get(subs.get(i), "name"));
                }
                if (cls.equals("AutoCroesusTab")) {
                    auto = i;
                    out.put("autoName", R.get(subs.get(i), "name"));
                    out.put("autoCheat", cheatOnly);
                }
            }
            out.put("croesus", croesus);
            out.put("auto", auto);
            out.put("lastLegit", lastLegit);
            out.put("firstCheat", firstCheat);
            out.put("croesusRows", rows(d, top, dungeon, croesus));
            if (auto >= 0) {
                out.put("autoRows", rows(d, top, dungeon, auto));
            }
            return out;
        });
        @SuppressWarnings("unchecked")
        List<String> croesusRows = (List<String>) r.get("croesusRows");
        c.note("Dungeon: Croesus tab \"" + r.get("croesusName") + "\" at " + r.get("croesus") + " rows " + croesusRows);
        c.check((Integer) r.get("croesus") >= 0 && "Croesus".equals(r.get("croesusName")), "no Dungeon > Croesus tab");
        for (String want : new String[]{"Chest Profit", "Price Mode", "Croesus Profit Logger"}) {
            c.check(croesusRows.stream().anyMatch(l -> l.startsWith(want)), "Croesus has no '" + want + "' row");
        }
        for (String gone : new String[]{"Auto Croesus", "Min Profit", "Min Delay", "Use Chest Keys", "Use Kismets"}) {
            c.check(croesusRows.stream().noneMatch(l -> l.startsWith(gone)), "Croesus still builds '" + gone + "'");
        }
        if (!cheat) {
            c.check((Integer) r.get("auto") < 0, "a legit jar has an Auto Croesus tab");
            close(c);
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> autoRows = (List<String>) r.get("autoRows");
        c.note("Auto Croesus tab \"" + r.get("autoName") + "\" at " + r.get("auto") + " (cheat-only " + r.get("autoCheat")
                + "; last legit tab " + r.get("lastLegit") + ", first cheat tab " + r.get("firstCheat") + ") rows " + autoRows);
        c.check((Integer) r.get("auto") > (Integer) r.get("lastLegit") && Boolean.TRUE.equals(r.get("autoCheat")),
                "Auto Croesus is not a cheat-only tab below every legit Dungeon tab");
        c.check(autoRows.stream().anyMatch(l -> l.startsWith("Auto Croesus")), "Auto Croesus has no toggle");
        // The toggle flips the same CroesusConfig switch and it persists; then the auto rows and their tooltips.
        Object cfg = Mod.cfg("croesus.CroesusConfig");
        boolean was = (Boolean) Mod.call(cfg, "getAutoCroesusEnabledRaw");
        try {
            boolean took = c.onClient(mc -> {
                for (AbstractWidget w : d.content()) {
                    if (d.isSettingsButton(w) && ModScreenDriver.label(w).startsWith("Auto Croesus:")) {
                        return ModScreenDriver.press(w);
                    }
                }
                return false;
            });
            c.check(took, "could not press Auto Croesus's toggle");
            boolean now = (Boolean) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getAutoCroesusEnabledRaw");
            boolean disk = c.onClient(mc -> {
                Mod.staticCall("croesus.CroesusConfig", "load");
                return (Boolean) Mod.call(Mod.cfg("croesus.CroesusConfig"), "getAutoCroesusEnabledRaw");
            });
            c.note("Auto Croesus toggle: " + was + " -> " + now + " (on disk " + disk + ")");
            c.check(now != was && disk == now, "the Auto Croesus toggle did not flip and persist CroesusConfig");
            c.onClient(mc -> {
                Mod.call(Mod.cfg("croesus.CroesusConfig"), "setAutoCroesusEnabled", true);
                rebuild(d);
                return null;
            });
            Map<String, String> tips = c.onClient(mc -> {
                Map<String, String> out = new LinkedHashMap<>();
                for (AbstractWidget w : d.content()) {
                    String label = ModScreenDriver.label(w);
                    for (String k : new String[]{"Min Delay", "Max Delay", "Use Chest Keys", "Use Kismets"}) {
                        if (label.startsWith(k)) {
                            out.put(k, (String) Mod.staticCall("gui.SettingTooltips", "describe", "Dungeon", w, label));
                        }
                    }
                }
                return out;
            });
            c.note("Auto Croesus tooltips: " + tips);
            for (String k : new String[]{"Min Delay", "Max Delay", "Use Chest Keys", "Use Kismets"}) {
                c.check(tips.get(k) != null && !tips.get(k).isBlank(), "no tooltip for Auto Croesus's " + k);
            }
            c.check(tips.get("Min Delay").contains("Auto Croesus"), "Min Delay shows another tab's tooltip: " + tips.get("Min Delay"));
            // killer560 2026-10-13: Auto Croesus has Price Mode too, linked to the Croesus tab's (one CroesusConfig field).
            Object before = Mod.call(Mod.cfg("croesus.CroesusConfig"), "getPriceMode");
            String[] pm = c.onClient(mc -> {
                for (AbstractWidget w : d.content()) {
                    String label = ModScreenDriver.label(w);
                    if (d.isSettingsButton(w) && label.startsWith("Price Mode")) {
                        String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Dungeon", w, label);
                        ModScreenDriver.press(w);
                        return new String[]{label, ModScreenDriver.label(w), tip};
                    }
                }
                return null;
            });
            c.check(pm != null, "Auto Croesus has no Price Mode button");
            if (pm != null) {
                Object after = Mod.call(Mod.cfg("croesus.CroesusConfig"), "getPriceMode");
                c.note("Auto Croesus Price Mode: '" + pm[0] + "' -> '" + pm[1] + "', config " + before + " -> " + after
                        + "; tooltip: " + pm[2]);
                c.check(!before.equals(after), "Auto Croesus's Price Mode did not change the shared CroesusConfig price mode");
                c.check(pm[2] != null && pm[2].contains("same setting"), "Auto Croesus Price Mode tooltip does not say it is linked");
                Mod.call(Mod.cfg("croesus.CroesusConfig"), "setPriceMode", before);
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg("croesus.CroesusConfig"), "setAutoCroesusEnabled", was);
                Mod.call(Mod.cfg("croesus.CroesusConfig"), "save");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** The labels sub-tab {@code index} of {@code folder} builds, with only it open. */
    private static List<String> rows(ModScreenDriver d, int top, Object folder, int index) {
        d.select(top);
        d.expanded(folder).add(index);
        rebuild(d);
        List<String> out = new ArrayList<>();
        Object tab = d.subTabs(folder).get(index);
        String scope = (String) R.get(tab, "name");
        for (AbstractWidget w : d.content()) {
            Object s = Mod.staticCall("gui.SettingTooltips", "scopeOf", w);
            if (scope.equals(s)) {
                out.add(ModScreenDriver.label(w));
            }
        }
        return out;
    }

    // ---- 866 ------------------------------------------------------------------------------------------------------

    /**
     * 866 (mod hud-key-3, killer560 2026-10-12 "Only if it falls in that last 10s"): with no container open, Edit HUD lists
     * a container-only overlay (the Croesus panel, Start Croesus on a cheat jar) only if it drew in the last 10 s - Show
     * Unseen ON does not bring it back - and over its menu, where it has just drawn, it is always listed. The draw stamps
     * are set on HudSeen's own clock (the HUD marked live now); normal elements are the control for Show Unseen.
     */
    static void containerGrace(UiCase c) {
        Object hud = Mod.cfg(HUD);
        Object croesus = Mod.cfg("croesus.CroesusConfig");
        boolean oldShowAll = (Boolean) Mod.call(hud, "isEditorShowAll");
        boolean oldProfit = (Boolean) Mod.call(croesus, "isChestProfitEnabled");
        boolean oldAuto = (Boolean) Mod.call(croesus, "getAutoCroesusEnabledRaw");
        int oldKey = (Integer) Mod.call(hud, "getEditKeyCode");
        List<String> ids = new ArrayList<>(List.of(PANEL));
        if (Mod.isCheat()) {
            ids.add("croesus_start_button");
        }
        try {
            c.onClient(mc -> {
                Mod.call(hud, "setEditorShowAll", true);
                Mod.call(hud, "setEditKeyCode", KEY);
                Mod.call(croesus, "setChestProfitEnabled", true);
                Mod.call(croesus, "setAutoCroesusEnabled", true);
                McCompat.setScreen(mc, null);
                return null;
            });
            for (String id : ids) {
                boolean container = c.onClient(mc -> (Boolean) Mod.staticCall("hud.HudElementRegistry", "isContainerOnly", id));
                c.check(container, id + " is not marked container-only");
            }
            List<String> at5 = listedAfterStamp(c, ids, 5_000);
            List<String> at15 = listedAfterStamp(c, ids, 15_000);
            c.note("Show Unseen ON, no container: drawn 5 s ago -> listed " + at5 + "; 15 s ago -> listed " + at15);
            c.check(at15.stream().anyMatch(i -> !ids.contains(i)),
                    "Show Unseen lists nothing else, so its being ignored for the overlays proves nothing");
            for (String id : ids) {
                c.check(at5.contains(id), id + " drawn 5 s ago is not listed");
                c.check(!at15.contains(id), id + " drawn 15 s ago is still listed (Show Unseen must not bring it back)");
            }
            // Over its menu: an old stamp, then the menu draws it (the feature's own draw call), the key opens Edit HUD there.
            stamp(c, ids, 60_000);
            Screen chest = open(c, HudKey2Cases::chestScreen);
            c.onClient(mc -> {
                drawAt(mc, PANEL, 40, 50, 60, 30);
                if (ids.contains("croesus_start_button")) {
                    Mod.staticCall("hud.HudSeen", "markDrawn", "croesus_start_button");
                }
                return null;
            });
            String after = press(c);
            List<String> over = c.onClient(mc -> listed(McCompat.screen(mc)));
            Object parent = c.onClient(mc -> Mod.call(McCompat.screen(mc), "parentScreen"));
            c.note("over the menu (after a stamp 60 s old, then its own draw): key opened " + after + ", lists " + over);
            c.check(parent == chest, "the key did not open Edit HUD over the menu");
            for (String id : ids) {
                c.check(over.contains(id), id + " is not listed over its own menu");
            }
        } finally {
            c.ctx().getInput().releaseKey(KEY);
            c.onClient(mc -> {
                Mod.call(hud, "setEditorShowAll", oldShowAll);
                Mod.call(hud, "setEditKeyCode", oldKey);
                Mod.call(hud, "save");
                Mod.call(croesus, "setChestProfitEnabled", oldProfit);
                Mod.call(croesus, "setAutoCroesusEnabled", oldAuto);
                Mod.call(croesus, "save");
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /** Sets each id's last draw to {@code agoMs} before now and marks the HUD live now (HudSeen's clock). */
    @SuppressWarnings("unchecked")
    private static void stamp(UiCase c, List<String> ids, long agoMs) {
        c.onClient(mc -> {
            Map<String, long[]> drawn = (Map<String, long[]>) R.getStatic(R.cls("hud.HudSeen"), "LAST_DRAWN");
            long now = System.currentTimeMillis();
            for (String id : ids) {
                drawn.put(id, new long[]{now - agoMs});
            }
            Mod.staticCall("hud.HudSeen", "markHudFrame");
            return null;
        });
    }

    private static List<String> listedAfterStamp(UiCase c, List<String> ids, long agoMs) {
        stamp(c, ids, agoMs);
        return c.onClient(mc -> listed(editor(mc)));
    }

    /** A fresh Edit HUD (no parent), laid out, as the key or the menu button would open it. */
    private static Screen editor(Minecraft mc) {
        try {
            Screen ed = (Screen) R.cls(EDITOR).getConstructor(Screen.class).newInstance((Object) null);
            ed.init(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            return ed;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listed(Screen ed) {
        List<String> out = new ArrayList<>();
        for (Object e : (List<Object>) R.get(ed, "shown")) {
            out.add((String) Mod.call(e, "id"));
        }
        return out;
    }

    // ---- 865 ------------------------------------------------------------------------------------------------------

    static void overlays(UiCase c) {
        Object croesus = Mod.cfg("croesus.CroesusConfig");
        Object choc = Mod.cfg("chocolatefactory.ChocolateFactoryConfig");
        boolean oldProfit = (Boolean) Mod.call(croesus, "isChestProfitEnabled");
        boolean oldChoc = (Boolean) Mod.call(choc, "isEnabled");
        boolean oldInfo = (Boolean) Mod.call(choc, "isInfoLine");
        try {
            Map<String, Object> r = c.onClient(mc -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (String id : new String[]{PANEL, INFO, "croesus_start_button", "experiments_start_button"}) {
                    out.put(id, Mod.staticCall("hud.HudElementRegistry", "byId", id) != null);
                }
                Mod.call(croesus, "setChestProfitEnabled", false);
                Mod.call(choc, "setEnabled", false);
                Object p = Mod.staticCall("hud.HudElementRegistry", "byId", PANEL);
                Object i = Mod.staticCall("hud.HudElementRegistry", "byId", INFO);
                out.put("offPanel", Mod.call(p, "isEnabledInSettings"));
                out.put("offInfo", Mod.call(i, "isEnabledInSettings"));
                Mod.call(croesus, "setChestProfitEnabled", true);
                Mod.call(choc, "setEnabled", true);
                Mod.call(choc, "setInfoLine", true);
                out.put("onPanel", Mod.call(p, "isEnabledInSettings"));
                out.put("onInfo", Mod.call(i, "isEnabledInSettings"));
                Object hud = Mod.cfg(HUD);
                removePosition(INFO);
                out.put("autoAt", drawAt(mc, INFO, 33, 44, 120, 9));
                int[] saved = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", 150, 70);
                Mod.call(hud, "setPosition", INFO, saved[0], saved[1]);
                out.put("savedAt", drawAt(mc, INFO, 33, 44, 120, 9));
                out.put("resolved", Mod.staticCall("hud.HudElementRegistry", "resolvePosition", i));
                removePosition(INFO);
                return out;
            });
            c.note("registered: panel " + r.get(PANEL) + ", info line " + r.get(INFO) + ", Start Croesus "
                    + r.get("croesus_start_button") + ", Start ETable " + r.get("experiments_start_button"));
            // Start Croesus exists only with Auto Croesus, i.e. on a cheat jar.
            for (String id : Mod.isCheat() ? new String[]{PANEL, INFO, "croesus_start_button"} : new String[]{PANEL, INFO}) {
                c.check(Boolean.TRUE.equals(r.get(id)), id + " is not a registered HUD element");
            }
            if (!Mod.isCheat()) {
                c.check(!Boolean.TRUE.equals(r.get("croesus_start_button")), "a legit jar registers Start Croesus");
            }
            c.note("enabled with their settings off: panel " + r.get("offPanel") + ", info " + r.get("offInfo")
                    + "; on: panel " + r.get("onPanel") + ", info " + r.get("onInfo"));
            c.check(Boolean.FALSE.equals(r.get("offPanel")) && Boolean.FALSE.equals(r.get("offInfo"))
                    && Boolean.TRUE.equals(r.get("onPanel")) && Boolean.TRUE.equals(r.get("onInfo")),
                    "the overlays' Edit HUD entries do not follow their settings");
            int[] autoAt = (int[]) r.get("autoAt");
            int[] savedAt = (int[]) r.get("savedAt");
            int[] resolved = (int[]) r.get("resolved");
            c.note("info line: unplaced draws at " + autoAt[0] + "," + autoAt[1] + " (asked 33,44); placed draws at "
                    + savedAt[0] + "," + savedAt[1] + " (resolved " + resolved[0] + "," + resolved[1] + ")");
            c.check(autoAt[0] == 33 && autoAt[1] == 44, "the unplaced info line is not where the feature put it");
            c.check(savedAt[0] == resolved[0] && savedAt[1] == resolved[1] && savedAt[0] != 33,
                    "the placed info line does not draw at its saved spot");
        } finally {
            c.onClient(mc -> {
                Mod.call(croesus, "setChestProfitEnabled", oldProfit);
                Mod.call(croesus, "save");
                Mod.call(choc, "setEnabled", oldChoc);
                Mod.call(choc, "setInfoLine", oldInfo);
                Mod.call(choc, "save");
                Mod.call(Mod.cfg(HUD), "save");
                return null;
            });
        }
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Forgets a saved HUD position (HudConfig has no remover): the element goes back to its default spot. */
    @SuppressWarnings("unchecked")
    private static void removePosition(String id) {
        ((Map<String, int[]>) R.get(Mod.cfg(HUD), "positions")).remove(id);
    }

    private static Screen chestScreen() {
        var player = Minecraft.getInstance().player;
        return new ContainerScreen(ChestMenu.threeRows(0, player.getInventory()), player.getInventory(),
                Component.literal("Testkit Chest"));
    }

    private static Screen modScreen() {
        try {
            Class<?> cls = R.cls(ModScreenDriver.MOD_SCREEN);
            R.setStatic(cls, "searchQuery", "");
            R.setStatic(cls, "selectedTab", 0);
            return (Screen) cls.getConstructor(Screen.class).newInstance((Object) null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** "Another mod's screen" with a plain vanilla text box. */
    private static Screen boxScreen() {
        return new Screen(Component.literal("Testkit Text Box Screen")) {
            @Override
            protected void init() {
                addRenderableWidget(new EditBox(this.font, this.width / 2 - 60, 40, 120, 18, Component.literal("box")));
            }
        };
    }

    private static void focusFirstBox(Screen s) {
        for (AbstractWidget w : Frames.widgets(s)) {
            if (w instanceof EditBox box) {
                s.setFocused(box);
                box.setFocused(true);
                return;
            }
        }
        throw new AssertionError("no EditBox on " + s.getClass().getSimpleName());
    }

    private static void unfocusAll(Screen s) {
        for (AbstractWidget w : Frames.widgets(s)) {
            if (w instanceof EditBox box) {
                box.setFocused(false);
            }
        }
        s.setFocused(null);
    }

    private static Screen open(UiCase c, Supplier<Screen> make) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, make.get());
            return null;
        });
        c.ticks(3);
        Screen s = c.onClient(McCompat::screen);
        c.check(s != null, "the screen did not open");
        return s;
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(2);
    }

    private static String press(UiCase c) {
        c.ctx().getInput().holdKey(KEY);
        c.ticks(4);
        c.ctx().getInput().releaseKey(KEY);
        c.ticks(3);
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            if (s == null) {
                return "(no screen)";
            }
            if (s.getClass().getName().equals(Mod.ROOT + EDITOR)) {
                return (Boolean) Mod.call(s, "isBarsMode") ? "Edit Bars" : "Edit HUD";
            }
            return s.getClass().getSimpleName();
        });
    }

    @SuppressWarnings("unchecked")
    private static int[] livePos(Screen editor, String id) {
        int[] p = ((Map<String, int[]>) R.get(editor, "livePositions")).get(id);
        if (p == null) {
            throw new AssertionError(id + " is not listed in the editor");
        }
        return p.clone();
    }

    private static void rebuild(ModScreenDriver d) {
        try {
            d.rebuild();
        } catch (Throwable t) {
            throw new AssertionError("ModScreen.rebuild threw: " + UiCase.describe(t), t);
        }
    }
}
