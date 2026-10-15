package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketWatch;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LeverBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 846-boss-lights-aura-cps (mod aura-2, 2026-10-12; was 846-boss-leveraura-cps): the Lights Aura's CPS slider (Lever
 * Aura's until the Lights Device became its own feature, mod lights-aura-1015). The six S2
 * Lights levers are built at their real coordinates (z 142) and pre-flicked in P3 S1; each round flicks all six, and the
 * rate is read off the client tick each use packet went out on: (uses - 1) / (last tick - first tick) x 20, over three rounds per setting (Goldor's line and unpowered
 * levers start each round). 5 and 10 CPS must measure within 15%. 20 CPS is measured and reported, not held to 20: every
 * lever but the one under the crosshair needs the body turned to it a tick before its click (util/TurnFirst, GrimAC
 * RotationPlace otherwise), so a flick a tick is not reachable on six different levers - the same ceiling Arrow Align's
 * nine-frame sweep measured (10.0). GrimAC must stay silent throughout (Session.test).
 */
final class LeverAuraCpsCases {

    private static final String CFG = LightsDeviceCases.CFG;
    private static final int[][] LIGHTS = {{62, 136}, {58, 136}, {60, 135}, {60, 134}, {62, 133}, {58, 133}};
    private static final int ROUNDS = 3;

    private LeverAuraCpsCases() {
    }

    static void register(Session s) {
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            return;
        }
        s.test("846-boss-lights-aura-cps", LeverAuraCpsCases::cps);
    }

    private static BlockPos lever(int[] l) {
        return new BlockPos(l[0], l[1], 142);
    }

    private static void resetLevers(Session c) {
        for (int[] l : LIGHTS) {
            c.server().command("setblock " + l[0] + " " + l[1] + " 142 minecraft:lever[face=wall,facing=north,powered=false]");
        }
    }

    static void cps(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        List<String> results = new ArrayList<>();
        try {
            c.server().command("forceload add 54 134 66 144");
            c.server().sync(20);
            c.server().command("fill 54 132 134 66 132 144 minecraft:stone");
            c.server().command("fill 54 133 134 66 139 142 minecraft:air");
            c.server().command("fill 56 132 143 64 138 143 minecraft:stone");
            resetLevers(c);
            c.ctx().waitTicks(10);
            TerminalAuraCases.tp(c, 60.5, 133, 139.5, 0f); // facing +z, the lever wall 2.5 blocks ahead
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 133) < 0.01),
                    "not standing on the arena floor: " + c.onClient(mc -> mc.player.position()));
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(CFG, "AuraEnabled", false)));
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(CFG, "TriggerEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(CFG, "PreFlick", true)));
            undo.add(c.onClient(mc -> Mod.with(CFG, "Finish", false)));
            undo.add(c.onClient(mc -> Mod.with(CFG, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(CFG, "ChatFeedback", false)));
            undo.add(c.onClient(mc -> Mod.with(CFG, "Cps", 5)));
            c.waitUntil("the six levers on the client", mc -> {
                for (int[] l : LIGHTS) {
                    if (!(mc.level.getBlockState(lever(l)).getBlock() instanceof LeverBlock)) {
                        return false;
                    }
                }
                return true;
            }, 100);
            double[] measured = new double[3];
            int[] settings = {5, 10, 20};
            for (int k = 0; k < settings.length; k++) {
                int cps = settings[k];
                c.onClient(mc -> {
                    Mod.set(CFG, "setCps", cps);
                    return null;
                });
                int gaps = 0;
                long ticks = 0;
                int uses = 0;
                for (int round = 0; round < ROUNDS; round++) {
                    c.onClient(mc -> {
                        Mod.set(CFG, "setAuraEnabled", false);
                        return null;
                    });
                    resetLevers(c);
                    c.waitUntil("six unlit levers", mc -> {
                        for (int[] l : LIGHTS) {
                            var st = mc.level.getBlockState(lever(l));
                            if (!(st.getBlock() instanceof LeverBlock) || st.getValue(LeverBlock.POWERED)) {
                                return false;
                            }
                        }
                        return true;
                    }, 100);
                    c.hx().chat(TerminalAuraCases.GOLDOR); // a new run: the Lights Device forgets which levers it flicked
                    c.waitUntil("Floor7Tracker phase P3",
                            mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
                    c.ctx().waitTicks(5);
                    PacketWatch.start();
                    c.onClient(mc -> {
                        Mod.set(CFG, "setAuraEnabled", true);
                        return null;
                    });
                    List<Integer> at = new ArrayList<>();
                    int seen = 0;
                    for (int t = 0; t < 200 && at.size() < LIGHTS.length; t++) {
                        c.ctx().waitTick();
                        int now = PacketWatch.totalUses();
                        while (seen < now) {
                            at.add(t);
                            seen++;
                        }
                    }
                    PacketWatch.stop();
                    c.onClient(mc -> {
                        Mod.set(CFG, "setAuraEnabled", false);
                        return null;
                    });
                    c.note(String.format(Locale.ROOT, "CPS %d round %d: uses on ticks %s", cps, round + 1, at));
                    c.check(at.size() == LIGHTS.length, "CPS " + cps + " round " + (round + 1) + ": " + at.size()
                            + " flick(s) of " + LIGHTS.length + " - the rate would mean nothing");
                    gaps += at.size() - 1;
                    ticks += at.get(at.size() - 1) - at.get(0);
                    uses += at.size();
                }
                c.ctx().waitTicks(5);
                int lit = c.onClient(mc -> {
                    int n = 0;
                    for (int[] l : LIGHTS) {
                        if (mc.level.getBlockState(lever(l)).getValue(LeverBlock.POWERED)) {
                            n++;
                        }
                    }
                    return n;
                });
                measured[k] = ticks == 0 ? Double.POSITIVE_INFINITY : gaps * 20.0 / ticks;
                String line = String.format(Locale.ROOT, "CPS %d: %d flicks, %d gaps over %d ticks = %.2f CPS measured;"
                        + " %d of 6 levers lit after the last round", cps, uses, gaps, ticks, measured[k], lit);
                results.add(line);
                c.note(line);
                c.check(lit == LIGHTS.length, "CPS " + cps + ": only " + lit + " of the six levers were lit by the server");
            }
            c.check(Math.abs(measured[0] - 5) <= 5 * 0.15, "5 CPS measured " + measured[0]);
            c.check(Math.abs(measured[1] - 10) <= 10 * 0.15, "10 CPS measured " + measured[1]);
            c.check(measured[2] >= measured[1] * 0.9 && measured[2] <= 20.0 + 1e-9,
                    "20 CPS measured " + measured[2] + " (expected at least the 10 CPS rate, never above 20)");
        } finally {
            c.onClient(mc -> {
                Mod.set(CFG, "setAuraEnabled", false);
                BossAuraTurnCases.restore(undo);
                return null;
            });
            results.forEach(r -> System.out.println("[846-boss-lights-aura-cps] RESULT " + r));
            c.server().command("fill 54 132 134 66 139 144 minecraft:air");
            c.server().command("forceload remove 54 134 66 144");
            c.hx().sidebarClear();
            c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
        }
    }
}
