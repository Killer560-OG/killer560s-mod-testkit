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
    /** GLFW keypad 7, 8, 9, decimal, divide, multiply: bound to nothing in vanilla or the mod's defaults. */
    private static final int K1 = 327;
    private static final int K2 = 328;
    private static final int K3 = 329;
    private static final int K4 = 330;
    private static final int K5 = 331;
    private static final int K6 = 332;

    private HxChatKeybindsCases() {
    }

    static void register(Session s) {
        s.test("623-hx-chat-keybinds-key-sends", HxChatKeybindsCases::keySends);
        s.test("624-hx-chat-keybinds-f7", HxChatKeybindsCases::f7);
        s.test("625-hx-chat-keybinds-custom-live", HxChatKeybindsCases::customLive);
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
            c.ctx().getInput().holdKeyFor(K1, 3);
            String warp = awaitCommand(c, "warp", 40);
            c.check(warp.equals("warp dh"), "the /warp bind sent /" + warp);
            c.ctx().getInput().holdKeyFor(K2, 3);
            c.waitUntil("a player chat event 'k5 bind chat'", mc -> c.events("chat").stream()
                    .anyMatch(e -> e.get("text").getAsString().equals("k5 bind chat")), 40);
            c.ctx().getInput().holdKeyFor(K3, 3);
            assertNoCommand(c, 20, "is ");
            c.check(commandsStarting(c, "warp").size() == 1, "one 3-tick press should send once: " + c.commands());
            long chats = c.events("chat").stream().filter(e -> e.get("text").getAsString().equals("k5 bind chat")).count();
            c.check(chats == 1, "chat bind sent " + chats + " times");

            run(c, () -> Mod.call(Mod.cfg(KB), "setEnabled", false));
            c.ctx().getInput().holdKeyFor(K1, 3);
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
            c.ctx().getInput().holdKeyFor(K4, 3);
            c.waitUntil("a second /warp hub from the key", mc -> commandsStarting(c, "warp hub").size() == 2, 40);
            c.ctx().getInput().holdKeyFor(K5, 3);
            String is = awaitCommand(c, "is ", 40);
            c.check(is.equals("is hub"), "the nameless row's key sent /" + is);
            c.ctx().getInput().holdKeyFor(K6, 3);
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
