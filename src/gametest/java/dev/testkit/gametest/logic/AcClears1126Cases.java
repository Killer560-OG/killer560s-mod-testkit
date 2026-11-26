package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.world.phys.Vec3;

/**
 * Mod autopilot-clears-1126 (his 11-26 Hypixel clears, logger session 20261125-232944-36672).
 * 1126-logic-acclears-doorgap: at 01:08:30 and 01:18:17 the Entrance's roof overhung its doorway, the world scan read the
 * gap as a connector (roof 98) and before the map the Entrance had no door, so Dungeons Autopilot decided "nothing left it
 * can reach" at the start. The gate's bricks standing in the gap make it a door whatever the roof.
 * 1126-logic-acclears-castteleport: at 01:01:42 and :44 an etherwarp onto a carpet landed 1.05 above its block and a
 * Hyperion cast in place while he fell was moved to the floor by Wither Impact (69.82 -> 69.00, 2 ticks after the use):
 * Auto Clear counted the cast's own teleport as a server correction and replanned.
 */
final class AcClears1126Cases {

    private static final String LM = "livemap.LiveMapFeature";
    private static final String AC = "autoclear.AutoClearFeature";

    private AcClears1126Cases() {
    }

    static void doorGap(LogicCase c) throws Exception {
        c.check("a gap with the gate's bricks under a roof at 98 (his 01:08 and 01:18 Entrances) is a door",
                (boolean) Mod.staticCall(LM, "doorGap", 98, true));
        c.check("a gap under a roof at 98 with no gate is a connector", !(boolean) Mod.staticCall(LM, "doorGap", 98, false));
        c.check("a gap under a doorway's roof (73) is a door as before", (boolean) Mod.staticCall(LM, "doorGap", 73, false));
        c.check("a gap under a roof at 99 with no gate is a connector as before",
                !(boolean) Mod.staticCall(LM, "doorGap", 99, false));
    }

    static void castTeleport(LogicCase c) throws Exception {
        Vec3 from = new Vec3(-120.5, 70.05, -136.5);   // his 01:01:42 landing on the carpet, cast pitch 90
        Vec3 down = new Vec3(0, -1, 0);
        Vec3 floor = new Vec3(-120.5, 69.0, -136.5);
        c.check("the floor right under a cast in place, 2 ticks after it, is the cast's own teleport",
                (boolean) Mod.staticCall(AC, "castOwnTeleport", from, down, floor, 2L));
        c.check("the same packet 8 ticks after the cast is a correction",
                !(boolean) Mod.staticCall(AC, "castOwnTeleport", from, down, floor, 8L));
        c.check("a packet 3 blocks to the side of a cast straight down is a correction",
                !(boolean) Mod.staticCall(AC, "castOwnTeleport", from, down, new Vec3(-123.5, 69.0, -136.5), 2L));
        Vec3 ahead = new Vec3(1, 0, 0);
        c.check("8 blocks along a level cast is its own teleport",
                (boolean) Mod.staticCall(AC, "castOwnTeleport", from, ahead, new Vec3(-112.5, 70.05, -136.5), 1L));
        c.check("8 blocks BEHIND a level cast is a correction",
                !(boolean) Mod.staticCall(AC, "castOwnTeleport", from, ahead, new Vec3(-128.5, 70.05, -136.5), 1L));
        c.check("with no cast pressed yet nothing is a cast's teleport",
                !(boolean) Mod.staticCall(AC, "castOwnTeleport", null, null, floor, 0L));
    }
}
