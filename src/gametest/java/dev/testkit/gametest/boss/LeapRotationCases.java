package dev.testkit.gametest.boss;

import com.google.gson.JsonObject;
import dev.testkit.gametest.hx.Session;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * norotate-rm-1118 (killer560, 2026-11-18): No Rotate (Leap Takes Target's Look) was removed from the mod. Whatever look a
 * server teleport carries, vanilla applies it and nothing in the mod touches it: a teleport with a RELATIVE 0/0 look
 * (the kept-look kind Hypixel used to send for a leap within a second of another teleport) moves him and leaves his
 * camera and the server's copy of his look exactly as they were, even with "You have teleported to Name!" arriving.
 */
final class LeapRotationCases {

    static final int AX = 70;
    static final int BX = 82;
    static final int Y = 180;
    static final int Z = 90;
    static final float YAW = 170f;
    static final float PITCH = 20f;

    private LeapRotationCases() {
    }

    static void register(Session s) {
        s.test("1118-leap-relative-keeps-look", LeapRotationCases::run);
    }

    private static void run(Session c) throws Exception {
        DungeonFixBossCases.floor(c, AX, Y, Z);
        DungeonFixBossCases.floor(c, BX, Y, Z);
        TerminalAuraCases.tp(c, AX + 0.5, Y, Z + 0.5, 0f);
        c.onClient(mc -> {
            mc.player.setYRot(YAW);
            mc.player.setXRot(PITCH);
            return null;
        });
        c.waitUntil("he stands at A", mc -> mc.player.onGround(), 100);
        c.ctx().waitTicks(30); // past any run of setbacks at the first stand
        c.hx().call("dungeon.tp", "x", BX + 0.5, "y", (double) Y, "z", Z + 0.5, "yaw", 0f, "pitch", 0f, "relRot", true);
        c.hx().chat("You have teleported to SicoKalebLeap!");
        c.waitUntil("the client at the leap target", mc -> mc.player != null
                && mc.player.position().distanceTo(new Vec3(BX + 0.5, Y, Z + 0.5)) < 0.05, 100);
        c.ctx().waitTicks(10);
        float[] look = c.onClient(mc -> new float[] {mc.player.getYRot(), mc.player.getXRot()});
        float serverYaw = Float.NaN;
        float serverPitch = Float.NaN;
        for (int i = 0; i < 8; i++) { // the next movement packet carries the look
            c.ctx().waitTicks(5);
            JsonObject st = c.hx().call("player.state").getAsJsonObject();
            serverYaw = st.get("yaw").getAsFloat();
            serverPitch = st.get("pitch").getAsFloat();
            if (Math.abs(Mth.wrapDegrees(serverYaw - YAW)) < 0.5f && Math.abs(serverPitch - PITCH) < 0.5f) {
                break;
            }
        }
        c.note(String.format(Locale.ROOT, "relative 0/0 leap teleport: camera %.1f/%.1f, server %.1f/%.1f, want %.0f/%.0f",
                look[0], look[1], serverYaw, serverPitch, YAW, PITCH));
        c.check(Math.abs(Mth.wrapDegrees(look[0] - YAW)) < 0.5f && Math.abs(look[1] - PITCH) < 0.5f,
                "camera " + look[0] + "/" + look[1] + ", want " + YAW + "/" + PITCH);
        c.check(Math.abs(Mth.wrapDegrees(serverYaw - YAW)) < 0.5f && Math.abs(serverPitch - PITCH) < 0.5f,
                "the server has his look at " + serverYaw + "/" + serverPitch + ", want " + YAW + "/" + PITCH);
    }
}
