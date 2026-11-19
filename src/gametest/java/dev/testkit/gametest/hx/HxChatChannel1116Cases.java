package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.ui.Frames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Chat Channel Indicator (mod branch chatchannel-1116): the real Hypixel lines and typed commands set the channel, the
 * line is drawn above the chat input (with Chat Tabs on and off, clear of the tab row), OFF draws nothing, settings persist.
 */
final class HxChatChannel1116Cases {

    static final String F = "chatchannel.ChatChannelFeature";
    static final String CFG = "chatchannel.ChatChannelConfig";
    static final String TABS_CFG = "chattabs.ChatTabsConfig";

    private HxChatChannel1116Cases() {
    }

    static void register(Session s) {
        s.test("1116-chatchannel-lines", HxChatChannel1116Cases::lines);
        s.test("1116-chatchannel-party-count", HxChatChannel1116Cases::partyCount);
        s.test("1116-chatchannel-draw", HxChatChannel1116Cases::draw);
        s.test("1116-chatchannel-settings", HxChatChannel1116Cases::settings);
        s.test("1118-chatchannel-hud", HxChatChannel1116Cases::hudMode);
    }

    private static String shown(Session c) {
        return c.onClient(mc -> (String) Mod.staticCall(F, "shownText"));
    }

    private static void waitShown(Session c, String what, String want) {
        c.waitUntil(what + " (want " + want + ")", mc -> want.equals(Mod.staticCall(F, "shownText")), 40);
    }

    private static void line(Session c, String legacy) {
        c.hx().chat(legacy);
    }

    private static void lines(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true)) {
            dungeon(c);
            run(c, () -> Mod.staticCall(F, "resetForTest"));
            c.check(shown(c) == null, "a channel was shown before any line settled it: " + shown(c));
            line(c, "§aYou are now in the §r§6GUILD§r§a channel");
            waitShown(c, "Hypixel's line sets Guild", "Chat: Guild");
            line(c, "§aYou are now in the §r§6OFFICER§r§a channel");
            waitShown(c, "Officer", "Chat: Officer");
            line(c, "§aYou are now in the §r§6SKYBLOCK CO-OP§r§a channel");
            waitShown(c, "Co-op", "Chat: Skyblock Co-op");
            line(c, "§aYou are now in the §r§6ALL§r§a channel");
            waitShown(c, "All", "Chat: All");
            // A typed /chat p predicts at once, Hypixel's line then confirms it.
            type(c, "/chat p");
            waitShown(c, "typed /chat p predicts Party", "Chat: Party");
            line(c, "§aYou are now in the §r§6PARTY§r§a channel");
            waitShown(c, "the line confirms Party", "Chat: Party");
            // /pc and /gc never change it.
            type(c, "/pc hello");
            type(c, "/gc hello");
            c.ctx().waitTicks(6);
            c.check("Chat: Party".equals(shown(c)), "/pc or /gc changed the channel: " + shown(c));
            // Not in a party: moved to ALL.
            line(c, "§cYou are not in a party and were moved to the ALL channel.");
            waitShown(c, "moved to ALL", "Chat: All");
            // A private conversation, then it expires.
            line(c, "§aOpened a chat conversation with §r§b[MVP§r§5+§r§b] HxMateA§r§a for the next 5 minutes. Use §r§b/chat a§r§a to leave");
            waitShown(c, "private conversation", "Chat: To HxMateA");
            line(c, "§cThe conversation you were in expired and you have been moved back to the ALL channel.");
            waitShown(c, "conversation expired", "Chat: All");
            // A player's chat carrying the text never sets it.
            line(c, "§7[VIP] HxMateB§7: You are now in the PARTY channel");
            c.ctx().waitTicks(6);
            c.check("Chat: All".equals(shown(c)), "a player's chat line changed the channel: " + shown(c));
            // A prediction nobody answers is given up.
            type(c, "/chat g");
            waitShown(c, "predicts Guild", "Chat: Guild");
            c.waitUntil("an unanswered prediction is dropped", mc -> "Chat: All".equals(Mod.staticCall(F, "shownText")), 120);
            // Disconnect state: reset clears it.
            run(c, () -> Mod.staticCall(F, "resetForTest"));
            c.check(shown(c) == null, "reset left a channel: " + shown(c));
            c.note("Guild/Officer/Co-op/All/Party lines, typed /chat p predicts then confirmed, /pc and /gc ignored, moved-to-ALL,"
                    + " private conversation + expiry, forged player line ignored, unanswered prediction dropped, reset clears");
        } finally {
            run(c, () -> Mod.staticCall(F, "resetForTest"));
        }
    }

    private static void partyCount(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(CFG, "ShowMemberCount", true)) {
            dungeon(c);
            run(c, () -> Mod.staticCall(F, "resetForTest"));
            line(c, "§eYou left the party.");
            line(c, "§aYou are now in the §r§6PARTY§r§a channel");
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), new ChatScreen("", false)));
            c.ctx().waitTicks(4);
            c.check("Chat: Party".equals(shown(c)), "no party known but shown " + shown(c));
            line(c, "§bHxMateA §ejoined the party.");
            line(c, "§bHxMateB §ejoined the party.");
            c.waitUntil("two joins show 3 members (them plus you)", mc -> "Chat: Party (3 members)".equals(Mod.staticCall(F, "shownText")), 60);
            line(c, "§bHxMateB §ehas left the party.");
            c.waitUntil("a leave shows 2 members", mc -> "Chat: Party (2 members)".equals(Mod.staticCall(F, "shownText")), 60);
            run(c, () -> Mod.set(CFG, "setShowMemberCount", false));
            c.check("Chat: Party".equals(shown(c)), "Show Member Count off still shows a count: " + shown(c));
            c.note("no party: no count; joins -> 3 members; leave -> 2; Show Member Count off -> no count");
        } finally {
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
            line(c, "§eYou left the party.");
            run(c, () -> Mod.staticCall(F, "resetForTest"));
        }
    }

    /** Texts drawn by one off-screen extract of a chat screen. */
    private static int texts(Session c) {
        return c.onClient(mc -> {
            ChatScreen s = new ChatScreen("", false);
            McCompat.setScreen(mc, s);
            return Frames.extract(mc, s, -1, -1).texts();
        });
    }

    private static void draw(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(TABS_CFG, "Enabled", false)) {
            dungeon(c);
            run(c, () -> Mod.staticCall(F, "resetForTest"));
            line(c, "§aYou are now in the §r§6GUILD§r§a channel");
            waitShown(c, "Guild", "Chat: Guild");
            int withLine = texts(c);
            long f0 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            c.check(f0 > 0, "the indicator never drew in an open chat");
            int y0 = c.onClient(mc -> (Integer) Mod.staticCall(F, "lastY"));
            int h = c.onClient(mc -> McCompat.screen(mc) == null ? -1 : McCompat.screen(mc).height);
            // Above the input bar (vanilla draws it from height - 14).
            c.check(y0 > 0 && y0 + 9 <= h - 14, "line at y " + y0 + " is not above the input bar (height " + h + ")");
            // With Chat Tabs on it sits above their row too (row top = height - 27).
            run(c, () -> Mod.set(TABS_CFG, "setEnabled", true));
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            int withLine2 = texts(c);
            int y1 = c.onClient(mc -> (Integer) Mod.staticCall(F, "lastY"));
            int h1 = c.onClient(mc -> McCompat.screen(mc) == null ? -1 : McCompat.screen(mc).height);
            c.check(y1 + 9 <= h1 - 27, "with Chat Tabs on, the line (y " + y1 + ") overlaps the tab row (top " + (h1 - 27) + ")");
            c.check(y1 < y0, "the line did not move up for the tab row: " + y0 + " -> " + y1);
            // OFF draws nothing: one fewer text group than with it on (control).
            run(c, () -> Mod.set(CFG, "setEnabled", false));
            int without = texts(c);
            c.check(without < withLine2, "switching it off did not remove its text: " + withLine2 + " -> " + without);
            c.note("drawn above the input (y " + y0 + ", height " + h + "), above the tab row with Chat Tabs on (y " + y1
                    + "), texts " + withLine + " on / " + without + " off");
        } finally {
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
            run(c, () -> Mod.staticCall(F, "resetForTest"));
        }
    }

    private static void settings(Session c) {
        try (Settings set = new Settings(c).with(CFG, "Enabled", false)) {
            run(c, () -> {
                Mod.set(CFG, "setEnabled", true);
                Mod.set(CFG, "setShowMemberCount", false);
                Mod.set(CFG, "setHypixelColors", false);
                Mod.call(Mod.cfg(CFG), "save");
                Mod.staticCall(CFG, "load");
            });
            Object cfg = c.onClient(mc -> Mod.cfg(CFG));
            c.check((Boolean) Mod.call(cfg, "isEnabledRaw"), "Enabled did not survive a reload");
            c.check(!(Boolean) Mod.call(cfg, "isShowMemberCount"), "Show Member Count did not survive a reload");
            c.check(!(Boolean) Mod.call(cfg, "isHypixelColors"), "Use Hypixel Colors did not survive a reload");
            c.note("enabled / show member count off / hypixel colors off saved and reloaded");
        } finally {
            run(c, () -> {
                Mod.set(CFG, "setEnabled", false);
                Mod.set(CFG, "setShowMemberCount", true);
                Mod.set(CFG, "setHypixelColors", true);
                Mod.call(Mod.cfg(CFG), "save");
            });
        }
    }

    private static boolean hudSeen(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "chat_channel"));
    }

    /** Show: While Chat Is Open draws nothing in game, Always is the movable HUD element (once, also with chat open). */
    private static void hudMode(Session c) {
        String hudCfg = "hud.HudConfig";
        try (Settings set = new Settings(c).with(CFG, "Enabled", true).with(TABS_CFG, "Enabled", false)) {
            dungeon(c);
            run(c, () -> {
                Mod.staticCall(F, "resetForTest");
                Mod.set(CFG, "setAlwaysShow", false);
            });
            line(c, "§aYou are now in the §r§6OFFICER§r§a channel");
            waitShown(c, "Officer", "Chat: Officer");
            // While Chat Is Open (default): no in-game HUD, the line draws with chat open.
            run(c, () -> Mod.staticCall("hud.HudSeen", "forget", "chat_channel"));
            c.ctx().waitTicks(10);
            c.check(!hudSeen(c), "While Chat Is Open drew the HUD element in game");
            long f0 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            texts(c);
            long f1 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            c.check(f1 > f0, "While Chat Is Open did not draw the line with chat open");
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
            // Always: the HUD element draws in game with the channel, and chat open adds no second line.
            run(c, () -> Mod.set(CFG, "setAlwaysShow", true));
            c.waitUntil("Always draws the HUD element in game", mc -> (Boolean) Mod.staticCall("hud.HudSeen", "drawnRecently", "chat_channel"), 40);
            line(c, "§aYou are now in the §r§6GUILD§r§a channel");
            waitShown(c, "Guild", "Chat: Guild");
            long g0 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            c.ctx().waitTicks(4);
            c.check(c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn")) > g0, "the HUD element did not keep drawing");
            // With chat open the screen mixin must add no line: only the HUD element (not run here) may draw.
            long h0 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            texts(c);
            long h1 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            run(c, () -> McCompat.setScreen(Minecraft.getInstance(), null));
            c.check(h1 == h0, "Always also drew the line above the chat input (" + (h1 - h0) + " draw(s))");
            // Moved position persists through a save and reload.
            run(c, () -> {
                Object hud = Mod.staticCall(hudCfg, "getInstance");
                Mod.call(hud, "setPosition", "chat_channel", 123, 77);
                Mod.call(hud, "save");
                Mod.staticCall(hudCfg, "load");
            });
            int[] pos = c.onClient(mc -> (int[]) Mod.call(Mod.staticCall(hudCfg, "getInstance"), "savedPosition", "chat_channel"));
            c.check(pos != null && pos[0] == 123 && pos[1] == 77, "moved HUD position lost: " + java.util.Arrays.toString(pos));
            // The HUD editor lists it.
            c.onClient(mc -> {
                try {
                    net.minecraft.client.gui.screens.Screen e = (net.minecraft.client.gui.screens.Screen)
                            Mod.cls("hud.HudEditorScreen").getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null);
                    McCompat.setScreen(mc, e);
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
                return null;
            });
            c.ctx().waitTicks(3);
            java.util.List<String> ids = c.onClient(mc -> {
                java.util.List<String> out = new java.util.ArrayList<>();
                Object sc = McCompat.screen(mc);
                for (Object el : (java.util.List<Object>) Mod.field(sc, "shown")) {
                    out.add((String) Mod.call(el, "id"));
                }
                McCompat.setScreen(mc, null);
                return out;
            });
            c.check(ids.contains("chat_channel"), "the HUD editor does not list chat_channel: " + ids);
            // Feature OFF: nothing in game or with chat open.
            run(c, () -> {
                Mod.set(CFG, "setEnabled", false);
                Mod.staticCall("hud.HudSeen", "forget", "chat_channel");
            });
            c.ctx().waitTicks(10);
            c.check(!hudSeen(c), "OFF still drew the HUD element");
            long o0 = c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn"));
            texts(c);
            c.check(c.onClient(mc -> (Long) Mod.staticCall(F, "framesDrawn")) == o0, "OFF still drew a line with chat open");
            c.note("While Chat Is Open: HUD silent in game, line with chat open; Always: HUD drawn in game, position 123,77 saved, listed in the editor; OFF: nothing");
        } finally {
            run(c, () -> {
                McCompat.setScreen(Minecraft.getInstance(), null);
                Mod.set(CFG, "setAlwaysShow", false);
                Object hud = Mod.staticCall(hudCfg, "getInstance");
                Mod.call(hud, "setPosition", "chat_channel", 10, 380);
                Mod.call(hud, "save");
                Mod.staticCall(F, "resetForTest");
            });
        }
    }
}
