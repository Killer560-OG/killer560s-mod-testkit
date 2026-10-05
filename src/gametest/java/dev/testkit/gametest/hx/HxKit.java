package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.testkit.gametest.Fixtures;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * WP2's shared helpers for {@code 1NN-hx-*} cases: replaying fixtures through Hx, reading what the mod printed, and
 * waiting for commands. Everything here goes through the frozen WP1 API ({@link Hx}, {@link Session}, {@link Mod}).
 */
public final class HxKit {

    private HxKit() {
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    /** A fixture by id, after checking it against the LOADED mod's patterns (a disagreement fails the case). */
    public static Fixtures.Fixture fx(Session c, String id) {
        Fixtures.Fixture f = Fixtures.byId(id);
        List<String> schema = Fixtures.validate(f);
        c.check(schema.isEmpty(), "fixture " + id + " is invalid: " + schema);
        List<String> agree = Fixtures.checkPatterns(f);
        c.check(agree.isEmpty(), "fixture " + id + " disagrees with the mod: " + agree);
        return f;
    }

    /** Send a fixture the way its kind arrives from Hypixel. */
    public static void send(Session c, Fixtures.Fixture f) {
        Hx hx = c.hx();
        switch (f.kind()) {
            case "chat" -> sendText(hx, f.payload(), false);
            case "overlay" -> sendText(hx, f.payload(), true);
            case "title" -> {
                if (f.payload().isJsonObject()) {
                    hx.call("title", f.payload().getAsJsonObject().deepCopy());
                } else {
                    hx.title(f.payload().getAsString(), "");
                }
            }
            case "sidebar" -> hx.call("sidebar.set", f.payload().getAsJsonObject().deepCopy());
            case "tab" -> hx.call("tab.set", f.payload().getAsJsonObject().deepCopy());
            case "item" -> hx.give(f.json().has("slot") ? f.json().get("slot").getAsInt() : 0,
                    f.payload().getAsJsonObject().get("stack").getAsString());
            default -> throw new AssertionError("[hx] cannot send a fixture of kind " + f.kind());
        }
    }

    /** Fixture id -> send it (after the pattern check). */
    public static Fixtures.Fixture send(Session c, String id) {
        Fixtures.Fixture f = fx(c, id);
        send(c, f);
        return f;
    }

    private static void sendText(Hx hx, JsonElement payload, boolean overlay) {
        if (payload.isJsonPrimitive()) {
            hx.call("chat", "text", payload.getAsString(), "overlay", overlay);
        } else {
            JsonObject p = payload.getAsJsonObject();
            hx.call("chat", "textJson", p.get("json"), "overlay", overlay);
        }
    }

    /** The plain text of a chat/overlay fixture. */
    public static String text(Fixtures.Fixture f) {
        return f.texts().isEmpty() ? "" : f.texts().get(0);
    }

    // ---- what the mod printed --------------------------------------------------------------------------------

    /** Client log lines (mod loggers and every shown chat line, "[System] [CHAT] ...") since {@code mark}. */
    public static List<String> logSince(long mark) {
        return LogTap.since(mark);
    }

    /** Lines since {@code mark} containing every one of {@code parts}. */
    public static List<String> logLines(long mark, String... parts) {
        List<String> out = new ArrayList<>();
        for (String line : LogTap.since(mark)) {
            boolean all = true;
            for (String p : parts) {
                if (!line.contains(p)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(line);
            }
        }
        return out;
    }

    // ---- commands and events ---------------------------------------------------------------------------------

    /** Wait until the client has sent a command starting with {@code prefix} (no slash) this case; returns it. */
    public static String awaitCommand(Session c, String prefix, int ticks) {
        c.waitUntil("a /" + prefix + "... command at the server",
                mc -> c.commands().stream().anyMatch(s -> s.startsWith(prefix)), ticks);
        return c.commands().stream().filter(s -> s.startsWith(prefix)).findFirst().orElseThrow();
    }

    /** Commands this case starting with {@code prefix}. */
    public static List<String> commandsStarting(Session c, String prefix) {
        return c.commands().stream().filter(s -> s.startsWith(prefix)).toList();
    }

    /** Add a responder rule: when the client sends {@code /root ...} matching {@code match}, answer {@code replies}. */
    public static int respond(Session c, String root, String match, Object... replies) {
        JsonArray arr = new JsonArray();
        for (Object r : replies) {
            arr.add(Hx.json(r));
        }
        JsonObject a = Hx.args("root", root, "replies", arr);
        if (match != null) {
            a.addProperty("match", match);
        }
        return c.hx().call("surface.respond", a).getAsJsonObject().get("rule").getAsInt();
    }

    /** A JSON chat component: plain text with a run_command click event (Hypixel's "next page" arrows). */
    public static JsonObject clickable(String text, String command) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        JsonObject click = new JsonObject();
        click.addProperty("action", "run_command");
        click.addProperty("command", command);
        o.add("click_event", click);
        return o;
    }

    // ---- client state ----------------------------------------------------------------------------------------

    /** Read a value on the client thread. */
    public static <T> T read(Session c, Supplier<T> fn) {
        return c.onClient(mc -> fn.get());
    }

    /** Run on the client thread (setters, mod calls that touch game state). */
    public static void run(Session c, Runnable r) {
        c.ctx().runOnClient(mc -> r.run());
    }

    /** The client player's name. */
    public static String self(Session c) {
        return c.onClient(mc -> mc.player.getGameProfile().name());
    }

    /** Write an INSTANCE field (any visibility), e.g. a public config field without a setter. */
    public static void setInstanceField(Object target, String field, Object value) {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // superclass
            } catch (IllegalAccessException e) {
                throw new AssertionError("[hx] cannot write " + field + " on " + target.getClass().getName(), e);
            }
        }
        throw new AssertionError("[hx] no field " + field + " on " + target.getClass().getName());
    }

    /** Settings changed for one case and restored (in reverse order) on close - the client outlives every case. */
    public static final class Settings implements AutoCloseable {
        private final Session c;
        private final java.util.ArrayDeque<Runnable> undo = new java.util.ArrayDeque<>();

        public Settings(Session c) {
            this.c = c;
        }

        /** {@code Mod.with(configClass, property, value)} on the client thread. */
        public Settings with(String configClass, String property, Object value) {
            AutoCloseable restore = c.onClient(mc -> Mod.with(configClass, property, value));
            undo.push(() -> c.ctx().runOnClient(mc -> {
                try {
                    restore.close();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
            return this;
        }

        /** A public/private instance field on a config singleton. */
        public Settings field(String configClass, String field, Object value) {
            Object cfg = c.onClient(mc -> Mod.cfg(configClass));
            Object old = c.onClient(mc -> Mod.field(cfg, field));
            c.ctx().runOnClient(mc -> setInstanceField(cfg, field, value));
            undo.push(() -> c.ctx().runOnClient(mc -> setInstanceField(cfg, field, old)));
            return this;
        }

        /** Any setter pair: {@code set} now, {@code restore} on close (both on the client thread). */
        public Settings custom(Runnable set, Runnable restore) {
            c.ctx().runOnClient(mc -> set.run());
            undo.push(() -> c.ctx().runOnClient(mc -> restore.run()));
            return this;
        }

        @Override
        public void close() {
            while (!undo.isEmpty()) {
                undo.pop().run();
            }
        }
    }

    // ---- Hypixel surfaces ------------------------------------------------------------------------------------

    /** Render a sidebar template (surface.sidebar) and show it (core sidebar.set). Returns what was shown. */
    public static JsonObject sidebar(Session c, Object... kv) {
        JsonObject rendered = c.hx().call("surface.sidebar", Hx.args(kv)).getAsJsonObject();
        c.hx().call("sidebar.set", rendered.deepCopy());
        return rendered;
    }

    /** Render a tab template (surface.tab) and show it (core tab.set). Returns what was shown. */
    public static JsonObject tab(Session c, Object... kv) {
        JsonObject rendered = c.hx().call("surface.tab", Hx.args(kv)).getAsJsonObject();
        c.hx().call("tab.set", rendered.deepCopy());
        return rendered;
    }

    /** One tab player for {@link #tab}'s {@code players}. */
    public static java.util.Map<String, Object> player(String name, String cls, String rank) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("cls", cls);
        m.put("level", "L");
        if (rank != null) {
            m.put("rank", rank);
        }
        return m;
    }

    /**
     * The standard dungeon: F7 sidebar, and a tab list with HxMateA (Mage), HxMateB (Berserk) and the client itself
     * (Archer). Waits for DungeonState.getFloor()=="F7" and PartyTracker to list both mates.
     */
    public static void dungeon(Session c, Object... tabExtra) {
        sidebar(c, "template", "dungeon", "floor", "F7");
        java.util.List<Object> kv = new java.util.ArrayList<>(java.util.List.of("template", "dungeon", "players",
                java.util.List.of(player("HxMateA", "Mage", "[MVP+]"), player("HxMateB", "Berserk", null),
                        player(self(c), "Archer", null))));
        kv.addAll(java.util.List.of(tabExtra));
        tab(c, kv.toArray());
        c.waitUntil("in F7 (DungeonState.getFloor)", mc -> "F7".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
        c.waitUntil("PartyTracker lists HxMateA and HxMateB", mc -> {
            @SuppressWarnings("unchecked")
            List<String> mates = (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
            return mates.contains("HxMateA") && mates.contains("HxMateB");
        }, 100);
    }

    /** Leave the dungeon: the hub sidebar and an Area tab (floor null). */
    public static void hub(Session c) {
        sidebar(c, "template", "hub");
        tab(c, "template", "area", "area", "Hub");
        c.waitUntil("out of the dungeon", mc -> Mod.staticCall("secrets.DungeonState", "getFloor") == null, 100);
    }

    /** Wait {@code ticks}, then fail if the client sent any command starting with one of {@code prefixes}. */
    public static void assertNoCommand(Session c, int ticks, String... prefixes) {
        c.ctx().waitTicks(ticks);
        for (String cmd : c.commands()) {
            for (String p : prefixes) {
                c.check(!cmd.startsWith(p), "the client sent /" + cmd + " (it must not have)");
            }
        }
    }

    /** Type a line into a real ChatScreen and submit it (the path a player's typing takes). */
    public static void type(Session c, String line) {
        c.ctx().runOnClient(mc -> {
            net.minecraft.client.gui.screens.ChatScreen screen = new net.minecraft.client.gui.screens.ChatScreen("", false);
            McCompat.setScreen(mc, screen);
            screen.handleChatInput(line, true);
            McCompat.setScreen(mc, null);
        });
    }

    /** Fail unless {@code mc.level != null} (the client is still connected). */
    public static void assertConnected(Session c, String after) {
        boolean connected = c.onClient(mc -> mc.level != null && mc.getConnection() != null);
        c.check(connected, "the client was DISCONNECTED after " + after);
    }
}
