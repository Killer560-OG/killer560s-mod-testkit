package dev.testkit.server.hx.surface;

import com.google.gson.JsonObject;

import dev.testkit.server.hx.HxBridge;
import dev.testkit.server.hx.HxModule;

/**
 * WP2's Hx module: Hypixel chat/HUD templates (sidebars, tab lists, footers) and command responders.
 *
 * <pre>
 * surface.status                         -&gt; {responders: [...], templates: [...]}
 * surface.respond {root, match?, replies[], times?}  -&gt; {rule, root}     (see HxResponders)
 * surface.respond.clear {root?}          -&gt; {removed}
 * surface.sidebar {template, ...}        -&gt; {title, lines[]}: RENDERS a Hypixel sidebar from a template (HxTemplates);
 *                                        the caller passes it to the core sidebar.set (HxKit.sidebar does both)
 * surface.tab {template, ...}            -&gt; {entries[], header?, footer?}: renders a tab list for the core tab.set
 * surface.map {id=9001, fill=0, rects[[x,y,w,h,color]]}  a 128x128 map-data packet (see HxMap); give a
 *                                        filled_map[map_id=id] in hotbar slot 8 to make it the dungeon map
 * </pre>
 *
 * Templates only render: the core sidebar.set/tab.set handlers are private to HxPrimitives (frozen), so the client
 * applies the result (request in docs/requests/hx.md to make them callable server-side). Every template line cites the mod source it was shaped from, in {@link HxTemplates}.
 */
public final class HxSurfaceModule implements HxModule {

    @Override
    public String name() {
        return "surface";
    }

    @Override
    public void register() {
        HxBridge.register("surface.status", (server, args) -> {
            JsonObject out = new JsonObject();
            out.add("responders", HxResponders.describe());
            out.add("templates", HxTemplates.names());
            return out;
        });
        HxBridge.register("surface.respond", HxResponders::add);
        HxBridge.register("surface.respond.clear", (server, args) -> HxResponders.clear(args));
        HxBridge.register("surface.sidebar", HxTemplates::sidebar);
        HxBridge.register("surface.tab", HxTemplates::tab);
        HxBridge.register("surface.map", HxMap::send);
    }
}
