package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 1104-ui-barsedit-panel (mod barsedit-1104; killer560, 2026-11-04: "make the edit bars button have the edit options for
 * the bars as well so you can see how the bars are changing as you change the settings"). Edit Bars shows Health and Mana
 * Bars' own rows in a side panel. Through real clicks on the panel's widgets: Bar Height to its top (the Health Bar
 * drawn on screen gets taller), Mana Bar off (its pixels go), Style stepped (the Health Bar's pixels change), each saved
 * to disk; the panel moves Left / Hidden (saved), a bar under the panel cannot be grabbed, and with it Hidden the bar
 * drags and saves as before. The panel carries no Edit Bars button.
 */
final class BarsEditPanelCases {

    private static final String PS = "playerstats.PlayerStatsConfig";
    private static final String HUD = "hud.HudConfig";
    private static final String READOUT = "playerstats.StatElements$Readout";
    private static final String FEATURE = "playerstats.PlayerStatsFeature";
    private static final int BX = 20;
    private static final int HY = 150;
    private static final int MY = 180;

    private BarsEditPanelCases() {
    }

    static void run(UiCase c) throws Exception {
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            setup(c);
            Screen ed = c.onClient(mc -> {
                try {
                    Screen s = (Screen) R.cls("hud.HudEditorScreen").getMethod("forBars", Screen.class)
                            .invoke(null, (Object) null);
                    McCompat.setScreen(mc, s);
                    return s;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            int sw = c.onClient(mc -> mc.getWindow().getGuiScaledWidth());

            String labels = c.onClient(mc -> labels(ed));
            c.note("panel rows in view: " + labels);
            for (String want : new String[]{"Settings Panel: Right", "Stat Bars", "Mana Bar", "Style", "Bar Height",
                    "Background", "Text Shadow", "Held Item Name"}) {
                c.check(c.onClient(mc -> seek(ed, want) != null), "the Edit Bars panel has no '" + want + "' row");
            }
            c.check(c.onClient(mc -> seek(ed, "Edit Bars") == null), "the Edit Bars panel shows an Edit Bars button");
            int px = c.onClient(mc -> (Integer) R.get(R.get(ed, "panel"), "x"));
            c.check(px > sw / 2, "the panel is not on the right (x " + px + " of " + sw + ")");

            BufferedImage before = HudFixCases.shot(c, "before");
            BarPixels.Scene scene = BarPixels.scene(c);
            double[] hb = BarPixels.box(before, HudFixCases.HEALTH, gs, scene);
            double[] mb = BarPixels.box(before, HudFixCases.MANA, gs, scene);
            c.note("before: Health " + show(hb) + ", Mana " + show(mb));
            if (hb == null || mb == null) {
                c.problem("a bar did not draw in Edit Bars before any change (Health " + show(hb) + ", Mana " + show(mb) + ")");
                return;
            }

            // Bar Height to its top, Mana Bar off: real clicks on the panel's own widgets.
            String height = c.onClient(mc -> {
                AbstractWidget s = seek(ed, "Bar Height");
                if (s == null) {
                    return "no Bar Height row";
                }
                click(ed, s.getX() + s.getWidth() - 1, s.getY() + s.getHeight() / 2.0);
                return "clicked " + s.getMessage().getString();
            });
            c.note("Bar Height: " + height);
            c.ticks(3);
            String mana = c.onClient(mc -> {
                AbstractWidget b = seek(ed, "Mana Bar:");
                if (b == null) {
                    return "no Mana Bar row";
                }
                click(ed, b.getX() + 4, b.getY() + b.getHeight() / 2.0);
                return "clicked " + b.getMessage().getString();
            });
            c.note("Mana Bar: " + mana);
            c.ticks(4);
            BufferedImage taller = HudFixCases.shot(c, "taller-no-mana");
            scene = BarPixels.scene(c);
            double[] ha = BarPixels.box(taller, HudFixCases.HEALTH, gs, scene);
            double[] ma = BarPixels.box(taller, HudFixCases.MANA, gs, scene);
            c.note("after: Health " + show(ha) + ", Mana " + show(ma));
            c.check(ha != null && ha[3] - ha[1] > hb[3] - hb[1] + gs,
                    "the Health Bar on screen did not get taller with Bar Height (" + show(hb) + " -> " + show(ha) + ")");
            c.check(ma == null, "the Mana Bar still draws after switching it off in the panel: " + show(ma));
            String disk = c.onClient(mc -> diskJson());
            int maxH = c.onClient(mc -> (Integer) R.getStatic(R.cls(PS), "MAX_BAR_HEIGHT"));
            c.check(disk.contains("\"barHeight\":" + maxH), "Bar Height " + maxH + " not on disk: " + cut(disk, "barHeight"));
            c.check(disk.matches("(?s).*\"mana_bar\":\\{[^}]*\"on\":false.*"), "Mana Bar off not on disk: "
                    + cut(disk, "mana_bar"));

            // Style: one step, the bar's pixels change, the new style on disk.
            String style0 = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle")));
            c.onClient(mc -> {
                AbstractWidget b = seek(ed, "Style:");
                click(ed, b.getX() + 4, b.getY() + b.getHeight() / 2.0);
                return null;
            });
            c.ticks(4);
            String style1 = c.onClient(mc -> String.valueOf(Mod.call(Mod.cfg(PS), "getBarStyle")));
            BufferedImage styled = HudFixCases.shot(c, "style");
            int diff = diff(taller, styled, ha);
            c.note("Style " + style0 + " -> " + style1 + "; " + diff + " pixels of the Health Bar changed");
            c.check(!style0.equals(style1), "the Style row did not step the style");
            c.check(diff > 20, "the Health Bar on screen did not change with Style (" + diff + " px)");
            String key = style1.toLowerCase(java.util.Locale.ROOT);
            c.check(c.onClient(mc -> diskJson()).contains("\"barStyle\":\"" + key + "\""), "Style " + key + " not on disk");

            // The panel to the Left: it ends above the Health Bar (a bar in the lower half under its columns), so the bar
            // still grabs below it, and a click on the panel's own empty space grabs nothing.
            c.onClient(mc -> {
                AbstractWidget b = seek(ed, "Settings Panel");
                click(ed, b.getX() + 4, b.getY() + 4);
                return null;
            });
            c.ticks(3);
            String left = c.onClient(mc -> {
                Object panel = R.get(ed, "panel");
                int x = (Integer) R.get(panel, "x");
                int y = (Integer) R.get(panel, "y");
                int w = (Integer) R.get(panel, "w");
                int h = (Integer) R.get(panel, "h");
                int[] pos = live(ed, "statbar_health");
                var info = new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0);
                // an empty spot inside the panel: no row on it
                double ex = -1;
                double ey = -1;
                for (double yy = y + h - 3; yy > y + 30 && ex < 0; yy -= 2) {
                    for (double xx = x + 3; xx < x + w - 3; xx += 4) {
                        if (hit(ed, xx, yy) == null) {
                            ex = xx;
                            ey = yy;
                            break;
                        }
                    }
                }
                Object onPanel = "no empty spot";
                if (ex >= 0) {
                    ed.mouseClicked(new MouseButtonEvent(ex, ey, info), false);
                    onPanel = R.get(ed, "draggingId");
                    ed.mouseReleased(new MouseButtonEvent(ex, ey, info));
                }
                double gx = pos[0] + 30;
                double gy = pos[1] + 2;
                ed.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
                Object bar = R.get(ed, "draggingId");
                ed.mouseReleased(new MouseButtonEvent(gx, gy, info));
                boolean ok = x < BX && x + w > BX + 100 && y + h <= pos[1] && onPanel == null
                        && "statbar_health".equals(bar);
                return "x " + x + " y " + y + " w " + w + " h " + h + ", bar at " + pos[1] + ", panel click -> " + onPanel
                        + ", bar click -> " + bar + (ok ? " OK" : " WRONG");
            });
            c.note("Left panel: " + left);
            c.check(left.endsWith(" OK"), "the Left panel covered the Health Bar or took a click: " + left);
            c.check(c.onClient(mc -> diskJson()).contains("\"editorPanel\":1"), "Settings Panel Left not on disk");
            HudFixCases.shot(c, "left");

            // Hidden: only the side button stays; the bar drags and saves as before.
            c.onClient(mc -> {
                AbstractWidget b = seek(ed, "Settings Panel");
                click(ed, b.getX() + 4, b.getY() + 4);
                return null;
            });
            c.ticks(3);
            String hidden = c.onClient(mc -> {
                int w = (Integer) R.get(R.get(ed, "panel"), "w");
                int rows = ((List<?>) R.get(ed, "panelWidgets")).size();
                int[] pos = live(ed, "statbar_health");
                var info = new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0);
                double gx = pos[0] + 30;
                double gy = pos[1] + 2;
                ed.mouseClicked(new MouseButtonEvent(gx, gy, info), false);
                Object dragging = R.get(ed, "draggingId");
                ed.mouseDragged(new MouseButtonEvent(gx + 23, gy + 11, info), 23, 11);
                ed.mouseReleased(new MouseButtonEvent(gx + 23, gy + 11, info));
                int[] want = (int[]) Mod.staticCall("hud.HudElementRegistry", "toSaved", pos[0] + 23, pos[1] + 11);
                String hudDisk = diskHud();
                boolean ok = w == 0 && rows == 1 && "statbar_health".equals(dragging)
                        && hudDisk.contains("\"statbar_health\":{\"x\":" + want[0] + ",\"y\":" + want[1] + ",");
                return "w " + w + ", " + rows + " widget(s), grabbed " + dragging + ", want " + want[0] + "," + want[1]
                        + (ok ? " OK" : " WRONG: " + cut(hudDisk, "statbar_health"));
            });
            c.note("Hidden panel: " + hidden);
            c.check(hidden.endsWith(" OK"), "Hidden panel / drag: " + hidden);
            c.check(c.onClient(mc -> diskJson()).contains("\"editorPanel\":2"), "Settings Panel Hidden not on disk");
            HudFixCases.shot(c, "hidden");
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            HudEditorCases.setWindow(c, window[0], window[1], gui);
            HudFixCases.restore(c, saved);
        }
    }

    private static final int FUEL = 0xFF13C7A1;
    private static final String FUEL_LINE = "§c1,000/1,000❤     §a100§a❈ Defense     §21,650/3k Drill Fuel";

    /**
     * 1104-ui-drill-fuel (mod drillfuel-1104; killer560, 2026-11-04: "also make a custom drill fuel bar and an option to hide
     * that text"). A real action bar packet carrying Hypixel's "1,650/3k Drill Fuel" segment: the Drill Fuel Bar reads
     * 1650/3000 and draws about 55% full beside a full Health Bar; Drill Fuel Text takes only that segment off the shown
     * action bar (the health stays); a line without it clears the reading and the bar goes; Edit Bars' panel has both rows.
     */
    static void drillFuel(UiCase c) throws Exception {
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            setup(c);
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Object ps = Mod.cfg(PS);
                Mod.call(ps, "setReadoutOn", r("DRILL_FUEL_BAR"), true);
                Mod.call(ps, "setReadoutOn", r("MANA_BAR"), false);
                Mod.call(ps, "setReadoutColor", r("DRILL_FUEL_BAR"), FUEL);
                Mod.call(ps, "setHideHypixelStatText", false);
                Mod.call(ps, "setHideDrillFuelText", false);
                Mod.call(ps, "save");
                Mod.call(Mod.cfg(HUD), "setPosition", "statbar_drillfuel", BX, 110);
                return null;
            });
            HudEditorCases.actionBar(c, FUEL_LINE);
            c.ticks(5);
            long[] fuel = c.onClient(mc -> new long[]{(Long) R.getStatic(R.cls(FEATURE), "drillFuelCur"),
                    (Long) R.getStatic(R.cls(FEATURE), "drillFuelMax")});
            c.check(fuel[0] == 1650 && fuel[1] == 3000, "Drill Fuel read " + fuel[0] + "/" + fuel[1] + ", want 1650/3000");
            c.check(overlay(c).contains("Drill Fuel"), "with Drill Fuel Text off the action bar lost its fuel: " + overlay(c));
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            BufferedImage shown = HudFixCases.shot(c, "fuel");
            BarPixels.Scene scene = BarPixels.scene(c);
            double[] fb = BarPixels.box(shown, FUEL, gs, scene);
            double[] hb = BarPixels.box(shown, HudFixCases.HEALTH, gs, scene);
            c.note("Drill Fuel " + show(fb) + ", Health " + show(hb));
            if (fb == null || hb == null) {
                c.problem("a bar did not draw (Drill Fuel " + show(fb) + ", Health " + show(hb) + ")");
                return;
            }
            double share = (fb[2] - fb[0] + 1) / (hb[2] - hb[0] + 1);
            c.check(share > 0.45 && share < 0.65, String.format(java.util.Locale.ROOT,
                    "the Drill Fuel Bar fill is %.2f of a full bar, want about 0.55 (1650/3000)", share));

            c.onClient(mc -> {
                Mod.call(Mod.cfg(PS), "setHideDrillFuelText", true);
                Mod.call(Mod.cfg(PS), "save");
                return null;
            });
            HudEditorCases.actionBar(c, FUEL_LINE);
            c.ticks(5);
            String hidden = overlay(c);
            c.note("hidden: " + hidden);
            c.check(!hidden.contains("Drill Fuel") && hidden.contains("1,000/1,000"),
                    "Drill Fuel Text did not take only the fuel off the action bar: " + hidden);
            c.check(c.onClient(mc -> (Long) R.getStatic(R.cls(FEATURE), "drillFuelCur")) == 1650,
                    "hiding the text stopped the bar reading the fuel");
            c.check(c.onClient(mc -> diskJson()).contains("\"hideDrillFuelText\":true"), "Drill Fuel Text not saved");

            HudEditorCases.actionBar(c, "§c1,000/1,000❤     §a100§a❈ Defense");
            c.ticks(5);
            c.check(c.onClient(mc -> (Long) R.getStatic(R.cls(FEATURE), "drillFuelCur")) == -1,
                    "a line without drill fuel left the reading set");
            BufferedImage gone = HudFixCases.shot(c, "fuel-gone");
            c.check(BarPixels.box(gone, FUEL, gs, BarPixels.scene(c)) == null, "the Drill Fuel Bar still draws with no drill");

            Screen ed = c.onClient(mc -> {
                try {
                    Screen s = (Screen) R.cls("hud.HudEditorScreen").getMethod("forBars", Screen.class)
                            .invoke(null, (Object) null);
                    McCompat.setScreen(mc, s);
                    return s;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            for (String want : new String[]{"Drill Fuel Bar", "Drill Fuel Color", "Drill Fuel Text"}) {
                c.check(c.onClient(mc -> seek(ed, want) != null), "the Edit Bars panel has no '" + want + "' row");
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                return null;
            });
            HudEditorCases.setWindow(c, window[0], window[1], gui);
            HudFixCases.restore(c, saved);
        }
    }

    private static final String MANA_LINE = "§c1,000/1,000❤     §b500/500✎ Mana";

    /**
     * 1104-ui-maniac-mana (mod maniac-1104; killer560, 2026-11-04: "on the main server maniac miner doesnt do a good job of
     * updating the mana bar ... use the predictive stuff to figure out how much mana it is using"). With the action bar
     * frozen at 500/500 mana: Maniac Miner's use line, then five server block changes under the crosshair with attack
     * held take 20 each off the Mana Bar (400, the bar drawn shorter); a change with attack released costs nothing; the
     * frozen figure printed again keeps 400; a new figure (450) wins; the expiry line stops the drain.
     */
    static void maniacMana(UiCase c) throws Exception {
        Map<Path, byte[]> saved = HudFixCases.snapshot(c, PS, HUD);
        int[] window = HudEditorCases.windowSize(c);
        int gui = c.onClient(mc -> mc.options.guiScale().get());
        boolean gate = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        Object mode = c.onClient(mc -> mc.gameMode == null ? null : mc.gameMode.getPlayerMode());
        try {
            HudEditorCases.setWindow(c, 854, 480, 2);
            setup(c);
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                mc.player.setXRot(90f);
                return null;
            });
            HudEditorCases.serverPlayer(c, sp -> sp.setGameMode(net.minecraft.world.level.GameType.ADVENTURE));
            HudEditorCases.actionBar(c, MANA_LINE);
            c.ticks(5);
            c.check(mana(c) == 500, "the action bar mana did not read 500: " + mana(c));
            BufferedImage full = HudFixCases.shot(c, "maniac-full");
            int gs = c.onClient(mc -> mc.getWindow().getGuiScale());
            double[] m0 = BarPixels.box(full, HudFixCases.MANA, gs, BarPixels.scene(c));

            HudEditorCases.serverPlayer(c, sp -> sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§aYou used your §6Maniac Miner §aPickaxe Ability!"), false));
            c.ticks(3);
            long until = c.onClient(mc -> (Long) R.getStatic(R.cls(FEATURE), "maniacUntil"));
            c.check(until > System.currentTimeMillis(), "the Maniac Miner use line did not start the drain");
            net.minecraft.core.BlockPos pos = c.onClient(mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult h
                    && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? h.getBlockPos() : null);
            c.note("aimed at " + pos);
            if (pos == null) {
                c.problem("the crosshair is on no block (looking down)");
                return;
            }
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(true);
                return null;
            });
            for (int i = 0; i < 5; i++) {
                net.minecraft.world.level.block.Block b = i % 2 == 0 ? net.minecraft.world.level.block.Blocks.COBBLESTONE
                        : net.minecraft.world.level.block.Blocks.STONE;
                HudEditorCases.serverPlayer(c, sp -> sp.level().setBlockAndUpdate(pos, b.defaultBlockState()));
                c.ticks(3);
            }
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                return null;
            });
            c.check(mana(c) == 400, "five blocks under Maniac Miner left mana at " + mana(c) + ", want 400 (5 x 20)");
            HudEditorCases.serverPlayer(c, sp -> sp.level().setBlockAndUpdate(pos,
                    net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState()));
            c.ticks(3);
            c.check(mana(c) == 400, "a block change with attack released cost mana: " + mana(c));
            HudEditorCases.actionBar(c, MANA_LINE);
            c.ticks(5);
            c.check(mana(c) == 400, "the frozen 500 printed again undid the drain: " + mana(c));
            BufferedImage drained = HudFixCases.shot(c, "maniac-drained");
            double[] m1 = BarPixels.box(drained, HudFixCases.MANA, gs, BarPixels.scene(c));
            c.note("Mana Bar " + show(m0) + " -> " + show(m1));
            c.check(m0 != null && m1 != null && (m1[2] - m1[0] + 1) / (m0[2] - m0[0] + 1) < 0.9
                    && (m1[2] - m1[0] + 1) / (m0[2] - m0[0] + 1) > 0.7,
                    "the drawn Mana Bar did not fall to about 80%: " + show(m0) + " -> " + show(m1));
            HudEditorCases.actionBar(c, "§c1,000/1,000❤     §b450/500✎ Mana");
            c.ticks(5);
            c.check(mana(c) == 450, "a new action bar figure did not win: " + mana(c));
            HudEditorCases.serverPlayer(c, sp -> sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§cYour Maniac Miner has expired!"), false));
            c.ticks(3);
            c.check(c.onClient(mc -> (Long) R.getStatic(R.cls(FEATURE), "maniacUntil")) == 0,
                    "the expiry line did not stop the drain");
        } finally {
            c.onClient(mc -> {
                mc.options.keyAttack.setDown(false);
                mc.player.setXRot(0f);
                R.setStatic(R.cls(FEATURE), "maniacUntil", 0L);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gate);
                return null;
            });
            if (mode instanceof net.minecraft.world.level.GameType g) {
                HudEditorCases.serverPlayer(c, sp -> sp.setGameMode(g));
            }
            HudEditorCases.setWindow(c, window[0], window[1], gui);
            HudFixCases.restore(c, saved);
        }
    }

    private static long mana(UiCase c) {
        return c.onClient(mc -> (Long) R.getStatic(R.cls(FEATURE), "manaCur"));
    }

    private static String overlay(UiCase c) {
        return c.onClient(mc -> {
            Object m = Mod.field(McCompat.hud(mc), "overlayMessageString");
            return m == null ? "(none)" : ((net.minecraft.network.chat.Component) m).getString();
        });
    }

    /** Stat Bars on, Custom layout, Health and Mana bars only at known spots, full readings, the panel on the right. */
    private static void setup(UiCase c) {
        c.onClient(mc -> {
            Object ps = Mod.cfg(PS);
            Mod.call(ps, "setEnabled", true);
            Mod.call(ps, "setPredefinedLayout", false);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(ps, "setReadoutOn", rr, false);
            }
            Mod.call(ps, "setReadoutOn", r("HEALTH_BAR"), true);
            Mod.call(ps, "setReadoutOn", r("MANA_BAR"), true);
            Mod.call(ps, "setReadoutColor", r("HEALTH_BAR"), HudFixCases.HEALTH);
            Mod.call(ps, "setReadoutColor", r("MANA_BAR"), HudFixCases.MANA);
            Mod.call(ps, "setBarBackground", HudFixCases.BACKGROUND);
            Mod.call(ps, "setBarShowValue", false);
            BarPixels.plain(ps);
            Mod.call(ps, "setBarWidth", 100);
            Mod.call(ps, "setBarHeight", 6);
            Mod.call(ps, "setMenuAdvancedOpen", true);
            Mod.call(ps, "setEditorPanel", 0);
            Mod.call(ps, "save");
            Object hud = Mod.cfg(HUD);
            for (Object rr : R.cls(READOUT).getEnumConstants()) {
                Mod.call(hud, "setScale", (String) R.get(rr, "hudId"), 1.0f);
            }
            Mod.call(hud, "setGlobalScale", 1.0f);
            Mod.call(hud, "setAutoScale", false);
            Mod.call(hud, "setPosition", "statbar_health", BX, HY);
            Mod.call(hud, "setPosition", "statbar_mana", BX, MY);
            Mod.call(hud, "save");
            Class<?> f = R.cls(FEATURE);
            R.setStatic(f, "healthCur", 1000L);
            R.setStatic(f, "healthMax", 1000L);
            R.setStatic(f, "manaCur", 1000L);
            R.setStatic(f, "manaMax", 1000L);
            McCompat.setScreen(mc, null);
            return null;
        });
        c.ticks(3);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object r(String name) {
        return Enum.valueOf((Class) R.cls(READOUT), name);
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> rows(Screen ed) {
        return (List<AbstractWidget>) R.get(ed, "panelWidgets");
    }

    private static String labels(Screen ed) {
        StringBuilder sb = new StringBuilder();
        for (AbstractWidget w : rows(ed)) {
            sb.append(net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString())).append(" | ");
        }
        return sb.toString();
    }

    private static AbstractWidget row(Screen ed, String prefix) {
        for (AbstractWidget w : rows(ed)) {
            String s = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
            if (s != null && s.startsWith(prefix)) {
                return w;
            }
        }
        return null;
    }

    /** The panel row starting with {@code prefix}, scrolling the panel with the wheel from its top until it is in view. */
    private static AbstractWidget seek(Screen ed, String prefix) {
        Object p = R.get(ed, "panel");
        double x = (Integer) R.get(p, "x") + 10;
        double y = (Integer) R.get(p, "y") + 40;
        for (int i = 0; i < 40; i++) {
            ed.mouseScrolled(x, y, 0, 1);
        }
        for (int i = 0; i < 60; i++) {
            AbstractWidget w = row(ed, prefix);
            if (w != null) {
                return w;
            }
            ed.mouseScrolled(x, y, 0, -1);
        }
        return null;
    }

    private static AbstractWidget hit(Screen ed, double x, double y) {
        for (AbstractWidget w : rows(ed)) {
            if (x >= w.getX() && x < w.getX() + w.getWidth() && y >= w.getY() && y < w.getY() + w.getHeight()) {
                return w;
            }
        }
        return null;
    }

    /** A press and release through the screen, as the mouse does. */
    private static void click(Screen ed, double x, double y) {
        var info = new MouseButtonInfo(dev.testkit.compat.McKeys.mouse(0), 0);
        ed.mouseClicked(new MouseButtonEvent(x, y, info), false);
        ed.mouseReleased(new MouseButtonEvent(x, y, info));
    }

    @SuppressWarnings("unchecked")
    private static int[] live(Screen ed, String id) {
        return ((Map<String, int[]>) R.get(ed, "livePositions")).get(id).clone();
    }

    private static String diskJson() {
        return read((Path) R.getStatic(R.cls(PS), "CONFIG_PATH")).replaceAll("\\s", "");
    }

    private static String diskHud() {
        return read((Path) R.getStatic(R.cls(HUD), "CONFIG_PATH")).replaceAll("\\s", "");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            return "unreadable " + p + ": " + e;
        }
    }

    private static String cut(String s, String key) {
        int i = s.indexOf(key);
        return i < 0 ? "(no " + key + ")" : s.substring(i, Math.min(s.length(), i + 120));
    }

    private static String show(double[] b) {
        return b == null ? "none" : String.format(java.util.Locale.ROOT, "%.0f,%.0f-%.0f,%.0f", b[0], b[1], b[2], b[3]);
    }

    /** Pixels that differ between two shots inside box {@code b} (pixels, inclusive). */
    private static int diff(BufferedImage a, BufferedImage b, double[] box) {
        if (box == null) {
            return 0;
        }
        int n = 0;
        for (int y = (int) box[1]; y <= (int) box[3] && y < a.getHeight(); y++) {
            for (int x = (int) box[0]; x <= (int) box[2] && x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }
}
