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
