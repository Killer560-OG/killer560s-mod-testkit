package dev.testkit.gametest.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WP4's allow and deny lists, in {@code src/gametest/resources/testkit-ui/}:
 * <ul>
 *   <li>{@code deny-buttons.json} - label keywords the UI sweeps never press (OS opens, clipboard, network, the real
 *       window, anything that starts a world or a recorder). Each with its reason and the mod source it cites.</li>
 *   <li>{@code deny-commands.json} - the client commands the smoke EXECUTES (with what each must visibly do), and the
 *       roots it only parses, each with why.</li>
 *   <li>{@code allow-screens.json} - every mod Screen class: how to construct it, or why it is not constructed.</li>
 * </ul>
 * Not under {@code testkit-fixtures/}: {@code Fixtures.load("")} walks every {@code .json} there as a JSON array of
 * Hypixel-input fixtures and would reject these (see docs/wp/ui.md).
 */
final class Deny {

    record Keyword(String match, String why) {
    }

    final List<Keyword> buttons = new ArrayList<>();
    final JsonObject commands;
    final JsonObject screens;

    private Deny(JsonObject buttonsJson, JsonObject commands, JsonObject screens) {
        for (JsonElement e : buttonsJson.getAsJsonArray("keywords")) {
            JsonObject o = e.getAsJsonObject();
            buttons.add(new Keyword(o.get("match").getAsString(), o.get("why").getAsString()));
        }
        this.commands = commands;
        this.screens = screens;
    }

    static Deny load() {
        return new Deny(read("deny-buttons.json"), read("deny-commands.json"), read("allow-screens.json"));
    }

    /** Why a button with this (formatting-stripped) label must not be pressed, or null if it may be. */
    String button(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        for (Keyword k : buttons) {
            if (l.contains(k.match().toLowerCase(Locale.ROOT))) {
                return "deny '" + k.match() + "': " + k.why();
            }
        }
        return null;
    }

    static JsonObject read(String name) {
        String resource = "/testkit-ui/" + name;
        try (InputStream in = Deny.class.getResourceAsStream(resource)) {
            if (in != null) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) {
            throw new AssertionError("[ui] cannot parse " + resource + ": " + e, e);
        }
        String res = System.getProperty("fabric.client.gametest.testModResourcesPath");
        Path file = res != null ? Path.of(res, "testkit-ui", name)
                : Path.of(System.getProperty("testkit.checkout", "."), "src", "gametest", "resources", "testkit-ui", name);
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("[ui] cannot read " + file + ": " + e, e);
        }
    }

    static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key)) {
            JsonArray a = o.getAsJsonArray(key);
            for (JsonElement e : a) {
                out.add(e.getAsString());
            }
        }
        return out;
    }
}
