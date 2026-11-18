package dev.testkit.gametest.menu;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.UUID;

/**
 * hotmtree-1118 (killer560: "for perks show their actual tree"): the Profile Viewer's HOTM page draws the perks as the
 * /hotm menu's tree (7 columns, tier 10 on top). A fixture profile with one maxed perk (Mining Speed 50/50), one partial
 * (Mining Fortune 20/50), a selected ability (Pickobulus) and HOTM level 8 (600,000 xp); the cursor is put on a node at its
 * real slot and the hover text read back. A hover on an empty cell must show nothing, so the test cannot pass without the
 * tree acting.
 */
final class HotmTree1118Cases {
    private HotmTree1118Cases() {
    }

    static void register(Session s) {
        MenuSuite.test(s, "1118-menu-hotmtree", HotmTree1118Cases::tree);
    }

    private static final String PROFILE = "{\"success\":true,\"profiles\":[{\"profile_id\":\"p1\",\"cute_name\":\"Mango\",\"selected\":true,"
            + "\"members\":{\"000000000000000000000000000000a1\":{\"currencies\":{\"coin_purse\":1},"
            + "\"mining_core\":{\"powder_mithril\":500000,\"powder_spent_mithril\":100000},"
            + "\"skill_tree\":{\"experience\":{\"mining\":600000},\"selected_ability\":{\"mining\":\"pickaxe_toss\"},"
            + "\"nodes\":{\"mining\":{\"mining_speed\":50,\"mining_fortune\":20,\"pickaxe_toss\":1}}}}}}]}";

    /** Slots from the table: mining_speed 85 (tier 1, col 3), mining_fortune 76 (tier 2, col 3), pickaxe_toss 78 (tier 2, col 5),
     *  mining_master 4 (tier 10, col 3, not unlocked), and an empty cell (tier 1, col 0). */
    static void tree(Session c) throws Exception {
        try (SocialCases.Env env = new SocialCases.Env(c)) {
            BazaarV3Cases.setWindow(c, 1600, 900, 2);
            c.ctx().runOnClient(mc -> {
                Object cfg = Mod.cfg(PvStyle1104Cases.PVCFG);
                Mod.call(cfg, "setRememberLastPage", false);
                Mod.call(cfg, "setStyle", Mod.enumValue(PvStyle1104Cases.PVCFG + "$Style", "DEFAULT"));
            });
            PvStyle1104Cases.seedWith(c, PROFILE);
            Screen s = c.onClient(mc -> {
                try {
                    Object target = Mod.cls("profileviewer.ProfileViewerFeature$Target")
                            .getDeclaredConstructor(String.class, UUID.class).newInstance("PvStyleTest", PvStyle1104Cases.ID);
                    Screen pv = (Screen) Mod.cls("profileviewer.screen.ProfileViewerScreen")
                            .getDeclaredConstructor(Screen.class, target.getClass()).newInstance(null, target);
                    McCompat.setScreen(mc, pv);
                    return pv;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot open the PV: " + e, e);
                }
            });
            c.waitUntil("the PV shows the offline profile", mc -> "READY".equals(String.valueOf(Mod.field(s, "state"))), 100);
            c.ctx().runOnClient(mc -> Mod.call(s, "setPage", 5));
            c.ctx().waitTicks(5);
            BazaarReskinCases.shot(c, "hotm-tree");
            int[] g = c.onClient(mc -> new int[]{PvStyle1104Cases.i(s, "contentX"), PvStyle1104Cases.i(s, "contentY"),
                    PvStyle1104Cases.i(s, "contentW"), PvStyle1104Cases.i(s, "contentH")});
            // The same arithmetic as ExtraPages.drawHotm: nav 84 + 6, left column 160 + 12, 6 padding.
            int tx0 = g[0] + 84 + 6 + 6 + 160 + 12, top = g[1] + 5 + 11, w = g[0] + g[2] - 6 - tx0, h = g[1] + g[3] - 4 - top;
            int cell = Math.max(8, Math.min(w / 7, h / 10)), x0 = tx0 + (w - cell * 7) / 2;
            c.note("tree cell " + cell + " px at " + x0 + ", " + top);
            String maxed = hover(c, s, x0, top, cell, 3, 9);
            String part = hover(c, s, x0, top, cell, 3, 7);
            String ability = hover(c, s, x0, top, cell, 5, 7);
            String locked = hover(c, s, x0, top, cell, 3, 0);
            String empty = hover(c, s, x0, top, cell, 0, 9);
            c.note("maxed: " + maxed + " | partial: " + part + " | ability: " + ability + " | locked: " + locked);
            c.check(maxed.contains("Mining Speed") && maxed.contains("Level 50/50") && maxed.contains("+1000 Mining Speed")
                    && !maxed.contains("Next level"), "the maxed perk's hover is wrong: " + maxed);
            c.check(part.contains("Mining Fortune") && part.contains("Level 20/50") && part.contains("+40 Mining Fortune")
                    && part.contains("Next level: +42 Mining Fortune"), "the partial perk's hover is wrong: " + part);
            c.check(ability.contains("Pickobulus") && ability.contains("Selected ability") && ability.contains("Cooldown 60s"),
                    "the selected ability's hover is wrong: " + ability);
            c.check(locked.contains("Mining Master") && locked.contains("Locked") && locked.contains("Requires HOTM level 10"),
                    "the locked perk's hover is wrong: " + locked);
            c.check(empty.isEmpty(), "a cell with no perk showed a hover: " + empty);
        }
    }

    /** Puts the cursor on tree cell (col, row) and returns the hover text, or "" when there is none. */
    private static String hover(Session c, Screen s, int x0, int top, int cell, int col, int row) {
        BazaarReskinCases.cursorTo(c, new int[]{x0 + col * cell + 2, top + row * cell + 2, cell - 4, cell - 4});
        c.ctx().waitTicks(2);
        return c.onClient(mc -> {
            Object t = Mod.field(s, "pendingTooltip");
            StringBuilder sb = new StringBuilder();
            if (t != null) {
                for (Object line : (List<?>) t) {
                    sb.append(((net.minecraft.network.chat.Component) line).getString()).append(" / ");
                }
            }
            return sb.toString();
        });
    }
}
