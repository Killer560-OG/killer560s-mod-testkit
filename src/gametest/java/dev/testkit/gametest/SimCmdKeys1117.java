package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.serverRun;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1117-sim-cmdkeys-all} (simcmdkeys-1117, killer560 2026-11-17: a keybind for /stats or /wd did nothing in the sim): a
 * command keybind bound to /stats, /wd and /items, pressed as a real key, opens the sim's screen; a command the mod sends
 * (ServerCommands.toServer, the custom shortcut path) does too; the /stats screen shows armor and inventory only, a click
 * on an inventory piece swaps it with the worn one (both positions checked) and a click on a worn piece takes it off, a full
 * set taken off saves NONE; /tpto, /goto and /warp coffee3 land at exactly 18.5 / 121.5 / 91.5 yaw 0 pitch 0. The outside-the-sim
 * half (the key sends the command to the server) is {@code 1117-sim-cmdkeys-outside} in the hx session.
 */
final class SimCmdKeys1117 {

    static final String NAME = "1117-sim-cmdkeys-all";
    private static final String KB = "com.killer560.hub.commandkeybinds.CommandKeybindsConfig";
    private static final String GATE = "com.killer560.hub.util.SkyblockGate";
    private static final String LOADOUT = "com.killer560.hub.roomsim.SimLoadout";
    private static final String UTIL = "com.killer560.hub.cheatutils.CheatUtils";
    private static final int[] KEYS = {327, 328, 329, 334, 330};
    private static final int[] ARMOR = {11, 20, 29, 38};
    private static final String[] SET_IDS = {"THERMODYNAMIC_HELMET", "THERMODYNAMIC_CHESTPLATE", "THERMODYNAMIC_LEGGINGS", "THERMODYNAMIC_BOOTS"};

    private SimCmdKeys1117() {
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        SimBossTests.withCapture(f, () -> {
            boolean gateWas = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(GATE, "isEnabled"));
            Object kb = ModUnderTest.config(KB);
            boolean masterWas = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(kb, "isEnabledRaw"));
            start(ctx, "S1", true);
            try {
                keys(ctx, f, kb);
                toServerPath(ctx, f);
                statsScreen(ctx, f);
                coffee3(ctx, f);
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                ctx.runOnClient(mc -> {
                    mc.player.closeContainer();
                    binds(kb).clear();
                    ModUnderTest.set(kb, "setEnabled", masterWas);
                    ModUnderTest.staticCall(GATE, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{gateWas});
                });
                setArmor(ctx, "NONE");
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> binds(Object kb) {
        return (List<Object>) ModUnderTest.call(kb, "binds", new Class<?>[0], new Object[0]);
    }

    private static void addBind(Object kb, int key, String text) {
        ModUnderTest.call(kb, "addBind", new Class<?>[0], new Object[0]);
        List<Object> list = binds(kb);
        Object b = list.get(list.size() - 1);
        try {
            b.getClass().getField("key").setInt(b, key);
            b.getClass().getField("text").set(b, text);
            b.getClass().getField("enabled").setBoolean(b, true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Bind fields: " + e, e);
        }
    }

    private static String screenName(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none"
                : McCompat.screen(mc).getClass().getSimpleName() + "|" + McCompat.screen(mc).getTitle().getString());
    }

    private static void closeAll(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            mc.player.closeContainer();
            McCompat.setScreen(mc, null);
        });
        ctx.waitTicks(4);
    }

    private static void keys(ClientGameTestContext ctx, List<String> f, Object kb) {
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(GATE, "setEnabled", new Class<?>[]{boolean.class}, new Object[]{false});
            ModUnderTest.set(kb, "setEnabled", true);
            binds(kb).clear();
            addBind(kb, KEYS[0], "/stats");
            addBind(kb, KEYS[1], " /wd ");
            addBind(kb, KEYS[2], "/items");
            addBind(kb, KEYS[3], "/equipment");
            McCompat.setScreen(mc, null);
        });
        ctx.waitTicks(3);
        String[] want = {"|Stats & Equipment", "|Wardrobe", "SimItemsScreen|", "|Stats & Equipment"};
        for (int i = 0; i < 4; i++) {
            closeAll(ctx);
            ctx.getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(KEYS[i]), 3);
            ctx.waitTicks(14);
            String got = screenName(ctx);
            println(NAME, "key " + KEYS[i] + " -> " + got);
            expect(f, "key " + i + " opens the sim screen", got.contains(want[i]), true);
        }
        closeAll(ctx);
    }

    private static void toServerPath(ClientGameTestContext ctx, List<String> f) {
        String[] cmds = {"stats", "wd", "items", "item", "eq"};
        String[] want = {"|Stats & Equipment", "|Wardrobe", "SimItemsScreen|", "SimItemsScreen|", "|Stats & Equipment"};
        for (int i = 0; i < cmds.length; i++) {
            closeAll(ctx);
            String c = cmds[i];
            boolean sent = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.util.ServerCommands", "toServer",
                    new Class<?>[]{String.class}, new Object[]{c}));
            ctx.waitTicks(12);
            String got = screenName(ctx);
            println(NAME, "toServer " + c + " -> " + sent + " " + got);
            expect(f, "a mod-sent /" + c + " reaches the sim", got.contains(want[i]), true);
        }
        closeAll(ctx);
    }

    private static void setArmor(ClientGameTestContext ctx, String set) {
        Object e = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimArmor", set);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(LOADOUT, "setArmor", new Class<?>[]{e.getClass()}, new Object[]{e}));
        ctx.waitTicks(8);
    }

    private static String loadoutArmor(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(SimBossTests.call(LOADOUT, "armor")));
    }

    private static String id(ItemStack s) {
        return String.valueOf(ModUnderTest.staticCall(UTIL, "skyblockId", new Class<?>[]{ItemStack.class}, new Object[]{s}));
    }

    private static String worn(ClientGameTestContext ctx, net.minecraft.world.entity.EquipmentSlot slot) {
        return ctx.computeOnClient(mc -> id(mc.player.getItemBySlot(slot)));
    }

    /** First menu slot of the open stats menu (inventory half) holding {@code want}, or -1. */
    private static int find(ClientGameTestContext ctx, String want) {
        return ctx.computeOnClient(mc -> {
            for (int i = 54; i < mc.player.containerMenu.slots.size(); i++) {
                if (want.equals(id(mc.player.containerMenu.getSlot(i).getItem()))) return i;
            }
            return -1;
        });
    }

    private static void click(ClientGameTestContext ctx, int slot) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player));
        ctx.waitTicks(6);
    }

    private static void statsScreen(ClientGameTestContext ctx, List<String> f) {
        var head = net.minecraft.world.entity.EquipmentSlot.HEAD;
        setArmor(ctx, "THERMODYNAMIC");
        SimBossTests.serverRun(ctx, sv -> {
            var inv = sv.getPlayerList().getPlayers().get(0).getInventory();
            for (int i = 0; i < 36; i++) inv.setItem(i, ItemStack.EMPTY);
            ItemStack m = new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putString("id", "BONZO_MASK");
            m.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            inv.setItem(10, m);
        });
        ctx.waitTicks(6);
        expect(f, "Thermodynamic helmet worn first", worn(ctx, head), "THERMODYNAMIC_HELMET");
        closeAll(ctx);
        ctx.runOnClient(mc -> mc.player.connection.sendCommand("stats"));
        ctx.waitTicks(12);
        expect(f, "/stats opened", screenName(ctx).contains("Stats & Equipment"), true);

        // No stat numbers: no paper, no lore line or name with a stat word or a digit-led value.
        String bad = ctx.computeOnClient(mc -> {
            for (int i = 0; i < 54; i++) {
                ItemStack s = mc.player.containerMenu.getSlot(i).getItem();
                if (s.is(net.minecraft.world.item.Items.PAPER)) return "paper at " + i;
                String text = s.getHoverName().getString();
                ItemLore lore = s.get(DataComponents.LORE);
                if (lore != null && i < 54 && !isArmorOrHeld(i)) for (var l : lore.lines()) text += " " + l.getString();
                if (text.matches("(?s).*(Attack Speed|Health|Defense|Arrows|Multiplier|Set Bonus).*") && !isArmorOrHeld(i)) return text + " at " + i;
            }
            return "";
        });
        expect(f, "the stats screen draws no stat text", bad, "");
        int pieceShown = ctx.computeOnClient(mc -> {
            int n = 0;
            for (int s : ARMOR) if (id(mc.player.containerMenu.getSlot(s).getItem()).startsWith("THERMODYNAMIC_")) n++;
            return n;
        });
        expect(f, "the four worn pieces show in the armor slots", pieceShown, 4);

        // Click the mask in the inventory: it goes on, the helmet goes into the SAME inventory slot.
        click(ctx, 55);
        expect(f, "mask is on the head", worn(ctx, head), "BONZO_MASK");
        expect(f, "the old helmet is in the clicked slot", ctx.computeOnClient(mc -> id(mc.player.getInventory().getItem(10))), "THERMODYNAMIC_HELMET");
        expect(f, "the armor slot shows the mask", ctx.computeOnClient(mc -> id(mc.player.containerMenu.getSlot(ARMOR[0]).getItem())), "BONZO_MASK");
        // Click the helmet back: the mask returns to slot 10 and the set is whole again.
        click(ctx, 55);
        expect(f, "helmet back on", worn(ctx, head), "THERMODYNAMIC_HELMET");
        expect(f, "the mask is back in its slot", ctx.computeOnClient(mc -> id(mc.player.getInventory().getItem(10))), "BONZO_MASK");

        // Clicking a worn piece takes it off into the first free slot (inventory slot 9 = menu slot 54 is free).
        click(ctx, ARMOR[1]);
        expect(f, "chestplate taken off", worn(ctx, net.minecraft.world.entity.EquipmentSlot.CHEST), "null");
        expect(f, "the chestplate landed in the first free slot", ctx.computeOnClient(mc -> id(mc.player.getInventory().getItem(9))), "THERMODYNAMIC_CHESTPLATE");
        // Full inventory: nothing happens.
        SimBossTests.serverRun(ctx, sv -> {
            var inv = sv.getPlayerList().getPlayers().get(0).getInventory();
            for (int i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) inv.setItem(i, new ItemStack(net.minecraft.world.item.Items.DIRT));
        });
        ctx.waitTicks(6);
        click(ctx, ARMOR[2]);
        expect(f, "leggings stay on when the inventory is full", worn(ctx, net.minecraft.world.entity.EquipmentSlot.LEGS), "THERMODYNAMIC_LEGGINGS");
        SimBossTests.serverRun(ctx, sv -> {
            var inv = sv.getPlayerList().getPlayers().get(0).getInventory();
            for (int i = 0; i < 36; i++) if (inv.getItem(i).is(net.minecraft.world.item.Items.DIRT)) inv.setItem(i, ItemStack.EMPTY);
        });
        ctx.waitTicks(6);
        // Take the rest off: a set no longer worn saves NONE; put the four back: Thermodynamic again.
        click(ctx, ARMOR[0]);
        click(ctx, ARMOR[2]);
        click(ctx, ARMOR[3]);
        expect(f, "all four off saves the loadout as NONE", loadoutArmor(ctx), "NONE");
        for (String sid : SET_IDS) {
            int slot = find(ctx, sid);
            expect(f, sid + " is in the inventory", slot >= 54, true);
            if (slot >= 54) click(ctx, slot);
        }
        expect(f, "the four pieces back save Thermodynamic", loadoutArmor(ctx), "THERMODYNAMIC");
        closeAll(ctx);
        serverRun(ctx, sv -> {
            var inv = sv.getPlayerList().getPlayers().get(0).getInventory();
            for (int i = 0; i < 36; i++) inv.setItem(i, ItemStack.EMPTY);
        });
        setArmor(ctx, "NONE");
    }

    private static boolean isArmorOrHeld(int slot) {
        for (int a : ARMOR) if (a == slot) return true;
        return slot == 2;
    }

    private static void coffee3(ClientGameTestContext ctx, List<String> f) {
        SimBossTests.start(ctx, "P1", true);
        for (String cmd : new String[]{"tpto coffee3", "warp coffee3", "goto coffee3"}) {
            SimBossTests.tp(ctx, 73.5, 221, 30.5, 77, 33);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand(cmd));
            SimBossTests.until(ctx, "/" + cmd + " landing", mc -> Math.abs(mc.player.getX() - 18.5) < 0.5 && Math.abs(mc.player.getZ() - 91.5) < 0.5, 200);
            ctx.waitTicks(6);
            double[] p = ctx.computeOnClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot()});
            println(NAME, "/" + cmd + " -> " + java.util.Arrays.toString(p));
            double[] want = {18.5, 121.5, 91.5, 0, 0};
            for (int i = 0; i < 5; i++) {
                expect(f, "/" + cmd + " axis " + i + " is exact", Math.abs(p[i] - want[i]) < 0.01, true);
            }
        }
        @SuppressWarnings("unchecked")
        List<String> names = ctx.computeOnClient(mc -> (List<String>) SimBossTests.call("com.killer560.hub.roomsim.SimTeleportCommands", "roomNames"));
        expect(f, "coffee3 is offered in the boss", names.contains("coffee3"), true);
    }
}
