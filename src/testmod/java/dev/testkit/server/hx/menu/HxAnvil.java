package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hypixel SkyBlock's Anvil: {@code menu.anvil {preview=true, claim="slot"}}.
 *
 * <p>Layout from hypixelskyblock.minecraft.wiki's {@code Anvil/UI} template (read 2026-10-07): a 6-row menu titled
 * "Anvil", black filler, red panes "Item to Upgrade" (slots 11, 12, 20) and "Item to Sacrifice" (14, 15, 24), the
 * result slot 13 (a barrier "Anvil" while empty), the "Combine Items" anvil in 22, the two inputs 29 (upgrade) and
 * 33 (sacrifice), a red pane bottom row and the close barrier in 49.
 *
 * <p>Behaviour (what is NOT on the wiki is marked as this fake's choice): a shift-click on one of the player's own
 * slots moves that stack into 29, or 33 if 29 is taken; a click on 29/33 hands it back. A click on 22 with both inputs
 * filled COMBINES - deliberately permissive, like an anvil that takes anything, so a wrong pair the mod sends is
 * recorded rather than hidden: two books merge enchant by enchant (equal levels +1, otherwise the higher; no cap), any
 * other pair yields a copy of the left item. Every combine is an {@code anvil.combine} event naming both inputs and the
 * result, which is the server-side truth the cases assert on. Then, with {@code claim:"slot"} (default) the result
 * sits in 13 and 22 reads "Claim the result item above!" until 22 or 13 is clicked (the double click on 22 QUOI's
 * module sends); with {@code claim:"direct"} it goes straight into the inventory. {@code preview:true} shows the result
 * in 13 while both inputs are filled (this fake's choice; whether Hypixel previews is not verified). {@code
 * previewOverride} replaces that preview with a given stack, to test a mismatching preview. Closing the anvil returns
 * its inputs and an unclaimed result to the inventory.
 *
 * <p>{@code menu.anvil.state} returns the last anvil's combines, inserts, claims and current slots.
 */
final class HxAnvil {

    static final String SCRIPT = "hx.anvil";
    static final int RESULT = 13;
    static final int COMBINE = 22;
    static final int LEFT = 29;
    static final int RIGHT = 33;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();
    private static final AtomicInteger UUIDS = new AtomicInteger();
    private static volatile State last;

    static final class State {
        final boolean preview;
        final boolean direct;
        final JsonElement previewOverride;
        boolean resultPending;
        final JsonArray combines = new JsonArray();
        int inserts;
        int claims;
        int takebacks;

        State(JsonObject a) {
            preview = !a.has("preview") || a.get("preview").getAsBoolean();
            direct = a.has("claim") && "direct".equals(a.get("claim").getAsString());
            previewOverride = a.has("previewOverride") ? a.get("previewOverride") : null;
        }
    }

    /** Hypixel's anvil returns what is in it when it closes. */
    static final class AnvilMenu extends HxChestMenu {
        AnvilMenu(int id, Inventory inv, SimpleContainer items) {
            super(id, inv, 6, items, "Anvil", SCRIPT);
        }

        @Override
        public void removed(Player player) {
            if (player instanceof ServerPlayer sp) {
                for (int s : new int[]{LEFT, RIGHT}) {
                    ItemStack st = items().getItem(s);
                    if (!st.isEmpty()) {
                        sp.getInventory().add(st.copy());
                        items().setItem(s, ItemStack.EMPTY);
                    }
                }
                State state = STATES.get(containerId);
                if (state != null && state.resultPending) {
                    sp.getInventory().add(items().getItem(RESULT).copy());
                    state.resultPending = false;
                }
            }
            super.removed(player);
        }
    }

    private HxAnvil() {
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxAnvil::onClick);
    }

    static JsonElement open(MinecraftServer server, JsonObject a) {
        ServerPlayer player = HxPrimitives.player(server, a);
        SimpleContainer items = new SimpleContainer(54);
        for (int i = 0; i < 54; i++) {
            items.setItem(i, HxMenus.filler(server));
        }
        String upgradeLore = "§7The item you want to upgrade";
        String sacrificeLore = "§7The item you are sacrificing in";
        for (int s : new int[]{11, 12, 20}) {
            items.setItem(s, HxMenus.named(server, "minecraft:red_stained_glass_pane", "§6Item to Upgrade", upgradeLore));
        }
        for (int s : new int[]{14, 15, 24}) {
            items.setItem(s, HxMenus.named(server, "minecraft:red_stained_glass_pane", "§6Item to Sacrifice", sacrificeLore));
        }
        for (int s = 45; s < 54; s++) {
            items.setItem(s, HxMenus.named(server, "minecraft:red_stained_glass_pane", " "));
        }
        items.setItem(49, HxMenus.named(server, "minecraft:barrier", "§cClose"));
        items.setItem(RESULT, emptyResult(server));
        items.setItem(COMBINE, combineButton(server));
        items.setItem(LEFT, ItemStack.EMPTY);
        items.setItem(RIGHT, ItemStack.EMPTY);
        State state = new State(a);
        AnvilMenu[] made = new AnvilMenu[1];
        var opened = player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            made[0] = new AnvilMenu(id, inv, items);
            return made[0];
        }, Component.literal("Anvil")));
        if (opened.isEmpty() || made[0] == null) {
            throw new IllegalStateException("openMenu returned no container id");
        }
        STATES.put(made[0].containerId, state);
        last = state;
        JsonObject data = new JsonObject();
        data.addProperty("containerId", made[0].containerId);
        data.addProperty("title", "Anvil");
        data.addProperty("rows", 6);
        data.addProperty("script", SCRIPT);
        HxEvents.custom("menu.opened", player, data);
        JsonObject out = new JsonObject();
        out.addProperty("containerId", made[0].containerId);
        return out;
    }

    static JsonElement state(MinecraftServer server, JsonObject a) {
        State s = last;
        JsonObject o = new JsonObject();
        if (s == null) {
            return o;
        }
        o.add("combines", s.combines.deepCopy());
        o.addProperty("inserts", s.inserts);
        o.addProperty("claims", s.claims);
        o.addProperty("takebacks", s.takebacks);
        o.addProperty("resultPending", s.resultPending);
        return o;
    }

    private static ItemStack emptyResult(MinecraftServer server) {
        return HxMenus.named(server, "minecraft:barrier", "§cAnvil", "§7Place a target item in the left",
                "§7slot and a sacrifice item in the", "§7right slot to combine them!");
    }

    private static ItemStack combineButton(MinecraftServer server) {
        return HxMenus.named(server, "minecraft:anvil", "§aCombine Items", "§7Combine the items in the slots",
                "§7to the left and right below.");
    }

    private static ItemStack claimButton(MinecraftServer server) {
        return HxMenus.named(server, "minecraft:anvil", "§aAnvil", "§7Claim the result item above!");
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        State st = STATES.get(menu.containerId);
        if (st == null) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        SimpleContainer items = menu.items();
        if (slot >= 54 && slot < menu.slots.size()) {
            if (input != ContainerInput.QUICK_MOVE || st.resultPending) {
                return;
            }
            Slot s = menu.slots.get(slot);
            ItemStack stack = s.getItem();
            if (stack.isEmpty()) {
                return;
            }
            int to = items.getItem(LEFT).isEmpty() ? LEFT : items.getItem(RIGHT).isEmpty() ? RIGHT : -1;
            if (to < 0) {
                return;
            }
            items.setItem(to, stack.copy());
            player.getInventory().setItem(s.getContainerSlot(), ItemStack.EMPTY);
            st.inserts++;
            JsonObject e = new JsonObject();
            e.addProperty("from", slot);
            e.addProperty("to", to);
            e.addProperty("item", describe(stack));
            HxEvents.custom("anvil.insert", player, e);
            refreshPreview(server, items, st);
            return;
        }
        if ((slot == LEFT || slot == RIGHT) && !items.getItem(slot).isEmpty() && !st.resultPending) {
            player.getInventory().add(items.getItem(slot).copy());
            items.setItem(slot, ItemStack.EMPTY);
            st.takebacks++;
            refreshPreview(server, items, st);
            return;
        }
        if (st.resultPending && (slot == COMBINE || slot == RESULT)) {
            ItemStack result = items.getItem(RESULT).copy();
            player.getInventory().add(result);
            items.setItem(RESULT, emptyResult(server));
            items.setItem(COMBINE, combineButton(server));
            st.resultPending = false;
            st.claims++;
            JsonObject e = new JsonObject();
            e.addProperty("slot", slot);
            e.addProperty("item", describe(result));
            HxEvents.custom("anvil.claim", player, e);
            return;
        }
        if (slot == COMBINE && !items.getItem(LEFT).isEmpty() && !items.getItem(RIGHT).isEmpty()) {
            ItemStack left = items.getItem(LEFT);
            ItemStack right = items.getItem(RIGHT);
            ItemStack result = combine(server, left, right);
            JsonObject e = new JsonObject();
            e.addProperty("left", describe(left));
            e.addProperty("right", describe(right));
            e.addProperty("result", describe(result));
            st.combines.add(e.deepCopy());
            items.setItem(LEFT, ItemStack.EMPTY);
            items.setItem(RIGHT, ItemStack.EMPTY);
            HxEvents.custom("anvil.combine", player, e);
            if (st.direct) {
                player.getInventory().add(result);
                items.setItem(RESULT, emptyResult(server));
            } else {
                items.setItem(RESULT, result);
                items.setItem(COMBINE, claimButton(server));
                st.resultPending = true;
            }
        }
    }

    private static void refreshPreview(MinecraftServer server, SimpleContainer items, State st) {
        if (st.resultPending) {
            return;
        }
        ItemStack left = items.getItem(LEFT);
        ItemStack right = items.getItem(RIGHT);
        if (st.preview && !left.isEmpty() && !right.isEmpty()) {
            items.setItem(RESULT, st.previewOverride != null ? HxMenuSpec.slot(server, st.previewOverride)
                    : combine(server, left, right));
        } else {
            items.setItem(RESULT, emptyResult(server));
        }
    }

    // ---- books --------------------------------------------------------------------------------------------------

    static CompoundTag extra(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? null : d.copyTag();
    }

    static boolean isBook(ItemStack s) {
        CompoundTag t = extra(s);
        return t != null && "ENCHANTED_BOOK".equals(t.getStringOr("id", ""));
    }

    /** "sharpness4", "sharpness4+smite4" for a book (sorted), "minecraft:diamond" otherwise; "" for empty. */
    static String describe(ItemStack s) {
        if (s.isEmpty()) {
            return "";
        }
        if (!isBook(s)) {
            CompoundTag t = extra(s);
            String sb = t == null ? "" : t.getStringOr("id", "");
            return HxTerminals.id(s) + (sb.isEmpty() ? "" : "/" + sb);
        }
        CompoundTag ench = extra(s).getCompoundOrEmpty("enchantments");
        List<String> parts = new ArrayList<>();
        for (String k : new java.util.TreeSet<>(ench.keySet())) {
            parts.add(k + ench.getIntOr(k, 0));
        }
        return String.join("+", parts);
    }

    private static ItemStack combine(MinecraftServer server, ItemStack left, ItemStack right) {
        if (!isBook(left) || !isBook(right)) {
            return left.copy();
        }
        CompoundTag a = extra(left).getCompoundOrEmpty("enchantments");
        CompoundTag b = extra(right).getCompoundOrEmpty("enchantments");
        Map<String, Integer> out = new TreeMap<>();
        for (String k : a.keySet()) {
            out.put(k, a.getIntOr(k, 0));
        }
        for (String k : b.keySet()) {
            int lb = b.getIntOr(k, 0);
            Integer la = out.get(k);
            out.put(k, la == null ? lb : la == lb ? la + 1 : Math.max(la, lb));
        }
        return book(server, out);
    }

    static ItemStack book(MinecraftServer server, Map<String, Integer> enchants) {
        StringBuilder sb = new StringBuilder();
        for (var e : enchants.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        String spec = "minecraft:enchanted_book[custom_data={id:\"ENCHANTED_BOOK\",enchantments:{" + sb
                + "},uuid:\"anvil-" + UUIDS.incrementAndGet() + "\"},custom_name={text:\"Enchanted Book\",italic:false}]";
        return HxMenus.stack(server, spec, 1);
    }
}
