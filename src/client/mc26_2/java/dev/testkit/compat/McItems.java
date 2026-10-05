package dev.testkit.compat;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Colour-variant items, for 26.2, where {@code Items.WOOL}, {@code Items.DYE} and {@code Items.STAINED_GLASS_PANE}
 * are {@code ColorCollection<Item>}s picked by {@code DyeColor} (javap'd on the 26.2 jar; the mod's own
 * McItems does the same). Same constants as the 26.1 copy; add a colour to BOTH.
 */
public final class McItems {

    private McItems() {
    }

    public static final Item BLUE_WOOL = Items.WOOL.pick(DyeColor.BLUE);
    public static final Item LIGHT_BLUE_WOOL = Items.WOOL.pick(DyeColor.LIGHT_BLUE);
    public static final Item GRAY_WOOL = Items.WOOL.pick(DyeColor.GRAY);
    public static final Item LIGHT_GRAY_WOOL = Items.WOOL.pick(DyeColor.LIGHT_GRAY);

    public static final Item LIGHT_BLUE_DYE = Items.DYE.pick(DyeColor.LIGHT_BLUE);
    public static final Item LIME_DYE = Items.DYE.pick(DyeColor.LIME);

    public static final Item BLACK_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK);
    public static final Item BLUE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.BLUE);
    public static final Item GREEN_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.GREEN);
    public static final Item LIME_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.LIME);
    public static final Item ORANGE_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.ORANGE);
    public static final Item RED_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.RED);
    public static final Item YELLOW_STAINED_GLASS_PANE = Items.STAINED_GLASS_PANE.pick(DyeColor.YELLOW);
}
