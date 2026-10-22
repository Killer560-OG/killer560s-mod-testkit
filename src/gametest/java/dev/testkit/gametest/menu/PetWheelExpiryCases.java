package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * 2777-menu-petwheel-expiry-screen (mod sweep-fix-1021, item 4): a right-click pick in the Pet Wheel's edit mode sends
 * /pets and waits for the menu; when it never opens, a 100-tick expiry gives up. That expiry used to reopen the edit
 * wheel whenever the screen was not a container, so it landed on top of chat or any other screen. The Hx server here
 * answers /pets with nothing, so the expiry is what runs.
 * <ul>
 *   <li>Control: no screen up when it expires - the edit wheel comes back.</li>
 *   <li>Chat open when it expires - chat stays, nothing opens over it, and the pick is dropped.</li>
 * </ul>
 */
final class PetWheelExpiryCases {

    private PetWheelExpiryCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "2777-menu-petwheel-expiry-screen", PetWheelExpiryCases::expiry);
    }

    private static final String EDITOR = "petwheel.PetWheelEditor";

    private static String screenName(Minecraft mc) {
        Screen s = McCompat.screen(mc);
        return s == null ? "none" : s.getClass().getSimpleName();
    }

    private static boolean armed(Session c) {
        return c.onClient(mc -> (Boolean) Mod.field(EDITOR, "armed"));
    }

    /** Opens the edit wheel and right-click-picks slot 0, as PetWheelScreen does; waits for the pick's /pets. */
    private static void pick(Session c) {
        int before = (int) c.commands().stream().filter("pets"::equals).count();
        c.ctx().runOnClient(mc -> {
            Object edit = Mod.staticCall("petwheel.PetWheelScreen", "forEdit", (Object) null);
            McCompat.setScreen(mc, (Screen) edit);
            Mod.staticCall(EDITOR, "beginPick", 0, edit);
        });
        c.waitUntil("the pick to send /pets", mc -> c.commands().stream().filter("pets"::equals).count() > before, 60);
        c.check(armed(c), "the pick is armed after /pets");
    }

    static void expiry(Session c) throws Exception {
        MenuKit.reset(c);
        c.hx().call("menu.onCommand", "name", "pets", "menu", null);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set("petwheel.PetWheelConfig", "Enabled", true);

            // Control: nothing on screen when the pick expires - the edit wheel comes back.
            pick(c);
            c.check(c.onClient(PetWheelExpiryCases::screenName).equals("none"),
                    "the edit wheel closed for the pick: " + c.onClient(PetWheelExpiryCases::screenName));
            c.waitUntil("the expiry to bring the edit wheel back", mc -> screenName(mc).equals("PetWheelScreen"), 200);
            c.check(!armed(c), "the pick is over once the wheel is back");
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.ctx().waitTicks(3);

            // Chat is open when it expires: chat stays, the wheel never opens over it, the pick is dropped.
            pick(c);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, new ChatScreen("", false)));
            c.waitUntil("the pick to expire", mc -> !(Boolean) Mod.field(EDITOR, "armed"), 200);
            c.ctx().waitTicks(5);
            String after = c.onClient(PetWheelExpiryCases::screenName);
            c.check(after.equals("ChatScreen"), "a screen opened over chat at the expiry: " + after);
            c.note("no screen: the edit wheel came back at the expiry; chat open: it stayed (" + after
                    + ") and the pick was dropped");
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            MenuKit.reset(c);
        }
    }
}
