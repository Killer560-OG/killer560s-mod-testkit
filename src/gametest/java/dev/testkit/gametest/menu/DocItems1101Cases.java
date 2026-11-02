package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.AABB;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * killer560's to-do Doc items of 2026-11-01 (doc-items-1101):
 * <ul>
 *   <li>1101-menu-customitems-rows: the renamed buttons, Clear Changes / Copy Look at the bottom, no Show With Look, the
 *       Customize Custom Color rows (Reset Color for Use Color, a clear on the right of each), "Skin: N/A", "emerald" not
 *       "minecraft:emerald", Reset Color and Clear Trim acting, and the armor stand preview drawing two trim PATTERNS
 *       differently (an icon draws only the trim's color).</li>
 *   <li>1101-menu-customitems-skins: Wither Goggles' skins found for a starred / fragged / reforged copy (by id and by
 *       name alone), a skin applied through the Skin list sets the drawn head texture, Clear Skin takes it off.</li>
 *   <li>1101-menu-customitems-picker: the Customize Custom Item page shows the applied item again on reopen, and Clear
 *       takes it off the item.</li>
 *   <li>1101-menu-colorpicker-dyes: the color picker's Hypixel Dyes button sets the item's color, Clear Dye resets it.</li>
 *   <li>1101-menu-name-color-shadow: your name in exactly the picked color (a non-chat color is no longer snapped), the
 *       preview following a pick, Text Shadow giving every letter a shadow.</li>
 *   <li>1101-menu-objecthider-witherborn: the Witherborn wither (Invul 800) hidden and its client particles dropped; a
 *       boss wither (Invul 0) never hidden.</li>
 *   <li>1101-menu-modtag-styles: the three Tag Styles, their colors, the name tag hook using the chosen one, pictures.</li>
 * </ul>
 */
final class DocItems1101Cases {

    private static final String ADC = "armourdye.ArmourDyeConfig";
    private static final String NCC = "namechanger.NameChangerConfig";
    private static final String OHC = "objecthider.ObjectHiderConfig";
    private static final String MUC = "modusers.ModUserConfig";

    private DocItems1101Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1101-menu-customitems-rows", loud(DocItems1101Cases::rows));
        MenuSuite.test(s, "1101-menu-customitems-skins", loud(DocItems1101Cases::skins));
        MenuSuite.test(s, "1101-menu-customitems-picker", loud(DocItems1101Cases::picker));
        MenuSuite.test(s, "1101-menu-colorpicker-dyes", loud(DocItems1101Cases::dyes));
        MenuSuite.test(s, "1101-menu-name-color-shadow", loud(DocItems1101Cases::nameColour));
        MenuSuite.test(s, "1101-menu-objecthider-witherborn", loud(DocItems1101Cases::witherborn));
        MenuSuite.test(s, "1101-menu-modtag-styles", loud(DocItems1101Cases::modTag));
    }

    /** Prints a failure (and where it came from) BEFORE Session's failure screenshot, so a hang there still leaves it. */
    private static Session.Case loud(Session.Case body) {
        return c -> {
            try {
                body.run(c);
            } catch (Throwable t) {
                System.out.println("[" + c.name() + "] FAILING: " + t);
                for (Throwable x = t; x != null; x = x.getCause()) {
                    System.out.println("[" + c.name() + "]   cause: " + x);
                    StackTraceElement[] st = x.getStackTrace();
                    for (int i = 0; i < Math.min(12, st.length); i++) {
                        System.out.println("[" + c.name() + "]   at " + st[i]);
                    }
                }
                throw t;
            }
        };
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static AutoCloseable gateOff() {
        boolean was = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
        Mod.staticCall("util.SkyblockGate", "setEnabled", false);
        return () -> Mod.staticCall("util.SkyblockGate", "setEnabled", was);
    }

    private static AbstractWidget button(Screen screen, String prefix) {
        return screen.children().stream()
                .filter(w -> w instanceof AbstractWidget aw && aw.getMessage().getString().startsWith(prefix))
                .map(w -> (AbstractWidget) w).findFirst().orElse(null);
    }

    private static List<String> labels(Screen screen) {
        List<String> out = new ArrayList<>();
        screen.children().forEach(w -> {
            if (w instanceof AbstractWidget aw) {
                out.add(aw.getMessage().getString());
            }
        });
        return out;
    }

    /** A left click on the middle of {@code w}, the way the mouse sends it. */
    private static void press(Session c, AbstractWidget w) {
        c.onClient(mc -> {
            w.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(w.getX() + w.getWidth() / 2.0,
                    w.getY() + w.getHeight() / 2.0,
                    new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
            return null;
        });
        c.ctx().waitTicks(3);
    }

    private static Screen screen(Session c) {
        return c.onClient(McCompat::screen);
    }

    private static ItemStack sbItem(net.minecraft.world.item.Item item, String id, String uuid, String name) {
        ItemStack s = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        if (id != null) {
            tag.putString("id", id);
        }
        if (uuid != null) {
            tag.putString("uuid", uuid);
        }
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (name != null) {
            s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
        return s;
    }

    /** Opens Custom Items on {@code slot}'s item (select + refresh, what a grid click does). */
    private static Screen openOn(Session c, int slot) {
        Screen s = c.onClient(mc -> {
            try {
                Screen sc = (Screen) Mod.cls("armourdye.CustomItemsScreen").getConstructor(Screen.class).newInstance((Object) null);
                McCompat.setScreen(mc, sc);
                return sc;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.ctx().waitTicks(3);
        c.onClient(mc -> {
            Mod.call(s, "select", mc.player.getInventory().getItem(slot));
            Mod.call(s, "refresh");
            return null;
        });
        c.ctx().waitTicks(2);
        return s;
    }

    private static void close(Session c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ctx().waitTicks(2);
    }

    private static Path shot(Session c, String label) {
        String name = c.name() + "-" + label;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        c.note("picture " + label + " -> " + (kept != null ? kept : taken));
        return taken;
    }

    // ---- 1101-menu-customitems-rows --------------------------------------------------------------------------------

    static void rows(Session c) throws Exception {
        try (AutoCloseable on = Mod.with(ADC, "Enabled", true); AutoCloseable gate = gateOff()) {
            Mod.call(Mod.cfg(ADC), "clear");
            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, sbItem(Items.LEATHER_CHESTPLATE, "TK_PLATE", "1101-rows", null));
                return null;
            });
            Screen main = openOn(c, 9);
            List<String> mainLabels = c.onClient(mc -> labels(main));
            c.note("main page buttons: " + mainLabels);
            c.check(mainLabels.contains("Customize Custom Item") && mainLabels.contains("Customize Custom Color"),
                    "renamed buttons missing: " + mainLabels);
            c.check(mainLabels.stream().noneMatch(l -> l.startsWith("Show With Look") || l.startsWith("Show Clear")
                    || l.equals("Custom Item") || l.equals("Custom Color")), "an old button is still there: " + mainLabels);
            int[] ys = c.onClient(mc -> new int[]{button(main, "Customize Custom Color").getY(),
                    button(main, "Clear Changes").getY(), button(main, "Copy Look").getY(),
                    ((int[]) Mod.call(main, "themedPanelBounds"))[1] + ((int[]) Mod.call(main, "themedPanelBounds"))[3]});
            c.note("y: Customize Custom Color " + ys[0] + ", Clear Changes " + ys[1] + ", Copy Look " + ys[2] + ", panel bottom " + ys[3]);
            c.check(ys[1] > ys[0] + 60 && ys[2] > ys[1] && ys[2] + 20 <= ys[3] && ys[3] - (ys[2] + 20) <= 12,
                    "Clear Changes / Copy Look are not at the bottom of the panel");
            shot(c, "main");

            press(c, c.onClient(mc -> button(main, "Customize Custom Color")));
            Screen page = screen(c);
            c.check(page != null && page.getClass().getSimpleName().equals("CustomColorScreen"), "the page did not open");
            List<String> pageLabels = c.onClient(mc -> labels(page));
            c.note("Customize Custom Color rows: " + pageLabels);
            c.check(pageLabels.equals(List.of("Back", "Applies To: Every Copy", "Color: Off", "Reset Color", "Skin: N/A",
                    "Clear Skin", "Trim Color: Off", "Clear Trim Color", "Trim Pattern: Off", "Clear Trim Pattern")),
                    "page rows " + pageLabels);
            // Each clear sits on the right of its own row.
            String[][] pairs = {{"Color", "Reset Color"}, {"Skin", "Clear Skin"}, {"Trim Color", "Clear Trim Color"},
                    {"Trim Pattern", "Clear Trim Pattern"}};
            for (String[] p : pairs) {
                boolean right = c.onClient(mc -> {
                    AbstractWidget a = button(page, p[0] + ":");
                    AbstractWidget b = button(page, p[1]);
                    return a != null && b != null && a.getY() == b.getY() && b.getX() >= a.getX() + a.getWidth();
                });
                c.check(right, p[1] + " is not on the right of " + p[0]);
            }
            int[] rowYs = c.onClient(mc -> new int[]{button(page, "Color:").getY(), button(page, "Skin:").getY(),
                    button(page, "Trim Color:").getY(), button(page, "Trim Pattern:").getY()});
            c.check(rowYs[0] < rowYs[1] && rowYs[1] < rowYs[2] && rowYs[2] < rowYs[3], "the rows do not run down the page");

            // Reset Color: a color on, then reset - the item draws its own color again and the empty entry is gone.
            c.onClient(mc -> {
                Mod.call(main, "setColour", 0xFF00FF00);
                Mod.call(page, "rebuildWidgets");
                return null;
            });
            int dyed = c.onClient(mc -> DyedItemColor.getOrDefault(mc.player.getInventory().getItem(9), 0xFF123456));
            press(c, c.onClient(mc -> button(page, "Reset Color")));
            int reset = c.onClient(mc -> DyedItemColor.getOrDefault(mc.player.getInventory().getItem(9), 0xFF123456));
            Object entryAfterReset = c.onClient(mc -> Mod.call(main, "entry"));
            c.note("color " + Integer.toHexString(dyed) + " -> after Reset Color " + Integer.toHexString(reset)
                    + ", entry " + entryAfterReset);
            c.check(dyed == 0xFF00FF00 && reset == 0xFF123456 && entryAfterReset == null, "Reset Color did not reset");

            // Trims: names without the namespace; two patterns drawn differently on the stand.
            List<?> mats = c.onClient(mc -> (List<?>) Mod.staticCall("armourdye.ArmourTrims", "materials"));
            List<?> pats = c.onClient(mc -> (List<?>) Mod.staticCall("armourdye.ArmourTrims", "patterns"));
            c.note("trim materials " + mats.size() + ", patterns " + pats.size());
            c.check(mats.contains("minecraft:emerald") && pats.contains("minecraft:sentry") && pats.contains("minecraft:spire"),
                    "premise: the trims are not loaded");
            c.onClient(mc -> {
                Mod.call(main, "setTrimChoice", true, "minecraft:emerald");
                Mod.call(main, "setTrimChoice", false, "minecraft:sentry");
                Mod.call(page, "rebuildWidgets");
                return null;
            });
            c.ctx().waitTicks(3);
            List<String> trimLabels = c.onClient(mc -> labels(page));
            c.check(trimLabels.contains("Trim Color: emerald") && trimLabels.contains("Trim Pattern: sentry"),
                    "trim rows read " + trimLabels);
            c.check("emerald".equals(Mod.staticCall("armourdye.ChoiceListScreen", "displayName", "minecraft:emerald")),
                    "the dropdown still says minecraft:emerald");
            boolean onStand = c.onClient(mc -> (Boolean) Mod.call(page, "previewOnStand"));
            c.check(onStand, "the armor piece is not previewed on an armor stand");
            c.ctx().getInput().setCursorPos(2, 2);
            c.ctx().waitTicks(4);
            BufferedImage sentry = ImageIO.read(shot(c, "trim-sentry").toFile());
            c.ctx().waitTicks(4);
            BufferedImage sentry2 = ImageIO.read(shot(c, "trim-sentry-again").toFile());
            c.onClient(mc -> {
                Mod.call(main, "setTrimChoice", false, "minecraft:spire");
                return null;
            });
            c.ctx().waitTicks(4);
            BufferedImage spire = ImageIO.read(shot(c, "trim-spire").toFile());
            int[] well = c.onClient(mc -> {
                int[] b = (int[]) Mod.call(page, "themedPanelBounds");
                // Layout units -> pixels: the window's GUI scale times Auto Scale's factor for this screen.
                double f = mc.getWindow().getGuiScale()
                        * ((Number) Mod.staticCall("hud.AutoScale", "appliedFactor", page)).doubleValue();
                return new int[]{(int) ((b[0] + 13) * f), (int) ((b[1] + 41) * f), (int) (122 * f), (int) (122 * f)};
            });
            int control = diff(sentry, sentry2, well);
            int patterns = diff(sentry, spire, well);
            c.note("pixels differing in the preview well: same pattern twice " + control + ", sentry vs spire " + patterns);
            c.check(patterns > 40 && patterns > control * 4 + 20, "two trim patterns draw the same preview");

            press(c, c.onClient(mc -> button(page, "Clear Trim Pattern")));
            Object pattern = c.onClient(mc -> Mod.field(Mod.call(main, "entry"), "trimPattern"));
            c.check("".equals(pattern), "Clear Trim Pattern left " + pattern);
            press(c, c.onClient(mc -> button(page, "Clear Trim Color")));
            Object gone = c.onClient(mc -> Mod.call(main, "entry"));
            c.check(gone == null, "after clearing both trims the entry is still there");
        } finally {
            close(c);
            Mod.call(Mod.cfg(ADC), "clear");
            Mod.call(Mod.cfg(ADC), "save");
        }
    }

    private static int diff(BufferedImage a, BufferedImage b, int[] r) {
        int n = 0;
        for (int x = r[0]; x < Math.min(r[0] + r[2], Math.min(a.getWidth(), b.getWidth())); x++) {
            for (int y = r[1]; y < Math.min(r[1] + r[3], Math.min(a.getHeight(), b.getHeight())); y++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                int d = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                        + Math.abs((p & 0xFF) - (q & 0xFF));
                if (d > 30) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---- 1101-menu-customitems-skins -------------------------------------------------------------------------------

    static void skins(Session c) throws Exception {
        try (AutoCloseable on = Mod.with(ADC, "Enabled", true); AutoCloseable gate = gateOff()) {
            Mod.call(Mod.cfg(ADC), "clear");
            String base = (String) Mod.staticCall("armourdye.ItemSkins", "baseName", "§d⚚ Heroic Wither Goggles §6✪✪✪✪✪§c➌");
            c.note("cleaned name: '" + base + "'");
            c.check("heroic wither goggles".equals(base), "baseName gave " + base);
            List<String> byName = skinNames((List<?>) Mod.staticCall("armourdye.ItemSkins", "forNames", null, base));
            c.note("skins by name alone: " + byName);
            c.check(byName.contains("Celestial Wither Goggles Skin") && byName.contains("Cyberpunk Wither Goggles Skin")
                    && byName.contains("Corrupt Wither Goggles Helmet Skin"), "Wither Goggles skins not found by name: " + byName);
            c.check(!byName.contains("Awakened Wither Helmet Skin"), "a Wither Helmet skin matched the goggles");

            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, sbItem(Items.PLAYER_HEAD, "STARRED_WITHER_GOGGLES", "1101-goggles",
                        "⚚ Heroic Wither Goggles ✪✪✪✪✪"));
                mc.player.getInventory().setItem(10, sbItem(Items.LEATHER_CHESTPLATE, "TK_PLATE2", "1101-plate", null));
                return null;
            });
            List<String> forItem = c.onClient(mc -> skinNames((List<?>) Mod.staticCall("armourdye.ItemSkins", "forItem",
                    mc.player.getInventory().getItem(9))));
            List<String> forPlate = c.onClient(mc -> skinNames((List<?>) Mod.staticCall("armourdye.ItemSkins", "forItem",
                    mc.player.getInventory().getItem(10))));
            c.note("skins for the starred fragged goggles: " + forItem + "; for a leather chestplate: " + forPlate);
            c.check(forItem.size() >= 3 && forItem.contains("Celestial Wither Goggles Skin"), "goggles skins " + forItem);
            c.check(forPlate.isEmpty(), "a plain chestplate has skins " + forPlate);

            Screen main = openOn(c, 9);
            press(c, c.onClient(mc -> button(main, "Customize Custom Color")));
            Screen page = screen(c);
            AbstractWidget skin = c.onClient(mc -> button(page, "Skin:"));
            c.note("goggles Skin row: " + skin.getMessage().getString() + ", active " + skin.active);
            c.check(skin.getMessage().getString().equals("Skin: None") && skin.active, "Skin row " + skin.getMessage().getString());
            press(c, skin);
            Screen list = screen(c);
            c.check(list != null && list.getClass().getSimpleName().equals("HypixelSkinScreen"), "the skin list did not open");
            // The first row, clicked where the mouse would.
            c.onClient(mc -> {
                int px = (Integer) Mod.field(list, "panelX");
                int listY = (Integer) Mod.field(list, "listY");
                list.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(px + 30, listY + 10,
                        new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
                return null;
            });
            c.ctx().waitTicks(3);
            Object entry = c.onClient(mc -> Mod.call(main, "entry"));
            String skinName = entry == null ? null : (String) Mod.field(entry, "skinName");
            String drawn = c.onClient(mc -> {
                var prof = mc.player.getInventory().getItem(9).get(DataComponents.PROFILE);
                return prof == null ? null : prof.partialProfile().properties().get("textures").iterator().next().value();
            });
            String want = entry == null ? null : (String) Mod.field(entry, "headTexture");
            c.note("applied skin " + skinName + "; drawn texture matches " + (drawn != null && drawn.equals(want)));
            c.check(skinName != null && forItem.contains(skinName) && drawn != null && drawn.equals(want),
                    "the skin was not applied and drawn");
            Screen back = screen(c);
            c.check(back == page, "picking a skin did not return to the page");
            c.onClient(mc -> {
                Mod.call(page, "rebuildWidgets");
                return null;
            });
            String row = c.onClient(mc -> button(page, "Skin:").getMessage().getString());
            c.check(row.equals("Skin: " + skinName), "Skin row reads " + row);
            shot(c, "goggles-skin");
            press(c, c.onClient(mc -> button(page, "Clear Skin")));
            Object after = c.onClient(mc -> Mod.call(main, "entry"));
            c.check(after == null, "Clear Skin left an entry");
            // The leather chestplate: N/A, the row inactive.
            close(c);
            Screen main2 = openOn(c, 10);
            press(c, c.onClient(mc -> button(main2, "Customize Custom Color")));
            Screen page2 = screen(c);
            AbstractWidget na = c.onClient(mc -> button(page2, "Skin:"));
            c.check(na.getMessage().getString().equals("Skin: N/A") && !na.active, "chestplate Skin row "
                    + na.getMessage().getString() + " active " + na.active);
        } finally {
            close(c);
            Mod.call(Mod.cfg(ADC), "clear");
            Mod.call(Mod.cfg(ADC), "save");
        }
    }

    private static List<String> skinNames(List<?> skins) {
        List<String> out = new ArrayList<>();
        for (Object s : skins) {
            out.add((String) Mod.call(s, "name"));
        }
        return out;
    }

    // ---- 1101-menu-customitems-picker ------------------------------------------------------------------------------

    static void picker(Session c) throws Exception {
        try (AutoCloseable on = Mod.with(ADC, "Enabled", true); AutoCloseable gate = gateOff()) {
            Mod.call(Mod.cfg(ADC), "clear");
            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, sbItem(Items.LEATHER_CHESTPLATE, "TK_PLATE3", "1101-picker", null));
                return null;
            });
            Screen main = openOn(c, 9);
            press(c, c.onClient(mc -> button(main, "Customize Custom Item")));
            Screen picker = screen(c);
            c.check(picker != null && picker.getClass().getSimpleName().equals("CustomItemPickerScreen"), "picker did not open");
            c.onClient(mc -> {
                ((net.minecraft.client.gui.components.EditBox) picker.children().stream()
                        .filter(w -> w instanceof net.minecraft.client.gui.components.EditBox).findFirst().orElseThrow())
                        .setValue("trident");
                return null;
            });
            int row = c.onClient(mc -> {
                List<?> shown = (List<?>) Mod.field(picker, "shown");
                for (int i = 0; i < shown.size(); i++) {
                    if ("Trident".equals(Mod.call(shown.get(i), "name")) && Mod.call(shown.get(i), "minecraft") != null) {
                        return i;
                    }
                }
                return -1;
            });
            c.check(row >= 0, "premise: no Trident row");
            c.onClient(mc -> {
                int px = (Integer) Mod.field(picker, "panelX");
                int listY = (Integer) Mod.field(picker, "listY");
                picker.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(px + 20, listY + row * 18 + 9,
                        new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
                return null;
            });
            c.ctx().waitTicks(2);
            press(c, c.onClient(mc -> button(picker, "Apply to Selected")));
            String model = c.onClient(mc -> String.valueOf(mc.player.getInventory().getItem(9).get(DataComponents.ITEM_MODEL)));
            c.check(model.equals("minecraft:trident"), "apply gave " + model);
            // Leave the page, come back: the trident is shown picked, and on the list.
            press(c, c.onClient(mc -> button(main, "Customize Custom Item")));
            Screen again = screen(c);
            String picked = c.onClient(mc -> (String) Mod.call(again, "pickedKey"));
            boolean visible = c.onClient(mc -> {
                List<?> shown = (List<?>) Mod.field(again, "shown");
                Object p = Mod.field(again, "picked");
                int at = shown.indexOf(p);
                int scroll = (Integer) Mod.field(again, "scroll");
                int rows = (Integer) Mod.field(again, "rows");
                return at >= scroll && at < scroll + rows;
            });
            c.note("reopened picker: picked " + picked + ", on screen " + visible);
            c.check("minecraft:trident".equals(picked) && visible, "the reopened picker does not show the applied trident");
            shot(c, "reopened");
            press(c, c.onClient(mc -> button(again, "Clear")));
            String cleared = c.onClient(mc -> String.valueOf(mc.player.getInventory().getItem(9).get(DataComponents.ITEM_MODEL)));
            Object entry = c.onClient(mc -> Mod.call(main, "entry"));
            c.note("after Clear: model " + cleared + ", entry " + entry);
            c.check(cleared.equals("minecraft:leather_chestplate") && entry == null, "Clear left " + cleared);
        } finally {
            close(c);
            Mod.call(Mod.cfg(ADC), "clear");
            Mod.call(Mod.cfg(ADC), "save");
        }
    }

    // ---- 1101-menu-colorpicker-dyes --------------------------------------------------------------------------------

    static void dyes(Session c) throws Exception {
        try (AutoCloseable on = Mod.with(ADC, "Enabled", true); AutoCloseable gate = gateOff()) {
            Mod.call(Mod.cfg(ADC), "clear");
            c.onClient(mc -> {
                mc.player.getInventory().setItem(9, sbItem(Items.LEATHER_CHESTPLATE, "TK_PLATE4", "1101-dyes",
                        "Loving Storm's Chestplate ✪✪✪✪✪"));
                return null;
            });
            Screen main = openOn(c, 9);
            press(c, c.onClient(mc -> button(main, "Customize Custom Color")));
            Screen page = screen(c);
            press(c, c.onClient(mc -> button(page, "Color:")));
            Screen picker = screen(c);
            c.check(picker != null && picker.getClass().getSimpleName().equals("ColorPickerScreen"), "picker did not open");
            // The title must stop before the swatch: the item's name is wider than that room.
            int[] room = c.onClient(mc -> {
                int panelX = (Integer) Mod.field(picker, "panelX");
                int panelW = (Integer) Mod.field(picker, "panelW");
                int titleW = panelX + panelW - 20 - 10 - 6 - (panelX + 10);
                return new int[]{mc.font.width(picker.getTitle().getString()), titleW};
            });
            c.note("title width " + room[0] + " vs room before the swatch " + room[1]);
            c.check(room[0] > room[1], "premise: the title fits anyway");
            shot(c, "long-title");
            AbstractWidget dyesBtn = c.onClient(mc -> button(picker, "Hypixel Dyes"));
            c.check(dyesBtn != null, "no Hypixel Dyes button on the color picker");
            press(c, dyesBtn);
            Screen dyeList = screen(c);
            c.check(dyeList != null && dyeList.getClass().getSimpleName().equals("DyePickerScreen"), "dye list did not open");
            int first = c.onClient(mc -> {
                List<?> all = (List<?>) Mod.staticCall("armourdye.HypixelDyes", "all");
                return 0xFF000000 | (Integer) Mod.call(all.get(0), "from");
            });
            c.onClient(mc -> {
                int px = (Integer) Mod.field(dyeList, "panelX");
                int listY = (Integer) Mod.field(dyeList, "listY");
                dyeList.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(px + 40, listY + 9,
                        new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0)), false);
                return null;
            });
            c.ctx().waitTicks(3);
            Screen backTo = screen(c);
            int pickerArgb = c.onClient(mc -> (Integer) Mod.call(picker, "currentArgb"));
            int itemColour = c.onClient(mc -> DyedItemColor.getOrDefault(mc.player.getInventory().getItem(9), 0xFF123456));
            c.note("dye " + Integer.toHexString(first) + ": picker " + Integer.toHexString(pickerArgb) + ", item "
                    + Integer.toHexString(itemColour));
            c.check(backTo == picker && pickerArgb == first && itemColour == first, "the dye was not applied");
            press(c, c.onClient(mc -> button(picker, "Hypixel Dyes")));
            Screen dyeList2 = screen(c);
            press(c, c.onClient(mc -> button(dyeList2, "Clear Dye")));
            int cleared = c.onClient(mc -> DyedItemColor.getOrDefault(mc.player.getInventory().getItem(9), 0xFF123456));
            c.check(cleared == 0xFF123456, "Clear Dye left " + Integer.toHexString(cleared));
        } finally {
            close(c);
            Mod.call(Mod.cfg(ADC), "clear");
            Mod.call(Mod.cfg(ADC), "save");
        }
    }

    // ---- 1101-menu-name-color-shadow -------------------------------------------------------------------------------

    static void nameColour(Session c) throws Exception {
        Object cfg = Mod.cfg(NCC);
        boolean wasEnabled = (Boolean) Mod.call(cfg, "isEnabled");
        boolean wasOwn = (Boolean) Mod.call(cfg, "isOwnNameEnabled");
        String wasName = (String) Mod.call(cfg, "getOwnDisplayName");
        int wasColour = (Integer) Mod.call(cfg, "getOwnColor");
        boolean wasShadow = (Boolean) Mod.call(cfg, "isOwnShadow");
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setOwnNameEnabled", true);
                Mod.call(cfg, "setOwnDisplayName", "TkName");
                Mod.call(cfg, "setOwnColor", 0xFFC87020);
                Mod.call(cfg, "setOwnShadow", false);
                return null;
            });
            List<Style> styles = previewStyles(c);
            c.note("preview letter colors with #C87020: " + colours(styles));
            c.check(!styles.isEmpty() && styles.stream().allMatch(s -> s.getColor() != null && (s.getColor().getValue() & 0xFFFFFF) == 0xC87020),
                    "the preview is not in the picked color: " + colours(styles));
            int[] shared = (int[]) Mod.staticCall("namechanger.NameChangerFeature", "ownShareColours");
            c.check(shared != null && shared.length == 6 && shared[0] == 0xC87020, "the exact color is not shared");
            // A chat color stays its code.
            c.onClient(mc -> {
                Mod.call(cfg, "setOwnColor", 0xFFFFAA00);
                return null;
            });
            String styled = (String) Mod.staticCall("namechanger.NameChangerFeature", "ownStyled", cfg);
            c.check(styled.startsWith("§6"), "gold is not the §6 code: " + styled);
            c.check(Mod.staticCall("namechanger.NameChangerFeature", "ownShareColours") == null, "gold sent a color list");

            // The tab's preview follows a pick made in the color picker.
            List<AbstractWidget> ws = c.onClient(mc -> {
                try {
                    Object tab = Mod.cls("gui.tab.NameChangerTab").getConstructor().newInstance();
                    @SuppressWarnings("unchecked")
                    List<AbstractWidget> list = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                    return list;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            List<String> tabLabels = ws.stream().map(w -> w.getMessage().getString()).toList();
            c.check(tabLabels.stream().anyMatch(l -> l.startsWith("Text Shadow:") && l.endsWith("OFF")),
                    "no Text Shadow row: " + tabLabels);
            AbstractWidget colourBtn = ws.stream().filter(w -> net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()).startsWith("Color:")).findFirst().orElse(null);
            AbstractWidget preview = ws.stream().filter(w -> net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString()).startsWith("Preview:")).findFirst().orElse(null);
            c.check(colourBtn != null && preview != null, "premise: no color button or preview");
            press(c, colourBtn);
            Screen picker = screen(c);
            c.onClient(mc -> {
                Mod.call(picker, "setColour", 0xFF2080C0);
                return null;
            });
            List<Style> after = new ArrayList<>();
            preview.getMessage().visit((style, text) -> {
                if (!text.contains("Preview") && !text.isBlank()) {
                    for (int i = 0; i < text.length(); i++) {
                        after.add(style);
                    }
                }
                return java.util.Optional.empty();
            }, Style.EMPTY);
            c.note("preview after picking #2080C0: " + colours(after));
            c.check(!after.isEmpty() && after.stream().allMatch(s -> s.getColor() != null
                    && (s.getColor().getValue() & 0xFFFFFF) == 0x2080C0), "the preview did not follow the pick");
            close(c);

            // Text Shadow: every letter gets a shadow, a quarter of its color.
            AbstractWidget shadowBtn = ws.stream().filter(w -> w.getMessage().getString().startsWith("Text Shadow")).findFirst().orElseThrow();
            press(c, shadowBtn);
            List<Style> shadowed = previewStyles(c);
            c.note("Text Shadow on: shadow colors " + shadowed.stream().map(s -> s.getShadowColor() == null ? "none"
                    : Integer.toHexString(s.getShadowColor())).toList());
            c.check((Boolean) Mod.call(cfg, "isOwnShadow"), "the Text Shadow button did not turn it on");
            c.check(!shadowed.isEmpty() && shadowed.stream().allMatch(s -> s.getShadowColor() != null
                    && s.getShadowColor() == 0xFF082030), "letters without the shadow");
            // Saved and loaded back.
            c.onClient(mc -> {
                Mod.call(cfg, "save");
                Mod.staticCall(NCC, "load");
                return null;
            });
            c.check((Boolean) Mod.call(Mod.cfg(NCC), "isOwnShadow"), "Text Shadow did not survive a reload");
        } finally {
            Object now = Mod.cfg(NCC);
            Mod.call(now, "setEnabled", wasEnabled);
            Mod.call(now, "setOwnNameEnabled", wasOwn);
            Mod.call(now, "setOwnDisplayName", wasName);
            Mod.call(now, "setOwnColor", wasColour);
            Mod.call(now, "setOwnShadow", wasShadow);
            Mod.call(now, "save");
            close(c);
        }
    }

    private static List<Style> previewStyles(Session c) {
        Component preview = c.onClient(mc -> (Component) Mod.staticCall("namechanger.NameChangerFeature", "ownPreview"));
        List<Style> out = new ArrayList<>();
        preview.visit((style, text) -> {
            for (int i = 0; i < text.length(); i++) {
                out.add(style);
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static List<String> colours(List<Style> styles) {
        return styles.stream().map(s -> s.getColor() == null ? "none" : Integer.toHexString(s.getColor().getValue())).toList();
    }

    // ---- 1101-menu-objecthider-witherborn ---------------------------------------------------------------------------

    static void witherborn(Session c) throws Exception {
        Object cfg = Mod.cfg(OHC);
        boolean wasMaster = (Boolean) Mod.call(cfg, "isObjectHiderEnabled");
        boolean was = (Boolean) Mod.call(cfg, "getHideWitherbornRaw");
        double[] at = c.onClient(mc -> new double[]{Math.floor(mc.player.getX()) + 0.5, Math.floor(mc.player.getY()) + 3,
                Math.floor(mc.player.getZ()) + 5.5});
        AABB box = new AABB(at[0] - 6, at[1] - 6, at[2] - 6, at[0] + 6, at[1] + 6, at[2] + 6);
        try (AutoCloseable gate = gateOff()) {
            Mod.call(cfg, "setObjectHiderEnabled", true);
            c.server().command("kill @e[tag=tk1101]");
            c.server().command(String.format(java.util.Locale.ROOT,
                    "summon wither %.1f %.1f %.1f {Tags:[\"tk1101\",\"born\"],NoAI:1b,Silent:1b,NoGravity:1b,Invul:800,PersistenceRequired:1b}",
                    at[0], at[1], at[2]));
            c.server().command(String.format(java.util.Locale.ROOT,
                    "summon wither %.1f %.1f %.1f {Tags:[\"tk1101\",\"tk1101boss\"],NoAI:1b,Silent:1b,NoGravity:1b,Invul:0,PersistenceRequired:1b,CustomName:\"Maxor\"}",
                    at[0] + 4, at[1], at[2]));
            c.waitUntil("the client to have both withers", mc -> mc.level.getEntitiesOfClass(WitherBoss.class, box).size() == 2, 200);
            c.ctx().waitTicks(5);
            WitherBoss[] w = c.onClient(mc -> {
                List<WitherBoss> all = mc.level.getEntitiesOfClass(WitherBoss.class, box);
                WitherBoss born = all.stream().filter(e -> !e.hasCustomName()).findFirst().orElseThrow();
                WitherBoss boss = all.stream().filter(e -> e.hasCustomName()).findFirst().orElseThrow();
                return new WitherBoss[]{born, boss};
            });
            int[] invul = c.onClient(mc -> new int[]{w[0].getInvulnerableTicks(), w[1].getInvulnerableTicks()});
            c.note("client invulnerable ticks: Witherborn-like " + invul[0] + ", boss " + invul[1]);
            c.check(invul[0] == 800 && invul[1] != 800, "premise: the server did not keep Invul 800 / 0");

            Mod.call(cfg, "setHideWitherborn", false);
            boolean[] off = c.onClient(mc -> new boolean[]{
                    (Boolean) Mod.staticCall("objecthider.ObjectHiderFeature", "shouldHideEntity", w[0]),
                    (Boolean) Mod.staticCall("objecthider.ObjectHiderFeature", "shouldHideEntity", w[1])});
            Mod.call(cfg, "setHideWitherborn", true);
            boolean[] on = c.onClient(mc -> new boolean[]{
                    (Boolean) Mod.staticCall("objecthider.ObjectHiderFeature", "shouldHideEntity", w[0]),
                    (Boolean) Mod.staticCall("objecthider.ObjectHiderFeature", "shouldHideEntity", w[1])});
            // Particles with only the Invul-800 wither left: a boss wither's own head smoke is not hidden (and must not
            // be), so it would count on both sides.
            boolean bossParticles = c.onClient(mc -> !(Boolean) Mod.staticCall("objecthider.ObjectHiderFeature",
                    "hidesWitherParticles", w[1]));
            c.check(bossParticles, "the boss wither's particles would be dropped");
            c.server().command("kill @e[tag=tk1101boss]");
            c.waitUntil("the boss wither gone", mc -> mc.level.getEntitiesOfClass(WitherBoss.class, box).size() == 1, 200);
            Mod.call(cfg, "setHideWitherborn", false);
            int particlesOff = particleGrowth(c, 40);
            Mod.call(cfg, "setHideWitherborn", true);
            int particlesOn = particleGrowth(c, 40);
            c.note("option off: hide born " + off[0] + ", boss " + off[1] + ", particle count +" + particlesOff
                    + "; option on: hide born " + on[0] + ", boss " + on[1] + ", particle count +" + particlesOn);
            c.check(!off[0] && !off[1], "something hidden with the option off");
            c.check(on[0] && !on[1], "option on: Witherborn hidden " + on[0] + ", boss hidden " + on[1]);
            c.check(particlesOff > 20, "premise: the Invul-800 wither made no particles with the option off (+" + particlesOff + ")");
            c.check(particlesOn * 5 < particlesOff, "its particles were not dropped: +" + particlesOn + " vs +" + particlesOff);
            c.onClient(mc -> {
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        new net.minecraft.world.phys.Vec3(at[0] + 2, at[1] + 1.5, at[2]));
                return null;
            });
            c.ctx().waitTicks(5);
            shot(c, "hidden");
            Mod.call(cfg, "setHideWitherborn", false);
            c.ctx().waitTicks(30);
            shot(c, "shown");
        } finally {
            c.server().command("kill @e[tag=tk1101]");
            Mod.call(cfg, "setHideWitherborn", was);
            Mod.call(cfg, "setObjectHiderEnabled", wasMaster);
        }
    }

    /** How much the client's particle count grew over {@code ticks}; the engine's count is read from its debug string. */
    private static int particleGrowth(Session c, int ticks) {
        c.onClient(mc -> {
            mc.particleEngine.clearParticles();
            return null;
        });
        c.ctx().waitTicks(ticks);
        String count = c.onClient(mc -> mc.particleEngine.countParticles());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(count == null ? "" : count);
        if (!m.find()) {
            c.note("unreadable particle count '" + count + "'");
            return -1;
        }
        return Integer.parseInt(m.group());
    }

    // ---- 1101-menu-modtag-styles -----------------------------------------------------------------------------------

    static void modTag(Session c) throws Exception {
        Object cfg = Mod.cfg(MUC);
        Object wasStyle = Mod.call(cfg, "getTagStyle");
        boolean wasShare = (Boolean) Mod.call(cfg, "isShare");
        Object sup = Mod.cfg("supporters.SupportersConfig");
        boolean wasF5 = (Boolean) Mod.call(sup, "isOwnNameInF5");
        Object wasCamera = c.onClient(mc -> mc.options.getCameraType());
        try {
            String[][] want = {{"MARK", "K "}, {"EMBER", "✦ "}, {"CLASSIC", "◆ "}};
            for (String[] w : want) {
                Object style = Mod.enumValue("modusers.ModUserTagStyle", w[0]);
                Component legit = (Component) Mod.call(style, "prefix", "legit");
                Component cheat = (Component) Mod.call(style, "prefix", "cheat");
                c.note(w[0] + ": '" + legit.getString() + "' legit " + styleColours(legit) + ", cheat " + styleColours(cheat));
                c.check(legit.getString().equals(w[1]) && cheat.getString().equals(w[1]), w[0] + " text " + legit.getString());
                c.check(!styleColours(legit).equals(styleColours(cheat)), w[0] + " does not tell legit from cheat");
            }
            Object mark = Mod.enumValue("modusers.ModUserTagStyle", "MARK");
            List<String> markColours = styleColours((Component) Mod.call(mark, "prefix", "legit"));
            c.check(markColours.contains("ff8a1f"), "the K is not in the mod's orange: " + markColours);
            // The removed [K560] badge (a saved BADGE) and anything unknown come back as the K.
            c.check(mark == Mod.staticCall("modusers.ModUserTagStyle", "byName", "BADGE")
                    && mark == Mod.staticCall("modusers.ModUserTagStyle", "byName", "nonsense"), "an old or unknown style is not the K");
            // Every tag is one glyph, and the name after it keeps its own color (it inherited the tag's at first).
            for (String[] w : want) {
                Object style = Mod.enumValue("modusers.ModUserTagStyle", w[0]);
                Component tagged = (Component) Mod.call(style, "tag", "legit", Component.literal("Player0"));
                List<String> nameColours = new ArrayList<>();
                tagged.visit((st, text) -> {
                    if (text.contains("Player0")) {
                        nameColours.add(st.getColor() == null ? "none" : Integer.toHexString(st.getColor().getValue()));
                    }
                    return java.util.Optional.empty();
                }, Style.EMPTY);
                c.check(w[1].trim().codePointCount(0, w[1].trim().length()) == 1, w[0] + " is not one glyph");
                c.check(nameColours.equals(List.of("none")), w[0] + " colors the name: " + nameColours);
            }

            // The name tag hook draws the chosen style on your own tag (My Name in F5), and each style gets a picture.
            c.onClient(mc -> {
                Mod.call(cfg, "setShare", true);
                Mod.call(sup, "setOwnNameInF5", true);
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                return null;
            });
            for (String[] w : want) {
                Object style = Mod.enumValue("modusers.ModUserTagStyle", w[0]);
                c.onClient(mc -> {
                    Mod.call(cfg, "setTagStyle", style);
                    return null;
                });
                String tag = c.onClient(mc -> {
                    try {
                        var renderer = mc.getEntityRenderDispatcher().getRenderer(mc.player);
                        java.lang.reflect.Method m = null;
                        for (Class<?> k = renderer.getClass(); k != null && m == null; k = k.getSuperclass()) {
                            for (java.lang.reflect.Method cand : k.getDeclaredMethods()) {
                                if (cand.getName().equals("getNameTag") && cand.getParameterCount() == 1) {
                                    m = cand;
                                }
                            }
                        }
                        m.setAccessible(true);
                        Object out = m.invoke(renderer, mc.player);
                        return out == null ? "null" : ((Component) out).getString();
                    } catch (ReflectiveOperationException e) {
                        return "error " + e;
                    }
                });
                c.note(w[0] + " own name tag: '" + tag + "'");
                c.check(tag.startsWith(w[1]), w[0] + " name tag reads " + tag);
                c.ctx().waitTicks(4);
                shot(c, "f5-" + w[0].toLowerCase(java.util.Locale.ROOT));
            }
            // Saved and loaded back.
            c.onClient(mc -> {
                Mod.call(cfg, "setTagStyle", Mod.enumValue("modusers.ModUserTagStyle", "EMBER"));
                Mod.call(cfg, "save");
                Mod.staticCall(MUC, "load");
                return null;
            });
            c.check(Mod.call(Mod.cfg(MUC), "getTagStyle") == Mod.enumValue("modusers.ModUserTagStyle", "EMBER"),
                    "Tag Style did not survive a reload");
        } finally {
            Object now = Mod.cfg(MUC);
            Mod.call(now, "setTagStyle", wasStyle);
            Mod.call(now, "setShare", wasShare);
            Mod.call(now, "save");
            Mod.call(sup, "setOwnNameInF5", wasF5);
            c.onClient(mc -> {
                mc.options.setCameraType((net.minecraft.client.CameraType) wasCamera);
                return null;
            });
        }
    }

    private static List<String> styleColours(Component c) {
        List<String> out = new ArrayList<>();
        c.visit((style, text) -> {
            if (!text.isBlank()) {
                out.add(style.getColor() == null ? "none" : Integer.toHexString(style.getColor().getValue()));
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }
}
