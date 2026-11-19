package dev.testkit.gametest.boss;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SAME-TICK GRIM CASES for Auto i4's Terror Aim (i4terror-1116, killer560 2026-11-16: with Terror and full Hydra Strike
 * stacks Auto i4 aims so the spread covers more targets, "same with machine gun shortbow"). He wears four Terror pieces
 * (Skyblock ids TERROR_*), the action bar shows 10 stacks ({@code 10⁑}), and the server fires the bow as Hypixel does with
 * two extra arrows ({@code dungeon.shortbow} extraArrows 2). Each lit target must get a shot, Terror Aim must weigh every
 * shot ({@code aimsWeighedForTest}) and, with the Machine Gun Shortbow, choose its own aim ({@code aimsChosenForTest}), and his own action goes into the same ticks ({@link SameTick}): a use
 * (with the bow, a shot of his own), an attack on a mob his crosshair is on, a hotbar swap between two bows, a container
 * click (inventory opened, clicked and closed between ticks), a teleport accept, and (act {@code again}) the feature run
 * again right behind itself (targets lit back to back, a target lit again while its shot flies). The wire
 * ({@link WireCapture}, encoder level) must show his packet and a use_item inside one server tick; GrimAC must say nothing.
 * Run on a checkout with {@code experimental-checks: true} too.
 */
final class I4Terror1116Cases {

    static final String PREFIX = "1116-same-i4terror-";
    private static final String CFG = GrimTickCases.I4_CFG;
    private static final String[][] BOWS = {{"term", "TERMINATOR", "TERMINATOR"}, {"mg", "MACHINE_GUN_BOW", "MACHINE_GUN_SHORTBOW"}};
    private static final SameTick.Act[] ACTS = {SameTick.Act.USE, SameTick.Act.ATTACK, SameTick.Act.SWAP, SameTick.Act.OPEN_CLICK,
            SameTick.Act.TP};

    private I4Terror1116Cases() {
    }

    static void register(Session s) {
        for (String[] bow : BOWS) {
            for (SameTick.Act a : ACTS) {
                s.test(PREFIX + bow[0] + "-" + a.name().toLowerCase(Locale.ROOT).replace("_", ""), c -> run(c, bow, a));
            }
            s.test(PREFIX + bow[0] + "-again", c -> run(c, bow, null));
        }
    }

    private static void run(Session c, String[] bow, SameTick.Act act) throws Exception {
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c);
             HxKit.Settings ip = GrimTickCases.hypixelIp(c);
             HxKit.Settings on = new HxKit.Settings(c).with(CFG, "AutoSwapToBow", false).with(CFG, "AutoMask", false)
                     .with(CFG, "AutoI4Weapon", Mod.enumValue(Mod.ROOT + CFG + "$Weapon", bow[2]))
                     .with(CFG, "AutoI4Predictions", false).with(CFG, "ShotAccuracyPercent", 100)
                     .with(CFG, "AutoI4Rotate", true).with(CFG, "AutoI4RotationTimeMs", 150)
                     .with(CFG, "AutoI4TerrorAim", true)
                     .custom(() -> Mod.call(Mod.cfg(CFG), "setDelayRange", 143, 143), GrimTickCases.restoreCps(c))
                     .with(CFG, "AutoI4Enabled", false)) {
            GrimTickCases.i4Platform(c, bow[1]);
            // A second bow in slot 1: his SWAP moves between two bows, so Auto i4 always holds one.
            c.server().command("item replace entity @a hotbar.1 with bow[custom_data={id:\"" + bow[1] + "\"}]");
            for (String[] piece : new String[][]{{"head", "leather_helmet", "HELMET"}, {"chest", "leather_chestplate", "CHESTPLATE"},
                    {"legs", "leather_leggings", "LEGGINGS"}, {"feet", "leather_boots", "BOOTS"}}) {
                c.server().command("item replace entity @a armor." + piece[0] + " with " + piece[1]
                        + "[custom_data={id:\"TERROR_" + piece[2] + "\"}]");
            }
            c.hx().call("dungeon.shortbow", "enabled", true, "cooldownTicks", 3, "extraArrows", 2);
            c.hx().actionBarPacket("§c4000/4000❤     §b10⁑     §a600❈ Defense");
            if (act == SameTick.Act.ATTACK) {
                // A box filling his view toward the wall, within reach: his click hits it whatever row he aims at. An
                // interaction entity, because arrows pass through it (canBeHitByProjectile false); an invulnerable slime
                // bounced every arrow back, knocked him off the plate and Auto i4 stopped after the first target.
                c.server().command("summon interaction 63.5 125.5 40.5 {width:6f,height:6f,response:1b,Tags:[\"i4terror\"]}");
            }
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            c.ctx().waitTicks(10);
            int flags0 = c.scenario().flags().size();
            int aims0 = c.onClient(mc -> (Integer) Mod.staticCall("i4sensors.TerrorAim", "aimsChosenForTest"));
            int weighed0 = c.onClient(mc -> (Integer) Mod.staticCall("i4sensors.TerrorAim", "aimsWeighedForTest"));
            WireCapture.start();
            if (act != null) {
                SameTick.Act a = act;
                st = c.onClient(mc -> SameTick.start(a, a == SameTick.Act.ATTACK ? 2 : 1, p -> p instanceof ServerboundUseItemPacket, 0, 1));
            }
            c.onClient(mc -> {
                Mod.set(CFG, "setAutoI4Enabled", true);
                return null;
            });
            c.ctx().waitTicks(5);
            BlockPos[] targets = GrimTickCases.I4_TARGETS;
            int rounds = act == null ? 2 : 1;
            List<String> perTarget = new ArrayList<>();
            boolean allShot = true;
            for (int r = 0; r < rounds; r++) {
                for (BlockPos t : targets) {
                    c.hx().actionBarPacket("§c4000/4000❤     §b10⁑     §a600❈ Defense");
                    long mark = c.hx().head();
                    String at = t.getX() + " " + t.getY() + " " + t.getZ();
                    c.server().command("setblock " + at + " emerald_block");
                    int lit = act == null ? 8 : 30;
                    for (int k = 0; k < lit; k++) {
                        if (act == SameTick.Act.TP && k % 2 == 0) {
                            c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                        }
                        if (act == null && k == 4) {
                            // Lit again while its shot is in the air: the feature runs again on the same target.
                            c.server().command("setblock " + at + " blue_terracotta");
                            c.ctx().waitTick();
                            c.server().command("setblock " + at + " emerald_block");
                        }
                        c.ctx().waitTick();
                    }
                    int shots = c.hx().events(mark, "use_item").size();
                    perTarget.add(at + ":" + shots);
                    allShot &= shots > 0;
                    c.server().command("setblock " + at + " blue_terracotta");
                    c.ctx().waitTicks(act == null ? 1 : 5);
                }
            }
            c.onClient(mc -> {
                Mod.set(CFG, "setAutoI4Enabled", false);
                return null;
            });
            c.ctx().waitTicks(5);
            WireCapture.stop();
            int aims = c.onClient(mc -> (Integer) Mod.staticCall("i4sensors.TerrorAim", "aimsChosenForTest")) - aims0;
            int weighed = c.onClient(mc -> (Integer) Mod.staticCall("i4sensors.TerrorAim", "aimsWeighedForTest")) - weighed0;
            int[] wire = sharedOnWire(act);
            c.note(String.format(Locale.ROOT, "%s %s: shots per lit target %s, Terror Aim points %d of %d weighed, wire: %d server ticks with a use_item, %d with his %s in the same tick",
                    bow[2], act == null ? "again" : act, perTarget, aims, weighed, wire[0], wire[1], act == null ? "second use" : act));
            c.check(allShot, "a lit target got no shot: " + perTarget);
            c.check(weighed > 0, "Terror Aim never weighed a shot (with Terror and 10 stacks): the feature did not act");
            // The Terminator's usual between-column aim already reaches the whole row with 5 arrows, so it keeps it; the
            // Machine Gun Shortbow's 3 arrows gain from an aim of their own.
            if (!"TERMINATOR".equals(bow[2])) {
                c.check(aims > 0, "Terror Aim chose no aim point (with Terror and 10 stacks): the feature did not act");
            }
            if (act != null) {
                SameTick s2 = st;
                st = null;
                SameTick.judge(c, s2, "auto i4 terror aim", act == SameTick.Act.ATTACK || act == SameTick.Act.USE
                        ? SameTick.Expect.ACTED : SameTick.Expect.SHARE);
                if (act != SameTick.Act.ATTACK && act != SameTick.Act.USE) {
                    c.check(wire[1] > 0, "the wire never carried his " + act + " and a use_item in one server tick (nothing tested)");
                }
            }
            c.check(c.scenario().flags().size() == flags0, "GrimAC flagged Terror Aim (" + bow[2] + ", " + (act == null ? "again" : act) + ")");
        } finally {
            WireCapture.stop();
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.server().command("kill @e[tag=i4terror]");
            GrimTickCases.i4Cleanup(c);
        }
    }

    /** Server ticks on the wire (split at a move or a tick end) with a use_item, and those also carrying his act's packet. */
    private static int[] sharedOnWire(SameTick.Act act) {
        String his = act == null ? "UseItemPacket" : switch (act) {
            case USE -> "UseItemPacket";
            case ATTACK -> "Attack";
            case SWAP -> "SetCarriedItemPacket";
            case OPEN_CLICK -> "ContainerClickPacket";
            case TP -> "AcceptTeleportationPacket";
            default -> "?";
        };
        int uses = 0;
        int shared = 0;
        int useN = 0;
        boolean hisSeen = false;
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            String n = s.className();
            if (n.endsWith("ServerboundUseItemPacket")) {
                useN++;
            } else if (n.contains(his) || (act == SameTick.Act.ATTACK && n.endsWith("ServerboundInteractPacket"))) {
                hisSeen = true;
            }
            // A tick ends at client_tick_end only: a teleport accept is followed by its own move in the same tick.
            if (n.endsWith("ClientTickEndPacket")) {
                if (useN > 0) {
                    uses++;
                    boolean both = "UseItemPacket".equals(his) ? useN >= 2 : hisSeen;
                    if (both) shared++;
                }
                useN = 0;
                hisSeen = false;
            }
        }
        return new int[]{uses, shared};
    }
}
