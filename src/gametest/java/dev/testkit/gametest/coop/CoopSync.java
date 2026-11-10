package dev.testkit.gametest.coop;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The two-client mode's rendezvous (run-coop.sh): both clients get {@code testkit.coop.dir} (one folder), their
 * {@code testkit.coop.role} (host or guest) and {@code testkit.coop.port} (the LAN port the host publishes on). A step is
 * a file named by its key holding one value; {@link #await} ticks the client while it waits (a blocking wait would park
 * the gametest lockstep, and with it a published host's server) and gives up at a wall-clock deadline or as soon as the
 * other side wrote its {@code <role>-failed} file.
 */
public final class CoopSync {

    private CoopSync() {
    }

    public static String role() {
        return System.getProperty("testkit.coop.role", "");
    }

    public static boolean active() {
        return !role().isBlank() && !System.getProperty("testkit.coop.dir", "").isBlank();
    }

    public static int port() {
        return Integer.getInteger("testkit.coop.port", 25870);
    }

    public static String other() {
        return role().equals("host") ? "guest" : "host";
    }

    private static Path file(String key) {
        return Path.of(System.getProperty("testkit.coop.dir")).resolve(key + ".txt");
    }

    /** Writes {@code key} = {@code value} (atomically, so the other side never reads half a file). */
    public static void put(String key, String value) {
        try {
            Path f = file(key);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(key + "." + role() + ".tmp");
            Files.writeString(tmp, value, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("[coop] " + role() + " wrote " + key + " = " + value.replace('\n', '|'));
        } catch (IOException e) {
            throw new AssertionError("coop sync: could not write " + key + ": " + e, e);
        }
    }

    public static String get(String key) {
        try {
            Path f = file(key);
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Tells the other side to stop waiting. */
    public static void fail(String why) {
        put(role() + "-failed", why);
    }

    /**
     * Waits for {@code key}, ticking the client about 20 times a second, for at most {@code seconds}. Returns its value,
     * or throws with the other side's failure reason or a timeout.
     */
    public static String await(ClientGameTestContext ctx, String key, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            String v = get(key);
            if (v != null) {
                return v;
            }
            String failed = get(other() + "-failed");
            if (failed != null) {
                throw new AssertionError("the " + other() + " failed while " + role() + " waited for " + key + ": " + failed);
            }
            ctx.waitTick();
            sleep(50);
        }
        throw new AssertionError(role() + " waited " + seconds + " s for " + key + " and it never came");
    }

    /** Ticks for about {@code ms} of wall time (paced, so a published server runs near 20 TPS for its guest). */
    public static void pace(ClientGameTestContext ctx, int ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ctx.waitTick();
            sleep(50);
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
