package dev.testkit.gametest.ui;

import dev.testkit.compat.McCompat;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Keeps the WP4 singleplayer player alive. The gametest world is superflat at y -60; run 7 of 380 lifted the player
 * to y 106 to leave "limbo", he fell, died, and the client sat on the death screen (seen by the user, 2026-10-04).
 * Every world case now starts creative, invulnerable, flying, with gamerule FALL_DAMAGE off; and a case that finds a
 * DeathScreen respawns the player and says so instead of hanging behind it.
 */
final class SafeWorld {

    private SafeWorld() {
    }

    /** Creative + invulnerable + flying + no fall damage, on the integrated server. Waits until the client sees it. */
    static void apply(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.player == null) {
                return;
            }
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                server.overworld().getGameRules().set(GameRules.FALL_DAMAGE, false, server);
                var sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    sp.setGameMode(GameType.CREATIVE);
                    sp.getAbilities().invulnerable = true;
                    sp.getAbilities().flying = true;
                    sp.onUpdateAbilities();
                }
            });
        });
        ctx.waitFor(mc -> mc.player != null && mc.player.getAbilities().invulnerable
                && mc.player.getAbilities().flying, 100);
    }

    /** If the client is on the death screen, respawn and re-apply; returns true if it had to. */
    static boolean reviveIfDead(ClientGameTestContext ctx) {
        boolean dead = ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof DeathScreen
                || (mc.player != null && mc.player.isDeadOrDying()));
        if (!dead) {
            return false;
        }
        System.out.println("[ui] the player was DEAD (death screen up) - respawning before the next case");
        ctx.runOnClient(mc -> {
            if (mc.player != null) {
                mc.player.respawn();
            }
            McCompat.setScreen(mc, null);
        });
        ctx.waitFor(mc -> mc.player != null && !mc.player.isDeadOrDying(), 200);
        apply(ctx);
        return true;
    }
}
