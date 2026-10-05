package dev.testkit.server.hx;

import com.google.gson.JsonObject;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The minimal menu behind the core {@code menu.open} op: a chest of 1-6 rows whose items cannot be taken.
 *
 * <p>Like a Hypixel menu, a click never moves an item: {@link #clicked} records the click, hands it to the named
 * {@link Script} if there is one, and re-sends the menu so the client's predicted pickup is undone. WP3 builds the
 * full Hypixel menus ({@code hx/menu/HxMenu}); this is the primitive they and anyone else can use.
 */
public class HxChestMenu extends ChestMenu {

    /** What a click does. Registered by name with {@link #registerScript}; {@code menu.open {script}} picks one. */
    @FunctionalInterface
    public interface Script {
        void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input);
    }

    private static final Map<String, Script> SCRIPTS = new ConcurrentHashMap<>();

    /** For modules: make a script available to {@code menu.open {"script": name}}. */
    public static void registerScript(String name, Script script) {
        if (SCRIPTS.putIfAbsent(name, script) != null) {
            throw new IllegalStateException("[hx] menu script registered twice: " + name);
        }
    }

    private final SimpleContainer items;
    private final String scriptName;
    private final String title;

    public HxChestMenu(int containerId, Inventory inventory, int rows, SimpleContainer items, String title,
                       String scriptName) {
        super(typeFor(rows), containerId, inventory, items, rows);
        this.items = items;
        this.title = title;
        this.scriptName = scriptName;
    }

    static MenuType<ChestMenu> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("rows must be 1-6, got " + rows);
        };
    }

    public SimpleContainer items() {
        return items;
    }

    public String title() {
        return title;
    }

    /** Replace one slot's item and push the change to the client. */
    public void setItem(int slot, ItemStack stack) {
        items.setItem(slot, stack);
        broadcastChanges();
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        // Never call super: a Hypixel menu does not let you pick anything up.
        if (player instanceof ServerPlayer sp) {
            JsonObject data = new JsonObject();
            data.addProperty("containerId", containerId);
            data.addProperty("slot", slot);
            data.addProperty("button", button);
            data.addProperty("input", input.name());
            data.addProperty("title", title);
            data.addProperty("script", scriptName == null ? "" : scriptName);
            HxEvents.custom("menu.click", sp, data);
            Script script = scriptName == null ? null : SCRIPTS.get(scriptName);
            if (script != null) {
                script.onClick(this, sp, slot, button, input);
            }
        }
        setCarried(ItemStack.EMPTY);
        broadcastFullState();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    static boolean hasScript(String name) {
        return SCRIPTS.containsKey(name);
    }
}
