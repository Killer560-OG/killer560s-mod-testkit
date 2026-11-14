package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Crystal Leap Auto and the leap's double attempt (mod crystalleap-1112; killer560, 2026-11-12: "it should leap the
 * second i pick up a crystal"). In Maxor P1 (y over 210), Auto leaps on HIS OWN "Name picked up an Energy Crystal!" line,
 * once per line, never on another player's. A Fast Leap press gives exactly one leap and no "Failed to leap! On
 * cooldown" line when the press arrives again right after the leap finished. Auto survives save, reload and a profile
 * apply. The same-tick cases put his own click / swap / attack into the tick the leap goes out and assert both on the
 * wire (the session's GrimAC check judges the rest; run on default and experimental Grim).
 */
final class CrystalLeap1112Cases {

    private static final String FLC = "fastleap.FastLeapConfig";
    private static final String LM = "fastleap.LeapManager";
    private static final String FF = "fastleap.FastLeapFeature";
    private static final String MATE = "SicoKalebCrys";
    private static final String FOE = "SicoKalebFoe";
    private static final int X = 70;
    private static final int Y = 220;
    private static final int Z = 90;

    private CrystalLeap1112Cases() {
    }

    static void register(Session s) {
        s.test("1112-crystalleap-own-line", c -> own(c));
        s.test("1112-crystalleap-other-line", c -> other(c));
        s.test("1112-crystalleap-once-per-line", c -> oncePerLine(c));
        s.test("1112-crystalleap-one-press", c -> onePress(c));
        s.test("1112-crystalleap-auto-persists", c -> persists(c));
        for (String act : new String[] {"use-key", "attack", "swap", "click"}) {
            s.test("1112-crystalleap-same-" + act, c -> sameTick(c, act));
        }
    }

    private static boolean cheat() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object p1() {
        return Mod.enumValue(FLC + "$LeapGroup", "P1");
    }

    private static void mode(Object cfg, String m) {
        Mod.call(cfg, "setLeapMode", p1(), Mod.enumValue(FLC + "$LeapMode", m));
    }

    private static String self(Session c) {
        return c.onClient(mc -> mc.player.getName().getString());
    }

    /** F7 boss, P1 height, the leap in slot 0, a Spirit Leap menu with MATE's head answering the use. */
    private static AutoCloseable setUp(Session c, String leapMode, boolean foe) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        DungeonFixBossCases.floor(c, X, Y, Z);
        TerminalAuraCases.tp(c, X + 0.5, Y, Z + 0.5, 270f);
        c.waitUntil("Floor7Tracker.inF7Boss", mc -> Boolean.TRUE.equals(Mod.staticCall("fastleap.Floor7Tracker", "inF7Boss")), 100);
        c.hx().give(0, Fixtures.byId("items.spirit-leap").payload().getAsJsonObject().get("stack").getAsString());
        JsonObject spec = Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
        spec.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + MATE);
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", spec);
        if (foe) {
            c.server().command(String.format(Locale.ROOT, "testkit spawn %s %.1f %d %.1f", FOE, X + 1.5, Y, Z + 0.5));
            c.server().sync(20);
        }
        AutoCloseable on = c.onClient(mc -> Mod.with(FLC, "Enabled", true));
        Object cfg = c.onClient(mc -> Mod.cfg(FLC));
        c.onClient(mc -> {
            Mod.call(cfg, "setTargetPickName", Mod.enumValue(FLC + "$LeapTarget", "P1"), MATE);
            mode(cfg, leapMode);
            mc.player.getInventory().setSelectedSlot(0);
            Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
            return null;
        });
        c.waitUntil("the leap in slot 0", mc -> "SPIRIT_LEAP".equals(
                Mod.staticCall("cheatutils.CheatUtils", "skyblockId", mc.player.getInventory().getItem(0))), 60);
        c.ctx().waitTicks(10); // past ActionGate's teleport settle
        return on;
    }

    private static void tearDown(Session c, AutoCloseable on) throws Exception {
        c.ctx().runOnClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
            Mod.staticCall(LM, "setCooldownTicksForTest", 0.0);
        });
        c.hx().call("menu.onUse", "id", "SPIRIT_LEAP", "menu", null);
        c.hx().call("give", "slot", 0, "stack", "minecraft:air");
        c.hx().sidebarClear();
        c.server().command("testkit remove " + FOE);
        on.close();
        c.ctx().waitTicks(30);
    }

    private static void line(Session c, String text) {
        c.ctx().runOnClient(mc -> Mod.staticCall(FF, "onChat", Component.literal(text)));
    }

    private static String pickup(String name) {
        return name + " picked up an Energy Crystal!";
    }

    private static int cooldown(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall(LM, "cooldownTicks")).intValue());
    }

    private static int chatCount(Session c, String needle) {
        return c.onClient(mc -> {
            @SuppressWarnings("unchecked")
            List<GuiMessage> all = (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
            int n = 0;
            for (GuiMessage m : all) {
                if (m.content().getString().contains(needle)) {
                    n++;
                }
            }
            return n;
        });
    }

    /** The captured play packets by client tick (client_tick_end closes a tick), class names only. */
    private static List<List<String>> ticks() {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (WireCapture.Sent s : WireCapture.snapshot()) {
            if (!s.phase().equals("play")) {
                continue;
            }
            if (s.text().equals("ServerboundClientTickEndPacket")) {
                out.add(cur);
                cur = new ArrayList<>();
            } else if (!s.text().equals("ServerboundKeepAlivePacket") && !s.text().equals("ServerboundPongPacket")) {
                cur.add(s.text());
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static int count(String marker) {
        int n = 0;
        for (List<String> t : ticks()) {
            for (String p : t) {
                if (p.contains(marker)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int firstTick(String marker) {
        List<List<String>> t = ticks();
        for (int i = 0; i < t.size(); i++) {
            for (String p : t.get(i)) {
                if (p.contains(marker)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void own(Session c) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "AUTO", false);
        try {
            WireCapture.start();
            line(c, pickup(self(c)));
            c.waitUntil("his own pickup line leaps (cooldown starts)", mc -> cooldown(c) > 0, 60);
            c.ctx().waitTicks(4);
            WireCapture.stop();
            c.note("wire: " + ticks());
            c.check(count("UseItem") == 1, "his own pickup line sent " + count("UseItem") + " use packets, want 1");
            c.check(count("ContainerClick") == 1, "his own pickup line sent " + count("ContainerClick") + " menu clicks, want 1");
            c.check(chatCount(c, "Failed to leap") == 0, "a Failed to leap line appeared");
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    private static void other(Session c) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "AUTO", false);
        try {
            WireCapture.start();
            line(c, pickup("SomeoneElse"));
            line(c, pickup(MATE));
            c.ctx().waitTicks(40);
            WireCapture.stop();
            c.check(count("UseItem") == 0 && count("ContainerClick") == 0,
                    "another player's pickup line leaped: " + ticks());
            c.check(cooldown(c) == 0, "another player's pickup line started a leap");
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    private static void oncePerLine(Session c) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "AUTO", false);
        try {
            String me = self(c);
            WireCapture.start();
            for (int i = 1; i <= 2; i++) {
                c.ctx().runOnClient(mc -> Mod.staticCall(LM, "setCooldownTicksForTest", 0.0));
                line(c, pickup(me));
                c.waitUntil("leap " + i + " done", mc -> cooldown(c) > 0, 60);
                c.ctx().waitTicks(20);
                c.check(count("UseItem") == i, "after line " + i + " there are " + count("UseItem") + " use packets");
            }
            WireCapture.stop();
            c.check(count("ContainerClick") == 2, "two lines made " + count("ContainerClick") + " menu clicks, want 2");
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    private static void onePress(Session c) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "FAST", false);
        try {
            WireCapture.start();
            int fails0 = chatCount(c, "On cooldown");
            long t0 = System.currentTimeMillis();
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.waitUntil("the press leaped (cooldown starts)", mc -> cooldown(c) > 0, 80);
            // his double click: the second press lands more than the click delay (250 ms) after the first but within
            // it after the leap finished, so only the leap's own finish time can tell it is the same press
            while (System.currentTimeMillis() - t0 < 280) {
                c.ctx().waitTicks(1);
            }
            long sinceFinish = System.currentTimeMillis() - ((Number) c.onClient(mc -> Mod.staticCall(LM, "lastLeapMs"))).longValue();
            c.check(sinceFinish < 250, "premise: the second press is " + sinceFinish + " ms after the leap finished, want under 250");
            c.ctx().getInput().pressMouse(dev.testkit.compat.McKeys.mouse(0));
            c.ctx().waitTicks(30);
            WireCapture.stop();
            c.note("wire: " + ticks());
            c.check(count("UseItem") == 1, "one press made " + count("UseItem") + " use packets, want 1");
            c.check(count("ContainerClick") == 1, "one press made " + count("ContainerClick") + " menu clicks, want 1");
            c.check(chatCount(c, "On cooldown") == fails0, "a press right after the leap printed On cooldown");
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }

    private static void persists(Session c) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        Object cfg = c.onClient(mc -> Mod.cfg(FLC));
        String name = "tkcrystalleap";
        c.onClient(mc -> {
            mode(cfg, "AUTO");
            Mod.call(cfg, "save");
            return null;
        });
        try {
            c.check(auto(c), "premise: P1 is Auto");
            c.onClient(mc -> Mod.staticCall(FLC, "load"));
            c.check(auto(c), "Auto lost after save and reload");
            c.onClient(mc -> Mod.staticCall("profiles.ProfileManager", "saveCurrentAsProfile", name));
            c.onClient(mc -> {
                mode(Mod.cfg(FLC), "OFF");
                Mod.call(Mod.cfg(FLC), "save");
                return null;
            });
            c.onClient(mc -> Mod.staticCall("profiles.ProfileManager", "applyProfile", name));
            c.check(auto(c), "Auto lost after applying a profile saved with it");
            c.onClient(mc -> Mod.staticCall("profiles.ProfileManager", "syncActiveProfile"));
            c.onClient(mc -> Mod.staticCall(FLC, "load"));
            c.check(auto(c), "Auto lost after the profile autosave and a reload");
            c.note("Auto on P1 survived save/reload, profile apply and autosave");
        } finally {
            c.onClient(mc -> {
                mode(Mod.cfg(FLC), "OFF");
                Mod.call(Mod.cfg(FLC), "save");
                return null;
            });
        }
    }

    private static boolean auto(Session c) {
        return c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(FLC), "isLeapAuto", p1()));
    }

    /** His own action lands in the very tick the pickup line starts the leap; both must be on the wire in one tick. */
    private static void sameTick(Session c, String act) throws Exception {
        if (!cheat()) {
            c.note("legit jar: Fast Leap is cheat-only");
            return;
        }
        AutoCloseable on = setUp(c, "AUTO", act.equals("attack"));
        try {
            String me = self(c);
            if (act.equals("attack")) {
                c.onClient(mc -> {
                    mc.player.setXRot(22f);
                    return null;
                });
                c.ctx().waitTicks(4);
            }
            if (act.equals("click")) { // the leap menu is open already; his click lands in the same tick
                JsonObject shown = Fixtures.byId("menus.spirit-leap").payload().getAsJsonObject().deepCopy();
                shown.getAsJsonObject("slots").getAsJsonObject("13").addProperty("name", "§a" + MATE);
                c.hx().call("menu.show", shown);
                c.waitUntil("a menu", mc -> mc.player.containerMenu != mc.player.inventoryMenu, 60);
                c.ctx().waitTicks(5);
            }
            WireCapture.start();
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(FF, "onChat", Component.literal(pickup(me)));
                switch (act) {
                    case "use-key" -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyUse, "key"));
                    case "attack" -> KeyMapping.click((InputConstants.Key) Mod.field(mc.options.keyAttack, "key"));
                    case "swap" -> mc.player.getInventory().setSelectedSlot(1);
                    default -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, 20, 0,
                            ContainerInput.PICKUP, mc.player);
                }
            });
            c.waitUntil("the leap finished (cooldown starts)", mc -> cooldown(c) > 0, 80);
            c.ctx().waitTicks(8);
            WireCapture.stop();
            c.note(act + " wire by client tick: " + ticks());
            int leapTick = act.equals("click") ? firstTick("ContainerClick") : firstTick("UseItem");
            c.check(leapTick >= 0, "no leap packet went out");
            int clicks = count("ContainerClick");
            switch (act) {
                case "use-key" -> {
                    // his right click with the leap in hand is the leap's own use (the menu opens on it): one use, in the leap's tick
                    c.check(count("UseItem") >= 1 && clicks == 1, "use-key: " + count("UseItem") + " uses, " + clicks + " menu clicks");
                }
                case "attack" -> c.check(firstTick("AttackPacket") == leapTick || firstTick("InteractPacket") == leapTick,
                        "his attack was not in the leap's tick " + leapTick + ": " + ticks());
                case "swap" -> c.check(firstTick("SetCarriedItem") >= 0, "his hotbar swap never went out: " + ticks());
                default -> c.check(clicks >= 2, "his container click and the leap's click: " + clicks + " clicks, want 2");
            }
            c.check(clicks >= 1 && cooldown(c) > 0, "the leap did not complete");
        } finally {
            WireCapture.stop();
            tearDown(c, on);
        }
    }
}
