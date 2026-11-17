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

    /** A plain click like a real chest: pick up (all, or half on button 1), set down (all, or one), merge, swap. */
    private static void pickup(HxChestMenu menu, int slot, int button) {
        if (slot < 9 || slot >= menu.slots.size()) {
            return;
        }
        Slot s = menu.slots.get(slot);
        ItemStack cur = s.getItem().copy();
        ItemStack held = menu.getCarried().copy();
        if (held.isEmpty()) {
            if (!cur.isEmpty()) {
                menu.setCarried(cur.split(button == 1 ? (cur.getCount() + 1) / 2 : cur.getCount()));
                s.set(cur);
            }
        } else if (cur.isEmpty()) {
            s.set(held.split(button == 1 ? 1 : held.getCount()));
            menu.setCarried(held);
        } else if (ItemStack.isSameItemSameComponents(cur, held)) {
            int put = Math.min(button == 1 ? 1 : held.getCount(), cur.getMaxStackSize() - cur.getCount());
            cur.grow(put);
            held.shrink(put);
            s.set(cur);
            menu.setCarried(held);
        } else if (button == 0) {
            s.set(held);
            menu.setCarried(cur);
        }
    }

    private static void onClick(HxChestMenu menu, ServerPlayer player, int slot, int button, ContainerInput input) {
        menu.keepCarried = true;
        if (input == ContainerInput.PICKUP) {
            pickup(menu, slot, button);
            return;
        }
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
