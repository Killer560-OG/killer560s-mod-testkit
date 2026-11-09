package dev.testkit.gametest;



import dev.testkit.harness.MultiBreakWatch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 1108-boss-breaker} (burstmax-1108, killer560 2026-11-08 in the boss sim: "it looks like i break them it plays a
 * sound but they stick around awhile on the custom sim"). His settings: Breaker Aura with Multi Break + Burst Multi Break,
 * Zero Ping and Auto Swap (1-tick delay) on, the boss started at S1. A 4x3 wall two deep (24 picks, more than the 20
 * charges) is built in front of him; every tick logs the STARTs sent, the picks still solid on the client and on the
 * server, the charges, and the sim's own refusal lines. The case fails when a pick the server broke is still solid on the
 * client 3 ticks later (the stick-around), or when fewer picks broke than the charges allowed.
 */
final class SimBossBreaker1108 {

    private static final String NAME = "1108-boss-breaker";
    private static final String CONFIG = "com.killer560.hub.dungeonextras.DungeonExtrasConfig";
    private static final String STORE = "com.killer560.hub.dungeonextras.BreakerAuraStore";
    private static final String CHARGES = "com.killer560.hub.roomsim.SimBreakerState";
    private static final String ITEMS = "com.killer560.hub.roomsim.SimItems";

    private SimBossBreaker1108() {
    }

    static void run(ClientGameTestContext ctx, List<String> f, boolean charge) {
        long mark = SimBossTests.start(ctx, "S1", true);
        if (!SimBossTests.waitChat(ctx, mark, "[BOSS] Goldor: Who dares trespass into my domain?", 40)) {
            f.add("Goldor's line never came");
            return;
        }
        SimBossTests.tp(ctx, 100.5, 107, 46.5, 0f, 10f);
        List<BlockPos> wall = new ArrayList<>();
        for (int z = 49; z <= 50; z++) {
            for (int y = 107; y <= 109; y++) {
                for (int x = 99; x <= 102; x++) {
                    wall.add(new BlockPos(x, y, z));
                }
            }
        }
        SimBossTests.command(ctx, "fill 99 107 49 102 109 50 minecraft:stone_bricks replace air");
        ctx.waitTicks(10);
        List<BlockPos> picks = ctx.computeOnClient(mc -> {
            List<BlockPos> out = new ArrayList<>();
            for (BlockPos p : wall) {
                if (!mc.level.getBlockState(p).isAir()) {
                    out.add(p);
                }
            }
            return out;
        });
        println("wall: " + picks.size() + " solid picks of " + wall.size());
        if (picks.size() < 21) {
            f.add("the wall has only " + picks.size() + " solid blocks; it must outnumber the 20 charges");
        }
        boolean gave = ctx.computeOnClient(mc -> (Boolean) ModUnderTest.staticCall(ITEMS, "give",
                new Class<?>[]{Minecraft.class, String.class}, new Object[]{mc, "DUNGEONBREAKER"}));
        ctx.waitTicks(10);
        int breakerSlot = ctx.computeOnClient(mc -> {
            for (int i = 0; i < 9; i++) {
                var st = mc.player.getInventory().getItem(i);
                if (!st.isEmpty() && st.getHoverName().getString().contains("Dungeon Breaker")) {
                    return i;
                }
            }
            return -1;
        });
        println("breaker given: " + gave + ", hotbar slot " + breakerSlot);
        if (breakerSlot < 0) {
            f.add("no Dungeonbreaker in the hotbar after SimItems.give");
            return;
        }
        int other = breakerSlot == 8 ? 7 : 8;
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(other);
            Object cfg = ModUnderTest.config(CONFIG);
            ModUnderTest.set(cfg, "setBreakerAuraEnabled", true);
            ModUnderTest.set(cfg, "setBreakerAuraMultiBreak", true);
            ModUnderTest.set(cfg, "setBreakerAuraBurstMultiBreak", true);
            ModUnderTest.set(cfg, "setBreakerAuraZeroPing", true);
            ModUnderTest.set(cfg, "setBreakerAuraAutoSwap", true);
        });
        ctx.runOnClient(mc -> {
            Object cfg = ModUnderTest.config(CONFIG);
            ModUnderTest.call(cfg, "setBreakerAuraSwapDelayTicks", new Class<?>[]{int.class}, new Object[]{1});
            ModUnderTest.call(cfg, "setBreakerAuraReach", new Class<?>[]{double.class}, new Object[]{6.0});
        });
        if (charge) {
            // His 11-08 run: AP3 with Blink Charge banking, which holds the server's block changes until their ping's
            // answer goes (about a second), so each broken block stood after its break sound.
            ctx.runOnClient(mc -> {
                dev.testkit.gametest.mod.Mod.setField("blink.BlinkFeature", "keyHeldForTest", Boolean.FALSE);
                dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.FALSE);
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setEnabled", true);
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setChargeMode",
                        dev.testkit.gametest.mod.Mod.enumValue("blink.BlinkConfig$Mode", "TOGGLE"));
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setChargeTicks", 20);
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setChatMessages", false);
            });
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.TRUE));
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", Boolean.FALSE));
            ctx.waitTicks(25);
            boolean on = ctx.computeOnClient(mc -> (Boolean) dev.testkit.gametest.mod.Mod.staticCall("blink.BlinkCharge", "isActive"));
            println("Blink Charge active: " + on);
            if (!on) {
                f.add("Blink Charge did not start");
            }
        }
        long log0 = LogTap.mark();
        int[] charges0 = {ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(CHARGES, "charges"))};
        println("charges before: " + charges0[0]);
        ctx.runOnClient(mc -> {
            MultiBreakWatch.start();
            Object store = ModUnderTest.staticCall(STORE, "getInstance");
            ModUnderTest.call(store, "clear", new Class<?>[0], new Object[0]);
            for (BlockPos p : picks) {
                ModUnderTest.call(store, "addPick", new Class<?>[]{String.class},
                        new Object[]{p.getX() + "," + p.getY() + "," + p.getZ()});
            }
        });
        // Per pick: the tick the server made it air, and the tick the client did.
        int[] serverAir = new int[picks.size()];
        int[] clientAir = new int[picks.size()];
        java.util.Arrays.fill(serverAir, -1);
        java.util.Arrays.fill(clientAir, -1);
        // The tick the client stopped DRAWING the pick: its world went air, or Breaker Aura drew it as air.
        int[] drawnAir = new int[picks.size()];
        java.util.Arrays.fill(drawnAir, -1);
        int[] dugAt = new int[picks.size()];
        java.util.Arrays.fill(dugAt, -1);
        int[] digCount = new int[picks.size()];
        boolean[] srv = new boolean[picks.size()];
        java.util.Arrays.fill(srv, true);
        int lastStarts = 0;
        int seenLines = 0;
        for (int t = 0; t < 120; t++) {
            int tick = t;
            String row = ctx.computeOnClient(mc -> {
                // The server's blocks are read on the server thread (a read from here waits on its chunk and hangs
                // the paused test), so each row shows what the server had one tick ago.
                mc.getSingleplayerServer().execute(() -> {
                    ServerPlayer sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
                    for (int i = 0; i < picks.size(); i++) {
                        srv[i] = !sp.level().getBlockState(picks.get(i)).isAir();
                    }
                });
                int cSolid = 0;
                int sSolid = 0;
                for (int i = 0; i < picks.size(); i++) {
                    BlockPos p = picks.get(i);
                    boolean c = !mc.level.getBlockState(p).isAir();
                    if (drawnAir[i] < 0 && (!c || (Boolean) ModUnderTest.staticCall(
                            "com.killer560.hub.dungeonextras.BreakerAuraFeature", "drawnAsAir",
                            new Class<?>[]{int.class, int.class, int.class}, new Object[]{p.getX(), p.getY(), p.getZ()}))) {
                        drawnAir[i] = tick;
                    }
                    boolean s = srv[i];
                    if (c) {
                        cSolid++;
                    } else if (clientAir[i] < 0) {
                        clientAir[i] = tick;
                    }
                    if (s) {
                        sSolid++;
                    } else if (serverAir[i] < 0) {
                        serverAir[i] = tick;
                    }
                }
                return "client solid " + cSolid + " server solid " + sSolid + " charges "
                        + ModUnderTest.staticCall(CHARGES, "charges") + " held slot "
                        + mc.player.getInventory().getSelectedSlot();
            });
            int starts = ctx.computeOnClient(mc -> {
                // the LAST dig before the server broke it: a refused dig (charges out) is dug again later.
                int[] now = new int[picks.size()];
                for (MultiBreakWatch.Dig d : MultiBreakWatch.digs()) {
                    int k = picks.indexOf(d.pos());
                    if (k >= 0) {
                        now[k]++;
                    }
                }
                for (int k = 0; k < now.length; k++) {
                    if (now[k] > digCount[k] && serverAir[k] < 0) {
                        dugAt[k] = tick;
                        drawnAir[k] = -1;
                    }
                    digCount[k] = now[k];
                }
                return MultiBreakWatch.digs().size();
            });
            List<String> lines = LogTap.since(log0);
            StringBuilder news = new StringBuilder();
            for (int i = seenLines; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("[Sim]") || l.contains("BreakerAura") || l.contains("charges")) {
                    news.append(" | ").append(l.length() > 160 ? l.substring(0, 160) : l);
                }
            }
            seenLines = lines.size();
            if (starts != lastStarts || news.length() > 0 || t % 10 == 0) {
                println("t" + t + " digs +" + (starts - lastStarts) + " " + row + news);
            }
            lastStarts = starts;
            ctx.waitTick();
        }
        List<MultiBreakWatch.Dig> digs = ctx.computeOnClient(mc -> {
            MultiBreakWatch.stop();
            return new ArrayList<>(MultiBreakWatch.digs());
        });
        java.util.Map<Integer, Integer> perTick = new java.util.TreeMap<>();
        for (MultiBreakWatch.Dig d : digs) {
            if (d.action() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                perTick.merge(d.tick(), 1, Integer::sum);
            }
        }
        println("STARTs per client tick: " + perTick);
        int broke = 0;
        int stuck = 0;
        int lateDrawn = 0;
        for (int i = 0; i < picks.size(); i++) {
            if (serverAir[i] >= 0) {
                broke++;
                if (!charge && (clientAir[i] < 0 || clientAir[i] > serverAir[i] + 3)) {
                    stuck++;
                    println("STUCK " + picks.get(i).toShortString() + ": server air at t" + serverAir[i]
                            + ", client air at t" + clientAir[i]);
                }
            }
            println("pick " + picks.get(i).toShortString() + " dug t" + dugAt[i] + " server air t" + serverAir[i]
                    + " client air t" + clientAir[i] + " drawn air t" + drawnAir[i]);
            if (serverAir[i] >= 0 && dugAt[i] >= 0 && (drawnAir[i] < 0 || drawnAir[i] > dugAt[i] + 2)) {
                lateDrawn++;
            }
        }
        println("broke " + broke + " of " + picks.size() + ", stuck " + stuck);
        if (digs.isEmpty()) {
            f.add("Breaker Aura sent no dig at all");
        }
        if (broke < Math.min(picks.size(), charges0[0])) {
            f.add("only " + broke + " picks broke on the server with " + charges0[0] + " charges for " + picks.size());
        }
        if (lateDrawn > 0) {
            f.add(lateDrawn + " broken pick(s) still drawn more than 2 ticks after their dig");
        }
        if (stuck > 0) {
            f.add(stuck + " pick(s) broke on the server but stayed solid on the client over 3 ticks");
        }
        ctx.runOnClient(mc -> {
            Object store = ModUnderTest.staticCall(STORE, "getInstance");
            ModUnderTest.call(store, "clear", new Class<?>[0], new Object[0]);
            ModUnderTest.turnOff(CONFIG, "setBreakerAuraEnabled");
            ModUnderTest.turnOff(CONFIG, "setBreakerAuraBurstMultiBreak");
            if (charge) {
                dev.testkit.gametest.mod.Mod.setField("blink.BlinkFeature", "keyHeldForTest", null);
                dev.testkit.gametest.mod.Mod.setField("blink.BlinkCharge", "keyHeldForTest", null);
                dev.testkit.gametest.mod.Mod.staticCall("blink.BlinkCharge", "reset", "test teardown");
                dev.testkit.gametest.mod.Mod.set("blink.BlinkConfig", "setEnabled", false);
            }
        });
    }

    private static void println(String line) {
        System.out.println("[" + NAME + "] " + line);
    }
}
