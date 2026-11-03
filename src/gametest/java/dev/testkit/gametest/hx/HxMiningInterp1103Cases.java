package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Mining interpolation (mod branch mithril-interp-1103, killer560 2026-11-03):
 * <ul>
 *   <li>1103-mining-pick-timer: "the pickobulus timer in tab only counts down every 3s" - tab readings every 60 ticks
 *       (35, 32, 29, 26, one late, one stale) make the timer step once a second (35, 34, 33 ...) and never back up;
 *       the ready line ends it while the tab still says 3s, the use line starts the measured cooldown while the tab
 *       still says Available.</li>
 *   <li>1103-mining-com-progress: "interpolate that too so it feels like it updates every block mined" - Upper Mines
 *       Mithril at 40% (goal 250): 5 counted blocks show 42%, the tab's 42% then does not jump, a block in Cliffside
 *       Veins does not count, an over-count eases down a step at a time to the tab, never 100% before DONE.</li>
 *   <li>1103-mining-com-progress-live: the same through the real path - the block under his crosshair with attack held,
 *       turned to bedrock by the server, in an area from a test graph; the block in the other area and a block he was
 *       not mining do not count.</li>
 * </ul>
 */
final class HxMiningInterp1103Cases {

    private static final String CFG = "miningqol.MiningQolConfig";
    private static final String STATE = "miningqol.MiningState";
    private static final String ARA = "miningqol.AbilityReadyAlert";
    private static final String PROG = "miningqol.CommissionProgress";
    private static final String HUD = "miningqol.CommissionHud";
    private static final String MAP = "miningqol.MinesAreaMap";

    private HxMiningInterp1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mining-pick-timer", HxMiningInterp1103Cases::pickTimer);
        s.test("1103-mining-com-progress", HxMiningInterp1103Cases::comProgress);
        s.test("1103-mining-com-progress-live", HxMiningInterp1103Cases::comProgressLive);
    }

    private static void mines(Session c, String area, String... coms) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ " + area, "Purse: 0");
        List<String> tab = new ArrayList<>();
        tab.add("Area: Dwarven Mines");
        tab.add("Profile: Test");
        tab.add("");
        tab.add("Commissions:");
        for (String com : coms) {
            tab.add(" " + com);
        }
        tab.add("");
        c.hx().tab(tab, null, null);
        c.waitUntil("Dwarven Mines, " + area + ", " + List.of(coms), mc -> "DWARVEN_MINES".equals(Mod.staticCall(STATE, "island"))
                && area.equals(Mod.staticCall(STATE, "area"))
                && ((List<?>) Mod.staticCall(STATE, "commissions")).size() == coms.length, 100);
    }

    @SuppressWarnings("unchecked")
    private static List<String> timerAt(Session c, List<String> tab, long now, long readAt) {
        return c.onClient(mc -> new ArrayList<>((List<String>) Mod.staticCall(ARA, "timerAtForTest", tab, now, readAt)));
    }

    /** The seconds the Pickobulus line shows, -1 when there is none. */
    private static int secs(List<String> lines) {
        for (String l : lines) {
            String p = l.replaceAll("§.", "");
            if (p.startsWith("Pickobulus: ") && p.endsWith("s") && !p.contains("m ")) {
                return Integer.parseInt(p.substring("Pickobulus: ".length(), p.length() - 1));
            }
        }
        return -1;
    }

    // ---- 1103-mining-pick-timer ---------------------------------------------------------------------------------

    private static void pickTimer(Session c) {
        try (Settings set = new Settings(c).with(CFG, "AbilityAlert", true).with(CFG, "AbilityTimer", true)
                .with(CFG, "AbilitySound", false)) {
            mines(c, "Royal Mines", "Goblin Slayer: 10%");
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            // An hour ahead, so the real tick's clock never ends these deadlines.
            long t0 = c.onClient(mc -> System.currentTimeMillis()) + 3_600_000L;
            // The tab every 60 ticks: 35, 32, 29, 26, the 32 400 ms late and the 26 900 ms late, then a 25 between refreshes.
            long[] at = {0, 3400, 6000, 9900, 10500};
            String[] val = {"35", "32", "29", "26", "25"};
            List<Integer> seen = new ArrayList<>();
            List<Long> changedAt = new ArrayList<>();
            int k = 0;
            int last = Integer.MAX_VALUE;
            for (long t = 0; t <= 13_000; t += 50) {
                List<String> tab = List.of();
                if (k < at.length && t >= at[k]) {
                    tab = List.of("Pickobulus: " + val[k] + "s");
                    k++;
                }
                int s = secs(timerAt(c, tab, t0 + t, t0 + t));
                c.check(s >= 0, "no Pickobulus line at +" + t + " ms");
                c.check(s <= last, "the timer went back up from " + last + " to " + s + " at +" + t + " ms");
                if (s != last) {
                    seen.add(s);
                    changedAt.add(t);
                    last = s;
                }
            }
            c.note("shown " + seen + " changing at " + changedAt + " ms");
            for (int i = 1; i < seen.size(); i++) {
                c.check(seen.get(i) == seen.get(i - 1) - 1, "skipped from " + seen.get(i - 1) + " to " + seen.get(i));
                long gap = changedAt.get(i) - changedAt.get(i - 1);
                c.check(i == 1 || Math.abs(gap - 1000) <= 100, "a step took " + gap + " ms (" + seen + " at " + changedAt + ")");
            }
            c.check(seen.get(0) == 35 && seen.size() >= 13, "did not count 35 down a second at a time: " + seen);
            // The ready line ends it while the tab still says 3s; that stale 3s does nothing.
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            long t1 = t0 + 100_000;
            timerAt(c, List.of("Pickobulus: Available"), t1, t1);
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "chatForTest", "You used your Pickobulus Pickaxe Ability!",
                    List.of("Pickobulus: Available"), t1));
            timerAt(c, List.of("Pickobulus: 60s"), t1 + 3000, t1 + 3000);
            c.check(secs(timerAt(c, List.of(), t1 + 3000, t1 + 30_000)) == 33, "the tab's 60s did not count down");
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "chatForTest", "Pickobulus is now available!",
                    List.of("Pickobulus: 3s"), t1 + 60_000));
            c.check(secs(timerAt(c, List.of(), t1 + 60_000, t1 + 60_000)) == -1, "the ready line left a timer");
            c.check(secs(timerAt(c, List.of("Pickobulus: 3s"), t1 + 60_500, t1 + 60_500)) == -1,
                    "the stale tab 3s after the ready line brought the timer back");
            timerAt(c, List.of("Pickobulus: Available"), t1 + 62_000, t1 + 62_000);
            // The use line: the measured cooldown (60 s from use to ready) at once, while the tab still says Available.
            Long learned = c.onClient(mc -> (Long) Mod.staticCall(ARA, "learnedMs", "Pickobulus"));
            c.check(learned != null && learned == 60_000L, "measured cooldown " + learned);
            long t2 = t1 + 200_000;
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "chatForTest", "You used your Pickobulus Pickaxe Ability!",
                    List.of("Pickobulus: Available"), t2));
            int afterUse = secs(timerAt(c, List.of(), t2, t2));
            c.check(afterUse == 60, "the use line showed " + afterUse + " (want the measured 60)");
            int stale = secs(timerAt(c, List.of("Pickobulus: Available"), t2 + 1000, t2 + 1000));
            c.check(stale == 59, "the stale Available after the use line changed the timer to " + stale);
            int resync = secs(timerAt(c, List.of("Pickobulus: 57s"), t2 + 3000, t2 + 3000));
            c.check(resync == 57, "the tab's 57s at +3 s read " + resync);
            c.check(secs(timerAt(c, List.of(), t2 + 3000, t2 + 61_000)) == -1, "a negative or zero timer was shown");
            c.note("ready line ends it under a stale 3s; use line starts the measured 60 s under a stale Available");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(ARA, "resetForTest"));
            hub(c);
        }
    }

    // ---- 1103-mining-com-progress -------------------------------------------------------------------------------

    private static String shown(Session c, String name, List<String> lines) {
        return c.onClient(mc -> (String) Mod.staticCall(PROG, "shownForTest", name, lines));
    }

    private static double num(String s) {
        return Double.parseDouble(s.substring(0, s.length() - 1));
    }

    private static void comProgress(Session c) {
        String um = "Upper Mines Mithril";
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionInterpolate", true)) {
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "resetForTest"));
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "holdLiveForTest", true));
            long t = c.onClient(mc -> System.currentTimeMillis()) + 3_600_000L;
            List<String> tab40 = List.of(um + ": 40%", "Titanium Miner: 0%", "Goblin Slayer: 10%");
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "tabForTest", tab40, t));
            c.check("40%".equals(shown(c, um, tab40)), "start " + shown(c, um, tab40));
            for (int i = 0; i < 5; i++) {
                long ti = t + 200L * i;
                c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "breakForTest", "Mithril", "Upper Mines", "Royal Mines", tab40, ti));
            }
            String five = shown(c, um, tab40);
            c.check("42%".equals(five), "5 blocks of 250 from 40% show " + five + " (want 42%)");
            // A block in Cliffside Veins, with him standing in the Upper Mines: does not count.
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "breakForTest", "Mithril", "Cliffside Veins", "Upper Mines", tab40, t + 1100));
            c.check("42%".equals(shown(c, um, tab40)), "a Cliffside Veins block counted: " + shown(c, um, tab40));
            // The tab catches up: no jump.
            List<String> tab42 = List.of(um + ": 42%", "Titanium Miner: 0%", "Goblin Slayer: 10%");
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "tabForTest", tab42, t + 3000));
            c.check("42%".equals(shown(c, um, tab42)), "the tab's 42% made it jump: " + shown(c, um, tab42));
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "tabForTest", tab42, t + 12_000));
            c.check("42%".equals(shown(c, um, tab42)), "later it moved to " + shown(c, um, tab42));
            // An unknown block area takes his own area (Upper Mines here): counts.
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "breakForTest", "Mithril", null, "Upper Mines", tab42, t + 12_100));
            c.check("42.4%".equals(shown(c, um, tab42)), "an unknown-area block in his Upper Mines: " + shown(c, um, tab42));
            // Over-count: 10 more blocks the tab never shows; once they are older than the lag, it eases down a step a tick.
            for (int i = 0; i < 10; i++) {
                long ti = t + 12_200 + 50L * i;
                c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "breakForTest", "Mithril", "Upper Mines", "Upper Mines", tab42, ti));
            }
            double top = num(shown(c, um, tab42));
            c.check(top > 46 && top < 47, "11 blocks over 42% show " + top);
            double prev = top;
            for (int i = 0; i < 40; i++) {
                long ti = t + 20_000 + 50L * i;
                c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "tabForTest", tab42, ti));
                double v = num(shown(c, um, tab42));
                c.check(v <= prev + 1e-9 && prev - v <= 0.4 + 0.11, "eased from " + prev + " to " + v + " in one tick");
                c.check(v >= 42, "eased below the tab: " + v);
                prev = v;
            }
            c.check(prev <= 42.4 + 1e-9, "never eased back to the tab: " + prev);
            // Titanium Miner (15): one titanium block anywhere is 6.6%.
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "breakForTest", "Titanium", "Cliffside Veins", "Upper Mines", tab42, t + 30_000));
            c.check("6.6%".equals(shown(c, "Titanium Miner", tab42)), "Titanium Miner after 1 block: " + shown(c, "Titanium Miner", tab42));
            c.check("10%".equals(shown(c, "Goblin Slayer", tab42)), "Goblin Slayer moved: " + shown(c, "Goblin Slayer", tab42));
            // Never 100% before DONE.
            List<String> tab99 = List.of(um + ": 99.8%");
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "resetForTest"));
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "tabForTest", tab99, t + 40_000));
            for (int i = 0; i < 5; i++) {
                long ti = t + 40_100 + 50L * i;
                c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "breakForTest", "Mithril", "Upper Mines", "Upper Mines", tab99, ti));
            }
            c.check(num(shown(c, um, tab99)) < 100, "showed " + shown(c, um, tab99) + " before DONE");
            List<String> done = List.of(um + ": DONE");
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "tabForTest", done, t + 41_000));
            c.check("DONE".equals(shown(c, um, done)), "DONE shows " + shown(c, um, done));
            c.note("40% +5 -> " + five + ", tab 42% no jump, Cliffside Veins block ignored, over-count eased " + top + " -> "
                    + prev + ", Titanium Miner 6.6%, 99.8% + 5 stays under 100");
        } finally {
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "holdLiveForTest", false));
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "resetForTest"));
        }
    }

    // ---- 1103-mining-com-progress-live --------------------------------------------------------------------------

    private static String graph(BlockPos up, BlockPos cliff) {
        return String.format(Locale.ROOT, "{\"0\":{\"Position\":\"%d:%d:%d\",\"Name\":\"Upper Mines\",\"Tags\":[\"area\"],"
                        + "\"Neighbours\":{}},\"1\":{\"Position\":\"%d:%d:%d\",\"Name\":\"Cliffside Veins\",\"Tags\":[\"area\"],"
                        + "\"Neighbours\":{}}}", up.getX(), up.getY(), up.getZ(), cliff.getX(), cliff.getY(), cliff.getZ());
    }

    private static void setb(Session c, BlockPos p, String block) {
        c.server().command("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block);
    }

    /** The HUD line for {@code name}, codes stripped. Client thread (safe inside a waitUntil predicate). */
    @SuppressWarnings("unchecked")
    private static String hudLineOn(String name) {
        for (String l : (List<String>) Mod.staticCall(HUD, "lines")) {
            if (l.contains(name)) {
                return l.replaceAll("§.", "");
            }
        }
        return null;
    }

    private static String hudLine(Session c, String name) {
        return c.onClient(mc -> hudLineOn(name));
    }

    private static void aim(Session c, float yaw, BlockPos target) {
        c.ctx().runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(3f);
            mc.options.keyAttack.setDown(true);
        });
        c.waitUntil("aimed at " + target.toShortString(), mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h
                && h.getBlockPos().equals(target) && (Integer) Mod.staticCall(PROG, "targets") > 0, 40);
    }

    private static void release(Session c) {
        c.ctx().runOnClient(mc -> mc.options.keyAttack.setDown(false));
    }

    private static void comProgressLive(Session c) {
        String um = "Upper Mines Mithril";
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        BlockPos a = f.offset(2, 1, 0);
        BlockPos b = f.offset(-2, 1, 0);
        BlockPos other = f.offset(0, 1, 3);
        String mode = c.onClient(mc -> mc.gameMode.getPlayerMode().name().toLowerCase(Locale.ROOT));
        try (Settings set = new Settings(c).with(CFG, "CommissionHud", true).with(CFG, "CommissionInterpolate", true)
                .with(CFG, "CommissionColorProgress", false)) {
            // Adventure: holding attack sends nothing and breaks nothing; only the server's setblock changes the blocks.
            c.server().command("gamemode adventure @p");
            String json = graph(f.offset(5, 1, 0), f.offset(-5, 1, 0));
            c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "useGraphJsonForTest", json));
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "resetForTest"));
            setb(c, a, "minecraft:gray_wool");
            setb(c, b, "minecraft:gray_wool");
            setb(c, other, "minecraft:gray_wool");
            mines(c, "Royal Mines", um + ": 40%", "Goblin Slayer: 10%");
            c.waitUntil("the HUD reads 40%", mc -> "Upper Mines Mithril: 40%".equals(hudLineOn(um)), 60);
            String areaA = c.onClient(mc -> (String) Mod.staticCall(MAP, "areaAt", a.getX(), a.getY(), a.getZ()));
            String areaB = c.onClient(mc -> (String) Mod.staticCall(MAP, "areaAt", b.getX(), b.getY(), b.getZ()));
            c.check("Upper Mines".equals(areaA) && "Cliffside Veins".equals(areaB), "areas " + areaA + " / " + areaB);
            int before = c.onClient(mc -> (Integer) Mod.staticCall(PROG, "countedBreaks"));
            // A block he was not mining changes: nothing.
            setb(c, other, "minecraft:bedrock");
            c.ctx().waitTicks(10);
            c.check("Upper Mines Mithril: 40%".equals(hudLine(c, um)), "a block he was not mining counted: " + hudLine(c, um));
            // The Upper Mines block he is mining turns to bedrock (from the Royal Mines' sidebar): counts.
            aim(c, -90f, a);
            setb(c, a, "minecraft:bedrock");
            c.waitUntil("the HUD moved to 40.4%", mc -> "Upper Mines Mithril: 40.4%".equals(hudLineOn(um)), 20);
            release(c);
            // The Cliffside Veins block he is mining: not this commission's area.
            aim(c, 90f, b);
            setb(c, b, "minecraft:bedrock");
            c.ctx().waitTicks(10);
            release(c);
            c.check("Upper Mines Mithril: 40.4%".equals(hudLine(c, um)), "a Cliffside Veins block counted: " + hudLine(c, um));
            int after = c.onClient(mc -> (Integer) Mod.staticCall(PROG, "countedBreaks"));
            c.check(after == before + 1, "counted " + (after - before) + " breaks (want 1)");
            c.note("areas " + areaA + "/" + areaB + "; HUD " + hudLine(c, um) + "; the unmined and the Cliffside block did not count");
        } finally {
            release(c);
            c.ctx().runOnClient(mc -> Mod.staticCall(MAP, "useGraphJsonForTest", (Object) null));
            c.ctx().runOnClient(mc -> Mod.staticCall(PROG, "resetForTest"));
            setb(c, a, "minecraft:air");
            setb(c, b, "minecraft:air");
            setb(c, other, "minecraft:air");
            c.server().command("gamemode " + mode + " @p");
            hub(c);
        }
    }
}
