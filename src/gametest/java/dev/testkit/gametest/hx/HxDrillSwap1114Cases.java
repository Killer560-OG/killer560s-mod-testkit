package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;

import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * drillswap-1114: Swap To Drill On Pigeon ranks the hotbar drills by the powder bonus of the commissions being claimed
 * (Powder Bonus) or by Compact level. Cheat jar only. Every case gives real drills with the lore of a Rare drill
 * (a "Grants +40% Mithril Powder." line, then a conditional "Stellar Bonus" paragraph), uses the Royal Pigeon with his
 * right click and checks on the wire (the server's held events) and on the client that the slot changed to the expected
 * drill before any menu opened.
 * <ul>
 *   <li>1114-drillswap-rank: Powder Bonus picks the +40% drill over a higher Compact drill with +10%, Compact Level picks
 *       the other; a drill with only a Stellar Bonus +60% never wins.</li>
 *   <li>1114-drillswap-module: an installed Upgrade Module's powder bonus is added (10 + 35 beats 40).</li>
 *   <li>1114-drillswap-types: Gemstone / Glacite / Mithril commissions done (live tab) pick that powder's drill.</li>
 *   <li>1114-drillswap-mixed: mixed done commissions go to the majority type; a tie to the area's powder.</li>
 *   <li>1114-drillswap-area: nothing done: the area decides (Crystal Hollows Gemstone, Glacite Tunnels Glacite, mines Mithril).</li>
 *   <li>1114-drillswap-parse: the parsers on their own (lore lines, commission names).</li>
 * </ul>
 */
final class HxDrillSwap1114Cases {

    private static final String QOL = "miningqol.MiningQolConfig";
    private static final String SWAP = "miningqol.PigeonDrillSwap";
    private static final String STATE = "miningqol.MiningState";
    private static final String PIGEON = "minecraft:feather[custom_data={id:\"ROYAL_PIGEON\"},item_name=\"Royal Pigeon\"]";
    private static final int PIGEON_SLOT = 6;

    private HxDrillSwap1114Cases() {
    }

    static void register(Session s) {
        s.test("1114-drillswap-rank", HxDrillSwap1114Cases::rank);
        s.test("1114-drillswap-module", HxDrillSwap1114Cases::module);
        s.test("1114-drillswap-types", HxDrillSwap1114Cases::types);
        s.test("1114-drillswap-mixed", HxDrillSwap1114Cases::mixed);
        s.test("1114-drillswap-area", HxDrillSwap1114Cases::area);
        s.test("1114-drillswap-parse", HxDrillSwap1114Cases::parse);
    }

    // ---- items ------------------------------------------------------------------------------------------------

    private static String lore(String... lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            b.append(b.length() == 0 ? "" : ",").append("'{\"text\":\"").append(l).append("\",\"italic\":false}'");
        }
        return b.toString();
    }

    private static String drill(String id, String name, String... lore) {
        return "minecraft:prismarine_shard[custom_data={id:\"" + id + "\"},item_name=\"" + name + "\",lore=[" + lore(lore) + "]]";
    }

    /** The Rare drill of the screenshot (lore order as shown), with the given enchant, bonus and module lines. */
    private static String rare(String id, String name, String enchants, String grants, String module, String moduleText) {
        List<String> l = new ArrayList<>(List.of(enchants, grants, "",
                "Fuel Tank: Not Installed", "Embed a Fuel Tank to hold more fuel.",
                "Drill Engine: Not Installed", "Embed an Engine to mine faster.",
                module, moduleText, "",
                "Fuel: 2,104/3k", "", "Ability: Pickobulus RIGHT CLICK", "Creates an explosion.", "",
                "Stellar Bonus", "Earn +20% Mithril Powder and +75 Mining Fortune when mining near Fallen Stars.", "", "RARE DRILL"));
        return drill(id, name, l.toArray(new String[0]));
    }

    private static final String NO_MODULE = "Upgrade Module: Not Installed";
    private static final String NO_MODULE_TEXT = "Embed a module to boost the drill.";

    private static void give(Session c, String... stacks) {
        for (int i = 0; i < 6; i++) {
            c.hx().give(i, i < stacks.length ? stacks[i] : "minecraft:air");
        }
        c.hx().give(PIGEON_SLOT, PIGEON);
    }

    private static void clear(Session c) {
        for (int i = 0; i <= PIGEON_SLOT; i++) {
            c.hx().give(i, "minecraft:air");
        }
    }

    private static int swaps(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(SWAP, "swaps"));
    }

    /** One pigeon use; asserts the held slot became {@code expect} (client and the server's held events). */
    private static void claim(Session c, int expect, String what) {
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(PIGEON_SLOT);
            mc.player.setXRot(0f);
            Mod.staticCall(SWAP, "resetForTest");
            return null;
        });
        c.ctx().waitTicks(4);
        long mark = c.hx().head();
        int before = swaps(c);
        c.onClient(mc -> {
            net.minecraft.client.KeyMapping.click(mc.options.keyUse.getDefaultKey());
            return null;
        });
        c.waitUntil(what + ": the swap", mc -> (Integer) Mod.staticCall(SWAP, "swaps") > before, 40);
        c.ctx().waitTicks(4);
        int held = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
        boolean wire = c.hx().events(mark, "held").stream().anyMatch(e -> e.get("slot").getAsInt() == expect);
        c.note(what + ": held slot " + held + " (want " + expect + "), server saw it: " + wire);
        c.check(held == expect, what + ": held slot " + held + ", wanted " + expect);
        c.check(wire, what + ": the server never saw slot " + expect);
    }

    private static Settings settings(Session c, String rank, String mode) {
        return new Settings(c).with(QOL, "PigeonDrill", true)
                .with(QOL, "PigeonDrillMode", Mod.enumValue(QOL + "$PigeonDrillMode", mode))
                .with(QOL, "PigeonDrillRank", Mod.enumValue(QOL + "$PigeonDrillRank", rank));
    }

    /** The mines (or the Hollows) tab with these commission lines; waits until the mod read them. */
    private static void tab(Session c, String island, String sidebarArea, String... coms) {
        c.hx().sidebar("SKYBLOCK", "Late Winter 3rd", "⏣ " + sidebarArea, "Purse: 0");
        List<String> tab = new ArrayList<>(List.of("Area: " + island, "Profile: Test", "", "Commissions:"));
        for (String com : coms) {
            tab.add(" " + com);
        }
        tab.add("");
        c.hx().tab(tab, null, null);
        String want = island.equals("Crystal Hollows") ? "CRYSTAL_HOLLOWS" : sidebarArea.contains("Glacite") ? "GLACITE_TUNNELS" : "DWARVEN_MINES";
        c.waitUntil("island " + want, mc -> want.equals(Mod.staticCall(STATE, "island")), 100);
        c.waitUntil(coms.length + " commission(s) read", mc -> {
            List<?> read = (List<?>) Mod.staticCall(STATE, "commissions");
            String all = String.valueOf(read);
            return read.size() == coms.length && List.of(coms).stream().allMatch(
                    com -> all.contains("name=" + com.substring(0, com.indexOf(':')) + ",") && all.contains(com.endsWith("DONE") ? "done=true" : "done=false"));
        }, 100);
    }

    // ---- cases ------------------------------------------------------------------------------------------------

    private static boolean legit(Session c) {
        if (Mod.isCheat()) return false;
        c.note("legit jar: Swap To Drill On Pigeon is cheat-only");
        return true;
    }

    private static void rank(Session c) {
        if (legit(c)) return;
        try {
            tab(c, "Dwarven Mines", "Royal Mines", "Royal Mines Mithril: DONE");
            // slot 0: the screenshot's Rare drill, +40% Mithril, Compact II. slot 1: higher tier, Compact X, +10%.
            // slot 2: Compact V and no Grants line (only the conditional Stellar Bonus line every drill here carries).
            give(c,
                    rare("MITHRIL_DRILL_1", "Mithril Drill SX-R226", "Efficiency VIII, Compact II", "Grants +40% Mithril Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("TITANIUM_DRILL_4", "Titanium Drill DR-X555", "Efficiency X, Compact X", "Grants +10% Mithril Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("TITANIUM_DRILL_3", "Titanium Drill DR-X455", "Efficiency X, Compact V", "Increases your mining speed.", NO_MODULE, NO_MODULE_TEXT));
            try (Settings set = settings(c, "POWDER", "AUTO")) {
                claim(c, 0, "Powder Bonus, +40% over Compact X +10%, Stellar +20% ignored");
            }
            try (Settings set = settings(c, "COMPACT", "AUTO")) {
                claim(c, 1, "Compact Level picks the Compact X drill");
            }
        } finally {
            clear(c);
            c.hx().tabClear();
            c.hx().sidebarClear();
        }
    }

    private static void module(Session c) {
        if (legit(c)) return;
        try {
            tab(c, "Dwarven Mines", "Royal Mines", "Royal Mines Mithril: DONE");
            give(c,
                    rare("MITHRIL_DRILL_1", "Mithril Drill SX-R226", "Efficiency VIII, Compact II", "Grants +40% Mithril Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("MITHRIL_DRILL_2", "Mithril Drill SX-R326", "Efficiency VIII, Compact III", "Grants +10% Mithril Powder.",
                            "Upgrade Module: Spicy Goblin Omelette", "+35% Mithril, Gemstone and Glacite Powder gain."));
            try (Settings set = settings(c, "POWDER", "AUTO")) {
                claim(c, 1, "10 + a 35 module beats 40");
            }
            // The module on a Gemstone claim: its generic powder line counts there too (10 Mithril does not).
            give(c,
                    rare("RUBY_DRILL_1", "Ruby Drill TX-15", "Efficiency V, Compact I", "Grants +30% Gemstone Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("MITHRIL_DRILL_2", "Mithril Drill SX-R326", "Efficiency VIII, Compact III", "Grants +10% Mithril Powder.",
                            "Upgrade Module: Spicy Goblin Omelette", "+35% Mithril, Gemstone and Glacite Powder gain."));
            try (Settings set = settings(c, "POWDER", "GEMSTONE")) {
                claim(c, 1, "gemstone claim: 35 module beats 30");
            }
        } finally {
            clear(c);
            c.hx().tabClear();
            c.hx().sidebarClear();
        }
    }

    private static void types(Session c) {
        if (legit(c)) return;
        try {
            give(c,
                    rare("MITHRIL_DRILL_1", "Mithril Drill SX-R226", "Efficiency VIII, Compact II", "Grants +40% Mithril Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("RUBY_DRILL_1", "Ruby Drill TX-15", "Efficiency V, Compact I", "Grants +30% Gemstone Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("GLACITE_DRILL_1", "Glacite Drill", "Efficiency V, Compact I", "Grants +25% Glacite Powder.", NO_MODULE, NO_MODULE_TEXT));
            try (Settings set = settings(c, "POWDER", "AUTO")) {
                tab(c, "Dwarven Mines", "Royal Mines", "Royal Mines Mithril: DONE");
                claim(c, 0, "mithril commission done");
                tab(c, "Dwarven Mines", "Royal Mines", "Amethyst Gemstone Collector: DONE");
                claim(c, 1, "gemstone commission done (in the Mines)");
                tab(c, "Dwarven Mines", "Glacite Tunnels", "Glacite Walker Slayer: DONE");
                claim(c, 2, "glacite commission done");
            }
            try (Settings set = settings(c, "POWDER", "GLACITE")) {
                tab(c, "Dwarven Mines", "Royal Mines", "Royal Mines Mithril: DONE");
                claim(c, 2, "Drill Choice Glacite Powder is fixed");
            }
        } finally {
            clear(c);
            c.hx().tabClear();
            c.hx().sidebarClear();
        }
    }

    private static void mixed(Session c) {
        if (legit(c)) return;
        try {
            give(c,
                    rare("MITHRIL_DRILL_1", "Mithril Drill SX-R226", "Efficiency VIII, Compact II", "Grants +40% Mithril Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("RUBY_DRILL_1", "Ruby Drill TX-15", "Efficiency V, Compact I", "Grants +30% Gemstone Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("GLACITE_DRILL_1", "Glacite Drill", "Efficiency V, Compact I", "Grants +25% Glacite Powder.", NO_MODULE, NO_MODULE_TEXT));
            try (Settings set = settings(c, "POWDER", "AUTO")) {
                // 2 glacite + 1 mithril done, the area is the Mines: glacite has the most.
                tab(c, "Dwarven Mines", "Royal Mines", "Glacite Walker Slayer: DONE", "Glacite Mineshaft Miner: DONE",
                        "Royal Mines Mithril: DONE", "Goblin Slayer: 40%");
                claim(c, 2, "two glacite and one mithril done");
                // One glacite, one mithril: a tie goes to the area's powder (Royal Mines = mithril).
                tab(c, "Dwarven Mines", "Royal Mines", "Glacite Walker Slayer: DONE", "Royal Mines Mithril: DONE");
                claim(c, 0, "tie goes to the area's powder");
            }
        } finally {
            clear(c);
            c.hx().tabClear();
            c.hx().sidebarClear();
        }
    }

    private static void area(Session c) {
        if (legit(c)) return;
        try {
            give(c,
                    rare("MITHRIL_DRILL_1", "Mithril Drill SX-R226", "Efficiency VIII, Compact II", "Grants +40% Mithril Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("RUBY_DRILL_1", "Ruby Drill TX-15", "Efficiency V, Compact I", "Grants +30% Gemstone Powder.", NO_MODULE, NO_MODULE_TEXT),
                    rare("GLACITE_DRILL_1", "Glacite Drill", "Efficiency V, Compact I", "Grants +25% Glacite Powder.", NO_MODULE, NO_MODULE_TEXT));
            try (Settings set = settings(c, "POWDER", "AUTO")) {
                tab(c, "Crystal Hollows", "Crystal Nucleus", "Goblin Slayer: 40%");
                claim(c, 1, "Crystal Hollows, nothing done");
                tab(c, "Dwarven Mines", "Glacite Tunnels", "Goblin Slayer: 40%");
                claim(c, 2, "Glacite Tunnels, nothing done");
                tab(c, "Dwarven Mines", "Lava Springs", "Goblin Slayer: 40%");
                claim(c, 0, "Dwarven Mines, nothing done");
            }
        } finally {
            clear(c);
            c.hx().tabClear();
            c.hx().sidebarClear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void parse(Session c) {
        if (legit(c)) return;
        String[] screenshot = {"Efficiency VIII, Compact II", "Grants +40% Mithril Powder.", "", "Upgrade Module: Not Installed",
                "Embed a module to boost the drill.", "", "Stellar Bonus",
                "Earn +20% Mithril Powder and +75 Mining Fortune when mining near Fallen Stars.", "", "RARE DRILL"};
        long mithril = (Long) Mod.staticCall(SWAP, "bonusTenths", List.of(screenshot), "mithril");
        c.check(mithril == 400, "screenshot lore: Mithril bonus " + mithril + " tenths, wanted 400 (Stellar +20% must not count)");
        long gem = (Long) Mod.staticCall(SWAP, "bonusTenths", List.of(screenshot), "gemstone");
        c.check(gem == 0, "screenshot lore: Gemstone bonus " + gem + ", wanted 0");
        // A Stellar paragraph whose lines wrap the other way and a Grants line of another powder.
        String[] odd = {"Grants +15.5% Glacite Powder.", "Stellar Bonus", "Grants +90% Glacite Powder.", "", "Grants +5% Mithril Powder."};
        long glacite = (Long) Mod.staticCall(SWAP, "bonusTenths", List.of(odd), "glacite");
        c.check(glacite == 155, "Glacite bonus " + glacite + ", wanted 155 (header section skipped)");
        List<?> done = (List<?>) Mod.staticCall(STATE, "parse", List.of("Glacite Walker Slayer: DONE", "Royal Mines Mithril: 50%"));
        c.check("glacite".equals(Mod.staticCall(SWAP, "claimPowder", done, "mithril")), "a done Glacite commission reads glacite");
        c.check("mithril".equals(Mod.staticCall(SWAP, "claimPowder", List.of(), "mithril")), "no commissions: the area's powder");
        c.note("parse: mithril " + mithril + ", gemstone " + gem + ", glacite " + glacite);
    }
}
