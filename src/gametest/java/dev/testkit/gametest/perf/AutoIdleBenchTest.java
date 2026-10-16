package dev.testkit.gametest.perf;

import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 95-perf-autoidle: what the automation features cost while switched ON but with nothing to do (2026-10-15,
 * fps-auto-1015). About 5 minutes; only when named.
 *
 * <p>95-perf-features never switches automation on ({@link PerfBench#NEVER_ON}), so its numbers only show what an
 * automation package costs while OFF. This scenario measures the other idle case: in {@link PerfBench}'s fixed scene
 * (the sim F7, the player standing still at the spawn, the camera turning) it alternates <b>OFF</b> (every switch off)
 * and <b>AUTO IDLE</b> (every switch off except the automation switches whose label starts with one of
 * {@code testkit.perf.auto}, default {@link #DEFAULT_AUTO}), {@code testkit.perf.rounds} (3) rounds of
 * {@code testkit.perf.bigTicks} (400) ticks, and prints both phases per feature package and every key of those
 * packages. It FAILS if the player moved or the hotbar slot changed during an AUTO IDLE phase, since then a feature
 * acted and the phase is not idle. Output: {@code perf-autoidle.txt} / {@code .tsv}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class AutoIdleBenchTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-autoidle";

    /** The packages of the automation area (fps-auto-1015). Label prefixes, matched with startsWith. */
    static final String DEFAULT_AUTO = "autoroutes.,ap3.,autopuzzles.,leveraura.,cheatutils.,terminalaura.,"
            + "terminaltrigger.,secrettrigger.,goldor.,dungeonbreaker.,simonsays.,autosecret.,bloodcamp.,terminals.,"
            + "dungeonextras.,fastleap.,autoclear.";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        int ticks = Integer.getInteger("testkit.perf.bigTicks", 400);
        int rounds = Integer.getInteger("testkit.perf.rounds", 3);
        String autoCsv = System.getProperty("testkit.perf.auto", DEFAULT_AUTO);
        String skipCsv = System.getProperty("testkit.perf.autoSkip", "");
        List<String> report = new ArrayList<>();
        List<String> tsv = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off or wrapped nothing: a dev jar is needed");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            Map<Switch, Boolean> auto = PerfBench.allOff(all);
            List<String> onLabels = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            for (Switch s : all) {
                if (matches(s.label(), autoCsv) && (skipCsv.isBlank() || !contains(s.label(), skipCsv))) {
                    auto.put(s, true);
                    onLabels.add(s.label());
                    String pkg = s.label().substring(0, s.label().indexOf('.') + 1);
                    if (!prefixes.contains(pkg)) {
                        prefixes.add(pkg);
                    }
                }
            }
            report.add("AUTO IDLE switches on (" + onLabels.size() + "): " + onLabels);
            if (onLabels.isEmpty()) {
                throw new AssertionError("[" + NAME + "] no switch matched " + autoCsv);
            }

            // A route in the room he stands in, every node well clear of him: Auto Routes' idle arm test then walks a
            // real node list every tick, as it does in any routed room he is not standing on a node of.
            report.add(ctx.computeOnClient(AutoIdleBenchTest::seedRoute));

            List<Phase> offs = new ArrayList<>();
            List<Phase> autos = new ArrayList<>();
            for (int r = 1; r <= rounds; r++) {
                offs.add(phase(ctx, off, "OFF r" + r, ticks, cal, report));
                double[] before = ctx.computeOnClient(AutoIdleBenchTest::playerState);
                autos.add(phase(ctx, auto, "AUTO IDLE r" + r, ticks, cal, report));
                double[] after = ctx.computeOnClient(AutoIdleBenchTest::playerState);
                double moved = Math.sqrt(sq(after[0] - before[0]) + sq(after[1] - before[1]) + sq(after[2] - before[2]));
                report.add(String.format(Locale.ROOT, "AUTO IDLE r%d: player moved %.3f blocks, slot %d -> %d", r, moved,
                        (int) before[3], (int) after[3]));
                if (moved > 0.05 || before[3] != after[3]) {
                    throw new AssertionError("[" + NAME + "] a feature ACTED during AUTO IDLE r" + r + " (moved " + moved
                            + ", slot " + before[3] + " -> " + after[3] + "): the phase is not idle; skip it with "
                            + "-PperfAutoSkip=<label part>");
                }
            }
            // ---- direct allocation checks of single hot calls (deterministic, unlike time) ----
            ctx.runOnClient(mc -> PerfBench.apply(auto));
            ctx.waitTicks(5);
            List<String> failures = new ArrayList<>();
            failures.addAll(ctx.computeOnClient(mc -> directChecks(mc, report)));
            if (Boolean.getBoolean("testkit.perf.autoJfr")) {
                // -PperfAutoJfr: one AUTO IDLE phase under JFR (CPU and allocation samples) for tools/fps-jfr.py.
                ctx.runOnClient(mc -> dev.testkit.harness.BenchCamera.start(0f, 10f, PerfBench.PERIOD));
                FpsBenchTest.jfrPhase(ctx, ticks, PerfBench.outDir().resolve("perf-autoidle.jfr"));
                report.add("JFR (AUTO IDLE, " + ticks + " ticks): " + PerfBench.outDir().resolve("perf-autoidle.jfr"));
            }
            ctx.runOnClient(mc -> PerfBench.apply(off));
            ctx.runOnClient(mc -> unseedRoute());

            // ---- the bench must have measured something ----
            Phase a1 = autos.get(0);
            if (a1.frames() < ticks / 2 || a1.ticks() < ticks / 2) {
                throw new AssertionError("[" + NAME + "] the frame clock recorded " + a1.frames() + " frames / "
                        + a1.ticks() + " ticks for " + ticks);
            }
            boolean sawAuto = a1.keys().keySet().stream().anyMatch(k -> prefixes.stream().anyMatch(k::startsWith));
            if (!sawAuto) {
                throw new AssertionError("[" + NAME + "] the probe saw no callback of " + prefixes);
            }
            summarize(report, tsv, offs, autos, prefixes);
            failures.addAll(keyBudgets(offs, autos, report));
            if (!failures.isEmpty()) {
                throw new AssertionError("[" + NAME + "] " + failures.size() + " budget(s) over: " + failures);
            }
            report.add("PASS: every budget held");
        } finally {
            try {
                if (!defaults.isEmpty()) {
                    ctx.runOnClient(mc -> PerfBench.apply(defaults));
                }
            } catch (Throwable ignored) {
                // restoring config must not mask the real failure
            }
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Path out = PerfBench.outDir();
                Files.createDirectories(out);
                Files.writeString(out.resolve("perf-autoidle.txt"), String.join(System.lineSeparator(), report)
                        + System.lineSeparator());
                Files.writeString(out.resolve("perf-autoidle.tsv"), String.join("\n", tsv) + "\n");
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write the report: " + e);
            }
            PerfBench.close(ctx);
        }
    }

    // ---- budgets (fps-auto-1015; main 69505e45 measured over them, the branch under) ----

    /** Render-thread bytes per call of a single hot call, measured directly (no probe in between). */
    static final int RANGE_BUDGET_B = 400;  // main c6697ee5: 11,368 (nine deep copies); branch 280
    static final int REFRESH_BUDGET_B = 128; // main: 1,112; branch 64

    /** Probe keys: {phase, key, max bytes per tick}. */
    static final Object[][] KEY_BYTES = {
            {"OFF", "autopuzzles.AutoPuzzlesFeature ClientTickEvents.StartTick", 120}, // main 528, branch 16-48
            {"AUTO IDLE", "autopuzzles.AutoPuzzlesFeature ClientTickEvents.StartTick", 300}, // main 590, branch 98-242
            {"AUTO IDLE", "autoroutes.AutoRoutesFeature ClientTickEvents.StartTick", 400}, // main 3,232, branch 78-94
            {"AUTO IDLE", "cheatutils.CheatUtils ClientTickEvents.StartTick", 2000}, // main 32,915 (a boxed Long per block), branch 1,084
    };

    /** HUD elements that have nothing to show while idle: never laid out ({phase, HUD id}). */
    static final String[][] NOT_DRAWN = {
            {"AUTO IDLE", "HUD[autopilot_status]"},
            {"AUTO IDLE", "HUD[auto_clear_status]"},
    };

    private static List<String> keyBudgets(List<Phase> offs, List<Phase> autos, List<String> report) {
        List<String> fails = new ArrayList<>();
        report.add("");
        report.add("== budgets ==");
        for (Object[] b : KEY_BYTES) {
            List<Phase> ps = b[0].equals("OFF") ? offs : autos;
            double bytes = 0;
            for (Phase p : ps) {
                Key k = p.keys().get((String) b[1]);
                bytes += k == null ? 0 : k.bytes() / Math.max(1, p.ticks()) / ps.size();
            }
            String line = String.format(Locale.ROOT, "%-9s %-62s %8.0f B/tick (budget %d)", b[0], b[1], bytes, (Integer) b[2]);
            report.add(line);
            if (bytes > (Integer) b[2]) {
                fails.add(line);
            }
        }
        for (String[] b : NOT_DRAWN) {
            List<Phase> ps = b[0].equals("OFF") ? offs : autos;
            long calls = 0;
            for (Phase p : ps) {
                for (Key k : p.keys().values()) {
                    if (k.key().endsWith(b[1])) {
                        calls += k.calls();
                    }
                }
            }
            String line = String.format(Locale.ROOT, "%-9s %-62s %8d draw call(s) (budget 0)", b[0], b[1], calls);
            report.add(line);
            if (calls > 0) {
                fails.add(line);
            }
        }
        return fails;
    }

    /** Client thread. One hot call each, timed for allocation alone. */
    private static List<String> directChecks(net.minecraft.client.Minecraft mc, List<String> report) {
        List<String> fails = new ArrayList<>();
        var inv = mc.player.getInventory();
        net.minecraft.world.item.ItemStack[] saved = new net.minecraft.world.item.ItemStack[9];
        try {
            // EtherwarpHopper.range(): read for all nine hotbar slots every tick the Interactive Map keeps its floor
            // graph warm. Nine Skyblock items, none of them an etherwarp item, so every slot is read.
            for (int i = 0; i < 9; i++) {
                saved[i] = inv.getItem(i).copy();
                inv.setItem(i, skyblockItem(i));
            }
            java.lang.invoke.MethodHandle range = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    dev.testkit.gametest.mod.Mod.cls("pathfinding.EtherwarpHopper"), "range",
                    java.lang.invoke.MethodType.methodType(double.class));
            double r = (double) range.invokeExact();
            long b0 = PerfBench.threadAllocated();
            for (int k = 0; k < 500; k++) {
                r += (double) range.invokeExact();
            }
            double per = (PerfBench.threadAllocated() - b0) / 500.0;
            String line = String.format(Locale.ROOT, "EtherwarpHopper.range() with 9 Skyblock items: %.0f B/call (budget %d)",
                    per, RANGE_BUDGET_B);
            report.add(line + " [sum " + r + "]");
            if (per > RANGE_BUDGET_B) {
                fails.add(line);
            }
        } catch (Throwable t) {
            fails.add("range check threw: " + t);
        } finally {
            for (int i = 0; i < 9; i++) {
                if (saved[i] != null) {
                    inv.setItem(i, saved[i]);
                }
            }
        }
        // TerminalSolverFeature.refreshState(): the head of every screen frame (two mixins), here with a plain chest
        // open and the solver on, the case where nothing is a terminal and the title used to be re-matched each time.
        net.minecraft.client.gui.screens.Screen before = dev.testkit.compat.McCompat.screen(mc);
        try {
            boolean solverOn = (Boolean) dev.testkit.gametest.mod.Mod.get("terminals.TerminalSolverConfig", "isEnabled");
            dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.ContainerScreen(
                    net.minecraft.world.inventory.ChestMenu.threeRows(0, inv), inv,
                    net.minecraft.network.chat.Component.literal("Large Chest")));
            java.lang.invoke.MethodHandle refresh = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    dev.testkit.gametest.mod.Mod.cls("terminals.TerminalSolverFeature"), "refreshState",
                    java.lang.invoke.MethodType.methodType(void.class));
            refresh.invokeExact();
            long b0 = PerfBench.threadAllocated();
            for (int k = 0; k < 500; k++) {
                refresh.invokeExact();
            }
            double per = (PerfBench.threadAllocated() - b0) / 500.0;
            String line = String.format(Locale.ROOT, "TerminalSolverFeature.refreshState() on a 'Large Chest' (solver %s): "
                    + "%.0f B/call (budget %d)", solverOn ? "on" : "OFF", per, REFRESH_BUDGET_B);
            report.add(line);
            if (!solverOn) {
                fails.add("the Terminal Solver was off for the refreshState check, so it measured nothing");
            } else if (per > REFRESH_BUDGET_B) {
                fails.add(line);
            }
        } catch (Throwable t) {
            fails.add("refreshState check threw: " + t);
        } finally {
            dev.testkit.compat.McCompat.setScreen(mc, before);
        }
        return fails;
    }

    private static net.minecraft.world.item.ItemStack skyblockItem(int i) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.DIAMOND_SWORD);
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", "HYPERION");
        tag.putString("uuid", "0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5" + i);
        tag.putLong("timestamp", 1_700_000_000_000L + i);
        net.minecraft.nbt.CompoundTag ench = new net.minecraft.nbt.CompoundTag();
        for (String e : new String[]{"sharpness", "critical", "ender_slayer", "giant_killer", "looting", "luck",
                "scavenger", "vampirism", "venomous", "first_strike", "execute", "cubism"}) {
            ench.putInt(e, 5 + i);
        }
        tag.put("enchantments", ench);
        net.minecraft.nbt.CompoundTag gems = new net.minecraft.nbt.CompoundTag();
        gems.putString("SAPPHIRE_0", "PERFECT");
        gems.putString("COMBAT_0", "FLAWLESS");
        tag.put("gems", gems);
        net.minecraft.nbt.ListTag scrolls = new net.minecraft.nbt.ListTag();
        for (String s : new String[]{"IMPLOSION_SCROLL", "WITHER_SHIELD_SCROLL", "SHADOW_WARP_SCROLL"}) {
            scrolls.add(net.minecraft.nbt.StringTag.valueOf(s));
        }
        tag.put("ability_scroll", scrolls);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    private static final String SEEDED = "fps-auto-idle";
    private static String seededRoom;

    /** Client thread. Thirty WALK nodes 8-18 blocks from the player in the room he stands in (in memory, not saved). */
    private static String seedRoute(net.minecraft.client.Minecraft mc) {
        try {
            Object frame = dev.testkit.gametest.mod.Mod.staticCall("autoroutes.RouteCoords$Frame", "current");
            if (frame == null) {
                return "route NOT seeded: the live map has no room frame here";
            }
            String room = (String) dev.testkit.gametest.mod.Mod.call(frame, "roomName");
            Object store = dev.testkit.gametest.mod.Mod.staticCall("autoroutes.RouteStore", "getInstance");
            Object route = dev.testkit.gametest.mod.Mod.call(store, "forRoomOrCreate", room);
            @SuppressWarnings("unchecked")
            List<Object> nodes = (List<Object>) dev.testkit.gametest.mod.Mod.call(route, "nodes");
            if (!nodes.isEmpty()) {
                return "route NOT seeded: " + room + " already has " + nodes.size() + " node(s)";
            }
            Class<?> nodeCls = dev.testkit.gametest.mod.Mod.cls("autoroutes.RouteNode");
            Class<?> typeCls = dev.testkit.gametest.mod.Mod.cls("autoroutes.RouteNode$Type");
            Object walk = dev.testkit.gametest.mod.Mod.enumValue("autoroutes.RouteNode$Type", "WALK");
            java.lang.reflect.Constructor<?> ctor = nodeCls.getConstructor(typeCls, double.class, double.class,
                    double.class, float.class, float.class, int.class);
            java.lang.reflect.Method toRel = dev.testkit.gametest.mod.Mod.cls("autoroutes.RouteCoords").getMethod(
                    "toRelative", frame.getClass(), double.class, double.class, double.class);
            for (int i = 0; i < 30; i++) {
                net.minecraft.world.phys.Vec3 rel = (net.minecraft.world.phys.Vec3) toRel.invoke(null, frame,
                        mc.player.getX() + 8 + (i % 6) * 2, mc.player.getY(), mc.player.getZ() + 8 + (i / 6) * 2);
                nodes.add(ctor.newInstance(walk, rel.x, rel.y, rel.z, 0f, 0f, i));
            }
            seededRoom = room;
            return "route seeded: 30 WALK nodes in " + room + " (" + SEEDED + "), 8-18 blocks from the player";
        } catch (Throwable t) {
            return "route NOT seeded: " + t;
        }
    }

    private static void unseedRoute() {
        if (seededRoom == null) {
            return;
        }
        try {
            Object store = dev.testkit.gametest.mod.Mod.staticCall("autoroutes.RouteStore", "getInstance");
            Object route = dev.testkit.gametest.mod.Mod.call(store, "forRoom", seededRoom);
            if (route != null) {
                ((List<?>) dev.testkit.gametest.mod.Mod.call(route, "nodes")).clear();
            }
        } catch (Throwable ignored) {
            // the client config is thrown away after the run anyway
        }
        seededRoom = null;
    }

    private static double[] playerState(net.minecraft.client.Minecraft mc) {
        if (mc.player == null) {
            return new double[]{0, 0, 0, -1};
        }
        return new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getInventory().getSelectedSlot()};
    }

    private static double sq(double v) {
        return v * v;
    }

    private static Phase phase(ClientGameTestContext ctx, Map<Switch, Boolean> state, String label, int ticks,
                               double[] cal, List<String> report) {
        ctx.runOnClient(mc -> PerfBench.apply(state));
        ctx.waitTicks(40);
        Phase p = PerfBench.measure(ctx, label, ticks, cal);
        report.add(p.line());
        System.out.println("[" + NAME + "] " + p.line());
        return p;
    }

    private static boolean matches(String label, String csv) {
        for (String part : csv.split(",")) {
            if (!part.isBlank() && label.startsWith(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String label, String csv) {
        for (String part : csv.split(",")) {
            if (!part.isBlank() && label.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static void summarize(List<String> report, List<String> tsv, List<Phase> offs, List<Phase> autos,
                                  List<String> prefixes) {
        report.add("");
        report.add(String.format(Locale.ROOT, "OFF       probe %.1f us/f@144, %.1f KB/frame (mean of %d)",
                avg(offs, Phase::at144Us), avg(offs, p -> p.allocPerFrame() / 1024.0), offs.size()));
        report.add(String.format(Locale.ROOT, "AUTO IDLE probe %.1f us/f@144, %.1f KB/frame (mean of %d)",
                avg(autos, Phase::at144Us), avg(autos, p -> p.allocPerFrame() / 1024.0), autos.size()));
        report.add(String.format(Locale.ROOT, "min over rounds: OFF %.1f, AUTO IDLE %.1f us/f@144",
                offs.stream().mapToDouble(Phase::at144Us).min().orElse(0),
                autos.stream().mapToDouble(Phase::at144Us).min().orElse(0)));
        for (Object[] cfg : new Object[][]{{"AUTO IDLE", autos}, {"OFF", offs}}) {
            @SuppressWarnings("unchecked")
            List<Phase> ps = (List<Phase>) cfg[1];
            Map<String, double[]> feat = new TreeMap<>();
            Map<String, double[]> keys = new TreeMap<>();
            Map<String, String> kinds = new TreeMap<>();
            for (Phase p : ps) {
                for (Key k : p.keys().values()) {
                    if (k.kind().equals("server")) {
                        continue;
                    }
                    double[] a = feat.computeIfAbsent(k.feature(), x -> new double[4]);
                    a[0] += p.keyAt144(k) / ps.size();
                    if (k.kind().equals("frame")) {
                        a[1] += k.ns() / 1e3 / Math.max(1, p.frames()) / ps.size();
                    } else {
                        a[2] += k.ns() / 1e3 / Math.max(1, p.ticks()) / ps.size();
                    }
                    a[3] += k.bytes() / Math.max(1, p.ticks()) / ps.size();
                    if (prefixes.stream().anyMatch(k.key()::startsWith)) {
                        double[] b = keys.computeIfAbsent(k.key(), x -> new double[4]);
                        b[0] += p.keyAt144(k) / ps.size();
                        b[1] += k.calls() / (double) Math.max(1, p.ticks()) / ps.size();
                        b[2] += k.bytes() / Math.max(1, p.ticks()) / ps.size();
                        b[3] = Math.max(b[3], k.maxNs() / 1e3);
                        kinds.put(k.key(), k.kind());
                    }
                }
            }
            List<Map.Entry<String, double[]>> rows = new ArrayList<>(feat.entrySet());
            rows.removeIf(e -> prefixes.stream().noneMatch(pre -> pre.equals(e.getKey() + ".")));
            rows.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[0]).reversed());
            report.add("");
            report.add("== " + cfg[0] + ": automation packages (probe self time, mean of rounds) ==");
            report.add(String.format(Locale.ROOT, "%-22s %10s %10s %10s %12s", "feature", "us/f@144", "frame us",
                    "tick us", "B/tick"));
            double total = 0;
            for (Map.Entry<String, double[]> e : rows) {
                double[] a = e.getValue();
                total += a[0];
                report.add(String.format(Locale.ROOT, "%-22s %10.2f %10.2f %10.2f %12.0f", e.getKey(), a[0], a[1], a[2], a[3]));
                tsv.add(String.join("\t", "feature", (String) cfg[0], e.getKey(), f(a[0]), f(a[1]), f(a[2]), f(a[3])));
            }
            report.add(String.format(Locale.ROOT, "%-22s %10.2f", "TOTAL", total));
            tsv.add(String.join("\t", "total", (String) cfg[0], f(total)));
            List<Map.Entry<String, double[]>> krows = new ArrayList<>(keys.entrySet());
            krows.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[0]).reversed());
            report.add("");
            report.add("== " + cfg[0] + ": every automation key ==");
            report.add(String.format(Locale.ROOT, "%-78s %-6s %9s %8s %9s %8s", "key", "kind", "us/f@144", "calls/t",
                    "B/tick", "max us"));
            for (Map.Entry<String, double[]> e : krows) {
                double[] b = e.getValue();
                report.add(String.format(Locale.ROOT, "%-78s %-6s %9.2f %8.2f %9.0f %8.0f", e.getKey(),
                        kinds.get(e.getKey()), b[0], b[1], b[2], b[3]));
                tsv.add(String.join("\t", "key", (String) cfg[0], e.getKey(), kinds.get(e.getKey()), f(b[0]), f(b[1]),
                        f(b[2])));
            }
        }
    }

    private static double avg(List<Phase> ps, java.util.function.ToDoubleFunction<Phase> f) {
        return ps.stream().mapToDouble(f).average().orElse(0);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
