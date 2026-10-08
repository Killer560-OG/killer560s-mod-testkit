package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 544: custom Command Shortcuts (killer560, 2026-10-07: "There should be a custom section on command shortcuts.") typed
 * in a real ChatScreen send EXACTLY the configured command to the server - slash or no slash in the setting, outer
 * spaces trimmed, once, and never the shortcut's own word - and a switched-off, master-off or deleted shortcut sends
 * nothing of its own: the typed word falls through to the server as an unknown command, as if it had never been
 * registered. The rows are made through the config API and registered with {@code syncNow} (the live path the tab
 * takes after a quiet moment); the tab itself is driven in 541-543 ({@code ui/CmdShortcutsCases}).
 */
final class HxCmdShortcutsCases {

    private static final String FEAT = "commandshortcuts.CommandShortcutsFeature";
    private static final String CFG = "commandshortcuts.CommandShortcutsConfig";

    private HxCmdShortcutsCases() {
    }

    static void register(Session s) {
        s.test("544-hx-cmd-shortcuts-send", c -> {
            boolean hasCustom = c.onClient(mc -> {
                try {
                    Mod.cls(CFG).getMethod("customs");
                    return true;
                } catch (NoSuchMethodException e) {
                    return false;
                }
            });
            c.check(hasCustom, "this jar has no custom command shortcuts (CommandShortcutsConfig.customs)");
            boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            boolean masterWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEnabledRaw"));
            try {
                run(c, () -> {
                    Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                    Object cfg = Mod.cfg(CFG);
                    Mod.call(cfg, "setEnabled", true);
                    clear(cfg);
                    Object a = Mod.call(cfg, "addCustom");
                    Mod.call(cfg, "setCustomName", a, "k5a");
                    Mod.call(cfg, "setCustomCommand", a, "/warp dh");
                    Object b = Mod.call(cfg, "addCustom");
                    Mod.call(cfg, "setCustomName", b, "k5b");
                    Mod.call(cfg, "setCustomCommand", b, "  is hub  ");
                    Mod.staticCall(FEAT, "syncNow");
                });
                @SuppressWarnings("unchecked")
                List<String> roots = new ArrayList<>(c.onClient(mc ->
                        (java.util.Set<String>) Mod.staticCall(FEAT, "customRoots")));
                c.check(roots.contains("k5a") && roots.contains("k5b"), "custom roots registered: " + roots);

                // Sends, exactly.
                type(c, "/k5a");
                String warp = awaitCommand(c, "warp", 40);
                c.check(warp.equals("warp dh"), "/k5a sent /" + warp);
                type(c, "/k5b");
                String is = awaitCommand(c, "is ", 40);
                c.check(is.equals("is hub"), "/k5b sent /" + is);
                c.ctx().waitTicks(10);
                c.check(commandsStarting(c, "warp").size() == 1 && commandsStarting(c, "is ").size() == 1,
                        "each shortcut should send once: " + c.commands());
                c.check(commandsStarting(c, "k5").isEmpty(), "a shortcut's own word reached the server: "
                        + c.commands());

                // Switched off: the word goes to the server as typed, and nothing of the shortcut's.
                run(c, () -> Mod.call(Mod.cfg(CFG), "setCustomEnabled", row("k5a"), false));
                type(c, "/k5a");
                awaitCommand(c, "k5a", 40);
                c.ctx().waitTicks(10);
                c.check(commandsStarting(c, "warp").size() == 1, "switched-off /k5a still sent: " + c.commands());

                // Master switch off: the same for one that is on.
                run(c, () -> {
                    Mod.call(Mod.cfg(CFG), "setCustomEnabled", row("k5a"), true);
                    Mod.call(Mod.cfg(CFG), "setEnabled", false);
                });
                type(c, "/k5b");
                awaitCommand(c, "k5b", 40);
                c.ctx().waitTicks(10);
                c.check(commandsStarting(c, "is ").size() == 1, "/k5b sent with the master switch off: "
                        + c.commands());

                // Deleted: likewise, and the other one still works.
                run(c, () -> {
                    Mod.call(Mod.cfg(CFG), "setEnabled", true);
                    Mod.call(Mod.cfg(CFG), "removeCustom", row("k5b"));
                });
                int k5bBefore = commandsStarting(c, "k5b").size();
                type(c, "/k5b");
                c.waitUntil("the deleted /k5b at the server as typed",
                        mc -> commandsStarting(c, "k5b").size() > k5bBefore, 40);
                type(c, "/k5a");
                c.waitUntil("a second /warp dh", mc -> commandsStarting(c, "warp").size() == 2, 40);
                c.ctx().waitTicks(10);
                c.check(commandsStarting(c, "is ").size() == 1, "deleted /k5b still sent: " + c.commands());
                c.check(commandsStarting(c, "warp").stream().allMatch("warp dh"::equals), "warp lines " + c.commands());
                c.note("/k5a -> /" + warp + ", /k5b ('  is hub  ') -> /" + is + ", once each, never the word itself;"
                        + " off / master off / deleted -> the typed word reached the server unhandled; server saw "
                        + c.commands());
            } finally {
                run(c, () -> {
                    Object cfg = Mod.cfg(CFG);
                    clear(cfg);
                    Mod.call(cfg, "setEnabled", masterWas);
                    Mod.call(cfg, "save");
                    Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                });
            }
        });
    }

    private static Object row(String name) {
        return Mod.call(Mod.cfg(CFG), "customNamed", name);
    }

    @SuppressWarnings("unchecked")
    private static void clear(Object cfg) {
        for (Object r : new ArrayList<>((List<Object>) Mod.call(cfg, "customs"))) {
            Mod.call(cfg, "removeCustom", r);
        }
    }
}
