package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static dev.testkit.gametest.InvWalkCampaignTests.*;

/**
 * 1108-iw-termwalk (invwalkterm-1108). Inventory Walk + Stop To Click while FLYING (a dungeon ghost hovers and is never
 * on the ground): before the fix a held click never went out (his 26.3 log 2026-11-08 21:19:27-32, a ghost's "Teleport to
 * Player" clicks held 40 ticks at 0.00 blocks/s, then dropped). Rows, each asserted on the server (Hx container.click):
 * a click in a plain server menu and in "Teleport to Player" while he hovers still goes out at once; a click made while he
 * flies forward waits for Stop To Click's one keyless tick (none reaches the server while it has him moving), then goes
 * while he still drifts (air drag keeps a flyer sliding for seconds; GrimAC judges the click on keys and sprint).
 * Hide Keys In Server Menus rows: a terminal (Correct all the panes!) open 5 s while he walks a shuttle (sprint held, which
 * the switch suppresses), a red pane clicked every 15 ticks; alone, with a hotbar swap put into every 3rd tick (SameTick, the
 * wire split into GrimAC's ticks, both must share ticks), and with Hide Keys For 10 ticks. Asserted:
 * he really walked, no input packet with a movement, jump or sprint flag reached the wire while the menu was up (what
 * Hypixel closes a terminal on), every pane click was handled with the server holding no keys and no sprint, the terminal
 * is solved, his real keys reach the server within 3 ticks of the close; setbacks are counted.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class InvWalkTermTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        String nm = "1108-iw-termwalk";
        if (!Mod.isCheat()) {
            Scenario.skipped(nm, "legit jar");
            return;
        }
        int x = -11400;
        int z = -9000;
        Scenario.runExpectingFlags(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Rows rows = new Rows(ctx, server, scenario);
            try (Hx hx = Hx.connect()) {
                iwOn(ctx, true, 0);
                // Warm the first walk (cold path) with the inventory open.
                tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
                openInventory(ctx);
                keys(ctx, Set.of("key.back"));
                ctx.waitTicks(12);
                keys(ctx, Set.of());
                closeScreen(ctx);
                ctx.waitTicks(20);
                for (int lat : latencies()) {
                    latency(ctx, lat);
                    for (int mode = 0; mode < 3; mode++) {
                        flyRow(rows, ctx, server, hx, x, z, lat, mode);
                    }
                    hideRow(rows, ctx, server, hx, x, z, lat, null, 41);
                    hideRow(rows, ctx, server, hx, x, z, lat, SameTick.Act.SWAP, 41);
                    hideRow(rows, ctx, server, hx, x, z, lat, null, 10);
                }
            } finally {
                server.command("kill @e[type=minecraft:interaction]");
                server.command("gamemode survival " + name(ctx));
                ctx.runOnClient(mc -> Mod.set(IW_CFG, "setHideKeysInMenus", false));
                tearDown(ctx, gate);
            }
            rows.finish(nm);
        });
    }

    static boolean openMenu(ClientGameTestContext ctx) {
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(1);
            if (ctx.computeOnClient(mc -> McCompat.screen(mc) instanceof AbstractContainerScreen<?>)) {
                return true;
            }
        }
        return false;
    }

    static boolean movingInput(Input in) {
        return in != null && (in.forward() || in.backward() || in.left() || in.right());
    }

    /** Ticks walked with keys held whose sent input had no movement key (what each held click costs him). */
    static final class Cost {
        int keyless;
        int ticks;
        double path;
        boolean screenAll = true;
        Vec3 prev;

        void tick(ClientGameTestContext ctx, Set<String> keys) {
            ctx.waitTicks(1);
            Object[] r = ctx.computeOnClient(mc -> new Object[]{mc.player.getLastSentInput(), mc.player.position(),
                    McCompat.screen(mc) instanceof AbstractContainerScreen<?>});
            if (!keys.isEmpty()) {
                ticks++;
                if (!movingInput((Input) r[0])) {
                    keyless++;
                }
            }
            Vec3 now = (Vec3) r[1];
            if (prev != null) {
                path += Math.hypot(now.x - prev.x, now.z - prev.z);
            }
            prev = now;
            screenAll &= (Boolean) r[2];
        }

        void walk(ClientGameTestContext ctx, Set<String> keys, int n) {
            keys(ctx, keys);
            for (int i = 0; i < n; i++) {
                tick(ctx, keys);
            }
        }
    }


    /**
     * Flying (creative flight, as a dungeon ghost hovers). mode 0 = a plain server menu, hovering with no keys; 1 =
     * "Teleport to Player", hovering (both must go out at once); 2 = "Teleport to Player" clicked while he flies forward,
     * keys let go 6 ticks later (held while moving, out once he stops).
     */
    private static void flyRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat, int mode) {
        boolean leap = mode > 0;
        boolean move = mode == 2;
        String label = "L" + lat + " flying " + (leap ? "leap menu, " : "server menu, ") + (move ? "moving then stop" : "hovering");
        keys(ctx, Set.of());
        server.command("gamemode creative " + name(ctx));
        server.sync(5);
        ctx.waitTicks(5);
        server.command(String.format(Locale.ROOT, "tp %s %.2f %d %.2f -90 0", name(ctx), x + 0.5, SURFACE_Y + 4, z + 0.5));
        // Under latency the tp lands late: switch flight on once he is up there, before he falls back down.
        for (int i = 0; i < 40 && ctx.computeOnClient(mc -> mc.player.getY() < SURFACE_Y + 3.5); i++) {
            ctx.waitTicks(1);
        }
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        });
        ctx.waitTicks(30);
        rows.begin();
        long gave0 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "gaveUp")).longValue());
        hx.menu(leap ? "Teleport to Player" : "SkyBlock Menu", 4, Map.of(11, "minecraft:player_head", 13, "minecraft:diamond"), null);
        if (!openMenu(ctx)) {
            rows.end(label, false, "the menu never opened", null);
            return;
        }
        ctx.waitTicks(10);
        long since = hx.head();
        Cost cost = new Cost();
        Set<String> k = move ? Set.of("key.forward") : Set.of();
        cost.walk(ctx, k, move ? 10 : 5);
        boolean flying = ctx.computeOnClient(mc -> mc.player.getAbilities().flying && !mc.player.onGround());
        click(ctx, List.of(Click.left(11)));
        int waited = 0;
        List<JsonObject> ev = List.of();
        for (; waited < 60; waited++) {
            ev = hx.events(since, "container.click");
            if (!ev.isEmpty()) {
                break;
            }
            if (move && waited == 6) {
                k = Set.of();
                keys(ctx, k);
            }
            cost.tick(ctx, k);
        }
        long gave = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "gaveUp")).longValue()) - gave0;
        int moving = movingClicks(ev);
        boolean ok = flying && ev.size() == 1 && moving == 0 && gave == 0
                // Moving: Stop To Click's one keyless tick, then the click goes while he still drifts (flying needs no full stop).
                && (move ? cost.keyless >= 1 && waited <= 1 + lat / 50 + 3 : waited <= 1 + lat / 50 + 2);
        String detail = String.format(Locale.ROOT, "flying %s, click handled %d after %d tick(s) (%d while moving), dropped batches %d, "
                + "keyless %d of %d walking tick(s), path %.2f", flying, ev.size(), waited, moving, gave, cost.keyless, cost.ticks, cost.path);
        closeScreen(ctx);
        keys(ctx, Set.of());
        ctx.runOnClient(mc -> {
            mc.player.getAbilities().flying = false;
            mc.player.onUpdateAbilities();
        });
        server.command("gamemode survival " + name(ctx));
        ctx.waitTicks(20);
        rows.end(label, ok, detail, null);
    }

    /** Hide Keys In Server Menus: a terminal open 5 s while he walks (see the class comment). {@code st} null = no same-tick act. */
    private static void hideRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat, SameTick.Act st,
                                int hideTicks) {
        String label = "L" + lat + " hide keys " + (hideTicks >= 41 ? "whole menu" : hideTicks + " ticks") + ", terminal 5 s walking"
                + (st == null ? "" : ", same tick " + st);
        keys(ctx, Set.of());
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        ctx.runOnClient(mc -> {
            Mod.set(IW_CFG, "setHideKeysInMenus", true);
            Mod.set(IW_CFG, "setHideKeysTicks", hideTicks);
        });
        rows.begin();
        hx.call("menu.terminal", "type", "PANES", "seed", 11 + lat + (st == null ? 0 : st.ordinal()), "count", 5,
                "closeOnSolve", false, "interval", 0);
        if (!openMenu(ctx)) {
            rows.end(label, false, "the terminal never opened", null);
            return;
        }
        ctx.waitTicks(3);
        List<Integer> red = ctx.computeOnClient(mc -> {
            List<Integer> out = new ArrayList<>();
            var m = mc.player.containerMenu;
            for (int i = 0; i < m.slots.size() - 36; i++) {
                if (stackText(m.slots.get(i).getItem()).startsWith("minecraft:red_stained_glass_pane")) {
                    out.add(i);
                }
            }
            return out;
        });
        long since = hx.head();
        int[] wireMoving = {0};
        int[] wireInputs = {0};
        boolean[] open = {true};
        int[] afterClose = {-1};
        int[] tickNo = {0};
        StringBuilder closeWire = new StringBuilder();
        java.util.function.Consumer<Packet<?>> prev = dev.testkit.harness.PacketTrace.tap;
        java.util.function.Consumer<Packet<?>> tap = pk -> {
            if (prev != null) {
                prev.accept(pk);
            }
            if (pk instanceof net.minecraft.network.protocol.game.ServerboundPlayerInputPacket in) {
                Input i = in.input();
                boolean mv = i.forward() || i.backward() || i.left() || i.right() || i.jump() || i.sprint();
                if (open[0]) {
                    wireInputs[0]++;
                    if (mv) {
                        wireMoving[0]++;
                    }
                } else {
                    closeWire.append(' ').append(tickNo[0]).append(':').append(mv ? "keys" : "none");
                    if (mv && afterClose[0] < 0) {
                        afterClose[0] = tickNo[0];
                    }
                }
            }
        };
        ctx.runOnClient(mc -> {
            dev.testkit.harness.PacketTrace.tap = tap;
            dev.testkit.harness.PacketTrace.start();
        });
        SameTick same = st == null ? null : ctx.computeOnClient(mc -> SameTick.start(st, 3,
                pk -> pk instanceof ServerboundContainerClickPacket, 1, 2));
        boolean whole = hideTicks >= 41;
        List<Set<String>> segs = List.of(SPRINT, Set.of("key.back", "key.sprint"), Set.of("key.left"), Set.of("key.right"));
        Cost cost = new Cost();
        int clicks = 0;
        boolean masked = true;
        for (int t = 0; t < 100; t++) {
            Set<String> k = segs.get((t / 12) % segs.size());
            keys(ctx, k);
            if (t >= 10 && (t - 10) % 15 == 0 && clicks < red.size()) {
                click(ctx, List.of(Click.left(red.get(clicks++))));
            }
            cost.tick(ctx, k);
            if (whole || t < hideTicks - 4) {
                masked &= ctx.computeOnClient(mc -> (Boolean) Mod.staticCall(IW, "isMasking"));
            }
        }
        if (same != null) {
            ctx.runOnClient(mc -> same.close());
        }
        List<JsonObject> ev = InvWalkCampaignTests.awaitClicks(ctx, hx, since, clicks);
        JsonObject state = hx.call("menu.terminal.state").getAsJsonObject();
        boolean solved = state.has("solved") && state.get("solved").getAsBoolean();
        int moving = movingClicks(ev);
        // Close while still walking: the real keys must reach the server right after.
        Set<String> k = Set.of("key.forward");
        keys(ctx, k);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            open[0] = false;
            tickNo[0] = 0;
        });
        closeScreen(ctx);
        for (int i = 0; i < 6; i++) {
            ctx.waitTicks(1);
            if (i == 0) {
                // Vanilla re-reads the keyboard when a screen closes (KeyMapping.setAll): the test's key is let go, so the
                // server rightly hears "none" first. He keeps walking: press it again.
                keys(ctx, k);
            }
            ctx.runOnClient(mc -> tickNo[0]++);
        }
        keys(ctx, Set.of());
        ctx.runOnClient(mc -> {
            dev.testkit.harness.PacketTrace.stop();
            dev.testkit.harness.PacketTrace.tap = prev;
        });
        int setbacks = dev.testkit.harness.PacketTrace.received("player_position");
        String sameVerdict = same == null ? null : same.verdict("hide keys + " + st, false);
        // Whole menu: no movement key ever on the wire while it is up. A tick limit: the keys come back after it, and the
        // clicks after that wait for Stop To Click's stop (still none handled while the server has him moving).
        boolean ok = masked && cost.screenAll && cost.path >= 3 && (whole ? wireMoving[0] == 0 : wireMoving[0] > 0)
                && clicks == red.size() && !red.isEmpty() && ev.size() >= clicks && moving == 0 && solved
                && afterClose[0] >= 0 && afterClose[0] <= 3 && sameVerdict == null;
        ctx.waitTicks(15);
        List<String> other = rows.scenario.flags().stream().filter(f -> !f.contains("Simulation")).toList();
        if (!other.isEmpty()) {
            ok = false;
        }
        String detail = String.format(Locale.ROOT, "masked %s, walked %.2f (screen up all along %s), wire inputs while open %d (%d "
                        + "with movement/jump/sprint), panes %d clicked %d handled %d (%d while the server had him moving), solved %s "
                        + "(clicks %s wrong %s), real keys on the wire %d tick(s) after the close (inputs after it:%s), setbacks %d%s", masked, cost.path,
                cost.screenAll, wireInputs[0], wireMoving[0], red.size(), clicks, ev.size(), moving, solved, state.get("clicks"),
                state.get("wrong"), afterClose[0], closeWire, setbacks, same == null ? "" : "; " + same.report()
                        + (sameVerdict == null ? "" : " [" + sameVerdict + "]"));
        if (!other.isEmpty()) {
            detail += " [FLAGS OTHER THAN Simulation: " + String.join(" | ", other.subList(0, Math.min(3, other.size()))) + "]";
        }
        ctx.runOnClient(mc -> Mod.set(IW_CFG, "setHideKeysInMenus", false));
        ctx.waitTicks(20);
        // Hiding keys from a server that predicts from them is GrimAC Simulation (and its setbacks): expected, counted.
        rows.end(label, ok, detail, "Simulation");
    }
}
