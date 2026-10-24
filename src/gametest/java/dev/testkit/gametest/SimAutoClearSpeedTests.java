package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>1020-sim-ac-speed</b> (mod autoclear-speed-1020, killer560 2026-10-20: "the best legit players clear rooms faster than
 * solo Auto Clear, purely because of mob-kill speed") - ticks per room of Auto Clear with LIVE mobs on whole sim floors.
 * <p>
 * Each floor: the sim's own live mobs ({@code SimLiveMobs}: {@code -PacsMobs=N} starred mobs a tile, default 2, of
 * {@code -PacsHp=H} health, default 1, walking with {@code -PacsWalk=true}; a starred miniboss in each CHAMPION room),
 * placed by the sim at GO; then Auto Clear started on its own (Any Mob Room, Spirit Sceptre, AOTV - his study run's
 * loadout, no Hyperion unless {@code -PacsHype=true}) until it says it is done or {@code -PacsMinutes} (default 10) run
 * out. Every room's {@code [AutoClear] room time:} line (the mod's RoomClock: from the first tick the clear owns, never
 * the trip to the room) is collected; per floor and over all floors the median, mean and worst ticks per CLEARED room
 * are printed as SUMMARY lines, with the buckets they went to. Floors: {@code -PacsCodes=<file>} (one map code a line)
 * replays the same floors on any jar; without it {@code -PacsRuns=N} (default 1) fresh F7s, each printing its MAP CODE.
 * <p>
 * Fails only when nothing was measured (no room cleared, no kill) - so a jar that does not clear cannot pass on an
 * empty table - or when a mod ERROR line appears. GrimAC does not apply: the sim's integrated server has no anticheat.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimAutoClearSpeedTests implements FabricClientGameTest {

    private static final String NAME = "1020-sim-ac-speed";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_LIVE = "com.killer560.hub.roomsim.SimLiveMobs";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_SCORE = "com.killer560.hub.roomsim.SimScore";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";

    static final Pattern ROOM_TIME = Pattern.compile("\\[AutoClear\\] room time: (.+?) (\\d+) ticks \\((.+?)\\): trips (\\d+)"
            + " \\((\\d+) ticks, (\\d+) warp use\\(s\\)\\), weapon uses (\\d+), kills (\\d+)(.*)$");
    private static final Pattern BUCKET = Pattern.compile(", ([a-z ]+?) (\\d+)");

    /** One room's line. */
    record RoomTime(String room, int ticks, String how, int trips, int tripTicks, int warps, int uses, int kills,
                    Map<String, Integer> buckets) {
        boolean cleared() {
            return how.startsWith("cleared") || how.startsWith("done without a map") || how.startsWith("left at its last death");
        }

        /** "cleared; last death 85" (mod RoomClock since its second version): 85, else -1. */
        int lastDeath() {
            Matcher m = Pattern.compile("last death (-?\\d+)").matcher(how);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            Scenario.skipped(NAME, "needs real room captures and room database");
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object map = ModUnderTest.config(LIVE_MAP_CONFIG);
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
        });
        List<String> codes = new ArrayList<>();
        String codesFile = System.getProperty("testkit.acsCodes", "");
        if (!codesFile.isBlank()) {
            try {
                for (String l : Files.readAllLines(Path.of(codesFile), StandardCharsets.UTF_8)) {
                    if (!l.isBlank() && !l.startsWith("#")) {
                        codes.add(l.trim());
                    }
                }
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read " + codesFile + ": " + e);
            }
        }
        int runs = codes.isEmpty() ? Integer.getInteger("testkit.acsRuns", 1) : codes.size();
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        List<RoomTime> all = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        try {
            for (int run = 1; run <= runs; run++) {
                String code = codes.isEmpty() ? null : codes.get(run - 1);
                List<RoomTime> rooms = new ArrayList<>();
                try {
                    summaries.add(oneFloor(ctx, run, code, rooms, problems));
                } catch (Throwable t) {
                    problems.add("floor " + run + " threw: " + t);
                    t.printStackTrace(System.out);
                } finally {
                    leave(ctx);
                }
                all.addAll(rooms);
            }
        } finally {
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(SIM_LIVE, "configure", new Class<?>[]{int.class, double.class, boolean.class,
                            boolean.class}, new Object[]{0, 1.0, false, true});
                } catch (RuntimeException | Error ignored) {
                    // a jar without the option
                }
            });
        }
        for (String s : summaries) {
            println("SUMMARY " + s);
        }
        println("SUMMARY all floors: " + stats(all));
        println("SUMMARY all floors buckets: " + buckets(all));
        long cleared = all.stream().filter(RoomTime::cleared).count();
        int kills = all.stream().mapToInt(RoomTime::kills).sum();
        if (cleared == 0 || kills == 0) {
            problems.add("nothing measured: " + cleared + " room(s) cleared, " + kills + " kill(s) - Auto Clear never cleared a room");
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    private static String oneFloor(ClientGameTestContext ctx, int run, String code, List<RoomTime> rooms,
                                   List<String> problems) {
        int perTile = Integer.getInteger("testkit.acsMobs", 2);
        double hp = Double.parseDouble(System.getProperty("testkit.acsHp", "1"));
        boolean walk = Boolean.getBoolean("testkit.acsWalk");
        boolean hype = Boolean.getBoolean("testkit.acsHype");
        int minutes = Integer.getInteger("testkit.acsMinutes", 10);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_LIVE, "configure", new Class<?>[]{int.class, double.class,
                boolean.class, boolean.class}, new Object[]{perTile, hp, walk, true}));
        long before = Scenario.simBuildCount(ctx);
        if (code == null) {
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class,
                        int.class}, new Object[]{mc, floor, 3, 4});
            }));
        } else {
            ctx.runOnClient(mc -> mc.execute(() -> {
                ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
                Consumer<Minecraft> build = c -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "build",
                        new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class,
                        String.class, Consumer.class, String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
            }));
        }
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        SimAutoClearTests.awaitFloorChunks(ctx);
        String mapCode = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
        println("floor " + run + " MAP CODE: " + mapCode);
        giveLoadout(ctx, hype);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_RUN, "begin", new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 600 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        String placedLine = null;
        for (int i = 0; i < 200 && placedLine == null; i += 5) {
            ctx.waitTicks(5);
            placedLine = LogTap.since(mark).stream().filter(l -> l.contains("[Sim] live mobs placed:")).findFirst()
                    .orElse(null);
        }
        println("floor " + run + ": " + (placedLine == null ? "NO live mobs line" : placedLine.replaceAll("^.*\\[Sim\\]", "[Sim]")));
        if (placedLine == null) {
            problems.add("floor " + run + ": the sim placed no live mobs");
            return "floor " + run + ": no mobs";
        }
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(AUTO_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", hype);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", hype && Boolean.getBoolean("testkit.acsHypeWeapon")
                ? "HYPERION" : "SPIRIT_SCEPTRE");
        long runMark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        List<String> log = new ArrayList<>();
        long read = runMark;
        int ticks = 0;
        int limit = minutes * 60 * 20;
        boolean started = false;
        for (; ticks < limit; ticks += 5) {
            ctx.waitTicks(5);
            long m2 = LogTap.mark();
            log.addAll(LogTap.since(read));
            read = m2;
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                problems.add("floor " + run + ": the world went away");
                break;
            }
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
            started |= busy;
            if (started && !busy) {
                break;
            }
            if (ticks % 1200 == 1195) {
                println(String.format(Locale.US, "floor %d t=%ds: %s", run, (ticks + 5) / 20,
                        ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(AUTO, "status")))));
            }
        }
        if (ctx.computeOnClient(mc -> mc.level != null && (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"))) {
            println("floor " + run + ": still running after " + minutes + " min - canceled");
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        }
        ctx.waitTicks(10);
        log.addAll(LogTap.since(read));
        String end = "?";
        int simCleared = 0;
        for (String l : log) {
            Matcher m = ROOM_TIME.matcher(l);
            if (m.find()) {
                Map<String, Integer> b = new LinkedHashMap<>();
                Matcher bm = BUCKET.matcher(m.group(9));
                while (bm.find()) {
                    b.merge(bm.group(1), Integer.parseInt(bm.group(2)), Integer::sum);
                }
                RoomTime r = new RoomTime(m.group(1), Integer.parseInt(m.group(2)), m.group(3), Integer.parseInt(m.group(4)),
                        Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), Integer.parseInt(m.group(7)),
                        Integer.parseInt(m.group(8)), b);
                rooms.add(r);
                println("floor " + run + " room: " + l.replaceAll("^.*\\[AutoClear\\] room time: ", ""));
            }
            if (l.contains("[AutoClear] trip time:")) {
                println("floor " + run + " trip: " + l.replaceAll("^.*\\[AutoClear\\] trip time: ", ""));
            }
            if (l.contains("[AutoClear] done: ") || l.contains("[AutoClear] stopped: ")) {
                end = l.replaceAll("^.*\\[AutoClear\\] ", "");
            }
            if (l.contains("[Sim] room cleared:")) {
                simCleared++;
            }
        }
        List<String> errors = new ArrayList<>();
        for (String l : log) {
            String[] parts = l.split(" ", 4);
            if (parts.length >= 3 && parts[1].equals("ERROR") && parts[2].startsWith("(")) {
                errors.add(l);
            }
        }
        if (!errors.isEmpty()) {
            problems.add("floor " + run + ": " + errors.size() + " mod ERROR line(s), first: " + errors.get(0));
        }
        int score = ctx.computeOnClient(mc -> {
            try {
                return (Integer) ModUnderTest.staticCall(SIM_SCORE, "roomsCleared");
            } catch (RuntimeException e) {
                return -1;
            }
        });
        return String.format(Locale.US, "floor %d (%s): %s; run %.1f s, sim rooms cleared %d (score count %d), ended: %s;"
                        + " buckets %s", run, mapCode.length() > 24 ? "#" + Integer.toHexString(mapCode.hashCode()) : mapCode,
                stats(rooms), ticks / 20.0, simCleared, score, end, buckets(rooms));
    }

    /** "rooms N cleared, median M, mean A, worst W ticks (worst room), total T" over the cleared rooms. */
    static String stats(List<RoomTime> rooms) {
        List<Integer> t = new ArrayList<>();
        String worstRoom = "-";
        int worst = -1;
        for (RoomTime r : rooms) {
            if (!r.cleared()) {
                continue;
            }
            t.add(r.ticks());
            if (r.ticks() > worst) {
                worst = r.ticks();
                worstRoom = r.room();
            }
        }
        long other = rooms.stream().filter(r -> !r.cleared()).count();
        if (t.isEmpty()) {
            return "rooms 0 cleared (" + other + " not)";
        }
        Collections.sort(t);
        double median = t.size() % 2 == 1 ? t.get(t.size() / 2) : (t.get(t.size() / 2 - 1) + t.get(t.size() / 2)) / 2.0;
        int total = t.stream().mapToInt(Integer::intValue).sum();
        // To the last starred death (the kills alone, without the wait for the map's clear), where the line has it.
        List<Integer> d = new ArrayList<>();
        for (RoomTime r : rooms) {
            if (r.cleared() && r.lastDeath() >= 0) {
                d.add(r.lastDeath() + 1);
            }
        }
        Collections.sort(d);
        String deaths = d.isEmpty() ? "" : String.format(Locale.US, "; to the last death median %.1f, worst %d, total %d",
                d.size() % 2 == 1 ? d.get(d.size() / 2) : (d.get(d.size() / 2 - 1) + d.get(d.size() / 2)) / 2.0,
                d.get(d.size() - 1), d.stream().mapToInt(Integer::intValue).sum());
        return String.format(Locale.US, "rooms %d cleared (%d not), median %.1f, mean %.1f, worst %d (%s), total %d ticks%s",
                t.size(), other, median, total / (double) t.size(), worst, worstRoom, total, deaths);
    }

    static String buckets(List<RoomTime> rooms) {
        Map<String, Integer> sum = new LinkedHashMap<>();
        int trips = 0;
        int tripTicks = 0;
        int warps = 0;
        int uses = 0;
        int kills = 0;
        for (RoomTime r : rooms) {
            if (!r.cleared()) {
                continue;
            }
            r.buckets().forEach((k, v) -> sum.merge(k, v, Integer::sum));
            trips += r.trips();
            tripTicks += r.tripTicks();
            warps += r.warps();
            uses += r.uses();
            kills += r.kills();
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "trips %d (%d ticks, %d warp uses), weapon uses %d,"
                + " kills %d", trips, tripTicks, warps, uses, kills));
        sum.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append(", ").append(e.getKey()).append(' ').append(e.getValue()));
        return sb.toString();
    }

    /** AOTV in slot 0 and the Spirit Sceptre in slot 1 (his study run's loadout), a Hyperion in slot 2 when asked. */
    private static void giveLoadout(ClientGameTestContext ctx, boolean hype) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        String[] ids = hype ? new String[]{"ASPECT_OF_THE_VOID", "BAT_WAND", "HYPERION"}
                : new String[]{"ASPECT_OF_THE_VOID", "BAT_WAND"};
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                for (int i = 0; i < ids.length; i++) {
                    inv.setItem(i, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS, "build", new Class<?>[]{String.class},
                            new Object[]{ids[i]}));
                }
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(5);
    }

    private static void leave(ClientGameTestContext ctx) {
        try {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
            ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", false));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 2400);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        } catch (Throwable e) {
            println("leaving the sim threw: " + e);
        }
    }

    private static void println(String s) {
        System.out.println("[" + NAME + "] " + s);
    }
}
