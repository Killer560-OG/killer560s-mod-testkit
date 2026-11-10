package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.server.ServerLinks;
import com.mojang.datafixers.util.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The built-in Mods screen (mod modsbtn-1104; killer560, 2026-11-04: "add another button for mods that opens a browser to see
 * all of their mods. essentially mod menu built into our mod" and "it should also be in your normal esc button press ingame").
 * <ul>
 *   <li>1104-ui-mods-title (no world): a real press of the Themed Main Menu's Mods button opens modlist.ModsScreen; it lists
 *       this mod and Fabric API with FabricLoader's versions, Fabric API's modules folded under it; this mod's icon is read
 *       and uploaded; the search filters (a name, then nothing); Configure on this mod opens its menu (ModScreen) and closing
 *       that comes back; Website on Fabric API asks to open its homepage (the no-external-open hook line); Escape returns
 *       to the title screen and the icons are released; the Main Menu Mods Button switch OFF removes the button.</li>
 *   <li>1104-ui-mods-pause (world): the pause menu has exactly one Mods button with Hide Pause Menu Icons ON and OFF, its
 *       press opens the Mods screen over the pause menu and Back returns to it; the Pause Menu Mods Button switch OFF
 *       removes it.</li>
 * </ul>
 */
final class ModsScreenCases {

    static final String TITLE = "1104-ui-mods-title";
    static final String PAUSE = "1104-ui-mods-pause";
    static final String LINKS = "1105-ui-pause-mods-links";
    private static final String CFG = "modlist.ModListConfig";
    private static final String MM = "mainmenu.MainMenuThemeConfig";

    private ModsScreenCases() {
    }

    private static List<AbstractWidget> named(Screen s, String label) {
        List<AbstractWidget> out = new ArrayList<>();
        if (s != null) {
            for (AbstractWidget w : Screens.getWidgets(s)) {
                if (w.visible && w.getMessage() != null && w.getMessage().getString().equals(label)) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    private static boolean isMods(Screen s) {
        return s != null && s.getClass().getName().endsWith(".modlist.ModsScreen");
    }

    /** The ids of the rows the screen lists right now. */
    @SuppressWarnings("unchecked")
    private static List<Object> rowEntries(Screen s) {
        List<Object> out = new ArrayList<>();
        for (Object r : (List<Object>) Mod.field(s, "rows")) {
            out.add(Mod.call(r, "entry"));
        }
        return out;
    }

    private static List<String> ids(Screen s) {
        List<String> out = new ArrayList<>();
        for (Object e : rowEntries(s)) {
            out.add((String) Mod.field(e, "id"));
        }
        return out;
    }

    private static Object entry(Screen s, String id) {
        for (Object e : rowEntries(s)) {
            if (id.equals(Mod.field(e, "id"))) {
                return e;
            }
        }
        return null;
    }

    private static void search(Screen s, String text) {
        ((EditBox) Mod.field(s, "search")).setValue(text);
    }

    private static String version(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }

    static void title(UiCase c) {
        boolean mm0 = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(MM), "isEnabled"));
        boolean defaults = c.onClient(mc -> {
            Mod.staticCall(CFG, "load");
            Object cfg = Mod.cfg(CFG);
            return (Boolean) Mod.call(cfg, "isShowLibraries");
        });
        c.check(defaults, "Libraries should default ON (the Mods buttons have no switch since 2026-11-09)");
        try {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MM), "setEnabled", true);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
            c.ticks(20);
            String opened = c.onClient(mc -> {
                if (mc.level != null) {
                    return "a world is loaded - this case needs the title screen";
                }
                List<AbstractWidget> mods = named(McCompat.screen(mc), "Mods");
                if (mods.size() != 1) {
                    return "the title screen has " + mods.size() + " Mods buttons, want 1";
                }
                List<AbstractWidget> settings = named(McCompat.screen(mc), "Mod Settings");
                if (settings.size() == 1 && mods.get(0).getY() <= settings.get(0).getY()) {
                    return "Mods is not under Mod Settings";
                }
                ModScreenDriver.press(mods.get(0));
                return isMods(McCompat.screen(mc)) ? null : "pressing Mods opened " + McCompat.screen(mc);
            });
            c.check(opened == null, opened);
            if (opened != null) {
                return;
            }
            for (int i = 0; i < 10; i++) {
                c.onClient(mc -> {
                    Frames.extract(mc, McCompat.screen(mc), -1, -1);
                    return null;
                });
                c.ticks(2);
            }
            String listed = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                List<String> ids = ids(s);
                c.note("rows: " + ids.size() + " of " + FabricLoader.getInstance().getAllMods().size() + " mods: " + ids);
                for (String id : new String[]{"killer560smod", "fabric-api"}) {
                    Object e = entry(s, id);
                    if (e == null) {
                        return id + " is not listed";
                    }
                    String v = (String) Mod.field(e, "version");
                    if (v == null || v.isEmpty() || !v.equals(version(id))) {
                        return id + " shows version " + v + ", FabricLoader says " + version(id);
                    }
                    c.note(id + " " + v);
                }
                List<?> kids = (List<?>) Mod.field(entry(s, "fabric-api"), "children");
                c.note("Fabric API carries " + kids.size() + " modules");
                if (kids.isEmpty()) {
                    return "Fabric API has no modules folded under it";
                }
                if (ids.contains("fabric-api-base")) {
                    return "a Fabric API module is listed at the top level while its parent is closed";
                }
                Map<?, ?> icons = (Map<?, ?>) Mod.field(s, "icons");
                Object own = icons.get("killer560smod");
                if (own == null || Mod.call(own, "id") == null) {
                    return "this mod's icon was never loaded: " + own;
                }
                return null;
            });
            c.check(listed == null, listed);

            String filtered = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                search(s, "killer560");
                List<String> ids = ids(s);
                if (!ids.contains("killer560smod") || ids.contains("fabric-api")) {
                    return "search \"killer560\" listed " + ids;
                }
                search(s, "fabric-api-base");
                ids = ids(s);
                if (!ids.contains("fabric-api") || !ids.contains("fabric-api-base")) {
                    return "search \"fabric-api-base\" should show Fabric API with that module under it, got " + ids;
                }
                search(s, "zzqx no such mod");
                if (!ids(s).isEmpty()) {
                    return "a search matching nothing listed " + ids(s);
                }
                search(s, "");
                return ids(s).contains("fabric-api") ? null : "clearing the search did not bring the list back";
            });
            c.check(filtered == null, filtered);

            String configure = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                Mod.call(s, "select", entry(s, "killer560smod"));
                AbstractWidget btn = (AbstractWidget) Mod.field(s, "configure");
                if (!btn.visible || !btn.active) {
                    return "Configure is not offered for this mod";
                }
                ModScreenDriver.press(btn);
                Screen now = McCompat.screen(mc);
                if (now == null || !now.getClass().getName().endsWith(".gui.ModScreen")) {
                    return "Configure opened " + now;
                }
                now.onClose();
                return isMods(McCompat.screen(mc)) ? null : "closing the mod menu went to " + McCompat.screen(mc);
            });
            c.check(configure == null, configure);
            c.ticks(10);

            long mark = LogTap.mark();
            String home = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                Object api = entry(s, "fabric-api");
                String url = (String) Mod.field(api, "homepage");
                Mod.call(s, "select", api);
                AbstractWidget w = (AbstractWidget) Mod.field(s, "website");
                if (url == null) {
                    return w.visible ? "Website shown for a mod with no homepage" : "";
                }
                if (!w.visible) {
                    return "no Website button for Fabric API (" + url + ")";
                }
                ModScreenDriver.press(w);
                return url;
            });
            c.ticks(2);
            if (home.isEmpty() || !home.startsWith("http")) {
                c.check(home.isEmpty(), home);
                c.note("Fabric API gives no homepage: the link press did not run");
            } else {
                boolean asked = LogTap.since(mark).stream().anyMatch(l -> l.contains("would open") && l.contains(home));
                c.check(asked, "Website did not ask to open " + home);
            }

            String closed = c.onClient(mc -> {
                Screen s = McCompat.screen(mc);
                s.onClose();
                Map<?, ?> icons = (Map<?, ?>) Mod.field(s, "icons");
                if (!(McCompat.screen(mc) instanceof TitleScreen)) {
                    return "Escape went to " + McCompat.screen(mc);
                }
                return icons.isEmpty() ? null : "the icons were not released on close: " + icons.keySet();
            });
            c.check(closed == null, closed);
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MM), "setEnabled", mm0);
                McCompat.setScreen(mc, new TitleScreen());
                return null;
            });
        }
    }

    static void pause(UiCase c) {
        boolean icons0 = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(MM), "isHidePauseIcons"));
        try {
            for (boolean hide : new boolean[]{true, false}) {
                String why = c.onClient(mc -> {
                    Mod.call(Mod.cfg(MM), "setHidePauseIcons", hide);
                    McCompat.setScreen(mc, new PauseScreen(true));
                    Screen pause = McCompat.screen(mc);
                    List<AbstractWidget> mods = named(pause, "Mods");
                    if (mods.size() != 1) {
                        return "icons hidden " + hide + ": the pause menu has " + mods.size() + " Mods buttons, want 1";
                    }
                    ModScreenDriver.press(mods.get(0));
                    Screen s = McCompat.screen(mc);
                    if (!isMods(s)) {
                        return "the pause menu's Mods opened " + s;
                    }
                    Frames.extract(mc, s, -1, -1);
                    if (entry(s, "killer560smod") == null) {
                        return "the Mods screen from the pause menu does not list this mod";
                    }
                    ModScreenDriver.press(named(s, "Back").get(0));
                    return McCompat.screen(mc) == pause ? null : "Back went to " + McCompat.screen(mc);
                });
                c.check(why == null, why);
                c.note("icons hidden " + hide + ": one Mods button, opens and returns");
            }
        } finally {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(MM), "setHidePauseIcons", icons0);
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }

    /**
     * 1105-ui-pause-mods-links (world; mod pausemods-1105, killer560: "make mods and server links next to each other as small
     * buttons"): with server links sent (ClientboundServerLinksPacket) the pause menu holds Mods and Server Links on ONE row,
     * each 98 wide, both active and visible, Mods opening the Mods screen and Back returning; without links Mods is 204 wide.
     */
    static void pauseLinks(UiCase c) {
        try {
            String why = c.onClient(mc -> {
                mc.player.connection.handleServerLinks(new ClientboundServerLinksPacket(List.of(
                        new ServerLinks.UntrustedEntry(Either.left(ServerLinks.KnownLinkType.SUPPORT), "https://example.com/support"))));
                if (mc.player.connection.serverLinks().isEmpty()) {
                    return "the fake server links did not arrive";
                }
                McCompat.setScreen(mc, new PauseScreen(true));
                Screen pause = McCompat.screen(mc);
                List<AbstractWidget> mods = named(pause, "Mods");
                List<AbstractWidget> links = new ArrayList<>();
                for (AbstractWidget w : Screens.getWidgets(pause)) {
                    if (w.visible && w.getMessage().getString().startsWith("Server Links")) {
                        links.add(w);
                    }
                }
                if (mods.size() != 1 || links.size() != 1) {
                    return "want one Mods and one Server Links, got " + mods.size() + " and " + links.size();
                }
                AbstractWidget m = mods.get(0);
                AbstractWidget l = links.get(0);
                if (m.getWidth() != 98 || l.getWidth() != 98) {
                    return "widths Mods " + m.getWidth() + " Server Links " + l.getWidth() + ", want 98 and 98";
                }
                if (m.getY() != l.getY() || m.getX() + 98 > l.getX() || l.getX() - m.getX() - 98 > 8) {
                    return "not side by side: Mods " + m.getX() + "," + m.getY() + " Server Links " + l.getX() + "," + l.getY();
                }
                if (!m.active || !l.active) {
                    return "a button is not active: Mods " + m.active + " Server Links " + l.active;
                }
                ModScreenDriver.press(m);
                if (!isMods(McCompat.screen(mc))) {
                    return "Mods opened " + McCompat.screen(mc);
                }
                ModScreenDriver.press(named(McCompat.screen(mc), "Back").get(0));
                if (McCompat.screen(mc) != pause) {
                    return "Back went to " + McCompat.screen(mc);
                }
                ModScreenDriver.press(l);
                return McCompat.screen(mc) == pause ? "Server Links did not open anything" : null;
            });
            c.check(why == null, why);
            c.note("links present: Mods and Server Links share a row, 98 wide each, both pressable");
            int w = c.onClient(mc -> {
                mc.player.connection.handleServerLinks(new ClientboundServerLinksPacket(List.of()));
                McCompat.setScreen(mc, new PauseScreen(true));
                List<AbstractWidget> mods = named(McCompat.screen(mc), "Mods");
                return mods.size() == 1 ? mods.get(0).getWidth() : -1;
            });
            c.check(w == 204, "without server links Mods is " + w + " wide, want 204");
            c.note("no links: Mods is 204 wide");
        } finally {
            c.onClient(mc -> {
                mc.player.connection.handleServerLinks(new ClientboundServerLinksPacket(List.of()));
                McCompat.setScreen(mc, null);
                return null;
            });
        }
    }
}
