package dev.testkit.gametest.hx;

import com.google.gson.JsonObject;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static dev.testkit.gametest.hx.CarnivalSuite.GX;
import static dev.testkit.gametest.hx.CarnivalSuite.GZ;
import static dev.testkit.gametest.hx.CarnivalSuite.PX;
import static dev.testkit.gametest.hx.CarnivalSuite.PZ;
import static dev.testkit.gametest.hx.CarnivalSuite.Y;

/**
 * Carnival cases added for the 26.3 port (carnival-1110), in {@code 3780-carnival-session}:
 * <ul>
 *   <li>3789 Fruit Digging Triggerbot (cheat): no dig with the shovel in the wrong mode or the crosshair on another tile;
 *       one dig on the solver's tile once both are right.</li>
 *   <li>3790 Prevent Misclicks / Prevent Wrong Shovel (legit): his click on another tile, or with the wrong shovel, never
 *       reaches the server; sneaking lets it through; the right shovel on the right tile digs.</li>
 *   <li>3791 Auto Zombie Shootout, Aim Mode No Rotate (cheat): the body turns and shoots while the screen holds still.</li>
 *   <li>3792-* SAME-TICK GRIM CASES: his use, dig, hotbar swap and teleport accept put into the ticks where the Fruit
 *       Digging Triggerbot, Auto Fruit Digging + Auto Shovel Swap and Auto Zombie Shootout act; the wire must show both,
 *       and GrimAC (default and experimental, by checkout) judges the rest. A container click cannot meet them: every
 *       Carnival auto is idle while a screen is open. An attack cannot either: they act only with a block or no entity
 *       under the crosshair (the Shootout's dart is a use).</li>
 * </ul>
 */
final class Carnival1110Cases {

    private Carnival1110Cases() {
    }

    static void register(Session s) {
        s.test("3789-carnival-dig-triggerbot", Carnival1110Cases::digTriggerbot);
        s.test("3790-carnival-click-guards", Carnival1110Cases::clickGuards);
        s.test("3791-carnival-auto-shootout-norotate", Carnival1110Cases::noRotate);
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE, SameTick.Act.DIG, SameTick.Act.SWAP, SameTick.Act.TP}) {
            String n = a.name().toLowerCase(Locale.ROOT);
            s.test("3792-carnival-same-trigger-" + n, c -> same(c, "trigger", a));
            if (a != SameTick.Act.DIG) {
                s.test("3792-carnival-same-autodig-" + n, c -> same(c, "autodig", a));
                s.test("3792-carnival-same-shootout-" + n, c -> same(c, "shootout", a));
            }
        }
    }

    // ---- shared ---------------------------------------------------------------------------------------------------

    /** Slots 0/1/2: golden (Treasure), iron (Mines), diamond (Anchor) Carnival Shovels. */
    private static void shovels(Session c) {
        c.hx().give(0, "minecraft:golden_shovel[custom_name='Carnival Shovel']");
        c.hx().give(1, "minecraft:iron_shovel[custom_name='Carnival Shovel']");
        c.hx().give(2, "minecraft:diamond_shovel[custom_name='Carnival Shovel']");
    }

    private static int slotFor(String mode) {
        return "Mines".equals(mode) ? 1 : "Anchor".equals(mode) ? 2 : 0;
    }

    /** {label, shovel mode} of the solver's pick, or null. */
    private static String[] pick(Session c) {
        return c.onClient(mc -> {
            Object rec = Mod.field(Mod.ROOT + "carnival.FruitDigging", "recommendation");
            return rec == null ? null : new String[]{String.valueOf(Mod.call(rec, "label")), (String) Mod.call(rec, "shovel")};
        });
    }

    private static int[] tile(String label) {
        return new int[]{GX + (label.charAt(1) - '1'), GZ + (label.charAt(0) - 'A')};
    }

    /** A diggable tile next to {@code label} (not it). */
    private static String other(String label) {
        char r = label.charAt(0);
        char col = label.charAt(1);
        return "" + (r == 'D' ? 'C' : 'D') + col;
    }

    /** Stands at the grid's center and looks at {@code label}'s top face, at its point nearest to him. */
    private static void aimAt(Session c, String label, boolean sneaking) {
        int[] t = tile(label);
        double x = Math.max(t[0] + 0.2, Math.min(t[0] + 0.8, PX));
        double z = Math.max(t[1] + 0.2, Math.min(t[1] + 0.8, PZ));
        double ey = Y + 1 + (sneaking ? 1.27 : 1.62);
        double dx = x - PX;
        double dy = (Y + 1) - ey;
        double dz = z - PZ;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        CarnivalSuite.stand(c, PX, PZ, yaw, Math.min(89.9f, pitch));
        c.waitUntil("the crosshair on " + label, mc -> mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b
                && b.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && b.getBlockPos().getX() == t[0] && b.getBlockPos().getZ() == t[1] && b.getBlockPos().getY() == Y, 40);
    }

    private static List<JsonObject> digsAt(Session c, long since, String label) {
        int[] t = tile(label);
        return c.hx().events(since, "attack_block").stream().filter(e -> {
            int[] p = CarnivalSuite.pos(e);
            return p[0] == t[0] && p[1] == Y && p[2] == t[1];
        }).toList();
    }

    private static void click(Session c) {
        c.onClient(mc -> {
            net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getDefaultKey());
            return null;
        });
        c.ctx().waitTicks(8);
    }

    private static int refusals(Session c) {
        return c.onClient(mc -> (Integer) Mod.staticCall("carnival.FruitDigging", "refusals"));
    }

    private static void setFruit(dev.testkit.gametest.hx.HxKit.Settings s, String setter, boolean v, boolean restore) {
        s.custom(() -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), setter, v),
                () -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), setter, restore));
    }

    private static void finish(Session c) {
        c.onClient(mc -> {
            mc.options.keyShift.setDown(false);
            mc.options.keyUse.setDown(false);
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        CarnivalSuite.cleanup(c);
    }

    // ---- 3789 -----------------------------------------------------------------------------------------------------

    private static void digTriggerbot(Session c) {
        boolean cheat = Mod.isCheat();
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.AutoFruitConfig", "trigger");
            shovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            c.ctx().waitTicks(10);
            String[] p = pick(c);
            c.check(p != null, "no pick to aim at");
            int right = slotFor(p[1]);
            // 1) the crosshair on the pick, the shovel in another mode.
            CarnivalSuite.holdSlot(c, (right + 1) % 3);
            aimAt(c, p[0], false);
            long t0 = c.hx().head();
            c.ctx().waitTicks(30);
            int wrongShovel = c.hx().events(t0, "attack_block").size();
            // 2) the right shovel, the crosshair on another tile.
            CarnivalSuite.holdSlot(c, right);
            String o = other(p[0]);
            aimAt(c, o, false);
            long t1 = c.hx().head();
            c.ctx().waitTicks(30);
            int wrongTile = c.hx().events(t1, "attack_block").size();
            // 3) both right.
            String[] p2 = pick(c);
            c.check(p2 != null && p2[0].equals(p[0]), "the pick moved while nothing was dug (" + p[0] + " -> "
                    + (p2 == null ? null : p2[0]) + ")");
            aimAt(c, p[0], false);
            long t2 = c.hx().head();
            c.ctx().waitTicks(30);
            List<JsonObject> on = digsAt(c, t2, p[0]);
            int all = c.hx().events(t2, "attack_block").size();
            Object taps = cheat ? c.onClient(mc -> Mod.staticCall("carnival.FruitDiggingTriggerbot", "taps")) : 0;
            c.note("pick " + p[0] + " (" + p[1] + "); digs: wrong shovel " + wrongShovel + ", on " + o + " " + wrongTile
                    + ", on the pick " + on.size() + " of " + all + "; taps " + taps);
            c.check(wrongShovel == 0, "it dug with the shovel in the wrong mode (" + wrongShovel + ")");
            c.check(wrongTile == 0, "it dug a tile that is not the pick (" + wrongTile + ")");
            if (cheat) {
                c.check(!on.isEmpty() && on.size() == all, "want digs on the pick only, got " + on.size() + " of " + all);
                c.check(((Integer) taps) >= 1, "the triggerbot counted no tap");
            } else {
                c.check(all == 0, "a legit jar dug " + all + " time(s)");
            }
        } finally {
            finish(c);
        }
    }

    // ---- 3790 -----------------------------------------------------------------------------------------------------

    private static void clickGuards(Session c) {
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
            setFruit(s, "setPreventMisclicks", true, false);
            setFruit(s, "setSneakAllows", true, true);
            shovels(c);
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startFruit(c);
            c.ctx().waitTicks(10);
            String[] p = pick(c);
            c.check(p != null, "no pick");
            int right = slotFor(p[1]);
            CarnivalSuite.holdSlot(c, right);
            String o = other(p[0]);
            // Prevent Misclicks: his click on another tile is refused.
            aimAt(c, o, false);
            int r0 = refusals(c);
            long t0 = c.hx().head();
            click(c);
            int misclick = digsAt(c, t0, o).size();
            int r1 = refusals(c);
            // Sneaking lets it through.
            c.onClient(mc -> {
                mc.options.keyShift.setDown(true);
                return null;
            });
            c.ctx().waitTicks(5);
            aimAt(c, o, true);
            long t1 = c.hx().head();
            click(c);
            int sneaked = digsAt(c, t1, o).size();
            c.onClient(mc -> {
                mc.options.keyShift.setDown(false);
                return null;
            });
            c.ctx().waitTicks(5);
            // Prevent Wrong Shovel: the pick with the shovel in another mode is refused, the right one digs.
            c.onClient(mc -> Mod.call(Mod.staticCall("carnival.FruitDiggingConfig", "getInstance"), "setPreventMisclicks", false));
            setFruit(s, "setPreventWrongShovel", true, false);
            String[] p2 = pick(c);
            CarnivalSuite.holdSlot(c, (slotFor(p2[1]) + 1) % 3);
            aimAt(c, p2[0], false);
            int r2 = refusals(c);
            long t2 = c.hx().head();
            click(c);
            int wrongShovel = digsAt(c, t2, p2[0]).size();
            int r3 = refusals(c);
            CarnivalSuite.holdSlot(c, slotFor(p2[1]));
            long t3 = c.hx().head();
            click(c);
            int good = digsAt(c, t3, p2[0]).size();
            c.note("pick " + p[0] + "/" + p[1] + ", then " + p2[0] + "/" + p2[1] + "; misclick on " + o + " sent " + misclick
                    + " (refusals +" + (r1 - r0) + "), sneaking sent " + sneaked + "; wrong shovel sent " + wrongShovel
                    + " (refusals +" + (r3 - r2) + "), right shovel sent " + good);
            c.check(misclick == 0 && r1 > r0, "Prevent Misclicks let a click on " + o + " through (or never refused it)");
            c.check(sneaked >= 1, "Sneak To Dig Anyway did not let the click through");
            c.check(wrongShovel == 0 && r3 > r2, "Prevent Wrong Shovel let the wrong mode dig (or never refused it)");
            c.check(good >= 1, "the right shovel on the pick did not dig");
        } finally {
            finish(c);
        }
    }

    // ---- 3791 -----------------------------------------------------------------------------------------------------

    private static void noRotate(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto Zombie Shootout is cheat-only (3786 checks nothing turns)");
            return;
        }
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            CarnivalSuite.on(s, "carnival.ZombieShootoutConfig", "getInstance");
            CarnivalSuite.on(s, "carnival.ShootoutShotConfig", "auto");
            s.custom(() -> Mod.call(Mod.staticCall("carnival.ShootoutShotConfig", "auto"), "setFreeCamera", true),
                    () -> Mod.call(Mod.staticCall("carnival.ShootoutShotConfig", "auto"), "setFreeCamera", false));
            c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
            CarnivalSuite.holdSlot(c, 0);
            CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
            CarnivalSuite.startShootout(c, false);
            double zx = PX + 9;
            double zz = PZ + 11;
            c.server().command(CarnivalSuite.zombie(zx, Y + 1, zz, "iron_helmet", "zi"));
            float[] want = CarnivalSuite.look(zx, Y + 2.0, zz);
            c.waitUntil("the zombie is a target", mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 1,
                    100);
            long t0 = c.hx().head();
            float maxView = 0f;
            boolean held = false;
            for (int i = 0; i < 12; i++) {
                c.ctx().waitTicks(5);
                Float v = c.onClient(mc -> (Float) Mod.staticCall("util.ViewFreeze", "viewYaw"));
                if (v != null && !v.isNaN()) {
                    held = true;
                    maxView = Math.max(maxView, Math.abs(v));
                }
            }
            long shots = CarnivalSuite.uses(c, t0, "carrot_on_a_stick");
            float[] got = c.onClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
            double yawErr = Math.abs(((got[0] - want[0]) % 360 + 540) % 360 - 180);
            c.note(String.format(Locale.ROOT, "shots %d; body yaw %.1f pitch %.1f (zombie %.1f %.1f); view held %s, max view yaw %.1f",
                    shots, got[0], got[1], want[0], want[1], held, maxView));
            c.check(yawErr < 6 && Math.abs(got[1] - want[1]) < 6, "the body did not turn to the zombie");
            c.check(shots >= 1, "No Rotate turned but never shot");
            c.check(held && maxView < 1f, "the screen did not hold still (view held " + held + ", max view yaw " + maxView + ")");
        } finally {
            finish(c);
        }
    }

    // ---- 3792 -----------------------------------------------------------------------------------------------------

    private static void same(Session c, String feat, SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: the Carnival autos are cheat-only");
            return;
        }
        SameTick st = null;
        try (HxKit.Settings s = CarnivalSuite.base(c)) {
            int slotA;
            int slotB;
            Predicate<Packet<?>> mine;
            if (feat.equals("shootout")) {
                CarnivalSuite.on(s, "carnival.ZombieShootoutConfig", "getInstance");
                CarnivalSuite.on(s, "carnival.ShootoutShotConfig", "auto");
                c.hx().give(0, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
                c.hx().give(1, "minecraft:carrot_on_a_stick[custom_name='Carnival Dart Tube']");
                CarnivalSuite.holdSlot(c, 0);
                CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
                CarnivalSuite.startShootout(c, false);
                c.server().command(CarnivalSuite.zombie(PX + 9, Y + 1, PZ + 11, "iron_helmet", "zi"));
                c.waitUntil("the zombie is a target",
                        mc -> (Integer) Mod.staticCall("carnival.ZombieShootout", "targetCount") == 1, 100);
                slotA = 0;
                slotB = 1;
                mine = p -> p instanceof ServerboundUseItemPacket || p instanceof ServerboundSetCarriedItemPacket;
            } else {
                CarnivalSuite.on(s, "carnival.FruitDiggingConfig", "getInstance");
                if (feat.equals("trigger")) {
                    CarnivalSuite.on(s, "carnival.AutoFruitConfig", "trigger");
                } else {
                    CarnivalSuite.on(s, "carnival.AutoFruitConfig", "swap");
                    CarnivalSuite.on(s, "carnival.AutoFruitConfig", "dig");
                }
                shovels(c);
                CarnivalSuite.holdSlot(c, 0);
                CarnivalSuite.stand(c, PX, PZ, 0f, 0f);
                CarnivalSuite.startFruit(c);
                c.ctx().waitTicks(10);
                String[] p = pick(c);
                c.check(p != null, "no pick");
                slotA = slotFor(p[1]);
                slotB = (slotA + 1) % 3;
                if (feat.equals("trigger")) {
                    CarnivalSuite.holdSlot(c, slotA);
                    aimAt(c, p[0], false);
                }
                mine = q -> q instanceof ServerboundPlayerActionPacket || q instanceof ServerboundUseItemPacket
                        || q instanceof ServerboundSetCarriedItemPacket;
            }
            int a = slotA;
            int b = slotB;
            Predicate<Packet<?>> m = mine;
            st = c.onClient(mc -> SameTick.start(act, 1, m, a, b));
            String tp = String.format(Locale.ROOT, "tp @p %.3f %d %.3f", PX, Y + 1, PZ);
            for (int i = 0; i < 12; i++) {
                if (act == SameTick.Act.TP) {
                    c.server().command(tp);
                }
                c.ctx().waitTicks(10);
            }
            SameTick cur = st;
            st = null;
            SameTick.judge(c, cur, "carnival " + feat, SameTick.Expect.ACTED);
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            finish(c);
        }
    }
}
