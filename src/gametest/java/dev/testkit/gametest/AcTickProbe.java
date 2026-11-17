package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick probe of Auto Clear for 1020-sim-ac-speed (achype4-1117): what each tick of a room's clock was spent on,
 * read by reflection so it works on any jar (origin's too). The room ticks of a speed run swung 1034-1329 on one jar;
 * nearly all of that was the wait for the planner thread (a trip's path search queued behind other work, wall time,
 * so server ticks do not remove it). {@code planWait} counts the clock's ticks with {@code ClearExecutor.pathPending}
 * set, and "quiet ticks" = room ticks - planWait is the comparison number.
 */
final class AcTickProbe {
    record Visit(String room, int ticks, int planWait, int useTicks, int turnTicks, int idle, long serverTicks) {
    }

    private static final List<Visit> VISITS = new ArrayList<>();
    private static boolean registered;
    private static Field roomF;
    private static Field pendingF;
    private static Method usesM;
    private static String room;
    private static int ticks, planWait, useTicks, turnTicks, idle, lastUses = -1;
    private static long serverStart;
    private static float lastYaw = Float.NaN, lastPitch = Float.NaN;

    private AcTickProbe() {
    }

    /** Registers the listener once (call on the client thread). */
    static void install() {
        if (registered) {
            return;
        }
        try {
            roomF = Class.forName("com.killer560.hub.autoclear.RoomClock").getDeclaredField("room");
            roomF.setAccessible(true);
            pendingF = Class.forName("com.killer560.hub.livemap.autoclear.ClearExecutor").getDeclaredField("pathPending");
            pendingF.setAccessible(true);
            usesM = Class.forName("com.killer560.hub.util.RightClick").getMethod("itemUsesSent");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("AcTickProbe: " + e);
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(AcTickProbe::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        String now;
        boolean pending;
        int uses;
        try {
            now = (String) roomF.get(null);
            pending = pendingF.getBoolean(null);
            uses = (Integer) usesM.invoke(null);
        } catch (ReflectiveOperationException e) {
            return;
        }
        long server = mc.getSingleplayerServer() == null ? 0 : mc.getSingleplayerServer().getTickCount();
        if (room != null && !room.equals(now)) {
            synchronized (VISITS) {
                VISITS.add(new Visit(room, ticks, planWait, useTicks, turnTicks, idle, server - serverStart));
            }
            room = null;
        }
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        boolean turned = yaw != lastYaw || pitch != lastPitch;
        boolean used = lastUses >= 0 && uses != lastUses;
        lastYaw = yaw;
        lastPitch = pitch;
        lastUses = uses;
        if (now == null) {
            return;
        }
        if (room == null) {
            room = now;
            ticks = planWait = useTicks = turnTicks = idle = 0;
            serverStart = server;
        }
        ticks++;
        if (pending) {
            planWait++;
        } else if (used) {
            useTicks++;
        } else if (turned) {
            turnTicks++;
        } else {
            idle++;
        }
    }

    /** The room visits finished since the last call (oldest first). */
    static List<Visit> drain() {
        synchronized (VISITS) {
            List<Visit> out = new ArrayList<>(VISITS);
            VISITS.clear();
            return out;
        }
    }
}
