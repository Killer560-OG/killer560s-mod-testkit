package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * killer560's 2026-10-13 dungeon to-do list (mod branch doc-dungeons), the singleplayer half.
 *
 * <pre>
 * 981 Etherwarp Overlay while sneaking and WALKING into the target block (a step, a wall, at speed), every tick drawn
 * 985 Fast/Auto Leap: Crystal (was P1), no Test leap, per-leap Mode and Target buttons pressed, old configs carried over
 * 986 Auto Croesus: Chest Key Min Profit 0..2,000k in 20k steps, Key Buy-Offer default (+ migration), key settings order
 * 987 Dungeon Map Names Holding Leap: names only with a Spirit Leap / Infinileap in hand, measured on screenshots
 * </pre>
 */
final class DocDungeonsCases {

    private DocDungeonsCases() {
    }

    private static final String EW = "etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String EWC = "etherwarpoverlay.EtherwarpOverlayConfig";

    // =================================================================================================================
    // 981 Etherwarp Overlay walking into the block
    // =================================================================================================================

    static void etherwarpWalkIn(UiCase c) throws Exception {
        Object wasOn = Mod.get(EWC, "isEnabled");
        Mod.set(EWC, "setEnabled", true);
        try {
            // A step: one stone at x 303 on a floor whose top is y 100; looked at 15 degrees down.
            walkInto(c, "step", 15.0, "fill 303 100 301 303 100 301 minecraft:stone", "303, 100, 301", false);
            // A wall two high at x 303; looked at level, so the ray meets its top block.
            walkInto(c, "wall", 0.0, "fill 303 100 301 303 101 301 minecraft:stone", "303, 101, 301", false);
            // Hypixel pace: Speed X, sprint key held, no view bobbing, from 8 blocks back at the wall.
            command(c, "effect give @a minecraft:speed 60 9 true");
            c.onClient(mc -> {
                mc.options.bobView().set(false);
                return null;
            });
            walkInto(c, "wall-fast", 0.0, "fill 303 100 301 303 101 301 minecraft:stone", "303, 101, 301", true);
        } finally {
            command(c, "effect clear @a");
            c.onClient(mc -> {
                mc.options.bobView().set(true);
                return null;
            });
            Mod.set(EWC, "setEnabled", wasOn);
        }
    }

    private static void walkInto(UiCase c, String what, double pitch, String build, String want,
                                 boolean sprint) throws Exception {
        command(c, "tp @a 299.5 110 301.5 -90 " + pitch);
        c.ticks(20);
        command(c, "fill 294 99 296 312 99 306 minecraft:stone");
        command(c, "fill 294 100 296 312 106 306 minecraft:air");
        command(c, build);
        command(c, "clear @a");
        command(c, "give @a minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1}]");
        flightOff(c);
        command(c, "tp @a " + (sprint ? "294.5" : "299.5") + " 100 301.5 -90 " + pitch);
        await(c, what + ": standing on the floor", mc -> mc.player != null && mc.player.onGround()
                && Math.abs(mc.player.getY() - 100.0) < 1e-6, 100);
        List<String> samples = new ArrayList<>();
        int drawn = 0;
        int walking = 0;
        double startX = c.onClient(mc -> mc.player.getX());
        double endX;
        try {
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                mc.options.keyShift.setDown(true);
                mc.player.setYRot(-90f);
                mc.player.setXRot((float) pitch);
                return null;
            });
            await(c, what + ": crouching", mc -> mc.player.isCrouching(), 40);
            c.onClient(mc -> {
                mc.options.keyUp.setDown(true);
                mc.options.keySprint.setDown(sprint);
                return null;
            });
            double lastX = startX;
            for (int i = 0; i < 60; i++) {
                c.ticks(1);
                String[] s = c.onClient(mc -> new String[]{
                        String.format(Locale.ROOT, "%.3f", mc.player.getX()),
                        (String) Mod.staticCall(EW, "lastOutcome"),
                        String.valueOf(mc.player.isShiftKeyDown()), String.valueOf(mc.player.isCrouching()),
                        String.valueOf(mc.player.horizontalCollision), String.valueOf(mc.player.isSprinting())});
                double x = Double.parseDouble(s[0]);
                boolean moving = x > lastX + 1e-4;
                lastX = x;
                if (moving || "true".equals(s[4])) {
                    walking++;
                    if (s[1].startsWith("drawn " + want + " ok")) {
                        drawn++;
                    } else if (samples.size() < 8) {
                        samples.add("x " + s[0] + " shift " + s[2] + " crouch " + s[3] + " collide " + s[4] + " sprint " + s[5] + ": "
                                + s[1]);
                    }
                }
            }
            endX = lastX;
            java.nio.file.Path shot = c.ctx().takeScreenshot(dev.testkit.harness.Report.fileName(c.name() + "-" + what));
            dev.testkit.harness.Report.screenshot(c.name() + "-" + what, shot);
        } finally {
            c.onClient(mc -> {
                mc.options.keyUp.setDown(false);
                mc.options.keySprint.setDown(false);
                mc.options.keyShift.setDown(false);
                return null;
            });
            command(c, "fill 294 99 296 312 106 306 minecraft:air");
            command(c, "clear @a");
        }
        c.note(String.format(Locale.ROOT, "%s: walked x %.3f -> %.3f, %d tick(s) walking or pressed into it, overlay"
                + " drawn on %s on %d; misses: %s", what, startX, endX, walking, want, drawn, samples));
        c.check(endX - startX > 1.5 && startX < 300, what + ": he did not walk into the block (moved " + (endX - startX) + ")");
        c.check(endX > 302.6, what + ": he stopped short of the block at x " + endX);
        c.check(walking >= 20, what + ": only " + walking + " tick(s) walking/pressed in");
        c.check(drawn == walking, what + ": the overlay missed " + (walking - drawn) + " of " + walking
                + " tick(s) walking into " + want + ": " + samples);
    }

    // =================================================================================================================
    // 985 Fast/Auto Leap: per-leap Mode and Target, Crystal, no Test leap, old configs carried over
    // =================================================================================================================

    private static final String FLC = "fastleap.FastLeapConfig";
    private static final String FLS = "fastleap.FastLeapSection";
    private static final String GROUP = "fastleap.FastLeapConfig$LeapGroup";
    private static final String TARGET = "fastleap.FastLeapConfig$LeapTarget";

    static void leapSettings(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            List<Object> ws = new ArrayList<>();
            c.onClient(mc -> Mod.staticCall(FLS, "buildLeapList", ws, 0, 0, 400, (Runnable) () -> { }));
            c.check(ws.isEmpty(), "legit jar: the leap list built " + ws.size() + " widget(s)");
            c.note("legit jar: Fast/Auto Leap is cheat-only, its leap list builds nothing");
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-fastleap.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        @SuppressWarnings("unchecked")
        java.util.Set<String> members = (java.util.Set<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
        List<String> membersWas = new ArrayList<>(members);
        try {
            // ---- an old file (Class mode, P1 on + Auto, the Test leap on) ----
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"enabled\":true,\"targetMode\":\"CLASS\",\"testLeapClass\":\"MAGE\",\"groups\":{"
                    + "\"P1\":{\"enabled\":true,\"auto\":true},\"P3\":{\"enabled\":true,\"auto\":false},"
                    + "\"TEST\":{\"enabled\":true,\"auto\":true}},\"targets\":{"
                    + "\"P1\":{\"name\":\"Bob\",\"class\":\"HEALER\",\"keywords\":\"p2, storm\"},"
                    + "\"S1\":{\"name\":\"\",\"class\":\"ARCHER\",\"keywords\":\"ee2\"}}}");
            Object p1 = Mod.enumValue(GROUP, "P1");
            Object p3 = Mod.enumValue(GROUP, "P3");
            Object tP1 = Mod.enumValue(TARGET, "P1");
            Object tS1 = Mod.enumValue(TARGET, "S1");
            String[] old = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                List<String> groups = new ArrayList<>();
                for (Object g : Mod.cls(GROUP).getEnumConstants()) {
                    groups.add(((Enum<?>) g).name());
                }
                return new String[]{(String) Mod.field(p1, "label"), String.join(",", groups),
                        "" + Mod.call(cfg, "isLeapEnabled", p1), "" + Mod.call(cfg, "isLeapAuto", p1),
                        "" + Mod.call(cfg, "isLeapFast", p1), "" + Mod.call(cfg, "isLeapFast", p3),
                        "" + Mod.call(cfg, "getTargetMode"), "" + Mod.call(cfg, "isTargetByClass", tP1),
                        "" + Mod.call(cfg, "getTargetClass", tP1), "" + Mod.call(cfg, "isTargetByClass", tS1),
                        "" + Mod.call(cfg, "getTargetClass", tS1)};
            });
            c.note("old Class-mode file: P1 label " + old[0] + "; groups " + old[1] + "; P1 on " + old[2] + " auto " + old[3]
                    + " fast " + old[4] + "; P3 fast " + old[5] + "; mode " + old[6] + "; P1 by class " + old[7] + " "
                    + old[8] + "; S1 by class " + old[9] + " " + old[10]);
            c.check("Crystal".equals(old[0]), "P1's leap is labelled " + old[0] + ", not Crystal");
            c.check(!old[1].contains("TEST"), "the Test leap is still a leap: " + old[1]);
            c.check("true".equals(old[2]) && "true".equals(old[3]) && "false".equals(old[4]),
                    "the old P1 (on + Auto) did not carry over to Crystal as an Auto leap");
            c.check("true".equals(old[5]), "the old P3 (on, no Auto) is not a Fast leap");
            c.check("PER_LEAP".equals(old[6]), "an old Class mode reads as " + old[6]);
            c.check("true".equals(old[7]) && "HEALER".equals(old[8]) && "true".equals(old[9]) && "ARCHER".equals(old[10]),
                    "an old Class mode's targets did not become class picks");
            // ---- an old Name-mode file, and an old Posmsg one with no name ----
            Files.writeString(file, "{\"enabled\":true,\"targetMode\":\"NAME\",\"targets\":{"
                    + "\"P1\":{\"name\":\"Bob\",\"class\":\"HEALER\",\"keywords\":\"p2\"}}}");
            String[] named = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                return new String[]{"" + Mod.call(cfg, "isTargetByClass", tP1), (String) Mod.call(cfg, "getTargetName", tP1)};
            });
            c.check("false".equals(named[0]) && "Bob".equals(named[1]), "an old Name mode's P1 is not the name pick Bob: "
                    + named[0] + " " + named[1]);
            Files.writeString(file, "{\"enabled\":true,\"targetMode\":\"POSMSG\",\"targets\":{"
                    + "\"P1\":{\"name\":\"\",\"class\":\"MAGE\",\"keywords\":\"p2\"}}}");
            String[] pos = c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                return new String[]{"" + Mod.call(cfg, "getTargetMode"), "" + Mod.call(cfg, "isTargetByClass", tP1)};
            });
            c.check("POSMSG".equals(pos[0]) && "true".equals(pos[1]), "an old Posmsg file read as " + pos[0] + " / " + pos[1]);
            c.note("old Name file: P1 by class " + named[0] + " name " + named[1] + "; old Posmsg file: " + pos[0]
                    + ", P1 by class " + pos[1]);

            // ---- the leap list: no Edit, no Test, Crystal, a Mode and a Target on every row ----
            c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Object cfg = Mod.staticCall(FLC, "getInstance");
                Mod.call(cfg, "setLeapEnabled", p1, true);
                Mod.call(cfg, "setLeapAuto", p1, false);
                Mod.call(cfg, "setTargetPickClass", tP1, Mod.enumValue("dungeonclass.DungeonClass", "HEALER"));
                members.clear();
                members.add("LeapMate");
                Mod.staticCall("dungeonclass.ClassOverrides", "set", "LeapMate", Mod.enumValue("dungeonclass.DungeonClass", "ARCHER"));
                return null;
            });
            List<AbstractWidget> ws = buildLeapList(c);
            List<String> labels = labels(ws);
            c.note("leap list (" + ws.size() + " widgets): " + labels);
            long modes = labels.stream().filter(l -> l.startsWith("Mode: ")).count();
            long targets = labels.stream().filter(l -> l.startsWith("Target: ")).count();
            long sections = labels.stream().filter(l -> l.matches("S[1-4]: .*")).count();
            c.check(labels.stream().noneMatch(l -> l.equals("Edit") || l.startsWith("Edit")), "an Edit button is still there");
            c.check(labels.stream().noneMatch(l -> l.toLowerCase(Locale.ROOT).contains("test")), "a Test leap row is still there");
            c.check(labels.contains("Crystal Leap: ON"), "no \"Crystal Leap: ON\" row");
            c.check(labels.stream().noneMatch(l -> l.startsWith("P1 Leap")), "a \"P1 Leap\" row is still there");
            c.check(modes == 12, modes + " Mode buttons, one per leap is 12");
            c.check(targets == 10 && sections == 4, targets + " Target and " + sections + " S1-S4 buttons, want 10 and 4");
            c.check(labels.contains("After Blood Off: OFF") || labels.contains("After Blood Off: ON"), "Door Opener lost After Blood Off");
            // Crystal's row: switch, Mode, Target in that order
            int at = labels.indexOf("Crystal Leap: ON");
            AbstractWidget mode = ws.get(at + 1);
            AbstractWidget target = ws.get(at + 2);
            c.check(labels.get(at + 1).equals("Mode: Fast"), "Crystal's Mode reads " + labels.get(at + 1));
            c.check(labels.get(at + 2).equals("Target: Healer"), "Crystal's Target reads " + labels.get(at + 2));
            // Mode: a press makes it Auto (and the click no longer fast-leaps), another makes it Fast again
            c.onClient(mc -> Mod.call(mode, "press"));
            boolean[] afterAuto = c.onClient(mc -> {
                Object cfg = Mod.staticCall(FLC, "getInstance");
                return new boolean[]{(Boolean) Mod.call(cfg, "isLeapAuto", p1), (Boolean) Mod.call(cfg, "isLeapFast", p1)};
            });
            String modeAuto = label(mode);
            c.onClient(mc -> Mod.call(mode, "press"));
            boolean fastAgain = c.onClient(mc -> (Boolean) Mod.call(Mod.staticCall(FLC, "getInstance"), "isLeapFast", p1));
            c.note("Mode press: \"" + modeAuto + "\" auto " + afterAuto[0] + " fast " + afterAuto[1] + "; press again -> \""
                    + label(mode) + "\" fast " + fastAgain);
            c.check(afterAuto[0] && !afterAuto[1] && modeAuto.equals("Mode: Auto"), "Mode press did not make Crystal Auto");
            c.check(fastAgain && label(mode).equals("Mode: Fast"), "a second Mode press did not make Crystal Fast");
            // Target: presses walk Healer -> Archer (LeapMate has Archer by Leap Order's class pick) -> Berserker ->
            // LeapMate by name -> None -> Mage
            List<String> walk = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                c.onClient(mc -> Mod.call(target, "press"));
                walk.add(label(target));
            }
            String[] pick = c.onClient(mc -> {
                Object cfg = Mod.staticCall(FLC, "getInstance");
                return new String[]{"" + Mod.call(cfg, "isTargetByClass", tP1), "" + Mod.call(cfg, "getTargetClass", tP1),
                        (String) Mod.call(cfg, "getTargetName", tP1)};
            });
            c.note("Target presses: " + walk + "; saved by class " + pick[0] + " class " + pick[1] + " name " + pick[2]);
            c.check(walk.get(0).equals("Target: Archer (LeapMate)"), "after Healer the Target reads \"" + walk.get(0)
                    + "\", want Archer with LeapMate (his Leap Order class pick)");
            c.check(walk.get(1).equals("Target: Berserker"), "then \"" + walk.get(1) + "\", want Berserker");
            c.check(walk.get(2).equals("Target: LeapMate (Archer)"), "then \"" + walk.get(2) + "\", want the party member LeapMate");
            c.check(walk.get(3).equals("Target: None") && walk.get(4).equals("Target: Mage"), "then " + walk.subList(3, 5)
                    + ", want None then Mage (wrapping)");
            c.check("true".equals(pick[0]) && "MAGE".equals(pick[1]), "the Target presses did not save Mage as a class pick");
            // saved and read back
            c.onClient(mc -> {
                Mod.call(Mod.staticCall(FLC, "getInstance"), "save");
                Mod.staticCall(FLC, "load");
                return null;
            });
            String text = Files.readString(file);
            boolean back = c.onClient(mc -> (Boolean) Mod.call(Mod.staticCall(FLC, "getInstance"), "isTargetByClass", tP1));
            c.check(text.contains("\"byClass\"") && !text.contains("testLeapClass") && !text.contains("\"TEST\""),
                    "the saved file still has the Test leap, or no byClass");
            c.check(back, "the Mage class pick did not survive a save and load");
        } finally {
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> {
                Mod.staticCall(FLC, "load");
                Mod.staticCall("dungeonclass.ClassOverrides", "clear", "LeapMate");
                members.clear();
                members.addAll(membersWas);
                return null;
            });
        }
    }

    private static List<AbstractWidget> buildLeapList(UiCase c) {
        List<AbstractWidget> ws = new ArrayList<>();
        c.onClient(mc -> Mod.staticCall(FLS, "buildLeapList", ws, 0, 0, 400, (Runnable) () -> { }));
        return ws;
    }

    static List<String> labels(List<AbstractWidget> ws) {
        List<String> out = new ArrayList<>();
        for (AbstractWidget w : ws) {
            out.add(label(w));
        }
        return out;
    }

    static String label(AbstractWidget w) {
        String s = net.minecraft.ChatFormatting.stripFormatting(w.getMessage().getString());
        return s == null ? "" : s;
    }

    // =================================================================================================================
    // 986 Auto Croesus: Chest Key Min Profit 0-2,000,000 in 20k steps, Key Buy-Offer default, key settings under keys
    // =================================================================================================================

    private static final String CC = "croesus.CroesusConfig";

    static void croesusKeys(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Croesus is cheat-only (its tab is not in the legit menu)");
            return;
        }
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-croesus.json"));
        byte[] was = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            // ---- defaults: no file ----
            Files.deleteIfExists(file);
            String fresh = c.onClient(mc -> {
                Mod.staticCall(CC, "load");
                return "" + Mod.call(Mod.staticCall(CC, "getInstance"), "getChestKeyMode");
            });
            c.check("KEY_BUY_OFFER".equals(fresh), "with no saved file Use Key Based On is " + fresh);
            // ---- an old file: Set Profit (the old default, always saved), key profit 5,000k ----
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"autoCroesusEnabled\":true,\"autoUseChestKeys\":true,\"autoUseKismets\":true,"
                    + "\"autoKeyMinProfitK\":5000,\"chestKeyMode\":\"SET_PROFIT\"}");
            String[] old = c.onClient(mc -> {
                Mod.staticCall(CC, "load");
                Object cfg = Mod.staticCall(CC, "getInstance");
                return new String[]{"" + Mod.call(cfg, "getChestKeyMode"), "" + Mod.call(cfg, "getAutoKeyMinProfitK")};
            });
            c.check("KEY_BUY_OFFER".equals(old[0]), "an old file's Set Profit (the old default) stayed " + old[0]);
            c.check("2000".equals(old[1]), "an old 5,000k key profit loaded as " + old[1] + "k, the cap is 2,000k");
            // ---- a Set Profit picked now stays ----
            String kept = c.onClient(mc -> {
                Object cfg = Mod.staticCall(CC, "getInstance");
                Mod.call(cfg, "setChestKeyMode", Mod.enumValue("croesus.CroesusConfig$ChestKeyMode", "SET_PROFIT"));
                Mod.call(cfg, "save");
                Mod.staticCall(CC, "load");
                return "" + Mod.call(Mod.staticCall(CC, "getInstance"), "getChestKeyMode");
            });
            c.check("SET_PROFIT".equals(kept), "a Set Profit picked after the change came back as " + kept);
            // ---- the tab: order, and the slider swept end to end ----
            List<AbstractWidget> ws = new ArrayList<>();
            c.onClient(mc -> {
                Object cfg = Mod.staticCall(CC, "getInstance");
                Mod.call(cfg, "setAutoCroesusEnabled", true);
                Mod.call(cfg, "setAutoUseChestKeys", true);
                Mod.call(cfg, "setAutoUseKismets", true);
                Object tab;
                try {
                    tab = Mod.cls("gui.tab.AutoCroesusTab").getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("could not make the Auto Croesus tab: " + e, e);
                }
                @SuppressWarnings("unchecked")
                List<AbstractWidget> built = (List<AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0, 400, (Runnable) () -> { });
                ws.addAll(built);
                return null;
            });
            List<String> labels = labels(ws);
            c.note("Auto Croesus tab: " + labels);
            int keys = indexStarting(labels, "Use Chest Keys:");
            int mode = indexStarting(labels, "Use Key Based On:");
            int profit = indexStarting(labels, "Chest Key Min Profit:");
            int kismets = indexStarting(labels, "Use Kismets:");
            int reroll = indexStarting(labels, "Reroll Below:");
            c.check(keys >= 0 && mode == keys + 1 && profit == keys + 2 && kismets == keys + 3 && reroll == keys + 4,
                    "order: keys " + keys + ", key mode " + mode + ", key profit " + profit + ", kismets " + kismets
                            + ", reroll " + reroll + " - want the two key settings straight under Use Chest Keys");
            if (keys >= 0 && kismets >= 0) {
                c.check(ws.get(kismets).getY() > ws.get(profit).getY() && ws.get(keys).getY() < ws.get(mode).getY(),
                        "Use Kismets is not on its own row below the key settings");
            }
            if (profit >= 0) {
                AbstractWidget slider = ws.get(profit);
                java.util.TreeSet<Integer> seen = new java.util.TreeSet<>();
                boolean offStep = false;
                for (int i = 0; i <= 1000; i++) {
                    double v = i / 1000.0;
                    int k = c.onClient(mc -> {
                        Mod.call(slider, "setValue", v);
                        return (Integer) Mod.call(Mod.staticCall(CC, "getInstance"), "getAutoKeyMinProfitK");
                    });
                    seen.add(k);
                    offStep |= k % 20 != 0;
                }
                String top = label(slider);
                c.note("Chest Key Min Profit slider swept 0..1: " + seen.size() + " values, " + seen.first() + "k.."
                        + seen.last() + "k, every one a 20k step: " + !offStep + "; label at the top \"" + top + "\"");
                c.check(seen.first() == 0 && seen.last() == 2000, "the slider runs " + seen.first() + "k.." + seen.last() + "k");
                c.check(!offStep && seen.size() == 101, seen.size() + " values (want 101 = 0..2,000k in 20k steps)");
                c.check(top.contains("2M") || top.contains("2,000,000") || top.contains("2.0M") || top.contains("2.00M"),
                        "the slider at the top reads \"" + top + "\"");
            }
            int[] stepped = c.onClient(mc -> {
                Object cfg = Mod.staticCall(CC, "getInstance");
                int[] out = new int[4];
                int[] in = {1234, -5, 99999, 10};
                for (int i = 0; i < in.length; i++) {
                    Mod.call(cfg, "setAutoKeyMinProfitK", in[i]);
                    out[i] = (Integer) Mod.call(cfg, "getAutoKeyMinProfitK");
                }
                return out;
            });
            c.check(stepped[0] == 1240 && stepped[1] == 0 && stepped[2] == 2000 && stepped[3] == 20,
                    "the setter gave " + java.util.Arrays.toString(stepped) + " for 1234, -5, 99999, 10 (want 1240, 0, 2000, 20)");
        } finally {
            if (was != null) {
                Files.write(file, was);
            } else {
                Files.deleteIfExists(file);
            }
            c.onClient(mc -> Mod.staticCall(CC, "load"));
        }
    }

    private static int indexStarting(List<String> labels, String prefix) {
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // =================================================================================================================
    // 987 Dungeon Map: Names Holding Leap
    // =================================================================================================================

    static void mapLeapNames(UiCase c) throws Exception {
        String cfgCls = MapHeadCases.CFG;
        if (!c.onClient(mc -> MapHeadCases.hasMethod(Mod.cfg(cfgCls), "setLeapNames"))) {
            c.check(false, "this jar has no Names Holding Leap (LiveMapConfig.setLeapNames)");
            return;
        }
        boolean hideGuiWas = c.onClient(mc -> dev.testkit.compat.McCompat.hudHidden(mc));
        List<AutoCloseable> restore = new ArrayList<>();
        double[] self = {MapHeadCases.START + 32, MapHeadCases.START - 48};
        double[] mateHead = MapHeadCases.room(3, 1);
        double[] mateArrow = MapHeadCases.room(1, 3);
        @SuppressWarnings("unchecked")
        java.util.Set<String> members = (java.util.Set<String>) Mod.field("leapmenu.PartyTracker", "MEMBERS");
        List<String> membersWas = new ArrayList<>(members);
        try {
            c.onClient(mc -> {
                restore.add(0, Mod.with("hud.HudConfig", "AutoScale", false));
                restore.add(0, Mod.with(cfgCls, "Enabled", true));
                restore.add(0, Mod.with(cfgCls, "ShowTeammates", true));
                restore.add(0, Mod.with(cfgCls, "ClassRecolorTeammates", false));
                restore.add(0, Mod.with(cfgCls, "IconScale", 1.5f));
                restore.add(0, Mod.with(cfgCls, "RoomPx", 20));
                restore.add(0, Mod.with(cfgCls, "MapBackground", MapHeadCases.GREEN_ROOM));
                restore.add(0, Mod.with(cfgCls, "PlayerHeads", false));
                restore.add(0, Mod.with(cfgCls, "LeapNames", true));
                Mod.staticCall("secrets.DungeonState", "setRoomSim", true);
                dev.testkit.compat.McCompat.setHudHidden(mc, false);
                members.clear();
                Mod.staticCall("livemap.LiveMapFeature", "resetGrid", "987 start");
                return null;
            });
            command(c, "clear @a");
            command(c, "item replace entity @a hotbar.0 with minecraft:ender_pearl[custom_data={id:\"SPIRIT_LEAP\"}]");
            command(c, "item replace entity @a hotbar.1 with minecraft:ender_pearl[custom_data={id:\"INFINITE_SPIRIT_LEAP\"}]");
            command(c, "item replace entity @a hotbar.2 with minecraft:stick");
            MapHeadCases.teleport(c, self[0], self[1], MapHeadCases.SELF_YAW);
            c.onClient(mc -> {
                MapHeadCases.addTeammates(mc, mateHead, mateArrow);
                return null;
            });
            c.ticks(3);
            Frame stick = mapFrame(c, 2, "stick");
            Frame leap = mapFrame(c, 0, "spirit-leap");
            Frame infini = mapFrame(c, 1, "infinileap");
            c.onClient(mc -> {
                Mod.call(Mod.cfg(cfgCls), "setLeapNames", false);
                return null;
            });
            Frame off = mapFrame(c, 0, "setting-off-holding-leap");
            int[] box = MapHeadCases.frameBox(stick.img, MapHeadCases.GREEN_ROOM);
            c.check(box != null, "no map frame on screen - the map HUD did not draw");
            if (box == null) {
                return;
            }
            // the map's interior, less its bottom 50 px, where the hotbar (whose selected slot moves with the held
            // item) can overlap it in the small test window; both teammates' names sit well above that
            int[] wide = {box[0], box[1], box[2], box[3] - 50};
            int leapPx = changedPixels(stick.img, leap.img, wide);
            int infiniPx = changedPixels(stick.img, infini.img, wide);
            int offPx = changedPixels(stick.img, off.img, wide);
            c.note(String.format(Locale.ROOT, "names shown: stick %s, Spirit Leap %s, Infinileap %s, setting off + leap %s;"
                            + " pixels changed against the stick frame: Spirit Leap %d, Infinileap %d, setting off %d",
                    stick.names, leap.names, infini.names, off.names, leapPx, infiniPx, offPx));
            c.check(!stick.names && leap.names && infini.names && !off.names, "names drawn: stick " + stick.names
                    + ", leap " + leap.names + ", infinileap " + infini.names + ", setting off " + off.names);
            c.check(leapPx > 40 && infiniPx > 40, "holding a leap changed only " + leapPx + "/" + infiniPx
                    + " pixels of the map - no names drawn");
            c.check(offPx < 10, "with the setting off, holding a leap still changed " + offPx + " pixels");
        } finally {
            c.onClient(mc -> {
                MapHeadCases.removeTeammates(mc);
                members.clear();
                members.addAll(membersWas);
                Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
                Mod.staticCall("livemap.LiveMapFeature", "resetGrid", "987 end");
                dev.testkit.compat.McCompat.setHudHidden(mc, hideGuiWas);
                mc.player.getInventory().setSelectedSlot(0);
                return null;
            });
            for (AutoCloseable r : restore) {
                r.close();
            }
            command(c, "clear @a");
        }
    }

    private record Frame(java.awt.image.BufferedImage img, boolean names) {
    }

    private static Frame mapFrame(UiCase c, int slot, String label) {
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(slot);
            return null;
        });
        c.ticks(4);
        MapHeadCases.Shot s = MapHeadCases.shot(c, label);
        boolean names = c.onClient(mc -> (Boolean) Mod.staticCall("livemap.LiveMapFeature", "namesShown"));
        return new Frame(s.img(), names);
    }

    private static int changedPixels(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b, int[] box) {
        int n = 0;
        for (int y = Math.max(0, box[1]); y <= Math.min(a.getHeight() - 1, box[3]); y++) {
            for (int x = Math.max(0, box[0]); x <= Math.min(a.getWidth() - 1, box[2]); x++) {
                if (MapHeadCases.changed(a.getRGB(x, y), b.getRGB(x, y))) {
                    n++;
                }
            }
        }
        return n;
    }

    // =================================================================================================================
    // shared
    // =================================================================================================================

    static void flightOff(UiCase c) {
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.getAbilities().flying = false;
                    sp.onUpdateAbilities();
                }
            });
            return null;
        });
        await(c, "flight off", mc -> mc.player != null && !mc.player.getAbilities().flying, 40);
    }

    static void await(UiCase c, String what, java.util.function.Predicate<Minecraft> p, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (c.onClient(p::test)) {
                return;
            }
            c.ticks(1);
        }
        String state = c.onClient(mc -> mc.player == null ? "no player" : String.format(Locale.ROOT,
                "pos %.3f,%.3f,%.3f onGround %s flying %s crouching %s screen %s", mc.player.getX(), mc.player.getY(),
                mc.player.getZ(), mc.player.onGround(), mc.player.getAbilities().flying, mc.player.isCrouching(),
                dev.testkit.compat.McCompat.screen(mc)));
        throw new AssertionError("timed out waiting for " + what + " (" + state + ")");
    }

    static void command(UiCase c, String cmd) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        c.onClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                            cmd);
                } finally {
                    done.set(true);
                }
            });
            return null;
        });
        c.ctx().waitFor(mc -> done.get(), 100);
    }
}
