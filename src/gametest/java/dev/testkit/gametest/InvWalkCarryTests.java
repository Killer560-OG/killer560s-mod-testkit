package dev.testkit.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static dev.testkit.gametest.InvWalkCampaignTests.*;

/**
 * Inventory Walk, 2026-11-05 (killer560: "the pause to pick up an item is perfect but if i am still holding it then it
 * cant start walking", and two Hypixel limbos "Bad client inventory move." in his 26.3 log, both a click in his own
 * inventory followed by walking with no close of window 0, one with the item still on the cursor).
 *
 * <p>1105-iw-carry: in his own inventory a pick-up is held unsent while he walks carrying it; the put-down goes after one
 * keyless tick with the pick-up and a close of container 0. Every row asserts on the SERVER: he really moved while carrying
 * (server path), the server never had the item on its cursor while he moved, no position-changing move reached it between
 * a container-0 click and its close (Hypixel's limbo pattern), every click and close reached it with still input, the items
 * ended where the client put them (and nothing was lost), and GrimAC stayed clean. Same-tick rows: put-down in the tick
 * the keys come back, a split and a swap of the cursor stack, a shift-click and a hotbar swap, Escape while carrying, a
 * server teleport while carrying, pick-up and put-down in one tick.
 *
 * <p>1105-iw-look: the Look Key. Held, a real MouseHandler.onMove turns the player (client and server yaw/pitch) while
 * the cursor (xpos/ypos) and the slots stay put and no click goes; let go, the same move moves the cursor and not the
 * player. Grim rows: turning in the tick of a slot click, of a close, while walking, with a chest open.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkCarryTests implements FabricClientGameTest {

    static final int KEY_V = 86;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(InvWalkCarryTests::carry,
                InvWalkCarryTests::lookKey)) {
            try {
                c.accept(ctx);
            } catch (AssertionError e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    // ============================================================================================== 1105-iw-carry

    /** What one row's server record showed. */
    static final class Seen {
        double serverPath;
        boolean carriedOnServer;
        String notes = "";
    }

    interface Body {
        /** Runs the row with the inventory open and Inventory Walk on; returns the expected server slots (index -> stack). */
        Map<Integer, String> run(ClientGameTestContext ctx, TestServer server, Hx hx, Seen seen, int x, int z);
    }

    private static void carry(ClientGameTestContext ctx) {
        String nm = "1105-iw-carry";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -10300;
        int z = -9000;
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                // Warm-up walk, not a row: the run's first walk on a cold server moved under a block (0.81 in 20 ticks).
                iwOn(ctx, true, 0);
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                openInventory(ctx);
                keys(ctx, SPRINT);
                ctx.waitTicks(20);
                keys(ctx, Set.of());
                closeScreen(ctx);
                ctx.waitTicks(10);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    String L = "L" + lat + " ";
                    row(rows, ctx, server, hx, x, z, L + "carry, sprint, put down", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(9)));
                        carryWalk(c, h, SPRINT, 20, seen);
                        click(c, List.of(Click.left(19)));
                        return Map.of(9, "", 19, "minecraft:stone*32");
                    });
                    row(rows, ctx, server, hx, x, z, L + "put down in the resume tick", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(9)));
                        carryWalk(c, h, FWD, 12, seen);
                        keys(c, Set.of());
                        c.waitTicks(8);
                        c.runOnClient(mc -> {
                            Mod.setField(IW, "heldForTest", FWD);
                            AbstractContainerScreen<?> scr = (AbstractContainerScreen<?>) McCompat.screen(mc);
                            mc.gameMode.handleContainerInput(scr.getMenu().containerId, 19, 0,
                                    net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                        });
                        return Map.of(9, "", 19, "minecraft:stone*32");
                    });
                    row(rows, ctx, server, hx, x, z, L + "split the carried stack", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(9)));
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.right(19)));
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.left(20)));
                        return Map.of(9, "", 19, "minecraft:stone*1", 20, "minecraft:stone*31");
                    });
                    row(rows, ctx, server, hx, x, z, L + "second pick-up (swap stacks)", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(9)));
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.left(10)));
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.left(19)));
                        return Map.of(9, "", 10, "minecraft:stone*32", 19, "minecraft:dirt*16");
                    });
                    row(rows, ctx, server, hx, x, z, L + "shift-click while carrying", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(13)));
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.shift(10)));
                        carryWalk(c, h, FWD, 6, seen);
                        click(c, List.of(Click.left(19)));
                        return Map.of(13, "", 10, "", 19, "minecraft:stone*5");
                    });
                    row(rows, ctx, server, hx, x, z, L + "shift-click walking", (c, s, h, seen, px, pz) -> {
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.shift(10)));
                        return Map.of(10, "");
                    });
                    row(rows, ctx, server, hx, x, z, L + "hotbar-number swap walking", (c, s, h, seen, px, pz) -> {
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.swap(10, 2)));
                        return Map.of(10, "", 38, "minecraft:dirt*16");
                    });
                    row(rows, ctx, server, hx, x, z, L + "pick up + put down in one tick", (c, s, h, seen, px, pz) -> {
                        carryWalk(c, h, FWD, 10, seen);
                        click(c, List.of(Click.left(9), Click.left(19)));
                        return Map.of(9, "", 19, "minecraft:stone*32");
                    });
                    row(rows, ctx, server, hx, x, z, L + "teleported while carrying", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(9)));
                        carryWalk(c, h, FWD, 8, seen);
                        s.command(String.format(Locale.ROOT, "tp %s %.2f %d %.2f", name(c), px + 0.5, SURFACE_Y, pz + 4.5));
                        carryWalk(c, h, FWD, 12, seen);
                        click(c, List.of(Click.left(19)));
                        return Map.of(9, "", 19, "minecraft:stone*32");
                    });
                    row(rows, ctx, server, hx, x, z, L + "Escape while carrying", (c, s, h, seen, px, pz) -> {
                        click(c, List.of(Click.left(9)));
                        carryWalk(c, h, FWD, 10, seen);
                        closeScreen(c);
                        return Map.of();
                    });
                }
            } finally {
                InvWalkLegitTests.hxOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(nm);
        });
    }

    /** Walks {@code ticks} with {@code keys}, reading the server every 2 ticks: its path and whether it had a carried item. */
    static void carryWalk(ClientGameTestContext ctx, Hx hx, Set<String> keys, int ticks, Seen seen) {
        keys(ctx, keys);
        double[] prev = spos(state(hx));
        for (int i = 0; i < ticks; i += 2) {
            ctx.waitTicks(2);
            JsonObject st = state(hx);
            double[] p = spos(st);
            seen.serverPath += flat(prev, p);
            prev = p;
            if (!st.get("carried").getAsString().isEmpty()) {
                seen.carriedOnServer = true;
            }
        }
    }

    /** Clicks held for the put-down; 0 on a jar before invwalk-1105 (no getter), whose test then reads the server only. */
    static int carryingNow() {
        try {
            return ((Number) Mod.staticCall(IW, "carryingNow")).intValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    static void row(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, String label, Body body) {
        iwOn(ctx, true, 0);
        keys(ctx, Set.of());
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        give(server, ctx);
        openInventory(ctx);
        ctx.waitTicks(3);
        rows.begin();
        hx.call("wire.record", "on", true);
        long since = hx.head();
        Seen seen = new Seen();
        Map<Integer, String> want = body.run(ctx, server, hx, seen, x, z);
        // Keep walking: the put-down's keyless tick, the clicks and the close, then he must walk on.
        Set<String> walkKeys = FWD;
        keys(ctx, walkKeys);
        for (int i = 0; i < 60 && ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "heldNow")).intValue()
                + carryingNow()) > 0; i++) {
            ctx.waitTicks(1);
        }
        boolean screenOpen = ctx.computeOnClient(mc -> McCompat.screen(mc) != null);
        double[] s0 = spos(state(hx));
        ctx.waitTicks(RESUME_TICKS);
        double resumed = flat(s0, spos(state(hx)));
        keys(ctx, Set.of());
        ctx.waitTicks(6);
        JsonObject st = state(hx);
        List<String> server0 = serverSlots(st);
        hx.call("wire.record", "on", false);
        List<JsonObject> wire = hx.events(since, "wire");
        List<JsonObject> clicks = hx.events(since, "container.click");
        List<JsonObject> closes = hx.events(since, "container.close");
        closeScreen(ctx);
        ctx.waitTicks(4);
        JsonObject after = state(hx);
        int openMoves = openMoves(wire);
        int movingClicks = movingClicks(clicks);
        boolean movingClose = closes.stream().anyMatch(InvWalkCampaignTests::moving);
        String slotsBad = "";
        for (Map.Entry<Integer, String> e : want.entrySet()) {
            String have = server0.get(e.getKey());
            if (!have.equals(e.getValue())) {
                slotsBad += e.getKey() + "=" + have + "(want " + e.getValue() + ") ";
            }
        }
        Map<String, Integer> totals = totals(after);
        boolean conserved = totals.getOrDefault("minecraft:stone", 0) == 42 && totals.getOrDefault("minecraft:dirt", 0) == 16
                && totals.getOrDefault("minecraft:oak_log", 0) == 8;
        boolean carriedLeft = !after.get("carried").getAsString().isEmpty();
        // Escape (want empty) leaves the screen, where the test's held keys do not walk him: no walk-on to measure.
        boolean acted = seen.serverPath >= 1.5 && !clicks.isEmpty() && (want.isEmpty() || resumed >= RESUME_MIN) && !seen.carriedOnServer
                && openMoves == 0 && movingClicks == 0 && !movingClose && slotsBad.isEmpty() && conserved && !carriedLeft
                && (want.isEmpty() || screenOpen);
        String detail = String.format(Locale.ROOT, "server walked %.2f carrying (server cursor %s), walked on %.2f; %d click(s), "
                        + "%d close(s) of %s, %d while moving; %d position move(s) while the server had window 0 open; slots %s; "
                        + "items %s%s", seen.serverPath, seen.carriedOnServer ? "HAD THE ITEM" : "empty", resumed, clicks.size(),
                closes.size(), closes.isEmpty() ? "-" : closes.get(0).get("containerId").getAsString(), movingClicks
                        + (movingClose ? 1 : 0), openMoves, slotsBad.isEmpty() ? "right" : "WRONG " + slotsBad,
                conserved ? "conserved" : "LOST " + totals, carriedLeft ? "; CURSOR LEFT ON SERVER" : "");
        rows.end(label, acted, detail, null);
    }

    /** Position-changing moves the server took between a container-0 click and the next close: Hypixel's limbo pattern. */
    static int openMoves(List<JsonObject> wire) {
        boolean open = false;
        double[] last = null;
        int n = 0;
        for (JsonObject e : wire) {
            String k = e.get("kind").getAsString();
            String d = e.get("detail").getAsString();
            if (k.equals("click")) {
                open = d.startsWith("0 ");
            } else if (k.equals("close")) {
                open = false;
            } else if (k.equals("move") && d.startsWith("pos")) {
                String[] t = d.split(" ");
                double[] p = {Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3])};
                if (open && last != null && dist3(last, p) > 0.001) {
                    n++;
                }
                last = p;
            }
        }
        return n;
    }

    /** Every item the server holds for him (inventory and cursor), by id. */
    static Map<String, Integer> totals(JsonObject st) {
        Map<String, Integer> out = new HashMap<>();
        List<String> all = new ArrayList<>();
        for (JsonElement e : st.getAsJsonArray("inventory")) {
            all.add(e.getAsString());
        }
        all.add(st.get("carried").getAsString());
        for (String s : all) {
            int star = s.lastIndexOf('*');
            if (star > 0) {
                out.merge(s.substring(0, star), Integer.parseInt(s.substring(star + 1)), Integer::sum);
            }
        }
        return out;
    }

    // ============================================================================================== 1105-iw-look

    /** MouseHandler.onMove by reflection: (long, x, y, dx, dy) on 26.3, (long, x, y) on 26.2 - the real mouse path. */
    public static void mouseTo(Minecraft mc, double x, double y) {
        try {
            MouseHandler mh = mc.mouseHandler;
            for (Method m : MouseHandler.class.getDeclaredMethods()) {
                if (m.getName().equals("onMove")) {
                    m.setAccessible(true);
                    long w = mc.getWindow().handle();
                    if (m.getParameterCount() == 5) {
                        m.invoke(mh, w, x, y, x - mh.xpos(), y - mh.ypos());
                    } else {
                        m.invoke(mh, w, x, y);
                    }
                    return;
                }
            }
            throw new AssertionError("MouseHandler.onMove not found");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("onMove: " + e);
        }
    }

    /** One mouse move of (dx, dy) window units from where the cursor is, on the client thread. */
    static void nudge(ClientGameTestContext ctx, double dx, double dy) {
        ctx.runOnClient(mc -> mouseTo(mc, mc.mouseHandler.xpos() + dx, mc.mouseHandler.ypos() + dy));
    }

    static float[] look(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
    }

    private static void lookKey(ClientGameTestContext ctx) {
        String nm = "1105-iw-look";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -10500;
        int z = -9000;
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            int keyNat = dev.testkit.compat.McKeys.nat(KEY_V);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                ctx.runOnClient(mc -> Mod.set(IW_CFG, "setLookKeyCode", KEY_V));
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                give(server, ctx);
                openInventory(ctx);
                ctx.waitTicks(3);

                // Held: the move turns him, the cursor and slots stay, no click.
                rows.begin();
                long since = hx.head();
                ctx.getInput().holdKey(keyNat);
                ctx.waitTicks(2);
                double[] cur0 = ctx.computeOnClient(mc -> new double[]{mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
                float[] l0 = look(ctx);
                long looks0 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "looks")).longValue());
                List<String> slots0 = serverSlots(state(hx));
                for (int i = 0; i < 6; i++) {
                    nudge(ctx, 40, 12);
                    ctx.waitTicks(1);
                }
                ctx.waitTicks(4);
                float[] l1 = look(ctx);
                double[] cur1 = ctx.computeOnClient(mc -> new double[]{mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
                JsonObject st = state(hx);
                long looks1 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "looks")).longValue());
                float dyaw = l1[0] - l0[0];
                float dpitch = l1[1] - l0[1];
                float syaw = st.get("yaw").getAsFloat();
                float spitch = st.get("pitch").getAsFloat();
                // The look is a real grab (vanilla accumulates), so the cursor is only put back on release.
                ctx.getInput().releaseKey(keyNat);
                ctx.waitTicks(3);
                double[] cur2 = ctx.computeOnClient(mc -> new double[]{mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
                boolean cursorStill = cur0[0] == cur2[0] && cur0[1] == cur2[1];
                double sens = ctx.computeOnClient(mc -> mc.options.sensitivity().get());
                double scale = Math.pow(sens * 0.6 + 0.2, 3) * 8.0 * 0.15;
                double expYaw = 6 * 40 * scale;
                double expPitch = 6 * 12 * scale;
                boolean feel = Math.abs(dyaw - expYaw) < expYaw * 0.02 && Math.abs(dpitch - expPitch) < expPitch * 0.02;
                int clicks = hx.events(since, "container.click").size();
                boolean slotsSame = slots0.equals(serverSlots(st));
                boolean acted = Math.abs(dyaw) > 5 && dpitch > 1 && Math.abs(syaw - l1[0]) < 0.5 && Math.abs(spitch - l1[1]) < 0.5
                        && cursorStill && clicks == 0 && slotsSame && looks1 - looks0 >= 6;
                rows.begin();
                rows.end("look feel = in-game", feel, String.format(Locale.ROOT, "%.4f deg/count yaw (vanilla %.4f), %.4f pitch "
                        + "(vanilla %.4f), sensitivity %.2f", dyaw / 240, scale, dpitch / 72, scale, sens), null);
                ctx.getInput().holdKey(keyNat);
                ctx.waitTicks(2);
                rows.begin();
                rows.end("look key held, 6 moves", acted, String.format(Locale.ROOT, "client turned yaw %+.2f pitch %+.2f, server "
                                + "yaw %.2f pitch %.2f (client %.2f %.2f), cursor %s, %d click(s), slots %s, %d turn(s)", dyaw, dpitch,
                        syaw, spitch, l1[0], l1[1], cursorStill ? "still" : "MOVED", clicks, slotsSame ? "unchanged" : "CHANGED",
                        looks1 - looks0), null);

                // Pitch stays inside -90..90 however far the mouse goes; yaw is never wrapped.
                rows.begin();
                for (int i = 0; i < 8; i++) {
                    nudge(ctx, 400, 600);
                    ctx.waitTicks(1);
                }
                ctx.waitTicks(4);
                float[] l2 = look(ctx);
                float sp2 = state(hx).get("pitch").getAsFloat();
                rows.end("pitch held to 90, yaw unwrapped", l2[1] <= 90f && l2[1] >= 89f && sp2 <= 90f && Math.abs(l2[0]) > 0,
                        String.format(Locale.ROOT, "client yaw %.2f pitch %.2f, server pitch %.2f", l2[0], l2[1], sp2), null);
                nudge(ctx, 0, -1500);
                ctx.waitTicks(3);

                // Same tick as a slot click (shift-click, standing) and while walking.
                rows.begin();
                since = hx.head();
                ctx.runOnClient(mc -> {
                    mouseTo(mc, mc.mouseHandler.xpos() + 60, mc.mouseHandler.ypos());
                    var m = mc.player.containerMenu;
                    mc.gameMode.handleContainerInput(m.containerId, 10, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE,
                            mc.player);
                });
                ctx.waitTicks(10);
                int c1 = hx.events(since, "container.click").size();
                rows.end("turn + shift-click, same tick", c1 == 1, c1 + " click(s) reached the server", null);

                rows.begin();
                since = hx.head();
                keys(ctx, FWD);
                double[] w0 = spos(state(hx));
                for (int i = 0; i < 16; i++) {
                    nudge(ctx, i % 2 == 0 ? 50 : -20, i % 4 == 0 ? 8 : -8);
                    if (i == 8) {
                        click(ctx, List.of(Click.shift(36)));
                    }
                    ctx.waitTicks(1);
                }
                for (int i = 0; i < 40 && ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "heldNow")).intValue()) > 0; i++) {
                    nudge(ctx, 30, 0);
                    ctx.waitTicks(1);
                }
                ctx.waitTicks(10);
                keys(ctx, Set.of());
                double walked = flat(w0, spos(state(hx)));
                int c2 = hx.events(since, "container.click").size();
                rows.end("turn while walking, with a click", walked > 2 && c2 == 1, String.format(Locale.ROOT,
                        "server walked %.2f, %d click(s)", walked, c2), null);

                // Same tick as the close.
                rows.begin();
                since = hx.head();
                ctx.runOnClient(mc -> {
                    mouseTo(mc, mc.mouseHandler.xpos() + 60, mc.mouseHandler.ypos() + 10);
                    McCompat.screen(mc).onClose();
                });
                ctx.waitTicks(10);
                boolean closed = ctx.computeOnClient(mc -> McCompat.screen(mc) == null);
                rows.end("turn + close, same tick", closed, "screen closed " + closed, null);

                // A chest (a server menu): turning while walking in it.
                rows.begin();
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest", x + 3, SURFACE_Y, z));
                server.sync(5);
                ctx.getInput().releaseKey(keyNat);
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                boolean opened = openBlock(ctx, x + 3, SURFACE_Y, z);
                double[] cw0 = spos(state(hx));
                ctx.getInput().holdKey(keyNat);
                ctx.waitTicks(2);
                float[] c0 = look(ctx);
                keys(ctx, Set.of("key.back"));
                for (int i = 0; i < 12; i++) {
                    nudge(ctx, -45, 5);
                    ctx.waitTicks(1);
                }
                keys(ctx, Set.of());
                ctx.waitTicks(5);
                float[] cl = look(ctx);
                double cw = flat(cw0, spos(state(hx)));
                rows.end("turn while walking in a chest", opened && Math.abs(cl[0] - c0[0]) > 5 && cw > 1, String.format(Locale.ROOT,
                        "chest open %s, yaw %+.2f, server walked %.2f", opened, cl[0] - c0[0], cw), null);
                closeScreen(ctx);
                ctx.waitTicks(5);

                // Released: the same move moves the cursor and not the player.
                rows.begin();
                openInventory(ctx);
                ctx.waitTicks(3);
                ctx.getInput().releaseKey(keyNat);
                ctx.waitTicks(2);
                float[] r0 = look(ctx);
                double[] rc0 = ctx.computeOnClient(mc -> new double[]{mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
                nudge(ctx, 50, 20);
                ctx.waitTicks(3);
                float[] r1 = look(ctx);
                double[] rc1 = ctx.computeOnClient(mc -> new double[]{mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
                boolean back = Math.abs(rc1[0] - rc0[0] - 50) < 0.01 && r1[0] == r0[0] && r1[1] == r0[1];
                rows.end("key released: mouse moves the cursor", back, String.format(Locale.ROOT, "cursor moved %+.1f,%+.1f, "
                        + "yaw %+.2f pitch %+.2f", rc1[0] - rc0[0], rc1[1] - rc0[1], r1[0] - r0[0], r1[1] - r0[1]), null);
            } finally {
                ctx.getInput().releaseKey(keyNat);
                ctx.runOnClient(mc -> Mod.set(IW_CFG, "setLookKeyCode", -1));
                tearDown(ctx, gate);
            }
            rows.finish(nm);
        });
    }
}
