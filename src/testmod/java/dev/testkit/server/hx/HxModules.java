package dev.testkit.server.hx;

import dev.testkit.server.hx.boss.HxBossModule;
import dev.testkit.server.hx.dungeon.HxDungeonModule;
import dev.testkit.server.hx.menu.HxMenuModule;
import dev.testkit.server.hx.surface.HxSurfaceModule;

import java.util.List;

/**
 * Every Hx module, in registration order. Frozen after WP1 - the four WP modules are pre-registered here as stubs
 * so no WP has to touch this file:
 *
 * <ul>
 *   <li>{@link HxPrimitives} (WP1, core): chat, action bar, title, sidebar, tab, sound, particle, give, stands, menus,
 *       kick, command stubs, events.</li>
 *   <li>{@link HxSurfaceModule} (WP2, {@code hx/surface/**}): Hypixel chat/HUD templates and command responders.</li>
 *   <li>{@link HxMenuModule} (WP3, {@code hx/menu/**}): Hypixel menus, terminals, experiments.</li>
 *   <li>{@link HxDungeonModule} (WP6, {@code hx/dungeon/**}): the sim's server rules on a real floor.</li>
 *   <li>{@link HxBossModule} (WP7, {@code hx/boss/**}): boss and P3 arena.</li>
 * </ul>
 */
public final class HxModules {

    private HxModules() {
    }

    public static List<HxModule> all() {
        return List.of(new HxPrimitives(), new HxSurfaceModule(), new HxMenuModule(), new HxDungeonModule(),
                new HxBossModule());
    }

    static void registerAll() {
        for (HxModule module : all()) {
            module.register();
        }
    }
}
