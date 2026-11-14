package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * 3050-3059: staging-mining-3's Mining features driven through what Hypixel would send (mod branch staging-mining-3,
 * pulled to main by mod chport-1104 on 2026-11-04; Commission Menu/Mob Highlight and Powder Chest Chat Filter stayed behind,
 * main's miningqol/powdermining features cover them). Each case switches its feature on in memory only (never saved), puts the player on the right
 * island through the tab list's Area line, sends the lines, and checks the feature ACTED (its state, a chat line it
 * printed, a line it hid, a slot it drew) before checking forged or off-island lines did nothing.
 * <ul>
 *   <li>3050-hx-mining-fetchur: the riddle answered in chat; a player's copy and an off-island riddle answer nothing.</li>
 *   <li>3051-hx-mining-puzzler: the arrows' block boxed at the walked spot; a player's copy moves nothing.</li>
 *   <li>3053-hx-mining-cold-overlay: the sidebar's Cold read and the frost layer drawn at Cold/100; a campfire clears it.</li>
 *   <li>3054-hx-mining-sky-mall: a Sky Mall buff held and on the HUD; a Heart of the Forest buff and a player's copy
 *       ignored.</li>
 *   <li>3055-hx-mining-worm-alert: a worm counted with the cooldown HUD; a Scatha nametag after it announced once; a
 *       player's copy and an off-island line count nothing.</li>
 *   <li>3057-hx-mining-fossil: a real "Fossil Excavator" menu: a fully dug Ugly is one placement named Ugly; a row of six
 *       leaves several, and every outlined best tile is still dirt.</li>
 *   <li>3058-hx-mining-wishing-compass: two happy-villager trails from two spots 12 blocks apart are solved to their
 *       common goal within 1.5 blocks; a trail off the Crystal Hollows is not read.</li>
 * </ul>
 */
final class HxMining3Cases {

    private HxMining3Cases() {
    }

    static void register(Session s) {
        s.test("3050-hx-mining-fetchur", HxMining3Cases::fetchur);
        s.test("3051-hx-mining-puzzler", HxMining3Cases::puzzler);
        s.test("3053-hx-mining-cold-overlay", HxMining3Cases::coldOverlay);
        s.test("3054-hx-mining-sky-mall", HxMining3Cases::skyMall);
        s.test("3055-hx-mining-worm-alert", HxMining3Cases::wormAlert);
        s.test("3057-hx-mining-fossil", HxMining3Cases::fossil);
        s.test("3058-hx-mining-wishing-compass", HxMining3Cases::compass);
    }

    // ---- 3057 Fossil Solver ---------------------------------------------------------------------------------------

    private static final String WHITE = "minecraft:white_stained_glass_pane";
    private static final String DIRT = "minecraft:brown_stained_glass_pane";

    /** A 9x6 Fossil Excavator board: {@code fossil} cells white, every other cell dirt. */
    private static Map<Integer, String> board(int[][] fossil) {
        Map<Integer, String> slots = new java.util.HashMap<>();
        for (int i = 0; i < 54; i++) {
            slots.put(i, DIRT);
        }
        for (int[] xy : fossil) {
            slots.put(xy[1] * 9 + xy[0], WHITE);
        }
        return slots;
    }

    private static void openExcavator(Session c, Map<Integer, String> slots) {
        c.hx().menu("Fossil Excavator", 6, slots, null);
        c.waitUntil("the Fossil Excavator is open", mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s
                && s.getTitle().getString().equals("Fossil Excavator")
                && !s.getMenu().slots.get(53).getItem().isEmpty(), 200);
    }

    private static void closeMenu(Session c) {
        c.ctx().runOnClient(mc -> {
            if (mc.player != null) {
                mc.player.closeContainer();
            }
        });
    }

    private static void fossil(Session c) {
        String k = "mining.fossil.FossilSolver";
        if (!has(c, k)) {
            return;
        }
        // The Ugly fossil (16 tiles) fully dug at the top left: exactly one placement fits.
        int[][] ugly = {{2, 0}, {3, 0}, {1, 1}, {2, 1}, {3, 1}, {4, 1}, {0, 2}, {1, 2}, {2, 2}, {3, 2}, {4, 2}, {5, 2},
                {1, 3}, {2, 3}, {3, 3}, {4, 3}};
        try (Settings set = on(c, k)) {
            island(c, "Dwarven Mines", "dwarven_mines", null);
            c.ctx().runOnClient(mc -> Mod.setField(k, "last", null));
            openExcavator(c, board(ugly));
            c.waitUntil("the solver drew a result", mc -> Mod.staticCall(k, "last") != null, 60);
            Object r = c.onClient(mc -> Mod.staticCall(k, "last"));
            String d = c.onClient(mc -> (String) Mod.staticCall(k, "describe", r));
            c.note("full Ugly: " + d);
            c.check(((Number) Mod.call(r, "placements")).intValue() == 1, "a fully dug Ugly leaves " + d);
            c.check("Ugly".equals(Mod.call(r, "fossil")), "the fossil is not named Ugly: " + d);
            c.check(((Number) Mod.call(r, "minimumLeft")).intValue() == 0, "a fully dug fossil still needs digging: " + d);
        } finally {
            closeMenu(c);
        }
        // One row of six white tiles: several placements, the outlined tiles are the solver's best and all still dirt.
        int[][] row = {{0, 2}, {1, 2}, {2, 2}, {3, 2}, {4, 2}, {5, 2}};
        try (Settings set = on(c, k)) {
            c.ctx().runOnClient(mc -> Mod.setField(k, "last", null));
            openExcavator(c, board(row));
            c.waitUntil("the solver drew a result", mc -> Mod.staticCall(k, "last") != null, 60);
            Object r = c.onClient(mc -> Mod.staticCall(k, "last"));
            String d = c.onClient(mc -> (String) Mod.staticCall(k, "describe", r));
            c.note("six in a row: " + d);
            int n = ((Number) Mod.call(r, "placements")).intValue();
            c.check(n > 1, "six fossil tiles in a row leave " + n + " placements");
            @SuppressWarnings("unchecked")
            List<Integer> drawn = c.onClient(mc -> (List<Integer>) Mod.staticCall(k, "lastBest"));
            c.check(!drawn.isEmpty(), "no tile was outlined as best");
            for (int i : drawn) {
                c.check(!(i >= 18 && i <= 23), "a dug fossil tile (" + i + ") was outlined as one to dig");
            }
        } finally {
            closeMenu(c);
        }
    }

    // ---- 3058 Wishing Compass Solver ------------------------------------------------------------------------------

    private static void trail(Session c, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        net.minecraft.world.phys.Vec3 dir = to.subtract(from).normalize();
        for (int i = 1; i <= 30; i++) {
            net.minecraft.world.phys.Vec3 p = from.add(dir.scale(0.5 * i));
            c.hx().call("particle", "type", "minecraft:happy_villager", "x", p.x, "y", p.y, "z", p.z);
        }
    }

    private static void compass(Session c) {
        String k = "mining.compass.WishingCompassSolver";
        if (!has(c, k)) {
            return;
        }
        try (Settings set = on(c, k)) {
            island(c, "Crystal Hollows", "crystal_hollows", null);
            c.ctx().runOnClient(mc -> Mod.setField(k, "target", null));
            net.minecraft.world.phys.Vec3 eye = c.onClient(mc -> mc.player.position().add(0, 1.62, 0));
            net.minecraft.world.phys.Vec3 goal = eye.add(60, -10, 45);
            c.ctx().runOnClient(mc -> Mod.staticCall(k, "startUse", eye));
            trail(c, eye, goal);
            c.waitUntil("the first reading taken", mc -> "WAIT_SECOND".equals(Mod.staticCall(k, "state")), 100);
            net.minecraft.world.phys.Vec3 eye2 = eye.add(0, 0, -12);
            c.ctx().runOnClient(mc -> Mod.staticCall(k, "startUse", eye2));
            trail(c, eye2, goal);
            c.waitUntil("the target solved", mc -> Mod.staticCall(k, "target") != null, 100);
            net.minecraft.world.phys.Vec3 t = c.onClient(mc -> (net.minecraft.world.phys.Vec3) Mod.staticCall(k, "target"));
            c.note("goal " + goal + ", solved " + t + " (" + c.onClient(mc -> Mod.staticCall(k, "targetName")) + ")");
            c.check(t.distanceTo(goal) < 1.5, "solved " + t + ", " + String.format(java.util.Locale.ROOT, "%.2f", t.distanceTo(goal))
                    + " from the goal " + goal);
            // Off the Crystal Hollows the trail is not read.
            island(c, "Dwarven Mines", "dwarven_mines", null);
            c.ctx().runOnClient(mc -> Mod.staticCall(k, "startUse", eye));
            trail(c, eye, goal);
            c.ctx().waitTicks(10);
            c.check("FIRST".equals(c.onClient(mc -> Mod.staticCall(k, "state"))),
                    "a trail off the Crystal Hollows was read: state " + c.onClient(mc -> Mod.staticCall(k, "state")));
        }
    }

    // ---- shared -------------------------------------------------------------------------------------------------

    /** True when the jar has this branch's class; otherwise the case is a problem, not a crash. */
    private static boolean has(Session c, String cls) {
        boolean ok = Mod.has(cls);
        c.check(ok, "the jar has no " + cls + ": not a staging-mining-3 jar");
        return ok;
    }

    /** The feature's IslandConfig switched on in memory (restored by the returned Settings). */
    private static Settings on(Session c, String featureClass) {
        Object cfg = c.onClient(mc -> Mod.field(featureClass, "CFG"));
        boolean was = c.onClient(mc -> (Boolean) Mod.call(cfg, "isEnabledRaw"));
        return new Settings(c).custom(() -> Mod.call(cfg, "set", "enabled", true),
                () -> Mod.call(cfg, "set", "enabled", was));
    }

    /** The SkyBlock sidebar and the tab's Area line for {@code area} ("Dwarven Mines", "Crystal Hollows", "Hub"). */
    private static void island(Session c, String area, String sidebarTemplate, List<String> extra) {
        if (extra == null) {
            sidebar(c, "template", sidebarTemplate);
        } else {
            sidebar(c, "template", sidebarTemplate, "extra", extra);
        }
        tab(c, "template", "area", "area", area);
        String want = switch (area) {
            case "Dwarven Mines" -> "DWARVEN_MINES";
            case "Crystal Hollows" -> "CRYSTAL_HOLLOWS";
            default -> "HUB";
        };
        c.waitUntil("IslandDetector reads " + want, mc -> want.equals(Mod.staticCall("pathfinding.IslandDetector",
                "graphIsland")), 100);
    }

    /** Chat lines shown since {@code mark} containing {@code part}, matched with color codes stripped (so a hidden-line
     *  check cannot pass just because the shown line carried codes between the words). */
    private static List<String> chatSince(long mark, String part) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : logLines(mark, "[CHAT]")) {
            if (line.replaceAll("§.", "").contains(part)) {
                out.add(line);
            }
        }
        return out;
    }

    // ---- 3050 Fetchur ---------------------------------------------------------------------------------------------

    private static void fetchur(Session c) {
        String k = "mining.fetchur.FetchurSolver";
        if (!has(c, k)) {
            return;
        }
        try (Settings set = on(c, k)) {
            island(c, "Hub", "hub", null);
            long mark = LogTap.mark();
            c.hx().chat("§e[NPC] Fetchur§f: its red and soft");
            c.ctx().waitTicks(10);
            c.check(chatSince(mark, "Fetchur wants:").isEmpty(), "Fetchur was answered off the Dwarven Mines");

            island(c, "Dwarven Mines", "dwarven_mines", null);
            mark = LogTap.mark();
            c.hx().chat("§7[VIP] HxEvil§f: [NPC] Fetchur: its red and soft");
            c.hx().chat("§2Guild > §7HxEvil§f: [NPC] Fetchur: its red and soft");
            c.ctx().waitTicks(10);
            c.check(chatSince(mark, "Fetchur wants:").isEmpty(), "a player's copy of the riddle was answered");

            mark = LogTap.mark();
            c.hx().chat("§e[NPC] Fetchur§f: its yellow and see through");
            long m2 = mark;
            c.waitUntil("Fetchur's answer in chat", mc -> !chatSince(m2, "Fetchur wants: 20x Yellow Stained Glass").isEmpty(), 40);
            c.check("20x Yellow Stained Glass".equals(c.onClient(mc -> Mod.staticCall(k, "lastAnswer"))),
                    "lastAnswer is " + c.onClient(mc -> Mod.staticCall(k, "lastAnswer")));
            c.note("answered: " + chatSince(m2, "Fetchur wants:"));
            assertConnected(c, "the Fetchur lines");
        }
    }

    // ---- 3051 Puzzler ---------------------------------------------------------------------------------------------

    private static void puzzler(Session c) {
        String k = "mining.puzzler.PuzzlerSolver";
        if (!has(c, k)) {
            return;
        }
        try (Settings set = on(c, k)) {
            island(c, "Dwarven Mines", "dwarven_mines", null);
            // Up x3 (+z 3), right x2 (-x 2), left x4 (+x 4), down x1 (-z 1): (181 + 2, 195, 135 + 2).
            String arrows = "▲▲▲▶▶◀◀◀◀▼";
            c.hx().chat("§7[VIP] HxEvil§f: [NPC] Puzzler: " + arrows);
            c.ctx().waitTicks(10);
            Object forged = c.onClient(mc -> Mod.staticCall(k, "target"));
            c.check(forged == null, "a player's copy of the arrows set a block: " + forged);

            long mark = LogTap.mark();
            c.hx().chat("§e[NPC] §dPuzzler§f: §d" + arrows);
            c.waitUntil("PuzzlerSolver.target set", mc -> Mod.staticCall(k, "target") != null, 40);
            net.minecraft.core.BlockPos t = c.onClient(mc -> (net.minecraft.core.BlockPos) Mod.staticCall(k, "target"));
            c.check(t.getX() == 183 && t.getY() == 195 && t.getZ() == 137, "the arrows lead to " + t + ", want 183, 195, 137");
            c.waitUntil("the coordinates in chat", mc -> !chatSince(mark, "Mine the block at 183, 195, 137").isEmpty(), 40);
            c.note("boxed " + t);

            // The reward line ends the puzzle: the box goes at once (a player's copy of it moves nothing).
            String solved = "Puzzler gave you 5,375 Mithril Powder for solving the puzzle!";
            c.hx().chat("§7[VIP] HxEvil§f: " + solved);
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> Mod.staticCall(k, "target")) != null, "a player's copy of the reward line cleared the box");
            c.hx().chat("§a" + solved);
            c.waitUntil("PuzzlerSolver.target cleared by the reward line", mc -> Mod.staticCall(k, "target") == null, 40);
        }
    }

    // ---- 3053 Cold Overlay ----------------------------------------------------------------------------------------

    private static void coldOverlay(Session c) {
        String k = "mining.cold.ColdOverlay";
        if (!has(c, k)) {
            return;
        }
        try (Settings set = on(c, k)) {
            island(c, "Dwarven Mines", "dwarven_mines", List.of("Cold: §b-40❄"));
            c.waitUntil("ColdOverlay.cold() reads 40", mc -> Integer.valueOf(40).equals(Mod.staticCall(k, "cold")), 60);
            float a = c.onClient(mc -> (Float) Mod.staticCall(k, "opacity"));
            c.check(Math.abs(a - 0.4f) < 0.001f, "opacity at Cold 40 is " + a + ", want 0.4");
            c.waitUntil("the frost layer drew at 0.4", mc -> Math.abs((Float) Mod.staticCall(k, "lastDrawn") - 0.4f) < 0.001f, 60);

            c.hx().chat("The warmth of the campfire reduced your ❄ Cold to 0!");
            c.waitUntil("a campfire clears the Cold", mc -> Integer.valueOf(0).equals(Mod.staticCall(k, "cold")), 40);
            c.waitUntil("the frost layer stops", mc -> (Float) Mod.staticCall(k, "lastDrawn") == 0f, 40);
            island(c, "Dwarven Mines", "dwarven_mines", null);
        }
    }

    // ---- 3054 Sky Mall Display ------------------------------------------------------------------------------------

    private static void skyMall(Session c) {
        String k = "mining.skymall.SkyMallDisplay";
        if (!has(c, k)) {
            return;
        }
        try (Settings set = on(c, k)) {
            island(c, "Dwarven Mines", "dwarven_mines", null);
            c.ctx().runOnClient(mc -> Mod.setField(k, "current", null));
            c.hx().chat("§7[VIP] HxEvil§f: New buff: Gain +100⸕ Mining Speed.");
            c.hx().chat("§eNew buff: §r§fGain §r§a+50 §r§6☘ Mangrove Fortune§r§f.");
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> Mod.staticCall(k, "current")) == null,
                    "a player's copy or a Heart of the Forest buff was taken: " + c.onClient(mc -> Mod.staticCall(k, "current")));

            c.hx().chat("§eNew buff: §r§fGain §r§a5x §r§9Titanium §r§fdrops.");
            c.waitUntil("Sky Mall reads TITANIUM", mc -> "TITANIUM".equals(String.valueOf(Mod.staticCall(k, "current"))), 40);
            c.waitUntil("the Sky Mall HUD has lines", mc -> !((List<?>) Mod.staticCall(k, "lines")).isEmpty(), 60);
            @SuppressWarnings("unchecked")
            List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(k, "lines"));
            c.check(lines.get(0).contains("5x Titanium drops"), "the HUD's first line is " + lines.get(0));
            c.note("HUD: " + lines);
        }
    }

    // ---- 3055 Worm Alert ------------------------------------------------------------------------------------------

    private static void wormAlert(Session c) {
        String k = "mining.worms.WormAlert";
        if (!has(c, k)) {
            return;
        }
        String spawn = "§cYou hear the sound of something approaching...";
        try (Settings set = on(c, k)) {
            island(c, "Dwarven Mines", "dwarven_mines", null);
            int before = c.onClient(mc -> (Integer) Mod.staticCall(k, "spawns"));
            c.hx().chat(spawn);
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(k, "spawns")) == before, "a worm counted off the Crystal Hollows");

            island(c, "Crystal Hollows", "crystal_hollows", null);
            c.hx().chat("§7[VIP] HxEvil§f: You hear the sound of something approaching...");
            c.ctx().waitTicks(10);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(k, "spawns")) == before, "a player's copy counted a worm");

            int scathas = c.onClient(mc -> (Integer) Mod.staticCall(k, "scathas"));
            c.hx().chat(spawn);
            c.waitUntil("the worm counted", mc -> (Integer) Mod.staticCall(k, "spawns") == before + 1, 40);
            c.waitUntil("the cooldown HUD counts down", mc -> {
                @SuppressWarnings("unchecked")
                List<String> l = (List<String>) Mod.staticCall(k, "lines");
                return !l.isEmpty() && l.get(0).contains("Worm cooldown:");
            }, 40);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(k, "scathas")) == scathas, "a Scatha was announced with none near");
            double[] p = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
            c.hx().stand(p[0] + 3, p[1], p[2], "§8[§7Lv10§8] §cScatha §a2M§f/§a2M§c❤", "nameVisible", true);
            c.waitUntil("the Scatha announced", mc -> (Integer) Mod.staticCall(k, "scathas") == scathas + 1, 60);
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(k, "scathas")) == scathas + 1, "the same Scatha was announced twice");
            c.note("HUD: " + c.onClient(mc -> Mod.staticCall(k, "lines")));
        } finally {
            c.hx().removeStands();
        }
    }

    private static List<String> names(Object entities) {
        List<String> out = new java.util.ArrayList<>();
        for (Object e : (List<?>) entities) {
            String n = ((net.minecraft.world.entity.Entity) e).getName().getString();
            out.add(n.replaceAll("§.", "").trim());
        }
        return out;
    }

}
