package dev.testkit.gametest.boss;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LeverBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * leverfreeze-1116 (killer560, 2026-11-16: "the camera should not change for the lever aura"). The Lever Aura turns the
 * real body to a lever behind him (the server must hear the look), while the camera (getViewYRot / getViewXRot) stays
 * where it was before, during and after, and the body is handed back. Each case stands him facing away from the S2 floor
 * lever at its longest reach, runs the Aura for 12 rounds (a lever reset each round, the Aura flicks it again), and checks
 * on the wire that a look packet pointed at the lever went out before the use_item_on, every tick that the camera read
 * the same, and that the lever really flipped. The act variants add his own action in the same ticks (GrimAC judges those
 * runs: the harness fails a case that drew a flag); the reflick case resets the lever every 3 ticks.
 */
final class LeverFreeze1116Cases {

    private static final BlockPos LEVER = BossAuraTurnCases.LEVER;
    private static final String FLOOR_LEVER = "minecraft:lever[face=floor,facing=north,powered=false]";

    private LeverFreeze1116Cases() {
    }

    static void register(Session s) {
        s.test("1116-leverfreeze-plain", c -> run(c, null, false));
        s.test("1116-leverfreeze-reflick", c -> run(c, null, true));
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.ATTACK, SameTick.Act.SWAP, SameTick.Act.CLICK,
                SameTick.Act.TP}) {
            s.test("1116-leverfreeze-" + a.name().toLowerCase(Locale.ROOT), c -> run(c, a, false));
        }
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
            return String.format(Locale.ROOT, "rot %.3f %.3f", m.getYRot(0f), m.getXRot(0f));
        }
        return LeverBest1112Cases.describe(p);
    }

    private static void run(Session c, SameTick.Act act, boolean reflick) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            LeverBest1112Cases.arena(c);
            LeverBest1112Cases.quiet(c, undo);
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            LeverBest1112Cases.p3(c);
            double x = LeverBest1112Cases.xFor(c, 4.47);
            LeverBest1112Cases.standAt(c, x, 124, 127.5, 90f, 0f); // facing west: the lever is behind him (east)
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            if (act == SameTick.Act.ATTACK) {
                c.server().command("summon zombie " + (x - 2.5) + " 124 127.5 {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"leverbest\"]}");
                c.waitUntil("the zombie under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult, 100);
            }
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "CooldownMs", 50)));
            int flags0 = c.scenario().flags().size();
            float[] cam = BossAuraTurnCases.start(c);
            WireCapture.describe = LeverFreeze1116Cases::describe;
            WireCapture.start();
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.ATTACK ? 3 : 1,
                        p -> p instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket, 4, 5));
            }
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "Enabled", true)));
            int flicks = 0;
            for (int r = 0; r < 12; r++) {
                c.server().command("setblock 27 124 127 " + FLOOR_LEVER);
                LeverBest1112Cases.newRun(c);
                if (act == SameTick.Act.CLICK) {
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        return null;
                    });
                    c.ctx().waitTicks(3);
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, null);
                        return null;
                    });
                }
                for (int t = 0; t < 12; t++) {
                    if (act == SameTick.Act.TP && t % 2 == 0) {
                        c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                    }
                    if (reflick && t % 3 == 2 && t < 9) {
                        c.server().command("setblock 27 124 127 " + FLOOR_LEVER);
                    }
                    c.ctx().waitTick();
                    c.onClient(mc -> {
                        BossAuraTurnCases.track(mc, cam);
                        return null;
                    });
                }
                if (c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED))) flicks++;
            }
            c.onClient(mc -> {
                Mod.set(BossAuraTurnCases.LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            BossAuraTurnCases.finish(c, cam, "lever aura " + (act == null ? "alone" : "with his " + act));
            WireCapture.stop();
            List<String> wire = WireCapture.snapshot().stream().map(WireCapture.Sent::text).toList();
            // The body turned to the lever (east, about -90) on the wire, and a flick followed a turned look.
            int turnedAt = -1;
            int flickAt = -1;
            int rots = 0;
            for (int i = 0; i < wire.size(); i++) {
                String w = wire.get(i);
                if (w.startsWith("rot ")) {
                    rots++;
                    float yaw = Float.parseFloat(w.split(" ")[1]);
                    if (turnedAt < 0 && Math.abs(Mth.wrapDegrees(yaw - cam[0])) > 60f) turnedAt = i;
                } else if (w.startsWith("use_item_on") && w.contains(LEVER.toShortString()) && flickAt < 0) {
                    flickAt = i;
                }
            }
            c.note("flicked in " + flicks + " of 12 rounds, " + rots + " look packets, first turned look at wire #" + turnedAt
                    + ", first flick at #" + flickAt + ", camera moved at most " + cam[4]);
            c.check(flicks >= (act == SameTick.Act.TP || act == SameTick.Act.ATTACK ? 2 : 6),
                    "the Lever Aura flicked in only " + flicks + " of 12 rounds (the feature did not act)");
            long onWire = wire.stream().filter(w -> w.startsWith("use_item_on") && w.contains(LEVER.toShortString())).count();
            c.check(!reflick || onWire > 12, "the lever was reset 3 times a round but only " + onWire + " flicks went out in 12 rounds");
            c.check(turnedAt >= 0, "no look packet turned away from his yaw " + cam[0] + ": the real body never turned");
            c.check(flickAt >= 0 && turnedAt <= flickAt, "the first flick (#" + flickAt + ") did not follow a turned look (#" + turnedAt + ")");
            if (act != null) {
                SameTick s2 = st;
                st = null;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
                c.check(s2.done() > 0, "his " + act + " never went in (nothing tested)");
                c.check(s2.featureWindows() > 0, "the Aura sent nothing while his " + act + " ran");
            }
            c.check(c.scenario().flags().size() == flags0, "GrimAC flagged with the lever turns (act " + act + ")");
        } finally {
            WireCapture.describe = null;
            WireCapture.stop();
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            LeverBest1112Cases.cleanup(c, undo);
        }
    }
}
