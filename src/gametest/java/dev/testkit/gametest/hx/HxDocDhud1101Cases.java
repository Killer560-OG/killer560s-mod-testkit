package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * To-do Doc items of 2026-11-01 (mod branch doc-dhud-1101): Score Warning, Split Timers' Counting, Core Entry Times,
 * Livid's timer unit and "Fill", Blessings Boss Only, the Score Calculator's Trigger lines, the secret counter's locked
 * chests and chest drops, and Door Keys' one-color sphere. Each case proves the feature ACTED (a title shown, a time
 * rounded, a player timed into the core, a secret taken back) and stays quiet where it must.
 */
final class HxDocDhud1101Cases {

    static final String SCORE_CFG = "scorecalc.ScoreCalculatorConfig";
    static final String WARN = "scorecalc.ScoreWarnings";
    static final String WATCHER_LINE = "[BOSS] The Watcher: You have proven yourself. You may pass.";
    static final String SPLIT_CFG = "splittimers.SplitTimersConfig";
    static final String SPLITS = "splittimers.SplitTimersFeature";
    static final String CORE = "splittimers.CoreEntryTimes";

    private HxDocDhud1101Cases() {
    }

    static void register(Session s) {
        s.test("1101-dhud-cryptreminder", HxDocDhud1101Cases::cryptReminder);
        s.test("1101-dhud-preboss", HxDocDhud1101Cases::preBoss);
        s.test("1101-dhud-splitticks", HxDocDhud1101Cases::splitTicks);
        s.test("1101-dhud-coreentry", HxDocDhud1101Cases::coreEntry);
        s.test("1101-dhud-livid", HxDocDhud1101Cases::livid);
        s.test("1101-dhud-blessings", HxDocDhud1101Cases::blessings);
        s.test("1101-dhud-triggerlines", HxDocDhud1101Cases::triggerLines);
        s.test("1101-dhud-lockedchest", HxDocDhud1101Cases::lockedChest);
        s.test("1101-dhud-chestdrop", HxDocDhud1101Cases::chestDrop);
        s.test("1101-dhud-doorkeys", HxDocDhud1101Cases::doorKeys);
    }

    // ---- Score Warning ------------------------------------------------------------------------------------------------

    private static void cryptReminder(Session c) {
        int def = c.onClient(mc -> (Integer) Mod.field(SCORE_CFG, "DEFAULT_CRYPT_REMINDER_SECONDS"));
        int min = c.onClient(mc -> (Integer) Mod.field(SCORE_CFG, "MIN_CRYPT_REMINDER_SECONDS"));
        int max = c.onClient(mc -> (Integer) Mod.field(SCORE_CFG, "MAX_CRYPT_REMINDER_SECONDS"));
        try (Settings set = new Settings(c).with(SCORE_CFG, "CryptReminderSeconds", 47)) {
            int stepped = c.onClient(mc -> (Integer) Mod.get(SCORE_CFG, "getCryptReminderSeconds"));
            run(c, () -> Mod.set(SCORE_CFG, "setCryptReminderSeconds", 600));
            int high = c.onClient(mc -> (Integer) Mod.get(SCORE_CFG, "getCryptReminderSeconds"));
            run(c, () -> Mod.set(SCORE_CFG, "setCryptReminderSeconds", 1));
            int low = c.onClient(mc -> (Integer) Mod.get(SCORE_CFG, "getCryptReminderSeconds"));
            c.check(def == 45, "default " + def + " s, not 45");
            c.check(min == 5 && max == 120, "range " + min + ".." + max + ", not 5..120");
            c.check(stepped == 45 && high == 120 && low == 5, "47 -> " + stepped + ", 600 -> " + high + ", 1 -> " + low);
            c.note("default " + def + " s, range " + min + ".." + max + "; 47 -> " + stepped + ", 600 -> " + high + ", 1 -> " + low);
        }
    }

    private static int warnings(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(WARN, "warningsShown"));
    }

    private static void freshRun(Session c) {
        run(c, () -> {
            Mod.staticCall(WARN, "reset");
            Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
            Mod.setField("scorecalc.ScoreCalculatorFeature", "inBoss", false);
            Mod.setField("scorecalc.ScoreCalculatorFeature", "bloodDone", false);
            Mod.setField("scorecalc.ScoreCalculatorFeature", "expectingBloodUpdate", false);
        });
    }

    private static void preBoss(Session c) {
        try (Settings set = new Settings(c).with(SCORE_CFG, "Enabled", false)
                .with(SCORE_CFG, "CryptReminder", false)
                .with(SCORE_CFG, "PreBossWarning", true)
                .with(SCORE_CFG, "PreBossMageOnly", false)
                .with(SCORE_CFG, "PreBossPermanent", false)
                .with(SCORE_CFG, "PreBossListMissing", true)
                .with(SCORE_CFG, "ScoreWarningSound", false)) {
            dungeon(c, "crypts", 2, "secrets", 10, "secretsPercent", "30.0", "rooms", 20);
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "05m 00s", "cleared", 80);
            freshRun(c);
            c.waitUntil("the calculator reads crypts 2",
                    mc -> ((Number) Mod.staticCall("scorecalc.ScoreCalculatorFeature", "getCrypts")).intValue() == 2, 100);

            // 1. On the Watcher's line itself - no "Cleared" change, no 3 s fallback.
            int base = warnings(c);
            c.hx().chat(WATCHER_LINE);
            // The sidebar never changes (no blood "Cleared" update): a warning at all is the one on the line itself. 100 ticks only
            // for the chat line's own trip under a loaded machine (26.2 once took more than 10).
            c.waitUntil("the pre-boss warning on the Watcher's line", mc -> (Integer) Mod.staticCall(WARN, "warningsShown") == base + 1, 100);
            String first = c.onClient(mc -> (String) Mod.staticCall(WARN, "lastWarning"));
            c.check(first != null && first.contains("Before the boss") && first.contains("Crypts 2/5")
                    && first.contains("Cleared 88%"), "warning was '" + first + "' (cleared 80% of 25 rooms + blood + boss = 88%)");

            // 2. Mage Only while playing Archer: nothing.
            // ChatObserver drops an identical line within 250 ms as a duplicate delivery: space the Watcher lines out.
            c.ctx().waitTicks(10);
            freshRun(c);
            run(c, () -> Mod.set(SCORE_CFG, "setPreBossMageOnly", true));
            int beforeMage = warnings(c);
            c.hx().chat(WATCHER_LINE);
            // Not vacuous: wait until the line has reached the mod (it sets watcherDoneTick whatever the class).
            c.waitUntil("the Watcher's line reached Score Warning", mc -> (Integer) Mod.field(WARN, "watcherDoneTick") >= 0, 100);
            c.ctx().waitTicks(5);
            int afterMage = warnings(c);
            c.check(afterMage == beforeMage, "Mage Only warned an Archer (" + beforeMage + " -> " + afterMage + ")");
            run(c, () -> Mod.set(SCORE_CFG, "setPreBossMageOnly", false));

            // 3. Permanent: DO NOT ENTER PORTAL with the list, held up (refreshed) - then the line alone.
            c.ctx().waitTicks(10);
            freshRun(c);
            run(c, () -> Mod.set(SCORE_CFG, "setPreBossPermanent", true));
            c.hx().chat(WATCHER_LINE);
            c.waitUntil("DO NOT ENTER PORTAL", mc -> String.valueOf(Mod.staticCall(WARN, "lastWarning")).contains("DO NOT ENTER PORTAL"), 100);
            String red = c.onClient(mc -> (String) Mod.staticCall(WARN, "lastWarning"));
            c.ctx().waitTicks(60);   // three seconds: a one-shot title (25 ticks) would be gone
            String held = c.onClient(HxAlerts1025Cases::guiTitle);
            String heldSub = c.onClient(HxDocDhud1101Cases::guiSubtitle);
            run(c, () -> Mod.set(SCORE_CFG, "setPreBossListMissing", false));
            c.waitUntil("the line alone", mc -> "§c§lDO NOT ENTER PORTAL".equals(Mod.staticCall(WARN, "lastWarning")), 20);
            String alone = c.onClient(mc -> (String) Mod.staticCall(WARN, "lastWarning"));
            c.check(red.contains("Crypts 2/5"), "DO NOT ENTER PORTAL listed nothing: '" + red + "'");
            c.check(held != null && held.contains("DO NOT ENTER PORTAL"), "after 3 s the title on screen was '" + held + "'");
            c.check(heldSub != null && heldSub.contains("Crypts 2/5"), "after 3 s the subtitle was '" + heldSub + "'");

            // 4. Nothing missing: the green ENTER THE PORTAL.
            dungeon(c, "crypts", 5, "secrets", 60, "secretsPercent", "100.0", "rooms", 25);
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "05m 10s", "cleared", 100);
            c.waitUntil("ENTER THE PORTAL", mc -> "§a§lENTER THE PORTAL".equals(Mod.staticCall(WARN, "lastWarning")), 100);
            String green = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(green != null && green.equals("§a§lENTER THE PORTAL"), "green title on screen was '" + green + "'");
            c.note("Watcher's line -> '" + first + "' at once; Mage Only as Archer: none; Permanent '" + red + "', after 3 s '"
                    + held + "' / '" + heldSub + "'; List Missing off '" + alone + "'; all done -> '" + green + "'");
        } finally {
            freshRun(c);
            dungeon(c);
        }
    }

    static String guiSubtitle(Minecraft mc) {
        Object holder = mc.gui;
        try {
            Field hud = mc.gui.getClass().getField("hud");
            holder = hud.get(mc.gui);
        } catch (ReflectiveOperationException e) {
            // 26.1: on Gui itself
        }
        try {
            Field f = holder.getClass().getDeclaredField("subtitle");
            f.setAccessible(true);
            Object t = f.get(holder);
            return t == null ? null : ((Component) t).getString();
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    // ---- Split Timers: Counting ---------------------------------------------------------------------------------------

    private static void splitTicks(Session c) {
        if (!c.onClient(mc -> Mod.isCheat())) {
            boolean shown = c.onClient(mc -> (Boolean) Mod.get(SPLIT_CFG, "isTickCounting"));
            c.check(!shown, "a legit jar counts in ticks");
            c.note("legit jar: Counting is cheat only, isTickCounting false");
            return;
        }
        try (Settings set = new Settings(c).with(SPLIT_CFG, "TickCounting", false)) {
            String a = c.onClient(mc -> (String) Mod.staticCall(SPLITS, "formatTime", 12_324L));
            String b = c.onClient(mc -> (String) Mod.staticCall(SPLITS, "formatTime", 62_376L));
            run(c, () -> Mod.set(SPLIT_CFG, "setTickCounting", true));
            String at = c.onClient(mc -> (String) Mod.staticCall(SPLITS, "formatTime", 12_324L));
            String bt = c.onClient(mc -> (String) Mod.staticCall(SPLITS, "formatTime", 62_376L));
            String lag = c.onClient(mc -> (String) Mod.staticCall(SPLITS, "lagSeconds", 3_337L));
            c.check("12.32s".equals(a) && "1m 2.38s".equals(b), "every 0.01s: " + a + ", " + b);
            c.check("12.30s".equals(at) && "1m 2.40s".equals(bt) && "3.35s".equals(lag), "ticks: " + at + ", " + bt + ", lag " + lag);
            c.note("12324 ms / 62376 ms: " + a + " / " + b + " -> ticks " + at + " / " + bt + "; lag 3337 ms -> " + lag);
        }
    }

    // ---- Core Entry Times ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Long> coreEntries(Session c) {
        return c.onClient(mc -> (Map<String, Long>) Mod.staticCall(CORE, "entries"));
    }

    private static void coreEntry(Session c) {
        String me = self(c);
        c.server().command("forceload add 40 20 70 70");
        c.ctx().waitTicks(10);
        c.server().command("fill 50 79 26 58 79 34 minecraft:stone");
        c.server().command("fill 50 114 54 58 114 62 minecraft:stone");
        try (Settings set = new Settings(c).with(SPLIT_CFG, "Enabled", false)
                .with(SPLIT_CFG, "CoreEntryTimes", true)
                .with(SPLIT_CFG, "CoreEntryWithoutSplits", false)
                .with(SPLIT_CFG, "CoreEntrySlowestChat", false)
                .with(SPLIT_CFG, "CoreEntrySlowestParty", false)) {
            dungeon(c);
            run(c, () -> Mod.staticCall(CORE, "reset"));
            // Splits off and Show With Splits Off off: nothing is timed.
            c.hx().chat("The Core entrance is opening!");
            c.server().command("tp @a 54.5 115 58.5");
            c.ctx().waitTicks(20);
            Map<String, Long> off = coreEntries(c);
            c.check(off.isEmpty(), "Core Entry timed with Split Timers off and Show With Splits Off off: " + off);

            // Show With Splits Off: on the P4 band under the core (the old rule) nothing; in the core, timed.
            run(c, () -> Mod.set(SPLIT_CFG, "setCoreEntryWithoutSplits", true));
            c.server().command("tp @a 54.5 80 30.5");
            c.ctx().waitTicks(10);
            c.hx().chat("The Core entrance is opening!");
            c.ctx().waitTicks(20);
            Map<String, Long> p4 = coreEntries(c);
            c.server().command("tp @a 54.5 115 58.5");
            c.waitUntil("timed into the core", mc -> {
                @SuppressWarnings("unchecked")
                Map<String, Long> m = (Map<String, Long>) Mod.staticCall(CORE, "entries");
                return m.containsKey(me);
            }, 40);
            Map<String, Long> in = coreEntries(c);
            @SuppressWarnings("unchecked")
            List<String> lines = c.onClient(mc -> (List<String>) Mod.staticCall(CORE, "lines"));
            boolean hud = c.onClient(mc -> (Boolean) Mod.call(Mod.cfg(SPLIT_CFG), "isCoreEntryAlone"));
            c.check(p4.isEmpty(), "standing on the P4 band (y 80) counted as entering the core: " + p4);
            c.check(in.get(me) != null && in.get(me) >= 1000L, "core entry time " + in + " (tp after ~1.5 s)");
            boolean myLine = false;
            for (String l : lines) {
                myLine |= l.contains(me) && !l.endsWith("-");
            }
            c.check(myLine, "the HUD rows had no time for " + me + ": " + lines);
            c.check(hud, "Core Entry was not running alone with Split Timers off");
            c.note("splits off: none; Show With Splits Off: y 80 under the core none, in the core " + in + "; rows " + lines);
        } finally {
            c.server().command("tp @a -419.5 151 -419.5");
            c.server().command("forceload remove 40 20 70 70");
            run(c, () -> {
                Mod.staticCall(CORE, "reset");
                Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
            });
            dungeon(c);
        }
    }

    // ---- Livid --------------------------------------------------------------------------------------------------------

    private static void livid(Session c) {
        String cfg = "boss.LividSolverConfig";
        try (Settings set = new Settings(c).with(cfg, "TimerSeconds", false)) {
            String ticks = c.onClient(mc -> (String) Mod.staticCall("boss.LividSolverFeature", "timerText", 120));
            run(c, () -> Mod.set(cfg, "setTimerSeconds", true));
            String secs = c.onClient(mc -> (String) Mod.staticCall("boss.LividSolverFeature", "timerText", 120));
            String secs2 = c.onClient(mc -> (String) Mod.staticCall("boss.LividSolverFeature", "timerText", 333));
            String fill = c.onClient(mc -> (String) Mod.field(
                    Mod.enumValue("puzzlesolvers.SolverEspConfig$WaypointStyle", "FULL"), "label"));
            c.check("120t".equals(ticks), "ticks text " + ticks);
            c.check("6.0s".equals(secs) && "16.7s".equals(secs2), "seconds text " + secs + ", " + secs2);
            c.check("Fill".equals(fill), "the full-block style is labeled '" + fill + "'");
            c.note("120 ticks: '" + ticks + "' / '" + secs + "', 333: '" + secs2 + "'; style label '" + fill + "'");
        }
    }

    // ---- Blessings Boss Only ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static int blessingRows(Session c) {
        return c.onClient(mc -> ((List<Object>) Mod.staticCall("blessings.BlessingsFeature$BlessingsHud", "lines")).size());
    }

    private static void blessings(Session c) {
        String cfg = "blessings.BlessingsConfig";
        try (Settings set = new Settings(c).field(cfg, "hud", true).with(cfg, "BossOnly", false)) {
            dungeon(c);
            run(c, () -> {
                Mod.call(Mod.cfg(cfg), "setShown", Mod.enumValue("blessings.Blessing", "POWER"), true);
                @SuppressWarnings("unchecked")
                Map<Object, Integer> levels = (Map<Object, Integer>) Mod.field("blessings.BlessingTracker", "LEVELS");
                levels.put(Mod.enumValue("blessings.Blessing", "POWER"), 12);
                Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
            });
            int clear = blessingRows(c);
            run(c, () -> Mod.set(cfg, "setBossOnly", true));
            int clearBossOnly = blessingRows(c);
            run(c, () -> Mod.setField("livemap.LiveMapFeature", "bossLatched", true));
            int boss = blessingRows(c);
            c.check(clear >= 1, "no Blessings row with the HUD on (" + clear + ")");
            c.check(clearBossOnly == 0, "Boss Only drew " + clearBossOnly + " row(s) in the clear");
            c.check(boss == clear, "Boss Only drew " + boss + " row(s) in the boss (all off: " + clear + ")");
            c.note("rows: clear " + clear + ", Boss Only in the clear " + clearBossOnly + ", Boss Only in the boss " + boss);
        } finally {
            run(c, () -> {
                Mod.staticCall("blessings.BlessingTracker", "reset");
                Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
            });
        }
    }

    // ---- Score Calculator: no Trigger lines ---------------------------------------------------------------------------

    private static void triggerLines(Session c) {
        try (Settings set = new Settings(c).with(SCORE_CFG, "Enabled", true)) {
            triggerLines0(c);
        }
    }

    private static void triggerLines0(Session c) {
        String found = c.onClient(mc -> {
            Object tab = newTab("gui.tab.ScoreCalculatorTab");
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Object w : (List<?>) Mod.call(tab, "buildWidgets", 0, 0, 320, (Runnable) () -> { })) {
                String text = ((net.minecraft.client.gui.components.AbstractWidget) w).getMessage().getString();
                n++;
                if (text.contains("Trigger")) {
                    sb.append(text).append(" | ");
                }
                if (text.contains("Mimic Killed Msg")) {
                    sb.append("<mimic row>");
                }
            }
            return n + ":" + sb;
        });
        c.check(found.contains("<mimic row>") && !found.contains("Trigger"), "Score Calculator rows: " + found);
        c.note("widgets:found = " + found);
    }

    static Object newTab(String name) {
        try {
            java.lang.reflect.Constructor<?> k = Mod.cls(name).getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- Secret counter: locked chests, chest drops -------------------------------------------------------------------

    static final net.minecraft.core.BlockPos CHEST = new net.minecraft.core.BlockPos(-417, 151, -422);
    static final String AWAIT = "autoroutes.AwaitEvents";
    static final String MEMORY = "autoroutes.RouteRunMemory";

    private static void clickChest(Session c) {
        c.onClient(mc -> {
            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(CHEST), net.minecraft.core.Direction.UP, CHEST, false);
            Mod.staticCall(AWAIT, "onLocalBlockClick", hit);
            return null;
        });
    }

    private static boolean remembered(Session c) {
        return c.onClient(mc -> (Boolean) Mod.staticCall(MEMORY, "has", "block " + CHEST.asLong()));
    }

    private static void lockedChest(Session c) {
        c.server().command("setblock -417 151 -422 minecraft:chest");
        try {
            dungeon(c);
            c.waitUntil("the client sees the chest", mc -> mc.level.getBlockState(CHEST).getBlock() == net.minecraft.world.level.block.Blocks.CHEST, 60);
            run(c, () -> Mod.staticCall(MEMORY, "reset"));
            int base = c.onClient(mc -> (Integer) Mod.staticCall(AWAIT, "lockedChests"));
            clickChest(c);
            boolean afterClick = remembered(c);
            c.hx().chat("§7Bob§7: That chest is locked!");
            c.ctx().waitTicks(3);
            boolean afterForged = remembered(c);
            c.hx().chat("§cThat chest is locked!");
            c.waitUntil("the locked chest taken back", mc -> (Integer) Mod.staticCall(AWAIT, "lockedChests") == base + 1, 20);
            boolean afterLocked = remembered(c);
            // An open chest (no locked line) stays counted; a locked line 2 s late answers nothing.
            run(c, () -> Mod.staticCall(MEMORY, "reset"));
            clickChest(c);
            c.ctx().waitTicks(40);
            c.hx().chat("§cThat chest is locked!");
            c.ctx().waitTicks(5);
            boolean late = remembered(c);
            int after = c.onClient(mc -> (Integer) Mod.staticCall(AWAIT, "lockedChests"));
            c.check(afterClick, "the chest click was not counted");
            c.check(afterForged, "a player's 'That chest is locked!' took the chest back");
            c.check(!afterLocked, "the locked chest is still counted");
            c.check(late && after == base + 1, "a locked line 2 s after the click took a chest back (" + late + ", " + after + ")");
            c.note("click counted " + afterClick + "; forged locked line: still counted " + afterForged + "; Hypixel's: "
                    + "counted " + afterLocked + "; late line: still counted " + late);
        } finally {
            c.server().command("setblock -417 151 -422 minecraft:air");
            run(c, () -> Mod.staticCall(MEMORY, "reset"));
        }
    }

    private static Object item(Minecraft mc, String name, double x, double y, double z, int id) {
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        net.minecraft.world.entity.item.ItemEntity e = new net.minecraft.world.entity.item.ItemEntity(mc.level, x, y, z, stack);
        e.setId(id);
        return e;
    }

    private static void chestDrop(Session c) {
        c.server().command("setblock -417 151 -422 minecraft:chest");
        try {
            dungeon(c);
            c.waitUntil("the client sees the chest", mc -> mc.level.getBlockState(CHEST).getBlock() == net.minecraft.world.level.block.Blocks.CHEST, 60);
            // Forget the chest clicks earlier cases made at this same chest.
            run(c, () -> ((java.util.Collection<?>) Mod.field(AWAIT, "chestClicks")).clear());
            int maxId = c.onClient(mc -> {
                int m = 0;
                for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
                    m = Math.max(m, e.getId());
                }
                return m;
            });
            // Before any click: a Spirit Leap beside the chest is an item secret.
            String before = c.onClient(mc -> (String) Mod.staticCall(AWAIT, "notASecretItem",
                    item(mc, "Spirit Leap", -416.5, 151, -421.5, maxId + 500)));
            clickChest(c);
            String drop = c.onClient(mc -> (String) Mod.staticCall(AWAIT, "notASecretItem",
                    item(mc, "Spirit Leap", -416.5, 151, -420.5, maxId + 1000)));
            String older = c.onClient(mc -> (String) Mod.staticCall(AWAIT, "notASecretItem",
                    item(mc, "Spirit Leap", -416.5, 151, -420.5, Math.max(1, maxId - 1))));
            String far = c.onClient(mc -> (String) Mod.staticCall(AWAIT, "notASecretItem",
                    item(mc, "Decoy", -405.5, 151, -421.5, maxId + 1001)));
            String junk = c.onClient(mc -> (String) Mod.staticCall(AWAIT, "notASecretItem",
                    item(mc, "Rotten Flesh", -405.5, 151, -421.5, maxId + 1002)));
            c.check(before == null, "a Spirit Leap before any chest click was not a secret: " + before);
            c.check(drop != null && drop.contains("came out of the chest"), "the item made beside the clicked chest: " + drop);
            c.check(older == null, "an item older than the click beside the chest was not a secret: " + older);
            c.check(far == null, "a Decoy 11 blocks away was not a secret: " + far);
            c.check(junk != null && junk.contains("not a dungeon secret item"), "Rotten Flesh: " + junk);
            c.note("before click: " + before + "; new beside the chest: " + drop + "; older: " + older + "; far: " + far
                    + "; Rotten Flesh: " + junk);
        } finally {
            c.server().command("setblock -417 151 -422 minecraft:air");
            run(c, () -> Mod.staticCall(MEMORY, "reset"));
        }
    }

    // ---- Door Keys ----------------------------------------------------------------------------------------------------

    private static void doorKeys(Session c) {
        String keys = "doorkeys.DoorKeysConfig";
        try (Settings set = new Settings(c).with(keys, "Radius", 0f).with(keys, "CircleColor", 0).with(keys, "TracerColor", 0)) {
            double pickup = c.onClient(mc -> (Double) Mod.call(Mod.cfg("autosecret.AutoSecretConfig"), "keyPickupRange"));
            double drawnAuto = c.onClient(mc -> (Double) Mod.call(Mod.cfg(keys), "drawnRadius"));
            run(c, () -> Mod.set(keys, "setRadius", 8.5f));
            double drawn8 = c.onClient(mc -> (Double) Mod.call(Mod.cfg(keys), "drawnRadius"));
            float[] wither = c.onClient(mc -> (float[]) Mod.staticCall("doorkeys.DoorKeysFeature", "resolveColor", 0,
                    new float[]{0f, 0f, 0f}, (Float) Mod.field("doorkeys.DoorKeysFeature", "AUTO_ALPHA")));
            String[] styles = c.onClient(mc -> (String[]) Mod.field(keys, "RANGE_STYLE_NAMES"));
            c.check(Math.abs(drawnAuto - pickup) < 1e-9, "Radius on Pickup Range drew " + drawnAuto + ", pickup " + pickup);
            c.check(Math.abs(drawn8 - 8.5) < 1e-9, "Radius 8.5 drew " + drawn8);
            c.check(wither[0] == 0f && wither[1] == 0f && wither[2] == 0f && Math.abs(wither[3] - 0.5f) < 1e-6,
                    "Auto wither color " + java.util.Arrays.toString(wither));
            c.check("Circle".equals(styles[0]) && "Sphere".equals(styles[1]), "styles " + java.util.Arrays.toString(styles));
            c.note("radius auto " + drawnAuto + " (pickup " + pickup + "), set 8.5 -> " + drawn8 + "; Auto wither "
                    + java.util.Arrays.toString(wither) + "; styles " + java.util.Arrays.toString(styles));
        }
    }
}
