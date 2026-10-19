package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.hx.TriggerTiming;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mod branch todo-arrowalign-cps-1018 (killer560, 2026-10-18): the Arrow Align Trigger Bot's delay became a CPS range;
 * the Trigger Bot, the Aura and the new Leave Last Arrow each got their own "Disable While Crouching" (ON by default);
 * the Aura leaves the lowest, leftmost arrow undone until stage S3 starts.
 *
 * <p>Every click is read from the SERVER's record ({@code use_entity}), after the case has proved its own premise (the
 * player really is crouching, the frames are there, the stage is what the case set). Cheat jar only.
 * <ul>
 *   <li>1018-arrowalign-cps-range: a frame wanting seven clicks under the crosshair, Trigger Bot at 3-20 CPS: seven
 *   clicks, every gap inside the range (tick-rounded), not all the same; and 500 rolled gaps all inside the range and
 *   varied. Fails on main (no range setting).</li>
 *   <li>1018-arrowalign-cps-oldconfig: a saved config with only {@code triggerBotDelayMs} loads as min = that delay's
 *   CPS, max = min + 2; a saved range loads as saved.</li>
 *   <li>1018-arrowalign-crouch-triggerbot / -aura / -leavelast: crouching with the toggle ON sends nothing (and
 *   clicks resume on release); with the toggle OFF the feature runs while crouching.</li>
 *   <li>1018-arrowalign-leavelast: before S3 the Aura clicks every frame but the lowest, leftmost one; at S3 it does that
 *   one too.</li>
 * </ul>
 */
final class ArrowAlignCpsCases {

    private static final String AA_CFG = "arrowalign.ArrowAlignConfig";
    private static final String AA_FEATURE = Mod.ROOT + "arrowalign.ArrowAlignFeature";
    private static final String TRACKER = Mod.ROOT + "fastleap.Floor7Tracker";
    /** Layout 7 of the mod's solutions (grid index dy + dz * 5), every frame wanting rotation 1. */
    private static final int[] LAYOUT = {5, 7, 9, 10, 12, 14, 15, 17, 19};
    /** Lowest row (dy 0), then the leftmost as seen from the east looking west (the larger z): grid index 15. */
    private static final int HELD = 15;
    private static final AABB BOX = new AABB(-4, 118, 73, 0, 126, 81);

    private ArrowAlignCpsCases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        s.test("1018-arrowalign-cps-range", ArrowAlignCpsCases::cpsRange);
        s.test("1018-arrowalign-cps-oldconfig", ArrowAlignCpsCases::oldConfig);
        s.test("1018-arrowalign-crouch-triggerbot", c -> crouch(c, "trigger"));
        s.test("1018-arrowalign-crouch-aura", c -> crouch(c, "aura"));
        s.test("1018-arrowalign-crouch-leavelast", ArrowAlignCpsCases::crouchLeaveLast);
        s.test("1018-arrowalign-leavelast", ArrowAlignCpsCases::leaveLast);
        s.test("1018-arrowalign-multiclick", ArrowAlignCpsCases::multiClick);
    }

    // ---- shared set-up ----------------------------------------------------------------------------------------------

    private static HxKit.Settings gateOff(Session c) {
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        return new HxKit.Settings(c).custom(() -> Mod.staticCall("util.SkyblockGate", "setEnabled", false),
                () -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas));
    }

    private static HxKit.Settings arrowSettings(Session c) {
        return new HxKit.Settings(c).with(AA_CFG, "SolverEnabled", true).with(AA_CFG, "PreventMisclicksEnabled", false)
                .with(AA_CFG, "TriggerBotEnabled", false).with(AA_CFG, "AuraEnabled", false)
                .with(AA_CFG, "AuraRange", 3.0).with(AA_CFG, "AuraCps", 20)
                .with(AA_CFG, "AuraLeaveLastArrow", false)
                .with(AA_CFG, "TriggerBotDisableWhileCrouching", true).with(AA_CFG, "AuraDisableWhileCrouching", true)
                .with(AA_CFG, "LeaveLastArrowDisableWhileCrouching", true);
    }

    private static void stand(Session c) {
        stand(c, 120.0);
    }

    /** {@code y} 121 stands him on an extra stone layer: a crouched eye is 0.35 lower, which put the top row of frames
     *  out of the Aura's 3.0 reach (the first run's crouch-aura OFF phase never finished). */
    private static void stand(Session c, double y) {
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        c.server().command("forceload add -3 72 3 82");
        c.server().sync(20);
        c.server().command("fill -1 119 72 3 119 82 stone");
        c.server().command("fill -3 119 74 -3 125 80 stone");
        if (y > 120.5) {
            c.server().command("fill -1 120 72 3 120 82 stone");
        }
        c.hx().call("dungeon.tp", "x", -0.7, "y", y, "z", 77.5, "yaw", 90f, "pitch", 0f);
        c.waitUntil("standing by the wall", mc -> mc.player.onGround() && Math.abs(mc.player.getY() - y) < 0.01
                && mc.level.getBlockState(new BlockPos(-3, 122, 77)).is(net.minecraft.world.level.block.Blocks.STONE), 200);
    }

    private static void leave(Session c) {
        c.onClient(mc -> {
            mc.options.keyShift.setDown(false);
            return null;
        });
        c.hx().removeStands();
        c.server().command("kill @e[type=item_frame,x=-2,y=122,z=77,distance=..6]");
        c.server().command("fill -3 119 72 3 125 82 air");
        c.server().command("forceload remove -3 72 3 82");
        c.hx().sidebarClear();
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
    }

    /** Layout 7 afresh; {@code seven} (a grid index or -1) starts at rotation 2, seven clicks from solved. Ids by index. */
    private static int[] grid(Session c, int seven) {
        c.hx().removeStands();
        c.waitUntil("the old frames gone", mc -> mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ItemFrame.class, BOX).isEmpty(), 100);
        int[] ids = new int[25];
        java.util.Arrays.fill(ids, -1);
        for (int idx : LAYOUT) {
            JsonObject f = c.hx().call("frame", "x", -2, "y", 120 + idx % 5, "z", 75 + idx / 5, "facing", "east",
                    "item", "arrow", "rotation", idx == seven ? 2 : 0).getAsJsonObject();
            ids[idx] = f.get("id").getAsInt();
        }
        c.waitUntil("the client to have the frames", mc -> mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ItemFrame.class, BOX).size() == LAYOUT.length, 200);
        c.waitUntil("Arrow Align to read a solution", mc -> Mod.field(AA_FEATURE, "solution") != null, 100);
        c.ctx().waitTicks(3);
        return ids;
    }

    private static List<JsonObject> onFrame(Session c, long since, int frameId) {
        return c.hx().events(since, "use_entity").stream().filter(e -> e.get("entityId").getAsInt() == frameId).toList();
    }

    private static void sneak(Session c, boolean down) {
        c.onClient(mc -> {
            mc.options.keyShift.setDown(down);
            return null;
        });
        c.ctx().waitTicks(4);
        boolean now = c.onClient(mc -> mc.player.isShiftKeyDown());
        c.check(now == down, "premise: the player's crouch is " + now + ", wanted " + down);
    }

    private static void set(Session c, String setter, Object... args) {
        c.onClient(mc -> {
            Mod.call(Mod.cfg(AA_CFG), setter, args);
            return null;
        });
    }

    // ---- cps range --------------------------------------------------------------------------------------------------

    private static void cpsRange(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c); HxKit.Settings on = arrowSettings(c)) {
            stand(c);
            int centre = grid(c, 12)[12];
            set(c, "setTriggerBotCpsRange", 3, 20);
            Vec3 at = new Vec3(-1.97, 122.5, 77.5);
            TriggerTiming.look(c, at);
            c.ctx().waitTicks(3);
            long mark = c.hx().head();
            set(c, "setTriggerBotEnabled", true);
            c.waitUntil("seven trigger bot clicks on the centre frame", mc -> onFrame(c, mark, centre).size() >= 7, 400);
            set(c, "setTriggerBotEnabled", false);
            List<JsonObject> mine = onFrame(c, mark, centre);
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < mine.size(); i++) {
                gaps.add(mine.get(i).get("tick").getAsLong() - mine.get(i - 1).get("tick").getAsLong());
            }
            c.note("trigger bot 3-20 CPS: " + mine.size() + " clicks, gaps in ticks " + gaps);
            c.check(mine.size() == 7, "the centre frame got " + mine.size() + " clicks, it needed 7");
            // 20 CPS is a 1-tick gap; 3 CPS is 6.67 ticks, so 7 once the fractional schedule is rounded to ticks.
            // A server tick can hold two client ticks' packets (a gap of 0), so one gap is read loosely (0-8) and the
            // average tightly: six gaps average 1 tick at the least (20 CPS) and 6.7 at the most (3 CPS).
            for (long g : gaps) {
                c.check(g >= 0 && g <= 8, "a gap of " + g + " ticks is outside the 3-20 CPS range");
            }
            double mean = gaps.stream().mapToLong(Long::longValue).sum() / (double) gaps.size();
            c.check(mean >= 0.9 && mean <= 7.5,
                    String.format(Locale.ROOT, "mean gap %.2f ticks is outside the 3-20 CPS range", mean));
            c.check(new HashSet<>(gaps).size() >= 2, "every click gap was the same: " + gaps);

            // The roll itself, 500 times: each gap inside the range exactly, and varied.
            Object cfg = c.onClient(mc -> Mod.cfg(AA_CFG));
            set(c, "setTriggerBotCpsRange", 8, 12);
            Set<Long> distinct = new HashSet<>();
            double lo = Double.MAX_VALUE;
            double hi = 0;
            for (int i = 0; i < 500; i++) {
                double g = (Double) c.onClient(mc -> Mod.staticCall("arrowalign.ArrowAlignFeature", "rollTriggerGapTicks",
                        Mod.cfg(AA_CFG)));
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
                distinct.add(Math.round(g * 1000));
            }
            c.note(String.format(Locale.ROOT, "500 rolled gaps at 8-12 CPS: %.3f - %.3f ticks (want 1.667 - 2.500), %d distinct",
                    lo, hi, distinct.size()));
            c.check(lo >= 20.0 / 12 - 1e-9 && hi <= 20.0 / 8 + 1e-9, "a rolled gap left the 8-12 CPS range");
            c.check(distinct.size() > 100, "the rolled gaps were not varied: " + distinct.size() + " distinct");
        } finally {
            leave(c);
        }
    }

    // ---- old config -------------------------------------------------------------------------------------------------

    private static void oldConfig(Session c) throws Exception {
        Path file = c.onClient(mc -> (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-arrowalign.json"));
        byte[] saved = Files.exists(file) ? Files.readAllBytes(file) : null;
        try {
            String[][] cases = {
                    {"{\"triggerBotDelayMs\":67}", "15", "17"},
                    {"{\"triggerBotDelayMs\":0}", "20", "20"},
                    {"{\"triggerBotDelayMs\":200}", "5", "7"},
                    {"{\"triggerBotDelayMs\":500}", "2", "4"},
                    {"{\"triggerBotCpsMin\":6,\"triggerBotCpsMax\":9,\"triggerBotDelayMs\":67}", "6", "9"},
                    {"{}", "15", "17"},
            };
            for (String[] k : cases) {
                Files.writeString(file, k[0], StandardCharsets.UTF_8);
                c.onClient(mc -> Mod.staticCall("arrowalign.ArrowAlignConfig", "load"));
                int min = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(AA_CFG), "getTriggerBotCpsMin"));
                int max = c.onClient(mc -> (Integer) Mod.call(Mod.cfg(AA_CFG), "getTriggerBotCpsMax"));
                c.note(k[0] + " -> " + min + "-" + max);
                c.check(min == Integer.parseInt(k[1]) && max == Integer.parseInt(k[2]),
                        k[0] + " loaded as " + min + "-" + max + ", wanted " + k[1] + "-" + k[2]);
            }
            // Saved and loaded: the range and the four toggles survive a save and a load.
            c.onClient(mc -> {
                Object cfg = Mod.cfg(AA_CFG);
                Mod.call(cfg, "setTriggerBotCpsRange", 4, 11);
                Mod.call(cfg, "setTriggerBotDisableWhileCrouching", false);
                Mod.call(cfg, "setAuraDisableWhileCrouching", false);
                Mod.call(cfg, "setAuraLeaveLastArrow", false);
                Mod.call(cfg, "setLeaveLastArrowDisableWhileCrouching", false);
                Mod.call(cfg, "save");
                Mod.staticCall("arrowalign.ArrowAlignConfig", "load");
                return null;
            });
            Object[] back = c.onClient(mc -> {
                Object cfg = Mod.cfg(AA_CFG);
                return new Object[]{Mod.call(cfg, "getTriggerBotCpsMin"), Mod.call(cfg, "getTriggerBotCpsMax"),
                        Mod.call(cfg, "isTriggerBotDisableWhileCrouching"), Mod.call(cfg, "isAuraDisableWhileCrouching"),
                        Mod.call(cfg, "isAuraLeaveLastArrow"), Mod.call(cfg, "isLeaveLastArrowDisableWhileCrouching")};
            });
            c.check(back[0].equals(4) && back[1].equals(11) && Boolean.FALSE.equals(back[2])
                    && Boolean.FALSE.equals(back[3]) && Boolean.FALSE.equals(back[4]) && Boolean.FALSE.equals(back[5]),
                    "the saved range/toggles did not come back: " + java.util.Arrays.toString(back));
        } finally {
            if (saved == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, saved);
            }
            c.onClient(mc -> Mod.staticCall("arrowalign.ArrowAlignConfig", "load"));
        }
    }

    // ---- crouch -----------------------------------------------------------------------------------------------------

    /** {@code who} is "trigger" or "aura": nothing while crouching with the toggle ON, resumes on release, runs with it OFF. */
    private static void crouch(Session c, String who) throws Exception {
        boolean trigger = who.equals("trigger");
        String toggle = trigger ? "setTriggerBotDisableWhileCrouching" : "setAuraDisableWhileCrouching";
        try (HxKit.Settings off = gateOff(c); HxKit.Settings on = arrowSettings(c)) {
            stand(c, 121.0);
            set(c, "setTriggerBotCpsRange", 20, 20);
            Vec3 at = new Vec3(-1.97, 122.5, 77.5);
            int want = trigger ? 7 : LAYOUT.length;

            // Toggle ON, crouching: nothing.
            set(c, toggle, true);
            int[] ids = grid(c, trigger ? 12 : -1);
            sneak(c, true);
            if (trigger) {
                TriggerTiming.look(c, at);
                c.ctx().waitTicks(3);
            }
            long mark = c.hx().head();
            enable(c, trigger, true);
            c.ctx().waitTicks(40);
            int whileCrouched = c.hx().events(mark, "use_entity").size();
            c.note(who + " with Disable While Crouching ON, crouched 40 ticks: " + whileCrouched + " use_entity");
            c.check(whileCrouched == 0, who + " sent " + whileCrouched + " clicks while crouching with the toggle ON");

            // Released: it resumes and finishes.
            sneak(c, false);
            if (trigger) {
                TriggerTiming.look(c, at);
            }
            c.waitUntil(who + " to resume after the crouch is released",
                    mc -> c.hx().events(mark, "use_entity").size() >= want, 300);
            enable(c, trigger, false);
            c.check(c.hx().events(mark, "use_entity").size() == want, who + " sent "
                    + c.hx().events(mark, "use_entity").size() + " clicks after release, wanted " + want);

            // Toggle OFF, crouching: it runs.
            set(c, toggle, false);
            grid(c, trigger ? 12 : -1);
            sneak(c, true);
            if (trigger) {
                TriggerTiming.look(c, at);
                c.ctx().waitTicks(3);
            }
            long mark2 = c.hx().head();
            enable(c, trigger, true);
            c.waitUntil(who + " to click while crouching with the toggle OFF",
                    mc -> c.hx().events(mark2, "use_entity").size() >= want, 300);
            enable(c, trigger, false);
            c.note(who + " with the toggle OFF, crouched: " + c.hx().events(mark2, "use_entity").size() + " use_entity");
            c.check(ids.length > 0, "frames");
        } finally {
            leave(c);
        }
    }

    private static void enable(Session c, boolean trigger, boolean on) {
        set(c, trigger ? "setTriggerBotEnabled" : "setAuraEnabled", on);
    }

    // ---- leave last arrow -------------------------------------------------------------------------------------------

    private static void stage(Session c, String name) {
        c.onClient(mc -> {
            Mod.setField(TRACKER, "stage", Mod.enumValue(TRACKER + "$Stage", name));
            return null;
        });
        String now = c.onClient(mc -> String.valueOf(Mod.staticCall("fastleap.Floor7Tracker", "getStage")));
        c.check(now.equals(name), "premise: the stage reads " + now + ", wanted " + name);
    }

    private static void leaveLast(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c); HxKit.Settings on = arrowSettings(c)
                .with(AA_CFG, "AuraLeaveLastArrow", true)) {
            stand(c);
            stage(c, "S2");
            int[] ids = grid(c, -1);
            long mark = c.hx().head();
            set(c, "setAuraEnabled", true);
            c.waitUntil("eight frames clicked before S3", mc -> c.hx().events(mark, "use_entity").size() >= 8, 300);
            c.ctx().waitTicks(40);
            int total = c.hx().events(mark, "use_entity").size();
            int held = onFrame(c, mark, ids[HELD]).size();
            c.note("before S3: " + total + " use_entity, " + held + " on the lowest-leftmost frame (grid " + HELD + ")");
            c.check(total == 8, "before S3 the Aura sent " + total + " clicks, wanted 8");
            c.check(held == 0, "before S3 the Aura clicked the frame it must leave (" + held + ")");

            stage(c, "S3");
            c.waitUntil("the held frame clicked once S3 starts", mc -> !onFrame(c, mark, ids[HELD]).isEmpty(), 200);
            c.check(onFrame(c, mark, ids[HELD]).size() == 1, "the held frame got "
                    + onFrame(c, mark, ids[HELD]).size() + " clicks at S3, wanted 1");
            set(c, "setAuraEnabled", false);
        } finally {
            stage(c, "UNKNOWN");
            leave(c);
        }
    }

    private static void crouchLeaveLast(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c); HxKit.Settings on = arrowSettings(c)
                .with(AA_CFG, "AuraLeaveLastArrow", true).with(AA_CFG, "AuraDisableWhileCrouching", false)) {
            stand(c, 121.0);
            stage(c, "S2");
            // Toggle ON, crouched: Leave Last Arrow is suspended, so the Aura does all nine.
            set(c, "setLeaveLastArrowDisableWhileCrouching", true);
            int[] ids = grid(c, -1);
            sneak(c, true);
            long mark = c.hx().head();
            set(c, "setAuraEnabled", true);
            c.waitUntil("all nine clicked while crouched, toggle ON", mc -> c.hx().events(mark, "use_entity").size() >= 9, 300);
            c.ctx().waitTicks(20);
            set(c, "setAuraEnabled", false);
            c.check(c.hx().events(mark, "use_entity").size() == 9 && onFrame(c, mark, ids[HELD]).size() == 1,
                    "with the toggle ON the held frame was not released while crouching: "
                            + c.hx().events(mark, "use_entity").size() + " clicks");
            sneak(c, false);

            // Toggle OFF, crouched: the hold stays, eight.
            set(c, "setLeaveLastArrowDisableWhileCrouching", false);
            ids = grid(c, -1);
            sneak(c, true);
            long mark2 = c.hx().head();
            set(c, "setAuraEnabled", true);
            c.waitUntil("eight clicked while crouched, toggle OFF", mc -> c.hx().events(mark2, "use_entity").size() >= 8, 300);
            c.ctx().waitTicks(40);
            set(c, "setAuraEnabled", false);
            int total = c.hx().events(mark2, "use_entity").size();
            c.check(total == 8 && onFrame(c, mark2, ids[HELD]).isEmpty(), "with the toggle OFF the held frame was clicked "
                    + "while crouching: " + total + " clicks");
        } finally {
            stage(c, "UNKNOWN");
            leave(c);
        }
    }

    // ---- several clicks in one tick -----------------------------------------------------------------------------------

    /**
     * Aura Clicks Per Tick 2 on a frame wanting seven clicks, under the crosshair: the server must see two use_entity in
     * one tick (premise: the option acted) and GrimAC must say nothing (the session's verdict is this case's).
     */
    private static void multiClick(Session c) throws Exception {
        try (HxKit.Settings off = gateOff(c); HxKit.Settings on = arrowSettings(c)) {
            stand(c);
            for (int per : new int[]{2, 4}) {
                int centre = grid(c, 12)[12];
                TriggerTiming.look(c, new Vec3(-1.97, 122.5, 77.5));
                c.ctx().waitTicks(3);
                set(c, "setAuraClicksPerTick", per);
                long mark = c.hx().head();
                set(c, "setAuraEnabled", true);
                c.waitUntil("seven clicks on the centre frame", mc -> onFrame(c, mark, centre).size() >= 7, 200);
                set(c, "setAuraEnabled", false);
                List<JsonObject> mine = onFrame(c, mark, centre);
                java.util.Map<Long, Integer> perTick = new java.util.TreeMap<>();
                for (JsonObject e : mine) {
                    perTick.merge(e.get("tick").getAsLong(), 1, Integer::sum);
                }
                c.note("Clicks Per Tick " + per + ": " + mine.size() + " clicks, per server tick " + perTick);
                c.check(mine.size() == 7, "the frame got " + mine.size() + " clicks, it needed 7");
                c.check(perTick.values().stream().anyMatch(n -> n >= per), "no tick had " + per + " clicks: " + perTick);
                c.check(perTick.values().stream().allMatch(n -> n <= per), "a tick had more than " + per + ": " + perTick);
            }
        } finally {
            set(c, "setAuraClicksPerTick", 1);
            leave(c);
        }
    }
}
