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
        s.test("1108-alerts-lowhealth-yellow", HxAlerts1025Cases::lowHealthYellow);
        s.test("1025-alerts-scorewarning", HxAlerts1025Cases::scoreWarning);
        s.test("1025-alerts-livid", HxAlerts1025Cases::livid);
        s.test("1025-alerts-secretclicked", HxAlerts1025Cases::secretClicked);
        // doc-auto-1101: the chest secret sound waits out "That chest is locked!"
        s.test("1025-alerts-secretsound-locked", HxAlerts1025Cases::secretSoundLocked);
        s.test("1108-alerts-secretsound-every-click", HxAlerts1025Cases::secretSoundEveryClick);
        // doc-auto-1101: Auto Ult's Wish On Clear (cheat jar only)
        s.test("1025-alerts-autoult-wish-clear", HxAlerts1025Cases::wishOnClear);
        s.test("1025-alerts-mutesounds", HxAlerts1025Cases::muteSounds);
        // sndheld-1104: Mute Lever Sounds swallowed the dungeon start countdown's ticks (Hypixel's random.click).
        s.test("1104-alerts-countdown-sound", HxAlerts1025Cases::countdownSound);
        s.test("1025-alerts-breakercharges", HxAlerts1025Cases::breakerCharges);
        s.test("1025-alerts-keyspawn", HxAlerts1025Cases::keySpawn);
    }

    // ---- 1025-alerts-secretclicked ----------------------------------------------------------------------------------

    static final String CLICKED = "dungeonalerts.SecretClicked";

    private static void secretClicked(Session c) {
        net.minecraft.core.BlockPos chest = new net.minecraft.core.BlockPos(-417, 151, -422);
        c.server().command("setblock -417 151 -422 minecraft:chest");
        try (Settings set = new Settings(c).with(ALERTS_CFG, "SecretClickedEnabled", true)
                .with(ALERTS_CFG, "SecretClickedLockedSound", false)
                // secretfade-1102: the gold box on a secret simply taken is behind Box On Every Secret (off by default).
                .with(ALERTS_CFG, "SecretClickedTakenBox", true)) {
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

    // ---- 1025-alerts-secretsound-locked (doc-auto-1101) -------------------------------------------------------------

    /** Secret Sound on a chest: (1) Hypixel's locked line inside the wait drops the sound; (2) no line plays it after the
     *  wait (500 ms). Counted by SecretSound.chestSoundCounts {played, dropped}. A player's copy of the line is ignored. */
    private static void secretSoundLocked(Session c) {
        net.minecraft.core.BlockPos chest = new net.minecraft.core.BlockPos(-417, 151, -422);
        c.server().command("setblock -417 151 -422 minecraft:chest");
        Object cfg = c.onClient(mc -> Mod.cfg(ALERTS_CFG));
        boolean soundWas = c.onClient(mc -> (Boolean) Mod.field(cfg, "secretSoundEnabled"));
        try (Settings set = new Settings(c).with(ALERTS_CFG, "SecretClickedEnabled", false)) {
            c.onClient(mc -> {
                try {
                    cfg.getClass().getField("secretSoundEnabled").set(cfg, true);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            dungeon(c);
            c.waitUntil("the client sees the chest", mc -> mc.level.getBlockState(chest).getBlock() == net.minecraft.world.level.block.Blocks.CHEST, 60);
            int[] before = c.onClient(mc -> (int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"));
            clickChest(c, chest);
            c.hx().chat("§7Bob§7: That chest is locked!");
            c.hx().chat("§cThat chest is locked!");
            c.ctx().waitTicks(20);
            int[] mid = c.onClient(mc -> (int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"));
            c.check(mid[0] == before[0] && mid[1] == before[1] + 1, "locked chest: played/dropped "
                    + (mid[0] - before[0]) + "/" + (mid[1] - before[1]) + ", want 0/1");
            c.ctx().waitTicks(25); // past the 1500 ms Secret Clicked window and the same-click guard
            clickChest(c, chest);
            c.waitUntil("the unlocked chest's sound after the wait", mc -> ((int[]) Mod.staticCall("dungeonalerts.SecretSound",
                    "chestSoundCounts"))[0] == mid[0] + 1, 40);
            int[] after = c.onClient(mc -> (int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"));
            c.check(after[1] == mid[1], "the unlocked chest's sound was dropped");
            c.note("locked chest: sound dropped (played/dropped +" + (mid[0] - before[0]) + "/+" + (mid[1] - before[1])
                    + "); chest with no locked line: played after the wait (+" + (after[0] - mid[0]) + ")");
        } finally {
            c.onClient(mc -> {
                try {
                    cfg.getClass().getField("secretSoundEnabled").set(cfg, soundWas);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            c.server().command("setblock -417 151 -422 minecraft:air");
        }
    }

    // ---- 1108-alerts-secretsound-every-click (dungsolve) ---------------------------------------------------------------

    /** killer560, 2026-11-08: "The secret sounds still dont play every time i click a chest". (1) Two different chests
     *  clicked inside the 500 ms wait: both play (one pending slot played one sound). (2) A chest click that reaches the wire
     *  without UseBlockCallback having seen it (a listener ahead answered non-PASS, or another sender): plays once. */
    private static void secretSoundEveryClick(Session c) {
        net.minecraft.core.BlockPos a = new net.minecraft.core.BlockPos(-417, 151, -422);
        net.minecraft.core.BlockPos b = new net.minecraft.core.BlockPos(-414, 151, -422);
        c.server().command("setblock -417 151 -422 minecraft:chest");
        c.server().command("setblock -414 151 -422 minecraft:chest");
        Object cfg = c.onClient(mc -> Mod.cfg(ALERTS_CFG));
        boolean soundWas = c.onClient(mc -> (Boolean) Mod.field(cfg, "secretSoundEnabled"));
        try (Settings set = new Settings(c).with(ALERTS_CFG, "SecretClickedEnabled", false)) {
            c.onClient(mc -> setSound(cfg, true));
            dungeon(c);
            c.waitUntil("the client sees both chests", mc -> mc.level.getBlockState(a).getBlock() == net.minecraft.world.level.block.Blocks.CHEST
                    && mc.level.getBlockState(b).getBlock() == net.minecraft.world.level.block.Blocks.CHEST, 60);
            int[] before = c.onClient(mc -> (int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"));
            clickChest(c, a);
            c.ctx().waitTicks(2);
            clickChest(c, b);
            c.waitUntil("both chests' sounds", mc -> ((int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"))[0] >= before[0] + 2, 60);
            c.ctx().waitTicks(15);
            int[] two = c.onClient(mc -> (int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"));
            c.check(two[0] == before[0] + 2, "two chests clicked inside the wait should play exactly 2 sounds, played " + (two[0] - before[0]));
            // The wire only: a real look at the chest first (a made-up hit draws RotationPlace), then the packet leaves.
            c.server().command("tp @p -417.5 151 -419.5");
            c.ctx().waitTicks(5);
            c.onClient(mc -> {
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(a));
                return null;
            });
            c.ctx().waitTicks(5);
            c.onClient(mc -> {
                net.minecraft.world.phys.HitResult pick = mc.player.pick(6.0, 1.0f, false);
                c.check(pick instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getBlockPos().equals(a), "the look should be on the chest, was " + pick);
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                        net.minecraft.world.InteractionHand.MAIN_HAND, (net.minecraft.world.phys.BlockHitResult) pick, 0));
                return null;
            });
            c.waitUntil("the wire-only click's sound", mc -> ((int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"))[0] >= two[0] + 1, 60);
            c.ctx().waitTicks(15);
            int[] wire = c.onClient(mc -> (int[]) Mod.staticCall("dungeonalerts.SecretSound", "chestSoundCounts"));
            c.check(wire[0] == two[0] + 1, "a wire-only chest click should play exactly 1 sound, played " + (wire[0] - two[0]));
            c.note("two chests inside the wait: +" + (two[0] - before[0]) + " sounds; wire-only click: +" + (wire[0] - two[0]));
        } finally {
            c.onClient(mc -> {
                if (mc.player != null) mc.player.closeContainer();
                setSound(cfg, soundWas);
                return null;
            });
            c.server().command("setblock -417 151 -422 minecraft:air");
            c.server().command("setblock -414 151 -422 minecraft:air");
        }
    }

    private static Object setSound(Object cfg, boolean on) {
        try {
            cfg.getClass().getField("secretSoundEnabled").set(cfg, on);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private static void clickChest(Session c, net.minecraft.core.BlockPos chest) {
        c.onClient(mc -> {
            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(chest), net.minecraft.core.Direction.UP, chest, false);
            net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(mc.player, mc.level,
                    net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            return null;
        });
    }

    // ---- 1025-alerts-autoult-wish-clear (doc-auto-1101) --------------------------------------------------------------

    private static final String CU_CFG = "cheatutils.CheatUtilsConfig";

    private static int wishes(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("cheatutils.AutoUltFeature", "wishesSent"));
    }

    /**
     * Wish On Clear as a Healer (class override) in the F7 clear: green mates -> no Wish; HxMateA red -> one Wish (a drop
     * packet); still red with no answer -> no second try; "Wish is ready" -> one more; a cooldown line answering it ->
     * nothing until that cooldown is over, then one more. The Wish never goes out more than once per freeing line.
     */
    private static void wishOnClear(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Ult does not exist");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        String ipWas = c.onClient(mc -> mc.getCurrentServer().ip);
        try (Settings set = new Settings(c).with(CU_CFG, "AutoUltEnabled", true).with(CU_CFG, "WishOnClear", true)
                .field(CU_CFG, "ultClassOverride", "HEALER")) {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", false);
                mc.getCurrentServer().ip = "mc.hypixel.net";
                return null;
            });
            dungeon(c);
            mates(c, "§a25,000§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int base = wishes(c);
            c.ctx().waitTicks(20);
            c.check(wishes(c) == base, "a Wish went out with both mates on green health");
            c.onClient(mc -> {
                dev.testkit.harness.PacketTrace.start();
                return null;
            });
            mates(c, "§c2,900§c❤", "§a30,100§c❤");
            c.waitUntil("a Wish for HxMateA", mc -> (Integer) Mod.staticCall("cheatutils.AutoUltFeature", "wishesSent") == base + 1, 60);
            c.ctx().waitTicks(80);
            int noRetry = wishes(c);
            int drops = c.onClient(mc -> dev.testkit.harness.PacketTrace.sent("player_action"));
            c.onClient(mc -> {
                dev.testkit.harness.PacketTrace.stop();
                return null;
            });
            c.check(noRetry == base + 1, "Wish was tried again with no answer (" + (noRetry - base) + " wishes in 80 ticks)");
            c.check(drops >= 1, "no drop (player_action) packet went out for the Wish");
            c.hx().chat("Wish is ready to use! Press DROP to activate it!");
            c.waitUntil("a Wish after the ready line", mc -> (Integer) Mod.staticCall("cheatutils.AutoUltFeature", "wishesSent") == base + 2, 60);
            c.hx().chat("This ability is on cooldown for 5s.");
            c.ctx().waitTicks(60);
            int during = wishes(c);
            c.check(during == base + 2, "a Wish went out during the 5 s cooldown Hypixel named");
            c.waitUntil("a Wish once the 5 s are over", mc -> (Integer) Mod.staticCall("cheatutils.AutoUltFeature", "wishesSent") == base + 3, 140);
            c.note("green: no Wish; red: 1 Wish (" + drops + " player_action); no answer: no retry in 80 ticks; ready line: 1 more;"
                    + " cooldown 5s line: none for 60 ticks, then 1 more");
        } finally {
            c.onClient(mc -> {
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                mc.getCurrentServer().ip = ipWas;
                return null;
            });
        }
    }

    // ---- 1025-alerts-mutesounds -------------------------------------------------------------------------------------

    private static int muted(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted"));
    }

    static final net.minecraft.core.BlockPos MUTE_CHEST = new net.minecraft.core.BlockPos(-417, 151, -422);
    static final net.minecraft.core.BlockPos MUTE_LEVER = new net.minecraft.core.BlockPos(-415, 151, -422);

    /** A chest and a lever for the mutes to find: since sndheld-1104 only a sound played IN one of them is muted. */
    private static void placeMuteBlocks(Session c) {
        c.server().command("setblock -417 151 -422 minecraft:chest");
        c.server().command("setblock -415 150 -422 minecraft:stone");
        c.server().command("setblock -415 151 -422 minecraft:lever[face=floor]");
        c.waitUntil("the client sees the chest and the lever",
                mc -> mc.level.getBlockState(MUTE_CHEST).getBlock() == net.minecraft.world.level.block.Blocks.CHEST
                        && mc.level.getBlockState(MUTE_LEVER).getBlock() == net.minecraft.world.level.block.Blocks.LEVER, 60);
    }

    private static void soundIn(Session c, String id, net.minecraft.core.BlockPos pos) {
        c.hx().soundAt(id, 1, 1, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static void muteSounds(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "MuteChestSounds", true)
                .with(ALERTS_CFG, "MuteLeverSounds", false)) {
            dungeon(c);
            placeMuteBlocks(c);
            int base = muted(c);
            soundIn(c, "minecraft:block.chest.open", MUTE_CHEST);
            c.waitUntil("the chest open sound is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == base + 1, 20);
            soundIn(c, "minecraft:block.chest.close", MUTE_CHEST);
            c.waitUntil("the chest close sound is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == base + 2, 20);
            soundIn(c, "minecraft:block.lever.click", MUTE_LEVER);
            c.hx().sound("minecraft:entity.experience_orb.pickup", 1, 1);
            c.ctx().waitTicks(10);
            int leverOff = muted(c);
            c.check(leverOff == base + 2, "a lever or orb sound was muted with only Mute Chest Sounds on (" + leverOff + ")");
            run(c, () -> Mod.call(Mod.cfg(ALERTS_CFG), "setMuteLeverSounds", true));
            soundIn(c, "minecraft:block.lever.click", MUTE_LEVER);
            c.waitUntil("the lever sound is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == base + 3, 20);
            // Outside a dungeon nothing is muted.
            hub(c);
            soundIn(c, "minecraft:block.chest.open", MUTE_CHEST);
            c.ctx().waitTicks(10);
            int inHub = muted(c);
            c.check(inHub == base + 3, "a chest sound was muted outside a dungeon (" + inHub + ")");
            c.note("dungeon: chest open/close muted, lever + orb not (lever mute off), lever muted once on; hub: none");
        } finally {
            dungeon(c);
        }
    }

    // ---- 1104-alerts-countdown-sound ---------------------------------------------------------------------------------

    /**
     * killer560 2026-11-04: "the sounds to the 5 4 3 2 1 for a run about to start dont come through". Hypixel ticks the
     * "Starting in N seconds." countdown with 1.8's random.click, which arrives as block.lever.click at the player; Mute
     * Lever Sounds (on in his config) dropped it. SoundProbe counts what reached the sound engine: the
     * countdown ticks must play, a lever's own click must still be muted, and an orb sound proves the counter hears.
     */
    private static int heard(Session c, String id) {
        return c.onClient(mc -> dev.testkit.harness.SoundProbe.count(id));
    }

    private static void countdownSound(Session c) {
        final String click = "minecraft:block.lever.click";
        final String orb = "minecraft:entity.experience_orb.pickup";
        try (Settings set = new Settings(c).with(ALERTS_CFG, "MuteChestSounds", true)
                .with(ALERTS_CFG, "MuteLeverSounds", true)) {
            dungeon(c);
            placeMuteBlocks(c);
            int base = muted(c);
            int clicks0 = heard(c, click);
            for (int n = 4; n >= 1; n--) {
                c.hx().chat("Starting in " + n + (n == 1 ? " second." : " seconds."));
                c.hx().sound(click, 1, 1);
                c.ctx().waitTicks(4);
            }
            // The control after the countdown: once it reached the engine, a missing tick was dropped, not late.
            int orb0 = heard(c, orb);
            c.hx().sound(orb, 1, 1);
            c.waitUntil("the control sound reached the engine", mc -> dev.testkit.harness.SoundProbe.count(orb) > orb0, 100);
            int played = heard(c, click) - clicks0;
            int mutedNow = muted(c);
            c.check(played == 4, "the countdown played " + played + " of 4 ticks (" + (mutedNow - base)
                    + " muted by Mute Lever Sounds)");
            // A lever's own click is still muted.
            int clicks1 = heard(c, click);
            soundIn(c, click, MUTE_LEVER);
            c.waitUntil("the lever click is muted", mc -> (Integer) Mod.staticCall(CLICKED, "soundsMuted") == mutedNow + 1, 20);
            int orb1 = heard(c, orb);
            c.hx().sound(orb, 1, 1);
            c.waitUntil("the second control sound reached the engine", mc -> dev.testkit.harness.SoundProbe.count(orb) > orb1, 100);
            int leverPlayed = heard(c, click) - clicks1;
            c.check(leverPlayed == 0, "a lever's own click played with Mute Lever Sounds on (" + leverPlayed + ")");
            c.note("countdown: " + played + "/4 ticks reached the sound engine with Mute Lever Sounds on; the lever's own click muted");
        }
    }

    // ---- 1025-alerts-breakercharges ---------------------------------------------------------------------------------

    static final String BREAKER_HUD = "dungeonalerts.BreakerChargesHud";

    private static void giveBreaker(Session c, int charges) {
        c.hx().give(3, "minecraft:diamond_pickaxe[custom_data={id:\"DUNGEONBREAKER\"},"
                + "lore=[{text:\"Charges: " + charges + "/20\",italic:false}]]");
    }

    /** Long.MAX_VALUE when it never drew since the last HudSeen.forget (HudSeen.everDrawn left the mod in cleanup-gui 163778ec). */
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
            boolean drawnWithout = c.onClient(mc -> msSinceDrawn(mc) != Long.MAX_VALUE);
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
            boolean drawnInHub = c.onClient(mc -> msSinceDrawn(mc) != Long.MAX_VALUE);
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
                .with(SCORE_CFG, "CryptReminderSeconds", 5)
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
            // cryptgate-1104: the reminder counts from Mort's "found this map" line (first gate), not from entering.
            c.ctx().waitTicks(160);
            c.check(warnings(c) == base, "the crypt reminder fired 8 s after entering with no run start (Mort's line) seen");
            c.hx().chat("[NPC] Mort: Here, I found this map when I first entered the dungeon.");
            c.ctx().waitTicks(40);
            c.check(warnings(c) == base, "the crypt reminder fired 2 s after the run started, with Remind At 0:05");
            c.waitUntil("the crypt reminder 5 s after the run started", mc -> (Integer) Mod.staticCall(WARN, "warningsShown") == base + 1, 200);
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
            // Since 2026-11-01 (doc-dhud-1101) the warning shows on the Watcher's line itself, blood counted as cleared
            // until the sidebar says so: 20 of 25 rooms + blood + boss = 88%.
            c.hx().chat(WATCHER_LINE);
            c.waitUntil("the pre-boss warning on the Watcher's line", mc -> (Integer) Mod.staticCall(WARN, "warningsShown") == base + 2, 100);
            sidebar(c, "template", "dungeon", "floor", "F7", "time", "02m 40s", "cleared", 84);
            String pre = c.onClient(mc -> (String) Mod.staticCall(WARN, "lastWarning"));
            String shownPre = c.onClient(HxAlerts1025Cases::guiTitle);
            c.check(pre != null && pre.contains("Crypts 2/5") && pre.contains("Cleared 88%"), "pre-boss warning was '" + pre + "'");
            c.check(shownPre != null && shownPre.contains("Before the boss"), "on-screen title '" + shownPre + "'");
            boolean blood = c.onClient(mc -> (Boolean) Mod.field("scorecalc.ScoreCalculatorFeature", "bloodDone"));
            c.note("1:23 none; 2:05 -> '" + crypt + "' (Gui '" + shownCrypt + "'), /" + pc + "; forged Watcher: none; real Watcher +"
                    + " on the line -> '" + pre + "' (bloodDone " + blood + ")");
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
                    boolean drawnE = !hideE || dev.testkit.compat.McCompat.shouldRender(mc.getEntityRenderDispatcher(), e, null, 0, 0, 0);
                    boolean drawnT = !hideT || dev.testkit.compat.McCompat.shouldRender(mc.getEntityRenderDispatcher(), tag, null, 0, 0, 0);
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

    // ---- 1108-alerts-lowhealth-yellow: the tier above red alerts too, and within a few ticks ----------------------------

    private static void lowHealthYellow(Session c) {
        try (Settings set = new Settings(c).with(ALERTS_CFG, "LowHealthEnabled", true)
                .with(ALERTS_CFG, "LowHealthOnlyHealer", false)
                .with(ALERTS_CFG, "LowHealthSound", false)
                .with(ALERTS_CFG, "LowHealthYellow", true)) {
            dungeon(c);
            mates(c, "§a25,000§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(20);
            int base = fired(c);
            mates(c, "§e9,000§c❤", "§a30,100§c❤");
            // The sidebar is read every tick now (it was every fifth): the alert is in within 4 ticks of the line.
            c.waitUntil("the yellow alert for HxMateA", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 1, 4);
            String yel = c.onClient(mc -> (String) Mod.staticCall(LOW_HEALTH, "lastAlertTitle"));
            c.check(yel != null && yel.contains("HxMateA") && yel.contains("getting low"), "yellow alert title was '" + yel + "'");
            // Yellow -> red alerts again; red -> yellow does not.
            mates(c, "§c2,900§c❤", "§a30,100§c❤");
            c.waitUntil("the red alert after yellow", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 2, 4);
            String red = c.onClient(mc -> (String) Mod.staticCall(LOW_HEALTH, "lastAlertTitle"));
            c.check(red != null && red.contains("is low") && !red.contains("getting"), "red alert title was '" + red + "'");
            mates(c, "§e5,000§c❤", "§a30,100§c❤");
            c.ctx().waitTicks(15);
            c.check(fired(c) == base + 2, "an alert fired as health went red -> yellow (" + fired(c) + ")");
            // Switch off: yellow is quiet, red still alerts.
            run(c, () -> Mod.call(Mod.cfg(ALERTS_CFG), "setLowHealthYellow", false));
            mates(c, "§a25,000§c❤", "§e8,000§c❤");
            c.ctx().waitTicks(15);
            c.check(fired(c) == base + 2, "a yellow alert fired with Alert At Yellow Health off (" + fired(c) + ")");
            mates(c, "§a25,000§c❤", "§c1,000§c❤");
            c.waitUntil("red still alerts with yellow off", mc -> (Integer) Mod.staticCall(LOW_HEALTH, "alertsFired") == base + 3, 6);
            c.note("yellow -> '" + yel + "' within 4 ticks; red after yellow -> '" + red + "'; red->yellow quiet; yellow off quiet, red alerts");
        } finally {
            dungeon(c);
        }
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
