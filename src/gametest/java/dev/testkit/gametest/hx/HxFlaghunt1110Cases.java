package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.List;

/**
 * flaghunt-1110 group C (inventory / GUI / chat automations vs GrimAC default and experimental).
 * <ul>
 *   <li>1110-c-chat-moving-&lt;fwd|sprint|sneak&gt;: lines the mod sends on its own (the OutgoingChat queue every callout
 *       uses, ServerCommands.toServer, the vanilla sendCommand a keybind or shortcut uses) while he walks, sprints or
 *       sneaks. Experimental GrimAC ChatC flags a chat or command packet sent with movement input, sprint or sneak, so the
 *       mod holds the line until he stands still; the case passes only when every line reached the server AFTER the keys
 *       were let go (none while held) and GrimAC stayed silent.</li>
 * </ul>
 */
final class HxFlaghunt1110Cases {

    private HxFlaghunt1110Cases() {
    }

    static void register(Session s) {
        for (String mode : new String[]{"fwd", "sprint", "sneak"}) {
            s.test("1110-c-chat-moving-" + mode, c -> chatMoving(c, mode));
        }
    }

    private static void hold(Session c, String mode, boolean down) {
        var in = c.ctx().getInput();
        if (down) {
            switch (mode) {
                case "sneak" -> in.holdKey(o -> o.keyShift);
                case "sprint" -> {
                    in.holdKey(o -> o.keyUp);
                    in.holdKey(o -> o.keySprint);
                }
                default -> in.holdKey(o -> o.keyUp);
            }
        } else {
            in.releaseKey(o -> o.keyUp);
            in.releaseKey(o -> o.keySprint);
            in.releaseKey(o -> o.keyShift);
        }
    }

    private static void chatMoving(Session c, String mode) {
        String[] lines = {"fh1110 queue " + mode, "fh1110 server " + mode, "fh1110 vanilla " + mode};
        try {
            hold(c, mode, true);
            c.ctx().waitTicks(10);
            c.onClient(mc -> {
                Mod.staticCall("util.OutgoingChat", "command", "pc " + lines[0]);
                Mod.staticCall("util.ServerCommands", "toServer", "pc " + lines[1]);
                mc.player.connection.sendCommand("pc " + lines[2]);
                return null;
            });
            // While he keeps moving the lines stay held (nothing at the server yet); that is the feature acting by waiting.
            c.ctx().waitTicks(12);
            c.check(c.commands().stream().noneMatch(s -> s.contains("fh1110")),
                    "a mod-sent line reached the server while he was moving: " + c.commands());
        } finally {
            hold(c, mode, false);
        }
        for (String l : lines) {
            c.waitUntil("the held line '" + l + "' reaching the server once he stood still",
                    mc -> c.commands().contains("pc " + l), 160);
        }
        List<String> sent = c.commands().stream().filter(s -> s.contains("fh1110")).toList();
        c.note("held until still, then sent in order: " + sent);
    }
}
