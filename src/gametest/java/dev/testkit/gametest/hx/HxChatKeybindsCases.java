package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Chat Keybinds with Command Shortcuts merged into it (killer560, 2026-10-08: "Chat keybinds and command shortcuts should
 * be combined into just chat keybinds."; mod branch chat-keybinds-merge), against the testkit server that records what
 * the client sends. Keys are real key presses ({@code holdKeyFor} on keypad keys nothing else binds), read by the mod's
 * own poll; commands are typed into a real ChatScreen.
 * <ul>
 *   <li>623: a key bind still sends - a "/" line as a command, a plain line as chat, once per press; a bind switched off
 *       on its own row sends nothing, nor does any bind with the one switch off.</li>
 *   <li>624: /f7 still sends {@code joininstance catacombs_floor_seven} (never /f7), /kuudra infernal its Kuudra id, and
 *       the Chat Keybinds switch is the shortcuts' switch: off, /kuudra hot sends nothing.</li>
 *   <li>625: a custom shortcut made through the config registers LIVE on the quiet-timer path the tab uses (no
 *       syncNow), sends its command when typed, and its key sends the same; a key on a row with no name sends too, a
 *       switched-off row's key does not.</li>
 * </ul>
 */
final class HxChatKeybindsCases {

    private static final String KB = "commandkeybinds.CommandKeybindsConfig";
    private static final String CFG = "commandshortcuts.CommandShortcutsConfig";
    private static final String FEAT = "commandshortcuts.CommandShortcutsFeature";
    /** GLFW keypad 7, 8, 9, decimal, +, multiply (pressed through McKeys.nat): bound to nothing in vanilla or the mod's defaults. */
    private static final int K1 = 327;
    private static final int K2 = 328;
    private static final int K3 = 329;
    private static final int K4 = 330;
    private static final int K5 = 334; // GLFW keypad + (keypad divide, 331, has no InputConstants name to map to 26.3)
    private static final int K6 = 332;

    private HxChatKeybindsCases() {
    }

    static void register(Session s) {
        s.test("623-hx-chat-keybinds-key-sends", HxChatKeybindsCases::keySends);
        s.test("624-hx-chat-keybinds-f7", HxChatKeybindsCases::f7);
        s.test("625-hx-chat-keybinds-custom-live", HxChatKeybindsCases::customLive);
        s.test("1111-hx-cmdkey-first", HxChatKeybindsCases::cmdFirst);
    }

    private static boolean merged(Session c) {
        return c.onClient(mc -> {
            try {
                Mod.cls(CFG).getMethod("setCustomKey", Mod.cls(CFG + "$CustomShortcut"), int.class);
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        });
    }

    // ---- 623 ------------------------------------------------------------------------------------------------------

    private static void keySends(Session c) {
        c.check(merged(c), "this jar has no merged Chat Keybinds (CommandShortcutsConfig.setCustomKey)");
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        boolean masterWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(KB), "isEnabledRaw"));
        try {
            run(c, () -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Object kb = Mod.cfg(KB);
                Mod.call(kb, "setEnabled", true);
                binds(kb).clear();
                addBind(kb, K1, " /warp dh ", true);
                addBind(kb, K2, "k5 bind chat", true);
                addBind(kb, K3, "/is hub", false);
                McCompat.setScreen(net.minecraft.client.Minecraft.getInstance(), null);
            });
            c.ctx().waitTicks(3);
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K1), 3);
            String warp = awaitCommand(c, "warp", 40);
            c.check(warp.equals("warp dh"), "the /warp bind sent /" + warp);
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K2), 3);
            c.waitUntil("a player chat event 'k5 bind chat'", mc -> c.events("chat").stream()
                    .anyMatch(e -> e.get("text").getAsString().equals("k5 bind chat")), 40);
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K3), 3);
            assertNoCommand(c, 20, "is ");
            c.check(commandsStarting(c, "warp").size() == 1, "one 3-tick press should send once: " + c.commands());
            long chats = c.events("chat").stream().filter(e -> e.get("text").getAsString().equals("k5 bind chat")).count();
            c.check(chats == 1, "chat bind sent " + chats + " times");

            run(c, () -> Mod.call(Mod.cfg(KB), "setEnabled", false));
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K1), 3);
            c.ctx().waitTicks(20);
            c.check(commandsStarting(c, "warp").size() == 1, "a bind sent with the switch off: " + c.commands());
            c.note("key bind ' /warp dh ' -> /" + warp + " once; 'k5 bind chat' -> chat once; row OFF -> nothing; "
                    + "switch OFF -> nothing; server saw " + c.commands());
        } finally {
            run(c, () -> {
                Object kb = Mod.cfg(KB);
                binds(kb).clear();
                Mod.call(kb, "setEnabled", masterWas);
                Mod.call(kb, "save");
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            });
        }
    }

    // ---- 624 ------------------------------------------------------------------------------------------------------

    private static void f7(Session c) {
        c.check(merged(c), "this jar has no merged Chat Keybinds (CommandShortcutsConfig.setCustomKey)");
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        boolean masterWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(KB), "isEnabledRaw"));
        try {
            run(c, () -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.call(Mod.cfg(KB), "setEnabled", true);
            });
            type(c, "/f7");
            String f7 = awaitCommand(c, "joininstance catacombs", 40);
            c.check(f7.equals("joininstance catacombs_floor_seven"), "/f7 sent /" + f7);
            type(c, "/kuudra infernal");
            String inf = awaitCommand(c, "joininstance kuudra", 40);
            c.check(inf.equals("joininstance kuudra_infernal"), "/kuudra infernal sent /" + inf);
            c.check(commandsStarting(c, "f7").isEmpty() && commandsStarting(c, "kuudra").isEmpty(),
                    "a shortcut's own word reached the server: " + c.commands());

            // The one switch: off in Chat Keybinds is off for the shortcuts.
            run(c, () -> Mod.call(Mod.cfg(KB), "setEnabled", false));
            boolean raw = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEnabledRaw"));
            c.check(!raw, "CommandShortcutsConfig.isEnabledRaw is still on with Chat Keybinds off");
            type(c, "/kuudra hot");
            c.ctx().waitTicks(20);
            c.check(commandsStarting(c, "joininstance kuudra_hot").isEmpty(), "/kuudra hot sent with the switch off: "
                    + c.commands());
            c.note("/f7 -> /" + f7 + ", /kuudra infernal -> /" + inf + "; Chat Keybinds OFF -> /kuudra hot sent nothing; "
                    + "server saw " + c.commands());
        } finally {
            run(c, () -> {
                Mod.call(Mod.cfg(KB), "setEnabled", masterWas);
                Mod.call(Mod.cfg(KB), "save");
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            });
        }
    }

    // ---- 625 ------------------------------------------------------------------------------------------------------

    private static void customLive(Session c) {
        c.check(merged(c), "this jar has no merged Chat Keybinds (CommandShortcutsConfig.setCustomKey)");
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        boolean masterWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(KB), "isEnabledRaw"));
        try {
            run(c, () -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Object kb = Mod.cfg(KB);
                Mod.call(kb, "setEnabled", true);
                binds(kb).clear();
                Object cfg = Mod.cfg(CFG);
                clearCustoms(cfg);
                Object row = Mod.call(cfg, "addCustom");
                Mod.call(cfg, "setCustomName", row, "k5live");
                Mod.call(cfg, "setCustomCommand", row, "/warp hub");
                Mod.call(cfg, "save");
                Mod.staticCall(FEAT, "customsChanged");   // the tab's path: registered once the quiet timer runs out
            });
            c.waitUntil("/k5live registered live", mc -> {
                @SuppressWarnings("unchecked")
                java.util.Set<String> roots = (java.util.Set<String>) Mod.staticCall(FEAT, "customRoots");
                return roots.contains("k5live");
            }, 100);
            type(c, "/k5live");
            String hub = awaitCommand(c, "warp", 40);
            c.check(hub.equals("warp hub"), "/k5live sent /" + hub);

            // Its key sends the same command; a key on a nameless row too; a switched-off row's key nothing.
            run(c, () -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setCustomKey", Mod.call(cfg, "customNamed", "k5live"), K4);
                Object nameless = Mod.call(cfg, "addCustom");
                Mod.call(cfg, "setCustomCommand", nameless, "is hub");
                Mod.call(cfg, "setCustomKey", nameless, K5);
                Object off = Mod.call(cfg, "addCustom");
                Mod.call(cfg, "setCustomName", off, "k5keyoff");
                Mod.call(cfg, "setCustomCommand", off, "/warp dh");
                Mod.call(cfg, "setCustomKey", off, K6);
                Mod.call(cfg, "setCustomEnabled", off, false);
                Mod.call(cfg, "save");
            });
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K4), 3);
            c.waitUntil("a second /warp hub from the key", mc -> commandsStarting(c, "warp hub").size() == 2, 40);
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K5), 3);
            String is = awaitCommand(c, "is ", 40);
            c.check(is.equals("is hub"), "the nameless row's key sent /" + is);
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K6), 3);
            assertNoCommand(c, 20, "warp dh");
            c.check(commandsStarting(c, "warp hub").size() == 2 && commandsStarting(c, "is ").size() == 1,
                    "each send once: " + c.commands());
            c.check(commandsStarting(c, "k5").isEmpty(), "a shortcut's own word reached the server: " + c.commands());
            c.note("/k5live registered live (customsChanged, no syncNow) and sent /" + hub + "; its key sent it again; a "
                    + "nameless row's key sent /" + is + "; a switched-off row's key sent nothing; server saw " + c.commands());
        } finally {
            run(c, () -> {
                Object cfg = Mod.cfg(CFG);
                clearCustoms(cfg);
                Object kb = Mod.cfg(KB);
                Mod.call(kb, "setEnabled", masterWas);
                Mod.call(kb, "save");
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            });
        }
    }

    // ---- 1111 -----------------------------------------------------------------------------------------------------

    /**
     * killer560, 2026-11-11: "each time i press my keybind for stats in ee3 it delays for a long time ... commands always
     * go first." Hypixel handles a player's commands in order, so the mod's party lines already sent sit in front of his.
     * Then (his test, 22:04): "my command should always be n1 prio". /stats must leave in the press's tick, no line of ours
     * may go until the server answers it (or 250 ms), a party line waits for its echo (or 250 ms), and the gap counts from
     * the echo.
     */
    private static void wire(List<String> order, List<Long> at) {
        for (dev.testkit.harness.WireCapture.Sent w : dev.testkit.harness.WireCapture.snapshot()) {
            if (w.text().startsWith("cmd:pc cf") || w.text().equals("cmd:stats")) {
                order.add(w.text().substring(4));
                at.add(w.nanos());
            }
        }
    }

    private static void cmdFirst(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        boolean masterWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(KB), "isEnabledRaw"));
        dev.testkit.harness.WireCapture.describe = p -> p instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket cp
                ? "cmd:" + cp.command() : "-";
        try {
            run(c, () -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Object kb = Mod.cfg(KB);
                Mod.call(kb, "setEnabled", true);
                binds(kb).clear();
                addBind(kb, K1, "/stats", true);
                McCompat.setScreen(net.minecraft.client.Minecraft.getInstance(), null);
            });
            // /stats is answered by nothing unless the case says so (a vanilla "Unknown command" would answer it at once).
            respond(c, "stats", "never-matches");
            c.ctx().waitTicks(40); // the last case's lines are out and their gap is over
            // (a) A party line in flight, then his command: /stats leaves in the press's tick, and no line of ours goes
            // until the server answers /stats, even after "cf one" echoed; then a full gap.
            dev.testkit.harness.WireCapture.start();
            run(c, () -> {
                Mod.staticCall("util.OutgoingChat", "command", "pc cf one");
                Mod.staticCall("util.OutgoingChat", "command", "pc cf two");
            });
            int waiting = c.onClient(mc -> (Integer) Mod.staticCall("util.OutgoingChat", "pending"));
            c.check(waiting == 1, "want 1 line waiting behind 'cf one', got " + waiting);
            c.ctx().waitTicks(3);
            long pressNs = System.nanoTime();
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K1), 3);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] Tester§f: cf one");   // "cf one" echoes as he presses
            c.ctx().waitTicks(1);
            boolean twoEarly = c.commands().contains("pc cf two");
            long answerNs = System.nanoTime();
            c.hx().chat("§7Your stats menu answer");                     // the server answers /stats (not party chat)
            c.waitUntil("'cf two' at the server", mc -> c.commands().contains("pc cf two"), 100);
            dev.testkit.harness.WireCapture.stop();
            List<String> order = new ArrayList<>();
            List<Long> at = new ArrayList<>();
            wire(order, at);
            c.note("(a) wire order " + order + "; ms after the press "
                    + at.stream().map(n -> (n - pressNs) / 1_000_000L).toList() + "; answer sent at "
                    + (answerNs - pressNs) / 1_000_000L + " ms; server saw " + c.commands());
            c.check(order.equals(List.of("pc cf one", "stats", "pc cf two")), "want one, stats, two on the wire: " + order);
            long statsMs = (at.get(1) - pressNs) / 1_000_000L;
            c.check(statsMs <= 120, "/stats left " + statsMs + " ms after the press (want the press's own tick)");
            c.check(!twoEarly, "'cf two' went before the server answered /stats");
            long afterAnswer = (at.get(2) - answerNs) / 1_000_000L;
            c.check(afterAnswer >= 230 && afterAnswer < 700, "'cf two' left " + afterAnswer + " ms after the answer (want a 250 ms gap after it)");

            c.hx().chat("§9Party §8> §b[MVP§c+§b] Tester§f: cf two");   // 'cf two' echoes: nothing in flight

            // (b) No answer: the hold ends after PLAYER_ANSWER_MS (250 ms).
            c.ctx().waitTicks(40);
            dev.testkit.harness.WireCapture.start();
            run(c, () -> Mod.staticCall("util.OutgoingChat", "command", "pc cf three"));
            c.waitUntil("'cf three' at the server", mc -> c.commands().contains("pc cf three"), 40);
            c.hx().chat("§9Party §8> §b[MVP§c+§b] Tester§f: cf three");
            c.ctx().waitTicks(12);
            long press2Ns = System.nanoTime();
            c.ctx().getInput().holdKeyFor(dev.testkit.compat.McKeys.nat(K1), 3);
            c.ctx().waitTicks(3);
            run(c, () -> Mod.staticCall("util.OutgoingChat", "command", "pc cf four"));
            c.waitUntil("'cf four' at the server", mc -> c.commands().contains("pc cf four"), 100);
            dev.testkit.harness.WireCapture.stop();
            order.clear();
            at.clear();
            wire(order, at);
            c.check(order.equals(List.of("pc cf three", "stats", "pc cf four")), "(b) want three, stats, four: " + order);
            long held = (at.get(2) - at.get(1)) / 1_000_000L;
            c.note("(b) unanswered /stats held 'cf four' " + held + " ms");
            c.check(held >= 230 && held < 700, "'cf four' left " + held + " ms after an unanswered /stats (want ~250)");

            c.hx().chat("§9Party §8> §b[MVP§c+§b] Tester§f: cf four");

            // (c) No echo: the next party line waits ECHO_WAIT_MS (250 ms); (d) with the echo, a gap after the echo.
            c.ctx().waitTicks(40);
            dev.testkit.harness.WireCapture.start();
            run(c, () -> {
                Mod.staticCall("util.OutgoingChat", "command", "pc cf five");
                Mod.staticCall("util.OutgoingChat", "command", "pc cf six");
            });
            c.waitUntil("'cf six' at the server", mc -> c.commands().contains("pc cf six"), 200);
            long echoNs = System.nanoTime();
            c.hx().chat("§9Party §8> §b[MVP§c+§b] Tester§f: cf six");
            run(c, () -> Mod.staticCall("util.OutgoingChat", "command", "pc cf seven"));
            c.waitUntil("'cf seven' at the server", mc -> c.commands().contains("pc cf seven"), 60);
            dev.testkit.harness.WireCapture.stop();
            order.clear();
            at.clear();
            wire(order, at);
            c.check(order.equals(List.of("pc cf five", "pc cf six", "pc cf seven")), "(c) want five, six, seven: " + order);
            long noEcho = (at.get(1) - at.get(0)) / 1_000_000L;
            long afterEcho = (at.get(2) - echoNs) / 1_000_000L;
            c.note("(c) 'cf six' " + noEcho + " ms after an unechoed 'cf five'; (d) 'cf seven' " + afterEcho
                    + " ms after the echo of 'cf six'");
            c.check(noEcho >= 230 && noEcho < 700, "'cf six' left " + noEcho + " ms after 'cf five' with no echo (want ~250)");
            c.check(afterEcho >= 230 && afterEcho < 700, "'cf seven' left " + afterEcho + " ms after the echo (want ~250)");
        } finally {
            dev.testkit.harness.WireCapture.stop();
            dev.testkit.harness.WireCapture.describe = null;
            c.hx().call("surface.respond.clear", dev.testkit.gametest.hx.Hx.args("root", "stats"));
            run(c, () -> {
                Object kb = Mod.cfg(KB);
                binds(kb).clear();
                Mod.call(kb, "setEnabled", masterWas);
                Mod.call(kb, "save");
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
            });
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> binds(Object kb) {
        return (List<Object>) Mod.call(kb, "binds");
    }

    private static void addBind(Object kb, int key, String text, boolean on) {
        Mod.call(kb, "addBind");
        List<Object> list = binds(kb);
        Object b = list.get(list.size() - 1);
        try {
            b.getClass().getField("key").setInt(b, key);
            b.getClass().getField("text").set(b, text);
            b.getClass().getField("enabled").setBoolean(b, on);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Bind fields: " + e, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearCustoms(Object cfg) {
        for (Object r : new ArrayList<>((List<Object>) Mod.call(cfg, "customs"))) {
            Mod.call(cfg, "removeCustom", r);
        }
    }
}
