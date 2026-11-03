package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Pack Disabler (mod {@code packdisabler/}, 2026-10-07): SkyBlock items drawn with their pre-resource-pack look.
 *
 * <ul>
 *   <li>427-ui-pack-disabler-models: real SkyBlock stacks (base item, {@code custom_data.id} and Hypixel's
 *       {@code item_model}, taken from NotEnoughUpdates' item NBT) go through vanilla's own
 *       {@code ItemModelResolver.updateForTopItem}, and the sprite it picked is read off the render state. ON: an item
 *       whose old look was a vanilla item draws exactly that item's sprite (compared with a plain stack of it through
 *       the same resolver), an old head draws the player-head model with the old skin's texture hash, an item with no
 *       old look draws our texture - never missingno, never paper. OFF: every one of them draws what Hypixel's model
 *       gives in this client (missingno, the pack is not loaded here) - nothing changes. A vanilla-model SkyBlock item
 *       and an id the table does not know are untouched either way.</li>
 *   <li>428-ui-pack-disabler-pack-push: a SkyBlock resource-pack push goes through the client's real packet handler
 *       (on the render thread, not over a socket): with the setting on and off alike, vanilla's own pack prompt opens.
 *       Pack Disabler never declines, cancels or answers Hypixel's pack (SkyBlock requires it).</li>
 *   <li>429-ui-pack-disabler-missing: removed with /k560textures and the missing-texture list (mod cmdclean-1101).</li>
 *   <li>430-ui-pack-disabler-screens: a chest of affected items at GUI scale 2, ON and OFF, screenshots.</li>
 *   <li>431-ui-pack-disabler-user-pack: a resource pack the player selects that replaces {@code wheat_seeds} and one
 *       of OUR textures wins for both: a Pack-Disabler-resolved Seeds item and Bee Saliva read the pack's pixels.</li>
 *   <li>432-ui-pack-disabler-bazaar: the Bazaar icon for every product comes from the same table (one answer).</li>
 *   <li>433-ui-pack-disabler-refresh (mod packdisabler-1103): 24 items from the 2026-11-03 refresh (Great Spook, Bingo,
 *       0.27.2 Reforge and Power Stones, renamed-model items) plus the 427 set draw their expected old item, head
 *       skin or our texture, with the SkyBlock id AND as a menu shows them (Hypixel's model, no id); every table row
 *       on a pack model resolves to a look; a plain vanilla stack and an unknown model are untouched; OFF changes
 *       nothing.</li>
 *   <li>434-ui-pack-disabler-menu-maps: Paper Menu Buttons. On 26.3 a map / filled map button with no SkyBlock id and
 *       no map data draws paper in a GUI slot (Create Sell Offer, Create Buy Order); the same stack in a hand, a real
 *       map with map data, a map with a SkyBlock id, the sub-option OFF and Pack Disabler OFF all keep the map. On
 *       26.2 (maps still the old flat sheet) nothing changes.</li>
 * </ul>
 */
final class PackDisablerCases {

    private static final String CFG = "packdisabler.PackDisablerConfig";
    static final String MISSINGNO = "minecraft:missingno";

    /** id, base item, Hypixel item_model (from NotEnoughUpdates-REPO master), expected look. */
    record Fixture(String id, Item base, String model, Item expectItem, String expectSkin, String expectOwn) {
    }

    static List<Fixture> fixtures() {
        return List.of(
                new Fixture("HYPERION", Items.IRON_SWORD, "hypixel_skyblock:item/uncategorized/hyperion",
                        Items.IRON_SWORD, null, null),
                new Fixture("ASPECT_OF_THE_VOID", Items.DIAMOND_SHOVEL,
                        "hypixel_skyblock:item/slayer/enderman/aspect_of_the_void", Items.DIAMOND_SHOVEL, null, null),
                new Fixture("TERMINATOR", Items.BOW, "hypixel_skyblock:item/slayer/enderman/weapons/terminator",
                        Items.BOW, null, null),
                new Fixture("DUNGEON_CHEST_KEY", Items.PAPER, "hypixel_skyblock:item/uncategorized/dungeon_chest_key",
                        Items.TRIPWIRE_HOOK, null, null),
                new Fixture("UMBER", Items.PAPER, "hypixel_skyblock:item/glacite/umber/umber", null,
                        "b565b5aa83d4aa7f7af22dc1271b2f0b27441f9ac1495f6b4653cf68dfb105ef", null),
                new Fixture("ROUGH_JADE_GEM", Items.PAPER,
                        "hypixel_skyblock:item/collections/gemstone/jade/rough_jade_gem", null,
                        "3b4c2afd544d0a6139e6ae8ef8f0bfc09a9fd837d0cad4f5cd0fe7f607b7d1a0", null),
                new Fixture("BEE_SALIVA", Items.PAPER, "hypixel_skyblock:item/island_relevant/foraging_3/bee_saliva",
                        null, null, "bee_saliva"),
                new Fixture("GIANT_SLOTH_CLAW", Items.PAPER,
                        "hypixel_skyblock:item/island_relevant/foraging_3/petupgrades/sloth_claw_giant", null, null,
                        "giant_sloth_claw"),
                new Fixture("HONEYCOMB_RING", Items.PAPER,
                        "hypixel_skyblock:item/island_relevant/foraging_3/accessories/honey/honeycomb_ring", null, null,
                        "honeycomb_ring"));
    }

    private PackDisablerCases() {
    }

    static ItemStack skyblockStack(Item base, String id, String model) {
        ItemStack s = new ItemStack(base);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (model != null) {
            s.set(DataComponents.ITEM_MODEL, Identifier.parse(model));
        }
        s.set(DataComponents.CUSTOM_NAME, Component.literal(id));
        return s;
    }

    /** What vanilla's resolver draws for {@code stack} in a GUI slot: "sprite name" or "head:<skin hash>". */
    static String drawn(Minecraft mc, ItemStack stack) {
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
        String head = headSkin(state);
        if (head != null) {
            return "head:" + head;
        }
        var particle = state.pickParticleMaterial(RandomSource.create(0));
        if (particle == null) {
            return state.isEmpty() ? "(empty)" : "(no sprite)";
        }
        return particle.sprite().contents().name().toString();
    }

    /** The texture hash of a player-head layer's skin, "" for a head without one, or null when no head is drawn. */
    static String headSkin(ItemStackRenderState state) {
        try {
            Object[] layers = (Object[]) Mod.field(state, "layers");
            int count = (Integer) Mod.field(state, "activeLayerCount");
            for (int i = 0; i < count; i++) {
                Object arg = Mod.field(layers[i], "argumentForSpecialRendering");
                if (arg != null && arg.getClass().getName().endsWith("PlayerSkinRenderCache$RenderInfo")) {
                    Object profile = Mod.call(arg, "gameProfile");
                    Object props = Mod.call(profile, "properties");
                    Collection<?> textures = (Collection<?>) props.getClass().getMethod("get", Object.class)
                            .invoke(props, "textures");
                    for (Object p : textures) {
                        String value = (String) p.getClass().getMethod("value").invoke(p);
                        String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                        int at = json.indexOf("/texture/");
                        return at < 0 ? json : json.substring(at + 9).replaceAll("[^0-9a-f].*$", "");
                    }
                    return "";
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the render state's layers: " + e, e);
        }
        return null;
    }

    static void setEnabled(UiCase c, boolean on) {
        c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            Mod.call(cfg, "setEnabled", on);
            return null;
        });
        boolean active = c.onClient(mc -> (Boolean) Mod.staticCall("packdisabler.PackDisabler", "isActive"));
        c.check(active == on, "Pack Disabler isActive() = " + active + " after setEnabled(" + on + ")");
    }

    static boolean savedEnabled() {
        return (Boolean) Mod.call(Mod.cfg(CFG), "isEnabled");
    }

    // ---- 427 ------------------------------------------------------------------------------------------------------

    static void models(UiCase c) {
        boolean old = c.onClient(mc -> savedEnabled());
        try {
            int rows = c.onClient(mc -> (Integer) Mod.staticCall("packdisabler.ItemLooks", "size"));
            c.note("shared item table: " + rows + " rows");
            c.check(rows > 2500, "item table has only " + rows + " rows");

            setEnabled(c, true);
            for (Fixture f : fixtures()) {
                String got = c.onClient(mc -> drawn(mc, skyblockStack(f.base(), f.id(), f.model())));
                String want;
                if (f.expectItem() != null) {
                    want = c.onClient(mc -> drawn(mc, new ItemStack(f.expectItem())));
                } else if (f.expectSkin() != null) {
                    want = "head:" + f.expectSkin();
                } else {
                    want = "killer560smod:item/packdisabler/" + f.expectOwn();
                }
                c.note("ON  " + f.id() + " -> " + got + " (want " + want + ")");
                c.check(got.equals(want), f.id() + " drew " + got + ", expected " + want);
                c.check(!got.equals(MISSINGNO) && !got.equals("minecraft:item/paper"), f.id() + " drew " + got);
            }
            // A SkyBlock item already on a vanilla model, and an id nobody knows, are left alone.
            String seedsOn = c.onClient(mc -> drawn(mc, skyblockStack(Items.WHEAT_SEEDS, "ENCHANTED_SEEDS",
                    "minecraft:wheat_seeds")));
            String unknownOn = c.onClient(mc -> drawn(mc, skyblockStack(Items.PAPER, "TESTKIT_UNKNOWN_427",
                    "hypixel_skyblock:item/testkit/unknown")));

            setEnabled(c, false);
            for (Fixture f : fixtures()) {
                String got = c.onClient(mc -> drawn(mc, skyblockStack(f.base(), f.id(), f.model())));
                c.note("OFF " + f.id() + " -> " + got);
                c.check(got.equals(MISSINGNO), f.id() + " with the setting OFF drew " + got
                        + " - expected Hypixel's own (unloaded) model, i.e. unchanged");
            }
            String seedsOff = c.onClient(mc -> drawn(mc, skyblockStack(Items.WHEAT_SEEDS, "ENCHANTED_SEEDS",
                    "minecraft:wheat_seeds")));
            String unknownOff = c.onClient(mc -> drawn(mc, skyblockStack(Items.PAPER, "TESTKIT_UNKNOWN_427",
                    "hypixel_skyblock:item/testkit/unknown")));
            c.note("Enchanted Seeds on/off: " + seedsOn + " / " + seedsOff + "; unknown id on/off: " + unknownOn + " / "
                    + unknownOff);
            c.check(seedsOn.equals(seedsOff) && seedsOn.endsWith("wheat_seeds"), "vanilla-model item changed: "
                    + seedsOn + " / " + seedsOff);
            c.check(unknownOn.equals(unknownOff), "unknown id changed: " + unknownOn + " / " + unknownOff);
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                return null;
            });
        }
    }

    // ---- 428 ------------------------------------------------------------------------------------------------------

    static void packPush(UiCase c) {
        boolean old = c.onClient(mc -> savedEnabled());
        try {
            List<String> seen = new ArrayList<>();
            for (boolean on : new boolean[]{true, false}) {
                setEnabled(c, on);
                String screen = c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    ClientboundResourcePackPushPacket push = new ClientboundResourcePackPushPacket(UUID.randomUUID(),
                            "https://resourcepacks.hypixel.net/SkyBlock/00000000-0000-0000-0000-000000000000/84.zip",
                            "0000000000000000000000000000000000000000", true,
                            Optional.of(Component.literal("testkit 428")));
                    mc.getConnection().handleResourcePackPush(push);
                    Screen s = McCompat.screen(mc);
                    return s == null ? "(none)" : s.getClass().getName();
                });
                c.note("setting " + (on ? "ON" : "OFF") + ": after a SkyBlock pack push the screen is " + screen);
                c.check(screen.contains("PackConfirmScreen") || screen.contains("ConfirmScreen"),
                        "with the setting " + (on ? "ON" : "OFF") + " vanilla's pack prompt did not open (" + screen
                                + ") - something intercepted the push");
                seen.add(screen);
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
                c.ticks(2);
            }
            c.check(seen.size() == 2 && seen.get(0).equals(seen.get(1)), "ON and OFF handled the push differently: "
                    + seen);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                return null;
            });
        }
    }

    // ---- 430 ------------------------------------------------------------------------------------------------------

    static void screens(UiCase c) {
        boolean old = c.onClient(mc -> savedEnabled());
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        try {
            c.ctx().getInput().resizeWindow(1280, 720);
            c.ticks(3);
            c.onClient(mc -> {
                mc.options.guiScale().set(2);
                mc.resizeGui();
                return null;
            });
            List<ItemStack> stacks = c.onClient(mc -> chestStacks());
            c.note(stacks.size() + " affected SkyBlock items in the chest");
            for (boolean on : new boolean[]{true, false}) {
                setEnabled(c, on);
                Screen s = c.onClient(mc -> {
                    SimpleContainer box = new SimpleContainer(54);
                    for (int i = 0; i < stacks.size() && i < 54; i++) {
                        box.setItem(i, stacks.get(i));
                    }
                    ContainerScreen screen = new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                            mc.player.getInventory(), Component.literal("Pack Disabler " + (on ? "ON" : "OFF")));
                    McCompat.setScreen(mc, screen);
                    return screen;
                });
                c.ticks(10);
                Frames.Drawn d = c.onClient(mc -> Frames.extract(mc, s, -1, -1));
                c.note((on ? "ON" : "OFF") + ": drawn " + d);
                c.check(d.items() >= stacks.size(), (on ? "ON" : "OFF") + ": only " + d.items() + " of "
                        + stacks.size() + " items drawn");
                // Count what each slot really resolves to while the screen is up.
                int missing = c.onClient(mc -> {
                    int n = 0;
                    for (ItemStack st : stacks) {
                        if (drawn(mc, st).equals(MISSINGNO)) {
                            n++;
                        }
                    }
                    return n;
                });
                c.note((on ? "ON" : "OFF") + ": " + missing + " of " + stacks.size() + " resolve to missingno");
                if (on) {
                    c.check(missing == 0, missing + " items still draw missingno with Pack Disabler ON");
                } else {
                    c.check(missing == stacks.size(), "OFF: expected all " + stacks.size()
                            + " on Hypixel's (unloaded) models, got " + (stacks.size() - missing) + " changed");
                }
                HudEditorCases.screenshot(c, on ? "chest-on-gui2" : "chest-off-gui2");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                mc.options.guiScale().set(oldGui);
                return null;
            });
            c.ctx().getInput().resizeWindow(oldWindow[0], oldWindow[1]);
            c.ticks(5);
            c.onClient(mc -> {
                mc.resizeGui();
                return null;
            });
        }
    }

    /** Every Hypixel-pack-model item the shared table has our texture for, plus the fixtures; capped at 54. */
    @SuppressWarnings("unchecked")
    static List<ItemStack> chestStacks() {
        List<ItemStack> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Fixture f : fixtures()) {
            out.add(skyblockStack(f.base(), f.id(), f.model()));
            ids.add(f.id());
        }
        try (InputStream in = PackDisablerCases.class.getResourceAsStream(
                "/assets/killer560smod/skyblock/item_looks.json")) {
            var items = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("items");
            java.util.TreeMap<String, com.google.gson.JsonElement> sorted = new java.util.TreeMap<>();
            for (var e : items.entrySet()) {
                sorted.put(e.getKey(), e.getValue());
            }
            for (var e : sorted.entrySet()) {
                var o = e.getValue().getAsJsonObject();
                if (out.size() >= 54) {
                    break;
                }
                if (o.has("m") && o.has("o") && !ids.contains(e.getKey()) && !o.has("s") && !o.has("i")) {
                    out.add(skyblockStack(Items.PAPER, e.getKey(), o.get("m").getAsString()));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the mod's item table: " + e, e);
        }
        return out;
    }

    // ---- 431 ------------------------------------------------------------------------------------------------------

    static void userPack(UiCase c) throws Exception {
        boolean old = c.onClient(mc -> savedEnabled());
        Path dir = c.onClient(mc -> mc.getResourcePackDirectory().resolve("testkit-431-pack"));
        String packId = "file/testkit-431-pack";
        Collection<String> oldSelected = c.onClient(mc -> List.copyOf(mc.getResourcePackRepository().getSelectedIds()));
        try {
            setEnabled(c, true);
            ItemStack seeds = skyblockStack(Items.WHEAT_SEEDS, "SEEDS", "hypixel_skyblock:item/testkit/seeds_431");
            ItemStack saliva = skyblockStack(Items.PAPER, "BEE_SALIVA",
                    "hypixel_skyblock:item/island_relevant/foraging_3/bee_saliva");
            // Without the pack: the vanilla seeds sprite and our own texture, neither a solid color.
            String seedsBefore = c.onClient(mc -> drawn(mc, seeds));
            String salivaBefore = c.onClient(mc -> drawn(mc, saliva));
            int[] seedsPx = c.onClient(mc -> spritePixels(mc, seeds));
            int[] salivaPx = c.onClient(mc -> spritePixels(mc, saliva));
            c.note("before: Seeds -> " + seedsBefore + " " + describe(seedsPx) + "; Bee Saliva -> " + salivaBefore + " "
                    + describe(salivaPx));
            c.check(seedsBefore.endsWith("item/wheat_seeds"), "SEEDS resolved to " + seedsBefore);
            c.check(salivaBefore.equals("killer560smod:item/packdisabler/bee_saliva"), "BEE_SALIVA -> " + salivaBefore);
            c.check(!allOf(seedsPx, MAGENTA) && !allOf(salivaPx, GREEN), "control: a sprite was already solid");

            writePack(dir);
            c.onClient(mc -> {
                mc.getResourcePackRepository().reload();
                List<String> sel = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
                sel.remove(packId);
                sel.add(packId); // last = highest priority, above the mod's own resources
                mc.getResourcePackRepository().setSelected(sel);
                return null;
            });
            boolean available = c.onClient(mc -> mc.getResourcePackRepository().getSelectedIds().contains(packId));
            c.check(available, "test pack not selected; available: "
                    + c.onClient(mc -> String.valueOf(mc.getResourcePackRepository().getAvailableIds())));
            reload(c);
            int[] seedsAfter = c.onClient(mc -> spritePixels(mc, seeds));
            int[] salivaAfter = c.onClient(mc -> spritePixels(mc, saliva));
            String seedsName = c.onClient(mc -> drawn(mc, seeds));
            c.note("with the pack: Seeds -> " + seedsName + " " + describe(seedsAfter) + "; Bee Saliva " + describe(salivaAfter));
            c.check(allOf(seedsAfter, MAGENTA), "the player's pack did not draw the Pack-Disabler Seeds: "
                    + describe(seedsAfter));
            c.check(allOf(salivaAfter, GREEN), "the player's pack did not replace our Bee Saliva texture: "
                    + describe(salivaAfter));
        } finally {
            c.onClient(mc -> {
                mc.getResourcePackRepository().setSelected(oldSelected);
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                return null;
            });
            reload(c);
            deleteTree(dir);
        }
    }

    static final int MAGENTA = 0xFFFF00FF; // same in ARGB and ABGR
    static final int GREEN = 0xFF00FF00;   // same in ARGB and ABGR

    private static void reload(UiCase c) {
        CompletableFuture<Void> f = c.onClient(mc -> mc.reloadResourcePacks());
        c.ctx().waitFor(mc -> f.isDone(), 20 * 60);
        c.ticks(5);
    }

    private static void writePack(Path dir) throws Exception {
        int format = resourceMajor();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"testkit 431\",\"pack_format\":"
                + format + ",\"min_format\":" + format + ",\"max_format\":" + format + "}}", StandardCharsets.UTF_8);
        solidPng(dir.resolve("assets/minecraft/textures/item/wheat_seeds.png"), MAGENTA);
        solidPng(dir.resolve("assets/killer560smod/textures/item/packdisabler/bee_saliva.png"), GREEN);
    }

    /** resource_major of the running client, from its own version.json. */
    private static int resourceMajor() throws Exception {
        try (InputStream in = Minecraft.class.getResourceAsStream("/version.json")) {
            var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return o.getAsJsonObject("pack_version").get("resource_major").getAsInt();
        }
    }

    private static void solidPng(Path file, int argbSymmetric) throws Exception {
        Files.createDirectories(file.getParent());
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, argbSymmetric);
            }
        }
        javax.imageio.ImageIO.write(img, "png", file.toFile());
    }

    /** Every pixel of the sprite the resolver picks for {@code stack}, as stored (color order does not matter here). */
    static int[] spritePixels(Minecraft mc, ItemStack stack) {
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
        var particle = state.pickParticleMaterial(RandomSource.create(0));
        if (particle == null) {
            return new int[0];
        }
        TextureAtlasSprite sprite = particle.sprite();
        Object img = Mod.field(sprite.contents(), "originalImage");
        int w = sprite.contents().width();
        int h = sprite.contents().height();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = (Integer) Mod.call(img, "getPixel", x, y);
            }
        }
        return px;
    }

    static boolean allOf(int[] px, int colour) {
        if (px.length == 0) {
            return false;
        }
        for (int p : px) {
            if (p != colour) {
                return false;
            }
        }
        return true;
    }

    static String describe(int[] px) {
        if (px.length == 0) {
            return "(no pixels)";
        }
        Set<Integer> distinct = new LinkedHashSet<>();
        for (int p : px) {
            distinct.add(p);
        }
        return px.length + " px, " + distinct.size() + " color(s), first " + Integer.toHexString(px[0]);
    }

    private static void deleteTree(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---- 432 ------------------------------------------------------------------------------------------------------

    static void bazaar(UiCase c) {
        @SuppressWarnings("unchecked")
        Set<String> ids = c.onClient(mc -> (Set<String>) Mod.staticCall("auction.BazaarCatalog", "ids"));
        int disagree = 0;
        int own = 0;
        List<String> bad = new ArrayList<>();
        for (String id : ids) {
            String[] r = c.onClient(mc -> {
                Object look = Mod.staticCall("packdisabler.ItemLooks", "get", id);
                Object entry = Mod.staticCall("auction.BazaarCatalog", "get", id);
                String src = String.valueOf(Mod.staticCall("auction.BazaarIcons", "source", id));
                String lookOwn = look == null ? null : (String) Mod.call(look, "ownTexture");
                String lookSkin = look == null ? null : (String) Mod.call(look, "skinHash");
                String entrySkin = (String) Mod.call(entry, "skinHash");
                String entryOwn = (String) Mod.call(entry, "ownTexture");
                return new String[]{src, String.valueOf(lookSkin), String.valueOf(entrySkin), String.valueOf(lookOwn),
                        String.valueOf(entryOwn)};
            });
            if (!r[1].equals(r[2]) || !r[3].equals(r[4])) {
                disagree++;
                bad.add(id);
            }
            if ("OWN_TEXTURE".equals(r[0])) {
                own++;
            }
            if ("FALLBACK".equals(r[0])) {
                bad.add(id + " (paper)");
            }
        }
        c.note(ids.size() + " products; " + own + " use our own texture; disagreeing/paper: " + bad);
        c.check(disagree == 0, disagree + " products where the Bazaar row and the shared table disagree");
        c.check(own >= 30, "only " + own + " products use our textures (expected the ~38 bazaar items with no old look)");
        c.check(bad.isEmpty(), "products without a look: " + bad);
    }

    // ---- 433 ------------------------------------------------------------------------------------------------------

    /** A vanilla item by registry id, so colored items need no per-version constant. */
    static Item vanilla(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id))
                .orElseThrow(() -> new AssertionError("no vanilla item " + id));
    }

    /** id, base item, Hypixel model, expected look (an item, a head skin hash, or our texture key). */
    static List<Fixture> refreshFixtures() {
        String hp = "hypixel_skyblock:item/";
        return List.of(
                // Great Spook 2026 (new rows / new models)
                new Fixture("BOO_STONE", Items.PAPER, hp + "reforge_stones/boo_stone", null,
                        "b04ad2524b961d20794575c67da928650a9ade1a0e60e7fde0fb30e577612d84", null),
                new Fixture("SCARE_FRAGMENT", Items.PAPER, hp + "great_spook/scare_fragment",
                        vanilla("black_stained_glass"), null, null),
                new Fixture("POLTERGEIST_PITCHFORK", Items.PAPER, hp + "great_spook/poltergeist_pitchfork",
                        Items.WOODEN_SWORD, null, null),
                new Fixture("GHOSTFLY_SOUL", Items.PAPER, hp + "great_spook/ghost_fly_soul", null, null,
                        "ghostfly_soul"),
                new Fixture("SPOOKY_SHARD", Items.PAPER, hp + "great_spook/spooky_fragment", null,
                        "d884ed1950bb8b198ada8191684400bd6640e03710481c8122b780b9ed1bd98c", null),
                new Fixture("DARK_CANDY", Items.PAPER, hp + "great_spook/dark_candy", null,
                        "7c866e4239551428f9c814d9dc11e7002e043b87f590f1fdcb4ff6bb497b8e18", null),
                new Fixture("ECTOPLASM", Items.PAPER, hp + "great_spook/ectoplasm", Items.PRISMARINE_CRYSTALS,
                        null, null),
                new Fixture("PUMPKIN_GUTS", Items.PAPER, hp + "great_spook/pumpkin_guts", Items.PUMPKIN_SEEDS, null,
                        null),
                new Fixture("BLACK_WOOLEN_YARN", Items.PAPER, hp + "great_spook/black_woolen_yarn",
                        vanilla("black_wool"), null, null),
                // Bingo tools and other items Hypixel's pack draws that neither source listed on a model
                new Fixture("BINGO_ROD", Items.PAPER, hp + "bingo/bingo_rod", Items.FISHING_ROD, null, null),
                new Fixture("BINGBOW", Items.PAPER, hp + "bingo/bingbow", Items.BOW, null, null),
                new Fixture("ABINGOPHONE", Items.PAPER, hp + "bingo/abingophone", null,
                        "6c3377e04d7a28e9302574ab609fb7ac4e72bdeda10189f3e560cfa5f9f16efb", null),
                new Fixture("HOE_OF_NO_TILLING", Items.PAPER, hp + "uncategorized/hoe_of_no_tilling",
                        Items.GOLDEN_HOE, null, null),
                new Fixture("SPELLING_STICK", Items.PAPER, hp + "uncategorized/spelling_stick", Items.STICK, null,
                        null),
                new Fixture("ETERNAL_CRYSTAL", Items.PAPER, hp + "uncategorized/eternal_crystal", Items.NETHER_STAR,
                        null, null),
                new Fixture("REVERSE_REFORGE_STONE_CORE", Items.PAPER, hp + "fishing/lotus_atoll/eroc_enots_egrofer",
                        Items.POPPED_CHORUS_FRUIT, null, null),
                new Fixture("CHUM_ROD", Items.PAPER, hp + "fishing/rod/broken_rod", Items.FISHING_ROD, null, null),
                // 0.27.2 Reforge and Power Stones (old skins from NEU just before its 2026-10-07 conversion)
                new Fixture("AMBER_MATERIAL", Items.PAPER, hp + "reforge_stones/amber_material", null,
                        "aace6bb3aa4ccac031168202f6d4532597bcac6351059abd9d10b28610493aeb", null),
                new Fixture("AOTE_STONE", Items.PAPER, hp + "reforge_stones/aote_stone", null,
                        "54d48ac7e65b91467904f2f4d154556776ffa9cd2d526b12a0499bb59cc656c7", null),
                new Fixture("BLAZE_WAX", Items.PAPER, hp + "reforge_stones/blaze_wax", null,
                        "f7bec376ecbec54dc6795cbca4918e12b153dd34a0827abc9ba7be63af87c078", null),
                new Fixture("CANDY_CORN", Items.PAPER, hp + "reforge_stones/candy_corn", null,
                        "176e4aac60e50be9495191e0098b2de52c4fe137d2f8fde10a267eef7123a2ee", null),
                new Fixture("ACACIA_BIRDHOUSE", Items.PAPER, hp + "power_stones/acacia_birdhouse", null,
                        "19e316ba48e51c6eb73cb068a2d468f678eed82d4529d4b12eec8bfa7819aa86", null),
                new Fixture("BEATING_HEART", Items.PAPER, hp + "power_stones/beating_heart", null,
                        "a338347c8a8456602f3bd9ae78ad383a7de6b569bb60bcf7f7b4345f0ef64045", null),
                new Fixture("BUBBA_BLISTER", Items.PAPER, hp + "power_stones/bubba_blister", null,
                        "6cb5727b986c36e07582148b886102cf8070b0ee3aa3771b2464393f588a832d", null));
    }

    static void refresh(UiCase c) {
        boolean old = c.onClient(mc -> savedEnabled());
        try {
            int rows = c.onClient(mc -> (Integer) Mod.staticCall("packdisabler.ItemLooks", "size"));
            c.note("item table: " + rows + " rows");
            c.check(rows >= 3039, "item table has " + rows + " rows, expected the 2026-11-03 refresh (3039+)");

            List<Fixture> all = new ArrayList<>(c.onClient(mc -> refreshFixtures()));
            all.addAll(fixtures());
            setEnabled(c, true);
            int heads = 0;
            int items = 0;
            int own = 0;
            int menuChecked = 0;
            for (Fixture f : all) {
                String want;
                if (f.expectItem() != null) {
                    want = c.onClient(mc -> drawn(mc, new ItemStack(f.expectItem())));
                    items++;
                } else if (f.expectSkin() != null) {
                    want = "head:" + f.expectSkin();
                    heads++;
                } else {
                    want = "killer560smod:item/packdisabler/" + f.expectOwn();
                    own++;
                }
                String withId = c.onClient(mc -> drawn(mc, skyblockStack(f.base(), f.id(), f.model())));
                // A menu's picture of the item: Hypixel's model and a name, no SkyBlock id.
                String menu = c.onClient(mc -> drawn(mc, menuStack(f.base(), f.model(), f.id())));
                boolean shared = sharedModel(c, f.model());
                c.note("ON  " + f.id() + " -> " + withId + " / menu " + menu + (shared ? " (model shared)" : "")
                        + " (want " + want + ")");
                c.check(withId.equals(want), f.id() + " drew " + withId + ", expected " + want);
                c.check(!withId.equals(MISSINGNO) && !withId.equals("minecraft:item/paper"), f.id() + " drew " + withId);
                if (!shared) {
                    menuChecked++;
                    c.check(menu.equals(want), f.id() + " in a menu (no id) drew " + menu + ", expected " + want);
                }
            }
            c.note(all.size() + " items: " + items + " old vanilla items, " + heads + " old heads, " + own
                    + " of our textures; " + menuChecked + " also checked as a menu shows them");
            c.check(all.size() >= 20 && heads >= 5 && items >= 5, "sample too small: " + all.size());
            c.check(menuChecked >= 20, "only " + menuChecked + " items checked without their id");

            // Every row on a Hypixel model resolves to a look (no dead vanilla id, legacy id or empty row).
            List<String> unresolved = c.onClient(mc -> unresolvedRows());
            c.note("rows on a Hypixel model with no resolvable look: " + unresolved.size() + " " + unresolved);
            c.check(unresolved.isEmpty(), unresolved.size() + " rows do not resolve: " + unresolved);

            // Untouched: a plain vanilla stack, and a Hypixel model no row names (with and without an id).
            String plainOn = c.onClient(mc -> drawn(mc, new ItemStack(Items.DIAMOND_SWORD)));
            String unknownMenuOn = c.onClient(mc -> drawn(mc, menuStack(Items.PAPER,
                    "hypixel_skyblock:item/testkit/unknown_433", "Unknown")));
            String unknownIdOn = c.onClient(mc -> drawn(mc, skyblockStack(Items.PAPER, "TESTKIT_UNKNOWN_433",
                    "hypixel_skyblock:item/testkit/unknown_433")));
            setEnabled(c, false);
            String plainOff = c.onClient(mc -> drawn(mc, new ItemStack(Items.DIAMOND_SWORD)));
            String unknownMenuOff = c.onClient(mc -> drawn(mc, menuStack(Items.PAPER,
                    "hypixel_skyblock:item/testkit/unknown_433", "Unknown")));
            c.note("plain diamond sword on/off: " + plainOn + " / " + plainOff + "; unknown model (menu) on/off: "
                    + unknownMenuOn + " / " + unknownMenuOff + "; unknown id on: " + unknownIdOn);
            c.check(plainOn.equals(plainOff) && plainOn.endsWith("diamond_sword"), "plain item changed: " + plainOn);
            c.check(unknownMenuOn.equals(MISSINGNO) && unknownMenuOff.equals(MISSINGNO)
                    && unknownIdOn.equals(MISSINGNO), "an item the table does not know was changed: " + unknownMenuOn
                    + " / " + unknownIdOn);
            int offChanged = 0;
            for (Fixture f : all) {
                String off = c.onClient(mc -> drawn(mc, menuStack(f.base(), f.model(), f.id())));
                if (!off.equals(MISSINGNO)) {
                    offChanged++;
                    c.check(false, f.id() + " (menu) with the setting OFF drew " + off);
                }
            }
            c.note("OFF: " + offChanged + " of " + all.size() + " menu stacks changed");
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                return null;
            });
        }
    }

    /** True when no single table row names {@code model} (two ids with different looks share it). */
    static boolean sharedModel(UiCase c, String model) {
        return c.onClient(mc -> Mod.staticCall("packdisabler.ItemLooks", "idForModel", Identifier.parse(model)) == null);
    }

    /** A stack the way a Hypixel menu shows an item: base item, Hypixel's model and a name, no custom data. */
    static ItemStack menuStack(Item base, String model, String name) {
        ItemStack s = new ItemStack(base);
        s.set(DataComponents.ITEM_MODEL, Identifier.parse(model));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    /** Every table row on a Hypixel model whose look the mod cannot resolve to a model. */
    static List<String> unresolvedRows() {
        List<String> bad = new ArrayList<>();
        try (InputStream in = PackDisablerCases.class.getResourceAsStream(
                "/assets/killer560smod/skyblock/item_looks.json")) {
            var items = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("items");
            for (var e : items.entrySet()) {
                if (!e.getValue().getAsJsonObject().has("m")) {
                    continue;
                }
                Object model = Mod.staticCall("packdisabler.ItemLooks", "modelFor", e.getKey());
                if (model == null || String.valueOf(model).equals("minecraft:paper")) {
                    bad.add(e.getKey());
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read the mod's item table: " + e, e);
        }
        return bad;
    }

    // ---- 434 ------------------------------------------------------------------------------------------------------

    static final String PAPER = "minecraft:item/paper";

    static void menuMaps(UiCase c) throws Exception {
        boolean old = c.onClient(mc -> savedEnabled());
        boolean oldPaper = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isPaperMenuButtons"));
        String version = minecraftVersion();
        boolean mapsRedrawn = !version.startsWith("26.2");
        c.note("Minecraft " + version + ": maps redrawn as folded maps = " + mapsRedrawn);
        try {
            setEnabled(c, true);
            setPaper(c, true);
            ItemStack sell = button(Items.MAP, "§6Create Sell Offer");
            ItemStack buy = button(Items.FILLED_MAP, "§aCreate Buy Order");
            String plainMap = c.onClient(mc -> handDrawn(mc, new ItemStack(Items.MAP)));
            String sellGui = c.onClient(mc -> drawn(mc, sell));
            String buyGui = c.onClient(mc -> drawn(mc, buy));
            String sellHand = c.onClient(mc -> handDrawn(mc, sell));
            ItemStack realMap = new ItemStack(Items.FILLED_MAP);
            realMap.set(DataComponents.MAP_ID, new net.minecraft.world.level.saveddata.maps.MapId(433));
            String realGui = c.onClient(mc -> drawn(mc, realMap));
            String sbMap = c.onClient(mc -> drawn(mc, skyblockStack(Items.MAP, "TESTKIT_MAP_434", null)));
            c.note("ON: sell " + sellGui + ", buy " + buyGui + ", sell in hand " + sellHand + ", real map " + realGui
                    + ", SkyBlock-id map " + sbMap + " (plain map in hand " + plainMap + ")");
            if (mapsRedrawn) {
                c.check(sellGui.equals(PAPER), version + " Create Sell Offer (map) drew " + sellGui + ", expected paper");
                c.check(buyGui.equals(PAPER), version + " Create Buy Order (filled map) drew " + buyGui
                        + ", expected paper");
            } else {
                c.check(!sellGui.equals(PAPER) && !buyGui.equals(PAPER), "26.2 map buttons changed: " + sellGui + " / "
                        + buyGui);
            }
            c.check(!sellHand.equals(PAPER), "a map in hand was drawn as paper");
            c.check(!realGui.equals(PAPER), "a real map (map data) was drawn as paper");
            c.check(!sbMap.equals(PAPER), "a map with a SkyBlock id was drawn as paper");

            setPaper(c, false);
            String subOff = c.onClient(mc -> drawn(mc, sell));
            setPaper(c, true);
            setEnabled(c, false);
            String allOff = c.onClient(mc -> drawn(mc, sell));
            c.note("Paper Menu Buttons OFF: " + subOff + "; Pack Disabler OFF: " + allOff);
            c.check(!subOff.equals(PAPER) && !allOff.equals(PAPER), "OFF still drew paper: " + subOff + " / " + allOff);

            // The sub-option is saved and loaded.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setPaperMenuButtons", false);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(CFG, "load");
                return null;
            });
            boolean reloaded = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isPaperMenuButtons"));
            c.check(!reloaded, "Paper Menu Buttons OFF did not survive save + load");
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setPaperMenuButtons", oldPaper);
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                Mod.call(Mod.cfg(CFG), "save");
                return null;
            });
        }
    }

    private static void setPaper(UiCase c, boolean on) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(CFG), "setPaperMenuButtons", on);
            return null;
        });
    }

    /** A Hypixel menu button: a vanilla item with only a name, as the Bazaar's product page sends them. */
    static ItemStack button(Item item, String name) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    /** {@link #drawn} for a first-person hand instead of a GUI slot. */
    static String handDrawn(Minecraft mc, ItemStack stack) {
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, mc.level,
                null, 0);
        var particle = state.pickParticleMaterial(RandomSource.create(0));
        return particle == null ? "(no sprite)" : particle.sprite().contents().name().toString();
    }

    /** The running client's version id ("26.3"), from its own version.json. */
    static String minecraftVersion() throws Exception {
        try (InputStream in = Minecraft.class.getResourceAsStream("/version.json")) {
            var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return o.get("id").getAsString();
        }
    }
}
