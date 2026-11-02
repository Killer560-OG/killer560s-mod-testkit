package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * killer560's to-do Doc items of 2026-11-01 (mod branch doc-small-1101), cases 1101-ds-*. Every case makes the feature
 * act and asserts what it did, not only that a setting saved:
 * <ul>
 *   <li>chat-history: 450 lines added under Off / Limited 400 / Unlimited leave 100 / 400 / 450 in ChatComponent's
 *       allMessages; the Limited slider is built only in Limited; mode and limit survive a reload.</li>
 *   <li>bugged (testing jars only): Mark bugged moves a tab into a Bugged category of the arranged menu, the marks file
 *       holds it, a reload keeps it, Mark tested takes it out; the row has both buttons.</li>
 *   <li>confine-mouse: the rule's truth table (never outside a world, Only In Dungeons, chat), the new row, persistence.</li>
 *   <li>held-item: the tab's Edit opens HeldItemEditScreen with the sliders on the left for a right-handed player;
 *       Swing Range scales a real swing (the swingArm hook's counter rises), and saves.</li>
 *   <li>dvd: a new box gets Level Up as its corner sound; 250% plays three layers of it; the tab builds Corner Volume
 *       and the On Screen Corner Text heading above the text box.</li>
 *   <li>edit-hud-bars: with Health and Mana Bars on, Edit HUD lists the bars as fixed (drawn, not draggable).</li>
 *   <li>ping-colors: 99 good, 100 and 149 okay, 150 and 199 bad, 200 awful.</li>
 *   <li>streamer (cheat jars): /streamer toggles Streamer Mode and says nothing in chat.</li>
 *   <li>tab-texts: Best Friends' sort row is white, Item Protection has no "saved by ID" line.</li>
 *   <li>chocolate: Hide Outside Hoppity's Hunt and Hide While Busy hide the egg HUD exactly when they should.</li>
 *   <li>nickhider: chat lines that name another player (friend join, party chat, PMs, lists...) add the name and the
 *       drawn text is replaced; [BOSS]/[NPC] lines and plain text add nothing.</li>
 *   <li>mage-beam: Fade and Rotating colors; a wide beam near the camera draws no core line (the darker middle), a thin
 *       far one does.</li>
 *   <li>commands: /killer560ah, /killer560bz, /killer560pv and /mage are not registered; /pv, /ah, /bz still are.</li>
 * </ul>
 */
final class DocSmall1101Cases {

    private DocSmall1101Cases() {
    }

    // ---- chat history ----------------------------------------------------------------------------------------------

    static void chatHistory(UiCase c) throws Exception {
        String cls = "chathistory.ChatHistoryConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        try {
            int off = fillChat(c, cls, "OFF", 400);
            int limited = fillChat(c, cls, "LIMITED", 400);
            int unlimited = fillChat(c, cls, "UNLIMITED", 400);
            c.note("450 lines added: Off kept " + off + ", Limited 400 kept " + limited + ", Unlimited kept " + unlimited);
            c.check(off == 100, "Off kept " + off + " messages, want vanilla's 100");
            c.check(limited == 400, "Limited (400) kept " + limited + " messages");
            c.check(unlimited == 450, "Unlimited kept " + unlimited + " of 450 messages");
            // The slider only in Limited, and the clamp/snap.
            List<String> offRows = rows(c, "gui.tab.ChatHistoryTab", cls, "OFF");
            List<String> limRows = rows(c, "gui.tab.ChatHistoryTab", cls, "LIMITED");
            c.note("Off rows " + offRows + "; Limited rows " + limRows);
            c.check(offRows.stream().noneMatch(l -> l.startsWith("History Limit")), "the slider shows in Off");
            c.check(limRows.stream().anyMatch(l -> l.startsWith("History Limit")), "no History Limit slider in Limited");
            c.check(limRows.stream().anyMatch(l -> l.startsWith("Chat History: Limited")), "mode row: " + limRows);
            Object[] clamp = c.onClient(mc -> {
                Object cfg = Mod.cfg(cls);
                Mod.call(cfg, "setCustomLimit", 50);
                int lo = (Integer) Mod.call(cfg, "getCustomLimit");
                Mod.call(cfg, "setCustomLimit", 99999);
                int hi = (Integer) Mod.call(cfg, "getCustomLimit");
                Mod.call(cfg, "setCustomLimit", 1290);
                int snap = (Integer) Mod.call(cfg, "getCustomLimit");
                Mod.call(cfg, "setMode", Mod.enumValue(cls + "$Mode", "LIMITED"));
                Mod.call(cfg, "save");
                Mod.staticCall(cls, "load");
                Object back = Mod.cfg(cls);
                return new Object[]{lo, hi, snap, String.valueOf(Mod.call(back, "getMode")), Mod.call(back, "getCustomLimit")};
            });
            c.note("limit 50 -> " + clamp[0] + ", 99999 -> " + clamp[1] + ", 1290 -> " + clamp[2] + "; reload: "
                    + clamp[3] + " " + clamp[4]);
            c.check((Integer) clamp[0] == 200 && (Integer) clamp[1] == 10000 && (Integer) clamp[2] == 1200,
                    "clamp/snap: " + clamp[0] + ", " + clamp[1] + ", " + clamp[2]);
            c.check("LIMITED".equals(clamp[3]) && Integer.valueOf(1200).equals(clamp[4]), "after a reload: " + clamp[3] + " " + clamp[4]);
            // An old file with only "unlimited": true still loads as Unlimited.
            String legacy = c.onClient(mc -> {
                try {
                    Path p = DungeonB2Cases.snapshotConfig(c, cls).keySet().iterator().next();
                    Files.writeString(p, "{\"unlimited\": true}");
                    Mod.staticCall(cls, "load");
                    return String.valueOf(Mod.call(Mod.cfg(cls), "getMode"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            c.check("UNLIMITED".equals(legacy), "a file from before Limited loads as " + legacy);
        } finally {
            c.onClient(mc -> {
                McCompat.chat(mc).clearMessages(false);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    private static int fillChat(UiCase c, String cls, String mode, int limit) {
        return c.onClient(mc -> {
            Object cfg = Mod.cfg(cls);
            Mod.call(cfg, "setMode", Mod.enumValue(cls + "$Mode", mode));
            Mod.call(cfg, "setCustomLimit", limit);
            McCompat.chat(mc).clearMessages(false);
            for (int i = 0; i < 450; i++) {
                McCompat.chat(mc).addClientSystemMessage(Component.literal("doc-small chat history line " + i));
            }
            return ((List<?>) Mod.field(McCompat.chat(mc), "allMessages")).size();
        });
    }

    /** The labels of a tab's rows after setting {@code cls}'s mode. */
    private static List<String> rows(UiCase c, String tabCls, String cls, String mode) {
        return c.onClient(mc -> {
            Mod.call(Mod.cfg(cls), "setMode", Mod.enumValue(cls + "$Mode", mode));
            return labels(build(tabCls));
        });
    }

    // ---- bugged category (testing jars) -----------------------------------------------------------------------------

    static void bugged(UiCase c) throws Exception {
        boolean testing = (Boolean) Mod.field("BuildVariant", "TESTING");
        if (!testing) {
            c.note("not a testing jar: the Bugged category is testing-build only, nothing to check here");
            c.check(!Mod.has("testing.TestedFeatures"), "a non-testing jar carries testing.TestedFeatures");
            return;
        }
        Path marks = c.onClient(mc -> (Path) Mod.staticCall("testing.TestedFeatures", "marksFile"));
        byte[] before = Files.exists(marks) ? Files.readAllBytes(marks) : null;
        try {
            Object tab = c.onClient(mc -> newTab("gui.tab.ChatHistoryTab"));
            List<String> row = c.onClient(mc -> {
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.staticCall("testing.TestingMenu", "markButtons", tab, 0, 0, 300);
                return labels(ws);
            });
            c.note("mark row: " + row);
            c.check(row.contains("Mark tested") && row.contains("Mark bugged"), "the mark row is " + row);
            Object[] after = c.onClient(mc -> {
                Mod.staticCall("testing.TestedFeatures", "markBugged", tab, true);
                boolean b = (Boolean) Mod.staticCall("testing.TestedFeatures", "isBugged", tab);
                boolean t = (Boolean) Mod.staticCall("testing.TestedFeatures", "isTested", tab);
                List<?> arranged = (List<?>) Mod.staticCall("testing.TestingMenu", "arrange", List.of(tab));
                List<String> names = new ArrayList<>();
                List<String> inBugged = new ArrayList<>();
                for (Object top : arranged) {
                    String n = String.valueOf(Mod.field(top, "name"));
                    names.add(n);
                    if (n.equals("Bugged")) {
                        for (Object sub : (List<?>) Mod.call(top, "subTabs")) {
                            inBugged.add(sub.getClass().getSimpleName());
                        }
                    }
                }
                return new Object[]{b, t, names, inBugged};
            });
            c.note("marked bugged: isBugged " + after[0] + ", isTested " + after[1] + ", categories " + after[2] + ", Bugged holds " + after[3]);
            c.check(Boolean.TRUE.equals(after[0]) && Boolean.FALSE.equals(after[1]), "bugged/tested: " + after[0] + "/" + after[1]);
            c.check(((List<?>) after[3]).contains("ChatHistoryTab"), "the Bugged category does not hold the tab: " + after[2]);
            String file = Files.readString(marks);
            c.check(file.contains("\"bugged\"") && file.contains("ChatHistoryTab"), "marks file: " + file);
            boolean reread = c.onClient(mc -> {
                Mod.staticCall("testing.TestedFeatures", "reload");
                return (Boolean) Mod.staticCall("testing.TestedFeatures", "isBugged", tab);
            });
            c.check(reread, "the bugged mark did not survive a reload");
            boolean cleared = c.onClient(mc -> {
                Mod.staticCall("testing.TestedFeatures", "mark", tab, true);
                return !(Boolean) Mod.staticCall("testing.TestedFeatures", "isBugged", tab)
                        && (Boolean) Mod.staticCall("testing.TestedFeatures", "isTested", tab);
            });
            c.check(cleared, "Mark tested did not take the tab out of Bugged");
        } finally {
            if (before != null) {
                Files.write(marks, before);
            } else {
                Files.deleteIfExists(marks);
            }
            c.onClient(mc -> Mod.staticCall("testing.TestedFeatures", "reload"));
        }
    }

    // ---- confine mouse ------------------------------------------------------------------------------------------------

    static void confineMouse(UiCase c) throws Exception {
        String f = "window.ConfineMouseFeature";
        // enabled, borderless, active, inWorld, onlyInDungeons, inDungeon, screenOpen, chatOpen -> want
        Object[][] table = {
                {true, true, true, true, false, false, true, false, true},     // a chest in game
                {true, true, true, false, false, false, true, false, false},   // the title screen / server list
                {true, true, true, true, false, false, true, true, false},     // chat
                {true, true, true, true, false, false, false, false, false},   // no screen
                {true, true, true, true, true, false, true, false, false},     // Only In Dungeons, not in one
                {true, true, true, true, true, true, true, false, true},       // Only In Dungeons, in one
                {false, true, true, true, false, false, true, false, false},   // off
                {true, false, true, true, false, false, true, false, false},   // not borderless
                {true, true, false, true, false, false, true, false, false}};  // alt-tabbed
        List<String> wrong = new ArrayList<>();
        for (Object[] r : table) {
            boolean got = c.onClient(mc -> (Boolean) Mod.staticCall(f, "decide", r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]));
            if (got != (Boolean) r[8]) {
                wrong.add(java.util.Arrays.toString(r) + " -> " + got);
            }
        }
        c.note(table.length + " rows of the confine rule, wrong: " + wrong);
        c.check(wrong.isEmpty(), "confine rule: " + wrong);
        String cls = "window.ConfineMouseConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        try {
            List<String> rows = c.onClient(mc -> labels(build("gui.tab.DisplayOptionsTab")));
            c.check(rows.stream().anyMatch(l -> l.startsWith("Only In Dungeons")), "Display Options rows: " + rows);
            boolean back = c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "setOnlyInDungeons", true);
                Mod.call(Mod.cfg(cls), "save");
                Mod.staticCall(cls, "load");
                return (Boolean) Mod.call(Mod.cfg(cls), "isOnlyInDungeons");
            });
            c.check(back, "Only In Dungeons did not survive a reload");
            // In this world with a screen open and the setting on, the real rule still refuses outside a dungeon.
            boolean live = c.onClient(mc -> (Boolean) Mod.staticCall(f, "shouldConfine", mc));
            c.check(!live, "confines with Only In Dungeons on outside a dungeon");
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    // ---- held item -----------------------------------------------------------------------------------------------------

    static void heldItem(UiCase c) throws Exception {
        String cls = "helditem.HeldItemConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try {
            c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "setEnabled", true);
                return null;
            });
            List<AbstractWidget> tab = c.onClient(mc -> build("gui.tab.HeldItemTab"));
            List<String> rows = labels(tab);
            AbstractWidget edit = null;
            for (AbstractWidget w : tab) {
                if (ModScreenDriver.label(w).equals("Edit")) {
                    edit = w;
                }
            }
            c.check(edit != null, "no Edit button: " + rows);
            c.check(rows.stream().anyMatch(l -> l.startsWith("Swing Range")), "no Swing Range slider: " + rows);
            c.check(edit == null || tab.indexOf(edit) <= 2, "Edit is not on the switch's row");
            if (edit != null) {
                AbstractWidget e = edit;
                c.onClient(mc -> ModScreenDriver.press(e));
                c.ticks(3);
                Object[] st = c.onClient(mc -> {
                    Screen s = McCompat.screen(mc);
                    return new Object[]{s == null ? "none" : s.getClass().getSimpleName(),
                            s == null ? null : Mod.call(s, "isPanelLeft"), s == null ? null : Mod.call(s, "themedPanelBounds"),
                            s == null ? 0 : s.width};
                });
                int[] bounds = (int[]) st[2];
                c.note("Edit opened " + st[0] + ", sliders on the " + (Boolean.TRUE.equals(st[1]) ? "left" : "right")
                        + (bounds == null ? "" : " at x " + bounds[0] + " w " + bounds[2] + " of " + st[3]));
                c.check("HeldItemEditScreen".equals(st[0]), "Edit opened " + st[0]);
                boolean rightHanded = c.onClient(mc -> mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT);
                c.check(Boolean.valueOf(rightHanded).equals(st[1]), "panel side " + st[1] + " for a "
                        + (rightHanded ? "right" : "left") + "-handed player");
                c.check(bounds != null && (rightHanded ? bounds[0] < (Integer) st[3] / 2 : bounds[0] > (Integer) st[3] / 2),
                        "panel bounds on the wrong side");
                boolean leftSide = c.onClient(mc -> (Boolean) Mod.staticCall("helditem.HeldItemEditScreen", "panelOnLeft",
                        net.minecraft.world.entity.HumanoidArm.LEFT));
                c.check(!leftSide, "a left-handed player gets the panel on the left");
                c.onClient(mc -> {
                    McCompat.setScreen(mc, null);
                    return null;
                });
            }
            // Swing Range acts: a real swing with range 2, holding an item (an empty hand is drawn by renderPlayerArm,
            // not swingArm), with Skyblock Only off (isActive includes it), goes through the swingArm hook.
            int before = c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
                Mod.call(Mod.cfg(cls), "setSwingRange", 2.0f);
                Mod.call(Mod.cfg(cls), "setNoSwing", false);
                return (Integer) Mod.field(cls, "swingRangeApplied");
            });
            for (int i = 0; i < 3; i++) {
                c.onClient(mc -> {
                    if (mc.player.getMainHandItem().isEmpty()) {
                        mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
                    }
                    McCompat.swingArm(mc.player);
                    return null;
                });
                c.ticks(4);
            }
            int after = c.onClient(mc -> (Integer) Mod.field(cls, "swingRangeApplied"));
            c.note("Swing Range 2.0: swingArm hook scaled " + (after - before) + " frames over 3 swings");
            c.check(after > before, "Swing Range never reached the swing (hook count " + before + " -> " + after + ")");
            float back = c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "save");
                Mod.staticCall(cls, "load");
                return (Float) Mod.call(Mod.cfg(cls), "getSwingRange");
            });
            c.check(back == 2.0f, "Swing Range after a reload: " + back);
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    // ---- DVD -----------------------------------------------------------------------------------------------------------

    static void dvd(UiCase c) throws Exception {
        String cls = "dvd.DvdConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        try {
            Object[] r = c.onClient(mc -> {
                Object e = Mod.call(Mod.cfg(cls), "addNew");
                String sound = String.valueOf(Mod.field(e, "cornerHitSoundFile"));
                String label = String.valueOf(Mod.staticCall("dvd.DvdFeature", "soundLabel", sound));
                float[] layers = (float[]) Mod.staticCall("dvd.DvdFeature", "layers", 2.5f);
                int n0 = (Integer) Mod.staticCall("dvd.DvdFeature", "cornerSoundLayers");
                Mod.staticCall("dvd.DvdFeature", "playBuiltin", sound, 2.5f);
                return new Object[]{sound, label, java.util.Arrays.toString(layers), n0};
            });
            c.ticks(3);
            int n1 = c.onClient(mc -> (Integer) Mod.staticCall("dvd.DvdFeature", "cornerSoundLayers"));
            c.note("new box: corner sound " + r[0] + " (" + r[1] + "), 250% layers " + r[2] + ", copies played " + (n1 - (Integer) r[3]));
            c.check("builtin:LEVEL_UP".equals(r[0]) && "Level Up".equals(r[1]), "a new box's corner sound is " + r[0]);
            c.check("[1.0, 1.0, 0.5]".equals(r[2]), "250% layers " + r[2]);
            c.check(n1 - (Integer) r[3] == 3, "250% played " + (n1 - (Integer) r[3]) + " copies, want 3");
            // The edit page: open the new box's sound step through the tab's own state.
            List<String> page = c.onClient(mc -> {
                Object tab = newTab("gui.tab.DvdTab");
                List<?> entries = (List<?>) Mod.call(Mod.cfg(cls), "entries");
                Object e = entries.get(entries.size() - 1);
                setInstance(tab, "editingId", Mod.field(e, "id"));
                setInstance(tab, "step", Mod.enumValue("gui.tab.DvdTab$Step", "FINAL_SETTINGS"));
                @SuppressWarnings("unchecked")
                List<AbstractWidget> ws = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
                return labels(ws);
            });
            c.note("DVD sound page rows: " + page);
            int vol = indexOfPrefix(page, "Corner Volume: ");
            int head = indexOfPrefix(page, "On Screen Corner Text");
            c.check(vol >= 0, "no Corner Volume slider: " + page);
            c.check(head >= 0, "no On Screen Corner Text heading: " + page);
            c.check(indexOfPrefix(page, "Corner Sound: ") >= 0 && page.get(indexOfPrefix(page, "Corner Sound: ")).contains("Level Up"),
                    "Corner Sound row: " + page);
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    // ---- Edit HUD bars -------------------------------------------------------------------------------------------------

    static void editHudBars(UiCase c) throws Exception {
        String cls = "playerstats.PlayerStatsConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        try {
            Object[] r = c.onClient(mc -> {
                Object cfg = Mod.cfg(cls);
                Mod.call(cfg, "setEnabled", true);
                for (String rd : List.of("HEALTH_BAR", "MANA_BAR")) {
                    Object r0 = Mod.enumValue("playerstats.StatElements$Readout", rd);
                    Mod.call(cfg, "setReadoutOn", r0, true);
                    Mod.call(cfg, "setArea", r0, Mod.enumValue("playerstats.StatLayout$Area", "ABOVE_HOTBAR"));
                }
                try {
                    Screen s = (Screen) Mod.cls("hud.HudEditorScreen").getConstructor(Screen.class).newInstance((Screen) null);
                    McCompat.setScreen(mc, s);
                    @SuppressWarnings("unchecked")
                    List<String> fixed = (List<String>) Mod.call(s, "fixedBarIds");
                    List<String> shown = new ArrayList<>();
                    for (Object e : (List<?>) Mod.field(s, "shown")) {
                        shown.add(String.valueOf(Mod.call(e, "id")));
                    }
                    return new Object[]{fixed, shown};
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ticks(3);
            @SuppressWarnings("unchecked")
            List<String> fixed = (List<String>) r[0];
            @SuppressWarnings("unchecked")
            List<String> shown = (List<String>) r[1];
            c.note("Edit HUD: fixed bars " + fixed + "; draggable " + shown.size());
            c.check(!fixed.isEmpty(), "Edit HUD shows no bar with Health and Mana Bars on");
            for (String id : fixed) {
                c.check(!shown.contains(id), id + " is draggable in Edit HUD");
                boolean stat = c.onClient(mc -> (Boolean) Mod.staticCall("playerstats.StatElements", "isStatElementId", id));
                c.check(stat, id + " is not a stat bar");
            }
        } finally {
            c.onClient(mc -> {
                McCompat.setScreen(mc, null);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    // ---- ping colors ---------------------------------------------------------------------------------------------------

    static void pingColors(UiCase c) {
        String el = "lagdisplay.LagDisplayFeature$LagHudElement";
        Map<Integer, String> want = new LinkedHashMap<>();
        want.put(0, "COLOR_GOOD");
        want.put(99, "COLOR_GOOD");
        want.put(100, "COLOR_OK");
        want.put(149, "COLOR_OK");
        want.put(150, "COLOR_BAD");
        want.put(199, "COLOR_BAD");
        want.put(200, "COLOR_AWFUL");
        want.put(450, "COLOR_AWFUL");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Integer, String> e : want.entrySet()) {
            Object[] got = c.onClient(mc -> new Object[]{Mod.staticCall(el, "pingColor", e.getKey()), Mod.field(el, e.getValue())});
            if (!got[0].equals(got[1])) {
                wrong.add(e.getKey() + " ms -> " + Integer.toHexString((Integer) got[0]) + " (want " + e.getValue() + ")");
            }
        }
        c.note(want.size() + " pings checked against the four colors, wrong: " + wrong);
        c.check(wrong.isEmpty(), "ping colors: " + wrong);
    }

    // ---- streamer mode -------------------------------------------------------------------------------------------------

    static void streamer(UiCase c) {
        if (!Mod.isCheat()) {
            c.check(!Mod.has("streamer.StreamerCommands") || !commandRoots(c).contains("streamer"), "legit jar registers /streamer");
            c.note("legit jar: Streamer Mode is cheat-only");
            return;
        }
        boolean was = c.onClient(mc -> (Boolean) Mod.staticCall("streamer.StreamerMode", "active"));
        long mark = LogTap.mark();
        c.onClient(mc -> {
            mc.player.connection.sendCommand("streamer");
            return null;
        });
        c.ticks(5);
        boolean now = c.onClient(mc -> (Boolean) Mod.staticCall("streamer.StreamerMode", "active"));
        c.onClient(mc -> {
            mc.player.connection.sendCommand("streamer");
            return null;
        });
        c.ticks(5);
        boolean restored = c.onClient(mc -> (Boolean) Mod.staticCall("streamer.StreamerMode", "active"));
        List<String> chat = new ArrayList<>();
        boolean logged = false;
        for (String line : LogTap.since(mark)) {
            if (line.contains("[CHAT]") && line.contains("Streamer Mode")) {
                chat.add(line);
            }
            if (line.contains("[Streamer Mode]") && line.contains("by command")) {
                logged = true;
            }
        }
        c.note("/streamer: " + was + " -> " + now + " -> " + restored + "; chat lines about it: " + chat.size() + "; logged " + logged);
        c.check(now != was && restored == was, "/streamer did not toggle: " + was + " -> " + now + " -> " + restored);
        c.check(chat.isEmpty(), "/streamer still says in chat: " + chat);
        c.check(logged, "the toggle was not logged");
    }

    // ---- small texts ---------------------------------------------------------------------------------------------------

    static void tabTexts(UiCase c) throws Exception {
        Map<Path, byte[]> bfSaved = DungeonB2Cases.snapshotConfig(c, "social.BestFriendsConfig");
        List<String> raw;
        try {
            raw = c.onClient(mc -> {
            Mod.call(Mod.cfg("social.BestFriendsConfig"), "setEnabled", true);
            List<String> out = new ArrayList<>();
            for (AbstractWidget w : build("gui.tab.BestFriendsTab")) {
                out.add(w.getMessage().getString());
            }
            return out;
        });
        } finally {
            DungeonB2Cases.restoreConfig(c, bfSaved, "social.BestFriendsConfig");
        }
        String sort = raw.stream().filter(l -> l.startsWith("Sort:")).findFirst().orElse(null);
        String screenSort = c.onClient(mc -> {
            Object mode = Mod.call(Mod.cfg("social.BestFriendsConfig"), "getSortMode");
            try {
                java.lang.reflect.Method m = Mod.cls("social.BestFriendsScreen").getDeclaredMethod("sortText", mode.getClass());
                m.setAccessible(true);
                return String.valueOf(m.invoke(null, mode));
            } catch (ReflectiveOperationException e) {
                return "(no sortText: " + e + ")";
            }
        });
        c.note("Best Friends sort row '" + sort + "', screen '" + screenSort + "'");
        c.check(sort != null && sort.contains("§f") && !sort.contains("§b"), "Best Friends sort row: " + sort);
        c.check(!screenSort.contains("§b"), "Best Friends screen sort label: " + screenSort);
        String ip = "itemprotect.ItemProtectConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, ip);
        try {
            List<String> rows = c.onClient(mc -> {
                try {
                    Mod.call(Mod.cfg(ip), "setEnabled", true);
                    Mod.call(Mod.cfg(ip), "setProtectItemEnabled", true); // the line sat in this section
                } catch (AssertionError ignored) {
                    // a config without setEnabled: the rows build regardless
                }
                return labels(build("gui.tab.ItemProtectTab"));
            });
            c.note("Item Protection rows: " + rows.size());
            c.check(rows.size() > 3, "Item Protection built " + rows.size() + " rows - nothing to judge");
            c.check(rows.stream().noneMatch(l -> l.toLowerCase(java.util.Locale.ROOT).contains("saved by id")),
                    "Item Protection still shows 'Items saved by ID'");
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, ip);
        }
    }

    // ---- chocolate factory ---------------------------------------------------------------------------------------------

    static void chocolate(UiCase c) throws Exception {
        String cls = "chocolatefactory.ChocolateFactoryConfig";
        String eggs = "chocolatefactory.egg.HoppityEggs";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        boolean simWas = c.onClient(mc -> (Boolean) Mod.staticCall("secrets.DungeonState", "isSimOverrideActive"));
        try {
            Object[] r = c.onClient(mc -> {
                Object cfg = Mod.cfg(cls);
                long now = System.currentTimeMillis();
                // A moment outside the hunt and one inside it, found from the calendar itself.
                long outside = -1;
                long inside = -1;
                for (long t = now; t < now + 400L * 24 * 3600 * 1000 && (outside < 0 || inside < 0); t += 3600_000L) {
                    boolean active = (Boolean) Mod.staticCall("chocolatefactory.egg.HoppityCalendar", "huntActive", t);
                    if (active && inside < 0) {
                        inside = t;
                    }
                    if (!active && outside < 0) {
                        outside = t;
                    }
                }
                Mod.call(cfg, "setHideWhileBusy", false);
                Mod.call(cfg, "setHideOutsideHunt", false);
                boolean offOut = (Boolean) Mod.staticCall(eggs, "hiddenNow", cfg, outside);
                Mod.call(cfg, "setHideOutsideHunt", true);
                boolean onOut = (Boolean) Mod.staticCall(eggs, "hiddenNow", cfg, outside);
                boolean onIn = (Boolean) Mod.staticCall(eggs, "hiddenNow", cfg, inside);
                Mod.call(cfg, "setHideOutsideHunt", false);
                Mod.call(cfg, "setHideWhileBusy", true);
                boolean busyOutside = (Boolean) Mod.staticCall(eggs, "hiddenNow", cfg, inside);
                return new Object[]{outside, inside, offOut, onOut, onIn, busyOutside};
            });
            c.note("hunt: outside " + r[0] + " inside " + r[1] + "; hidden: switch off/outside " + r[2] + ", on/outside " + r[3]
                    + ", on/inside " + r[4] + ", busy switch on, not in a dungeon " + r[5]);
            c.check((Long) r[0] > 0 && (Long) r[1] > 0, "could not find a moment in and out of the hunt");
            c.check(!(Boolean) r[2] && (Boolean) r[3] && !(Boolean) r[4], "Hide Outside Hoppity's Hunt: " + r[2] + "/" + r[3] + "/" + r[4]);
            c.check(!(Boolean) r[5], "Hide While Busy hid outside a dungeon");
            boolean inDungeon = c.onClient(mc -> {
                if (!(Boolean) Mod.staticCall("secrets.DungeonState", "isSimOverrideActive")) {
                    Mod.staticCall("secrets.DungeonState", "toggleSimOverride");
                }
                return (Boolean) Mod.staticCall("secrets.DungeonState", "isInDungeon");
            });
            boolean busyIn = c.onClient(mc -> (Boolean) Mod.staticCall(eggs, "hiddenNow", Mod.cfg(cls), (Long) r[1]));
            c.note("in a (sim override) dungeon " + inDungeon + ": Hide While Busy hides " + busyIn);
            c.check(inDungeon, "the sim override did not put the client in a dungeon - Hide While Busy untested");
            c.check(busyIn, "Hide While Busy did not hide in a dungeon");
            List<String> rows = c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "setEnabled", true);
                Mod.call(Mod.cfg(cls), "setEggHud", true);
                return labels(build("gui.tab.ChocolateFactoryTab"));
            });
            c.check(rows.stream().anyMatch(l -> l.startsWith("Hide Outside Hoppity's Hunt")) && rows.stream().anyMatch(l -> l.startsWith("Hide While Busy")),
                    "Chocolate Factory rows: " + rows);
            boolean back = c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "save");
                Mod.staticCall(cls, "load");
                return (Boolean) Mod.call(Mod.cfg(cls), "isHideWhileBusy");
            });
            c.check(back, "Hide While Busy did not survive a reload");
        } finally {
            c.onClient(mc -> {
                if ((Boolean) Mod.staticCall("secrets.DungeonState", "isSimOverrideActive") != simWas) {
                    Mod.staticCall("secrets.DungeonState", "toggleSimOverride");
                }
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    // ---- nickhider -----------------------------------------------------------------------------------------------------

    static void nickhider(UiCase c) throws Exception {
        String names = "namechanger.ChatNames";
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put("Friend > DsFriendA joined.", List.of("DsFriendA"));
        lines.put("Guild > [MVP+] DsGuildB left.", List.of("DsGuildB"));
        lines.put("Guild > [MVP++] DsGuildC [Officer]: hello", List.of("DsGuildC"));
        lines.put("Party > [VIP+] DsPartyD: hi", List.of("DsPartyD"));
        lines.put("Co-op > DsCoopE: ok", List.of("DsCoopE"));
        lines.put("From [MVP+] DsFromF: yo", List.of("DsFromF"));
        lines.put("To DsToG: k", List.of("DsToG"));
        lines.put("[312] ⚡ [MVP+] DsAllH: wts", List.of("DsAllH"));
        lines.put("[VIP] DsInvI invited [MVP+] DsInvJ to the party! They have 60 seconds to accept.", List.of("DsInvI", "DsInvJ"));
        lines.put("The party was transferred to DsTrK by [VIP] DsTrL", List.of("DsTrK", "DsTrL"));
        lines.put("Party Members: [MVP+] DsMemM ● DsMemN ●", List.of("DsMemM", "DsMemN"));
        lines.put("DsLeftO has left the party.", List.of("DsLeftO"));
        lines.put("You are now friends with [MVP] DsNewP", List.of("DsNewP"));
        lines.put("Party Finder > DsPfQ joined the dungeon group! (Mage Level 50)", List.of("DsPfQ"));
        lines.put("[BOSS] Maxor: hello", List.of());
        lines.put("[NPC] Mort: Good luck.", List.of());
        lines.put("Your Implosion hit 3 enemies for 1,000 damage.", List.of());
        lines.put("Party > hi", List.of());
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : lines.entrySet()) {
            @SuppressWarnings("unchecked")
            List<String> got = c.onClient(mc -> (List<String>) Mod.staticCall(names, "namesIn", e.getKey()));
            if (!got.equals(e.getValue())) {
                wrong.add("'" + e.getKey() + "' -> " + got + " (want " + e.getValue() + ")");
            }
        }
        c.note(lines.size() + " chat lines parsed, wrong: " + wrong);
        c.check(wrong.isEmpty(), "chat names: " + wrong);
        String cfgCls = "namechanger.NameChangerConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cfgCls);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try {
            int before = c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                Mod.call(Mod.cfg(cfgCls), "setRandomizeOthers", true);
                return (Integer) Mod.staticCall(names, "namesAdded");
            });
            // A real chat line, through the chat window, the way Hypixel's arrives.
            c.onClient(mc -> {
                McCompat.chat(mc).addClientSystemMessage(Component.literal("Friend > DsLiveFriend joined."));
                return null;
            });
            c.ticks(3);
            Object[] r = c.onClient(mc -> new Object[]{
                    Mod.staticCall(names, "namesAdded"),
                    Mod.staticCall("namechanger.NameChangerFeature", "fakeNameFor", "DsLiveFriend"),
                    Mod.staticCall("namechanger.NameReplacer", "replace", "Friend > DsLiveFriend joined.")});
            c.note("chat line added " + ((Integer) r[0] - before) + " name(s); DsLiveFriend shows as " + r[1] + "; drawn '" + r[2] + "'");
            c.check((Integer) r[0] > before, "the friend join line added no name");
            c.check(r[1] != null && !"DsLiveFriend".equals(r[1]), "no fake name for DsLiveFriend: " + r[1]);
            c.check(!String.valueOf(r[2]).contains("DsLiveFriend") && String.valueOf(r[2]).contains(String.valueOf(r[1])),
                    "the drawn line still shows the real name: " + r[2]);
        } finally {
            c.onClient(mc -> {
                McCompat.chat(mc).clearMessages(false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, cfgCls);
        }
    }

    // ---- mage beam -----------------------------------------------------------------------------------------------------

    static void mageBeam(UiCase c) throws Exception {
        String f = "dungeonextras.MageBeamFeature";
        String cls = "dungeonextras.DungeonExtrasConfig";
        Object[] colors = c.onClient(mc -> new Object[]{
                Mod.staticCall(f, "colorAt", 0xFF0000, 0x0000FF, 1, 0f, 0L),
                Mod.staticCall(f, "colorAt", 0xFF0000, 0x0000FF, 1, 1f, 0L),
                Mod.staticCall(f, "colorAt", 0xFF0000, 0x0000FF, 2, 0f, 0L),
                Mod.staticCall(f, "colorAt", 0xFF0000, 0x0000FF, 2, 0f, 500L),
                Mod.staticCall(f, "colorAt", 0xFF0000, 0x0000FF, 0, 1f, 0L)});
        float[] fadeStart = (float[]) colors[0];
        float[] fadeEnd = (float[]) colors[1];
        float[] rot0 = (float[]) colors[2];
        float[] rot1 = (float[]) colors[3];
        float[] solidEnd = (float[]) colors[4];
        c.note("fade start " + java.util.Arrays.toString(fadeStart) + " end " + java.util.Arrays.toString(fadeEnd)
                + "; rotating t0 " + java.util.Arrays.toString(rot0) + " t500 " + java.util.Arrays.toString(rot1));
        c.check(fadeStart[0] == 1f && fadeStart[2] == 0f && fadeEnd[0] == 0f && fadeEnd[2] == 1f, "Fade does not run start to end");
        c.check(!java.util.Arrays.equals(rot0, rot1), "Rotating does not change over time");
        c.check(solidEnd[0] == 1f && solidEnd[2] == 0f, "Solid changed along the beam");
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        try {
            int[] wide = beamDraws(c, cls, f, 20f, 3.0);
            int[] thin = beamDraws(c, cls, f, 1f, 160.0);
            c.note("beam 1 block wide, 3 blocks away: body " + wide[0] + " frames, core " + wide[1]
                    + "; 5 cm wide, 160 blocks away: body " + thin[0] + ", core " + thin[1]);
            c.check(wide[0] > 0, "the wide beam was never drawn");
            c.check(wide[1] == 0, "the wide near beam still drew the core line (the darker middle) " + wide[1] + " times");
            c.check(thin[0] > 0 && thin[1] > 0, "the thin far beam drew no core line (body " + thin[0] + ", core " + thin[1] + ")");
            boolean back = c.onClient(mc -> {
                Mod.call(Mod.cfg(cls), "setMageBeamColorMode", 2);
                Mod.call(Mod.cfg(cls), "save");
                Mod.staticCall(cls, "load");
                return ((Integer) Mod.call(Mod.cfg(cls), "getMageBeamColorMode")) == 2;
            });
            c.check(back, "Color Mode did not survive a reload");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                ((List<?>) Mod.field(f, "BEAMS")).clear();
                return null;
            });
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }

    /** Draws one beam {@code dist} blocks in front of the player at {@code width} units for a few frames: {body, core}. */
    private static int[] beamDraws(UiCase c, String cls, String f, float width, double dist) {
        int[] before = c.onClient(mc -> {
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            Object cfg = Mod.cfg(cls);
            Mod.call(cfg, "setMageBeamEnabled", true);
            Mod.call(cfg, "setMageBeamFade", false);
            Mod.call(cfg, "setMageBeamWidth", width);
            Mod.call(cfg, "setMageBeamShape", 1);
            Mod.call(cfg, "setMageBeamDurationTicks", 100);
            ((List<?>) Mod.field(f, "BEAMS")).clear();
            Vec3 eye = mc.player.getEyePosition();
            Vec3 look = mc.player.getLookAngle();
            Vec3 side = new Vec3(-look.z, 0, look.x).normalize();
            Vec3 center = eye.add(look.scale(dist));
            for (int i = 0; i < 8; i++) {
                Mod.staticCall(f, "onPoint", center.add(side.scale((i - 4) * 0.4)));
            }
            return (int[]) Mod.staticCall(f, "drawCounts");
        });
        c.ticks(6);
        int[] after = c.onClient(mc -> (int[]) Mod.staticCall(f, "drawCounts"));
        return new int[]{after[0] - before[0], after[1] - before[1]};
    }

    // ---- commands ------------------------------------------------------------------------------------------------------

    static void commands(UiCase c) {
        List<String> roots = commandRoots(c);
        c.note(roots.size() + " command roots registered");
        c.check(roots.size() > 30, "only " + roots.size() + " roots - the dispatcher was not read");
        for (String gone : List.of("killer560ah", "killer560bz", "killer560pv", "mage")) {
            c.check(!roots.contains(gone), "/" + gone + " is still registered");
        }
        for (String kept : List.of("pv", "ah", "bz", "killer560")) {
            c.check(roots.contains(kept), "/" + kept + " is gone");
        }
    }

    private static List<String> commandRoots(UiCase c) {
        return c.onClient(mc -> {
            List<String> out = new ArrayList<>();
            var d = net.fabricmc.fabric.impl.command.client.ClientCommandInternals.getActiveDispatcher();
            if (d != null) {
                for (var n : d.getRoot().getChildren()) {
                    out.add(n.getName());
                }
            }
            return out;
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static void setInstance(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field fl = target.getClass().getDeclaredField(field);
            fl.setAccessible(true);
            fl.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object newTab(String tabCls) {
        try {
            return Mod.cls(tabCls).getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AbstractWidget> build(String tabCls) {
        Object t = newTab(tabCls);
        return (List<AbstractWidget>) Mod.call(t, "buildWidgets", 0, 0, 300, (Runnable) () -> { });
    }

    private static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(ModScreenDriver.label(w));
        }
        return out;
    }

    private static int indexOfPrefix(List<String> l, String prefix) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
