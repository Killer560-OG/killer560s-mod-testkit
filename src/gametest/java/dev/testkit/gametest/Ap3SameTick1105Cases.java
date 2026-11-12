package dev.testkit.gametest;

import com.google.gson.JsonObject;

import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static dev.testkit.gametest.Ap3RuntimeTests.CONFIG;
import static dev.testkit.gametest.Ap3RuntimeTests.LANE_X0;
import static dev.testkit.gametest.Ap3RuntimeTests.node;
import static dev.testkit.gametest.Ap3RuntimeTests.onClient;

/**
 * SAME-TICK GRIM CASES for AP3 (grimgapA-1105; killer560 2026-11-05: every automated look, slot, click or use is tested
 * with his own action landing in the same tick). AP3 carries him down a lane (a real W press into a RUN box) to one node
 * while {@link SameTick} puts HIS action into every client tick: a right click with a blaze rod in hand ({@code use}), a
 * scroll between two blaze rods ({@code swap}), a server teleport he answers ({@code tp}), or a left click on what AP3
 * aims him at ({@code dig}, BOOM only). A case passes only when the server saw the node act (Hx events), a GrimAC tick
 * carried both the node's packets and his (left clicks: ActionGate holds the node, so never one tick), and GrimAC said
 * nothing (Session; run on an {@code experimental-checks: true} checkout too).
 * <ul>
 *   <li>1105-ap3-same-use-&lt;act&gt;: a USE node that swaps to the wand (slot 4) and uses it at the sky.</li>
 *   <li>1105-ap3-same-block-&lt;act&gt;: a BLOCK node (slab from slot 6, placed on the floor beside the lane).</li>
 *   <li>1105-ap3-same-boom-&lt;act&gt;: a BOOM node (Superboom from slot 7, tapped on the floor ahead).</li>
 *   <li>1105-ap3-same-align-&lt;method&gt;-use: an ALIGN off the lane's line (Camera Planner turns his real camera,
 *       Sent-Yaw Planner steers the yaw sent) while he right-clicks every tick (his use carries his look: BadPacketsJ when
 *       the tick's movement packet then reports another).</li>
 * </ul>
 */
final class Ap3SameTick1105Cases {

    private static final int ROD_A = 7;
    private static final int ROD_B = 8;

    private Ap3SameTick1105Cases() {
    }

    static void register(Session s) {
        if (!Mod.isCheat()) {
            return;
        }
        for (String type : new String[]{"USE", "BLOCK", "BOOM"}) {
            for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE_KEY, SameTick.Act.SWAP, SameTick.Act.TP}) {
                s.test("1105-ap3-same-" + type.toLowerCase(Locale.ROOT) + "-" + (a == SameTick.Act.USE_KEY ? "use"
                        : a.name().toLowerCase(Locale.ROOT)),
                        c -> node1(c, type, a));
            }
        }
        s.test("1105-ap3-same-boom-dig", c -> node1(c, "BOOM", SameTick.Act.DIG));
        s.test("1105-ap3-same-align-camera-use", c -> align(c, "CAMERA"));
        s.test("1105-ap3-same-align-sentyaw-use", c -> align(c, "SENT_YAW"));
    }

    /** Two blaze rods in slots 8 and 9 (his hand: a use that passes, sends use_item and no swing), slot 8 held. */
    private static void rods(Session c) {
        c.server().command("item replace entity @p hotbar." + ROD_A + " with minecraft:blaze_rod");
        c.server().command("item replace entity @p hotbar." + ROD_B + " with minecraft:blaze_rod");
        c.ctx().waitTicks(3);
        onClient(c.ctx(), mc -> {
            mc.player.getInventory().setSelectedSlot(ROD_A);
            return null;
        });
        c.ctx().waitTicks(3);
    }

    private static boolean actFired() {
        return Boolean.TRUE.equals(Mod.field("ap3.Ap3Executor", "actFired"));
    }

    private static SameTick start(Session c, SameTick.Act act, Predicate<Packet<?>> feature) {
        // A left click every tick would hold AP3's act for good (ActionGate gives his queued click the tick), and so would a
        // right click every tick (the act's hotbar change waits out his use: PacketOrderE): every other tick, a 10 cps click.
        return onClient(c.ctx(), mc -> SameTick.start(act, act == SameTick.Act.DIG || act == SameTick.Act.USE_KEY ? 2 : 1,
                feature, ROD_A, ROD_B));
    }

    private static void close(Session c, SameTick st) {
        if (st != null) {
            onClient(c.ctx(), mc -> {
                st.close();
                return null;
            });
        }
    }

    /** Waits {@code ticks}, the server teleporting him in place every other tick for a TP case. */
    private static void run(Session c, SameTick.Act act, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (act == SameTick.Act.TP && i % 2 == 0) {
                c.server().command("execute as @a at @s run tp @s ~ ~ ~");
            }
            c.ctx().waitTick();
        }
    }

    /**
     * A TP case retries a node whose use the server dropped (flake-sameblocktp-1111, 18 of 59 runs): the server's /tp sets
     * the player's awaiting-position state, and vanilla ignores a use_item_on until it gets the matching accept, so a use
     * that reaches the server after a NEWER /tp (the test issues one every other tick, on a thread of its own) is dropped
     * whatever the client sent; the client cannot know of a teleport still in flight. A drop with the node's use on the
     * wire is retried up to {@link #TP_ATTEMPTS} times; a node that never acts in any attempt still fails.
     */
    private static final int TP_ATTEMPTS = 6;

    private static void node1(Session c, String type, SameTick.Act act) {
        for (int attempt = 1; !node1Once(c, type, act, act != SameTick.Act.TP || attempt == TP_ATTEMPTS); attempt++) {
            c.note("attempt " + attempt + ": the server dropped the node's use (a newer teleport was in flight), again");
        }
    }

    /** Runs the case once; false = no node act seen and {@code last} is false (the caller runs it again). */
    private static boolean node1Once(Session c, String type, SameTick.Act act, boolean last) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        // A BLOCK node places the HELD block first: an earlier case of a big session left the TNT in slot 7 selected, so it placed that, not the slab. Hold the empty slot 1.
        onClient(ctx, mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            return null;
        });
        c.server().command("item replace entity @p hotbar.3 with minecraft:stick[minecraft:custom_data={id:\"AP3_TEST_WAND\"}]");
        c.server().command("item replace entity @p hotbar.5 with minecraft:smooth_stone_slab 64");
        c.server().command("item replace entity @p hotbar.6 with minecraft:tnt[minecraft:custom_data={id:\"SUPERBOOM_TNT\"}] 64");
        Object run = Ap3RuntimeTests.runNode(z);
        Object n = switch (type) {
            case "USE" -> node("USE", LANE_X0 + 10.0, z, 20f, -35f, 1.0, 3.0);
            case "BLOCK" -> node("BLOCK", LANE_X0 + 10.0, z, 0f, 60f, 1.0, 3.0);
            default -> node("BOOM", LANE_X0 + 10.0, z, -90f, 60f, 1.0, 3.0);
        };
        if (type.equals("USE")) {
            Ap3RuntimeTests.set(n, "useItemId", "AP3_TEST_WAND");
        }
        Object stop = node("STOP", LANE_X0 + 22.0, z, -90f, 0f, 2.0, 3.0);
        int slot = type.equals("USE") ? 3 : type.equals("BLOCK") ? 5 : 6;
        // The node's packets: its hotbar change and its click (a BOOM's dig only once it has fired, so his own dig on
        // the same block before it in that tick is his).
        Predicate<Packet<?>> feature = p -> (p instanceof ServerboundSetCarriedItemPacket h && h.getSlot() == slot)
                || switch (type) {
                    case "USE" -> p instanceof ServerboundUseItemPacket;
                    case "BLOCK" -> p instanceof ServerboundUseItemOnPacket;
                    default -> p instanceof ServerboundPlayerActionPacket && actFired();
                };
        SameTick st = null;
        try {
            rods(c);
            long since = c.hx().head();
            Ap3RuntimeTests.walkIntoRun(c, z, run, n, stop);
            if (act == SameTick.Act.DIG) {
                // His crosshair on the floor ahead, so his left clicks dig from the start (AP3 aims the node itself).
                onClient(ctx, mc -> {
                    mc.player.setXRot(50f);
                    return null;
                });
            }
            st = start(c, act, feature);
            run(c, act, 60);
            close(c, st);
            SameTick done = st;
            st = null;
            c.note(type + " " + done.report());
            List<JsonObject> acted = switch (type) {
                case "USE" -> c.hx().events(since, "use_item").stream()
                        .filter(e -> "minecraft:stick".equals(e.get("item").getAsString())).toList();
                case "BLOCK" -> c.hx().events(since, "use_block").stream()
                        .filter(e -> e.get("item").getAsString().contains("slab")).toList();
                default -> boomTaps(c.hx().events(since, "held", "attack_block"));
            };
            c.note("server saw the node act " + acted.size() + " time(s)");
            c.note("server events " + c.hx().events(since, "held", "use_block", "use_item").stream().map(e -> e.get("type") + ":" + (e.has("item") ? e.get("item") : e.has("slot") ? e.get("slot") : "") + "@" + e.get("tick")).toList());
            if (acted.isEmpty() && !last) {
                return false;
            }
            // A USE node leaves him on its wand, so his own right clicks after it are wand uses too.
            boolean onWand = type.equals("USE") && act == SameTick.Act.USE_KEY;
            c.check(onWand ? !acted.isEmpty() : acted.size() == 1, type + ": the server saw the node act " + acted.size()
                    + " time(s), not once");
            // His right click comes first in a tick, so the node's hotbar change waits for the next one (PacketOrderE).
            String why = done.verdict(type, act == SameTick.Act.USE_KEY || act == SameTick.Act.DIG);
            c.check(why == null, why);
            return true;
        } finally {
            close(c, st);
            onClient(c.ctx(), mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            c.server().command("clear @p minecraft:blaze_rod");
        }
    }

    /** attack_block events that follow a held change to slot 7 (the Superboom), the boom's tap as the server saw it. */
    private static List<JsonObject> boomTaps(List<JsonObject> evs) {
        java.util.ArrayList<JsonObject> out = new java.util.ArrayList<>();
        boolean tnt = false;
        for (JsonObject e : evs) {
            if (e.get("type") != null && "held".equals(e.get("type").getAsString())) {
                tnt = e.get("slot").getAsInt() == 6;
            } else if (tnt) {
                out.add(e);
                tnt = false;
            }
        }
        return out;
    }

    private static void align(Session c, String method) {
        ClientGameTestContext ctx = c.ctx();
        double z = Ap3RuntimeTests.lane();
        double ax = LANE_X0 + 10.5;
        double az = z + 0.7; // off the line he runs on: the align has to steer
        Object run = Ap3RuntimeTests.runNode(z);
        Object align = node("ALIGN", ax, az, 0f, 0f, 2.0, 3.0);
        Object restore = onClient(ctx, mc -> Mod.get(CONFIG, "getAlignMethod"));
        SameTick st = null;
        try {
            onClient(ctx, mc -> {
                Mod.set(CONFIG, "setAlignMethod", Mod.enumValue("ap3.Ap3Config$AlignMethod", method));
                return null;
            });
            rods(c);
            Ap3RuntimeTests.walkIntoRun(c, z, run, align);
            st = start(c, SameTick.Act.USE, p -> p instanceof ServerboundMovePlayerPacket
                    && Boolean.TRUE.equals(Mod.staticCall("ap3.Ap3Executor", "isRunning")));
            run(c, SameTick.Act.USE, 80);
            close(c, st);
            SameTick done = st;
            st = null;
            double[] at = onClient(ctx, mc -> new double[]{mc.player.getX(), mc.player.getZ()});
            c.note(String.format(Locale.ROOT, "%s align: ended at (%.4f, %.4f), the align's point (%.4f, %.4f); %s", method, at[0],
                    at[1], ax, az, done.report()));
            c.check(Math.abs(at[0] - ax) < 0.05 && Math.abs(at[1] - az) < 0.05, method + ": the align never landed him on its point");
            String why = done.verdict(method + " align");
            c.check(why == null, why);
        } finally {
            close(c, st);
            onClient(c.ctx(), mc -> {
                Ap3RuntimeTests.staticCallStop();
                return null;
            });
            onClient(ctx, mc -> {
                Mod.set(CONFIG, "setAlignMethod", restore);
                return null;
            });
            c.server().command("clear @p minecraft:blaze_rod");
        }
    }
}
