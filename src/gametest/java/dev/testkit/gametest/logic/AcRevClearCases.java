package dev.testkit.gametest.logic;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Mod acrev-clear-1122 (his 11-22 live clears). 1122-logic-acrev-lastdeath: Long Hall (four tiles) was left at its last
 * death with a mob unseen at the far end; the last death now ends a room only when every tile of it is in sight.
 * 1122-logic-acrev-refused-direct: Auto Clear's direct one-warp trip is not started for a landing refused from the spot.
 * 1122-logic-acrev-offreason: Dungeon Autopilot's "off:" line carries the real reason, not "the secrets are done".
 */
final class AcRevClearCases {

    private static final String AC = "autoclear.AutoClearFeature";
    private static final String CE = "livemap.autoclear.ClearExecutor";
    private static final String DA = "autosecret.DungeonsAutopilot";
    private static final String AS = "autosecret.AutoSecretFeature";

    private AcRevClearCases() {
    }

    static void lastDeath(LogicCase c) throws Exception {
        int grid = (Integer) Mod.field("livemap.DungeonLayout", "GRID");
        int gz = 2;
        int[] longHall = new int[4];
        for (int i = 0; i < 4; i++) {
            longHall[i] = gz * grid + i * 2;
        }
        int[] oneTile = {gz * grid + 2};
        Vec3 end = center(0, gz);
        Vec3 near = center(1, gz);
        Vec3 mid = center(3, gz);
        c.check("at the far end of a four-tile room the last death does not end it",
                !(boolean) Mod.staticCall(AC, "roomInSight", end, longHall));
        c.check("from the middle of a four-tile room every tile is in sight", (boolean) Mod.staticCall(AC, "roomInSight", mid, longHall));
        c.check("one tile away from a single-tile room it is in sight", (boolean) Mod.staticCall(AC, "roomInSight", near, oneTile));
        c.note("far end -> " + Mod.staticCall(AC, "roomInSight", end, longHall) + ", middle -> "
                + Mod.staticCall(AC, "roomInSight", mid, longHall));
    }

    private static Vec3 center(int gx, int gz) {
        BlockPos p = (BlockPos) Mod.staticCall("livemap.DungeonLayout", "cellCenter", gx, gz);
        return new Vec3(p.getX(), p.getY(), p.getZ());
    }

    static void refusedDirect(LogicCase c) throws Exception {
        Class<?> tc = Mod.cls("livemap.autoclear.TripCache");
        java.lang.reflect.Method clear = tc.getDeclaredMethod("clear");
        clear.setAccessible(true);
        try {
            clear.invoke(null);
            Vec3 spot = new Vec3(10.5, 65.0, 10.5);
            BlockPos to = new BlockPos(40, 64, 10);
            c.check("before a refusal the direct trip is allowed", !(boolean) Mod.staticCall(CE, "directRefused", spot, to));
            Mod.staticCall("livemap.autoclear.TripCache", "refuseHop", spot, new Vec3(40.5, 65.05, 10.5));
            c.check("after that warp never landed from the spot the direct trip is not started",
                    (boolean) Mod.staticCall(CE, "directRefused", new Vec3(10.7, 65.0, 10.3), to));
            c.check("another landing from the spot is still direct", !(boolean) Mod.staticCall(CE, "directRefused", spot,
                    new BlockPos(20, 64, 10)));
            c.check("the same landing from another spot is still direct", !(boolean) Mod.staticCall(CE, "directRefused",
                    new Vec3(30.5, 65.0, 30.5), to));
        } finally {
            clear.invoke(null);
        }
    }

    static void offReason(LogicCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.skip("off reason", "legit jar: no Dungeon Autopilot");
            return;
        }
        Object level = Minecraft.getInstance().level;
        Mod.setField(DA, "level", level);
        // Auto Secret stops itself with no wither key; the autopilot sees it idle on its next tick.
        Mod.setField(AS, "phase", Mod.enumValue(AS + "$Phase", "DOOR"));
        Mod.staticCall(AS, "stop", "no wither key for the shut door", false);
        c.check("Auto Secret keeps why it stopped", "no wither key for the shut door".equals(Mod.staticCall(AS, "lastStopReason")),
                "kept " + Mod.staticCall(AS, "lastStopReason"));
        Mod.setField(DA, "on", true);
        Mod.setField(DA, "phase", Mod.enumValue(DA + "$Phase", "SECRETS"));
        Mod.staticCall(DA, "tick", Minecraft.getInstance());
        c.check("a no-key stop is logged as that, not as the secrets being done",
                "no wither key for the shut door".equals(Mod.field(DA, "lastOffReason")), "off: " + Mod.field(DA, "lastOffReason"));
        Mod.setField(DA, "on", true);
        Mod.staticCall(DA, "setOn", false);
        c.check("his own switch-off is logged as that", "you switched it off".equals(Mod.field(DA, "lastOffReason")),
                "off: " + Mod.field(DA, "lastOffReason"));
    }
}
