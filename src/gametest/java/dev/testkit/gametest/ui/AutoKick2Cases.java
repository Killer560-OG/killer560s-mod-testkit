package dev.testkit.gametest.ui;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * killer560's 2026-10-11 Auto Kick follow-up (mod branch autokick-2):
 * <p>801 Judge By: S+, S and S+ else S each judge the time they name (S+ else S falls to S only with no S+).
 * <p>802 Detect Queued (Party Finder only): a client-side "Group Builder" with his screenshot's exact items ("Select
 * Dungeon Type" / "Select Floor", "Currently Selected: ...") is read by item name, and the pick becomes the floor only
 * when "Party Finder > Your party has been queued in the dungeon finder!" arrives through the real chat hook; a joiner is
 * then judged on it. Normal mode, other slots and color codes too.
 * <p>803 /joininstance is NOT a source: after a Party Finder F5 queue, a sendCommand /joininstance, the /m6 shortcut
 * and the ServerCommands packet path all leave F5.
 * <p>804 Forgery: another player's chat line carrying the queued text (all, party, guild, private, with a suffix), a
 * "Currently Selected" line on another item, and a queued line with no builder pick all leave the floor alone.
 * <p>805 Floor Source settings: Last Run / Fixed / Detect (and Detect's Last Run fallback), save/load, old-file
 * migration, a profile reload, tooltips and the tab rows.
 * <p>806 / 807 (mod menufix-1102): each floor's own switch, and Populate's PB + 20 s rounded to the slider's step.
 */
final class AutoKick2Cases {

    private static final String AK = "autokick.AutoKickFeature";
    private static final String AK_CFG = "autokick.AutoKickConfig";
    private static final String DET = "autokick.QueuedFloorDetector";
    private static final String QUEUED = "Party Finder > Your party has been queued in the dungeon finder!";

    private AutoKick2Cases() {
    }

    // ---- 801 ------------------------------------------------------------------------------------------------------

    static void timeMode(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            setup(c, "F7", 300);
            c.onClient(mc -> {
                // S+ 4:30 (ok), S 5:30 (too slow) / S+ 5:40 (too slow), S 4:00 (ok) / no S+, S 4:20 / no S+, S 5:50.
                stats("BothFastSp", "4:30", "5:30");
                stats("BothFastS", "5:40", "4:00");
                stats("OnlyS", null, "4:20");
                stats("OnlySlowS", null, "5:50");
                return null;
            });
            String[] names = {"BothFastSp", "BothFastS", "OnlyS", "OnlySlowS"};
            Map<String, List<String>> got = new java.util.LinkedHashMap<>();
            for (String mode : new String[]{"S_PLUS", "S", "S_PLUS_ELSE_S"}) {
                c.onClient(mc -> {
                    Mod.call(Mod.cfg(AK_CFG), "setTimeMode", Mod.enumValue(AK_CFG + "$TimeMode", mode));
                    return null;
                });
                int d0 = decisions(c).size();
                for (String n : names) {
                    chat(c, "Party Finder > " + n + " joined the dungeon group! (Mage Level 40)");
                }
                c.ticks(5);
                List<String> all = decisions(c);
                got.put(mode, new ArrayList<>(all.subList(Math.min(d0, all.size()), all.size())));
            }
            c.note("decisions per mode: " + got);
            List<String> sp = got.get("S_PLUS");
            List<String> s = got.get("S");
            List<String> both = got.get("S_PLUS_ELSE_S");
            c.check(sp.size() == 4 && s.size() == 4 && both.size() == 4, "not every join was judged in every mode");
            c.check(sp.equals(List.of("BothFastSp F7 S+ 4:30 limit 5:00 -> ok", "BothFastS F7 S+ 5:40 limit 5:00 -> kick",
                    "OnlyS F7 S+ none limit 5:00 -> kick", "OnlySlowS F7 S+ none limit 5:00 -> kick")), "S+ mode: " + sp);
            c.check(s.equals(List.of("BothFastSp F7 S 5:30 limit 5:00 -> kick", "BothFastS F7 S 4:00 limit 5:00 -> ok",
                    "OnlyS F7 S 4:20 limit 5:00 -> ok", "OnlySlowS F7 S 5:50 limit 5:00 -> kick")), "S mode: " + s);
            c.check(both.equals(List.of("BothFastSp F7 S+ 4:30 limit 5:00 -> ok", "BothFastS F7 S+ 5:40 limit 5:00 -> kick",
                    "OnlyS F7 S 4:20 limit 5:00 -> ok", "OnlySlowS F7 S 5:50 limit 5:00 -> kick")), "S+ else S mode: " + both);
            // Default, and survives a save/load.
            Object[] rt = c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setTimeMode", Mod.enumValue(AK_CFG + "$TimeMode", "S_PLUS_ELSE_S"));
                Mod.call(k, "save");
                Mod.staticCall(AK_CFG, "load");
                String back = Mod.call(Mod.cfg(AK_CFG), "getTimeMode").toString();
                return new Object[]{back};
            });
            Path p = saved.keySet().iterator().next();
            Files.writeString(p, "{\"enabled\":true}");
            String def = c.onClient(mc -> {
                Mod.staticCall(AK_CFG, "load");
                return Mod.call(Mod.cfg(AK_CFG), "getTimeMode").toString();
            });
            c.note("saved S_PLUS_ELSE_S read back " + rt[0] + "; a file without the key reads " + def);
            c.check("S_PLUS_ELSE_S".equals(rt[0]), "Judge By did not survive a save/load");
            c.check("S_PLUS".equals(def), "Judge By does not default to S+");
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    // ---- 802 ------------------------------------------------------------------------------------------------------

    static void detectPartyFinder(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            setup(c, null, 0);
            c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "DETECT"));
                Mod.call(k, "setTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", "M4"), 200);
                Mod.call(k, "setTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", "F7"), 360);
                Mod.staticCall(DET, "reset");
                Mod.setField(Mod.ROOT + AK, "lastRunFloor", Mod.enumValue(AK_CFG + "$Floor", "F7"));
                JsonObject master = new JsonObject();
                JsonObject sp = new JsonObject();
                sp.addProperty("floor_4", "3:50");
                master.add("s_plus", sp);
                JsonObject normal = new JsonObject();
                JsonObject nsp = new JsonObject();
                nsp.addProperty("floor_7", "3:00");
                normal.add("s_plus", nsp);
                putStats("MmJoiner", normal, master);
                return null;
            });
            String before = floor(c);
            // His screenshot: Party Finder's "Start a New Queue" opens the Group Builder, row 2 holding "Select Dungeon
            // Type" and "Select Floor" (red blocks), the green confirm further down.
            openBuilder(c, 10, "Master Mode The Catacombs", 11, "Floor IV", true);
            String pick = pick(c);
            String beforeQueue = floor(c);
            close(c);
            chat(c, QUEUED);
            String after = floor(c);
            int d0 = decisions(c).size();
            chat(c, "Party Finder > MmJoiner joined the dungeon group! (Archer Level 45)");
            c.ticks(5);
            List<String> all = decisions(c);
            List<String> mine = new ArrayList<>(all.subList(Math.min(d0, all.size()), all.size()));
            // Normal mode, the items in other slots (found by NAME), color codes on the lines.
            openBuilder(c, 12, "§aThe Catacombs", 30, "§aFloor VI", false);
            String pick2 = pick(c);
            close(c);
            chat(c, QUEUED);
            String after2 = floor(c);
            c.note("before: " + before + "; Group Builder pick " + pick + " (floor still " + beforeQueue
                    + "); after the queued line: " + after + "; joiner: " + mine + "; normal-mode builder in other slots "
                    + "with color codes: pick " + pick2 + " -> " + after2);
            c.check("F7".equals(before), "Detect did not fall back to the last run (F7) with nothing queued");
            c.check("M4".equals(pick), "the Group Builder's Master Mode / Floor IV was not read (" + pick + ")");
            c.check("F7".equals(beforeQueue), "the builder pick became the floor before the party was queued");
            c.check("M4".equals(after), "the queued line did not commit M4");
            c.check(mine.equals(List.of("MmJoiner M4 S+ 3:50 limit 3:20 -> kick")),
                    "the joiner was not judged on M4 (his F7 time would have passed): " + mine);
            c.check("F6".equals(pick2) && "F6".equals(after2), "the normal-mode Floor VI builder (other slots) was not read");
        } finally {
            close(c);
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    // ---- 803 ------------------------------------------------------------------------------------------------------

    static void joinInstanceIgnored(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            setup(c, null, 0);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(AK_CFG), "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "DETECT"));
                Mod.staticCall(DET, "reset");
                return null;
            });
            // Queue F5 in Party Finder first (control: detection works in this client).
            openBuilder(c, 10, "The Catacombs", 11, "Floor V", true);
            close(c);
            chat(c, QUEUED);
            String queued = floor(c);
            List<String> sent = new ArrayList<>();
            // 1. Through the real connection (Fabric's COMMAND event), the exact call /f7 and /m7 make.
            c.onClient(mc -> {
                mc.player.connection.sendCommand("joininstance master_catacombs_floor_seven");
                return null;
            });
            c.ticks(3);
            sent.add("sendCommand joininstance master_catacombs_floor_seven -> " + floor(c));
            // 2. The mod's own /m6 shortcut, when registered on this client.
            boolean m6 = c.onClient(mc -> {
                Object d = Mod.staticCall("net.fabricmc.fabric.impl.command.client.ClientCommandInternals",
                        "getActiveDispatcher");
                boolean has = d != null && Mod.call(Mod.call(d, "getRoot"), "getChild", "m6") != null;
                if (has) {
                    mc.player.connection.sendCommand("m6");
                }
                return has;
            });
            c.ticks(3);
            sent.add("/m6 (registered " + m6 + ") -> " + floor(c));
            // 3. The raw packet path custom shortcuts use.
            c.onClient(mc -> {
                Mod.staticCall("util.ServerCommands", "toServer", "joininstance catacombs_floor_two");
                return null;
            });
            c.ticks(3);
            sent.add("ServerCommands.toServer joininstance catacombs_floor_two -> " + floor(c));
            c.note("after the Party Finder queue: " + queued + "; " + sent);
            c.check("F5".equals(queued), "the Party Finder queue did not set F5 (control)");
            c.check(m6, "the /m6 shortcut is not registered, so the shortcut path was not exercised");
            for (String s : sent) {
                c.check(s.endsWith("-> F5"), "a /joininstance changed the floor: " + s);
            }
        } finally {
            close(c);
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    // ---- 804 ------------------------------------------------------------------------------------------------------

    static void forgery(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            setup(c, null, 0);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(AK_CFG), "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "DETECT"));
                Mod.staticCall(DET, "reset");
                return null;
            });
            openBuilder(c, 10, "The Catacombs", 11, "Floor III", true);
            close(c);
            chat(c, QUEUED);
            String start = floor(c);
            // A Group Builder pick of M5 waits for the queued line; none of the forgeries below may commit it.
            openBuilder(c, 10, "Master Mode The Catacombs", 11, "Floor V", true);
            close(c);
            String pick = pick(c);
            String[] forged = {
                    "[MVP+] Bob: " + QUEUED,
                    "Bob: " + QUEUED,
                    "Party > [MVP+] Bob: " + QUEUED,
                    "Guild > Bob [GM]: " + QUEUED,
                    "[42] [VIP] Bob: " + QUEUED,
                    QUEUED + " (just kidding)",
                    "From [MVP+] Bob: " + QUEUED};
            for (String f : forged) {
                chat(c, f);
            }
            String afterForged = floor(c);
            // "Currently Selected:" on an item that is NOT "Select Floor"/"Select Dungeon Type" is not read.
            c.onClient(mc -> {
                SimpleContainer box = new SimpleContainer(54);
                box.setItem(10, item(Items.REDSTONE_BLOCK, "Select Dungeon Type", "Currently Selected: The Catacombs"));
                box.setItem(11, item(Items.PAPER, "Dungeon Classes", "Currently Selected: Floor VII"));
                box.setItem(12, item(Items.REDSTONE_BLOCK, "Select Floor", "Click to change!"));
                show(mc, box);
                return null;
            });
            c.ticks(5);
            close(c);
            String pickAfterDecoy = pick(c);
            // The real line commits M5.
            chat(c, QUEUED);
            String real = floor(c);
            // A queued line with no Group Builder seen detects nothing.
            c.onClient(mc -> {
                Mod.staticCall(DET, "reset");
                return null;
            });
            chat(c, QUEUED);
            boolean noPickNoDetect = !c.onClient(mc -> (Boolean) Mod.staticCall(DET, "hasDetection"));
            c.note("start " + start + "; pick " + pick + "; after " + forged.length + " forged lines floor " + afterForged
                    + "; after a decoy item pick " + pickAfterDecoy + "; real line -> " + real
                    + "; queued with no pick detected nothing " + noPickNoDetect);
            c.check("F3".equals(start), "the first Party Finder queue did not set F3 (control)");
            c.check("M5".equals(pick), "the builder pick M5 was not read");
            c.check("F3".equals(afterForged), "a forged queued line changed the floor to " + afterForged);
            c.check("M5".equals(pickAfterDecoy), "a decoy item's 'Currently Selected' changed the pick to " + pickAfterDecoy);
            c.check("M5".equals(real), "the real queued line did not commit M5 (control)");
            c.check(noPickNoDetect, "a queued line with no Group Builder pick detected a floor");
        } finally {
            close(c);
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    // ---- 805 ------------------------------------------------------------------------------------------------------

    static void floorSourceSettings(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            setup(c, null, 0);
            String[] r = c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.staticCall(DET, "reset");
                Mod.setField(Mod.ROOT + AK, "lastRunFloor", Mod.enumValue(AK_CFG + "$Floor", "F6"));
                Mod.call(k, "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "DETECT"));
                String detectNone = String.valueOf(Mod.staticCall(AK, "queueFloor"));
                Mod.staticCall(DET, "onBuilderItems", List.of("Currently Selected: Master Mode The Catacombs"),
                        List.of("Currently Selected: Floor V"));
                Mod.staticCall("util.ChatObserver", "onChatComponentAdd", Component.literal(QUEUED));
                String detect = String.valueOf(Mod.staticCall(AK, "queueFloor"));
                Mod.call(k, "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "LAST_RUN"));
                String last = String.valueOf(Mod.staticCall(AK, "queueFloor"));
                Mod.call(k, "setQueueFloor", Mod.enumValue(AK_CFG + "$Floor", "F2"));
                String fixed = String.valueOf(Mod.staticCall(AK, "queueFloor"));
                String src = Mod.call(k, "getFloorSource").toString();
                return new String[]{detectNone, detect, last, fixed, src};
            });
            c.note("Detect with nothing queued " + r[0] + ", after a Party Finder M5 queue " + r[1] + "; Last Run " + r[2]
                    + "; Fixed F2 " + r[3] + " (source " + r[4] + ")");
            c.check("F6".equals(r[0]), "Detect did not fall back to the last run");
            c.check("M5".equals(r[1]), "Detect did not use the queued M5");
            c.check("F6".equals(r[2]), "Last Run did not use the last run");
            c.check("F2".equals(r[3]) && "FIXED".equals(r[4]), "Fixed did not use the Fixed Floor");

            // Save/load, defaults and old files.
            Path p = saved.keySet().iterator().next();
            String[] files = {
                    "{\"enabled\":true}",
                    "{\"enabled\":true,\"queueFloor\":\"\"}",
                    "{\"enabled\":true,\"queueFloor\":\"M3\"}",
                    "{\"enabled\":true,\"queueFloor\":\"M3\",\"floorSource\":\"LAST_RUN\",\"timeMode\":\"S\"}"};
            List<String> read = new ArrayList<>();
            for (String f : files) {
                Files.writeString(p, f);
                read.add(c.onClient(mc -> {
                    Mod.staticCall(AK_CFG, "load");
                    Object k = Mod.cfg(AK_CFG);
                    return Mod.call(k, "getFloorSource") + "/" + Mod.call(k, "getQueueFloor") + "/"
                            + Mod.call(k, "getTimeMode");
                }));
            }
            c.note("files " + read);
            c.check(read.equals(List.of("DETECT/F7/S_PLUS", "DETECT/F7/S_PLUS", "FIXED/M3/S_PLUS", "LAST_RUN/M3/S")),
                    "defaults / old files read wrong: " + read);
            c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "DETECT"));
                Mod.call(k, "setTimeMode", Mod.enumValue(AK_CFG + "$TimeMode", "S"));
                Mod.call(k, "save");
                return null;
            });
            // A profile apply copies the files back and reloads every config (ProfileManager.reloadAllConfigs).
            String text = Files.readString(p);
            Files.writeString(p, text.replace("\"DETECT\"", "\"FIXED\"").replace("\"timeMode\": \"S\"",
                    "\"timeMode\": \"S_PLUS_ELSE_S\""));
            String afterProfile = c.onClient(mc -> {
                Mod.staticCall("profiles.ProfileManager", "reloadAllConfigs");
                Object k = Mod.cfg(AK_CFG);
                return Mod.call(k, "getFloorSource") + "/" + Mod.call(k, "getTimeMode");
            });
            c.note("saved file: " + text.replace('\n', ' ') + "; after an edited profile reload " + afterProfile);
            c.check(text.contains("\"floorSource\": \"DETECT\"") && text.contains("\"timeMode\": \"S\""),
                    "Floor Source / Judge By were not saved");
            c.check("FIXED/S_PLUS_ELSE_S".equals(afterProfile), "a profile reload did not load the new settings");

            // Tooltips.
            String[] tips = c.onClient(mc -> new String[]{
                    (String) Mod.staticCall("gui.SettingTooltips", "describe", "Auto Kick", "Judge By: S+"),
                    (String) Mod.staticCall("gui.SettingTooltips", "describe", "Auto Kick", "Floor Source: Detect Queued"),
                    (String) Mod.staticCall("gui.SettingTooltips", "describe", "Auto Kick", "Fixed Floor: Floor 7")});
            c.note("tooltips: " + String.join(" | ", java.util.Arrays.stream(tips).map(String::valueOf).toList()));
            c.check(tips[0] != null && tips[0].contains("S+ else S"), "no Judge By tooltip");
            c.check(tips[1] != null && tips[1].contains("Detect Queued"), "no Floor Source tooltip");
            c.check(tips[2] != null && tips[2].contains("Fixed"), "no Fixed Floor tooltip");

            // The tab: Fixed Floor shows only when the source is Fixed.
            c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setEnabled", true);
                Mod.call(k, "setFloorSource", Mod.enumValue(AK_CFG + "$FloorSource", "DETECT"));
                return null;
            });
            Screen s = DungeonB2Cases.openTab(c, "Auto Kick");
            List<String> detectRows = DungeonB2Cases.labels(c, s);
            c.onClient(mc -> {
                Mod.call(Mod.cfg(AK_CFG), "setQueueFloor", Mod.enumValue(AK_CFG + "$Floor", "M7"));
                return null;
            });
            List<String> fixedRows = DungeonB2Cases.labels(c, s);
            c.note("tab rows (Detect): " + detectRows + "; (Fixed): " + fixedRows);
            c.check(detectRows.contains("Judge By: S+ else S") && detectRows.contains("Floor Source: Detect Queued"),
                    "the tab lacks Judge By / Floor Source rows");
            c.check(detectRows.stream().noneMatch(l -> l.startsWith("Fixed Floor")), "Fixed Floor shows while Detect");
            c.check(fixedRows.contains("Floor Source: Fixed") && fixedRows.contains("Fixed Floor: Master 7"),
                    "Fixed Floor row missing under Fixed");
            c.check(detectRows.stream().noneMatch(l -> l.toLowerCase(Locale.ROOT).startsWith("queue floor")),
                    "the old Queue Floor row is still there");
            // killer560, 2026-10-14: no warn-only section, only the kick - the Action Mode row is gone.
            c.check(!detectRows.isEmpty() && detectRows.stream().noneMatch(l -> l.toLowerCase(Locale.ROOT).startsWith("action mode")
                    || l.toLowerCase(Locale.ROOT).contains("warn only")), "the removed Action Mode / Warn Only row is still there");
        } finally {
            close(c);
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    // ---- 806 / 807 (mod menufix-1102) -------------------------------------------------------------------------------

    /**
     * 806: each floor's own switch (killer560, 2026-11-02: "make auto kick have a toggle for each floor as well. separate
     * them by mastermode and regular"). A slow F7 joiner is kicked with F7 ON and never judged with F7 OFF; M7 OFF does
     * not touch F7; the switches survive a save/load; an old file without them reads every floor ON; the tab shows a
     * "Normal Floors" and a "Master Mode" group with F1-F7 / M1-M7 switches, each with a tooltip.
     */
    @SuppressWarnings("unchecked")
    static void floorSwitch(UiCase c) throws Exception {
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            setup(c, "F7", 300);
            c.onClient(mc -> {
                stats("SlowOnF7", "5:40", "5:40");
                stats("SlowOnF7b", "5:50", "5:50");
                stats("SlowOnF7c", "5:55", "5:55");
                return null;
            });
            // ON (the default): kicked.
            int d0 = decisions(c).size();
            chat(c, "Party Finder > SlowOnF7 joined the dungeon group! (Mage Level 40)");
            c.ticks(5);
            List<String> on = tail(decisions(c), d0);
            // F7 OFF: never judged, never kicked.
            c.onClient(mc -> {
                Mod.call(Mod.cfg(AK_CFG), "setFloorEnabled", Mod.enumValue(AK_CFG + "$Floor", "F7"), false);
                return null;
            });
            int d1 = decisions(c).size();
            chat(c, "Party Finder > SlowOnF7b joined the dungeon group! (Mage Level 40)");
            c.ticks(5);
            List<String> off = tail(decisions(c), d1);
            // F7 back ON, M7 OFF: F7 judged again.
            c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setFloorEnabled", Mod.enumValue(AK_CFG + "$Floor", "F7"), true);
                Mod.call(k, "setFloorEnabled", Mod.enumValue(AK_CFG + "$Floor", "M7"), false);
                return null;
            });
            int d2 = decisions(c).size();
            chat(c, "Party Finder > SlowOnF7c joined the dungeon group! (Mage Level 40)");
            c.ticks(5);
            List<String> m7off = tail(decisions(c), d2);
            c.note("F7 on: " + on + "; F7 off: " + off + "; F7 on, M7 off: " + m7off);
            c.check(on.equals(List.of("SlowOnF7 F7 S+ 5:40 limit 5:00 -> kick")), "F7 ON did not kick a slow joiner: " + on);
            c.check(off.equals(List.of("SlowOnF7b F7 floor off -> none")), "F7 OFF still judged a joiner: " + off);
            c.check(m7off.equals(List.of("SlowOnF7c F7 S+ 5:55 limit 5:00 -> kick")),
                    "M7 OFF changed what F7 does: " + m7off);
            List<String> kicks = c.onClient(mc -> (List<String>) Mod.staticCall(AK, "kicksSent"));
            c.note("kicks queued/sent: " + kicks);
            c.check(!kicks.contains("SlowOnF7b"), "the F7-OFF joiner was kicked: " + kicks);
            // Save / load, and an old file.
            String back = c.onClient(mc -> {
                Object k = Mod.cfg(AK_CFG);
                Mod.call(k, "setFloorEnabled", Mod.enumValue(AK_CFG + "$Floor", "F3"), false);
                Mod.call(k, "save");
                Mod.staticCall(AK_CFG, "load");
                Object k2 = Mod.cfg(AK_CFG);
                StringBuilder sb = new StringBuilder();
                for (String f : new String[]{"F3", "F7", "M7"}) {
                    sb.append(f).append('=').append(Mod.call(k2, "isFloorEnabled", Mod.enumValue(AK_CFG + "$Floor", f))).append(' ');
                }
                return sb.toString().trim();
            });
            Path p = saved.keySet().iterator().next();
            String file = Files.readString(p);
            Files.writeString(p, "{\"enabled\":true,\"target.F7\":200}");
            String old = c.onClient(mc -> {
                Mod.staticCall(AK_CFG, "load");
                Object k = Mod.cfg(AK_CFG);
                int offCount = 0;
                for (Object f : Mod.cls(AK_CFG + "$Floor").getEnumConstants()) {
                    if (!(Boolean) Mod.call(k, "isFloorEnabled", f)) {
                        offCount++;
                    }
                }
                return offCount + " off";
            });
            c.note("after save/load: " + back + "; an old file without floor switches: " + old);
            c.check(back.equals("F3=false F7=true M7=false"), "floor switches did not survive a save/load: " + back);
            c.check(file.contains("\"judge.F3\": false") && file.contains("\"judge.F1\": true"),
                    "the floor switches are not saved as judge.<floor> keys");
            c.check(old.equals("0 off"), "an old file did not read every floor ON: " + old);
            // The tab: two groups, a switch per floor, each with a tooltip.
            List<String> rows = c.onClient(mc -> {
                Mod.call(Mod.cfg(AK_CFG), "setEnabled", true);
                try {
                    Object tab = Mod.cls("gui.tab.AutoKickTab").getDeclaredConstructor().newInstance();
                    List<net.minecraft.client.gui.components.AbstractWidget> built =
                            (List<net.minecraft.client.gui.components.AbstractWidget>) Mod.call(tab, "buildWidgets", 0, 0,
                                    340, (Runnable) () -> { });
                    built.sort(java.util.Comparator.comparingInt(net.minecraft.client.gui.components.AbstractWidget::getY)
                            .thenComparingInt(net.minecraft.client.gui.components.AbstractWidget::getX));
                    List<String> out = new ArrayList<>();
                    for (net.minecraft.client.gui.components.AbstractWidget w : built) {
                        out.add(ModScreenDriver.label(w));
                    }
                    return out;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.note("Auto Kick rows: " + rows);
            int normal = rows.indexOf("Normal Floors");
            int master = rows.indexOf("Master Mode");
            c.check(normal >= 0 && master > normal, "no Normal Floors then Master Mode groups: " + rows);
            for (int i = 1; i <= 7; i++) {
                int f = indexStarting(rows, "F" + i + ":");
                int m = indexStarting(rows, "M" + i + ":");
                c.check(f > normal && f < master, "no F" + i + " switch in Normal Floors: " + rows);
                c.check(m > master, "no M" + i + " switch in Master Mode: " + rows);
                c.check(f >= 0 && f + 1 < rows.size() && rows.get(f + 1).startsWith("Floor " + i),
                        "F" + i + "'s switch is not beside its slider: " + rows);
                String tipF = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Auto Kick", "F" + i);
                c.check(tipF != null && tipF.contains("Floor " + i), "no tooltip on the F" + i + " switch: " + tipF);
                String tipM = (String) Mod.staticCall("gui.SettingTooltips", "describe", "Auto Kick", "M" + i);
                c.check(tipM != null && tipM.contains("Master " + i), "no tooltip on the M" + i + " switch: " + tipM);
            }
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    /**
     * 807: Populate (killer560, 2026-11-02: "populate 20s higher than their pb rounded to the nearest increment of the
     * slider"): PB 2:37.4 + 20 s is 2:57 on a 1 s step and 2:55 on a 5 s step (the sliders' step), clamped into the floor's
     * range; a real saved profile (AntsRNG) through the mod's own populate sets every floor with an S+ PB to that, leaves a
     * floor without one alone, and never touches the floor switches.
     */
    @SuppressWarnings("unchecked")
    static void populate(UiCase c) throws Exception {
        String api = "autokick.AutoKickApi";
        Object f7 = Mod.enumValue(AK_CFG + "$Floor", "F7");
        Object f1 = Mod.enumValue(AK_CFG + "$Floor", "F1");
        int step = (Integer) Mod.field(AK_CFG, "SLIDER_STEP_SECONDS");
        int one = (Integer) Mod.staticCall(api, "populatedSeconds", 157_400L, 1, f7);
        int five = (Integer) Mod.staticCall(api, "populatedSeconds", 157_400L, 5, f7);
        int half = (Integer) Mod.staticCall(api, "populatedSeconds", 157_500L, 1, f7);
        int up5 = (Integer) Mod.staticCall(api, "populatedSeconds", 158_000L, 5, f7);
        int top = (Integer) Mod.staticCall(api, "populatedSeconds", 300_000L, 5, f1);
        int low = (Integer) Mod.staticCall(api, "populatedSeconds", 4_000L, 5, f1);
        int none = (Integer) Mod.staticCall(api, "populatedSeconds", 0L, 5, f1);
        c.note("step " + step + "; 2:37.4 -> 1 s " + one + ", 5 s " + five + "; 2:37.5 -> 1 s " + half + "; 2:38.0 -> 5 s "
                + up5 + "; F1 5:00 -> " + top + "; 0:04 -> " + low + "; none -> " + none);
        c.check(step == 5, "the slider step is " + step + ", not 5");
        c.check(one == 177, "2:37.4 on a 1 s step gave " + one + ", want 177 (2:57)");
        c.check(five == 175, "2:37.4 on a 5 s step gave " + five + ", want 175 (2:55)");
        c.check(half == 178, "2:37.5 on a 1 s step gave " + half + ", want 178 (half rounds up)");
        c.check(up5 == 180, "2:38.0 on a 5 s step gave " + up5 + ", want 180 (3:00, the nearer step)");
        c.check(top == 240, "a PB over F1's top gave " + top + ", want 240 (clamped)");
        c.check(low == 30, "a 4 s PB gave " + low + ", want 30 (the slider's floor)");
        c.check(none == 0, "no PB gave " + none + ", want 0 (left alone)");

        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, AK_CFG);
        try {
            com.google.gson.JsonObject root;
            try (java.io.InputStream in = AutoKick2Cases.class.getResourceAsStream(
                    "/testkit-http/partyfinder-stats/antsrng.json")) {
                c.check(in != null, "the AntsRNG fixture is not on the classpath");
                root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in,
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
            java.util.UUID ants = java.util.UUID.fromString("014fd282-53f5-474f-9d79-110e408e5a7c");
            String report = c.onClient(mc -> {
                try {
                    List<Object> profiles = (List<Object>) Mod.staticCall("profileviewer.data.SbProfile", "parseAll",
                            root, ants);
                    Object profile = Mod.staticCall("profileviewer.data.SbProfile", "dungeonProfile", profiles);
                    Object k = Mod.cfg(AK_CFG);
                    Object[] floors = Mod.cls(AK_CFG + "$Floor").getEnumConstants();
                    boolean[] sw = new boolean[floors.length];
                    for (int i = 0; i < floors.length; i++) {
                        sw[i] = i % 3 != 0;
                        Mod.call(k, "setFloorEnabled", floors[i], sw[i]);
                        Mod.call(k, "setTargetSeconds", floors[i], 61);
                    }
                    Object result = Mod.cls("profileviewer.api.ProfileViewerApi$ProfilesResult").getDeclaredConstructors()[0]
                            .newInstance(ants, profiles, "test", System.currentTimeMillis());
                    Object pr = Mod.staticCall(api, "apply", "AntsRNG", result);
                    Object dungeons = Mod.field(profile, "dungeons");
                    StringBuilder sb = new StringBuilder();
                    int set = 0;
                    for (int i = 0; i < floors.length; i++) {
                        Object f = floors[i];
                        boolean master = (Boolean) Mod.call(f, "master");
                        int n = (Integer) Mod.call(f, "number");
                        Map<Integer, Object> map = (Map<Integer, Object>) Mod.call(dungeons, master ? "master" : "normal");
                        Object fl = map.get(n);
                        long pb = fl == null ? 0L : (Long) Mod.call(fl, "fastestSPlus");
                        int want = pb > 0 ? (Integer) Mod.staticCall(api, "populatedSeconds", pb, 5, f) : 61;
                        int got = (Integer) Mod.call(k, "getTargetSeconds", f);
                        boolean swNow = (Boolean) Mod.call(k, "isFloorEnabled", f);
                        if (pb > 0) {
                            set++;
                            // Independent of the helper: PB + 20 s to the nearest 5 s.
                            long ind = Math.round((pb / 1000.0 + 20) / 5.0) * 5;
                            ind = Math.max(30, Math.min((Integer) Mod.call(f, "maxSeconds"), ind));
                            if (ind != got) {
                                sb.append(f).append(": pb ").append(pb).append(" ms set ").append(got).append(", want ")
                                        .append(ind).append("; ");
                            }
                        }
                        if (got != want) {
                            sb.append(f).append(": got ").append(got).append(" want ").append(want).append("; ");
                        }
                        if (swNow != sw[i]) {
                            sb.append(f).append(": switch changed to ").append(swNow).append("; ");
                        }
                    }
                    int floorsSet = (Integer) Mod.call(pr, "floorsSet");
                    if (floorsSet != set || set == 0) {
                        sb.append("floorsSet ").append(floorsSet).append(" but ").append(set).append(" floors have a PB; ");
                    }
                    Object f7k = floors[6];
                    return "F7 set to " + Mod.call(k, "getTargetSeconds", f7k) + " s, " + set + " floors with a PB. "
                            + (sb.length() == 0 ? "ok" : "BAD " + sb);
                } catch (ReflectiveOperationException e) {
                    return "BAD " + e;
                }
            });
            c.note("populate from AntsRNG: " + report);
            c.check(report.endsWith("ok"), "populate: " + report);
        } finally {
            DungeonB2Cases.restoreConfig(c, saved, AK_CFG);
        }
    }

    private static List<String> tail(List<String> all, int from) {
        return new ArrayList<>(all.subList(Math.min(from, all.size()), all.size()));
    }

    private static int indexStarting(List<String> rows, String prefix) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Auto Kick on (it always kicks - no Warn Only since 2026-10-14), out of a dungeon; {@code fixed} (with its limit)
     *  or no change to the source. */
    private static void setup(UiCase c, String fixed, int limit) {
        c.onClient(mc -> {
            Mod.staticCall("secrets.DungeonState", "setRoomSim", false);
            Object k = Mod.cfg(AK_CFG);
            Mod.call(k, "setEnabled", true);
            Mod.call(k, "setTimeMode", Mod.enumValue(AK_CFG + "$TimeMode", "S_PLUS"));
            if (fixed != null) {
                Mod.call(k, "setQueueFloor", Mod.enumValue(AK_CFG + "$Floor", fixed));
                Mod.call(k, "setTargetSeconds", Mod.enumValue(AK_CFG + "$Floor", fixed), limit);
            }
            return null;
        });
    }

    /** One line through the real chat hook (ChatObserver, the chat mixin's call). */
    private static void chat(UiCase c, String text) {
        c.onClient(mc -> {
            Mod.staticCall("util.ChatObserver", "onChatComponentAdd", Component.literal(text));
            return null;
        });
        c.ticks(2);
    }

    /**
     * Opens a client-side "Group Builder" as his screenshot shows it: "Select Dungeon Type" and "Select Floor" red blocks
     * carrying "Currently Selected: ..." lines, plus (when {@code confirm}) a green block further down. Lets the detector
     * read it for a few ticks; the caller closes it.
     */
    private static void openBuilder(UiCase c, int typeSlot, String type, int floorSlot, String floor, boolean confirm) {
        c.onClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(typeSlot, item(Items.REDSTONE_BLOCK, "Select Dungeon Type",
                    "Allows you to change your selected dungeon type.", "", "Currently Selected: " + type, "",
                    "Click to change!"));
            box.setItem(floorSlot, item(Items.REDSTONE_BLOCK, "Select Floor",
                    "Allows you to change your selected floor number.", "", "Currently Selected: " + floor, "",
                    "Click to change!"));
            if (confirm) {
                box.setItem(40, item(Items.EMERALD_BLOCK, "Confirm Group", "Click to confirm!"));
            }
            show(mc, box);
            return null;
        });
        c.ticks(5);
    }

    private static void show(net.minecraft.client.Minecraft mc, SimpleContainer box) {
        McCompat.setScreen(mc, new ContainerScreen(ChestMenu.sixRows(0, mc.player.getInventory(), box),
                mc.player.getInventory(), Component.literal("Group Builder")));
    }

    private static void close(UiCase c) {
        c.onClient(mc -> {
            McCompat.setScreen(mc, null);
            return null;
        });
    }

    private static ItemStack item(net.minecraft.world.item.Item type, String name, String... lore) {
        ItemStack stack = new ItemStack(type);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Component.literal(l));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines));
        return stack;
    }

    private static String floor(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(AK, "queueFloor")));
    }

    private static String pick(UiCase c) {
        return c.onClient(mc -> String.valueOf(Mod.staticCall(DET, "builderPick")));
    }

    @SuppressWarnings("unchecked")
    private static List<String> decisions(UiCase c) {
        return c.onClient(mc -> (List<String>) Mod.staticCall(AK, "decisions"));
    }

    /** A Party Finder lookup answer with an F7 S+ and S time (either may be null). */
    private static void stats(String name, String sPlusF7, String sF7) {
        JsonObject normal = new JsonObject();
        JsonObject sp = new JsonObject();
        JsonObject s = new JsonObject();
        if (sPlusF7 != null) {
            sp.addProperty("floor_7", sPlusF7);
        }
        if (sF7 != null) {
            s.addProperty("floor_7", sF7);
        }
        normal.add("s_plus", sp);
        normal.add("s", s);
        putStats(name, normal, new JsonObject());
    }

    @SuppressWarnings("unchecked")
    private static void putStats(String name, JsonObject normal, JsonObject master) {
        try {
            Class<?> rec = Mod.cls("partyfinder.PartyFinderStatsApi$PlayerStats");
            Object st = rec.getDeclaredConstructor(double.class, int.class, double.class, JsonObject.class,
                    JsonObject.class, long.class).newInstance(40.0, 1000, 8.0, normal, master,
                    System.currentTimeMillis());
            ((Map<String, Object>) Mod.field("partyfinder.PartyFinderStatsApi", "CACHE"))
                    .put(name.toLowerCase(Locale.ROOT), st);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
