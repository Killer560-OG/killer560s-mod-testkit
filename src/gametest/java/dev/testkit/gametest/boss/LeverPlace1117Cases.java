package dev.testkit.gametest.boss;

import dev.testkit.gametest.LogTap;
import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * leverplace0-1117: the EE3 route's low lever was never flicked. GrimAC 2.3.74 RotationPlace keeps a place buffer that a
 * missed (no-look) lever click sets to 1 and only 11 striking clicks empty, never time; while it is above 0 another
 * missed click is CANCELED. So with keys held (the aura may change only the pitch) a lever click the walk's yaw cannot
 * reach is held, and must go as soon as a look that strikes is available: a pitch-only look through the lever's full cube
 * (what RotationPlace judges, not the small outline), or the full aim on a key-free tick. A flick the server did not take
 * (lever unchanged after the round trip) is sent again. Every case primes the buffer with one missed click first, so the
 * held path is what runs; the only flag allowed is that priming click's own RotationPlace.
 */
final class LeverPlace1117Cases {

    private static final BlockPos LEVER = BossAuraTurnCases.LEVER;
    private static final String FLOOR_LEVER = "minecraft:lever[face=floor,facing=north,powered=false]";

    private LeverPlace1117Cases() {
    }

    static void register(Session s) {
        s.testExpectingFlags("1117-leverplace-pitch", c -> pitchOnly(c, null));
        // No OPEN_CLICK while walking: his own inventory click with W held draws MultiActionsC with the aura off too
        // (Ap3AuraLook1116Cases leaves it out for the same reason); the held case below covers it standing still.
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.ATTACK, SameTick.Act.SWAP, SameTick.Act.TP}) {
            s.testExpectingFlags("1117-leverplace-pitch-" + a.name().toLowerCase(Locale.ROOT), c -> pitchOnly(c, a));
        }
        s.testExpectingFlags("1117-leverplace-held", c -> heldThenStill(c, null));
        s.testExpectingFlags("1117-leverplace-held-open_click", c -> heldThenStill(c, SameTick.Act.OPEN_CLICK));
        s.test("1117-leverplace-retry", LeverPlace1117Cases::retry);
    }

    private static String describe(Object p) {
        if (p instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket m && m.hasRotation()) {
            return String.format(Locale.ROOT, "rot %.3f %.3f", m.getYRot(0f), m.getXRot(0f));
        }
        return LeverBest1112Cases.describe(p);
    }

    private static void keys(Session c, boolean forward, boolean back) {
        c.onClient(mc -> {
            mc.options.keyUp.setDown(forward);
            mc.options.keyDown.setDown(back);
            return null;
        });
    }

    /** One missed block click (looking straight up at the floor under him): GrimAC's place buffer goes to 1, the mod's too. */
    private static void prime(Session c) {
        float pitch = c.onClient(mc -> mc.player.getXRot());
        c.onClient(mc -> {
            mc.player.setXRot(-90f);
            return null;
        });
        c.ctx().waitTicks(3);
        int f0 = c.scenario().flags().size();
        c.onClient(mc -> {
            BlockPos below = mc.player.getOnPos();
            Vec3 hit = new Vec3(mc.player.getX(), below.getY() + 1.0, mc.player.getZ());
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
            return null;
        });
        c.waitUntil("GrimAC's RotationPlace line for the missed priming click", mc -> c.scenario().flags().size() > f0, 200);
        c.onClient(mc -> {
            mc.player.setXRot(pitch);
            return null;
        });
        c.ctx().waitTicks(3);
        List<String> got = c.scenario().flags().subList(f0, c.scenario().flags().size());
        double buf = c.onClient(mc -> ((Number) Mod.staticCall("util.GrimHitBuffer", "placeValue")).doubleValue());
        c.note("primed: " + got + "; mod place buffer " + buf);
        c.check(got.stream().allMatch(l -> l.contains("RotationPlace")), "the priming click drew something other than RotationPlace");
        c.check(buf >= 1.0, "the mod's GrimHitBuffer did not see the missed priming click (" + buf + ")");
    }

    private static void setup(Session c, List<AutoCloseable> undo) {
        LeverBest1112Cases.arena(c);
        LeverBest1112Cases.quiet(c, undo);
        c.hx().give(4, "minecraft:stick");
        c.hx().give(5, "minecraft:stick");
        LeverBest1112Cases.p3(c);
        c.onClient(mc -> {
            mc.player.getInventory().setSelectedSlot(4);
            return null;
        });
    }

    private static long leverUses(List<String> wire) {
        return wire.stream().filter(w -> w.startsWith("use_item_on") && w.contains(LEVER.toShortString())).count();
    }

    /**
     * Walking east (W held, yaw -90, pitch 0) along z 127.9: his level look passes over the floor lever, and its outline
     * (x/z 0.31-0.69 of the block) is off his walk's plane, but its full cube is not. The buffer is primed, so a no-look
     * click would be canceled; the aura must click after a pitch-only packet look through the cube, with no flag and no
     * change to his yaw on any keys tick. With {@code act} his own action lands in the same ticks.
     */
    private static void pitchOnly(Session c, SameTick.Act act) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            setup(c, undo);
            LeverBest1112Cases.standAt(c, 21.3, 124, 127.9, -90f, 0f);
            if (act == SameTick.Act.ATTACK) {
                c.server().command("summon zombie 25.6 124 127.9 {NoAI:1b,Invulnerable:1b,Silent:1b,Tags:[\"leverbest\"]}");
                c.waitUntil("the zombie on the client", mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                        mc.player.getBoundingBox().inflate(6)).size() > 0, 100);
            }
            prime(c);
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "SectionLevers", true)));
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "SectionLeversEarly", true)));
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "Range", 4.5)));
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "CooldownMs", 50)));
            int flags0 = c.scenario().flags().size();
            long mark = LogTap.mark();
            long hx0 = c.hx().head();
            WireCapture.describe = LeverPlace1117Cases::describe;
            WireCapture.start();
            if (act != null) {
                // Every 2nd tick (3rd for an attack), as Ap3AuraLook1116Cases: an act on every tick leaves the aura no tick
                // its pitch-only look may ride (a use carries a look, an attack takes no turn under it).
                st = c.onClient(mc -> SameTick.start(act, act == SameTick.Act.ATTACK ? 3 : 2,
                        p -> p instanceof ServerboundUseItemOnPacket, 4, 5));
            }
            undo.add(c.onClient(mc -> Mod.with(BossAuraTurnCases.LEVER_CONFIG, "Enabled", true)));
            boolean flicked = false;
            float worstYaw = 0f;
            for (int t = 0; t < 22 && !flicked; t++) {
                if (act == SameTick.Act.TP) c.server().command("execute as @a at @s run tp @s ~ ~ ~");
                keys(c, true, false);
                c.ctx().waitTick();
                float y = c.onClient(mc -> mc.player.getYRot());
                worstYaw = Math.max(worstYaw, Math.abs(net.minecraft.util.Mth.wrapDegrees(y + 90f)));
                flicked = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
            }
            keys(c, false, false);
            c.ctx().waitTicks(4);
            flicked = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
            WireCapture.stop();
            c.onClient(mc -> {
                Mod.set(BossAuraTurnCases.LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            List<String> wire = WireCapture.snapshot().stream().map(WireCapture.Sent::text).toList();
            List<String> lines = LogTap.since(mark);
            int pitched = 0;
            for (String w : wire) {
                if (!w.startsWith("rot ")) continue;
                String[] f = w.split(" ");
                if (Math.abs(net.minecraft.util.Mth.wrapDegrees(Float.parseFloat(f[1]) + 90f)) < 0.01f
                        && Math.abs(Float.parseFloat(f[2])) > 1f) {
                    pitched++;
                }
            }
            c.note("flicked " + flicked + ", " + leverUses(wire) + " lever use(s) on the wire, " + pitched
                    + " pitch-only look packet(s), his yaw off -90 by at most " + worstYaw + "; aura lines "
                    + lines.stream().filter(l -> l.contains("[TurnFirst]") || l.contains("[LeverAura]")).limit(6).toList());
            c.ctx().waitTicks(10);
            Ap3AuraLook1116Cases.stepFour(c, flags0, lines, "Lever Aura walking, buffer primed");
            c.check(c.scenario().flags().size() == flags0, "Lever Aura drew a flag with the buffer primed: "
                    + c.scenario().flags().subList(flags0, c.scenario().flags().size()));
            c.check(flicked, "the walk passed the lever and it was never flicked (the held click never went)");
            c.check(leverUses(wire) >= 1, "no lever use_item_on on the wire (the feature did not act)");
            c.check(pitched > 0, "no pitch-only packet look went out: the click did not take the pitch path");
            c.check(lines.stream().noneMatch(l -> l.contains("with no look")), "a no-look lever click went with the buffer at 1");
            c.check(worstYaw < 0.01f, "his yaw moved " + worstYaw + " degrees while W was held");
            Ap3AuraLook1116Cases.noLookTaken(c, hx0, lines, "Lever Aura walking");
            if (act != null) {
                SameTick s2 = st;
                st = null;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
                c.note(s2.report());
                // A use, swap or teleport accept shares a server tick with the flick; an attack holds the actor, so apart.
                String v = s2.verdict("Lever Aura with his " + act);
                c.check(v == null, v);
            }
        } finally {
            keys(c, false, false);
            WireCapture.describe = null;
            WireCapture.stop();
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            LeverBest1112Cases.cleanup(c, undo);
        }
    }

    /**
     * Backing toward the lever (S held, facing west, yaw 90): no pitch along his yaw reaches a lever behind him, and with the
     * buffer primed a no-look click would be canceled, so the click is held (never sent, never dropped) while he walks and
     * goes with a full aim on the first tick he lets go.
     */
    private static void heldThenStill(Session c, SameTick.Act act) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        SameTick st = null;
        try {
            setup(c, undo);
            LeverBest1112Cases.standAt(c, 21.6, 124, 127.5, 90f, 0f);
            prime(c);
            LeverBest1112Cases.auraOn(c, undo);
            c.onClient(mc -> {
                Mod.set(BossAuraTurnCases.LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            int flags0 = c.scenario().flags().size();
            long mark = LogTap.mark();
            WireCapture.describe = LeverPlace1117Cases::describe;
            WireCapture.start();
            c.onClient(mc -> {
                Mod.set(BossAuraTurnCases.LEVER_CONFIG, "setEnabled", true);
                return null;
            });
            int walkTicks = 0;
            boolean inRange = false;
            for (int t = 0; t < 20; t++) {
                keys(c, false, true);
                c.ctx().waitTick();
                walkTicks++;
                inRange |= c.onClient(mc -> mc.player.getEyePosition().distanceTo(new Vec3(27.5, 124.5, 127.5)) < 4.0);
                if (inRange && walkTicks > 12) break;
            }
            long whileWalking = leverUses(WireCapture.snapshot().stream().map(WireCapture.Sent::text).toList());
            boolean poweredWalking = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
            keys(c, false, false);
            if (act != null) {
                // His own action from the tick he lets go, every tick: the held flick goes in one of them.
                st = c.onClient(mc -> SameTick.start(act, 1, p -> p instanceof ServerboundUseItemOnPacket, 4, 5));
            }
            boolean flicked = false;
            int after = 0;
            for (; after < 20 && !flicked; after++) {
                c.ctx().waitTick();
                flicked = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
            }
            WireCapture.stop();
            c.onClient(mc -> {
                Mod.set(BossAuraTurnCases.LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            List<String> wire = WireCapture.snapshot().stream().map(WireCapture.Sent::text).toList();
            List<String> lines = LogTap.since(mark);
            long held = lines.stream().filter(l -> l.contains("held: it would be canceled")).count();
            c.note("walked " + walkTicks + " ticks (in range " + inRange + "), lever uses while walking " + whileWalking
                    + ", held lines " + held + ", flicked " + after + " tick(s) after letting go: " + flicked + ", uses in all "
                    + leverUses(wire));
            c.ctx().waitTicks(10);
            c.check(inRange, "he never came within 4 blocks of the lever (nothing tested)");
            c.check(whileWalking == 0 && !poweredWalking, "a lever click went while he walked with the buffer at 1");
            c.check(held > 0, "the aura never logged the held no-look click (the held path did not run)");
            c.check(flicked, "the held lever click was dropped: no flick within 20 ticks of letting go");
            c.check(c.scenario().flags().size() == flags0, "Lever Aura drew a flag: "
                    + c.scenario().flags().subList(flags0, c.scenario().flags().size()));
            if (act != null) {
                SameTick s2 = st;
                st = null;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
                c.note(s2.report());
                String v = s2.verdict("held lever flick with his " + act, false);
                c.check(v == null, v);
            }
        } finally {
            keys(c, false, false);
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            WireCapture.describe = null;
            WireCapture.stop();
            LeverBest1112Cases.cleanup(c, undo);
        }
    }

    /**
     * The server refuses the first flick (testkit {@code refuseuse}: the use answers FAIL and the lever stays off, as a GrimAC
     * cancel or a lost packet would leave it). The aura must flick again once the round trip shows the lever unchanged.
     */
    private static void retry(Session c) throws Exception {
        List<AutoCloseable> undo = new ArrayList<>();
        try {
            setup(c, undo);
            LeverBest1112Cases.standAt(c, 24.0, 124, 127.5, -90f, 25f);
            c.server().command("testkit refuseuse 27 124 127 1");
            // The console command runs on a later server tick: an aura switched on at once flicked before it (fix50 run).
            for (int t = 0; t < 60 && c.server().since().stream().noneMatch(l -> l.contains("refuseuse 27, 124, 127 1")); t++) {
                c.ctx().waitTick();
            }
            c.ctx().waitTicks(2);
            long mark = LogTap.mark();
            List<String> srv0 = c.server().since();
            int srvFrom = srv0.size();
            WireCapture.describe = LeverPlace1117Cases::describe;
            WireCapture.start();
            LeverBest1112Cases.auraOn(c, undo);
            boolean flicked = false;
            for (int t = 0; t < 60 && !flicked; t++) {
                c.ctx().waitTick();
                flicked = c.onClient(mc -> mc.level.getBlockState(LEVER).getValue(LeverBlock.POWERED));
            }
            c.ctx().waitTicks(3);
            WireCapture.stop();
            c.onClient(mc -> {
                Mod.set(BossAuraTurnCases.LEVER_CONFIG, "setEnabled", false);
                return null;
            });
            List<String> wire = WireCapture.snapshot().stream().map(WireCapture.Sent::text).toList();
            List<String> lines = LogTap.since(mark);
            List<String> srv = c.server().since();
            long refused = srv.subList(Math.min(srvFrom, srv.size()), srv.size()).stream()
                    .filter(l -> l.contains("refused use of 27, 124, 127")).count();
            long retries = lines.stream().filter(l -> l.contains("not taken")).count();
            c.note("refused " + refused + ", retry lines " + retries + ", lever uses " + leverUses(wire) + ", flicked " + flicked
                    + "; " + lines.stream().filter(l -> l.contains("[LeverAura]")).limit(4).toList());
            c.check(refused == 1, "the server did not refuse the first flick (" + refused + "): the case tested nothing");
            c.check(retries >= 1, "the aura never saw the flick was not taken");
            c.check(leverUses(wire) >= 2, "only " + leverUses(wire) + " lever use(s) went out: no retry");
            c.check(flicked, "the refused lever was never flicked again");
        } finally {
            c.server().command("testkit refuseuse 27 124 127 0");
            WireCapture.describe = null;
            WireCapture.stop();
            LeverBest1112Cases.cleanup(c, undo);
        }
    }
}
