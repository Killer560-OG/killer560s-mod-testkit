package dev.testkit.server.hx.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel's sign prompts (the Bazaar's Custom Amount, 2026-10-16): the menu closes, an oak sign with the prompt's
 * lines is put at the player's feet (and sent at once, block and text), then {@code ClientboundOpenSignEditorPacket}.
 * The client's sign editor sends {@code ServerboundSignUpdatePacket} on Done; {@code MixinServerSignUpdate} hands it
 * here before vanilla would write it to the sign, the block that was there comes back, a {@code sign.update} event
 * records the four lines, and the prompt's action runs.
 *
 * <p>A menu slot action {@code {"sign": {lines: [4 strings], amount: {...} | none: true}}}:
 * <ul>
 *   <li>{@code amount}: line 1 is read as a whole number {@code n}. With {@code 1 <= n <= max} the purchase happens:
 *       {@code unit} (a slot spec for one unit) is given {@code n} times in stacks of {@code stack}, and the Bazaar's
 *       {@code "[Bazaar] Bought <n>x <name> for <n*each> coins!"} is sent; with {@code confirm: true} a "Confirm Instant
 *       Buy" page comes first, whose slot 13 states {@code Amount: <n>x} and {@code Price: <n*each*1.04> coins} (Hypixel's 4% quote) and does
 *       the purchase when clicked. After the purchase {@code reopen} (a menu.show spec), if given, opens. Any other
 *       line 1 records {@code sign.rejected} and does nothing else.</li>
 *   <li>{@code none: true}: the click does nothing at all - the sign never opens (the stop-cleanly case).</li>
 * </ul>
 */
public final class HxSigns {

    /** A prompt waiting for its sign update, by player. */
    private record Pending(BlockPos pos, JsonObject spec, BlockState was) {
    }

    private static final Map<java.util.UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private HxSigns() {
    }

    /** From a menu click: close the menu and open the sign prompt. */
    static void open(MinecraftServer server, ServerPlayer player, JsonObject spec) {
        if (spec.has("none") && spec.get("none").getAsBoolean()) {
            return;
        }
        player.closeContainer();
        // At his feet, where the floor holds it up: a standing sign in mid-air was dropped by the client a tick after it
        // arrived (BlockEntity.isRemoved went true, measured 2026-10-16), which closed the editor before anything was typed.
        BlockPos pos = player.blockPosition();
        BlockState sign = Blocks.OAK_SIGN.defaultBlockState();
        // A REAL sign, not only a fake one on the client: with only the packets, the client's sign was replaced a tick
        // after it arrived (its BlockEntity.isRemoved went true, 2026-10-16; what re-sent the block was not traced), which
        // closed the editor before anything was typed. The block it replaced comes back when the update arrives.
        BlockState was = player.level().getBlockState(pos);
        player.level().setBlock(pos, sign, 3);
        SignBlockEntity be = player.level().getBlockEntity(pos) instanceof SignBlockEntity real ? real
                : new SignBlockEntity(pos, sign);
        if (be.getLevel() == null) {
            be.setLevel(player.level());
        }
        SignText text = new SignText();
        JsonArray lines = spec.has("lines") ? spec.getAsJsonArray("lines") : new JsonArray();
        for (int i = 0; i < 4; i++) {
            text = text.setMessage(i, Component.literal(i < lines.size() ? lines.get(i).getAsString() : ""));
        }
        be.setText(text, true);
        PENDING.put(player.getUUID(), new Pending(pos, spec.deepCopy(), was));
        player.connection.send(new ClientboundBlockUpdatePacket(pos, sign));
        player.connection.send(ClientboundBlockEntityDataPacket.create(be));
        player.connection.send(new ClientboundOpenSignEditorPacket(pos, true));
        JsonObject e = new JsonObject();
        e.addProperty("x", pos.getX());
        e.addProperty("y", pos.getY());
        e.addProperty("z", pos.getZ());
        HxEvents.custom("sign.opened", player, e);
        System.out.println("[hx] sign prompt at " + pos.toShortString());
    }

    /**
     * A sign update from the client, on the server thread. True when it was one of ours (vanilla must not handle it).
     */
    public static boolean onSignUpdate(ServerPlayer player, BlockPos pos, String[] lines) {
        Pending p = PENDING.get(player.getUUID());
        System.out.println("[hx] sign update at " + pos.toShortString() + " " + String.join("|", lines)
                + (p == null ? " (no prompt)" : " (prompt at " + p.pos().toShortString() + ")"));
        if (p == null || !p.pos().equals(pos)) {
            return false;
        }
        PENDING.remove(player.getUUID());
        // Put the block that was there back.
        player.level().setBlock(pos, p.was(), 3);
        player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
        JsonObject e = new JsonObject();
        JsonArray arr = new JsonArray();
        for (String l : lines) {
            arr.add(l);
        }
        e.add("lines", arr);
        HxEvents.custom("sign.update", player, e);
        MinecraftServer server = player.level().getServer();
        if (p.spec().has("amount")) {
            HxMenus.later(() -> amount(server, player, p.spec().getAsJsonObject("amount"), lines[0]));
        }
        return true;
    }

    private static void amount(MinecraftServer server, ServerPlayer player, JsonObject a, String line) {
        long max = a.has("max") ? a.get("max").getAsLong() : Long.MAX_VALUE;
        long n;
        try {
            n = line.matches("\\d{1,9}") ? Long.parseLong(line) : -1;
        } catch (NumberFormatException ex) {
            n = -1;
        }
        if (n < 1 || n > max) {
            JsonObject e = new JsonObject();
            e.addProperty("line", line);
            e.addProperty("max", max);
            HxEvents.custom("sign.rejected", player, e);
            return;
        }
        if (a.has("confirm") && a.get("confirm").getAsBoolean()) {
            double each = a.get("each").getAsDouble();
            JsonObject slots = new JsonObject();
            JsonObject item = new JsonObject();
            item.addProperty("item", "minecraft:oak_sign");
            item.addProperty("name", "§aCustom Amount");
            JsonArray lore = new JsonArray();
            lore.add("§7Amount: §a" + String.format(Locale.US, "%,d", n) + "§7x");
            // Hypixel quotes the book + 4% (refunded after); the Bought line charges the book price.
            lore.add("§7Price: §6" + String.format(Locale.US, "%,.0f", n * each * 1.04) + " coins");
            lore.add("");
            lore.add("§eClick to buy!");
            item.add("lore", lore);
            slots.add("13", item);
            JsonObject back = new JsonObject();
            back.addProperty("item", "minecraft:arrow");
            back.addProperty("name", "§aGo Back");
            slots.add("31", back);
            JsonObject on = new JsonObject();
            JsonObject buy = new JsonObject();
            JsonObject deliver = a.deepCopy();
            deliver.addProperty("n", n);
            buy.add("deliver", deliver);
            on.add("13", buy);
            JsonObject menu = new JsonObject();
            menu.addProperty("title", "Confirm Instant Buy");
            menu.addProperty("rows", 4);
            menu.addProperty("fill", true);
            menu.add("slots", slots);
            menu.add("on", on);
            HxMenuSpec.open(server, player, menu);
            return;
        }
        JsonObject deliver = a.deepCopy();
        deliver.addProperty("n", n);
        deliver(server, player, deliver);
    }

    /** The purchase: {@code n} units in stacks, the Bought line, then {@code reopen}. */
    static void deliver(MinecraftServer server, ServerPlayer player, JsonObject a) {
        long n = a.get("n").getAsLong();
        int stack = a.has("stack") ? a.get("stack").getAsInt() : 64;
        double each = a.get("each").getAsDouble();
        long left = n;
        while (left > 0) {
            int c = (int) Math.min(stack, left);
            ItemStack given = HxMenuSpec.slot(server, a.get("unit"));
            given.setCount(c);
            JsonObject ge = new JsonObject();
            ge.addProperty("item", HxTerminals.id(given));
            ge.addProperty("count", c);
            player.getInventory().add(given);
            HxEvents.custom("menu.give", player, ge);
            left -= c;
        }
        player.inventoryMenu.broadcastChanges();
        String name = a.get("name").getAsString();
        String chat = String.format(Locale.US, "[Bazaar] Bought %,dx %s for %,.0f coins!", n, name, n * each);
        HxMenus.later(() -> player.connection.send(new ClientboundSystemChatPacket(Component.literal(chat), false)));
        if (a.has("reopen") && a.get("reopen").isJsonObject()) {
            JsonObject reopen = a.getAsJsonObject("reopen");
            HxMenus.later(() -> HxMenuSpec.open(server, player, reopen));
        } else if (player.containerMenu != player.inventoryMenu) {
            HxMenus.later(player::closeContainer);
        }
    }
}
