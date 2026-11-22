package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * SAME-TICK GRIM CASES for Auto Secret's door wait (acrev-door-1122, cheat jar only). While a wither or blood door Hypixel
 * took our click stands as barriers (~14 ticks), Auto Secret makes the next warp ready every tick through
 * {@code ClearExecutor.primeNextWarp()}: the AOTV in hand (a hotbar change vanilla sends at the next tick's start) and the
 * sneak down (the input packet), so the first warp goes the tick the door clears. The Hx server has no dungeon map, so the
 * case drives that hook as Auto Secret does (once per tick, between ticks) in cycles of 14 primed ticks and 6 with his own
 * item back in hand, while HIS action lands in the same ticks ({@link SameTick}).
 * <ul>
 *   <li>1122-hx-doorprime-same-&lt;idle|use|click|attack|swap|tp&gt;: his right click, a click in his inventory, a left
 *       click on a zombie, a hotbar scroll or the server's teleport accept; GrimAC (default and experimental checkouts)
 *       must stay silent, and the prime's packets and his action must both reach the wire.</li>
 *   <li>1122-hx-doorprime-same-rerun: the hook asked twice in one tick (a second run of the feature), with his right click.</li>
 * </ul>
 */
final class HxDoorPrime1122Cases {

    private static final String CE = "livemap.autoclear.ClearExecutor";
    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\"},item_name=\"Aspect of the Void\"]";
    private static final int AOTV_SLOT = 2;

    private HxDoorPrime1122Cases() {
    }

    static void register(Session s) {
        s.test("1122-hx-doorprime-same-idle", c -> same(c, "idle", 1));
        s.test("1122-hx-doorprime-same-use", c -> same(c, "use", 1));
        s.test("1122-hx-doorprime-same-click", c -> same(c, "click", 1));
        s.test("1122-hx-doorprime-same-attack", c -> same(c, "attack", 1));
        s.test("1122-hx-doorprime-same-swap", c -> same(c, "swap", 1));
        s.test("1122-hx-doorprime-same-tp", c -> same(c, "tp", 1));
        s.test("1122-hx-doorprime-same-rerun", c -> same(c, "use", 2));
    }

    /** The prime's own packets: the sneak going down, or the hotbar change to the AOTV's slot. */
    private static boolean prime(Packet<?> p) {
        return p instanceof ServerboundPlayerInputPacket in && in.input().shift()
                || p instanceof ServerboundSetCarriedItemPacket s && s.getSlot() == AOTV_SLOT;
    }

    private static void same(Session c, String a, int asks) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Secret is cheat-only");
            return;
        }
        SameTick.Act act = switch (a) {
            case "use" -> SameTick.Act.USE;
            case "click" -> SameTick.Act.OPEN_CLICK;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            case "tp" -> SameTick.Act.TP;
            default -> null;
        };
        SameTick st = null;
        try {
            c.hx().give(AOTV_SLOT, AOTV);
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            c.server().command("kill @e[tag=sametick]");
            if (act == SameTick.Act.ATTACK) {
                c.server().command("execute as @a at @s run summon zombie ^ ^ ^1.8 "
                        + "{NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}");
                c.server().command("execute as @a at @s run tp @s ~ ~ ~ ~ 20");
            }
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            c.ctx().waitTicks(10);
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.USE ? 2 : 3,
                        HxDoorPrime1122Cases::prime, 4, 5));
            }
            int primedTicks = 0;
            int heldAotv = 0;
            int sneaking = 0;
            for (int i = 0; i < 240; i++) {
                int phase = i % 20;
                if (act == SameTick.Act.TP && i % 2 == 0) {
                    c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                }
                if (phase < 14) {
                    primedTicks++;
                    int[] seen = c.onClient(mc -> {
                        for (int k = 0; k < asks; k++) {
                            Mod.staticCall(CE, "primeNextWarp");
                        }
                        return new int[]{mc.player.getInventory().getSelectedSlot() == AOTV_SLOT ? 1 : 0,
                                mc.player.getLastSentInput().shift() ? 1 : 0};
                    });
                    heldAotv += seen[0];
                    sneaking += seen[1];
                } else if (phase == 14) {
                    c.onClient(mc -> {
                        mc.player.getInventory().setSelectedSlot(4);
                        return null;
                    });
                }
                c.ctx().waitTick();
            }
            c.note("door prime " + a + " x" + asks + ": " + primedTicks + " primed tick(s), AOTV held at " + heldAotv
                    + ", sneak heard at " + sneaking);
            c.check(heldAotv > 0 && sneaking > 0, "the prime never put the AOTV in hand and the sneak down (nothing"
                    + " tested: AOTV " + heldAotv + ", sneak " + sneaking + ")");
            if (st != null) {
                SameTick.judge(c, st, "Door prime", SameTick.Expect.ACTED);
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
            c.server().command("kill @e[tag=sametick]");
            for (int s : new int[]{AOTV_SLOT, 4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }
}
