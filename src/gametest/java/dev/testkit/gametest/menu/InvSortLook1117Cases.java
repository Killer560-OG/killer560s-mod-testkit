package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * invsorttex-1117 (killer560 2026-11-17: a saved Spirit Sceptre drew as paper in the layouts list when not held):
 * <ul>
 *   <li>1117-invsort-look-absent: a layout saves a skull, a dyed chestplate, a custom-model item and a plain tool; the
 *       inventory is emptied and the layout loaded: every red placeholder has the held stack's item and the held
 *       stack's profile / item model / dye (the tint is drawn over it, unchanged); the file keeps "looks".</li>
 *   <li>1117-invsort-look-oldfile: an old-format file (no "looks") loads, nothing throws, and a SkyBlock id the
 *       catalog or Pack Disabler's table knows resolves to that look instead of the saved vanilla icon.</li>
 * </ul>
 */
final class InvSortLook1117Cases {

    private static final String STORE = "invsort.InventoryLayoutStore";
    private static final String SCREEN = "invsort.InventorySorterScreen";

    private InvSortLook1117Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1117-invsort-look-absent", InvSortLook1117Cases::absent);
        MenuSuite.test(s, "1117-invsort-look-oldfile", InvSortLook1117Cases::oldFile);
    }

    private static Object store() {
        return Mod.staticCall(STORE, "getInstance");
    }

    private static Object openScreen(Session c) {
        c.ctx().runOnClient(mc -> {
            try {
                McCompat.setScreen(mc, (Screen) Mod.cls(SCREEN).getConstructor(Screen.class).newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        c.waitUntil("the layouts menu", mc -> McCompat.screen(mc) != null
                && McCompat.screen(mc).getClass().getSimpleName().equals("InventorySorterScreen"), 40);
        c.ctx().waitTicks(3);
        return c.onClient(mc -> McCompat.screen(mc));
    }

    private static ItemStack held(int slot) {
        switch (slot) {
            case 0: {
                ItemStack s = new ItemStack(Items.PLAYER_HEAD);
                s.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved("InvSortLookHx"));
                s.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Hx Head"));
                return withId(s, "HX_LOOK_HEAD");
            }
            case 1: {
                ItemStack s = new ItemStack(Items.LEATHER_CHESTPLATE);
                s.set(DataComponents.DYED_COLOR, new DyedItemColor(0x336699));
                return withId(s, "HX_LOOK_DYED");
            }
            case 2: {
                ItemStack s = new ItemStack(Items.PAPER);
                s.set(DataComponents.ITEM_MODEL, Identifier.parse("minecraft:bow"));
                return withId(s, "HX_LOOK_MODEL");
            }
            default:
                return withId(new ItemStack(Items.DIAMOND_PICKAXE), "HX_LOOK_TOOL");
        }
    }

    private static ItemStack withId(ItemStack s, String id) {
        net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
        t.putString("id", id);
        t.putString("uuid", java.util.UUID.nameUUIDFromBytes(id.getBytes()).toString());
        s.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
        return s;
    }

    static void absent(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only");
            return;
        }
        String name = "hx-1117-look";
        c.ctx().runOnClient(mc -> {
            Mod.call(store(), "delete", name);
            for (int i = 0; i < 4; i++) {
                mc.player.getInventory().setItem(i, held(i));
            }
        });
        Object scr = openScreen(c);
        c.check(scr != null, "the layouts screen did not open");
        String file = c.onClient(mc -> {
            Object layout = Mod.call(scr, "toLayout", name);
            Mod.call(store(), "save", layout);
            try {
                return Files.readString(((Path) Mod.staticCall(STORE, "directory")).resolve(name + ".json"));
            } catch (Exception e) {
                return "ERR " + e;
            }
        });
        c.check(file.contains("\"looks\""), "the saved layout file has no looks: " + file.substring(0, Math.min(300, file.length())));
        // Empty the inventory, then reopen the screen and load the layout: every slot is now a placeholder.
        c.ctx().runOnClient(mc -> {
            for (int i = 0; i < 36; i++) {
                mc.player.getInventory().setItem(i, ItemStack.EMPTY);
            }
            Mod.cls(STORE); // keep the class referenced
        });
        Object scr2 = openScreen(c);
        c.ctx().runOnClient(mc -> Mod.call(scr2, "loadLayout", Mod.call(store(), "get", name)));
        int checked = 0;
        for (int i = 0; i < 4; i++) {
            final int slot = i;
            ItemStack want = held(i);
            ItemStack got = c.onClient(mc -> (ItemStack) Mod.call(scr2, "gridAt", slot));
            c.check(got != null && !got.isEmpty(), "slot " + slot + " drew nothing");
            c.check(got.getItem() == want.getItem(), "slot " + slot + " item " + got.getItem() + " != held " + want.getItem());
            c.check(got.getHoverName().getString().contains("not in"), "slot " + slot + " is not drawn as a missing placeholder: " + got.getHoverName().getString());
            c.check(java.util.Objects.equals(got.get(DataComponents.ITEM_MODEL), want.get(DataComponents.ITEM_MODEL)),
                    "slot " + slot + " item model " + got.get(DataComponents.ITEM_MODEL) + " != " + want.get(DataComponents.ITEM_MODEL));
            c.check(java.util.Objects.equals(got.get(DataComponents.DYED_COLOR), want.get(DataComponents.DYED_COLOR)),
                    "slot " + slot + " dye differs");
            c.check(java.util.Objects.equals(got.get(DataComponents.PROFILE) == null, want.get(DataComponents.PROFILE) == null),
                    "slot " + slot + " skull profile presence differs");
            checked++;
        }
        c.check(checked == 4, "only " + checked + " slots checked");
        Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-absent"));
        c.note("picture " + shot);
        c.ctx().runOnClient(mc -> {
            Mod.call(store(), "delete", name);
            McCompat.setScreen(mc, null);
        });
    }

    static void oldFile(Session c) throws Exception {
        MenuKit.reset(c);
        InventoryCases.clearInv(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: the sorter is cheat-only");
            return;
        }
        String name = "hx-1117-old";
        String json = "{\"version\":1,\"name\":\"" + name + "\",\"slots\":{\"0\":\"BAT_WAND\",\"1\":\"HX_NOT_A_REAL_ITEM\","
                + "\"2\":\"HYPERION\"},\"icons\":{\"0\":\"minecraft:paper\",\"1\":\"minecraft:bogus_item\",\"2\":\"minecraft:iron_sword\"},"
                + "\"counts\":{\"0\":1,\"1\":1,\"2\":1},\"fetchFromStorage\":false}";
        Path dir = c.onClient(mc -> (Path) Mod.staticCall(STORE, "directory"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), json);
        try {
            c.ctx().runOnClient(mc -> Mod.staticCall(STORE, "reload"));
            Object scr = openScreen(c);
            c.ctx().runOnClient(mc -> Mod.call(scr, "loadLayout", Mod.call(store(), "get", name)));
            ItemStack unknown = c.onClient(mc -> (ItemStack) Mod.call(scr, "gridAt", 1));
            c.check(unknown != null && !unknown.isEmpty(), "an unknown item drew nothing");
            ItemStack iron = c.onClient(mc -> (ItemStack) Mod.call(scr, "gridAt", 2));
            ItemStack scepter = c.onClient(mc -> (ItemStack) Mod.call(scr, "gridAt", 0));
            c.check(iron != null && !iron.isEmpty() && scepter != null && !scepter.isEmpty(), "known ids drew nothing");
            // Acted: what the table knows for HYPERION must be on the placeholder (model or vanilla item).
            Identifier model = c.onClient(mc -> (Identifier) Mod.staticCall("packdisabler.ItemLooks", "modelFor", "HYPERION"));
            Identifier got = iron.get(DataComponents.ITEM_MODEL);
            if (model != null) {
                c.check(model.equals(got), "HYPERION placeholder model " + got + " != the table's " + model);
            } else {
                c.note("the look table knows no HYPERION in this run; only 'nothing throws' was checked for it");
            }
            // BAT_WAND is the Spirit Sceptre: Hypixel paper, drawn through its look, never as plain paper.
            Identifier sceptreModel = c.onClient(mc -> (Identifier) Mod.staticCall("packdisabler.ItemLooks", "modelFor", "BAT_WAND"));
            c.check(sceptreModel != null, "the look table has no BAT_WAND look (test data gone?)");
            c.check(sceptreModel.equals(scepter.get(DataComponents.ITEM_MODEL)) || scepter.get(DataComponents.ITEM_MODEL) != null
                    && scepter.get(DataComponents.ITEM_MODEL).getPath().contains("spirit_sceptre"),
                    "the Spirit Sceptre placeholder model is " + scepter.get(DataComponents.ITEM_MODEL) + ", wanted " + sceptreModel);
            c.note("scepter placeholder: " + scepter.getItem() + " model " + scepter.get(DataComponents.ITEM_MODEL)
                    + ", hyperion: " + iron.getItem() + " model " + got);
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(store(), "delete", name);
                McCompat.setScreen(mc, null);
            });
        }
    }
}
