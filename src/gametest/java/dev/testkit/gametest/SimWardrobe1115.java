package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1115-sim-wardrobe-*} (simwardrobe-1115, killer560 2026-11-15: "add in custom wardrobe and stats menu and stuff to
 * swap things between"). The sim's /wardrobe: open it, click a set, and the worn pieces, the saved loadout and the next bow
 * shot follow; the stats menu lists numbers that change with the set. Every check reads what the menu really held or what
 * the sim did, never only that no error came.
 */
final class SimWardrobe1115 {

    static final String NAME = "1115-sim-wardrobe";
    private static final String WARD = "com.killer560.hub.roomsim.SimWardrobe";
    private static final int[] SET_SLOTS = {20, 22, 24};

    private SimWardrobe1115() {
    }

    private static boolean open(ClientGameTestContext ctx, boolean stats) {
        boolean ok = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(WARD, "open",
                new Class<?>[]{Minecraft.class, boolean.class}, new Object[]{mc, stats}));
        ctx.waitTicks(8);
        return ok;
    }

    private static String title(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "none" : McCompat.screen(mc).getTitle().getString());
    }

    private static void click(ClientGameTestContext ctx, int slot) {
        ctx.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0,
                ContainerInput.PICKUP, mc.player));
        ctx.waitTicks(6);
    }

    private static void close(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.player.closeContainer());
        ctx.waitTicks(4);
    }

    /** Menu slots' hover name -> first lore line, for the open menu. */
    private static Map<String, String> sheet(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < 54; i++) {
                ItemStack s = mc.player.containerMenu.getSlot(i).getItem();
                ItemLore lore = s.get(DataComponents.LORE);
                m.put(s.getHoverName().getString(), lore == null || lore.lines().isEmpty() ? "" : lore.lines().get(0).getString());
            }
            return m;
        });
    }

    private static int arrows(ClientGameTestContext ctx) {
        return (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired");
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        start(ctx, "S1", true);
        try {
            expect(f, "wardrobe opens in the sim", open(ctx, false), true);
            expect(f, "wardrobe title", title(ctx), "Wardrobe");
            Map<String, String> w0 = sheet(ctx);
            println(NAME, "wardrobe: " + w0.entrySet().stream().filter(e -> e.getKey().contains("Armor") || e.getKey().equals("Stats")).toList());
            expect(f, "None starts equipped", w0.get("No Armor"), "Equipped");
            expect(f, "Terror waits to be equipped", w0.get("Terror Armor"), "Click to equip");

            click(ctx, SET_SLOTS[2]);
            Map<String, String> w1 = sheet(ctx);
            expect(f, "click on Thermodynamic equips it in the menu", w1.get("Thermodynamic Armor"), "Equipped");
            expect(f, "the old set is no longer marked", w1.get("No Armor"), "Click to equip");
            expect(f, "the loadout now holds Thermodynamic", ctx.computeOnClient(mc -> String.valueOf(call("com.killer560.hub.roomsim.SimLoadout", "armor"))), "THERMODYNAMIC");
            close(ctx);
            String worn = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                    new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)})));
            expect(f, "the chestplate is on", worn, "THERMODYNAMIC_CHESTPLATE");

            // Stats of Thermodynamic, then swap to Terror from the wardrobe and read them again.
            expect(f, "stats menu opens", open(ctx, true), true);
            expect(f, "stats title", title(ctx), "Your Equipment and Stats");
            Map<String, String> st = sheet(ctx);
            println(NAME, "Thermodynamic stats: " + st);
            expect(f, "Thermodynamic Attack Speed", st.get("Attack Speed (effective)"), "150");
            expect(f, "Thermodynamic arrows per shot", st.get("Bow Arrows Per Shot"), "3");
            expect(f, "Thermodynamic set bonus", st.get("Set Bonus"), "Newton's Demise");
            close(ctx);

            open(ctx, false);
            click(ctx, SET_SLOTS[1]);
            Map<String, String> w2 = sheet(ctx);
            expect(f, "Terror equipped from the menu", w2.get("Terror Armor"), "Equipped");
            close(ctx);
            expect(f, "the chestplate swapped", ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                    new Class<?>[]{ItemStack.class}, new Object[]{mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)}))), "INFERNAL_TERROR_CHESTPLATE");
            open(ctx, true);
            Map<String, String> st2 = sheet(ctx);
            println(NAME, "Terror stats: " + st2);
            expect(f, "Terror Attack Speed back to base", st2.get("Attack Speed (effective)"), "100");
            expect(f, "Terror arrows per shot", st2.get("Bow Arrows Per Shot"), "5");
            expect(f, "Terror damage multiplier", st2.get("Arrow Damage Multiplier"), "2.5");
            expect(f, "Terror health is listed and summed from the pieces", st2.get("Health"), "1828");
            expect(f, "the stats differ between the sets", st.equals(st2), false);
            close(ctx);

            // The next bow shot follows the swap.
            int before = arrows(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTerminator", "use",
                    new Class<?>[]{Minecraft.class, boolean.class}, new Object[]{mc, false}));
            ctx.waitTicks(6);
            expect(f, "next shot fires the Terror 5 arrows", arrows(ctx) - before, 5);

            // Saved on disk and read back after a restart.
            ctx.runOnClient(mc -> call("com.killer560.hub.roomsim.SimLoadout", "reload"));
            expect(f, "reload (a restart) keeps Terror", ctx.computeOnClient(mc -> String.valueOf(call("com.killer560.hub.roomsim.SimLoadout", "armor"))), "TERROR");

            // Barrier button closes the wardrobe.
            open(ctx, false);
            click(ctx, 53);
            expect(f, "Close button closes the menu", title(ctx), "none");
        } finally {
            ctx.runOnClient(mc -> mc.player.closeContainer());
            SimArmor1115.selectNone(ctx);
        }
    }
}
