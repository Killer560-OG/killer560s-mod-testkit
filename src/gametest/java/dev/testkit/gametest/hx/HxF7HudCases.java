package dev.testkit.gametest.hx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mod branch f7hud-1102 (killer560, 2026-11-02, two screenshots from a real F7), every boss event at an exact SERVER tick
 * through the testmod's {@code boss.timeline}:
 * <ul>
 *   <li>1102-hx-goldor-tick: Goldor's "Tick:" reads 0 (within one tick) on every hit when the hits come on Goldor's
 *       60-tick cycle from his line; with the hits 30 ticks off that phase (his report: "it procced my mask at like 1.5s
 *       instead of at 0") it re-locks on the second hit and reads 0 on every hit after. The hits are real
 *       {@code damage} commands; the mod sees them only as its health dropping.</li>
 *   <li>1102-hx-storm-line: Storm's line is ONE line through the fight - "Lightning:" from his opening line, "Purple:" once
 *       the lightning line arrives, "Yellow:" after the first crush, gone on his death - and no Pad / Storm / PY / Crush
 *       line is ever drawn; the purple timer reads 0 every 20 server ticks.</li>
 * </ul>
 */
final class HxF7HudCases {

    private static final String TT = "ticktimers.TickTimersFeature";
    private static final String TTC = "ticktimers.TickTimersConfig";
    private static final String GT = "ticktimers.GoldorTick";
    private static final String SL = "ticktimers.StormLine";
    private static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";

    private HxF7HudCases() {
    }

    static void register(Session s) {
        goldorTick(s);
        stormLine(s);
    }

    private static JsonObject step(int at, String chat, float damage) {
        JsonObject o = new JsonObject();
        o.addProperty("at", at);
        if (chat != null) {
            o.addProperty("chat", chat);
        }
        if (damage > 0f) {
            o.addProperty("damage", damage);
        }
        return o;
    }

    /** Runs the steps and waits for the last one; checks every step ran on its own server tick. */
    private static void timeline(Session c, String self, List<JsonObject> steps, int lastAt) {
        JsonObject args = new JsonObject();
        args.addProperty("player", self);
        JsonArray arr = new JsonArray();
        steps.forEach(arr::add);
        args.add("steps", arr);
        c.hx().call("boss.timeline", args);
        long deadline = System.currentTimeMillis() + (lastAt / 20L + 15L) * 1000L;
        JsonObject st;
        do {
            c.ctx().waitTicks(10);
            st = c.hx().call("boss.timeline.state").getAsJsonObject();
        } while (st.get("pending").getAsInt() > 0 && System.currentTimeMillis() < deadline);
        c.check(st.get("pending").getAsInt() == 0, "timeline did not finish: " + st);
        for (JsonElement e : st.getAsJsonArray("fired")) {
            JsonObject f = e.getAsJsonObject();
            c.check(f.get("at").getAsInt() == f.get("tick").getAsInt(), "a step ran late on the server: " + f);
        }
        c.ctx().waitTicks(10); // the last hit's health packet
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> offsets(Session c) {
        return c.onClient(mc -> (List<Integer>) Mod.staticCall(GT, "testOffsetsAtHits"));
    }

    // ---- 1102-hx-goldor-tick --------------------------------------------------------------------------------------

    private static void goldorTick(Session s) {
        s.test("1102-hx-goldor-tick", c -> {
            dungeon(c);
            String self = self(c);
            try (Settings st = new Settings(c)) {
                st.with(TTC, "Enabled", true).with(TTC, "GoldorTimer", true).with(TTC, "GoldorShowTotal", false);
                c.server().command("gamemode survival " + self);
                c.server().command("effect give " + self + " minecraft:instant_health 1 4 true");
                c.ctx().waitTicks(20);

                // (a) Hits on Goldor's cycle: line at 5, hits 60, 120, 180, 240 ticks after it.
                run(c, () -> Mod.staticCall(TT, "resetAll"));
                List<JsonObject> steps = new ArrayList<>();
                steps.add(step(5, GOLDOR, 0f));
                for (int k = 1; k <= 4; k++) {
                    steps.add(step(5 + 60 * k, null, 1f));
                }
                timeline(c, self, steps, 245);
                List<Integer> a = offsets(c);
                c.note("(a) in phase, offsets at the hits: " + a + ", re-locks " + c.onClient(mc -> Mod.staticCall(GT, "testRelocks")));
                c.check(a.size() == 4, "(a) 4 hits seen from the health drops, saw " + a.size() + ": " + a);
                c.check(a.stream().allMatch(o -> Math.abs(o) <= 1), "(a) the Tick read 0 (+-1 tick) at every hit: " + a);

                c.hx().chat("The Core entrance is opening!");
                c.server().command("effect give " + self + " minecraft:instant_health 1 4 true");
                c.ctx().waitTicks(20);

                // (b) Hits 30 ticks off the line's phase: line at 5, hits 35, 95, 155, 215.
                run(c, () -> Mod.staticCall(TT, "resetAll"));
                steps = new ArrayList<>();
                steps.add(step(5, GOLDOR, 0f));
                for (int k = 0; k < 4; k++) {
                    steps.add(step(35 + 60 * k, null, 1f));
                }
                timeline(c, self, steps, 215);
                List<Integer> b = offsets(c);
                int relocks = c.onClient(mc -> (Integer) Mod.staticCall(GT, "testRelocks"));
                c.note("(b) 30 ticks off, offsets at the hits: " + b + ", re-locks " + relocks);
                c.check(b.size() == 4, "(b) 4 hits seen, saw " + b.size() + ": " + b);
                if (b.size() == 4) {
                    c.check(Math.abs(b.get(0)) >= 28 && Math.abs(b.get(1)) >= 28, "(b) the first two hits were ~30 off: " + b);
                    c.check(Math.abs(b.get(2)) <= 1 && Math.abs(b.get(3)) <= 1, "(b) after the re-lock the Tick read 0 at each hit: " + b);
                }
                c.check(relocks == 1, "(b) one re-lock, got " + relocks);
                String line = c.onClient(mc -> String.valueOf(Mod.staticCall(GT, "remaining")));
                c.note("(b) remaining after the run: " + line);
            } finally {
                c.hx().chat("The Core entrance is opening!");
                c.server().command("effect give " + self(c) + " minecraft:instant_health 1 4 true");
                run(c, () -> Mod.staticCall(TT, "resetAll"));
            }
        });
    }

    // ---- 1102-hx-storm-line ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> stormLines(Session c) {
        return c.onClient(mc -> {
            Object hud = Mod.staticCall("hud.HudElementRegistry", "byId", "tick_timers");
            List<String> out = new ArrayList<>();
            if (hud == null) {
                out.add("<no tick_timers element>");
                return out;
            }
            for (String l : (List<String>) Mod.call(hud, "activeLines")) {
                String p = l.replaceAll("§.", "");
                if (p.startsWith("Lightning:") || p.startsWith("Purple:") || p.startsWith("Yellow:") || p.startsWith("Pad:")
                        || p.startsWith("Storm:") || p.startsWith("PY:") || p.startsWith("Crush")) {
                    out.add(p);
                }
            }
            return out;
        });
    }

    private static void stormLine(Session s) {
        s.test("1102-hx-storm-line", c -> {
            dungeon(c);
            String self = self(c);
            try (Settings st = new Settings(c)) {
                st.with(TTC, "Enabled", true).with(TTC, "StormLine", true).with(TTC, "DisplayInTicks", true);
                run(c, () -> Mod.staticCall(TT, "resetAll"));
                JsonObject args = new JsonObject();
                args.addProperty("player", self);
                JsonArray arr = new JsonArray();
                arr.add(step(5, "[BOSS] Storm: Pathetic Maxor, just like expected.", 0f));
                args.add("steps", arr);
                c.hx().call("boss.timeline", args);
                c.waitUntil("Lightning line", mc -> "LIGHTNING".equals(String.valueOf(Mod.staticCall(SL, "phase"))), 60);
                List<String> l1 = stormLines(c);
                c.note("after Storm's line: " + l1);
                c.check(l1.size() == 1 && l1.get(0).startsWith("Lightning: 5"), "one Lightning line near 560t: " + l1);

                c.hx().chat("[BOSS] Storm: ENERGY HEED MY CALL!");
                c.waitUntil("Purple line", mc -> "PURPLE".equals(String.valueOf(Mod.staticCall(SL, "phase"))), 60);
                List<String> l2 = stormLines(c);
                c.check(l2.size() == 1 && l2.get(0).startsWith("Purple: "), "one Purple line: " + l2);
                // The pad timer: 0 on ticks 20 server ticks apart.
                List<Long> zeros = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    long[] r = c.onClient(mc -> new long[]{(Integer) Mod.staticCall(SL, "remaining"),
                            (Long) Mod.staticCall("util.PerTickServerClock", "now")});
                    if (r[0] == 0 && (zeros.isEmpty() || zeros.get(zeros.size() - 1) != r[1])) {
                        zeros.add(r[1]);
                    }
                    c.ctx().waitTicks(1);
                }
                c.note("purple pad zeros at clock ticks " + zeros);
                c.check(zeros.size() >= 2, "the purple timer reached 0 at least twice in 50 ticks: " + zeros);
                for (int i = 1; i < zeros.size(); i++) {
                    c.check((zeros.get(i) - zeros.get(i - 1)) % 20 == 0, "pad zeros 20 ticks apart: " + zeros);
                }

                c.hx().chat("[BOSS] Storm: Oof");
                c.waitUntil("Yellow line", mc -> "YELLOW".equals(String.valueOf(Mod.staticCall(SL, "phase"))), 60);
                List<String> l3 = stormLines(c);
                c.check(l3.size() == 1 && l3.get(0).startsWith("Yellow: "), "one Yellow line: " + l3);

                c.hx().chat("[BOSS] Storm: I should have known that I stood no chance.");
                c.waitUntil("Storm line gone", mc -> "NONE".equals(String.valueOf(Mod.staticCall(SL, "phase"))), 60);
                c.check(stormLines(c).isEmpty(), "no Storm line after his death: " + stormLines(c));
                c.note("lines: " + l1 + " -> " + l2 + " -> " + l3 + " -> []");
            } finally {
                run(c, () -> Mod.staticCall(TT, "resetAll"));
            }
        });
    }
}
