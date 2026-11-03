package dev.testkit.gametest.hx;

import com.mojang.authlib.GameProfile;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Split Timers chat colors and Core Entry's zero (mod branch splitchat-1102, owner report 2026-11-02 from a real F7 run):
 * <ul>
 * <li>1102-splitchat-colors: a whole F7 run fed to the split handler at exact times. Every segment name in chat - each
 * "X took Y!" line as a split ends and every row of the end-of-run summary - must carry exactly the color that row's
 * label gives it on the HUD ({@code layoutRows}), and the times keep their format ("Watcher took 47.50s.").</li>
 * <li>1102-splitchat-core0 / -core0ticks: the client and HxMateA stand inside the core when "The Core entrance is
 * opening!" arrives and must read exactly 0 (0.00s); HxMateB walks in 1.00 s after the line and reads 1.00 s within a
 * tick. Run with Counting Every 0.01s and Ticks.</li>
 * </ul>
 */
final class HxSplitChat1102Cases {

    static final String SPLIT_CFG = "splittimers.SplitTimersConfig";
    static final String SPLITS = "splittimers.SplitTimersFeature";
    static final String CORE = "splittimers.CoreEntryTimes";
    static final int MATE_ID_BASE = 911_020;

    private HxSplitChat1102Cases() {
    }

    static void register(Session s) {
        s.test("1102-splitchat-colors", HxSplitChat1102Cases::colors);
        s.test("1102-splitchat-core0", c -> coreZero(c, false));
        s.test("1102-splitchat-core0ticks", c -> coreZero(c, true));
    }

    // ---- segment colors -------------------------------------------------------------------------------------------

    static final String[][] RUN = {
            // line, ms after the previous line
            {"[NPC] Mort: Here, I found this map when I first entered the dungeon.", "1000"},
            {"[BOSS] The Watcher: Congratulations, you made it through the Entrance.", "22390"},
            {"[BOSS] The Watcher: You have proven yourself. You may pass.", "47500"},
            {"[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", "6050"},
            {"[BOSS] Storm: Pathetic Maxor, just like expected.", "28400"},
            {"[BOSS] Goldor: Who dares trespass into my domain?", "40120"},
            {"The Core entrance is opening!", "38550"},
            {"[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.", "12300"},
            {"☠ Defeated Necron in 04m 30s", "31800"},
    };

    private static void colors(Session c) {
        try (Settings set = new Settings(c).with(SPLIT_CFG, "Enabled", true)
                .with(SPLIT_CFG, "AnnounceInChat", true)
                .with(SPLIT_CFG, "LaglessTimes", false)
                .with(SPLIT_CFG, "TickCounting", false)
                .with(SPLIT_CFG, "CoreEntryTimes", false)
                .with(SPLIT_CFG, "WatcherMoveSplit", false)
                .with(SPLIT_CFG, "P5DragonLines", false)
                .with(SPLIT_CFG, "P5RelicLines", false)) {
            dungeon(c);
            int before = c.onClient(mc -> allMessages(mc).size());
            long end = c.onClient(mc -> {
                long t = System.currentTimeMillis() - 300_000L;
                Mod.staticCall(SPLITS, "onLine", "Starting in 1 second.", t);
                for (String[] l : RUN) {
                    t += Long.parseLong(l[1]);
                    Mod.staticCall(SPLITS, "onLine", l[0], t);
                }
                return t;
            });
            c.ctx().waitTicks(15);   // the summary prints 10 ticks after the run's end line

            // The HUD's rows (labels with their section codes) for the finished run.
            Map<String, Integer> hud = c.onClient(mc -> {
                Map<String, Integer> out = new LinkedHashMap<>();
                for (Object row : (List<?>) Mod.staticCall(SPLITS, "layoutRows", end)) {
                    String kind = String.valueOf(Mod.call(row, "kind"));
                    if ("DIVIDER".equals(kind)) {
                        continue;
                    }
                    String label = (String) Mod.call(row, "name");
                    out.put(ChatFormatting.stripFormatting(label), labelColor(label));
                }
                return out;
            });
            List<Component> fresh = c.onClient(mc -> {
                List<GuiMessage> all = allMessages(mc);
                List<Component> out = new ArrayList<>();
                for (int i = Math.min(all.size() - before, all.size()) - 1; i >= 0; i--) {
                    out.add(all.get(i).content());   // oldest first
                }
                return out;
            });
            List<String> texts = new ArrayList<>();
            for (Component m : fresh) {
                texts.add(m.getString());
            }
            c.note("HUD rows " + hud.keySet() + "; new chat " + texts);
            List<String> expectRows = List.of("Blood Open", "Watcher", "Portal Entry", "Boss Entry", "Maxor", "Storm",
                    "Terminals", "Goldor", "Necron", "Boss", "Total");
            c.check(hud.keySet().containsAll(expectRows), "premise: the HUD rows were " + hud.keySet());

            // Each split's own line ("Watcher took 47.50s!") and the summary's ("Watcher took 47.50s.").
            List<String> perSplit = List.of("Blood Open", "Watcher", "Portal Entry", "Maxor", "Storm", "Terminals",
                    "Goldor", "Necron");
            int checked = 0;
            List<String> bad = new ArrayList<>();
            for (String name : hud.keySet()) {
                boolean lag = name.equals("Lag");
                String summaryStart = lag ? "Lag: " : name + " took ";
                boolean summarySeen = false;
                boolean splitSeen = false;
                for (Component m : fresh) {
                    String s = m.getString();
                    if (!s.startsWith(summaryStart)) {
                        continue;
                    }
                    Integer color = nameColor(m, name);
                    if (!hud.get(name).equals(color)) {
                        bad.add("'" + s + "': name color " + hex(color) + ", HUD " + hex(hud.get(name)));
                    }
                    checked++;
                    if (s.endsWith("!")) {
                        splitSeen = true;
                    } else {
                        summarySeen = true;
                    }
                }
                c.check(summarySeen, "no summary line for " + name + " in " + texts);
                if (perSplit.contains(name)) {
                    c.check(splitSeen, "no '" + name + " took ...!' line in " + texts);
                }
            }
            c.check(bad.isEmpty(), "segment names not in their HUD color: " + bad);
            c.check(texts.contains("Watcher took 47.50s!") && texts.contains("Watcher took 47.50s."),
                    "Watcher's lines lost their time format: " + texts);
            c.check(texts.contains("Total took 3m 47.11s."), "Total's line lost its time format: " + texts);
            c.check(checked >= 19, "only " + checked + " colored names were checked");
            c.note(checked + " segment names checked against the HUD colors " + hexes(hud));
        } finally {
            run(c, () -> Mod.staticCall(SPLITS, "resetAll"));
        }
    }

    /** The color a HUD label's leading section codes give it (the last color code wins), as RGB, or null. */
    static Integer labelColor(String label) {
        Integer rgb = null;
        for (int i = 0; i + 1 < label.length() && label.charAt(i) == '§'; i += 2) {
            ChatFormatting f = ChatFormatting.getByCode(label.charAt(i + 1));
            if (f != null && f.ordinal() < 16) {
                rgb = Style.EMPTY.applyFormat(f).getColor().getValue() & 0xFFFFFF;
            }
        }
        return rgb;
    }

    /** The color of the first piece of {@code m} whose text is {@code name}, as RGB, or null. */
    static Integer nameColor(Component m, String name) {
        Integer[] out = {null};
        boolean[] found = {false};
        m.visit((style, text) -> {
            if (!found[0] && text.equals(name)) {
                found[0] = true;
                out[0] = style.getColor() == null ? null : style.getColor().getValue() & 0xFFFFFF;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0] ? out[0] : Integer.valueOf(-1);
    }

    static String hex(Integer rgb) {
        return rgb == null ? "none" : rgb == -1 ? "(name not a separate piece)" : String.format("#%06X", rgb);
    }

    static String hexes(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append('=').append(hex(v)).append(' '));
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    static List<GuiMessage> allMessages(Minecraft mc) {
        return (List<GuiMessage>) Mod.field(dev.testkit.compat.McCompat.chat(mc), "allMessages");
    }

    // ---- Core Entry: already inside reads 0 -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Long> coreEntries(Session c) {
        return c.onClient(mc -> (Map<String, Long>) Mod.staticCall(CORE, "entries"));
    }

    private static void coreZero(Session c, boolean ticks) {
        String me = self(c);
        c.server().command("forceload add 40 20 70 70");
        c.ctx().waitTicks(10);
        c.server().command("fill 50 114 54 58 114 62 minecraft:stone");
        try (Settings set = new Settings(c).with(SPLIT_CFG, "Enabled", true)
                .with(SPLIT_CFG, "AnnounceInChat", false)
                .with(SPLIT_CFG, "TickCounting", ticks)
                .with(SPLIT_CFG, "CoreEntryTimes", true)
                .with(SPLIT_CFG, "CoreEntryWithoutSplits", false)
                .with(SPLIT_CFG, "CoreEntrySlowestChat", false)
                .with(SPLIT_CFG, "CoreEntrySlowestParty", false)) {
            dungeon(c);
            run(c, () -> Mod.staticCall(CORE, "reset"));
            c.server().command("tp @a 54.5 115 58.5");
            c.waitUntil("the client stands in the core", mc -> mc.player != null
                    && (Boolean) Mod.staticCall(CORE, "inCore", mc.player.getX(), mc.player.getY(), mc.player.getZ()), 100);
            // HxMateA stands inside the core with us; HxMateB is outside it, on the P4 floor under it.
            c.onClient(mc -> {
                spawn(mc, 0, "HxMateA", 50.5, 115, 60.5);
                spawn(mc, 1, "HxMateB", 54.5, 80, 30.5);
                return null;
            });
            c.ctx().waitTicks(2);
            c.hx().chat("The Core entrance is opening!");
            c.waitUntil("the core opening reached Core Entry", mc -> (Long) Mod.field(CORE, "openedMs") != 0L, 100);
            Map<String, Long> atLine = coreEntries(c);
            // HxMateB walks in once 1.00 s has passed since the line.
            long[] moved = {-1L};
            c.waitUntil("1.00 s after the line", mc -> {
                long opened = (Long) Mod.field(CORE, "openedMs");
                long now = System.currentTimeMillis();
                if (now - opened < 1000L) {
                    return false;
                }
                Entity b = mc.level.getEntity(MATE_ID_BASE + 1);
                b.setPos(56.5, 115, 58.5);
                moved[0] = now - opened;
                return true;
            }, 60);
            c.waitUntil("HxMateB timed into the core", mc -> {
                @SuppressWarnings("unchecked")
                Map<String, Long> m = (Map<String, Long>) Mod.staticCall(CORE, "entries");
                return m.containsKey("HxMateB");
            }, 40);
            Map<String, Long> after = coreEntries(c);
            @SuppressWarnings("unchecked")
            List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(CORE, "lines"));
            long b = after.get("HxMateB");
            c.note((ticks ? "Ticks" : "Every 0.01s") + ": at the line " + atLine + "; HxMateB moved in at +" + moved[0]
                    + " ms, timed " + b + " ms; rows " + lines);
            c.check(Long.valueOf(0L).equals(atLine.get(me)), me + " stood in the core at the line and read " + atLine.get(me) + " ms");
            c.check(Long.valueOf(0L).equals(atLine.get("HxMateA")), "HxMateA stood in the core at the line and read " + atLine.get("HxMateA") + " ms");
            c.check(!atLine.containsKey("HxMateB"), "HxMateB (outside) was timed at the line: " + atLine);
            // The move is made from a tick poll, so it lands 1000-1050 ms after the line; the time must be the move's
            // own moment, seen at the latest one tick later.
            c.check(moved[0] >= 1000L && moved[0] <= 1060L, "premise: HxMateB was moved at +" + moved[0] + " ms");
            c.check(b >= moved[0] && b <= moved[0] + 60L, "HxMateB moved in at +" + moved[0] + " ms and read " + b + " ms");
            String mine = rowFor(lines, me);
            String mateA = rowFor(lines, "HxMateA");
            String mateB = rowFor(lines, "HxMateB");
            c.check(mine != null && mine.endsWith("§f: 0.00s"), "my row: " + mine);
            c.check(mateA != null && mateA.endsWith("§f: 0.00s"), "HxMateA's row: " + mateA);
            String bShown = c.onClient(mc -> String.format(java.util.Locale.US, "%.2fs",
                    ((Number) Mod.staticCall(SPLIT_CFG, "shown", b)).longValue() / 1000.0));
            c.check(mateB != null && mateB.endsWith("§f: " + bShown), "HxMateB's row: " + mateB + " (expected " + bShown + ")");
            if (ticks) {
                c.check(bShown.equals("1.00s") || bShown.equals("1.05s") || bShown.equals("1.10s"), "Ticks shows " + bShown);
            }
        } finally {
            c.onClient(mc -> {
                for (int i = 0; i < 2; i++) {
                    if (mc.level != null && mc.level.getEntity(MATE_ID_BASE + i) != null) {
                        mc.level.removeEntity(MATE_ID_BASE + i, Entity.RemovalReason.DISCARDED);
                    }
                }
                return null;
            });
            c.server().command("tp @a -419.5 151 -419.5");
            c.server().command("forceload remove 40 20 70 70");
            run(c, () -> {
                Mod.staticCall(CORE, "reset");
                Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
            });
            dungeon(c);
        }
    }

    private static String rowFor(List<String> lines, String name) {
        for (String l : lines) {
            if (ChatFormatting.stripFormatting(l).startsWith(name + ":")) {
                return l;
            }
        }
        return null;
    }

    private static void spawn(Minecraft mc, int i, String name, double x, double y, double z) {
        Entity existing = mc.level.getEntity(MATE_ID_BASE + i);
        if (existing != null) {
            existing.setPos(x, y, z);
            return;
        }
        // Random UUIDs are version 4, the filter Core Entry (and Class Colors) applies to players.
        RemotePlayer p = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), name));
        p.setId(MATE_ID_BASE + i);
        p.setPos(x, y, z);
        mc.level.addEntity(p);
    }
}
