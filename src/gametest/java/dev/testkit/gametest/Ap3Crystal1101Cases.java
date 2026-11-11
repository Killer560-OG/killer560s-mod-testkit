package dev.testkit.gametest;

import dev.testkit.gametest.hx.Session;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.testkit.gametest.Ap3RuntimeTests.EXEC;
import static dev.testkit.gametest.Ap3RuntimeTests.FLOOR;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;
import static dev.testkit.gametest.Ap3RuntimeTests.set;

/**
 * mod doc-routes-1101, the AP3 Crystal node (to-do Doc 2026-11-01: "add a crystal node. It should work for getting crystals
 * with spring boots. Itll hold crouch once it hits that node till it has enough height to get the crystal then release
 * crouch"). Vanilla has no Spring Boots, so the case plays Hypixel's charge notes itself: while he crouches the server sends
 * {@code block.note_block.pling} at the pitches the mod's Spring Boots counter reads (two lows, then highs), every 3 ticks.
 * A RUN carries him into the Crystal node; it must crouch from its first tick, keep crouching while the counted height is
 * short, and let go on the tick the count reaches the height - never before.
 * <ul>
 *   <li>1101-ap3-crystal-height: the node's own height, 11.5 blocks (the fourth note: 3, 6.5, 9, 11.5).</li>
 *   <li>1101-ap3-crystal-auto: no height; an end crystal 10 blocks over the node needs 9 blocks (the third note).</li>
 * </ul>
 */
final class Ap3Crystal1101Cases {

    private Ap3Crystal1101Cases() {
    }

    private static final String SPRING = "com.killer560.hub.dungeonalerts.SpringBootsOverlay";
    private static final String[] PITCHES = {"0.6984127", "0.6984127", "0.82539684", "0.8888889", "0.93650794",
            "1.0476191", "1.1746032"};

    static void register(Session s) {
        if (!Ap3Crystal1109Cases.cheatBuild()) { // AP3 does not run on a legit jar
            Scenario.skipped("1101-ap3-crystal-height", "AP3 is cheat-only (legit jar)");
            Scenario.skipped("1101-ap3-crystal-auto", "AP3 is cheat-only (legit jar)");
            return;
        }
        s.test("1101-ap3-crystal-height", c -> run(c, 11.5, 4, false));
        s.test("1101-ap3-crystal-auto", c -> run(c, 0, 3, true));
    }

    private static void run(Session c, double height, int notesNeeded, boolean crystalAbove) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 20.5; // far enough down the lane that a summoned crystal is there first
        boolean gate = onClient(ctx, mc -> (Boolean) ModUnderTest.staticCall("com.killer560.hub.util.SkyblockGate",
                "isEnabled"));
        onClient(ctx, mc -> ModUnderTest.staticCall("com.killer560.hub.util.SkyblockGate", "setEnabled",
                new Class<?>[]{boolean.class}, new Object[]{false}));
        c.server().command("item replace entity @p armor.feet with minecraft:leather_boots[custom_data={id:\"SPRING_BOOTS\"}]");
        Object run = Ap3RuntimeTests.runNode(z);
        Object crystal = node("CRYSTAL", ax, z, -90f, 0f, 3.0, 3.0);
        set(crystal, "crystalHeight", height);
        int releases0 = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(EXEC, "crystalReleases"));
        int notes0 = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(SPRING, "notesCounted"));
        long mark = LogTap.mark();
        try {
            Ap3RuntimeTests.walkIntoRun(c, z, run, crystal);
            if (crystalAbove) {
                c.server().command(String.format(Locale.ROOT, "summon minecraft:end_crystal %.1f %d %.1f {ShowBottom:0b}",
                        ax, FLOOR + 1 + 10, z));
                c.server().sync(5);
            }
            c.waitUntil("AP3's Crystal node to crouch", mc -> mc.player.isCrouching(), 120);
            List<String> rows = new ArrayList<>();
            int sent = 0;
            int crouchTicks = 0;
            int releasedTick = -1;
            int noteTickAtNeed = -1;
            int sinceNote = 0;
            int reachedTick = -1;
            float want = crystalAbove ? 9.0f : 11.5f;
            for (int t = 0; t < 120 && releasedTick < 0; t++) {
                boolean crouching = onClient(ctx, mc -> mc.player.isCrouching());
                if (crouching) {
                    crouchTicks++;
                    if (++sinceNote >= 3 && sent < PITCHES.length) {
                        c.server().command("execute at @p run playsound minecraft:block.note_block.pling player @p ~ ~ ~ 1 " + PITCHES[sent]);
                        sent++;
                        sinceNote = 0;
                        if (sent == notesNeeded) {
                            noteTickAtNeed = t;
                        }
                    }
                } else if (crouchTicks > 0) {
                    releasedTick = t;
                }
                float h = onClient(ctx, mc -> (Float) ModUnderTest.staticCall(SPRING, "chargedHeight"));
                if (reachedTick < 0 && h >= want) {
                    reachedTick = t;
                }
                if (rows.size() < 40) {
                    rows.add(String.format(Locale.ROOT, "t%d crouch %s notes sent %d height %.1f", t, crouching, sent, h));
                }
                ctx.waitTick();
            }
            for (String l : LogTap.since(mark)) {
                if (l.contains("crystal #")) {
                    c.note("  " + l);
                }
            }
            rows.forEach(r -> c.note("  " + r));
            int releases = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(EXEC, "crystalReleases")) - releases0;
            float at = onClient(ctx, mc -> (Float) ModUnderTest.staticCall(EXEC, "crystalReleasedAt"));
            int notes = onClient(ctx, mc -> (Integer) ModUnderTest.staticCall(SPRING, "notesCounted")) - notes0;
            c.note(String.format(Locale.ROOT, "crouched %d tick(s), %d note(s) sent (%d counted), released at tick %d (the"
                            + " needed note sent at %d, counted at %d), released at %.1f blocks, releases %d", crouchTicks, sent, notes,
                    releasedTick, noteTickAtNeed, reachedTick, at, releases));
            c.check(crouchTicks > 0, "the Crystal node never crouched");
            // The test keeps sending a note every 3 ticks while it still sees him crouched, so one more may go out while the
            // release is on its way; what counts is how many the mod had COUNTED when it let go.
            c.check(notes == notesNeeded, "it let go after " + notes + " counted notes, " + notesNeeded + " were needed");
            c.check(releases == 1 && releasedTick >= 0, "the crouch was not let go once (" + releases + ")");
            c.check(reachedTick >= 0 && releasedTick > reachedTick && releasedTick - reachedTick <= 4,
                    "the crouch did not end right after the height was reached (" + reachedTick + " -> " + releasedTick + ")");
            c.check(at == want, "released at " + at + " blocks, wanted " + want);
        } finally {
            onClient(ctx, mc -> {
                Ap3RuntimeTests.staticCallStop();
                ModUnderTest.staticCall("com.killer560.hub.util.SkyblockGate", "setEnabled",
                        new Class<?>[]{boolean.class}, new Object[]{gate});
                return null;
            });
            c.server().command("item replace entity @p armor.feet with minecraft:air");
            c.server().command("kill @e[type=minecraft:end_crystal]");
        }
    }
}
