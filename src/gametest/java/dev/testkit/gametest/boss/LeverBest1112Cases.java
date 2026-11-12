package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lever Aura's best spot and the EE3 reach rule (leverbest-1112), on the low S2 lever 27,124,127 as Hypixel has it: a FLOOR
 * lever facing north (its outline x 27.31-27.69, y 124-124.375, z 127.25-127.75). GrimAC 2.3.74's RotationPlace judges a
 * block click by the reported look meeting the lever's FULL cube within 4.5 from the eye (any of its eye heights), so the
 * reach that matters is where the ray enters the cube, not the distance to the outline hit.
 * <ul>
 *   <li>1112-boss-lever-reach-probe (flags expected, a measurement): hand flicks along the best ray at cube entries 4.40 to
 *       4.70, flags counted per distance; then his own spot (22.87, 124.375 on snow, 129.36) with Full Block's cube hit and
 *       a correct look, and last with the look Freeze State left (60.10 / 32.36), which must draw RotationPlace.</li>
 *   <li>1112-boss-lever-best-spot: Lever Aura from where the best ray enters at about 4.47 and the old center aim at over
 *       4.5: it flicks, the flick's use_item_on is on the wire at that entry, GrimAC silent, camera never moved.</li>
 *   <li>1112-boss-lever-his-spot: his spot with Full Block off (best ray 4.57: no flick, no turn, no flag) and on (flick).</li>
 *   <li>1112-boss-lever-max-*: the best-spot flick with his use, hotbar swap, container click, attack and teleport accept in
 *       the same ticks, 12 rounds (the Aura's repeat flicks).</li>
 * </ul>
 */
final class LeverBest1112Cases {

    static final BlockPos LEVER = BossAuraTurnCases.LEVER;
    private static final String LEVER_CONFIG = BossAuraTurnCases.LEVER_CONFIG;
    private static final String SECRETS = "secrets.SecretsConfig";
    private static final String FLOOR_LEVER = "minecraft:lever[face=floor,facing=north,powered=false]";
    /** His hand flick (2026-11-12 13:14:03, 26.3 sim): feet 22.87, 124.41, 129.36; here on 4 snow layers (124.375). */
    private static final double HIS_X = 22.87, HIS_Y = 124.375, HIS_Z = 129.36;

    private LeverBest1112Cases() {
    }

    static void register(Session s) {
        s.testExpectingFlags("1112-boss-lever-reach-probe", LeverBest1112Cases::probe);
        s.test("1112-boss-lever-best-spot", LeverBest1112Cases::bestSpot);
        s.test("1112-boss-lever-his-spot", LeverBest1112Cases::hisSpot);
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.SWAP, SameTick.Act.CLICK, SameTick.Act.ATTACK,
                SameTick.Act.TP}) {
            s.test("1112-boss-lever-max-" + a.name().toLowerCase(Locale.ROOT), c -> maxSameTick(c, a));
        }
    }

    // ------------------------------------------------------------------------------------------------------------ arena

    private static void arena(Session c) {
        c.server().command("forceload add 16 123 30 142");
        c.server().sync(20);
        c.server().command("fill 17 123 123 30 123 132 minecraft:stone");
        c.server().command("fill 17 124 123 30 129 132 minecraft:air");
        c.server().command("setblock 27 124 127 " + FLOOR_LEVER);
        c.ctx().waitTicks(10);
    }

    private static void p3(Session c) {
        c.server().command("kill @e[type=armor_stand,x=27,y=125,z=127,distance=..3]");
        c.server().command("summon armor_stand 27.5 125 127.5 {NoGravity:1b,Invisible:1b,CustomName:\"Not Activated\",CustomNameVisible:1b}");
        c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
        c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
        newRun(c);
        c.waitUntil("the lever on the client", mc -> mc.level.getBlockState(LEVER).getBlock() instanceof LeverBlock, 100);
    }

    /** Goldor's line: P3 again, and the Aura forgets which levers it flicked. */
    private static void newRun(Session c) {
        c.hx().chat(TerminalAuraCases.GOLDOR);
        c.waitUntil("Floor7Tracker phase P3",
                mc -> "P3".equals(String.valueOf(Mod.staticCall(TerminalAuraCases.TRACKER, "getPhase"))), 100);
    }

    private static void cleanup(Session c, List<AutoCloseable> undo) {
        c.onClient(mc -> {
            BossAuraTurnCases.restore(undo);
            if (mc.player != null && dev.testkit.compat.McCompat.screen(mc) != null) dev.testkit.compat.McCompat.setScreen(mc, null);
            return null;
        });
        c.server().command("kill @e[type=armor_stand,x=27,y=125,z=127,distance=..3]");
        c.server().command("kill @e[type=zombie,tag=leverbest]");
        c.server().command("fill 17 123 123 30 129 132 minecraft:air");
        c.server().command("forceload remove 16 123 30 142");
        c.hx().give(4, "minecraft:air");
        c.hx().give(5, "minecraft:air");
        c.hx().sidebarClear();
        c.hx().call("dungeon.tp", "x", -619.5, "y", 151.0, "z", -619.5, "yaw", 0f, "pitch", 0f);
    }

    private static void quiet(Session c, List<AutoCloseable> undo) {
        undo.add(c.onClient(mc -> LightsDeviceCases.raw(LightsDeviceCases.CFG, "AuraEnabled", false)));
        undo.add(c.onClient(mc -> LightsDeviceCases.raw(LightsDeviceCases.CFG, "TriggerEnabled", false)));
        undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Enabled", false)));
        undo.add(c.onClient(mc -> Mod.with(SECRETS, "MasterEnabled", false)));
    }

    private static void fullBlock(Session c, List<AutoCloseable> undo, boolean on) {
        undo.add(c.onClient(mc -> Mod.with(SECRETS, "MasterEnabled", on)));
        undo.add(c.onClient(mc -> Mod.with(SECRETS, "LeversEnabled", on)));
        undo.add(c.onClient(mc -> Mod.with(SECRETS, "DungeonsOnly", false)));
        undo.add(c.onClient(mc -> Mod.with(SECRETS, "BossOnly", false)));
        undo.add(c.onClient(mc -> Mod.with(SECRETS, "LeverShape", Mod.enumValue(SECRETS + "$Shape", "FULL"))));
    }

    private static void auraOn(Session c, List<AutoCloseable> undo) {
        undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLevers", true)));
        undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLeversEarly", true)));
        undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Range", 4.5)));
        undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "CooldownMs", 50)));
        undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Enabled", true)));
    }

    // --------------------------------------------------------------------------------------------------------- geometry

    /** The mod's best outline hit on the lever from {@code eye} (client thread). */
    private static BlockHitResult best(Minecraft mc, Vec3 eye) {
        return (BlockHitResult) Mod.staticCall("util.BlockHits", "best", mc.level, LEVER, eye);
    }

    /** Where a ray from {@code eye} toward {@code at} enters the lever's full cube (+inf: misses it). */
    static double entry(Vec3 eye, Vec3 at) {
        AABB cube = new AABB(LEVER);
        if (cube.contains(eye)) return 0;
        Vec3 dir = at.subtract(eye).normalize();
        return cube.clip(eye, eye.add(dir.scale(64))).map(eye::distanceTo).orElse(Double.POSITIVE_INFINITY);
    }

    /** {entry of the best ray, entry of the ray at the outline's middle (the old aim)} from feet x, y, z standing. */
    private static double[] entries(Minecraft mc, double x, double y, double z) {
        Vec3 eye = new Vec3(x, y + 1.62, z);
        BlockHitResult b = best(mc, eye);
        Vec3 mid = mc.level.getBlockState(LEVER).getShape(mc.level, LEVER).bounds().getCenter()
                .add(LEVER.getX(), LEVER.getY(), LEVER.getZ());
        return new double[]{b == null ? Double.POSITIVE_INFINITY : entry(eye, b.getLocation()), entry(eye, mid)};
    }

    /** The feet x (z 127.5, y 124) whose best ray enters the cube at {@code want}: entries fall as x grows toward the lever. */
    private static double xFor(Session c, double want) {
        return c.onClient(mc -> {
            double lo = 22.0, hi = 23.2;
            for (int i = 0; i < 40; i++) {
                double mid = (lo + hi) / 2;
                if (entries(mc, mid, 124.0, 127.5)[0] > want) lo = mid; else hi = mid;
            }
            return hi;
        });
    }

    private static void standAt(Session c, double x, double y, double z, float yaw, float pitch) {
        c.hx().call("dungeon.tp", "x", x, "y", y, "z", z, "yaw", yaw, "pitch", pitch);
        c.waitUntil("the client at " + x + "," + y + "," + z, mc -> mc.player != null
                && mc.player.position().distanceTo(new Vec3(x, y, z)) < 0.02, 200);
        c.ctx().waitTicks(5);
    }

    /**
     * A hand flick: the look (body and camera) set to yaw/pitch and held 4 ticks so every look GrimAC tries is that one, then
     * use_item_on with {@code hit} (null: the lever outline along that look). Returns {flag lines it drew, 1 if the lever
     * flipped on the server's say-so}.
     */
    private static int[] handFlick(Session c, float yaw, float pitch, BlockHitResult hit) {
        c.server().command("setblock 27 124 127 " + FLOOR_LEVER);
        c.waitUntil("the lever reset", mc -> !mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED), 60);
        c.onClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            return null;
        });
        c.ctx().waitTicks(4);
        int f0 = c.scenario().flags().size();
        String sent = c.onClient(mc -> {
            BlockHitResult h = hit;
            if (h == null) {
                Vec3 eye = mc.player.getEyePosition();
                h = mc.level.getBlockState(LEVER).getShape(mc.level, LEVER)
                        .clip(eye, eye.add(mc.player.getViewVector(1f).scale(6)), LEVER);
            }
            if (h == null) return null;
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, h);
            return h.getLocation().toString();
        });
        c.check(sent != null, "the hand flick's look at " + yaw + "/" + pitch + " does not strike the lever outline");
        c.ctx().waitTicks(8);
        boolean on = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
        return new int[]{c.scenario().flags().size() - f0, on ? 1 : 0};
    }

    private static float[] lookAt(Vec3 eye, Vec3 at) {
        Vec3 d = at.subtract(eye);
        return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)))};
    }

    // ----------------------------------------------------------------------------------------------------------- cases

    /** The measurement behind docs/AP3.md "Lever reach": GrimAC's real limit on a lever flick, by cube entry. */
    static void probe(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            arena(c);
            quiet(c, undo);
            double[] wants = {4.40, 4.45, 4.48, 4.50, 4.52, 4.55, 4.60, 4.70};
            StringBuilder table = new StringBuilder();
            int cleanMax = -1;
            for (int i = 0; i < wants.length; i++) {
                double x = xFor(c, wants[i]);
                standAt(c, x, 124, 127.5, -90f, 0f);
                double[] aim = c.onClient(mc -> {
                    Vec3 eye = mc.player.getEyePosition();
                    BlockHitResult b = best(mc, eye);
                    float[] l = lookAt(eye, b.getLocation());
                    return new double[]{l[0], l[1], entry(eye, b.getLocation()), eye.distanceTo(b.getLocation()),
                            Math.sqrt(new AABB(LEVER).distanceToSqr(eye))};
                });
                int[] r = handFlick(c, (float) aim[0], (float) aim[1], null);
                table.append(String.format(Locale.ROOT, "%n  feet x %.3f: cube entry %.3f (outline hit %.3f, cube nearest %.3f) -> flags %d, lever %s",
                        x, aim[2], aim[3], aim[4], r[0], r[1] == 1 ? "ON" : "off"));
                if (r[0] == 0 && cleanMax == i - 1) cleanMax = i;
            }
            c.note("RotationPlace reach probe (best ray, standing eye 1.62):" + table);
            c.check(cleanMax >= 1, "a flick entering the cube at 4.45 or less flagged: " + table);

            // His spot. Full Block makes the lever's client shape the whole cube, as it was for his flick.
            c.server().command("fill 22 124 129 23 124 129 minecraft:snow[layers=4]");
            c.ctx().waitTicks(5);
            standAt(c, HIS_X, HIS_Y, HIS_Z, 60.10f, 32.36f);
            double[] his = c.onClient(mc -> {
                Vec3 eye = mc.player.getEyePosition();
                double[] e = entries(mc, HIS_X, HIS_Y, HIS_Z);
                return new double[]{e[0], e[1], Math.sqrt(new AABB(LEVER).distanceToSqr(eye)), mc.player.getY()};
            });
            c.note(String.format(Locale.ROOT, "his spot (feet y %.3f): cube nearest %.3f; real outline best ray enters %.3f, middle aim %.3f",
                    his[3], his[2], his[0], his[1]));
            fullBlock(c, undo, true);
            c.ctx().waitTicks(2);
            Object[] cube = c.onClient(mc -> {
                Vec3 eye = mc.player.getEyePosition();
                BlockHitResult b = best(mc, eye);
                return new Object[]{b, lookAt(eye, b.getLocation()), entry(eye, b.getLocation())};
            });
            float[] cl = (float[]) cube[1];
            int[] good = handFlick(c, cl[0], cl[1], null);
            c.note(String.format(Locale.ROOT, "his spot, Full Block cube hit, look at it (%.2f/%.2f, entry %.3f): flags %d, lever %s",
                    cl[0], cl[1], (Double) cube[2], good[0], good[1] == 1 ? "ON" : "off"));
            // His flick as it went out: Freeze State had left the body at 60.10/32.36 and sent the camera's cube hit.
            int[] frozen = handFlick(c, 60.10f, 32.36f, (BlockHitResult) cube[0]);
            List<String> fl = c.scenario().flags();
            c.note("his spot, Freeze State's look 60.10/32.36 with the cube hit: flags " + frozen[0] + ", lever "
                    + (frozen[1] == 1 ? "ON" : "off") + (frozen[0] > 0 ? ", last: " + fl.get(fl.size() - 1) : ""));
            c.check(good[1] == 1, "the cube hit from his spot did not flip the lever");
        } finally {
            cleanup(c, undo);
        }
    }

    /** Lever Aura flicks from where only the best ray reaches (entry about 4.47, the middle aim over 4.5). */
    static void bestSpot(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            arena(c);
            quiet(c, undo);
            p3(c);
            double x = xFor(c, 4.47);
            standAt(c, x, 124, 127.5, 90f, 0f); // facing away: the Aura must turn the body
            double[] e = c.onClient(mc -> entries(mc, mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            c.note(String.format(Locale.ROOT, "feet x %.3f: best ray enters at %.3f, middle aim at %.3f", x, e[0], e[1]));
            c.check(e[1] > 4.5, "the spot is not one only the best ray reaches (middle aim " + e[1] + ")");
            long mark = LogTap.mark();
            WireCapture.describe = LeverBest1112Cases::describe;
            WireCapture.start();
            float[] cam = BossAuraTurnCases.start(c);
            auraOn(c, undo);
            c.waitUntil("the lever flipped on the server's say-so", mc -> {
                BossAuraTurnCases.track(mc, cam);
                return mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED);
            }, 60);
            c.onClient(mc -> {
                Mod.set(LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            BossAuraTurnCases.finish(c, cam, "lever aura best spot");
            WireCapture.stop();
            List<String> wire = WireCapture.snapshot().stream().map(WireCapture.Sent::text).filter(t -> t.startsWith("use_item_on")).toList();
            c.note("wire: " + wire);
            c.check(wire.size() == 1, "want one use_item_on on the wire, got " + wire.size());
            double we = Double.parseDouble(wire.get(0).replaceAll(".*entry=([0-9.]+).*", "$1"));
            c.check(wire.get(0).contains(LEVER.toShortString()) && we > 4.45 && we <= 4.5,
                    "the flick on the wire is not on the lever at the long entry (" + wire.get(0) + ")");
            List<String> log = LogTap.since(mark).stream().filter(l -> l.contains("[LeverAura] flick sent")).toList();
            c.note("log: " + log);
            c.check(log.size() == 1, "want one flick sent line, got " + log.size());
        } finally {
            WireCapture.describe = null;
            WireCapture.stop();
            cleanup(c, undo);
        }
    }

    /** His spot: the real outline is out of GrimAC's reach (no flick, no turn); with Full Block on it flicks. */
    static void hisSpot(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            arena(c);
            c.server().command("fill 22 124 129 23 124 129 minecraft:snow[layers=4]");
            quiet(c, undo);
            p3(c);
            standAt(c, HIS_X, HIS_Y, HIS_Z, 60.10f, 32.36f);
            long mark = LogTap.mark();
            float[] cam = BossAuraTurnCases.start(c);
            auraOn(c, undo);
            float[] body = {cam[0], cam[1], 0f};
            for (int t = 0; t < 30; t++) {
                c.ctx().waitTick();
                c.onClient(mc -> {
                    BossAuraTurnCases.track(mc, cam);
                    body[2] = Math.max(body[2], Math.abs(Mth.wrapDegrees(mc.player.getYRot() - body[0])));
                    return null;
                });
            }
            boolean on = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
            List<String> held = LogTap.since(mark).stream().filter(l -> l.contains("best look enters its block")).toList();
            c.note("Full Block off: lever " + (on ? "ON" : "off") + ", body turned at most " + body[2] + " deg, held: "
                    + (held.isEmpty() ? "none" : held.get(0)));
            c.check(!on, "the lever flipped from his spot with the real outline (GrimAC range is 4.5 to the cube along the ray)");
            c.check(body[2] < 0.01f, "the body was turned for a flick that cannot go");
            c.check(!held.isEmpty(), "no 'best look enters its block' held line: the Aura did not consider the lever");
            fullBlock(c, undo, true);
            c.waitUntil("Full Block: the lever flipped on the server's say-so", mc -> {
                BossAuraTurnCases.track(mc, cam);
                return mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED);
            }, 60);
            c.onClient(mc -> {
                Mod.set(LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            BossAuraTurnCases.finish(c, cam, "lever aura his spot, Full Block");
            c.note("log: " + LogTap.since(mark).stream().filter(l -> l.contains("[LeverAura] flick sent")).toList());
        } finally {
            cleanup(c, undo);
        }
    }

    /** The best-spot flick (entry about 4.47) with his action in the same ticks, 12 rounds. */
    static void maxSameTick(Session c, SameTick.Act act) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            arena(c);
            quiet(c, undo);
            c.hx().give(4, "minecraft:stick");
            c.hx().give(5, "minecraft:stick");
            p3(c);
            double x = xFor(c, 4.47);
            standAt(c, x, 124, 127.5, 90f, 0f);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(4);
                return null;
            });
            if (act == SameTick.Act.ATTACK) {
                // A mob where he looks (west): he hits it while the Aura turns the body to the lever behind him.
                c.server().command("summon zombie " + (x - 2.5) + " 124 127.5 {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"leverbest\"]}");
                c.waitUntil("the zombie under his crosshair", mc -> mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult, 100);
            }
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "CooldownMs", 50)));
            int flags0 = c.scenario().flags().size();
            long mark = LogTap.mark();
            st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.ATTACK ? 2 : 1,
                    p -> p instanceof ServerboundUseItemOnPacket, 4, 5));
            undo.add(c.onClient(mc -> Mod.with(LEVER_CONFIG, "Enabled", true)));
            int flicks = 0;
            for (int r = 0; r < 12; r++) {
                c.server().command("setblock 27 124 127 " + FLOOR_LEVER);
                newRun(c);
                if (act == SameTick.Act.CLICK) {
                    // His clicks in his inventory; the Aura waits while a container screen is open and flicks as it shuts.
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        return null;
                    });
                    c.ctx().waitTicks(3);
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, null);
                        return null;
                    });
                }
                for (int t = 0; t < 12; t++) {
                    if (act == SameTick.Act.TP && t % 2 == 0) {
                        c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                    }
                    c.ctx().waitTick();
                }
                if (c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED))) flicks++;
            }
            List<String> sent = LogTap.since(mark).stream().filter(l -> l.contains("[LeverAura] flick sent")).toList();
            c.note("lever flicked in " + flicks + " of 12 rounds; flick lines " + sent.size()
                    + (sent.isEmpty() ? "" : ", first: " + sent.get(0).replaceAll(".*flick sent: ", "")));
            c.check(flicks >= (act == SameTick.Act.TP ? 2 : 6), "the Lever Aura flicked in only " + flicks + " of 12 rounds");
            c.check(sent.stream().allMatch(l -> {
                double e = Double.parseDouble(l.replaceAll(".*entry=([0-9.]+).*", "$1"));
                return e > 4.45 && e <= 4.5;
            }), "a flick went out at an entry outside 4.45-4.5: " + sent);
            SameTick.judge(c, st, "lever aura at its longest reach, his " + act,
                    act == SameTick.Act.USE ? SameTick.Expect.SHARE
                            : act == SameTick.Act.ATTACK ? SameTick.Expect.APART : SameTick.Expect.ACTED);
            st = null;
            c.check(c.scenario().flags().size() == flags0, "GrimAC flagged with his " + act + " in the flicks' ticks");
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            cleanup(c, undo);
        }
    }

    /** use_item_on on the wire: its block, and where the ray from the eye at that moment enters the cube. */
    private static String describe(Object p) {
        if (!(p instanceof ServerboundUseItemOnPacket)) return p.getClass().getSimpleName();
        BlockHitResult h = null;
        for (java.lang.reflect.Method m : p.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == BlockHitResult.class) {
                try {
                    h = (BlockHitResult) m.invoke(p);
                } catch (ReflectiveOperationException e) {
                    return "use_item_on ?";
                }
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (h == null || mc.player == null) return "use_item_on ?";
        Vec3 eye = mc.player.getEyePosition();
        return String.format(Locale.ROOT, "use_item_on %s at %.3f,%.3f,%.3f entry=%.3f", h.getBlockPos().toShortString(),
                h.getLocation().x, h.getLocation().y, h.getLocation().z, entry(eye, h.getLocation()));
    }
}
