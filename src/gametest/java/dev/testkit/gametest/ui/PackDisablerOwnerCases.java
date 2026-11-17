package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1117-ui-pack-disabler-owner-looks (texredo3-1117): the looks killer560 approved on 2026-11-17. With Pack Disabler ON
 * every approved item draws its form: a vanilla player head wearing the skin bundled in the jar (read off the head's
 * render info: {@code killer560smod:entity/packdisabler/<key>}), or a plain vanilla item, with the glint where the
 * approved look is enchanted. Critter Capsules pick their skin by state (custom_data), Safari Belts by the rarity model,
 * a head the server already gave a skin keeps it, and a Carnival Shovel draws as a plain shovel while its model
 * component (which Fruit Digging reads) stays Hypixel's. OFF changes nothing: every stack draws Hypixel's (unloaded)
 * model and no glint. Ends with a screenshot of a chest and inventory holding all of them.
 */
final class PackDisablerOwnerCases {

    /** The approved vanilla forms (tools/items/owner_looks.py VANILLA). */
    static final Map<String, String> VANILLA = Map.of(
            "NOTHING_IN_A_BOTTLE", "glass_bottle", "DISTANT_ECHO", "echo_shard", "BEASTSLAYERS_CODEX", "book",
            "DEAD_MANS_JOURNAL", "writable_book", "MAGIC_FOR_PETS", "enchanted_book", "BEE_SALIVA", "honey_bottle",
            "HELIXIS", "weeping_vines", "RAINBOW_FEATHER", "feather");
    static final String CAPSULE_MODEL = "hypixel_skyblock:item/island_relevant/safari/critter_capsule";
    static final String SHOVEL_MODEL = "hypixel_skyblock:item/uncategorized/carnival_shovel_";

    /** One stack and what it must draw ON: "head:bundled:<key>", "item:<vanilla id>" or "head:<hash>". */
    record Expect(String label, ItemStack stack, String want, boolean glint) {
    }

    private PackDisablerOwnerCases() {
    }

    static ItemStack stack(String id, String model, CompoundTag extra) {
        ItemStack s = PackDisablerCases.skyblockStack(Items.PAPER, id, model);
        if (extra != null) {
            CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
            tag.merge(extra);
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return s;
    }

    static CompoundTag flags(String... names) {
        CompoundTag t = new CompoundTag();
        for (String n : names) {
            t.putBoolean(n, true);
        }
        return t;
    }

    static List<Expect> expects() {
        List<Expect> out = new ArrayList<>();
        com.google.gson.JsonObject root;
        try (InputStream in = PackDisablerOwnerCases.class.getResourceAsStream(
                "/assets/killer560smod/skyblock/item_looks.json")) {
            root = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("could not read the mod's item table: " + e, e);
        }
        var items = root.getAsJsonObject("items");
        Map<String, com.google.gson.JsonObject> sorted = new java.util.TreeMap<>();
        for (var e : items.entrySet()) {
            sorted.put(e.getKey(), e.getValue().getAsJsonObject());
        }
        for (var e : sorted.entrySet()) {
            var o = e.getValue();
            String model = o.has("m") ? o.get("m").getAsString() : "hypixel_skyblock:item/testkit/" + e.getKey().toLowerCase();
            boolean glint = o.has("g");
            if (o.has("k") && !e.getKey().contains("CRITTER_CAPSULE")) {
                out.add(new Expect(e.getKey(), stack(e.getKey(), model, null), "head:bundled:" + o.get("k").getAsString(), glint));
            } else if (VANILLA.containsKey(e.getKey())) {
                out.add(new Expect(e.getKey(), stack(e.getKey(), model, null), "item:" + VANILLA.get(e.getKey()), glint));
            }
        }
        // Items known only by Hypixel's pack model (and the Safari Belt's rarities): any id, the model decides.
        for (var e : root.getAsJsonObject("models").entrySet()) {
            String key = e.getValue().getAsString();
            String id = key.startsWith("safari_belt") ? "SAFARI_BELT" : "TESTKIT_MODEL_1117";
            out.add(new Expect("model " + key, stack(id, e.getKey(), null), "head:bundled:" + key, false));
        }
        // Critter Capsules by state, as Hypixel's own capsule model selects them.
        out.add(new Expect("capsule inactive", stack("CRITTER_CAPSULE", CAPSULE_MODEL, null),
                "head:bundled:critter_capsule_inactive", false));
        out.add(new Expect("capsule active", stack("CRITTER_CAPSULE", CAPSULE_MODEL, flags("active")),
                "head:bundled:critter_capsule_active", false));
        out.add(new Expect("capsule model", stack("CRITTER_CAPSULE", CAPSULE_MODEL, flags("model")),
                "head:bundled:critter_capsule", false));
        out.add(new Expect("capsule failed", stack("CRITTER_CAPSULE", CAPSULE_MODEL, flags("model", "failed")),
                "head:bundled:critter_capsule_fail", false));
        String masterful = items.getAsJsonObject("MASTERFUL_CRITTER_CAPSULE").get("m").getAsString();
        out.add(new Expect("masterful inactive", stack("MASTERFUL_CRITTER_CAPSULE", masterful, null),
                "head:bundled:masterful_critter_capsule_inactive", false));
        out.add(new Expect("masterful active", stack("MASTERFUL_CRITTER_CAPSULE", masterful, flags("active")),
                "head:bundled:masterful_critter_capsule_active", false));
        out.add(new Expect("masterful model", stack("MASTERFUL_CRITTER_CAPSULE", masterful, flags("model")),
                "head:bundled:masterful_critter_capsule", false));
        // Carnival Shovels: a plain vanilla shovel per mode.
        for (String[] m : new String[][]{{"iron", "iron_shovel"}, {"gold", "golden_shovel"}, {"diamond", "diamond_shovel"}}) {
            ItemStack s = PackDisablerCases.skyblockStack(Items.IRON_SHOVEL, "CARNIVAL_SHOVEL", SHOVEL_MODEL + m[0]);
            out.add(new Expect("shovel " + m[0], s, "item:" + m[1], false));
        }
        return out;
    }

    static String want(Minecraft mc, Expect e) {
        if (!e.want().startsWith("item:")) {
            return e.want();
        }
        var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(e.want().substring(5)));
        return PackDisablerCases.drawn(mc, new ItemStack(item));
    }

    static void ownerLooks(UiCase c) {
        boolean old = c.onClient(mc -> PackDisablerCases.savedEnabled());
        int oldGui = c.onClient(mc -> mc.options.guiScale().get());
        int[] oldWindow = c.onClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        List<ItemStack> oldInv = c.onClient(mc -> {
            List<ItemStack> l = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                l.add(mc.player.getInventory().getItem(i).copy());
            }
            return l;
        });
        try {
            List<Expect> all = c.onClient(mc -> expects());
            c.note(all.size() + " approved looks to check");
            c.check(all.size() >= 75, "only " + all.size() + " approved looks found in the table");

            PackDisablerCases.setEnabled(c, true);
            List<String> bad = new ArrayList<>();
            Map<String, Integer> forms = new LinkedHashMap<>();
            for (Expect e : all) {
                String got = c.onClient(mc -> PackDisablerCases.drawn(mc, e.stack()));
                String want = c.onClient(mc -> want(mc, e));
                boolean foil = c.onClient(mc -> e.stack().hasFoil());
                forms.merge(e.want().startsWith("item:") ? "vanilla" : "bundled head", 1, Integer::sum);
                if (!got.equals(want) || foil != e.glint()) {
                    bad.add(e.label() + " drew " + got + " foil " + foil + " (want " + want + " foil " + e.glint() + ")");
                }
            }
            c.note("ON forms: " + forms);
            bad.forEach(c::note);
            c.check(bad.isEmpty(), bad.size() + " approved looks wrong with Pack Disabler ON; first: "
                    + (bad.isEmpty() ? "" : bad.get(0)));

            // The enchanted look is the id's, not the model's: plain Ruby Veilshroom shares the model and has no glint.
            boolean plainFoil = c.onClient(mc -> stack("RUBY_VEILSHROOM",
                    "hypixel_skyblock:item/island_relevant/foraging_3/resources/ruby_veilshroom", null).hasFoil());
            boolean enchFoil = c.onClient(mc -> stack("ENCHANTED_RUBY_VEILSHROOM",
                    "hypixel_skyblock:item/island_relevant/foraging_3/resources/ruby_veilshroom", null).hasFoil());
            c.note("Ruby Veilshroom foil " + plainFoil + ", Enchanted Ruby Veilshroom foil " + enchFoil);
            c.check(!plainFoil && enchFoil, "glint follows the model instead of the id: plain " + plainFoil
                    + ", enchanted " + enchFoil);

            // A head the server already dressed keeps its own skin.
            ItemStack served = LockStarHeadCases.head("HONEYCOMB_RING", null, "Honeycomb Ring", "§9§lRARE");
            served.set(DataComponents.ITEM_MODEL, Identifier.parse(
                    "hypixel_skyblock:item/island_relevant/foraging_3/accessories/honey/honeycomb_ring"));
            String servedGot = c.onClient(mc -> PackDisablerCases.drawn(mc, served));
            c.note("server-skinned Honeycomb Ring -> " + servedGot);
            c.check(servedGot.equals("head:4ba74e76b3a99f146db628ede6be2f3c3720daa8f6829ccc1251d9db42bc2d63"),
                    "a head with the server's skin drew " + servedGot);

            // Fruit Digging reads the shovel's mode off the model component, which the look must leave alone.
            for (String[] m : new String[][]{{"iron", "Mines"}, {"gold", "Treasure"}, {"diamond", "Anchor"}}) {
                ItemStack s = PackDisablerCases.skyblockStack(Items.IRON_SHOVEL, "CARNIVAL_SHOVEL", SHOVEL_MODEL + m[0]);
                String mode = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoShovelSwap", "modeOf", s));
                c.note("shovel " + m[0] + " mode " + mode);
                c.check(m[1].equals(mode), "with Pack Disabler ON a " + m[0] + " Carnival Shovel reads mode " + mode);
            }

            // The Bazaar browser draws a bundled-head product the same way.
            String bzId = "ENCHANTED_RUBY_VEILSHROOM";
            String src = c.onClient(mc -> String.valueOf(Mod.staticCall("auction.BazaarIcons", "source", bzId)));
            if (!"FALLBACK".equals(src) && !"CATALOG".equals(src)) {
                ItemStack icon = c.onClient(mc -> (ItemStack) Mod.staticCall("auction.BazaarIcons", "icon", bzId));
                String iconGot = c.onClient(mc -> PackDisablerCases.drawn(mc, icon));
                c.note("Bazaar icon " + bzId + " (" + src + ") -> " + iconGot + " foil " + icon.hasFoil());
                c.check(iconGot.startsWith("head:bundled:") && icon.hasFoil(), "Bazaar icon for " + bzId + " drew "
                        + iconGot + " foil " + icon.hasFoil());
            } else {
                c.note("Bazaar icon " + bzId + ": not a catalog product (" + src + ")");
            }

            screenshot(c, all, "on");

            PackDisablerCases.setEnabled(c, false);
            List<String> changed = new ArrayList<>();
            for (Expect e : all) {
                String got = c.onClient(mc -> PackDisablerCases.drawn(mc, e.stack()));
                boolean foil = c.onClient(mc -> e.stack().hasFoil());
                if (!got.equals(PackDisablerCases.MISSINGNO) || foil) {
                    changed.add(e.label() + " drew " + got + " foil " + foil);
                }
            }
            changed.forEach(c::note);
            c.check(changed.isEmpty(), changed.size() + " stacks changed with Pack Disabler OFF; first: "
                    + (changed.isEmpty() ? "" : changed.get(0)));
            String servedOff = c.onClient(mc -> PackDisablerCases.drawn(mc, served));
            c.check(servedOff.equals(PackDisablerCases.MISSINGNO), "OFF: the server-skinned head drew " + servedOff);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                for (int i = 0; i < 36; i++) {
                    mc.player.getInventory().setItem(i, oldInv.get(i));
                }
                Mod.call(Mod.cfg("packdisabler.PackDisablerConfig"), "setEnabled", old);
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

    /** A six-row chest plus the player's inventory holding every approved look, as the player sees it. */
    static void screenshot(UiCase c, List<Expect> all, String suffix) {
        c.ctx().getInput().resizeWindow(1600, 900);
        c.ticks(3);
        Screen s = c.onClient(mc -> {
            mc.options.guiScale().set(3);
            mc.resizeGui();
            SimpleContainer box = new SimpleContainer(54);
            for (int i = 0; i < all.size() && i < 54; i++) {
                box.setItem(i, all.get(i).stack().copy());
            }
            for (int i = 0; i < 36; i++) {
                int j = 54 + i;
                mc.player.getInventory().setItem(i, j < all.size() ? all.get(j).stack().copy() : ItemStack.EMPTY);
            }
            ContainerScreen screen = new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                    mc.player.getInventory(), Component.literal("Pack Disabler looks"));
            McCompat.setScreen(mc, screen);
            return screen;
        });
        c.ticks(20); // bundled skins load on first draw
        c.check(all.size() <= 90, all.size() + " looks do not fit one chest and inventory");
        HudEditorCases.screenshot(c, suffix);
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }
}
