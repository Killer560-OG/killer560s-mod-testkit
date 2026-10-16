package dev.testkit.gametest.hx;

import dev.testkit.compat.McCompat;
import dev.testkit.gametest.TextRuns;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Staleness of the FPS pass's caches (fps-combined-1016, killer560 2026-10-16: "make sure the fps optimizers are actually
 * working and working well"). The eight fps-* branches of 2026-10-15 replaced a lot of per-frame work with caches keyed
 * on what the text is made from; a cache that misses an input shows an old value. Every case here changes one cached
 * input through the path Hypixel would use (sidebar, tab list, chat, inventory packets) or through the setting itself,
 * then reads what is DRAWN (the real in-game draw into a throwaway render state) or what the feature holds, tick by tick,
 * and where the mod keeps a cache it also compares the cached answer with one built afresh in the same instant.
 *
 * <p>A case fails when a display shows the old value after its source changed by more than the feature's own refresh
 * period (Custom Scoreboard rebuilds every 5 ticks, SkyblockGate reads every 5: both by design, on main as well), when a
 * cached answer differs from a fresh one, or when a value flips back. Every case also records the latency in ticks, so a
 * main jar and an fps jar run of the same case can be compared line for line. Checks whose mod hook does not exist on a
 * jar (main has no card cache, no label cache) say "n/a" in the notes instead of failing.
 * <ul>
 *   <li>1016-hx-stale-sidebar: keys, purse and area lines (DungeonState's kept sidebar text against a fresh read, the
 *       Custom Scoreboard's drawn lines), and the SKYBLOCK title (SkyblockGate's per-title memo).</li>
 *   <li>1016-hx-stale-scorecalc: crypts and deaths from the tab list, the drawn Score Calculator, its cached lines
 *       against buildLines.</li>
 *   <li>1016-hx-stale-chat-burst: key, door and party lines delivered back to back (ChatObserver's one shared text per
 *       line, and the literal prefilters): every line counted, the last one last.</li>
 *   <li>1016-hx-stale-inventory-hud: the Inventory HUD's drawn count after a server slot change and after an in-place
 *       client change of the same stack.</li>
 *   <li>1016-hx-stale-slot-text: a pet level on an inventory slot after the server replaces the item and after the
 *       stack's name is changed in place.</li>
 *   <li>1016-hx-stale-menu-cards: the mod menu's card text against a fresh layout after Show In Menu changes, and the
 *       description lines drawn or not.</li>
 *   <li>1016-hx-stale-split-timers: the split rows' cached text against a fresh build while the running row moves.</li>
 *   <li>1016-hx-stale-labels: Teammates' distance label cache against the old String.format, Etherwarp Waypoints'
 *       store version bumped by every change.</li>
 * </ul>
 */
final class HxStaleCases {

    private HxStaleCases() {
    }

    static void register(Session s) {
        s.test("1016-hx-stale-sidebar", HxStaleCases::sidebarCase);
        s.test("1016-hx-stale-scorecalc", HxStaleCases::scoreCalc);
        s.test("1016-hx-stale-chat-burst", HxStaleCases::chatBurst);
        s.test("1016-hx-stale-inventory-hud", HxStaleCases::inventoryHud);
        s.test("1016-hx-stale-slot-text", HxStaleCases::slotText);
        s.test("1016-hx-stale-menu-cards", HxStaleCases::menuCards);
        s.test("1016-hx-stale-split-timers", HxStaleCases::splitTimers);
        s.test("1016-hx-stale-labels", HxStaleCases::labels);
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Collected per case; the case fails once at the end, naming every problem. */
    private static final class Result {
        final Session c;
        final List<String> fails = new ArrayList<>();

        Result(Session c) {
            this.c = c;
        }

        void fail(String s) {
            fails.add(s);
            c.note("FAIL " + s);
        }

        void check(boolean ok, String s) {
            if (!ok) {
                fail(s);
            }
        }

        void done() {
            c.check(fails.isEmpty(), fails.size() + " stale or wrong value(s):\n    " + String.join("\n    ", fails));
        }
    }

    /**
     * After a change: polls once a tick, up to {@code max} ticks, for the source (what the display is made from) and the
     * display to show the new value. Then 10 more ticks: neither may flip back. Records "what: source +N, display +M"
     * and fails when the display lags its source by more than {@code allowedLag} ticks or never shows it.
     */
    private static void follow(Result r, String what, Predicate<Minecraft> source, Predicate<Minecraft> display,
                               int allowedLag, int max) {
        int srcAt = -1;
        int dispAt = -1;
        for (int t = 0; t <= max && (srcAt < 0 || dispAt < 0); t++) {
            boolean[] now = r.c.onClient(mc -> new boolean[]{source.test(mc), display.test(mc)});
            if (srcAt < 0 && now[0]) {
                srcAt = t;
            }
            if (dispAt < 0 && now[1]) {
                dispAt = t;
            }
            if (srcAt < 0 || dispAt < 0) {
                r.c.ctx().waitTicks(1);
            }
        }
        if (srcAt < 0) {
            r.fail(what + ": the source never changed in " + max + " ticks (the test could not make the change)");
            return;
        }
        if (dispAt < 0) {
            r.fail(what + ": source changed at +" + srcAt + " ticks, the display still showed the old value " + max
                    + " ticks after the change");
            return;
        }
        int lag = dispAt - srcAt;
        r.c.note(String.format(Locale.ROOT, "%s: source +%d, display +%d ticks (lag %d, allowed %d)", what, srcAt,
                dispAt, lag, allowedLag));
        r.check(lag <= allowedLag, what + ": the display followed its source " + lag + " ticks late (allowed "
                + allowedLag + ")");
        for (int t = 0; t < 10; t++) {
            r.c.ctx().waitTicks(1);
            boolean[] now = r.c.onClient(mc -> new boolean[]{source.test(mc), display.test(mc)});
            if (now[0] && !now[1]) {
                r.fail(what + ": the display went back to an old value " + (t + 1) + " ticks after it had followed");
                return;
            }
        }
    }

    /** Every text run {@code draw} puts in a fresh render state, " | " separated (HudTextCache scope open, as in game). */
    static String drawn(Minecraft mc, Consumer<GuiGraphicsExtractor> draw) {
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        boolean scope = Mod.has("hud.HudTextCache");
        if (scope) {
            Mod.staticCall("hud.HudTextCache", "begin");
        }
        try {
            draw.accept(g);
        } finally {
            if (scope) {
                Mod.staticCall("hud.HudTextCache", "end");
            }
        }
        StringBuilder sb = new StringBuilder();
        state.forEachText(t -> sb.append(TextRuns.string(t)).append(" | "));
        return sb.toString();
    }

    /** One HUD element drawn as the HUD draws it (position, scale, render). */
    static String element(Minecraft mc, String id) {
        Object el = Mod.staticCall("hud.HudElementRegistry", "byId", id);
        if (el == null) {
            return "";
        }
        return drawn(mc, g -> Mod.staticCall("hud.HudElementRegistry", "drawAt", g, el));
    }

    static boolean hasMethod(String cls, String name) {
        if (!Mod.has(cls)) {
            return false;
        }
        for (Class<?> k = Mod.cls(cls); k != null; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasField(String cls, String name) {
        try {
            Mod.cls(cls).getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException | RuntimeException | AssertionError e) {
            return false;
        }
    }

    /** The vanilla sidebar's lines as the client holds them (team prefix + suffix or the entry's display). */
    static String vanillaSidebar(Minecraft mc) {
        if (mc.level == null) {
            return "";
        }
        Scoreboard sb = mc.level.getScoreboard();
        Objective o = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (o == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(o.getDisplayName().getString()).append(" || ");
        for (PlayerScoreEntry e : sb.listPlayerScores(o)) {
            if (e.display() != null) {
                out.append(e.display().getString());
            } else {
                PlayerTeam team = sb.getPlayersTeam(e.owner());
                if (team != null) {
                    out.append(team.getPlayerPrefix().getString()).append(team.getPlayerSuffix().getString());
                }
            }
            out.append(" | ");
        }
        return out.toString();
    }

    static String plain(String s) {
        String p = net.minecraft.ChatFormatting.stripFormatting(s);
        return p == null ? "" : p.trim();
    }

    private static List<Object> dungeonSidebar(String keys, String purse, String area, String title) {
        List<Object> kv = new ArrayList<>(List.of("template", "dungeon", "floor", "F7", "keys", keys,
                "extra", List.of("Purse: §6" + purse, "Area Probe: §b" + area)));
        if (title != null) {
            kv.add("title");
            kv.add(title);
        }
        return kv;
    }

    // ---- 1016-hx-stale-sidebar ------------------------------------------------------------------------------------

    private static void sidebarCase(Session c) {
        Result r = new Result(c);
        try (Settings set = new Settings(c).with("scoreboard.CustomScoreboardConfig", "Enabled", true)) {
            dungeon(c);
            HxKit.sidebar(c, dungeonSidebar("0x", "111,111", "Alpha", null).toArray());
            c.ctx().waitTicks(15);
            boolean kept = hasField("secrets.DungeonState", "sidebarText");
            // DungeonState: what the client thread keeps against a read made afresh on this (test) thread, where the
            // mod never caches. The client is parked between test steps, so the scoreboard does not move under it.
            String[] keptVsFresh = keptVsFresh(c);
            r.check(keptVsFresh[0].equals(keptVsFresh[1]), "DungeonState's kept sidebar text differs from a fresh read"
                    + " before any change:\n      kept  " + keptVsFresh[0] + "\n      fresh " + keptVsFresh[1]);
            c.note("DungeonState kept-vs-fresh hook: " + (kept ? "yes" : "n/a"));

            // 1. Keys 0x -> 1x: DungeonState's count and the board's line.
            HxKit.sidebar(c, dungeonSidebar("1x", "111,111", "Alpha", null).toArray());
            follow(r, "Keys line -> DungeonState.sidebarWitherKeys", mc -> vanillaSidebar(mc).contains("1x"),
                    mc -> ((Number) Mod.staticCall("secrets.DungeonState", "sidebarWitherKeys")).intValue() == 1, 1, 40);
            follow(r, "Keys line -> Custom Scoreboard drawn", mc -> vanillaSidebar(mc).contains("1x"),
                    mc -> drawnBoard(mc).contains("1x"), 6, 40);
            keptVsFresh = keptVsFresh(c);
            r.check(keptVsFresh[0].equals(keptVsFresh[1]), "after the keys change DungeonState's kept sidebar text"
                    + " differs from a fresh read:\n      kept  " + keptVsFresh[0] + "\n      fresh " + keptVsFresh[1]);

            // 2. Purse and another line: the board's drawn text.
            HxKit.sidebar(c, dungeonSidebar("1x", "222,333", "Bravo", null).toArray());
            follow(r, "Purse line -> Custom Scoreboard drawn", mc -> vanillaSidebar(mc).contains("222,333"),
                    mc -> drawnBoard(mc).contains("222,333") && !drawnBoard(mc).contains("111,111"), 6, 40);
            follow(r, "Area line -> Custom Scoreboard drawn", mc -> vanillaSidebar(mc).contains("Bravo"),
                    mc -> drawnBoard(mc).contains("Bravo") && !drawnBoard(mc).contains("Alpha"), 6, 40);
            keptVsFresh = keptVsFresh(c);
            r.check(keptVsFresh[0].equals(keptVsFresh[1]), "after the purse change DungeonState's kept sidebar text"
                    + " differs from a fresh read:\n      kept  " + keptVsFresh[0] + "\n      fresh " + keptVsFresh[1]);

            // 3. The title: SkyblockGate keeps its answer per title component.
            HxKit.sidebar(c, dungeonSidebar("1x", "222,333", "Bravo", "§e§lPROTOTYPE").toArray());
            follow(r, "Title SKYBLOCK -> PROTOTYPE -> SkyblockGate off", mc -> plain(vanillaSidebar(mc)).startsWith("PROTOTYPE"),
                    mc -> !(Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 6, 40);
            HxKit.sidebar(c, dungeonSidebar("1x", "222,333", "Bravo", null).toArray());
            follow(r, "Title PROTOTYPE -> SKYBLOCK -> SkyblockGate on", mc -> plain(vanillaSidebar(mc)).startsWith("SKYBLOCK"),
                    mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isOnSkyblock"), 6, 40);
            c.note("board drawn now: " + c.onClient(HxStaleCases::drawnBoard));
        } finally {
            hub(c);
        }
        r.done();
    }

    private static String drawnBoard(Minecraft mc) {
        return drawn(mc, g -> Mod.staticCall("scoreboard.CustomScoreboardFeature", "drawInGame", g));
    }

    /** {kept (client thread), fresh (this thread)} DungeonState sidebar text. */
    private static String[] keptVsFresh(Session c) {
        return c.onClient(mc -> new String[]{(String) Mod.staticCall("secrets.DungeonState", "readSidebarText"),
                (String) offThread(() -> Mod.staticCall("secrets.DungeonState", "readSidebarText"))});
    }

    /**
     * {@code fn} on a plain thread of its own, joined, while the client thread waits in this call: the mod's caches are
     * client-thread only, so off it the same method builds its answer afresh. Call from the client thread.
     */
    static Object offThread(java.util.function.Supplier<Object> fn) {
        Object[] out = new Object[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = fn.get();
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "hx-stale-fresh");
        t.start();
        try {
            t.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (err[0] != null) {
            throw new AssertionError("the fresh build threw: " + err[0], err[0]);
        }
        return out[0];
    }

    // ---- 1016-hx-stale-scorecalc ----------------------------------------------------------------------------------

    private static void dungeonTab(Session c, int crypts, int deaths) {
        tab(c, "template", "dungeon", "players", List.of(player("HxMateA", "Mage", "[MVP+]"),
                        player("HxMateB", "Berserk", null), player(self(c), "Archer", null)),
                "secrets", 12, "secretsPercent", "34.5", "crypts", crypts, "rooms", 9, "deaths", deaths);
    }

    private static void scoreCalc(Session c) {
        Result r = new Result(c);
        String f = "scorecalc.ScoreCalculatorFeature";
        try (Settings set = new Settings(c).with("scorecalc.ScoreCalculatorConfig", "Enabled", true)
                .with("scorecalc.ScoreCalculatorConfig", "PrinceAlertEnabled", false)
                .with("scorecalc.ScoreCalculatorConfig", "ShowBreakdown", true)) {
            dungeon(c);
            dungeonTab(c, 2, 0);
            c.waitUntil("crypts 2 from the tab", mc -> ((Number) Mod.staticCall(f, "getCrypts")).intValue() == 2, 100);
            c.ctx().waitTicks(10);
            boolean cached = hasMethod(f, "buildLines") && hasMethod(f, "lines");
            String before = c.onClient(mc -> element(mc, "score_calculator"));
            c.note("drawn before: " + before);
            r.check(!before.isBlank(), "the Score Calculator drew nothing in the dungeon");

            int[] cmp = {0, 0};
            Predicate<Minecraft> same = mc -> {
                if (!cached) {
                    return true;
                }
                Object cfg = Mod.cfg("scorecalc.ScoreCalculatorConfig");
                Object k = Mod.staticCall(f, "lines", cfg, false);
                Object fresh = Mod.staticCall(f, "buildLines", cfg, false);
                cmp[0]++;
                if (!k.equals(fresh)) {
                    cmp[1]++;
                    return false;
                }
                return true;
            };
            dungeonTab(c, 5, 0);
            follow(r, "Crypts 2 -> 5 -> drawn Score Calculator",
                    mc -> ((Number) Mod.staticCall(f, "getCrypts")).intValue() == 5,
                    mc -> !element(mc, "score_calculator").equals(before) && same.test(mc), 1, 60);
            String afterCrypts = c.onClient(mc -> element(mc, "score_calculator"));
            dungeonTab(c, 5, 2);
            follow(r, "Deaths 0 -> 2 -> drawn Score Calculator",
                    mc -> ((Number) Mod.field(f, "deaths")).intValue() == 2,
                    mc -> !element(mc, "score_calculator").equals(afterCrypts) && same.test(mc), 1, 60);
            for (int t = 0; t < 20; t++) {
                boolean ok = c.onClient(same::test);
                if (!ok) {
                    r.fail("Score Calculator's cached lines differ from buildLines " + t + " ticks after the deaths change");
                    break;
                }
                c.ctx().waitTicks(1);
            }
            c.note("cached lines == buildLines: " + (cached ? cmp[0] + " comparisons, " + cmp[1] + " differed" : "n/a (no cache)"));
            c.note("drawn after: " + c.onClient(mc -> element(mc, "score_calculator")));
        } finally {
            hub(c);
        }
        r.done();
    }

    // ---- 1016-hx-stale-chat-burst ---------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void chatBurst(Session c) {
        Result r = new Result(c);
        String keys = "doorkeys.DungeonKeys";
        hub(c);
        c.ctx().runOnClient(mc -> {
            Mod.setField(keys, "chatWither", 0);
            Mod.setField(keys, "chatBlood", false);
        });
        c.ctx().waitTicks(2);
        // One burst, nothing waited between the lines: the client handles them one after another in the same tick.
        String[] lines = {
                "HxKeyA has obtained Wither Key!",
                "Party > [MVP+] HxChatter: stale check one",
                "HxKeyB has obtained Wither Key!",
                "§7[VIP] HxEvil§f: HxKeyC has obtained Wither Key!",   // forged: must not count
                "HxJoiner joined the party.",
                "HxKeyA opened a WITHER door!",
                "HxKeyB has obtained Blood Key!",
        };
        for (String line : lines) {
            c.hx().chat(line);
        }
        follow(r, "burst -> two Wither keys, one door, blood key (DungeonKeys)", mc -> true,
                mc -> ((Number) Mod.field(keys, "chatWither")).intValue() == 1 && (Boolean) Mod.field(keys, "chatBlood"),
                3, 40);
        String last = c.onClient(mc -> (String) Mod.staticCall(keys, "lastPickupLine"));
        r.check("HxKeyB has obtained Blood Key!".equals(last), "the last pickup line is '" + last
                + "', not the burst's last key line (a reader got another line's text)");
        follow(r, "burst -> party chat and join (PartyTracker)", mc -> true, mc -> {
            List<String> mates = (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
            return mates.contains("HxChatter") && mates.contains("HxJoiner");
        }, 3, 40);
        int wither = c.onClient(mc -> ((Number) Mod.field(keys, "chatWither")).intValue());
        r.check(wither == 1, "the forged all-chat key line counted (wither " + wither + ")");
        // Leave the burst's party state behind: two leave lines (also checks the LEFT path's prefilter).
        c.hx().chat("HxChatter has left the party.");
        c.hx().chat("HxJoiner has left the party.");
        follow(r, "leave lines -> PartyTracker", mc -> true, mc -> {
            List<String> mates = (List<String>) Mod.staticCall("leapmenu.PartyTracker", "teammates");
            return !mates.contains("HxChatter") && !mates.contains("HxJoiner");
        }, 3, 40);
        c.ctx().runOnClient(mc -> {
            Mod.setField(keys, "chatWither", 0);
            Mod.setField(keys, "chatBlood", false);
        });
        r.done();
    }

    // ---- 1016-hx-stale-inventory-hud ------------------------------------------------------------------------------

    private static void giveCount(Session c, int slot, String item, int count) {
        c.hx().call("give", "slot", slot, "stack", item, "count", count);
    }

    private static void inventoryHud(Session c) {
        Result r = new Result(c);
        String cfg = "inventoryhud.InventoryHudConfig";
        try (Settings set = new Settings(c).with(cfg, "Enabled", true)
                .with(cfg, "Visibility", c.onClient(mc -> Mod.enumValue(cfg + "$Visibility", "ALWAYS")))
                .with(cfg, "ShowCounts", true).with(cfg, "HideInScreens", false).with(cfg, "HideWhenEmpty", false)) {
            giveCount(c, 10, "minecraft:dirt", 37);
            follow(r, "server slot 10 dirt x37 -> Inventory HUD drawn", mc -> mc.player.getInventory().getItem(10).getCount() == 37,
                    mc -> drawnInventory(mc).contains("37"), 0, 40);
            giveCount(c, 10, "minecraft:dirt", 23);
            follow(r, "server slot 10 x37 -> x23 -> Inventory HUD drawn", mc -> mc.player.getInventory().getItem(10).getCount() == 23,
                    mc -> drawnInventory(mc).contains("23") && !drawnInventory(mc).contains("37"), 0, 40);
            // The same ItemStack object changed in place on the client (what a click prediction does).
            String inPlace = c.onClient(mc -> {
                mc.player.getInventory().getItem(10).setCount(51);
                return drawnInventory(mc);
            });
            r.check(inPlace.contains("51") && !inPlace.contains("23"), "the Inventory HUD drawn in the frame after an"
                    + " in-place count change still shows the old count: " + inPlace);
            c.note("in-place x51, drawn: " + inPlace);
        } finally {
            c.hx().give(10, "minecraft:air");
        }
        r.done();
    }

    private static String drawnInventory(Minecraft mc) {
        return drawn(mc, g -> Mod.staticCall("inventoryhud.InventoryHudFeature", "drawInGame", g, 0f));
    }

    // ---- 1016-hx-stale-slot-text ----------------------------------------------------------------------------------

    private static void slotText(Session c) {
        Result r = new Result(c);
        String cfg = "slottext.SlotTextConfig";
        Object pet = c.onClient(mc -> Mod.enumValue("slottext.SlotTextType", "PET_LEVEL"));
        boolean petWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfg), "isTypeEnabled", pet));
        boolean enabledWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(cfg), "isEnabledRaw"));
        try (Settings set = new Settings(c).custom(() -> Mod.call(Mod.cfg(cfg), "setEnabled", true),
                        () -> Mod.call(Mod.cfg(cfg), "setEnabled", enabledWas)).with(cfg, "SkyblockOnly", false)
                .with(cfg, "Mode", c.onClient(mc -> Mod.enumValue(cfg + "$Mode", "ALWAYS")))
                .custom(() -> Mod.call(Mod.cfg(cfg), "setTypeEnabled", pet, true),
                        () -> Mod.call(Mod.cfg(cfg), "setTypeEnabled", pet, petWas))) {
            c.hx().give(11, "minecraft:player_head[custom_name='[Lvl 42] Ender Dragon']");
            c.waitUntil("the pet is in slot 11", mc -> mc.player.getInventory().getItem(11).getHoverName().getString()
                    .contains("Lvl 42"), 60);
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc,
                    new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
            c.ctx().waitTicks(3);
            String first = c.onClient(HxStaleCases::drawnScreen);
            r.check(first.contains("42"), "slot text did not draw the pet's level 42 at all (nothing to test): " + first);
            c.hx().give(11, "minecraft:player_head[custom_name='[Lvl 57] Ender Dragon']");
            follow(r, "server replaces the pet (Lvl 42 -> 57) -> slot text drawn",
                    mc -> mc.player.getInventory().getItem(11).getHoverName().getString().contains("Lvl 57"),
                    mc -> {
                        String d = drawnScreen(mc);
                        return d.contains("57") && !d.contains("42");
                    }, 0, 40);
            String inPlace = c.onClient(mc -> {
                ItemStack st = mc.player.getInventory().getItem(11);
                st.set(DataComponents.CUSTOM_NAME, Component.literal("[Lvl 77] Ender Dragon"));
                return drawnScreen(mc);
            });
            r.check(inPlace.contains("77") && !inPlace.contains("57"), "slot text in the frame after the stack's name"
                    + " changed in place still shows the old level: " + inPlace);
        } finally {
            c.ctx().runOnClient(mc -> McCompat.setScreen(mc, null));
            c.hx().give(11, "minecraft:air");
        }
        r.done();
    }

    /** The open screen's whole frame, text runs only. */
    static String drawnScreen(Minecraft mc) {
        Screen s = McCompat.screen(mc);
        if (s == null) {
            return "";
        }
        GuiRenderState state = new GuiRenderState();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
        s.extractRenderStateWithTooltipAndSubtitles(g, -1, -1, 0.5f);
        StringBuilder sb = new StringBuilder();
        state.forEachText(t -> sb.append(TextRuns.string(t)).append(" | "));
        return sb.toString();
    }

    // ---- 1016-hx-stale-menu-cards ---------------------------------------------------------------------------------

    private static void menuCards(Session c) {
        Result r = new Result(c);
        boolean cache = hasField("gui.FeatureCards", "TEXT");
        boolean showWas = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg("gui.DescriptionConfig"), "isShowMenu"));
        try {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg("gui.DescriptionConfig"), "setShowMenu", true);
                try {
                    McCompat.setScreen(mc, (Screen) Mod.cls("gui.ModScreen").getConstructor(Screen.class)
                            .newInstance((Object) null));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });
            c.ctx().waitTicks(20); // open animation
            String on = c.onClient(HxStaleCases::drawnScreen);
            // Without the card cache (main) there is no laid-out line to look for: the drawn checks below are skipped.
            String probe = cache ? c.onClient(mc -> firstDescriptionLine()) : null;
            c.note("a drawn card description line: '" + probe + "'");
            r.check(!cache || probe != null && on.contains(probe), "no card description line is drawn with Show In Menu on"
                    + " (nothing to test): '" + probe + "'");
            if (cache) {
                cardsLive(c, r, "Show In Menu on");
            } else {
                c.note("card text cache: n/a (no FeatureCards.TEXT on this jar)");
            }
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("gui.DescriptionConfig"), "setShowMenu", false));
            String off = c.onClient(HxStaleCases::drawnScreen);
            r.check(probe == null || !off.contains(probe), "the frame right after Show In Menu went off still draws the"
                    + " card description '" + probe + "'");
            if (cache) {
                cardsLive(c, r, "Show In Menu off");
            }
            c.ctx().runOnClient(mc -> Mod.call(Mod.cfg("gui.DescriptionConfig"), "setShowMenu", true));
            String back = c.onClient(HxStaleCases::drawnScreen);
            r.check(probe == null || back.contains(probe), "the frame right after Show In Menu came back on does not"
                    + " draw '" + probe + "' again");
            if (cache) {
                cardsLive(c, r, "Show In Menu back on");
            }
        } finally {
            c.ctx().runOnClient(mc -> {
                Mod.call(Mod.cfg("gui.DescriptionConfig"), "setShowMenu", showWas);
                McCompat.setScreen(mc, null);
            });
        }
        r.done();
    }

    /** A description line of a card the menu laid out (from the card cache, or the first visible tab's description). */
    @SuppressWarnings("unchecked")
    private static String firstDescriptionLine() {
        if (hasField("gui.FeatureCards", "TEXT")) {
            for (Object v : ((Map<Object, Object>) Mod.field("gui.FeatureCards", "TEXT")).values()) {
                Object text = Mod.call(v, "text");
                List<String> desc = (List<String>) Mod.call(text, "desc");
                if (!desc.isEmpty() && desc.get(0).length() > 6) {
                    return desc.get(0);
                }
            }
            return null;
        }
        List<Object> tabs = (List<Object>) Mod.field("gui.ModScreen", "tabs");
        for (Object tab : tabs) {
            try {
                String d = (String) Mod.staticCall("gui.FeatureCards", "description", tab);
                if (d != null && d.length() > 12) {
                    return d.split(" ")[0] + " " + d.split(" ")[1];
                }
            } catch (AssertionError | RuntimeException e) {
                // a folder tab: no description
            }
        }
        return null;
    }

    /** Draws one more frame, then: every cached card whose inputs match now (so the next draw would use it) must hold
     *  exactly what a fresh layout gives. At least one must be live, or nothing was tested. */
    @SuppressWarnings("unchecked")
    private static void cardsLive(Session c, Result r, String when) {
        String out = c.onClient(mc -> {
            drawnScreen(mc);
            boolean show = (Boolean) Mod.call(Mod.cfg("gui.DescriptionConfig"), "isShowMenu");
            int fv = ((Number) Mod.staticCall("fonts.FontConfig", "version")).intValue();
            Object font = Mod.staticCall("gui.MenuStyle", "font");
            int live = 0;
            List<String> bad = new ArrayList<>();
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) Mod.field("gui.FeatureCards", "TEXT")).entrySet()) {
                Object v = e.getValue();
                boolean vShow = (Boolean) Mod.call(v, "showMenu");
                int vFv = ((Number) Mod.call(v, "fontVersion")).intValue();
                Object desc = show ? Mod.staticCall("gui.FeatureCards", "description", e.getKey()) : null;
                if (vShow != show || vFv != fv || Mod.call(v, "font") != font || !Objects.equals(Mod.call(v, "description"), desc)) {
                    continue; // the next draw of this card lays it out again
                }
                live++;
                Object fresh = Mod.staticCall("gui.FeatureCards", "layoutCardText", e.getKey(), Mod.call(v, "w"),
                        Mod.call(v, "h"), Mod.call(v, "hasSwitch"));
                if (!fresh.equals(Mod.call(v, "text"))) {
                    bad.add(Mod.field(e.getKey(), "name") + ": cached " + Mod.call(v, "text") + " fresh " + fresh);
                }
            }
            return live + "|" + String.join("; ", bad);
        });
        int live = Integer.parseInt(out.substring(0, out.indexOf('|')));
        String bad = out.substring(out.indexOf('|') + 1);
        c.note(when + ": " + live + " live cached cards compared with a fresh layout");
        r.check(live > 0, when + ": no cached card matched the current inputs (nothing compared)");
        r.check(bad.isEmpty(), when + ": cached card text differs from a fresh layout: " + bad);
    }

    // ---- 1016-hx-stale-split-timers -------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void splitTimers(Session c) {
        Result r = new Result(c);
        String f = "splittimers.SplitTimersFeature";
        String el = "splittimers.SplitTimersFeature$SplitTimersHudElement";
        try (Settings set = new Settings(c).with("splittimers.SplitTimersConfig", "Enabled", true)) {
            dungeon(c);
            long t0 = System.currentTimeMillis() - 4000L;
            c.ctx().runOnClient(mc -> {
                Mod.staticCall(f, "onLine", "Starting in 1 second.", t0);
                Mod.staticCall(f, "onLine", "[NPC] Mort: Here, I found this map when I first entered the dungeon.", t0 + 1000L);
                Mod.staticCall(f, "onLine", "[BOSS] The Watcher: Congratulations, you made it through the Entrance.", t0 + 3000L);
            });
            Set<String> runningSeen = new LinkedHashSet<>();
            int compared = 0;
            for (int t = 0; t < 30; t++) {
                long now = System.currentTimeMillis();
                Object[] got = c.onClient(mc -> {
                    List<?> rows = (List<?>) Mod.staticCall(f, "layoutRows", now);
                    List<String> kept = new ArrayList<>((List<String>) Mod.staticCall(el, "rowTexts", rows));
                    // Off the render thread rowTexts never reuses (or stores) a row's text: a fresh build of the same rows.
                    Object freshRows = offThread(() -> Mod.staticCall(el, "rowTexts", rows));
                    return new Object[]{rows, kept, element(mc, "split_timers"), freshRows};
                });
                List<String> fresh = (List<String>) got[3];
                compared++;
                if (!fresh.equals(got[1])) {
                    r.fail("split rows' kept text differs from a fresh build at tick " + t + ":\n      kept  " + got[1]
                            + "\n      fresh " + fresh);
                    break;
                }
                for (String row : (List<String>) got[1]) {
                    if (row.contains("Watcher")) {
                        runningSeen.add(row);
                    }
                }
                c.ctx().waitTicks(1);
            }
            c.note(compared + " ticks: kept rows == fresh; running Watcher row showed " + runningSeen.size()
                    + " distinct values, e.g. " + runningSeen.stream().limit(3).toList());
            r.check(runningSeen.size() >= 10, "the running Watcher row showed only " + runningSeen.size()
                    + " distinct times in 30 ticks (stuck): " + runningSeen);
            String drawn = c.onClient(mc -> element(mc, "split_timers"));
            r.check(drawn.contains("Blood Open"), "the split timers HUD does not draw the finished Blood Open row: " + drawn);
            c.note("drawn: " + drawn);
        } finally {
            c.ctx().runOnClient(mc -> {
                try {
                    Mod.setField(f, "run", null);
                } catch (AssertionError | RuntimeException e) {
                    // older jar: the run ends with the dungeon
                }
            });
            hub(c);
        }
        r.done();
    }

    // ---- 1016-hx-stale-labels -------------------------------------------------------------------------------------

    private static void labels(Session c) {
        Result r = new Result(c);
        String tf = "teammates.TeammatesFeature";
        String tc = "teammates.TeammatesConfig";
        boolean newLabel = c.onClient(mc -> {
            for (java.lang.reflect.Method m : Mod.cls(tf).getDeclaredMethods()) {
                if (m.getName().equals("labelText") && m.getParameterCount() == 4) {
                    return true;
                }
            }
            return false;
        });
        try (Settings set = new Settings(c).with(tc, "ShowName", true).with(tc, "ShowDistance", true)) {
            String out = c.onClient(mc -> {
                Object cfg = Mod.cfg(tc);
                Random rng = new Random(1016L);
                String[] names = {"HxMateA", "HxMateB", "HxRenamed"};
                int n = 0;
                for (int i = 0; i < 6000; i++) {
                    boolean showName = rng.nextInt(5) != 0;
                    Mod.call(cfg, "setShowName", showName);
                    int id = rng.nextInt(4);
                    String name = names[rng.nextInt(names.length)];
                    // Distances around and on the .5 boundaries, where rounding modes differ, and plain ones.
                    double d = rng.nextBoolean() ? rng.nextInt(120) + 0.5 + (rng.nextInt(3) - 1) * 1e-9 : rng.nextDouble() * 150;
                    String want = String.format(Locale.US, "%.0fm", d);
                    want = showName ? name + " §7(" + want + ")" : want;
                    String got = newLabel ? (String) Mod.staticCall(tf, "labelText", cfg, id, name, d)
                            : (String) Mod.staticCall(tf, "labelText", cfg, name, d);
                    n++;
                    if (!want.equals(got)) {
                        return "label for id " + id + " '" + name + "' at " + d + " m (show name " + showName + "): '"
                                + got + "', String.format gives '" + want + "' (after " + n + " calls)";
                    }
                }
                return "";
            });
            r.check(out.isEmpty(), "Teammates distance label: " + out);
            c.note("Teammates label: 6000 random (id, name, distance, show name) calls == String.format"
                    + (newLabel ? " (cached labelText)" : " (main's labelText)"));
        }
        // Etherwarp Waypoints: the per-room draw cache is keyed on the store's version; every change must move it.
        if (hasMethod("etherwarp.EtherwarpWaypointsStore", "version")) {
            String store = "etherwarp.EtherwarpWaypointsStore";
            String out = c.onClient(mc -> {
                List<String> bad = new ArrayList<>();
                int v0 = ((Number) Mod.staticCall(store, "version")).intValue();
                Object w;
                try {
                    w = Mod.cls("etherwarp.EtherwarpWaypoint").getConstructor(String.class, String.class, String.class,
                            int.class, int.class, int.class, int.class).newInstance("hx-stale-1016", "HxStale",
                            "HxStaleRoom", 1, 2, 3, 1);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                Mod.staticCall(store, "add", w);
                int v1 = ((Number) Mod.staticCall(store, "version")).intValue();
                if (v1 == v0) {
                    bad.add("add");
                }
                Mod.staticCall(store, "remove", "hx-stale-1016");
                int v2 = ((Number) Mod.staticCall(store, "version")).intValue();
                if (v2 == v1) {
                    bad.add("remove");
                }
                Mod.staticCall(store, "clearRoom", "HxStaleRoom");
                int v3 = ((Number) Mod.staticCall(store, "version")).intValue();
                if (v3 == v2) {
                    bad.add("clearRoom");
                }
                return String.join(", ", bad);
            });
            r.check(out.isEmpty(), "Etherwarp Waypoints: the store version did not move on " + out
                    + " (the room's drawn boxes and numbers would stay stale)");
            c.note("Etherwarp store version moves on add, remove, clearRoom");
        } else {
            c.note("Etherwarp store version: n/a on this jar");
        }
        r.done();
    }
}
