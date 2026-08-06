package dev.testkit.harness;

import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A ring buffer of every chat line the client has received, kept so a test can read what the server
 * said back.
 *
 * <p>This is the other half of the anticheat feedback loop. {@link Setbacks} records the server
 * <i>correcting</i> us, which only happens once a check has already escalated; Grim's verbose output
 * names the check and its violation level the moment it fires, and it arrives as ordinary chat. With
 * this, a scenario can assert "no check flagged" instead of "it looked fine", which is the difference
 * between a test and a demo.
 *
 * <p>Always recording costs one string per chat line and is worth it: a flag that only appears on the
 * run you forgot to instrument is a flag you will chase twice.
 */
public final class ChatWatch {

    private static final int RING = 512;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private ChatWatch() {
    }

    /** Called from the client packet listener for every chat flavour the server can send. */
    public static void record(Component message) {
        if (message == null) {
            return;
        }
        String line = message.getString();
        if (line.isEmpty()) {
            return;
        }
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > RING) {
                LINES.removeFirst();
            }
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    /** Every line received since the last {@link #clear()}, oldest first. */
    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    /** Lines containing {@code needle}, case-insensitively. */
    public static List<String> matching(String needle) {
        String lower = needle.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String line : lines()) {
            if (line.toLowerCase(Locale.ROOT).contains(lower)) {
                out.add(line);
            }
        }
        return out;
    }
}
