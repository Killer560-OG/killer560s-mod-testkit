package dev.testkit.server.mixin;

import com.mojang.brigadier.ParseResults;

import dev.testkit.server.hx.HxEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every command a real player sends, handled or not, for Hx ({@code [hx] cmd /...}).
 *
 * <p>{@code Commands.performCommand(ParseResults, String)} is where signed and unsigned player commands both end up
 * (javap 26.1.2), and it runs even when the parse failed - which is what a Hypixel command this server does not
 * have looks like. Fabric has no event for this.
 */
@Mixin(Commands.class)
public class MixinCommands {

    @Inject(method = "performCommand", at = @At("HEAD"))
    private void testkit$recordCommand(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        ServerPlayer player = parse.getContext().getSource().getPlayer();
        if (player != null) {
            HxEvents.command(player, command);
        }
    }
}
