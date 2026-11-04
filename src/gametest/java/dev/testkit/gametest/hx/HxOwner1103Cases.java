package dev.testkit.gametest.hx;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Owner requests of 2026-11-03 (late):
 * <ul>
 *   <li>1103-mask-proc-title: a mask's "saved your life" line shows a title when Proc Title is on, and none when off.</li>
 *   <li>1103-partycmd-self: your own "!pt name" in party chat runs nothing (it transferred his party away).</li>
 * </ul>
 */
final class HxOwner1103Cases {

    private static final String MASK_CFG = "maskinvincibility.MaskInvincibilityConfig";
    private static final String MASK = "maskinvincibility.MaskInvincibilityFeature";

    private HxOwner1103Cases() {
    }

    static void register(Session s) {
        s.test("1103-mask-proc-title", HxOwner1103Cases::maskTitle);
        s.test("1103-partycmd-self", HxOwner1103Cases::partySelf);
    }

    private static void maskTitle(Session c) {
        try (Settings set = new Settings(c).with(MASK_CFG, "Enabled", true).with(MASK_CFG, "AnnounceToParty", false)
                .with(MASK_CFG, "AutoSwapEnabled", false).with(MASK_CFG, "ProcTitle", true)) {
            int t0 = c.onClient(mc -> ((Number) Mod.staticCall(MASK, "procTitles")).intValue());
            c.hx().chat("§r§aSecond Wind Activated!§r§a Your Spirit Mask saved your life!");
            c.waitUntil("a proc title", mc -> ((Number) Mod.staticCall(MASK, "procTitles")).intValue() == t0 + 1, 40);
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(MASK_CFG), "setProcTitle", false));
            c.hx().chat("§r§aSecond Wind Activated!§r§a Your Spirit Mask saved your life!");
            c.ctx().waitTicks(10);
            int t1 = c.onClient(mc -> ((Number) Mod.staticCall(MASK, "procTitles")).intValue());
            c.check(t1 == t0 + 1, "a title with Proc Title OFF (" + (t1 - t0) + " titles)");
            c.hx().chat("§7[VIP] Bob§f: Second Wind Activated! Your Spirit Mask saved your life!");
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg(MASK_CFG), "setProcTitle", true));
            c.ctx().waitTicks(10);
            int t2 = c.onClient(mc -> ((Number) Mod.staticCall(MASK, "procTitles")).intValue());
            c.check(t2 == t1, "a player's chat line showed a title");
        }
    }

    private static void partySelf(Session c) {
        try (Settings set = new Settings(c).with("partycommands.PartyCommandsConfig", "Enabled", true)) {
            String self = c.onClient(mc -> mc.player.getGameProfile().name());
            long mark = LogTap.mark();
            c.hx().chat("§9Party §8> §b[MVP§c+§b] " + self + "§f: !pt HxMateA");
            c.waitUntil("the mod saw your own command", mc -> LogTap.since(mark).stream()
                    .anyMatch(l -> l.contains("Ignored your own \"!pt\"")), 40);
            c.ctx().waitTicks(10);
            boolean ran = LogTap.since(mark).stream().anyMatch(l -> l.contains("transferred the party"));
            c.check(!ran, "your own !pt ran a transfer");
            c.note("own !pt ignored for " + self);
        }
    }
}
