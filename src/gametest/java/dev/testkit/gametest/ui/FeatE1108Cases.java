package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * featE-1108: Auto Close Chest's Click mode, the Item Quality tooltip, Iced Mob Boxes and the Current Room Name HUD.
 * Each case asserts the feature acted (a screen closed, a line added, mobs boxed, the HUD text set) and that the same
 * action with the switch OFF, or the wrong item / title, changes nothing.
 */
final class FeatE1108Cases {

    private static final String CLOSE = "autoclosechest.AutoCloseChestConfig";
    private static final String CLICK = "autoclosechest.AutoCloseChestClick";
    private static final String QOL = "dungeonqol.DungeonQolConfig";
    private static final String ZOMBIE = "{Tags:[\"feiced\"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}";

    private FeatE1108Cases() {
    }

    private static void server(UiCase c, String... cmds) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (String cmd : cmds) {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
                }
            });
            return null;
        });
        c.ticks(6);
    }

    private static void set(UiCase c, String cfg, String setter, Object value) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(cfg), setter, value);
            return null;
        });
    }

    private static void roomSim(UiCase c, boolean on) {
        c.onClient(mc -> Mod.staticCall("secrets.DungeonState", "setRoomSim", on));
    }

    // ---- Item Quality ---------------------------------------------------------------------------------------------

    private static ItemStack drop(int quality, int tier) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("baseStatBoostPercentage", quality);
        tag.putInt("item_tier", tier);
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static List<String> tooltip(UiCase c, ItemStack stack) {
        return c.onClient(mc -> Screen.getTooltipFromItem(mc, stack).stream().map(Component::getString).toList());
    }

    static void itemQuality(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, QOL);
        try {
            set(c, QOL, "setItemQuality", false);
            ItemStack stack = drop(47, 7);
            c.check(tooltip(c, stack).stream().noneMatch(l -> l.contains("Item Quality")), "the line shows with the switch OFF");
            set(c, QOL, "setItemQuality", true);
            List<String> on = tooltip(c, stack);
            c.note("tooltip with the switch on: " + on);
            c.check(on.contains("Item Quality: 47/50 (Floor 7)"), "no 'Item Quality: 47/50 (Floor 7)' line: " + on);
            c.check(tooltip(c, drop(50, 0)).contains("Item Quality: 50/50 (Entrance)"), "tier 0 is not named Entrance");
            c.check(tooltip(c, new ItemStack(Items.IRON_SWORD)).stream().noneMatch(l -> l.contains("Item Quality")),
                    "a plain item got a quality line");
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, QOL);
        }
    }

    // ---- Auto Close Chest, Click mode -----------------------------------------------------------------------------

    private static void openChest(UiCase c, String title) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, new ContainerScreen(ChestMenu.threeRows(0, mc.player.getInventory(),
                    new SimpleContainer(27)), mc.player.getInventory(), Component.literal(title)));
            return null;
        });
        c.ticks(2);
    }

    private static boolean chestOpen(UiCase c) {
        return c.onClient(mc -> McCompat.screen(mc) instanceof ContainerScreen);
    }

    private static void press(UiCase c, int key) {
        c.onClient(mc -> {
            try {
                java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",
                        long.class, int.class, net.minecraft.client.input.KeyEvent.class);
                m.setAccessible(true);
                var ev = dev.testkit.compat.McKeys.keyEvent(key, 0);
                m.invoke(mc.keyboardHandler, mc.getWindow().handle(), InputConstants.PRESS, ev);
                m.invoke(mc.keyboardHandler, mc.getWindow().handle(), InputConstants.RELEASE, ev);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("KeyboardHandler.keyPress", e);
            }
            return null;
        });
        c.ticks(2);
    }

    static void chestClick(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, CLOSE);
        boolean wasDungeon = c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon"));
        try {
            set(c, CLOSE, "setEnabled", true);
            set(c, CLOSE, "setMode", Mod.enumValue("autoclosechest.AutoCloseChestConfig$Mode", "CLICK"));
            roomSim(c, true);
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon")), "not in a dungeon");
            int before = c.onClient(mc -> (Integer) Mod.staticCall(CLICK, "closedCount"));
            // A secret chest stays open until a press: it is still there after 2 ticks, then a key closes it.
            openChest(c, "Chest");
            c.check(chestOpen(c), "the chest never opened (Click mode must not close it by itself)");
            press(c, InputConstants.KEY_A);
            c.check(!chestOpen(c), "a key press did not close the chest");
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(CLICK, "closedCount")) == before + 1,
                    "the close was not Click mode's");
            // A mouse press does the same (the event Fabric runs for every click on the screen).
            openChest(c, "Large Chest");
            boolean allowed = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                var ev = new MouseButtonEvent(10, 10, new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                return ScreenMouseEvents.allowMouseClick(s).invoker().allowMouseClick(s, ev);
            });
            c.check(!allowed, "the click was not eaten");
            c.check(!chestOpen(c), "a mouse press did not close the chest");
            // Controls: a reward chest title, and the switch OFF.
            openChest(c, "Wood Chest");
            press(c, InputConstants.KEY_A);
            c.check(chestOpen(c), "a reward chest ('Wood Chest') was closed");
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            set(c, CLOSE, "setEnabled", false);
            openChest(c, "Chest");
            press(c, InputConstants.KEY_A);
            c.check(chestOpen(c), "the chest closed with the switch OFF");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            if (!wasDungeon) {
                roomSim(c, false);
            }
            DungeonB2Cases.restoreConfig(c, saved, CLOSE);
        }
    }

    // ---- Iced Mob Boxes --------------------------------------------------------------------------------------------

    private static int count(UiCase c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("dungeonqol.IcedMobsFeature", "icedCount"));
    }

    private static void use(UiCase c) {
        c.onClient(mc -> {
            UseItemCallback.EVENT.invoker().interact(mc.player, mc.level, InteractionHand.MAIN_HAND);
            return null;
        });
    }

    static void icedMobs(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, QOL);
        try {
            server(c, "kill @e[tag=feiced]", "tp @a 0.5 -60 0.5 0 0",
                    "summon zombie 0.5 -60 3.5 " + ZOMBIE, "summon zombie 0.5 -60 -3.5 " + ZOMBIE,
                    "summon zombie 0.5 -60 12.5 " + ZOMBIE);
            c.ctx().waitFor(mc -> mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(20)).size() >= 3, 200);
            c.onClient(mc -> {
                mc.player.setYRot(0f);
                mc.player.setXRot(0f);
                return null;
            });
            set(c, QOL, "setIcedBoxes", true);
            // Control: a stick without the wand's id does nothing.
            server(c, "item replace entity @p weapon.mainhand with minecraft:stick");
            use(c);
            c.check(count(c) == 0, "a plain stick boxed mobs");
            server(c, "item replace entity @p weapon.mainhand with minecraft:stick[custom_data={id:\"ICE_SPRAY_WAND\"}]");
            c.ctx().waitFor(mc -> mc.player.getMainHandItem().has(DataComponents.CUSTOM_DATA), 100);
            set(c, QOL, "setIcedBoxes", false);
            use(c);
            c.check(count(c) == 0, "the wand boxed mobs with the switch OFF");
            set(c, QOL, "setIcedBoxes", true);
            use(c);
            int n = count(c);
            c.note("mobs boxed by one wand click: " + n + " of 3 (one in front, one behind, one 12 blocks away)");
            c.check(n == 1, "expected only the zombie in front boxed, got " + n);
            c.ticks(3);
            c.check(count(c) == 1, "the box did not last");
            c.ticks(105);
            c.check(count(c) == 0, "the box outlived the 5 second freeze");
        } finally {
            server(c, "kill @e[tag=feiced]");
            DungeonB2Cases.restoreConfig(c, saved, QOL);
        }
    }

    // ---- Current Room Name -----------------------------------------------------------------------------------------

    private static String shown(UiCase c) {
        return c.onClient(mc -> (String) Mod.staticCall("dungeonqol.RoomNameHud", "shown"));
    }

    static void roomName(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, QOL);
        boolean wasDungeon = c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon"));
        try {
            server(c, "scoreboard objectives add feroom dummy", "scoreboard objectives setdisplay sidebar feroom",
                    "team add feroomt", "team modify feroomt prefix {\"text\":\"Room: Water Board\"}",
                    "team join feroomt feroomholder", "scoreboard players set feroomholder feroom 1");
            set(c, QOL, "setRoomHud", false);
            roomSim(c, true);
            c.ticks(25);
            c.check(shown(c).isEmpty(), "the HUD text was set with the switch OFF: '" + shown(c) + "'");
            set(c, QOL, "setRoomHud", true);
            c.ticks(25);
            c.note("HUD text: '" + shown(c) + "'");
            c.check("Room: Water Board".equals(shown(c)), "expected 'Room: Water Board', got '" + shown(c) + "'");
            if (!wasDungeon) {
                roomSim(c, false);
                c.ticks(25);
                c.check(shown(c).isEmpty(), "the HUD still shows outside a dungeon: '" + shown(c) + "'");
            }
        } finally {
            server(c, "scoreboard objectives remove feroom", "team remove feroomt");
            if (!wasDungeon) {
                roomSim(c, false);
            }
            DungeonB2Cases.restoreConfig(c, saved, QOL);
        }
    }
}
