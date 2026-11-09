package dev.testkit.gametest;

import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.testkit.gametest.InvWalkCampaignTests.*;
import static dev.testkit.gametest.InvWalkChestCarryTests.chestRow;
import static dev.testkit.gametest.InvWalkChestCarryTests.walk;

/**
 * 1108-iw-chestcarry-sametick (SAME-TICK GRIM CASES for Carry In Chests, iwbypass-1108): the carried pick-up and its
 * put-down go out after the full stop, and his own action lands in that same server tick: a container click and a hotbar
 * swap with the chest still open, and after Escape a right click (use key) and a left click on an entity (attack) through
 * vanilla's keybinds. Each row is a full 1108 chest row (server cursor never held the item, no click while moving, items
 * conserved, GrimAC clean) plus {@link SameTick}'s split of the client wire into GrimAC's ticks: his action went in, the
 * replayed clicks went out, and one server tick carried both. A click of his made while the replay is still held joins
 * the batch behind it in order (the mod holds every click until he stands still), so the CLICK row accepts that too and
 * says so. A use or attack sent straight through the game mode with the chest screen open is not tested: vanilla cannot
 * send one (the screen takes the mouse), and GrimAC flags it on its own (MultiActionsC; Post when it follows the move).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkChestCarrySameTickTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String nm = "1108-iw-chestcarry-sametick";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -10600;
        int z = -9000;
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            int[] p = {x + 2, SURFACE_Y, z};
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                // Warm the first block use and walk (cold paths, see 1108-iw-chestcarry).
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=west]", p[0], p[1], p[2]));
                server.sync(10);
                ctx.waitTicks(10);
                if (openBlock(ctx, p[0], p[1], p[2])) {
                    keys(ctx, Set.of("key.back"));
                    ctx.waitTicks(12);
                    keys(ctx, Set.of());
                    closeScreen(ctx);
                }
                ctx.waitTicks(20);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    for (SameTick.Act act : List.of(SameTick.Act.CLICK, SameTick.Act.SWAP, SameTick.Act.USE_KEY, SameTick.Act.ATTACK)) {
                        row(rows, ctx, server, hx, x, z, p, "L" + lat + " chest replay + his " + act, act);
                    }
                }
            } finally {
                ctx.runOnClient(mc -> {
                    try {
                        Mod.set(IW_CFG, "setCarryInChests", false);
                    } catch (Throwable ignored) {
                    }
                });
                server.command("kill @e[type=minecraft:interaction]");
                InvWalkLegitTests.hxOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(nm);
        });
    }

    /**
     * Pick up slot 0, walk, put it down at the chest's first player slot + 10 while walking, with his action put into every
     * client tick from the put-down on (USE_KEY, ATTACK: he presses Escape right after it).
     */
    private static void row(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int[] p, String label,
                            SameTick.Act act) {
        SameTick[] st = new SameTick[1];
        boolean world = act == SameTick.Act.USE_KEY || act == SameTick.Act.ATTACK;
        chestRow(rows, ctx, server, hx, x, z, p, "chest", label, (c, h, seen, n) -> {
            if (act == SameTick.Act.USE_KEY) {
                // A stick in hand and his look in the sky: the right click is a bare use_item (no block placed or used).
                server.command("item replace entity " + name(c) + " hotbar.8 with minecraft:stick");
                server.sync(5);
                c.runOnClient(mc -> {
                    mc.player.getInventory().setSelectedSlot(8);
                    mc.player.setXRot(-50f);
                });
                c.waitTicks(4);
            }
            click(c, List.of(Click.left(0)));
            walk(c, h, Set.of("key.back"), 8, seen);
            walk(c, h, Set.of("key.left"), 8, seen);
            if (act == SameTick.Act.ATTACK) {
                // A hitbox with no collision 2 blocks along his look, wide enough that his crosshair stays on it while he
                // walks on: vanilla's handleKeybinds attacks it once the screen is gone.
                Vec3 at = c.computeOnClient(mc -> mc.player.getEyePosition().add(mc.player.getLookAngle().scale(2.0)));
                server.command("kill @e[type=minecraft:interaction]");
                server.command(String.format(Locale.ROOT, "summon minecraft:interaction %.3f %.3f %.3f {width:2.5f,height:2.5f}",
                        at.x, at.y - 1.25, at.z));
                server.sync(5);
                c.waitTicks(4);
            }
            int sel = c.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
            st[0] = c.computeOnClient(mc -> SameTick.start(act, 1, pk -> pk instanceof ServerboundContainerClickPacket,
                    world ? sel : 0, world ? sel : 1));
            st[0].allow(world ? 12 : 8);
            click(c, List.of(Click.left(n + 10)));
            if (!world) {
                return Map.of(0, "", n + 10, "minecraft:stone*32");
            }
            // He presses Escape right after the put-down and clicks into the world: the held replay and its close go out
            // after the full stop, his right / left clicks from vanilla's keybinds around it (impossible while the chest
            // screen is open: the screen takes the mouse).
            closeScreen(c);
            return Map.of();
        }, () -> {
            ctx.runOnClient(mc -> st[0].close());
            SameTick s = st[0];
            String r = s.report();
            String why = null;
            if (s.done() == 0) {
                why = "his action never went in";
            } else if (s.featureWindows() == 0) {
                why = "the replayed clicks never went out";
            } else if (act == SameTick.Act.CLICK ? s.both() == 0 && s.playerWindows() > 0 : s.both() == 0) {
                // CLICK: a click of his either shares a tick with the replay or rides in the held batch (then it is never
                // on the wire as his outside it); a click of his alone in another tick while the replay was due is a miss.
                why = "no server tick carried both the replayed clicks and his action";
            }
            if (act == SameTick.Act.CLICK && why == null && s.both() == 0) {
                r += " (his clicks rode in the held batch, in order)";
            }
            return new String[]{why, r};
        });
        server.command("kill @e[type=minecraft:interaction]");
    }
}
