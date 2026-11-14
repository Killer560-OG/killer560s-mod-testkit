package dev.testkit.gametest;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The F7/M7 boss in the dungeon sim (mod doc-adm-boss-1101; killer560, 2026-11-01: "We have built the sim clear but not
 * the actual boss ... the map and how the lava functions and all that and all the terms"). Each case starts the boss
 * straight at a phase (the sim menu's F7 Boss entry, {@code BossSim.start}) and drives it the way he would, then judges
 * what the mod's own trackers made of it - and first proves the thing under test happened (the line was said, the block
 * changed, the terminal was solved by the mod's Auto Terminal).
 *
 * <ul>
 * <li>{@code 1101-boss-p1p2}: P1 Maxor (crystals picked up and placed at the pylons by real clicks, two stuns, his death
 * and the floor opening), the fall into P2, Storm crushed twice by real pad presses, then Goldor's line. DungeonState's
 * boss phase and Floor7Tracker's chat phase follow P1 -> P2 -> P3.</li>
 * <li>{@code 1101-boss-p3}: all four sections. Terminals opened with real clicks on their stands and solved by the mod's
 * Auto Terminal (every one of the six types), levers flicked with real clicks, Simon Says pressed button by button,
 * Lights' levers flicked, the Arrows frames turned by real clicks, Target hit by arrows the test summons at the lit
 * block. P3SectionTracker and Floor7Tracker must walk S1 -> S2 -> S3 -> S4 -> core.</li>
 * <li>{@code 1101-boss-p4p5}: the core (Goldor's arrival lines, his death), P4 Necron's lines and death, P5's dragons.</li>
 * <li>{@code 1105-boss-dragons} (simdragons-1105): P5 from its start. Wither Dragons (on for the case) reads the flame
 * burst as Red's spawn timer, the dragon appears at its spawn point, flies (moves, stays in its area), its body parts are
 * on it, real Terminator arrows kill Red and real melee kills Orange (8 hits each, the sim's log), the mod reads both
 * deaths (health, "dragon counts" from the King's line); the other three fall to testHit and the boss ends.</li>
 * <li>{@code 1101-boss-lava}: a step into P3's lava bounces him (motion 2.25 up), as Hypixel's server does.</li>
 * <li>{@code 1101-boss-ap3}: an AP3 RUN node on S1's floor carries him once he walks into it.</li>
 * <li>{@code 1102-boss-maxorcrys}: Maxor's Crystals' Active counter (killer560, 2026-11-02: it "should go away as 0/2 the
 * second that the next set can be picked up"). Both crystals placed by real clicks reads 2/2; it stays 2/2 until the
 * next set's first crystal is on a tower on the client, reads 0/2 on that very tick (the tower crystal, not the backstop
 * countdown, must be what reset it), and one of the new set placed reads 1/2.</li>
 * <li>{@code 1105-boss-crystalwalk} (simcrystal-1105, owner: Hypixel's crystals need no click near them): 2 blocks from a
 * tower crystal nothing is picked up; walking within 1 block picks it up; a carried crystal is not placed 2 blocks from a
 * pad but is within 1; a right click (crystal 0) and a left click (crystal 1) from 3 blocks pick up. Each asserts the tower
 * crystal is gone, the Energy Crystal star is in slot 8 and the chat line came.</li>
 * <li>{@code 1103-boss-moves} (bossparity-1103, ADM's facts): where each boss is over time. Maxor holds at (73, 226, 53)
 * until t 84, then flies at the player at no more than 0.9 a tick; Storm flies his opening route at 0.4 a tick, parks
 * at (102.375, 183, 52.375) and chases from t 647, and Giga Lightning reaches an exposed player; Goldor walks his loop from
 * (80, 119, 40) at 0.048 a tick and sprints (0.6) to the S2 corner when S1's door opens with him still in S1; in the
 * core he flies at 0.8 and his Frenzy lines come; Necron sidesteps at 80, is back at mid at 98, and his platform goes
 * to lava at 275.</li>
 * <li>{@code 1103-boss-rules} (bossparity-1103): Hypixel's refusals (another section's terminal, a done terminal, a done
 * lever), another section's lever toggling with no credit, a terminal keeping its type across reopens, Melody's six
 * rows, the 5-clicks-in-10-ticks limit, Simon Says' start-button reset and wrong-press stop (and its timing), the Lights
 * neighbour rule, the Target plate (off it, the attempt resets), and a death tick in a section not started yet.</li>
 * <li>{@code 1104-boss-capture} (bossfloor-1104): the boss built from a capture of Hypixel's room (fixture
 * {@code ~/killer560s-mod-logs/fixtures/boss-arena-1104.bin}, or {@code -Dtestkit.bossCapture}; kept out of this repo)
 * equals the capture block for block; the spots the owner found wrong read the real blocks (Storm's red floor, no iron
 * gate walls, redstone lamps on Lights, the plate on its block, the Simon Says frame, Storm's pillars as diorite); the
 * Arrows frames hang from P1; its lava does not move in 200 ticks; Lights lights by Hypixel's rule and stays so; Simon
 * Says plays through on the real wall; a gate opening clears only its barriers.</li>
 * <li>{@code 1105-boss-onehit} (simitems-1105, killer560 2026-11-05: "my mage beam should do all the damage for a phase of
 * any boss andd term as well"): one weapon hit (the Mage beam's own {@code hurtServer} with his player-attack source,
 * {@code BossFight.testWeaponHit}) is each boss's whole damage part: a stunned Maxor's bar drops to the stun's floor and
 * he enrages within 12 ticks; a pinned Storm's bar drops to 0.45; Goldor in the core goes down; Necron's first drop
 * (1 to 0.8) lands; a P5 dragon dies. Before each hit the bar is proved to hold still with no hit. A real Terminator
 * volley at Necron drops his bar the same way.</li>
 * <li>{@code 1105-boss-nostray} ("necron took a bunch of damage withotu me hitting him"): Necron with lava under him and
 * fire and lava damage offered to him keeps his bar for 60 ticks, and the refusal is logged with its source.</li>
 * <li>{@code 1101-boss-portal}: a generated F7, /goto Blood, the portal opens (the sim has no Watcher yet: five seconds in
 * Blood), stepping in starts P1 with the floor's map code unchanged (the floor is not reset).</li>
 * <li>{@code 1105-boss-fix3} (bossfix3-1105, both captures): items kept across phase starts and through the chooser's
 * Section 1 button; the Arrows wall's lime (start) and red (end) wool frames; the Simon Says skip (start,
 * reset, start 3 ticks apart: the first round put in is round 2), no button under a lit lamp, 16 west-facing buttons at
 * input; S1's boundary gone after its gate line (arch, door, portcullis); a Superboom on gate 2 during S2 takes the arch
 * at once and leaves the door, one on gate 3 does nothing, and S2 then done opens its door at once with no countdown.</li>
 * </ul>
 * Only when named. Hits on the bosses (Maxor, Goldor, Necron, the dragons) go through {@code BossFight.testHit}, the same
 * path a weapon's hit takes - the test has no weapon to swing at a boss floating out of reach.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class SimBossTests implements FabricClientGameTest {

    private static final String P1P2 = "1101-boss-p1p2";
    private static final String P3 = "1101-boss-p3";
    private static final String P4P5 = "1101-boss-p4p5";
    private static final String LAVA = "1101-boss-lava";
    private static final String AP3 = "1101-boss-ap3";
    private static final String PORTAL = "1101-boss-portal";
    private static final String MAXOR_CRYS = "1102-boss-maxorcrys";
    private static final String MOVES = "1103-boss-moves";
    private static final String LIGHTS1109 = "1109-boss-lights";
    private static final String RULES = "1103-boss-rules";
    private static final String CAPTURE = "1104-boss-capture";
    private static final String FRESH = "1108-boss-fresh";
    private static final String DRAGONS = "1105-boss-dragons";
    private static final String WD = "com.killer560.hub.witherdragons.";
    private static final String ONEHIT = "1105-boss-onehit";
    private static final String NOSTRAY = "1105-boss-nostray";
    private static final String FIX3 = "1105-boss-fix3";
    private static final String GATES = "1105-boss-simgates";
    private static final String CRYS_WALK = "1105-boss-crystalwalk";
    private static final String NOWITHER = "1109-boss-nowither";
    private static final String BREAKER = "1108-boss-breaker";
    private static final String BREAKER_CHARGE = "1108-boss-breaker-charge";
    private static final String MAXOR = "com.killer560.hub.maxor.MaxorCrystalsFeature";

    private static final String B = "com.killer560.hub.bosssim.";
    private static final String SIM = B + "BossSim";
    private static final String FIGHT = B + "BossFight";
    private static final String START = B + "BossFight$Start";
    private static final String STATIONS = B + "BossStations";
    private static final String DEVICES = B + "BossDevices";
    private static final String TERMS = B + "BossTerminals";
    private static final String LAVA_CLASS = B + "BossLava";
    private static final String ARENA = B + "BossArena";
    private static final String STATIC_WATER = "com.killer560.hub.roomsim.SimStaticWater";
    private static final String F7 = "com.killer560.hub.fastleap.Floor7Tracker";
    private static final String SECTIONS = "com.killer560.hub.leveraura.P3SectionTracker";
    private static final String DUNGEON = "com.killer560.hub.secrets.DungeonState";
    private static final String TERM_CFG = "com.killer560.hub.terminals.TerminalSolverConfig";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (System.getProperty("testkit.scenario", "").isBlank()) {
            return;
        }
        String[] all = {P1P2, P3, P4P5, LAVA, AP3, PORTAL, MAXOR_CRYS, MOVES, RULES, CAPTURE, DRAGONS, ONEHIT, NOSTRAY, FIX3, GATES, CRYS_WALK, FRESH, BREAKER, BREAKER_CHARGE, SimBossTpto1109.NAME, NOWITHER, SimBossStartStay1112.NAME, SimBossEe3Route1109.NAME, LIGHTS1109, SimBossNecron1111.PULL, SimBossNecron1111.DRAGON_HP};
        List<String> chosen = new ArrayList<>();
        for (String n : all) {
            if (!Scenario.skip(n)) {
                chosen.add(n);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        ModUnderTest.require("killer560smod");
        LogTap.install();
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> ModUnderTest.turnOff("com.killer560.hub.auction.AuctionConfig", "setAhEnabled"));
        List<String> failures = new ArrayList<>();
        for (String name : chosen) {
            // P3 waits on Auto Terminal and AP3 on AP3; both are compiled out of a legit jar.
            if ((name.equals(P3) || name.equals(AP3) || name.equals(GATES) || name.equals(BREAKER) || name.equals(BREAKER_CHARGE) || name.equals(SimBossEe3Route1109.NAME)) && !dev.testkit.gametest.mod.Mod.isCheat()) {
                println(name, "SKIPPED: legit jar (Auto Terminal / AP3 are cheat-only)");
                continue;
            }
            List<String> f = new ArrayList<>();
            try {
                switch (name) {
                    case P1P2 -> p1p2(ctx, f);
                    case P3 -> p3(ctx, f);
                    case P4P5 -> p4p5(ctx, f);
                    case LAVA -> lava(ctx, f);
                    case PORTAL -> portal(ctx, f);
                    case MAXOR_CRYS -> maxorCrystals(ctx, f);
                    case MOVES -> moves(ctx, f);
                    case LIGHTS1109 -> lights1109(ctx, f);
                    case RULES -> rules(ctx, f);
                    case CAPTURE -> capture(ctx, f);
                    case FRESH -> fresh(ctx, f);
                    case DRAGONS -> dragons(ctx, f);
                    case ONEHIT -> oneHit(ctx, f);
                    case NOSTRAY -> noStray(ctx, f);
                    case FIX3 -> fix3(ctx, f);
                    case GATES -> simGates(ctx, f);
                    case CRYS_WALK -> crystalWalk(ctx, f);
                    case SimBossTpto1109.NAME -> SimBossTpto1109.run(ctx, f);
                    case NOWITHER -> SimBossNoWither1109.run(ctx, f);
                    case SimBossNecron1111.PULL -> SimBossNecron1111.pull(ctx, f);
                    case SimBossNecron1111.DRAGON_HP -> SimBossNecron1111.dragonHp(ctx, f);
                    case SimBossStartStay1112.NAME -> SimBossStartStay1112.run(ctx, f);
                    case BREAKER -> SimBossBreaker1108.run(ctx, f, false);
                    case BREAKER_CHARGE -> SimBossBreaker1108.run(ctx, f, true);
                    case SimBossEe3Route1109.NAME -> SimBossEe3Route1109.run(ctx, f);
                    default -> ap3(ctx, f);
                }
            } catch (Throwable t) {
                f.add(name + " threw: " + t);
                t.printStackTrace(System.out);
            } finally {
                cleanup(ctx);
            }
            for (String s : f) {
                println(name, "FAIL: " + s);
            }
            println(name, f.isEmpty() ? "PASS" : "FAIL (" + f.size() + ")");
            for (String s : f) {
                failures.add(name + ": " + s);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " problem(s): " + String.join("; ", failures));
        }
    }

    // ============================================================================================== cases

    private static float bar() {
        return ((Number) call(FIGHT, "barProgress")).floatValue();
    }

    private static float testBar(String boss) {
        return ((Number) ModUnderTest.staticCall(FIGHT, "testBar", new Class<?>[]{String.class}, new Object[]{boss})).floatValue();
    }

    static boolean weaponHit(ClientGameTestContext ctx, String boss) {
        boolean[] ok = {false};
        serverRun(ctx, s -> ok[0] = (Boolean) ModUnderTest.staticCall(FIGHT, "testWeaponHit", new Class<?>[]{String.class},
                new Object[]{boss}));
        ctx.waitTicks(2);
        return ok[0];
    }

    /** The bar holds still for {@code ticks} with no hit (a refresh lands at most every 20). */
    private static void barHolds(ClientGameTestContext ctx, List<String> f, String who, int ticks) {
        ctx.waitTicks(21);
        float a = bar();
        ctx.waitTicks(ticks);
        float b = bar();
        println(ONEHIT, who + " bar with no hit: " + a + " -> " + b);
        if (Math.abs(a - b) > 0.001f) {
            f.add(who + "'s bar moved with no hit: " + a + " -> " + b);
        }
    }

    private static void oneHit(ClientGameTestContext ctx, List<String> f) {
        // Maxor: the laser's stun (the rule step), then one hit.
        long mark = start(ctx, "P1", true);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 40)) {
            f.add("Maxor's first line never came");
            return;
        }
        // Maxor spawns 5 ticks after his first line: a laser before that is a no-op (ship7 gate, 1.0 -> 1.0).
        ctx.waitTicks(8);
        long stun = LogTap.mark();
        serverRun(ctx, s -> call(FIGHT, "testLaser"));
        if (!until(ctx, "the laser hit Maxor", mc -> LogTap.since(stun).stream().anyMatch(l -> l.contains("Maxor laser hit 1")), 20)) {
            f.add("the test laser never hit Maxor");
            cleanup(ctx);
            return;
        }
        barHolds(ctx, f, "Maxor (stunned)", 40);
        float m0 = bar();
        expect(f, "a hit on stunned Maxor landed", weaponHit(ctx, "Maxor"), true);
        ctx.waitTicks(21);
        float m1 = bar();
        println(ONEHIT, "Maxor bar " + m0 + " -> " + m1 + " from one hit");
        if (!(m1 < m0 - 0.05f)) {
            f.add("one hit on stunned Maxor did not drop his bar (" + m0 + " -> " + m1 + ")");
        }
        expect(f, "Maxor enraged after one hit", waitChat(ctx, stun, "Maxor is enraged!", 20), true);
        cleanup(ctx);
        // Storm: a crush (the rule step) pins him; one hit is the pin's damage. Read in the same server task, before his
        // enrage (0-60 ticks after the crush) could set the bar itself.
        start(ctx, "P2", true);
        if (!until(ctx, "Storm up", mc -> ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class},
                new Object[]{"Storm"}) != null, 300)) {
            f.add("Storm never appeared");
            return;
        }
        float[] st = new float[3];
        serverRun(ctx, s -> {
            ModUnderTest.staticCall(FIGHT, "testCrush", new Class<?>[]{String.class}, new Object[]{"Purple"});
            st[0] = testBar("Storm");
            st[2] = (Boolean) ModUnderTest.staticCall(FIGHT, "testWeaponHit", new Class<?>[]{String.class},
                    new Object[]{"Storm"}) ? 1f : 0f;
            st[1] = testBar("Storm");
        });
        println(ONEHIT, "Storm (pinned) " + st[0] + " -> " + st[1] + " from one hit");
        expect(f, "a hit on pinned Storm landed", st[2], 1.0f);
        if (!(st[0] > 0.9f && Math.abs(st[1] - 0.45f) < 0.001f)) {
            f.add("one hit on pinned Storm did not take his bar from 0.95 to 0.45 (" + st[0] + " -> " + st[1] + ")");
        }
        cleanup(ctx);
        // Goldor in the core: one hit and he is down.
        mark = start(ctx, "CORE", true);
        tp(ctx, 54.5, 107, 90.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: You have done it, you destroyed the factory...", 400)) {
            f.add("walking into the core never brought Goldor's arrival lines");
            return;
        }
        long g = LogTap.mark();
        ctx.waitTicks(20);
        expect(f, "a hit on Goldor landed", weaponHit(ctx, "Goldor"), true);
        ctx.waitTicks(3);
        boolean down = false;
        for (String l : LogTap.since(g)) {
            down |= l.contains("[BossSim] Goldor down");
        }
        expect(f, "Goldor down from one hit", down, true);
        cleanup(ctx);
        // Necron: his first drop waits for the hit, and one real Terminator volley deals it.
        start(ctx, "P4", true);
        if (!until(ctx, "Necron's first drop part (t 80)", mc -> ((Number) call(FIGHT, "phaseTick")).intValue() >= 82, 200)) {
            f.add("P4 never reached t 82");
            return;
        }
        barHolds(ctx, f, "Necron", 10);
        float n0 = bar();
        boolean term = terminatorAt(ctx, "Necron");
        ctx.waitTicks(10);
        if (!term || bar() > n0 - 0.1f) {
            println(ONEHIT, "the Terminator volley " + (term ? "missed" : "was not fired") + "; a weapon hit instead");
            f.add("a real Terminator volley at Necron did not deal his drop (" + n0 + " -> " + bar() + ")");
            weaponHit(ctx, "Necron");
        }
        ctx.waitTicks(21);
        float n1 = bar();
        println(ONEHIT, "Necron bar " + n0 + " -> " + n1);
        if (Math.abs(n1 - 0.8f) > 0.011f) {
            f.add("Necron's bar after one volley: " + n1 + ", want 0.8");
        }
        cleanup(ctx);
        // A P5 dragon: 15 arrow hits kill it (boss-tpto-1109; one hit was a kill from simitems-1105 to then).
        start(ctx, "P5", true);
        if (!until(ctx, "a dragon up", mc -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
                new AABB(0, 0, 20, 110, 60, 150), d -> d.getHealth() > 0).isEmpty(), 400)) {
            f.add("no dragon spawned");
            return;
        }
        long d = LogTap.mark();
        boolean landed = true;
        for (int i = 0; i < 15; i++) {
            landed &= weaponHit(ctx, "Dragon");
        }
        expect(f, "15 hits on a dragon landed", landed, true);
        ctx.waitTicks(3);
        boolean dead = false;
        for (String l : LogTap.since(d)) {
            dead |= l.contains("dragon down at P5");
        }
        expect(f, "the dragon down from 15 hits", dead, true);
    }

    /** Fires a real Terminator volley (the sim's own right click) at {@code boss} from 6 blocks; true if it fired. */
    private static boolean terminatorAt(ClientGameTestContext ctx, String boss) {
        double[] b = (double[]) ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class}, new Object[]{boss});
        if (b == null) {
            return false;
        }
        serverRun(ctx, s -> s.getPlayerList().getPlayers().get(0).setItemInHand(InteractionHand.MAIN_HAND,
                (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "build",
                        new Class<?>[]{String.class}, new Object[]{"TERMINATOR"})));
        command(ctx, String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f", b[0], b[1], b[2] - 6.0));
        ctx.waitTicks(3);
        return ctx.computeOnClient(mc -> {
            Entity w = null;
            for (Entity e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.wither.WitherBoss.class,
                    mc.player.getBoundingBox().inflate(16))) {
                if (w == null || e.distanceTo(mc.player) < w.distanceTo(mc.player)) {
                    w = e;
                }
            }
            if (w == null) {
                return false;
            }
            Vec3 d = w.getBoundingBox().getCenter().subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            return true;
        });
    }

    private static void noStray(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P4", true);
        if (!until(ctx, "Necron's first drop part (t 80)", mc -> ((Number) call(FIGHT, "phaseTick")).intValue() >= 82, 200)) {
            f.add("P4 never reached t 82");
            return;
        }
        ctx.waitTicks(21);
        float a = bar();
        long mark = LogTap.mark();
        boolean offered = false;
        for (int i = 0; i < 3; i++) {
            boolean[] ok = {false};
            serverRun(ctx, s -> ok[0] = (Boolean) ModUnderTest.staticCall(FIGHT, "testStrayDamage",
                    new Class<?>[]{String.class}, new Object[]{"Necron"}));
            offered |= ok[0];
            ctx.waitTicks(20);
        }
        float b = bar();
        boolean logged = false;
        for (String l : LogTap.since(mark)) {
            logged |= l.contains("[BossSim] refused") && (l.contains("lava") || l.contains("inFire"));
        }
        println(NOSTRAY, "Necron bar " + a + " -> " + b + " over 60 ticks of lava and fire; refusal logged " + logged);
        expect(f, "lava and fire damage offered to Necron", offered, true);
        expect(f, "the refusal logged with its source", logged, true);
        if (Math.abs(a - b) > 0.001f) {
            f.add("Necron's bar moved with no hit from him: " + a + " -> " + b);
        }
    }

    // ============================================================================================== 1105-boss-fix3

    /** Each P3 door's box (x1, y1, z1, x2, y2, z2): the gate's arch and the door's blocks are their kinds inside it. */
    private static final int[][] DOOR_BOX = {{95, 115, 121, 105, 136, 124}, {16, 115, 127, 19, 136, 137}, {3, 115, 48, 13, 136, 51}};

    /** Boundary {@code s}'s cells on the server: {arch (gate), door, portcullis}. */
    static int[] gateCells(ClientGameTestContext ctx, int s) {
        int[] b = DOOR_BOX[s - 1];
        int[] n = new int[3];
        for (String id : serverBox(ctx, b[0], b[1], b[2], b[3], b[4], b[5])) {
            String k = id.replace("minecraft:", "");
            n[0] += k.equals("cracked_stone_bricks") || k.equals("stone_brick_stairs") ? 1 : 0;
            n[1] += k.equals("barrier") || k.equals("iron_block") || k.equals("nether_brick_stairs") ? 1 : 0;
            n[2] += k.equals("cobblestone") || k.equals("cobblestone_wall") || k.equals("nether_brick_fence") ? 1 : 0;
        }
        println(FIX3, "boundary " + s + ": arch " + n[0] + ", door " + n[1] + ", portcullis " + n[2]);
        return n;
    }

    /** The arch cell of boundary {@code s} nearest {@code eye} on the server, or null. */
    private static BlockPos archCell(ClientGameTestContext ctx, int s, Vec3 eye) {
        int[] b = DOOR_BOX[s - 1];
        String[] ids = serverBox(ctx, b[0], b[1], b[2], b[3], b[4], b[5]);
        BlockPos best = null;
        int i = 0;
        for (int y = b[1]; y <= b[4]; y++) {
            for (int z = b[2]; z <= b[5]; z++) {
                for (int x = b[0]; x <= b[3]; x++) {
                    String k = ids[i++];
                    if ((k.endsWith(":cracked_stone_bricks") || k.endsWith(":stone_brick_stairs"))
                            && (best == null || Vec3.atCenterOf(new BlockPos(x, y, z)).distanceTo(eye) < Vec3.atCenterOf(best).distanceTo(eye))) {
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /** Flying, his eyes level with {@code cell} and {@code dx, dz} blocks out from it. */
    private static void hover(ClientGameTestContext ctx, BlockPos cell, int dx, int dz, float yaw) {
        if (cell == null) {
            return;
        }
        serverRun(ctx, s -> {
            var sp = s.getPlayerList().getPlayers().get(0);
            sp.getAbilities().mayfly = true;
            sp.getAbilities().flying = true;
            sp.onUpdateAbilities();
        });
        tp(ctx, cell.getX() + 0.5 + dx, cell.getY() + 0.5 - 1.62, cell.getZ() + 0.5 + dz, yaw, 0);
        ctx.waitTicks(5);
    }

    /** The player's inventory on the server: every non-empty slot and the armor, "0=stick x1 ...". */
    private static String inventory(ClientGameTestContext ctx) {
        java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>();
        serverRun(ctx, s -> {
            var sp = s.getPlayerList().getPlayers().get(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 36; i++) {
                var st = sp.getInventory().getItem(i);
                if (!st.isEmpty()) {
                    sb.append(i).append('=').append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath())
                            .append(" x").append(st.getCount()).append(' ');
                }
            }
            for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
                    net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
                    net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
                sb.append(slot.getName()).append('=').append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(sp.getItemBySlot(slot).getItem()).getPath()).append(' ');
            }
            out.set(sb.toString().trim());
        });
        until(ctx, "an inventory read", mc -> out.get() != null, 20);
        return out.get();
    }

    /** The client's Simon Says grid: {buttons up, of them facing west, lamps lit, cells with both a lamp and a button}. */
    private static int[] ssGrid(Minecraft mc) {
        int[] n = new int[4];
        for (int y = 120; y <= 123; y++) {
            for (int z = 92; z <= 95; z++) {
                var b = mc.level.getBlockState(new BlockPos(110, y, z));
                boolean button = b.is(net.minecraft.world.level.block.Blocks.STONE_BUTTON);
                boolean lit = mc.level.getBlockState(new BlockPos(111, y, z)).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN);
                n[0] += button ? 1 : 0;
                n[1] += button && b.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                        == Direction.WEST ? 1 : 0;
                n[2] += lit ? 1 : 0;
                n[3] += button && lit ? 1 : 0;
            }
        }
        return n;
    }

    private static void fix3(ClientGameTestContext ctx, List<String> f) throws Exception {
        fix3Items(ctx, f);
        fix3Chooser(ctx, f);
        fix3Marks(ctx, f);
        fix3Ss(ctx, f);
        fix3Gates(ctx, f);
    }

    /** killer560: "if you start a phase of the boss you should keep the items you had from any phase". */
    private static void fix3Items(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P1", false);
        serverRun(ctx, s -> {
            var sp = s.getPlayerList().getPlayers().get(0);
            net.minecraft.world.item.Item[] kinds = {net.minecraft.world.item.Items.STICK, net.minecraft.world.item.Items.BONE,
                    net.minecraft.world.item.Items.ARROW, net.minecraft.world.item.Items.FEATHER};
            for (int i = 0; i < 36; i++) {
                sp.getInventory().setItem(i, new net.minecraft.world.item.ItemStack(kinds[i % 4], i + 1));
            }
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_LEGGINGS));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS));
            sp.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        });
        ctx.waitTicks(2);
        String want = inventory(ctx);
        expect(f, "the test's items went in (36 slots + armor)", want.contains("35=feather x36") && want.contains("head=diamond_helmet"), true);
        for (String phase : new String[]{"S2", "P4", "S1", "P1", "P5"}) {
            start(ctx, phase, false);
            ctx.waitTicks(10);
            String got = inventory(ctx);
            if (!got.equals(want)) {
                f.add("items after starting " + phase + ": " + got + " (want " + want + ")");
            } else {
                println(FIX3, "items kept after starting " + phase);
            }
        }
        serverRun(ctx, s -> {
            var sp = s.getPlayerList().getPlayers().get(0);
            for (int i = 0; i < 36; i++) {
                sp.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            }
        });
    }

    /**
     * killer560: "i set up a hotbar in p1 but then in p3 it was a different one. a whole new inventory". Lays out a hotbar
     * and inventory in P1, rearranges it, then starts Section 1 through the F7 Boss chooser's own button: every slot stays.
     */
    private static void fix3Chooser(ClientGameTestContext ctx, List<String> f) throws Exception {
        start(ctx, "P1", false);
        serverRun(ctx, s -> {
            var inv = s.getPlayerList().getPlayers().get(0).getInventory();
            net.minecraft.world.item.Item[] kinds = {net.minecraft.world.item.Items.DIAMOND_SWORD, net.minecraft.world.item.Items.BOW,
                    net.minecraft.world.item.Items.ENDER_PEARL, net.minecraft.world.item.Items.GOLDEN_APPLE, net.minecraft.world.item.Items.TNT};
            for (int i = 0; i < 36; i++) {
                inv.setItem(i, i % 3 == 0 ? net.minecraft.world.item.ItemStack.EMPTY : new net.minecraft.world.item.ItemStack(kinds[i % 5], 1 + i % 7));
            }
            // His rearranging: a swap inside the hotbar and one item moved up into the inventory.
            var a = inv.getItem(1);
            inv.setItem(1, inv.getItem(8));
            inv.setItem(8, a);
            inv.setItem(30, inv.getItem(2));
            inv.setItem(2, net.minecraft.world.item.ItemStack.EMPTY);
        });
        ctx.waitTicks(2);
        String want = inventory(ctx);
        Class<?> screen = Class.forName(B + "BossStartScreen");
        ctx.runOnClient(mc -> {
            try {
                McCompat.setScreen(mc, (net.minecraft.client.gui.screens.Screen) screen.getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        ctx.waitTicks(3);
        String pressed = ctx.computeOnClient(mc -> {
            var sc = McCompat.screen(mc);
            for (var child : sc.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.getMessage().getString().equals("Section 1")) {
                    var ev = new net.minecraft.client.input.MouseButtonEvent(w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0,
                            new net.minecraft.client.input.MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0));
                    sc.mouseClicked(ev, false);
                    if (McCompat.screen(mc) == sc) {
                        sc.mouseReleased(ev);
                    }
                    return "ok";
                }
            }
            return "no Section 1 button on " + sc;
        });
        expect(f, "the chooser's Section 1 button", pressed, "ok");
        boolean p3 = until(ctx, "P3 from the chooser", mc -> "P3".equals(String.valueOf(call(FIGHT, "phase")))
                && Integer.valueOf(1).equals(call(FIGHT, "section")), 400);
        expect(f, "the chooser started P3 Section 1", p3, true);
        ctx.waitTicks(10);
        String got = inventory(ctx);
        expect(f, "every slot after Change Phase to P3", got, want);
        if (got.equals(want)) {
            println(FIX3, "chooser P1 -> S1 kept: " + want);
        }
    }

    /** killer560: "for frames wherever it starts is the green where it ends should be red". */
    private static void fix3Marks(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P1", false);
        hover(ctx, new BlockPos(-2, 122, 77), 3, 0, 90); // in front of the wall, so its frames are on the client
        until(ctx, "the Arrows frames", mc -> !mc.level.getEntitiesOfClass(ItemFrame.class,
                new AABB(-3, 119, 74, -1, 126, 81), fr -> fr.getItem().is(net.minecraft.world.item.Items.ARROW)).isEmpty(), 100);
        int[] sol = new int[25];
        for (int i = 0; i < 25; i++) {
            sol[i] = (Integer) ModUnderTest.staticCall(DEVICES, "arrowSolution", new Class<?>[]{int.class}, new Object[]{i});
        }
        // The test's own reading of the layout: rotation 1/5 = -z/+z, 3/7 = down/up along the wall (the mapping under
        // which every one of the nine layouts' arrows stays on the wall). Red = an empty cell an arrow points into;
        // green = the empty cell behind an arrow nothing points into.
        java.util.Set<Integer> red = new java.util.TreeSet<>(), green = new java.util.TreeSet<>(), heads = new java.util.TreeSet<>();
        for (int i = 0; i < 25; i++) {
            if (sol[i] >= 0) {
                heads.add(i);
            }
        }
        for (int i = 0; i < 25; i++) {
            if (sol[i] < 0) {
                continue;
            }
            int y = i % 5 + (sol[i] == 7 ? 1 : sol[i] == 3 ? -1 : 0), z = i / 5 + (sol[i] == 5 ? 1 : sol[i] == 1 ? -1 : 0);
            if (y < 0 || y > 4 || z < 0 || z > 4) {
                f.add("Arrows: cell " + i + " points off the wall");
                continue;
            }
            heads.remove(y + 5 * z);
            if (sol[y + 5 * z] < 0) {
                red.add(y + 5 * z);
            }
        }
        for (int h : heads) {
            int y = h % 5 - (sol[h] == 7 ? 1 : sol[h] == 3 ? -1 : 0), z = h / 5 - (sol[h] == 5 ? 1 : sol[h] == 1 ? -1 : 0);
            if (y >= 0 && y <= 4 && z >= 0 && z <= 4 && sol[y + 5 * z] < 0) {
                green.add(y + 5 * z);
            }
        }
        java.util.Set<Integer> gotRed = new java.util.TreeSet<>(), gotGreen = new java.util.TreeSet<>();
        int arrows = 0;
        for (int i = 0; i < 25; i++) {
            BlockPos pos = new BlockPos(-2, 120 + i % 5, 75 + i / 5);
            int cell = i;
            String item = ctx.computeOnClient(mc -> {
                ItemFrame fr = frameAt(mc, pos);
                return fr == null ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(fr.getItem().getItem()).getPath();
            });
            switch (item) {
                case "red_wool" -> gotRed.add(cell);
                case "lime_wool" -> gotGreen.add(cell);
                case "arrow" -> arrows++;
                default -> { }
            }
        }
        println(FIX3, "Arrows marks: green " + gotGreen + " red " + gotRed + " (layout's starts " + green + ", ends " + red + "), " + arrows + " arrows");
        expect(f, "Arrows: at least one red and one green frame", !gotRed.isEmpty() && !gotGreen.isEmpty(), true);
        expect(f, "Arrows: red wool where the path ends", gotRed, red);
        if (!gotGreen.containsAll(green)) {
            f.add("Arrows: green wool " + gotGreen + " misses a start behind an arrow " + green);
        }
        expect(f, "Arrows: one frame per arrow", arrows, (int) java.util.Arrays.stream(sol).filter(v -> v >= 0).count());
        serverRun(ctx, s -> {
            var sp = s.getPlayerList().getPlayers().get(0);
            sp.getAbilities().flying = false;
            sp.getAbilities().mayfly = false;
            sp.onUpdateAbilities();
        });
    }

    /** killer560: "the simon says logic is still broken. skip doesnt work and the buttons dont show right". */
    private static void fix3Ss(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", false);
        expect(f, "arena source", call(ARENA, "source"), "capture");
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        tp(ctx, 108.5, 120, 93.5, -90, 0);
        ctx.waitTicks(8);
        // The skip: start, reset 3 ticks later, start 3 ticks after that. Round 1 shows off the first start's task and
        // the second start's adds round 2: the first input is round 2.
        // Paced on the server's ticks (a press is handled on the tick after it is sent): client ticks drift against them.
        BlockPos startButton = new BlockPos(110, 121, 91);
        String[] steps = {"Simon Says started", "Simon Says reset by the start button", "Simon Says started"};
        int[] at = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                untilTick(ctx, at[i - 1] + 2, 20);
            }
            long pm = LogTap.mark();
            useBlock(ctx, startButton, Direction.EAST);
            String step = steps[i];
            if (!until(ctx, step, mc -> LogTap.since(pm).stream().anyMatch(l -> l.contains(step)), 20)) {
                f.add("SS skip press " + (i + 1) + " never landed as '" + step + "' (" + call(DEVICES, "describe") + ")");
                return;
            }
            at[i] = phaseTick();
        }
        println(FIX3, "SS skip presses at P3 t " + at[0] + ", " + at[1] + ", " + at[2]);
        int[] during = new int[]{-1, 0, 0, 0};
        boolean shown = until(ctx, "a lamp of the show", mc -> {
            int[] g = ssGrid(mc);
            if (g[2] > 0) {
                System.arraycopy(g, 0, during, 0, 4);
                return true;
            }
            return false;
        }, 30);
        expect(f, "SS show: a lamp lit on the client", shown, true);
        expect(f, "SS show: no button in front of a lit lamp", during[3], 0);
        if (!until(ctx, "SS input", mc -> (Boolean) call(DEVICES, "ssInput"), 80)) {
            f.add("SS never took input after the skip (" + call(DEVICES, "describe") + ")");
            return;
        }
        expect(f, "SS skip: the first round put in is round 2", ((List<?>) call(DEVICES, "ssSequence")).size(), 2);
        // Two shows overlap in a skip: the buttons come back on the first one's timer, the button under the second one's
        // last lamp with its own (8n+10 after it began), so all 16 stand about 14 ticks into input.
        ctx.waitTicks(18);
        int[] g = ctx.computeOnClient(SimBossTests::ssGrid);
        expect(f, "SS input: 16 buttons up on the client", g[0], 16);
        expect(f, "SS input: every button faces west (on the wall's face toward him)", g[1], 16);
        long dm = LogTap.mark();
        for (int round = 2; round <= 4; round++) {
            int r = round;
            if (!until(ctx, "SS round " + round + " input", mc -> (Boolean) call(DEVICES, "ssInput")
                    && ((List<?>) call(DEVICES, "ssSequence")).size() == r, 200)) {
                f.add("Simon Says never reached round " + round + " (" + call(DEVICES, "describe") + ")");
                return;
            }
            ctx.waitTicks(3);
            @SuppressWarnings("unchecked")
            List<int[]> seq = (List<int[]>) call(DEVICES, "ssSequence");
            for (int[] lantern : seq) {
                useBlock(ctx, new BlockPos(110, lantern[1], lantern[2]), Direction.EAST);
                ctx.waitTicks(3);
            }
        }
        expect(f, "SS done after the skip (completed a device!)", waitChat(ctx, dm, "completed a device!", 40), true);
        expect(f, "SS state", call(DEVICES, "ssState"), "DONE");
        ctx.waitTicks(3);
        int[] after = ctx.computeOnClient(SimBossTests::ssGrid);
        expect(f, "SS done: buttons and lamps gone", after[0] + after[2], 0);
    }

    /**
     * killer560: "once the gate has been destroyed the gate needs to be removed", and a Superboom on a gate destroys it
     * at once, but the way opens only once the section before it is done.
     */
    private static void fix3Gates(ClientGameTestContext ctx, List<String> f) {
        // Continues fix3Ss's S1 with Simon Says done: the rest of S1, then the 5 s countdown and the gate.
        int[] g1 = gateCells(ctx, 1);
        expect(f, "boundary 1 standing before S1 ends (arch, door, portcullis > 0)", g1[0] > 0 && g1[1] > 0 && g1[2] > 0, true);
        long end = LogTap.mark();
        for (String label : new String[]{"T1", "T2", "T3", "T4", "east lever", "west lever"}) {
            serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                    new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, 1, label}));
        }
        expect(f, "S1 done: 'The gate will open in 5 seconds!'", waitChat(ctx, end, "The gate will open in 5 seconds!", 20), true);
        if (!waitChat(ctx, end, "The gate has been destroyed!", 130)) {
            f.add("S1's gate never went (" + call(STATIONS, "describe") + ")");
            return;
        }
        ctx.waitTicks(10);
        int[] a1 = gateCells(ctx, 1);
        expect(f, "boundary 1 after its gate line: arch, door and portcullis all gone", a1[0] + a1[1] + a1[2], 0);
        expect(f, "boundary 1's top (103 134 123) air", serverBlockId(ctx, new BlockPos(103, 134, 123)), "minecraft:air");
        expect(f, "section after S1", call(FIGHT, "section"), 2);

        // A Superboom on gate 2 while S2 is not done: the gate goes at once, the door stays, S2 goes on.
        serverRun(ctx, s -> s.getPlayerList().getPlayers().get(0).setItemInHand(InteractionHand.MAIN_HAND,
                (net.minecraft.world.item.ItemStack) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimItems", "build",
                        new Class<?>[]{String.class}, new Object[]{"SUPERBOOM_TNT"})));
        int[] g2 = gateCells(ctx, 2);
        expect(f, "boundary 2 standing in S2 (arch, door > 0)", g2[0] > 0 && g2[1] > 0, true);
        // In front of the gate there is a drop to y 106: he hovers 3 blocks out from an arch cell on S2's side.
        BlockPos aim = archCell(ctx, 2, new Vec3(25, 120, 132));
        hover(ctx, aim, 3, 0, 90);
        println(FIX3, "Superboom on gate 2 at " + aim + " from " + ctx.computeOnClient(mc -> mc.player.getEyePosition()));
        long boom = LogTap.mark();
        if (aim != null) {
            useBlock(ctx, aim, Direction.EAST);
        }
        expect(f, "Superboom on gate 2 in S2: 'The gate has been destroyed!' at once", waitChat(ctx, boom, "The gate has been destroyed!", 10), true);
        ctx.waitTicks(6);
        int[] b2 = gateCells(ctx, 2);
        expect(f, "gate 2's arch gone", b2[0], 0);
        expect(f, "gate 2's top (17 134 135) air", serverBlockId(ctx, new BlockPos(17, 134, 135)), "minecraft:air");
        expect(f, "door 2 still shut while S2 is not done", b2[1], g2[1]);
        expect(f, "no countdown for an unfinished section", chatSeen(boom, "The gate will open in 5 seconds!"), false);
        expect(f, "section after the early Superboom", call(FIGHT, "section"), 2);

        // A Superboom on gate 3 during S2 does nothing: its section has not begun.
        int[] g3 = gateCells(ctx, 3);
        BlockPos aim3 = archCell(ctx, 3, new Vec3(8, 120, 57));
        hover(ctx, aim3, 0, 3, 180);
        println(FIX3, "Superboom on gate 3 at " + aim3 + " from " + ctx.computeOnClient(mc -> mc.player.getEyePosition()));
        long boom3 = LogTap.mark();
        if (aim3 != null) {
            useBlock(ctx, aim3, Direction.SOUTH);
        }
        ctx.waitTicks(10);
        expect(f, "Superboom on gate 3 during S2: no gate line", chatSeen(boom3, "The gate has been destroyed!"), false);
        expect(f, "gate 3 untouched (arch cells as before)", gateCells(ctx, 3)[0], g3[0]);
        expect(f, "gate 3's (5 134 49) still cracked stone bricks", serverBlockId(ctx, new BlockPos(5, 134, 49)), "minecraft:cracked_stone_bricks");

        // S2 done with its gate already down: the door opens at once, no countdown.
        long done2 = LogTap.mark();
        for (String label : new String[]{"T1", "T2", "T3", "T4", "T5", "low lever", "high lever", "Lights"}) {
            serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                    new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, 2, label}));
        }
        boolean next = until(ctx, "S3 in progress", mc -> Integer.valueOf(3).equals(call(FIGHT, "section")), 20);
        expect(f, "S2 done with its gate down: S3 begins at once", next, true);
        ctx.waitTicks(8);
        expect(f, "S2 done with its gate down: no countdown", chatSeen(done2, "The gate will open in 5 seconds!"), false);
        int[] d2 = gateCells(ctx, 2);
        expect(f, "door 2 open (door and portcullis gone)", d2[1] + d2[2], 0);
        serverRun(ctx, s -> {
            var sp = s.getPlayerList().getPlayers().get(0);
            sp.getAbilities().mayfly = sp.getAbilities().flying = false;
            sp.onUpdateAbilities();
        });
    }

    private static void p1p2(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "P1", true);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 40)) {
            f.add("Maxor's first line never came");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "DungeonState boss phase after Maxor's line", call(DUNGEON, "isBossPhaseActive"), true);
        expect(f, "Floor7Tracker phase in P1", phase(ctx), "P1");
        if (!until(ctx, "both crystals on their towers", mc -> (Boolean) fight("crystalOnTower", 0) && (Boolean) fight("crystalOnTower", 1), 120)) {
            f.add("the crystals never appeared");
            return;
        }
        for (int round = 1; round <= 2; round++) {
            if (round == 2 && !until(ctx, "crystals back for round 2", mc -> (Boolean) fight("crystalOnTower", 0), 200)) {
                f.add("the crystals never came back after the first stun");
                return;
            }
            long m = LogTap.mark();
            if (!pickUpCrystals(ctx, f, "round " + round)) {
                return;
            }
            if (!placeCrystals(ctx, f, m, "round " + round)) {
                return;
            }
            if (!waitChat(ctx, m, "The Energy Laser is charging up!", 60)) {
                f.add("round " + round + ": the laser never charged");
                return;
            }
            if (!lureMaxor(ctx, f, m, "round " + round)) {
                return;
            }
            if (round == 1) {
                for (int h = 0; h < 5; h++) {
                    serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Maxor"}));
                    ctx.waitTicks(2);
                }
                if (!waitChat(ctx, m, "Maxor is enraged!", 40)) {
                    f.add("five hits in his stun never ended it (Hypixel: the stun ends on damage)");
                }
            }
        }
        long dead = LogTap.mark();
        expect(f, "two laser hits", call(FIGHT, "maxorLaserHits"), 2);
        // He dies 8-15 ticks after the second hit and his floor goes 59 after that. "I'M TOO YOUNG TO DIE AGAIN!" comes
        // 82 after the second stun line only while he is still there (before kill + 80): with a kill 8 ticks after the
        // hit it never comes (ADM), so the floor is the verdict and the line is logged.
        boolean hole = until(ctx, "Maxor's floor opening", mc -> mc.level.getBlockState(new BlockPos(73, 220, 63)).isAir(), 120);
        println(P1P2, "too young line: " + waitChat(ctx, LogTap.mark() - 200, "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!", 40));
        expect(f, "Maxor's floor open over Storm", hole, true);
        tp(ctx, 73.5, 221, 63.5, 0, 0);
        if (!waitChat(ctx, dead, "[BOSS] Storm: Pathetic Maxor, just like expected.", 200)) {
            f.add("Storm's first line never came after the fall");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "Floor7Tracker phase in P2", phase(ctx), "P2");
        // Owner 2026-11-03: the crystal counter showed 0/2 for the rest of the run after Maxor. Not vacuous: the run
        // must have read a crystal line (total > 0), and the HUD must not show it now.
        String crys = counter(ctx);
        boolean shown = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(MAXOR, "counterShown"));
        println(P1P2, "crystal counter in P2: " + crys + ", shown " + shown);
        if (!crys.endsWith("/0")) {
            expect(f, "crystal counter shown in P2", shown, false);
        } else {
            f.add("no crystal line was read in P1 (counter " + crys + "), so the P2 check would prove nothing");
        }
        // Two crushes: Storm under Purple, then Yellow, and a real stand on that pillar's pad each time.
        String[][] pads = {{"Purple", "114.5", "94.5"}, {"Yellow", "32.5", "94.5"}};
        for (int i = 0; i < 2; i++) {
            String pillar = pads[i][0];
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testParkStorm", new Class<?>[]{String.class}, new Object[]{pillar}));
            tp(ctx, Double.parseDouble(pads[i][1]), 170, Double.parseDouble(pads[i][2]), 0, 0);
            int want = i + 1;
            if (!until(ctx, "crush " + want, mc -> (Integer) ModUnderTest.staticCall(FIGHT, "stormCrushes") >= want, 260)) {
                f.add("standing on the " + pillar + " pad with Storm under it never crushed him (" + call(FIGHT, "stormState") + ")");
                return;
            }
            tp(ctx, 73.5, 169, 50.5, 0, 0);
            ctx.waitTicks(5);
        }
        long d2 = LogTap.mark() - 50;
        if (!waitChat(ctx, d2, "[BOSS] Storm: I should have known that I stood no chance.", 40)) {
            f.add("Storm's death line never came after two crushes");
            return;
        }
        // Hypixel's drop to S1 is under the Red pillar, and opens 24-30 ticks after Goldor's line.
        tp(ctx, 100.5, 169, 40.5, 0, 0);
        if (!waitChat(ctx, d2, "[BOSS] Goldor: Who dares trespass into my domain?", 140)) {
            f.add("Goldor's line never came after Storm's death");
            return;
        }
        until(ctx, "the fall to S1", mc -> mc.player.getY() < 120, 90);
        ctx.waitTicks(10);
        expect(f, "Floor7Tracker phase in P3", phase(ctx), "P3");
        expect(f, "P3SectionTracker running", call(SECTIONS, "running"), true);
        expect(f, "P3SectionTracker section", call(SECTIONS, "section"), 1);
        double y = ctx.computeOnClient(mc -> mc.player.getY());
        println(P1P2, "after the drop from Storm's floor: y " + y);
        if (y > 120) {
            f.add("the fall from Storm's floor did not reach S1 (y " + y + ")");
        }
    }

    // ---------------------------------------------------------------------------------------- 1108-boss-fresh

    /**
     * bosssim1108 / coopnet-1110: every instance (and every co-op host) builds his capture, bundled in the jar; a capture
     * file left in the shared store or the config folder changes nothing.
     */
    private static void fresh(ClientGameTestContext ctx, List<String> f) throws Exception {
        java.nio.file.Path shared = ((java.nio.file.Path) ModUnderTest.staticCall("com.killer560.hub.shared.SharedRecords", "root",
                new Class<?>[0], new Object[0])).resolve("sim-boss").resolve("killer560smod-sim-bossarena.bin");
        java.nio.file.Path legacy = (java.nio.file.Path) ModUnderTest.staticCall("com.killer560.hub.util.ModPaths", "config",
                new Class<?>[]{String.class}, new Object[]{"killer560smod-sim-bossarena.bin"});
        for (java.nio.file.Path p : new java.nio.file.Path[]{shared, legacy}) {
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, "not a capture");
        }
        try {
            start(ctx, "P1", false);
            expect(f, "arena source with stray capture files on disk", call(ARENA, "source"), "capture");
        } finally {
            java.nio.file.Files.deleteIfExists(shared);
            java.nio.file.Files.deleteIfExists(legacy);
        }
    }

    // ---------------------------------------------------------------------------------------- 1104-boss-capture

    private static void capture(ClientGameTestContext ctx, List<String> f) throws Exception {
        withCapture(f, () -> captureCase(ctx, f));
    }

    /** Runs {@code body} on his capture: since coopnet-1110 it is bundled in the jar and always used, nothing to install. */
    static void withCapture(List<String> f, Runnable body) throws Exception {
        body.run();
    }

    private static void captureCase(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P1", false);
        expect(f, "arena source", call(ARENA, "source"), "capture");
        Object cap = call(ARENA, "readCapture");
        if (cap == null) {
            f.add("the mod could not read the fixture");
            return;
        }
        // The whole room against the capture, right after the build.
        int[] diff = diff(ctx, cap, false);
        expect(f, "blocks differing from the capture after the build", diff[0], 0);
        if (diff[1] == 0) {
            f.add("the capture holds no lava, so the still-lava check proves nothing");
        }
        // The owner's spots (2026-11-04), each read on the server.
        String[][] spots = {
                {"100,168,40", "minecraft:red_terracotta", "Storm's floor where the gray pad was"},
                {"100,125,122", "minecraft:barrier", "S1's gate (barriers, no iron wall)"},
                {"60,134,143", "minecraft:redstone_lamp", "a Lights lamp"},
                {"63,127,35", "minecraft:light_weighted_pressure_plate", "Target's plate"},
                {"63,126,35", "minecraft:blue_terracotta", "the block under Target's plate"},
                {"111,119,91", "minecraft:sea_lantern", "Simon Says' frame"},
                {"111,121,93", "minecraft:obsidian", "a Simon Says lamp cell"},
                {"100,180,65", "minecraft:polished_diorite", "Storm's Purple pillar (the capture's I Hate Diorite glass undone)"}};
        for (String[] sp : spots) {
            String[] c = sp[0].split(",");
            BlockPos pos = new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
            expect(f, sp[2] + " at " + sp[0], serverBlockId(ctx, pos), sp[1]);
        }
        // The Arrows frames hang from P1 (Hypixel's are entities; the capture has none).
        int want = 0;
        for (int i = 0; i < 25; i++) {
            if ((Integer) ModUnderTest.staticCall(DEVICES, "arrowSolution", new Class<?>[]{int.class}, new Object[]{i}) >= 0) {
                want++;
            }
        }
        tp(ctx, -0.5, 120, 77.5, 90, 0);
        int need = want;
        boolean hung = until(ctx, "the Arrows frames in P1", mc -> mc.level.getEntitiesOfClass(ItemFrame.class,
                new AABB(-3, 119, 74, 0, 126, 81), fr -> fr.getItem().is(net.minecraft.world.item.Items.ARROW)).size() == need, 60);
        expect(f, "Arrows frames with arrows in P1 (want " + want + ")", hung && want > 0, true);
        // Lava stands still: 200 ticks on, no fluid cell differs, and the hook canceled ticks.
        ctx.waitTicks(200);
        int[] later = diff(ctx, cap, true);
        expect(f, "fluid cells differing from the capture after 200 ticks", later[0], 0);
        // The build places blocks without onPlace, so no fluid tick was due; wake a falling lava cell and see it held.
        BlockPos fall = new BlockPos(later[2], later[3], later[4]);
        long frozen0 = (Long) call(STATIC_WATER, "frozenTicks");
        serverRun(ctx, s -> s.overworld().scheduleTick(fall, s.overworld().getFluidState(fall).getType(), 1));
        ctx.waitTicks(40);
        expect(f, "a woken lava tick at " + fall.toShortString() + " canceled", (Long) call(STATIC_WATER, "frozenTicks") > frozen0, true);
        expect(f, "fluid cells differing 40 ticks after the lava tick", diff(ctx, cap, true)[0], 0);
        // Lights on the real wall: a pull lights by Hypixel's rule, on redstone lamps, and nothing dims it after.
        start(ctx, "S2", false);
        tp(ctx, 60.5, 132, 140.5, 180, -20);
        ctx.waitTicks(8);
        BlockPos pull = null;
        for (int y = 133; y <= 136 && pull == null; y++) {
            for (int x = 58; x <= 62 && pull == null; x++) {
                BlockPos p = new BlockPos(x, y, 142);
                if (!ctx.computeOnClient(mc -> mc.level.getBlockState(p).getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED))) {
                    pull = p;
                }
            }
        }
        if (pull == null) {
            f.add("Lights started with all twenty levers on");
        } else {
            BlockPos lever = pull;
            useBlock(ctx, pull, Direction.NORTH);
            ctx.waitTicks(4);
            // With most levers on, a pull may light nothing new (its lamps are lit by neighbours); the lever must move.
            expect(f, "Lights: the pulled lever is on", ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)), true);
            expect(f, "Lights: every lamp by Hypixel's rule after a pull", lightsRuleHolds(ctx), "ok");
            ctx.waitTicks(12);
            expect(f, "Lights: still by the rule 12 ticks later (no vanilla dimming)", lightsRuleHolds(ctx), "ok");
        }
        // P3 from the P3 capture: its band equals it, and S1's spawn stands on the captured ground.
        long mark = start(ctx, "S1", false);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        Object cap3 = ModUnderTest.staticCall(ARENA, "readCapture", new Class<?>[]{String.class}, new Object[]{"P3"});
        if (cap3 == null) {
            f.add("the mod could not read the P3 fixture");
            return;
        }
        expect(f, "P3 band cells differing from the P3 capture at S1 (Lights left out)", diff(ctx, cap3, false, 100, 145)[0], 0);
        // Maxor's and Storm's holes as the P3 capture shows them (solid in P1, air in P3), open at S1.
        int[] holes = holesLeft(ctx, ModUnderTest.staticCall(ARENA, "readCapture", new Class<?>[]{String.class}, new Object[]{"P1"}), cap3);
        println(CAPTURE, "boss holes: " + holes[0] + " cell(s), " + holes[1] + " still solid");
        expect(f, "Maxor's and Storm's holes from the P3 capture (cells > 500, none left solid)", holes[0] > 500 && holes[1] == 0, true);
        ctx.waitTicks(10);
        double[] stand = ctx.computeOnClient(mc -> new double[]{mc.player.getY(), mc.player.onGround() ? 1 : 0});
        println(CAPTURE, "S1 spawn: y " + stand[0] + " on ground " + stand[1]);
        expect(f, "S1 spawn on the captured ground (y 116.0625, the carpet), on ground",
                Math.abs(stand[0] - 116.0625) < 0.01 && stand[1] == 1, true);
        // Simon Says on the real wall, played through.
        int failsBefore = f.size();
        simonSays(ctx, f);
        if (f.size() == failsBefore) {
            boolean done = until(ctx, "Simon Says done", mc -> String.valueOf(call(DEVICES, "describe")).contains("ss=DONE"), 60);
            expect(f, "Simon Says done on the captured wall (" + call(DEVICES, "describe") + ")", done, true);
        }
        // A gate opening clears its barriers and leaves the room's wall in its box.
        serverRun(ctx, s -> ModUnderTest.staticCall(B + "BossBlocks", "open",
                new Class<?>[]{net.minecraft.server.level.ServerLevel.class, int[].class},
                new Object[]{s.overworld(), new int[]{89, 107, 122, 113, 134, 124}}));
        ctx.waitTicks(2);
        expect(f, "S1 gate opened: its barrier", serverBlockId(ctx, new BlockPos(100, 125, 122)), "minecraft:air");
        expect(f, "S1 gate opened: the wall beside it", serverBlockId(ctx, new BlockPos(89, 125, 122)), "minecraft:stone_bricks");
        // The core door over the capture: opening the core removes its 35 gold blocks (x 52-56, y 115-121, z 54), nothing else.
        String[] before = serverBox(ctx, 50, 113, 53, 58, 123, 55);
        start(ctx, "CORE", false);
        ctx.waitTicks(3);
        String[] after = serverBox(ctx, 50, 113, 53, 58, 123, 55);
        int goldGone = 0, otherChanged = 0;
        for (int i = 0; i < before.length; i++) {
            boolean gold = before[i].equals("minecraft:gold_block");
            goldGone += gold && after[i].equals("minecraft:air") ? 1 : 0;
            otherChanged += !gold && !before[i].equals(after[i]) ? 1 : 0;
        }
        expect(f, "core door gold blocks gone on core open", goldGone, 35);
        expect(f, "other cells around the core door changed on core open", otherChanged, 0);
        // The core's floor over the capture is its 7x7 gold and bedrock plug at y 113 over an open shaft to P4 (63).
        expect(f, "core plug before P4 (54 113 114)", serverBlockId(ctx, new BlockPos(54, 113, 114)), "minecraft:gold_block");
        start(ctx, "P4", false);
        int solid = 0;
        for (String id : serverBox(ctx, 51, 113, 111, 57, 113, 117)) {
            solid += id.equals("minecraft:air") ? 0 : 1;
        }
        for (String id : serverBox(ctx, 54, 64, 114, 54, 112, 114)) {
            solid += id.equals("minecraft:air") ? 0 : 1;
        }
        expect(f, "solid cells left in the core plug and the shaft under it at P4", solid, 0);
        ctx.waitTicks(10);
        double[] p4 = ctx.computeOnClient(mc -> new double[]{mc.player.getY(), mc.player.onGround() ? 1 : 0});
        println(CAPTURE, "P4 spawn: y " + p4[0] + " on ground " + p4[1]);
        expect(f, "P4 spawn on the captured platform (y 64), on ground", Math.abs(p4[0] - 64) < 0.01 && p4[1] == 1, true);
    }

    /** {differing cells, lava cells in the capture, x y z of a falling lava cell}; each kind of difference is printed. Fluid cells only when asked. */
    private static int[] diff(ClientGameTestContext ctx, Object cap, boolean fluidOnly) {
        return diff(ctx, cap, fluidOnly, 0, 255);
    }

    /** As {@link #diff(ClientGameTestContext, Object, boolean)}, over y {@code yFrom..yTo} only; a band below the whole
     *  box leaves out the Lights levers and lamps, whose start the sim deals itself (a device mechanic). */
    private static int[] diff(ClientGameTestContext ctx, Object cap, boolean fluidOnly, int yFrom, int yTo) {
        java.util.concurrent.atomic.AtomicReference<int[]> out = new java.util.concurrent.atomic.AtomicReference<>();
        int x0 = (Integer) acc(cap, "x0"), y0 = (Integer) acc(cap, "y0"), z0 = (Integer) acc(cap, "z0");
        int w = (Integer) acc(cap, "w"), d = (Integer) acc(cap, "d");
        net.minecraft.world.level.block.state.BlockState[] states = (net.minecraft.world.level.block.state.BlockState[]) acc(cap, "states");
        short[] cells = (short[]) acc(cap, "cells");
        serverRun(ctx, s -> {
            var level = s.overworld();
            java.util.Map<String, Integer> pairs = new java.util.TreeMap<>();
            int n = 0, lava = 0, fx = 0, fy = 0, fz = 0;
            boolean fall = false;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = Math.max(0, (yFrom - y0) * w * d); i < Math.min(cells.length, (yTo - y0 + 1) * w * d); i++) {
                var want = states[cells[i]];
                pos.set(x0 + i % w, y0 + i / (w * d), z0 + (i / w) % d);
                var got = level.getBlockState(pos);
                if (yTo < 255 && pos.getZ() >= 142 && pos.getZ() <= 143 && pos.getX() >= 58 && pos.getX() <= 62
                        && pos.getY() >= 133 && pos.getY() <= 136) {
                    continue;
                }
                if (want.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                    lava++;
                    if (fy == 0 || !fall && !want.getFluidState().isSource()) {
                        fx = pos.getX(); fy = pos.getY(); fz = pos.getZ();
                        fall = !want.getFluidState().isSource();
                    }
                }
                if (got == want || fluidOnly && got.getFluidState().isEmpty() && want.getFluidState().isEmpty()) {
                    continue;
                }
                n++;
                String k = want + " -> " + got;
                if (pairs.merge(k, 1, Integer::sum) == 1 && pairs.size() <= 40) {
                    println(CAPTURE, "differs at " + pos.toShortString() + ": " + k);
                }
            }
            pairs.forEach((k, v) -> println(CAPTURE, "  " + v + " x " + k));
            out.set(new int[]{n, lava, fx, fy, fz});
        });
        until(ctx, "the arena diff", mc -> out.get() != null, 600);
        int[] r = out.get();
        println(CAPTURE, (fluidOnly ? "fluid " : "") + "diff: " + (r == null ? "none" : r[0] + " cell(s), capture lava " + r[1]));
        return r == null ? new int[]{-1, 0, 0, 0, 0} : r;
    }

    private static Object acc(Object rec, String name) {
        try {
            var m = rec.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(rec);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("capture record has no " + name, e);
        }
    }

    /** {cells solid in the P1 capture and air in the P3 one over y 214-224 and 160-170, of those not air on the server}. */
    private static int[] holesLeft(ClientGameTestContext ctx, Object cap1, Object cap3) {
        java.util.concurrent.atomic.AtomicReference<int[]> out = new java.util.concurrent.atomic.AtomicReference<>();
        int x0 = (Integer) acc(cap3, "x0"), y0 = (Integer) acc(cap3, "y0"), z0 = (Integer) acc(cap3, "z0");
        int w = (Integer) acc(cap3, "w"), d = (Integer) acc(cap3, "d");
        var s1 = (net.minecraft.world.level.block.state.BlockState[]) acc(cap1, "states");
        var s3 = (net.minecraft.world.level.block.state.BlockState[]) acc(cap3, "states");
        short[] c1 = (short[]) acc(cap1, "cells"), c3 = (short[]) acc(cap3, "cells");
        serverRun(ctx, s -> {
            int n = 0, left = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int[] band : new int[][]{{214, 224}, {160, 170}}) {
                for (int i = (band[0] - y0) * w * d; i < (band[1] - y0 + 1) * w * d; i++) {
                    var a = s1[c1[i]];
                    if (s3[c3[i]].isAir() && !a.isAir() && a.getFluidState().isEmpty()) {
                        n++;
                        left += s.overworld().getBlockState(pos.set(x0 + i % w, y0 + i / (w * d), z0 + (i / w) % d)).isAir() ? 0 : 1;
                    }
                }
            }
            out.set(new int[]{n, left});
        });
        until(ctx, "the holes read", mc -> out.get() != null, 200);
        return out.get() == null ? new int[]{0, -1} : out.get();
    }

    /** The block ids of a box on the server, x fastest, then z, then y, in one server read. */
    private static String[] serverBox(ClientGameTestContext ctx, int x1, int y1, int z1, int x2, int y2, int z2) {
        java.util.concurrent.atomic.AtomicReference<String[]> out = new java.util.concurrent.atomic.AtomicReference<>();
        serverRun(ctx, s -> {
            String[] ids = new String[(x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1)];
            int i = 0;
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    for (int x = x1; x <= x2; x++) {
                        ids[i++] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                s.overworld().getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
                    }
                }
            }
            out.set(ids);
        });
        until(ctx, "a server box read", mc -> out.get() != null, 20);
        return out.get();
    }

    private static String serverBlockId(ClientGameTestContext ctx, BlockPos pos) {
        java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>();
        serverRun(ctx, s -> out.set(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                s.overworld().getBlockState(pos).getBlock()).toString()));
        until(ctx, "a server block read", mc -> out.get() != null, 20);
        return out.get();
    }

    private static boolean litLamp(net.minecraft.world.level.block.state.BlockState st) {
        return st.is(net.minecraft.world.level.block.Blocks.REDSTONE_LAMP)
                && st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
    }

    private static boolean[] lampsLit(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            boolean[] lit = new boolean[20];
            for (int i = 0; i < 20; i++) {
                lit[i] = litLamp(mc.level.getBlockState(new BlockPos(58 + i % 5, 133 + i / 5, 143)));
            }
            return lit;
        });
    }

    /** "ok", or the first lamp that is not a redstone lamp lit exactly when its lever or a neighbour's is on. */
    private static String lightsRuleHolds(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            for (int y = 133; y <= 136; y++) {
                for (int x = 58; x <= 62; x++) {
                    boolean on = false;
                    for (int[] o : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int lx = x + o[0], ly = y + o[1];
                        if (lx >= 58 && lx <= 62 && ly >= 133 && ly <= 136) {
                            var st = mc.level.getBlockState(new BlockPos(lx, ly, 142));
                            on |= st.is(net.minecraft.world.level.block.Blocks.LEVER) && st.getValue(
                                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
                        }
                    }
                    var lamp = mc.level.getBlockState(new BlockPos(x, y, 143));
                    if (!lamp.is(net.minecraft.world.level.block.Blocks.REDSTONE_LAMP) || litLamp(lamp) != on) {
                        return "lamp " + x + "," + y + " is " + lamp + ", want lit=" + on;
                    }
                }
            }
            return "ok";
        });
    }

    private static void crystalWalk(ClientGameTestContext ctx, List<String> f) {
        long m = start(ctx, "P1", true);
        if (!until(ctx, "both crystals on their towers", mc -> (Boolean) fight("crystalOnTower", 0) && (Boolean) fight("crystalOnTower", 1), 160)) {
            f.add("the crystals never appeared");
            return;
        }
        tp(ctx, 64.5, 238, 48.5, 0, 0);
        ctx.waitTicks(5);
        expect(f, "2 blocks from a crystal: still on its tower", fight("crystalOnTower", 0), true);
        expect(f, "2 blocks from a crystal: nothing picked up", count(m, "picked up an Energy Crystal!"), 0);
        tp(ctx, 63.5, 238, 49.5, 0, 0);
        ctx.waitTicks(3);
        expect(f, "walk-in: crystal 0 left its tower", fight("crystalOnTower", 0), false);
        expect(f, "walk-in: picked up once", count(m, "picked up an Energy Crystal!"), 1);
        expect(f, "walk-in: star in slot 8", ctx.computeOnClient(mc -> mc.player.getInventory().getItem(8).getHoverName().getString().contains("Energy Crystal")), true);
        until(ctx, "the pylons open (P1 t 80)", mc -> (Integer) call(FIGHT, "phaseTick") > 81, 200);
        tp(ctx, 52.5, 224, 43.5, 180, 0);
        ctx.waitTicks(5);
        expect(f, "2 blocks from a pad: nothing placed", count(m, "Energy Crystals are now active!"), 0);
        tp(ctx, 52.5, 224, 42.5, 180, 0);
        ctx.waitTicks(3);
        expect(f, "walk-in: placed at the pad", count(m, "1/2 Energy Crystals are now active!"), 1);
        expect(f, "walk-in: star gone from slot 8", ctx.computeOnClient(mc -> mc.player.getInventory().getItem(8).getHoverName().getString().contains("Energy Crystal")), false);
        cleanup(ctx);
        m = start(ctx, "P1", true);
        if (!until(ctx, "both crystals on their towers again", mc -> (Boolean) fight("crystalOnTower", 0) && (Boolean) fight("crystalOnTower", 1), 160)) {
            f.add("the crystals never appeared (second start)");
            return;
        }
        tp(ctx, 64.5, 238, 47.5, 0, 0);
        clickCrystal(ctx, 64.5, 238.375, 50.5);
        ctx.waitTicks(3);
        expect(f, "right click from 3 blocks: crystal 0 gone", fight("crystalOnTower", 0), false);
        tp(ctx, 82.5, 238, 47.5, 0, 0);
        boolean hit = ctx.computeOnClient(mc -> {
            for (net.minecraft.world.entity.boss.enderdragon.EndCrystal c : mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, new AABB(81, 237, 49, 84, 240, 52))) {
                mc.gameMode.attack(mc.player, c);
                return true;
            }
            return false;
        });
        ctx.waitTicks(3);
        expect(f, "left click: a crystal was there to hit", hit, true);
        expect(f, "left click from 3 blocks: crystal 1 gone", fight("crystalOnTower", 1), false);
        expect(f, "clicks: two pickups", count(m, "picked up an Energy Crystal!"), 2);
    }

    private static void maxorCrystals(ClientGameTestContext ctx, List<String> f) {
        ctx.runOnClient(mc -> ModUnderTest.set(ModUnderTest.config("com.killer560.hub.maxor.MaxorConfig"), "setActiveCounter", true));
        long mark = start(ctx, "P1", true);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 40)
                || !until(ctx, "both crystals on their towers", mc -> (Boolean) fight("crystalOnTower", 0) && (Boolean) fight("crystalOnTower", 1), 120)) {
            f.add("P1 or its first crystals never came");
            return;
        }
        long m = LogTap.mark();
        if (!pickUpCrystals(ctx, f, "first set") || !placeCrystals(ctx, f, m, "first set")) {
            return;
        }
        ctx.waitTicks(1);
        expect(f, "counter after the 2/2 line", counter(ctx), "2/2");
        if (!lureMaxor(ctx, f, m, "first set")) {
            return;
        }
        // Every tick from the laser line on: the counter, and whether a crystal is on a tower in the CLIENT's world.
        String before = null;
        int ticks = 0;
        boolean seen = false;
        String atSpawn = null;
        for (; ticks < 120 && !seen; ticks++) {
            ctx.waitTick();
            String c = counter(ctx);
            seen = ctx.computeOnClient(mc -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EndCrystal.class,
                    new AABB(55, 230, 40, 92, 245, 60)).isEmpty());
            if (seen) {
                atSpawn = c;
            } else {
                before = c;
                if (!"2/2".equals(c)) {
                    f.add("the counter left 2/2 (" + c + ") " + ticks + " tick(s) after the laser line, before the next set was on a tower");
                    return;
                }
            }
        }
        println(MAXOR_CRYS, "next set on a tower " + ticks + " tick(s) after the laser line; counter the tick before " + before
                + ", on that tick " + atSpawn);
        if (!seen) {
            f.add("the next set never appeared on its towers within 120 ticks of the laser line");
            return;
        }
        expect(f, "counter on the tick the next set is on a tower", atSpawn, "0/2");
        boolean byTower = false;
        for (String l : LogTap.since(m)) {
            if (l.contains("[Maxor] next crystal set up (crystal on a tower")) {
                byTower = true;
            }
        }
        expect(f, "reset by the tower crystal (not the backstop)", byTower, true);
        long m2 = LogTap.mark();
        tp(ctx, 63.5, 238, 49.5, 0, 0);
        ctx.waitTicks(3);
        clickCrystal(ctx, 64.5, 238.375, 50.5);
        ctx.waitTicks(3);
        tp(ctx, 52.5, 221, 44.5, 180, 30);
        ctx.waitTicks(5);
        useBlock(ctx, new BlockPos(52, 223, 42), Direction.SOUTH);
        if (!waitChat(ctx, m2, "1/2 Energy Crystals are now active!", 20)) {
            f.add("placing one crystal of the next set never said 1/2");
            return;
        }
        ctx.waitTicks(1);
        expect(f, "counter after one of the next set placed", counter(ctx), "1/2");
    }

    private static String counter(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            int[] c = (int[]) ModUnderTest.staticCall(MAXOR, "activeCounter");
            return c[0] + "/" + c[1];
        });
    }

    private static void p3(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setAutoTerminalsEnabled", true);
            for (String s : new String[]{"Panes", "Rubix", "Numbers", "StartsWith", "Select", "Melody"}) {
                ModUnderTest.set(cfg, "setAuto" + s + "Enabled", true);
                ModUnderTest.set(cfg, "set" + s + "Enabled", true);
            }
        });
        ctx.waitTicks(5);
        String[] types = {"NUMBERS", "PANES", "RUBIX", "STARTS_WITH", "SELECT", "MELODY"};
        int typeIndex = 0;
        int[][] dirs = {{-1, 0}, {0, -1}, {1, 0}, {0, 1}};
        int[] sectionsSeen = new int[6];
        for (int sec = 1; sec <= 4; sec++) {
            int section = sec;
            expect(f, "P3SectionTracker at the start of S" + sec, call(SECTIONS, "section"), sec);
            sectionsSeen[sec] = (Integer) call(SECTIONS, "section");
            // ---- terminals, opened with a real click and solved by the mod's Auto Terminal ----
            for (String label : new String[]{"T1", "T2", "T3", "T4", "T5"}) {
                double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class},
                        new Object[]{sec, label});
                if (at == null) {
                    continue;
                }
                String type = types[typeIndex++ % types.length];
                System.setProperty("killer560.bosssim.terminal", type);
                int[] d = dirs[sec - 1];
                tp(ctx, Math.floor(at[0]) + d[0] * 2 + 0.5, at[1], Math.floor(at[2]) + d[1] * 2 + 0.5, 0, 0);
                ctx.waitTicks(8);
                long m = LogTap.mark();
                int solvedBefore = (Integer) call(TERMS, "solvedCount");
                boolean clicked = ctx.computeOnClient(mc -> {
                    Entity stand = standAt(mc, at, "Inactive Terminal");
                    if (stand == null) {
                        return false;
                    }
                    println(P3, String.format(Locale.ROOT, "click stand #%d at %.2f %.2f %.2f from %.2f %.2f %.2f",
                            stand.getId(), stand.getX(), stand.getY(), stand.getZ(), mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                    mc.gameMode.interact(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, 1, 0)), InteractionHand.MAIN_HAND);
                    return true;
                });
                if (!clicked) {
                    f.add("S" + sec + " " + label + ": no Inactive Terminal stand at its spot");
                    continue;
                }
                int openedBefore = (Integer) call(TERMS, "openedCount");
                if (!until(ctx, "S" + sec + " " + label + " opened", mc -> (Integer) call(TERMS, "openedCount") > openedBefore, 20)) {
                    // One more click, from a block nearer (logged, so a second click is visible in the result).
                    println(P3, "S" + sec + " " + label + ": first click opened nothing, clicking again from 1 block");
                    tp(ctx, Math.floor(at[0]) + d[0] + 0.5, at[1], Math.floor(at[2]) + d[1] + 0.5, 0, 0);
                    ctx.waitTicks(5);
                    ctx.runOnClient(mc -> {
                        Entity stand = standAt(mc, at, "Inactive Terminal");
                        if (stand != null) {
                            mc.gameMode.interact(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, 1, 0)),
                                    InteractionHand.MAIN_HAND);
                        }
                    });
                }
                if (!until(ctx, "S" + sec + " " + label + " solved", mc -> (Integer) call(TERMS, "solvedCount") > solvedBefore, 600)) {
                    f.add("S" + sec + " " + label + " (" + type + "): the mod's Auto Terminal never solved it");
                    ctx.runOnClient(mc -> {
                        if (mc.player.containerMenu != mc.player.inventoryMenu) {
                            mc.player.closeContainer();
                        }
                    });
                    serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                            new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{s, section, label}));
                    continue;
                }
                if (!waitChat(ctx, m, "activated a terminal!", 20)) {
                    f.add("S" + sec + " " + label + ": solved but no 'activated a terminal!' line");
                }
                println(P3, "S" + sec + " " + label + " " + type + " solved by Auto Terminal");
            }
            System.clearProperty("killer560.bosssim.terminal");
            // ---- levers, real clicks ----
            for (String label : sec == 1 || sec == 3 ? new String[]{"east lever", "west lever"} : new String[]{"low lever", "high lever"}) {
                double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class},
                        new Object[]{sec, label});
                int[] d = dirs[sec - 1];
                BlockPos lever = BlockPos.containing(at[0], Math.round(at[1] - 0.688), at[2]);
                tp(ctx, lever.getX() + d[0] * 2 + 0.5, lever.getY(), lever.getZ() + d[1] * 2 + 0.5, 0, 30);
                ctx.waitTicks(8);
                long m = LogTap.mark();
                useBlock(ctx, lever, Direction.UP);
                if (!waitChat(ctx, m, "activated a lever!", 20)) {
                    f.add("S" + sec + " " + label + ": a click on the lever said nothing");
                }
            }
            // ---- the section's device ----
            long dm = LogTap.mark();
            switch (sec) {
                case 1 -> simonSays(ctx, f);
                case 2 -> lights(ctx, f);
                case 3 -> arrows(ctx, f);
                default -> target(ctx, f);
            }
            if (!waitChat(ctx, dm, "completed a device!", 40)) {
                f.add("S" + sec + ": the device never said 'completed a device!'");
                serverRun(ctx, s -> ModUnderTest.staticCall(STATIONS, "testComplete",
                        new Class<?>[]{MinecraftServer.class, int.class, String.class},
                        new Object[]{s, section, new String[]{"SS", "Lights", "Arrows", "Target"}[section - 1]}));
            }
            // ---- the section ends ----
            long end = LogTap.mark() - 30;
            if (sec < 4) {
                if (!waitChat(ctx, end, "The gate has been destroyed!", 160)) {
                    f.add("S" + sec + ": the gate was never destroyed (" + call(STATIONS, "describe") + ")");
                    return;
                }
                ctx.waitTicks(4);
                int s2 = sec;
                boolean gateGone = ctx.computeOnClient(mc -> {
                    int[] g = s2 == 1 ? new int[]{100, 115, 123} : s2 == 2 ? new int[]{18, 115, 133} : new int[]{6, 115, 50};
                    return mc.level.getBlockState(new BlockPos(g[0], g[1], g[2])).isAir();
                });
                expect(f, "S" + sec + " gate blocks gone", gateGone, true);
            } else if (!waitChat(ctx, end, "The Core entrance is opening!", 40)) {
                f.add("S4: the core never opened");
                return;
            }
            ctx.waitTicks(5);
            expect(f, "P3SectionTracker after S" + sec, call(SECTIONS, "section"), sec + 1);
            expect(f, "P3SectionTracker.sectionDone(" + sec + ")",
                    ModUnderTest.staticCall(SECTIONS, "sectionDone", new Class<?>[]{int.class}, new Object[]{sec}), true);
        }
        expect(f, "Floor7Tracker stage at the end", ctx.computeOnClient(mc ->
                String.valueOf(ModUnderTest.staticCall(F7, "getStage"))), "S5");
        println(P3, "stations: " + call(STATIONS, "describe"));
    }

    /**
     * simgates-1105 (killer560, 2026-11-05: every solver, auto and dungeon-only feature treats the sim as a dungeon, the boss
     * as the boss room). In the boss sim at P3 the shared gates report the F7/M7 boss at the right phase, floor and section,
     * and the Terminal Solver, Auto Terminals, the Simon Says solver and Auto Simon Says each act (each proved by what it did).
     */
    private static void simGates(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        ctx.waitTicks(10);
        gates(ctx, f, "M7 S1", "M7", "S1");
        String tsf = "terminals.TerminalSolverFeature";
        // ---- the Terminal Solver alone: it reads the window and works out the clicks, and clicks nothing ----
        System.setProperty("killer560.bosssim.terminal", "PANES");
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setPanesEnabled", true);
            ModUnderTest.set(cfg, "setAutoTerminalsEnabled", false);
        });
        int solved0 = (Integer) call(TERMS, "solvedCount");
        int opened0 = (Integer) call(TERMS, "openedCount");
        if (!clickTerminal(ctx, 1, "T1", "Inactive Terminal")
                || !until(ctx, "T1 opened", mc -> (Integer) call(TERMS, "openedCount") > opened0, 20)) {
            f.add("S1 T1 never opened");
            return;
        }
        boolean solver = until(ctx, "the Terminal Solver's highlights", mc ->
                dev.testkit.gametest.mod.Mod.field(tsf, "currentType") != null
                        && !((java.util.Map<?, ?>) dev.testkit.gametest.mod.Mod.field(tsf, "currentHighlights")).isEmpty(), 40);
        expect(f, "Terminal Solver read the sim's Panes terminal", solver
                ? ctx.computeOnClient(mc -> String.valueOf(dev.testkit.gametest.mod.Mod.field(tsf, "currentType"))) : "nothing", "PANES");
        ctx.waitTicks(10);
        expect(f, "terminals solved with the solver alone (it must not click)", (Integer) call(TERMS, "solvedCount"), solved0);
        closeMenu(ctx);
        // ---- Auto Terminals: the mod solves it ----
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(TERM_CFG);
            ModUnderTest.set(cfg, "setAutoTerminalsEnabled", true);
            ModUnderTest.set(cfg, "setAutoPanesEnabled", true);
        });
        long tm = LogTap.mark();
        if (!clickTerminal(ctx, 1, "T1", "Inactive Terminal")) {
            f.add("S1 T1: no Inactive Terminal stand for the second open");
        } else if (!until(ctx, "Auto Terminals solved T1", mc -> (Integer) call(TERMS, "solvedCount") > solved0, 600)) {
            f.add("Auto Terminals never solved S1 T1 in the sim");
        } else if (!waitChat(ctx, tm, "activated a terminal!", 20)) {
            f.add("T1 solved but no 'activated a terminal!' line");
        }
        closeMenu(ctx);
        System.clearProperty("killer560.bosssim.terminal");
        ctx.runOnClient(mc -> ModUnderTest.turnOff(TERM_CFG, "setAutoTerminalsEnabled"));
        // ---- Simon Says: the solver records the shown steps; Auto Simon Says presses them ----
        String ssCfg = "com.killer560.hub.simonsays.SimonSaysConfig";
        String ssf = "simonsays.SimonSaysFeature";
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(ssCfg);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.set(cfg, "setSolverEnabled", true);
            ModUnderTest.set(cfg, "setAutoStartEnabled", false);
            ModUnderTest.set(cfg, "setTriggerBotEnabled", false);
            ModUnderTest.set(cfg, "setAutoSolveRotate", false);
            ModUnderTest.set(cfg, "setDiagnosticLoggingEnabled", true);
            ModUnderTest.set(cfg, "setAutoSimonSaysEnabled", true);
            ModUnderTest.set(cfg, "setAutoSolveEnabled", true);
        });
        tp(ctx, 108.5, 120, 93.5, -90, 0);
        if (!until(ctx, "Simon Says at the device", mc -> (Boolean) dev.testkit.gametest.mod.Mod.field(ssf, "wasActive"), 100)) {
            f.add("the Simon Says solver never armed at the sim's device");
        }
        long sm = LogTap.mark();
        useBlock(ctx, new BlockPos(110, 121, 91), Direction.EAST);
        boolean recorded = until(ctx, "the solver recorded a step", mc ->
                !((List<?>) dev.testkit.gametest.mod.Mod.field(ssf, "clickInOrder")).isEmpty(), 200);
        expect(f, "Simon Says solver recorded the sim's first step", recorded, true);
        try {
            if (!waitChat(ctx, sm, "completed a device!", 1200)) {
                f.add("Auto Simon Says never completed the sim's device (" + call(DEVICES, "describe") + ")");
            } else {
                println(GATES, "Auto Simon Says completed the device; the test pressed only the start button");
            }
        } finally {
            ctx.runOnClient(mc -> {
                Object cfg = ModUnderTest.config(ssCfg);
                ModUnderTest.set(cfg, "setAutoSolveEnabled", false);
                ModUnderTest.set(cfg, "setAutoSimonSaysEnabled", false);
                ModUnderTest.set(cfg, "setDiagnosticLoggingEnabled", false);
            });
        }
        // ---- a Floor 7 fight started at S2: F7, and the section the player stands in ----
        long m2 = start(ctx, "S2", false);
        if (!waitChat(ctx, m2, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("S2 start: Goldor's line never came");
            return;
        }
        ctx.waitTicks(10);
        gates(ctx, f, "F7 S2", "F7", "S2");
    }

    /** The shared dungeon gates as a feature reads them, in the sim's boss at P3. */
    private static void gates(ClientGameTestContext ctx, List<String> f, String what, String floor, String stage) {
        ctx.waitTicks(6); // SkyblockGate reads the sidebar every 5 ticks
        String got = ctx.computeOnClient(mc -> "inDungeon=" + call(DUNGEON, "isInDungeon")
                + " f7OrM7=" + call(DUNGEON, "isF7OrM7")
                + " bossPhase=" + call(DUNGEON, "isBossPhaseActive")
                + " simBoss=" + call(DUNGEON, "isSimBoss")
                + " floor=" + call(DUNGEON, "getFloor")
                + " inF7Boss=" + call(F7, "inF7Boss")
                + " phase=" + call(F7, "getPhase")
                + " stage=" + call(F7, "getStage")
                + " dungeonServer=" + ModUnderTest.staticCall("com.killer560.hub.cheatutils.CheatUtils", "isOnDungeonServer",
                new Class<?>[]{Minecraft.class}, new Object[]{mc})
                + " skyblock=" + call("com.killer560.hub.util.SkyblockGate", "isOnSkyblock"));
        expect(f, what + " gates", got, "inDungeon=true f7OrM7=true bossPhase=true simBoss=true floor=" + floor
                + " inF7Boss=true phase=P3 stage=" + stage + " dungeonServer=true skyblock=true");
    }

    private static void simonSays(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, 108.5, 120, 93.5, -90, 0);
        ctx.waitTicks(8);
        useBlock(ctx, new BlockPos(110, 121, 91), Direction.EAST);
        for (int round = 1; round <= 4; round++) {
            int r = round;
            if (!until(ctx, "SS round " + round + " input", mc -> (Boolean) call(DEVICES, "ssInput")
                    && ((List<?>) call(DEVICES, "ssSequence")).size() == r, 200)) {
                f.add("Simon Says never reached round " + round + " (" + call(DEVICES, "describe") + ")");
                return;
            }
            ctx.waitTicks(3);
            @SuppressWarnings("unchecked")
            List<int[]> seq = (List<int[]>) call(DEVICES, "ssSequence");
            for (int[] lantern : seq) {
                useBlock(ctx, new BlockPos(110, lantern[1], lantern[2]), Direction.EAST);
                ctx.waitTicks(3);
            }
        }
    }

    private static void lights(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, 60.5, 131, 140.5, 180, -20);
        ctx.waitTicks(8);
        int[][] levers = {{62, 136, 142}, {58, 136, 142}, {60, 135, 142}, {60, 134, 142}, {62, 133, 142}, {58, 133, 142}};
        // The device starts with the corners and the middle two on (1109): the first pull of any lever completes it.
        useBlock(ctx, new BlockPos(levers[0][0], levers[0][1], levers[0][2]), Direction.NORTH);
        ctx.waitTicks(3);
        println(P3, "Lights: flicked one lever");
    }

    private static boolean leverOn(ClientGameTestContext ctx, int x, int y) {
        return ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(x, y, 142)).getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED));
    }

    /** Lights starts as the corners + the middle two, one pull of any lever (on or off) completes it, /pc answers quietly. */
    private static void lights1109(ClientGameTestContext ctx, List<String> f) {
        java.util.Set<String> start = java.util.Set.of("58,133", "62,133", "58,136", "62,136", "60,134", "60,135");
        java.util.Random rnd = new java.util.Random();
        for (int run = 0; run < 2; run++) {
            start(ctx, "S2", false);
            tp(ctx, 60.5, 132, 140.5, 180, -20);
            ctx.waitTicks(8);
            for (int y = 133; y <= 136; y++) {
                for (int x = 58; x <= 62; x++) {
                    expect(f, "run " + run + " lever " + x + "," + y + " at the start", leverOn(ctx, x, y), start.contains(x + "," + y));
                }
            }
            expect(f, "run " + run + " lamps by the rule at the start", lightsRuleHolds(ctx), "ok");
            expect(f, "run " + run + " not done before a pull", String.valueOf(call(DEVICES, "describe")).contains("lights=false"), true);
            int x, y;
            if (run == 0) {
                do {
                    x = 58 + rnd.nextInt(5);
                    y = 133 + rnd.nextInt(4);
                } while (start.contains(x + "," + y));
            } else {
                x = 60;
                y = 135;
            }
            long mark = LogTap.mark();
            useBlock(ctx, new BlockPos(x, y, 142), Direction.NORTH);
            ctx.waitTicks(10);
            expect(f, "run " + run + " (" + x + "," + y + ") completed by one flick", String.valueOf(call(DEVICES, "describe")).contains("lights=true"), true);
            expect(f, "run " + run + " completion line", chatSeen(mark, "completed a device!"), true);
            if (run == 1) {
                mark = LogTap.mark();
                ctx.runOnClient(mc -> mc.player.connection.sendCommand("pc at ee2"));
                ctx.waitTicks(6);
                expect(f, "/pc at ee2 is not an unknown command", chatSeen(mark, "Unknown or incomplete"), false);
                expect(f, "/pc at ee2 shows the local Party > line (simpc-1110)", chatSeen(mark, ": at ee2"), true);
            }
            cleanup(ctx);
        }
    }

    private static void arrows(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, -0.5, 119, 77.5, 90, 0);
        ctx.waitTicks(10);
        int clicks = 0;
        for (int i = 0; i < 25; i++) {
            int want = (Integer) ModUnderTest.staticCall(DEVICES, "arrowSolution", new Class<?>[]{int.class}, new Object[]{i});
            if (want < 0) {
                continue;
            }
            BlockPos pos = new BlockPos(-2, 120 + i % 5, 75 + i / 5);
            for (int k = 0; k < 8; k++) {
                Integer rot = ctx.computeOnClient(mc -> {
                    ItemFrame fr = frameAt(mc, pos);
                    return fr == null ? null : fr.getRotation() & 7;
                });
                if (rot == null) {
                    f.add("Arrows: no frame at " + pos.toShortString());
                    return;
                }
                if (rot == want) {
                    break;
                }
                ctx.runOnClient(mc -> {
                    ItemFrame fr = frameAt(mc, pos);
                    mc.gameMode.interact(mc.player, fr, new EntityHitResult(fr, fr.position()), InteractionHand.MAIN_HAND);
                });
                clicks++;
                ctx.waitTicks(3);
            }
        }
        println(P3, "Arrows: " + clicks + " real click(s) on frames");
        if (clicks == 0) {
            f.add("Arrows needed no click - the layout started solved");
        }
    }

    private static void target(ClientGameTestContext ctx, List<String> f) {
        tp(ctx, 63.5, 127, 35.5, 0, -20);
        ctx.waitTicks(8);
        for (int hit = 0; hit < 9; hit++) {
            if (!until(ctx, "a lit target", mc -> call(DEVICES, "litTarget") != null, 40)) {
                f.add("Target: nothing lit after " + hit + " hit(s)");
                return;
            }
            ctx.waitTicks(2);   // the block change reaches the client a tick or two after the server lights it
            int[] lit = (int[]) call(DEVICES, "litTarget");
            if (lit == null) {
                f.add("Target: the lit target went out on its own");
                return;
            }
            boolean emerald = ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(lit[0], lit[1], lit[2]))
                    .is(net.minecraft.world.level.block.Blocks.EMERALD_BLOCK));
            if (!emerald) {
                f.add("Target: the lit block is not emerald on the client");
            }
            command(ctx, String.format(Locale.ROOT, "summon minecraft:arrow %.2f %.2f %.2f {Motion:[0.0,0.0,0.5d]}",
                    lit[0] + 0.5, lit[1] + 0.5, lit[2] - 0.4));
            ctx.waitTicks(6);
        }
    }

    private static void p4p5(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "CORE", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        expect(f, "P3SectionTracker at the core", call(SECTIONS, "section"), 1);
        tp(ctx, 54.5, 107, 90.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: You have done it, you destroyed the factory...", 400)) {
            f.add("walking into the core never brought Goldor's arrival lines");
            return;
        }
        for (int h = 0; h < 10; h++) {
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Goldor"}));
            ctx.waitTicks(2);
        }
        // His "...." queues behind the arrival lines still to come, so the core's floor (2 ticks before Necron's line, 62
        // after the kill) can go first.
        tp(ctx, 54.5, 107, 108.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Necron: You went further than any human before, congratulations.", 140)) {
            f.add("Necron's first line never came");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "Floor7Tracker phase in P4", phase(ctx), "P4");
        // The floor goes only 2 ticks before Necron's line, so he is still falling now.
        until(ctx, "the fall into P4", mc -> mc.player.getY() < 80, 60);
        double y4 = ctx.computeOnClient(mc -> mc.player.getY());
        if (y4 > 80) {
            f.add("the core floor never let him down into P4 (y " + y4 + ")");
        }
        if (!waitChat(ctx, mark, "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.", 200)) {
            f.add("Necron's intro never finished");
            return;
        }
        expect(f, "Goldor's \"....\" came (queued behind his lines)", waitChat(ctx, mark, "[BOSS] Goldor: ....", 300), true);
        if (!waitChat(ctx, mark, "[BOSS] Necron: ARGH!", 300)) {
            f.add("Necron's ARGH never came (his fight runs on its own clock)");
            return;
        }
        if (!waitChat(ctx, mark, "[BOSS] Necron: Let's make some space!", 120)) {
            f.add("Necron's \"Let's make some space!\" never came");
            return;
        }
        expect(f, "no \"All this, for nothing...\" (gone since Hypixel's boss update)",
                chatSeen(mark, "[BOSS] Necron: All this, for nothing..."), false);
        if (!until(ctx, "P5", mc -> "P5".equals(String.valueOf(call(FIGHT, "phase"))), 120)) {
            f.add("the fight never went on to P5 after Necron (" + call(FIGHT, "describe") + ")");
            return;
        }
        tp(ctx, 54.5, 63, 76.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] The Wither King: You... again?", 160)) {
            f.add("the Wither King's line never came");
            return;
        }
        double y5 = ctx.computeOnClient(mc -> mc.player.getY());
        expect(f, "Floor7Tracker phase in P5", phase(ctx), "P5");
        if (y5 > 20) {
            f.add("Necron's platform never let him down into P5 (y " + y5 + ")");
        }
        int killed = 0;
        for (int i = 0; i < 5; i++) {
            if (!until(ctx, "a dragon up", mc -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
                    new AABB(0, 0, 20, 110, 60, 150), d -> d.getHealth() > 0).isEmpty(), 260)) {
                f.add("dragon " + (i + 1) + " never spawned");
                return;
            }
            // One hit is a dragon's whole damage since simitems-1105.
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Dragon"}));
            ctx.waitTicks(2);
            killed++;
            ctx.waitTicks(25);
        }
        if (!until(ctx, "boss cleared", mc -> "DONE".equals(String.valueOf(call(FIGHT, "phase"))), 100)) {
            f.add("the fight never ended after " + killed + " dragons (" + call(FIGHT, "describe") + ")");
        }
        expect(f, "Wither King counts lines", count(mark, "[BOSS] The Wither King: ") >= 6, true);
    }

    private static void dragons(ClientGameTestContext ctx, List<String> f) throws Exception {
        Object cfg = ModUnderTest.config(WD + "WitherDragonsConfig");
        ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setEnabled", true));
        try {
            withCapture(f, () -> {
                expectStartSource(ctx, f);
                necronBreak(ctx, f);
                dragonsRun(ctx, f);
            });
        } finally {
            ctx.runOnClient(mc -> ModUnderTest.set(cfg, "setEnabled", false));
        }
    }

    private static void dragonsRun(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "P5", true);
        if (!waitChat(ctx, mark, "[BOSS] The Wither King: You... again?", 160)) {
            f.add("the Wither King's line never came");
            return;
        }
        expect(f, "Floor7Tracker phase in P5", phase(ctx), "P5");
        p5Arena(ctx, f);
        Object red = ModUnderTest.enumValue(WD + "WitherDragon", "RED");
        Object orange = ModUnderTest.enumValue(WD + "WitherDragon", "ORANGE");
        if (!until(ctx, "Red's spawn timer", mc -> "SPAWNING".equals(String.valueOf(spec(red, "state"))), 80)) {
            f.add("Wither Dragons never read Red's flame burst (state " + spec(red, "state") + ")");
            return;
        }
        int timer = (Integer) spec(red, "timeToSpawn");
        println(DRAGONS, "Red spawning, spawn timer " + timer);
        if (timer < 80) {
            f.add("Red's spawn timer read " + timer + " at the burst, want about 100");
        }
        net.minecraft.world.entity.boss.enderdragon.EnderDragon[] e = new net.minecraft.world.entity.boss.enderdragon.EnderDragon[1];
        if (!until(ctx, "Red's dragon", mc -> (e[0] = dragonIn(mc, red)) != null, 140)) {
            f.add("Red's dragon never spawned in its area");
            return;
        }
        Vec3 spawn = Vec3.atBottomCenterOf((BlockPos) field(red, "spawnPos"));
        Vec3 p0 = ctx.computeOnClient(mc -> e[0].position());
        println(DRAGONS, "Red's dragon first seen at " + p0 + ", spawn point " + spawn);
        if (p0.distanceTo(spawn) > 1.5) {
            f.add("Red's dragon appeared at " + p0 + ", not at its spawn point " + spawn);
        }
        ctx.waitTicks(2);
        expect(f, "Wither Dragons: Red alive", String.valueOf(spec(red, "state")), "ALIVE");
        expect(f, "\"dragon spawned\" message", chatSeen(mark, "dragon spawned"), true);
        expect(f, "priority dragon message (Red and Orange spawning together)", chatSeen(mark, "priority dragon"), true);
        // Path length, not displacement: it turns toward a new goal whenever it reaches one, so it can end near its start.
        double path = 0;
        Vec3 p1 = p0;
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(1);
            Vec3 at = ctx.computeOnClient(mc -> e[0].position());
            path += at.distanceTo(p1);
            p1 = at;
        }
        AABB area = (AABB) field(red, "box");
        println(DRAGONS, "Red's dragon 40 ticks later at " + p1 + " (flew " + String.format(Locale.ROOT, "%.1f", path) + ")");
        if (path < 4) {
            f.add("Red's dragon did not fly (" + path + " blocks in 40 ticks)");
        }
        if (!area.contains(p1)) {
            f.add("Red's dragon left its area: " + p1);
        }
        double partGap = ctx.computeOnClient(mc -> e[0].getSubEntities()[2].position().distanceTo(e[0].position()));
        if (partGap > 2) {
            f.add("Red's body part is " + partGap + " from the dragon (parts never placed: nothing can hit it)");
        }
        int arrowsBefore = (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired");
        boolean redDown = false;
        for (int shot = 0; shot < 40 && !redDown; shot++) {
            Vec3 c = ctx.computeOnClient(mc -> e[0].getSubEntities()[2].getBoundingBox().getCenter());
            tp(ctx, c.x - 7, c.y, c.z, 0, 0);
            ctx.runOnClient(mc -> {
                Vec3 at = e[0].getSubEntities()[2].getBoundingBox().getCenter().subtract(mc.player.getEyePosition());
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-at.x, at.z)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(at.y, Math.hypot(at.x, at.z))));
                ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTerminator", "use", new Class<?>[]{Minecraft.class, boolean.class},
                        new Object[]{mc, false});
            });
            ctx.waitTicks(4);
            redDown = logSeen(mark, "[BossSim] Red dragon down");
        }
        int arrows = (Integer) call("com.killer560.hub.roomsim.SimTerminator", "arrowsFired") - arrowsBefore;
        println(DRAGONS, "arrows fired at Red: " + arrows);
        if (!redDown) {
            f.add("40 Terminator shots (" + arrows + " arrows) never killed Red");
            return;
        }
        expect(f, "Red took 8 hits", logSeen(mark, "Red dragon down at P5") && logSeen(mark, "(15 hits)"), true);
        if (!until(ctx, "Wither Dragons reads Red dead", mc -> "DEAD".equals(String.valueOf(spec(red, "state"))), 40)) {
            f.add("Wither Dragons never read Red's death (state " + spec(red, "state") + ")");
        }
        expect(f, "\"alive for\" message (the synched health death)", chatSeen(mark, "alive for"), true);
        expect(f, "\"dragon counts\" message (the King's counts line)", waitChat(ctx, mark, "dragon counts", 20), true);
        // Orange: melee. Stand beside its body and swing at the nearest part, as a click would.
        if (!until(ctx, "Orange's dragon", mc -> (e[0] = dragonIn(mc, orange)) != null, 200)) {
            f.add("Orange's dragon never spawned in its area");
            return;
        }
        boolean orangeDown = false;
        int swings = 0;
        for (int i = 0; i < 60 && !orangeDown; i++) {
            Vec3 c = ctx.computeOnClient(mc -> e[0].getSubEntities()[2].getBoundingBox().getCenter());
            tp(ctx, c.x + 3.5, c.y - 1, c.z, 90, 0);
            boolean swung = ctx.computeOnClient(mc -> {
                Entity best = null;
                for (Entity part : e[0].getSubEntities()) {
                    if (part.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) < 9
                            && (best == null || part.distanceToSqr(mc.player) < best.distanceToSqr(mc.player))) {
                        best = part;
                    }
                }
                if (best == null) {
                    return false;
                }
                mc.gameMode.attack(mc.player, best);
                return true;
            });
            swings += swung ? 1 : 0;
            ctx.waitTicks(2);
            orangeDown = logSeen(mark, "[BossSim] Orange dragon down");
        }
        println(DRAGONS, "melee swings at Orange: " + swings);
        if (!orangeDown) {
            f.add(swings + " melee swings never killed Orange");
            return;
        }
        if (!until(ctx, "Wither Dragons reads Orange dead", mc -> "DEAD".equals(String.valueOf(spec(orange, "state"))), 40)) {
            f.add("Wither Dragons never read Orange's death");
        }
        for (int i = 0; i < 3; i++) {
            if (!until(ctx, "a dragon up", mc -> !mc.level.getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
                    new AABB(0, 0, 20, 110, 60, 150), d -> d.getHealth() > 0).isEmpty(), 260)) {
                f.add("dragon " + (i + 3) + " never spawned");
                return;
            }
            // One hit kills a dragon since simitems-1105: eight hits here also killed the NEXT dragon the moment it spawned,
            // so the loop ran out of dragons (ship7 merge of simdragons + simitems).
            serverRun(ctx, s -> ModUnderTest.staticCall(FIGHT, "testHit", new Class<?>[]{String.class}, new Object[]{"Dragon"}));
            ctx.waitTicks(2);
            ctx.waitTicks(25);
        }
        if (!until(ctx, "boss cleared", mc -> "DONE".equals(String.valueOf(call(FIGHT, "phase"))), 100)) {
            f.add("the fight never ended after five dragons (" + call(FIGHT, "describe") + ")");
        }
    }

    private static void expectStartSource(ClientGameTestContext ctx, List<String> f) {
        start(ctx, "P4", false);
        expect(f, "arena source (dragons run in his capture)", call(ARENA, "source"), "capture");
    }

    /**
     * His "offset lava" (simdragons-1105): Necron's platform break once laid a lava plane at y 62 over the capture's
     * open sea (y 58). After the break: no lava anywhere over Necron's arena above the sea, and the platform's centre
     * column is open to it.
     */
    private static void necronBreak(ClientGameTestContext ctx, List<String> f) {
        if (!untilTick(ctx, 277, 300)) {
            f.add("P4 never reached Necron's platform break (" + call(FIGHT, "describe") + ")");
            return;
        }
        String pl = String.valueOf(call(FIGHT, "necronPlatform"));
        int[][] centres = {{54, 42}, {28, 50}, {80, 50}, {28, 102}, {80, 102}};
        int i = java.util.Arrays.asList("N", "NW", "NE", "SW", "SE").indexOf(pl);
        if (i < 0) {
            f.add("no platform reported broken (" + pl + ")");
            return;
        }
        int[] cc = centres[i];
        int[] high = ctx.computeOnClient(mc -> {
            int n = 0;
            BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
            for (int x = 0; x <= 110; x++) {
                for (int z = 20; z <= 135; z++) {
                    for (int y = 59; y <= 66; y++) {
                        n += mc.level.getBlockState(q.set(x, y, z)).is(net.minecraft.world.level.block.Blocks.LAVA) ? 1 : 0;
                    }
                }
            }
            int solid = 0;
            for (int y = 59; y <= 64; y++) {
                solid += mc.level.getBlockState(q.set(cc[0], y, cc[1])).isAir() ? 0 : 1;
            }
            return new int[]{n, solid};
        });
        println(DRAGONS, "Necron broke " + pl + ": lava blocks above the sea (y 59-66) " + high[0]
                + ", solid blocks left in its centre column " + high[1]);
        expect(f, "no lava above the lava sea after Necron's platform break", high[0], 0);
        expect(f, "Necron's broken platform open at its centre (y 59-64)", high[1], 0);
    }

    /**
     * His "missing the underside" (simdragons-1105): P5 clears only the disc over the shaft under Necron's platform,
     * so the stone ring (y 54-62 around x 50-58, z 72-80) still holds the disc up.
     */
    private static void p5Arena(ClientGameTestContext ctx, List<String> f) {
        int[][] ring = {{50, 61, 76}, {50, 62, 76}, {58, 62, 76}, {54, 62, 72}, {54, 62, 80}, {50, 59, 76}, {54, 57, 80}};
        for (int[] r : ring) {
            boolean solid = ctx.computeOnClient(mc -> !mc.level.getBlockState(new BlockPos(r[0], r[1], r[2])).isAir());
            expect(f, "Necron's platform ring under the disc at " + r[0] + "," + r[1] + "," + r[2], solid, true);
        }
        boolean shaft = ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(54, 63, 76)).isAir()
                && mc.level.getBlockState(new BlockPos(54, 64, 76)).isAir());
        expect(f, "the disc over the shaft (54, 63-64, 76) open for the drop to P5", shaft, true);
        boolean disc = ctx.computeOnClient(mc -> !mc.level.getBlockState(new BlockPos(50, 63, 76)).isAir());
        expect(f, "the disc over the ring (50, 63, 76) kept", disc, true);
    }

    /** A living ender dragon inside this WitherDragon's area on the client, or null. */
    static net.minecraft.world.entity.boss.enderdragon.EnderDragon dragonIn(Minecraft mc, Object spec) {
        List<net.minecraft.world.entity.boss.enderdragon.EnderDragon> in = mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.boss.enderdragon.EnderDragon.class, (AABB) field(spec, "box"), d -> d.getHealth() > 0);
        return in.isEmpty() ? null : in.get(0);
    }

    static Object spec(Object dragon, String getter) {
        return ModUnderTest.call(dragon, getter, new Class<?>[]{}, new Object[]{});
    }

    static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("no field " + name + " on " + target.getClass().getName(), ex);
        }
    }

    private static boolean logSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static void lava(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        int before = (Integer) call(LAVA_CLASS, "bounces");
        tp(ctx, 30.5, 109, 90.5, 0, 0);
        double[] top = {0};
        int[] hurt = {0};
        boolean bounced = until(ctx, "a lava bounce", mc -> {
            hurt[0] = Math.max(hurt[0], mc.player.hurtTime);
            top[0] = Math.max(top[0], mc.player.getY());
            return (Integer) call(LAVA_CLASS, "bounces") > before && mc.player.getY() > 108.5;
        }, 80);
        for (int i = 0; i < 40; i++) {
            ctx.waitTick();
            top[0] = Math.max(top[0], ctx.computeOnClient(mc -> mc.player.getY()));
            hurt[0] = Math.max(hurt[0], ctx.computeOnClient(mc -> mc.player.hurtTime));
        }
        // simitems-1105 ("everytime i hit lava in the sim it moves my pov like im taking damage even though i am not"):
        // the bounces (several in 50 ticks of lava) send no hurt tilt.
        expect(f, "his hurt tilt (hurtTime) over every lava bounce", hurt[0], 0);
        println(LAVA, "bounces " + ((Integer) call(LAVA_CLASS, "bounces") - before) + ", highest y " + top[0]);
        if (!bounced) {
            f.add("standing in P3's lava never bounced him");
        }
        if (top[0] < 110.0) {
            f.add("the bounce only reached y " + top[0] + " (Hypixel's from 106: 108.25, 110.03, 111.69 ...)");
        }
    }

    private static void ap3(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(Ap3RuntimeTests.CONFIG);
            ModUnderTest.set(cfg, "setEnabled", true);
            ModUnderTest.staticCall(Ap3RuntimeTests.FEATURE, "setForceDungeon", new Class<?>[]{boolean.class}, new Object[]{false});
        });
        // A RUN node on S1's floor, pointing south (+z), box 2x2; he walks into it from the north with a real W.
        Object run = ctx.computeOnClient(mc -> {
            Object n = ap3Node("RUN", 100.5, 107, 50.5, 0f, 0f);
            Ap3RuntimeTests.set(n, "length", 2.0);
            Ap3RuntimeTests.set(n, "width", 2.0);
            Ap3RuntimeTests.setChain(n);
            return n;
        });
        tp(ctx, 100.5, 107, 46.5, 0, 0);
        ctx.waitTicks(10);
        double z0 = ctx.computeOnClient(mc -> mc.player.getZ());
        ctx.getInput().holdKey(options -> options.keyUp);
        boolean inside = false;
        for (int i = 0; i < 60 && !inside; i++) {
            ctx.waitTick();
            inside = ctx.computeOnClient(mc -> Ap3RuntimeTests.contains(run, mc.player.position()));
        }
        ctx.getInput().releaseKey(options -> options.keyUp);
        if (!inside) {
            f.add("walking with W never reached the RUN node's box");
            return;
        }
        boolean ran = until(ctx, "AP3 running", mc -> (Boolean) ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "isRunning"), 20);
        ctx.waitTicks(20);
        double z1 = ctx.computeOnClient(mc -> mc.player.getZ());
        println(AP3, String.format(Locale.ROOT, "AP3 ran %s, z %.2f -> %.2f", ran, z0, z1));
        if (!ran) {
            f.add("AP3 never ran the RUN node in the sim boss");
        }
        if (z1 - z0 < 6.0) {
            f.add(String.format(Locale.ROOT, "AP3's run carried him only %.2f blocks", z1 - z0));
        }
        ctx.runOnClient(mc -> {
            ModUnderTest.staticCall(Ap3RuntimeTests.EXEC, "stop", new Class<?>[]{String.class}, new Object[]{"test over"});
            Ap3RuntimeTests.setChain();
        });
    }

    private static void portal(ClientGameTestContext ctx, List<String> f) {
        if (!SimPuzzleSolveTests.seedRooms(ctx)) {
            Scenario.skipped(PORTAL, "needs real room captures and room database");
            return;
        }
        String code = null;
        for (int i = 0; i < 6 && code == null; i++) {
            code = planF7(ctx);
        }
        if (code == null) {
            f.add("no F7 could be planned");
            return;
        }
        buildFloorAndEnter(ctx, f, code);
    }

    static String planF7(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            Object floor = ModUnderTest.enumValue("com.killer560.hub.roomsim.SimFloorGen$Floor", "F7");
            Object planned = ModUnderTest.staticCall("com.killer560.hub.roomsim.SimFloorGen", "plan",
                    new Class<?>[]{floor.getClass(), int.class, int.class}, new Object[]{floor, 4, 5});
            return planned == null ? null : (String) ModUnderTest.call(planned, "code", new Class<?>[0], new Object[0]);
        });
    }

    private static void buildFloorAndEnter(ClientGameTestContext ctx, List<String> f, String code) {
        int[] p = floorWithPortal(ctx, f, code);
        if (p == null) {
            return;
        }
        String mapBefore = String.valueOf(call("com.killer560.hub.roomsim.SimState", "mapCode"));
        ctx.waitTicks(10);
        expect(f, "standing on the spot as it opened did not take him in", call(FIGHT, "running"), false);
        tp(ctx, p[0] + 4.5, p[1] + 1, p[2] + 0.5, 0, 0);
        ctx.waitTicks(10);
        boolean placed = ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(p[0], p[1], p[2]))
                .is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN));
        expect(f, "portal blocks in Blood", placed, true);
        long mark = LogTap.mark();
        tp(ctx, p[0] + 0.5, p[1] + 1, p[2] + 0.5, 0, 0);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 600)) {
            f.add("stepping into the portal never started P1");
            return;
        }
        ctx.waitTicks(5);
        expect(f, "the floor's map code after entering the boss",
                String.valueOf(call("com.killer560.hub.roomsim.SimState", "mapCode")), mapBefore);
        expect(f, "Floor7Tracker phase after the portal", phase(ctx), "P1");
    }

    /** Builds floor {@code code}, puts him in Blood and waits for its portal: the portal block, or null (failure added). */
    static int[] floorWithPortal(ClientGameTestContext ctx, List<String> f, String code) {
        long before = Scenario.simBuildCount(ctx);
        ctx.runOnClient(mc -> mc.execute(() -> {
            java.util.function.Consumer<Minecraft> build = c -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimBuilder",
                    "build", new Class<?>[]{Minecraft.class, String.class}, new Object[]{c, code});
            ModUnderTest.staticCall("com.killer560.hub.roomsim.SimWorld", "open", new Class<?>[]{Minecraft.class, String.class,
                    java.util.function.Consumer.class, String.class}, new Object[]{mc, code, build, "Generating Floor 7"});
        }));
        ctx.waitFor(mc -> mc.level != null, 2400);
        Scenario.awaitSimBuild(ctx, before);
        ctx.waitTicks(80);
        ctx.runOnClient(mc -> ModUnderTest.staticCall("com.killer560.hub.roomsim.SimTeleportCommands", "goTo",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "Blood"}));
        if (!until(ctx, "standing in Blood", mc -> "Blood".equals(call("com.killer560.hub.roomsim.SimState", "currentRoomName")), 100)) {
            f.add("/goto Blood never put him in the Blood room");
            return null;
        }
        if (!until(ctx, "the portal", mc -> call(B + "BossPortal", "portal") != null, 200)) {
            f.add("no portal opened in Blood");
            return null;
        }
        return (int[]) call(B + "BossPortal", "portal");
    }

    // ============================================================================================== bossparity-1103

    private static double[] boss(String name) {
        return (double[]) ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class}, new Object[]{name});
    }

    private static int phaseTick() {
        return (Integer) call(FIGHT, "phaseTick");
    }

    private static boolean untilTick(ClientGameTestContext ctx, int tick, int max) {
        return until(ctx, "phase tick " + tick, mc -> phaseTick() >= tick, max);
    }

    private static String fmt(double[] p) {
        return p == null ? "none" : String.format(Locale.ROOT, "%.2f %.2f %.2f", p[0], p[1], p[2]);
    }

    private static double dist(double[] a, double x, double y, double z) {
        return Math.sqrt((a[0] - x) * (a[0] - x) + (a[1] - y) * (a[1] - y) + (a[2] - z) * (a[2] - z));
    }

    private static void moves(ClientGameTestContext ctx, List<String> f) {
        // ---- P1: Maxor ----
        long mark = start(ctx, "P1", true);
        if (!waitChat(ctx, mark, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", 40)) {
            f.add("Maxor's first line never came");
            return;
        }
        untilTick(ctx, 60, 100);
        double[] m60 = boss("Maxor");
        untilTick(ctx, 83, 60);
        double[] m83 = boss("Maxor");
        println(MOVES, "Maxor t60 " + fmt(m60) + ", t83 " + fmt(m83));
        expect(f, "Maxor still at (73, 226, 53) until t 84", m60 != null && m83 != null && dist(m60, 73, 226, 53) < 0.01
                && dist(m83, 73, 226, 53) < 0.01, true);
        double maxStep = 0;
        double[] prev = boss("Maxor");
        int prevT = phaseTick();
        for (int i = 0; i < 30; i++) {
            ctx.waitTick();
            double[] now = boss("Maxor");
            int nt = phaseTick();
            if (nt > prevT && now != null && prev != null) {
                maxStep = Math.max(maxStep, dist(now, prev[0], prev[1], prev[2]) / (nt - prevT));
            }
            prev = now;
            prevT = nt;
        }
        double[] m115 = boss("Maxor");
        println(MOVES, String.format(Locale.ROOT, "Maxor t%d %s, largest step %.3f/tick", prevT, fmt(m115), maxStep));
        expect(f, "Maxor flew toward the player (z down from 53)", m115 != null && m115[2] < 50, true);
        expect(f, "Maxor's step never over 0.9 a tick", maxStep <= 0.9 + 1e-6 && maxStep > 0.2, true);
        cleanup(ctx);

        // ---- P2: Storm ----
        mark = start(ctx, "P2", true);
        tp(ctx, 73.5, 169, 50.5, 0, 0);
        untilTick(ctx, 100, 140);
        double[] s100 = boss("Storm");
        int t100 = phaseTick();
        // The route's first leg: (103, 188, 53) -> (73, 183, 83), 0.4 a tick from t 0.
        double lx = 73 - 103, ly = 183 - 188, lz = 83 - 53, len = Math.sqrt(lx * lx + ly * ly + lz * lz);
        double k = Math.min(1, 0.4 * t100 / len);
        double ex = 103 + lx * k, ey = 188 + ly * k, ez = 53 + lz * k;
        println(MOVES, String.format(Locale.ROOT, "Storm t%d %s, expected about %.2f %.2f %.2f", t100, fmt(s100), ex, ey, ez));
        expect(f, "Storm on his first leg at 0.4 a tick", s100 != null && dist(s100, ex, ey, ez) < 1.5, true);
        untilTick(ctx, 450, 400);
        double[] s450 = boss("Storm");
        println(MOVES, "Storm t450 " + fmt(s450));
        expect(f, "Storm parked at (102.375, 183, 52.375) after his route", s450 != null && dist(s450, 102.375, 183, 52.375) < 0.05, true);
        long lm = LogTap.mark();
        untilTick(ctx, 572, 140);
        expect(f, "Giga Lightning reached him out in the open", chatSeen(lm, "Storm's Giga Lightning hit you for"), true);
        tp(ctx, 50.5, 169, 85.5, 0, 0);
        untilTick(ctx, 647, 100);
        double[] s647 = boss("Storm");
        untilTick(ctx, 700, 80);
        double[] s700 = boss("Storm");
        println(MOVES, "Storm t647 " + fmt(s647) + ", t700 " + fmt(s700));
        expect(f, "Storm left his park at t 647 to chase him", s647 != null && s700 != null
                && dist(s700, 50.5, 172, 85.5) < dist(s647, 50.5, 172, 85.5) - 10, true);
        cleanup(ctx);

        // ---- P3: Goldor's loop and his catch-up sprint ----
        mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        double[] g0 = boss("Goldor");
        untilTick(ctx, 100, 140);
        double[] g100 = boss("Goldor");
        int gt = phaseTick();
        println(MOVES, "Goldor t0 " + fmt(g0) + ", t" + gt + " " + fmt(g100));
        expect(f, "Goldor starts at (80, 119, 40) (a few ticks of 0.048 east by the time it is read)", g0 != null && dist(g0, 80, 119, 40) < 0.5, true);
        expect(f, "Goldor walks the S4 line east at 0.048 a tick", g100 != null && Math.abs(g100[0] - (80 + 0.048 * (gt + 1))) < 0.25
                && Math.abs(g100[2] - 40) < 0.01, true);
        untilTick(ctx, 365, 320);
        for (String label : new String[]{"T1", "T2", "T3", "T4", "east lever", "west lever", "SS"}) {
            serverRun(ctx, sv -> ModUnderTest.staticCall(STATIONS, "testComplete",
                    new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{sv, 1, label}));
        }
        if (!waitChat(ctx, mark, "The gate has been destroyed!", 140)) {
            f.add("S1's gate never went after its stations were done");
            return;
        }
        if (!until(ctx, "Goldor's sprint", mc -> String.valueOf(call(FIGHT, "goldorState")).contains("sprint=91.2"), 90)) {
            f.add("Goldor, still in S1 when its door opened, never sprinted (" + call(FIGHT, "goldorState") + ")");
        } else {
            double[] a = boss("Goldor");
            int ta = phaseTick();
            ctx.waitTicks(5);
            double[] b = boss("Goldor");
            int tb = phaseTick();
            double v = dist(b, a[0], a[1], a[2]) / Math.max(1, tb - ta);
            println(MOVES, String.format(Locale.ROOT, "Goldor sprint %.3f a tick (%s -> %s)", v, fmt(a), fmt(b)));
            expect(f, "Goldor's sprint at 0.6 a tick", Math.abs(v - 0.6) < 0.05, true);
        }
        cleanup(ctx);

        // ---- the core: his flight and his Frenzy ----
        mark = start(ctx, "CORE", true);
        waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40);
        tp(ctx, 54.5, 107, 90.5, 0, 0);
        if (!until(ctx, "Goldor flying", mc -> String.valueOf(call(FIGHT, "goldorState")).contains("flying=true"), 40)) {
            f.add("everyone in the core never sent Goldor in (" + call(FIGHT, "goldorState") + ")");
        } else {
            double[] a = boss("Goldor");
            int ta = phaseTick();
            ctx.waitTicks(5);
            double[] b = boss("Goldor");
            int tb = phaseTick();
            double v = Math.hypot(b[0] - a[0], b[2] - a[2]) / Math.max(1, tb - ta);
            println(MOVES, String.format(Locale.ROOT, "Goldor flight %.3f a tick", v));
            expect(f, "Goldor flies to the core at 0.8 a tick", Math.abs(v - 0.8) < 0.05, true);
        }
        if (!until(ctx, "Goldor at the core", mc -> String.valueOf(call(FIGHT, "goldorState")).contains("atCore=true"), 200)) {
            f.add("Goldor never reached the core point (" + call(FIGHT, "goldorState") + ")");
        }
        tp(ctx, 54.5, 107, 62.5, 0, 0);
        long fm = LogTap.mark();
        expect(f, "Goldor's Frenzy line near him in the core", waitChat(ctx, fm, "Goldor's Frenzy hit you for", 60), true);
        cleanup(ctx);

        // ---- P4: Necron ----
        mark = start(ctx, "P4", false);
        untilTick(ctx, 79, 120);
        double[] n79 = boss("Necron");
        untilTick(ctx, 87, 20);
        double[] n87 = boss("Necron");
        untilTick(ctx, 98, 20);
        double[] n98 = boss("Necron");
        println(MOVES, "Necron t79 " + fmt(n79) + ", t87 " + fmt(n87) + ", t98 " + fmt(n98));
        expect(f, "Necron at mid (54, 66, 76) until his sidestep", n79 != null && dist(n79, 54, 66, 76) < 0.01, true);
        expect(f, "Necron's sidestep at 80-86 (7 x 0.25 across)", n87 != null
                && Math.abs(Math.hypot(n87[0] - 54, n87[2] - 76) - 1.75) < 0.05, true);
        expect(f, "Necron teleported back to mid at 98", n98 != null && dist(n98, 54, 66, 76) < 0.01, true);
        untilTick(ctx, 277, 220);
        String pl = String.valueOf(call(FIGHT, "necronPlatform"));
        int[][] centres = {{54, 42}, {28, 50}, {80, 50}, {28, 102}, {80, 102}};
        String[] names = {"N", "NW", "NE", "SW", "SE"};
        int[] c = null;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(pl)) {
                c = centres[i];
            }
        }
        int[] cc = c;
        boolean lava = cc != null && ctx.computeOnClient(mc -> mc.level.getBlockState(new BlockPos(cc[0], 62, cc[1]))
                .is(net.minecraft.world.level.block.Blocks.LAVA));
        println(MOVES, "Necron broke the " + pl + " platform; lava at its centre: " + lava);
        expect(f, "Necron's platform to lava at 275", lava, true);
    }

    private static Entity standNear(Minecraft mc, double[] at, String name) {
        return standAt(mc, at, name);
    }

    /** Teleports beside a terminal's stand and clicks it. False when there is no stand named {@code name}. */
    static boolean clickTerminal(ClientGameTestContext ctx, int sec, String label, String name) {
        double[] at = (double[]) ModUnderTest.staticCall(STATIONS, "position", new Class<?>[]{int.class, String.class},
                new Object[]{sec, label});
        int[][] dirs = {{-1, 0}, {0, -1}, {1, 0}, {0, 1}};
        int[] d = dirs[sec - 1];
        tp(ctx, Math.floor(at[0]) + d[0] * 2 + 0.5, at[1], Math.floor(at[2]) + d[1] * 2 + 0.5, 0, 0);
        ctx.waitTicks(6);
        return ctx.computeOnClient(mc -> {
            Entity stand = standNear(mc, at, name);
            if (stand == null) {
                return false;
            }
            mc.gameMode.interact(mc.player, stand, new EntityHitResult(stand, stand.position().add(0, 1, 0)), InteractionHand.MAIN_HAND);
            return true;
        });
    }

    private static String menuTitle(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) == null ? "" : McCompat.screen(mc).getTitle().getString());
    }

    private static void closeMenu(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        });
        ctx.waitTicks(3);
    }

    private static String family(String title) {
        for (String k : new String[]{"Click in order!", "Correct all the panes!", "Change all to same color!", "What starts with",
                "Select all the", "Click the button on time!"}) {
            if (title.startsWith(k)) {
                return k;
            }
        }
        return title;
    }

    private static void rules(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "S1", true);
        if (!waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        // ---- refusals ----
        long m = LogTap.mark();
        clickTerminal(ctx, 2, "T1", "Inactive Terminal");
        expect(f, "S2's terminal during S1 refuses", waitChat(ctx, m, "This Terminal doesn't seem to be responsive at the moment.", 20), true);
        expect(f, "no window for S2's terminal during S1", menuTitle(ctx), "");
        serverRun(ctx, sv -> ModUnderTest.staticCall(STATIONS, "testComplete",
                new Class<?>[]{MinecraftServer.class, int.class, String.class}, new Object[]{sv, 1, "T1"}));
        m = LogTap.mark();
        clickTerminal(ctx, 1, "T1", "Terminal Active");
        expect(f, "a done terminal refuses", waitChat(ctx, m, "This Terminal has already been completed!", 20), true);
        // ---- levers ----
        BlockPos s2low = new BlockPos(27, 124, 127);
        tp(ctx, 27.5, 124, 129.5, 180, 30);
        ctx.waitTicks(6);
        m = LogTap.mark();
        useBlock(ctx, s2low, Direction.UP);
        ctx.waitTicks(6);
        boolean toggled = ctx.computeOnClient(mc -> mc.level.getBlockState(s2low).getValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED));
        expect(f, "S2's lever during S1 toggles", toggled, true);
        expect(f, "S2's lever during S1 gives no credit", chatSeen(m, "activated a lever!"), false);
        BlockPos east = new BlockPos(106, 124, 113);
        tp(ctx, 104.5, 124, 113.5, -90, 30);
        ctx.waitTicks(6);
        m = LogTap.mark();
        useBlock(ctx, east, Direction.UP);
        expect(f, "S1's lever counts", waitChat(ctx, m, "activated a lever!", 20), true);
        m = LogTap.mark();
        useBlock(ctx, east, Direction.UP);
        expect(f, "a done lever refuses", waitChat(ctx, m, "Someone has already activated this lever!", 20), true);
        // ---- a terminal keeps its type; Melody's six rows; the click limit ----
        clickTerminal(ctx, 1, "T2", "Inactive Terminal");
        until(ctx, "T2 open", mc -> !menuTitle(ctx).isEmpty(), 20);
        String first = family(menuTitle(ctx));
        closeMenu(ctx);
        clickTerminal(ctx, 1, "T2", "Inactive Terminal");
        until(ctx, "T2 open again", mc -> !menuTitle(ctx).isEmpty(), 20);
        String again = family(menuTitle(ctx));
        closeMenu(ctx);
        println(RULES, "T2 opened as '" + first + "' then '" + again + "'");
        expect(f, "a terminal keeps its type across reopens", !first.isEmpty() && first.equals(again), true);
        System.setProperty("killer560.bosssim.terminal", "MELODY");
        clickTerminal(ctx, 1, "T3", "Inactive Terminal");
        until(ctx, "Melody open", mc -> menuTitle(ctx).startsWith("Click the button on time!"), 20);
        int rows = ctx.computeOnClient(mc -> mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu cm ? cm.getRowCount() : -1);
        expect(f, "Melody's window rows", rows, 6);
        closeMenu(ctx);
        System.setProperty("killer560.bosssim.terminal", "PANES");
        clickTerminal(ctx, 1, "T4", "Inactive Terminal");
        until(ctx, "Panes open", mc -> menuTitle(ctx).startsWith("Correct all the panes!"), 20);
        ctx.waitTicks(3);
        int[] grid = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
        String before = ctx.computeOnClient(mc -> panes(mc, grid));
        ctx.runOnClient(mc -> {
            var menu = mc.player.containerMenu;
            for (int g = 0; g < 8; g++) {
                mc.gameMode.handleContainerInput(menu.containerId, grid[g], 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            }
        });
        ctx.waitTicks(6);
        String after = ctx.computeOnClient(mc -> panes(mc, grid));
        int changed = 0;
        for (int i = 0; i < before.length(); i++) {
            if (before.charAt(i) != after.charAt(i)) {
                changed++;
            }
        }
        println(RULES, "Panes before " + before + ", after 8 clicks in one tick " + after);
        expect(f, "panes changed by 8 clicks in one tick (Hypixel counts 5 in 10 ticks)", changed, 5);
        closeMenu(ctx);
        System.clearProperty("killer560.bosssim.terminal");
        // ---- Simon Says: timing, the start button's reset, a wrong press ----
        tp(ctx, 108.5, 120, 93.5, -90, 0);
        ctx.waitTicks(8);
        useBlock(ctx, new BlockPos(110, 121, 91), Direction.EAST);
        int pressed = phaseTick();
        int[] firstLamp = {-1};
        until(ctx, "the first lamp", mc -> {
            for (int y = 120; y <= 123; y++) {
                for (int z = 92; z <= 95; z++) {
                    if (mc.level.getBlockState(new BlockPos(111, y, z)).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)) {
                        firstLamp[0] = phaseTick();
                        return true;
                    }
                }
            }
            return false;
        }, 30);
        println(RULES, "Simon Says: press at t" + pressed + ", first lamp on the client at t" + firstLamp[0]);
        expect(f, "SS shows round 1 about 4 ticks after the start press", firstLamp[0] - pressed >= 3 && firstLamp[0] - pressed <= 7, true);
        if (!until(ctx, "SS input", mc -> (Boolean) call(DEVICES, "ssInput"), 40)) {
            f.add("SS never took input (" + call(DEVICES, "describe") + ")");
        }
        useBlock(ctx, new BlockPos(110, 121, 91), Direction.EAST);
        ctx.waitTicks(4);
        expect(f, "the start button pressed while SS runs resets it", call(DEVICES, "ssState"), "IDLE");
        ctx.waitTicks(8);
        useBlock(ctx, new BlockPos(110, 121, 91), Direction.EAST);
        if (until(ctx, "SS input again", mc -> (Boolean) call(DEVICES, "ssInput"), 60)) {
            @SuppressWarnings("unchecked")
            List<int[]> seq = (List<int[]>) call(DEVICES, "ssSequence");
            int wrongY = 120, wrongZ = 92;
            outer:
            for (int y = 120; y <= 123; y++) {
                for (int z = 92; z <= 95; z++) {
                    if (seq.get(0)[1] != y || seq.get(0)[2] != z) {
                        wrongY = y;
                        wrongZ = z;
                        break outer;
                    }
                }
            }
            useBlock(ctx, new BlockPos(110, wrongY, wrongZ), Direction.EAST);
            ctx.waitTicks(4);
            expect(f, "a wrong press stops SS", call(DEVICES, "ssState"), "BROKEN");
            boolean strayShow = until(ctx, "the stray show", mc -> {
                for (int y = 120; y <= 123; y++) {
                    for (int z = 92; z <= 95; z++) {
                        if (mc.level.getBlockState(new BlockPos(111, y, z)).is(net.minecraft.world.level.block.Blocks.SEA_LANTERN)) {
                            return true;
                        }
                    }
                }
                return false;
            }, 50);
            expect(f, "a stray show follows a wrong press on its own", strayShow, true);
        } else {
            f.add("SS never took input after its restart");
        }
        // ---- Lights: a lamp lights from its own lever or a neighbour's ----
        tp(ctx, 60.5, 131, 140.5, 180, -20);
        ctx.waitTicks(8);
        BlockPos odd = new BlockPos(59, 134, 142);
        useBlock(ctx, odd, Direction.NORTH);
        ctx.waitTicks(4);
        boolean lit = ctx.computeOnClient(mc -> {
            for (BlockPos q : new BlockPos[]{new BlockPos(59, 134, 143), new BlockPos(58, 134, 143), new BlockPos(60, 134, 143),
                    new BlockPos(59, 133, 143), new BlockPos(59, 135, 143)}) {
                if (!litLamp(mc.level.getBlockState(q))) {
                    return false;
                }
            }
            return true;
        });
        expect(f, "Lights: a lever lights its lamp and its four neighbours'", lit, true);
        // ---- Target: on the plate it lights; off it, the attempt resets ----
        tp(ctx, 63.5, 127, 35.5, 0, -20);
        boolean shown = until(ctx, "a lit target", mc -> call(DEVICES, "litTarget") != null, 30);
        expect(f, "Target lights while he is on the plate", shown, true);
        tp(ctx, 61.5, 127, 33.5, 0, -20);
        boolean reset = until(ctx, "the target going out", mc -> call(DEVICES, "litTarget") == null, 40);
        expect(f, "Target: off the plate, the lit one goes out", reset, true);
        // ---- a death tick in a section not started yet ----
        tp(ctx, 6.5, 107, 100.5, 0, 0);
        m = LogTap.mark();
        expect(f, "a death tick in S3 during S1", waitChat(ctx, m, "[BOSS] Goldor: What do you think you are doing there!", 80), true);
        tp(ctx, 100.5, 107, 40.5, 0, 0);
        ctx.waitTicks(3);
        m = LogTap.mark();
        ctx.waitTicks(70);
        expect(f, "no death tick in S1 during S1 (Goldor still in it)", chatSeen(m, "What do you think you are doing there!"), false);
    }

    private static String panes(Minecraft mc, int[] grid) {
        StringBuilder sb = new StringBuilder();
        for (int g : grid) {
            String id = mc.player.containerMenu.getSlot(g).getItem().getItem().toString();
            sb.append(id.contains("lime") ? 'L' : id.contains("red") ? 'R' : '?');
        }
        return sb.toString();
    }

    // ============================================================================================== helpers

    /** Clicks the crystal nearest (x, y, z) in the client's world, as a player's right-click. */
    private static boolean clickCrystal(ClientGameTestContext ctx, double x, double y, double z) {
        return ctx.computeOnClient(mc -> {
            for (net.minecraft.world.entity.boss.enderdragon.EndCrystal c : mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.boss.enderdragon.EndCrystal.class, new AABB(x - 1.5, y - 1.5, z - 1.5, x + 1.5, y + 1.5, z + 1.5))) {
                mc.gameMode.interact(mc.player, c, new EntityHitResult(c, c.position()), InteractionHand.MAIN_HAND);
                return true;
            }
            return false;
        });
    }

    /** Both tower crystals clicked from the towers' tops (ADM's standing spots), as Hypixel's are picked up. */
    private static boolean pickUpCrystals(ClientGameTestContext ctx, List<String> f, String what) {
        long m = LogTap.mark();
        tp(ctx, 63.5, 238, 49.5, 0, 0);
        ctx.waitTicks(3);
        clickCrystal(ctx, 64.5, 238.375, 50.5);
        ctx.waitTicks(3);
        tp(ctx, 83.5, 238, 49.5, 0, 0);
        ctx.waitTicks(3);
        clickCrystal(ctx, 82.5, 238.375, 50.5);
        ctx.waitTicks(3);
        int n = count(m, "picked up an Energy Crystal!");
        if (n < 2) {
            f.add(what + ": clicking the crystals picked up " + n);
            return false;
        }
        boolean star = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(8).getHoverName().getString().contains("Energy Crystal"));
        expect(f, what + ": the carried crystal sits in slot 8", star, true);
        return true;
    }

    /** The pylons take crystals from P1 t 80; two placed 30 ticks apart say 1/2 then 2/2 (each counts once 28 ticks in). */
    private static boolean placeCrystals(ClientGameTestContext ctx, List<String> f, long m, String what) {
        until(ctx, "the pylons open (P1 t 80)", mc -> "P1".equals(String.valueOf(call(FIGHT, "phase")))
                && (Integer) call(FIGHT, "phaseTick") > 81, 200);
        tp(ctx, 52.5, 221, 44.5, 180, 30);
        ctx.waitTicks(3);
        useBlock(ctx, new BlockPos(52, 223, 42), Direction.SOUTH);
        if (!waitChat(ctx, m, "1/2 Energy Crystals are now active!", 20)) {
            f.add(what + ": the first crystal at a pylon never said 1/2");
            return false;
        }
        ctx.waitTicks(30);
        tp(ctx, 94.5, 221, 44.5, 180, 30);
        ctx.waitTicks(3);
        useBlock(ctx, new BlockPos(94, 223, 42), Direction.SOUTH);
        if (!waitChat(ctx, m, "2/2 Energy Crystals are now active!", 20)) {
            f.add(what + ": the second crystal, 30 ticks after the first, never said 2/2");
            return false;
        }
        return true;
    }

    /** Stands south of the beam on the strip (ADM's lure spot) until Maxor flies into it and the laser stuns him. */
    private static boolean lureMaxor(ClientGameTestContext ctx, List<String> f, long m, String what) {
        tp(ctx, 73.5, 225, 76.5, 180, 0);
        if (!waitChatAny(ctx, m, new String[]{"[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!"}, 260)) {
            double[] at = (double[]) ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class}, new Object[]{"Maxor"});
            f.add(what + ": luring Maxor into the beam never stunned him (Maxor at "
                    + (at == null ? "none" : String.format(Locale.ROOT, "%.1f %.1f %.1f", at[0], at[1], at[2])) + ")");
            return false;
        }
        double[] at = (double[]) ModUnderTest.staticCall(FIGHT, "bossPos", new Class<?>[]{String.class}, new Object[]{"Maxor"});
        boolean inBeam = at != null && at[1] >= 225.0 && Math.hypot(at[0] - 73.5, at[2] - 73.5) <= 3.5;
        println("boss", what + String.format(Locale.ROOT, ": stunned with Maxor at %.2f %.2f %.2f", at[0], at[1], at[2]));
        expect(f, what + ": Maxor in the beam when the laser hit", inBeam, true);
        return true;
    }

    private static Object ap3Node(String type, double x, double y, double z, float yaw, float pitch) {
        try {
            Class<?> cls = Class.forName("com.killer560.hub.ap3.Ap3Node");
            return cls.getConstructor(Class.forName("com.killer560.hub.ap3.Ap3Node$Type"), double.class, double.class,
                    double.class, float.class, float.class).newInstance(
                    ModUnderTest.enumValue("com.killer560.hub.ap3.Ap3Node$Type", type), x, y, z, yaw, pitch);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot make an AP3 " + type + " node", e);
        }
    }

    /** Starts the boss at {@code phase} (opening the sim world from the title screen) and waits for it to be running. */
    static long start(ClientGameTestContext ctx, String phase, boolean master) {
        long mark = LogTap.mark();
        Object start = ModUnderTest.enumValue(START, phase);
        ctx.runOnClient(mc -> mc.execute(() -> ModUnderTest.staticCall(SIM, "start",
                new Class<?>[]{Minecraft.class, start.getClass(), boolean.class}, new Object[]{mc, start, master})));
        ctx.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        if (!until(ctx, "the boss built and running", mc -> !(Boolean) call(SIM, "isBuilding")
                && (Boolean) call(FIGHT, "running"), 2400)) {
            throw new AssertionError("the boss never started at " + phase);
        }
        ctx.waitFor(mc -> McCompat.screen(mc) == null, 600);
        println("boss", "started at " + phase + " from the " + call(ARENA, "source") + ": " + call(FIGHT, "describe"));
        return mark;
    }

    static void cleanup(ClientGameTestContext ctx) {
        System.clearProperty("killer560.bosssim.terminal");
        try {
            ctx.runOnClient(mc -> {
                ModUnderTest.turnOff(TERM_CFG, "setAutoTerminalsEnabled");
                ModUnderTest.turnOff(Ap3RuntimeTests.CONFIG, "setEnabled");
            });
        } catch (Throwable ignored) {
            // never let cleanup replace the verdict
        }
        if (ctx.computeOnClient(mc -> mc.level != null)) {
            SimPuzzleSolveTests.teardown(ctx);
        }
    }

    static Object call(String cls, String method) {
        return ModUnderTest.staticCall(cls, method, new Class<?>[]{}, new Object[]{});
    }

    private static Object fight(String method, int arg) {
        return ModUnderTest.staticCall(FIGHT, method, new Class<?>[]{int.class}, new Object[]{arg});
    }

    private static String phase(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> String.valueOf(ModUnderTest.staticCall(F7, "getPhase")));
    }

    static void serverRun(ClientGameTestContext ctx, java.util.function.Consumer<MinecraftServer> body) {
        ctx.runOnClient(mc -> {
            MinecraftServer s = mc.getSingleplayerServer();
            s.execute(() -> body.accept(s));
        });
        ctx.waitTicks(1);
    }

    static void command(ClientGameTestContext ctx, String cmd) {
        serverRun(ctx, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd));
    }

    static void tp(ClientGameTestContext ctx, double x, double y, double z, float yaw, float pitch) {
        command(ctx, String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
        ctx.waitTicks(3);
    }

    private static void useBlock(ClientGameTestContext ctx, BlockPos pos, Direction face) {
        ctx.runOnClient(mc -> {
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        });
    }

    private static Entity standAt(Minecraft mc, double[] at, String name) {
        for (ArmorStand s : mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(at[0] - 1, at[1] - 1, at[2] - 1,
                at[0] + 1, at[1] + 1, at[2] + 1))) {
            if (s.getCustomName() != null && s.getCustomName().getString().contains(name)) {
                return s;
            }
        }
        return null;
    }

    private static ItemFrame frameAt(Minecraft mc, BlockPos pos) {
        for (ItemFrame fr : mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(pos).inflate(0.2))) {
            if (fr.blockPosition().equals(pos)) {
                return fr;
            }
        }
        return null;
    }

    static boolean until(ClientGameTestContext ctx, String what, Predicate<Minecraft> p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (ctx.computeOnClient(p::test)) {
                return true;
            }
            ctx.waitTick();
        }
        println("boss", "timed out waiting for " + what);
        return false;
    }

    private static boolean chatSeen(long mark, String text) {
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static int count(long mark, String text) {
        int n = 0;
        for (String l : LogTap.since(mark)) {
            if (l.contains("[CHAT]") && l.contains(text)) {
                n++;
            }
        }
        return n;
    }

    static boolean waitChat(ClientGameTestContext ctx, long mark, String text, int ticks) {
        return until(ctx, "chat '" + text + "'", mc -> chatSeen(mark, text), ticks);
    }

    private static boolean waitChatAny(ClientGameTestContext ctx, long mark, String[] texts, int ticks) {
        return until(ctx, "one of " + String.join(" | ", texts), mc -> {
            for (String t : texts) {
                if (chatSeen(mark, t)) {
                    return true;
                }
            }
            return false;
        }, ticks);
    }

    static void expect(List<String> f, String what, Object got, Object want) {
        boolean ok = String.valueOf(want).equals(String.valueOf(got));
        println("boss", what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) {
            f.add(what + ": " + got + ", want " + want);
        }
    }

    static void println(String name, String line) {
        System.out.println("[" + name + "] " + line);
    }
}
