package dev.testkit.server.hx.menu;

import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxChestMenu;
import dev.testkit.server.hx.HxEvents;
import dev.testkit.server.hx.HxPrimitives;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP3's menu plumbing on top of WP1's {@link HxChestMenu} (which already is the "HxMenu" the plan asks for: a chest
 * whose {@code clicked} never moves an item or gives the cursor anything, records {@code menu.click}, hands the click to
 * a named script and re-sends the menu). This class opens one with a state object attached (keyed by container id),
 * builds stacks, and runs deferred work on the next server tick (closing a solved terminal from inside its own click
 * handler would close the menu while {@code clicked} is still using it).
 */
public final class HxMenus {

    /** Per-menu behaviour that also wants the server tick (Melody's moving marker, experiment playback). */
    public interface Ticking {
        void tick(MinecraftServer server, HxChestMenu menu, ServerPlayer player);
    }

    private static final Map<Integer, Object> STATE = new ConcurrentHashMap<>();
    private static final Deque<Runnable> NEXT_TICK = new ArrayDeque<>();

    private HxMenus() {
    }

    /** Open a {@link HxChestMenu} with these items; {@code state} is retrievable by container id for its script. */
    public static HxChestMenu open(ServerPlayer player, int rows, Component title, List<ItemStack> items,
                                   String script, Object state) {
        SimpleContainer container = new SimpleContainer(rows * 9);
        for (int i = 0; i < items.size() && i < rows * 9; i++) {
            container.setItem(i, items.get(i) == null ? ItemStack.EMPTY : items.get(i));
        }
        String plain = title.getString();
        HxChestMenu[] made = new HxChestMenu[1];
        var opened = player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            made[0] = new HxChestMenu(id, inv, rows, container, plain, script);
            return made[0];
        }, title));
        if (opened.isEmpty() || made[0] == null) {
            throw new IllegalStateException("openMenu returned no container id");
        }
        if (state != null) {
            STATE.put(made[0].containerId, state);
        }
        JsonObject data = new JsonObject();
        data.addProperty("containerId", made[0].containerId);
        data.addProperty("title", plain);
        data.addProperty("rows", rows);
        data.addProperty("script", script == null ? "" : script);
        HxEvents.custom("menu.opened", player, data);
        return made[0];
    }

    @SuppressWarnings("unchecked")
    public static <T> T state(int containerId, Class<T> type) {
        Object o = STATE.get(containerId);
        return type.isInstance(o) ? (T) o : null;
    }

    /** The menu the player has open, if it is one of ours. */
    public static HxChestMenu current(ServerPlayer player) {
        return player.containerMenu instanceof HxChestMenu m ? m : null;
    }

    public static void later(Runnable r) {
        synchronized (NEXT_TICK) {
            NEXT_TICK.addLast(r);
        }
    }

    /** END_SERVER_TICK: deferred work, then every open stateful menu that ticks. */
    static void tick(MinecraftServer server) {
        List<Runnable> due;
        synchronized (NEXT_TICK) {
            due = new ArrayList<>(NEXT_TICK);
            NEXT_TICK.clear();
        }
        due.forEach(Runnable::run);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            HxChestMenu m = current(p);
            if (m != null && STATE.get(m.containerId) instanceof Ticking t) {
                t.tick(server, m, p);
            }
        }
        // Forget menus nobody has open any more (a container id is reused after 100 opens).
        if (server.getTickCount() % 200 == 0) {
            java.util.Set<Integer> open = new java.util.HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                open.add(p.containerMenu.containerId);
            }
            STATE.keySet().removeIf(id -> !open.contains(id));
        }
    }

    // ---- stacks -------------------------------------------------------------------------------------------------

    /** A stack from vanilla item syntax, e.g. {@code minecraft:red_stained_glass_pane}. */
    public static ItemStack stack(MinecraftServer server, String spec, int count) {
        try {
            return HxPrimitives.stack(server, spec, count);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad item '" + spec + "': " + e.getMessage(), e);
        }
    }

    public static ItemStack named(MinecraftServer server, String spec, String name, String... lore) {
        ItemStack s = stack(server, spec, 1);
        s.set(DataComponents.CUSTOM_NAME, legacy(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(legacy(l));
            }
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        return s;
    }

    /**
     * Section-sign text as STYLED components, the shape a modern client gets Hypixel's item names and lore in (the
     * colour codes become styles, so {@code getString()} is the plain text - which is what the mod's lore readers
     * match). Not italic, like Hypixel's. {@code "§6LEGENDARY"} -> literal "LEGENDARY" coloured gold.
     */
    public static Component legacy(String text) {
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY.withItalic(false);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '§' && i + 1 < text.length()) {
                net.minecraft.ChatFormatting f = net.minecraft.ChatFormatting.getByCode(text.charAt(i + 1));
                if (f != null) {
                    if (run.length() > 0) {
                        out.append(Component.literal(run.toString()).setStyle(style));
                        run.setLength(0);
                    }
                    if (f == net.minecraft.ChatFormatting.RESET) {
                        style = net.minecraft.network.chat.Style.EMPTY.withItalic(false);
                    } else if (f.ordinal() < 16) { // the 16 colours come first; isColor() is gone on 26.2 (same rule as the mod's ChatColors)
                        style = net.minecraft.network.chat.Style.EMPTY.withItalic(false).applyFormat(f);
                    } else {
                        style = style.applyFormat(f);
                    }
                    i++;
                    continue;
                }
            }
            run.append(ch);
        }
        if (run.length() > 0 || out.getSiblings().isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(style));
        }
        return out;
    }

    /**
     * Hypixel's menu filler: a black pane with an EMPTY name. Source: the mod's ExperimentSolver.java:566 comment
     * ("minecraft:black_stained_glass_pane" with name="" are genuine decorative border filler).
     */
    public static ItemStack filler(MinecraftServer server) {
        ItemStack s = stack(server, "minecraft:black_stained_glass_pane", 1);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(""));
        return s;
    }

    public static ItemStack glint(ItemStack s) {
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    public static List<ItemStack> filled(MinecraftServer server, int rows) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < rows * 9; i++) {
            out.add(filler(server));
        }
        return out;
    }
}
