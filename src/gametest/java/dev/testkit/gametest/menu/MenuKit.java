package dev.testkit.gametest.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared helpers for the WP3 menu cases. Every method is called from the TEST thread. */
final class MenuKit {

    private MenuKit() {
    }

    /** Config switches set for one case and restored (on the client thread) when it ends. */
    static final class Cfg implements AutoCloseable {
        private final Session c;
        private final List<AutoCloseable> undo = new ArrayList<>();

        Cfg(Session c) {
            this.c = c;
        }

        Cfg set(String cls, String property, Object value) {
            undo.add(c.onClient(mc -> Mod.with(cls, property, value)));
            return this;
        }

        @Override
        public void close() {
            c.ctx().runOnClient(mc -> {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    try {
                        undo.get(i).close();
                    } catch (Exception e) {
                        System.out.println("[menu] restore failed: " + e);
                    }
                }
            });
        }
    }

    static boolean cheat() {
        return Mod.isCheat();
    }

    /** The open container screen's title, or null. */
    static String screenTitle(Minecraft mc) {
        return mc.screen instanceof AbstractContainerScreen<?> s ? s.getTitle().getString() : null;
    }

    static int containerId(Minecraft mc) {
        return mc.screen instanceof AbstractContainerScreen<?> s ? s.getMenu().containerId : -1;
    }

    /** Wait for a container screen whose plain title equals {@code title}; returns its container id. */
    static int awaitScreen(Session c, String title, int ticks) {
        c.waitUntil("a container screen titled '" + title + "'", mc -> title.equals(screenTitle(mc)), ticks);
        return c.onClient(MenuKit::containerId);
    }

    static void awaitNoScreen(Session c, int ticks) {
        c.waitUntil("no screen open", mc -> mc.screen == null, ticks);
    }

    /** A human click: what a real mouse click on that slot sends. */
    static void click(Session c, int slot, int button, ContainerInput input) {
        c.ctx().runOnClient(mc -> {
            if (mc.screen instanceof AbstractContainerScreen<?> s) {
                mc.gameMode.handleContainerInput(s.getMenu().containerId, slot, button, input, mc.player);
            }
        });
    }

    static void closeClient(Session c) {
        c.ctx().runOnClient(mc -> {
            if (mc.player != null && mc.screen instanceof AbstractContainerScreen<?>) {
                mc.player.closeContainer();
            }
        });
    }

    /** Close whatever is open on both sides, so the next case starts from no screen. */
    static void reset(Session c) {
        closeClient(c);
        c.hx().call("menu.close");
        c.ctx().waitTicks(4);
    }

    /** Events of a type since the case started. */
    static List<JsonObject> ev(Session c, String type) {
        return c.events(type);
    }

    static JsonObject awaitEvent(Session c, String type, int ticks) {
        c.waitUntil("an Hx '" + type + "' event", mc -> !c.events(type).isEmpty(), ticks);
        List<JsonObject> e = c.events(type);
        return e.get(e.size() - 1);
    }

    /** Container clicks recorded by the server since the case started, per server tick. */
    static Map<Long, Integer> clicksPerTick(Session c) {
        Map<Long, Integer> m = new TreeMap<>();
        for (JsonObject e : c.events("container.click")) {
            m.merge(e.get("tick").getAsLong(), 1, Integer::sum);
        }
        return m;
    }

    static int maxPerTick(Map<Long, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** "n clicks over t ticks, max k in one tick, gaps min/median/max". */
    static String cadence(Session c) {
        List<JsonObject> clicks = c.events("container.click");
        if (clicks.isEmpty()) {
            return "0 clicks";
        }
        List<Long> ticks = new ArrayList<>();
        clicks.forEach(e -> ticks.add(e.get("tick").getAsLong()));
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < ticks.size(); i++) {
            gaps.add(ticks.get(i) - ticks.get(i - 1));
        }
        gaps.sort(Long::compare);
        String g = gaps.isEmpty() ? "-" : gaps.get(0) + "/" + gaps.get(gaps.size() / 2) + "/" + gaps.get(gaps.size() - 1);
        return clicks.size() + " clicks over " + (ticks.get(ticks.size() - 1) - ticks.get(0) + 1) + " ticks, max "
                + maxPerTick(clicksPerTick(c)) + "/tick, gap min/median/max " + g + " ticks";
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /** A menu fixture's payload (a menu.show spec), deep-copied so a case may add to it. */
    static JsonObject menu(String id) {
        return Fixtures.byId(id).payload().getAsJsonObject().deepCopy();
    }

    /** An item fixture's stack string. */
    static String item(String id) {
        JsonElement p = Fixtures.byId(id).payload();
        return p.getAsJsonObject().get("stack").getAsString();
    }

    static int show(Session c, JsonObject spec) {
        return c.hx().call("menu.show", spec).getAsJsonObject().get("containerId").getAsInt();
    }

    static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    static JsonArray arr(JsonObject o, String key) {
        return o.getAsJsonArray(key);
    }

    static Hx hx(Session c) {
        return c.hx();
    }
}
