package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import dev.testkit.gametest.hx.Session;
import dev.testkit.gametest.mod.Mod;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Leap Takes Target's Look (mod leaprot-1104, fastleap/LeapRotation; killer560, 2026-11-04). His 26.3 log: a leap's
 * teleport normally carries the leaped-to player's look (absolute), but a teleport within about 1 s of the one before
 * comes with a RELATIVE look 0/0, so a leap then keeps his own look. With the setting ON the mod turns him to the
 * teammate's look when such a teleport and Hypixel's "You have teleported to Name!" line meet. Here the teammate is a
 * fake player {@link #MATE} at the destination looking {@link #MATE_YAW} / {@link #MATE_PITCH}; he looks 170 / 20.
 * <ul>
 *   <li>1104-leaplook-relative-on: ON, relative-look teleport + the line: he turns to the mate's look, the mod counts one,
 *       and six ticks later the SERVER has that look (the next movement packet carried it); GrimAC (the session's flag
 *       check) says nothing.</li>
 *   <li>1104-leaplook-relative-off: OFF, the same: his own look stays.</li>
 *   <li>1104-leaplook-no-line: ON, the relative-look teleport with no leap line (an etherwarp, a setback): his look stays.</li>
 *   <li>1104-leaplook-absolute: ON, an absolute teleport (Hypixel's usual leap, look 0 / 0) + the line: vanilla's look,
 *       nothing counted.</li>
 * </ul>
 */
final class LeapRotationCases {

    static final String CONFIG = "fastleap.FastLeapConfig";
    /** "SicoKaleb" names are what HxPrimitives.player skips when it picks the real client. */
    static final String MATE = "SicoKalebLeap";
    static final int AX = 70;
    static final int BX = 82;
    static final int Y = 180;
    static final int Z = 90;
    static final float YAW = 170f;
    static final float PITCH = 20f;
    static final float MATE_YAW = 230f;
    static final float MATE_PITCH = -30f;

    private LeapRotationCases() {
    }

    static void register(Session s) {
        s.test("1104-leaplook-relative-on", c -> run(c, true, true, true));
        s.test("1104-leaplook-relative-off", c -> run(c, false, true, true));
        s.test("1104-leaplook-no-line", c -> run(c, true, true, false));
        s.test("1104-leaplook-absolute", c -> run(c, true, false, true));
    }

    private static void run(Session c, boolean on, boolean relative, boolean line) throws Exception {
        DungeonFixBossCases.floor(c, AX, Y, Z);
        DungeonFixBossCases.floor(c, BX, Y, Z);
        TerminalAuraCases.tp(c, AX + 0.5, Y, Z + 0.5, 0f);
        c.server().command(String.format(Locale.ROOT, "testkit spawn %s %.1f %d %.1f", MATE, BX + 1.5, Y, Z + 0.5));
        c.server().sync(20);
        c.hx().call("player.look", "player", MATE, "yaw", MATE_YAW, "pitch", MATE_PITCH);
        try (AutoCloseable cfg = c.onClient(mc -> Mod.with(CONFIG, "LeapTakesTargetLook", on))) {
            c.waitUntil("the client sees " + MATE + " at " + MATE_YAW + "/" + MATE_PITCH, mc -> mc.level != null
                    && mc.level.players().stream().anyMatch(p -> p.getName().getString().equals(MATE)
                    && Math.abs(Mth.wrapDegrees(p.getYHeadRot() - MATE_YAW)) < 1f && Math.abs(p.getXRot() - MATE_PITCH) < 1f), 100);
            c.onClient(mc -> {
                mc.player.setYRot(YAW);
                mc.player.setXRot(PITCH);
                return null;
            });
            c.ctx().waitTicks(5);
            long before = taken(c);
            if (relative) {
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f, "relRot", true);
            } else {
                c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f);
            }
            if (line) {
                c.hx().chat("You have teleported to " + MATE + "!");
            }
            c.waitUntil("the client at the leap target", mc -> mc.player != null
                    && mc.player.position().distanceTo(new Vec3(BX + 0.5, Y, Z + 0.5)) < 0.05, 100);
            boolean turns = cheat() && on && relative && line; // a legit jar has the switch but never turns
            float wantYaw = turns ? MATE_YAW : relative ? YAW : 0f;
            float wantPitch = turns ? MATE_PITCH : relative ? PITCH : 0f;
            c.ctx().waitTicks(10);
            // a remote player's look reaches the client in 1/256-turn steps (1.4 degrees)
            float tol = turns ? 1.5f : 0.5f;
            float[] look = c.onClient(mc -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
            long after = taken(c);
            c.ctx().waitTicks(6);
            JsonObject st = c.hx().call("player.state").getAsJsonObject();
            float serverYaw = st.get("yaw").getAsFloat();
            float serverPitch = st.get("pitch").getAsFloat();
            c.note(String.format(Locale.ROOT, "setting %s, %s look, leap line %s: camera %.1f/%.1f (want %.0f/%.0f), taken "
                    + "%d -> %d, server look %.1f/%.1f", on ? "ON" : "OFF", relative ? "relative" : "absolute", line,
                    look[0], look[1], wantYaw, wantPitch, before, after, serverYaw, serverPitch));
            c.check(Math.abs(Mth.wrapDegrees(look[0] - wantYaw)) < tol && Math.abs(look[1] - wantPitch) < tol,
                    "camera " + look[0] + "/" + look[1] + ", want " + wantYaw + "/" + wantPitch);
            if (turns) {
                c.check(Math.abs(look[0] - YAW) <= 180.5f, "the turn took the short way: yaw " + look[0] + " from " + YAW);
            }
            c.check(after - before == (turns ? 1 : 0), "taken count went " + before + " -> " + after);
            c.check(Math.abs(Mth.wrapDegrees(serverYaw - wantYaw)) < tol && Math.abs(serverPitch - wantPitch) < tol,
                    "the server has his look at " + serverYaw + "/" + serverPitch + ", want " + wantYaw + "/" + wantPitch);
        } finally {
            c.server().command("testkit remove " + MATE);
            c.ctx().waitTicks(30); // past the 1.5 s pairing window before the next case
        }
    }

    private static boolean cheat() {
        try {
            return Class.forName("com.killer560.hub.BuildVariant").getField("CHEAT_FEATURES_ENABLED").getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static long taken(Session c) {
        return c.onClient(mc -> ((Number) Mod.staticCall("fastleap.LeapRotation", "taken")).longValue());
    }
}
