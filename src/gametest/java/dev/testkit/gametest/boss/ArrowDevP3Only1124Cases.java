package dev.testkit.gametest.boss;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.hx.TriggerTiming;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Arrow Align "Trigger Bot Only During P3" and "Aura Only During P3" (mod arrowdev-p3only-1124, killer560 2026-11-24).
 * With the option ON the automation sends nothing outside P3 and acts in P3; with it OFF it acts outside P3 too; the
 * solver stays active in both. Each case is cheat only (the automations do not exist on a legit jar). The wire is read
 * from the server's own event log ({@code use_entity}).
 */
final class ArrowDevP3Only1124Cases {
    private static final String AA = Mod.ROOT + "arrowalign.ArrowAlignFeature";
    private static final AABB WALL = new AABB(-4, 118, 73, 0, 126, 81);

    private ArrowDevP3Only1124Cases() {
    }

    static void register(Session s) {
        s.test("1124-boss-arrowdev-p3only-trigger", ArrowDevP3Only1124Cases::trigger);
        s.test("1124-boss-arrowdev-p3only-aura", ArrowDevP3Only1124Cases::aura);
    }

    private static int frameId(Session c, int idx) {
        BlockPos at = new BlockPos(-2, 120 + idx % 5, 75 + idx / 5);
        return c.onClient(mc -> mc.level.getEntitiesOfClass(ItemFrame.class, WALL).stream()
                .filter(f -> f.blockPosition().equals(at)).mapToInt(ItemFrame::getId).findFirst().orElse(-1));
    }

    private static boolean solverActive(Session c) {
        return c.onClient(mc -> Boolean.TRUE.equals(Mod.field(AA, "active")) && Mod.field(AA, "solution") != null);
    }

    private static long fire(Session c, int idx) {
        int id = frameId(c, idx);
        c.check(id >= 0, "no arrow frame at grid index " + idx);
        TriggerTiming.lookAway(c);
        c.ctx().waitTicks(10);
        Vec3 at = new Vec3(-1.97, 120 + idx % 5 + 0.5, 75 + idx / 5 + 0.5);
        long t = TriggerTiming.ticksToFire(c, at, "use_entity", e -> e.get("entityId").getAsInt() == id, null, 60);
        TriggerTiming.lookAway(c);
        c.ctx().waitTicks(10);
        return t;
    }

    private static void trigger(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Trigger Bot to gate");
            return;
        }
        String cfg = BossTriggerDelayCases.AA_CFG;
        int[] was = c.onClient(mc -> new int[]{(Integer) Mod.call(Mod.cfg(cfg), "getTriggerBotDelayMin"),
                (Integer) Mod.call(Mod.cfg(cfg), "getTriggerBotDelayMax")});
        try (HxKit.Settings off = BossTriggerDelayCases.gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(cfg, "SolverEnabled", true)
                     .with(cfg, "PreventMisclicksEnabled", false).with(cfg, "AuraEnabled", false)
                     .with(cfg, "TriggerBotEnabled", true).with(cfg, "TriggerBotOnlyP3", true)
                     .custom(() -> Mod.call(Mod.cfg(cfg), "setTriggerBotDelayRange", 50, 50),
                             () -> Mod.call(Mod.cfg(cfg), "setTriggerBotDelayRange", was[0], was[1]))) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            BossTriggerDelayCases.buildWall(c);
            c.check("UNKNOWN".equals(String.valueOf(c.<Object>onClient(mc -> Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase")))),
                    "not outside P3 at the start");
            long onOutside = fire(c, 7);
            c.check(solverActive(c), "solver not showing outside P3 with the option ON");
            c.note("trigger ON outside P3: " + onOutside + " (-1 = never fired)");
            c.check(onOutside < 0, "Trigger Bot Only During P3 ON still clicked outside P3");
            c.onClient(mc -> Mod.call(Mod.cfg(cfg), "setTriggerBotOnlyP3", false));
            long offOutside = fire(c, 12);
            c.note("trigger OFF outside P3: " + offOutside);
            c.check(offOutside >= 0, "option OFF: the Trigger Bot did not click outside P3 (it acted nowhere)");
            c.onClient(mc -> Mod.call(Mod.cfg(cfg), "setTriggerBotOnlyP3", true));
            BossTriggerDelayCases.p3(c);
            long onInside = fire(c, 7);
            c.note("trigger ON inside P3: " + onInside);
            c.check(onInside >= 0, "option ON: the Trigger Bot did not click inside P3");
            c.check(solverActive(c), "solver not showing inside P3");
            c.check(c.scenario().flags().isEmpty(), "anticheat flags: " + c.scenario().flags());
        } finally {
            BossTriggerDelayCases.clearWall(c);
        }
    }

    /** All frames gone and the nine put back, so the Aura has clicks to send again. */
    private static void resetFrames(Session c) {
        c.server().command("kill @e[type=minecraft:item_frame,x=-2,y=122,z=77,distance=..8]");
        c.waitUntil("the old frames gone", mc -> mc.level.getEntitiesOfClass(ItemFrame.class, WALL).isEmpty(), 200);
        for (int idx : BossTriggerDelayCases.LAYOUT) {
            c.hx().call("frame", "x", -2, "y", 120 + idx % 5, "z", 75 + idx / 5, "facing", "east", "item", "arrow");
        }
        c.waitUntil("the new frames", mc -> mc.level.getEntitiesOfClass(ItemFrame.class, WALL).size()
                == BossTriggerDelayCases.LAYOUT.length, 200);
        c.ctx().waitTicks(10);
    }

    private static int clicksIn(Session c, int ticks) {
        long since = c.hx().head();
        c.ctx().waitTicks(ticks);
        return c.hx().events(since, "use_entity").size();
    }

    private static void aura(Session c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: no Aura to gate");
            return;
        }
        String cfg = BossTriggerDelayCases.AA_CFG;
        try (HxKit.Settings off = BossTriggerDelayCases.gateOff(c);
             HxKit.Settings on = new HxKit.Settings(c).with(cfg, "SolverEnabled", true)
                     .with(cfg, "PreventMisclicksEnabled", false).with(cfg, "TriggerBotEnabled", false)
                     .with(cfg, "AuraEnabled", true).with(cfg, "AuraOnlyP3", true).with(cfg, "AuraLeaveLastArrow", false)
                     .with(cfg, "AuraCooldownMs", 50)) {
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            BossTriggerDelayCases.buildWall(c);
            int onOutside = clicksIn(c, 60);
            c.note("aura ON outside P3: " + onOutside + " clicks");
            c.check(solverActive(c), "solver not showing outside P3 with the option ON");
            c.check(onOutside == 0, "Aura Only During P3 ON still sent " + onOutside + " clicks outside P3");
            c.onClient(mc -> Mod.call(Mod.cfg(cfg), "setAuraOnlyP3", false));
            int offOutside = clicksIn(c, 60);
            c.note("aura OFF outside P3: " + offOutside + " clicks");
            c.check(offOutside > 0, "option OFF: the Aura sent nothing outside P3 (it acted nowhere)");
            c.onClient(mc -> Mod.call(Mod.cfg(cfg), "setAuraOnlyP3", true));
            resetFrames(c);
            BossTriggerDelayCases.p3(c);
            int onInside = clicksIn(c, 60);
            c.note("aura ON inside P3: " + onInside + " clicks");
            c.check(onInside > 0, "option ON: the Aura sent nothing inside P3");
            c.check(c.scenario().flags().isEmpty(), "anticheat flags: " + c.scenario().flags());
        } finally {
            BossTriggerDelayCases.clearWall(c);
        }
    }
}
