package dev.testkit.gametest.hx;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.PacketTrace;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.function.Predicate;

import static dev.testkit.gametest.hx.HxKit.*;

/**
 * Storm Bow and Auto M5 (mod stormm5-1111), cheat jar only. Every case proves on the wire that the feature acted, and
 * GrimAC (default and experimental checkouts) judges the session.
 * <ul>
 *   <li>1111-stormbow-release: holding right click with a Last Breath releases it at charge tick 35 (setting 34.25),
 *       then swaps to the Terminator (a later server tick than the release) and walks forward for Walk Ticks.</li>
 *   <li>1111-same-stormbow-dig / -swap / -usekey / -tp: his left click, hotbar scroll (between two Last Breaths), use
 *       click or teleport accept in the release ticks; the release still goes out and so does his action.</li>
 *   <li>1111-autom5-fight: Livid's welcome line, then the walk to mid by keys, the Ragnarock cast (swap, use, swap back
 *       after invulnerability) and hits on the real Livid with the aim on its head.</li>
 *   <li>1111-same-autom5-use / -swap / -tp / -attack: the same fight with his use, scroll, teleport accept or attack in
 *       the feature's ticks.</li>
 * </ul>
 */
final class HxStormM51111Cases {

    private static final String F7 = "f7spots.F7SpotsConfig";
    private static final String STORM = "f7spots.StormBowFeature";
    private static final String M5 = "boss.AutoM5Feature";
    private static final String LB = "minecraft:bow[custom_data={id:\"LAST_BREATH\"},item_name=\"Last Breath\"]";
    private static final String TERM = "minecraft:bow[custom_data={id:\"TERMINATOR\"},item_name=\"Terminator\"]";
    private static final String RAG = "minecraft:iron_axe[custom_data={id:\"RAGNAROCK_AXE\"},item_name=\"Ragnarock Axe\"]";
    private static final String SWORD = "minecraft:diamond_sword";

    private HxStormM51111Cases() {
    }

    static void register(Session s) {
        s.test("1111-stormbow-release", HxStormM51111Cases::stormRelease);
        s.test("1111-same-stormbow-dig", c -> stormSame(c, SameTick.Act.DIG));
        s.test("1111-same-stormbow-swap", c -> stormSame(c, SameTick.Act.SWAP));
        s.test("1111-same-stormbow-usekey", c -> stormSame(c, SameTick.Act.USE_KEY));
        s.test("1111-same-stormbow-tp", c -> stormSame(c, SameTick.Act.TP));
        // Flags are judged in m5: only experimental Grim's InvalidInteractTarget on the client-only Livids is allowed.
        s.testExpectingFlags("1111-autom5-fight", c -> m5(c, null));
        s.testExpectingFlags("1111-same-autom5-use", c -> m5(c, SameTick.Act.USE));
        s.testExpectingFlags("1111-same-autom5-swap", c -> m5(c, SameTick.Act.SWAP));
        s.testExpectingFlags("1111-same-autom5-tp", c -> m5(c, SameTick.Act.TP));
        s.testExpectingFlags("1111-same-autom5-attack", c -> m5(c, SameTick.Act.ATTACK));
    }

    /** An entity attack on the wire: ServerboundAttackPacket on 26.3, an interact packet on 26.2. */
    private static boolean attack(Packet<?> p) {
        String n = p.getClass().getSimpleName();
        return n.equals("ServerboundAttackPacket") || p instanceof ServerboundInteractPacket;
    }

    private static boolean release(Packet<?> p) {
        return p instanceof ServerboundPlayerActionPacket a
                && a.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM;
    }

    private static int intOf(String cls, String method) {
        return ((Number) Mod.staticCall(cls, method)).intValue();
    }

    // ---- Storm Bow ------------------------------------------------------------------------------------------------

    private static void stormRelease(Session c) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Storm Bow is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        java.util.function.Consumer<Packet<?>> prev = PacketTrace.tap;
        int[] wire = new int[2]; // [releases, carried=2 after a release]
        try (Settings set = new Settings(c).with(F7, "StormBow", true).with(F7, "StormBowTerminator", true)
                .with(F7, "StormBowWalk", true).with(F7, "StormBowWalkTicks", 6)
                .with(F7, "StormBowReleaseTicks", 34.25f)) {
            run(c, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
            c.hx().give(1, LB);
            c.hx().give(2, TERM);
            c.hx().give(7, "minecraft:arrow 64");
            c.server().command("fill ~-3 ~-1 ~-3 ~3 ~-1 ~12 minecraft:stone");
            c.server().command("fill ~-3 ~ ~-3 ~3 ~3 ~12 minecraft:air");
            int r0 = c.onClient(mc -> intOf(STORM, "releases"));
            int s0 = c.onClient(mc -> intOf(STORM, "swaps"));
            double[] start = c.onClient(mc -> {
                mc.player.setYRot(0f);
                mc.player.setXRot(-10f);
                mc.player.getInventory().setSelectedSlot(1);
                return new double[]{mc.player.getX(), mc.player.getZ()};
            });
            c.ctx().waitTicks(3);
            c.onClient(mc -> {
                PacketTrace.tap = p -> {
                    if (prev != null) prev.accept(p);
                    if (release(p)) wire[0]++;
                    if (p instanceof ServerboundSetCarriedItemPacket h && h.getSlot() == 2 && wire[0] > 0) wire[1]++;
                };
                mc.options.keyUse.setDown(true);
                return null;
            });
            c.waitUntil("Storm Bow released the Last Breath", mc -> intOf(STORM, "releases") > r0, 120);
            int charge = c.onClient(mc -> intOf(STORM, "lastReleaseCharge"));
            c.waitUntil("the Terminator swap", mc -> intOf(STORM, "swaps") > s0, 40);
            c.ctx().waitTicks(12);
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            double[] end = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            boolean fwd = c.onClient(mc -> mc.options.keyUp.isDown());
            int held = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            double moved = Math.hypot(end[0] - start[0], end[1] - start[1]);
            c.note("released at charge " + charge + "; wire releases " + wire[0] + ", set_carried 2 after it " + wire[1]
                    + "; walked " + String.format(java.util.Locale.ROOT, "%.2f", moved) + " blocks; held slot " + held);
            c.check(charge == 35, "released at charge tick " + charge + ", want 35 (34.25 rounds up)");
            c.check(wire[0] >= 1, "no RELEASE_USE_ITEM reached the wire (the feature did not act)");
            c.check(wire[1] >= 1, "the Terminator swap never reached the wire");
            c.check(held == 2, "held slot is " + held + ", want the Terminator in 2");
            c.check(moved > 0.5, "Walk After Release did not move him (" + moved + " blocks)");
            c.check(!fwd, "the forward key is still held after the walk");
        } finally {
            c.onClient(mc -> {
                PacketTrace.tap = prev;
                mc.options.keyUse.setDown(false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                return null;
            });
            for (int s : new int[]{1, 2, 7}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }

    private static void stormSame(Session c, SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Storm Bow is cheat-only");
            return;
        }
        boolean gateWas = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
        SameTick st = null;
        try (Settings set = new Settings(c).with(F7, "StormBow", true).with(F7, "StormBowTerminator", false)
                .with(F7, "StormBowWalk", false).with(F7, "StormBowReleaseTicks", 20f)) {
            run(c, () -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
            c.hx().give(1, LB);
            c.hx().give(3, LB);
            c.hx().give(7, "minecraft:arrow 64");
            double[] at = c.onClient(mc -> {
                mc.player.setYRot(0f);
                mc.player.setXRot(0f);
                mc.player.getInventory().setSelectedSlot(1);
                return new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
            });
            // A block under the crosshair for his left click (a dig while not drawing).
            c.server().command(String.format(java.util.Locale.ROOT, "setblock %d %d %d minecraft:stone",
                    (int) Math.floor(at[0]), (int) Math.floor(at[1]) + 1, (int) Math.floor(at[2]) + 2));
            String tp = String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f 0 0", at[0], at[1], at[2]);
            int r0 = c.onClient(mc -> intOf(STORM, "releases"));
            c.ctx().waitTicks(3);
            st = c.onClient(mc -> {
                mc.options.keyUse.setDown(true);
                // A slot change ends a draw (the server stops the use and tells the client), so his swaps are
                // spaced past one 20-tick draw; every 3 ticks no draw would ever reach its release.
                return SameTick.start(act, act == SameTick.Act.SWAP ? 30 : 3, HxStormM51111Cases::release, 1, 3);
            });
            int rounds = act == SameTick.Act.SWAP ? 18 : 12;
            for (int i = 0; i < rounds; i++) {
                if (act == SameTick.Act.TP) {
                    c.server().command(tp);
                }
                c.ctx().waitTicks(10);
            }
            SameTick cur = st;
            st = null;
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                return null;
            });
            int releases = c.onClient(mc -> intOf(STORM, "releases")) - r0;
            int deferred = c.onClient(mc -> intOf(STORM, "deferred"));
            c.note("Storm Bow releases " + releases + ", deferred (total) " + deferred);
            c.check(releases >= 2, "Storm Bow released " + releases + " times in " + rounds * 10 + " ticks (the feature barely acted)");
            SameTick.judge(c, cur, "storm bow", act == SameTick.Act.TP ? SameTick.Expect.ACTED : SameTick.Expect.ACTED);
            c.check(cur.playerAfterFeature() == 0, "his packet went after the release in one server tick ("
                    + cur.report() + ")");
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                mc.options.keyUse.setDown(false);
                Mod.staticCall("util.SkyblockGate", "setEnabled", gateWas);
                return null;
            });
            for (int s : new int[]{1, 3, 7}) {
                c.hx().give(s, "minecraft:air");
            }
        }
    }

    // ---- Auto M5 ----------------------------------------------------------------------------------------------------

    private static void m5(Session c, SameTick.Act act) {
        if (!Mod.isCheat()) {
            c.note("legit jar: Auto M5 is cheat-only");
            return;
        }
        String lividCls = HxAlerts1025Cases.LIVID;
        String cfg = HxAlerts1025Cases.LIVID_CFG;
        int base = HxAlerts1025Cases.LIVID_ID_BASE;
        String[] names = HxAlerts1025Cases.LIVIDS;
        double[][] spots = HxAlerts1025Cases.LIVID_AT;
        c.server().command("forceload add 0 40 11 56");
        c.ctx().waitTicks(10);
        c.server().command("fill 0 105 40 11 105 56 minecraft:stone");
        c.server().command("fill 0 106 40 11 112 56 minecraft:air");
        c.server().command("setblock 5 108 43 minecraft:red_wool");
        c.server().command("tp @a 1.5 106 45.5 0 0");
        SameTick st = null;
        java.util.function.Consumer<Packet<?>> prev = PacketTrace.tap;
        int[] wire = new int[3]; // [attacks, rag uses (held slot 3), set_carried 3]
        try (Settings set = new Settings(c).with(cfg, "Enabled", true).with(cfg, "AutoM5", true)
                .with(cfg, "M5Mid", true).with(cfg, "M5ArmorSwap", false).with(cfg, "M5Buff", true)
                .with(cfg, "M5Aim", true).with(cfg, "M5AimHead", true).with(cfg, "M5Kill", true)
                .with(cfg, "M5Smoothing", 1)
                .custom(() -> Mod.call(Mod.cfg(cfg), "setM5MidSpot", new BlockPos(4, 106, 50)),
                        () -> Mod.call(Mod.cfg(cfg), "setM5MidSpot", (Object) null))) {
            c.hx().give(0, SWORD);
            c.hx().give(1, SWORD);
            c.hx().give(3, RAG);
            sidebar(c, "template", "dungeon", "floor", "F5");
            c.waitUntil("in F5", mc -> "F5".equals(Mod.staticCall("secrets.DungeonState", "getFloor")), 100);
            c.waitUntil("the client sees the red wool", mc -> mc.level != null
                    && mc.level.getBlockState(new BlockPos(5, 108, 43)).getBlock().getDescriptionId().endsWith("red_wool"), 200);
            c.onClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                for (int i = 0; i < names.length; i++) {
                    net.minecraft.client.player.RemotePlayer p = new net.minecraft.client.player.RemotePlayer(mc.level,
                            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), names[i]));
                    p.setId(base + i);
                    p.setPos(spots[i][0], 106, spots[i][1]);
                    mc.level.addEntity(p);
                }
                return null;
            });
            c.waitUntil("the solver names Hockey Livid", mc -> "Hockey Livid".equals(Mod.staticCall(lividCls, "currentLividName")), 100);
            // The previous case ends with a far teleport out of the arena; let him land and stand before the run
            // starts, as he would on entering the boss room (a walk begun in the tick after a teleport read as a
            // setup flag, not the feature).
            c.waitUntil("standing in the arena", mc -> mc.player.onGround() && mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6, 100);
            c.ctx().waitTicks(10);
            int runs0 = c.onClient(mc -> intOf(M5, "runs"));
            int hits0 = c.onClient(mc -> intOf(M5, "hits"));
            int casts0 = c.onClient(mc -> intOf(M5, "casts"));
            // With his own attacks in the case, an attack packet cannot be told apart by type, so the feature's
            // marker there is the Ragnarock swap and cast; the hits still count on the wire.
            Predicate<Packet<?>> feature = act == SameTick.Act.ATTACK
                    ? p -> p instanceof ServerboundUseItemPacket || (p instanceof ServerboundSetCarriedItemPacket h && h.getSlot() == 3)
                    : p -> attack(p) || (p instanceof ServerboundSetCarriedItemPacket h && h.getSlot() == 3);
            c.onClient(mc -> {
                PacketTrace.tap = p -> {
                    if (prev != null) prev.accept(p);
                    if (attack(p)) wire[0]++;
                    if (p instanceof ServerboundUseItemPacket && mc.player.getInventory().getSelectedSlot() == 3) wire[1]++;
                    if (p instanceof ServerboundSetCarriedItemPacket h && h.getSlot() == 3) wire[2]++;
                };
                return null;
            });
            c.hx().chat("[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.");
            c.waitUntil("Auto M5 started", mc -> intOf(M5, "runs") > runs0, 40);
            run(c, () -> Mod.setField(lividCls, "invulnTicks", 90));
            if (act != null) {
                st = c.onClient(mc -> SameTick.start(act, 3, feature, 0, 1));
            }
            for (int i = 0; i < 30 && c.onClient(mc -> intOf(M5, "hits")) < hits0 + 3; i++) {
                if (act == SameTick.Act.TP && "FIGHT".equals(c.onClient(mc -> Mod.staticCall(M5, "phaseName")))) {
                    double[] at = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
                    c.server().command(String.format(java.util.Locale.ROOT, "tp @p %.3f %.3f %.3f ~ ~", at[0], at[1], at[2]));
                }
                c.ctx().waitTicks(10);
            }
            String phase = c.onClient(mc -> (String) Mod.staticCall(M5, "phaseName"));
            int hits = c.onClient(mc -> intOf(M5, "hits")) - hits0;
            int casts = c.onClient(mc -> intOf(M5, "casts")) - casts0;
            double[] pos = c.onClient(mc -> new double[]{mc.player.getX(), mc.player.getZ(), mc.player.getXRot()});
            int held = c.onClient(mc -> mc.player.getInventory().getSelectedSlot());
            double fromMid = Math.hypot(pos[0] - 4.5, pos[1] - 50.5);
            c.note("phase " + phase + "; hits " + hits + ", casts " + casts + "; wire attacks " + wire[0] + ", rag uses "
                    + wire[1] + ", swaps to rag " + wire[2] + "; " + String.format(java.util.Locale.ROOT,
                    "%.2f from mid, pitch %.1f", fromMid, pos[2]) + "; held " + held);
            c.check(casts == 1 && wire[1] >= 1 && wire[2] >= 1, "the Ragnarock cast did not reach the wire (casts "
                    + casts + ", uses " + wire[1] + ", swaps " + wire[2] + ")");
            c.check(hits >= 1 && wire[0] >= hits, "no hit on the real Livid reached the wire (hits " + hits + ", wire " + wire[0] + ")");
            c.check(pos[2] >= -90 && pos[2] <= 90, "pitch out of range: " + pos[2]);
            if (act != SameTick.Act.SWAP) {
                c.check(held == 0, "held slot is " + held + ", want the sword back in 0");
                c.check(fromMid < 2.5, "he did not walk to mid (" + fromMid + " blocks off)");
            }
            // The Livids are client-side players the server never sent, so experimental Grim flags each hit on one as
            // InvalidInteractTarget; any other flag fails the case.
            c.ctx().waitTicks(10);
            for (String f : c.scenario().flags()) {
                c.check(f.contains("InvalidInteractTarget") && f.contains("entityId=7725"), "anticheat flagged: " + f);
            }
            if (st != null) {
                SameTick cur = st;
                st = null;
                SameTick.judge(c, cur, "auto m5", SameTick.Expect.ACTED);
            }
        } finally {
            if (st != null) {
                SameTick s2 = st;
                c.onClient(mc -> {
                    s2.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                PacketTrace.tap = prev;
                for (int i = 0; i < names.length; i++) {
                    if (mc.level != null && mc.level.getEntity(base + i) != null) {
                        mc.level.removeEntity(base + i, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    }
                }
                return null;
            });
            for (int s : new int[]{0, 1, 3}) {
                c.hx().give(s, "minecraft:air");
            }
            c.server().command("setblock 5 108 43 minecraft:air");
            c.server().command("tp @a -419.5 151 -419.5");
            c.server().command("forceload remove 0 40 11 56");
            run(c, () -> Mod.setField("livemap.LiveMapFeature", "bossLatched", false));
            dungeon(c);
        }
    }
}
