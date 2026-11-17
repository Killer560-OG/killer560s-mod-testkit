package dev.testkit.gametest;

import dev.testkit.compat.McCompat;
import dev.testkit.harness.WireCapture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static dev.testkit.gametest.SimBossTests.expect;
import static dev.testkit.gametest.SimBossTests.println;
import static dev.testkit.gametest.SimBossTests.start;

/**
 * {@code 1116-boss-etherwarp-nokick} (simbosswarpkick-1116, killer560 2026-11-16: "someone else just told me that etherwarp
 * in sim in boss room kicks them"; the reporter read "Invalid move player packet received" on 26.3 cheat, solo). 26.3's
 * server sends that text for a NaN coordinate or a non-finite look, AND for a second position-carrying move packet before
 * the client's tick end (receivedPositionThisTick, new in 26.3). Every warp below runs with every serverbound packet
 * captured: each move must be finite, no client tick may carry two positions, the connection must live, and a warp with a
 * target must land on it. Spots: Maxor's platform, the lava pit level, P3 ground, Necron's platform; ZPEW off and on.
 */
final class SimBossEtherwarp1116 {

    static final String NAME = "1116-boss-etherwarp-nokick";
    private static final String SIM_ITEMS = "com.killer560.hub.roomsim.SimItems";
    private static final String EW = "com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature";
    private static final String ZPEW = "zpew.ZeroPingEtherwarpConfig";

    /** name, x, feet y, z, a yaw that looks over open air there. */
    private record Spot(String name, double x, double y, double z, float openYaw) {
    }

    /** name, yaw offset from the spot's open yaw, pitch, start this many blocks up (falling). */
    private record Kind(String name, float dyaw, float pitch, double up) {
    }

    private static final Spot[] SPOTS = {
            new Spot("maxor platform", 73.5, 221, 14.5, 0f),
            new Spot("lava pit level", 100.5, 108, 50.5, 90f),
            new Spot("p3 ground", 54.5, 116, 50.5, 0f),
            new Spot("necron platform", 54.5, 65, 76.5, 0f),
    };

    private static final Kind[] KINDS = {
            new Kind("down", 0f, 90f, 0),
            new Kind("up", 0f, -90f, 0),
            new Kind("at air", 0f, -30f, 0),
            new Kind("onto a block", 0f, 35f, 0),
            new Kind("far block", 0f, 4f, 0),
            new Kind("down while falling", 0f, 90f, 6),
            new Kind("ahead while falling", 0f, 30f, 6),
    };

    private SimBossEtherwarp1116() {
    }

    static String describe(Object p) {
        if (p instanceof ServerboundMovePlayerPacket m) {
            return String.format(Locale.ROOT, "move pos=%s rot=%s %s %s %s yaw=%s pitch=%s", m.hasPosition(), m.hasRotation(),
                    m.getX(0), m.getY(0), m.getZ(0), m.getYRot(0f), m.getXRot(0f));
        }
        if (p instanceof ServerboundClientTickEndPacket) {
            return "end";
        }
        if (p instanceof ServerboundAcceptTeleportationPacket) {
            return "accept";
        }
        if (p instanceof ServerboundUseItemPacket) {
            return "useitem";
        }
        return p.getClass().getSimpleName();
    }

    private static boolean alive(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> mc.getConnection() != null && mc.level != null && mc.player != null
                && !(McCompat.screen(mc) instanceof DisconnectedScreen));
    }

    private static void turn(Player p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    private static double[] serverPos(ClientGameTestContext ctx) {
        AtomicReference<double[]> now = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var srv = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            srv.execute(() -> {
                var sp = srv.getPlayerList().getPlayer(uuid);
                now.set(new double[]{sp.getX(), sp.getY(), sp.getZ()});
            });
        });
        ctx.waitFor(mc -> now.get() != null, 100);
        return now.get();
    }

    private static void giveAotv(ClientGameTestContext ctx) {
        AtomicReference<Boolean> given = new AtomicReference<>();
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            UUID uuid = mc.player.getUUID();
            server.execute(() -> {
                server.getPlayerList().getPlayer(uuid).getInventory().setItem(0, (ItemStack) ModUnderTest.staticCall(SIM_ITEMS,
                        "build", new Class<?>[]{String.class}, new Object[]{"ASPECT_OF_THE_VOID"}));
                given.set(true);
            });
        });
        ctx.waitFor(mc -> given.get() != null, 200);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        ctx.waitTicks(2);
    }

    /** 26.2's vanilla client answers a teleport with accept + its own PosRot beside the tick's move, and its server allows it. */
    private static final boolean ONE_POSITION_RULE =
            !net.minecraft.SharedConstants.getCurrentVersion().name().startsWith("26.2");

    /** Every move between two tick ends: finite values, and on 26.3 at most one position. Returns the faults found. */
    static List<String> wireFaults(List<WireCapture.Sent> sent) {
        List<String> out = new ArrayList<>();
        int positions = 0;
        StringBuilder tick = new StringBuilder();
        for (WireCapture.Sent s : sent) {
            String t = s.text();
            tick.append(t).append(" | ");
            if (t.equals("end")) {
                positions = 0;
                tick.setLength(0);
                continue;
            }
            if (!t.startsWith("move ")) {
                continue;
            }
            if (t.contains("NaN") || t.contains("Infinity")) {
                out.add("non-finite move: " + t);
            }
            if (ONE_POSITION_RULE && t.startsWith("move pos=true") && ++positions > 1) {
                out.add("two positions in one client tick: " + tick);
            }
        }
        return out;
    }

    private static void warp(ClientGameTestContext ctx, List<String> f, Spot s, Kind k, String zpew) {
        String tag = s.name() + " / " + k.name() + " / zpew " + zpew;
        ctx.getInput().releaseKey(o -> o.keyShift);
        SimBossTests.tp(ctx, s.x(), s.y() + k.up(), s.z(), s.openYaw() + k.dyaw(), k.pitch());
        if (k.up() == 0) {
            ctx.waitTicks(15);
        }
        ctx.getInput().holdKey(o -> o.keyShift);
        if (k.up() == 0) {
            for (int t = 0; t < 40 && !ctx.computeOnClient(mc -> mc.player.isCrouching()); t++) {
                ctx.waitTicks(1);
            }
            ctx.waitTicks(3);
        } else {
            ctx.waitTicks(2);   // falling: warp a couple of ticks into the drop
        }
        ctx.runOnClient(mc -> turn(mc.player, s.openYaw() + k.dyaw(), k.pitch()));
        ctx.waitTicks(1);
        BlockPos target = ctx.computeOnClient(mc -> (BlockPos) ModUnderTest.staticCall(EW, "resolveTarget",
                new Class<?>[]{Level.class, Vec3.class, Player.class, double.class},
                new Object[]{mc.level, mc.player.position(), mc.player, 57.0}));
        boolean grounded = ctx.computeOnClient(mc -> mc.player.onGround());
        double[] before = serverPos(ctx);
        WireCapture.describe = SimBossEtherwarp1116::describe;
        WireCapture.start();
        ctx.getInput().pressKey(o -> o.keyUse);
        ctx.waitTicks(12);
        WireCapture.stop();
        List<WireCapture.Sent> sent = WireCapture.snapshot();
        boolean ok = alive(ctx);
        List<String> faults = wireFaults(sent);
        ctx.getInput().releaseKey(o -> o.keyShift);
        long moves = sent.stream().filter(x -> x.text().startsWith("move ")).count();
        println("boss", String.format(Locale.ROOT, "%s: target %s, %d packets (%d moves), alive %s, faults %d", tag,
                target == null ? "none" : target.toShortString(), sent.size(), moves, ok, faults.size()));
        for (String fault : faults) {
            println("boss", "  " + tag + ": " + fault);
        }
        expect(f, tag + ": the wire carried packets (the warp ran)", sent.isEmpty(), false);
        expect(f, tag + ": no invalid move packet", faults.isEmpty() ? "none" : faults.get(0), "none");
        expect(f, tag + ": still connected", ok, true);
        if (!ok) {
            return;
        }
        double[] after = serverPos(ctx);
        if (target != null && k.up() == 0 && grounded) {   // the lava pit bounces him: no fixed landing there
            double dx = after[0] - (target.getX() + 0.5);
            double dz = after[2] - (target.getZ() + 0.5);
            boolean landed = Math.abs(dx) < 0.1 && Math.abs(dz) < 0.1 && after[1] > target.getY() && after[1] < target.getY() + 2.6;
            println("boss", String.format(Locale.ROOT, "  %s: from %.2f %.2f %.2f to %.2f %.2f %.2f", tag, before[0], before[1],
                    before[2], after[0], after[1], after[2]));
            expect(f, tag + ": landed on " + target.toShortString(), landed, true);
        }
    }

    /**
     * The tester's mod list carries zero-ping etherwarps written for older protocols, which answer a landing with their own
     * PosRot beside the tick's move: two positions before one tick end. Hypixel takes that; 26.3's vanilla server kicked
     * it with "Invalid move player packet received". Sends exactly that after a real sim etherwarp landing.
     */
    private static void secondPositionAfterLanding(ClientGameTestContext ctx, List<String> f) {
        Spot s = SPOTS[0];
        ctx.getInput().releaseKey(o -> o.keyShift);
        SimBossTests.tp(ctx, s.x(), s.y(), s.z(), s.openYaw(), 35f);
        ctx.waitTicks(15);
        ctx.getInput().holdKey(o -> o.keyShift);
        for (int t = 0; t < 40 && !ctx.computeOnClient(mc -> mc.player.isCrouching()); t++) {
            ctx.waitTicks(1);
        }
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> turn(mc.player, s.openYaw(), 35f));
        ctx.waitTicks(1);
        double z0 = ctx.computeOnClient(mc -> mc.player.getZ());
        int before = extraPositions(ctx);
        ctx.getInput().pressKey(o -> o.keyUse);
        boolean landed = SimBossTests.until(ctx, "the landing", mc -> Math.abs(mc.player.getZ() - z0) > 0.5, 40);
        ctx.runOnClient(mc -> {
            var p = mc.player;
            for (int i = 0; i < 2; i++) {
                mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(p.getX(), p.getY(), p.getZ(), p.getYRot(),
                        p.getXRot(), p.onGround(), false));
            }
        });
        ctx.waitTicks(10);
        ctx.getInput().releaseKey(o -> o.keyShift);
        boolean ok = alive(ctx);
        int after = ok ? extraPositions(ctx) : before;
        println("boss", "second position in the landing tick: landed " + landed + ", alive " + ok + ", extra positions let through "
                + (after - before));
        expect(f, "second position: the warp landed", landed, true);
        expect(f, "second position in one client tick: still connected", ok, true);
        expect(f, "second position: the sim server let it through (acted, 26.3 only)", after > before, ONE_POSITION_RULE);
    }

    private static int extraPositions(ClientGameTestContext ctx) {
        try {
            return ((Number) ModUnderTest.staticCall("com.killer560.hub.roomsim.SimState", "extraPositions",
                    new Class<?>[]{}, new Object[]{})).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    static void run(ClientGameTestContext ctx, List<String> f) {
        long mark = start(ctx, "P1", true);
        giveAotv(ctx);
        boolean cheat = dev.testkit.gametest.mod.Mod.isCheat();
        String[] modes = cheat ? new String[]{"off", "on"} : new String[]{"off"};
        try {
            for (String mode : modes) {
                if (cheat) {
                    boolean on = mode.equals("on");
                    ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.set(ZPEW, "setEnabled",
                            on));
                }
                for (Spot s : SPOTS) {
                    for (Kind k : KINDS) {
                        warp(ctx, f, s, k, mode);
                        if (!alive(ctx)) {
                            for (String l : LogTap.since(mark)) {
                                if (l.contains("Invalid move") || l.contains("invalid_player_movement") || l.contains("isconnect")) {
                                    println("boss", "  log: " + l);
                                }
                            }
                            return;
                        }
                    }
                }
            }
            secondPositionAfterLanding(ctx, f);
            if (!alive(ctx)) {
                for (String l : LogTap.since(mark)) {
                    if (l.contains("Invalid move") || l.contains("lost connection")) {
                        println("boss", "  log: " + l);
                    }
                }
            }
        } finally {
            WireCapture.describe = null;
            ctx.getInput().releaseKey(o -> o.keyShift);
            if (cheat && alive(ctx)) {
                ctx.runOnClient(mc -> dev.testkit.gametest.mod.Mod.set(ZPEW, "setEnabled",
                        false));
            }
        }
    }
}
