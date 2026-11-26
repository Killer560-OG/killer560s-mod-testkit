package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * acgreenstart-1122 (killer560 2026-11-22: "if I activated in Green room have it teleport is close to the door as possible
 * so that way it doesn't waste an extra two ether warps just getting to a Start spot"). Dungeons Autopilot switched on in
 * the green room during the countdown (held long by the case) walks to the Entrance door's front; the countdown is then let
 * run out. Cheat jar only.
 * <ul>
 *   <li>1122-sim-ac-greenstart: before GO he has walked (no per-tick jump over 1.2 blocks) at least 6 blocks from the spawn
 *       to within 5.5 blocks of the entrance door; after GO the first trip is out through the door within 40 ticks with
 *       at most 1 warp and none landing inside the Entrance, and the autopilot is running. The floor is seeded, so an
 *       origin jar builds the same one; origin stands at the spawn (fails).</li>
 *   <li>1122-sim-ac-greenstart-keys: his W pressed mid-walk takes over within 3 ticks (walk ended, body back on his view),
 *       W moves him along his own facing, and with W released nothing walks him again before GO; the autopilot still
 *       starts at GO.</li>
 * </ul>
 * Both run with Warp to Door Before Start OFF (the switch-off case of autopilot-greenroom-warp-1126). That job's cases
 * (killer560 2026-11-26: "it should etherwarp in green room to get to the gate before it starts if autopilot is on not run
 * there"), same seeded floor:
 * <ul>
 *   <li>1126-sim-ac-greenwarp: a fresh config has the switch OFF (the default); switched ON, AOTV in the hotbar: before GO a use_item on the wire, one warp step (over 2.5
 *       blocks in a tick), no walking key or ShortWalk in any tick, result "warped", standing within 1 block of its goal,
 *       which is nearer the door than the walk's spot (under 4 blocks from the door block). After GO out through the door
 *       within 40 ticks, no warp landing inside the Entrance, and no later than the walk case's exit plus 2 ticks (planning
 *       time varies by a tick or two). Origin has no warp: fails.</li>
 *   <li>1126-sim-ac-greenwarp-noether: switch ON, no etherwarp item: walks as before (largest step 1.2, no use_item
 *       before GO, walk logged, within 5.5 of the door).</li>
 * </ul>
 * On a legit jar both new cases PASS by checking Dungeons Autopilot refuses to switch on (cheat-only).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimGreenStart1122Tests implements FabricClientGameTest {

    private static final String WALK_CASE = "1122-sim-ac-greenstart";
    private static final String KEYS_CASE = "1122-sim-ac-greenstart-keys";
    private static final String WARP_CASE = "1126-sim-ac-greenwarp";
    private static final String NOETHER_CASE = "1126-sim-ac-greenwarp-noether";
    /** The walk case's exit tick after GO on this floor (-1 when it did not run): the warp case compares with it. */
    private static int walkOut = -1;
    private enum Mode { WALK, KEYS, WARP, NOETHER }
    private static final String SIM_STATE = "com.killer560.hub.roomsim.SimState";
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String ROOM_LIBRARY = "com.killer560.hub.roomsim.RoomLibrary";
    private static final String FLOOR_GEN = "com.killer560.hub.roomsim.SimFloorGen";
    private static final String BUILDER = "com.killer560.hub.roomsim.SimBuilder";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String GRS = "com.killer560.hub.autosecret.GreenRoomStart";
    private static final String AS_CONFIG = "com.killer560.hub.autosecret.AutoSecretConfig";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final int HOLD = 20 * 120;
    private static final long SEED = 1122_2026_0950L;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean walk = !Scenario.skip(WALK_CASE);
        boolean keys = !Scenario.skip(KEYS_CASE);
        boolean warp = !Scenario.skip(WARP_CASE);
        boolean noether = !Scenario.skip(NOETHER_CASE);
        if (!walk && !keys && !warp && !noether) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : new String[]{WARP_CASE, NOETHER_CASE}) {
                if (!Scenario.skip(n)) {
                    legitRow(ctx, n);
                }
            }
            if (walk) {
                Scenario.skipped(WALK_CASE, "legit jar: no Dungeons Autopilot");
            }
            if (keys) {
                Scenario.skipped(KEYS_CASE, "legit jar: no Dungeons Autopilot");
            }
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : new String[]{WALK_CASE, KEYS_CASE, WARP_CASE, NOETHER_CASE}) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "needs real room captures and room database");
                }
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(ROOM_LIBRARY, "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall(ROOM_LIBRARY, "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            Object w = ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{w.getClass()}, new Object[]{w});
            Object as = ModUnderTest.config(AS_CONFIG);
            Object door = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "DOOR");
            ModUnderTest.call(as, "setBloodRush", new Class<?>[]{door.getClass()}, new Object[]{door});
            ModUnderTest.set(as, "setDisableAfterBloodRush", true);
        });
        List<String> failed = new ArrayList<>();
        walkOut = -1;
        if (walk) {
            run(ctx, WALK_CASE, Mode.WALK, failed);
        }
        if (warp) {
            run(ctx, WARP_CASE, Mode.WARP, failed);
        }
        if (noether) {
            run(ctx, NOETHER_CASE, Mode.NOETHER, failed);
        }
        if (keys) {
            run(ctx, KEYS_CASE, Mode.KEYS, failed);
        }
        setWarp(ctx, true);
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config(AS_CONFIG);
            Object none = ModUnderTest.enumValue(AS_CONFIG + "$BloodRush", "NONE");
            ModUnderTest.call(as, "setBloodRush", new Class<?>[]{none.getClass()}, new Object[]{none});
            ModUnderTest.set(as, "setDisableAfterBloodRush", false);
            ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false);
        });
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join("; ", failed));
        }
    }

    /** Warp to Door Before Start (a jar without it keeps walking: nothing to set). */
    static void setWarp(ClientGameTestContext ctx, boolean on) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.set(ModUnderTest.config(AS_CONFIG), "setGreenRoomWarp", on);
            } catch (Throwable ignored) {
                // origin: no switch
            }
        });
    }

    /** Legit jar: Dungeons Autopilot (and so the green room warp) must refuse to switch on. */
    static void legitRow(ClientGameTestContext ctx, String name) {
        boolean on = ctx.computeOnClient(mc -> {
            ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true});
            boolean v = (Boolean) ModUnderTest.staticCall(PILOT, "isOn");
            ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
            return v;
        });
        String text = on ? "legit jar: Dungeons Autopilot switched on (it is cheat-only)"
                : "legit jar: Dungeons Autopilot refused to switch on, so nothing warps in the green room";
        System.out.println("[" + name + "] " + (on ? "FAIL" : "PASS") + " - " + text);
        Report.caseStarted(name);
        Report.caseFinished(name, on ? "FAIL" : "PASS", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (on) {
            throw new AssertionError(name + ": " + text);
        }
    }

    private static void run(ClientGameTestContext ctx, String name, Mode mode, List<String> failed) {
        List<String> f = new ArrayList<>();
        String detail;
        try {
            setWarp(ctx, mode == Mode.WARP || mode == Mode.NOETHER);
            detail = switch (mode) {
                case KEYS -> keysCase(ctx, f);
                case WALK -> walkCase(ctx, f);
                case WARP -> warpCase(ctx, f);
                case NOETHER -> noEtherCase(ctx, f);
            };
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            f.add("threw " + t);
            detail = "";
        } finally {
            ctx.runOnClient(mc -> {
                PacketTrace.tap = null;
                mc.options.keyUp.setDown(false);
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            leave(ctx);
        }
        boolean pass = f.isEmpty();
        String text = pass ? detail : String.join("; ", f) + " | " + detail;
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + text);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", text, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            failed.add(name + ": " + text);
        }
    }

    /** The floor built, its countdown held; the entrance door; null (with a failure) when it could not be set up. */
    private static BlockPos stage(ClientGameTestContext ctx, List<String> f) {
        return stage(ctx, f, true);
    }

    static BlockPos stage(ClientGameTestContext ctx, List<String> f, boolean aotv) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_STATE, "enter", new Class<?>[]{String.class},
                    new Object[]{"gametest"}));
            long seed = SEED + attempt;
            // Seeded, so the origin jar and the branch's build the same floor and their first trips compare.
            for (String cls : new String[]{"roomsim.SimFloorGen", "roomsim.SimGenerator", "roomsim.SimMimic"}) {
                try {
                    ((java.util.Random) dev.testkit.gametest.mod.Mod.field(cls, "RNG")).setSeed(seed);
                } catch (Throwable ignored) {
                    // a class without an RNG leaves that part unseeded
                }
            }
            ctx.runOnClient(mc -> mc.execute(() -> {
                Object fl = ModUnderTest.enumValue(FLOOR_GEN + "$Floor", "F7");
                ModUnderTest.staticCall(FLOOR_GEN, "generate",
                        new Class<?>[]{Minecraft.class, fl.getClass(), int.class, int.class},
                        new Object[]{mc, fl, 3, 3});
            }));
            ctx.waitFor(mc -> mc.level != null, 2400);
            boolean held = false;
            for (int t = 0; t < 2400 && !held; t++) {
                ctx.waitTicks(1);
                held = ctx.computeOnClient(mc -> {
                    if ((Boolean) ModUnderTest.staticCall(SIM_RUN, "isArmed")
                            && !(Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning")
                            && (Integer) ModUnderTest.staticCall(SIM_RUN, "countdownTicks") > 0) {
                        setTicksLeft(HOLD);
                        return true;
                    }
                    return false;
                });
            }
            BlockPos door = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(BUILDER, "entranceDoor"));
            if (held && door != null) {
                SimAutopilotTests.awaitFloorChunks(ctx);
                loadout(ctx, aotv);
                SimAutopilotTests.ground(ctx);
                ctx.waitTicks(40);   // the world scan reads the Entrance and its gate
                return door;
            }
            System.out.println("[" + WALK_CASE + "] floor " + attempt + ": countdown not caught (held " + held + ", door "
                    + door + ") - again");
            leave(ctx);
        }
        f.add("could not hold a sim countdown on three floors");
        return null;
    }

    static void setTicksLeft(int ticks) {
        try {
            java.lang.reflect.Field fl = Class.forName(SIM_RUN).getDeclaredField("ticksLeft");
            fl.setAccessible(true);
            fl.setInt(null, ticks);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SimRun.ticksLeft: " + e, e);
        }
    }

    private static void loadout(ClientGameTestContext ctx, boolean aotv) {
        AtomicReference<Boolean> given = new AtomicReference<>();
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
                String[] ids = aotv ? new String[]{"ASPECT_OF_THE_VOID", "HYPERION"} : new String[]{"HYPERION"};
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
    }

    /** +1/-1 along the door's axis toward the inside of the Entrance, as {x, z}. */
    private static int[] inward(Vec3 spawn, BlockPos door) {
        double dx = spawn.x - (door.getX() + 0.5);
        double dz = spawn.z - (door.getZ() + 0.5);
        return Math.abs(dx) >= Math.abs(dz) ? new int[]{(int) Math.signum(dx), 0} : new int[]{0, (int) Math.signum(dz)};
    }

    private static double flat(Vec3 a, BlockPos b) {
        return Math.hypot(a.x - (b.getX() + 0.5), a.z - (b.getZ() + 0.5));
    }

    private static String walkCase(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = stage(ctx, f);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        int[] in = inward(spawn, door);
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        Vec3 last = spawn;
        double maxStep = 0;
        int still = 0;
        int t = 0;
        String result = "";
        for (; t < 400 && still < 20; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            double step = Math.hypot(now.x - last.x, now.z - last.z);
            maxStep = Math.max(maxStep, step);
            result = ctx.computeOnClient(SimGreenStart1122Tests::result);
            // Settled: its walk ended (or origin, with no such walk, has stood still for 10 s).
            boolean ended = !"armed".equals(result) && !"walking".equals(result) && !"missing".equals(result);
            still = step < 0.01 && (ended || t > 200) ? still + 1 : 0;
            last = now;
        }
        Vec3 before = last;
        double moved = Math.hypot(before.x - spawn.x, before.z - spawn.z);
        double atDoor = flat(before, door);
        boolean shut = ctx.computeOnClient(mc -> !(Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        String pre = String.format(Locale.US, "spawn %.1f from the door, before GO walked %.1f (largest tick step %.2f) to %.1f"
                + " from it in %d ticks, result %s", flat(spawn, door), moved, maxStep, atDoor, t, result);
        int[] go = afterGo(ctx, door, in);
        int out = go[0];
        int warps = go[1];
        int inside = go[2];
        walkOut = out;
        boolean on = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PILOT, "isOn"));
        boolean logged = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] walking"));
        String post = String.format(Locale.US, "after GO: out through the door at tick %d with %d warp(s), %d landing inside"
                + " the Entrance; autopilot on %s; walk logged %s", out, warps, inside, on, logged);
        f.add(shut ? null : "the run started before the case let it");
        f.add(moved >= 6.0 ? null : String.format(Locale.US, "walked only %.1f blocks before GO (want >= 6)", moved));
        f.add(maxStep <= 1.2 ? null : String.format(Locale.US, "a %.2f-block step before GO: it must walk, not warp", maxStep));
        f.add(atDoor <= 5.5 ? null : String.format(Locale.US, "%.1f blocks from the door at GO (want <= 5.5)", atDoor));
        // The planner's first search from a cold graph takes ~1 s (970 ms on the first green run): 40 ticks, not 15.
        f.add(out > 0 && out <= 40 ? null : "not out through the door within 40 ticks of GO (tick " + out + ")");
        f.add(inside == 0 ? null : inside + " warp(s) landed inside the Entrance before the door (want 0)");
        f.add(warps <= 1 ? null : warps + " warps before leaving the Entrance (want <= 1)");
        f.add(logged ? null : "no \"[GreenRoomStart] walking\" log line");
        f.removeIf(java.util.Objects::isNull);
        return pre + "; " + post;
    }

    /** GO, then {tick he is out through the door (-1 none in 200), warps until then, of them landing inside the Entrance}. */
    private static int[] afterGo(ClientGameTestContext ctx, BlockPos door, int[] in) {
        ctx.runOnClient(mc -> setTicksLeft(1));
        int toGo = 0;
        while (toGo < 40 && !ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"))) {
            ctx.waitTicks(1);
            toGo++;
        }
        int warps = 0;
        int inside = 0;
        int out = -1;
        Vec3 last = ctx.computeOnClient(mc -> mc.player.position());
        for (int i = 1; i <= 200 && out < 0; i++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            double past = -((now.x - (door.getX() + 0.5)) * in[0] + (now.z - (door.getZ() + 0.5)) * in[1]);
            if (now.distanceTo(last) > 2.5) {
                warps++;
                if (past <= 1.0) {
                    inside++;   // a warp that landed still inside the Entrance: one spent reaching a start spot
                }
            }
            last = now;
            if (past > 1.0) {
                out = i;
            }
        }
        return new int[]{out, warps, inside};
    }

    /** Counts use_item packets on the wire from now on (client thread hook), and how many went before GO. */
    private static int[] useTap() {
        int[] uses = {0};
        PacketTrace.tap = p -> {
            if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
                uses[0]++;
            }
        };
        return uses;
    }

    /** Armed with the switch on: polls until the green room start settles; {maxStep, walkTicks, ticks, final result}. */
    private static Object[] settle(ClientGameTestContext ctx, Vec3 spawn) {
        Vec3 last = spawn;
        double maxStep = 0;
        int walkTicks = 0;
        int still = 0;
        int t = 0;
        String result = "";
        for (; t < 400 && still < 20; t++) {
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            maxStep = Math.max(maxStep, Math.hypot(now.x - last.x, now.z - last.z));
            boolean walkKeys = ctx.computeOnClient(mc -> {
                var k = mc.player.input.keyPresses;
                return k.forward() || k.backward() || k.left() || k.right() || k.jump()
                        || (Boolean) ModUnderTest.staticCall("com.killer560.hub.pathfinding.ShortWalk", "isActive");
            });
            walkTicks += walkKeys ? 1 : 0;
            result = ctx.computeOnClient(SimGreenStart1122Tests::result);
            boolean ended = !"armed".equals(result) && !"walking".equals(result) && !"warping".equals(result)
                    && !"missing".equals(result);
            still = now.distanceTo(last) < 0.01 && (ended || t > 200) ? still + 1 : 0;
            last = now;
        }
        return new Object[]{maxStep, walkTicks, t, result};
    }

    private static String warpCase(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = stage(ctx, f, true);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        int[] in = inward(spawn, door);
        long mark = LogTap.mark();
        int[] uses = ctx.computeOnClient(mc -> useTap());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        Object[] s = settle(ctx, spawn);
        double maxStep = (Double) s[0];
        int walkTicks = (Integer) s[1];
        String result = (String) s[3];
        int usesBefore = ctx.computeOnClient(mc -> uses[0]);
        Vec3 before = ctx.computeOnClient(mc -> mc.player.position());
        BlockPos goal = ctx.computeOnClient(mc -> {
            try {
                return (BlockPos) Class.forName(GRS).getMethod("goal").invoke(null);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        });
        double offGoal = goal == null ? 99 : flat(before, goal);
        double goalToDoor = goal == null ? 99 : Math.hypot(goal.getX() - door.getX(), goal.getZ() - door.getZ());
        boolean shut = ctx.computeOnClient(mc -> !(Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
        boolean logged = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] etherwarping"));
        String pre = String.format(Locale.US, "spawn %.1f from the door; before GO: result %s in %d ticks, %d use_item(s),"
                        + " largest tick step %.2f, %d tick(s) with a walking key, goal %s (%.1f from the door block), standing"
                        + " %.2f from it, %.1f from the door", flat(spawn, door), result, (Integer) s[2], usesBefore, maxStep,
                walkTicks, goal == null ? "none" : goal.toShortString(), goalToDoor, offGoal, flat(before, door));
        int[] go = afterGo(ctx, door, in);
        String post = String.format(Locale.US, "after GO: out through the door at tick %d (walk case: %d) with %d warp(s),"
                + " %d landing inside the Entrance", go[0], walkOut, go[1], go[2]);
        // A fresh config (what a new player gets) has the switch OFF: today's walk stays the default (killer560 2026-11-26).
        Boolean freshOn = ctx.computeOnClient(mc -> {
            try {
                var k = ModUnderTest.config(AS_CONFIG).getClass().getDeclaredConstructor();
                k.setAccessible(true);
                Object fresh = k.newInstance();
                return (Boolean) fresh.getClass().getMethod("isGreenRoomWarp").invoke(fresh);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        });
        f.add(Boolean.FALSE.equals(freshOn) ? null : "a fresh config has Warp to Door Before Start " + freshOn + " (want OFF)");
        f.add(shut ? null : "the run started before the case let it");
        f.add("warped".equals(result) ? null : "result " + result + " before GO (want warped)");
        f.add(usesBefore >= 1 ? null : "no use_item on the wire before GO");
        f.add(maxStep > 2.5 ? null : String.format(Locale.US, "no warp step before GO (largest %.2f)", maxStep));
        f.add(walkTicks == 0 ? null : walkTicks + " tick(s) with a walking key or ShortWalk before GO (want 0)");
        f.add(offGoal <= 1.0 ? null : String.format(Locale.US, "%.2f from its goal at GO (want <= 1)", offGoal));
        f.add(goalToDoor < 4.0 ? null : String.format(Locale.US, "goal %.1f from the door block (want nearer than the walk's 4)",
                goalToDoor));
        f.add(logged ? null : "no \"[GreenRoomStart] etherwarping\" log line");
        f.add(go[0] > 0 && go[0] <= 40 ? null : "not out through the door within 40 ticks of GO (tick " + go[0] + ")");
        f.add(walkOut < 0 || go[0] <= walkOut + 2 ? null : "out at tick " + go[0] + ", later than the walk's " + walkOut + " + 2");
        f.add(go[2] == 0 ? null : go[2] + " warp(s) after GO landed inside the Entrance (want 0)");
        f.removeIf(java.util.Objects::isNull);
        return pre + "; " + post;
    }

    private static String noEtherCase(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = stage(ctx, f, false);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        long mark = LogTap.mark();
        int[] uses = ctx.computeOnClient(mc -> useTap());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        Object[] s = settle(ctx, spawn);
        double maxStep = (Double) s[0];
        int usesBefore = ctx.computeOnClient(mc -> uses[0]);
        Vec3 before = ctx.computeOnClient(mc -> mc.player.position());
        boolean walked = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] walking"));
        double moved = Math.hypot(before.x - spawn.x, before.z - spawn.z);
        String d = String.format(Locale.US, "no etherwarp item: result %s, %d use_item(s), largest step %.2f, walked %.1f to %.1f"
                + " from the door, walk logged %s", s[3], usesBefore, maxStep, moved, flat(before, door), walked);
        f.add(usesBefore == 0 ? null : usesBefore + " use_item(s) with no etherwarp item (want 0)");
        f.add(maxStep <= 1.2 ? null : String.format(Locale.US, "a %.2f-block step: it must walk", maxStep));
        f.add(walked && moved >= 6.0 ? null : "it did not walk to the door");
        f.add(flat(before, door) <= 5.5 ? null : String.format(Locale.US, "%.1f from the door (want <= 5.5)", flat(before, door)));
        f.removeIf(java.util.Objects::isNull);
        return d;
    }

    private static String keysCase(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = stage(ctx, f);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        int[] in = inward(spawn, door);
        // Facing across the door's axis: his W walks somewhere the walk to the door never would.
        float yaw0 = in[0] != 0 ? 0f : 90f;
        ctx.runOnClient(mc -> {
            mc.player.setYRot(yaw0);
            mc.player.setYHeadRot(yaw0);
            mc.player.setXRot(0f);
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
        int walking = 0;
        for (int t = 0; t < 200 && walking < 6; t++) {
            ctx.waitTicks(1);
            walking = "walking".equals(ctx.computeOnClient(SimGreenStart1122Tests::result)) ? walking + 1 : 0;
        }
        if (walking < 6) {
            f.add("the walk to the door never started");
            return "";
        }
        Vec3 atPress = ctx.computeOnClient(mc -> mc.player.position());
        ctx.runOnClient(mc -> mc.options.keyUp.setDown(true));
        ctx.waitTicks(3);
        String result = ctx.computeOnClient(SimGreenStart1122Tests::result);
        boolean walkOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(
                "com.killer560.hub.pathfinding.ShortWalk", "isActive"));
        float yawNow = ctx.computeOnClient(mc -> mc.player.getYRot());
        ctx.waitTicks(10);
        Vec3 afterW = ctx.computeOnClient(mc -> mc.player.position());
        ctx.runOnClient(mc -> mc.options.keyUp.setDown(false));
        double fx = -Math.sin(Math.toRadians(yaw0));
        double fz = Math.cos(Math.toRadians(yaw0));
        double along = (afterW.x - atPress.x) * fx + (afterW.z - atPress.z) * fz;
        ctx.waitTicks(15);
        Vec3 rest = ctx.computeOnClient(mc -> mc.player.position());
        ctx.waitTicks(40);
        Vec3 later = ctx.computeOnClient(mc -> mc.player.position());
        double drift = Math.hypot(later.x - rest.x, later.z - rest.z);
        boolean armedOn = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(PILOT, "isOn"));
        ctx.runOnClient(mc -> setTicksLeft(1));
        boolean started = false;
        for (int t = 0; t < 100 && !started; t++) {
            ctx.waitTicks(1);
            started = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AUTO_CLEAR, "isBusy")
                    || (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
        }
        String d = String.format(Locale.US, "W pressed mid-walk: result \"%s\", walk active %s, body yaw %.1f (his view %.1f);"
                        + " W moved him %.2f along his facing; drift with W up %.2f; autopilot on %s, started at GO %s",
                result, walkOn, yawNow, yaw0, along, drift, armedOn, started);
        f.add("you moved".equals(result) ? null : "his W did not take over (result " + result + ")");
        f.add(!walkOn ? null : "the walk still drives the keys after his W");
        f.add(Math.abs(yawNow - yaw0) < 1.0 ? null : "the body was not given back to his view");
        f.add(along > 0.5 ? null : String.format(Locale.US, "his W moved him only %.2f along his facing", along));
        f.add(drift < 0.3 ? null : String.format(Locale.US, "walked again (%.2f) after he took over", drift));
        f.add(armedOn ? null : "the autopilot switched off");
        f.add(started ? null : "the autopilot did not start at GO");
        f.removeIf(java.util.Objects::isNull);
        return d;
    }

    /** GreenRoomStart.result(), or "missing" on a jar without it (origin). */
    private static String result(Minecraft mc) {
        try {
            return String.valueOf(Class.forName(GRS).getMethod("result").invoke(null));
        } catch (ReflectiveOperationException e) {
            return "missing";
        }
    }

    static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            try {
                ModUnderTest.staticCall(SIM_RUN, "reset");
                ModUnderTest.staticCall(SIM_STATE, "leave", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored) {
                // cleanup never replaces a verdict
            }
        });
        ctx.runOnClient(mc -> mc.execute(() -> {
            if (mc.level != null) {
                mc.level.disconnect(net.minecraft.network.chat.Component.literal("scenario over"));
                mc.disconnectWithSavingScreen();
            }
        }));
        ctx.waitFor(mc -> mc.level == null && mc.getSingleplayerServer() == null, 1200);
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> mc.execute(() ->
                McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen())));
        ctx.waitFor(mc -> McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen, 1200);
    }
}
