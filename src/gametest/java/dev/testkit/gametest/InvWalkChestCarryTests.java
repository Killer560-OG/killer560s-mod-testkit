package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.testkit.gametest.InvWalkCampaignTests.*;
import static dev.testkit.gametest.InvWalkCarryTests.Seen;
import static dev.testkit.gametest.InvWalkCarryTests.carryWalk;
import static dev.testkit.gametest.InvWalkCarryTests.carryingNow;
import static dev.testkit.gametest.InvWalkCarryTests.openMoves;
import static dev.testkit.gametest.InvWalkCarryTests.totals;

/**
 * 1108-iw-chestcarry (iwbypass-1108, killer560 2026-11-06: "look into ways to walk while holding items"). Inventory Walk's
 * Carry In Chests: in a chest, large chest, ender chest, barrel or Hypixel storage page a pick-up is held unsent while he
 * walks carrying it, and the put-down goes with it after the full stop, so the SERVER never has an item on its cursor while
 * he moves and never gets a click while he moves. Every row asserts on the server: he walked while carrying, the client
 * really held the carry (the mod's carrying deque), the server cursor stayed empty, no click or close with moving input,
 * the slots ended where the client put them and no item was lost. Rows: carry and put down, put-down in the tick the keys
 * come back (his click in the feature's tick), split and swap of the carried stack, shift-click while carrying, Escape while
 * carrying, a second carry right after the first (second run), a hotbar-number swap walking, an ender chest, a server
 * resync of the cursor while carrying (the unsent clicks are dropped, nothing reaches the server), and in his own
 * inventory a put-down into the craft grid while carrying (before iwbypass-1108 it reached the server AHEAD of its pick-up).
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkChestCarryTests implements FabricClientGameTest {

    interface Body {
        /** Runs the row with the menu open; returns the expected server slots (index -> stack; -1 -> "no clicks"). */
        Map<Integer, String> run(ClientGameTestContext ctx, Hx hx, Seen seen, int c);
    }

    static int maxCarry;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String nm = "1108-iw-chestcarry";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -10400;
        int z = -9000;
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            int[] p = {x + 2, SURFACE_Y, z};
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                // Warm the server's first block use and the first walk (cold paths, see 1027-iw-containers / 1105-iw-carry).
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=west]", p[0], p[1], p[2]));
                server.sync(10);
                ctx.waitTicks(10);
                if (openBlock(ctx, p[0], p[1], p[2])) {
                    keys(ctx, Set.of("key.back"));
                    ctx.waitTicks(12);
                    keys(ctx, Set.of());
                    closeScreen(ctx);
                }
                ctx.waitTicks(20);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    String L = "L" + lat + " ";
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: carry, walk, put down", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        shuttle(c, h, seen);
                        click(c, List.of(Click.left(n + 10)));
                        return Map.of(0, "", n + 10, "minecraft:stone*32");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: put down in the resume tick", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        shuttle(c, h, seen);
                        keys(c, Set.of());
                        c.waitTicks(8);
                        c.runOnClient(mc -> {
                            Mod.setField(IW, "heldForTest", FWD);
                            AbstractContainerScreen<?> scr = (AbstractContainerScreen<?>) McCompat.screen(mc);
                            mc.gameMode.handleContainerInput(scr.getMenu().containerId, n + 10, 0,
                                    net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
                        });
                        return Map.of(0, "", n + 10, "minecraft:stone*32");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: split, swap, put down", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        walk(c, h, Set.of("key.back"), 8, seen);
                        click(c, List.of(Click.right(n + 10)));
                        walk(c, h, Set.of("key.left"), 8, seen);
                        click(c, List.of(Click.left(1)));
                        walk(c, h, Set.of("key.right"), 8, seen);
                        click(c, List.of(Click.left(n + 11)));
                        return Map.of(0, "", 1, "minecraft:stone*31", n + 10, "minecraft:stone*1", n + 11, "minecraft:dirt*16");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: shift-click while carrying", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        walk(c, h, Set.of("key.back"), 8, seen);
                        click(c, List.of(Click.shift(1)));
                        walk(c, h, Set.of("key.left"), 8, seen);
                        click(c, List.of(Click.left(n + 10)));
                        return Map.of(0, "", 1, "", n + 10, "minecraft:stone*32");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: second carry right after", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        walk(c, h, Set.of("key.back"), 8, seen);
                        click(c, List.of(Click.left(n + 10), Click.left(n + 10)));
                        walk(c, h, Set.of("key.left"), 8, seen);
                        click(c, List.of(Click.left(2)));
                        return Map.of(0, "", 2, "minecraft:stone*32", n + 10, "");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: Escape while carrying", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        shuttle(c, h, seen);
                        closeScreen(c);
                        return Map.of();
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: server resync while carrying", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        walk(c, h, Set.of("key.back"), 8, seen);
                        // The server resends the menu with an empty cursor (a resync, as after a refused click): the unsent
                        // pick-up must be dropped, never sent later against a cursor the server does not have.
                        c.runOnClient(mc -> {
                            AbstractContainerMenu m = mc.player.containerMenu;
                            List<ItemStack> items = new ArrayList<>();
                            for (ItemStack s : m.getItems()) {
                                items.add(s.copy());
                            }
                            items.set(0, m.getCarried().copy());
                            m.initializeContents(m.incrementStateId(), items, ItemStack.EMPTY);
                        });
                        walk(c, h, Set.of("key.left"), 8, seen);
                        return Map.of(-1, "no clicks", 0, "minecraft:stone*32");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "chest", L + "chest: hotbar-number swap walking", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(0)));
                        walk(c, h, Set.of("key.back"), 8, seen);
                        click(c, List.of(Click.left(n + 10)));
                        walk(c, h, Set.of("key.left"), 8, seen);
                        click(c, List.of(Click.swap(1, 2)));
                        return Map.of(0, "", 1, "", n + 10, "minecraft:stone*32", n + 29, "minecraft:dirt*16");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, "ender", L + "ender chest: carry, walk, put down", (c, h, seen, n) -> {
                        click(c, List.of(Click.left(1)));
                        shuttle(c, h, seen);
                        click(c, List.of(Click.left(n + 10)));
                        return Map.of(1, "", n + 10, "minecraft:dirt*16");
                    });
                    chestRow(rows, ctx, server, hx, x, z, p, null, L + "inventory: put down into the craft grid while carrying",
                            (c, h, seen, n) -> {
                                click(c, List.of(Click.left(9)));
                                walk(c, h, Set.of("key.back"), 8, seen);
                                click(c, List.of(Click.right(1)));
                                walk(c, h, Set.of("key.left"), 8, seen);
                                click(c, List.of(Click.left(19)));
                                return Map.of(9, "", 1, "minecraft:stone*1", 19, "minecraft:stone*31");
                            });
                }
            } finally {
                ctx.runOnClient(mc -> {
                    try {
                        Mod.set(IW_CFG, "setCarryInChests", false);
                    } catch (Throwable ignored) {
                    }
                });
                InvWalkLegitTests.hxOff(ctx);
                tearDown(ctx, gate);
            }
            rows.finish(nm);
        });
    }

    /** Back, left, right, forward: he walks about 4 blocks and stays in the chest's reach. */
    static void shuttle(ClientGameTestContext ctx, Hx hx, Seen seen) {
        walk(ctx, hx, Set.of("key.back"), 6, seen);
        walk(ctx, hx, Set.of("key.left"), 6, seen);
        walk(ctx, hx, Set.of("key.right"), 6, seen);
        walk(ctx, hx, FWD, 6, seen);
    }

    static void walk(ClientGameTestContext ctx, Hx hx, Set<String> keys, int ticks, Seen seen) {
        carryWalk(ctx, hx, keys, ticks, seen);
        noteCarry(ctx);
    }

    static void noteCarry(ClientGameTestContext ctx) {
        maxCarry = Math.max(maxCarry, ctx.computeOnClient(mc -> carryingNow()));
    }

    /**
     * One row: {@code box} "chest" or "ender" (built at {@code p}, slot 0 stone*32, slot 1 dirt*16), null for his own
     * inventory. Carry In Chests is switched on by reflection; on a jar without it the row still runs and must fail.
     */
    static void chestRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int[] p, String box,
                         String label, Body body) {
        chestRow(rows, ctx, server, hx, x, z, p, box, label, body, null);
    }

    /** As above; {@code after} runs before the row is judged and returns null or why the row failed (its text is noted). */
    static void chestRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int[] p, String box,
                         String label, Body body, java.util.function.Supplier<String[]> extra) {
        iwOn(ctx, true, 0);
        boolean setting = ctx.computeOnClient(mc -> {
            try {
                Mod.set(IW_CFG, "setCarryInChests", true);
                return true;
            } catch (Throwable t) {
                return false;
            }
        });
        keys(ctx, Set.of());
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        give(server, ctx);
        String n = name(ctx);
        if (box != null) {
            server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", p[0], p[1], p[2]));
            if (box.equals("ender")) {
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:ender_chest[facing=west]", p[0], p[1], p[2]));
                server.command("item replace entity " + n + " enderchest.0 with minecraft:stone 32");
                server.command("item replace entity " + n + " enderchest.1 with minecraft:dirt 16");
            } else {
                server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:chest[facing=west]", p[0], p[1], p[2]));
                server.command(String.format(Locale.ROOT, "item replace block %d %d %d container.0 with minecraft:stone 32", p[0], p[1], p[2]));
                server.command(String.format(Locale.ROOT, "item replace block %d %d %d container.1 with minecraft:dirt 16", p[0], p[1], p[2]));
            }
            server.sync(10);
            ctx.waitTicks(10);
        }
        rows.begin();
        boolean opened;
        if (box == null) {
            openInventory(ctx);
            opened = true;
        } else {
            opened = openBlock(ctx, p[0], p[1], p[2]);
        }
        if (!opened) {
            rows.end(label, false, "the container never opened", null);
            return;
        }
        ctx.waitTicks(3);
        int c = ctx.computeOnClient(mc -> mc.player.containerMenu.slots.size()) - 36;
        hx.call("wire.record", "on", true);
        long since = hx.head();
        Seen seen = new Seen();
        maxCarry = 0;
        Map<Integer, String> want = body.run(ctx, hx, seen, box == null ? 9 : c);
        noteCarry(ctx);
        boolean escape = want.isEmpty();
        boolean noClicks = want.containsKey(-1);
        keys(ctx, FWD);
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
        int movingClicks = movingClicks(clicks);
        boolean movingClose = closes.stream().anyMatch(InvWalkCampaignTests::moving);
        String slotsBad = "";
        for (Map.Entry<Integer, String> e : want.entrySet()) {
            if (e.getKey() < 0) {
                continue;
            }
            String have = server0.get(e.getKey());
            if (!have.equals(e.getValue())) {
                slotsBad += e.getKey() + "=" + have + "(want " + e.getValue() + ") ";
            }
        }
        // Items: a chest row counts the open menu (chest + inventory) and the cursor; Escape and the inventory row count his
        // inventory after the close (the server hands a carried item and the craft grid back to it).
        Map<String, Integer> tot;
        int stone;
        int dirt;
        if (box != null && !escape) {
            tot = new HashMap<>();
            for (String s : server0) {
                add(tot, s);
            }
            add(tot, st.get("carried").getAsString());
            stone = 74;
            dirt = 32;
        } else {
            tot = totals(after);
            stone = box != null ? 74 : 42;
            dirt = 16;
        }
        boolean conserved = tot.getOrDefault("minecraft:stone", 0) == stone && tot.getOrDefault("minecraft:dirt", 0) == dirt
                && tot.getOrDefault("minecraft:oak_log", 0) == 8;
        boolean carriedLeft = !after.get("carried").getAsString().isEmpty();
        // Own inventory with a filled craft grid has no close (it would hand the grid back), so window-0 moves are vanilla's.
        int openMoves = box == null ? 0 : openMoves(wire);
        boolean acted = setting && maxCarry > 0 && seen.serverPath >= 1.5 && (noClicks ? clicks.isEmpty() : !clicks.isEmpty())
                && (escape || resumed >= RESUME_MIN) && !seen.carriedOnServer && movingClicks == 0 && !movingClose
                && openMoves == 0 && slotsBad.isEmpty() && conserved && !carriedLeft && (escape || screenOpen);
        String detail = String.format(Locale.ROOT, "setting %s, carried %d click(s) unsent; server walked %.2f carrying (server "
                        + "cursor %s), walked on %.2f; %d click(s), %d close(s), %d while moving; slots %s; items %s%s",
                setting ? "on" : "MISSING", maxCarry, seen.serverPath, seen.carriedOnServer ? "HAD THE ITEM" : "empty", resumed,
                clicks.size(), closes.size(), movingClicks + (movingClose ? 1 : 0),
                slotsBad.isEmpty() ? "right" : "WRONG " + slotsBad, conserved ? "conserved" : "LOST " + tot,
                carriedLeft ? "; CURSOR LEFT ON SERVER" : "");
        if (extra != null) {
            // [0] null or why it failed, [1] the note.
            String[] a = extra.get();
            acted &= a[0] == null;
            detail += "; " + a[1] + (a[0] == null ? "" : " FAILED: " + a[0]);
        }
        rows.end(label, acted, detail, null);
        ctx.waitTicks(5);
    }

    private static void add(Map<String, Integer> tot, String s) {
        int star = s.lastIndexOf('*');
        if (star > 0) {
            tot.merge(s.substring(0, star), Integer.parseInt(s.substring(star + 1)), Integer::sum);
        }
    }
}
