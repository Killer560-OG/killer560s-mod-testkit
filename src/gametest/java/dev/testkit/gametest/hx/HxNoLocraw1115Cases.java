package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import java.util.ArrayList;
import java.util.List;
import static dev.testkit.gametest.hx.HxKit.*;

/**
 * nolocraw-1115 (owner 11-15: a server must never be able to tell the mod is installed): Mod Chat's Lobby mode reads its
 * lobby id from the scoreboard sidebar's date line and sends no command. With a sidebar id set, Lobby mode on and the
 * player standing still for 10 s, no {@code locraw} command goes over the wire, the mod's lobby value is the sidebar id
 * (long form, as the old command reply was stored: m107F -> mini107f), and a lobby swap (a new id) is followed.
 */
final class HxNoLocraw1115Cases {
    private HxNoLocraw1115Cases() { }

    private static final String LOC = "relay.HypixelLocation";

    static void register(Session s) {
        s.test("1115-hx-nolocraw", HxNoLocraw1115Cases::noLocraw);
    }

    private static String id(Session c) {
        return c.onClient(mc -> (String) Mod.field(LOC, "serverId"));
    }

    private static String key(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(LOC, "lobbyKey"));
    }

    private static void sidebar(Session c, String id) {
        c.hx().sidebar("SKYBLOCK", "§711/15/26 §8" + id, " Late Spring 8th", " Purse: 0");
    }

    private static void noLocraw(Session c) {
        HxSpotifyDocCases.noParty(c);
        WireCapture.describe = p -> p.getClass().getSimpleName().contains("Command") ? p.getClass().getSimpleName() + " " + p
                : p.getClass().getSimpleName();
        try (Settings s = HxSpotifyDocCases.noData(new Settings(c)).with("modchat.ModChatConfig", "Enabled", true)
                .with("modchat.ModChatConfig", "RoomMode", Mod.enumValue("relay.RelayRoom$Mode", "LOBBY"))) {
            c.check(id(c) == null || !id(c).startsWith("mini107"), "an old lobby id was left over (premise)");
            WireCapture.start();
            sidebar(c, "m107F");
            c.waitUntil("the lobby id read off the sidebar", mc -> "mini107f".equals(Mod.field(LOC, "serverId")), 100);
            String first = key(c);
            c.check(first != null && first.startsWith("lobby:"), "no lobby room key from the sidebar id: " + first);
            // Standing still for 10 s: the old sender fired here (up to 3 tries, 10 s apart).
            c.ctx().waitTicks(230);
            // Control: the capture does see a command (a harmless one the test sends itself).
            c.onClient(mc -> {
                mc.player.connection.sendCommand("list");
                return null;
            });
            c.ctx().waitTicks(5);
            List<String> cmds = new ArrayList<>();
            for (WireCapture.Sent w : WireCapture.snapshot()) {
                if (w.phase().equals("play") && (w.type() + " " + w.text()).contains("Command")) {
                    cmds.add(w.type() + " " + w.text());
                }
            }
            c.check(cmds.stream().anyMatch(t -> t.contains("list")), "the wire capture did not see the control command: " + cmds);
            c.check(cmds.stream().noneMatch(t -> t.toLowerCase().contains("locraw")), "a locraw command was sent: " + cmds);
            // A lobby swap: a new id on the sidebar is followed, and the room key changes with it.
            sidebar(c, "m22XQ");
            c.waitUntil("the new lobby id", mc -> "mini22xq".equals(Mod.field(LOC, "serverId")), 100);
            String second = key(c);
            c.check(second != null && !second.equals(first), "the lobby key did not follow the swap: " + first + " -> " + second);
            c.note("lobby id read off the sidebar (mini107f then mini22xq), keys " + first + " / " + second + ", wire commands "
                    + cmds.size() + " (control only), no locraw in 235 ticks standing still");
        } finally {
            WireCapture.stop();
            WireCapture.describe = null;
            c.hx().sidebarClear();
            c.onClient(mc -> {
                Mod.setField(LOC, "serverId", null);
                return null;
            });
        }
    }
}
