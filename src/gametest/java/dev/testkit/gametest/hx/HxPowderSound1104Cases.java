package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.SoundProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod powdersound-1104 / powdersound-1105 (killer560, 2026-11-05: "it doesnt do what it says. i can still hear the sound of
 * blocks being broken"). On Hypixel the SERVER breaks a mined block, so the break sound comes as the server's level event
 * 2001 (no entity) or a sound packet, and the dig hit sounds are separate block ".hit" sounds; the 1104 hook only touched a
 * client-side break of his own, which Hypixel mining never makes. Every case here counts what reaches SoundEngine.play
 * (SoundProbe), never a counter of the mod's: a server {@code setblock ... destroy} next to him (the server's 2001), a
 * server {@code playsound} of a break and of a hit, and a client-side 2001 with himself as the entity.
 * <ul>
 *   <li>1104-powdersound-normal: switch OFF in a fake Hollows: every one of them plays.</li>
 *   <li>1104-powdersound-mute: Mute in the Hollows: none plays; a break 10 blocks away still does; outside powder
 *       mining (hub) the server's break plays again.</li>
 *   <li>1104-powdersound-custom: Custom (Note Pling): no stone sound plays, each break plays the pling, a hit plays
 *       nothing.</li>
 * </ul>
 */
final class HxPowderSound1104Cases {

    private static final String CFG = "powdermining.PowderMiningConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String BREAK = "minecraft:block.stone.break";
    private static final String HIT = "minecraft:block.stone.hit";
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

    /** Sounds of {@code id} that reached the engine while {@code act} ran and 10 ticks after. */
    private static int heard(Session c, String id, Runnable act) {
        int before = count(c, id);
        act.run();
        c.ctx().waitTicks(10);
        return count(c, id) - before;
    }

    /** A block {@code dx} east of his head, as an absolute "x y z" for server commands. */
    private static String spot(Session c, int dx) {
        BlockPos p = c.onClient(mc -> mc.player.blockPosition().offset(dx, 1, 0));
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** The server breaks a stone next to him (what Hypixel does when he mines one): level event 2001, no entity. */
    private static void serverBreak(Session c) {
        String at = spot(c, 2);
        c.server().command("setblock " + at + " minecraft:stone");
        c.ctx().waitTicks(4);
        c.server().command("setblock " + at + " minecraft:air destroy");
    }

    private static void playsound(Session c, String id, int dx) {
        c.server().command("execute at @p run playsound " + id + " block @p ~" + dx + " ~1 ~ 1 1");
    }

    private static void ownBreak(Session c) {
        c.ctx().runOnClient(mc -> mc.level.levelEvent(mc.player, 2001, mc.player.blockPosition().offset(2, 1, 0),
                Block.getId(Blocks.STONE.defaultBlockState())));
    }

    private static void run(Session c, boolean on, boolean mute) {
        try (Settings set = new Settings(c).field(CFG, "breakSound", on).field(CFG, "breakSoundMute", mute)) {
            hollows(c);
            // A treasure chest from an earlier case keeps powder mining on anywhere for 2 minutes; the Hollows needs none.
            Mod.setField("powdermining.PowderMining", "lastChestAt", -1L);
            c.ctx().waitTicks(10);
            int server = heard(c, BREAK, () -> serverBreak(c));
            int packet = heard(c, BREAK, () -> playsound(c, BREAK, 2));
            int hit = heard(c, HIT, () -> playsound(c, HIT, 2));
            int own = heard(c, BREAK, () -> ownBreak(c));
            int pling0 = count(c, PLING);
            c.note("on=" + on + " mute=" + mute + " server=" + server + " packet=" + packet + " hit=" + hit + " own=" + own);
            if (!on) {
                c.check(server == 1 && packet == 1 && hit == 1 && own == 1,
                        "switch OFF: a break or hit sound did not play once (server " + server + ", packet " + packet
                                + ", hit " + hit + ", own " + own + ")");
                return;
            }
            String mode = mute ? "Mute" : "Custom";
            c.check(server == 0, mode + ": the server's break (level event 2001) still played (" + server + ")");
            c.check(packet == 0, mode + ": the server's break sound packet still played (" + packet + ")");
            c.check(hit == 0, mode + ": the hit sound still played (" + hit + ")");
            c.check(own == 0, mode + ": his own client-side break still played (" + own + ")");
            if (mute) {
                c.check(count(c, PLING) == pling0, "Mute: a custom sound played");
                int far = heard(c, BREAK, () -> playsound(c, BREAK, 10));
                c.check(far == 1, "Mute: a break 10 blocks away was silenced too (" + far + ")");
                hub(c);
                c.ctx().waitTicks(10);
                int outside = heard(c, BREAK, () -> serverBreak(c));
                c.check(outside == 1, "Mute: a break outside powder mining was silenced (" + outside + ")");
            } else {
                int stone = count(c, BREAK);
                int pling = heard(c, PLING, () -> serverBreak(c));
                c.check(pling == 1, "Custom: the chosen sound did not play once for a break (" + pling + ")");
                c.check(heard(c, PLING, () -> playsound(c, HIT, 2)) == 0, "Custom: a hit played the chosen sound");
                c.check(count(c, BREAK) == stone, "Custom: the stone break sound played");
            }
        } finally {
            hub(c);
        }
    }
}
