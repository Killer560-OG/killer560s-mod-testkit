package dev.testkit.gametest.menu;

import dev.testkit.gametest.mod.Mod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto Tune's own view, one row per client tick on which it changed (autotune-signal-1126-fix2): the game time it reads as
 * {@code now}, its round trip, last click, pending answer, finisher, and for each beat its pane and the beacon pane (slot,
 * the {@code now} it was last seen stepping, steps seen) with pause, boost and settle. Written next to the client as
 * {@code autotune-trace-<label>.txt}; the server's {@code beacon.step} events give the real step ticks to set it against.
 */
final class BeaconTrace {
    private static final String AUTO = "foraging.BeaconAutoTuner";
    private static final String TUNER = "foraging.BeaconTuner";
    private static boolean registered;
    private static StringBuilder rows;
    private static String last = "";

    private BeaconTrace() {
    }

    static synchronized void start() {
        if (!registered) {
            registered = true;
            ClientTickEvents.END_CLIENT_TICK.register(mc -> sample(mc.level == null ? -1 : mc.level.getGameTime()));
        }
        rows = new StringBuilder("now | rtt lastClick pending fin attempts retry | per beat: mine slot/moved/steps p? b? settle ; beacon slot/moved/steps\n");
        last = "";
    }

    static synchronized void stop(String label, String server) {
        if (rows == null) {
            return;
        }
        try {
            Files.writeString(Path.of("autotune-trace-" + label + ".txt"), rows + server, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a trace is a diagnostic only
        }
        rows = null;
    }

    private static synchronized void sample(long now) {
        if (rows == null) {
            return;
        }
        try {
            StringBuilder r = new StringBuilder();
            r.append(Mod.field(AUTO, "rtt")).append(' ').append(Mod.field(AUTO, "lastClick")).append(' ')
                    .append(Mod.field(AUTO, "pendingSlot")).append(' ').append(Mod.field(AUTO, "fin")).append(' ')
                    .append(Mod.field(AUTO, "attempts")).append(' ').append(Mod.field(AUTO, "retry")).append(" |");
            Object mine = Mod.field(TUNER, "mine");
            Object beat = Mod.field(AUTO, "beat");
            boolean[] paused = (boolean[]) Mod.field(TUNER, "paused");
            boolean[] boost = (boolean[]) Mod.field(AUTO, "boost");
            long[] settle = (long[]) Mod.field(AUTO, "settle");
            for (int g = 0; g < Array.getLength(mine); g++) {
                r.append(' ').append(pane(Array.get(mine, g))).append(g < paused.length && paused[g] ? " P" : " -")
                        .append(boost[g] ? "B" : "-").append(' ').append(settle[g] == Long.MIN_VALUE ? "." : String.valueOf(settle[g]))
                        .append(" ; ").append(g < Array.getLength(beat) ? pane(Array.get(beat, g)) : "?").append(" |");
            }
            String s = r.toString();
            if (!s.equals(last)) {
                last = s;
                rows.append(now).append(" | ").append(s).append('\n');
            }
        } catch (Exception e) {
            if (!"error".equals(last)) {
                last = "error";
                rows.append(now).append(" | trace error ").append(e).append('\n');
            }
        }
    }

    private static String pane(Object b) {
        return b == null ? "null" : Mod.field(b, "slot") + "/" + Mod.field(b, "moved") + "/" + Mod.field(b, "steps");
    }
}
