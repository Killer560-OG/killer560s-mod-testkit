package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.testkit.gametest.InvWalkCampaignTests.*;

/**
 * 1027-iwl-diff: Inventory Walk's own-inventory click against a VANILLA client doing the same thing (killer560, 2026-10-27:
 * "I could be holding W legit open my inventory on a tick move something on a tick close my inventory on a tick and then
 * walk again"). Both runs sprint east, move one item (pick up slot 9, put down slot 19) and walk on; the server's own record
 * of what it handled (testmod wire.* events: every move, input, sprint command, click and close, in arrival order) is cut
 * from two packets before the first click to four moves after the close, and the two packet SHAPES must be identical:
 * the same kinds in the same order with the same inputs and sprint commands. The momentum kept (distance in the 5 ticks
 * after the close) is printed for both. GrimAC must stay clean (Scenario.run).
 *
 * <p>Vanilla: Inventory Walk off, W and sprint held, the inventory key pressed (Minecraft.handleKeybinds opens the screen and
 * KeyMapping.releaseAll lets the keys go before the player's tick), the two clicks and the close in the frame after that tick,
 * then W and sprint set down again as KeyMapping.setAll does on close (it reads the physical keyboard, which a test's held
 * keys are not). Inventory Walk: on, Stop To Click on, walking in his open inventory, the same two clicks.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkLegitTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String nm = "1027-iwl-diff";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -10100;
        int z = -9000;
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            List<String> bad = new ArrayList<>();
            try (Hx hx = Hx.connect()) {
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    for (int rep = 0; rep < 2; rep++) {
                        Run v = vanilla(ctx, server, hx, x, z);
                        Run w = invWalk(ctx, server, hx, x, z);
                        String vs = String.join(" ", v.shape);
                        String ws = String.join(" ", w.shape);
                        boolean same = vs.equals(ws);
                        String row = String.format(Locale.ROOT, "L%d rep %d: %s; vanilla kept %.3f in 5 ticks after the close, "
                                        + "Inventory Walk kept %.3f; items vanilla %s, IW %s", lat, rep, same ? "SAME SHAPE" : "DIFFERENT",
                                v.after, w.after, v.items, w.items);
                        scenario.log("ROW " + row);
                        scenario.log("    vanilla: " + vs);
                        scenario.log("    invwalk: " + ws);
                        scenario.log("    vanilla wire: " + v.raw);
                        scenario.log("    invwalk wire: " + w.raw);
                        if (!same || v.shape.isEmpty() || !w.items || !v.items) {
                            bad.add("L" + lat + " rep " + rep + (same ? "" : " shape differs") + (w.items ? "" : " IW items wrong")
                                    + (v.items ? "" : " vanilla items wrong"));
                        }
                    }
                }
            } finally {
                hxOff(ctx);
                tearDown(ctx, gate);
            }
            scenario.log("SUMMARY " + nm + ": bad " + bad.size() + " " + bad);
            check(bad.isEmpty(), nm + ": " + bad);
        });
    }

    static void hxOff(ClientGameTestContext ctx) {
        try (Hx hx = Hx.connect()) {
            hx.call("wire.record", "on", false);
        } catch (Exception ignored) {
        }
    }

    static final class Run {
        List<String> shape = new ArrayList<>();
        String raw = "";
        double after;
        boolean items;
    }

    static Run vanilla(ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z) {
        ctx.runOnClient(mc -> Mod.set(IW_CFG, "setEnabled", false));
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        give(server, ctx);
        ctx.getInput().holdKey(o -> o.keyUp);
        ctx.getInput().holdKey(o -> o.keySprint);
        ctx.waitTicks(15);
        hx.call("wire.record", "on", true);
        long since = hx.head();
        ctx.waitTicks(3);
        ctx.getInput().pressKey(o -> o.keyInventory);
        // The tick that handles the key opens the screen and lets the keys go before the player's tick.
        for (int i = 0; i < 5 && !ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof InventoryScreen); i++) {
            ctx.waitTicks(1);
        }
        boolean[] ok = new boolean[1];
        ctx.runOnClient(mc -> {
            var m = mc.player.containerMenu;
            mc.gameMode.handleContainerInput(m.containerId, 9, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(m.containerId, 19, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            ok[0] = !mc.player.inventoryMenu.getSlot(19).getItem().isEmpty();
            McCompat.screen(mc).onClose();
            // KeyMapping.setAll on close: his fingers are still on W and sprint.
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        });
        return finish(ctx, hx, since, ok[0], () -> {
            ctx.getInput().releaseKey(o -> o.keyUp);
            ctx.getInput().releaseKey(o -> o.keySprint);
        });
    }

    static Run invWalk(ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z) {
        iwOn(ctx, true, 0);
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        give(server, ctx);
        openInventory(ctx);
        keys(ctx, SPRINT);
        ctx.waitTicks(15);
        hx.call("wire.record", "on", true);
        long since = hx.head();
        ctx.waitTicks(3);
        boolean[] ok = new boolean[1];
        ctx.runOnClient(mc -> {
            var m = mc.player.containerMenu;
            mc.gameMode.handleContainerInput(m.containerId, 9, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            mc.gameMode.handleContainerInput(m.containerId, 19, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            ok[0] = !mc.player.inventoryMenu.getSlot(19).getItem().isEmpty();
        });
        Run r = finish(ctx, hx, since, ok[0], () -> {
        });
        keys(ctx, Set.of());
        closeScreen(ctx);
        ctx.waitTicks(10);
        return r;
    }

    /** Walks 12 more ticks, stops recording, and cuts the server's wire record around the click. */
    static Run finish(ClientGameTestContext ctx, Hx hx, long since, boolean clientOk, Runnable release) {
        ctx.waitTicks(12);
        hx.call("wire.record", "on", false);
        release.run();
        ctx.waitTicks(10);
        List<JsonObject> ev = hx.events(since, "wire");
        Run r = new Run();
        JsonObject st = state(hx);
        List<String> srv = serverSlots(st);
        r.items = clientOk && srv.size() > 19 && !srv.get(19).isEmpty() && srv.get(9).isEmpty();
        int firstClick = -1;
        int close = -1;
        for (int i = 0; i < ev.size(); i++) {
            String k = ev.get(i).get("kind").getAsString();
            if (k.equals("click") && firstClick < 0) {
                firstClick = i;
            }
            if (k.equals("close") && firstClick >= 0 && close < 0) {
                close = i;
            }
        }
        StringBuilder raw = new StringBuilder();
        for (JsonObject e : ev) {
            raw.append(e.get("tick").getAsInt()).append(':').append(e.get("kind").getAsString()).append('(')
                    .append(e.get("detail").getAsString()).append(") ");
        }
        r.raw = raw.toString().trim();
        if (firstClick < 0 || close < 0) {
            r.shape.add("NO-CLICK-OR-CLOSE");
            return r;
        }
        // From the second move before the first click...
        int from = firstClick;
        for (int moves = 0; from > 0 && moves < 2; ) {
            from--;
            if (ev.get(from).get("kind").getAsString().equals("move")) {
                moves++;
            }
        }
        // ...to the fourth move after the close.
        int to = close;
        double[] prev = null;
        double[] closePos = null;
        for (int moves = 0; to < ev.size() - 1 && moves < 4; ) {
            to++;
            if (ev.get(to).get("kind").getAsString().equals("move")) {
                moves++;
            }
        }
        for (int i = from; i <= to; i++) {
            JsonObject e = ev.get(i);
            String k = e.get("kind").getAsString();
            String d = e.get("detail").getAsString();
            switch (k) {
                case "move" -> r.shape.add(d.startsWith("pos") ? "move" : "move(nopos)");
                case "input" -> r.shape.add("input[" + d + "]");
                case "cmd" -> r.shape.add(d);
                case "click" -> r.shape.add("click");
                case "close" -> r.shape.add("close(" + d + ")");
                default -> r.shape.add(k);
            }
        }
        // Momentum: the server's position at the close, and 5 moves later.
        double[] at = null;
        int moves = 0;
        for (int i = close; i < ev.size(); i++) {
            JsonObject e = ev.get(i);
            double[] p = {e.get("x").getAsDouble(), e.get("y").getAsDouble(), e.get("z").getAsDouble()};
            if (at == null) {
                at = p;
            }
            if (e.get("kind").getAsString().equals("move")) {
                moves++;
                if (moves == 6) {
                    r.after = flat(at, p);
                    break;
                }
            }
        }
        return r;
    }
}
