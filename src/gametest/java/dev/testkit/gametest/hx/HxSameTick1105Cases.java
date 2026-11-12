package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.function.Predicate;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * SAME-TICK GRIM CASES (grimgapC-1105, the "no Grim case at all" gap of grimsame-1105) for two hotbar / drop automations,
 * idle and with HIS action in the feature's server tick ({@link SameTick}). Cheat jar only. Each case also proves the
 * server saw the feature act, and GrimAC (default and experimental checkouts) must stay silent.
 * <ul>
 *   <li>1105-same-pigeondrill-&lt;idle|use|attack|swap&gt;: Swap To Drill On Pigeon. The Royal Pigeon in slot 4, a stick in
 *       5, a drill in 2; his use of the pigeon makes the mod change the held slot to 2 (END tick, sent by vanilla at the
 *       start of the next tick). Three rounds; the server's {@code held} events must show slot 2 each round. use: his
 *       right click every tick (the drill's slot goes out inside his next use, ahead of it); attack: his left click on a
 *       zombie every other tick; swap: his scroll 4/5 every other tick (only one slot change can leave per tick, so the
 *       two are apart).</li>
 *   <li>1105-same-autoult-&lt;idle|use|attack|swap&gt;: Auto Ult (Wish on Clear as a Healer, freed by Hypixel's "Used Wish!"
 *       + ready lines) sends DROP_ITEM eight times; the server drops one of his 64 sticks for each. use: his right click
 *       every tick (the drop goes first, at START; GrimAC PacketOrderG flags a drop AFTER a use / attack / dig in one
 *       tick); attack: ActionGate holds the drop a tick behind his queued left click (apart); swap: his scroll every tick.</li>
 * </ul>
 */
final class HxSameTick1105Cases {

    private static final String QOL = "miningqol.MiningQolConfig";
    private static final String CU_CFG = "cheatutils.CheatUtilsConfig";
    private static final String ULT = "cheatutils.AutoUltFeature";
    private static final String PIGEON = "minecraft:feather[custom_data={id:\"ROYAL_PIGEON\"},item_name=\"Royal Pigeon\"]";
    private static final String DRILL = "minecraft:prismarine_shard[custom_data={id:\"MITHRIL_DRILL_1\"},item_name=\"Mithril Drill SX-R226\"]";

    private HxSameTick1105Cases() {
    }

    static void register(Session s) {
        String[] acts = {"idle", "use", "attack", "swap"};
        for (String a : acts) {
            s.test("1105-same-pigeondrill-" + a, c -> pigeon(c, act(a)));
            s.test("1105-same-autoult-" + a, c -> autoUlt(c, act(a)));
        }
        s.test("1110-pigeondrill-block", HxSameTick1105Cases::pigeonBlock);
    }

    private static SameTick.Act act(String a) {
        return switch (a) {
            case "use" -> SameTick.Act.USE;
            case "attack" -> SameTick.Act.ATTACK;
            case "swap" -> SameTick.Act.SWAP;
            default -> null;
        };
    }

    private static SameTick start(Session c, SameTick.Act act, int every, Predicate<Packet<?>> feature) {
        return act == null ? null : c.onClient(mc -> SameTick.start(act, every, feature, 4, 5));
    }

    private static void stop(Session c, SameTick st) {
        if (st != null) {
            c.onClient(mc -> {
                st.close();
                return null;
            });
        }
    }

    /** He faces a NoAI zombie two blocks ahead (his ATTACK target). */
    static void zombie(Session c) {
        c.onClient(mc -> {
            mc.player.setYRot(0f);
            mc.player.setXRot(0f);
            return null;
        });
        c.ctx().waitTicks(2);
        double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        c.server().command(String.format(java.util.Locale.ROOT, "summon zombie %.2f %.2f %.2f "
                + "{NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"sametick\"]}", at[0], at[1], at[2] + 2.0));
        c.waitUntil("the zombie under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult, 100);
    }

    /** pigeondrill-1110: the pigeon (a head, so the block use consumes it) used while aimed at the ground: the swap must
     *  still happen and the server must see the drill slot, with no flag. Asserts the use went out as a block use. */
    private static void pigeonBlock(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Swap To Drill On Pigeon is cheat-only");
            return;
        }
        String head = "minecraft:player_head[custom_data={id:\"ROYAL_PIGEON\"},item_name=\"Royal Pigeon\"]";
        try (Settings set = new Settings(c).with(QOL, "PigeonDrill", true)
                .with(QOL, "PigeonDrillMode", Mod.enumValue(QOL + "$PigeonDrillMode", "AUTO"))) {
            c.hx().give(2, DRILL);
            c.hx().give(4, head);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                mc.player.setXRot(90f);
                Mod.staticCall("miningqol.PigeonDrillSwap", "resetForTest");
                return null;
            });
            c.ctx().waitTicks(5);
            long since = c.hx().head();
            int swaps = c.onClient(mc -> (Integer) Mod.staticCall("miningqol.PigeonDrillSwap", "swaps"));
            c.onClient(mc -> {
                net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
                return null;
            });
            c.waitUntil("the swap after a block-targeted pigeon use", mc -> (Integer) Mod.staticCall("miningqol.PigeonDrillSwap", "swaps") > swaps, 40);
            c.ctx().waitTicks(4);
            boolean held = c.hx().events(since, "held").stream().anyMatch(e -> e.get("slot").getAsInt() == 2);
            c.note("server saw drill slot: " + held);
            c.check(held, "the server never saw the drill slot after a pigeon use aimed at a block");
        } finally {
            c.onClient(mc -> {
                mc.player.setXRot(0f);
                return null;
            });
            for (int s : new int[]{2, 4}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }

    // ---- Swap To Drill On Pigeon --------------------------------------------------------------------------------

    private static void pigeon(Session c, SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Swap To Drill On Pigeon is cheat-only");
            return;
        }
        SameTick st = null;
        try (Settings set = new Settings(c).with(QOL, "PigeonDrill", true)
                .with(QOL, "PigeonDrillMode", Mod.enumValue(QOL + "$PigeonDrillMode", "AUTO"))) {
            c.hx().give(2, DRILL);
            c.hx().give(4, PIGEON);
            c.hx().give(5, "minecraft:stick");
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                Mod.staticCall("miningqol.PigeonDrillSwap", "resetForTest");
                return null;
            });
            if (act == SameTick.Act.ATTACK) {
                zombie(c);
            }
            c.ctx().waitTicks(5);
            long since = c.hx().head();
            // Every other tick for attack and swap: his scroll every tick would take the slot back each time.
            st = start(c, act, act == SameTick.Act.USE ? 1 : 2,
                    p -> p instanceof ServerboundSetCarriedItemPacket h && h.getSlot() == 2);
            int rounds = 0;
            for (int i = 0; i < 8 && rounds < 3; i++) {
                // Read before the pigeon goes back in his hand: with his use every tick the swap comes at once.
                long mark = c.hx().head();
                int swaps = c.onClient(mc -> (Integer) Mod.staticCall("miningqol.PigeonDrillSwap", "swaps"));
                c.onClient(mc -> {
                    mc.player.getInventory().setSelectedSlot(4);
                    return null;
                });
                c.ctx().waitTicks(3);
                if (act != SameTick.Act.USE) {
                    // His right click on the pigeon, as a mouse press: vanilla's handleKeybinds uses it next tick.
                    c.onClient(mc -> {
                        net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
                        return null;
                    });
                }
                c.waitUntil("the pigeon's swap", mc -> (Integer) Mod.staticCall("miningqol.PigeonDrillSwap", "swaps") > swaps, 30);
                c.ctx().waitTicks(4);
                if (c.hx().events(mark, "held").stream().anyMatch(e -> e.get("slot").getAsInt() == 2)) {
                    rounds++;
                }
            }
            int seen = (int) c.hx().events(since, "held").stream().filter(e -> e.get("slot").getAsInt() == 2).count();
            c.note("server held=2 events " + seen + ", rounds the server saw " + rounds);
            c.check(rounds >= (act == SameTick.Act.SWAP ? 1 : 3), "the server saw the drill slot in only " + rounds + " round(s)");
            if (st != null) {
                // His attack and scroll at END come after the tick's client_tick_end, so the swap waits a tick
                // (swapWouldFlag, PacketOrderE): apart by design.
                SameTick.judge(c, st, "pigeon drill", act == SameTick.Act.USE ? SameTick.Expect.SHARE : SameTick.Expect.APART);
                st = null;
            }
        } finally {
            stop(c, st);
            c.server().command("kill @e[tag=sametick]");
            for (int s : new int[]{2, 4, 5}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }

    // ---- Auto Ult -----------------------------------------------------------------------------------------------

    private static int wishes(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(ULT, "wishesSent"));
    }

    /** His sticks as the server holds them ({@code clear ... 0} only counts). */
    private static int serverSticks(Session c) {
        c.server().command("clear @p minecraft:stick 0");
        c.ctx().waitTicks(5);
        java.util.List<String> lines = c.server().since();
        for (int i = lines.size() - 1; i >= 0; i--) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Found (\\d+) matching item").matcher(lines.get(i));
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        throw new AssertionError("no 'Found N matching item' line from clear");
    }

    private static void autoUlt(Session c, SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Ult is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String ipWas = c.onClient(mc -> mc.getCurrentServer().ip);
        SameTick st = null;
        try (Settings set = new Settings(c).with(CU_CFG, "AutoUltEnabled", true).with(CU_CFG, "WishOnClear", true)
                .field(CU_CFG, "ultClassOverride", "HEALER")) {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                mc.getCurrentServer().ip = "mc.hypixel.net";
                return null;
            });
            c.hx().call("give", "slot", 4, "stack", "minecraft:stick", "count", 64);
            c.hx().call("give", "slot", 5, "stack", "minecraft:stick", "count", 64);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            dungeon(c);
            if (act == SameTick.Act.ATTACK) {
                zombie(c);
            }
            sidebar(c, "template", "dungeon", "floor", "F7", "extra", java.util.List.of("§e[M] §bHxMateA §c2,900§c❤",
                    "§e[B] §6HxMateB §a30,100§c❤"));
            // A Wish still blocked from an earlier case is freed by Hypixel's ready line.
            c.hx().chat("Wish is ready to use! Press DROP to activate it!");
            c.ctx().waitTicks(10);
            // The server drops a stick per DROP_ITEM but never re-sends the slot (vanilla predicts its own drop), so the
            // drops are counted in the server's own inventory.
            int before = serverSticks(c);
            int base = wishes(c);
            st = start(c, act, act == SameTick.Act.ATTACK ? 2 : 1, p -> p instanceof ServerboundPlayerActionPacket a
                    && a.getAction() == ServerboundPlayerActionPacket.Action.DROP_ITEM);
            for (int i = 0; i < 8; i++) {
                int want = base + i + 1;
                c.hx().chat("Used Wish!");
                c.hx().chat("Wish is ready to use! Press DROP to activate it!");
                c.waitUntil("Wish " + (i + 1), mc -> (Integer) Mod.staticCall(ULT, "wishesSent") >= want, 60);
                // Past ChatObserver's 250 ms de-dupe of a repeated line, and the stick gone before its 40-tick pickup delay.
                c.ctx().waitTicks(8);
                c.server().command("kill @e[type=item]");
            }
            c.ctx().waitTicks(10);
            int dropped = before - serverSticks(c);
            c.server().command("kill @e[type=item]");
            c.note("wishes " + (wishes(c) - base) + ", sticks the server dropped " + dropped);
            c.check(dropped >= 8, "the server dropped " + dropped + " sticks for 8 Wishes");
            if (st != null) {
                SameTick.judge(c, st, "auto ult", act == SameTick.Act.ATTACK ? SameTick.Expect.APART : SameTick.Expect.SHARE);
                st = null;
            }
        } finally {
            stop(c, st);
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                mc.getCurrentServer().ip = ipWas;
                return null;
            });
            c.server().command("kill @e[tag=sametick]");
            c.server().command("kill @e[type=item]");
            c.hx().give(4, "minecraft:air");
            c.hx().give(5, "minecraft:air");
            hub(c);
        }
    }
}
