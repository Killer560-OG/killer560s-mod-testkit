package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1105-ui-invsearch-click (invsearchclick-1105). killer560 2026-11-05: "i still cannot click into the serach bar and type
 * in my inventory either". His config: Inventory Search on (lore, ignore case, box scale 1.0) and the Item Browser on
 * (10 columns, horizontal, right, only in inventory). On the player inventory and on a chest this moves the real cursor
 * to the bar, presses the real left button, then types letters through the real key path (KeyboardHandler.keyPress, as
 * a keyboard does) and the char path, with the cursor over an item, and asserts: the bar took focus, it holds exactly
 * what was typed, E did not close the screen, Q did not drop the hovered item and 1 did not swap it into the hotbar.
 * When the click is not taken it names the screen listener that ate it.
 * <p>
 * The cause was 26.3's SDL3 input: typed characters arrive only while text input is started, which vanilla does for a
 * focused EditBox only, so the bar lit up and nothing typed reached it. typeChars injects characters past SDL, so the
 * case also asserts the text input state, and the same for the mod's other non-EditBox typing targets (Auction House and
 * Bazaar search, the type-anywhere Best Friends and Account Switcher screens).
 */
final class InvSearchClick1105Cases {

    private static final String ISC = "inventorysearch.InventorySearchConfig";
    private static final String ISF = "inventorysearch.InventorySearchFeature";
    private static final String IBC = "itembrowser.ItemBrowserConfig";
    private static final String TYPED = "eq1f";

    private InvSearchClick1105Cases() {
    }

    static void click(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, ISC);
        Map<Path, byte[]> savedIb = DungeonB2Cases.snapshotConfig(c, IBC);
        int[] window = c.onClient(mc -> new int[]{mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(),
                mc.options.guiScale().get()});
        try {
            InvBatchCases.survival(c); // creative turns InventoryScreen into the creative inventory
            c.onClient(mc -> {
                Object is = Mod.cfg(ISC);
                Mod.call(is, "setEnabled", true);
                Mod.call(is, "setSearchLore", true);
                Mod.call(is, "setIgnoreCase", true);
                Mod.call(is, "setBoxScale", 1.0f);
                Object ib = Mod.cfg(IBC);
                Mod.call(ib, "setEnabled", true);
                return null;
            });
            InvBatchCases.serverPlayer(c, sp -> {
                sp.getInventory().setItem(0, ItemStack.EMPTY);
                sp.getInventory().setItem(9, new ItemStack(Items.STICK, 5));
            });
            c.ticks(5);
            // The test window, then his (2560x1440 at GUI 3, and maximized, not fullscreen), then 1080p at GUI 2.
            int[][] sizes = {null, {2560, 1440, 3}, {2560, 1361, 3}, {1920, 1080, 2}};
            for (int[] size : sizes) {
                String tag = "";
                if (size != null) {
                    setWindow(c, size[0], size[1], size[2]);
                    tag = " " + size[0] + "x" + size[1] + "@" + size[2];
                }
                run(c, "inventory" + tag, mc -> new InventoryScreen(mc.player));
                run(c, "chest" + tag, mc -> new ContainerScreen(ChestMenu.threeRows(0, mc.player.getInventory()),
                        mc.player.getInventory(), Component.literal("Chest")));
            }
            otherTargets(c);
        } finally {
            setWindow(c, window[0], window[1], window[2]);
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                StringBuilder sb = (StringBuilder) Mod.field(ISF, "QUERY");
                sb.setLength(0);
                return null;
            });
            InvBatchCases.serverPlayer(c, sp -> sp.getInventory().setItem(9, ItemStack.EMPTY));
            DungeonB2Cases.restoreConfig(c, saved, ISC);
            DungeonB2Cases.restoreConfig(c, savedIb, IBC);
        }
    }

    private static void run(UiCase c, String what, java.util.function.Function<Minecraft, Screen> open) {
        c.onClient(mc -> {
            ((StringBuilder) Mod.field(ISF, "QUERY")).setLength(0);
            McCompat.setScreen(mc, open.apply(mc));
            return null;
        });
        c.ticks(5);
        int[] box = c.onClient(mc -> (int[]) Mod.field(ISF, "lastBoxBounds"));
        c.check(box != null, what + ": the search bar was not drawn");
        if (box == null) {
            return;
        }
        double bx = box[0] + box[2] / 2.0;
        double by = box[1] + box[3] / 2.0;
        cursorGui(c, bx, by);
        c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
        c.ticks(2);
        boolean listening = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
        c.note(what + ": bar at " + box[0] + "," + box[1] + " " + box[2] + "x" + box[3] + " on a "
                + c.onClient(mc -> McCompat.screen(mc).width + "x" + McCompat.screen(mc).height) + " screen, clicked at "
                + bx + "," + by + ": listening " + listening);
        if (!listening) {
            c.note(what + ": " + whoAteClick(c, bx, by));
        }
        c.check(listening, what + ": a left click on the search bar did not focus it");
        // 26.3 (SDL3) sends typed characters only while text input is started; typeChars below injects them past SDL,
        // so this is the check that sees what his keyboard sees.
        Boolean textOn = c.onClient(InvSearchClick1105Cases::textInputOn);
        c.note(what + ": SDL text input after the click: " + (textOn == null ? "n/a (26.2, GLFW)" : textOn));
        c.check(textOn == null || textOn, what + ": the click focused the bar but did not start text input, so real typing never reaches it");

        // Type with the cursor over the stick in inventory slot 9, as a player who moved the mouse would.
        int[] stick = c.onClient(mc -> {
            var s = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) McCompat.screen(mc);
            int left = (Integer) Mod.field(s, "leftPos");
            int top = (Integer) Mod.field(s, "topPos");
            for (var slot : s.getMenu().slots) {
                if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == 9) {
                    return new int[]{left + slot.x + 8, top + slot.y + 8};
                }
            }
            throw new AssertionError("no slot for inventory index 9");
        });
        cursorGui(c, stick[0], stick[1]);
        for (char ch : TYPED.toCharArray()) {
            MenuOpenSuite.realKey(c, dev.testkit.compat.McKeys.nat(Character.toUpperCase(ch)));
            c.ctx().getInput().typeChars(String.valueOf(ch));
            c.ticks(1);
        }
        c.ticks(5);
        String query = c.onClient(mc -> (String) Mod.staticCall(ISF, "getQuery"));
        String screen = c.onClient(mc -> McCompat.screen(mc) == null ? "null" : McCompat.screen(mc).getClass().getSimpleName());
        int[] counts = c.onClient(mc -> new int[]{mc.player.getInventory().getItem(9).getCount(),
                mc.player.getInventory().getItem(0).getCount()});
        c.note(what + ": typed \"" + TYPED + "\": query \"" + query + "\", screen " + screen + ", stick slot 9 x" + counts[0]
                + ", hotbar 0 x" + counts[1]);
        c.ctx().takeScreenshot("1105-ui-invsearch-click-" + what.replace(' ', '-').replace('@', '-'));
        c.check(TYPED.equals(query), what + ": the bar holds \"" + query + "\", typed \"" + TYPED + "\"");
        c.check(!"null".equals(screen), what + ": typing E in the bar closed the screen");
        c.check(counts[0] == 5, what + ": typing Q or 1 in the bar moved or dropped the hovered stack (slot 9 x" + counts[0] + ")");
        c.check(counts[1] == 0, what + ": typing 1 in the bar swapped the hovered stack into the hotbar");
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(3);
        Boolean afterClose = c.onClient(InvSearchClick1105Cases::textInputOn);
        boolean stillListening = c.onClient(mc -> (Boolean) Mod.staticCall(ISF, "isListening"));
        c.check(afterClose == null || !afterClose, what + ": text input stayed on after the screen closed");
        c.check(!stillListening, what + ": the bar still counts as typing after the screen closed (keybinds stay blocked)");
    }

    /** SDL text input state on 26.3; null on 26.2 (GLFW sends characters at all times). */
    private static Boolean textInputOn(Minecraft mc) {
        try {
            Object tim = Minecraft.class.getMethod("textInputManager").invoke(mc);
            // 26.2 has a TextInputManager too, but it only switches the IME: GLFW's char callback is never gated
            tim.getClass().getMethod("onTextInputFocusChange", Object.class, boolean.class);
            Field f = tim.getClass().getDeclaredField("textInputEnabled");
            f.setAccessible(true);
            return f.getBoolean(tim);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The mod's other typing targets that are not a focused EditBox: type-anywhere screens and the Bazaar search. Each
     *  screen's search box gains focus and loses it again (a click away, Escape in the Auction House), and typing must
     *  still reach the search after that (an EditBox losing focus stops SDL text input). */
    private static void otherTargets(UiCase c) {
        // {class, its search EditBox field (null: the AH browser draws its own and keeps the text in "query")}
        String[][] screens = {{"auction.screen.AuctionHouseScreen", null}, {"social.BestFriendsScreen", "searchBox"},
                {"social.FriendsListScreen", "addBox"}, {"accounts.gui.AccountSwitcherScreen", "searchBox"}};
        for (String[] row : screens) {
            String cls = row[0];
            String boxField = row[1];
            c.onClient(mc -> {
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls(cls).getConstructor(Screen.class).newInstance((Screen) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
            c.ticks(5);
            Boolean open = c.onClient(InvSearchClick1105Cases::textInputOn);
            c.check(open == null || open, cls + ": typing anywhere cannot reach it, text input is off while it is open");
            boolean hasBox = boxField != null && c.onClient(mc -> Mod.field(McCompat.screen(mc), boxField) != null);
            if (boxField != null && !hasBox) {
                c.note(cls + ": no search box on this screen here (no accounts in the test launcher file); text input checked only");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.ticks(3);
                continue;
            }
            if (boxField != null) {
                int[] r = c.onClient(mc -> {
                    EditBox b = (EditBox) Mod.field(McCompat.screen(mc), boxField);
                    return new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()};
                });
                cursorModScreen(c, r[0] + r[2] / 2.0, r[1] + r[3] / 2.0);
                c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
                c.ticks(2);
                boolean focused = c.onClient(mc -> ((EditBox) Mod.field(McCompat.screen(mc), boxField)).isFocused());
                c.check(focused, cls + ": a click on its search box did not focus it");
                // Tab moves focus on to the next widget, as clicking a button does: the box loses focus.
                MenuOpenSuite.realKey(c, dev.testkit.compat.McKeys.nat(258));
                c.ticks(2);
                boolean left = !c.onClient(mc -> ((EditBox) Mod.field(McCompat.screen(mc), boxField)).isFocused());
                c.check(left, cls + ": Tab did not move focus off its search box");
            } else {
                c.ctx().getInput().typeChars("a"); // typing focuses the AH search ...
                c.ticks(2);
                MenuOpenSuite.realKey(c, dev.testkit.compat.McKeys.nat(256)); // ... and Escape leaves it
                c.ticks(2);
            }
            String screenNow = c.onClient(mc -> McCompat.screen(mc) == null ? "null" : McCompat.screen(mc).getClass().getName());
            Boolean after = c.onClient(InvSearchClick1105Cases::textInputOn);
            c.ctx().getInput().typeChars("z");
            c.ticks(2);
            String text = c.onClient(mc -> {
                Screen sc = McCompat.screen(mc);
                if (sc == null) {
                    return "(no screen)";
                }
                return boxField == null ? (String) Mod.field(sc, "query") : ((EditBox) Mod.field(sc, boxField)).getValue();
            });
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            c.ticks(3);
            Boolean closed = c.onClient(InvSearchClick1105Cases::textInputOn);
            c.note(cls + ": text input open " + open + ", after the search lost focus " + after + " (screen " + screenNow
                    + "), then typed z: search \"" + text + "\"; closed " + closed);
            c.check(screenNow.endsWith(cls.substring(cls.lastIndexOf('.') + 1)), cls + ": leaving the search closed the screen (" + screenNow + ")");
            c.check(after == null || after, cls + ": after its search box gained and lost focus, text input is off, so typing anywhere stops working");
            c.check(text.contains("z"), cls + ": typing after the search lost focus did not reach it (\"" + text + "\")");
            c.check(closed == null || !closed, cls + ": text input stayed on after it closed");
        }
        Boolean[] bz = c.onClient(mc -> {
            Mod.staticCall("bazaar.BazaarView", "focusSearch", true);
            Boolean on = textInputOn(mc);
            Mod.staticCall("bazaar.BazaarView", "focusSearch", false);
            return new Boolean[]{on, textInputOn(mc)};
        });
        c.note("Bazaar search: text input focused " + bz[0] + ", left " + bz[1]);
        c.check(bz[0] == null || bz[0], "the Bazaar search box took focus without starting text input");
        c.check(bz[1] == null || !bz[1], "the Bazaar search box left text input on after losing focus");
    }

    private static void setWindow(UiCase c, int w, int h, int gui) {
        c.ctx().getInput().resizeWindow(w, h);
        c.ticks(3);
        c.onClient(mc -> {
            mc.options.guiScale().set(gui);
            mc.resizeGui();
            return null;
        });
        c.ticks(4);
    }

    /** A mod screen is laid out at hud/AutoScale's factor for it, so its GUI units are scaled once more. */
    private static void cursorModScreen(UiCase c, double sx, double sy) {
        double f = c.onClient(mc -> ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", McCompat.screen(mc))).doubleValue());
        cursorGui(c, sx * f, sy * f);
    }

    private static void cursorGui(UiCase c, double gx, double gy) {
        double[] f = c.onClient(mc -> new double[]{mc.getWindow().getScreenWidth() / (double) mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getScreenHeight() / (double) mc.getWindow().getGuiScaledHeight()});
        c.ctx().getInput().setCursorPos(gx * f[0], gy * f[1]);
        c.ticks(3);
    }

    /** Calls the open screen's allowMouseClick listeners in order and names the first that refuses the click. */
    private static String whoAteClick(UiCase c, double x, double y) {
        return c.onClient(mc -> {
            Screen s = McCompat.screen(mc);
            Object event = net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(s);
            try {
                Object[] handlers = null;
                for (Class<?> k = event.getClass(); k != null && handlers == null; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (f.getType().isArray()) {
                            f.setAccessible(true);
                            handlers = (Object[]) f.get(event);
                            break;
                        }
                    }
                }
                if (handlers == null) {
                    return "no listener array on " + event.getClass().getName();
                }
                MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                List<String> names = new ArrayList<>();
                for (Object h : handlers) {
                    names.add(h.getClass().getName());
                    boolean ok = ((net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.AllowMouseClick) h)
                            .allowMouseClick(s, ev);
                    if (!ok) {
                        return "click refused by listener " + names.size() + " of " + handlers.length + ": " + h.getClass().getName();
                    }
                }
                return "every allowMouseClick listener passed the click: " + names;
            } catch (ReflectiveOperationException e) {
                return "listener scan failed: " + e;
            }
        });
    }
}
