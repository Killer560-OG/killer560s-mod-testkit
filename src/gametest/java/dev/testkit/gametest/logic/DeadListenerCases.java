package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * 1118-logic-deadlisteners-bar (mod deadlisteners-1118): Ability Cooldowns and Metal Detector read the action bar through
 * a plain Fabric GAME / MODIFY_GAME listener, which Hypixel's action bar packet never reaches and which Chat Hider's
 * "Hide actionbar" starves on the system-chat path. Both now hang off PlayerStatsFeature.onActionBar. Each line goes
 * through a real packet handler with Hide actionbar ON: Ink Wand's "-N Mana (Ink Bomb)" as a system-chat overlay (the
 * path Hide actionbar drops) and Fire Veil's as ClientboundSetActionBarTextPacket (Hypixel's own path), then a metal
 * detector "TREASURE: 37.4m" readout in Mines of Divan. Each feature must have acted (cooldown running, distance read).
 * Positive control: the cooldowns are not running before the lines. (SelfDerivation is asserted in 1118-sim-map-hidden-bar.)
 */
final class DeadListenerCases {

    static ClientGameTestContext ctx;

    private static final String CHAT_TIDY = "chattidy.ChatTidyConfig";
    private static final String ABILITY_CFG = "abilitycooldown.AbilityCooldownConfig";
    private static final String ABILITY_STATE = "abilitycooldown.AbilityCooldownState";
    private static final String ITEM_ABILITY = "abilitycooldown.ItemAbility";
    private static final String METAL_CFG = "mining.metaldetector.MetalDetectorConfig";
    private static final String METAL = "mining.metaldetector.MetalDetectorFeature";
    private static final String ISLAND = "pathfinding.IslandDetector";

    private DeadListenerCases() {
    }

    private static boolean cooling(String ability) {
        return ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(ABILITY_STATE, "isOnCooldown",
                Mod.enumValue(ITEM_ABILITY, ability)));
    }

    static void bar(LogicCase c) {
        boolean wasHide = (Boolean) Mod.get(CHAT_TIDY, "getHideActionbarRaw");
        boolean wasAbility = (Boolean) Mod.get(ABILITY_CFG, "isEnabled");
        boolean wasBarDetect = (Boolean) Mod.get(ABILITY_CFG, "isActionBarDetection");
        boolean wasMetal = (Boolean) Mod.get(METAL_CFG, "isEnabled");
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                Mod.set(CHAT_TIDY, "setHideActionbar", true);
                Mod.set(ABILITY_CFG, "setEnabled", true);
                Mod.set(ABILITY_CFG, "setActionBarDetection", true);
                Mod.set(METAL_CFG, "setEnabled", true);
            });
            c.check("control: Ink Wand is not cooling down yet", !cooling("INK_WAND"), "");
            c.check("control: Fire Veil is not cooling down yet", !cooling("FIRE_VEIL"), "");
            // System-chat overlay: the path Hide actionbar drops (ALLOW_GAME returns false).
            ctx.runOnClient(mc -> mc.getConnection().handleSystemChat(new ClientboundSystemChatPacket(
                    Component.literal("§b-30 Mana (§6Ink Bomb§b)"), true)));
            c.check("Ink Wand cooldown started from a system-chat action bar with Hide actionbar ON", cooling("INK_WAND"), "");
            // Hypixel's own action bar packet.
            ctx.runOnClient(mc -> mc.getConnection().handleSetActionBarText(new ClientboundSetActionBarTextPacket(
                    Component.literal("§b-20 Mana (§6Fire Veil§b)"))));
            c.check("Fire Veil cooldown started from the action bar packet", cooling("FIRE_VEIL"), "");
            // Metal Detector: Crystal Hollows, Mines of Divan; set and read in one client tick so no area tick undoes it.
            Object read = ctx.computeOnClient(mc -> {
                Mod.setField(ISLAND, "graphIsland", "CRYSTAL_HOLLOWS");
                Mod.setField(ISLAND, "scoreboardArea", "Mines of Divan");
                Mod.setField(METAL, "prevDistance", -1.0);
                mc.getConnection().handleSetActionBarText(new ClientboundSetActionBarTextPacket(
                        Component.literal("§3§lTREASURE: §b37.4m")));
                Object d = Mod.field(METAL, "prevDistance");
                Mod.setField(ISLAND, "graphIsland", null);
                Mod.setField(ISLAND, "scoreboardArea", "");
                return d;
            });
            c.eq("Metal Detector read the TREASURE distance from the action bar packet", 37.4, read);
            c.note("Ink Wand (system chat, Hide ON), Fire Veil (action bar packet) and Metal Detector (TREASURE 37.4m) all acted");
        } finally {
            ctx.runOnClient(mc -> {
                Mod.set(CHAT_TIDY, "setHideActionbar", wasHide);
                Mod.set(ABILITY_CFG, "setEnabled", wasAbility);
                Mod.set(ABILITY_CFG, "setActionBarDetection", wasBarDetect);
                Mod.set(METAL_CFG, "setEnabled", wasMetal);
            });
        }
    }
}
