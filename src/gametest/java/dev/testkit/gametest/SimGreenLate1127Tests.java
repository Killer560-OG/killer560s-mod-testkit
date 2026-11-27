package dev.testkit.gametest;

import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * greenroom-late-1127 (his 11-27 F7 runs: "sometimes it doesnt warp to the door in green room either till after the run
 * starts"). The green room start (GreenRoomStart, Warp to Door Before Start ON) must put him on the stand by the Entrance
 * gate before the run starts, whenever he arms it, and must do nothing once the run is about to start. Seeded floor and
 * loadout of SimGreenStart1122Tests, AOTV and Hyperion, countdown held.
 * <ul>
 *   <li>1127-sim-ac-greenlate-loading: armed while the world is still loading (the dungeon is not identified yet: his key in
 *       the join second, runs 3 and 6 of the log, where the arm ended at its first tick and never warped). Waits, no use_item
 *       meanwhile; once the dungeon is identified it warps onto the stand before GO. Origin ends the arm (fails).</li>
 *   <li>1127-sim-ac-greenlate-countdown: armed with the countdown already near its end (his key during "Starting in N"): warps
 *       onto the stand before GO, never after.</li>
 *   <li>1127-sim-ac-greenlate-lastsecond: armed after "Starting in 1 second." (the run starts in a second): no warp, no use_item
 *       from it, the reason logged.</li>
 *   <li>1127-sim-ac-greenlate-after: armed after GO: no "[GreenRoomStart] etherwarping" log, the arm never warps.</li>
 * </ul>
 * Each asserts the feature acted (a use_item on the wire, a warp step, standing on its goal) or, for the two refusals, that
 * it logged why. On a legit jar each row passes by checking Dungeons Autopilot refuses to switch on.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimGreenLate1127Tests implements FabricClientGameTest {

    private static final String[] CASES = {"1127-sim-ac-greenlate-loading", "1127-sim-ac-greenlate-countdown",
            "1127-sim-ac-greenlate-lastsecond", "1127-sim-ac-greenlate-after"};
    private static final String SIM_RUN = "com.killer560.hub.roomsim.SimRun";
    private static final String PILOT = "com.killer560.hub.autosecret.DungeonsAutopilot";
    private static final String GRS = "com.killer560.hub.autosecret.GreenRoomStart";
    private static final String CHAT_OBSERVER = "com.killer560.hub.util.ChatObserver";
    private static final String AUTO_CLEAR = "com.killer560.hub.autoclear.AutoClearFeature";
    private static final String AUTO_CLEAR_CONFIG = "com.killer560.hub.autoclear.AutoClearConfig";
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean any = false;
        for (String n : CASES) {
            any |= !Scenario.skip(n);
        }
        if (!any) {
            return;
        }
        ModUnderTest.require("killer560smod");
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : CASES) {
                if (!Scenario.skip(n)) {
                    SimGreenStart1122Tests.legitRow(ctx, n);
                }
            }
            return;
        }
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : CASES) {
                if (!Scenario.skip(n)) {
                    Scenario.skipped(n, "needs real room captures and room database");
                }
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        ctx.runOnClient(mc -> {
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autopuzzles.AutoPuzzlesConfig"),
                    "setAutoPuzzlesMasterEnabled", false);
            Object cfg = ModUnderTest.config(AUTO_CLEAR_CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setHyperionHops", true);
            Object w = ModUnderTest.enumValue(AUTO_CLEAR_CONFIG + "$Weapon", "HYPERION");
            ModUnderTest.call(cfg, "setWeapon", new Class<?>[]{w.getClass()}, new Object[]{w});
        });
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < CASES.length; i++) {
            if (!Scenario.skip(CASES[i])) {
                run(ctx, CASES[i], i, failed);
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config(AUTO_CLEAR_CONFIG), "setEnabled", false));
        if (!failed.isEmpty()) {
            throw new AssertionError(String.join("; ", failed));
        }
    }

    private static void run(ClientGameTestContext ctx, String name, int which, List<String> failed) {
        List<String> f = new ArrayList<>();
        String detail = "";
        try {
            SimGreenStart1122Tests.setWarp(ctx, true);
            detail = switch (which) {
                case 0 -> loading(ctx, f);
                case 1 -> countdown(ctx, f);
                case 2 -> lastSecond(ctx, f);
                default -> after(ctx, f);
            };
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            f.add("threw " + t);
        } finally {
            ctx.runOnClient(mc -> {
                PacketTrace.tap = null;
                try {
                    ModUnderTest.staticCall(GRS, "testUnidentified", new Class<?>[]{boolean.class}, new Object[]{false});
                } catch (Throwable ignored) {
                    // origin: no hook
                }
                ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{false});
                ModUnderTest.staticCall(AUTO_CLEAR, "cancel");
                if ((Boolean) ModUnderTest.staticCall(AS, "isRunning")) {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class},
                            new Object[]{"test over", true});
                }
            });
            SimGreenStart1122Tests.leave(ctx);
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

    private static int[] useTap() {
        int[] uses = {0};
        PacketTrace.tap = p -> {
            if (p instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket) {
                uses[0]++;
            }
        };
        return uses;
    }

    private static boolean started(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(SIM_RUN, "isRunning"));
    }

    private static String result(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            try {
                return String.valueOf(Class.forName(GRS).getMethod("result").invoke(null));
            } catch (ReflectiveOperationException e) {
                return "missing";
            }
        });
    }

    private static void arm(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> ModUnderTest.staticCall(PILOT, "setOn", new Class<?>[]{boolean.class}, new Object[]{true}));
    }

    /** Waits (up to {@code max} ticks) until the green room start ended its arm; the ticks it took. */
    private static int settle(ClientGameTestContext ctx, int max) {
        int t = 0;
        for (; t < max; t++) {
            ctx.waitTicks(1);
            String r = result(ctx);
            if (!"armed".equals(r) && !"warping".equals(r) && !"walking".equals(r)) {
                ctx.waitTicks(3);
                return t;
            }
        }
        return t;
    }

    /** What a warp before GO must show: acted (use_item, a warp step, standing on its goal), the run still shut. */
    private static String warpedBeforeGo(ClientGameTestContext ctx, List<String> f, Vec3 spawn, int[] uses, long mark, String how) {
        String result = result(ctx);
        Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
        BlockPos goal = ctx.computeOnClient(mc -> {
            try {
                return (BlockPos) Class.forName(GRS).getMethod("goal").invoke(null);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        });
        double off = goal == null ? 99 : Math.hypot(now.x - (goal.getX() + 0.5), now.z - (goal.getZ() + 0.5));
        double moved = Math.hypot(now.x - spawn.x, now.z - spawn.z);
        boolean logged = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] etherwarping"));
        int u = ctx.computeOnClient(mc -> uses[0]);
        f.add(started(ctx) ? "the run started before the case let it" : null);
        f.add("warped".equals(result) ? null : "result " + result + " before GO (want warped)");
        f.add(u >= 1 ? null : "no use_item on the wire before GO");
        f.add(moved > 2.5 ? null : String.format(Locale.US, "he moved only %.2f blocks (no warp)", moved));
        f.add(off <= 1.0 ? null : String.format(Locale.US, "%.2f from its goal (want <= 1)", off));
        f.add(logged ? null : "no \"[GreenRoomStart] etherwarping\" log line");
        f.removeIf(java.util.Objects::isNull);
        return String.format(Locale.US, "%s: result %s, %d use_item(s), moved %.1f, %.2f from goal %s", how, result, u, moved, off,
                goal == null ? "none" : goal.toShortString());
    }

    private static String loading(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = SimGreenStart1122Tests.stage(ctx, f, true);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        long mark = LogTap.mark();
        int[] uses = ctx.computeOnClient(mc -> useTap());
        // The sidebar has not named the floor yet (the join second): the arm's hook says so.
        ctx.runOnClient(mc -> ModUnderTest.staticCall(GRS, "testUnidentified", new Class<?>[]{boolean.class}, new Object[]{true}));
        arm(ctx);
        ctx.waitTicks(30);
        int usesWaiting = ctx.computeOnClient(mc -> uses[0]);
        String waiting = result(ctx);
        Vec3 held = ctx.computeOnClient(mc -> mc.player.position());
        boolean logged = LogTap.since(mark).stream().anyMatch(l -> l.contains("not acting yet: the dungeon is not identified"));
        f.add("armed".equals(waiting) ? null : "after 30 ticks of an unidentified dungeon the arm is \"" + waiting + "\" (want armed, waiting)");
        f.add(usesWaiting == 0 ? null : usesWaiting + " use_item(s) while the dungeon was unidentified");
        f.add(logged ? null : "the wait was not logged (\"not acting yet: the dungeon is not identified\")");
        ctx.runOnClient(mc -> ModUnderTest.staticCall(GRS, "testUnidentified", new Class<?>[]{boolean.class}, new Object[]{false}));
        int ticks = settle(ctx, 400);
        String d = warpedBeforeGo(ctx, f, held, uses, mark, "identified, warped in " + ticks + " ticks");
        f.removeIf(java.util.Objects::isNull);
        return d + String.format(Locale.US, "; waited armed with %d use_item(s), spawn %.1f from the door", usesWaiting,
                Math.hypot(spawn.x - door.getX(), spawn.z - door.getZ()));
    }

    private static String countdown(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = SimGreenStart1122Tests.stage(ctx, f, true);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        long mark = LogTap.mark();
        int[] uses = ctx.computeOnClient(mc -> useTap());
        // The countdown is on its way out (a bit over three seconds left, as when he presses the key at "Starting in 4").
        ctx.runOnClient(mc -> SimGreenStart1122Tests.setTicksLeft(70));
        arm(ctx);
        int ticks = settle(ctx, 120);
        String d = warpedBeforeGo(ctx, f, spawn, uses, mark, "armed with 70 ticks to GO, warped in " + ticks + " ticks");
        return d;
    }

    private static String lastSecond(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = SimGreenStart1122Tests.stage(ctx, f, true);
        if (door == null) {
            return "";
        }
        Vec3 spawn = ctx.computeOnClient(mc -> mc.player.position());
        long mark = LogTap.mark();
        int[] uses = ctx.computeOnClient(mc -> useTap());
        ctx.runOnClient(mc -> ModUnderTest.staticCall(CHAT_OBSERVER, "onChatComponentAdd",
                new Class<?>[]{net.minecraft.network.chat.Component.class},
                new Object[]{net.minecraft.network.chat.Component.literal("Starting in 1 second.")}));
        arm(ctx);
        ctx.waitTicks(40);
        Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
        int u = ctx.computeOnClient(mc -> uses[0]);
        String result = result(ctx);
        boolean warpLog = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] etherwarping"));
        boolean why = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] ended: the run starts in a second"));
        f.add(started(ctx) ? "the run started before the case let it" : null);
        f.add(u == 0 ? null : u + " use_item(s) after \"Starting in 1 second.\" (want 0)");
        f.add(Math.hypot(now.x - spawn.x, now.z - spawn.z) < 1.0 ? null : "he moved after \"Starting in 1 second.\"");
        f.add(warpLog ? "it began a warp after \"Starting in 1 second.\"" : null);
        f.add(why ? null : "no \"[GreenRoomStart] ended: the run starts in a second\" log line (result " + result + ")");
        f.removeIf(java.util.Objects::isNull);
        return String.format(Locale.US, "result %s, %d use_item(s), moved %.2f", result, u,
                Math.hypot(now.x - spawn.x, now.z - spawn.z));
    }

    private static String after(ClientGameTestContext ctx, List<String> f) {
        BlockPos door = SimGreenStart1122Tests.stage(ctx, f, true);
        if (door == null) {
            return "";
        }
        long mark = LogTap.mark();
        ctx.runOnClient(mc -> SimGreenStart1122Tests.setTicksLeft(1));
        for (int t = 0; t < 100 && !started(ctx); t++) {
            ctx.waitTicks(1);
        }
        f.add(started(ctx) ? null : "the run did not start");
        String before = result(ctx);
        arm(ctx);
        ctx.waitTicks(30);
        String result = result(ctx);
        boolean warpLog = LogTap.since(mark).stream().anyMatch(l -> l.contains("[GreenRoomStart] etherwarping"));
        f.add(warpLog ? "the green room start warped after GO" : null);
        f.add("warping".equals(result) || "walking".equals(result) ? "result " + result + " after GO" : null);
        f.removeIf(java.util.Objects::isNull);
        return "armed after GO: result " + before + " -> " + result + ", no green room warp";
    }
}
