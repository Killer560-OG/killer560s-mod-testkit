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
 * flies forward is held (none reaches the server while it has him moving) and goes out once he lets go and stops.
 * (Terminals while walking are not tested: Hypixel itself closes a terminal on his first movement, his log 21:23:56-21:24:27.)
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
        Scenario.run(ctx, nm, (server, scenario) -> arena(server, x, z), (server, scenario) -> {
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
                }
            } finally {
                server.command("kill @e[type=minecraft:interaction]");
                server.command("gamemode survival " + name(ctx));
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
        tp(server, ctx, x + 0.5, SURFACE_Y + 3, z + 0.5, -90f);
        server.command("gamemode creative " + name(ctx));
        server.sync(5);
        ctx.waitTicks(5);
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
                && (move ? waited > 6 && waited <= 6 + 20 + lat / 50 : waited <= 1 + lat / 50 + 2);
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
}
