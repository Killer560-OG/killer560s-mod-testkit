package dev.testkit.gametest.hx;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod nobosspearl-1110 (killer560: "in boss phase I cannot throw ender pearls"). Block Ender Pearls In Boss cancels the
 * player's own right click ({@code Minecraft.startUseItem}) with a pearl in hand while the boss phase is on (the sim
 * override stands in for Maxor's line). Read off the WIRE (WireCapture), and each case asserts the press really happened.
 * <ul>
 *   <li>1110-bosspearl-block: boss phase, pearl, real use press: no use_item and no swing go out.</li>
 *   <li>1110-bosspearl-outside: same press out of the boss: the use goes out (the control: the press does throw).</li>
 *   <li>1110-bosspearl-off: boss phase with the switch off: the use goes out.</li>
 *   <li>1110-bosspearl-mod: boss phase, a use the mod makes itself (MultiPlayerGameMode.useItem, the Walk With Pearls
 *       path): goes out.</li>
 * </ul>
 */
final class HxNoBossPearl1110Cases {

    private static final String CFG = "dungeonqol.DungeonQolConfig";
    private static final String STATE = "secrets.DungeonState";

    private HxNoBossPearl1110Cases() {
    }

    static void register(Session s) {
        s.test("1110-bosspearl-block", c -> run(c, true, true, false));
        s.test("1110-bosspearl-outside", c -> run(c, true, false, false));
        s.test("1110-bosspearl-off", c -> run(c, false, true, false));
        s.test("1110-bosspearl-mod", c -> run(c, true, true, true));
        s.test("1121-hx-bosspearl-leap", c -> {
            run(c, true, true, false, true);
            run(c, true, true, false, false); // control: a plain pearl in the same state is blocked
        });
    }

    private static void run(Session c, boolean on, boolean boss, boolean mod) {
        run(c, on, boss, mod, false);
    }

    private static void run(Session c, boolean on, boolean boss, boolean mod, boolean leap) {
        WireCapture.describe = p -> p.getClass().getSimpleName();
        boolean toggled = false;
        try (Settings set = new Settings(c).with(CFG, "BlockBossPearls", on)) {
            dungeon(c);
            if (boss) {
                c.onClient(mc -> Mod.staticCall(STATE, "toggleSimOverride"));
                toggled = true;
                c.check((Boolean) c.onClient(mc -> Mod.staticCall(STATE, "isBossPhaseActive")), "boss phase did not turn on");
            }
            if (leap) {
                c.hx().give(0, Fixtures.byId("items.spirit-leap").payload().getAsJsonObject().get("stack").getAsString());
                c.onClient(mc -> {
                    mc.player.getInventory().setSelectedSlot(0);
                    return null;
                });
                c.waitUntil("a Spirit Leap in hand", mc -> "SPIRIT_LEAP".equals(
                        Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getMainHandItem())), 60);
            } else {
                c.server().command("item replace entity @p weapon.mainhand with minecraft:ender_pearl 16");
            }
            c.waitUntil("a pearl in hand", mc -> mc.player.getMainHandItem().is(net.minecraft.world.item.Items.ENDER_PEARL), 60);
            c.ctx().waitTicks(25);
            WireCapture.start();
            if (mod) {
                c.ctx().runOnClient(mc -> mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND));
            } else {
                c.ctx().getInput().pressKey(o -> o.keyUse);
            }
            c.ctx().waitTicks(10);
            List<String> wire = new ArrayList<>();
            for (WireCapture.Sent s : WireCapture.snapshot()) {
                if (s.phase().equals("play")) {
                    wire.add(s.text());
                }
            }
            boolean used = wire.contains("ServerboundUseItemPacket");
            boolean swung = wire.contains("ServerboundSwingPacket");
            boolean blocked = on && boss && !mod && !leap;
            c.check(used != blocked, blocked ? "a pearl use went out in the boss: " + wire : "no use went out: " + wire);
            c.check(!blocked || !swung, "a swing went out with the blocked use: " + wire);
            c.note((blocked ? "blocked, wire " : "went out, wire ") + wire);
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            if (toggled) {
                c.onClient(mc -> Mod.staticCall(STATE, "toggleSimOverride"));
            }
        }
    }
}
