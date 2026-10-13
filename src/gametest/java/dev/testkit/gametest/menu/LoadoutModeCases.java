package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loadout Keybinds' per-key mode (killer560, 2026-10-13: "for loadouts make a toggle per keybind to have that keybind open
 * the menu and swap then close it or just only swap if you are in the menu"; mod branch loadout-modes). The key is slot
 * 5's (menu slot 24) set to keypad 7, which nothing else binds, held for 3 ticks like a real press. The loadout menu is
 * the Hx fake {@code menus.loadout}; {@code menu.onCommand} makes /loadouts open it, as Hypixel does. Every case runs on
 * the session's GrimAC server and fails on any flag.
 * <ul>
 *   <li>1361: Only In Menu (the default): the key outside the menu sends nothing; inside it, one PICKUP on slot 24 and
 *       the menu stays open.</li>
 *   <li>1362: Open, Swap, Close: exactly one /loadouts, one PICKUP on slot 24 in the menu it opened, then that menu
 *       closed on a later tick than the click; no screen left.</li>
 *   <li>1363: Open, Swap, Close with no menu answering /loadouts: no click, no close, and the timeout line in chat.</li>
 *   <li>1364: Open, Swap, Close when /loadouts opens a DIFFERENT menu: never clicked or closed, timeout line; and the key
 *       pressed while typing in chat sends nothing.</li>
 *   <li>1365: the tab's setting: twelve "Slot N Mode" cycling buttons, Only In Menu by default, a left click steps to
 *       Open, Swap, Close and saves it (read back by a fresh load), a right click steps back, and it has a tooltip.</li>
 * </ul>
 */
final class LoadoutModeCases {

    private static final String CFG = "loadoutkeybinds.LoadoutKeybindsConfig";
    private static final String MODE = CFG + "$Mode";
    private static final int INDEX = 4;          // slot 5 of the twelve
    private static final int MENU_SLOT = 24;     // LoadoutKeybindsFeature.LOADOUT_SLOTS[4]
    private static final int KP_7 = 327;         // GLFW_KEY_KP_7

    private LoadoutModeCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1361-menu-loadout-inmenu", LoadoutModeCases::onlyInMenu);
        MenuSuite.test(s, "1362-menu-loadout-osc", LoadoutModeCases::openSwapClose);
        MenuSuite.test(s, "1363-menu-loadout-osc-timeout", LoadoutModeCases::timeout);
        MenuSuite.test(s, "1364-menu-loadout-osc-wrongmenu-chat", LoadoutModeCases::wrongMenuAndChat);
        MenuSuite.test(s, "1365-menu-loadout-mode-setting", LoadoutModeCases::modeSetting);
    }

    /** Runs {@code body} with the switch on and slot 5 on KP_7 in {@code mode}; puts everything back after. */
    private static void withBind(Session c, String mode, Session.Case body) throws Exception {
        MenuKit.reset(c);
        Object[] was = c.onClient(mc -> {
            Object cfg = Mod.cfg(CFG);
            return new Object[]{Mod.call(cfg, "getSlotKey", INDEX), Mod.call(cfg, "getSlotMode", INDEX)};
        });
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CFG, "Enabled", true);
            c.ctx().runOnClient(mc -> {
                Object o = Mod.cfg(CFG);
                Mod.call(o, "setSlotKey", INDEX, KP_7);
                Mod.call(o, "setSlotMode", INDEX, Mod.enumValue(MODE, mode));
            });
            boolean on = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(CFG), "isEnabled"));
            c.check(on, "Loadout Keybinds reads OFF after switching it on (Skyblock Only gate?)");
            body.run(c);
        } finally {
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", null);
            c.ctx().runOnClient(mc -> {
                Object o = Mod.cfg(CFG);
                Mod.call(o, "setSlotKey", INDEX, was[0]);
                Mod.call(o, "setSlotMode", INDEX, was[1]);
            });
            MenuKit.reset(c);
        }
    }

    private static long loadoutsSent(Session c) {
        return c.commands().stream().filter("loadouts"::equals).count();
    }

    private static void press(Session c) {
        c.ctx().getInput().holdKeyFor(KP_7, 3);
    }

    static void onlyInMenu(Session c) throws Exception {
        withBind(c, "ONLY_IN_MENU", s -> {
            c.ctx().waitTicks(3);
            press(c);
            c.ctx().waitTicks(20);
            c.check(loadoutsSent(c) == 0, "Only In Menu sent /loadouts outside the menu: " + c.commands());
            c.check(c.events("container.click").isEmpty(), "Only In Menu clicked outside the menu: " + c.events("container.click"));
            MenuKit.show(c, MenuKit.menu("menus.loadout"));
            int id = MenuKit.awaitScreen(c, "(1/2) Loadout", 100);
            c.ctx().waitTicks(5);
            press(c);
            c.waitUntil("a click from the key in the menu", mc -> !c.events("container.click").isEmpty(), 60);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = c.events("container.click");
            c.check(clicks.size() == 1, "one press sent " + clicks.size() + " clicks: " + clicks);
            JsonObject click = clicks.get(0);
            c.check(click.get("slot").getAsInt() == MENU_SLOT && click.get("input").getAsString().equals("PICKUP"),
                    "the key sent " + click + ", want PICKUP on " + MENU_SLOT);
            c.check(c.events("container.close").isEmpty(), "Only In Menu closed the menu: " + c.events("container.close"));
            c.check("(1/2) Loadout".equals(c.onClient(MenuKit::screenTitle)), "the loadout menu is no longer open");
            c.check(loadoutsSent(c) == 0, "Only In Menu sent /loadouts: " + c.commands());
            c.note("Only In Menu: outside -> nothing sent; in menu (container " + id + ") -> PICKUP slot " + MENU_SLOT
                    + ", menu left open");
        });
    }

    static void openSwapClose(Session c) throws Exception {
        withBind(c, "OPEN_SWAP_CLOSE", s -> {
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", MenuKit.menu("menus.loadout"));
            c.ctx().waitTicks(3);
            press(c);
            c.waitUntil("the loadout menu closed again", mc -> !c.events("container.close").isEmpty()
                    && McCompat.screen(mc) == null, 200);
            c.ctx().waitTicks(10);
            List<JsonObject> clicks = c.events("container.click");
            List<JsonObject> closes = c.events("container.close");
            c.check(loadoutsSent(c) == 1, "want exactly one /loadouts, server saw " + c.commands());
            c.check(clicks.size() == 1, "want one click, got " + clicks);
            JsonObject click = clicks.get(0);
            c.check(click.get("slot").getAsInt() == MENU_SLOT && click.get("input").getAsString().equals("PICKUP"),
                    "the swap clicked " + click + ", want PICKUP on " + MENU_SLOT);
            c.check(closes.size() == 1, "want one close, got " + closes);
            JsonObject close = closes.get(0);
            c.check(close.get("containerId").getAsInt() == click.get("containerId").getAsInt(),
                    "closed container " + close + " is not the one clicked " + click);
            long clickTick = click.get("tick").getAsLong();
            long closeTick = close.get("tick").getAsLong();
            c.check(closeTick > clickTick, "click (tick " + clickTick + ") and close (tick " + closeTick
                    + ") reached the server on the same tick");
            c.check(c.onClient(mc -> McCompat.screen(mc) == null), "a screen is still open after the swap");
            c.note("Open, Swap, Close: /loadouts x1, PICKUP slot " + MENU_SLOT + " in container "
                    + click.get("containerId") + " at server tick " + clickTick + ", closed at " + closeTick);
        });
    }

    static void timeout(Session c) throws Exception {
        withBind(c, "OPEN_SWAP_CLOSE", s -> {
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", null);
            LogTap.install();
            long mark = LogTap.mark();
            c.ctx().waitTicks(3);
            press(c);
            c.waitUntil("the timeout line in chat", mc -> timedOut(mark), 200);
            c.check(loadoutsSent(c) == 1, "want exactly one /loadouts, server saw " + c.commands());
            c.check(c.events("container.click").isEmpty(), "clicked with no menu: " + c.events("container.click"));
            c.check(c.events("container.close").isEmpty(), "closed with no menu: " + c.events("container.close"));
            c.note("no menu: /loadouts x1, no click, no close, chat said it timed out");
        });
    }

    static void wrongMenuAndChat(Session c) throws Exception {
        withBind(c, "OPEN_SWAP_CLOSE", s -> {
            c.hx().call("menu.onCommand", "name", "loadouts", "menu", MenuKit.menu("menus.pets"));
            LogTap.install();
            long mark = LogTap.mark();
            c.ctx().waitTicks(3);
            press(c);
            MenuKit.awaitScreen(c, "Pets", 100);
            c.waitUntil("the timeout line in chat", mc -> timedOut(mark), 200);
            c.ctx().waitTicks(5);
            c.check(loadoutsSent(c) == 1, "want exactly one /loadouts, server saw " + c.commands());
            c.check(c.events("container.click").isEmpty(), "clicked in a menu that is not the loadout menu: "
                    + c.events("container.click"));
            c.check(c.events("container.close").isEmpty(), "closed a menu that is not the loadout menu: "
                    + c.events("container.close"));
            c.check("Pets".equals(c.onClient(MenuKit::screenTitle)), "the Pets menu was closed");
            MenuKit.reset(c);

            c.hx().call("menu.onCommand", "name", "loadouts", "menu", MenuKit.menu("menus.loadout"));
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new ChatScreen("", false)));
            c.ctx().waitTicks(3);
            press(c);
            c.ctx().waitTicks(5);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.ctx().waitTicks(20);
            c.check(loadoutsSent(c) == 1, "the key typed into chat (or held as chat closed) sent /loadouts: "
                    + c.commands());
            c.check(c.events("container.click").isEmpty(), "a click after typing in chat: " + c.events("container.click"));
            c.note("wrong menu (Pets): never clicked or closed, timed out; the key in chat: nothing sent");
        });
    }

    static void modeSetting(Session c) throws Exception {
        MenuKit.reset(c);
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-loadoutkeybinds.json"));
        byte[] original = Files.exists(file) ? Files.readAllBytes(file) : null;
        boolean fresh = original == null || !new String(original, StandardCharsets.UTF_8).contains("slotModes");
        try {
            String report = c.onClient(mc -> {
                Object cfg = Mod.cfg(CFG);
                Mod.call(cfg, "setEnabled", true);
                Object only = Mod.enumValue(MODE, "ONLY_IN_MENU");
                Object osc = Mod.enumValue(MODE, "OPEN_SWAP_CLOSE");
                if (fresh) {
                    for (int i = 0; i < 12; i++) {
                        check(Mod.call(cfg, "getSlotMode", i) == only, "slot " + (i + 1) + " defaults to "
                                + Mod.call(cfg, "getSlotMode", i) + ", want ONLY_IN_MENU");
                    }
                }
                Mod.call(cfg, "setSlotMode", INDEX, only);
                List<AbstractWidget> modes = modeButtons();
                check(modes.size() == 12, "want 12 'Slot N Mode' buttons, found " + modes.size());
                for (AbstractWidget w : modes) {
                    check((Boolean) Mod.call(w, "isCycling"), "'" + w.getMessage().getString() + "' is not a cycling button");
                }
                AbstractWidget five = modeButtons().get(INDEX);
                String before = five.getMessage().getString();
                check(before.equals("Slot 5 Mode: Only In Menu"), "slot 5's button reads '" + before + "'");
                Mod.call(five, "press");
                String after = five.getMessage().getString();
                check(after.equals("Slot 5 Mode: Open, Swap, Close"), "a left click made it '" + after + "'");
                String saved;
                try {
                    saved = Files.readString(file, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("config file unreadable: " + e, e);
                }
                check(saved.contains("OPEN_SWAP_CLOSE"), "the click was not saved: " + saved);
                Mod.staticCall(CFG, "load");
                check(Mod.call(Mod.cfg(CFG), "getSlotMode", INDEX) == osc, "a fresh load reads slot 5 as "
                        + Mod.call(Mod.cfg(CFG), "getSlotMode", INDEX));
                AbstractWidget again = modeButtons().get(INDEX);
                try {
                    java.lang.reflect.Field f = again.getClass().getDeclaredField("onSecondaryPress");
                    f.setAccessible(true);
                    Object secondary = f.get(again);
                    java.lang.reflect.Method onPress = null;
                    for (java.lang.reflect.Method m : f.getType().getMethods()) {
                        if (m.getName().equals("onPress") && m.getParameterCount() == 1) {
                            onPress = m;
                        }
                    }
                    check(onPress != null, f.getType() + " has no onPress");
                    onPress.invoke(secondary, again);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("right click: " + e, e);
                }
                check(Mod.call(Mod.cfg(CFG), "getSlotMode", INDEX) == only, "a right click left slot 5 on "
                        + Mod.call(Mod.cfg(CFG), "getSlotMode", INDEX));
                String tip = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Loadout Keybinds", after);
                check(tip != null && tip.contains("/loadouts"), "no tooltip for '" + after + "': " + tip);
                return before + " -> left click -> " + after + " (saved, reloaded) -> right click -> back; tooltip: " + tip;
            });
            c.note(report);
        } finally {
            if (original != null) {
                Files.write(file, original);
            } else {
                Files.deleteIfExists(file);
            }
            c.ctx().runOnClient(mc -> Mod.staticCall(CFG, "load"));
        }
    }

    /** The tab's "Slot N Mode" buttons, slot order, built the way the menu builds them. */
    private static List<AbstractWidget> modeButtons() {
        Object tab;
        try {
            tab = Mod.cls("gui.tab.LoadoutKeybindsTab").getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("LoadoutKeybindsTab: " + e, e);
        }
        Runnable rebuild = () -> {
        };
        @SuppressWarnings("unchecked")
        List<AbstractWidget> all = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, rebuild);
        List<AbstractWidget> out = new ArrayList<>();
        for (AbstractWidget w : all) {
            if (w.getMessage().getString().matches("Slot \\d+ Mode: .*")) {
                out.add(w);
            }
        }
        return out;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static boolean timedOut(long mark) {
        return LogTap.since(mark).stream().anyMatch(l -> l.contains("The Loadout menu didn't open"));
    }
}
