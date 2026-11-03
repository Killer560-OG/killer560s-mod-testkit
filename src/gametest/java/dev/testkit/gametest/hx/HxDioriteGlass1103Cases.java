package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch f7fix-1103 (killer560, 2026-11-03: "the pillars in storm should always be glass if i have that setting on not
 * only during storm. also if the pillar goes down then those blocks arent instantly set to glass it has to update over
 * time"). I Hate Diorite (cheat-only): in the F7/M7 boss, with no Storm line ever sent, a diorite block the server sets
 * inside a pillar column is glass on the client from the first tick it exists; a diorite block outside every pillar, or
 * outside the boss, stays diorite.
 */
final class HxDioriteGlass1103Cases {

    private static final String CFG = "diorite.DioriteGlassConfig";
    private static final String DS = "secrets.DungeonState";

    private HxDioriteGlass1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-hx-diorite-glass", HxDioriteGlass1103Cases::glass);
    }

    private static String blockAt(Session c, BlockPos p) {
        return c.onClient(mc -> BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath());
    }

    private static void glass(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: I Hate Diorite is cheat-only, nothing to check");
            return;
        }
        String self = self(c);
        BlockPos inPillar = new BlockPos(47, 175, 42);
        BlockPos inPillar2 = new BlockPos(48, 190, 43);
        BlockPos outside = new BlockPos(60, 175, 42);
        boolean override = c.onClient(mc -> (Boolean) Mod.staticCall(DS, "isSimOverrideActive"));
        try (Settings st = new Settings(c)) {
            st.with(CFG, "Enabled", true);
            c.server().command("tp " + self + " 47 172 42");
            c.server().command("gamemode creative " + self);
            c.ctx().waitTicks(40);
            // Not in the boss: the setting does nothing (control).
            if (override) {
                run(c, () -> Mod.staticCall(DS, "toggleSimOverride"));
            }
            c.server().command("setblock " + inPillar.getX() + " " + inPillar.getY() + " " + inPillar.getZ() + " minecraft:diorite");
            c.waitUntil("diorite arrives outside the boss", mc -> "diorite".equals(blockAt(c, inPillar)), 40);
            c.ctx().waitTicks(25);
            c.check("diorite".equals(blockAt(c, inPillar)), "a pillar block became glass outside the boss: " + blockAt(c, inPillar));
            c.server().command("setblock " + inPillar.getX() + " " + inPillar.getY() + " " + inPillar.getZ() + " minecraft:air");
            c.ctx().waitTicks(5);

            // In the F7 boss, NO Storm line sent.
            run(c, () -> Mod.staticCall(DS, "toggleSimOverride"));
            c.check(c.onClient(mc -> (Boolean) Mod.staticCall(DS, "isBossPhaseActive")), "premise: in the boss");
            c.ctx().waitTicks(2);
            List<String> firstSeen = new ArrayList<>();
            for (BlockPos p : new BlockPos[]{inPillar, inPillar2, outside}) {
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:polished_diorite");
                String first = null;
                for (int i = 0; i < 20 && first == null; i++) {
                    String b = blockAt(c, p);
                    if (!b.equals("air")) {
                        first = b;
                    } else {
                        c.ctx().waitTicks(1);
                    }
                }
                firstSeen.add(p.toShortString() + " first reads " + first);
                if (p == outside) {
                    c.ctx().waitTicks(25);
                    c.check("polished_diorite".equals(blockAt(c, p)), "a block outside every pillar changed: " + blockAt(c, p));
                } else {
                    c.check(first != null && first.endsWith("stained_glass"),
                            "the server's polished diorite at " + p.toShortString() + " first showed as " + first + ", not glass");
                }
            }
            c.note("block changes the tick they arrive: " + firstSeen);
            for (BlockPos p : new BlockPos[]{inPillar, inPillar2, outside}) {
                c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " minecraft:air");
            }
            c.ctx().waitTicks(5);
        } finally {
            if (c.onClient(mc -> (Boolean) Mod.staticCall(DS, "isSimOverrideActive")) != override) {
                run(c, () -> Mod.staticCall(DS, "toggleSimOverride"));
            }
        }
    }
}
