package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod mithril-hold-1103 (killer560, 2026-11-03): his AttackHoldProbe log showed server-opened "Commissions" containers
 * letting go of a held left click mid-dig. Resume Held Break After Menus sets the attack key down again when such a menu
 * closes and the button is still held. The button is simulated: util.MouseButtons gets the press (as MouseHandler.onButton
 * would report it) and the attack key is set down; HeldBreakResume.trustTrackedButtonForTest stands in for the platform's
 * button state, which a test cannot press. A pick on obsidian keeps a dig going for seconds; the dig is read off the WIRE
 * (WireCapture: START_DESTROY_BLOCK as encoded), not off the mod.
 * <ul>
 *   <li>1103-mining-hold-resume: held dig, the server opens and closes a container: within 1 tick of the close the attack
 *       key is down again without a new press from the test, the client digs the obsidian, and when the close aborted the
 *       dig a START_DESTROY_BLOCK on it goes out again; nothing but digging, swings and moves is sent.</li>
 *   <li>1103-mining-hold-released: the button is let go during the menu: after the close the key stays up, no resume, no
 *       dig.</li>
 *   <li>1103-mining-hold-off: the setting off: nothing resumes.</li>
 *   <li>1103-mining-hold-dump: a container opened under a held click dumps the last sent packets (a command's NAME only,
 *       never its arguments) and the last chat line before it.</li>
 * </ul>
 */
final class HxAttackHold1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String RESUME = "miningqol.HeldBreakResume";
    private static final String PROBE = "miningqol.AttackHoldProbe";

    private HxAttackHold1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-hold-resume", c -> run(c, true, false));
        s.test("1103-mining-hold-released", c -> run(c, true, true));
        s.test("1103-mining-hold-off", c -> run(c, false, false));
        s.test("1103-mining-hold-dump", HxAttackHold1103Cases::dump);
    }

    private static void mines(Session c) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Royal Mines", "Purse: 0");
        c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", "", "Commissions:", " Goblin Slayer: 10%", ""), null, null);
        c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island")), 100);
    }

    /** A real press, as MouseHandler.onButton makes it: the button tracked, the key set down AND a click counted (a held
     *  key without a click on a block whose dig was aborted digs on the client only, see HeldBreakResume). */
    private static void press(Session c) {
        c.ctx().runOnClient(mc -> {
            Mod.staticCall("util.MouseButtons", "onButton", 0, 1);
            var key = (com.mojang.blaze3d.platform.InputConstants.Key) Mod.field(mc.options.keyAttack, "key");
            net.minecraft.client.KeyMapping.set(key, true);
            net.minecraft.client.KeyMapping.click(key);
        });
    }

    private static void release(Session c) {
        c.ctx().runOnClient(mc -> {
            Mod.staticCall("util.MouseButtons", "onButton", 0, 0);
            mc.options.keyAttack.setDown(false);
        });
    }

    private static int intCall(Session c, String cls, String m) {
        return c.onClient(mc -> ((Number) Mod.staticCall(cls, m)).intValue());
    }

    /** START_DESTROY_BLOCKs on {@code pos} on the wire since the capture started, and every other play type sent. */
    private static List<String> wire() {
        List<String> out = new ArrayList<>();
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            if (s.phase().equals("play")) {
                out.add(s.type() + " " + s.text());
            }
        }
        return out;
    }

    private static float destroyTicks(Session c) {
        return c.onClient(mc -> ((Number) Mod.field(mc.gameMode, "destroyTicks")).floatValue());
    }

    private static BlockPos setUp(Session c) {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        BlockPos ob = f.offset(2, 1, 0);
        c.server().command("setblock " + ob.getX() + " " + ob.getY() + " " + ob.getZ() + " minecraft:obsidian");
        c.hx().give(0, "minecraft:diamond_pickaxe");
        c.ctx().runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            mc.player.setYRot(-90f);
            mc.player.setXRot(3f);
        });
        c.waitUntil("aiming at the obsidian", mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                && b.getBlockPos().equals(ob), 40);
        return ob;
    }

    private static void run(Session c, boolean settingOn, boolean letGo) {
        WireCapture.describe = p -> p instanceof ServerboundPlayerActionPacket a
                ? a.getAction() + " " + a.getPos().toShortString() : p.getClass().getSimpleName();
        BlockPos ob = null;
        try (Settings set = new Settings(c).with(CFG, "ResumeHeldBreak", settingOn)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(RESUME, "trustTrackedButtonForTest", true));
            mines(c);
            ob = setUp(c);
            String dig = "START_DESTROY_BLOCK " + ob.toShortString();
            WireCapture.start();
            press(c);
            c.waitUntil("the held dig starts", mc -> wire().stream().anyMatch(w -> w.contains(dig)), 20);
            int resumes = intCall(c, RESUME, "resumes");
            c.hx().menu("Commissions", 6, Map.of(13, "minecraft:writable_book"), null);
            c.waitUntil("the server's menu is open", mc -> McCompat.screen(mc) != null, 40);
            c.check(!c.onClient(mc -> mc.options.keyAttack.isDown()), "the menu did not release the attack key (control)");
            if (letGo) {
                c.ctx().runOnClient(mc -> Mod.staticCall("util.MouseButtons", "onButton", 0, 0));
            }
            c.ctx().waitTicks(10);
            WireCapture.start();
            c.hx().call("menu.close");
            c.waitUntil("the menu closed", mc -> McCompat.screen(mc) == null, 40);
            int afterClose = c.onClient(mc -> mc.options.keyAttack.isDown()) ? 0 : -1;
            if (settingOn && !letGo) {
                // The resume runs at the end of the tick the screen went: give it the one tick.
                if (afterClose < 0) {
                    c.ctx().waitTicks(1);
                    afterClose = c.onClient(mc -> mc.options.keyAttack.isDown()) ? 1 : -1;
                }
                c.check(afterClose >= 0, "the attack key was not down again within 1 tick of the close");
                c.check(intCall(c, RESUME, "resumes") == resumes + 1, "no resume counted");
                // Digging again: the client's dig on the obsidian counts ticks.
                float t0 = destroyTicks(c);
                c.ctx().waitTicks(5);
                float t1 = destroyTicks(c);
                boolean digging = c.onClient(mc -> (Boolean) Mod.field(mc.gameMode, "isDestroying"))
                        && ob.equals(c.onClient(mc -> Mod.field(mc.gameMode, "destroyBlockPos")));
                c.check(digging && t1 > t0, "not digging the obsidian after the close: destroying " + digging + ", ticks "
                        + t0 + " -> " + t1 + "; wire " + wire());
                // The server must hear of it: a dig the close aborted needs a new START_DESTROY (the first run of this
                // case caught the held key alone digging on the client only: ABORT, then only punches).
                boolean aborted = wire().stream().anyMatch(w -> w.contains("ABORT_DESTROY_BLOCK"));
                boolean started = wire().stream().anyMatch(w -> w.contains(dig));
                c.check(!aborted || started, "the dig was aborted on the close and never started again on the wire: " + wire());
                for (String w : wire()) {
                    boolean ok = w.contains("player_action") || w.contains("swing") || w.contains("move_player")
                            || w.contains("client_tick_end") || w.contains("keep_alive") || w.contains("pong")
                            || w.contains("player_input") || w.contains("chunk_batch") || w.contains("container_close");
                    c.check(ok, "the resume sent something a held button does not: " + w);
                }
                c.note("key down " + afterClose + " tick(s) after the close, dig ticks " + t0 + " -> " + t1 + " on "
                        + ob.toShortString() + "; wire after close: " + wire().size() + " play packets, all dig/move ("
                        + wire().stream().filter(w -> w.contains("player_action")).toList() + ")");
            } else {
                c.ctx().waitTicks(20);
                c.check(!c.onClient(mc -> mc.options.keyAttack.isDown()), "the attack key came back down ("
                        + (letGo ? "button let go during the menu" : "setting off") + ")");
                c.check(intCall(c, RESUME, "resumes") == resumes, "a resume was counted");
                c.check(wire().stream().noneMatch(w -> w.contains("START_DESTROY")), "a dig started after the close: " + wire());
                c.check(!c.onClient(mc -> (Boolean) Mod.field(mc.gameMode, "isDestroying")), "still digging after the close");
                c.note(letGo ? "let go during the menu: nothing resumed" : "setting off: nothing resumed");
            }
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            release(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(RESUME, "trustTrackedButtonForTest", false));
            if (ob != null) {
                c.server().command("setblock " + ob.getX() + " " + ob.getY() + " " + ob.getZ() + " minecraft:air");
            }
            c.hx().give(0, "minecraft:air");
            hub(c);
        }
    }

    @SuppressWarnings("unchecked")
    private static void dump(Session c) {
        try {
            mines(c);
            c.hx().stub(null, "k560probe");
            c.ctx().runOnClient(mc -> Mod.staticCall(PROBE, "resetDumpGapForTest"));
            int dumps = intCall(c, PROBE, "dumps");
            c.hx().chat("§a§lROYAL MINES MITHRIL §r§eCommission Complete! Visit the King to claim your rewards!");
            c.ctx().runOnClient(mc -> mc.player.connection.sendCommand("k560probe secret-argument"));
            c.ctx().waitTicks(5);
            press(c);
            c.ctx().waitTicks(3);
            c.hx().menu("Commissions", 6, Map.of(13, "minecraft:writable_book"), null);
            c.waitUntil("the dump fired", mc -> ((Number) Mod.staticCall(PROBE, "dumps")).intValue() == dumps + 1, 40);
            List<String> sent = c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(PROBE, "sentForTest")));
            List<String> heard = c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(PROBE, "heardForTest")));
            c.check(sent.stream().anyMatch(s -> s.contains("/k560probe")), "the command is not in the sent ring: " + sent);
            c.check(sent.stream().noneMatch(s -> s.contains("secret-argument")), "a command argument was recorded: " + sent);
            c.check(heard.stream().anyMatch(s -> s.contains("Commission Complete!")), "the chat line is not in the ring: " + heard);
            c.note("dump fired; sent ring " + sent.size() + " (command name only), heard ring " + heard.size());
        } finally {
            c.hx().call("menu.close");
            release(c);
            hub(c);
        }
    }
}
