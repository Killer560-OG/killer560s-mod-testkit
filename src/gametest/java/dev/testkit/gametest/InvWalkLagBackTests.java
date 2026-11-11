package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.testkit.gametest.InvWalkCampaignTests.*;

/**
 * 1111-iw-lagback (invwalkmenu-1111): Inventory Walk's cheat "Lag Back Walk". In a server menu (containerId != 0) he walks
 * on his real keys with vanilla physics, but every player_input the server gets says no movement key (Hypixel closes a
 * SkyBlock menu 1-4 ticks after a movement key goes down; drift with no keys keeps it open), while his positions still go
 * out. GrimAC then predicts no movement and flags Simulation every tick: the owner's accepted exception (killer560
 * 2026-11-11), so Simulation lines are tallied, any OTHER check fails the row. Asserted on the wire (encoder level, after
 * the mod's rewrite): no movement key in any player_input while the menu is open, positions moving; a server pullback is
 * honored (the client lands where the server put him), counted by the mod, and he walks on with the keys still hidden;
 * and his own container click, hotbar swap and teleport accept in the same tick as a hidden input.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkLagBackTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String nm = "1111-iw-lagback";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -11600;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            rows.tolerate = "Simulation";
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                ctx.runOnClient(mc -> Mod.set(IW_CFG, "setLagBackWalk", true));
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    row(rows, ctx, server, hx, x, z, lat, null);
                    for (SameTick.Act st : List.of(SameTick.Act.SCREEN_CLICK, SameTick.Act.SWAP, SameTick.Act.TP)) {
                        row(rows, ctx, server, hx, x, z, lat, st);
                    }
                }
            } finally {
                ctx.runOnClient(mc -> Mod.set(IW_CFG, "setLagBackWalk", false));
                tearDown(ctx, gate);
            }
            scenario.log("Simulation lines tallied (accepted for Lag Back Walk): " + rows.tolerated);
            rows.finish(nm);
        });
    }

    private static int num(ClientGameTestContext ctx, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, getter)).intValue());
    }

    private static boolean menuUp(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?> s && s.getMenu().containerId != 0);
    }

    /** {@code st} null: walk, a forced pullback, walk on. Otherwise his act in the same ticks as the hidden inputs. */
    private static void row(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z,
            int lat, SameTick.Act st) {
        String label = "L" + lat + " lag back walk" + (st == null ? ", pullback" : ", same tick " + st);
        String who = name(ctx);
        keys(ctx, Set.of());
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
        rows.begin();
        hx.menu("SkyBlock Menu", 4, Map.of(11, "minecraft:player_head", 13, "minecraft:diamond"), null);
        if (!InvWalkTermTests.openMenu(ctx)) {
            rows.end(label, false, "the menu never opened", null);
            return;
        }
        ctx.waitTicks(5);
        int hidden0 = num(ctx, "hiddenInputs");
        int pull0 = num(ctx, "pullbacks");
        long since = hx.head();
        // Wire record: player_input as its movement keys, positions as x,z (encoder level: what the server reads).
        WireCapture.describe = o -> o instanceof ServerboundPlayerInputPacket in
                ? "IN " + (in.input().forward() || in.input().backward() || in.input().left() || in.input().right() ? "MOVING" : "still")
                : o instanceof ServerboundMovePlayerPacket m && m.hasPosition()
                ? String.format(Locale.ROOT, "POS %.3f %.3f", m.getX(0), m.getZ(0)) : o.getClass().getSimpleName();
        WireCapture.start();
        // Keys change every tick so a (hidden) player_input goes out every tick: the feature's packet in each server tick.
        List<Set<String>> alt = List.of(Set.of("key.forward"), Set.of("key.forward", "key.left"));
        SameTick[] same = {null};
        if (st != null) {
            same[0] = ctx.computeOnClient(mc -> SameTick.start(st, st == SameTick.Act.SWAP ? 2 : 5,
                    pk -> pk instanceof ServerboundPlayerInputPacket, 1, 2));
        }
        boolean up = true;
        double path = 0;
        Vec3 prev = ctx.computeOnClient(mc -> mc.player.position());
        double before = 0;
        double landed = 0;
        int pullAfter = 0;
        int pullAt40 = Integer.MAX_VALUE;
        double after = 0;
        for (int t = 0; t < 80; t++) {
            keys(ctx, alt.get(t % 2));
            if (st == SameTick.Act.TP && t % 8 == 4) {
                // A teleport onto his own spot: his accept (and echo) is the act, the hidden input shares the tick.
                server.command(String.format(Locale.ROOT, "execute as %s at @s run tp @s ~ ~ ~", who));
            }
            if (st == null && t == 40) {
                before = ctx.computeOnClient(mc -> mc.player.getX());
                pullAt40 = num(ctx, "pullbacks");
                server.command(String.format(Locale.ROOT, "execute as %s at @s run tp @s ~-2 ~ ~", who));
            }
            ctx.waitTicks(1);
            Vec3 now = ctx.computeOnClient(mc -> mc.player.position());
            if (st == null && t >= 40 && landed == 0 && now.x < before - 1 && num(ctx, "pullbacks") > pullAt40) {
                landed = now.x;
                pullAfter = t;
            }
            if (st == null && landed != 0 && t > pullAfter) {
                after += Math.hypot(now.x - prev.x, now.z - prev.z);
            }
            path += Math.hypot(now.x - prev.x, now.z - prev.z);
            prev = now;
            up &= menuUp(ctx);
        }
        keys(ctx, Set.of());
        SameTick s = same[0];
        if (s != null) {
            ctx.runOnClient(mc -> s.close());
        }
        boolean masking = ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(IW, "isMasking"));
        ctx.waitTicks(3);
        WireCapture.stop();
        WireCapture.describe = null;
        List<WireCapture.Sent> wire = WireCapture.snapshot();
        int inputs = 0;
        int moving = 0;
        int pos = 0;
        String lastPos = null;
        int distinct = 0;
        for (WireCapture.Sent w : wire) {
            if (w.text().startsWith("IN ")) {
                inputs++;
                moving += w.text().endsWith("MOVING") ? 1 : 0;
            } else if (w.text().startsWith("POS ")) {
                pos++;
                distinct += w.text().equals(lastPos) ? 0 : 1;
                lastPos = w.text();
            }
        }
        List<JsonObject> clicks = hx.events(since, "container.click");
        int clicksMoving = movingClicks(clicks);
        int hiddenN = num(ctx, "hiddenInputs") - hidden0;
        int pulls = num(ctx, "pullbacks") - pull0;
        closeScreen(ctx);
        ctx.waitTicks(3);
        boolean unmasked = !ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(IW, "isMasking"));
        String sameVerdict = null;
        if (s != null) {
            sameVerdict = st == SameTick.Act.SCREEN_CLICK
                    // Stop To Click holds his click for a still tick (keys really let go), so it never shares a tick with a
                    // rewritten input; what counts is that every click reached the server and none while it had him moving.
                    ? (s.done() == 0 ? "his click never went in" : clicks.size() < s.done() ? s.done() + " click(s) put in, "
                    + clicks.size() + " handled by the server" : null)
                    : s.verdict("lag back walk + " + st);
        }
        // GrimAC sets him back on every Simulation flag, so on a fast connection he gains almost nothing (L0: 0.1 a tick and
        // back); what must hold is that he keeps walking: a new position every tick, keys hidden in every input, menu up.
        boolean walked = distinct >= 20 && inputs >= 8 && hiddenN >= 5 && moving == 0 && up && masking && unmasked;
        // The forced pullback: the client lands where the server put him (2 back) and the mod counted it; "walked" holds after.
        boolean pulled = st != null || (pulls >= 1 && landed != 0 && landed < before - 1);
        boolean ok = walked && pulled && sameVerdict == null && clicksMoving == 0;
        String detail = String.format(Locale.ROOT, "path %.2f with the menu up all along %s, wire: %d player_input (%d with a "
                        + "movement key), %d positions (%d changes), mod hid %d, masking %s then off %s, pullbacks %d%s, clicks "
                        + "handled %d (%d while the server had him moving)%s", path, up, inputs, moving, pos, distinct, hiddenN,
                masking, unmasked, pulls, st != null ? "" : String.format(Locale.ROOT, " (x %.2f -> %.2f at t%d, walked on %.2f)",
                        before, landed, pullAfter, after), clicks.size(), clicksMoving,
                s == null ? "" : "; " + s.report() + (sameVerdict == null ? "" : " [" + sameVerdict + "]"));
        ctx.waitTicks(20);
        rows.end(label, ok, detail, null);
    }
}
