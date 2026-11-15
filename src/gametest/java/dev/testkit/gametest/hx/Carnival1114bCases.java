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
 * </ul>
 */
final class Carnival1114bCases {

    private Carnival1114bCases() {
    }

    static void register(Session s) {
        s.test("3823-carnival-fish-legacy-hook", Carnival1114bCases::fishLegacy);
        s.test("3824-carnival-dig-walk-in-reach", Carnival1114bCases::digWalkInReach);
        s.test("3825-carnival-shovel-lost-tap", Carnival1114bCases::shovelLostTap);
    }

    // ---- 3823 -----------------------------------------------------------------------------------------------------

    static void fishLegacy(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Catch a Fish is cheat-only");
            return;
        }
        int z1 = CZ + 4;
        int z2 = CZ + 8;
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.CatchAFishConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoCatchAFishConfig", "getInstance");
            c.onClient(mc -> Mod.staticCall("carnival.Lead", "clearForTest"));
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", CX - 7, Y - 1, z1, CX + 7, Y - 1, z2));
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", CX - 7, Y, z1, CX + 7, Y, z2));
            c.server().command("tag @a add legacyhook");
            c.hx().give(0, "minecraft:fishing_rod[custom_name='Carnival Rod']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ - 2, 0f, 0f);
            CarnivalSuite.sidebar(c);
            c.hx().chat("§e[NPC] Carnival Fisherman§f: Good luck, friend!");
            c.waitUntil("Catch a Fish starts", mc -> "CATCH_A_FISH".equals(String.valueOf(
                    Mod.staticCall("carnival.CarnivalState", "game"))), 60);
            double fz1 = CZ + 5.5;
            double fz2 = CZ + 7.0;
            String a = c.hx().stand(PX - 3, Y, fz1, null, "helmet", CarnivalSuite.head(CarnivalSuite.YELLOW), "marker", false, "invisible", false);
            String b = c.hx().stand(PX + 3, Y, fz2, null, "helmet", CarnivalSuite.head(CarnivalSuite.OTHER), "marker", false, "invisible", false);
            c.waitUntil("two fish seen", mc -> (Integer) Mod.staticCall("carnival.CatchAFish", "fishCount") == 2, 60);
            int casts0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "casts"));
            int reels0 = c.onClient(mc -> (Integer) Mod.staticCall("carnival.AutoCatchAFish", "reels"));
            List<long[]> rows = new ArrayList<>();
            double phase = 0;
            for (int i = 0; i < 700; i++) {
                if (i % 2 == 0) {
                    // Both fish swim 0.1 a tick, back and forth across 6 blocks, opposite ways.
                    phase += 0.2;
                    double off = 3 - Math.abs((phase % 12) - 6);
                    c.server().command(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", a, PX + off, Y, fz1));
                    c.server().command(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", b, PX - off, Y, fz2));
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
            c.check(casts <= reels + 3, casts + " casts for " + reels + " catches: the casts miss the fish");
            c.check(strayLeft == 0, strayLeft + " stray cast(s) still out 15 ticks after a reel");
            c.check(Math.abs(cal[4]) < 1.0, "the learned cast range error is " + cal[4] + " on a server that runs the same physics");
        } finally {
            c.server().command("tag @a remove legacyhook");
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone", CX - 7, Y, z1, CX + 7, Y, z2));
            c.server().command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", CX - 7, Y - 1, z1, CX + 7, Y - 1, z2));
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
            c.check(startFlat > 2.5, "the start was already next to the pick (" + startFlat + "); the case proved nothing");
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
