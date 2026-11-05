package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.LeverBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * SAME-TICK GRIM CASES for the P3 auras (grimsame-1105; killer560 2026-11-05, after Leap Takes Target's Look was shipped
 * tested only with an idle tick after its turn). Each aura runs while HIS OWN action goes into every client tick
 * ({@link SameTick}): a right click ({@code use}), his attack on whatever his crosshair is on ({@code attack}), and a hotbar
 * swap between two sticks ({@code swap}). A case passes only when (a) the feature really acted (the server's own record),
 * (b) at least one of GrimAC's ticks carried the feature's packet AND his, and (c) GrimAC said nothing (Session). Run on a
 * checkout with {@code experimental-checks: true} too.
 * <ul>
 *   <li>1105-same-lights-&lt;act&gt;: the Lights Aura flicks the six S2 levers (use_item_on, body turned first).</li>
 *   <li>1105-same-termaura-&lt;act&gt;: Terminal Aura, the stand behind him (body turned, then interact on the stand).</li>
 *   <li>1105-same-arrowaura-&lt;act&gt;: Arrow Align Aura's nine-frame sweep (interact on item frames, a turn each).</li>
 * </ul>
 */
final class SameTickCases {

    private static final SameTick.Act[] ACTS = {SameTick.Act.USE, SameTick.Act.ATTACK, SameTick.Act.SWAP};

    private SameTickCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        // His attack goes on what his crosshair is on: only the terminal stand is something he would hit (a lever would
        // break, an item frame would drop its arrow).
        for (SameTick.Act a : ACTS) {
            String n = a.name().toLowerCase(java.util.Locale.ROOT);
            if (a != SameTick.Act.ATTACK) {
                s.test("1105-same-lights-" + n, c -> lights(c, a));
                s.test("1105-same-arrowaura-" + n, c -> arrowAura(c, a));
            }
            s.test("1105-same-termaura-" + n, c -> termAura(c, a));
        }
    }

    /** Two sticks in slots 4 and 5 (his hand for USE, the swap's two ends), slot 4 held. */
    static void sticks(Session c) {
        c.server().command("item replace entity @a hotbar.4 with stick");
        c.server().command("item replace entity @a hotbar.5 with stick");
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(4);
            return null;
        });
        c.ctx().waitTicks(5);
    }

    static SameTick start(Session c, SameTick.Act act, Predicate<Packet<?>> feature) {
        return c.onClient(mc -> SameTick.start(act, 1, feature, null, 4, 5));
    }

    /** Stops his action, notes the counts, and fails a case that tested nothing. */
    static void judge(Session c, SameTick st, String what) {
        c.onClient(mc -> {
            st.close();
            return null;
        });
        c.note(what + " " + st.report());
        c.check(st.done() > 0, what + ": his action never went in (nothing tested)");
        c.check(st.both() > 0, what + ": no server tick carried both the feature's packet and his - nothing tested ("
                + st.report() + ")");
    }

    // ---- Lights Aura ------------------------------------------------------------------------------------------------

    private static void lights(Session c, SameTick.Act act) throws Exception {
        String cfg = LightsDeviceCases.CFG;
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            c.server().command("forceload add 54 134 66 144");
            c.server().sync(20);
            c.server().command("fill 54 132 134 66 132 144 minecraft:stone");
            c.server().command("fill 54 133 134 66 139 142 minecraft:air");
            c.server().command("fill 56 132 143 64 138 143 minecraft:stone");
            LeverAuraCpsCases.resetLevers(c);
            c.ctx().waitTicks(10);
            TerminalAuraCases.tp(c, 60.5, 133, 139.5, 0f);
            c.ctx().waitTicks(20);
            sticks(c);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(cfg, "AuraEnabled", false)));
            undo.add(c.onClient(mc -> LightsDeviceCases.raw(cfg, "TriggerEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "PreFlick", true)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "Finish", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "ChatFeedback", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "Cps", 20)));
            c.waitUntil("six unlit levers", mc -> {
                for (int[] l : LeverAuraCpsCases.LIGHTS) {
                    var s = mc.level.getBlockState(LeverAuraCpsCases.lever(l));
                    if (!(s.getBlock() instanceof LeverBlock) || s.getValue(LeverBlock.POWERED)) return false;
                }
                return true;
            }, 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            c.ctx().waitTicks(5);
            long since = c.hx().head();
            st = start(c, act, p -> p instanceof ServerboundUseItemOnPacket);
            c.onClient(mc -> {
                Mod.set(cfg, "setAuraEnabled", true);
                return null;
            });
            c.waitUntil("six levers lit by the server", mc -> {
                int n = 0;
                for (int[] l : LeverAuraCpsCases.LIGHTS) {
                    if (mc.level.getBlockState(LeverAuraCpsCases.lever(l)).getValue(LeverBlock.POWERED)) n++;
                }
                return n == LeverAuraCpsCases.LIGHTS.length;
            }, 300);
            c.ctx().waitTicks(5);
            c.note("server use_block " + c.hx().events(since, "use_block").size());
            judge(c, st, "lights aura");
            st = null;
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                Mod.set(cfg, "setAuraEnabled", false);
                BossAuraTurnCases.restore(undo);
                return null;
            });
            c.server().command("fill 54 132 134 66 139 144 minecraft:air");
            c.server().command("forceload remove 54 134 66 144");
            c.server().command("clear @a stick");
            GrimTickCases.home(c);
        }
    }

    // ---- Terminal Aura ----------------------------------------------------------------------------------------------

    private static void termAura(Session c, SameTick.Act act) throws Exception {
        String cfg = TerminalAuraCases.CONFIG;
        int x = TerminalAuraCases.X;
        int z = TerminalAuraCases.Z;
        int g = TerminalAuraCases.G;
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            c.hx().call("boss.term.build", "x", x, "z", z, "groundY", g);
            TerminalAuraCases.tp(c, x - 1.5, g + 3, z + 0.5, 90f); // facing away: the aura turns the body to the stand
            c.ctx().waitTicks(20);
            sticks(c);
            JsonObject stand = c.hx().call("boss.term.spawn").getAsJsonObject();
            int standId = stand.get("standId").getAsInt();
            c.hx().call("boss.term.config", "line", true);
            c.waitUntil("the client to have the terminal stand", mc -> mc.level.getEntity(standId) != null, 200);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.hx().chat(TerminalAuraCases.GOLDOR);
            c.waitUntil("Floor7Tracker phase P3",
                    mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
            undo.add(c.onClient(mc -> Mod.with(cfg, "Range", 3.0)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "DelayMs", 750)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "GroundOnly", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "LeapDelayEnabled", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "PauseOnMovementKeys", false)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "FovDegrees", 360)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "TurnToTerminal", true)));
            undo.add(c.onClient(mc -> Mod.with(cfg, "Enabled", false)));
            int before = c.events("term.interact").size();
            st = start(c, act, p -> p instanceof ServerboundInteractPacket);
            c.onClient(mc -> {
                Mod.set(cfg, "setEnabled", true);
                return null;
            });
            c.waitUntil("the aura's click reaching the server", mc -> c.events("term.interact").size() > before, 100);
            c.ctx().waitTicks(5);
            c.onClient(mc -> {
                Mod.set(cfg, "setEnabled", false);
                return null;
            });
            judge(c, st, "terminal aura");
            st = null;
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            TerminalAuraCases.closeScreen(c);
            c.onClient(mc -> {
                BossAuraTurnCases.restore(undo);
                return null;
            });
            c.hx().call("boss.term.clear");
            c.server().command("clear @a stick");
            GrimTickCases.home(c);
        }
    }

    // ---- Arrow Align Aura -------------------------------------------------------------------------------------------

    private static void arrowAura(Session c, SameTick.Act act) throws Exception {
        String cfg = "arrowalign.ArrowAlignConfig";
        SameTick st = null;
        try (HxKit.Settings off = GrimTickCases.gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(cfg, "SolverEnabled", true)
                     .with(cfg, "PreventMisclicksEnabled", false).with(cfg, "TriggerBotEnabled", false)
                     .with(cfg, "AuraEnabled", false).with(cfg, "AuraRange", 3.0).with(cfg, "AuraCps", 20)
                     .with(cfg, "AuraLeaveLastArrow", false)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            c.server().command("forceload add -3 72 3 82");
            c.server().sync(20);
            c.server().command("fill -1 119 72 3 119 82 stone");
            c.server().command("fill -3 119 74 -3 125 80 stone");
            c.hx().call("dungeon.tp", "x", -0.7, "y", 120.0, "z", 77.5, "yaw", 90f, "pitch", 0f);
            c.waitUntil("standing by the wall", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - 120.0) < 0.01
                    && mc.level.getBlockState(new BlockPos(-3, 122, 77)).is(net.minecraft.world.level.block.Blocks.STONE), 200);
            sticks(c);
            GrimTickCases.grid(c, -1);
            long since = c.hx().head();
            st = start(c, act, p -> p instanceof ServerboundInteractPacket);
            GrimTickCases.aura(c, true);
            c.waitUntil("every frame clicked (server use_entity)", mc -> c.hx().events(since, "use_entity").size() >= 9, 300);
            c.ctx().waitTicks(5);
            GrimTickCases.aura(c, false);
            judge(c, st, "arrow align aura");
            st = null;
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            GrimTickCases.aura(c, false);
            c.hx().removeStands();
            c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
            c.server().command("fill -3 119 72 3 125 82 air");
            c.server().command("forceload remove -3 72 3 82");
            c.server().command("clear @a stick");
            GrimTickCases.home(c);
        }
    }
}
