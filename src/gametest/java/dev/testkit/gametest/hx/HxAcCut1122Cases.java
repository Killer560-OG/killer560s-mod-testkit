package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

import static dev.testkit.gametest.hx.HxAcDoorOnly1115Cases.*;
import static dev.testkit.gametest.hx.HxKit.*;

/**
 * SAME-TICK GRIM CASES for the Quiz countdown cut (acplan2-1121, cheat jar only): Auto Secret stops a clear it started in
 * the countdown with {@code AutoClearFeature.cancel()} on whatever tick the trip back becomes due, mid turn, mid key swap
 * or right after a click, and the next action (the trip back) starts on the following tick. The Hx server has no Quiz, so
 * the case drives the same two calls on Door Only's staged door (HxAcDoorOnly1115Cases): cancel at a tick offset that steps
 * 1..7 after each start, so every point of the turn / swap / click sequence is cut, then start again the next tick, with
 * HIS action ({@link SameTick}) in those ticks. GrimAC (default and experimental checkouts) must stay silent, and the
 * feature's use_item_on and his action must both reach the wire.
 * <ul>
 *   <li>1122-ac-cut-same-&lt;idle|use|attack|swap|tp&gt;</li>
 * </ul>
 */
final class HxAcCut1122Cases {

    private HxAcCut1122Cases() {
    }

    static void register(Session s) {
        for (String a : new String[]{"idle", "use", "attack", "swap", "tp"}) {
            s.test("1122-ac-cut-same-" + a, c -> cut(c, a));
        }
    }

    private static void cut(Session c, String a) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Clear is cheat-only");
            return;
        }
        SameTick.Act act = switch (a) {
            case "use" -> SameTick.Act.USE;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        BlockPos door = null;
        SameTick st = null;
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "DoorOnly", true)) {
            door = stage(c, act == SameTick.Act.ATTACK);
            long since = c.hx().head();
            startAc(c);
            c.waitUntil("Door Only running", mc -> (Boolean) Mod.staticCall(AC, "isBusy"), 40);
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.USE ? 2 : 3,
                        p -> p instanceof ServerboundUseItemOnPacket, 4, 5));
            }
            int cuts = 0;
            int offset = 1;
            int sinceStart = 0;
            for (int i = 0; i < 300; i++) {
                if (act == SameTick.Act.TP && i % 2 == 0) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                if (++sinceStart == offset) {
                    boolean wasBusy = c.onClient(mc -> {
                        boolean b = (Boolean) Mod.staticCall(AC, "isBusy");
                        Mod.staticCall(AC, "cancel");
                        return b;
                    });
                    cuts += wasBusy ? 1 : 0;
                    c.ctx().waitTick();
                    startAc(c);           // the next action, on the next tick (as the trip back after a cut)
                    sinceStart = 0;
                    offset = offset % 7 + 1;
                }
                c.ctx().waitTick();
            }
            int uses = blockUses(c, since);
            c.note("cut " + a + ": " + cuts + " cut(s) of a running clear, server use_block " + uses);
            c.check(cuts >= 30, "only " + cuts + " cut(s) of a running clear (nothing tested)");
            c.check(uses > 0, "the server saw no door click (nothing tested)");
            if (st != null) {
                SameTick.judge(c, st, "Door Only click around a cut",
                        act == SameTick.Act.ATTACK ? SameTick.Expect.APART : SameTick.Expect.ACTED);
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick st2 = st;
                c.onClient(mc -> {
                    st2.close();
                    return null;
                });
            }
            clean(c, door);
        }
    }
}
