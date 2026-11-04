package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.SoundProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod powdersound-1104 (killer560, 2026-11-03: "add custom sounds for blocks broken while powder mining or options to mute
 * them entirely"). The client plays a break sound from level event 2001; his own break arrives with himself as the entity
 * (what Block.playerWillDestroy sends), another player's or the server's without one. SoundProbe counts what reaches
 * SoundEngine.play, and the mod's own counters prove the hook acted.
 * <ul>
 *   <li>1104-powdersound-normal: switch OFF in a fake Hollows: the stone break sound plays once, no mute, no custom.</li>
 *   <li>1104-powdersound-mute: Mute in the Hollows: the stone break sound count does not move, the mute count rises;
 *       the same event with no entity (a server's) still plays; outside powder mining (hub) it plays.</li>
 *   <li>1104-powdersound-custom: Custom (Note Pling) in the Hollows: the stone sound does not move, the pling plays.</li>
 * </ul>
 */
final class HxPowderSound1104Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String BS = "powdermining.PowderBreakSound";
    private static final String STATE = "miningqol.MiningState";
    private static final String STONE = "minecraft:block.stone.break";
    private static final String PLING = "minecraft:block.note_block.pling";

    private HxPowderSound1104Cases() {
    }

    static void register(Session s) {
        s.test("1104-powdersound-normal", c -> run(c, false, true));
        s.test("1104-powdersound-mute", c -> run(c, true, true));
        s.test("1104-powdersound-custom", c -> run(c, true, false));
    }

    private static void hollows(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Jungle", "Purse: 0");
        c.hx().tab(List.of("Area: Crystal Hollows", "Profile: Test", "", "Powders:", " Gemstone: 1,000", ""), null, null);
        c.waitUntil("Crystal Hollows", mc -> "CRYSTAL_HOLLOWS".equals(Mod.staticCall(STATE, "island")), 100);
    }

    private static int count(Session c, String id) {
        return c.onClient(mc -> SoundProbe.count(id));
    }

    private static int mod(Session c, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(BS, m)).intValue());
    }

    /** One stone break at his feet: with himself as the entity (his own) or null (the server's). */
    private static void breakStone(Session c, boolean own) {
        c.ctx().runOnClient(mc -> {
            BlockPos p = mc.player.blockPosition().offset(1, 0, 0);
            mc.level.levelEvent(own ? mc.player : null, 2001, p, Block.getId(Blocks.STONE.defaultBlockState()));
        });
        c.ctx().waitTicks(6);
    }

    private static void run(Session c, boolean on, boolean mute) {
        try (Settings set = new Settings(c).field(CFG, "breakSound", on).field(CFG, "breakSoundMute", mute)) {
            hollows(c);
            c.ctx().waitTicks(10);
            int stone0 = count(c, STONE);
            int pling0 = count(c, PLING);
            int mute0 = mod(c, "mutedCount");
            int custom0 = mod(c, "customCount");
            breakStone(c, true);
            int stone = count(c, STONE) - stone0;
            int pling = count(c, PLING) - pling0;
            if (!on) {
                c.check(stone == 1, "switch OFF: the stone sound did not play once (" + stone + ")");
                c.check(mod(c, "mutedCount") == mute0 && mod(c, "customCount") == custom0, "switch OFF: the hook acted");
            } else if (mute) {
                c.check(stone == 0, "Mute: the stone sound still played (" + stone + ")");
                c.check(mod(c, "mutedCount") == mute0 + 1, "Mute: the hook did not act");
                c.check(pling == 0, "Mute: a custom sound played");
                int s1 = count(c, STONE);
                breakStone(c, false);
                c.check(count(c, STONE) == s1 + 1, "Mute: a break with no entity (the server's) was silenced");
                hub(c);
                // A treasure chest from an earlier case (1104-chestgoblin-chest) keeps powder mining on for 2 minutes
                // anywhere, by design; forget it so the hub really is outside powder mining.
                Mod.setField("powdermining.PowderMining", "lastChestAt", -1L);
                c.ctx().waitTicks(10);
                int s2 = count(c, STONE);
                breakStone(c, true);
                c.check(count(c, STONE) == s2 + 1, "Mute: his break outside powder mining was silenced");
            } else {
                c.check(stone == 0, "Custom: the stone sound still played (" + stone + ")");
                c.check(pling >= 1, "Custom: the chosen sound did not play (" + pling + ")");
                c.check(mod(c, "customCount") == custom0 + 1, "Custom: the hook did not act");
            }
            c.note("stone=" + stone + " pling=" + pling + " on=" + on + " mute=" + mute);
        } finally {
            hub(c);
        }
    }
}
