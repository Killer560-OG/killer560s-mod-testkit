package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auto Secret's insta-clear recorder ({@code com.killer560.hub.autosecret.InstaClearTracker}).
 *
 * <p>Two cases, both selected by {@code -Pscenario=insta-clear}:
 * <ul>
 *   <li><b>361-logic-insta-clear</b> - the "known" rule and the evidence file, fed fake observations: N successes and
 *       no failure, a single failure disqualifying, not-counted outcomes ignored, the threshold, persistence across a
 *       reload, the readout, and an unreadable file never being overwritten.</li>
 *   <li><b>98-sim-insta-clear</b> - the recorder on a real sim floor: walk into one room and teleport into others that
 *       hold real sim starred mobs, then check each entry was recorded with a sane key and the right outcome, and was
 *       persisted. The sim has NO dungeon map item and does not implement Hypixel's insta-clear rule, so the map
 *       state is forced through the recorder's test hook - this measures the recorder (entry detection, keys,
 *       starred-stand counting, kill counting, outcome rules, file), never whether Hypixel clears a room.</li>
 * </ul>
 */
public class InstaClearTests implements FabricClientGameTest {

    private static final String TRACKER = "com.killer560.hub.autosecret.InstaClearTracker";
    private static final String LAYOUT = "com.killer560.hub.livemap.DungeonLayout";
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_MOBS = "com.killer560.hub.roomsim.SimMobs";
    private static final String SOURCE_ROOMS =
            ModUnderTest.instanceConfig("C:/Users/Hunter/AppData/Roaming/PrismLauncher/instances/26.1.2 (Mod Only Test)"
                    + "/minecraft/config", "killer560smod-rooms");

    // DungeonMapScanner's states, mirrored as LiveMapFeature.MAP_*.
    private static final int MAP_CLEARED = 1;
    private static final int MAP_DISCOVERED = 2;
    private static final int MAP_UNOPENED = 4;
    private static final int GRID = 11;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Each case runs even when one before it failed, so a run on an old jar shows which cases the fix is for.
        List<String> failed = new ArrayList<>();
        runCase(ctx, "361-logic-insta-clear", InstaClearTests::logicCase, failed);
        runCase(ctx, "361-logic-insta-clear-v2", InstaClearTests::logicV2Case, failed);
        // Not by skip("98-sim-insta-clear"): that name is contained in the -live case's, so the filter
        // "98-sim-insta-clear-live" would not select it, but "98-sim-insta-clear" selects both.
        runCase(ctx, "98-sim-insta-clear", InstaClearTests::simCase, failed);
        runCase(ctx, "98-sim-insta-clear-live", InstaClearTests::liveCase, failed);
        if (!failed.isEmpty()) {
            throw new AssertionError(failed.size() + " insta-clear case(s) failed:\n" + String.join("\n", failed));
        }
    }

    private static void runCase(ClientGameTestContext ctx, String name,
                                java.util.function.Consumer<ClientGameTestContext> body, List<String> failed) {
        if (Scenario.skip(name)) {
            return;
        }
        ModUnderTest.require("killer560smod");
        try {
            body.accept(ctx);
        } catch (Throwable t) {
            System.out.println("[" + name + "] FAIL - " + t);
            failed.add("[" + name + "] " + t.getMessage());
        }
    }

    // ================================================================================================ logic

    private static void logicCase(ClientGameTestContext ctx) {
        List<String> problems = new ArrayList<>();
        Path dir;
        try {
            dir = Files.createTempDirectory("insta-clear-logic");
        } catch (Exception e) {
            throw new AssertionError("no temp dir", e);
        }
        String file = dir.resolve("insta-clear.json").toString();
        try {
            ctx.runOnClient(mc -> {
                call("testUseFile", new Class<?>[]{String.class}, file);
                call("testClearAll", new Class<?>[]{});
                call("testSetMinSuccesses", new Class<?>[]{int.class}, 2);
            });
            String r = "Logic Room";
            String k1 = "from=-1,0;land=10,70,12";
            String k2 = "from=0,-1;land=5,70,5";
            String k3 = "from=-2,0;land=7,71,3";
            check(problems, "default threshold is 2", minSuccesses(ctx) == 2);
            ctx.runOnClient(mc -> add(r, k1, "INSTA", 400));
            check(problems, "1 success is not known", !known(ctx, r, k1));
            ctx.runOnClient(mc -> add(r, k1, "INSTA", 600));
            check(problems, "2 successes, 0 failures is known", known(ctx, r, k1));
            check(problems, "knownEntries lists it", knownEntries(ctx, r).contains(k1));
            ctx.runOnClient(mc -> {
                add(r, k1, "NO_STARS", -1);
                add(r, k1, "NO_MAP", -1);
                add(r, k1, "UNOBSERVED", 900);
            });
            check(problems, "not-counted outcomes do not disqualify", known(ctx, r, k1));
            int[] c1 = counts(ctx, r, k1);
            check(problems, "counts 2/0/3 for k1 (got " + c1[0] + "/" + c1[1] + "/" + c1[2] + ")",
                    c1[0] == 2 && c1[1] == 0 && c1[2] == 3);
            ctx.runOnClient(mc -> {
                for (int i = 0; i < 3; i++) {
                    add(r, k2, "INSTA", 500);
                }
                add(r, k2, "KILLED", 3000);
                add(r, k3, "NO_CLEAR", -1);
                for (int i = 0; i < 5; i++) {
                    add(r, k3, "INSTA", 500);
                }
            });
            check(problems, "3 successes + 1 KILLED is not known", !known(ctx, r, k2));
            check(problems, "1 NO_CLEAR + 5 successes is not known", !known(ctx, r, k3));
            check(problems, "knownEntries holds only k1", knownEntries(ctx, r).equals(List.of(k1)));
            ctx.runOnClient(mc -> call("testSetMinSuccesses", new Class<?>[]{int.class}, 3));
            check(problems, "threshold 3: 2 successes no longer known", !known(ctx, r, k1));
            ctx.runOnClient(mc -> call("testSetMinSuccesses", new Class<?>[]{int.class}, 2));
            check(problems, "unknown room is not known", !known(ctx, "No Such Room", k1));
            check(problems, "null key is not known", !known(ctx, r, null));
            check(problems, "unknown room has no entries", knownEntries(ctx, "No Such Room").isEmpty());

            // A room whose only successes are k-alone: median of 400 and 600.
            String r2 = "Median Room";
            ctx.runOnClient(mc -> {
                add(r2, k1, "INSTA", 400);
                add(r2, k1, "INSTA", 600);
                add(r2, k2, "KILLED", 9000);
            });
            List<String> lines = summary(ctx);
            for (String line : lines) {
                System.out.println("[361-logic-insta-clear] readout: " + line);
            }
            check(problems, "readout has a line for Median Room with 2 insta / 1 fail and median 500 ms",
                    lines.stream().anyMatch(l -> l.startsWith("Median Room:") && l.contains("2 insta / 1 fail")
                            && l.contains("median 500 ms") && l.contains("known 1")));

            // Persistence: the file holds it, and a reload (a restart) gives back the same answers.
            ctx.runOnClient(mc -> call("testSetMinSuccesses", new Class<?>[]{int.class}, 4));
            int sizeBefore = size(ctx);
            ctx.runOnClient(mc -> call("testReload", new Class<?>[]{}));
            check(problems, "reload keeps every observation (" + sizeBefore + " -> " + size(ctx) + ")",
                    size(ctx) == sizeBefore && sizeBefore == 18);
            check(problems, "reload keeps the threshold 4", minSuccesses(ctx) == 4);
            ctx.runOnClient(mc -> call("testSetMinSuccesses", new Class<?>[]{int.class}, 2));
            ctx.runOnClient(mc -> call("testReload", new Class<?>[]{}));
            check(problems, "after reload k1 is known again at threshold 2", known(ctx, r, k1));
            String text = Files.readString(Path.of(file));
            check(problems, "file is JSON with rooms and settings", text.contains("\"rooms\"")
                    && text.contains("\"minSuccesses\": 2") && text.contains(k1));

            // An unreadable file is never overwritten.
            Path bad = dir.resolve("bad.json");
            Files.writeString(bad, "{ this is not json");
            ctx.runOnClient(mc -> call("testUseFile", new Class<?>[]{String.class}, bad.toString()));
            check(problems, "unreadable file loads as empty", size(ctx) == 0);
            ctx.runOnClient(mc -> add(r, k1, "INSTA", 100));
            check(problems, "unreadable file left untouched after a write",
                    Files.readString(bad).equals("{ this is not json"));
            String redirected = ctx.computeOnClient(mc -> (String) call("testFilePath", new Class<?>[]{}));
            check(problems, "writes went to a sibling (" + redirected + ")",
                    redirected.contains("insta-clear.unreadable-") && Files.exists(Path.of(redirected)));

            // No dungeon, no key.
            String noKey = ctx.computeOnClient(mc -> (String) call("entryKeyFor",
                    new Class<?>[]{String.class, BlockPos.class, String.class}, r, BlockPos.ZERO, "Other"));
            check(problems, "entryKeyFor outside a dungeon is null (got " + noKey + ")", noKey == null);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            ctx.runOnClient(mc -> call("testUseFile", new Class<?>[]{String.class}, (Object) null));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("insta-clear logic:\n    " + String.join("\n    ", problems));
        }
        System.out.println("[361-logic-insta-clear] PASS - rule, threshold, readout, persistence and bad-file safety");
    }

    // ================================================================================================ logic, v2

    /**
     * The version 2 file (mod 2026-10-06): a version 1 file's failures were mostly Interactive Map pass-throughs (his
     * live F7 runs), so on load they become LEGACY (not counted), the Entrance is dropped, INSTA and manual verdicts
     * stay, the original file is copied aside unchanged, and PASS_THROUGH never disqualifies an entry.
     */
    private static void logicV2Case(ClientGameTestContext ctx) {
        String name = "361-logic-insta-clear-v2";
        List<String> problems = new ArrayList<>();
        Path dir;
        try {
            dir = Files.createTempDirectory("insta-clear-v2");
        } catch (Exception e) {
            throw new AssertionError("no temp dir", e);
        }
        Path file = dir.resolve("insta-clear.json");
        String kFlags = "from=0,2;land=11,69,43";
        String kMuseum = "from=2,0;land=47,70,14";
        String kSlime = "from=1,1;land=81,71,13";
        String kMage = "from=0,2;land=14,70,20";
        String kDuncan = "from=0,1;land=15,68,27";
        // The shape of his 2026-10-06 file (version 1), cut down: one of each kind of record.
        String v1 = "{\n  \"version\": 1,\n  \"settings\": {\"minSuccesses\": 2, \"windowSeconds\": 15},\n  \"rooms\": {\n"
                + "    \"Entrance\": {\"from=?;land=walk\": [" + obs(1, "walk", "manual", "NO_CLEAR", null) + "]},\n"
                + "    \"Flags\": {\"" + kFlags + "\": [" + obs(2, "etherwarp", "interactivemap", "KILLED", null) + "]},\n"
                + "    \"Museum\": {\"" + kMuseum + "\": [" + obs(3, "teleport", "manual", "NO_CLEAR", null) + "]},\n"
                + "    \"Slime\": {\"" + kSlime + "\": [" + obs(4, "teleport", "manual", "INSTA", null) + ", "
                + obs(5, "teleport", "manual", "INSTA", null) + "]},\n"
                + "    \"Mage\": {\"" + kMage + "\": [" + obs(6, "teleport", "manual", "INSTA", null) + ", "
                + obs(7, "teleport", "manual", "INSTA", "NOT_INSTA") + "]},\n"
                + "    \"Duncan\": {\"" + kDuncan + "\": [" + obs(8, "etherwarp", "interactivemap", "NO_STARS", null) + "]}\n"
                + "  }\n}\n";
        try {
            Files.writeString(file, v1);
            ctx.runOnClient(mc -> call("testUseFile", new Class<?>[]{String.class}, file.toString()));
            List<Path> asides = asides(dir);
            System.out.println("[" + name + "] after loading the v1 file: " + asides.size() + " copy(ies) aside "
                    + asides);
            check(problems, "the v1 file was copied aside once (" + asides.size() + ")", asides.size() == 1);
            check(problems, "the copy aside is the v1 file byte for byte",
                    asides.size() == 1 && Files.readString(asides.get(0)).equals(v1));
            String now = Files.readString(file);
            check(problems, "the file itself is now version 2", now.contains("\"version\": 2"));
            int[] flags = counts(ctx, "Flags", kFlags);
            int[] museum = counts(ctx, "Museum", kMuseum);
            check(problems, "v1 KILLED (Flags) no longer counts as a failure (" + c(flags) + ")",
                    flags[0] == 0 && flags[1] == 0 && flags[2] == 1);
            check(problems, "v1 NO_CLEAR (Museum) no longer counts as a failure (" + c(museum) + ")",
                    museum[0] == 0 && museum[1] == 0 && museum[2] == 1);
            String lastFlags = lastFor(ctx, "Flags");
            check(problems, "Flags kept as LEGACY with its v1 outcome (" + lastFlags + ")", lastFlags != null
                    && lastFlags.contains("\"outcome\":\"LEGACY\"") && lastFlags.contains("\"v1Outcome\":\"KILLED\""));
            check(problems, "Slime's two INSTA kept: known", known(ctx, "Slime", kSlime));
            int[] mage = counts(ctx, "Mage", kMage);
            check(problems, "a manual NOT_INSTA verdict still counts as a failure (Mage " + c(mage) + ")",
                    mage[0] == 1 && mage[1] == 1 && !known(ctx, "Mage", kMage));
            check(problems, "the Entrance's entries are dropped", counts(ctx, "Entrance", "from=?;land=walk")[2] == 0
                    && lastFor(ctx, "Entrance") == null);
            check(problems, "7 observations left of 8, the Entrance's gone (" + size(ctx) + ")", size(ctx) == 7);
            // A reload of the (now version 2) file migrates nothing and copies nothing again.
            ctx.runOnClient(mc -> call("testReload", new Class<?>[]{}));
            check(problems, "a reload copies nothing aside again (" + asides(dir).size() + ")", asides(dir).size() == 1);
            check(problems, "a reload keeps Flags LEGACY (" + c(counts(ctx, "Flags", kFlags)) + ")",
                    counts(ctx, "Flags", kFlags)[1] == 0);
            // PASS_THROUGH is recorded but never a failure.
            ctx.runOnClient(mc -> {
                add("Slime", kSlime, "PASS_THROUGH", -1);
                add("Slime", kSlime, "PASS_THROUGH", -1);
            });
            int[] slime = counts(ctx, "Slime", kSlime);
            check(problems, "PASS_THROUGH is not counted and does not disqualify (Slime " + c(slime) + ")",
                    slime[0] == 2 && slime[1] == 0 && slime[2] == 2 && known(ctx, "Slime", kSlime));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            ctx.runOnClient(mc -> call("testUseFile", new Class<?>[]{String.class}, (Object) null));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("insta-clear v2:\n    " + String.join("\n    ", problems));
        }
        System.out.println("[" + name + "] PASS - v1 failures LEGACY, Entrance dropped, INSTA and verdicts kept, the"
                + " original copied aside once, PASS_THROUGH not counted");
    }

    private static String obs(int t, String method, String driver, String outcome, String verdict) {
        return "{\"t\": " + (1791332900000L + t * 1000L) + ", \"floor\": \"F7\", \"method\": \"" + method
                + "\", \"driver\": \"" + driver + "\", \"travel\": 30.0, \"skip\": 0, \"stars\": 3, \"kills\": 0,"
                + " \"aliveAtFlip\": -1, \"flipMs\": " + (outcome.equals("NO_STARS") ? 197 : -1) + ", \"outcome\": \""
                + outcome + "\"" + (verdict == null ? "" : ", \"verdict\": \"" + verdict + "\"") + "}";
    }

    private static List<Path> asides(Path dir) throws java.io.IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("insta-clear.v1-")).toList();
        }
    }

    private static String c(int[] counts) {
        return counts[0] + " ok / " + counts[1] + " fail / " + counts[2] + " other";
    }

    // ================================================================================================ sim

    private static void simCase(ClientGameTestContext ctx) {
        String name = "98-sim-insta-clear";
        List<String> problems = new ArrayList<>();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(), "killer560smod-roomdata");
        Scenario.ensureRoomDatabase(ctx);
        Path dir;
        try {
            dir = Files.createTempDirectory("insta-clear-sim");
        } catch (Exception e) {
            throw new AssertionError("no temp dir", e);
        }
        String file = dir.resolve("insta-clear.json").toString();
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            call("testUseFile", new Class<?>[]{String.class}, file);
            call("testClearAll", new Class<?>[]{});
            call("testClearForcedStates", new Class<?>[]{});
            call("testSetMinSuccesses", new Class<?>[]{int.class}, 2);
            call("testSetWindowSeconds", new Class<?>[]{int.class}, 6);
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimFloorGen$Floor", "F7");
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        try {
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(60);
            waitForFloor(ctx, name);

            // ---- pick rooms off the mod's own published layout
            List<Room> rooms = ctx.computeOnClient(mc -> rooms());
            System.out.println("[" + name + "] " + rooms.size() + " identified room(s) on the floor");
            if (rooms.size() < 5) {
                throw new AssertionError("fewer than 5 identified rooms - nothing below would mean anything");
            }
            // A and B share a normal door (walk case); C, D are other rooms; E is two tiles or more from A.
            Room a = null;
            Room b = null;
            int door = -1;
            outer:
            for (Room x : rooms) {
                for (Room y : rooms) {
                    if (x == y) {
                        continue;
                    }
                    int d = ctx.computeOnClient(mc -> normalDoorBetween(x, y));
                    if (d >= 0) {
                        a = x;
                        b = y;
                        door = d;
                        break outer;
                    }
                }
            }
            if (a == null) {
                throw new AssertionError("no two rooms share a normal door");
            }
            final Room ra = a;
            final Room rb = b;
            List<Room> others = new ArrayList<>();
            Room farRoom = null;
            for (Room x : rooms) {
                if (x == ra || x == rb) {
                    continue;
                }
                if (farRoom == null && tileGap(ra, x) >= 2) {
                    farRoom = x;
                } else if (others.size() < 2) {
                    others.add(x);
                }
            }
            final Room re = farRoom;
            if (re == null || others.size() < 2) {
                throw new AssertionError("could not pick rooms C, D and a far room E");
            }
            Room rc = others.get(0);
            Room rd = others.get(1);
            System.out.println("[" + name + "] A=" + ra.name + " B=" + rb.name + " (door cell " + door + ") C=" + rc.name
                    + " D=" + rd.name + " E=" + re.name + " (gap " + tileGap(ra, re) + " tiles from A)");

            // ---- real sim starred mobs in B, C, D, E (two each, off the landing column)
            for (Room x : new Room[]{rb, rc, rd, re}) {
                int n = 0;
                for (int[] off : new int[][]{{5, 0}, {-5, 0}, {0, 5}, {0, -5}}) {
                    if (n >= 2) {
                        break;
                    }
                    BlockPos stand = standable(ctx, x.tiles[0], off[0], off[1]);
                    if (stand != null) {
                        ctx.runOnClient(mc -> {
                            Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                            ModUnderTest.staticCall(SIM_MOBS, "spawnStarred",
                                    new Class<?>[]{Minecraft.class, BlockPos.class, k.getClass()},
                                    new Object[]{mc, stand, k});
                        });
                        n++;
                    }
                }
                System.out.println("[" + name + "] spawned " + n + " starred zombie(s) in " + x.name);
            }
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> {
                force(ra.name, MAP_DISCOVERED);
                for (Room x : new Room[]{rb, rc, rd, re}) {
                    force(x.name, MAP_UNOPENED);
                }
            });

            // ---- 1. WALK from A into B through their door; B flips while its stars stand -> INSTA
            Vec3 doorAt = ctx.computeOnClient(mc -> (Vec3) ModUnderTest.staticCall(LAYOUT, "doorCentre",
                    new Class<?>[]{int.class}, new Object[]{doorOf(ra, rb)}));
            // Unit step from A's tile towards B's tile.
            int[] ta = ra.tileFacing(rb);
            int[] tb = rb.tileFacing(ra);
            int sx = Integer.signum(tb[0] - ta[0]);
            int sz = Integer.signum(tb[1] - ta[1]);
            BlockPos startCol = BlockPos.containing(doorAt.x - sx * 6, doorAt.y, doorAt.z - sz * 6);
            String placed = placeAt(ctx, startCol.getX(), startCol.getZ());
            System.out.println("[" + name + "] walk start, door at " + doorAt + ": " + placed);
            ctx.waitTicks(20);
            String inA = ctx.computeOnClient(InstaClearTests::roomUnderPlayer);
            check(problems, "standing in A before the walk (in " + inA + ")", ra.name.equals(inA));
            float yaw = sx > 0 ? -90f : sx < 0 ? 90f : sz > 0 ? 0f : 180f;
            ctx.runOnClient(mc -> {
                mc.player.setYRot(yaw);
                mc.player.setXRot(0f);
            });
            Vec3 walkFrom = ctx.computeOnClient(mc -> mc.player.position());
            ctx.getInput().holdKey(options -> options.keyUp);
            String inB = null;
            for (int i = 0; i < 80; i++) {
                ctx.waitTick();
                inB = ctx.computeOnClient(InstaClearTests::roomUnderPlayer);
                if (rb.name.equals(inB)) {
                    break;
                }
            }
            ctx.waitTicks(6);
            ctx.getInput().releaseKey(options -> options.keyUp);
            double walked = ctx.computeOnClient(mc -> mc.player.position().distanceTo(walkFrom));
            System.out.println("[" + name + "] walked " + String.format("%.1f", walked) + " blocks, now in " + inB);
            ctx.waitTicks(10);
            List<String> pending = pending(ctx);
            System.out.println("[" + name + "] open after the walk: " + pending);
            String bLine = pending.stream().filter(p -> p.startsWith(rb.name + "|")).findFirst().orElse(null);
            if (walked < 3) {
                problems.add("the walk moved only " + String.format("%.1f", walked) + " blocks - the harness never "
                        + "walked, so the walk case measured nothing");
            } else if (!rb.name.equals(inB)) {
                problems.add("walked " + String.format("%.1f", walked) + " blocks but never reached B (" + inB + ")");
            } else if (bLine == null) {
                problems.add("walking into B opened no observation");
            } else {
                String[] f = bLine.split("\\|");
                check(problems, "walk key is from=<fx>,<fz>;land=walk (got " + f[1] + ")",
                        f[1].matches("from=-?\\d+,-?\\d+;land=walk"));
                check(problems, "walk method is walk (got " + f[2] + ")", f[2].equals("walk"));
                check(problems, "B's starred stands were seen (got " + f[3] + ")", Integer.parseInt(f[3]) >= 1);
            }
            ctx.runOnClient(mc -> force(rb.name, MAP_CLEARED));
            ctx.waitTicks(10);
            String lastB = lastFor(ctx, rb.name);
            System.out.println("[" + name + "] B closed: " + lastB);
            check(problems, "B closed as INSTA with stars alive at the flip",
                    lastB != null && lastB.startsWith(rb.name + "|") && lastB.contains("\"outcome\":\"INSTA\"")
                            && !lastB.contains("\"aliveAtFlip\":0") && !lastB.contains("\"aliveAtFlip\":-1")
                            && lastB.contains("\"method\":\"walk\"") && lastB.contains("\"from\":\"" + ra.name + "\"")
                            && lastB.contains("\"skip\":0"));

            // ---- 2. TELEPORT A -> C twice onto the same block: two INSTA on one key -> known; key round-trips
            String keyC1 = null;
            for (int run = 1; run <= 2; run++) {
                placeAt(ctx, centreX(ra), centreZ(ra));
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> force(rc.name, MAP_UNOPENED));
                String landedC = placeAt(ctx, centreX(rc) + 2, centreZ(rc) + 2);
                ctx.waitTicks(10);
                String bp = ctx.computeOnClient(mc -> mc.player.blockPosition().below().toShortString());
                String cLine = pending(ctx).stream().filter(p -> p.startsWith(rc.name + "|")).findFirst().orElse(null);
                System.out.println("[" + name + "] C run " + run + ": " + landedC + ", open " + cLine);
                if (cLine == null) {
                    problems.add("teleport into C (run " + run + ") opened no observation");
                    continue;
                }
                String key = cLine.split("\\|")[1];
                check(problems, "C key is from=<adjacent or skip>;land=x,y,z (" + key + ")",
                        key.matches("from=-?\\d+,-?\\d+;land=-?\\d+,-?\\d+,-?\\d+"));
                check(problems, "C method is teleport (" + cLine.split("\\|")[2] + ")",
                        cLine.split("\\|")[2].equals("teleport"));
                String back = ctx.computeOnClient(mc -> {
                    BlockPos p = (BlockPos) call("realLandingFor", new Class<?>[]{String.class, String.class},
                            rc.name, key);
                    return p == null ? "null" : p.toShortString();
                });
                check(problems, "realLandingFor(C, key) = the block he stands on (" + back + " vs " + bp + ")",
                        back.equals(bp));
                String recomputed = ctx.computeOnClient(mc -> (String) call("entryKeyFor",
                        new Class<?>[]{String.class, BlockPos.class, String.class}, rc.name,
                        mc.player.blockPosition().below(), ra.name));
                check(problems, "entryKeyFor(C, landing, A) equals the recorded key (" + recomputed + ")",
                        key.equals(recomputed));
                if (run == 1) {
                    keyC1 = key;
                } else {
                    check(problems, "the second entry on the same block has the same key", key.equals(keyC1));
                }
                ctx.runOnClient(mc -> force(rc.name, MAP_CLEARED));
                ctx.waitTicks(10);
                String lc = lastFor(ctx, rc.name);
                check(problems, "C run " + run + " closed INSTA (" + lc + ")",
                        lc != null && lc.startsWith(rc.name + "|") && lc.contains("\"outcome\":\"INSTA\""));
                // Out of C so the next run is a fresh entry.
                placeAt(ctx, centreX(ra), centreZ(ra));
                ctx.waitTicks(10);
            }
            final String kc = keyC1;
            boolean knownC = ctx.computeOnClient(mc -> (Boolean) call("knownToInstaClear",
                    new Class<?>[]{String.class, String.class}, rc.name, kc));
            check(problems, "two real INSTA entries on one key make C known", knownC);
            @SuppressWarnings("unchecked")
            List<String> knownList = ctx.computeOnClient(mc -> (List<String>) call("knownEntries",
                    new Class<?>[]{String.class}, rc.name));
            check(problems, "knownEntries(C) = [that key] (" + knownList + ")", knownList.equals(List.of(kc)));

            // ---- 3. TELEPORT into D, kill its stars, THEN flip -> KILLED (a real clear, a failure)
            ctx.runOnClient(mc -> force(rd.name, MAP_UNOPENED));
            placeAt(ctx, centreX(rd) + 1, centreZ(rd) + 1);
            ctx.waitTicks(20);
            String dLine = pending(ctx).stream().filter(p -> p.startsWith(rd.name + "|")).findFirst().orElse(null);
            System.out.println("[" + name + "] D open: " + dLine);
            int killed = killStarredNear(ctx, rd);
            System.out.println("[" + name + "] discarded " + killed + " starred pair(s) in D");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> force(rd.name, MAP_CLEARED));
            ctx.waitTicks(10);
            String ld = lastFor(ctx, rd.name);
            System.out.println("[" + name + "] D closed: " + ld);
            check(problems, "D: stars killed before the flip closes KILLED with kills > 0",
                    killed > 0 && ld != null && ld.startsWith(rd.name + "|") && ld.contains("\"outcome\":\"KILLED\"")
                            && !ld.contains("\"kills\":0") && ld.contains("\"aliveAtFlip\":0"));

            // ---- 4. TELEPORT A -> E (a room or more skipped), never flips -> NO_CLEAR after the 6 s window
            placeAt(ctx, centreX(ra), centreZ(ra));
            ctx.waitTicks(10);
            placeAt(ctx, centreX(re) - 2, centreZ(re) - 2);
            ctx.waitTicks(10);
            String eLine = pending(ctx).stream().filter(p -> p.startsWith(re.name + "|")).findFirst().orElse(null);
            System.out.println("[" + name + "] E open: " + eLine);
            check(problems, "E opened an observation: " + eLine, eLine != null);
            ctx.waitTicks(150);
            String le = lastFor(ctx, re.name);
            System.out.println("[" + name + "] E closed: " + le);
            check(problems, "E closed NO_CLEAR after the window with skip >= 1",
                    le != null && le.startsWith(re.name + "|") && le.contains("\"outcome\":\"NO_CLEAR\"")
                            && !le.contains("\"skip\":0") && !le.contains("\"skip\":-1"));

            // ---- 5. Persisted: reload from disk and the evidence is all there.
            int size = ctx.computeOnClient(mc -> (Integer) call("testSize", new Class<?>[]{}));
            ctx.runOnClient(mc -> call("testReload", new Class<?>[]{}));
            int reloaded = ctx.computeOnClient(mc -> (Integer) call("testSize", new Class<?>[]{}));
            String text = Files.readString(Path.of(file));
            System.out.println("[" + name + "] file " + file + ": " + text.length() + " chars, " + reloaded
                    + " observation(s) after reload (" + size + " before)");
            check(problems, "reload gives back every observation (" + size + " -> " + reloaded + ")",
                    reloaded == size && size >= 5);
            check(problems, "file names B, C, D and E", text.contains(rb.name) && text.contains(rc.name)
                    && text.contains(rd.name) && text.contains(re.name));
            boolean knownAfter = ctx.computeOnClient(mc -> (Boolean) call("knownToInstaClear",
                    new Class<?>[]{String.class, String.class}, rc.name, kc));
            check(problems, "C still known after the reload", knownAfter);
            for (String l : summary(ctx)) {
                System.out.println("[" + name + "] readout: " + l);
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            ctx.getInput().releaseKey(options -> options.keyUp);
            ctx.runOnClient(mc -> {
                call("testClearForcedStates", new Class<?>[]{});
                call("testUseFile", new Class<?>[]{String.class}, (Object) null);
                mc.options.renderDistance().set(renderBefore);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("insta-clear sim:\n    " + String.join("\n    ", problems));
        }
        System.out.println("[" + name + "] PASS - walk and teleport entries recorded with sane keys, outcomes INSTA /"
                + " KILLED / NO_CLEAR as the rules say, known after two real successes, persisted");
    }

    // ================================================================================================ sim, live F7 cases

    private static final String LIVE_MAP_CONFIG = "com.killer560.hub.livemap.LiveMapConfig";
    private static final String CLEAR_UTILS = "com.killer560.hub.livemap.autoclear.AutoClearUtils";
    private static final String EXECUTOR = "com.killer560.hub.livemap.autoclear.ClearExecutor";
    private static final java.util.regex.Pattern WARM =
            java.util.regex.Pattern.compile("\\[Path\\] (?:quick )?floor graph warm: (\\d+) node");

    /**
     * His live F7 runs of 2026-10-06, travelling only with the Interactive Map, rebuilt on a sim floor (map states
     * forced, as in 98-sim-insta-clear; the paths, landings and mobs are real):
     * <ul>
     *   <li><b>Entrance</b> - walking/teleporting into it records nothing.</li>
     *   <li><b>Duncan</b> - a room that flips ~200 ms after he lands, before any of its mobs is in sight, and that he
     *       leaves at once: once its starred mobs come into view (spawned after the flip here) it is INSTA, not
     *       NO_STARS. Never seen at all, it is UNOBSERVED; standing in it with none, NO_STARS (control).</li>
     *   <li><b>Mage / Hall</b> - a map path's own landing in its destination, flipped with stars up, is INSTA as
     *       "etherwarp by interactivemap", not "teleport by manual".</li>
     *   <li><b>Duncan mid-path</b> - every room a map path lands in on the way, flipped on entry before its mobs are
     *       seen, is INSTA once they are.</li>
     *   <li><b>Pass-through</b> - on the way back, rooms the path only passes through (mobs standing, no flip) close
     *       PASS_THROUGH with no kills even when their mobs vanish right after; the destination, where he stops and
     *       nothing flips, is still NO_CLEAR.</li>
     * </ul>
     */
    private static void liveCase(ClientGameTestContext ctx) {
        String name = "98-sim-insta-clear-live";
        List<String> problems = new ArrayList<>();
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        copyDir(Path.of(SOURCE_ROOMS).resolveSibling("killer560smod-roomdata").toString(), "killer560smod-roomdata");
        Scenario.ensureRoomDatabase(ctx);
        Path dir;
        try {
            dir = Files.createTempDirectory("insta-clear-live");
        } catch (Exception e) {
            throw new AssertionError("no temp dir", e);
        }
        String file = dir.resolve("insta-clear.json").toString();
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        boolean oldMap = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(mapCfg, "isEnabled"));
        boolean oldIm = ctx.computeOnClient(mc -> ModUnderTest.getBoolean(mapCfg, "isInteractiveMapEnabled"));
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        boolean[] verifyHook = {false};
        ctx.runOnClient(mc -> {
            mc.options.renderDistance().set(16);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            ModUnderTest.set(mapCfg, "setEnabled", true);
            ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", false);
            call("testUseFile", new Class<?>[]{String.class}, file);
            call("testClearAll", new Class<?>[]{});
            call("testClearForcedStates", new Class<?>[]{});
            call("testSetMinSuccesses", new Class<?>[]{int.class}, 2);
            call("testSetWindowSeconds", new Class<?>[]{int.class}, 6);
            try {
                call("testSetVerifySeconds", new Class<?>[]{int.class}, 8);
                verifyHook[0] = true;
            } catch (RuntimeException | AssertionError e) {
                // ModUnderTest reports a missing method as an AssertionError. A jar from before 2026-10-06 has no wait at all; its cases below fail on their own
            }
            ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class}, new Object[]{"gametest"});
        });
        System.out.println("[" + name + "] verify-window hook present: " + verifyHook[0]);
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            Object floor = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimFloorGen$Floor", "F7");
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "generate",
                    new Class<?>[]{Minecraft.class, floor.getClass(), int.class, int.class},
                    new Object[]{mc, floor, 3, 4});
        }));
        ctx.waitFor(mc -> mc.level != null);
        try {
            Scenario.awaitSimBuild(ctx, before);
            ctx.waitTicks(60);
            waitForFloor(ctx, name);
            liveBody(ctx, name, problems);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            ctx.runOnClient(mc -> {
                try {
                    call("testSetVerifySeconds", new Class<?>[]{int.class}, 60);
                } catch (RuntimeException | AssertionError ignored) {
                    // old jar
                }
                call("testClearForcedStates", new Class<?>[]{});
                call("testUseFile", new Class<?>[]{String.class}, (Object) null);
                ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", oldIm);
                ModUnderTest.set(mapCfg, "setEnabled", oldMap);
                mc.options.renderDistance().set(renderBefore);
            });
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "leave"));
            ctx.runOnClient(mc -> mc.execute(() -> {
                if (mc.level != null) {
                    mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                    mc.disconnectWithSavingScreen();
                }
            }));
            ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null);
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> mc.execute(() ->
                    McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
            ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("insta-clear live:\n    " + String.join("\n    ", problems));
        }
        System.out.println("[" + name + "] PASS - Entrance ignored; a flip before the mobs are seen waits for them"
                + " (INSTA / UNOBSERVED / NO_STARS); map landings are etherwarp by interactivemap; pass-throughs are"
                + " PASS_THROUGH with no kills; a real stop with no flip is still NO_CLEAR");
    }

    private static void liveBody(ClientGameTestContext ctx, String name, List<String> problems)
            throws java.io.IOException {
        List<Room> all = ctx.computeOnClient(mc -> allRooms());
        Room entrance = all.stream().filter(r -> r.name.equalsIgnoreCase("Entrance")).findFirst().orElse(null);
        List<Room> rooms = ctx.computeOnClient(mc -> rooms());
        System.out.println("[" + name + "] " + rooms.size() + " identified room(s) besides the Entrance ("
                + (entrance == null ? "no Entrance identified" : "Entrance identified") + ")");
        if (rooms.size() < 8 || entrance == null) {
            throw new AssertionError("fewer than 8 identified rooms, or no Entrance - nothing below would mean anything");
        }
        ctx.runOnClient(mc -> {
            force(entrance.name, MAP_DISCOVERED);
            for (Room r : rooms) {
                force(r.name, MAP_UNOPENED);
            }
        });
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimRun", "begin",
                new Class<?>[]{Minecraft.class, BlockPos.class},
                new Object[]{mc, ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder", "entranceDoor")}));
        for (int i = 0; i < 400 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.roomsim.SimRun", "isRunning")); i++) {
            ctx.waitTicks(1);
        }
        giveAotv(ctx);

        // Rooms for the teleport cases: X, Y, U, Z, plus a neighbour of each to step out to. The map path uses
        // the rest, so the path's two ends are picked from rooms none of these touched.
        List<Room> pool = new ArrayList<>(rooms);
        Room rx = pool.remove(0);
        Room ry = pool.remove(0);
        Room ru = pool.remove(0);
        Room rz = pool.remove(0);
        System.out.println("[" + name + "] X=" + rx.name + " Y=" + ry.name + " U=" + ru.name + " Z=" + rz.name);

        // ---- 1. Entrance: in and out of it records nothing ------------------------------------------------
        placeAt(ctx, centreX(rx), centreZ(rx));
        ctx.waitTicks(10);
        placeAt(ctx, centreX(entrance), centreZ(entrance));
        ctx.waitTicks(20);
        List<String> pend = pending(ctx);
        System.out.println("[" + name + "] in the Entrance, open: " + pend);
        check(problems, "standing in the Entrance opens no observation (" + pend + ")",
                pend.stream().noneMatch(p -> p.startsWith(entrance.name + "|")));
        ctx.waitTicks(10);

        // ---- 2. Duncan: flips at once, mobs not yet in sight, he leaves; the mobs show up later -> INSTA ------
        Room outY = neighbour(rooms, ry, rx);
        placeAt(ctx, centreX(ry), centreZ(ry));
        ctx.runOnClient(mc -> force(ry.name, MAP_CLEARED));
        ctx.waitTicks(2);
        placeAt(ctx, centreX(outY), centreZ(outY));
        ctx.waitTicks(10);
        String yEarly = lastFor(ctx, ry.name);
        System.out.println("[" + name + "] Y right after leaving (closed already on a pre-fix jar): " + yEarly);
        int spawnedY = spawnStarred(ctx, ry, 2);
        ctx.waitTicks(60);
        String ly = lastFor(ctx, ry.name);
        System.out.println("[" + name + "] Y (" + spawnedY + " starred spawned after the flip): " + ly);
        check(problems, "Y: a flip before its mobs were seen, mobs seen later -> INSTA with lateStars and no kills ("
                + ly + ")", spawnedY > 0 && ly != null && ly.contains("\"outcome\":\"INSTA\"")
                && ly.contains("\"lateStars\":") && ly.contains("\"kills\":0"));

        // ---- 3. the same with no mob ever in sight -> UNOBSERVED once the wait runs out (8 s here) -----------
        Room outU = neighbour(rooms, ru, ry);
        placeAt(ctx, centreX(ru), centreZ(ru));
        ctx.runOnClient(mc -> force(ru.name, MAP_CLEARED));
        ctx.waitTicks(2);
        placeAt(ctx, centreX(outU), centreZ(outU));
        ctx.waitTicks(220);
        String lu = lastFor(ctx, ru.name);
        System.out.println("[" + name + "] U (no mob ever): " + lu);
        check(problems, "U: flipped before any mob was seen and none ever seen -> UNOBSERVED (" + lu + ")",
                lu != null && lu.contains("\"outcome\":\"UNOBSERVED\""));

        // ---- 4. control: flips with none in sight while he STAYS in it -> NO_STARS --------------------------
        placeAt(ctx, centreX(rz), centreZ(rz));
        ctx.runOnClient(mc -> force(rz.name, MAP_CLEARED));
        ctx.waitTicks(80);
        String lz = lastFor(ctx, rz.name);
        System.out.println("[" + name + "] Z (stayed, no mobs): " + lz);
        check(problems, "Z: flipped with none in sight while he stood in it -> NO_STARS (" + lz + ")",
                lz != null && lz.contains("\"outcome\":\"NO_STARS\""));

        // ---- 5. the Interactive Map: warm its floor graph -----------------------------------------------------
        long warmMark = LogTap.mark();
        Object mapCfg = ctx.computeOnClient(mc -> ModUnderTest.config(LIVE_MAP_CONFIG));
        ctx.runOnClient(mc -> ModUnderTest.set(mapCfg, "setInteractiveMapEnabled", true));
        String warmLine = null;
        for (int i = 0; i < 1800 && warmLine == null; i++) {
            ctx.waitTicks(1);
            for (String l : LogTap.since(warmMark)) {
                java.util.regex.Matcher m = WARM.matcher(l);
                if (m.find() && Integer.parseInt(m.group(1)) >= 3000) {
                    warmLine = l;
                }
            }
        }
        System.out.println("[" + name + "] graph: " + (warmLine == null ? "NOT warm after 90 s"
                : warmLine.replaceAll("^.*\\[Path\\]", "[Path]")));
        if (warmLine == null) {
            throw new AssertionError("the Interactive Map's floor graph never warmed - the map cases cannot run");
        }

        // The path's ends: the two non-trap rooms of the pool farthest apart (no wait for a correction there).
        List<Room> usable = new ArrayList<>();
        for (Room r : pool) {
            if (!ctx.computeOnClient(mc -> isTrap(r.name))) {
                usable.add(r);
            }
        }
        Room rs = null;
        Room rt = null;
        int best = -1;
        for (Room a : usable) {
            for (Room b : usable) {
                if (a != b && tileGap(a, b) > best) {
                    best = tileGap(a, b);
                    rs = a;
                    rt = b;
                }
            }
        }
        if (rs == null || best < 2) {
            throw new AssertionError("no two non-trap rooms two tiles apart for the map path");
        }
        final Room s = rs;
        final Room t = rt;
        System.out.println("[" + name + "] map path S=" + s.name + " -> T=" + t.name + " (" + best + " tiles apart)");

        // ---- 6. path S -> T: every room landed in on the way flips on entry with no mob in it; T has mobs ----
        placeAt(ctx, centreX(s), centreZ(s));
        ctx.waitTicks(20);
        int spawnedT = spawnStarred(ctx, t, 2);
        ctx.waitTicks(30);
        List<String> mids = new ArrayList<>();
        String tOpen = runPath(ctx, name, problems, t, s, mids, true);
        String inT = ctx.computeOnClient(InstaClearTests::roomUnderPlayer);
        System.out.println("[" + name + "] path 1 ended in " + inT + "; T opened as " + tOpen + "; rooms on the way "
                + mids);
        if (!t.name.equals(inT)) {
            problems.add("the map path did not take him to T (" + t.name + "), he is in " + inT);
        }
        if (mids.isEmpty()) {
            problems.add("the map path landed in no room between S and T - the mid-path cases measured nothing");
        }
        if (tOpen != null) {
            String[] f = tOpen.split("\\|");
            check(problems, "T's entry (the path's own landing) is etherwarp by interactivemap (" + f[2] + " by "
                    + (f.length > 5 ? f[5] : "?") + ")", f[2].equals("etherwarp") && f.length > 5
                    && f[5].equals("interactivemap"));
        } else {
            problems.add("arriving in T opened no observation");
        }
        ctx.runOnClient(mc -> force(t.name, MAP_CLEARED));
        ctx.waitTicks(10);
        String lt = lastFor(ctx, t.name);
        System.out.println("[" + name + "] T (" + spawnedT + " starred, flipped on arrival): " + lt);
        check(problems, "T: INSTA, recorded as etherwarp by interactivemap (" + lt + ")", lt != null
                && lt.contains("\"outcome\":\"INSTA\"") && lt.contains("\"method\":\"etherwarp\"")
                && lt.contains("\"driver\":\"interactivemap\""));
        // The rooms on the way: their mobs come into view only now.
        for (String m : mids) {
            Room r = byName(rooms, m);
            if (r != null) {
                spawnStarred(ctx, r, 2);
            }
        }
        ctx.waitTicks(60);
        // A room far back along the path is out of entity range from T, so its mobs never come into sight: it must
        // still be waiting (or UNOBSERVED), never NO_STARS. Within 3 tiles of T they are in sight: INSTA.
        int near = 0;
        for (String m : mids) {
            String lm = lastFor(ctx, m);
            Room r = byName(rooms, m);
            int gap = r == null ? 99 : tileGap(r, t);
            System.out.println("[" + name + "] on the way, " + m + " (" + gap + " tiles from T): " + lm);
            if (gap <= 3) {
                near++;
                check(problems, "mid-path " + m + ": flipped before its mobs were seen -> INSTA once they are, as"
                        + " etherwarp by interactivemap (" + lm + ")", lm != null && lm.contains("\"outcome\":\"INSTA\"")
                        && lm.contains("\"driver\":\"interactivemap\"") && lm.contains("\"method\":\"etherwarp\""));
            } else {
                check(problems, "mid-path " + m + " (out of sight from T): not NO_STARS - still waiting, INSTA or"
                        + " UNOBSERVED (" + lm + ")", lm == null || lm.contains("\"outcome\":\"INSTA\"")
                        || lm.contains("\"outcome\":\"UNOBSERVED\""));
            }
        }
        check(problems, "at least one room on the way within sight of T (" + near + ")", near > 0);

        // ---- 7. path T -> S: mobs everywhere, nothing flips; the rooms on the way are only passed through ---
        for (Room r : rooms) {
            if (r == rx || r == ry || r == ru || r == rz || r == t || mids.contains(r.name)) {
                continue;
            }
            spawnStarred(ctx, r, 2);
        }
        ctx.runOnClient(mc -> {
            for (Room r : rooms) {
                if (r != ry && r != ru && r != rz && r != t) {
                    force(r.name, MAP_UNOPENED);
                }
            }
        });
        ctx.waitTicks(40);
        List<String> mids2 = new ArrayList<>();
        long pathMark = System.currentTimeMillis();
        runPath(ctx, name, problems, s, t, mids2, false);
        // Their mobs vanish right after he has gone by (out of sight, or a teammate's kills): not his kills.
        int vanished = 0;
        for (String m : mids2) {
            Room r = byName(rooms, m);
            if (r != null) {
                vanished += killStarredNear(ctx, r);
            }
        }
        String inS = ctx.computeOnClient(InstaClearTests::roomUnderPlayer);
        System.out.println("[" + name + "] path 2 ended in " + inS + "; passed through " + mids2 + "; "
                + vanished + " starred pair(s) removed from them after he passed");
        if (!s.name.equals(inS)) {
            problems.add("the map path back did not take him to S (" + s.name + "), he is in " + inS);
        }
        if (mids2.isEmpty()) {
            problems.add("the path back landed in no room between T and S - the pass-through case measured nothing");
        }
        ctx.waitTicks(60);
        for (String m : mids2) {
            String lm = lastFor(ctx, m);
            System.out.println("[" + name + "] passed through " + m + ": " + lm);
            boolean fresh = lm != null && lm.contains("\"t\":") && newerThan(lm, pathMark);
            check(problems, "passed-through " + m + " -> PASS_THROUGH with no kills (" + lm + ")", fresh
                    && lm.contains("\"outcome\":\"PASS_THROUGH\"") && lm.contains("\"kills\":0"));
        }
        // S: he stopped there and nothing flips -> still a failure after the 6 s window.
        ctx.waitTicks(140);
        String ls = lastFor(ctx, s.name);
        System.out.println("[" + name + "] S (stopped, stars up, no flip): " + ls);
        check(problems, "S: a real stop with no flip is still NO_CLEAR, etherwarp by interactivemap (" + ls + ")",
                ls != null && newerThan(ls, pathMark) && ls.contains("\"outcome\":\"NO_CLEAR\"")
                        && ls.contains("\"driver\":\"interactivemap\""));
        for (String l : summary(ctx)) {
            System.out.println("[" + name + "] readout: " + l);
        }
    }

    /**
     * Presses the map on {@code to} and follows the trip to its end. Every room other than {@code from}/{@code to}
     * that opens an observation on the way is added to {@code mids}; with {@code flipOnEntry} each is forced cleared
     * the tick it is seen, before any of its mobs exist. @return the destination's pending line when it opened, or null
     */
    private static String runPath(ClientGameTestContext ctx, String name, List<String> problems, Room to, Room from,
                                  List<String> mids, boolean flipOnEntry) {
        for (int i = 0; i < 100 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        long mark = LogTap.mark();
        // Anything still open from before the press is not a room on the way.
        List<String> openBefore = new ArrayList<>();
        for (String p : pending(ctx)) {
            openBefore.add(p.substring(0, p.indexOf('|')));
        }
        Boolean ok = ctx.computeOnClient(mc -> {
            Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
            int room = roomIndex(layout, to.name);
            return (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "pathToRoom",
                    new Class<?>[]{layout.getClass(), int.class, int.class, int.class},
                    new Object[]{layout, room, to.tiles[0], 0});
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new AssertionError("the map press on " + to.name + " was refused");
        }
        String toLine = null;
        boolean busySeen = false;
        for (int tick = 0; tick < 600; tick++) {
            ctx.waitTick();
            for (String p : pending(ctx)) {
                String room = p.substring(0, p.indexOf('|'));
                if (room.equals(to.name)) {
                    toLine = toLine == null ? p : toLine;
                } else if (!room.equals(from.name) && !mids.contains(room) && !openBefore.contains(room)) {
                    mids.add(room);
                    if (flipOnEntry) {
                        ctx.runOnClient(mc -> force(room, MAP_CLEARED));
                    }
                    System.out.println("[" + name + "]   on the way: " + p + (flipOnEntry ? " -> flipped now" : ""));
                }
            }
            boolean busy = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(EXECUTOR, "isBusy"));
            busySeen |= busy;
            if (busySeen && !busy) {
                break;
            }
        }
        ctx.waitTicks(4);
        for (String p : pending(ctx)) {
            if (toLine == null && p.startsWith(to.name + "|")) {
                toLine = p;
            }
        }
        for (String l : LogTap.since(mark)) {
            if (l.contains("[Path] running") || l.contains("[Path] off the plan") || l.contains("[InstaClear]")) {
                System.out.println("[" + name + "]   log: " + l.replaceAll("^.*?\\[(Path|InstaClear)\\]", "[$1]"));
            }
        }
        return toLine;
    }

    private static boolean newerThan(String lastForLine, long ms) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"t\":(\\d+)").matcher(lastForLine);
        return m.find() && Long.parseLong(m.group(1)) >= ms;
    }

    private static Room byName(List<Room> rooms, String n) {
        return rooms.stream().filter(r -> r.name.equals(n)).findFirst().orElse(null);
    }

    /** A room one tile from {@code r} (so its mobs stay in entity range), not {@code avoid}; else any other room. */
    private static Room neighbour(List<Room> rooms, Room r, Room avoid) {
        Room any = null;
        for (Room x : rooms) {
            if (x == r || x == avoid) {
                continue;
            }
            if (tileGap(r, x) == 1) {
                return x;
            }
            if (any == null) {
                any = x;
            }
        }
        return any;
    }

    private static int roomIndex(Object layout, String n) {
        int count = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        for (int r = 0; r < count; r++) {
            if (n.equals(ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r}))) {
                return r;
            }
        }
        return -1;
    }

    private static boolean isTrap(String n) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "capture");
        int r = roomIndex(layout, n);
        return r >= 0 && (Boolean) ModUnderTest.staticCall(CLEAR_UTILS, "isTrap",
                new Class<?>[]{layout.getClass(), int.class}, new Object[]{layout, r});
    }

    /** Spawns up to {@code n} real sim starred zombies in the room, off its first tile's centre. @return how many. */
    private static int spawnStarred(ClientGameTestContext ctx, Room x, int n) {
        int done = 0;
        for (int[] off : new int[][]{{5, 0}, {-5, 0}, {0, 5}, {0, -5}, {8, 8}, {-8, -8}}) {
            if (done >= n) {
                break;
            }
            BlockPos stand = standable(ctx, x.tiles[0], off[0], off[1]);
            if (stand != null) {
                ctx.runOnClient(mc -> {
                    Object k = ModUnderTest.enumValue(SIM_MOBS + "$Kind", "ZOMBIE");
                    ModUnderTest.staticCall(SIM_MOBS, "spawnStarred",
                            new Class<?>[]{Minecraft.class, BlockPos.class, k.getClass()}, new Object[]{mc, stand, k});
                });
                done++;
            }
        }
        return done;
    }

    private static void giveAotv(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    given.set(false);
                    return;
                }
                var inv = sp.getInventory();
                for (int i = 0; i < 36; i++) {
                    inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
                inv.setItem(0, (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall(
                        "com.killer560.hub.roomsim.SimItems", "build", new Class<?>[]{String.class},
                        new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(10);
    }

    // ================================================================================================ helpers

    /** A room of the published layout: name and its even tiles. */
    private record Room(String name, int[] tiles) {
        /** This room's tile nearest to {@code other}'s tiles, as {@code {gx, gz}}. */
        int[] tileFacing(Room other) {
            int[] best = null;
            int bestD = Integer.MAX_VALUE;
            for (int a : tiles) {
                for (int b : other.tiles) {
                    int dx = a % GRID - b % GRID;
                    int dz = a / GRID - b / GRID;
                    if (dx * dx + dz * dz < bestD) {
                        bestD = dx * dx + dz * dz;
                        best = new int[]{a % GRID, a / GRID};
                    }
                }
            }
            return best;
        }
    }

    private static int tileGap(Room a, Room b) {
        int[] ta = a.tileFacing(b);
        int[] tb = b.tileFacing(a);
        return (Math.abs(ta[0] - tb[0]) + Math.abs(ta[1] - tb[1])) / 2;
    }

    private static int doorOf(Room a, Room b) {
        int[] ta = a.tileFacing(b);
        int[] tb = b.tileFacing(a);
        return ((ta[1] + tb[1]) / 2) * GRID + (ta[0] + tb[0]) / 2;
    }

    /** Identified rooms of the published layout, without the Entrance (the recorder never records it, mod 2026-10-06). */
    private static List<Room> rooms() {
        List<Room> out = new ArrayList<>();
        for (Room r : allRooms()) {
            if (!r.name.equalsIgnoreCase("Entrance")) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Room> allRooms() {
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        int n = (Integer) ModUnderTest.call(layout, "roomCount", new Class<?>[]{}, new Object[]{});
        List<Room> out = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            String nm = (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
            Object cr = ModUnderTest.call(layout, "clayRotation", new Class<?>[]{int.class}, new Object[]{r});
            int[] tiles = (int[]) ModUnderTest.call(layout, "tiles", new Class<?>[]{int.class}, new Object[]{r});
            if (nm != null && !nm.equals("Unknown") && cr != null && tiles.length > 0) {
                out.add(new Room(nm, tiles));
            }
        }
        return out;
    }

    /** The door cell between two adjacent rooms if it is a NORMAL door, else -1. */
    private static int normalDoorBetween(Room a, Room b) {
        if (tileGap(a, b) != 1) {
            return -1;
        }
        int door = doorOf(a, b);
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        int type = (Integer) ModUnderTest.call(layout, "doorType", new Class<?>[]{int.class}, new Object[]{door});
        return type == 1 ? door : -1;
    }

    private static String roomUnderPlayer(Minecraft mc) {
        Object layout = ModUnderTest.staticCall(LAYOUT, "current");
        int r = (Integer) ModUnderTest.call(layout, "roomAtWorld", new Class<?>[]{double.class, double.class},
                new Object[]{mc.player.getX(), mc.player.getZ()});
        return r < 0 ? null : (String) ModUnderTest.call(layout, "name", new Class<?>[]{int.class}, new Object[]{r});
    }

    private static int centreX(Room r) {
        return -185 + (r.tiles[0] % GRID) * 16;
    }

    private static int centreZ(Room r) {
        return -185 + (r.tiles[0] / GRID) * 16;
    }

    /** First standable spot (solid, two air) in the column at the tile centre + (dx, dz), found on the server. */
    private static BlockPos standable(ClientGameTestContext ctx, int tile, int dx, int dz) {
        AtomicReference<BlockPos> out = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            int x = -185 + (tile % GRID) * 16 + dx;
            int z = -185 + (tile / GRID) * 16 + dz;
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                for (int y = level.getMinY(); y < level.getMaxY() - 2; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    if (!level.getBlockState(b).isAir() && level.getBlockState(b.above()).isAir()
                            && level.getBlockState(b.above(2)).isAir()) {
                        out.set(b.above());
                        break;
                    }
                }
                done.set(true);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        return out.get();
    }

    /** Teleports him (on the server, so a real position packet) onto the first standable block in the column. */
    private static String placeAt(ClientGameTestContext ctx, int x, int z) {
        AtomicReference<String> done = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp == null) {
                    done.set("no server player");
                    return;
                }
                var level = sp.level();
                for (int y = level.getMinY(); y < level.getMaxY() - 2; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    if (!level.getBlockState(b).isAir() && level.getBlockState(b.above()).isAir()
                            && level.getBlockState(b.above(2)).isAir()) {
                        sp.teleportTo(x + 0.5, y + 1, z + 0.5);
                        done.set("placed at " + x + "," + (y + 1) + "," + z);
                        return;
                    }
                }
                done.set("no standable block at " + x + "," + z);
            });
        });
        ctx.waitFor(mc -> done.get() != null, 200);
        ctx.waitTicks(4);
        for (int i = 0; i < 60 && !ctx.computeOnClient(mc -> mc.player.onGround()); i++) {
            ctx.waitTicks(1);
        }
        return done.get();
    }

    /** Discards every recorded sim starred pair standing in the room (mob and stand), on the server. */
    private static int killStarredNear(ClientGameTestContext ctx, Room room) {
        AtomicReference<Integer> out = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            @SuppressWarnings("unchecked")
            List<UUID[]> pairs = (List<UUID[]>) ModUnderTest.staticCall(SIM_MOBS, "starredPairs");
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                int n = 0;
                for (UUID[] pair : pairs) {
                    var mob = level.getEntity(pair[0]);
                    var tag = level.getEntity(pair[1]);
                    if (mob == null || tag == null) {
                        continue;
                    }
                    boolean inside = false;
                    for (int t : room.tiles) {
                        int cx = -185 + (t % GRID) * 16;
                        int cz = -185 + (t / GRID) * 16;
                        if (Math.abs(tag.getX() - cx) <= 16 && Math.abs(tag.getZ() - cz) <= 16) {
                            inside = true;
                        }
                    }
                    if (inside) {
                        mob.discard();
                        tag.discard();
                        n++;
                    }
                }
                out.set(n);
            });
        });
        ctx.waitFor(mc -> out.get() != null, 200);
        return out.get();
    }

    private static void waitForFloor(ClientGameTestContext ctx, String name) {
        boolean ready = false;
        for (int i = 0; i < 180 && !ready; i++) {
            ready = ctx.computeOnClient(mc -> mc.level != null && mc.player != null
                    && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir());
            if (!ready) {
                ctx.waitTicks(10);
            }
        }
        System.out.println("[" + name + "] client floor ready: " + ready);
        if (!ready) {
            throw new AssertionError("the client never got the floor under the spawn point in 90 s");
        }
    }

    private static Object call(String method, Class<?>[] types, Object... args) {
        return ModUnderTest.staticCall(TRACKER, method, types, args);
    }

    private static void add(String room, String key, String outcome, long flipMs) {
        call("testAdd", new Class<?>[]{String.class, String.class, String.class, long.class}, room, key, outcome,
                flipMs);
    }

    private static void force(String room, int state) {
        call("testForceMapState", new Class<?>[]{String.class, Integer.class}, room, state);
    }

    private static boolean known(ClientGameTestContext ctx, String room, String key) {
        return ctx.computeOnClient(mc -> (Boolean) call("knownToInstaClear",
                new Class<?>[]{String.class, String.class}, room, key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> knownEntries(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> (List<String>) call("knownEntries", new Class<?>[]{String.class}, room));
    }

    private static int[] counts(ClientGameTestContext ctx, String room, String key) {
        return ctx.computeOnClient(mc -> (int[]) call("testCounts", new Class<?>[]{String.class, String.class},
                room, key));
    }

    private static int minSuccesses(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) call("minSuccesses", new Class<?>[]{}));
    }

    private static int size(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Integer) call("testSize", new Class<?>[]{}));
    }

    @SuppressWarnings("unchecked")
    private static List<String> summary(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (List<String>) call("testSummaryLines", new Class<?>[]{}));
    }

    @SuppressWarnings("unchecked")
    private static List<String> pending(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (List<String>) call("testPending", new Class<?>[]{}));
    }

    private static String lastFor(ClientGameTestContext ctx, String room) {
        return ctx.computeOnClient(mc -> (String) call("testLastFor", new Class<?>[]{String.class}, room));
    }

    private static void check(List<String> problems, String what, boolean ok) {
        System.out.println("    " + (ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            problems.add(what);
        }
    }

    private static void copyDir(String from, String into) {
        try {
            Path source = Path.of(from);
            if (!Files.isDirectory(source)) {
                return;
            }
            Path target = ModUnderTest.modConfig(into);
            Files.createDirectories(target);
            try (var s = Files.list(source)) {
                for (Path f : s.toList()) {
                    if (Files.isRegularFile(f)) {
                        Files.copy(f, target.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception ignored) {
            // the room database download still covers a fresh client
        }
    }
}
