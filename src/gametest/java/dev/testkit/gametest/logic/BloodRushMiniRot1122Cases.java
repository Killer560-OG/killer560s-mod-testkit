package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;

/**
 * Mod branch mapname-1122 (killer560, 2026-11-22, his F7 run, latest.log 19:50:02): Blood Rush Helper's mob scan recorded a
 * miniboss look with {@code layout.clayRotation(room)} indexed while the room's rotation was not found yet, a NullPointerException
 * three times over switched the feature off. 1122-logic-bloodrush-minirot: on a named room with no rotation the frame lookup answers
 * "not yet" instead of throwing.
 */
final class BloodRushMiniRot1122Cases {

    private BloodRushMiniRot1122Cases() {
    }

    static void noRotation(LogicCase c) {
        try {
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[]{"a"}, new String[]{"Diagonal|1x1|NORMAL"});
            Object known = Mod.staticCall("bloodrush.BloodRushHelperFeature", "testMinibossFrameKnown", "Diagonal");
            c.check("a named room with no rotation yet has no miniboss frame (not an exception)", Boolean.FALSE.equals(known));
        } finally {
            Mod.staticCall("livemap.LiveMapFeature", "testLoadFloor", new String[0], new String[0]);
        }
    }
}
