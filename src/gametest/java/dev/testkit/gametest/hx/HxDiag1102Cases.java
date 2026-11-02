package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Owner-evidence log lines (mod branch diag-1101, package P5 of doc-audit-1101): INFO lines, since DEBUG never reaches his
 * log, each written once per spell and changing nothing.
 * <ul>
 *   <li>1102-diag-leap-stuck: a "Spirit Leap" menu with no heads in it for over a second writes ONE "[FastLeap] stuck for"
 *       line naming it (not two after three seconds), the menu stays open (the line changes nothing) and no leap starts;
 *       closing it writes one "stuck state ended" line; a Spirit Leap menu WITH a head writes nothing.</li>
 *   <li>1102-diag-raffle: in the Dwarven Mines with the Raffle highlight on, a dropped Name Tag (a ticket) starts a Raffle:
 *       ONE "[Raffle] Raffle start" dump listing a named armor stand with its helmet and the Name Tag item (not a second
 *       dump three seconds later), and the highlight still boxes the ticket as before.</li>
 * </ul>
 */
final class HxDiag1102Cases {

    private static final String LEAP = "fastleap.LeapManager";
    private static final String STUCK = "fastleap.FastLeapStuckLog";
    private static final String RAFFLE = "miningqol.RaffleLog";
    private static final String MCFG = "miningqol.MiningQolConfig";
    private static final String HIGHLIGHTS = "miningqol.MiningHighlights";

    private HxDiag1102Cases() {
    }

    static void register(Session s) {
        s.test("1102-diag-leap-stuck", HxDiag1102Cases::leapStuck);
        s.test("1102-diag-raffle", HxDiag1102Cases::raffle);
    }

    private static boolean leapMenuOpen(Session c) {
        return c.onClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && s.getTitle().getString().contains("Spirit Leap"));
    }

    private static void closeScreen(Session c) {
        c.ctx().runOnClient(mc -> {
            if (McCompat.screen(mc) != null) {
                McCompat.screen(mc).onClose();
            }
        });
        c.waitUntil("the screen closes", mc -> McCompat.screen(mc) == null, 40);
    }

    // ---- 1102-diag-leap-stuck ---------------------------------------------------------------------------------------

    private static void leapStuck(Session c) {
        c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(LEAP, "isBusy")), "premise: a leap is already busy");
        // Control first: a leap menu with a head in it is not stuck.
        long m0 = dev.testkit.gametest.LogTap.mark();
        c.hx().menu("Spirit Leap", 3, Map.of(11, "minecraft:player_head"), null);
        c.waitUntil("the Spirit Leap menu (with a head) opens", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && s.getTitle().getString().contains("Spirit Leap") && !s.getMenu().slots.get(11).getItem().isEmpty(), 60);
        c.ctx().waitTicks(40);
        List<String> control = logLines(m0, "[FastLeap] stuck for");
        c.check(control.isEmpty(), "a leap menu with a head logged as stuck: " + control);
        closeScreen(c);

        int linesBefore = c.onClient(mc -> ((Number) Mod.staticCall(STUCK, "lines")).intValue());
        long mark = dev.testkit.gametest.LogTap.mark();
        c.hx().menu("Spirit Leap", 3, Map.of(), null);
        c.waitUntil("the empty Spirit Leap menu opens", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && s.getTitle().getString().contains("Spirit Leap"), 60);
        c.waitUntil("the stuck line after a second", mc -> !logLines(mark, "[FastLeap] stuck for").isEmpty(), 60);
        c.ctx().waitTicks(60);
        List<String> stuck = logLines(mark, "[FastLeap] stuck for");
        c.note("stuck line: " + (stuck.isEmpty() ? "none" : stuck.get(0)));
        c.check(stuck.size() == 1, "want exactly 1 stuck line after 3 s of an empty leap menu, got " + stuck.size());
        c.check(stuck.get(0).contains("leap menu with no heads") && stuck.get(0).contains("heads=0")
                && stuck.get(0).contains("busy=false"), "the stuck line does not describe the menu: " + stuck.get(0));
        c.check(leapMenuOpen(c), "the empty leap menu was closed (the log must change nothing)");
        c.check(!c.onClient(mc -> (Boolean) Mod.staticCall(LEAP, "isBusy")), "a leap started");
        closeScreen(c);
        c.waitUntil("the ended line", mc -> !logLines(mark, "[FastLeap] stuck state ended").isEmpty(), 20);
        c.ctx().waitTicks(20);
        int ended = logLines(mark, "[FastLeap] stuck state ended").size();
        int linesAfter = c.onClient(mc -> ((Number) Mod.staticCall(STUCK, "lines")).intValue());
        c.check(ended == 1, "want 1 ended line, got " + ended);
        c.check(linesAfter - linesBefore == 2, "the log counted " + (linesAfter - linesBefore) + " line(s), want 2");
        c.note("1 stuck line in 3 s, 1 ended line; a menu with a head logged nothing");
    }

    // ---- 1102-diag-raffle -------------------------------------------------------------------------------------------

    private static void raffle(Session c) {
        BlockPos f = c.onClient(mc -> mc.player.blockPosition());
        try (Settings set = new Settings(c).with(MCFG, "Raffle", true).with(MCFG, "RaffleEsp", true)) {
            c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ Divan's Gateway", "Purse: 0");
            c.hx().tab(List.of("Area: Dwarven Mines", "Profile: Test", ""), null, null);
            c.waitUntil("Dwarven Mines", mc -> "DWARVEN_MINES".equals(Mod.staticCall("miningqol.MiningState", "island")), 100);
            run(c, () -> Mod.staticCall(RAFFLE, "testReset"));
            c.ctx().waitTicks(10);
            int dumps0 = c.onClient(mc -> ((Number) Mod.staticCall(RAFFLE, "dumps")).intValue());
            long mark = dev.testkit.gametest.LogTap.mark();
            c.server().command("summon armor_stand " + (f.getX() + 3.5) + " " + f.getY() + " " + (f.getZ() + 0.5)
                    + " {CustomName:\"Raffle Clerk\",CustomNameVisible:1b,NoGravity:1b,"
                    + "equipment:{head:{id:\"minecraft:golden_helmet\",count:1}}}");
            c.ctx().waitTicks(10);
            c.check(logLines(mark, "[Raffle] Raffle start").isEmpty(), "a Raffle started with no ticket and no event line");
            c.server().command("summon item " + (f.getX() + 2.5) + " " + f.getY() + " " + (f.getZ() + 0.5)
                    + " {Item:{id:\"minecraft:name_tag\",count:1},PickupDelay:32767,NoGravity:1b}");
            c.waitUntil("the Raffle start dump", mc -> !logLines(mark, "[Raffle] Raffle start").isEmpty(), 60);
            c.ctx().waitTicks(60);
            List<String> starts = logLines(mark, "[Raffle] Raffle start");
            List<String> all = logLines(mark, "[Raffle]");
            c.note("dump: " + all);
            c.check(starts.size() == 1, "want exactly 1 Raffle start dump in 3 s, got " + starts.size());
            c.check(all.stream().anyMatch(l -> l.contains("armor_stand") && l.contains("Raffle Clerk")
                    && l.contains("head=") && l.contains("golden_helmet")), "the armor stand (name, helmet) is not listed");
            c.check(all.stream().anyMatch(l -> l.contains("minecraft:item") && l.contains("name_tag") && l.contains("ticket=true")),
                    "the dropped Name Tag is not listed as a ticket");
            int dumps1 = c.onClient(mc -> ((Number) Mod.staticCall(RAFFLE, "dumps")).intValue());
            c.check(dumps1 - dumps0 == 1, "dumps counted " + (dumps1 - dumps0) + ", want 1");
            // Unchanged: the highlight still boxes the one ticket.
            c.check(c.onClient(mc -> ((Number) Mod.staticCall(HIGHLIGHTS, "ticketCount")).intValue()) == 1,
                    "the highlight no longer finds the ticket");
        } finally {
            c.server().command("kill @e[type=item]");
            c.server().command("kill @e[type=armor_stand,name=\"Raffle Clerk\"]");
            hub(c);
        }
    }
}
