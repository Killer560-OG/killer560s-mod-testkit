package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560's 2026-10-08 dungeon list (mod branch dungeon-fixes), the parts a singleplayer world can prove.
 *
 * <p>551 Split Timers layout: "blood open, watcher, portal entry, then boss entry which is that sum. Then a line, then
 * maxor, storm, term, goldor, necron, dragons if in M7 and then the boss overall timer. Then another line, then the
 * total time and the lag in seconds." An M7 run is fed line by line through the mod's own chat path
 * ({@code SplitTimersFeature.onLine}) with exact times and lag, and the drawn rows must be that layout to the
 * hundredth, every time "X (Y)". Then F7 (no Dragons row, Necron to the end), F3 (one boss row, no separate Boss row),
 * a run whose Mort line never comes (clock starts 1 s after "Starting in 1 second."), the end-of-run chat summary,
 * Lagless Times persisting across a reload, an old config file with the removed toggles, and screenshots at GUI 2
 * (copied to {@code TESTKIT_DUNGEONFIX_SHOTS} when set).
 *
 * <p>552 Blood Camp stall: "Blood camp can break and not show the path if the server lags for a tick." A stand moved
 * on the integrated server stops for 15 ticks mid-trip (no move packets - a stall, longer than the 10-tick gap the old
 * code read as "a new trip"), then carries on; a duplicate move is fed to the mod's packet hook, and a move for the
 * stand after it is removed. The path must be drawn every tick from the first move to the end, the trip must keep its
 * start and countdown, and the predicted spot must still be where the stand stops.
 *
 * <p>553 Etherwarp on p3sim: on p3sim.net only the overlay casts from 1.8.9's sneak eye height 1.54 instead of 1.27.
 * The cast start must differ by exactly 0.27 with p3sim on vs off, the target block must be the one a ray from that
 * height reaches on a flat floor, and on a Hypixel address or none nothing changes.
 */
final class DungeonFixCases {

    private static final String SPLITS = "splittimers.SplitTimersConfig";
    private static final String SF = "splittimers.SplitTimersFeature";
    private static final String LAG = "splittimers.SplitLagClock";
    private static final String BC_CFG = "bloodcamp.BloodCampConfig";
    private static final String BC = "bloodcamp.BloodCampFeature";

    private DungeonFixCases() {
    }

    // =================================================================================================================
    // 551 Split Timers layout
    // =================================================================================================================

    /** One boundary line: seconds after the arming line, the lag clock then (ms), the line. */
    private record Step(double at, long lagMs, String line) {
    }

    private static final String MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon.";
    private static final String DOOR = "[BOSS] The Watcher: Things feel a little more roomy now, eh?";
    private static final String PROVEN = "[BOSS] The Watcher: You have proven yourself. You may pass.";
    private static final String DEFEATED = "                       ☠ Defeated Maxor, Storm, Goldor, and Necron in 04m 38s";

    private static final List<Step> M7 = List.of(
            new Step(1.0, 0, MORT),
            new Step(23.39, 2800, DOOR),
            new Step(57.49, 3700, PROVEN),
            new Step(63.54, 3850, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"),
            new Step(91.94, 4300, "[BOSS] Storm: Pathetic Maxor, just like expected."),
            new Step(132.06, 4720, "[BOSS] Goldor: Who dares trespass into my domain?"),
            new Step(170.61, 6170, "The Core entrance is opening!"),
            new Step(182.91, 6420, "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much."),
            // The other Necron line must not move the Necron split (first write wins).
            new Step(186.00, 6500, "[BOSS] Necron: You went further than any human before, congratulations."),
            new Step(214.71, 6970, "[BOSS] Necron: All this, for nothing..."),
            new Step(279.91, 7770, DEFEATED));

    /** What the HUD must draw for {@link #M7}; "" is a divider line. */
    private static final List<String> M7_ROWS = List.of(
            "Blood Open: 22.39s (19.59s)", "Watcher: 34.10s (33.20s)", "Portal Entry: 6.05s (5.90s)",
            "Boss Entry: 1m 2.54s (58.69s)", "",
            "Maxor: 28.40s (27.95s)", "Storm: 40.12s (39.70s)", "Terminals: 38.55s (37.10s)", "Goldor: 12.30s (12.05s)",
            "Necron: 31.80s (31.25s)", "Dragons: 1m 5.20s (1m 4.40s)", "Boss: 3m 36.37s (3m 32.45s)", "",
            "Total: 4m 38.91s (4m 31.14s)", "Lag: 7.77s");

    /** F7: the same lines; the Necron row runs on to the end, and there is no Dragons row. */
    private static final List<String> F7_ROWS = List.of(
            "Blood Open: 22.39s (19.59s)", "Watcher: 34.10s (33.20s)", "Portal Entry: 6.05s (5.90s)",
            "Boss Entry: 1m 2.54s (58.69s)", "",
            "Maxor: 28.40s (27.95s)", "Storm: 40.12s (39.70s)", "Terminals: 38.55s (37.10s)", "Goldor: 12.30s (12.05s)",
            "Necron: 1m 37.00s (1m 35.65s)", "Boss: 3m 36.37s (3m 32.45s)", "",
            "Total: 4m 38.91s (4m 31.14s)", "Lag: 7.77s");

    private static final List<Step> F3 = List.of(
            new Step(1.0, 0, MORT),
            new Step(21.0, 500, "[BOSS] The Watcher: So you made it this far... interesting."),
            new Step(51.0, 900, PROVEN),
            new Step(56.0, 1000, "[BOSS] The Professor: I was burdened with terrible news recently..."),
            new Step(156.0, 2000, "[BOSS] The Professor: What?! My Guardian power is unbeatable!"),
            new Step(171.0, 2100, "                       ☠ Defeated The Professor in 02m 50s"));

    private static final List<String> F3_ROWS = List.of(
            "Blood Open: 20.00s (19.50s)", "Watcher: 30.00s (29.60s)", "Portal Entry: 5.00s (4.90s)",
            "Boss Entry: 55.00s (54.00s)", "", "The Professor: 1m 55.00s (1m 53.90s)", "",
            "Total: 2m 50.00s (2m 47.90s)", "Lag: 2.10s");

    static void splitsLayout(UiCase c) throws Exception {
        Map<Path, byte[]> saved = snapshotConfig(c, SPLITS);
        long savedLag = c.onClient(mc -> (Long) R.getStatic(R.cls(LAG), "accumulatedLagMs"));
        boolean savedSeen = c.onClient(mc -> (Boolean) R.getStatic(R.cls(LAG), "pingClockSeen"));
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            c.onClient(mc -> {
                Object st = Mod.cfg(SPLITS);
                Mod.call(st, "setEnabled", true);
                Mod.call(st, "setAnnounceInChat", true);
                Mod.call(st, "setLaglessTimes", true);
                Mod.call(st, "setWatcherMoveSplit", false);
                Mod.call(st, "setP5DragonLines", false);
                Mod.call(st, "setP5RelicLines", false);
                Mod.call(st, "setCoreEntryTimes", false);
                Mod.call(st, "setCoreEntrySlowestHud", false);
                R.setStatic(R.cls(LAG), "pingClockSeen", true);
                return null;
            });

            // ---- M7, with a mid-run picture at GUI 2 --------------------------------------------------------------
            long base = System.currentTimeMillis() - 150_000L;
            arm(c, "M7", base);
            String midLabel = null;
            for (Step s : M7) {
                if (s.at() > 150) {
                    break;
                }
                feed(c, base, s);
                if (s.line().startsWith("[BOSS] Goldor")) {
                    midLabel = c.onClient(mc -> (String) Mod.staticCall(SF, "getCurrentSegmentLabel"));
                }
            }
            c.check("Terminals".equals(midLabel), "during terminals the current segment is '" + midLabel
                    + "', not Terminals");
            List<String> mid = drawn(c);
            c.note("M7 mid-run (during terminals), the HUD draws:");
            mid.forEach(l -> c.note("  | " + l));
            c.check(mid.size() >= 9 && mid.get(0).startsWith("Blood Open: ") && mid.get(3).startsWith("Boss Entry: ")
                            && mid.get(4).isBlank() && mid.get(7).startsWith("Terminals: "),
                    "mid-run layout is not Blood Open / Watcher / Portal Entry / Boss Entry / line / Maxor / Storm / "
                            + "Terminals...: " + mid);
            c.check(mid.stream().anyMatch(l -> l.startsWith("Boss: ")), "mid-run: no running Boss row");
            c.check(mid.stream().anyMatch(l -> l.startsWith("Total: ")) && mid.stream().anyMatch(l -> l.startsWith("Lag: ")),
                    "mid-run: no Total / Lag rows");
            shotAtGui2(c, "m7-terminals", gui);

            for (Step s : M7) {
                if (s.at() > 150) {
                    feed(c, base, s);
                }
            }
            List<String> rows = drawn(c);
            compare(c, "M7", rows, M7_ROWS);
            shotAtGui2(c, "m7-finished", gui);

            // The end-of-run chat summary mirrors the layout (dividers left out).
            List<String> chat = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<Component> lines = (List<Component>) R.getStatic(R.cls(SF), "pendingFinishMessages");
                List<String> out = new ArrayList<>();
                lines.forEach(l -> out.add(l.getString()));
                return out;
            });
            c.note("M7 end-of-run chat summary:");
            chat.forEach(l -> c.note("  > " + l));
            List<String> wantChat = new ArrayList<>(List.of("Dragons took 65.20s (64.40s)!"));
            for (String r : M7_ROWS) {
                if (r.isEmpty()) {
                    continue;
                }
                wantChat.add(r.startsWith("Lag: ") ? r + "." : r.replaceFirst(": ", " took ") + ".");
            }
            c.check(chat.size() >= wantChat.size() && chat.subList(0, wantChat.size()).equals(wantChat),
                    "chat summary is not the layout: want " + wantChat + ", got " + chat);

            // ---- F7: no Dragons; Necron to the end ---------------------------------------------------------------
            base = System.currentTimeMillis() - 300_000L;
            arm(c, "F7", base);
            for (Step s : M7) {
                feed(c, base, s);
            }
            compare(c, "F7", drawn(c), F7_ROWS);

            // ---- M7 where Phase 5 starts on the Wither King's line, Necron's never heard -------------------------
            base = System.currentTimeMillis() - 300_000L;
            arm(c, "M7", base);
            for (Step s : M7) {
                feed(c, base, s.line().startsWith("[BOSS] Necron: All this")
                        ? new Step(s.at(), s.lagMs(), "[BOSS] The Wither King: You... again?") : s);
            }
            compare(c, "M7 with the Wither King's line", drawn(c), M7_ROWS);

            // ---- F3: one boss row named after the boss ------------------------------------------------------------
            base = System.currentTimeMillis() - 200_000L;
            arm(c, "F3", base);
            for (Step s : F3) {
                feed(c, base, s);
            }
            compare(c, "F3", drawn(c), F3_ROWS);

            // ---- Mort's line never comes: the clock starts 1 s after "Starting in 1 second." --------------------------
            base = System.currentTimeMillis() - 200_000L;
            arm(c, "F3", base);
            for (Step s : F3) {
                if (!s.line().equals(MORT)) {
                    feed(c, base, s);
                }
            }
            compare(c, "F3 without Mort's line", drawn(c), F3_ROWS);

            // ---- Lagless Times persists; off drops every parenthesis ---------------------------------------------
            boolean reloaded = c.onClient(mc -> {
                Object st = Mod.cfg(SPLITS);
                Mod.call(st, "setLaglessTimes", false);
                Mod.call(st, "save");
                Mod.staticCall(SPLITS, "load");
                return (Boolean) Mod.call(Mod.cfg(SPLITS), "isLaglessTimes");
            });
            c.check(!reloaded, "Lagless Times OFF did not survive save + load");
            List<String> plain = drawn(c);
            c.note("Lagless Times OFF: " + plain);
            c.check(plain.stream().noneMatch(l -> l.contains("(")), "Lagless Times OFF still draws a parenthesis");
            c.check(plain.contains("Total: 2m 50.00s") && plain.contains("Lag: 2.10s"),
                    "Lagless Times OFF: Total / Lag rows wrong: " + plain);

            // ---- an old file with the removed toggles loads, and the layout ignores them --------------------------
            Path cfgPath = c.onClient(mc -> (Path) R.getStatic(R.cls(SPLITS), "CONFIG_PATH"));
            Files.writeString(cfgPath, "{\"enabled\": true, \"laglessTimes\": true, \"clearSplits\": true,"
                    + " \"clearBossDivider\": false, \"bossEntryTimer\": false, \"bossTimer\": false,"
                    + " \"totalWithLag\": false, \"totalWithoutLag\": false, \"lagLostLine\": false}");
            c.onClient(mc -> {
                Mod.staticCall(SPLITS, "load");
                return null;
            });
            compare(c, "F3 with an old config's toggles all off", drawn(c), F3_ROWS);
        } finally {
            c.onClient(mc -> {
                R.setStatic(R.cls(LAG), "accumulatedLagMs", savedLag);
                R.setStatic(R.cls(LAG), "pingClockSeen", savedSeen);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                mc.options.guiScale().set(gui);
                mc.resizeGui();
                return null;
            });
            clearRun(c);
            restoreConfig(c, saved, SPLITS);
        }
    }

    /** A fresh armed run for {@code floor}, as "Starting in 1 second." makes one, at {@code base}. */
    private static void arm(UiCase c, String floor, long base) {
        c.onClient(mc -> {
            try {
                Class<?> f = R.cls(SF);
                Constructor<?> ctor = R.cls(SF + "$RunState").getDeclaredConstructor();
                ctor.setAccessible(true);
                Object run = ctor.newInstance();
                Method m = f.getDeclaredMethod("splitsForFloor", String.class);
                m.setAccessible(true);
                List<?> splits = (List<?>) m.invoke(null, floor);
                R.set(run, "splits", splits);
                R.set(run, "timeMs", new long[splits.size()]);
                R.set(run, "lagMs", new long[splits.size()]);
                R.set(run, "armedAtMs", base);
                R.set(run, "armedLagMs", 0L);
                R.setStatic(f, "run", run);
                R.setStatic(R.cls(LAG), "accumulatedLagMs", 0L);
                return null;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
    }

    /** One line through the mod's own chat path at its exact time, with the lag clock at its value then. */
    private static void feed(UiCase c, long base, Step s) {
        c.onClient(mc -> {
            try {
                R.setStatic(R.cls(LAG), "accumulatedLagMs", s.lagMs());
                Method m = R.cls(SF).getDeclaredMethod("onLine", String.class, long.class);
                m.setAccessible(true);
                m.invoke(null, s.line(), base + Math.round(s.at() * 1000.0));
                return null;
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(UiCase.describe(e), e);
            }
        });
    }

    private static void compare(UiCase c, String what, List<String> got, List<String> want) {
        c.note(what + ", the HUD draws:");
        got.forEach(l -> c.note("  | " + l));
        List<String> norm = new ArrayList<>();
        got.forEach(l -> norm.add(l.isBlank() ? "" : l));
        if (!norm.equals(want)) {
            c.problem(what + ": drawn rows " + norm + " are not " + want);
        }
    }

    /** The text runs Split Timers' render() draws, top to bottom. */
    private static List<String> drawn(UiCase c) {
        c.ticks(2);
        return c.onClient(mc -> {
            Object e = Mod.staticCall("hud.HudElementRegistry", "byId", "split_timers");
            GuiRenderState state = new GuiRenderState();
            try {
                GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
                R.cls("hud.HudElement").getMethod("render", GuiGraphicsExtractor.class, int.class, int.class)
                        .invoke(e, g, 0, 0);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(UiCase.describe(ex), ex);
            }
            List<String> out = new ArrayList<>();
            state.forEachText(t -> out.add(dev.testkit.gametest.TextRuns.string(t)));
            return out;
        });
    }

    /** A screenshot of the live HUD at GUI scale 2, the Split Timers at (10, 10) scale 1. */
    private static void shotAtGui2(UiCase c, String suffix, int gui) throws Exception {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            McCompat.clearChatAndToasts(mc);
            Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
            Object hud = Mod.cfg("hud.HudConfig");
            Mod.call(hud, "setScale", "split_timers", 1.0f);
            Mod.call(hud, "setPosition", "split_timers", 10, 10);
            mc.options.guiScale().set(2);
            mc.resizeGui();
            return null;
        });
        c.ticks(5);
        String name = c.name() + "-" + suffix;
        Path taken = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(name));
        Path kept = dev.testkit.harness.Report.screenshot(name, taken);
        int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
        long since = c.onClient(mc -> ((Number) Mod.staticCall("hud.HudSeen", "msSince", "split_timers",
                System.currentTimeMillis())).longValue());
        c.note("picture " + name + " at GUI " + gs + " -> " + (kept != null ? kept : taken) + " (Split Timers drew "
                + since + " ms ago)");
        c.check(gs == 2, "the picture is at GUI " + gs + ", not 2");
        c.check(since < 1000, "Split Timers did not draw for the picture");
        copyShot(c, taken, name);
        // Room sim stays on until the case ends: turning it off is "left the dungeon", which resets the run.
    }

    private static void copyShot(UiCase c, Path taken, String name) {
        String dir = System.getenv("TESTKIT_DUNGEONFIX_SHOTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String jar = Mod.isCheat() ? "cheat" : "legit";
            Path out = Path.of(dir).resolve(name + "-" + jar + ".png");
            Files.createDirectories(out.getParent());
            Files.copy(taken, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            c.note("copied to " + out);
        } catch (java.io.IOException e) {
            c.note("could not copy the picture: " + e);
        }
    }

    private static void clearRun(UiCase c) {
        try {
            c.onClient(mc -> {
                try {
                    Constructor<?> ctor = R.cls(SF + "$RunState").getDeclaredConstructor();
                    ctor.setAccessible(true);
                    R.setStatic(R.cls(SF), "run", ctor.newInstance());
                    R.setStatic(R.cls(SF), "pendingFinishMessages", List.of());
                    R.setStatic(R.cls(SF), "finishDelayTicks", -1);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
        } catch (Throwable t) {
            c.note("run reset: " + UiCase.describe(t));
        }
    }

    // =================================================================================================================
    // 552 Blood Camp across a stall
    // =================================================================================================================

    static void bloodCampStall(UiCase c) throws Exception {
        Object cfg = c.onClient(mc -> Mod.cfg(BC_CFG));
        Object[] saved = c.onClient(mc -> new Object[]{Mod.call(cfg, "isEnabledRaw"), Mod.call(cfg, "getShowOverlayRaw"),
                Mod.call(cfg, "getSpawnLineRaw"), Mod.call(cfg, "isCountdownStartSound"), Mod.call(cfg, "isKillSound"),
                Mod.call(cfg, "getTriggerBotRaw"), Mod.call(cfg, "getAuraRaw")});
        Difficulty[] oldDifficulty = new Difficulty[1];
        try {
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", true);
                Mod.call(cfg, "setShowOverlay", true);
                Mod.call(cfg, "setSpawnLine", true);
                Mod.call(cfg, "setCountdownStartSound", false);
                Mod.call(cfg, "setKillSound", false);
                Mod.call(cfg, "setTriggerBotEnabled", false);
                Mod.call(cfg, "setAuraEnabled", false);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                oldDifficulty[0] = mc.getSingleplayerServer().getWorldData().getDifficulty();
                return null;
            });
            server(c, s -> s.setDifficulty(Difficulty.EASY, true));
            command(c, "tp @a 40.5 -60 40.5 0 15");
            String watcherTex = anyOf(Mod.field(Mod.ROOT + BC, "WATCHER_SKULL_TEXTURES"));
            String mobTex = anyOf(Mod.field(Mod.ROOT + BC, "MOB_SKULL_TEXTURES"));
            command(c, "summon zombie 40.5 -60 50.5 {Tags:[\"bcstall\"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,"
                    + "PersistenceRequired:1b,equipment:{head:" + head(watcherTex) + "}}");
            c.ctx().waitFor(mc -> Mod.field(Mod.ROOT + BC, "watcherEntityId") != null, 200);

            Vec3 start = new Vec3(32.5, -60, 54.5);
            command(c, String.format(Locale.ROOT, "summon armor_stand %.3f %.3f %.3f {Tags:[\"bcstallmob\"],"
                    + "NoGravity:1b,equipment:{head:%s}}", start.x, start.y, start.z, head(mobTex)));
            c.ticks(10);
            boolean first;
            try {
                first = (Boolean) c.onClient(mc -> Mod.staticCall("bloodcamp.BloodCampMoveTimer", "isFirstSpawns"));
            } catch (RuntimeException | AssertionError e) {
                first = true;
            }
            double distance = first ? 16.1 : 11.9;
            int moveTicks = (int) Math.round((first ? 40 : 0) + 38 + 0.8) - 4;
            double step = distance / moveTicks;
            List<Integer> ids = serverIds(c, "bcstallmob");
            c.check(ids.size() == 1, "summoned " + ids.size() + " stands, want 1");
            int standId = ids.get(0);
            int stallAt = moveTicks / 3;
            int stall = 15;
            int bridgedBefore = bridged(c);

            Long startedAt = null;
            Vec3 startVec = null;
            int pathTicks = 0;
            int missingTicks = 0;
            int firstPathTick = -1;
            int restarts = 0;
            List<String> gaps = new ArrayList<>();
            int moved = 0;
            int total = moveTicks + stall + 20;
            for (int i = 1; i <= total; i++) {
                // Ticks stallAt+1 .. stallAt+stall: the server sends nothing (the stand does not move).
                boolean inStall = i > stallAt && i <= stallAt + stall;
                if (!inStall && moved < moveTicks) {
                    moved++;
                    double f = moved == moveTicks ? distance : step * moved;
                    move(c, "bcstallmob", start.add(f, 0, 0));
                }
                c.ticks(1);
                if (i == stallAt + stall + 1) {
                    // A doubled update right as movement resumes: the same packet handed to the mod twice.
                    c.onClient(mc -> {
                        ClientboundMoveEntityPacket p = new ClientboundMoveEntityPacket.Pos(standId,
                                (short) Math.round(step * 4096), (short) 0, (short) 0, false);
                        Mod.staticCall(BC, "onMoveEntity", p, mc.level);
                        Mod.staticCall(BC, "onMoveEntity", p, mc.level);
                        return null;
                    });
                }
                Object[] now = c.onClient(mc -> state(mc, standId));
                Vec3 end = (Vec3) now[0];
                boolean drawn = now[3] != null;
                if (end != null && drawn) {
                    if (firstPathTick < 0) {
                        firstPathTick = i;
                    }
                    pathTicks++;
                    if (startedAt == null) {
                        startedAt = (Long) now[1];
                        startVec = (Vec3) now[2];
                    } else if (!startedAt.equals(now[1]) || !startVec.equals(now[2])) {
                        restarts++;
                        gaps.add("tick " + i + ": trip restarted (start " + now[2] + ", countdown origin " + now[1] + ")");
                        startedAt = (Long) now[1];
                        startVec = (Vec3) now[2];
                    }
                } else if (firstPathTick >= 0 && i <= moveTicks + stall) {
                    missingTicks++;
                    gaps.add("tick " + i + (inStall ? " (stall)" : "") + ": no path");
                }
            }
            Object[] last = c.onClient(mc -> state(mc, standId));
            Vec3 trueEnd = start.add(distance, 0, 0);
            double err = last[0] == null ? Double.NaN : ((Vec3) last[0]).distanceTo(trueEnd);
            int bridgedAfter = bridged(c);
            c.note(String.format(Locale.ROOT, "%s wave, %d move ticks, stall of %d ticks after move %d; path drawn on %d"
                            + " tick(s) from tick %d, missing on %d, trip restarted %d time(s); stalls bridged %d; predicted"
                            + " spot %.3f from where the stand stopped", first ? "first" : "later", moveTicks, stall,
                    stallAt, pathTicks, firstPathTick, missingTicks, restarts, bridgedAfter - bridgedBefore, err));
            gaps.stream().limit(12).forEach(g -> c.note("  " + g));
            c.check(firstPathTick > 0 && firstPathTick < stallAt, "the path never appeared before the stall - nothing"
                    + " was tracked (first at " + firstPathTick + ")");
            c.check(missingTicks == 0, "the path disappeared on " + missingTicks + " tick(s): " + gaps);
            c.check(restarts == 0, "the trip restarted " + restarts + " time(s) across the stall: " + gaps);
            c.check(bridgedAfter > bridgedBefore, "the mod never bridged a stall (stallsBridged "
                    + bridgedBefore + " -> " + bridgedAfter + ") - the stall did not reach it");
            c.check(err < 0.05, String.format(Locale.ROOT, "predicted spot %.3f from where the stand stopped", err));

            // A move for the stand after it is gone must not bring it back.
            command(c, "kill @e[tag=bcstallmob]");
            c.ticks(5);
            boolean back = c.onClient(mc -> {
                ClientboundMoveEntityPacket p = new ClientboundMoveEntityPacket.Pos(standId, (short) 1000, (short) 0,
                        (short) 0, false);
                Mod.staticCall(BC, "onMoveEntity", p, mc.level);
                return state(mc, standId)[0] != null;
            });
            c.check(!back, "a move after the stand was removed brought its path back");
            c.note("move after removal: path " + (back ? "BACK" : "stays gone"));
        } finally {
            command(c, "kill @e[tag=bcstall]");
            command(c, "kill @e[tag=bcstallmob]");
            if (oldDifficulty[0] != null) {
                Difficulty d = oldDifficulty[0];
                server(c, s -> s.setDifficulty(d, true));
            }
            c.onClient(mc -> {
                Mod.call(cfg, "setEnabled", saved[0]);
                Mod.call(cfg, "setShowOverlay", saved[1]);
                Mod.call(cfg, "setSpawnLine", saved[2]);
                Mod.call(cfg, "setCountdownStartSound", saved[3]);
                Mod.call(cfg, "setKillSound", saved[4]);
                Mod.call(cfg, "setTriggerBotEnabled", saved[5]);
                Mod.call(cfg, "setAuraEnabled", saved[6]);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                return null;
            });
        }
    }

    private static int bridged(UiCase c) {
        try {
            return c.onClient(mc -> (Integer) Mod.staticCall(BC, "stallsBridged"));
        } catch (RuntimeException | AssertionError e) {
            return 0;   // a jar without the fix
        }
    }

    /** [endVector, startedAtTick, startVec, last drawn row for the stand]. */
    private static Object[] state(Minecraft mc, int standId) {
        Object[] out = new Object[4];
        @SuppressWarnings("unchecked")
        Map<Object, Object> mobs = (Map<Object, Object>) Mod.field(Mod.ROOT + BC, "bloodMobs");
        for (Map.Entry<Object, Object> m : mobs.entrySet()) {
            if (((Entity) m.getKey()).getId() == standId) {
                out[0] = Mod.field(m.getValue(), "endVector");
                out[1] = Mod.field(m.getValue(), "startedAtTick");
                out[2] = Mod.field(m.getValue(), "startVec");
            }
        }
        @SuppressWarnings("unchecked")
        List<double[]> frame = (List<double[]>) Mod.staticCall(BC, "lastFrame");
        for (double[] r : frame) {
            if ((int) r[14] == standId) {
                out[3] = r;
            }
        }
        return out;
    }

    // =================================================================================================================
    // 553 Etherwarp on p3sim
    // =================================================================================================================

    static void etherwarpP3sim(UiCase c) throws Exception {
        final double pitch = 10.0;
        // A flat floor whose top is y 100, open sky above, well away from every other case's blocks.
        // Go there first: /fill refuses blocks in chunks that are not loaded.
        command(c, "tp @a 200.5 110 201.5 -90 " + pitch);
        c.ticks(20);
        command(c, "fill 196 99 196 230 99 206 minecraft:stone");
        command(c, "fill 196 100 196 230 106 206 minecraft:air");
        command(c, "tp @a 200.5 100 201.5 -90 " + pitch);
        c.ctx().waitFor(mc -> mc.player != null && Math.abs(mc.player.getY() - 100.0) < 1e-6 && mc.player.onGround(), 100);
        try {
            c.onClient(mc -> {
                mc.options.keyShift.setDown(true);
                return null;
            });
            c.ctx().waitFor(mc -> mc.player != null && mc.player.isCrouching(), 100);
            c.onClient(mc -> {
                mc.player.setYRot(-90f);
                mc.player.setXRot((float) pitch);
                return null;
            });
            c.ticks(2);
            double[] off = cast(c, null);
            double[] p3 = cast(c, "p3sim.net");
            double[] hyp = cast(c, "mc.hypixel.net");
            double[] none = cast(c, null);
            // Expected landing column for a cast from eye height h: the ray meets the floor's top (y 100) at
            // x = feet x + h / tan(pitch); the block under that point is the target.
            double feetX = off[4];
            double feetY = off[5];
            int want127 = (int) Math.floor(feetX + (feetY + 1.27 - 100.0) / Math.tan(Math.toRadians(pitch)));
            int want154 = (int) Math.floor(feetX + (feetY + 1.54 - 100.0) / Math.tan(Math.toRadians(pitch)));
            c.note(String.format(Locale.ROOT, "feet %.3f,%.3f; cast start y: off %.4f, p3sim %.4f, hypixel %.4f, none"
                            + " %.4f; target x: off %d, p3sim %d, hypixel %d (want %d from 1.27, %d from 1.54);"
                            + " isOnP3Sim off/p3sim/hypixel = %s/%s/%s; TeleportUtils sneak eye off/p3sim = %.2f/%.2f, stand"
                            + " %.2f/%.2f", feetX, feetY, off[0], p3[0], hyp[0], none[0], (int) off[1], (int) p3[1],
                    (int) hyp[1], want127, want154, off[2] > 0, p3[2] > 0, hyp[2] > 0, off[3], p3[3], off[6], p3[6]));
            c.check(off[1] != Integer.MIN_VALUE && p3[1] != Integer.MIN_VALUE, "the overlay found no target at all");
            c.check(Math.abs((off[0] - feetY) - 1.27) < 1e-9, "off p3sim the cast starts " + (off[0] - feetY)
                    + " above the feet, not 1.27");
            c.check(Math.abs((p3[0] - off[0]) - 0.27) < 1e-9, "p3sim vs off: cast start differs by "
                    + (p3[0] - off[0]) + ", not exactly 0.27 (1.54 - 1.27)");
            c.check(hyp[0] == off[0] && none[0] == off[0] && hyp[1] == off[1],
                    "a Hypixel address (or none) changed the cast: " + hyp[0] + "/" + none[0] + " vs " + off[0]);
            c.check((int) off[1] == want127, "off p3sim the target x is " + (int) off[1] + ", the ray from 1.27 lands on "
                    + want127);
            c.check((int) p3[1] == want154, "on p3sim the target x is " + (int) p3[1] + ", the ray from 1.54 lands on "
                    + want154);
            c.check(want127 != want154, "the floor geometry does not tell the two heights apart");
            c.check(p3[2] > 0 && off[2] == 0 && hyp[2] == 0, "Floor7Tracker.isOnP3Sim did not follow the address");
            c.check(off[3] == 1.27 && p3[3] == 1.54 && off[6] == 1.62 && p3[6] == 1.62,
                    "TeleportUtils.eyeHeight: sneak " + off[3] + "/" + p3[3] + ", stand " + off[6] + "/" + p3[6]);
        } finally {
            setServerData(c, null);
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                return null;
            });
            command(c, "fill 196 99 196 230 106 206 minecraft:air");
        }
    }

    /** [cast start y, target x (MIN_VALUE: none), isOnP3Sim 1/0, TeleportUtils sneak eye, feet x, feet y, stand eye]
     *  with the connection's server address set to {@code address} (null: none). */
    private static double[] cast(UiCase c, String address) {
        setServerData(c, address);
        return c.onClient(mc -> {
            Object start = Mod.staticCall("etherwarpoverlay.EtherwarpOverlayFeature", "castStart", mc.player.position(),
                    mc.player);
            Object target = Mod.staticCall("etherwarpoverlay.EtherwarpOverlayFeature", "resolveTarget", mc.level,
                    mc.player.position(), mc.player, 57.0);
            boolean p3 = (Boolean) Mod.staticCall("fastleap.Floor7Tracker", "isOnP3Sim");
            double sneak = (Double) Mod.staticCall("livemap.autoclear.TeleportUtils", "eyeHeight", true);
            double stand = (Double) Mod.staticCall("livemap.autoclear.TeleportUtils", "eyeHeight", false);
            return new double[]{((Vec3) start).y, target == null ? Integer.MIN_VALUE : ((BlockPos) target).getX(),
                    p3 ? 1 : 0, sneak, mc.player.getX(), mc.player.getY(), stand};
        });
    }

    /** Sets the live connection's ServerData (what {@code Minecraft.getCurrentServer()} returns) - singleplayer has
     *  none, and the mod reads p3sim off its address. */
    private static void setServerData(UiCase c, String address) {
        c.onClient(mc -> {
            try {
                Field f = null;
                for (Class<?> k = mc.getConnection().getClass(); k != null && f == null; k = k.getSuperclass()) {
                    for (Field candidate : k.getDeclaredFields()) {
                        if (candidate.getType() == ServerData.class) {
                            f = candidate;
                        }
                    }
                }
                if (f == null) {
                    throw new AssertionError("no ServerData field on the connection");
                }
                f.setAccessible(true);
                f.set(mc.getConnection(), address == null ? null
                        : new ServerData("test", address, ServerData.Type.OTHER));
                return null;
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    private static Map<Path, byte[]> snapshotConfig(UiCase c, String cls) {
        Map<Path, byte[]> out = new java.util.LinkedHashMap<>();
        Path p = c.onClient(mc -> (Path) R.getStatic(R.cls(cls), "CONFIG_PATH"));
        try {
            out.put(p, Files.exists(p) ? Files.readAllBytes(p) : null);
        } catch (java.io.IOException e) {
            c.note("snapshot " + p + ": " + e);
        }
        return out;
    }

    private static void restoreConfig(UiCase c, Map<Path, byte[]> saved, String cls) {
        try {
            for (Map.Entry<Path, byte[]> e : saved.entrySet()) {
                if (e.getValue() == null) {
                    Files.deleteIfExists(e.getKey());
                } else {
                    Files.write(e.getKey(), e.getValue());
                }
            }
            c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                return null;
            });
        } catch (Throwable t) {
            c.note("config restore: " + UiCase.describe(t));
        }
    }

    private static String head(String texture) {
        return "{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":{properties:[{name:\"textures\","
                + "value:\"" + texture + "\"}]}}}";
    }

    private static String anyOf(Object set) {
        return (String) ((Collection<?>) set).iterator().next();
    }

    private static List<Integer> serverIds(UiCase c, String tag) {
        java.util.concurrent.atomic.AtomicReference<List<Integer>> out = new java.util.concurrent.atomic.AtomicReference<>();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                List<Integer> ids = new ArrayList<>();
                for (ArmorStand s : server.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains(tag))) {
                    ids.add(s.getId());
                }
                ids.sort(null);
                out.set(ids);
            });
            return null;
        });
        c.ctx().waitFor(mc -> out.get() != null, 100);
        return out.get();
    }

    private static void move(UiCase c, String tag, Vec3 p) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (ArmorStand s : server.overworld().getEntities(EntityTypeTest.forClass(ArmorStand.class),
                        e -> e.entityTags().contains(tag))) {
                    s.setPos(p.x, p.y, p.z);
                }
            });
            return null;
        });
    }

    private static void command(UiCase c, String cmd) {
        server(c, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd));
    }

    private static void server(UiCase c, java.util.function.Consumer<net.minecraft.server.MinecraftServer> task) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    task.accept(server);
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
    }
}
