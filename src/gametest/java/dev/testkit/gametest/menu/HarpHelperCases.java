package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import com.mojang.blaze3d.platform.InputConstants;

import dev.testkit.compat.McCompat;
import dev.testkit.compat.McKeys;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Melody's Harp Helper (both builds; harp-extras-1121) against {@code HxHarp}. Keys reach the screen the way a real key does,
 * through {@code KeyboardHandler.keyPress} (where Fabric's ScreenKeyboardEvents run). Every case asserts the feature acted.
 * <ul>
 *   <li>1121-harphelper-keys: the whole song played with the default keys 1-7 only: the fake server judged every note right,
 *       none wrong or missed, one container click per press, and the tracker's hit / seen equal the server's.</li>
 *   <li>1121-harphelper-tracker: one note is left out: the server says 16 right and 1 missed, the tracker says 16 of 17.</li>
 *   <li>1121-harphelper-rebind: column 1 rebound to Q: Q plays it, the old key 1 no longer does.</li>
 *   <li>1121-harphelper-hold: a held key (press, repeats, release) is one click, and the number key's vanilla swap of the
 *       slot under the cursor is swallowed.</li>
 *   <li>1121-harphelper-scale: Custom GUI Scale sets the window's scale in the harp (screen width follows), the saved option
 *       is untouched, and the scale is back after closing; Auto too.</li>
 *   <li>1121-harphelper-tips: Hide Tooltips empties an item's tooltip in the harp only, and not with the switch off.</li>
 *   <li>1121-harphelper-quick: Quick Restart turns a click on the close button into a click on the selected song, only in the
 *       first 2 seconds, only while on.</li>
 *   <li>1121-harphelper-off: with the switch OFF the keys click nothing and no label is drawn.</li>
 *   <li>1121-same-harphelper-chord: two keys in one client tick are two clicks in that tick (not delayed or queued).</li>
 *   <li>1121-same-harphelper-click: keys while his own container click lands in the same ticks; run on default and
 *       experimental GrimAC.</li>
 * </ul>
 */
final class HarpHelperCases {

    private static final String F = "islands.HarpHelperFeature";
    private static final String AUTO = "islands.AutoHarpFeature";

    private HarpHelperCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1121-harphelper-keys", c -> play(c, -1, null, false));
        MenuSuite.test(s, "1121-harphelper-tracker", c -> play(c, 5, null, false));
        MenuSuite.test(s, "1121-harphelper-rebind", HarpHelperCases::rebind);
        MenuSuite.test(s, "1121-harphelper-hold", HarpHelperCases::hold);
        MenuSuite.test(s, "1121-harphelper-scale", HarpHelperCases::scale);
        MenuSuite.test(s, "1121-harphelper-tips", HarpHelperCases::tips);
        MenuSuite.test(s, "1121-harphelper-quick", HarpHelperCases::quick);
        MenuSuite.test(s, "1121-harphelper-off", HarpHelperCases::off);
        MenuSuite.test(s, "1121-term-guiscale", HarpHelperCases::termScale);
        MenuSuite.test(s, "1121-same-harphelper-chord", c -> play(c, -1, null, true));
        MenuSuite.test(s, "1121-same-harphelper-click", c -> play(c, -1, SameTick.Act.SCREEN_CLICK, false));
    }

    private static void set(Session c, String key, Object v) {
        c.ctx().runOnClient(mc -> Mod.call(Mod.field(F, "CFG"), "set", key, v));
    }

    private static int[] counts(Session c) {
        return c.onClient(mc -> (int[]) Mod.staticCall(F, "counts"));
    }

    /** A key as a real press (action 1) or release (0) reaches the open screen. */
    private static void key(Minecraft mc, int nativeKey, int action) {
        try {
            Method m = KeyboardHandler.class.getDeclaredMethod("keyPress", long.class, int.class, net.minecraft.client.input.KeyEvent.class);
            m.setAccessible(true);
            m.invoke(mc.keyboardHandler, mc.getWindow().handle(), action, McKeys.keyEvent(nativeKey, 0));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("KeyboardHandler.keyPress", e);
        }
    }

    private static boolean quartz(Minecraft mc, int slot) {
        if (!(McCompat.screen(mc) instanceof AbstractContainerScreen<?> s) || slot >= s.getMenu().slots.size()) {
            return false;
        }
        ItemStack it = s.getMenu().slots.get(slot).getItem();
        return !it.isEmpty() && "quartz_block".equals(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath());
    }

    /** Opens the fake song. */
    private static void open(Session c) throws Exception {
        c.hx().call("menu.harp", AutoHarpCases.spec(0, "[]"));
        MenuKit.awaitScreen(c, "Harp - Test", 100);
    }

    /**
     * Plays the song with keys: each tick, every column whose click-row slot shows a quartz note that was not pressed yet
     * gets its key. {@code skipArrival} (when >= 0) leaves out that arrival (0-based, in order of arrival).
     * {@code chord}: the presses of one tick go together and the case checks two clicks left in one client tick.
     */
    private static void play(Session c, int skipArrival, SameTick.Act his, boolean chord) throws Exception {
        MenuKit.reset(c);
        int before = counts(c)[4];
        SameTick st = null;
        try {
            set(c, "keys", true);
            set(c, "numbers", true);
            set(c, "tracker", true);
            set(c, "enabled", true);
            if (his != null) {
                st = c.onClient(mc -> SameTick.start(his, 1, p -> p instanceof ServerboundContainerClickPacket, 4, 5));
            }
            c.ctx().runOnClient(mc -> PacketTrace.start());
            open(c);
            boolean[] pressed = new boolean[7];
            int[] arrivals = {0};
            int[] sentKeys = {0};
            for (int t = 0; t < 1200 && c.events("harp.done").isEmpty(); t++) {
                c.ctx().runOnClient(mc -> {
                    for (int col = 0; col < 7; col++) {
                        boolean q = quartz(mc, 37 + col);
                        if (q && !pressed[col]) {
                            pressed[col] = true;
                            if (arrivals[0]++ != skipArrival) {
                                key(mc, InputConstants.KEY_1 + col, 1);
                                key(mc, InputConstants.KEY_1 + col, 0);
                                sentKeys[0]++;
                            }
                        } else if (!q) {
                            pressed[col] = false;
                        }
                    }
                });
                c.ctx().waitTicks(1);
            }
            c.ctx().waitTicks(8); // the tracker reads the last step one settled tick after it
            int perClientTick = c.onClient(mc -> PacketTrace.maxPerTick("container_click"));
            c.ctx().runOnClient(mc -> PacketTrace.stop());
            c.check(!c.events("harp.done").isEmpty(), "the song never finished");
            JsonObject done = c.events("harp.done").get(0);
            int notes = AutoHarpCases.NOTES;
            int want = skipArrival >= 0 ? notes - 1 : notes;
            int[] tr = counts(c);
            c.note("done " + done + ", keys pressed " + sentKeys[0] + ", tracker hit/seen/combo/best " + tr[0] + "/" + tr[1] + "/"
                    + tr[2] + "/" + tr[3] + ", labels drawn " + (tr[4] - before) + ", most container clicks in one client tick "
                    + perClientTick);
            c.check(done.get("ok").getAsInt() == want, "right clicks " + done.get("ok") + " of " + want);
            c.check(done.get("wrong").getAsInt() == 0, done.get("wrong") + " wrong click(s)");
            c.check(done.get("missed").getAsInt() == notes - want, done.get("missed") + " missed note(s), expected " + (notes - want));
            c.check(sentKeys[0] == want, "keys pressed " + sentKeys[0] + " of " + want);
            c.check(tr[0] == want && tr[1] == notes, "the tracker says " + tr[0] + "/" + tr[1] + ", the server " + want + "/" + notes);
            c.check(tr[4] - before > 0, "no key label was drawn");
            if (skipArrival < 0) {
                c.check(tr[3] == notes, "best combo " + tr[3] + " of " + notes);
            } else {
                c.check(tr[3] < want && tr[3] >= 1, "best combo " + tr[3] + " with one note left out");
            }
            if (chord) {
                c.check(perClientTick >= 2, "no client tick carried two key clicks (max " + perClientTick + "): the second press was held back");
            }
            if (his != null) {
                SameTick.judge(c, st, "keys with his " + his, SameTick.Expect.SHARE);
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick last = st;
                c.onClient(mc -> {
                    last.close();
                    return null;
                });
            }
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }

    private static void rebind(Session c) throws Exception {
        MenuKit.reset(c);
        Object stored = c.onClient(mc -> Mod.staticCall("compat.McInput", "toStoredKey", InputConstants.KEY_Q));
        Object def = c.onClient(mc -> Mod.staticCall("compat.McInput", "toStoredKey", InputConstants.KEY_1));
        try {
            set(c, "key1", stored);
            set(c, "enabled", true);
            open(c);
            c.waitUntil("a note in column 1", mc -> quartz(mc, 37), 400);
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_1, 1));
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_1, 0));
            c.ctx().waitTicks(4);
            c.check(c.events("harp.click").isEmpty(), "the old key 1 still plays column 1");
            c.waitUntil("a note in column 1", mc -> quartz(mc, 37), 400);
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_Q, 1));
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_Q, 0));
            c.waitUntil("the click of Q", mc -> !c.events("harp.click").isEmpty(), 40);
            JsonObject k = c.events("harp.click").get(0);
            c.note("Q clicked " + k);
            c.check(k.get("slot").getAsInt() == 37 && k.get("ok").getAsBoolean(), "Q did not play column 1 right: " + k);
        } finally {
            set(c, "key1", def);
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }

    /** The screen position (gui pixels) of a slot's center on the open container screen. */
    private static double[] slotAt(Minecraft mc, int slot) {
        Screen s = McCompat.screen(mc);
        Slot sl = ((AbstractContainerScreen<?>) s).getMenu().slots.get(slot);
        return new double[]{((Number) Mod.field(s, "leftPos")).intValue() + sl.x + 8, ((Number) Mod.field(s, "topPos")).intValue() + sl.y + 8};
    }

    private static void cursorTo(Session c, int slot) {
        double[] at = c.onClient(mc -> slotAt(mc, slot));
        double gui = c.onClient(mc -> (double) mc.getWindow().getGuiScale());
        c.ctx().getInput().setCursorPos(at[0] * gui, at[1] * gui);
        c.ctx().waitTicks(3);
    }

    private static void hold(Session c) throws Exception {
        MenuKit.reset(c);
        try {
            set(c, "enabled", true);
            open(c);
            cursorTo(c, 37);
            c.ctx().runOnClient(mc -> {
                key(mc, InputConstants.KEY_1, 1);
                key(mc, InputConstants.KEY_1, 1); // auto-repeat of a held key
                key(mc, InputConstants.KEY_1, 1);
                key(mc, InputConstants.KEY_1, 0);
                key(mc, InputConstants.KEY_1, 1); // a second real press
                key(mc, InputConstants.KEY_1, 0);
            });
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = c.events("harp.click");
            c.note("clicks the fake harp judged: " + clicks);
            c.check(clicks.size() == 2, clicks.size() + " click(s) for two presses (repeats and the number-key swap must not count)");
        } finally {
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }

    private static void scale(Session c) throws Exception {
        MenuKit.reset(c);
        int option = c.onClient(mc -> mc.options.guiScale().get());
        int base = c.onClient(mc -> mc.getWindow().calculateScale(option, mc.isEnforceUnicode()));
        int want = base > 1 ? 1 : 2; // a small test window clamps the big scales, 1 always fits
        int[] expected = c.onClient(mc -> new int[]{mc.getWindow().calculateScale(want, mc.isEnforceUnicode()),
                mc.getWindow().calculateScale(0, mc.isEnforceUnicode())});
        c.note("option " + option + ", window scale " + base + ", wanted " + want + " -> " + expected[0] + ", auto " + expected[1]);
        try {
            set(c, "scaleOn", true);
            set(c, "scale", want);
            set(c, "enabled", true);
            open(c);
            c.waitUntil("the window scale " + expected[0], mc -> mc.getWindow().getGuiScale() == expected[0], 40);
            int[] in = c.onClient(mc -> new int[]{(int) mc.getWindow().getGuiScale(), McCompat.screen(mc).width,
                    mc.getWindow().getGuiScaledWidth(), mc.options.guiScale().get()});
            c.note("in the harp: scale " + in[0] + ", screen width " + in[1] + ", scaled window width " + in[2] + ", option " + in[3]);
            c.check(expected[0] != base && in[0] == expected[0], "the scale did not change from " + base + " to " + expected[0] + ": " + in[0]);
            c.check(in[1] == in[2], "the screen was not resized to the new scale: " + in[1] + " vs " + in[2]);
            c.check(in[3] == option, "the saved GUI Scale option was changed to " + in[3]);
            MenuKit.reset(c);
            c.waitUntil("the scale back at " + base, mc -> mc.getWindow().getGuiScale() == base, 40);
            set(c, "scale", 0);
            open(c);
            c.waitUntil("Auto scale " + expected[1], mc -> mc.getWindow().getGuiScale() == expected[1], 40);
            set(c, "scaleOn", false);
            c.waitUntil("the scale back at " + base + " with the switch off", mc -> mc.getWindow().getGuiScale() == base, 40);
        } finally {
            set(c, "scaleOn", false);
            set(c, "scale", 0);
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }

    private static int tipLines(Session c) {
        return c.onClient(mc -> Screen.getTooltipFromItem(mc, new ItemStack(net.minecraft.world.item.Items.QUARTZ_BLOCK)).size());
    }

    private static void tips(Session c) throws Exception {
        MenuKit.reset(c);
        try {
            set(c, "hideTips", true);
            set(c, "enabled", true);
            c.check(tipLines(c) > 0, "no tooltip outside the harp");
            open(c);
            c.ctx().waitTicks(3);
            int hidden = tipLines(c);
            set(c, "hideTips", false);
            int shown = tipLines(c);
            set(c, "hideTips", true);
            set(c, "enabled", false);
            int off = tipLines(c);
            c.note("tooltip lines in the harp: hidden " + hidden + ", Hide Tooltips off " + shown + ", feature off " + off);
            c.check(hidden == 0, "the tooltip was not hidden: " + hidden + " line(s)");
            c.check(shown > 0 && off > 0, "the tooltip vanished with the setting or the feature off");
            MenuKit.reset(c);
            set(c, "enabled", true);
            c.ctx().waitTicks(2);
            c.check(tipLines(c) > 0, "the tooltip stayed hidden after leaving the harp");
        } finally {
            set(c, "hideTips", false);
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }

    private static void quick(Session c) throws Exception {
        MenuKit.reset(c);
        JsonObject list = MenuKit.obj("{\"title\":\"Melody\",\"rows\":6,\"fill\":true,\"slots\":{"
                + "\"40\":{\"item\":\"minecraft:barrier\",\"name\":\"§cClose\"},"
                + "\"22\":{\"item\":\"minecraft:quartz_block\",\"name\":\"§aSong A\",\"lore\":[\"§aSong is selected!\"]},"
                + "\"23\":{\"item\":\"minecraft:quartz_block\",\"name\":\"§aSong B\",\"lore\":[\"§7Click to select\"]}}}");
        try {
            set(c, "enabled", true);
            set(c, "quick", true);
            MenuKit.show(c, list);
            MenuKit.awaitScreen(c, "Melody", 100);
            cursorTo(c, 40);
            int before = c.events("menu.click").size();
            c.ctx().getInput().pressMouse(McKeys.mouse(0));
            c.ctx().waitTicks(6);
            List<JsonObject> a = c.events("menu.click");
            c.note("click on Close (quick on) -> " + a.subList(before, a.size()));
            c.check(a.size() == before + 1 && a.get(before).get("slot").getAsInt() == 22,
                    "the close click did not become a click on the selected song: " + a.subList(before, a.size()));
            // After the 2 seconds the close button is a plain close click.
            MenuKit.reset(c);
            MenuKit.show(c, list);
            MenuKit.awaitScreen(c, "Melody", 100);
            c.ctx().waitTicks(50);
            cursorTo(c, 40);
            int b0 = c.events("menu.click").size();
            c.ctx().getInput().pressMouse(McKeys.mouse(0));
            c.ctx().waitTicks(6);
            List<JsonObject> b = c.events("menu.click");
            c.check(b.size() == b0 + 1 && b.get(b0).get("slot").getAsInt() == 40, "late click: " + b.subList(b0, b.size()));
            // Switch off: the click is the close click.
            MenuKit.reset(c);
            set(c, "quick", false);
            MenuKit.show(c, list);
            MenuKit.awaitScreen(c, "Melody", 100);
            cursorTo(c, 40);
            int d0 = c.events("menu.click").size();
            c.ctx().getInput().pressMouse(McKeys.mouse(0));
            c.ctx().waitTicks(6);
            List<JsonObject> d = c.events("menu.click");
            c.check(d.size() == d0 + 1 && d.get(d0).get("slot").getAsInt() == 40, "quick off: " + d.subList(d0, d.size()));
        } finally {
            set(c, "quick", false);
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }

    /** The Terminal QoL Melody scale now runs through the same util/GuiScaleOverride: set, held while the terminal is open, put back. */
    private static void termScale(Session c) throws Exception {
        MenuKit.reset(c);
        String qol = "terminals.TerminalQolConfig";
        int option = c.onClient(mc -> mc.options.guiScale().get());
        int base = c.onClient(mc -> mc.getWindow().calculateScale(option, mc.isEnforceUnicode()));
        int want = base > 1 ? 1 : 2; // a small test window clamps the big scales, 1 always fits
        int expected = c.onClient(mc -> mc.getWindow().calculateScale(want, mc.isEnforceUnicode()));
        try {
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(qol), "setMelodyGuiScale", want));
            JsonObject t = c.hx().call("menu.terminal", "type", "MELODY", "seed", 31, "target", 2, "freeze", true,
                    "bandRows", 3).getAsJsonObject();
            MenuKit.awaitScreen(c, t.get("title").getAsString(), 100);
            c.waitUntil("the window scale " + expected + " in the Melody terminal (was " + base + ")",
                    mc -> mc.getWindow().getGuiScale() == expected, 60);
            c.check(expected != base, "the test scale equals the normal one");
            MenuKit.reset(c);
            c.waitUntil("the scale back at " + base, mc -> mc.getWindow().getGuiScale() == base, 60);
        } finally {
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(qol), "setMelodyGuiScale", 0));
            MenuKit.reset(c);
        }
    }

    private static void off(Session c) throws Exception {
        MenuKit.reset(c);
        int labels = counts(c)[4];
        try {
            set(c, "enabled", false);
            open(c);
            c.waitUntil("a note in column 1", mc -> quartz(mc, 37), 400);
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_1, 1));
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_1, 0));
            c.ctx().waitTicks(20);
            c.check(c.events("harp.click").isEmpty(), "a key clicked with the feature OFF");
            c.check(counts(c)[4] == labels, "a key label was drawn with the feature OFF");
            MenuKit.reset(c);
            set(c, "enabled", true);
            set(c, "keys", false);
            open(c);
            c.waitUntil("a note in column 1", mc -> quartz(mc, 37), 400);
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_1, 1));
            c.ctx().runOnClient(mc -> key(mc, InputConstants.KEY_1, 0));
            c.ctx().waitTicks(20);
            c.check(c.events("harp.click").isEmpty(), "a key clicked with Key Binds OFF");
            c.note("OFF and Key Binds OFF: no click, no label");
        } finally {
            set(c, "keys", true);
            set(c, "enabled", false);
            MenuKit.reset(c);
        }
    }
}
