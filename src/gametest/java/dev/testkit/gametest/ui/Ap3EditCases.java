package dev.testkit.gametest.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 387: the AP3 node editor ({@code /ap3 edit <n>}, cheat jar) - killer560, 2026-10-06: the type is a dropdown, a type
 * shows and saves only the fields it reads ("For nodes like align i do not need yaw or pitch so dont have it saved, if
 * i swap to a look node then add those boxes in"), a Use node's item is picked from his hotbar, and the close gate is
 * gone.
 *
 * <ul>
 *   <li>LOAD: an old-format chain (an Align WITH yaw 90, pitch and {@code close}; a Use whose saved item is not on the
 *       bar) loads, the Align with no yaw, no pitch, no close gate and its 1x3 box turned to the same rectangle.</li>
 *   <li>REAL INPUT: the editor on the Align; cursor moved and left button pressed in WINDOW coordinates (Auto Scale in
 *       the path) on Type, then on Look in the grid: Yaw and Pitch rows appear, filled with the player's view. Save,
 *       read the JSON: yaw/pitch present at 6 decimals, no close. Reload the file and compare exactly. Then Look back to
 *       Align: the rows go, Save, and the JSON has no yaw/pitch and the box turned back.</li>
 *   <li>USE: known items in hotbar slots 1, 3 (Skyblock ids) and 5 (none); the Item button names the saved item as
 *       "not in hotbar"; the dropdown lists the bar; clicking slot 3 stores its id (JSON and after reload), and
 *       "Held item / none" removes it.</li>
 *   <li>OVERLAP: every type, with each dropdown closed and open, no two widgets intersect and none leaves the screen.</li>
 * </ul>
 */
final class Ap3EditCases {

    private static final String SCREEN = "ap3.Ap3EditScreen";
    private static final String FILE = "testkit-ap3-edit";
    /** Far from the player, so nothing is ever walked into. */
    private static final double X0 = 900.5; // inside Ap3Store.MAX_ABS_COORD (1024)

    private Ap3EditCases() {
    }

    static void run(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: AP3 is cheat-only, skipped");
            return;
        }
        Object cfg = Mod.cfg("ap3.Ap3Config");
        String oldFile = (String) Mod.call(cfg, "getChainsFile");
        boolean oldForce = (Boolean) Mod.staticCall("ap3.Ap3Feature", "isForceDungeon");
        Path file = null;
        try {
            file = c.onClient(mc -> {
                Mod.call(cfg, "setChainsFile", FILE);
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", true);
                Path f = (Path) Mod.staticCall("ap3.Ap3Store", "file");
                try {
                    Files.writeString(f, oldFormatChain(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("cannot write " + f, e);
                }
                Mod.staticCall("ap3.Ap3Store", "reload");
                return f;
            });
            c.note("chains file " + file);
            loaded(c);
            overlaps(c);
            alignToLookAndBack(c, file);
            useItem(c, file);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(cfg, "setChainsFile", oldFile);
                Mod.staticCall("ap3.Ap3Feature", "setForceDungeon", oldForce);
                Mod.staticCall("ap3.Ap3Store", "reload");
                return null;
            });
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static String oldFormatChain() {
        return "{ \"version\": 2, \"chains\": { \"BOSS\": { \"class\": \"\", \"nodes\": [\n"
                + "  { \"type\": \"ALIGN\", \"x\": " + X0 + ", \"y\": 70.0, \"z\": 10.5, \"yaw\": 90.0, \"pitch\": 12.5,"
                + " \"width\": 1.0, \"length\": 3.0, \"close\": true, \"name\": \"stale\", \"useItemId\": \"STALE\" },\n"
                + "  { \"type\": \"USE\", \"x\": " + (X0 + 4) + ", \"y\": 70.0, \"z\": 10.5, \"yaw\": 33.123456,"
                + " \"pitch\": -12.654321, \"width\": 0.5, \"length\": 0.5, \"useItemId\": \"SAVED_GONE\" }\n"
                + "] } } }";
    }

    // ---- load ------------------------------------------------------------------------------------------------------

    private static void loaded(UiCase c) {
        List<?> nodes = nodes(c);
        c.check(nodes.size() == 2, "the test chain loaded " + nodes.size() + " node(s), expected 2");
        Object a = nodes.get(0);
        float yaw = (Float) Mod.field(a, "yaw");
        float pitch = (Float) Mod.field(a, "pitch");
        boolean close = (Boolean) Mod.field(a, "closeGate");
        double w = (Double) Mod.field(a, "width");
        double l = (Double) Mod.field(a, "length");
        c.note(String.format(Locale.ROOT, "loaded old Align: yaw %s pitch %s close %s box %sx%s name %s item %s", yaw,
                pitch, close, w, l, Mod.field(a, "name"), Mod.field(a, "useItemId")));
        if (yaw != 0f || pitch != 0f) {
            c.problem("an Align loaded from a file with yaw 90 / pitch 12.5 kept them in memory: " + yaw + " / " + pitch);
        }
        if (close) {
            c.problem("an old node's close gate was loaded (it should be dropped)");
        }
        if (w != 3.0 || l != 1.0) {
            c.problem("the Align's 1x3 box at yaw 90 should load as 3x1 at yaw 0 (same rectangle), got " + w + "x" + l);
        }
        if (Mod.field(a, "name") != null || Mod.field(a, "useItemId") != null) {
            c.problem("an Align kept a name / use item it never reads");
        }
        Object u = nodes.get(1);
        c.check((Float) Mod.field(u, "yaw") == 33.123456f && (Float) Mod.field(u, "pitch") == -12.654321f,
                "the Use node's angle did not load exactly: " + Mod.field(u, "yaw") + " / " + Mod.field(u, "pitch"));
    }

    // ---- type dropdown, real input ---------------------------------------------------------------------------------

    private static void alignToLookAndBack(UiCase c, Path file) throws Exception {
        c.onClient(mc -> {
            mc.player.setYRot(-37.25f);
            mc.player.setXRot(14.5f);
            return null;
        });
        c.ticks(2);
        Screen s = open(c, 0);
        List<String> before = fieldNames(c, s);
        c.note("Align editor fields: " + before + "; buttons: " + buttonLabels(c, s));
        if (before.contains("Yaw") || before.contains("Pitch")) {
            c.problem("the Align editor shows Yaw / Pitch: " + before);
        }
        for (String b : buttonLabels(c, s)) {
            if (b.toLowerCase(Locale.ROOT).contains("close")) {
                c.problem("the editor still has a close-gate control: '" + b + "'");
            }
        }
        click(c, s, button(c, s, "Type:"));
        c.check(button(c, s, "Look") != null, "the Type dropdown did not open a Look choice; buttons: "
                + buttonLabels(c, s));
        click(c, s, button(c, s, "Look"));
        Screen now = c.onClient(McCompat::screen);
        c.check(now == s, "picking a type closed the editor");
        List<String> after = fieldNames(c, s);
        String yawText = boxText(c, s, "Yaw");
        String pitchText = boxText(c, s, "Pitch");
        c.note("after picking Look: fields " + after + ", Yaw '" + yawText + "', Pitch '" + pitchText + "'");
        c.check(after.contains("Yaw") && after.contains("Pitch"), "Yaw / Pitch did not appear for Look: " + after);
        if (!"-37.25".equals(yawText) || !"14.5".equals(pitchText)) {
            c.problem("the new Yaw / Pitch should start at the player's view -37.25 / 14.5, got " + yawText + " / "
                    + pitchText);
        }
        c.check(String.valueOf(label(button(c, s, "Type:"))).contains("Look"), "the Type button does not read Look");

        // Six-decimal values through the boxes, then Save by real click.
        setBox(c, s, "Yaw", "123.456789");
        setBox(c, s, "Pitch", "-45.123456");
        setBox(c, s, "X", "900.123457");
        click(c, s, button(c, s, "Save"));
        JsonObject saved = node(file, 0);
        c.note("saved Look: " + saved);
        c.check("LOOK".equals(str(saved, "type")), "the saved type is " + str(saved, "type") + ", expected LOOK");
        c.check(saved.has("yaw") && saved.has("pitch"), "a Look node was saved without yaw/pitch: " + saved);
        exactFloat(c, "saved yaw", saved.get("yaw").getAsDouble(), 123.456789);
        exactFloat(c, "saved pitch", saved.get("pitch").getAsDouble(), -45.123456);
        exact(c, "saved x", saved.get("x").getAsDouble(), 900.123457);
        if (saved.has("close") || saved.has("name") || saved.has("useItemId")) {
            c.problem("the Look node was saved with a field it does not read: " + saved);
        }
        reload(c);
        Object n = nodes(c).get(0);
        c.check((Float) Mod.field(n, "yaw") == 123.456789f && (Float) Mod.field(n, "pitch") == -45.123456f
                        && (Double) Mod.field(n, "x") == 900.123457,
                "after reload the Look node reads " + Mod.field(n, "yaw") + " / " + Mod.field(n, "pitch") + " x "
                        + Mod.field(n, "x"));
        double w = (Double) Mod.field(n, "width");
        double l = (Double) Mod.field(n, "length");

        // Back to Align: the rows go, nothing angle-shaped is saved, and the box keeps its rectangle.
        s = open(c, 0);
        click(c, s, button(c, s, "Type:"));
        click(c, s, button(c, s, "Align"));
        List<String> back = fieldNames(c, s);
        c.note("after picking Align: fields " + back);
        if (back.contains("Yaw") || back.contains("Pitch")) {
            c.problem("Yaw / Pitch stayed after switching to Align: " + back);
        }
        click(c, s, button(c, s, "Save"));
        JsonObject align = node(file, 0);
        c.note("saved Align: " + align);
        c.check("ALIGN".equals(str(align, "type")), "the saved type is " + str(align, "type") + ", expected ALIGN");
        if (align.has("yaw") || align.has("pitch")) {
            c.problem("an Align node was saved WITH yaw/pitch: " + align);
        }
        // yaw 123.46 lays the box along east-west; with no yaw the same rectangle is length<->width swapped.
        exact(c, "Align width (was the Look's length)", align.get("width").getAsDouble(), l);
        exact(c, "Align length (was the Look's width)", align.get("length").getAsDouble(), w);
        exact(c, "Align x round trip", align.get("x").getAsDouble(), 900.123457);
    }

    // ---- Use item dropdown -----------------------------------------------------------------------------------------

    private static void useItem(UiCase c, Path file) throws Exception {
        give(c, 0, Items.DIAMOND_SWORD, "TESTKIT_BLADE", "Testkit Blade");
        give(c, 2, Items.STICK, "TESTKIT_WAND", "Testkit Wand");
        give(c, 4, Items.STONE, null, "Plain Stone");
        Screen s = open(c, 1);
        String itemButton = label(button(c, s, "Item:"));
        c.note("Use editor fields " + fieldNames(c, s) + "; item button '" + itemButton + "'");
        c.check(itemButton != null && itemButton.contains("SAVED_GONE") && itemButton.contains("not in hotbar"),
                "the Item button does not show the saved item as missing from the hotbar: '" + itemButton + "'");
        c.check(fieldNames(c, s).contains("Yaw") && fieldNames(c, s).contains("Pitch"), "a Use node lacks Yaw / Pitch");
        click(c, s, button(c, s, "Item:"));
        List<String> options = buttonLabels(c, s);
        c.note("hotbar dropdown: " + options);
        for (String want : new String[]{"Held item / none", "1: Testkit Blade", "3: Testkit Wand", "SAVED_GONE"}) {
            boolean found = false;
            for (String o : options) {
                found |= o.contains(want);
            }
            if (!found) {
                c.problem("the hotbar dropdown has no '" + want + "' choice");
            }
        }
        click(c, s, button(c, s, "3: Testkit Wand"));
        c.check(String.valueOf(label(button(c, s, "Item:"))).contains("Testkit Wand"),
                "after the pick the Item button reads '" + label(button(c, s, "Item:")) + "'");
        click(c, s, button(c, s, "Save"));
        JsonObject use = node(file, 1);
        c.note("saved Use: " + use);
        c.check("TESTKIT_WAND".equals(str(use, "useItemId")), "useItemId saved as " + str(use, "useItemId"));
        exactFloat(c, "untouched Use yaw", use.get("yaw").getAsDouble(), 33.123456);
        exactFloat(c, "untouched Use pitch", use.get("pitch").getAsDouble(), -12.654321);
        reload(c);
        c.check("TESTKIT_WAND".equals(Mod.field(nodes(c).get(1), "useItemId")),
                "after reload useItemId is " + Mod.field(nodes(c).get(1), "useItemId"));

        s = open(c, 1);
        click(c, s, button(c, s, "Item:"));
        click(c, s, button(c, s, "Held item / none"));
        click(c, s, button(c, s, "Save"));
        JsonObject held = node(file, 1);
        if (held.has("useItemId")) {
            c.problem("'Held item / none' still saved useItemId " + held.get("useItemId"));
        }
        c.note("after 'Held item / none': " + held);
    }

    private static void give(UiCase c, int slot, net.minecraft.world.item.Item item, String id, String name) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ItemStack st = new ItemStack(item);
                st.set(DataComponents.CUSTOM_NAME, Component.literal(name));
                if (id != null) {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("id", id);
                    st.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                }
                var sp = server.getPlayerList().getPlayer(uuid);
                sp.getInventory().setItem(slot, st);
                sp.inventoryMenu.broadcastChanges();
                done.set(true);
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get() != null && mc.player.getInventory().getItem(slot).is(item), 100);
        c.ticks(2);
    }

    // ---- overlap ---------------------------------------------------------------------------------------------------

    /** Every type, with neither dropdown, the Type dropdown, and (Use) the item dropdown open. */
    private static void overlaps(UiCase c) throws Exception {
        Screen s = open(c, 1);
        List<String> found = new ArrayList<>();
        int[] layouts = {0};
        c.onClient(mc -> {
            try {
                Object[] types = Mod.cls("ap3.Ap3Node$Type").getEnumConstants();
                for (Object t : types) {
                    for (int mode = 0; mode < 3; mode++) {
                        if (mode == 2 && !"USE".equals(((Enum<?>) t).name())) {
                            continue;
                        }
                        R.set(s, "type", t);
                        R.set(s, "typeOpen", mode == 1);
                        R.set(s, "itemOpen", mode == 2);
                        R.call0(s, "rebuildWidgets");
                        layouts[0]++;
                        String state = ((Enum<?>) t).name() + (mode == 1 ? " (type list open)" : mode == 2
                                ? " (item list open)" : "");
                        List<AbstractWidget> ws = widgets(s);
                        for (int a = 0; a < ws.size(); a++) {
                            AbstractWidget p = ws.get(a);
                            if (p.getY() < 0 || p.getY() + p.getHeight() > s.height || p.getX() < 0
                                    || p.getX() + p.getWidth() > s.width) {
                                found.add(state + ": '" + ModScreenDriver.label(p) + "' leaves the " + s.width + "x"
                                        + s.height + " screen at " + box(p));
                            }
                            for (int b = a + 1; b < ws.size(); b++) {
                                AbstractWidget q = ws.get(b);
                                if (p.getX() < q.getX() + q.getWidth() && q.getX() < p.getX() + p.getWidth()
                                        && p.getY() < q.getY() + q.getHeight() && q.getY() < p.getY() + p.getHeight()) {
                                    found.add(state + ": '" + ModScreenDriver.label(p) + "' " + box(p) + " and '"
                                            + ModScreenDriver.label(q) + "' " + box(q));
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                throw new AssertionError("overlap sweep: " + UiCase.describe(t), t);
            }
            McCompat.setScreen(mc, null);
            return null;
        });
        c.note("overlap sweep: " + layouts[0] + " editor layouts, " + found.size() + " problem(s)");
        c.check(layouts[0] >= 19, "only " + layouts[0] + " layouts checked");
        for (String f : found) {
            c.problem("overlap: " + f);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static List<?> nodes(UiCase c) {
        return c.onClient(mc -> new ArrayList<>((List<?>) Mod.staticCall("ap3.Ap3Feature", "currentChainNodes")));
    }

    private static void reload(UiCase c) {
        c.onClient(mc -> {
            Mod.staticCall("ap3.Ap3Store", "reload");
            return null;
        });
    }

    private static JsonObject node(Path file, int i) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray nodes = root.getAsJsonObject("chains").getAsJsonObject("BOSS").getAsJsonArray("nodes");
        return nodes.get(i).getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    /**
     * Yaw and pitch are FLOATS on the node (Ap3Node), so 123.456789 is held as 123.45678710... and written back at six
     * decimals as 123.456787: the file's sixth decimal can move on the first save, the angle the game uses cannot.
     * "Exact" for an angle is therefore the same float.
     */
    private static void exactFloat(UiCase c, String what, double got, double want) {
        if ((float) got != (float) want) {
            c.problem(what + " is " + got + " (float " + (float) got + "), expected the float of " + want + " ("
                    + (float) want + ")");
        }
    }

    private static void exact(UiCase c, String what, double got, double want) {
        if (got != want) {
            c.problem(what + " is " + got + ", expected exactly " + want);
        }
    }

    /** Opens the real editor on node {@code index} (0-based), as {@code /ap3 edit} does. */
    private static Screen open(UiCase c, int index) {
        Screen s = c.onClient(mc -> {
            try {
                Constructor<?> k = Mod.cls(SCREEN).getConstructor(Screen.class, int.class);
                Screen screen = (Screen) k.newInstance(null, index);
                McCompat.setScreen(mc, screen);
                return screen;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot open " + SCREEN + ": " + UiCase.describe(e), e);
            }
        });
        c.ticks(3);
        c.check(c.onClient(McCompat::screen) == s, "the AP3 editor did not stay open");
        return s;
    }

    private static List<AbstractWidget> widgets(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        for (Object o : s.children()) {
            if (o instanceof AbstractWidget w && w.visible) {
                out.add(w);
            }
        }
        return out;
    }

    private static List<String> fieldNames(UiCase c, Screen s) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : widgets(s)) {
                if (w instanceof EditBox) {
                    out.add(ModScreenDriver.label(w));
                }
            }
            return out;
        });
    }

    private static List<String> buttonLabels(UiCase c, Screen s) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : widgets(s)) {
                if (!(w instanceof EditBox)) {
                    out.add(ModScreenDriver.label(w).trim());
                }
            }
            return out;
        });
    }

    /** The first non-box widget whose label (formatting and padding stripped) starts with {@code prefix}. */
    private static AbstractWidget button(UiCase c, Screen s, String prefix) {
        return c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (!(w instanceof EditBox) && ModScreenDriver.label(w).trim().startsWith(prefix)) {
                    return w;
                }
            }
            return null;
        });
    }

    private static String label(AbstractWidget w) {
        return w == null ? null : ModScreenDriver.label(w).trim();
    }

    private static String boxText(UiCase c, Screen s, String name) {
        return c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (w instanceof EditBox e && ModScreenDriver.label(w).equals(name)) {
                    return e.getValue();
                }
            }
            return null;
        });
    }

    private static void setBox(UiCase c, Screen s, String name, String value) {
        boolean ok = c.onClient(mc -> {
            for (AbstractWidget w : widgets(s)) {
                if (w instanceof EditBox e && ModScreenDriver.label(w).equals(name)) {
                    e.setValue(value);
                    return true;
                }
            }
            return false;
        });
        c.check(ok, "no '" + name + "' box on the editor");
    }

    /** A real left click at the widget's centre, in window coordinates through the screen's Auto Scale factor. */
    private static void click(UiCase c, Screen s, AbstractWidget w) {
        c.check(w != null, "no such control on the AP3 editor");
        double[] a = c.onClient(mc -> {
            float f = ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", s)).floatValue();
            Window win = mc.getWindow();
            double sx = w.getX() + w.getWidth() / 2.0;
            double sy = w.getY() + w.getHeight() / 2.0;
            return new double[]{sx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                    sy * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
        });
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(2);
        c.ctx().getInput().pressMouse(0);
        c.ticks(3);
    }

    private static String box(AbstractWidget w) {
        return "[" + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight() + "]";
    }
}
