package dev.testkit.gametest.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.testkit.gametest.mod.Mod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 402-logic-autokick-units: Auto Kick's "populate from a player" reads a profile's {@code fastest_time_s}, which
 * Hypixel and SkyBlockPV give in MILLISECONDS. It was read as seconds, so every populated floor clamped to the
 * 3600 s maximum (found 2026-10-07 by the Party Finder stats work). Parses a real saved profile (AntsRNG, the
 * 400-menu-partyfinder-stats fixture) through the mod's own parser and checks the converted target.
 */
final class AutoKickCases {

    private static final String FIXTURE = "/testkit-http/partyfinder-stats/antsrng.json";
    private static final UUID ANTS = UUID.fromString("014fd282-53f5-474f-9d79-110e408e5a7c");

    private AutoKickCases() {
    }

    @SuppressWarnings("unchecked")
    static void units(LogicCase c) throws Exception {
        JsonObject root;
        try (InputStream in = AutoKickCases.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                c.check("the AntsRNG fixture is on the classpath", false, FIXTURE);
                return;
            }
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<Object> profiles = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll", root, ANTS);
        Object selected = null;
        for (Object p : profiles) {
            if (Boolean.TRUE.equals(Mod.field(p, "selected"))) {
                selected = p;
            }
        }
        if (!c.check("the selected profile parses", selected != null, profiles.size() + " profile(s)")) {
            return;
        }
        Object dungeons = Mod.field(selected, "dungeons");
        Map<Integer, Object> normal = (Map<Integer, Object>) Mod.call(dungeons, "normal");
        Object f7 = normal.get(7);
        long raw = (Long) Mod.call(f7, "fastestS");
        c.eq("F7 fastest_time_s is the raw millisecond value (a 4:23 clear)", 263003L, raw);
        c.eq("converted to Auto Kick's whole seconds, rounded up", 264L,
                Mod.staticCall("autokick.AutoKickApi", "targetSecondsFromMillis", raw));
        c.eq("no clear stays 0 (never kick)", 0L, Mod.staticCall("autokick.AutoKickApi", "targetSecondsFromMillis", 0L));
        c.eq("an exact second is not rounded up", 120L,
                Mod.staticCall("autokick.AutoKickApi", "targetSecondsFromMillis", 120000L));
    }
}
