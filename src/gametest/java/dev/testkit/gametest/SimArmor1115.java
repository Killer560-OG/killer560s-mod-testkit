package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.testkit.gametest.SimBossTests.call;
import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1115-sim-armor-*} (simarmor-1115, killer560 2026-11-15: "for dungeon sim can you add two different armors. first is
 * terror ... then the thermodynamic armor as well", "assume i have max attack speed to fill its cap"). Sim Armor in the loadout
 * file: None, Terror (Hydra Strike always at 10 stacks) or Thermodynamic (Newton's Demise: Attack Speed at the raised cap).
 * Run in the boss session, S1, master mode; every check reads what the sim really did (arrows spawned, each arrow's damage,
 * the cadence readyToFire enforces, the worn pieces, the file on disk).
 */
final class SimArmor1115 {

    static final String NAME = "1115-sim-armor";
    private static final String R = "com.killer560.hub.roomsim.";
    private static final String TERM = R + "SimTerminator";
    private static final String LOADOUT = R + "SimLoadout";
    private static final String ARMOR = R + "SimArmor";
    private static final String AS = R + "SimAttackSpeed";
    private static final double BASE_DAMAGE = 10_000;

    private SimArmor1115() {
    }

    private static void select(ClientGameTestContext ctx, String set) {
        Object v = ModUnderTest.enumValue(ARMOR, set);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(LOADOUT, "setArmor", new Class<?>[]{v.getClass()}, new Object[]{v}));
        ctx.waitTicks(4);
    }

    private static int arrows(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) call(TERM, "arrowsFired"));
    }

    /** One right-click volley; returns {arrows spawned, damages of that volley}. */
    private static Object[] volley(ClientGameTestContext ctx) {
        int before = arrows(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(TERM, "use", new Class<?>[]{Minecraft.class, boolean.class}, new Object[]{mc, false}));
        ctx.waitTicks(6);
        double[] d = (double[]) call(TERM, "lastShotDamagesForTest");
        return new Object[]{arrows(ctx) - before, d};
    }

    /** Client ticks between two right-click shots the sim lets through (its real gate, readyToFire). */
    private static int cadence(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(TERM, "reset");
            int guard = 0;
            while (!(Boolean) call(TERM, "readyToFire") && guard++ < 50) {
                call(TERM, "tick");
            }
            int gap = 0;
            do {
                call(TERM, "tick");
                gap++;
            } while (!(Boolean) call(TERM, "readyToFire") && gap < 50);
            return gap;
        });
    }

    private static String worn(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            StringBuilder b = new StringBuilder();
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                Object id = ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "skyblockId",
                        new Class<?>[]{net.minecraft.world.item.ItemStack.class}, new Object[]{mc.player.getItemBySlot(s)});
                b.append(id).append(' ');
            }
            return b.toString().trim();
        });
    }

    private static String file() throws Exception {
        var fld = Class.forName(LOADOUT).getDeclaredField("FILE");
        fld.setAccessible(true);
        Path p = (Path) fld.get(null);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    static void run(ClientGameTestContext ctx, List<String> f) throws Exception {
        start(ctx, "S1", true);
        try {
            select(ctx, "NONE");
            Object[] none = volley(ctx);
            double[] dn = (double[]) none[1];
            println(NAME, "None: " + none[0] + " arrows, damages " + java.util.Arrays.toString(dn));
            expect(f, "None: arrows in one volley", none[0], 3);
            expect(f, "None: every arrow does the base damage", java.util.Arrays.stream(dn).allMatch(x -> x == BASE_DAMAGE), true);
            expect(f, "None: Attack Speed", ctx.computeOnClient(mc -> call(AS, "attackSpeed")), 100);
            int gapNone = cadence(ctx);
            expect(f, "None: shot gap the gate enforces (ticks)", gapNone, 5);
            expect(f, "None: nothing worn", worn(ctx), "null null null null");

            select(ctx, "TERROR");
            Object[] terror = volley(ctx);
            double[] dt = (double[]) terror[1];
            println(NAME, "Terror: " + terror[0] + " arrows, damages " + java.util.Arrays.toString(dt));
            expect(f, "Terror: arrows in one volley (3 + 2 Hydra Strike)", terror[0], 5);
            double full = BASE_DAMAGE * 2.5;
            expect(f, "Terror: the three main arrows at 10 stacks (+150%)", dt.length == 5 && dt[1] == full && dt[2] == full && dt[3] == full, true);
            expect(f, "Terror: the two extra arrows at 75% of that", dt.length == 5 && dt[0] == full * 0.75 && dt[4] == full * 0.75, true);
            expect(f, "Terror: Attack Speed unchanged", ctx.computeOnClient(mc -> call(AS, "attackSpeed")), 100);
            expect(f, "Terror: four pieces worn", worn(ctx),
                    "INFERNAL_TERROR_HELMET INFERNAL_TERROR_CHESTPLATE INFERNAL_TERROR_LEGGINGS INFERNAL_TERROR_BOOTS");
            String file = file();
            expect(f, "Terror: saved in the loadout file", file.contains("\"armor\": \"TERROR\""), true);
            ctx.runOnClient(mc -> call(LOADOUT, "reload"));
            expect(f, "Terror: reload (a restart) reads it back", ctx.computeOnClient(mc -> String.valueOf(call(LOADOUT, "armor"))), "TERROR");

            select(ctx, "THERMODYNAMIC");
            Object[] th = volley(ctx);
            println(NAME, "Thermodynamic: " + th[0] + " arrows");
            expect(f, "Thermodynamic: arrows in one volley (no extras)", th[0], 3);
            expect(f, "Thermodynamic: Attack Speed at the raised cap (100 + 50)", ctx.computeOnClient(mc -> call(AS, "attackSpeed")), 150);
            expect(f, "Thermodynamic: melee interval", ctx.computeOnClient(mc -> call(AS, "meleeInterval")), 4);
            expect(f, "Thermodynamic: shot gap the gate enforces (ticks)", cadence(ctx), 4);
            expect(f, "Thermodynamic: four pieces worn", worn(ctx),
                    "THERMODYNAMIC_HELMET THERMODYNAMIC_CHESTPLATE THERMODYNAMIC_LEGGINGS THERMODYNAMIC_BOOTS");
            expect(f, "Thermodynamic: saved in the loadout file", file().contains("\"armor\": \"THERMODYNAMIC\""), true);

            select(ctx, "NONE");
            expect(f, "None again: our pieces come off", worn(ctx), "null null null null");
            expect(f, "None again: Attack Speed back to 100", ctx.computeOnClient(mc -> call(AS, "attackSpeed")), 100);
        } finally {
            select(ctx, "NONE");
        }
    }
}
