package dev.testkit.gametest.perf;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.ModUnderTest;
import dev.testkit.gametest.Scenario;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.gametest.perf.PerfBench.Key;
import dev.testkit.gametest.perf.PerfBench.Phase;
import dev.testkit.gametest.perf.PerfBench.Switch;

import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 95-perf-screens: what the mod costs with a SCREEN open (fps-gui-1015, 2026-10-15). About 5 minutes; only when named.
 *
 * <p>95-perf-features and the gate measure with no screen open, so the mod menu, the container overlays (Item Rarity,
 * Slot Text, Storage Overlay, Inventory Theme, tooltips...) and the main menu scenes never show in them. In
 * {@link PerfBench}'s fixed scene this opens, in turn: nothing ({@code none}, the reference), the mod menu on its card
 * grid ({@code menu}), the menu with Live Map's drawer open ({@code drawer}), a 54-slot "Auctions Browser" chest of
 * SkyBlock items with the mouse on slot 10 so its tooltip shows ({@code chest}), the same items on "Ender Chest (1/9)"
 * which Storage Overlay takes over ({@code storage}), and after leaving the world the title screen with the mod's
 * main menu scene ({@code title}). Each screen phase runs with every switch OFF and with ALL ON (as 95-perf-features),
 * {@code testkit.perf.bigTicks} (200) ticks each, and records frame CPU, the render thread's allocation per frame, the
 * probe's keys and, from the mod's own probes, the menu's and the scene's draw bytes per frame.
 * {@code -PperfScreens=menu,chest} limits the phases; unless {@code -PperfNoJfr}, one ALL ON JFR per phase
 * ({@code perf-screens-<phase>.jfr}, execution and allocation samples) lands in the report directory.
 *
 * <p>It fails when a phase never had its screen open, when the screen probe ({@code screen.<Class> extract}) recorded
 * nothing, or when a screen allocates more per frame than {@link #CEILING_KB} allows ({@code -PperfNoCeiling} to only
 * measure). Results: {@code perf-screens.txt}.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class PerfScreensTest implements FabricClientGameTest {

    private static final String NAME = "95-perf-screens";

    /** Upper bound on the mod's own draw allocation, KB per frame, ALL ON (the menu's from ModScreen.allocProbe, the
     *  others from the render thread minus the OFF phase of the same screen). Main 69505e45 measured the menu at
     *  ~750 KB a frame. */
    static final Map<String, Double> CEILING_KB = new LinkedHashMap<>();

    static {
        // main 69505e45: menu 342, drawer 697, title scene 100 KB/frame; fps-gui-1015: 86, 163, 18.
        CEILING_KB.put("menu", 150.0);
        CEILING_KB.put("drawer", 250.0);
        CEILING_KB.put("title", 50.0);
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank() || Scenario.skip(NAME)) {
            return;
        }
        int ticks = Integer.getInteger("testkit.perf.bigTicks", 200);
        String only = System.getProperty("testkit.perf.screens", "");
        boolean jfr = !Boolean.getBoolean("testkit.bench.noJfr");
        int jfrTicks = Integer.getInteger("testkit.perf.jfrTicks", ticks);
        boolean ceiling = !Boolean.getBoolean("testkit.perf.noCeiling");
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (!PerfBench.open(ctx, NAME, report)) {
            return;
        }
        List<String> phases = new ArrayList<>();
        for (String p : new String[]{"none", "menu", "drawer", "chest", "storage", "title"}) {
            if (only.isBlank() || p.equals("none") || List.of(only.split(",")).contains(p)) {
                phases.add(p);
            }
        }
        Map<Switch, Boolean> defaults = new LinkedHashMap<>();
        boolean left = false;
        try {
            if (!PerfBench.probeOn(ctx)) {
                throw new AssertionError("[" + NAME + "] the mod's PerfProbe is off or wrapped nothing: run with "
                        + "-PperfProbe (automatic for -Pscenario=95-perf...) and a dev jar, not a -Prelease one");
            }
            double[] cal = PerfBench.calibrate(ctx);
            List<Switch> all = ctx.computeOnClient(mc -> PerfBench.switches());
            defaults.putAll(ctx.computeOnClient(mc -> PerfBench.states(all)));
            Map<Switch, Boolean> on = PerfBench.allOn(defaults);
            Map<Switch, Boolean> off = PerfBench.allOff(all);
            report.add(String.format(Locale.ROOT, "probe calibration: %.1f ns outer, %.1f ns / %.1f B recorded per call",
                    cal[0], cal[1], cal[2]));
            report.add("phases: " + phases + ", " + ticks + " ticks each, OFF then ALL ON");
            ctx.runOnClient(mc -> PerfBench.apply(on));
            ctx.waitTicks(200);
            Map<String, Phase[]> results = new LinkedHashMap<>();
            for (String phase : phases) {
                if (phase.equals("title") && !left) {
                    PerfBench.close(ctx); // ends on the title screen
                    left = true;
                }
                Phase[] pair = new Phase[2];
                for (int k = 0; k < 2; k++) {
                    Map<Switch, Boolean> cfg = k == 0 ? off : on;
                    ctx.runOnClient(mc -> PerfBench.apply(cfg));
                    String why = enter(ctx, phase);
                    if (why != null) {
                        failures.add(phase + ": " + why);
                        break;
                    }
                    ctx.waitTicks(40);
                    setProbes(ctx, true);
                    pair[k] = PerfBench.measure(ctx, phase + (k == 0 ? " OFF" : " ALL ON"), ticks, cal);
                    double[] own = setProbes(ctx, false);
                    String still = expectOpen(ctx, phase);
                    if (still != null) {
                        failures.add(phase + (k == 0 ? " OFF" : " ALL ON") + ": " + still);
                    }
                    report.add(pair[k].line());
                    if (own[0] >= 0) {
                        report.add(String.format(Locale.ROOT, "    mod menu draw (ModScreen.allocProbe): %.1f KB/frame",
                                own[0] / 1024.0));
                    }
                    if (own[1] >= 0) {
                        report.add(String.format(Locale.ROOT, "    main menu scene (MainMenuScenes.allocProbe): %.1f KB/frame",
                                own[1] / 1024.0));
                    }
                    topKeys(report, pair[k], 12);
                    if (!phase.equals("none")) {
                        Key sk = screenKey(pair[k]);
                        if (sk == null || sk.calls() < pair[k].frames() / 2) {
                            failures.add(phase + ": the screen probe recorded " + (sk == null ? "nothing" : sk.calls()
                                    + " calls over " + pair[k].frames() + " frames"));
                        }
                    }
                    double ownB = phase.equals("title") ? own[1] : own[0];
                    if (k == 1 && ceiling && CEILING_KB.containsKey(phase)) {
                        if (ownB < 0) {
                            failures.add(phase + ": the mod's draw probe counted no frames (did the screen draw?)");
                        } else if (ownB / 1024.0 > CEILING_KB.get(phase)) {
                            failures.add(String.format(Locale.ROOT, "%s: the mod's draw allocates %.1f KB/frame, over the"
                                    + " %.0f KB ceiling", phase, ownB / 1024.0, CEILING_KB.get(phase)));
                        }
                    }
                }
                if (jfr && pair[1] != null) {
                    Path file = PerfBench.outDir().resolve("perf-screens-" + phase + ".jfr");
                    jfrPhase(ctx, jfrTicks, file);
                    report.add("JFR (ALL ON, " + jfrTicks + " ticks): " + file);
                }
                leave(ctx);
                results.put(phase, pair);
            }
            report.add("");
            report.add("summary (ALL ON minus OFF, same screen): mod us/f@144, render-thread KB/frame, frame ms mean");
            for (Map.Entry<String, Phase[]> e : results.entrySet()) {
                Phase a = e.getValue()[0];
                Phase b = e.getValue()[1];
                if (a == null || b == null) {
                    continue;
                }
                Key sa = screenKey(a);
                Key sb = screenKey(b);
                report.add(String.format(Locale.ROOT, "%-8s OFF %.1f us %.1f KB %.3f ms | ON %.1f us %.1f KB %.3f ms | screen "
                                + "extract OFF %.1f us %.1f KB, ON %.1f us %.1f KB per frame", e.getKey(), a.at144Us(),
                        a.allocPerFrame() / 1024.0, a.frame().mean(), b.at144Us(), b.allocPerFrame() / 1024.0,
                        b.frame().mean(), perFrame(a, sa, false), perFrame(a, sa, true) / 1024.0, perFrame(b, sb, false),
                        perFrame(b, sb, true) / 1024.0));
            }
        } finally {
            try {
                ctx.runOnClient(mc -> {
                    setProbesOnClient(false);
                    McCompat.setScreen(mc, null);
                    PerfBench.apply(defaults);
                });
            } catch (Throwable ignored) {
                // restoring must not mask the real failure
            }
            if (!left) {
                try {
                    PerfBench.close(ctx);
                } catch (Throwable ignored) {
                    // the run is over either way
                }
            }
            report.add(failures.isEmpty() ? "RESULT: PASS" : "RESULT: FAIL " + failures);
            for (String line : report) {
                System.out.println("[" + NAME + "] " + line);
            }
            try {
                Files.createDirectories(PerfBench.outDir());
                Files.writeString(PerfBench.outDir().resolve("perf-screens.txt"),
                        String.join(System.lineSeparator(), report) + System.lineSeparator());
            } catch (Exception e) {
                System.out.println("[" + NAME + "] could not write perf-screens.txt: " + e);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("[" + NAME + "] " + failures);
        }
    }

    // ---- screens ------------------------------------------------------------------------------------------------

    /** Opens the phase's screen and puts the mouse where it should be. Null when it is open, else why not. */
    private static String enter(ClientGameTestContext ctx, String phase) {
        switch (phase) {
            case "none" -> ctx.runOnClient(mc -> McCompat.setScreen(mc, null));
            case "menu", "drawer" -> {
                ctx.runOnClient(mc -> {
                    try {
                        McCompat.setScreen(mc, (Screen) Mod.cls("gui.ModScreen").getConstructor(Screen.class)
                                .newInstance((Object) null));
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError("could not open gui.ModScreen: " + e, e);
                    }
                });
                ctx.waitTicks(5);
                if (phase.equals("drawer")) {
                    ctx.runOnClient(mc -> {
                        Object s = McCompat.screen(mc);
                        Mod.call(s, "openFeature", leaf("LiveMapTab"));
                    });
                }
                cursor(ctx, 0.5, 0.45);
            }
            case "chest", "storage" -> {
                String title = phase.equals("chest") ? "Auctions Browser" : "Ender Chest (1/9)";
                ctx.runOnClient(mc -> {
                    SimpleContainer box = new SimpleContainer(54);
                    for (int i = 0; i < 54; i++) {
                        box.setItem(i, i >= 45 && i != 49 ? pane() : sbItem(i));
                    }
                    ChestMenu menu = ChestMenu.sixRows(0, mc.player.getInventory(), box);
                    mc.player.containerMenu = menu;
                    McCompat.setScreen(mc, new ContainerScreen(menu, mc.player.getInventory(), Component.literal(title)));
                });
                ctx.waitTicks(5);
                // the mouse on slot 10 (second row, second column) so its tooltip is drawn every frame
                double[] at = ctx.computeOnClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    if (!(s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
                        return new double[]{mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0};
                    }
                    int lp = ((Number) Mod.field(s, "leftPos")).intValue();
                    int tp = ((Number) Mod.field(s, "topPos")).intValue();
                    return toWindow(mc, lp + 8 + 18 + 8, tp + 18 + 18 + 8);
                });
                ctx.getInput().setCursorPos(at[0], at[1]);
            }
            case "title" -> {
                ctx.runOnClient(mc -> McCompat.setScreen(mc, new net.minecraft.client.gui.screens.TitleScreen()));
                cursor(ctx, 0.05, 0.05);
            }
            default -> throw new AssertionError("unknown phase " + phase);
        }
        ctx.waitTicks(5);
        return expectOpen(ctx, phase);
    }

    private static String expectOpen(ClientGameTestContext ctx, String phase) {
        return ctx.computeOnClient(mc -> {
            Screen s = McCompat.screen(mc);
            String got = s == null ? "no screen" : s.getClass().getName();
            boolean ok = switch (phase) {
                case "none" -> s == null;
                case "menu" -> s != null && s.getClass().getName().endsWith(".ModScreen")
                        && Mod.call(s, "openFeature") == null;
                case "drawer" -> s != null && s.getClass().getName().endsWith(".ModScreen")
                        && Mod.call(s, "openFeature") != null;
                case "chest", "storage" -> s instanceof ContainerScreen;
                case "title" -> s instanceof net.minecraft.client.gui.screens.TitleScreen;
                default -> false;
            };
            return ok ? null : "expected the " + phase + " screen, found " + got;
        });
    }

    private static void leave(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            if (McCompat.screen(mc) instanceof ContainerScreen && mc.player != null) {
                mc.player.containerMenu = mc.player.inventoryMenu;
            }
            if (!(McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.TitleScreen)) {
                McCompat.setScreen(mc, null);
            }
        });
        ctx.waitTicks(5);
    }

    private static Object leaf(String simpleName) {
        @SuppressWarnings("unchecked")
        List<Object> leaves = (List<Object>) Mod.staticCall("gui.ModScreen", "allLeaves");
        for (Object t : leaves) {
            if (t.getClass().getSimpleName().equals(simpleName)) {
                return t;
            }
        }
        throw new AssertionError("no feature tab " + simpleName + " in the menu");
    }

    private static void cursor(ClientGameTestContext ctx, double fx, double fy) {
        double[] at = ctx.computeOnClient(mc -> new double[]{mc.getWindow().getScreenWidth() * fx,
                mc.getWindow().getScreenHeight() * fy});
        ctx.getInput().setCursorPos(at[0], at[1]);
    }

    private static double[] toWindow(Minecraft mc, double sx, double sy) {
        Window win = mc.getWindow();
        float f = 1f;
        try {
            f = ((Number) ModUnderTest.staticCall("com.killer560.hub.hud.AutoScale", "appliedFactor",
                    new Class<?>[]{Screen.class}, new Object[]{McCompat.screen(mc)})).floatValue();
        } catch (RuntimeException | AssertionError ignored) {
            // no Auto Scale in this jar: plain GUI units
        }
        return new double[]{sx * f * win.getScreenWidth() / (double) win.getGuiScaledWidth(),
                sy * f * win.getScreenHeight() / (double) win.getGuiScaledHeight()};
    }

    private static final Item[] ITEMS = {Items.DIAMOND_SWORD, Items.BOW, Items.PLAYER_HEAD, Items.ENDER_PEARL,
            Items.GOLDEN_AXE, Items.STICK, Items.DIAMOND, Items.ENCHANTED_BOOK};
    private static final String[][] SB = {
            {"HYPERION", "Hyperion", "LEGENDARY DUNGEON SWORD", "6"},
            {"TERMINATOR", "Terminator", "MYTHIC DUNGEON BOW", "d"},
            {"WITHER_GOGGLES", "Wither Goggles", "EPIC DUNGEON HELMET", "5"},
            {"SPIRIT_LEAP", "Spirit Leap", "RARE", "9"},
            {"RAGNAROCK_AXE", "Ragnarock Axe", "LEGENDARY DUNGEON SWORD", "6"},
            {"ASPECT_OF_THE_VOID", "Aspect of the Void", "EPIC SWORD", "5"},
            {"ENCHANTED_DIAMOND", "Enchanted Diamond", "UNCOMMON", "a"},
            {"ENCHANTED_BOOK", "Enchanted Book", "COMMON", "f"},
    };

    /** A SkyBlock-looking stack: id/uuid custom data, a coloured name, five lore lines, a stack count on some. */
    private static ItemStack sbItem(int i) {
        String[] it = SB[i % SB.length];
        ItemStack s = new ItemStack(ITEMS[i % ITEMS.length], i % SB.length == 6 ? 1 + i % 64 : 1);
        CompoundTag t = new CompoundTag();
        t.putString("id", it[0]);
        t.putString("uuid", "00000000-0000-0000-0000-0000000000" + String.format(Locale.ROOT, "%02d", i));
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§" + it[3] + it[1]).withStyle(Style.EMPTY.withItalic(false)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("§7Gear Score: §d" + (500 + i)));
        lore.add(Component.literal("§7Damage: §c+" + (200 + i)));
        lore.add(Component.literal(""));
        lore.add(Component.literal("§6Ability: Something §e§lRIGHT CLICK"));
        lore.add(Component.literal("§" + it[3] + "§l" + it[2]));
        s.set(DataComponents.LORE, new ItemLore(lore));
        return s;
    }

    private static ItemStack pane() {
        ItemStack s = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        s.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        return s;
    }

    // ---- the mod's own draw probes ------------------------------------------------------------------------------

    /** Turns ModScreen's and MainMenuScenes' allocation probes on (zeroed) or off; off returns their bytes per frame
     *  (-1 when that probe counted no frames or is missing). */
    private static double[] setProbes(ClientGameTestContext ctx, boolean on) {
        return ctx.computeOnClient(mc -> setProbesOnClient(on));
    }

    private static double[] setProbesOnClient(boolean on) {
        double[] out = {-1, -1};
        String[] cls = {"gui.ModScreen", "mainmenu.MainMenuScenes"};
        for (int i = 0; i < cls.length; i++) {
            try {
                if (on) {
                    Mod.setField(Mod.ROOT + cls[i], "allocBytes", 0L);
                    Mod.setField(Mod.ROOT + cls[i], "allocFrames", 0L);
                    Mod.setField(Mod.ROOT + cls[i], "allocProbe", true);
                } else {
                    Mod.setField(Mod.ROOT + cls[i], "allocProbe", false);
                    long b = (Long) Mod.field(Mod.ROOT + cls[i], "allocBytes");
                    long f = (Long) Mod.field(Mod.ROOT + cls[i], "allocFrames");
                    out[i] = f == 0 ? -1 : b / (double) f;
                }
            } catch (Throwable ignored) {
                // that probe is not in this jar
            }
        }
        return out;
    }

    // ---- report helpers -----------------------------------------------------------------------------------------

    private static Key screenKey(Phase p) {
        Key best = null;
        for (Key k : p.keys().values()) {
            if (k.key().startsWith("screen.") && (best == null || k.calls() > best.calls())) {
                best = k;
            }
        }
        return best;
    }

    private static double perFrame(Phase p, Key k, boolean bytes) {
        if (k == null) {
            return 0;
        }
        return (bytes ? k.bytes() : k.ns() / 1e3) / Math.max(1, p.frames());
    }

    private static void topKeys(List<String> report, Phase p, int n) {
        List<Key> keys = new ArrayList<>(p.keys().values());
        keys.removeIf(k -> k.kind().equals("server"));
        keys.sort(Comparator.comparingDouble((Key k) -> -p.keyAt144(k)));
        for (int i = 0; i < Math.min(n, keys.size()); i++) {
            Key k = keys.get(i);
            report.add(String.format(Locale.ROOT, "    %8.1f us/f@144 %9.1f B/call %8d calls  %s", p.keyAt144(k),
                    k.bytes() / Math.max(1, k.calls()), k.calls(), k.key()));
        }
    }

    static void jfrPhase(ClientGameTestContext ctx, int ticks, Path file) {
        try {
            Files.createDirectories(file.getParent());
            jdk.jfr.Recording rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            rec.enable("jdk.ObjectAllocationSample").with("throttle", "5000/s");
            rec.start();
            ctx.waitTicks(ticks);
            rec.stop();
            rec.dump(file);
            rec.close();
        } catch (Exception e) {
            System.out.println("[" + NAME + "] JFR failed: " + e);
        }
    }
}
