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

    // The dig-gap probe (multibreak-1102 RotationBreak root cause).
    @Accessor(value = "lastBlockBreak", remap = false)
    long testkit$lastBlockBreak();

    @Accessor(value = "queuedBreaks", remap = false)
    Queue<?> testkit$queuedBreaks();

    @Accessor(value = "lastYaw", remap = false)
    float testkit$lastYaw();

    @Accessor(value = "lastPitch", remap = false)
    float testkit$lastPitch();

    @Accessor(value = "x", remap = false)
    double testkit$x();

    @Accessor(value = "y", remap = false)
    double testkit$y();

    @Accessor(value = "z", remap = false)
    double testkit$z();
}
