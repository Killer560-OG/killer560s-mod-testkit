package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * gardencopper-1124: AGreenMan's Garden Copper never showed in the Custom Scoreboard. His reports never held a Garden sidebar,
 * so each way the Copper line could be missed is fed to the mod's own readers: a sidebar line with symbols before the label
 * and an abbreviated value, a tab line that is indented, an island that reads Hub, no Area line, a line that drops for a
 * tick (last read value), and leaving the Garden (no value carried out of it).
 */
final class GardenCopper1124Cases {

    private static final String TEAM = "k560gc1124";
    private static final String SB = "scoreboard.CustomScoreboardConfig";

    private GardenCopper1124Cases() {
    }

    static void copper(UiCase c) throws Exception {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(SB), "setNumberFormat", Mod.enumValue(SB + "$NumberFormat", "LONG"));
            return null;
        });
        // Hypixel's own Garden shape (color codes, red value) with the label behind a symbol and an abbreviated value
        String sym = " §7❖ §fCopper: §c1.2k";
        check(c, "symbol before the label, 1.2k, tab says Garden", List.of("§7Purse: §6100", sym), "Area: Garden", null, "Copper: 1,200");
        check(c, "plain line, tab says Hub (a stale island)", List.of("§fCopper: §c1,234"), "Area: Hub", null, "Copper: 1,234");
        check(c, "line followed by a gain", List.of("§fCopper: §c2,000 §a(+5)"), "Area: Garden", null, "Copper: 2,000");
        check(c, "sidebar has no Copper, an indented tab line does", List.of("§7Purse: §6100"), "Area: Garden", List.of("Area: Garden", " Copper: 3,456"), "Copper: 3,456");
        check(c, "same, with the island reading Hub", List.of("§7Purse: §6100"), "Area: Hub", List.of("Area: Hub", " Copper: 3,456"), "Copper: 3,456");
        check(c, "no Area line, the sidebar names the Garden", List.of("§7⏣ §aThe Garden", "§fCopper: §c77"), "Player", null, "Copper: 77");
        check(c, "Area reads The Garden", List.of("§fCopper: §c88"), "Area: The Garden", null, "Copper: 88");
        // the line drops for a moment in the same world: the last value stays while the island is the Garden
        check(c, "line drops, still the Garden", List.of("§7Purse: §6100"), "Area: Garden", null, "Copper: 88");
        // and nothing is carried out of the Garden
        check(c, "line drops, now the Hub", List.of("§7Purse: §6100"), "Area: Hub", null, "none");
        // the bug report keeps the last Garden-side line
        String report = c.onClient(mc -> (String) Mod.staticCall("scoreboard.ChunkedStat", "report"));
        String copperLine = report.lines().filter(l -> l.startsWith("Copper")).findFirst().orElse("");
        c.note("readers: " + copperLine);
        c.check(copperLine.contains("last read '88'") && copperLine.contains("on island 'Garden'"),
                "readers.txt's Copper line does not carry the last Garden-side read: " + copperLine);
        clearBoard(c);
        c.onClient(mc -> {
            mc.getConnection().getListedOnlinePlayers().iterator().next().setTabListDisplayName(null);
            return null;
        });
    }

    /** Sets the sidebar and the tab, lets the mod read them, and checks the Copper line it would draw ("none" = no line). */
    private static void check(UiCase c, String label, List<String> sidebar, String area, List<String> tabLines, String want) {
        board(c, sidebar);
        String got = c.onClient(mc -> {
            try {
                mc.getConnection().getListedOnlinePlayers().iterator().next().setTabListDisplayName(Component.literal(area));
                Mod.staticCall("scoreboard.ScoreboardData", "refresh", mc);
                if (tabLines != null) {
                    Field f = Mod.cls("scoreboard.ScoreboardData").getDeclaredField("tabPlain");
                    f.setAccessible(true);
                    f.set(null, tabLines);
                }
                Mod.staticCall("scoreboard.ScoreboardData", "sidebarNow", mc);
                Object cfg = Mod.cfg(SB);
                Class<?> entryCls = Mod.cls("scoreboard.ScoreboardEntry");
                Object copper = Mod.enumValue("scoreboard.ScoreboardEntry", "COPPER");
                Method visible = entryCls.getDeclaredMethod("visible", Mod.cls(SB));
                visible.setAccessible(true);
                Method lines = entryCls.getDeclaredMethod("lines", Mod.cls(SB));
                lines.setAccessible(true);
                List<String> out = new ArrayList<>();
                if ((Boolean) visible.invoke(copper, cfg)) {
                    for (Object line : (List<?>) lines.invoke(copper, cfg)) {
                        out.add(net.minecraft.ChatFormatting.stripFormatting((String) Mod.call(line, "text")));
                    }
                }
                return out.isEmpty() ? "none" : out.get(0);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(UiCase.describe(ex), ex);
            }
        });
        c.note(label + " -> " + got);
        c.check(want.equals(got), label + ": the Copper line reads '" + got + "', want '" + want + "'");
    }

    private static void board(UiCase c, List<String> lines) {
        c.onClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> {
                ServerScoreboard sb = server.getScoreboard();
                wipe(sb);
                Objective obj = sb.addObjective(TEAM, ObjectiveCriteria.DUMMY, Component.literal("SKYBLOCK"),
                        ObjectiveCriteria.RenderType.INTEGER, false, null);
                for (int i = 0; i < lines.size(); i++) {
                    String owner = "§" + Integer.toHexString(i) + "§r";
                    PlayerTeam team = sb.addPlayerTeam(TEAM + i);
                    team.setPlayerPrefix(Component.literal(lines.get(i)));
                    sb.addPlayerToTeam(owner, team);
                    sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(owner), obj).set(lines.size() - i);
                }
                sb.setDisplayObjective(DisplaySlot.SIDEBAR, obj);
            });
            return null;
        });
        c.ticks(6);
    }

    private static void clearBoard(UiCase c) {
        c.onClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> wipe(server.getScoreboard()));
            return null;
        });
        c.ticks(4);
    }

    private static void wipe(ServerScoreboard sb) {
        Objective old = sb.getObjective(TEAM);
        if (old != null) {
            sb.removeObjective(old);
        }
        for (PlayerTeam t : new ArrayList<>(sb.getPlayerTeams())) {
            if (t.getName().startsWith(TEAM)) {
                sb.removePlayerTeam(t);
            }
        }
    }
}
