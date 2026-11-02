package dev.testkit.server.hx.menu;

import dev.testkit.server.hx.HxChestMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A Hypixel storage page ({@code /enderchest N}, {@code /backpack N}): unlike a Hypixel menu, items really move, but
 * only by shift-click and only below the chrome row (slots 0-8 never move). {@code menu.open {"script": "storage"}}.
 * Built for the mod's Inventory Sorter storage mode (doc-invsort-1101): a shift-click on a page slot moves the stack to
 * the player's first empty inventory slot (last hotbar slot first, as vanilla's chest does), and one on an inventory slot
 * moves it to the page's first empty slot from 9.
 */
final class HxStorage {

    static final String SCRIPT = "storage";

    private HxStorage() {
    }

    static void register() {
        HxChestMenu.registerScript(SCRIPT, HxStorage::onClick);
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        if (input != ContainerInput.QUICK_MOVE || slot < 0 || slot >= menu.slots.size()) {
            return;
        }
        int page = menu.items().getContainerSize();
        Slot from = menu.slots.get(slot);
        ItemStack stack = from.getItem();
        if (stack.isEmpty() || slot < 9) {
            return;
        }
        if (slot < page) {
            for (int i = menu.slots.size() - 1; i >= page; i--) {
                Slot to = menu.slots.get(i);
                if (to.getItem().isEmpty()) {
                    to.set(stack.copy());
                    from.set(ItemStack.EMPTY);
                    return;
                }
            }
        } else {
            for (int i = 9; i < page; i++) {
                if (menu.items().getItem(i).isEmpty()) {
                    menu.items().setItem(i, stack.copy());
                    from.set(ItemStack.EMPTY);
                    return;
                }
            }
        }
    }
}
