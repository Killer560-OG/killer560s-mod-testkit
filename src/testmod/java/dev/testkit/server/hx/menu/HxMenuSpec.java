package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven Hypixel menus: {@code menu.show {title, rows, slots, on, sell?}}.
 *
 * <p>{@code slots}: {@code {"13": <slot>}} where a slot is either vanilla item syntax ({@code "minecraft:diamond"}) or
 * {@code {item, count?, name?, lore?: [..], glint?, filler?}} - {@code name}/{@code lore} take section-sign codes, sent as
 * STYLED components ({@link HxMenus#legacy}) so the mod's {@code getString()} sees plain text, as from Hypixel. {@code "fill": true} fills every unset slot with Hypixel's filler (black pane, empty name).
 *
 * <p>{@code on}: what a click on a top slot does, {@code {"13": {open: <spec>} | {close: true} | {chat: "..."} |
 * {set: {"13": <slot>}} | {experiment: <menu.experiment args>}}} (several keys may combine; chat is a system line sent after the click). A click on any slot
 * is recorded by {@link HxChestMenu} as {@code menu.click}; the action table adds {@code menu.action}.
 *
 * <p>{@code sell: true}: a QUICK_MOVE (shift-click) on one of the player's own slots SELLS that stack - it leaves the
 * player's inventory and a {@code sell.sold} event names it - the way an NPC shop takes items. A PICKUP on a player slot
 * does nothing, like Hypixel.
 */
final class HxMenuSpec {

    static final String SCRIPT = "hx.spec";

    record State(JsonObject spec) {
    }

    private HxMenuSpec() {
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxMenuSpec::onClick);
    }

    static JsonElement show(MinecraftServer server, JsonObject a) {
        ServerPlayer player = HxPrimitives.player(server, a);
        HxChestMenu menu = open(server, player, a);
        JsonObject out = new JsonObject();
        out.addProperty("containerId", menu.containerId);
        return out;
    }

    static HxChestMenu open(MinecraftServer server, ServerPlayer player, JsonObject spec) {
        int rows = spec.has("rows") ? spec.get("rows").getAsInt() : 6;
        List<ItemStack> items = items(server, spec, rows);
        Component title = HxPrimitives.text(server, spec, "title");
        return HxMenus.open(player, rows, title, items, SCRIPT, new State(spec.deepCopy()));
    }

    static List<ItemStack> items(MinecraftServer server, JsonObject spec, int rows) {
        boolean fill = spec.has("fill") && spec.get("fill").getAsBoolean();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < rows * 9; i++) {
            items.add(fill ? HxMenus.filler(server) : ItemStack.EMPTY);
        }
        if (spec.has("slots")) {
            for (var e : spec.getAsJsonObject("slots").entrySet()) {
                items.set(Integer.parseInt(e.getKey()), slot(server, e.getValue()));
            }
        }
        return items;
    }

    static ItemStack slot(MinecraftServer server, JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return ItemStack.EMPTY;
        }
        if (el.isJsonPrimitive()) {
            String s = el.getAsString();
            return s.isEmpty() ? ItemStack.EMPTY : HxMenus.stack(server, s, 1);
        }
        JsonObject o = el.getAsJsonObject();
        if (o.has("filler") && o.get("filler").getAsBoolean()) {
            return HxMenus.filler(server);
        }
        ItemStack s = HxMenus.stack(server, o.get("item").getAsString(), o.has("count") ? o.get("count").getAsInt() : 1);
        if (o.has("name")) {
            s.set(DataComponents.CUSTOM_NAME, HxMenus.legacy(o.get("name").getAsString()));
        }
        if (o.has("lore")) {
            List<Component> lines = new ArrayList<>();
            for (JsonElement l : o.getAsJsonArray("lore")) {
                lines.add(HxMenus.legacy(l.getAsString()));
            }
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (o.has("glint") && o.get("glint").getAsBoolean()) {
            HxMenus.glint(s);
        }
        return s;
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        State st = HxMenus.state(menu.containerId, State.class);
        if (st == null) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        int top = menu.items().getContainerSize();
        JsonObject spec = st.spec();
        if (slot >= top && slot < menu.slots.size()) {
            if (spec.has("sell") && spec.get("sell").getAsBoolean() && input == ContainerInput.QUICK_MOVE) {
                Slot s = menu.slots.get(slot);
                ItemStack stack = s.getItem();
                if (!stack.isEmpty()) {
                    JsonObject e = new JsonObject();
                    e.addProperty("slot", slot);
                    e.addProperty("inventorySlot", s.getContainerSlot());
                    e.addProperty("item", HxTerminals.id(stack));
                    e.addProperty("count", stack.getCount());
                    var data = stack.get(DataComponents.CUSTOM_DATA);
                    e.addProperty("skyblockId", data == null ? "" : data.copyTag().getStringOr("id", ""));
                    e.addProperty("name", stack.getHoverName().getString());
                    player.getInventory().setItem(s.getContainerSlot(), ItemStack.EMPTY);
                    HxEvents.custom("sell.sold", player, e);
                }
            }
            return;
        }
        if (!spec.has("on") || !spec.getAsJsonObject("on").has(String.valueOf(slot))) {
            return;
        }
        JsonObject action = spec.getAsJsonObject("on").getAsJsonObject(String.valueOf(slot));
        JsonObject e = new JsonObject();
        e.addProperty("slot", slot);
        e.addProperty("button", button);
        e.addProperty("input", input.name());
        e.add("action", action.deepCopy());
        HxEvents.custom("menu.action", player, e);
        if (action.has("set")) {
            for (var se : action.getAsJsonObject("set").entrySet()) {
                menu.items().setItem(Integer.parseInt(se.getKey()), slot(server, se.getValue()));
            }
        }
        if (action.has("chat")) {
            JsonArray lines = action.get("chat").isJsonArray() ? action.getAsJsonArray("chat") : null;
            List<String> texts = new ArrayList<>();
            if (lines != null) {
                lines.forEach(l -> texts.add(l.getAsString()));
            } else {
                texts.add(action.get("chat").getAsString());
            }
            HxMenus.later(() -> texts.forEach(t -> player.connection.send(
                    new ClientboundSystemChatPacket(Component.literal(t), false))));
        }
        if (action.has("experiment")) {
            JsonObject next = action.getAsJsonObject("experiment");
            HxMenus.later(() -> HxExperiments.start(server, player, next));
        } else if (action.has("open")) {
            JsonObject next = action.getAsJsonObject("open");
            HxMenus.later(() -> open(server, player, next));
        } else if (action.has("close") && action.get("close").getAsBoolean()) {
            int id = menu.containerId;
            HxMenus.later(() -> {
                if (player.containerMenu.containerId == id) {
                    player.closeContainer();
                }
            });
        }
    }
}
