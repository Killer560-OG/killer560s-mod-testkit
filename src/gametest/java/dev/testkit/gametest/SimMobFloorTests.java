package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>1101-sim-mobfloor</b> (mod doc-adm-sim-1101; killer560's Doc item "SIM MOB SPAWNS", and "BLOOD RUSH SPLIT" from the
 * reference mod adm): a generated sim F7 built WITH MOBS ({@code SimMobFloor.setEnabled(true)}, the Map Designer's Mobs
 * button), then Auto Clear (Any Mob Room, Spirit Sceptre + AOTV) on it, then the Blood door. Every step is asserted from
 * what the sim and the mod DID (log lines, server state, the client's world), never from a setting:
 * <ol>
 *   <li><b>floor</b>: the sim planned wither doors and key rooms on the path to Blood ("[Sim] floor with mobs: path ..."),
 *       said Hypixel's "Starting in 1 second." and Mort's line, placed starred mobs ("live mobs placed") and Fels
 *       ({@code SimMobs.felsPlaced() > 0}); the first wither door is coal in the client's world.</li>
 *   <li><b>control</b> (key rooms disarmed meanwhile, so the teleport there is not an insta clear): a right click on that
 *       wither door with no key leaves it shut - no "opened a WITHER door!", the coal still there 40 ticks later.</li>
 *   <li><b>clear</b>: Auto Clear runs until done. Required: at least one key room's last starred mob died and its key
 *       dropped ("Sim key room: X's last starred mob died"), "has obtained Wither Key!", "opened a WITHER door!" AFTER that
 *       key, a Fel woke ("[Sim] Fel woke"), and every mob room on the map cleared (none left uncleared, none given up).</li>
 *   <li><b>blood</b>: a click on the Blood door before the Blood Key is held leaves it shut; with the key picked up (the
 *       test walks him onto it if Auto Clear left it) the click opens it with "The BLOOD DOOR has been opened!", and
 *       Blood Rush Splits (on for the run) shows one named row per rush room and the Average row.</li>
 * </ol>
 * {@code -PmobfloorCode=<map code>} replays a floor; {@code -PmobfloorMinutes} (default 12). Only when named. GrimAC does not
 * apply: the sim's integrated server has no anticheat.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimMobFloorTests implements FabricClientGameTest {

    static final String NAME = "1101-sim-mobfloor";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String SIM_KEY_ROOM = "com.killer560.hub.roomsim.SimKeyRoom";
    private static final String SIM_DOORS = "com.killer560.hub.roomsim.SimDoors";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String AUTO = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String KEYS = "com.killer560.hub.doorkeys.DungeonKeys";
    private static final String RUSH = "com.killer560.hub.splittimers.BloodRushSplits";

    private static final Pattern PLAN = Pattern.compile("\\[Sim\\] floor with mobs: path (.+?); (\\d+) wither door\\(s\\); key rooms (.+?); fairy door (.+?); blood door (.+)$");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank() || Scenario.skip(NAME)) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) { // Auto Clear is cheat build only
            Scenario.skipped(NAME, "Auto Clear is cheat build only");
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            Scenario.skipped(NAME, "legit jar: no Auto Clear");
            return;
        }
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
            Object map = ModUnderTest.config("com.killer560.hub.livemap.LiveMapConfig");
            ModUnderTest.set(map, "setEnabled", true);
            ModUnderTest.set(map, "setInteractiveMapEnabled", true);
            Object splits = ModUnderTest.config("com.killer560.hub.splittimers.SplitTimersConfig");
            ModUnderTest.set(splits, "setEnabled", true);
            Object rush = ModUnderTest.config("com.killer560.hub.splittimers.BloodRushSplitsConfig");
            ModUnderTest.set(rush, "setEnabled", true);
        });
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        List<String> problems = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        try {
            oneFloor(ctx, problems, facts);
        } catch (Throwable t) {
            problems.add("threw: " + t);
            t.printStackTrace(System.out);
        } finally {
            leave(ctx);
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
        }
        for (String f : facts) {
            println("FACT " + f);
        }
        if (!problems.isEmpty()) {
            for (String p : problems) {
                println("PROBLEM " + p);
            }
            throw new AssertionError(problems.size() + " problem(s): " + String.join("; ", problems));
        }
        println("PASS");
    }

    private static void oneFloor(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        int minutes = Integer.getInteger("testkit.mobfloorMinutes", 12);
        if (!buildMobFloor(ctx, problems, facts)) {
            return;
        }
        // ---- control: a keyless click on the first wither door leaves it shut ----
        @SuppressWarnings("unchecked")
        List<Integer> cells = ctx.computeOnClient(mc -> (List<Integer>) ModUnderTest.staticCall(SIM_FLOOR,
                "witherDoorCellsForTest"));
        int door = cells.isEmpty() ? -1 : cells.get(0);
        BlockPos coal = door < 0 ? null : serverBlockAt(ctx, door, "coal_block");
        if (coal == null) {
            problems.add("no shut wither door (coal) found on the server at the planned door cell " + door + " (cells "
                    + cells + "; column " + (door < 0 ? "-" : serverColumn(ctx, door)) + ")");
            return;
        }
        facts.add("first wither door: cell " + door + ", coal at " + coal.toShortString());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_KEY_ROOM, "disarm"));
        Vec3 home = ctx.computeOnClient(mc -> mc.player.position());
        boolean alongX = (door % 11) % 2 != 0;
        BlockPos stand = alongX ? coal.offset(-2, 0, 0) : coal.offset(0, 0, -2);
        teleport(ctx, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        ctx.waitTicks(10);
        long markCtl = LogTap.mark();
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(8);
            Direction face = alongX ? Direction.WEST : Direction.NORTH;
            BlockPos hitPos = coal.above();
            Vec3 hit = Vec3.atCenterOf(hitPos).add(face.getStepX() * 0.5, 0, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, hitPos, false));
        });
        ctx.waitTicks(40);
        boolean stillShut = serverBlockAt(ctx, door, "coal_block") != null;
        boolean openedLine = LogTap.since(markCtl).stream().anyMatch(l -> l.contains("opened a WITHER door!"));
        facts.add("control: keyless click on the wither door - still coal " + stillShut + ", 'opened a WITHER door!' " + openedLine);
        if (!stillShut || openedLine) {
            problems.add("CONTROL FAILED: a wither door opened with no key (coal " + stillShut + ", line " + openedLine + ")");
            return;
        }
        teleport(ctx, home.x, home.y, home.z);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "rearmKeyRoomsForTest", new Class<?>[]{Minecraft.class},
                new Object[]{mc}));
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));

        // ---- Auto Clear over the whole floor ----
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CONFIG), "setEnabled", true));
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        long runMark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "start"));
        int ticks = 0;
        boolean started = false;
        for (; ticks < minutes * 1200; ticks += 5) {
            ctx.waitTicks(5);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                problems.add("the world went away");
                return;
            }
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"));
            started |= busy;
            if (started && !busy) {
                break;
            }
            if (ticks % 1200 == 1195) {
                println(String.format(Locale.US, "t=%ds: %s", (ticks + 5) / 20,
                        ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(AUTO, "status")))));
            }
        }
        if (ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO, "isBusy"))) {
            problems.add(String.format(Locale.US, "Auto Clear still running after %d min", minutes));
            ctx.runOnClient(mc -> ModUnderTest.staticCall(AUTO, "cancel"));
        }
        ctx.waitTicks(20);
        List<String> log = LogTap.since(runMark);
        int dropped = 0;
        int obtained = 0;
        int opened = 0;
        int felWakes = 0;
        int felTrips = 0;
        int cleared = 0;
        boolean openBeforeKey = false;
        String end = "?";
        List<String> gaveUp = new ArrayList<>();
        for (String l : log) {
            if (l.contains("Sim key room:") && l.contains("last starred mob died")) {
                dropped++;
            }
            if (l.contains("[CHAT]") && l.matches(".*\\b[A-Za-z0-9_]{1,16} has obtained Wither Key!.*")) {
                obtained++;
            }
            if (l.contains("[CHAT]") && l.contains("opened a WITHER door!")) {
                opened++;
                openBeforeKey |= obtained < opened;
            }
            if (l.contains("[Sim] Fel woke")) {
                felWakes++;
            }
            if (l.contains("[AutoClear] Fel #") && l.contains("trip to")) {
                felTrips++;
            }
            if (l.contains("[Sim] room cleared:")) {
                cleared++;
            }
            if (l.contains("[AutoClear] gave up") || l.contains("giving up on")) {
                gaveUp.add(l.replaceAll("^.*\\[AutoClear\\] ", ""));
            }
            if (l.contains("[AutoClear] done: ") || l.contains("[AutoClear] stopped: ")) {
                end = l.replaceAll("^.*\\[AutoClear\\] ", "");
            }
            if (l.contains("Sim key room") || l.contains("[Sim] Fel woke") || l.contains("WITHER door")
                    || l.contains("has obtained") || l.contains("[AutoClear] Fel #") || l.contains("[Sim] the Fairy door")) {
                println("  log: " + l.replaceAll("^.*?(\\[AutoClear\\]|\\[Sim\\]|Sim key room|\\[CHAT\\])", "$1"));
            }
        }
        @SuppressWarnings("unchecked")
        List<String> uncleared = ctx.computeOnClient(mc -> unclearedMobRooms());
        facts.add(String.format(Locale.US, "clear: %.1f s; %d sim room clears; keys dropped %d, obtained %d, wither doors opened %d;"
                + " fels woke %d (Auto Clear fel trips %d); ended: %s", ticks / 20.0, cleared, dropped, obtained, opened,
                felWakes, felTrips, end));
        facts.add("mob rooms not cleared: " + (uncleared.isEmpty() ? "none" : String.join(", ", uncleared)));
        if (!gaveUp.isEmpty()) {
            facts.add("Auto Clear gave up: " + String.join(" | ", gaveUp));
        }
        if (dropped < 1 || obtained < 1 || opened < 1) {
            problems.add("keys: dropped " + dropped + ", obtained " + obtained + ", doors opened " + opened
                    + " - a key from a room's mobs never opened a wither door");
        }
        if (openBeforeKey) {
            problems.add("a wither door opened before a key was obtained");
        }
        if (felWakes < 1) {
            problems.add("no Fel woke during the clear");
        }
        if (!uncleared.isEmpty()) {
            problems.add(uncleared.size() + " mob room(s) not cleared: " + String.join(", ", uncleared));
        }

        // ---- the Blood door: shut without the Blood Key, open with it; Blood Rush Splits ----
        bloodDoor(ctx, problems, facts);
    }

    /**
     * Enters the sim, builds a generated F7 WITH MOBS ({@code -PmobfloorCode} replays one), waits for GO and the mobs, and
     * checks the floor (doors and key rooms planned, Hypixel's lines, mobs and Fels placed, sleeping Fels invisible).
     * @return false when a problem was recorded (nothing to test on this floor)
     */
    static boolean buildMobFloor(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        String code = System.getProperty("testkit.mobfloorCode", "");
        ctx.runOnClient(mc -> hookChat());
        CHAT_TIMES.clear();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                new Object[]{"gametest"}));
        ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                new Object[]{true}));
        long mark0 = LogTap.mark();
        long before = Scenario.simBuildCount(ctx);
        if (code.isBlank()) {
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object floor = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate", new Class<?>[]{Minecraft.class, floor.getClass(), int.class,
                        int.class}, new Object[]{mc, floor, 2, 6});
            }));
        } else {
            ctx.runOnClient(mc -> mc.execute(() -> {
                ModUnderTest.staticCall(SIM_STATE, "setFloorLabel", new Class<?>[]{String.class}, new Object[]{"F7"});
                java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimBuilder", "build", new Class<?>[]{Minecraft.class, String.class},
                        new Object[]{c, code});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class,
                        String.class, java.util.function.Consumer.class, String.class},
                        new Object[]{mc, code, build, "Generating Floor 7"});
            }));
        }
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        String mapCode = ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(SIM_STATE, "mapCode")));
        println("MAP CODE: " + mapCode);
        giveLoadout(ctx);
        // The build started the run by itself (SimRun.autoStart): wait for GO and the mobs.
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        String placed = null;
        for (int i = 0; i < 200 && placed == null; i += 5) {
            ctx.waitTicks(5);
            placed = LogTap.since(mark0).stream().filter(l -> l.contains("[Sim] live mobs placed:")).findFirst().orElse(null);
        }
        SimAutoClearTests.awaitFloorChunks(ctx);
        List<String> log0 = LogTap.since(mark0);
        String planLine = log0.stream().filter(l -> l.contains("[Sim] floor with mobs: path")).findFirst().orElse(null);
        boolean starting = log0.stream().anyMatch(l -> l.contains("[CHAT]") && l.trim().endsWith("Starting in 1 second."));
        boolean mort = log0.stream().anyMatch(l -> l.contains("[CHAT]")
                && l.contains("[NPC] Mort: Here, I found this map when I first entered the dungeon."));
        int fels = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SIM_MOBS, "felsPlaced"));
        String felLine = log0.stream().filter(l -> l.contains("[Sim] fels placed:")).findFirst().orElse("(none)");
        @SuppressWarnings("unchecked")
        List<String> keyRooms = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(SIM_FLOOR, "keyRoomsForTest"));
        facts.add("plan: " + (planLine == null ? "NONE" : planLine.replaceAll("^.*\\[Sim\\] ", "")));
        facts.add("mobs: " + (placed == null ? "NONE" : placed.replaceAll("^.*\\[Sim\\] ", "")));
        facts.add("fels: " + fels + " placed (" + felLine.replaceAll("^.*\\[Sim\\] ", "") + ")");
        facts.add("Hypixel lines: Starting in 1 second " + starting + ", Mort " + mort);
        int witherDoors = 0;
        Matcher pm = planLine == null ? null : PLAN.matcher(planLine);
        if (pm != null && pm.find()) {
            witherDoors = Integer.parseInt(pm.group(2));
        }
        if (planLine == null) {
            problems.add("the sim planned no doors or keys (no '[Sim] floor with mobs: path' line)");
        }
        if (witherDoors < 1 || keyRooms.isEmpty()) {
            problems.add("the floor has " + witherDoors + " wither door(s) and " + keyRooms.size() + " key room(s) - nothing to prove");
        }
        if (placed == null) {
            problems.add("no mobs were placed");
        }
        if (fels < 1) {
            problems.add("no Fel was placed");
        }
        if (!starting || !mort) {
            problems.add("Hypixel's start lines missing (Starting in 1 second " + starting + ", Mort " + mort + ")");
        }
        // A sleeping starred Fel is an invisible enderman on the client (what Auto Clear reads); count them at GO. Under a
        // loaded gate the entities can reach the client a few ticks after GO (26.2, 2026-11-04: 0 at GO, the next case's
        // floor 8), so give them up to 5 s to arrive.
        boolean wantSleepers = felLine.matches(".*fels placed: [1-9][0-9]* starred.*");
        // The server only sends an entity to a player inside its tracking range (an enderman: 128 blocks, less on a small view
        // distance) and a floor is 192 blocks across, so a floor whose only starred Fel lies at the far end has nothing for the
        // client to see at GO (felflake-1105: the "flake"). Ask the server where its sleepers are and demand the client sees one
        // only when one is well inside the range; otherwise the server's own sleeper (alive, invisible) is the check.
        double[] serverSleepers = serverSleeperDistances(ctx);
        double nearest = 1e9;
        for (double d : serverSleepers) {
            nearest = Math.min(nearest, d);
        }
        boolean inRange = nearest <= TRACK_SAFE;
        facts.add("server sleeping Fels: " + serverSleepers.length + ", nearest " + (int) nearest + " blocks (client must see one within " + (int) TRACK_SAFE + ")");
        if (wantSleepers && serverSleepers.length < 1) {
            problems.add("starred Fels were placed but the server has no invisible (sleeping) enderman");
        }
        int[] sleepers = new int[3];
        int waited = 0;
        for (; waited <= 100; waited += 10) {
            int[] got = ctx.computeOnClient(mc -> {
                int inv = 0;
                int all = 0;
                int far = 0;
                for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                    if (dev.testkit.compat.McEntities.endermanClass().isInstance(e)) {
                        all++;
                        inv += e.isInvisible() ? 1 : 0;
                        far = Math.max(far, (int) Math.hypot(e.getX() - mc.player.getX(), e.getZ() - mc.player.getZ()));
                    }
                }
                return new int[]{inv, all, far};
            });
            sleepers = got;
            if (!wantSleepers || !inRange || sleepers[0] >= 1) {
                break;
            }
            ctx.waitTicks(10);
        }
        facts.add("client endermen at GO (+" + waited + " ticks): " + sleepers[1] + ", invisible (sleeping Fels) " + sleepers[0] + ", farthest seen " + sleepers[2] + " blocks");
        if (wantSleepers && inRange && sleepers[0] < 1) {
            problems.add("starred Fels were placed but the client sees no invisible (sleeping) enderman");
        }
        return problems.isEmpty();

    }

    /** Blocks inside which the client must see a sleeping Fel (the server's range is 128 at most). */
    private static final double TRACK_SAFE = 64;

    /** Horizontal distance from the player of each alive invisible enderman on the server (a sleeping starred Fel). */
    private static double[] serverSleeperDistances(ClientGameTestContext ctx) {
        AtomicReference<double[]> box = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID id = mc.player.getUUID();
            server.execute(() -> {
                var level = server.overworld();
                var sp = server.getPlayerList().getPlayer(id);
                List<Double> out = new ArrayList<>();
                for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                    if (dev.testkit.compat.McEntities.endermanClass().isInstance(e) && e.isAlive() && e.isInvisible()) {
                        out.add(Math.hypot(e.getX() - sp.getX(), e.getZ() - sp.getZ()));
                    }
                }
                box.set(out.stream().mapToDouble(Double::doubleValue).toArray());
            });
        });
        ctx.waitFor(mc -> box.get() != null, 400);
        return box.get();
    }

    private static void bloodDoor(ClientGameTestContext ctx, List<String> problems, List<String> facts) {
        int blood = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            return (Integer) ModUnderTest.call(layout, "bloodDoor", new Class<?>[0], new Object[0]);
        });
        BlockPos block = blood < 0 ? null : serverBlockAt(ctx, blood, "red_terracotta");
        if (block == null) {
            problems.add("no shut Blood door (red terracotta) found at cell " + blood);
            return;
        }
        boolean alongX = (blood % 11) % 2 != 0;
        // Stand on the side away from Blood: the side nearer the Entrance is where the map has the room before it.
        BlockPos[] sides = alongX ? new BlockPos[]{block.offset(-2, 0, 0), block.offset(2, 0, 0)}
                : new BlockPos[]{block.offset(0, 0, -2), block.offset(0, 0, 2)};
        BlockPos stand = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            for (BlockPos s : sides) {
                int room = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                        new Object[]{s.getX() + 0.5, s.getZ() + 0.5});
                String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{room});
                if (name != null && !name.equalsIgnoreCase("Blood")) {
                    return s;
                }
            }
            return sides[0];
        });
        boolean towardPlus = alongX ? block.getX() > stand.getX() : block.getZ() > stand.getZ();
        Direction face = alongX ? (towardPlus ? Direction.WEST : Direction.EAST) : (towardPlus ? Direction.NORTH : Direction.SOUTH);
        boolean haveKey = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(KEYS, "bloodKey") == 1);
        if (!haveKey) {
            // Control: shut without the key.
            teleport(ctx, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            ctx.waitTicks(10);
            // The Blood Key can lie within pickup range of the door (simmobs2-1101, 26.3 floor b: Golden Oasis's last mob
            // died by the door, the teleport picked it up, and the "keyless" click opened the door): no control then.
            boolean pickedOnTheWay = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(KEYS, "bloodKey") == 1);
            if (pickedOnTheWay) {
                facts.add("blood control skipped: the Blood Key lay within pickup range of the door's stand and was taken");
            }
            long m = LogTap.mark();
            if (!pickedOnTheWay) {
                clickBlock(ctx, block.above(), face);
                ctx.waitTicks(40);
            }
            boolean shut = pickedOnTheWay || serverBlockAt(ctx, blood, "red_terracotta") != null;
            boolean line = LogTap.since(m).stream().anyMatch(l -> l.contains("The BLOOD DOOR has been opened!"));
            facts.add("blood control: keyless click - still shut " + shut + ", line " + line);
            if (!shut || line) {
                problems.add("BLOOD CONTROL FAILED: the Blood door opened with no Blood Key");
                return;
            }
            if (pickedOnTheWay) {
                haveKey = true;
            } else {
                // The Blood Key lies where the blood key room's last mob died: walk onto it (the test, not Auto Clear).
                double[] key = ctx.computeOnClient(mc -> {
                    for (Object k : (List<?>) ModUnderTest.staticCall(KEYS, "dropped", new Class<?>[]{Minecraft.class},
                            new Object[]{mc})) {
                        if ((Boolean) ModUnderTest.call(k, "blood", new Class<?>[0], new Object[0])) {
                            return new double[]{(Double) ModUnderTest.call(k, "x", new Class<?>[0], new Object[0]),
                                    (Double) ModUnderTest.call(k, "y", new Class<?>[0], new Object[0]),
                                    (Double) ModUnderTest.call(k, "z", new Class<?>[0], new Object[0])};
                        }
                    }
                    return null;
                });
                if (key == null) {
                    problems.add("no Blood Key on the ground and none held after the clear");
                    return;
                }
                teleport(ctx, key[0], key[1], key[2]);
                for (int i = 0; i < 60 && !ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(KEYS, "bloodKey") == 1); i++) {
                    ctx.waitTicks(1);
                }
                haveKey = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(KEYS, "bloodKey") == 1);
                facts.add("Blood Key picked up by the test: " + haveKey);
            }
        } else {
            facts.add("Blood Key already held after the clear");
        }
        if (!haveKey) {
            problems.add("the Blood Key could not be picked up");
            return;
        }
        teleport(ctx, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        ctx.waitTicks(10);
        long m = LogTap.mark();
        clickBlock(ctx, block.above(), face);
        boolean line = false;
        for (int i = 0; i < 60 && !line; i += 5) {
            ctx.waitTicks(5);
            line = LogTap.since(m).stream().anyMatch(l -> l.contains("[CHAT]") && l.contains("The BLOOD DOOR has been opened!"));
        }
        ctx.waitTicks(40);
        boolean open = serverBlockAt(ctx, blood, "red_terracotta") == null && serverBlockAt(ctx, blood, "barrier") == null;
        facts.add("blood door with the key: line " + line + ", open " + open);
        if (!line || !open) {
            problems.add("the Blood door did not open with the Blood Key (line " + line + ", open " + open + ")");
        }
        @SuppressWarnings("unchecked")
        List<String> rows = ctx.computeOnClient(mc -> (List<String>) ModUnderTest.staticCall(RUSH, "rowsForTest",
                new Class<?>[]{long.class, String.class}, new Object[]{System.currentTimeMillis(), "COMPACT"}));
        for (String r : rows) {
            println("  rush: " + r.replaceAll("§.", ""));
        }
        long named = rows.stream().filter(r -> r.matches("^§[5d][^.].*: .*")).count();
        boolean average = rows.stream().anyMatch(r -> r.contains("Average"));
        facts.add("Blood Rush Splits: " + rows.size() + " row(s), " + named + " named room row(s), average " + average);
        if (named < 2 || !average) {
            problems.add("Blood Rush Splits showed " + named + " named room row(s) and average " + average);
        }
        rushAgainstChat(rows, problems, facts);
    }

    /**
     * simmobs2-1101 (P4, "Blood Rush Splits checked against a full sim rush"): each room row's time must be the time between
     * the chat lines that open and close it - Mort's line or the "opened a WITHER door!" before it, to the next one or "The
     * BLOOD DOOR has been opened!" - as the test's own chat listener stamped them (client clock), within 0.3 s, and the
     * rows as many as those stretches.
     */
    private static void rushAgainstChat(List<String> rows, List<String> problems, List<String> facts) {
        List<Long> marks = new ArrayList<>();
        for (Object[] c : CHAT_TIMES) {
            String t = ((String) c[1]).replaceAll("§.", "").trim();
            if (marks.isEmpty() ? t.startsWith("[NPC] Mort: Here, I found this map")
                    : t.endsWith("opened a WITHER door!") || t.equals("The BLOOD DOOR has been opened!")) {
                marks.add((Long) c[0]);
                if (t.equals("The BLOOD DOOR has been opened!")) {
                    break;
                }
            }
        }
        List<Double> chat = new ArrayList<>();
        for (int i = 1; i < marks.size(); i++) {
            chat.add((marks.get(i) - marks.get(i - 1)) / 1000.0);
        }
        List<Double> shown = new ArrayList<>();
        Pattern time = Pattern.compile("(?:(\\d+):)?(\\d+\\.\\d+)s");
        for (String r : rows) {
            String t = r.replaceAll("§.", "");
            if (!r.matches("^§[5d][^.].*") || t.contains("Average")) {
                continue;
            }
            Matcher m = time.matcher(t);
            if (m.find()) {
                shown.add((m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 60) + Double.parseDouble(m.group(2)));
            }
        }
        StringBuilder sb = new StringBuilder();
        double worst = 0;
        for (int i = 0; i < Math.max(chat.size(), shown.size()); i++) {
            Double a = i < chat.size() ? chat.get(i) : null;
            Double b = i < shown.size() ? shown.get(i) : null;
            sb.append(String.format(Locale.US, " [%s chat %s / shown %s]", i + 1, a == null ? "-" : String.format(Locale.US,
                    "%.2f", a), b == null ? "-" : String.format(Locale.US, "%.2f", b)));
            if (a != null && b != null) {
                worst = Math.max(worst, Math.abs(a - b));
            }
        }
        facts.add(String.format(Locale.US, "Blood Rush Splits vs the chat lines: %d stretch(es), %d room row(s), worst gap %.2f s:%s",
                chat.size(), shown.size(), worst, sb));
        if (chat.isEmpty()) {
            problems.add("the test's chat listener saw no rush (Mort's line to the Blood door) - nothing to compare");
        } else if (chat.size() != shown.size() || worst > 0.3) {
            problems.add(String.format(Locale.US, "Blood Rush Splits disagree with the rush's chat lines: %d stretch(es) vs"
                    + " %d row(s), worst gap %.2f s", chat.size(), shown.size(), worst));
        }
    }

    /** (client ms, text) of every game chat line since the floor was built, for {@link #rushAgainstChat}. */
    static final List<Object[]> CHAT_TIMES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static boolean chatHooked;

    static void hookChat() {
        if (chatHooked) {
            return;
        }
        chatHooked = true;
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) {
                CHAT_TIMES.add(new Object[]{System.currentTimeMillis(), msg.getString()});
            }
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static int firstWitherDoor(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        for (int idx = 0; idx < 121; idx++) {
            int type = (Integer) ModUnderTest.call(layout, "doorType", new Class<?>[]{int.class}, new Object[]{idx});
            boolean locked = (Boolean) ModUnderTest.call(layout, "isLocked", new Class<?>[]{int.class}, new Object[]{idx});
            if (type == 2 && locked && coalAt(mc, idx) != null) {
                return idx;
            }
        }
        return -1;
    }

    /** The lowest coal block in the door cell's column (the door's floor), or null. */
    private static BlockPos coalAt(Minecraft mc, int idx) {
        return blockAt(mc, idx, "coal_block");
    }

    /** By registry id: a color-variant block constant differs between 26.2 and 26.3 (testkit CLAUDE.md). */
    private static BlockPos terracottaAt(Minecraft mc, int idx) {
        return blockAt(mc, idx, "red_terracotta");
    }

    private static BlockPos blockAt(Minecraft mc, int idx, String id) {
        BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
        for (int y = c.getY() - 10; y <= c.getY() + 10; y++) {
            BlockPos p = new BlockPos(c.getX(), y, c.getZ());
            if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath()
                    .equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** The lowest block with registry path {@code id} in the door cell's center column, read on the integrated server. */
    static BlockPos serverBlockAt(ClientGameTestContext ctx, int idx, String id) {
        AtomicReference<Object> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                for (int y = c.getY() - 12; y <= c.getY() + 12; y++) {
                    BlockPos p = new BlockPos(c.getX(), y, c.getZ());
                    if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock())
                            .getPath().equals(id)) {
                        out.set(p);
                        return;
                    }
                }
                out.set("none");
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get() instanceof BlockPos b ? b : null;
    }

    /** The cell's center column as block ids, bottom to top, for a failure message. */
    private static String serverColumn(ClientGameTestContext ctx, int idx) {
        AtomicReference<String> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            BlockPos c = (BlockPos) ModUnderTest.staticCall(LAYOUT, "cellCenter", new Class<?>[]{int.class}, new Object[]{idx});
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                StringBuilder sb = new StringBuilder(c.toShortString() + ":");
                for (int y = c.getY() - 6; y <= c.getY() + 6; y++) {
                    sb.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                            server.overworld().getBlockState(new BlockPos(c.getX(), y, c.getZ())).getBlock()).getPath());
                }
                out.set(sb.toString());
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get();
    }

    static List<String> unclearedMobRooms() {
        List<String> out = new ArrayList<>();
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[0], new Object[0]);
        for (int r = 0; r < n; r++) {
            boolean mob = (Boolean) ModUnderTest.staticCall(AUTO, "isMobRoom", new Class<?>[]{layout.getClass(), int.class},
                    new Object[]{layout, r});
            if (!mob) {
                continue;
            }
            String name = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
            boolean cleared = (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRoomState", "isCleared",
                    new Class<?>[]{String.class}, new Object[]{name});
            if (!cleared) {
                out.add(name);
            }
        }
        return out;
    }

    static void clickBlock(ClientGameTestContext ctx, BlockPos pos, Direction face) {
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(8);
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, 0, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        });
    }

    static void teleport(ClientGameTestContext ctx, double x, double y, double z) {
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.teleportTo(x, y, z);
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(3);
    }

    /** AOTV in slot 0, the Spirit Sceptre in slot 1, slot 8 left empty for the door clicks. */
    static void giveLoadout(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        String[] ids = {"ASPECT_OF_THE_VOID", "BAT_WAND"};
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

    static void leave(ClientGameTestContext ctx) {
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
