package dev.testkit.compat;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Colour-variant items, for 26.1.x, where each colour is its own {@code Items} constant. 26.2 folds them into
 * {@code ColorCollection}s, so scenarios name them through here. Only what the scenarios use; add a colour to
 * BOTH copies.
 */
public final class McItems {

    private McItems() {
    }

    public static final Item BLUE_WOOL = Items.BLUE_WOOL;
    public static final Item LIGHT_BLUE_WOOL = Items.LIGHT_BLUE_WOOL;
    public static final Item GRAY_WOOL = Items.GRAY_WOOL;
    public static final Item LIGHT_GRAY_WOOL = Items.LIGHT_GRAY_WOOL;

    public static final Item LIGHT_BLUE_DYE = Items.LIGHT_BLUE_DYE;
    public static final Item LIME_DYE = Items.LIME_DYE;

    public static final Item BLACK_STAINED_GLASS_PANE = Items.BLACK_STAINED_GLASS_PANE;
    public static final Item BLUE_STAINED_GLASS_PANE = Items.BLUE_STAINED_GLASS_PANE;
    public static final Item GREEN_STAINED_GLASS_PANE = Items.GREEN_STAINED_GLASS_PANE;
    public static final Item LIME_STAINED_GLASS_PANE = Items.LIME_STAINED_GLASS_PANE;
    public static final Item ORANGE_STAINED_GLASS_PANE = Items.ORANGE_STAINED_GLASS_PANE;
    public static final Item RED_STAINED_GLASS_PANE = Items.RED_STAINED_GLASS_PANE;
    public static final Item YELLOW_STAINED_GLASS_PANE = Items.YELLOW_STAINED_GLASS_PANE;
}
