package dev.testkit.gametest.ui;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.blaze3d.platform.Window;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 961 (mod lockstar-heads): killer560, 2026-10-13: "the locked item star does not appear on player heads for some
 * reason." Locks, by the REAL protect key over the hovered slot (his path), a Skyblock-style accessory head (real
 * Hypixel skin, lore, ExtraAttributes) with a UUID, one without (Item ID Fallback on), a normal item, a pack-era head
 * (hypixel_skyblock item_model, no PROFILE, drawn as a head by Pack Disabler) and a starred head worn as a helmet; then
 * reads the screenshot: every locked slot shows the star's exact pixels in its top-right corner, an unlocked head none,
 * and two hovered heads keep theirs. Passes on mod main cd358704 (26.1.2 cheat + legit, 26.2): the star DOES draw on
 * heads with this mod alone, so it guards against a regression rather than reproducing his report. Own class for merges.
 */
final class LockStarHeadCases {

    private static final String IPC = "itemprotect.ItemProtectConfig";
    private static final String PDC = "packdisabler.PackDisablerConfig";
    private static final String PACK_MODEL = "hypixel_skyblock:item/fishing/misc/blue_ring";
    private static final int MAGENTA = 0xFF00FF;
    private static final int KEY_K = 75; // GLFW_KEY_K
    /** A real Skyblock head texture (Blue Ring's, from the mod's item_looks.json), in Hypixel's own encoding:
     *  base64 of {"textures":{"SKIN":{"url":".../texture/<hash>"}}}. */
    private static final String SKIN_URL =
            "http://textures.minecraft.net/texture/4ba74e76b3a99f146db628ede6be2f3c3720daa8f6829ccc1251d9db42bc2d63";

    private LockStarHeadCases() {
    }

    static ItemStack head(String id, String uuid, String name, String rarityLine) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        String value = Base64.getEncoder().encodeToString(
                ("{\"textures\":{\"SKIN\":{\"url\":\"" + SKIN_URL + "\"}}}").getBytes(StandardCharsets.UTF_8));
        Multimap<String, Property> backing = HashMultimap.create();
        backing.put("textures", new Property("textures", value));
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)), "", new PropertyMap(backing));
        s.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        s.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Increases your §f❂ True Defense"), Component.literal("§7by §a5§7."),
                Component.literal(""), Component.literal(rarityLine))));
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        if (uuid != null) {
            t.putString("uuid", uuid);
        }
        t.putLong("timestamp", 1700000000000L);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** Is {@code rgb} the star's colour, also when the hover highlight's translucent white is blended over it? */
    static boolean starish(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= 200 && b >= 200 && r - g >= 60 && b - g >= 60;
    }

    static int starCount(BufferedImage img, int[] slot, double ppu, boolean loose) {
        int n = 0;
        int x0 = (int) Math.round(slot[0] * ppu);
        int y0 = (int) Math.round(slot[1] * ppu);
        int x1 = (int) Math.round((slot[0] + 16) * ppu);
        int y1 = (int) Math.round((slot[1] + 16) * ppu);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = InvBatchCases.rgb(img, x, y);
                if (p >= 0 && (loose ? starish(p) : p == MAGENTA)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Cells of the 5x5 star in the slot's top-right corner that are not drawn as the star (sampled at cell centres). */
    static int wrongCells(BufferedImage img, int[] slot, double ppu, String[] star, boolean loose) {
        int x0 = slot[0] + 16 - star.length;
        int y0 = slot[1];
        int wrong = 0;
        for (int r = 0; r < star.length; r++) {
            for (int col = 0; col < star[r].length(); col++) {
                boolean want = star[r].charAt(col) == '#';
                int p = InvBatchCases.rgb(img, (int) ((x0 + col + 0.5) * ppu), (int) ((y0 + r + 0.5) * ppu));
                boolean got = loose ? starish(p) : p == MAGENTA;
                if (want != got) {
                    wrong++;
                }
            }
        }
        return wrong;
    }

    /** Puts the real cursor over the centre of a slot (GUI units, screen-relative). */
    static void hover(UiCase c, int[] slot) {
        double[] a = c.onClient(mc -> {
            Window win = mc.getWindow();
            double f = win.getScreenWidth() / (double) McCompat.screen(mc).width;
            return new double[]{(slot[0] + 8) * f, (slot[1] + 8) * f};
        });
        c.ctx().getInput().setCursorPos(a[0], a[1]);
        c.ticks(3);
    }

    // ==== 961 ==========================================================================================================

    static void heads(UiCase c) throws Exception {
        Path cfgPath = InvBatchCases.configPath(c, IPC);
        byte[] old = InvBatchCases.read(cfgPath);
        int[] window = HudEditorCases.windowSize(c);
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        boolean oldPack = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(PDC), "isEnabled"));
        // Inventory slots under test, what each is, and whether the case locks it.
        int[] invSlot = {9, 10, 11, 12, 13, 39};
        String[] labels = {"head with UUID", "head without UUID (id fallback)", "normal item", "unlocked head",
                "pack-era head (Hypixel model, Pack Disabler on)", "starred head worn as helmet"};
        boolean[] lock = {true, true, true, false, true, true};
        int n = invSlot.length;
        try {
            InvBatchCases.survival(c);
            Map<Integer, ItemStack> items = new LinkedHashMap<>();
            items.put(9, head("BAT_ARTIFACT", "ls-head-uuid-1", "§5Bat Artifact", "§5§lEPIC ACCESSORY"));
            items.put(10, head("SPEED_TALISMAN", null, "§fSpeed Talisman", "§f§lCOMMON ACCESSORY"));
            items.put(11, InvBatchCases.sb(Items.DIAMOND_SWORD, "HYPERION", "ls-norm-1", 0));
            items.put(12, head("WOLF_TALISMAN", "ls-head-free", "§fWolf Talisman", "§f§lCOMMON ACCESSORY"));
            // Hypixel since its 2026 pack: the stack names a hypixel_skyblock model and carries no PROFILE; Pack
            // Disabler (on in his instances) draws it as the old player head with item_looks.json's skin.
            ItemStack pack = head("BLUE_RING", "ls-head-pack", "§9Blue Ring", "§9§lRARE ACCESSORY");
            pack.remove(DataComponents.PROFILE);
            pack.set(DataComponents.ITEM_MODEL, Identifier.parse(PACK_MODEL));
            items.put(13, pack);
            ItemStack mask = head("BONZO_MASK", "ls-head-mask", "§9Bonzo's Mask §6✪✪✪✪✪", "§9§lRARE DUNGEON HELMET");
            CompoundTag maskTag = mask.get(DataComponents.CUSTOM_DATA).copyTag();
            maskTag.putInt("upgrade_level", 5);
            mask.set(DataComponents.CUSTOM_DATA, CustomData.of(maskTag));
            items.put(39, mask);
            InvBatchCases.inventory(c, items);
            c.onClient(mc -> {
                Object cfg = Mod.cfg(IPC);
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setProtectItemEnabled", true);
                Mod.call(cfg, "setProtectStarredEnabled", true);
                Mod.call(cfg, "setUseItemIdFallback", true);
                Mod.call(cfg, "setLockInPlace", false);
                Mod.call(cfg, "setProtectKey", KEY_K);
                Mod.call(cfg, "setProtectedColor", 0xFF000000 | MAGENTA);
                return null;
            });
            PackDisablerCases.setEnabled(c, true);
            String[] star = (String[]) Mod.field("itemprotect.ItemProtectFeature", "STAR");
            int cells = 0;
            for (String row : star) {
                cells += (int) row.chars().filter(ch -> ch == '#').count();
            }
            HudEditorCases.setWindow(c, 1280, 720, 2);
            c.onClient(mc -> {
                McCompat.setScreen(mc, new InventoryScreen(mc.player));
                return null;
            });
            c.ticks(40); // the heads' skins resolve asynchronously
            int[][] pos = c.onClient(mc -> {
                AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) McCompat.screen(mc);
                int left = (Integer) Mod.field(s, "leftPos");
                int top = (Integer) Mod.field(s, "topPos");
                int[][] out = new int[n][];
                for (Slot slot : s.getMenu().slots) {
                    for (int i = 0; i < n; i++) {
                        if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == invSlot[i]) {
                            out[i] = new int[]{left + slot.x, top + slot.y};
                        }
                    }
                }
                return out;
            });
            for (int i = 0; i < n; i++) {
                c.check(pos[i] != null, "no screen slot for inventory slot " + invSlot[i]);
            }
            String drawnPack = c.onClient(mc -> String.valueOf(Mod.staticCall("packdisabler.PackDisabler", "resolveModel",
                    mc.player.getInventory().getItem(13), Identifier.parse(PACK_MODEL))));
            c.note("pack-era head drawn with model " + drawnPack);
            c.check(drawnPack.contains("player_head"), "Pack Disabler did not turn the pack-era item into a player head: " + drawnPack);

            // Lock them by the real protect key over the hovered slot, the way he does.
            long mark = LogTap.mark();
            for (int i = 0; i < n; i++) {
                if (lock[i]) {
                    hover(c, pos[i]);
                    c.ctx().getInput().pressKey(KEY_K);
                    c.ticks(8); // 400 ms: past the feature's 250 ms key-repeat guard for the same key
                }
            }
            String keys = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(IPC), "getProtectedKeys")));
            List<String> chat = LogTap.since(mark).stream().filter(l -> l.contains("Protect")).toList();
            c.note("after the protect key: keys " + keys + "; chat " + chat);
            for (int i = 0; i < n; i++) {
                final int slot = invSlot[i];
                boolean explicit = c.onClient(mc -> (Boolean) Mod.staticCall("itemprotect.ItemProtect", "isExplicitlyProtected",
                        mc.player.getInventory().getItem(slot)));
                c.note(labels[i] + ": isExplicitlyProtected " + explicit);
                if (explicit != lock[i]) {
                    c.problem(labels[i] + ": protected " + explicit + ", want " + lock[i] + " (keys " + keys + ")");
                }
            }

            // Unhovered: exact pixels.
            c.ctx().getInput().setCursorPos(2, 2);
            c.ticks(3);
            BufferedImage img = InvBatchCases.shot(c, "unhovered");
            double ppu = img.getWidth() / (double) c.onClient(mc -> McCompat.screen(mc).width);
            int want = (int) Math.round(cells * ppu * ppu);
            int total = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if (InvBatchCases.rgb(img, x, y) == MAGENTA) {
                        total++;
                    }
                }
            }
            int locked = 0;
            for (int i = 0; i < n; i++) {
                int got = starCount(img, pos[i], ppu, false);
                int wrong = wrongCells(img, pos[i], ppu, star, false);
                c.note(String.format(Locale.ROOT, "unhovered %s: star px %d (want %d), cells not the star %d",
                        labels[i], got, lock[i] ? want : 0, lock[i] ? wrong : 0));
                if (lock[i]) {
                    locked++;
                    if (got != want || wrong != 0) {
                        c.problem(labels[i] + ": star " + got + " px, want " + want + "; " + wrong + " cell(s) not the star");
                    }
                } else if (got != 0) {
                    c.problem(labels[i] + " shows a star (" + got + " px)");
                }
            }
            c.note("star-colored px on the whole screen " + total + " (want " + locked * want + ")");
            if (total != locked * want) {
                c.problem("star-colored px elsewhere on screen: " + (total - locked * want));
            }

            // Hovered: each locked head keeps its star under the slot highlight; the tooltip opens beside it.
            for (int i : new int[]{0, 4}) {
                hover(c, pos[i]);
                BufferedImage hov = InvBatchCases.shot(c, "hovered-" + invSlot[i]);
                int hovGot = starCount(hov, pos[i], ppu, true);
                int hovWrong = wrongCells(hov, pos[i], ppu, star, true);
                c.note("hovered " + labels[i] + ": star-like px " + hovGot + " (want " + want + "), cells not the star " + hovWrong);
                if (hovGot < want || hovWrong != 0) {
                    c.problem("hovered " + labels[i] + ": star " + hovGot + " px, want " + want + "; " + hovWrong + " cell(s) not the star");
                }
            }
            c.check(c.problemCount() == 0, c.problemCount() + " problem(s), see above");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg(PDC), "setEnabled", oldPack);
                return null;
            });
            HudEditorCases.restoreWindow(c, window, oldGui);
            InvBatchCases.restoreConfig(c, IPC, cfgPath, old);
            InvBatchCases.inventory(c, Map.of());
        }
    }
}
