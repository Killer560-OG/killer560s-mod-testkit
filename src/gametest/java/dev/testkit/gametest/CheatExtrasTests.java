package dev.testkit.gametest;

import com.google.gson.JsonObject;
import dev.testkit.compat.McCompat;
import dev.testkit.gametest.hx.Hx;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 1025-lcew-* and 1025-fullblock-* (mod cheat-extras-1025, killer560 2026-10-25, reference-mods-1018 items 2 and 3).
 *
 * <p><b>Left Click Etherwarp</b>: with an etherwarp item in hand a left click is an etherwarp - sneak in that tick's input,
 * the right click the next tick (at once when the server already has the sneak), sneak back up, and no attack, swing or
 * dig. Every case runs the server's real etherwarp (hx/dungeon/HxAbilities) and proves the server warped him before
 * quoting Grim; the packet order is read from every serverbound packet (PacketTrace.tap). A control shows that with the
 * feature off the same left click digs and swings and nothing warps.
 *
 * <p><b>Full Block</b> (the mod's existing secret hitbox feature, Secrets folder, cheat build; OdinClient calls it Secret
 * Hitboxes): a vanilla right click aimed where only the widened shape is - the crosshair misses the real lever/chest with
 * Full Block off and hits it with it on (both checked) - must flip the lever / open the chest on the server with Grim
 * clean, the hit point outside the real shape measured and logged. 62-full-block-reach records the same through Secret
 * Triggerbot; these click by hand and require clean.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CheatExtrasTests implements FabricClientGameTest {

    private static final String LC_CFG = "leftclickew.LeftClickEtherwarpConfig";
    private static final String LC = "leftclickew.LeftClickEtherwarp";
    private static final String ZP_CFG = "zpew.ZeroPingEtherwarpConfig";
    private static final String ZP = "zpew.ZeroPingEtherwarp";
    private static final String SECRETS = "secrets.SecretsConfig";
    private static final String DUNGEON_LABEL = "localhost.p3sim.net:25565";
    private static final int SURFACE_Y = 151;
    private static final int Z = -3200;
    private static final String AOTV = "minecraft:diamond_shovel[custom_data={id:\"ASPECT_OF_THE_VOID\",ethermerge:1,"
            + "tuned_transmission:4},unbreakable={}]";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        AssertionError first = null;
        for (Consumer<ClientGameTestContext> c : List.<Consumer<ClientGameTestContext>>of(
                CheatExtrasTests::lcSetting, CheatExtrasTests::lcControl, CheatExtrasTests::lcWarp,
                CheatExtrasTests::lcNear, CheatExtrasTests::lcSneaking, CheatExtrasTests::lcZpew,
                CheatExtrasTests::fbLever, CheatExtrasTests::fbChest)) {
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

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static void check(boolean ok, String why) {
        if (!ok) {
            throw new AssertionError(why);
        }
    }

    private static String fmt(Vec3 p) {
        return p == null ? "-" : String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", p.x, p.y, p.z);
    }

    private static long num(ClientGameTestContext ctx, String cls, String getter) {
        return ctx.computeOnClient(mc -> ((Number) Mod.staticCall(cls, getter)).longValue());
    }

    private static boolean gateOff(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            boolean gate = (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled");
            Mod.staticCall("util.SkyblockGate", "setEnabled", false);
            return gate;
        });
    }

    private static void gateBack(ClientGameTestContext ctx, boolean gate) {
        ctx.runOnClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gate));
    }

    private static void arena(TestServer server, int x, int z, int radius) {
        TestMap.on(server)
                .platform(x, SURFACE_Y - 1, z, radius)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .spawn(x + 0.5, z + 0.5, -90f)
                .build();
    }

    /** Every serverbound packet in order: its name, the input packet with its sneak bit, a dig with its action. */
    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private static void startEvents(ClientGameTestContext ctx) {
        EVENTS.clear();
        ctx.runOnClient(mc -> {
            PacketTrace.start();
            PacketTrace.tap = CheatExtrasTests::onPacket;
        });
    }

    private static List<String> stopEvents(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            PacketTrace.tap = null;
            PacketTrace.stop();
        });
        synchronized (EVENTS) {
            return List.copyOf(EVENTS);
        }
    }

    private static void onPacket(Packet<?> p) {
        String name = p.type().id().getPath();
        if (p instanceof ServerboundPlayerInputPacket in) {
            name = "player_input:" + (in.input().shift() ? "SNEAK" : "up");
        } else if (p instanceof ServerboundPlayerActionPacket a) {
            name = "player_action:" + a.getAction();
        }
        if (name.startsWith("move_player") || name.equals("keep_alive") || name.equals("pong")
                || name.equals("chunk_batch_received") || name.equals("accept_teleportation")) {
            return; // noise for these assertions; Grim reads them all anyway
        }
        EVENTS.add(name);
    }

    /** The events around the first use_item, for the log. */
    private static String around(List<String> ev, int index, int before, int after) {
        int a = Math.max(0, index - before);
        int b = Math.min(ev.size(), index + after + 1);
        return String.join(" ", ev.subList(a, b));
    }

    private static int count(List<String> ev, String prefix) {
        int n = 0;
        for (String e : ev) {
            if (e.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** The yaw/pitch from the sneaking eye at {@code feet} to the top center of block (bx, SURFACE_Y-1, bz). */
    private static float[] aimAt(Vec3 feet, int bx, int bz) {
        double dx = bx + 0.5 - feet.x;
        double dy = SURFACE_Y - (feet.y + 1.27);
        double dz = bz + 0.5 - feet.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    /** Turns him (a client-side turn the next movement packets report) and waits for it to be heard. */
    private static void look(ClientGameTestContext ctx, float yaw, float pitch) {
        ctx.runOnClient(mc -> {
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
        });
        ctx.waitTicks(5);
    }

    private static Hx lcSetUp(ClientGameTestContext ctx, boolean on, boolean zpew) {
        Hx hx = Hx.connect();
        hx.call("dungeon.abilities", "enabled", true);
        hx.give(0, AOTV);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            Mod.set(LC_CFG, "setEnabled", false);
            Mod.set(ZP_CFG, "setEnabled", false);
        });
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            Mod.set(LC_CFG, "setEnabled", on);
            if (zpew) {
                Mod.set(ZP_CFG, "setEnabled", true);
                Mod.set(ZP_CFG, "setChain", true);
                Mod.set(ZP_CFG, "setTimeoutMs", 1500);
            }
        });
        return hx;
    }

    private static void lcTearDown(ClientGameTestContext ctx, Hx hx) {
        ctx.getInput().releaseKey(o -> o.keyShift);
        ctx.runOnClient(mc -> {
            PacketTrace.tap = null;
            Latency.remove();
            Mod.set(LC_CFG, "setEnabled", false);
            Mod.set(ZP_CFG, "setEnabled", false);
        });
        if (hx != null) {
            hx.call("dungeon.abilities", "enabled", false);
            hx.close();
        }
        ctx.waitTicks(20);
    }

    private static int warps(Hx hx) {
        JsonObject stats = hx.call("dungeon.stats").getAsJsonObject();
        return stats.get("etherwarps").getAsInt();
    }

    // ---- Left Click Etherwarp -------------------------------------------------------------------------------

    /** 1025-lcew-setting: OFF by default, the setting saved and loaded from disk. */
    private static void lcSetting(ClientGameTestContext ctx) {
        Scenario.run(ctx, "1025-lcew-setting", (server, scenario) -> arena(server, -5000, Z, 8), (server, scenario) -> {
            String[] got = ctx.computeOnClient(mc -> {
                Mod.staticCall(LC_CFG, "load");
                String defaults = String.valueOf(Mod.call(Mod.cfg(LC_CFG), "isEnabled"));
                Mod.set(LC_CFG, "setEnabled", true);
                Mod.call(Mod.cfg(LC_CFG), "save");
                Mod.set(LC_CFG, "setEnabled", false);
                Mod.staticCall(LC_CFG, "load");
                String loaded = String.valueOf(Mod.call(Mod.cfg(LC_CFG), "isEnabled"));
                Mod.set(LC_CFG, "setEnabled", false);
                Mod.call(Mod.cfg(LC_CFG), "save");
                return new String[]{defaults, loaded};
            });
            scenario.log("left click etherwarp default " + got[0] + "; loaded " + got[1]);
            check(got[0].equals("false"), "default is not OFF: " + got[0]);
            check(got[1].equals("true"), "the setting did not come back from disk: " + got[1]);
        });
    }

    /** 1025-lcew-control: feature OFF - a left click on the floor in reach digs and swings, and nothing warps. */
    private static void lcControl(ClientGameTestContext ctx) {
        int x = -5050;
        Scenario.run(ctx, "1025-lcew-control", (server, scenario) -> arena(server, x, Z, 20), (server, scenario) -> {
            boolean gate = gateOff(ctx);
            Hx hx = null;
            try {
                hx = lcSetUp(ctx, false, false);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                float[] aim = aimAt(start, x + 3, Z);
                look(ctx, aim[0], aim[1]);
                int w0 = warps(hx);
                startEvents(ctx);
                ctx.getInput().pressKey(o -> o.keyAttack);
                ctx.waitTicks(20);
                List<String> ev = stopEvents(ctx);
                int warps = warps(hx) - w0;
                int digs = count(ev, "player_action:START_DESTROY_BLOCK");
                int swings = count(ev, "swing");
                scenario.log("control: " + digs + " dig start(s), " + swings + " swing(s), " + warps + " warp(s); "
                        + String.join(" ", ev.subList(0, Math.min(ev.size(), 30))));
                check(digs >= 1 && swings >= 1, "with the feature off the left click did not dig and swing: the premise is"
                        + " not there");
                check(warps == 0, "the server warped him with the feature off");
            } finally {
                lcTearDown(ctx, hx);
                gateBack(ctx, gate);
            }
        });
    }

    /**
     * One left click with the AOTV, standing (not sneaking), aimed 16 blocks off: returns the packet events. Asserts the
     * server warped him exactly once onto the block and nothing attacked, dug or swung.
     */
    private static List<String> leftClickWarp(ClientGameTestContext ctx, Scenario scenario, Hx hx, int tx, int tz,
                                             boolean holdSneak) {
        if (holdSneak) {
            ctx.getInput().holdKey(o -> o.keyShift);
            ctx.waitTicks(10);
        }
        Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
        float[] aim = aimAt(start, tx, tz);
        look(ctx, aim[0], aim[1]);
        int w0 = warps(hx);
        long lcw0 = num(ctx, LC, "warps");
        long same0 = num(ctx, LC, "sameTick");
        long next0 = num(ctx, LC, "nextTick");
        startEvents(ctx);
        ctx.getInput().pressKey(o -> o.keyAttack);
        ctx.waitTicks(30);
        List<String> ev = stopEvents(ctx);
        int warps = warps(hx) - w0;
        long modWarps = num(ctx, LC, "warps") - lcw0;
        long same = num(ctx, LC, "sameTick") - same0;
        long next = num(ctx, LC, "nextTick") - next0;
        long wait = num(ctx, LC, "lastWaitTicks");
        Vec3 end = ctx.computeOnClient(mc -> mc.player.position());
        Vec3 want = new Vec3(tx + 0.5, SURFACE_Y + 0.05, tz + 0.5);
        int use = ev.indexOf("use_item");
        scenario.log(String.format(Locale.ROOT, "left click: mod sent %d warp(s) (%d same tick, %d next tick, waited %d), "
                        + "server warped %d; %s -> %s (wanted %s, %.2f off); packets around the use: %s", modWarps, same,
                next, wait, warps, fmt(start), fmt(end), fmt(want), end.distanceTo(want),
                use < 0 ? String.join(" ", ev.subList(0, Math.min(ev.size(), 40))) : around(ev, use, 8, 6)));
        check(modWarps == 1, "the mod did not turn the left click into one etherwarp: " + modWarps);
        check(warps == 1, "the server did not etherwarp exactly once: " + warps);
        check(end.distanceTo(new Vec3(want.x, end.y, want.z)) < 0.5, "he did not land on the aimed block");
        check(count(ev, "player_action:START_DESTROY_BLOCK") == 0, "a dig went out with the left click");
        check(count(ev, "swing") == 0, "a swing went out with the left click");
        check(count(ev, "interact") == 0, "an attack went out with the left click");
        check(PacketTrace.sentOutsideTick().isEmpty(), "sent outside a tick: " + PacketTrace.sentOutsideTick());
        if (holdSneak) {
            check(same == 1, "already sneaking, the warp did not go on the click's own tick");
        } else {
            check(next == 1 && wait == 1, "not sneaking, the warp did not go exactly one tick after the click (next "
                    + next + ", waited " + wait + ")");
        }
        return ev;
    }

    /** Checks the sneak went to the server in an input packet one tick before the use, and came back up after it. */
    private static void checkSneakOrder(Scenario scenario, List<String> ev) {
        int use = ev.indexOf("use_item");
        check(use >= 0, "no use_item went out");
        int sneak = -1;
        for (int i = use - 1; i >= 0; i--) {
            if (ev.get(i).startsWith("player_input:")) {
                sneak = ev.get(i).equals("player_input:SNEAK") ? i : -1;
                break;
            }
        }
        check(sneak >= 0, "the last input packet before the use did not say sneak");
        int ends = 0;
        for (int i = sneak + 1; i < use; i++) {
            if (ev.get(i).equals("client_tick_end")) {
                ends++;
            }
        }
        check(ends == 1, "the use was not exactly one tick after the sneak's input packet (" + ends + " tick end(s))");
        int up = -1;
        for (int i = use + 1; i < ev.size(); i++) {
            if (ev.get(i).startsWith("player_input:")) {
                up = ev.get(i).equals("player_input:up") ? i : -1;
                break;
            }
        }
        check(up >= 0, "sneak did not come back up after the use");
        int endsAfter = 0;
        for (int i = use + 1; i < up; i++) {
            if (ev.get(i).equals("client_tick_end")) {
                endsAfter++;
            }
        }
        scenario.log("sneak input " + (use - sneak) + " packet(s) before the use (1 tick), released " + endsAfter
                + " tick(s) after it");
        check(endsAfter == 0, "sneak was held past the use's own tick (" + endsAfter + " tick(s))");
    }

    /** 1025-lcew-warp: not sneaking, a left click 16 blocks off - sneak, warp the next tick, sneak up. */
    private static void lcWarp(ClientGameTestContext ctx) {
        int x = -5100;
        Scenario.run(ctx, "1025-lcew-warp", (server, scenario) -> arena(server, x, Z, 30), (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            boolean gate = gateOff(ctx);
            Hx hx = null;
            try {
                hx = lcSetUp(ctx, true, false);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                List<String> ev = leftClickWarp(ctx, scenario, hx, (int) Math.floor(start.x) + 16, (int) Math.floor(start.z),
                        false);
                checkSneakOrder(scenario, ev);
                check(ev.indexOf("use_item_on") < 0, "a block click went out for a warp aimed out of reach");
                boolean upNow = ctx.computeOnClient(mc -> !mc.player.getLastSentInput().shift());
                check(upNow, "the server still has him sneaking");
            } finally {
                lcTearDown(ctx, hx);
                gateBack(ctx, gate);
            }
        });
    }

    /** 1025-lcew-near: aimed at the floor 3 blocks off (in reach) - the right click's block half goes first, no dig. */
    private static void lcNear(ClientGameTestContext ctx) {
        int x = -5200;
        Scenario.run(ctx, "1025-lcew-near", (server, scenario) -> arena(server, x, Z, 20), (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            boolean gate = gateOff(ctx);
            Hx hx = null;
            try {
                hx = lcSetUp(ctx, true, false);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                List<String> ev = leftClickWarp(ctx, scenario, hx, (int) Math.floor(start.x) + 3, (int) Math.floor(start.z),
                        false);
                checkSneakOrder(scenario, ev);
                int on = ev.indexOf("use_item_on");
                int use = ev.indexOf("use_item");
                check(on >= 0 && on < use, "the block half of the right click did not go before the use (" + on + ", "
                        + use + ")");
            } finally {
                lcTearDown(ctx, hx);
                gateBack(ctx, gate);
            }
        });
    }

    /** 1025-lcew-sneaking: holding sneak - the warp goes on the click's own tick and sneak stays down. */
    private static void lcSneaking(ClientGameTestContext ctx) {
        int x = -5300;
        Scenario.run(ctx, "1025-lcew-sneaking", (server, scenario) -> arena(server, x, Z, 30), (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            boolean gate = gateOff(ctx);
            Hx hx = null;
            try {
                hx = lcSetUp(ctx, true, false);
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                List<String> ev = leftClickWarp(ctx, scenario, hx, (int) Math.floor(start.x) + 14, (int) Math.floor(start.z),
                        true);
                check(count(ev, "player_input:") == 0, "an input packet changed while he held sneak: " + ev);
                boolean still = ctx.computeOnClient(mc -> mc.player.getLastSentInput().shift());
                check(still, "the server no longer has him sneaking though he holds the key");
            } finally {
                lcTearDown(ctx, hx);
                gateBack(ctx, gate);
            }
        });
    }

    /** 1025-lcew-zpew: with Zero Ping Etherwarp on, under 150 ms, the left click's warp is predicted and matched. */
    private static void lcZpew(ClientGameTestContext ctx) {
        int x = -5400;
        Scenario.run(ctx, "1025-lcew-zpew", (server, scenario) -> arena(server, x, Z, 30), (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            boolean gate = gateOff(ctx);
            Hx hx = null;
            try {
                hx = lcSetUp(ctx, true, true);
                ctx.runOnClient(mc -> Latency.install(150));
                ctx.waitTicks(20);
                long preds0 = num(ctx, ZP, "predictions");
                long hits0 = num(ctx, ZP, "hits");
                Vec3 start = ctx.computeOnClient(mc -> mc.player.position());
                leftClickWarp(ctx, scenario, hx, (int) Math.floor(start.x) + 16, (int) Math.floor(start.z), false);
                long preds = num(ctx, ZP, "predictions") - preds0;
                long hits = num(ctx, ZP, "hits") - hits0;
                scenario.log("zero ping: " + preds + " prediction(s), " + hits + " hit(s)");
                check(preds == 1 && hits == 1, "the left click's warp was not predicted and matched (" + preds + " / "
                        + hits + ")");
            } finally {
                lcTearDown(ctx, hx);
                gateBack(ctx, gate);
            }
        });
    }

    // ---- Full Block -----------------------------------------------------------------------------------------

    private static void fullBlock(boolean on) {
        Mod.set(SECRETS, "setMasterEnabled", on);
        Mod.set(SECRETS, "setLeversEnabled", on);
        Mod.set(SECRETS, "setChestsEnabled", on);
        Mod.set(SECRETS, "setDungeonsOnly", false);
        Mod.set(SECRETS, "setBossOnly", false);
        Mod.set(SECRETS, "setLeverShape", Mod.enumValue(SECRETS + "$Shape", "FULL"));
        Mod.set(SECRETS, "setChestShape", Mod.enumValue(SECRETS + "$Shape", "FULL"));
    }

    private static void triggerbotsOff(Minecraft mc) {
        Mod.set("secrettrigger.SecretTriggerbotConfig", "setEnabled", false);
        Mod.set("cheatutils.CheatUtilsConfig", "setSecretAuraEnabled", false);
    }

    /** Where the crosshair is: "block x,y,z face hit" or the miss. */
    private static String crosshair(Minecraft mc) {
        HitResult h = mc.hitResult;
        if (h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK) {
            return String.format(Locale.ROOT, "block %s %s at %.3f %.3f %.3f", b.getBlockPos().toShortString(),
                    b.getDirection(), h.getLocation().x, h.getLocation().y, h.getLocation().z);
        }
        return h == null ? "nothing" : h.getType().toString();
    }

    private static boolean onBlock(Minecraft mc, BlockPos pos) {
        return mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos);
    }

    /**
     * Aims at {@code target}, checks the crosshair misses {@code block} with Full Block off and hits it with it on, and
     * returns how far outside the block's real shape the hit point is. Leaves Full Block on.
     */
    private static double aimThroughWidenedBox(ClientGameTestContext ctx, Scenario scenario, BlockPos block, Vec3 target) {
        ctx.runOnClient(mc -> {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 d = target.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()));
            mc.player.setXRot(pitch);
            fullBlock(false);
        });
        ctx.waitTicks(5);
        String off = ctx.computeOnClient(CheatExtrasTests::crosshair);
        boolean offHit = ctx.computeOnClient(mc -> onBlock(mc, block));
        AABB real = ctx.computeOnClient(mc -> mc.level.getBlockState(block).getShape(mc.level, block).bounds().move(block));
        ctx.runOnClient(mc -> fullBlock(true));
        ctx.waitTicks(5);
        String on = ctx.computeOnClient(CheatExtrasTests::crosshair);
        boolean onHit = ctx.computeOnClient(mc -> onBlock(mc, block));
        Vec3 at = ctx.computeOnClient(mc -> mc.hitResult == null ? null : mc.hitResult.getLocation());
        double dx = Math.max(0, Math.max(real.minX - at.x, at.x - real.maxX));
        double dy = Math.max(0, Math.max(real.minY - at.y, at.y - real.maxY));
        double dz = Math.max(0, Math.max(real.minZ - at.z, at.z - real.maxZ));
        double outside = Math.sqrt(dx * dx + dy * dy + dz * dz);
        scenario.log(String.format(Locale.ROOT, "Full Block off: crosshair %s; on: %s; real shape %s; the hit is %.3f "
                + "blocks outside it", off, on, real, outside));
        check(!offHit, "the crosshair is on the block with Full Block off: an ordinary click, nothing tested");
        check(onHit, "the crosshair is not on the block with Full Block on");
        check(outside > 0.05, "the hit point is inside the real shape: an ordinary click, nothing tested");
        return outside;
    }

    /** 1025-fullblock-lever: a vanilla right click on a wall lever through Full Block's widened box. */
    private static void fbLever(ClientGameTestContext ctx) {
        int bx = -5600;
        BlockPos lever = new BlockPos(bx, SURFACE_Y + 1, Z);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, "1025-fullblock-lever", (server, scenario) -> TestMap.on(server)
                .platform(bx, SURFACE_Y - 1, Z, 10)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .skyblockSidebar("F7")
                .command("fill " + (bx + 1) + " " + SURFACE_Y + " " + (Z - 1) + " " + (bx + 1) + " " + (SURFACE_Y + 3) + " "
                        + (Z + 1) + " minecraft:stone")
                .command("setblock " + bx + " " + (SURFACE_Y + 1) + " " + Z + " minecraft:lever[face=wall,facing=west]")
                .spawn(bx - 2.5, Z + 0.5, -90f)
                .build(), (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            try {
                ctx.runOnClient(CheatExtrasTests::triggerbotsOff);
                // The stone's west face just above the lever's real top (0.75), in the lever's own column.
                aimThroughWidenedBox(ctx, scenario, lever, new Vec3(bx + 1.0, SURFACE_Y + 1.92, Z + 0.5));
                boolean before = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                startEvents(ctx);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(20);
                List<String> ev = stopEvents(ctx);
                boolean after = ctx.computeOnClient(mc -> mc.level.getBlockState(lever).getValue(LeverBlock.POWERED));
                scenario.log("lever powered " + before + " -> " + after + "; " + count(ev, "use_item_on")
                        + " block click(s); GrimAC " + scenario.flags().size() + " line(s)");
                check(count(ev, "use_item_on") == 1, "the right click did not send one block click");
                check(!before && after, "the server did not flip the lever");
            } finally {
                ctx.runOnClient(mc -> fullBlock(false));
            }
        });
    }

    /** 1025-fullblock-chest: a vanilla right click on a chest's top band (above its real 0.875 lid) - the chest opens. */
    private static void fbChest(ClientGameTestContext ctx) {
        int bx = -5700;
        BlockPos chest = new BlockPos(bx, SURFACE_Y, Z);
        Scenario.labelServerAs(DUNGEON_LABEL);
        Scenario.run(ctx, "1025-fullblock-chest", (server, scenario) -> TestMap.on(server)
                .platform(bx, SURFACE_Y - 1, Z, 10)
                .catchFloor(120)
                .survival()
                .clearInventory()
                .skyblockSidebar("F7")
                .command("fill " + (bx + 1) + " " + SURFACE_Y + " " + (Z - 1) + " " + (bx + 1) + " " + (SURFACE_Y + 3) + " "
                        + (Z + 1) + " minecraft:stone")
                .command("setblock " + bx + " " + SURFACE_Y + " " + Z + " minecraft:chest[facing=west]")
                .spawn(bx - 2.5, Z + 0.5, -90f)
                .build(), (server, scenario) -> {
            scenario.assertDetectorWorks();
            ctx.waitTicks(20);
            try {
                ctx.runOnClient(CheatExtrasTests::triggerbotsOff);
                // The stone's west face 0.97 up: the ray crosses the chest's cell above its real lid.
                aimThroughWidenedBox(ctx, scenario, chest, new Vec3(bx + 1.0, SURFACE_Y + 0.97, Z + 0.5));
                startEvents(ctx);
                ctx.getInput().pressKey(o -> o.keyUse);
                ctx.waitTicks(20);
                List<String> ev = stopEvents(ctx);
                int opened = PacketTrace.received("open_screen");
                boolean screen = ctx.computeOnClient(mc -> McCompat.screen(mc)
                        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
                scenario.log("chest: " + count(ev, "use_item_on") + " block click(s), " + opened + " open_screen, container "
                        + "screen up " + screen + "; GrimAC " + scenario.flags().size() + " line(s)");
                check(count(ev, "use_item_on") == 1, "the right click did not send one block click");
                check(opened >= 1, "the server did not open the chest");
                ctx.runOnClient(mc -> {
                    if (mc.player.containerMenu != mc.player.inventoryMenu) {
                        mc.player.closeContainer();
                    }
                });
                ctx.waitTicks(5);
            } finally {
                ctx.runOnClient(mc -> fullBlock(false));
            }
        });
    }
}
