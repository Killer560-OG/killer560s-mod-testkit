package dev.testkit.gametest;

import dev.testkit.harness.Report;
import dev.testkit.harness.SuiteVerdict;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dungeon Autopilot idle gaps (acgaps2-1121, killer560 2026-11-21: clear a floor to 300 in under ~1:50, every avoidable
 * wait gone). One Blood First Dungeon Autopilot run on 1118-sim-acbloodfirst's fixed F7, timed per tick:
 * <ul>
 *   <li>1121-sim-acgaps2-lastdeath: ticks from a handed-over room's last starred death to Auto Secret getting it back. At
 *   least two rooms must come back within 1 tick (the map's checkmark comes several ticks later).</li>
 *   <li>1121-sim-acgaps2-keywait: ticks Blood First stood waiting for "the cleared room's key" when no key came.</li>
 *   <li>1121-sim-acgaps2-essence: ticks the end-of-route wait held for a Wither Essence already clicked. The sim takes a
 *   clicked essence 22 ticks later as Hypixel does; on a jar whose sim takes it at once the case adds that delay itself,
 *   so the wait is measured against Hypixel's pace either way.</li>
 * </ul>
 * Every row also needs the run to have cleared rooms and opened doors, so a run that did nothing fails.
 */
public class SimAcGaps21121Tests implements FabricClientGameTest {
    private static final String LASTDEATH = "1121-sim-acgaps2-lastdeath";
    private static final String KEYWAIT = "1121-sim-acgaps2-keywait";
    private static final String ESSENCE = "1121-sim-acgaps2-essence";
    private static final String NAME = "1121-sim-acgaps2";
    private static final String[] ROWS = {LASTDEATH, KEYWAIT, ESSENCE};
    private static final String AS = "com.killer560.hub.autosecret.AutoSecretFeature";
    private static final String SIM_FLOOR = "com.killer560.hub.roomsim.SimMobFloor";
    private static final String SIM_SECRETS = "com.killer560.hub.roomsim.SimSecrets";
    /** Hypixel's essence pickup, 1093-1247 ms after the click in his 11-21 log. */
    private static final int ESSENCE_TICKS = 22;
    private static final int KEYWAIT_MAX = Integer.getInteger("testkit.acgaps2KeyWaitMax", 10);
    private static final int ESSENCE_MAX = Integer.getInteger("testkit.acgaps2EssenceMax", 2);
    private static final java.util.regex.Pattern WAIT_TICKS = java.util.regex.Pattern.compile("after (\\d{1,4}) tick\\(s\\) of the last-node wait");
    private final List<String> all = new ArrayList<>();

    private static volatile boolean emulate;
    private static boolean hooked;
    private static final Map<BlockPos, Object[]> HELD = new ConcurrentHashMap<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        boolean any = false;
        for (String n : ROWS) {
            any |= !System.getProperty("testkit.scenario", "").isBlank() && !Scenario.skip(n);
        }
        if (!any) {
            return;
        }
        if (!dev.testkit.gametest.mod.Mod.isCheat()) {
            for (String n : ROWS) {
                Scenario.skipped(n, "Auto Clear is cheat build only");
            }
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        if (SimMapTests.copyRoomsForOthers() < 20) {
            for (String n : ROWS) {
                Scenario.skipped(n, "needs real room captures and room database");
            }
            return;
        }
        Scenario.ensureRoomDatabase(ctx);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "forceReload"));
        ctx.waitFor(mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.roomsim.RoomLibrary", "isReady"), 2400);
        int renderBefore = ctx.computeOnClient(mc -> mc.options.renderDistance().get());
        ctx.runOnClient(mc -> mc.options.renderDistance().set(16));
        String before = System.getProperty("testkit.mobfloorCode", "");
        boolean jarDelays = jarDelaysEssence();
        try {
            if (before.isBlank()) {
                System.setProperty("testkit.mobfloorCode", SimAcBloodFirst1118Tests.FIXED_FLOOR);
            }
            hook();
            emulate = !jarDelays;
            HELD.clear();
            List<String> problems = new ArrayList<>();
            List<String> facts = new ArrayList<>();
            SimMobFloorTests.buildMobFloor(ctx, problems, facts);
            if (ctx.computeOnClient(mc -> mc.level == null)) {
                for (String n : ROWS) {
                    row(n, false, "the floor did not load: " + problems);
                }
                return;
            }
            body(ctx, jarDelays);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            for (String n : ROWS) {
                row(n, false, "threw: " + t);
            }
        } finally {
            emulate = false;
            HELD.clear();
            if (before.isBlank()) {
                System.clearProperty("testkit.mobfloorCode");
            }
            ctx.runOnClient(mc -> {
                try {
                    ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true});
                    ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig"), "setBloodFirst", false);
                    ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", false);
                } catch (Throwable ignored) {
                    // cleanup must not replace the verdict
                }
            });
            SimMobFloorTests.leave(ctx);
            ctx.runOnClient(mc -> ModUnderTest.staticCall(SIM_FLOOR, "setEnabled", new Class<?>[]{boolean.class},
                    new Object[]{false}));
            ctx.runOnClient(mc -> mc.options.renderDistance().set(renderBefore));
        }
        if (!all.isEmpty()) {
            throw new AssertionError(all.size() + " problem(s): " + String.join("; ", all));
        }
    }

    private static boolean jarDelaysEssence() {
        try {
            Class.forName(SIM_SECRETS).getDeclaredField("ESSENCE_TAKE_TICKS");
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * For a jar whose sim takes a clicked essence the same tick: a server click listener ahead of the sim's holds the click
     * and takes the essence {@link #ESSENCE_TICKS} later the way the sim would (air, secret counted, credited).
     */
    @SuppressWarnings("unchecked")
    private static synchronized void hook() {
        if (hooked) {
            return;
        }
        hooked = true;
        net.minecraft.resources.Identifier early = net.minecraft.resources.Identifier.fromNamespaceAndPath("testkit", "acgaps2_essence");
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.addPhaseOrdering(early,
                net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(early, (player, level, hand, hit) -> {
            if (!emulate || level.isClientSide() || hit == null
                    || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            BlockPos at = hit.getBlockPos().immutable();
            if (HELD.containsKey(at)) {
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            if (!placed().contains(at)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            HELD.put(at, new Object[]{sp, level, ESSENCE_TICKS});
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!emulate || HELD.isEmpty()) {
                return;
            }
            for (var it = HELD.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Object[] t = e.getValue();
                int left = (Integer) t[2] - 1;
                t[2] = left;
                if (left > 0) {
                    continue;
                }
                it.remove();
                BlockPos at = e.getKey();
                if (!placed().remove(at)) {
                    continue;
                }
                ((net.minecraft.server.level.ServerLevel) t[1]).setBlockAndUpdate(at,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimScore", "secretFound",
                        new Class<?>[]{BlockPos.class}, new Object[]{at});
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimScore", "creditSecret",
                        new Class<?>[]{java.util.UUID.class},
                        new Object[]{((net.minecraft.server.level.ServerPlayer) t[0]).getUUID()});
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Set<BlockPos> placed() {
        try {
            return (Set<BlockPos>) Class.forName(SIM_SECRETS).getField("PLACED_WITHER").get(null);
        } catch (ReflectiveOperationException e) {
            return Set.of();
        }
    }

    private void body(ClientGameTestContext ctx, boolean jarDelays) {
        ctx.runOnClient(mc -> {
            Object as = ModUnderTest.config("com.killer560.hub.autosecret.AutoSecretConfig");
            Object solo = ModUnderTest.enumValue("com.killer560.hub.autosecret.AutoSecretConfig$RunMode", "SOLO");
            ModUnderTest.call(as, "setRunMode", new Class<?>[]{solo.getClass()}, new Object[]{solo});
            ModUnderTest.set(as, "setBloodFirst", true);
            ModUnderTest.set(as, "setChatFeedback", true);
            ModUnderTest.set(ModUnderTest.config("com.killer560.hub.autoclear.AutoClearConfig"), "setEnabled", true);
        });
        SimAutoClearTests.setMode(ctx, "ANY_MOB_ROOM", "SPIRIT_SCEPTRE");
        ctx.runOnClient(mc -> SimAcBloodFirst1118Tests.drawKeys(mc.getSingleplayerServer()));
        ctx.waitTicks(25);
        long mark = LogTap.mark();
        Boolean startedOk = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "startAutopilot"));
        int minutes = Integer.getInteger("testkit.acgaps2Minutes", 6);
        int tick = 0;
        int lastDeath = -1;
        List<Integer> backs = new ArrayList<>();
        int kills = 0;
        int doors = 0;
        int keyWaitStart = -1;
        int keyWaitWasted = 0;
        int keyWaits = 0;
        int keyWaitsWithKey = 0;
        int essenceClicks = 0;
        int essenceHeld = 0;
        boolean essenceWait = false;
        boolean bloodOpen = false;
        boolean wasRunning = false;
        for (; tick < minutes * 1200; tick++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> mc.level == null || mc.player == null)) {
                break;
            }
            if (tick % 20 == 0) {
                ctx.runOnClient(mc -> SimAcBloodFirst1118Tests.drawKeys(mc.getSingleplayerServer()));
            }
            boolean running = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(AS, "isRunning"));
            wasRunning |= running;
            long upTo = LogTap.mark();
            List<String> lines = LogTap.between(mark, upTo);
            mark = upTo;
            for (String l : lines) {
                if (l.contains("[AutoClear]") && l.contains(" is dead (")) {
                    kills++;
                    lastDeath = tick;
                }
                if (l.contains("[AutoSecret] Auto Clear cleared") && lastDeath >= 0) {
                    backs.add(tick - lastDeath);
                    lastDeath = -1;
                }
                if (l.contains("[AutoSecret]") && l.contains("door") && l.contains("opened")) {
                    doors++;
                }
                if (l.contains("Blood First: waiting up to")) {
                    keyWaitStart = tick;
                    keyWaits++;
                } else if (keyWaitStart >= 0 && (l.contains("Picking up the") || l.contains("Blood First: next door"))) {
                    keyWaitsWithKey++;
                    keyWaitStart = -1;
                } else if (keyWaitStart >= 0 && l.contains("[Autopilot] decide")) {
                    keyWaitWasted += tick - keyWaitStart;
                    keyWaitStart = -1;
                }
                if (l.contains("[SecretAura] click sent: kind=essence")) {
                    essenceClicks++;
                }
                if (l.contains("waiting for essence at") && l.contains("(clicked")) {
                    essenceWait = true;
                } else if (essenceWait && l.contains("of the last-node wait")) {
                    java.util.regex.Matcher m = WAIT_TICKS.matcher(l);
                    essenceHeld += m.find() ? Integer.parseInt(m.group(1)) : 0;
                    essenceWait = false;
                }
                if (l.contains("The BLOOD DOOR has been opened!")) {
                    bloodOpen = true;
                }
            }
            if (bloodOpen || wasRunning && !running) {
                break;
            }
        }
        ctx.runOnClient(mc -> ModUnderTest.staticCall(AS, "stop", new Class<?>[]{String.class, boolean.class}, new Object[]{"test over", true}));
        long quick = backs.stream().filter(b -> b <= 1).count();
        double backMean = backs.stream().mapToInt(Integer::intValue).average().orElse(-1);
        String did = String.format(Locale.US, "started %s, run %.1f s, %d starred kill(s), %d door line(s), blood open %s,"
                + " essence pickup delayed by %s", startedOk, tick / 20.0, kills, doors, bloodOpen,
                jarDelays ? "the jar's sim" : "the case");
        System.out.println("[" + NAME + "] RESULT: " + did + "; hand-backs after last death " + backs + "; key waits "
                + keyWaits + " (" + keyWaitsWithKey + " got a key, " + keyWaitWasted + " tick(s) wasted); essence clicks "
                + essenceClicks + ", leave-wait ticks on clicked essences " + essenceHeld);
        boolean acted = Boolean.TRUE.equals(startedOk) && kills >= 3 && backs.size() >= 2;
        row(LASTDEATH, acted && quick >= 2, String.format(Locale.US, "%d of %d handed-over clears back within 1 tick of the"
                + " last death (mean %.1f, need 2); %s", quick, backs.size(), backMean, did));
        row(KEYWAIT, acted && keyWaitWasted <= KEYWAIT_MAX, keyWaitWasted + " tick(s) waited for a key that never came over "
                + keyWaits + " wait(s), " + keyWaitsWithKey + " got one (max " + KEYWAIT_MAX + "); " + did);
        row(ESSENCE, acted && essenceClicks >= 1 && essenceHeld <= ESSENCE_MAX, essenceHeld + " tick(s) the end-of-route wait"
                + " held for a clicked essence (max " + ESSENCE_MAX + "), " + essenceClicks + " essence click(s); " + did);
    }

    private void row(String name, boolean pass, String detail) {
        if (!Scenario.named(name) && !System.getProperty("testkit.scenario", "").contains(NAME)) {
            return;
        }
        System.out.println("[" + name + "] " + (pass ? "PASS" : "FAIL") + " - " + detail);
        Report.caseStarted(name);
        Report.caseFinished(name, pass ? "PASS" : "FAIL", "n/a (singleplayer)", detail, List.of(), List.of());
        SuiteVerdict.finished(name);
        if (!pass) {
            all.add(name + ": " + detail);
        }
    }
}
