package dev.testkit.gametest.menu;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto E-Table defaults and click pacing (mod branch etable-defaults-1015). killer560, 2026-10-15: a friend's run "was
 * about as slow but for some reason it was clicking insanely fast", and his own settings are to be everyone's defaults.
 * <ul>
 *   <li>288: a fresh config, an old (version 1) file at the old defaults, an old file with zero and garbage values, the
 *   setters' floor, and every value surviving save + load (311-style). Read through the real loader from the real
 *   file.</li>
 *   <li>289: Auto E-Table playing Chronomatron and Superpairs against {@code menu.experiment}, measuring the server's
 *   tick gap between two clicks of one sequence under the defaults, Random Delay larger than Click Delay, an old file
 *   with zero delays, and Superpairs with Adaptive Timeout on. Every run must also have clicked (the game reached its
 *   end), or its gaps prove nothing.</li>
 * </ul>
 */
final class EtableDefaultsCases {

    private static final String CONFIG = ExperimentCases.CONFIG;

    private EtableDefaultsCases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "288-menu-exp-etable-defaults", EtableDefaultsCases::defaults);
        MenuSuite.test(s, "289-menu-exp-etable-click-spacing", EtableDefaultsCases::spacing);
    }

    // ---- the config file -------------------------------------------------------------------------------------------

    private static Path path() {
        return (Path) Mod.field(CONFIG, "CONFIG_PATH");
    }

    /** The file's bytes, or null when there is none - put back by {@link #restore}. */
    private static byte[] backup(Session c) {
        return c.onClient(mc -> {
            try {
                return Files.exists(path()) ? Files.readAllBytes(path()) : null;
            } catch (Exception e) {
                throw new AssertionError("reading " + path() + ": " + e, e);
            }
        });
    }

    private static void restore(Session c, byte[] saved) {
        c.ctx().runOnClient(mc -> {
            try {
                if (saved == null) {
                    Files.deleteIfExists(path());
                } else {
                    Files.write(path(), saved);
                }
            } catch (Exception e) {
                throw new AssertionError("restoring " + path() + ": " + e, e);
            }
            Mod.staticCall(CONFIG, "load");
        });
    }

    /** Writes {@code json} as the config file (null deletes it) and runs the mod's own loader on it. */
    private static void loadFrom(Session c, String json) {
        c.ctx().runOnClient(mc -> {
            try {
                if (json == null) {
                    Files.deleteIfExists(path());
                } else {
                    Files.createDirectories(path().getParent());
                    Files.writeString(path(), json, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                throw new AssertionError("writing " + path() + ": " + e, e);
            }
            Mod.staticCall(CONFIG, "load");
        });
    }

    /** Every setting the Auto Experimentation Table tab shows, read through the getters, as one line. */
    private static String snapshot(Session c) {
        return c.onClient(mc -> {
            Object cfg = Mod.cfg(CONFIG);
            return "superpairs=" + raw(cfg, "superpairsEnabled")
                    + " valuableOnly=" + Mod.call(cfg, "isSuperpairsValuableOnly")
                    + " skipGrand=" + Mod.call(cfg, "isSkipGrandExpBottles")
                    + " skipGuardian=" + Mod.call(cfg, "isSkipGuardianPets")
                    + " adaptive=" + raw(cfg, "superpairsAdaptiveTimeout")
                    + " blockInput=" + Mod.call(cfg, "isBlockInputEnabled")
                    + " guardianSwap=" + Mod.call(cfg, "isAutoSwapGuardianPet")
                    + " stop=" + Mod.call(cfg, "getStopStrategy")
                    + " delay=" + Mod.call(cfg, "getDelayMs")
                    + " first=" + Mod.call(cfg, "getFirstClickDelayMs")
                    + " random=" + Mod.call(cfg, "getRandomDelayMaxMs")
                    + " renew=" + Mod.call(cfg, "getAutoRenewCount")
                    + " titanic=" + Math.round((Double) Mod.call(cfg, "getTitanicMaxPriceCoins"))
                    + " auto=" + raw(cfg, "autonomousMode");
        });
    }

    /** A raw field (getters of these are Skyblock- or variant-gated, which is not what is being measured). */
    private static Object raw(Object cfg, String field) {
        return Mod.field(cfg, field);
    }

    /** killer560's screenshot, 2026-10-15. The feature's own switch keeps its default (off). */
    static final String HIS = "superpairs=true valuableOnly=true skipGrand=true skipGuardian=true adaptive=false"
            + " blockInput=false guardianSwap=true stop=MAX_CLICKS delay=400 first=600 random=50 renew=0 titanic=800000"
            + " auto=false";

    /** The defaults before 2026-10-15, as a version-1 file wrote them. */
    private static String oldDefaultsFile(String extra) {
        return "{\"configVersion\":1,\"enabled\":true,\"superpairsEnabled\":true,\"superpairsValuableOnly\":false,"
                + "\"skipGrandExpBottles\":false,\"skipGuardianPets\":false,\"delayMs\":200,\"autonomousMode\":false,"
                + "\"firstClickDelayMs\":1000,\"stopStrategy\":\"MAX_CLICKS\",\"blockInputEnabled\":false,"
                + "\"autoRenewCount\":0,\"titanicMaxPriceCoins\":0.0,\"randomDelayMaxMs\":0,\"autoSwapGuardianPet\":true,"
                + "\"clickProtectionEnabled\":true,\"notifyMaxClicksReached\":true,\"profitTrackerEnabled\":true,"
                + "\"superpairsAdaptiveTimeout\":false" + extra + "}";
    }

    static void defaults(Session c) throws Exception {
        MenuKit.reset(c);
        byte[] saved = backup(c);
        List<String> problems = new ArrayList<>();
        try {
            // (a) no file at all: a fresh install, or a profile reset (which deletes the file).
            loadFrom(c, null);
            String fresh = snapshot(c);
            c.note("no file: " + fresh);
            if (!fresh.equals(HIS)) {
                problems.add("no file: got " + fresh + ", expected " + HIS);
            }
            // (b) an old file still at the old defaults, with two values its owner changed (Stop At, Auto-Renew).
            loadFrom(c, oldDefaultsFile("").replace("\"stopStrategy\":\"MAX_CLICKS\"", "\"stopStrategy\":\"MAX_XP\"")
                    .replace("\"autoRenewCount\":0", "\"autoRenewCount\":2"));
            String migrated = snapshot(c);
            String expectMigrated = HIS.replace("stop=MAX_CLICKS", "stop=MAX_XP").replace("renew=0", "renew=2");
            c.note("old file at old defaults (Stop At and Auto-Renew changed): " + migrated);
            if (!migrated.equals(expectMigrated)) {
                problems.add("old defaults file: got " + migrated + ", expected " + expectMigrated);
            }
            // (c) an old file whose owner changed Click Delay, Random Delay and the Titanic price: those stay as they
            // are, while everything still at an old default moves.
            loadFrom(c, oldDefaultsFile("").replace("\"delayMs\":200", "\"delayMs\":550")
                    .replace("\"randomDelayMaxMs\":0", "\"randomDelayMaxMs\":100")
                    .replace("\"titanicMaxPriceCoins\":0.0", "\"titanicMaxPriceCoins\":1234567.0"));
            String kept = snapshot(c);
            String expectKept = HIS.replace("delay=400", "delay=550").replace("random=50", "random=100")
                    .replace("titanic=800000", "titanic=1234567");
            c.note("old file with changed delays/price: " + kept);
            if (!kept.equals(expectKept)) {
                problems.add("changed values: got " + kept + ", expected " + expectKept);
            }
            // (d) zero, negative and garbage: the floor, the ceiling, and one bad value must not take the rest of
            // the file with it (Auto-Renew 3 is kept).
            loadFrom(c, oldDefaultsFile("").replace("\"delayMs\":200", "\"delayMs\":0")
                    .replace("\"firstClickDelayMs\":1000", "\"firstClickDelayMs\":-5")
                    .replace("\"randomDelayMaxMs\":0", "\"randomDelayMaxMs\":\"abc\"")
                    .replace("\"titanicMaxPriceCoins\":0.0", "\"titanicMaxPriceCoins\":1.0E12")
                    .replace("\"autoRenewCount\":0", "\"autoRenewCount\":3"));
            String garbage = snapshot(c);
            String expectGarbage = HIS.replace("delay=400", "delay=150").replace("first=600", "first=150")
                    .replace("titanic=800000", "titanic=3000000").replace("renew=0", "renew=3");
            c.note("zero/negative/garbage file: " + garbage);
            if (!garbage.equals(expectGarbage)) {
                problems.add("zero/garbage file: got " + garbage + ", expected " + expectGarbage);
            }
            // (e) the setters clamp to the same floor the loader does.
            String setters = c.onClient(mc -> {
                Object cfg = Mod.cfg(CONFIG);
                Mod.call(cfg, "setDelayMs", 0);
                Mod.call(cfg, "setFirstClickDelayMs", -100);
                Mod.call(cfg, "setRandomDelayMaxMs", -1);
                return Mod.call(cfg, "getDelayMs") + "/" + Mod.call(cfg, "getFirstClickDelayMs") + "/"
                        + Mod.call(cfg, "getRandomDelayMaxMs");
            });
            c.note("setDelayMs(0), setFirstClickDelayMs(-100), setRandomDelayMaxMs(-1) read back " + setters);
            if (!setters.equals("150/150/0")) {
                problems.add("setters read back " + setters + ", expected 150/150/0");
            }
            // (f) restart: every value set away from its default survives save + load from the file.
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(CONFIG);
                Mod.call(cfg, "setSuperpairsEnabled", false);
                Mod.call(cfg, "setSuperpairsValuableOnly", false);
                Mod.call(cfg, "setSkipGrandExpBottles", false);
                Mod.call(cfg, "setSkipGuardianPets", false);
                Mod.call(cfg, "setSuperpairsAdaptiveTimeout", true);
                Mod.call(cfg, "setBlockInputEnabled", true);
                Mod.call(cfg, "setAutoSwapGuardianPet", false);
                Mod.call(cfg, "setStopStrategy", Mod.enumValue("experiments.ExperimentStopStrategy", "MAX_XP"));
                Mod.call(cfg, "setDelayMs", 650);
                Mod.call(cfg, "setFirstClickDelayMs", 2500);
                Mod.call(cfg, "setRandomDelayMaxMs", 300);
                Mod.call(cfg, "setAutoRenewCount", 1);
                Mod.call(cfg, "setTitanicMaxPriceCoins", 1_500_000.0);
                Mod.call(cfg, "setAutonomousMode", false);
                Mod.call(cfg, "save");
                Mod.staticCall(CONFIG, "load");
            });
            String restarted = snapshot(c);
            String expectRestarted = "superpairs=false valuableOnly=false skipGrand=false skipGuardian=false adaptive=true"
                    + " blockInput=true guardianSwap=false stop=MAX_XP delay=650 first=2500 random=300 renew=1"
                    + " titanic=1500000 auto=false";
            c.note("after save + load: " + restarted);
            if (!restarted.equals(expectRestarted)) {
                problems.add("save + load: got " + restarted + ", expected " + expectRestarted);
            }
            // ... and the saved file loads again unchanged: no second migration of a version-2 file's false/0 values.
            c.ctx().runOnClient(mc -> Mod.staticCall(CONFIG, "load"));
            String twice = snapshot(c);
            if (!twice.equals(expectRestarted)) {
                problems.add("second load of the saved file: got " + twice);
            }
        } finally {
            restore(c, saved);
            MenuKit.reset(c);
        }
        c.check(problems.isEmpty(), String.join(" | ", problems));
    }

    // ---- click spacing ---------------------------------------------------------------------------------------------

    /** One game played by Auto E-Table: the server's tick gaps between consecutive clicks of one sequence. */
    record Run(String label, String reason, int clicks, int wrong, List<Long> gaps) {
        long minGap() {
            return gaps.stream().mapToLong(Long::longValue).min().orElse(-1);
        }

        String line() {
            List<Long> sorted = new ArrayList<>(gaps);
            sorted.sort(Long::compare);
            return label + ": game " + reason + ", " + clicks + " clicks (" + wrong + " wrong), " + gaps.size()
                    + " in-sequence gaps, min " + minGap() + " ticks, all " + sorted;
        }
    }

    /**
     * Plays one game with the CURRENT config plus the switches Auto E-Table needs (no delay is touched here, so the
     * config under test is what paces it). Stop At Max XP plays every round; the Guardian swap is off (228 covers it).
     */
    private static Run play(Session c, String label, String game) throws Exception {
        MenuKit.reset(c);
        try (MenuKit.Cfg cfg = new MenuKit.Cfg(c)) {
            cfg.set(CONFIG, "Enabled", true).set(CONFIG, "AutonomousMode", true).set(CONFIG, "SuperpairsEnabled", true)
                    .set(CONFIG, "StopStrategy", Mod.enumValue("experiments.ExperimentStopStrategy", "MAX_XP"))
                    .set(CONFIG, "BlockInputEnabled", false).set(CONFIG, "AutoSwapGuardianPet", false)
                    .set(CONFIG, "SuperpairsValuableOnly", false);
            c.ctx().runOnClient(mc -> Mod.setField(ExperimentCases.FEATURE, "armed", true));
            try {
                int before = c.events("experiment.click").size();
                int overBefore = c.events("experiment.over").size();
                JsonObject st = c.hx().call("menu.experiment", "game", game, "seed", 47, "rounds", 5, "watch", 8,
                        "hold", 6, "pairs", 4, "clicks", 30).getAsJsonObject();
                MenuKit.awaitScreen(c, st.get("title").getAsString(), 100);
                c.waitUntil(label + ": the game to end", mc -> c.events("experiment.over").size() > overBefore, 3000);
                JsonObject over = c.events("experiment.over").get(c.events("experiment.over").size() - 1);
                List<JsonObject> clicks = c.events("experiment.click");
                clicks = new ArrayList<>(clicks.subList(before, clicks.size()));
                List<Long> gaps = new ArrayList<>();
                for (int i = 1; i < clicks.size(); i++) {
                    JsonObject a = clicks.get(i - 1);
                    JsonObject b = clicks.get(i);
                    // One sequence: the same round (Superpairs is all round 0), both in the player's turn.
                    if (a.get("round").getAsInt() == b.get("round").getAsInt()
                            && !a.get("phase").getAsString().equals("WATCH") && !b.get("phase").getAsString().equals("WATCH")) {
                        gaps.add(b.get("tick").getAsLong() - a.get("tick").getAsLong());
                    }
                }
                return new Run(label, over.get("reason").getAsString(), clicks.size(), over.get("wrong").getAsInt(), gaps);
            } finally {
                c.ctx().runOnClient(mc -> Mod.setField(ExperimentCases.FEATURE, "armed", false));
            }
        } finally {
            MenuKit.reset(c);
        }
    }

    static void spacing(Session c) throws Exception {
        MenuKit.reset(c);
        if (!MenuKit.cheat()) {
            c.note("legit jar: Auto E-Table clicks nothing (223-225 cover the no-click check)");
            return;
        }
        byte[] saved = backup(c);
        List<String> problems = new ArrayList<>();
        List<Run> runs = new ArrayList<>();
        try {
            // A click is sent on a client tick and counted on the server's next one, so two clicks d ms apart land
            // d/50 ticks apart, give or take one. The floors below allow that one tick.
            // (1) Defaults: what a friend gets on first use. Click Delay 400 ms = 8 ticks.
            loadFrom(c, null);
            Run defaults = play(c, "defaults (no file)", "CHRONOMATRON");
            runs.add(defaults);
            expect(problems, defaults, "max rounds", 7);
            // (2) Random Delay above Click Delay: 400 ms with up to 500 ms of jitter. Paced from the DECISION, two
            // clicks were 400 - jitter apart, or swapped.
            loadFrom(c, "{\"configVersion\":2,\"delayMs\":400,\"firstClickDelayMs\":600,\"randomDelayMaxMs\":500}");
            Run jitter = play(c, "Click Delay 400 + Random Delay 500", "CHRONOMATRON");
            runs.add(jitter);
            expect(problems, jitter, "max rounds", 7);
            // (3) An old file with zero delays: 0 ms clicked every tick. The floor is 150 ms = 3 ticks.
            loadFrom(c, oldDefaultsFile("").replace("\"delayMs\":200", "\"delayMs\":0")
                    .replace("\"firstClickDelayMs\":1000", "\"firstClickDelayMs\":0"));
            Run zero = play(c, "old file, delays 0", "CHRONOMATRON");
            runs.add(zero);
            expect(problems, zero, "max rounds", 2);
            // (4) Superpairs with Adaptive Timeout on, defaults otherwise.
            loadFrom(c, "{\"configVersion\":2,\"superpairsAdaptiveTimeout\":true}");
            Run pairs = play(c, "Superpairs, Adaptive Timeout on", "SUPERPAIRS");
            runs.add(pairs);
            expect(problems, pairs, "all pairs", 7);
        } finally {
            runs.forEach(r -> c.note(r.line()));
            restore(c, saved);
            MenuKit.reset(c);
        }
        c.check(MenuKit.maxPerTick(MenuKit.clicksPerTick(c)) <= 1, "more than one click in a server tick");
        c.check(problems.isEmpty(), String.join(" | ", problems));
    }

    private static void expect(List<String> problems, Run r, String reason, long minTicks) {
        if (!r.reason().equals(reason) || r.wrong() != 0) {
            problems.add(r.label() + ": game ended '" + r.reason() + "' with " + r.wrong() + " wrong, expected '" + reason + "'");
        }
        if (r.gaps().size() < 3) {
            problems.add(r.label() + ": only " + r.gaps().size() + " in-sequence gaps measured, the run proves nothing");
        } else if (r.minGap() < minTicks) {
            problems.add(r.label() + ": two clicks " + r.minGap() + " tick(s) apart, expected at least " + minTicks
                    + " " + r.gaps());
        }
    }
}
