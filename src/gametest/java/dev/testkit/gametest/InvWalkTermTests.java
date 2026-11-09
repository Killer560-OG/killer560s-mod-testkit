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
 * Stand Still In Server Menus rows (invwalkclean-1109, see stillRow): whole menu, 40 and 10 ticks, and the same-tick acts;
 * every row must be clean on both GrimAC configs.
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
                    stillRow(rows, ctx, server, hx, x, z, lat, null, 41);
                    stillRow(rows, ctx, server, hx, x, z, lat, null, 40);
                    stillRow(rows, ctx, server, hx, x, z, lat, null, 10);
                    for (SameTick.Act st : List.of(SameTick.Act.SWAP, SameTick.Act.ATTACK, SameTick.Act.USE, SameTick.Act.SCREEN_CLICK)) {
                        stillRow(rows, ctx, server, hx, x, z, lat, st, 41);
                    }
                    stillRow(rows, ctx, server, hx, x, z, lat, SameTick.Act.SWAP, 10);
                }
            } finally {
                server.command("kill @e[type=minecraft:interaction]");
                server.command("gamemode survival " + name(ctx));
                ctx.runOnClient(mc -> Mod.set(IW_CFG, "setStillInMenus", false));
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

    /**
     * Stand Still In Server Menus (invwalkclean-1109): he runs (sprint) with his inventory open, a terminal (Correct all the
     * panes!) opens on top while he still holds his keys, and he keeps holding a shuttle of keys for 5 s with a red pane
     * clicked every 15 ticks. The mod really lets his keys go (vanilla then sends an honest keyless input and his momentum
     * decays as GrimAC predicts), so this must be clean on both Grim configs. Asserted on the wire: it acted (stand-still
     * ticks counted, he held keys the whole time), no input with a movement, jump or sprint flag went out while it stood him
     * still (whole menu: none while the menu was up), a {@code stillTicks} window walks on after it (path, movement inputs),
     * every pane click handled with the server holding no keys, the terminal solved; {@code st}: his act in the same tick.
     */
    private static void stillRow(Rows rows, ClientGameTestContext ctx, TestServer server, Hx hx, int x, int z, int lat, SameTick.Act st,
                                 int stillTicks) {
        boolean whole = stillTicks >= 41;
        String label = "L" + lat + " stand still " + (whole ? "whole menu" : stillTicks + " ticks") + ", terminal opened running"
                + (st == null ? "" : ", same tick " + st);
        keys(ctx, Set.of());
        tp(server, ctx, x + 0.5, SURFACE_Y, z + 0.5, -90f);
        ctx.runOnClient(mc -> {
            Mod.set(IW_CFG, "setStillInMenus", true);
            Mod.set(IW_CFG, "setStillTicks", stillTicks);
        });
        rows.begin();
        long still0 = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "standStillTicks")).longValue());
        // Running when the terminal opens: walk with the inventory open first (own inventory, Inventory Walk moves him).
        openInventory(ctx);
        keys(ctx, SPRINT);
        ctx.waitTicks(15);
        int[] wireMoving = {0};
        int[] wireStillMoving = {0};
        int[] wireStill = {0};
        int[] wireInputs = {0};
        boolean[] open = {true};
        java.util.function.Consumer<Packet<?>> prev = dev.testkit.harness.PacketTrace.tap;
        java.util.function.Consumer<Packet<?>> tap = pk -> {
            if (prev != null) {
                prev.accept(pk);
            }
            if (open[0] && pk instanceof net.minecraft.network.protocol.game.ServerboundPlayerInputPacket in
                    && McCompat.screen(net.minecraft.client.Minecraft.getInstance()) instanceof AbstractContainerScreen<?> s
                    && s.getMenu().containerId != 0) {
                Input i = in.input();
                boolean mv = i.forward() || i.backward() || i.left() || i.right() || i.jump() || i.sprint();
                boolean still = (Boolean) Mod.staticCall(IW, "isStandingStill");
                wireInputs[0]++;
                wireMoving[0] += mv ? 1 : 0;
                wireStill[0] += still ? 1 : 0;
                wireStillMoving[0] += still && mv ? 1 : 0;
            }
        };
        ctx.runOnClient(mc -> {
            dev.testkit.harness.PacketTrace.tap = tap;
            dev.testkit.harness.PacketTrace.start();
        });
        double speed0 = ctx.computeOnClient(mc -> Math.hypot(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z));
        hx.call("menu.terminal", "type", "PANES", "seed", 31 + lat + (st == null ? 0 : st.ordinal()) + stillTicks, "count", 5,
                "closeOnSolve", false, "interval", 0);
        if (!openMenu(ctx)) {
            keys(ctx, Set.of());
            closeScreen(ctx);
            ctx.runOnClient(mc -> {
                dev.testkit.harness.PacketTrace.stop();
                dev.testkit.harness.PacketTrace.tap = prev;
            });
            rows.end(label, false, "the terminal never opened", null);
            return;
        }
        Vec3 at = ctx.computeOnClient(mc -> mc.player.position());
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
        double slid = ctx.computeOnClient(mc -> Math.hypot(mc.player.getX() - at.x, mc.player.getZ() - at.z));
        long since = hx.head();
        SameTick same = st == null ? null : ctx.computeOnClient(mc -> SameTick.start(st, 3,
                pk -> pk instanceof ServerboundContainerClickPacket, 1, 2));
        List<Set<String>> segs = List.of(SPRINT, Set.of("key.back", "key.sprint"), Set.of("key.left"), Set.of("key.right"));
        Cost cost = new Cost();
        int clicks = 0;
        for (int t = 0; t < 100; t++) {
            Set<String> k = segs.get((t / 12) % segs.size());
            keys(ctx, k);
            if (t >= 10 && (t - 10) % 15 == 0 && clicks < red.size()) {
                click(ctx, List.of(Click.left(red.get(clicks++))));
            }
            cost.tick(ctx, k);
        }
        if (same != null) {
            ctx.runOnClient(mc -> same.close());
        }
        List<JsonObject> ev = InvWalkCampaignTests.awaitClicks(ctx, hx, since, clicks);
        JsonObject state = hx.call("menu.terminal.state").getAsJsonObject();
        boolean solved = state.has("solved") && state.get("solved").getAsBoolean();
        int moving = movingClicks(ev);
        open[0] = false;
        long still = ctx.computeOnClient(mc -> ((Number) Mod.staticCall(IW, "standStillTicks")).longValue()) - still0;
        keys(ctx, Set.of());
        closeScreen(ctx);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            dev.testkit.harness.PacketTrace.stop();
            dev.testkit.harness.PacketTrace.tap = prev;
        });
        String sameVerdict = same == null ? null : same.verdict("stand still + " + st);
        int setbacks = dev.testkit.harness.PacketTrace.received("player_position");
        // Acted: stood him still for its window although he held keys all along, never sent a key while it did, and (a
        // tick window) let him walk on after it. Whole menu: no movement key reached the server while the menu was up.
        boolean acted = speed0 > 0.1 && still >= Math.min(stillTicks, 100) && wireStill[0] >= 1 && wireStillMoving[0] == 0
                && (whole ? wireMoving[0] == 0 && cost.path < 1.5 : wireMoving[0] > 0 && cost.path >= 3);
        boolean ok = acted && clicks == red.size() && !red.isEmpty() && ev.size() >= clicks && moving == 0 && solved
                && sameVerdict == null && setbacks == 0;
        String detail = String.format(Locale.ROOT, "speed at open %.3f, slid %.2f in 3 ticks, stood still %d tick(s), walked %.2f after "
                        + "(screen up all along %s), wire inputs in the menu %d (%d with movement/jump/sprint, %d while standing still, %d of "
                        + "them with keys), panes %d clicked %d handled %d (%d while the server had him moving), solved %s (clicks %s "
                        + "wrong %s), setbacks %d%s", speed0, slid, still, cost.path, cost.screenAll, wireInputs[0], wireMoving[0],
                wireStill[0], wireStillMoving[0], red.size(), clicks, ev.size(), moving, solved, state.get("clicks"), state.get("wrong"),
                setbacks, same == null ? "" : "; " + same.report() + (sameVerdict == null ? "" : " [" + sameVerdict + "]"));
        ctx.runOnClient(mc -> Mod.set(IW_CFG, "setStillInMenus", false));
        ctx.waitTicks(20);
        rows.end(label, ok, detail, null);
    }
}
