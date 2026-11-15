package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.FrameClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.testkit.gametest.hx.CarnivalSuite.CX;
import static dev.testkit.gametest.hx.CarnivalSuite.CZ;
import static dev.testkit.gametest.hx.CarnivalSuite.GX;
import static dev.testkit.gametest.hx.CarnivalSuite.GZ;
import static dev.testkit.gametest.hx.CarnivalSuite.PX;
import static dev.testkit.gametest.hx.CarnivalSuite.PZ;
import static dev.testkit.gametest.hx.CarnivalSuite.Y;

/**
 * carnfix3-1114 cases, from killer560's third round of games on pincarn4 (11-14: "the digger didnt walk and it was all
 * overall still quite bad"; findings in killer560s-mod-logs/carnfix3-1114-findings.md), in {@code 3780-carnival-session}:
 * <ul>
 *   <li>3823 Auto Catch a Fish against Hypixel's 1.8 hook (the test server's {@code legacyhook} model: 1.5 b/t, splash and
 *       glide, hooks what its path crosses on the water too): two fish swimming on a pond; it must catch at least four, at
 *       most three casts more than catches, and every stray cast after a reel is gone within 15 ticks.</li>
 *   <li>3824 Auto Fruit Digging walks onto a pick that is already inside reach (his game: it never walked).</li>
 *   <li>3825 Auto Shovel Swap with taps lost on the client (no use packet): it notices, taps again, and the first dig
 *       goes out only with the shovel in the pick's mode.</li>
 *   <li>3826 Auto Zombie Shootout against real darts (the test server's {@code legacydart} model: an arrow at 4 b/t from
 *       the eye, as his log shows): one-hit zombies walking across his look at 12 and 16 blocks; kills per dart.</li>
 *   <li>carnfix4-1114 (his pincarn6 games): 3827 two fish swimming circles (the circle fit must act, 4+ catches), 3828 a
 *       raised bank in front of the shore (at most one bobber stops on it), 3829 the server's lever click confirms each
 *       shovel step before the model follows 30 ticks later (exactly the needed uses, right mode at the first dig).</li>
 * </ul>
 */
final class Carnival1114bCases {

    private Carnival1114bCases() {
    }

    static void register(Session s) {
        s.test("3823-carnival-fish-legacy-hook", Carnival1114bCases::fishLegacy);
        s.test("3824-carnival-dig-walk-in-reach", Carnival1114bCases::digWalkInReach);
        s.test("3825-carnival-shovel-lost-tap", Carnival1114bCases::shovelLostTap);
        s.test("3826-carnival-zombie-hit-ratio", Carnival1114bCases::zombieHits);
        s.test("3827-carnival-fish-circles", Carnival1114bCases::fishCircles);
        s.test("3828-carnival-fish-bank", Carnival1114bCases::fishBank);
        s.test("3829-carnival-shovel-lever-click", Carnival1114bCases::shovelLeverClick);
    }

    // ---- 3827 / 3828 (carnfix4-1114) ------------------------------------------------------------------------------

    /** One Catch a Fish game on the pond: two fish moved by {@code path(tick)} = {ax, az, bx, bz} every 2 ticks for
     *  {@code ticks}; returns {casts, catches, bank rests (a bobber that stopped on solid ground west of {@code bankX}),
     *  ticks a circle was fitted to the target}. {@code bankX} NaN: no bank. */
    private static int[] fishGame(Session c, int ticks, java.util.function.IntFunction<double[]> path, double bankX) {
        String a = c.hx().stand(path.apply(0)[0], Y, path.apply(0)[1], null, "helmet", CarnivalSuite.head(CarnivalSuite.YELLOW), "marker", false, "invisible", false);
        String b = c.hx().stand(path.apply(0)[2], Y, path.apply(0)[3], null, "helmet", CarnivalSuite.head(CarnivalSuite.OTHER), "marker", false, "invisible", false);
        c.waitUntil("two fish seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 2, 60);
        int casts0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"));
        int reels0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "reels"));
        int bankRests = 0;
        int circled = 0;
        boolean restCounted = false;
        for (int i = 0; i < ticks; i++) {
            if (i % 2 == 0) {
                double[] p = path.apply(i);
                c.server().command(String.format(Locale.ROOT, "tp %s %.3f %d %.3f", a, p[0], Y, p[1]));
                c.server().command(String.format(Locale.ROOT, "tp %s %.3f %d %.3f", b, p[2], Y, p[3]));
            }
            double[] h = c.onClient(mc -> {
                var hook = mc.player.fishing;
                double[] circle = (double[]) Mod.staticCall("carnival.AutoCatchAFish", "targetCircle");
                return hook == null ? new double[]{0, 0, 0, 1, circle == null ? 0 : 1}
                        : new double[]{hook.getX(), hook.getY(), hook.getDeltaMovement().lengthSqr(), 0, circle == null ? 0 : 1};
            });
            circled += (int) h[4];
            if (h[3] == 1) {
                restCounted = false;
            } else if (!restCounted && h[2] < 1e-4 && !Double.isNaN(bankX) && h[0] < bankX && h[1] >= Y + 0.95) {
                restCounted = true;
                bankRests++;
            }
            c.ctx().waitTicks(1);
        }
        int casts = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts")) - casts0;
        int reels = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "reels")) - reels0;
        return new int[]{casts, reels, bankRests, circled};
    }

    private static void fishSetup(Session c, HxKit.Settings s) {
        CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
        CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
        c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
        pond(c);
        c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
        CarnivalSuite.holdSlot(c, 0);
        CarnivalSuite.stand(c, SHORE_X, PZ, -90f, 0f);
        CarnivalSuite.sidebar(c);
        c.hx().chat("§e[NPC] Carnival Fisherman§f: Good luck, friend!");
        c.waitUntil("Catch a Fish starts", mc -> "CATCH_A_FISH".equals(String.valueOf(
                Mod.staticCall("carnival.CarnivalState", "game"))), 60);
    }

    private static void fishTeardown(Session c) {
        c.onClient(mc -> {
            mc.options.keyUse.setDown(false);
            return null;
        });
        unpond(c);
        c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
        CarnivalSuite.cleanup(c);
    }

    /** 3827: his pincarn6 fish swam circles (radius 2-5, 0.012-0.039 rad a tick, either way): two fish circle, opposite
     *  ways; the auto must fit the circle (asserted) and catch at least four in 700 ticks at 0.5+ catches a cast. */
    static void fishCircles(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            fishSetup(c, s);
            int[] r = fishGame(c, 700, i -> new double[]{
                    CX + 8.5 + 2.5 * Math.cos(0.03 * i), PZ + 2.5 * Math.sin(0.03 * i),
                    CX + 10.5 + 3.5 * Math.cos(-0.02 * i + 2), PZ + 3.5 * Math.sin(-0.02 * i + 2)}, Double.NaN);
            c.note(String.format(Locale.ROOT, "%d casts, %d catches (%.2f a cast); a circle fitted to the target on %d of 700 ticks",
                    r[0], r[1], r[0] == 0 ? 0 : r[1] / (double) r[0], r[3]));
            c.check(r[3] > 50, "a circle was fitted to the target on only " + r[3] + " ticks: the circle tracking never acted");
            c.check(r[1] >= 4, "only " + r[1] + " fish caught in 700 ticks (want 4+)");
            c.check(r[1] >= 0.5 * r[0], r[1] + " catches for " + r[0] + " casts, want 0.5+ a cast");
        } finally {
            fishTeardown(c);
        }
    }

    /** 3828: a raised bank (stone level with the ground, a hair above the water) two blocks wide in front of him, and a
     *  fish circling close behind it, where a steep cast meets the bank (about 25 of his 38 pincarn6 casts struck the
     *  slab at the real pond's edge). No more than one bobber may stop on the bank, and it must still catch. */
    static void fishBank(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            fishSetup(c, s);
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", CX + 4, Y, CZ - 7, CX + 5, Y, CZ + 7));
            int[] r = fishGame(c, 700, i -> new double[]{
                    CX + 8.0 + 1.5 * Math.cos(0.03 * i), PZ + 1.5 * Math.sin(0.03 * i),
                    CX + 11.0 + 2.0 * Math.cos(-0.025 * i), PZ + 3 + 2.0 * Math.sin(-0.025 * i)}, CX + 6.0);
            c.note(String.format(Locale.ROOT, "%d casts, %d catches; %d bobber(s) stopped on the bank", r[0], r[1], r[2]));
            c.check(r[0] >= 1, "it never cast");
            c.check(r[2] <= 1, r[2] + " casts stopped on the bank: the plan casts through it");
            c.check(r[1] >= 3, "only " + r[1] + " fish caught in 700 ticks (want 3+)");
        } finally {
            fishTeardown(c);
        }
    }

    // ---- 3829 (carnfix4-1114) -------------------------------------------------------------------------------------

    private static String pitchOf(String mode) {
        return "Mines".equals(mode) ? "1" : "Anchor".equals(mode) ? "1.142857" : "1.285714";
    }

    /** 3829: as Hypixel does, the server clicks a lever at the player (pitch = the new mode) 2 ticks after each shovel use
     *  and changes the model only 30 ticks later (his pincarn6 log: the model 1-2 s behind the click, and every retap came
     *  from waiting on the model). Two steps are needed: exactly two shovel uses may reach the server, the first dig must
     *  hold the pick's mode, and the click must have been heard. */
    static void shovelLeverClick(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Shovel Swap is cheat-only");
            return;
        }
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            Object fd = c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"));
            boolean guard = c.onClient(mc -> (Boolean) Mod.call(fd, "isPreventWrongShovel"));
            s.custom(() -> Mod.call(fd, "setPreventWrongShovel", false), () -> Mod.call(fd, "setPreventWrongShovel", guard));
            Object dig = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "dig"));
            Object swap = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "swap"));
            s.custom(() -> { }, () -> {
                Mod.call(dig, "setEnabled", false);
                Mod.call(swap, "setEnabled", false);
            });
            c.server().command("clear @p");
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.02");
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            String want = Carnival1110Cases.pick(c)[1];
            String held = next(want);
            String serverMode = held;
            c.hx().give(0, Carnival1111Cases.shovel(model(held)));
            CarnivalSuite.holdSlot(c, 0);
            int heard0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoShovelSwap", "heard"));
            int d0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps"));
            long b0 = c.hx().head();
            c.onClient(mc -> {
                Mod.call(swap, "setEnabled", true);
                Mod.call(dig, "setEnabled", true);
                return null;
            });
            int seen = 0;
            List<int[]> due = new ArrayList<>(); // {tick, 0 = click / 1 = model}
            List<String> dueMode = new ArrayList<>();
            String modeAtDig = null;
            String wantAtDig = null;
            for (int t = 0; t < 300 && modeAtDig == null; t++) {
                List<com.google.gson.JsonObject> ev = c.hx().events(b0, "use_item").stream()
                        .filter(e -> e.get("item").getAsString().contains("shovel")).toList();
                for (; seen < ev.size(); seen++) {
                    serverMode = next(serverMode);
                    due.add(new int[]{t + 2, 0});
                    dueMode.add(serverMode);
                    due.add(new int[]{t + 30, 1});
                    dueMode.add(serverMode);
                }
                for (int k = 0; k < due.size(); k++) {
                    if (due.get(k)[0] == t) {
                        if (due.get(k)[1] == 0) {
                            c.server().command("execute at @p run playsound minecraft:block.lever.click block @p ~ ~ ~ 1 " + pitchOf(dueMode.get(k)));
                        } else {
                            held = dueMode.get(k);
                            c.hx().give(0, Carnival1111Cases.shovel(model(held)));
                        }
                    }
                }
                String got = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps") > d0
                        ? (String) Mod.staticCall("carnival.AutoShovelSwap", "modeOf", mc.player.getMainHandItem()) : null);
                if (got != null) {
                    modeAtDig = got;
                    wantAtDig = Carnival1110Cases.pick(c)[1];
                }
                c.ctx().waitTicks(1);
            }
            int heard = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoShovelSwap", "heard")) - heard0;
            c.note("shovel uses that reached the server " + seen + " (2 needed); clicks heard " + heard
                    + "; at the first dig held " + modeAtDig + ", pick wants " + wantAtDig);
            c.check(heard >= 1, "the lever click was never heard: the sound confirm never acted");
            c.check(seen == 2, seen + " shovel uses reached the server for a 2-step swap (a retap while the model lagged)");
            c.check(modeAtDig != null, "it never dug");
            c.check(modeAtDig == null || modeAtDig.equals(wantAtDig), "it dug holding " + modeAtDig + " for a " + wantAtDig + " pick");
        } finally {
            c.server().command("attribute @p minecraft:block_break_speed base set 1");
            c.server().command("gamemode " + mode0 + " @p");
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                return null;
            });
            Carnival1110Cases.finish(c);
        }
    }

    // ---- pond (3821, 3823) ----------------------------------------------------------------------------------------

    /** The shore block's east edge: he stands on it facing the water (yaw -90), as at the real pond, where the water is
     *  level with the ground and starts at his feet. A pond that starts blocks away behind dry ground stops every cast
     *  on the ground (the first 3823 run: 18 casts, all stuck on the platform 2 blocks out). */
    static final double SHORE_X = CX + 3.7;
    static final double FISH_A_X = CX + 9.5;
    static final double FISH_B_X = CX + 12.0;

    /** Water three deep from the shore east past the platform edge, in a sealed stone basin, and the 1.8 hook for the
     *  player. One block deep was too shallow: a cast sank to the floor and stuck there (the second 3823 run). */
    static void pond(Session c) {
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", CX + 3, Y - 3, CZ - 8, CX + 15, Y - 1, CZ + 8));
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", CX + 4, Y, CZ - 8, CX + 15, Y, CZ + 8));
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", CX + 4, Y - 2, CZ - 7, CX + 14, Y, CZ + 7));
        c.server().command("tag @a add legacyhook");
    }

    static void unpond(Session c) {
        c.server().command("tag @a remove legacyhook");
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", CX + 3, Y - 3, CZ - 8, CX + 15, Y - 1, CZ + 8));
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", CX + 4, Y, CZ - 8, CX + 15, Y, CZ + 8));
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone", CX + 4, Y, CZ - 8, CX + 8, Y, CZ + 8));
        c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", GX, Y - 1, GZ, GX + 6, Y - 1, GZ + 6));
    }

    // ---- 3823 -----------------------------------------------------------------------------------------------------

    static void fishLegacy(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            pond(c);
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, SHORE_X, PZ, -90f, 0f);
            CarnivalSuite.sidebar(c);
            c.hx().chat("§e[NPC] Carnival Fisherman§f: Good luck, friend!");
            c.waitUntil("Catch a Fish starts", mc -> "CATCH_A_FISH".equals(String.valueOf(
                    Mod.staticCall("carnival.CarnivalState", "game"))), 60);
            String a = c.hx().stand(FISH_A_X, Y, PZ - 5, null, "helmet", CarnivalSuite.head(CarnivalSuite.YELLOW), "marker", false, "invisible", false);
            String b = c.hx().stand(FISH_B_X, Y, PZ + 5, null, "helmet", CarnivalSuite.head(CarnivalSuite.OTHER), "marker", false, "invisible", false);
            c.waitUntil("two fish seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 2, 60);
            int casts0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"));
            int reels0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "reels"));
            List<long[]> rows = new ArrayList<>();
            double phase = 0;
            for (int i = 0; i < 700; i++) {
                if (i % 2 == 0) {
                    // Both fish swim 0.1 a tick across his look, back and forth over 10 blocks, opposite ways.
                    phase += 0.2;
                    double off = 5 - Math.abs((phase % 20) - 10);
                    c.server().command(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", a, FISH_A_X, Y, PZ + off));
                    c.server().command(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", b, FISH_B_X, Y, PZ - off));
                }
                rows.add(c.onClient(mc -> new long[]{FrameClock.tickCount(),
                        (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"),
                        (Integer) Mod.staticCall("carnival.AutoCatchAFish", "reels"),
                        (Boolean) Mod.staticCall("carnival.AutoCatchAFish", "holding") ? 1 : 0,
                        mc.player.fishing != null ? 1 : 0}));
                c.ctx().waitTicks(1);
            }
            long[] last = rows.get(rows.size() - 1);
            int casts = (int) last[1] - casts0;
            int reels = (int) last[2] - reels0;
            int strayLeft = 0;
            int reelEnds = 0;
            for (int i = 1; i < rows.size(); i++) {
                if (rows.get(i - 1)[3] == 1 && rows.get(i)[3] == 0) {
                    reelEnds++;
                    int j = Math.min(rows.size() - 1, i + 15);
                    if (rows.get(j)[4] == 1 && rows.get(j)[1] == rows.get(i)[1]) {
                        strayLeft++;
                    }
                }
            }
            double[] cal = c.onClient(mc -> (double[]) Mod.staticCall("carnival.Lead", "calibrationForTest"));
            c.note(String.format(Locale.ROOT, "%d casts, %d catches in %d ticks (hit ratio %.2f); %d reel ends, %d stray casts left after 15 ticks; strays reeled back %s; cast range error learned %.2f",
                    casts, reels, rows.size(), casts == 0 ? 0 : reels / (double) casts, reelEnds, strayLeft,
                    c.onClient(mc -> Mod.staticCall("carnival.AutoCatchAFish", "strays")), cal[4]));
            c.check(casts >= 1, "it never cast");
            c.check(reels >= 4, "only " + reels + " fish caught in 700 ticks (want 4+)");
            // The test fish turn round every 50 ticks, inside some 22-tick casts: a cast led to where a fish was going then
            // lands about 4 blocks off, which no lead can know (run 5: 13 of 17, 3 of the 4 misses that way).
            c.check(reels >= 0.7 * casts, String.format(Locale.ROOT, "%d catches for %d casts (%.2f), want 0.7+: the casts miss the fish",
                    reels, casts, casts == 0 ? 0 : reels / (double) casts));
            c.check(strayLeft == 0, strayLeft + " stray cast(s) still out 15 ticks after a reel");
            c.check(Math.abs(cal[4]) < 1.0, "the learned cast range error is " + cal[4] + " on a server that runs the same physics");
        } finally {
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            unpond(c);
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3826 -----------------------------------------------------------------------------------------------------

    private static String hitZombie(double x, double z, String name) {
        return String.format(Locale.ROOT, "summon zombie %.2f %d %.2f {Tags:[\"carn\"],NoAI:1b,NoGravity:1b,Silent:1b,"
                + "PersistenceRequired:1b,IsBaby:0b,Health:1f,CustomName:\"%s\",equipment:{head:{id:\"minecraft:leather_helmet\","
                + "count:1}}}", x, Y + 1, z, name);
    }

    static void zombieHits(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Zombie Shootout is cheat-only");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.ZombieShootoutConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.ShootoutShotConfig", "auto");
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            c.server().command("tag @a add legacydart");
            c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startShootout(c, false);
            double[] lane = {PZ + 12, PZ + 16};
            double[] x = {PX - 4, PX + 4};
            double[] vx = {0.12, -0.12};
            String[] alive = new String[2];
            int[] respawnAt = {0, 0};
            int spawned = 0;
            int kills = 0;
            int shots0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoZombieShootout", "shots"));
            for (int t = 0; t < 600; t++) {
                for (int i = 0; i < 2; i++) {
                    if (alive[i] == null) {
                        if (t >= respawnAt[i]) {
                            alive[i] = "zh" + spawned++;
                            c.server().command(hitZombie(x[i], lane[i], alive[i]));
                        }
                        continue;
                    }
                    x[i] += vx[i];
                    if (Math.abs(x[i] - PX) > 6) {
                        vx[i] = -vx[i];
                    }
                    c.server().command(String.format(Locale.ROOT, "tp @e[tag=carn,name=%s] %.3f %d %.3f", alive[i], x[i], Y + 1, lane[i]));
                }
                String a0 = alive[0];
                String a1 = alive[1];
                boolean[] dead = c.onClient(mc -> {
                    boolean[] d = {a0 != null, a1 != null};
                    for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                        if (e.isAlive() && e.getCustomName() != null) {
                            String n = e.getCustomName().getString();
                            d[0] &= !n.equals(a0);
                            d[1] &= !n.equals(a1);
                        }
                    }
                    return d;
                });
                for (int i = 0; i < 2; i++) {
                    // A fresh zombie needs a few ticks to reach the client before it can be missing.
                    if (dead[i] && alive[i] != null && t > respawnAt[i] + 5) {
                        kills++;
                        alive[i] = null;
                        respawnAt[i] = t + 10;
                    }
                }
                c.ctx().waitTicks(1);
            }
            int shots = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoZombieShootout", "shots")) - shots0;
            double[] cal = c.onClient(mc -> (double[]) Mod.staticCall("carnival.Lead", "calibrationForTest"));
            double ratio = shots == 0 ? 0 : kills / (double) shots;
            c.note(String.format(Locale.ROOT, "%d darts, %d kills (%.0f%% hit) of %d zombies in 600 ticks; learned lead %.2f ticks, height %.2f, dart speed %.2f",
                    shots, kills, ratio * 100, spawned, cal[1], cal[2], cal[0]));
            c.check(shots >= 8, "only " + shots + " darts in 600 ticks");
            c.check(kills >= 6, "only " + kills + " zombies killed: the darts miss");
            c.check(ratio >= 0.8, String.format(Locale.ROOT, "hit ratio %.2f (%d kills for %d darts), want 0.8+", ratio, kills, shots));
        } finally {
            c.server().command("tag @a remove legacydart");
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3824 -----------------------------------------------------------------------------------------------------

    private static double[] center(String label) {
        return new double[]{GX + (label.charAt(1) - '1') + 0.5, GZ + (label.charAt(0) - 'A') + 0.5};
    }

    static void digWalkInReach(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Fruit Digging is cheat-only");
            return;
        }
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            Object dig = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "dig"));
            Object swap = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "swap"));
            s.custom(() -> { }, () -> {
                Mod.call(dig, "setEnabled", false);
                Mod.call(swap, "setEnabled", false);
            });
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.1");
            Carnival1111Cases.realShovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 20f);
            CarnivalSuite.startFruit(c);
            String first = Carnival1110Cases.pick(c)[0];
            double[] fc = center(first);
            // The grid corner farthest from the pick: every tile is still inside 4.5 reach of the eye there.
            double sx = fc[0] < PX ? GX + 6.5 : GX + 0.5;
            double sz = fc[1] < PZ ? GZ + 6.5 : GZ + 0.5;
            CarnivalSuite.stand(c, sx, sz, 0f, 20f);
            int w0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "walkTicks"));
            int d0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps"));
            c.onClient(mc -> {
                Mod.call(swap, "setEnabled", true);
                Mod.call(dig, "setEnabled", true);
                return null;
            });
            c.waitUntil("it digs", mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps") > d0, 300);
            double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            String target = c.onClient(mc -> (String) Mod.staticCall("carnival.AutoFruitDigging", "lastTarget"));
            String label = target.split("/")[0];
            double[] tc = center(label);
            double flat = Math.hypot(at[0] - tc[0], at[1] - tc[1]);
            double moved = Math.hypot(at[0] - sx, at[1] - sz);
            double startFlat = Math.hypot(sx - tc[0], sz - tc[1]);
            int w1 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "walkTicks"));
            boolean keyUp = c.onClient(mc -> mc.options.keyUp.isDown());
            c.note(String.format(Locale.ROOT, "pick %s (first pick %s); started %.2f from it, dug from %.2f after walking %.2f blocks in %d ticks; Forward down %s",
                    label, first, startFlat, flat, moved, w1 - w0, keyUp));
            // The auto may take another tile than the predicted first pick (ties on an empty board); any start past the
            // 2-block walk threshold still proves the walk inside reach.
            c.check(startFlat > 2.0, "the start was already next to the pick (" + startFlat + "); the case proved nothing");
            c.check(w1 > w0 && moved > 1.0, "it did not walk to a pick inside reach (" + (w1 - w0) + " ticks, " + moved + " blocks)");
            c.check(flat <= 1.6, "it dug from " + flat + " blocks away, want next to the tile (1.6)");
        } finally {
            c.server().command("attribute @p minecraft:block_break_speed base set 1");
            c.server().command("gamemode " + mode0 + " @p");
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                mc.options.keyUp.setDown(false);
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            CarnivalSuite.cleanup(c);
        }
    }

    // ---- 3825 -----------------------------------------------------------------------------------------------------

    /** Use clicks still to swallow before vanilla's handleKeybinds sees them (runs after the mod's START tick). */
    private static final AtomicInteger SWALLOW = new AtomicInteger();
    private static boolean registered;

    private static String next(String mode) {
        return "Mines".equals(mode) ? "Anchor" : "Anchor".equals(mode) ? "Treasure" : "Mines";
    }

    private static String model(String mode) {
        return "Mines".equals(mode) ? "iron" : "Anchor".equals(mode) ? "diamond" : "gold";
    }

    static void shovelLostTap(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Shovel Swap is cheat-only");
            return;
        }
        c.onClient(mc -> {
            if (!registered) {
                registered = true;
                ClientTickEvents.START_CLIENT_TICK.register(m -> {
                    if (SWALLOW.get() > 0 && m.options.keyUse.consumeClick()) {
                        while (m.options.keyUse.consumeClick()) {
                            // drop them all
                        }
                        SWALLOW.decrementAndGet();
                    }
                });
            }
            return null;
        });
        String mode0 = c.onClient(mc -> mc.gameMode.getPlayerMode().getName());
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            Object fd = c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"));
            boolean guard = c.onClient(mc -> (Boolean) Mod.call(fd, "isPreventWrongShovel"));
            s.custom(() -> Mod.call(fd, "setPreventWrongShovel", false), () -> Mod.call(fd, "setPreventWrongShovel", guard));
            Object dig = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "dig"));
            Object swap = c.onClient(mc -> Mod.staticCall("carnival.AutoFruitConfig", "swap"));
            s.custom(() -> { }, () -> {
                Mod.call(dig, "setEnabled", false);
                Mod.call(swap, "setEnabled", false);
            });
            c.server().command("clear @p");
            c.server().command("gamemode survival @p");
            c.server().command("attribute @p minecraft:block_break_speed base set 0.02");
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            String want = Carnival1110Cases.pick(c)[1];
            String held = next(want);
            c.hx().give(0, Carnival1111Cases.shovel(model(held)));
            CarnivalSuite.holdSlot(c, 0);
            int lost0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoShovelSwap", "lostTaps"));
            int d0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps"));
            long b0 = c.hx().head();
            SWALLOW.set(2);
            c.onClient(mc -> {
                Mod.call(swap, "setEnabled", true);
                Mod.call(dig, "setEnabled", true);
                return null;
            });
            int seen = 0;
            List<Integer> replaceAt = new ArrayList<>();
            String modeAtDig = null;
            String wantAtDig = null;
            for (int t = 0; t < 200 && modeAtDig == null; t++) {
                // The server answers each use with the next model 12 ticks later (his lag).
                List<com.google.gson.JsonObject> ev = c.hx().events(b0, "use_item").stream()
                        .filter(e -> e.get("item").getAsString().contains("shovel")).toList();
                for (; seen < ev.size(); seen++) {
                    replaceAt.add(t + 12);
                }
                while (!replaceAt.isEmpty() && replaceAt.get(0) <= t) {
                    replaceAt.remove(0);
                    held = next(held);
                    c.hx().give(0, Carnival1111Cases.shovel(model(held)));
                }
                String got = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoFruitDigging", "digTaps") > d0
                        ? (String) Mod.staticCall("carnival.AutoShovelSwap", "modeOf", mc.player.getMainHandItem()) : null);
                if (got != null) {
                    modeAtDig = got;
                    wantAtDig = Carnival1110Cases.pick(c)[1];
                }
                c.ctx().waitTicks(1);
            }
            int lost = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoShovelSwap", "lostTaps")) - lost0;
            c.note("lost taps noticed " + lost + " (2 swallowed, " + SWALLOW.get() + " left); shovel uses that reached the server " + seen
                    + "; at the first dig held " + modeAtDig + ", pick wants " + wantAtDig);
            c.check(SWALLOW.get() == 0, "the case swallowed only " + (2 - SWALLOW.get()) + " of 2 taps; nothing was lost");
            c.check(lost >= 2, "Auto Shovel Swap noticed " + lost + " lost tap(s), want 2");
            c.check(modeAtDig != null, "it never dug after the lost taps");
            c.check(modeAtDig == null || modeAtDig.equals(wantAtDig), "it dug holding " + modeAtDig + " for a " + wantAtDig + " pick");
        } finally {
            SWALLOW.set(0);
            c.server().command("attribute @p minecraft:block_break_speed base set 1");
            c.server().command("gamemode " + mode0 + " @p");
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                return null;
            });
            Carnival1110Cases.finish(c);
        }
    }
}
