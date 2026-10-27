package dev.testkit.server.grimprobe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

/** GrimAC 2.3.74 {@code GrimPlayer}'s fields the place-gap probe reads (javap). Reflection on that class fails: listing its
 *  fields loads ViaVersion types the test server does not have. */
@Mixin(targets = "ac.grim.grimac.player.GrimPlayer", remap = false)
public interface GrimPlayerAccess {

    @Accessor(value = "placeUseItemPackets", remap = false)
    Queue<?> testkit$placeUseItemPackets();

    @Accessor(value = "lastBlockPlaceUseItem", remap = false)
    long testkit$lastBlockPlaceUseItem();

    @Accessor(value = "yaw", remap = false)
    float testkit$yaw();

    @Accessor(value = "pitch", remap = false)
    float testkit$pitch();
}
