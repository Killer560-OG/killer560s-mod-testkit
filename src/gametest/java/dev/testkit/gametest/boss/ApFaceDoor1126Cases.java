package dev.testkit.gametest.boss;

import dev.testkit.gametest.SameTick;
import dev.testkit.gametest.hx.HxKit;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import dev.testkit.harness.WireCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Dungeons Autopilot's Face Door on Arrival (autopilot-face-witherdoor-1126): on arriving at a wither or blood door it
 * turns the player to look at the door, with the Interactive Map's own turn. The feature is called the way the Autopilot's
 * door step calls it ({@code AutoSecretFeature.faceDoorOnArrival(cell)}, from {@code beginDoor}); every case asserts the look
 * and the wire, then GrimAC (default and experimental, by checkout) judges.
 * <ul>
 *   <li>1126-apfacedoor-on: six arrivals, each from a look 6 degrees off the door: the client look is on the door (1 degree) and a
 *       rotation packet with that look is on the wire after the call; pitch within -90..90, the yaw never wrapped.</li>
 *   <li>1126-apfacedoor-off: the option off: the look is untouched and no rotation packet goes out.</li>
 *   <li>1126-apfacedoor-same-&lt;act&gt;: his own use, attack, hotbar swap and container click in every tick, so the turn's tick
 *       carries one; the look is still on the door and his action reached the wire.</li>
 * </ul>
 * A legit jar has no Dungeons Autopilot: each case passes with a note and tests nothing.
 */
final class ApFaceDoor1126Cases {

    private static final String AS = "autosecret.AutoSecretFeature";
    private static final String CFG = "autosecret.AutoSecretConfig";
    /** A map cell far from the platform: only the direction to its door matters. */
    private static final int CELL = 60;
    private static final int ROUNDS = 6;

    private ApFaceDoor1126Cases() {
    }

    static void register(Session s) {
        s.test("1126-apfacedoor-on", c -> run(c, null, true));
        s.test("1126-apfacedoor-off", c -> run(c, null, false));
        for (SameTick.Act a : new SameTick.Act[]{SameTick.Act.USE_KEY, SameTick.Act.ATTACK, SameTick.Act.SWAP,
                SameTick.Act.CLICK}) {
            s.test("1126-apfacedoor-same-" + a.name().toLowerCase(Locale.ROOT).replace("_", ""), c -> run(c, a, true));
        }
    }

    private static String describe(Object p) {
        if (p instanceof ServerboundMovePlayerPacket m && m.hasRotation()) {
            return String.format(Locale.ROOT, "rot %.4f %.4f", m.getYRot(0f), m.getXRot(0f));
        }
        return p.getClass().getSimpleName();
    }

    private static void run(Session c, SameTick.Act act, boolean on) throws Exception {
        if (!Mod.isCheat()) {
            c.note("legit jar: Dungeons Autopilot is cheat-only, Face Door on Arrival does nothing");
            return;
        }
        SameTick st = null;
        double x = 40.5;
        double y = 151.0;
        double z = 47.7;
        try (HxKit.Settings off = GrimTickCases.gateOff(c);
             HxKit.Settings ip = GrimTickCases.hypixelIp(c);
             HxKit.Settings face = new HxKit.Settings(c).with(CFG, "FaceDoorOnArrival", on)) {
            c.server().command("forceload add 34 42 46 56");
            c.server().sync(20);
            c.server().command("fill 34 148 42 46 150 56 stone");
            SameTickGapCases.onPlatform(c, x, y, z, new BlockPos(40, 150, 47));
            SameTickCases.sticks(c);
            c.hx().sidebar("SKYBLOCK", "The Catac§combs §7(F7)");
            c.waitUntil("DungeonState F7", mc -> Boolean.TRUE.equals(Mod.staticCall("secrets.DungeonState", "isF7OrM7")), 100);
            // The expected look from his eye (standing, 1.62) to the door's centre, as the turn computes it.
            Vec3 centre = c.onClient(mc -> (Vec3) Mod.staticCall("livemap.DungeonLayout", "doorCentre", CELL));
            Vec3 d = centre.subtract(x, y + 1.62, z);
            float wantYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float wantPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            float startYaw = wantYaw + 6f;
            if (act == SameTick.Act.ATTACK) {
                // One zombie on the line between his look before and after the turn (1.8 blocks out), so his attack lands
                // in the turn's tick whichever look the tick starts from.
                double mid = Math.toRadians(wantYaw + 3f);
                c.server().command(String.format(Locale.ROOT, "summon zombie %.2f %.2f %.2f {NoAI:1b,Invulnerable:1b,Silent:1b,"
                        + "Tags:[\"sametick\"]}", x - Math.sin(mid) * 1.8, y, z + Math.cos(mid) * 1.8));
            }
            c.onClient(mc -> {
                WireCapture.describe = ApFaceDoor1126Cases::describe;
                WireCapture.start();
                return null;
            });
            if (act != null) {
                st = SameTickCases.start(c, act, p -> p instanceof ServerboundMovePlayerPacket m && m.hasRotation());
            }
            int turned = 0;
            int onWire = 0;
            String notes = "";
            for (int round = 0; round < ROUNDS; round++) {
                c.hx().call("dungeon.tp", "x", x, "y", y, "z", z, "yaw", startYaw, "pitch", 0f);
                c.waitUntil("the client at the start look", mc -> Math.abs(Mth.wrapDegrees(mc.player.getYRot() - startYaw)) < 0.01f
                        && Math.abs(mc.player.getXRot()) < 0.01f, 60);
                c.ctx().waitTicks(4);
                if (act == SameTick.Act.CLICK) {
                    c.onClient(mc -> {
                        dev.testkit.compat.McCompat.setScreen(mc, new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                        return null;
                    });
                    c.ctx().waitTicks(2);
                }
                float[] was = c.onClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
                int mark = c.onClient(mc -> WireCapture.snapshot().size());
                c.onClient(mc -> {
                    Mod.staticCall(AS, "faceDoorOnArrival", CELL);
                    return null;
                });
                float[] now = c.onClient(mc -> new float[]{mc.player.getYRot(), mc.player.getXRot()});
                c.ctx().waitTicks(4);
                if (act == SameTick.Act.CLICK) {
                    c.onClient(mc -> {
                        mc.player.closeContainer();
                        return null;
                    });
                }
                List<WireCapture.Sent> wire = c.onClient(mc -> WireCapture.snapshot());
                boolean rotSent = false;
                boolean lookSent = false;
                for (WireCapture.Sent s : wire.subList(Math.min(mark, wire.size()), wire.size())) {
                    if (s.text().startsWith("rot ")) {
                        rotSent = true;
                        String[] p = s.text().split(" ");
                        lookSent |= Math.abs(Mth.wrapDegrees(Float.parseFloat(p[1]) - wantYaw)) < 1f
                                && Math.abs(Float.parseFloat(p[2]) - wantPitch) < 1f;
                    }
                }
                boolean onDoor = Math.abs(Mth.wrapDegrees(now[0] - wantYaw)) < 1f && Math.abs(now[1] - wantPitch) < 1f;
                if (on) {
                    turned += onDoor ? 1 : 0;
                    onWire += lookSent ? 1 : 0;
                    c.check(Math.abs(now[0] - was[0]) < 180f, "round " + round + ": the yaw was wrapped (" + was[0] + " to " + now[0] + ")");
                    c.check(Math.abs(now[1]) <= 90f, "round " + round + ": pitch left -90..90 (" + now[1] + ")");
                } else {
                    c.check(now[0] == was[0] && now[1] == was[1], "round " + round + ": the option is off but the look moved ("
                            + was[0] + "/" + was[1] + " to " + now[0] + "/" + now[1] + ")");
                    c.check(!rotSent, "round " + round + ": the option is off but a rotation packet went out");
                }
                notes = String.format(Locale.ROOT, "last round look %.2f/%.2f, door %.2f/%.2f", now[0], now[1], wantYaw, wantPitch);
            }
            c.note(String.format(Locale.ROOT, "option %s: %d of %d arrivals turned onto the door, %d with that look on the wire; %s%s",
                    on ? "ON" : "OFF", turned, ROUNDS, onWire, notes, st == null ? "" : "; " + st.report()));
            if (on) {
                c.check(turned == ROUNDS, "Face Door on Arrival ON: only " + turned + " of " + ROUNDS + " arrivals turned onto the door");
                c.check(onWire == ROUNDS, "Face Door on Arrival ON: the door look was on the wire for only " + onWire + " of " + ROUNDS);
            }
            if (st != null) {
                SameTick.judge(c, st, "face door", SameTick.Expect.ACTED);
                st = null;
            }
        } finally {
            if (st != null) {
                SameTick s0 = st;
                c.onClient(mc -> {
                    s0.close();
                    return null;
                });
            }
            c.onClient(mc -> {
                WireCapture.stop();
                WireCapture.describe = null;
                if (dev.testkit.compat.McCompat.screen(mc) != null) mc.player.closeContainer();
                return null;
            });
            c.server().command("kill @e[tag=sametick]");
            GrimTickCases.home(c);
            c.server().sync(5);
            c.server().command("fill 34 148 42 46 154 56 air");
            c.server().command("forceload remove 34 42 46 56");
            c.server().command("clear @a");
        }
    }
}
