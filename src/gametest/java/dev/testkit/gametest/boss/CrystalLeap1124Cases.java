package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.world.inventory.ContainerInput;

import java.util.List;

/**
 * Crystal Leap speed and an already-open menu (mod crystalleap-fast-1124; killer560, 2026-11-24: "ensure it is leaping as
 * fast as possible ... make sure it would still work if i already had the menu open"). The trigger is his own
 * "picked up an Energy Crystal!" line sent by the server (a real packet, not a direct call). Every step is read in
 * ActionGate ticks: the tick the line ARRIVED, then the mod's own swap / use / click ticks (LeapManager.stepLog) and the
 * first START tick that saw the leap menu open. The mod acts at the first tick START after the line, swaps and uses in
 * that same tick (a hand's own order: hotbar key, then use), and clicks in the START of the tick the menu is seen.
 */
final class CrystalLeap1124Cases {

    private static final String LM = "fastleap.LeapManager";
    private static final String GATE = "util.ActionGate";
    private static final String CLICK_SLOT = "13";

    /** Gate ticks seen by the hooks; -1 until set. */
    private static volatile boolean rec;
    private static volatile int arrive = -1;
    private static volatile int menuSeen = -1;
    private static boolean hooked;

    private CrystalLeap1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-crystalleap-trace-held", c -> trace(c, false, null));
        s.test("1124-crystalleap-trace-swap", c -> trace(c, true, null));
        s.test("1124-crystalleap-menu-open", c -> trace(c, false, "leap"));
        s.test("1124-crystalleap-menu-open-swap", c -> trace(c, true, "leap"));
        s.test("1124-crystalleap-other-container", CrystalLeap1124Cases::otherContainer);
        s.test("1124-crystalleap-his-menu-between", CrystalLeap1124Cases::hisMenu);
        for (String act : new String[] {"use-key", "attack", "swap", "click"}) {
            s.test("1124-crystalleap-same-open-" + act, c -> sameOpen(c, act));
        }
    }

    private static int gateTick() {
        return ((Number) Mod.staticCall(GATE, "currentTick")).intValue();
    }

    private static void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        ClientReceiveMessageEvents.GAME.register((m, overlay) -> {
            if (rec && arrive < 0 && m.getString().contains("picked up an Energy Crystal")) {
                arrive = gateTick();
            }
        });
        // an earlier phase than the mod's own START hook: the leap clicks (and closes) in the very START the menu is first
        // there, so a hook after it would never see the menu open (the lane's menu-seen -1)
        net.minecraft.resources.Identifier early = net.minecraft.resources.Identifier.parse("testkit:crystalleap-early");
        ClientTickEvents.START_CLIENT_TICK.addPhaseOrdering(early, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        ClientTickEvents.START_CLIENT_TICK.register(early, mc -> {
            if (rec && menuSeen < 0 && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                menuSeen = gateTick();
            }
        });
    }

    private static void arm() {
        hook();
        arrive = -1;
        menuSeen = -1;
        rec = true;
    }

    @SuppressWarnings("unchecked")
    private static List<String> steps(Session c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(LM, "stepLog"));
    }

    /** The gate tick of the first step starting with {@code what} (tick is the text before the first space), or -1. */
    private static int stepTick(List<String> steps, String what) {
        for (String s : steps) {
            int sp = s.indexOf(' ');
            if (s.substring(sp + 1).startsWith(what)) {
                return Integer.parseInt(s.substring(0, sp));
            }
        }
        return -1;
    }

    private static String stepText(List<String> steps, String what) {
        for (String s : steps) {
            if (s.substring(s.indexOf(' ') + 1).startsWith(what)) {
                return s.substring(s.indexOf(' ') + 1);
            }
        }
        return "";
    }

    /** The leap in hotbar slot 1 with something else held in slot 0 (so the leap needs a swap). */
    private static void leapInSlot1(Session c) {
        String stack = Fixtures.byId("items.spirit-leap").payload().getAsJsonObject().get("stack").getAsString();
        c.hx().give(1, stack);
        c.hx().give(0, "minecraft:stone");
        c.waitUntil("leap in slot 1", mc -> "SPIRIT_LEAP".equals(
                Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(1))), 60);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        c.ctx().waitTicks(6);
    }

    private static void showLeapMenu(Session c) {
        JsonObject shown = Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
        shown.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + "SicoKalebCrys");
        c.hx().call("menu.show", shown);
        c.waitUntil("a menu", mc -> mc.player.containerMenu != mc.player.inventoryMenu, 60);
        c.ctx().waitTicks(8); // past ActionGate's screen settle: it has been open a while when the line comes
    }

    /** Trigger by a real server line; read arrival, swap, use, menu seen and click ticks. */
    private static void trace(Session c, boolean swap, String open) throws Exception {
        if (!CrystalLeap1112Cases.cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = CrystalLeap1112Cases.setUp(c, "AUTO", false);
        try {
            if (swap) {
                leapInSlot1(c);
            }
            if (open != null) {
                showLeapMenu(c);
            }
            WireCapture.start();
            arm();
            c.hx().chat(CrystalLeap1112Cases.pickup(CrystalLeap1112Cases.self(c)));
            c.waitUntil("the leap finished (cooldown starts)", mc -> CrystalLeap1112Cases.cooldown(c) > 0, 80);
            c.ctx().waitTicks(6);
            rec = false;
            WireCapture.stop();
            List<String> st = steps(c);
            int sw = stepTick(st, "swap");
            int use = stepTick(st, "use");
            int click = stepTick(st, "click");
            c.note("TRACE swap=" + swap + " open=" + open + ": arrived tick " + arrive + ", steps " + st + ", menu seen tick "
                    + menuSeen + " -> swap +" + (sw < 0 ? "-" : String.valueOf(sw - arrive)) + ", use +"
                    + (use < 0 ? "-" : String.valueOf(use - arrive)) + ", click +" + (click - arrive) + ", click after menu seen "
                    + (click - menuSeen) + "; wire " + CrystalLeap1112Cases.ticks());
            c.check(arrive >= 0, "the pickup line never arrived");
            c.check(click >= 0 && CLICK_SLOT.equals(stepText(st, "click").substring(6)), "no click on the target head: " + st);
            c.check(CrystalLeap1112Cases.chatCount(c, "Failed to leap") == 0, "a Failed to leap line appeared");
            // the mod acts at the first START after the line: arrival tick + 1 (the gate tick counts at START)
            if (open != null) {
                c.check(CrystalLeap1112Cases.count("UseItem") == 0, "an open menu still got a use packet");
                c.check(CrystalLeap1112Cases.count("SetCarriedItem") == 0, "an open menu still got a hotbar swap");
                c.check(click == arrive + 1, "open menu: click at +" + (click - arrive) + ", want +1 (the first START after the line)");
            } else {
                c.check(CrystalLeap1112Cases.count("UseItem") == 1, "uses: " + CrystalLeap1112Cases.count("UseItem"));
                c.check(use == arrive + 1, "use at +" + (use - arrive) + ", want +1 (the first START after the line)");
                if (swap) {
                    c.check(sw == arrive + 1, "swap at +" + (sw - arrive) + ", want +1");
                }
                c.check(menuSeen >= 0, "the leap menu never opened on his client");
                c.check(click - menuSeen <= 1, "click " + (click - menuSeen) + " ticks after its menu was seen open, want 0 or 1");
            }
            c.check(CrystalLeap1112Cases.count("ContainerClick") == 1, "menu clicks: " + CrystalLeap1112Cases.count("ContainerClick"));
        } finally {
            rec = false;
            WireCapture.stop();
            CrystalLeap1112Cases.tearDown(c, on);
        }
    }

    /** A non-leap container open at the trigger: the leap waits (queued), never closes his screen, goes once it is closed. */
    private static void otherContainer(Session c) throws Exception {
        if (!CrystalLeap1112Cases.cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = CrystalLeap1112Cases.setUp(c, "AUTO", false);
        try {
            c.hx().call("menu.show", "title", "Chest", "rows", 3);
            c.waitUntil("a chest menu", mc -> mc.player.containerMenu != mc.player.inventoryMenu, 60);
            c.ctx().waitTicks(8);
            WireCapture.start();
            c.hx().chat(CrystalLeap1112Cases.pickup(CrystalLeap1112Cases.self(c)));
            c.ctx().waitTicks(30);
            c.check(CrystalLeap1112Cases.count("UseItem") == 0 && CrystalLeap1112Cases.count("ContainerClick") == 0,
                    "a leap went out with another container open: " + CrystalLeap1112Cases.ticks());
            c.check(c.onClient(mc -> mc.player.containerMenu != mc.player.inventoryMenu), "his open container was closed");
            c.check(CrystalLeap1112Cases.chatCount(c, "Queued leap") >= 1, "no Queued leap line");
            c.hx().call("menu.close");
            c.waitUntil("the queued leap went", mc -> CrystalLeap1112Cases.cooldown(c) > 0, 100);
            c.ctx().waitTicks(4);
            WireCapture.stop();
            c.note("other container: wire " + CrystalLeap1112Cases.ticks() + ", steps " + steps(c));
            c.check(CrystalLeap1112Cases.count("UseItem") == 1 && CrystalLeap1112Cases.count("ContainerClick") >= 1,
                    "after he closed it: " + CrystalLeap1112Cases.ticks());
        } finally {
            WireCapture.stop();
            CrystalLeap1112Cases.tearDown(c, on);
        }
    }

    /** The server answers the auto's use late and HE has the leap menu open meanwhile (his own use): one leap, one click. */
    private static void hisMenu(Session c) throws Exception {
        if (!CrystalLeap1112Cases.cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = CrystalLeap1112Cases.setUp(c, "AUTO", false);
        try {
            c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null); // the use gets no answer of its own
            WireCapture.start();
            c.hx().chat(CrystalLeap1112Cases.pickup(CrystalLeap1112Cases.self(c)));
            c.waitUntil("the auto used the leap", mc -> CrystalLeap1112Cases.count("UseItem") >= 1, 60);
            showLeapMenu(c); // his own use opened it
            c.waitUntil("the leap finished", mc -> CrystalLeap1112Cases.cooldown(c) > 0, 100);
            c.ctx().waitTicks(4);
            WireCapture.stop();
            c.note("his menu between: wire " + CrystalLeap1112Cases.ticks() + ", steps " + steps(c));
            c.check(CrystalLeap1112Cases.count("ContainerClick") == 1, "clicks: " + CrystalLeap1112Cases.count("ContainerClick"));
            c.check(CrystalLeap1112Cases.chatCount(c, "Failed to leap") == 0, "a Failed to leap line appeared");
            c.check(CLICK_SLOT.equals(stepText(steps(c), "click").substring(6)), "wrong head: " + steps(c));
        } finally {
            WireCapture.stop();
            CrystalLeap1112Cases.tearDown(c, on);
        }
    }

    /** The menu already open (visible) AND his own action in the tick the line arrives: both on the wire. */
    private static void sameOpen(Session c, String act) throws Exception {
        if (!CrystalLeap1112Cases.cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = CrystalLeap1112Cases.setUp(c, "AUTO", act.equals("attack"));
        try {
            if (act.equals("attack")) {
                c.onClient(mc -> {
                    mc.player.setXRot(22f);
                    return null;
                });
                c.ctx().waitTicks(4);
            }
            showLeapMenu(c);
            WireCapture.start();
            c.ctx().runOnClient(mc -> {
                Mod.staticCall("fastleap.FastLeapFeature", "onChat",
                        net.minecraft.network.chat.Component.literal(CrystalLeap1112Cases.pickup(CrystalLeap1112Cases.self(c))));
                switch (act) {
                    case "use-key" -> net.minecraft.client.KeyMapping.click(
                            (com.mojang.blaze3d.platform.InputConstants.Key) Mod.field(mc.options.keyUse, "key"));
                    case "attack" -> net.minecraft.client.KeyMapping.click(
                            (com.mojang.blaze3d.platform.InputConstants.Key) Mod.field(mc.options.keyAttack, "key"));
                    case "swap" -> mc.player.getInventory().setSelectedSlot(1);
                    default -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 20, 0,
                            ContainerInput.PICKUP, mc.player);
                }
            });
            c.waitUntil("the leap finished (cooldown starts)", mc -> CrystalLeap1112Cases.cooldown(c) > 0, 80);
            c.ctx().waitTicks(8);
            WireCapture.stop();
            c.note(act + " (menu open) wire by client tick: " + CrystalLeap1112Cases.ticks() + ", steps " + steps(c));
            int clicks = CrystalLeap1112Cases.count("ContainerClick");
            c.check(CLICK_SLOT.equals(stepText(steps(c), "click").substring(6)), "the leap did not click the target: " + steps(c));
            c.check(CrystalLeap1112Cases.count("UseItem") == (act.equals("use-key") ? 1 : 0)
                    || act.equals("use-key"), "the leap used the item with its menu open: " + CrystalLeap1112Cases.ticks());
            switch (act) {
                // vanilla: with a container screen open his left click is a slot click, never an attack (the 1112 same-attack
                // case covers the attack with no menu); the leap must not turn it into one
                case "attack" -> c.check(CrystalLeap1112Cases.firstTick("AttackPacket") < 0
                        && CrystalLeap1112Cases.firstTick("InteractPacket") < 0, "an attack went out with the menu open");
                case "swap" -> c.check(CrystalLeap1112Cases.firstTick("SetCarriedItem") >= 0, "his swap never went out");
                case "click" -> c.check(clicks >= 2, "his click and the leap's: " + clicks);
                default -> c.check(clicks >= 1, "no click");
            }
        } finally {
            WireCapture.stop();
            CrystalLeap1112Cases.tearDown(c, on);
        }
    }
}
