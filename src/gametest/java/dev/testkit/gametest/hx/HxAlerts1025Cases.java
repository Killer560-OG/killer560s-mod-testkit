package dev.testkit.gametest.hx;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Dungeon alerts of 2026-10-25 (branch dungeon-alerts-1025): Teammate Low Health Alert, Livid extras, the pre-boss
 * score warning and crypt reminder, secret feedback, the Dungeon Breaker charges HUD and the Wither Key spawn alert.
 * Each case proves the feature ACTED (an alert counter moved and the title on screen says so, a HUD drew, a command
 * reached the server) and that it stays quiet where it must.
 */
final class HxAlerts1025Cases {

    static final String ALERTS_CFG = "dungeonalerts.DungeonAlertsConfig";
    static final String LOW_HEALTH = "dungeonalerts.LowHealthAlert";

    private HxAlerts1025Cases() {
    }

    static void register(Session s) {
        s.test("1025-alerts-lowhealth", HxAlerts1025Cases::lowHealth);
        s.test("1025-alerts-scorewarning", HxAlerts1025Cases::scoreWarning);
        s.test("1025-alerts-livid", HxAlerts1025Cases::livid);
        s.test("1025-alerts-secretclicked", HxAlerts1025Cases::secretClicked);
        s.test("1025-alerts-mutesounds", HxAlerts1025Cases::muteSounds);
        s.test("1025-alerts-breakercharges", HxAlerts1025Cases::breakerCharges);
        s.test("1025-alerts-keyspawn", HxAlerts1025Cases::keySpawn);
    }

    // ---- 1025-alerts-secretclicked ----------------------------------------------------------------------------------

    static final String CLICKED = "dungeonalerts.SecretClicked";

    private static void secretClicked(Session c) {
        net.minecraft.core.BlockPos chest = new net.minecraft.core.BlockPos(-417, 151, -422);
        c.server().command("setblock -417 151 -422 minecraft:chest");
        try (Settings set = new Settings(c).with(ALERTS_CFG, "SecretClickedEnabled", true)
                .with(ALERTS_CFG, "SecretClickedLockedSound", false)) {
            dungeon(c);
            c.waitUntil("the client sees the chest", mc -> mc.level.getBlockState(chest).getBlock() == net.minecraft.world.level.block.Blocks.CHEST, 60);
            run(c, () -> Mod.staticCall(CLICKED, "onWorldChange"));
            int added = c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "boxesAdded"));
            int locked = c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "lockedMarked"));
            // The click as the mod sees it: Fabric's UseBlockCallback, the event Secret Sound's detection listens on.
            // Invoked directly, so nothing reaches the server (no interaction for the anticheat to judge).
            c.onClient(mc -> {
                net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(chest), net.minecraft.core.Direction.UP, chest, false);
                net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(mc.player, mc.level,
                        net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                return null;
            });
            c.waitUntil("a box on the clicked chest", mc -> (Integer) Mod.staticCall(CLICKED, "boxesAdded") == added + 1, 20);
            int frames = c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "boxesDrawnFrames"));
            c.ctx().waitTicks(10);
            int framesAfter = c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "boxesDrawnFrames"));
            c.check(framesAfter > frames, "the box was never drawn (" + frames + " -> " + framesAfter + " frames)");
            String gold = c.onClient(mc -> (String) Mod.staticCall(CLICKED, "liveBoxes"));
            // A player's copy of the locked line first, then Hypixel's.
            c.hx().chat("§7Bob§7: That chest is locked!");
            c.ctx().waitTicks(3);
            int afterForged = c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "lockedMarked"));
            c.hx().chat("§cThat chest is locked!");
            c.waitUntil("the box turns red on the locked line", mc -> (Integer) Mod.staticCall(CLICKED, "lockedMarked") == locked + 1, 20);
            String red = c.onClient(mc -> (String) Mod.staticCall(CLICKED, "liveBoxes"));
            c.check("1/0".equals(gold), "boxes after the click (live/red) were " + gold);
            c.check(afterForged == locked, "a player's 'That chest is locked!' turned the box red");
            c.check("1/1".equals(red), "boxes after the locked line (live/red) were " + red);
            // Gone after its 7 s.
            c.waitUntil("the box expires", mc -> "0/0".equals(Mod.staticCall(CLICKED, "liveBoxes")), 200);
            c.note("click -> box " + gold + " drawn " + (framesAfter - frames) + " frames in 10 ticks; forged locked line ignored;"
                    + " real -> " + red + "; expired after 7 s");
        } finally {
            c.server().command("setblock -417 151 -422 minecraft:air");
        }
    }

    // ---- 1025-alerts-mutesounds -------------------------------------------------------------------------------------

    private static int muted(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted"));
    }

    private static void muteSounds(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "MuteChestSounds", true)
                .with(ALERTS_CFG, "MuteLeverSounds", false)) {
            dungeon(c);
            int base = muted(c);
            c.hx().sound("minecraft:block.chest.open", 1, 1);
            c.waitUntil("the chest open sound is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == base + 1, 20);
            c.hx().sound("minecraft:block.chest.close", 1, 1);
            c.waitUntil("the chest close sound is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == base + 2, 20);
            c.hx().sound("minecraft:block.lever.click", 1, 1);
            c.hx().sound("minecraft:entity.experience_orb.pickup", 1, 1);
            c.ctx().waitTicks(10);
            int leverOff = muted(c);
            c.check(leverOff == base + 2, "a lever or orb sound was muted with only Mute Chest Sounds on (" + leverOff + ")");
            run(c, () -> Mod.call(Mod.cfg(ALERTS_CFG), "setMuteLeverSounds", true));
            c.hx().sound("minecraft:block.lever.click", 1, 1);
            c.waitUntil("the lever sound is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == base + 3, 20);
            // Outside a dungeon nothing is muted.
            hub(c);
            c.hx().sound("minecraft:block.chest.open", 1, 1);
            c.ctx().waitTicks(10);
            int inHub = muted(c);
            c.check(inHub == base + 3, "a chest sound was muted outside a dungeon (" + inHub + ")");
            c.note("dungeon: chest open/close muted, lever + orb not (lever mute off), lever muted once on; hub: none");
        } finally {
            dungeon(c);
        }
    }

    // ---- 1025-alerts-breakercharges ---------------------------------------------------------------------------------

    static final String BREAKER_HUD = "dungeonalerts.BreakerChargesHud";

    private static void giveBreaker(Session c, int charges) {
        c.hx().give(3, "minecraft:diamond_pickaxe[custom_data={id:\"DUNGEONBREAKER\"},"
                + "lore=[{text:\"Charges: " + charges + "/20\",italic:false}]]");
    }

    private static long msSinceDrawn(Minecraft mc) {
        return (Long) Mod.staticCall("hud.HudSeen", "msSince", "breaker_charges", System.currentTimeMillis());
    }

    private static void breakerCharges(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "BreakerChargesHud", true)) {
            dungeon(c);
            c.server().command("item replace entity @a hotbar.3 with minecraft:air");
            c.ctx().waitTicks(10);
            run(c, () -> Mod.staticCall("hud.HudSeen", "forget", "breaker_charges"));
            c.ctx().waitTicks(10);
            boolean drawnWithout = c.onClient(mc -> (Boolean) Mod.staticCall("hud.HudSeen", "everDrawn", "breaker_charges"));
            c.check(!drawnWithout, "the Breaker Charges line drew with no breaker in the hotbar");
            giveBreaker(c, 14);
            c.waitUntil("the HUD draws 14/20", mc -> String.valueOf(Mod.staticCall(BREAKER_HUD, "lastDrawn")).contains("14§7/§f20")
                    && msSinceDrawn(mc) < 1000, 60);
            String at14 = c.onClient(mc -> (String) Mod.staticCall(BREAKER_HUD, "lastDrawn"));
            giveBreaker(c, 3);
            c.waitUntil("the HUD follows the lore to 3/20", mc -> String.valueOf(Mod.staticCall(BREAKER_HUD, "lastDrawn")).startsWith("§c3§7/"), 60);
            String at3 = c.onClient(mc -> (String) Mod.staticCall(BREAKER_HUD, "lastDrawn"));
            c.check(at14.startsWith("§a14"), "14/20 drawn as '" + at14 + "'");
            // Out of a dungeon it stays off.
            hub(c);
            run(c, () -> Mod.staticCall("hud.HudSeen", "forget", "breaker_charges"));
            c.ctx().waitTicks(20);
            boolean drawnInHub = c.onClient(mc -> (Boolean) Mod.staticCall("hud.HudSeen", "everDrawn", "breaker_charges"));
            c.check(!drawnInHub, "the Breaker Charges line drew outside a dungeon");
            c.note("no breaker: not drawn; 14/20 -> '" + at14 + "'; 3/20 -> '" + at3 + "'; hub: not drawn");
        } finally {
            c.server().command("item replace entity @a hotbar.3 with minecraft:air");
            dungeon(c);
        }
    }

    // ---- 1025-alerts-keyspawn ---------------------------------------------------------------------------------------

    static final String KEY = "dungeonalerts.KeySpawnAlert";

    private static void keySpawn(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "KeySpawnAlert", true)) {
            dungeon(c);
            c.hx().removeStands();
            run(c, () -> Mod.staticCall(KEY, "onWorldChange"));
            int base = c.onClient(mc -> (Integer) Mod.staticCall(KEY, "alertsFired"));
            // A stand whose name only contains the words is not a key.
            c.hx().stand(-416.5, 151, -416.5, "§7Bob's Wither Key");
            c.ctx().waitTicks(20);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(KEY, "alertsFired")) == base, "a 'Bob's Wither Key' stand alerted");
            c.hx().stand(-417.5, 151, -416.5, "Wither Key");
            c.waitUntil("the Wither Key alert", mc -> (Integer) Mod.staticCall(KEY, "alertsFired") == base + 1, 40);
            String title = c.onClient(mc -> (String) Mod.staticCall(KEY, "lastAlertTitle"));
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.ctx().waitTicks(20);
            int stay = c.onClient(mc -> (Integer) Mod.staticCall(KEY, "alertsFired"));
            c.hx().stand(-418.5, 151, -416.5, "§cBlood Key");
            c.waitUntil("the Blood Key alert", mc -> (Integer) Mod.staticCall(KEY, "alertsFired") == base + 2, 40);
            String blood = c.onClient(mc -> (String) Mod.staticCall(KEY, "lastAlertTitle"));
            c.check(title != null && title.contains("Wither Key") && title.contains("spawned"), "alert was '" + title + "'");
            c.check(shown != null && net.minecraft.ChatFormatting.stripFormatting(shown).contains("Wither Key spawned"), "on-screen title '" + shown + "'");
            c.check(stay == base + 1, "the same Wither Key stand alerted again (" + stay + ")");
            c.check(blood != null && blood.contains("Blood Key"), "second alert was '" + blood + "'");
            c.note("Bob's Wither Key: none; Wither Key -> '" + title + "' (Gui '" + shown + "'), once; Blood Key -> '" + blood + "'");
        } finally {
            c.hx().removeStands();
        }
    }

    // ---- 1025-alerts-scorewarning ---------------------------------------------------------------------------------

    static final String SCORE_CFG = "scorecalc.ScoreCalculatorConfig";
    static final String WARN = "scorecalc.ScoreWarnings";
    static final String WATCHER_LINE = "[BOSS] The Watcher: You have proven yourself. You may pass.";

    private static int warnings(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(WARN, "warningsShown"));
    }

    private static void scoreWarning(Session c) {
        try (Settings set = new Settings(c).with(SCORE_CFG, "Enabled", false)
                .with(SCORE_CFG, "CryptReminder", true)
                .with(SCORE_CFG, "CryptReminderSeconds", 120)
                .with(SCORE_CFG, "CryptReminderParty", true)
                .with(SCORE_CFG, "PreBossWarning", false)
                .with(SCORE_CFG, "ScoreWarningSound", false)) {
            dungeon(c, "crypts", 2, "secrets", 10, "secretsPercent", "30.0", "rooms", 20);
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "01m 23s", "cleared", 80);
            run(c, () -> {
                Mod.staticCall(WARN, "reset");
                // The session never changes world, so a boss latched by an earlier case (LiveMap's coordinate check,
                // the calculator's own) would hold the warnings off for good: clear both.
                Mod.setField("livemap.LiveMapFeature", "bossLatched", false);
                Mod.setField("scorecalc.ScoreCalculatorFeature", "inBoss", false);
                Mod.setField("scorecalc.ScoreCalculatorFeature", "bloodDone", false);
                Mod.setField("scorecalc.ScoreCalculatorFeature", "expectingBloodUpdate", false);
            });
            c.waitUntil("the Score Calculator tracks with only Score Warning on (crypts 2)",
                    mc -> ((Number) Mod.staticCall("scorecalc.ScoreCalculatorFeature", "getCrypts")).intValue() == 2, 100);
            int base = warnings(c);
            c.ctx().waitTicks(40);
            c.check(warnings(c) == base, "the crypt reminder fired at 1:23 with the reminder set to 2:00");

            sidebar(c, "template", "dungeon", "floor", "F7", "time", "02m 05s", "cleared", 80);
            c.waitUntil("the crypt reminder at 2:05", mc -> (Integer) Mod.staticCall(WARN, "warningsShown") == base + 1, 60);
            String crypt = c.onClient(mc -> (String) Mod.staticCall(WARN, "lastWarning"));
            String shownCrypt = c.onClient(HxAlerts1025Cases::guiTitle);
            String pc = awaitCommand(c, "pc ", 60);
            c.check(crypt != null && crypt.contains("Crypts: 2/5"), "crypt reminder was '" + crypt + "'");
            c.check(shownCrypt != null && shownCrypt.contains("Crypts: 2/5"), "on-screen title '" + shownCrypt + "'");
            c.check(pc.contains("Crypts: 2/5"), "party message was '/" + pc + "'");
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "02m 30s", "cleared", 80);
            c.ctx().waitTicks(30);
            c.check(warnings(c) == base + 1, "the crypt reminder fired twice in one run");

            // Pre-boss: a forged Watcher line from a player first, then the real one.
            run(c, () -> Mod.call(Mod.cfg(SCORE_CFG), "setPreBossWarning", true));
            c.hx().chat("§7Bob§7: " + WATCHER_LINE);
            c.ctx().waitTicks(80);
            c.check(warnings(c) == base + 1, "a player's copy of the Watcher's line started the pre-boss warning");
            c.hx().chat(WATCHER_LINE);
            c.ctx().waitTicks(15);
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "02m 40s", "cleared", 84);
            c.waitUntil("the pre-boss warning once blood's clear counts", mc -> (Integer) Mod.staticCall(WARN, "warningsShown") == base + 2, 80);
            String pre = c.onClient(mc -> (String) Mod.staticCall(WARN, "lastWarning"));
            String shownPre = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(pre != null && pre.contains("Crypts 2/5") && pre.contains("Cleared 84%"), "pre-boss warning was '" + pre + "'");
            c.check(shownPre != null && shownPre.contains("Before the boss"), "on-screen title '" + shownPre + "'");
            boolean blood = c.onClient(mc -> (Boolean) Mod.field("scorecalc.ScoreCalculatorFeature", "bloodDone"));
            c.note("1:23 none; 2:05 -> '" + crypt + "' (Gui '" + shownCrypt + "'), /" + pc + "; forged Watcher: none; real Watcher +"
                    + " cleared 80->84 (bloodDone " + blood + ") -> '" + pre + "'");
        }
    }

    // ---- 1025-alerts-livid ----------------------------------------------------------------------------------------

    static final String LIVID = "boss.LividSolverFeature";
    static final String LIVID_CFG = "boss.LividSolverConfig";
    static final int LIVID_ID_BASE = 772_500;
    static final String[] LIVIDS = {"Hockey Livid", "Arcade Livid", "Smile Livid"};
    static final double[][] LIVID_AT = {{3.5, 52.5}, {8.5, 52.5}, {7.5, 47.5}};

    private static void livid(Session c) {
        // The arena is far from the session's platform: load its chunks before placing blocks there.
        c.server().command("forceload add 0 40 11 56");
        c.ctx().waitTicks(10);
        c.server().command("fill 0 105 44 11 105 56 minecraft:stone");
        c.server().command("fill 0 106 44 11 112 56 minecraft:air");
        c.server().command("setblock 5 108 43 minecraft:red_wool");
        c.server().command("setblock 5 108 40 minecraft:air");
        c.server().command("tp @a 5.5 106 50.5");
        try (Settings set = new Settings(c).with(LIVID_CFG, "Enabled", true)
                .with(LIVID_CFG, "HideWrong", true)
                .with(LIVID_CFG, "IceSprayAlert", true)
                .with(LIVID_CFG, "AnnounceColor", true)) {
            sidebar(c, "template", "dungeon", "floor", "F5");
            c.waitUntil("in F5", mc -> "F5".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            c.waitUntil("the client sees the red wool at (5,108,43)", mc -> mc.level != null
                    && mc.level.getBlockState(new net.minecraft.core.BlockPos(5, 108, 43)).getBlock().getDescriptionId().endsWith("red_wool"), 200);
            c.onClient(mc -> {
                for (int i = 0; i < LIVIDS.length; i++) {
                    net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level,
                            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), LIVIDS[i]));
                    p.setId(LIVID_ID_BASE + i);
                    p.setPos(LIVID_AT[i][0], 106, LIVID_AT[i][1]);
                    mc.level.addEntity(p);
                    // Each Livid's name tag stand, as Hypixel shows it over the clone.
                    net.minecraft.world.entity.decoration.ArmorStand tag = new net.minecraft.world.entity.decoration.ArmorStand(
                            mc.level, LIVID_AT[i][0], 108.2, LIVID_AT[i][1]);
                    tag.setId(LIVID_ID_BASE + 10 + i);
                    tag.setCustomName(Component.literal("﴾ " + LIVIDS[i] + " ﴿"));
                    tag.setCustomNameVisible(true);
                    mc.level.addEntity(tag);
                }
                return null;
            });
            c.waitUntil("the solver names Hockey Livid", mc -> "Hockey Livid".equals(Mod.staticCall(LIVID, "currentLividName")), 100);

            // Hide Wrong Livids: through the real render hook (EntityRenderDispatcher.shouldRender). A hidden entity
            // returns false at HEAD before the frustum is touched, so a null frustum is safe for it.
            String hidden = c.onClient(mc -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < LIVIDS.length; i++) {
                    net.minecraft.world.entity.Entity e = mc.level.getEntity(LIVID_ID_BASE + i);
                    net.minecraft.world.entity.Entity tag = mc.level.getEntity(LIVID_ID_BASE + 10 + i);
                    boolean hideE = (Boolean) Mod.staticCall(LIVID, "shouldHideEntity", e);
                    boolean hideT = (Boolean) Mod.staticCall(LIVID, "shouldHideEntity", tag);
                    boolean drawnE = !hideE || mc.getEntityRenderDispatcher().shouldRender(e, null, 0, 0, 0);
                    boolean drawnT = !hideT || mc.getEntityRenderDispatcher().shouldRender(tag, null, 0, 0, 0);
                    sb.append(LIVIDS[i]).append(" hide=").append(hideE).append(" drawn=").append(drawnE)
                            .append(" tagHide=").append(hideT).append(" tagDrawn=").append(drawnT).append("; ");
                }
                return sb.toString();
            });
            c.check(hidden.contains("Hockey Livid hide=false") && hidden.contains("Hockey Livid hide=false drawn=true tagHide=false"),
                    "the real Livid (or its tag) is hidden: " + hidden);
            c.check(hidden.contains("Arcade Livid hide=true drawn=false tagHide=true tagDrawn=false")
                    && hidden.contains("Smile Livid hide=true drawn=false tagHide=true tagDrawn=false"),
                    "a wrong Livid (or its tag) still renders: " + hidden);

            // Announce Color: one party message once the answer has held 2 s.
            String pc = awaitCommand(c, "pc ", 120);
            c.check("pc Livid is Red (Hockey Livid)".equals(pc), "announcement was '/" + pc + "'");
            c.ctx().waitTicks(60);
            int sent = commandsStarting(c, "pc ").size();
            c.check(sent == 1, "the color was announced " + sent + " times for one answer");

            // Ice Spray: Livid's real welcome line starts the invulnerability countdown; its end shows the title.
            int iceBase = c.onClient(mc -> (Integer) Mod.staticCall(LIVID, "iceSprayAlerts"));
            c.hx().chat("[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.");
            c.waitUntil("the invulnerability countdown runs", mc -> ((Number) Mod.field(LIVID, "invulnTicks")).intValue() > 300, 40);
            c.check(c.onClient(mc -> (Integer) Mod.staticCall(LIVID, "iceSprayAlerts")) == iceBase,
                    "the Ice Spray title fired at the start of the invulnerability");
            run(c, () -> Mod.setField(LIVID, "invulnTicks", 5));
            c.waitUntil("the Ice Spray title when the invulnerability ends",
                    mc -> (Integer) Mod.staticCall(LIVID, "iceSprayAlerts") == iceBase + 1, 40);
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(shown != null && shown.contains("Ice Spray Livid"), "the on-screen title is '" + shown + "'");

            // Hide Wrong off: nothing hidden.
            run(c, () -> Mod.call(Mod.cfg(LIVID_CFG), "setHideWrong", false));
            c.ctx().waitTicks(15);
            boolean anyHidden = c.onClient(mc -> (Boolean) Mod.staticCall(LIVID, "shouldHideEntity", mc.level.getEntity(LIVID_ID_BASE + 1)));
            c.check(!anyHidden, "Arcade Livid still hidden with Hide Wrong Livids off");
            c.note("render: " + hidden + "announced '/" + pc + "' once; Ice Spray title '" + shown + "'; Hide Wrong off -> shown");
        } finally {
            c.onClient(mc -> {
                for (int i = 0; i < LIVIDS.length; i++) {
                    for (int id : new int[]{LIVID_ID_BASE + i, LIVID_ID_BASE + 10 + i}) {
                        if (mc.level != null && mc.level.getEntity(id) != null) {
                            mc.level.removeEntity(id, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                        }
                    }
                }
                return null;
            });
            c.server().command("setblock 5 108 43 minecraft:air");
            c.server().command("tp @a -419.5 151 -419.5");
            c.server().command("forceload remove 0 40 11 56");
            // Standing at x 5, z 50 latched LiveMap's boss check; the session goes on in the clear.
            run(c, () -> Mod.setField("livemap.LiveMapFeature", "bossLatched", false));
            dungeon(c);
        }
    }

    static String guiTitle(Minecraft mc) {
        // 26.1: Gui.title. 26.2: the title moved to Gui.hud (a public final field), as McCompat says.
        Object holder = mc.gui;
        try {
            Field hud = mc.gui.getClass().getField("hud");
            holder = hud.get(mc.gui);
        } catch (ReflectiveOperationException e) {
            // 26.1: no hud field, the title is on Gui itself
        }
        try {
            Field f = holder.getClass().getDeclaredField("title");
            f.setAccessible(true);
            Object t = f.get(holder);
            return t == null ? null : ((Component) t).getString();
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** The F7 dungeon sidebar with the two mates' health lines (Hypixel's shape: class letter, name, colored number). */
    private static void mates(Session c, String healthA, String healthB) {
        List<String> extra = new ArrayList<>();
        extra.add("§e[M] §bHxMateA " + healthA);
        extra.add("§e[B] §6HxMateB " + healthB);
        sidebar(c, "template", "dungeon", "floor", "F7", "extra", extra);
    }

    private static int fired(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired"));
    }

    // ---- 1025-alerts-lowhealth -----------------------------------------------------------------------------------

    private static void lowHealth(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "LowHealthEnabled", true)
                .with(ALERTS_CFG, "LowHealthOnlyHealer", false)
                .with(ALERTS_CFG, "LowHealthSound", false)) {
            dungeon(c);
            mates(c, "§a25,000§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int base = fired(c);
            c.ctx().waitTicks(20);
            c.check(fired(c) == base, "an alert fired with both mates on green health");

            mates(c, "§c2,900§c❤", "§a30,100§c❤");
            c.waitUntil("the alert fires when HxMateA's health turns red", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 1, 40);
            String title = c.onClient(mc -> (String) Mod.staticCall(LOW_HEALTH, "lastAlertTitle"));
            String shown = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(title != null && title.contains("HxMateA") && title.contains("is low"), "alert title was '" + title + "'");
            c.check(shown != null && shown.contains("HxMateA"), "the on-screen title is '" + shown + "'");

            // Still red, a new number: no second alert until HxMateA has left red.
            mates(c, "§c1,200§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int stillRed = fired(c);
            // Dead is not "low".
            mates(c, "§cDEAD", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int dead = fired(c);
            c.check(stillRed == base + 1, "a second alert fired while HxMateA stayed red (" + stillRed + ")");
            c.check(dead == base + 1, "an alert fired for a DEAD teammate (" + dead + ")");

            // Only While Healer: the client is Archer in the tab list, so HxMateB going red says nothing ...
            run(c, () -> Mod.call(Mod.cfg(ALERTS_CFG), "setLowHealthOnlyHealer", true));
            mates(c, "§a20,000§c❤", "§c4,000§c❤");
            c.ctx().waitTicks(25);
            int asArcher = fired(c);
            c.check(asArcher == base + 1, "an alert fired with Only While Healer on while playing Archer (" + asArcher + ")");
            // ... until the tab list says Healer.
            tab(c, "template", "dungeon", "players", List.of(player("HxMateA", "Mage", "[MVP+]"), player("HxMateB", "Berserk", null),
                    player(self(c), "Healer", null)));
            c.waitUntil("PartyTracker.selfClass() == HEALER", mc -> "HEALER".equals(String.valueOf(Mod.staticCall("leapmenu.PartyTracker", "selfClass"))), 60);
            c.waitUntil("the alert fires for HxMateB once you are Healer", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 2, 40);
            String title2 = c.onClient(mc -> (String) Mod.staticCall(LOW_HEALTH, "lastAlertTitle"));
            c.check(title2 != null && title2.contains("HxMateB"), "second alert title was '" + title2 + "'");
            c.note("green: none; A red -> '" + title + "' (Gui '" + shown + "'); still red / DEAD: none; Healer-only as Archer: none;"
                    + " as Healer -> '" + title2 + "'");
        } finally {
            dungeon(c);
        }
    }
}
