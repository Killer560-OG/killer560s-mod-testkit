package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1118-sim-menus} (simmenus-1118, killer560 2026-11-18, screenshots of the sim against Hypixel): the sim's Stats &amp;
 * Equipment menu has the armor in Hypixel's column (11/20/29/38); /loadouts is "(1/1) Loadouts" with the worn armor on the
 * left and the 12 spots on the right (default Terror 1, Thermodynamic 2), a click on a filled spot wears that set exactly as
 * Hypixel's click does (so Loadout Keybinds' Open, Swap, Close works against it unchanged), the two sets' spots can be moved
 * and never share one, and they survive a reload; /class opens the class menu and /class &lt;name&gt; picks the class. Every
 * check reads what the menu held or what the sim did.
 */
final class SimMenus1118 {

    static final String NAME = "1118-sim-menus";
    private static final String LOADOUT = "com.killer560.hub.roomsim.SimLoadout";
    private static final String ARMOR = "com.killer560.hub.roomsim.SimArmor";
    private static final String SWAPPER = "com.killer560.hub.loadoutkeybinds.LoadoutSwapper";
    private static final String UTIL = "com.killer560.hub.cheatutils.CheatUtils";
    private static final int[] SPOTS = {14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43};
    private static final String[] PIECES = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};

    private SimMenus1118() {
    }

    private static void cmd(ClientGameTestContext ctx, String line) {
        ctx.runOnClient(mc -> mc.player.connection.sendCommand(line));
        ctx.waitTicks(12);
    }

    private static String title(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getTitle().getString());
    }

    private static String id(ItemStack s) {
        return String.valueOf(ModUnderTest.staticCall(UTIL, "skyblockId", new Class<?>[]{ItemStack.class}, new Object[]{s}));
    }

    private static String menuId(ClientGameTestContext ctx, int slot) {
        return ctx.computeOnClient(mc -> id(mc.player.containerMenu.getSlot(slot).getItem()));
    }

    private static String name(ClientGameTestContext ctx, int slot) {
        return ctx.computeOnClient(mc -> mc.player.containerMenu.getSlot(slot).getItem().getHoverName().getString());
    }

    private static String lore(ClientGameTestContext ctx, int slot) {
        return ctx.computeOnClient(mc -> {
            ItemLore l = mc.player.containerMenu.getSlot(slot).getItem().get(DataComponents.LORE);
            return l == null ? "" : String.join("|", l.lines().stream().map(x -> x.getString()).toList());
        });
    }

    private static void click(ClientGameTestContext ctx, int slot) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                ContainerInput.PICKUP, mc.player));
        ctx.waitTicks(8);
    }

    private static void close(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(4);
    }

    private static void setArmor(ClientGameTestContext ctx, String set) {
        Object v = ModUnderTest.enumValue(ARMOR, set);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(LOADOUT, "setArmor", new Class<?>[]{v.getClass()}, new Object[]{v}));
        ctx.waitTicks(6);
    }

    private static void setSpot(ClientGameTestContext ctx, String set, int n) {
        Object v = ModUnderTest.enumValue(ARMOR, set);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(LOADOUT, "setSpot", new Class<?>[]{v.getClass(), int.class}, new Object[]{v, n}));
    }

    private static String spot(ClientGameTestContext ctx, String set) {
        Object v = ModUnderTest.enumValue(ARMOR, set);
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(LOADOUT, "spot", new Class<?>[]{v.getClass()}, new Object[]{v})));
    }

    private static String armor(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(call(LOADOUT, "armor")));
    }

    private static String head(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> id(mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)));
    }

    private static String simClass(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                Object p = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimMenuPrefs", "cached");
                return String.valueOf(p.getClass().getField("simClass").get(p));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static void stats(ClientGameTestContext ctx, List<String> f) {
        setArmor(ctx, "TERROR");
        cmd(ctx, "stats");
        expect(f, "stats title", title(ctx), "Stats & Equipment");
        for (int i = 0; i < 4; i++) {
            expect(f, "armor column slot " + new int[]{11, 20, 29, 38}[i], menuId(ctx, new int[]{11, 20, 29, 38}[i]), "INFERNAL_TERROR_" + PIECES[i]);
        }
        for (int old : new int[]{21, 22, 23}) expect(f, "the old armor row slot " + old + " holds no piece", menuId(ctx, old), "null");
        expect(f, "close at 49", name(ctx, 49), "Close");
        close(ctx);
    }

    private static void loadouts(ClientGameTestContext ctx, List<String> f) {
        setSpot(ctx, "TERROR", 1);
        setSpot(ctx, "THERMODYNAMIC", 2);
        setArmor(ctx, "NONE");
        cmd(ctx, "loadouts");
        expect(f, "loadouts title", title(ctx), "(1/1) Loadouts");
        expect(f, "spot 1 holds Terror's helmet", menuId(ctx, SPOTS[0]), "INFERNAL_TERROR_HELMET");
        expect(f, "spot 2 holds Thermodynamic's helmet", menuId(ctx, SPOTS[1]), "THERMODYNAMIC_HELMET");
        expect(f, "spot 3 is empty", lore(ctx, SPOTS[2]), "Empty");
        expect(f, "spot 12 is empty", lore(ctx, SPOTS[11]), "Empty");
        expect(f, "spot 1 is named Loadout 1", name(ctx, SPOTS[0]), "Loadout 1");
        expect(f, "nothing worn: the left armor column is empty", menuId(ctx, 10), "null");
        expect(f, "close at 49", name(ctx, 49), "Close");
        expect(f, "the unworn spot offers the equip click", lore(ctx, SPOTS[0]).contains("Left-click to equip!"), true);

        click(ctx, SPOTS[0]);
        expect(f, "a click on spot 1 wears Terror", armor(ctx), "TERROR");
        expect(f, "the helmet is Terror's", head(ctx), "INFERNAL_TERROR_HELMET");
        expect(f, "the left column shows the worn helmet at 10", menuId(ctx, 10), "INFERNAL_TERROR_HELMET");
        expect(f, "the left column shows the worn boots at 37", menuId(ctx, 37), "INFERNAL_TERROR_BOOTS");
        expect(f, "the worn spot loses the equip click", lore(ctx, SPOTS[0]).contains("Left-click to equip!"), false);
        expect(f, "the other spot keeps it", lore(ctx, SPOTS[1]).contains("Left-click to equip!"), true);
        click(ctx, SPOTS[2]);
        expect(f, "an empty spot does nothing", armor(ctx), "TERROR");
        click(ctx, SPOTS[1]);
        expect(f, "a click on spot 2 wears Thermodynamic", armor(ctx), "THERMODYNAMIC");
        expect(f, "the chestplate follows", ctx.computeOnClient(mc -> id(mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST))), "THERMODYNAMIC_CHESTPLATE");
        click(ctx, 49);
        expect(f, "close button closes", title(ctx), "none");

        // Loadout Keybinds' own Open, Swap, Close and the Inventory Sorter's loadout step against the sim menu, unchanged.
        setArmor(ctx, "NONE");
        boolean started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SWAPPER, "requestLoadout",
                new Class<?>[]{Minecraft.class, int.class}, new Object[]{mc, 1}));
        expect(f, "LoadoutSwapper starts Open, Swap, Close for loadout 1", started, true);
        for (int t = 0; t < 120 && ctx.computeOnClient(mc -> (Boolean) call(SWAPPER, "isBusy")); t += 5) ctx.waitTicks(5);
        ctx.waitTicks(6);
        expect(f, "the swap finished and clicked", ctx.computeOnClient(mc -> (Boolean) call(SWAPPER, "lastSwapped")), true);
        expect(f, "Terror is on after the swapper", armor(ctx), "TERROR");
        expect(f, "the swapper closed the menu", title(ctx), "none");

        // Moving a set: two sets never share a spot.
        setSpot(ctx, "TERROR", 5);
        expect(f, "Terror moved to 5", spot(ctx, "TERROR"), "5");
        expect(f, "Thermodynamic stays at 2", spot(ctx, "THERMODYNAMIC"), "2");
        setSpot(ctx, "THERMODYNAMIC", 5);
        expect(f, "Thermodynamic takes 5", spot(ctx, "THERMODYNAMIC"), "5");
        expect(f, "Terror takes Thermodynamic's old spot 2, never the same", spot(ctx, "TERROR"), "2");
        ctx.runOnClient(mc -> call(LOADOUT, "reload"));
        expect(f, "spots survive a reload (Terror)", spot(ctx, "TERROR"), "2");
        expect(f, "spots survive a reload (Thermodynamic)", spot(ctx, "THERMODYNAMIC"), "5");
        setArmor(ctx, "NONE");
        cmd(ctx, "loadouts");
        expect(f, "spot 2 now holds Terror", menuId(ctx, SPOTS[1]), "INFERNAL_TERROR_HELMET");
        expect(f, "spot 5 holds Thermodynamic", menuId(ctx, SPOTS[4]), "THERMODYNAMIC_HELMET");
        expect(f, "spot 1 is empty now", lore(ctx, SPOTS[0]), "Empty");
        click(ctx, SPOTS[4]);
        expect(f, "a click on the moved spot wears Thermodynamic", armor(ctx), "THERMODYNAMIC");
        close(ctx);
        setSpot(ctx, "TERROR", 1);
        setSpot(ctx, "THERMODYNAMIC", 2);
        expect(f, "default spots back (Terror 1)", spot(ctx, "TERROR"), "1");
        expect(f, "default spots back (Thermodynamic 2)", spot(ctx, "THERMODYNAMIC"), "2");
    }

    private static void classes(ClientGameTestContext ctx, List<String> f) {
        cmd(ctx, "class");
        expect(f, "/class opens the class menu", title(ctx), "Dungeon Classes");
        expect(f, "slot 11 is Healer", name(ctx, 11), "Healer");
        expect(f, "slot 13 is Berserk", name(ctx, 13), "Berserk");
        expect(f, "slot 15 is Tank", name(ctx, 15), "Tank");
        click(ctx, 13);
        expect(f, "a click on Berserk picks it", simClass(ctx), "BERSERKER");
        expect(f, "the menu marks the pick", lore(ctx, 13), "Currently selected");
        close(ctx);
        cmd(ctx, "class tank");
        expect(f, "/class tank picks Tank", simClass(ctx), "TANK");
        cmd(ctx, "class berserk");
        expect(f, "/class berserk picks Berserker", simClass(ctx), "BERSERKER");
        cmd(ctx, "class nonsense");
        expect(f, "an unknown class changes nothing", simClass(ctx), "BERSERKER");
        cmd(ctx, "class mage");
        expect(f, "/class mage puts the default back", simClass(ctx), "MAGE");
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        start(ctx, "S1", true);
        try {
            stats(ctx, f);
            loadouts(ctx, f);
            classes(ctx, f);
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            setSpot(ctx, "TERROR", 1);
            setSpot(ctx, "THERMODYNAMIC", 2);
            SimArmor1115.selectNone(ctx);
        }
    }
}
