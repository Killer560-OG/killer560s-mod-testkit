package dev.testkit.server.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Every public constructor of {@link ClientboundPlayerInfoUpdatePacket} takes {@code ServerPlayer}s (javap 26.1.2);
 * the entry list is a private final field. Hypixel's tab list is eighty fake profiles, so {@code tab.set} builds the
 * packet with no players and sets the entries - the same approach as the mod's own {@code SimPlayerInfoPacketAccessor}.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface AccessorPlayerInfoUpdatePacket {

    @Mutable
    @Accessor("entries")
    void testkit$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
