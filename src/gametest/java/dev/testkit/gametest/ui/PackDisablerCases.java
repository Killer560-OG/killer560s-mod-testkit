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
 *   <li>429-ui-pack-disabler-missing: items drawn with unknown ids are listed by {@code /k560textures missing}, which
 *       writes exactly those ids to its file (known items and vanilla-model items are not listed).</li>
 *   <li>430-ui-pack-disabler-screens: a chest of affected items at GUI scale 2, ON and OFF, screenshots.</li>
 *   <li>431-ui-pack-disabler-user-pack: a resource pack the player selects that replaces {@code wheat_seeds} and one
 *       of OUR textures wins for both: a Pack-Disabler-resolved Seeds item and Bee Saliva read the pack's pixels.</li>
 *   <li>432-ui-pack-disabler-bazaar: the Bazaar icon for every product comes from the same table (one answer).</li>
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

    // ---- 429 ------------------------------------------------------------------------------------------------------

    static void missing(UiCase c) throws Exception {
        if (!Mod.isDevTools()) {
            c.note("release jar: /k560textures is compiled out, nothing to test");
            return;
        }
        boolean old = c.onClient(mc -> savedEnabled());
        try {
            setEnabled(c, true);
            c.onClient(mc -> Mod.staticCall("packdisabler.PackDisabler", "clearMissing"));
            Set<String> expected = new TreeSet<>(List.of("TESTKIT_NEW_429_A", "TESTKIT_NEW_429_B"));
            c.onClient(mc -> {
                for (String id : expected) {
                    drawn(mc, skyblockStack(Items.PAPER, id, "hypixel_skyblock:item/testkit/" + id.toLowerCase()));
                }
                // Known looks and vanilla models are not "missing".
                drawn(mc, skyblockStack(Items.PAPER, "UMBER", "hypixel_skyblock:item/glacite/umber/umber"));
                drawn(mc, skyblockStack(Items.PAPER, "BEE_SALIVA",
                        "hypixel_skyblock:item/island_relevant/foraging_3/bee_saliva"));
                drawn(mc, skyblockStack(Items.WHEAT_SEEDS, "ENCHANTED_SEEDS", "minecraft:wheat_seeds"));
                drawn(mc, skyblockStack(Items.PAPER, "TESTKIT_NO_MODEL_429", null));
                return null;
            });
            Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config",
                    "killer560smod-packdisabler-missing.txt"));
            Files.deleteIfExists(file);
            c.onClient(mc -> {
                mc.player.connection.sendCommand("k560textures missing");
                return null;
            });
            c.ticks(5);
            c.check(Files.exists(file), "/k560textures missing wrote no file at " + file);
            Set<String> listed = new TreeSet<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    listed.add(line.split("\t")[0]);
                }
            }
            c.note("listed: " + listed);
            c.check(listed.equals(expected), "the missing list is " + listed + ", expected exactly " + expected);
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(CFG), "setEnabled", old);
                Mod.staticCall("packdisabler.PackDisabler", "clearMissing");
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
            // Without the pack: the vanilla seeds sprite and our own texture, neither a solid colour.
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

    /** Every pixel of the sprite the resolver picks for {@code stack}, as stored (colour order does not matter here). */
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
        return px.length + " px, " + distinct.size() + " colour(s), first " + Integer.toHexString(px[0]);
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
}
