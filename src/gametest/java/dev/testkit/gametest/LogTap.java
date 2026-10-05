package dev.testkit.gametest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A ring buffer of the mod's own log lines and every chat line the client shows, read back by a scenario.
 *
 * <p>{@code ChatWatch} only sees chat that arrives as a PACKET. The mod's {@code ModChat.send} adds its lines to
 * the chat window directly, so ChatWatch never sees them - and those lines ("[Sim] Boulder solved", "[AutoPuzzles]
 * Blaze: done") are exactly what a puzzle scenario needs as evidence. Vanilla logs every line the chat window
 * shows as {@code [CHAT] ...}, so a log4j appender sees both those and the mod's own INFO lines, which is where
 * every auto puzzle explains why it is waiting.
 *
 * <p>Installed once per client and never removed: the root logger outlives every scenario, and a second appender
 * would double every line.
 */
public final class LogTap {

    private static final int RING = 4000;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static volatile boolean installed;
    private static long seq;

    private LogTap() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        AbstractAppender tap = new AbstractAppender("testkit-logtap", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
                String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
                boolean mod = logger.startsWith("killer560smod");
                boolean chat = msg.contains("[CHAT]");
                if (!mod && !chat) {
                    return;
                }
                String line = String.format("%s %-5s %s %s", time(event.getTimeMillis()), event.getLevel(),
                        mod ? "(" + logger.replace("killer560smod-", "") + ")" : "", msg);
                synchronized (LINES) {
                    seq++;
                    LINES.addLast(seq + "\t" + line);
                    while (LINES.size() > RING) {
                        LINES.removeFirst();
                    }
                }
            }
        };
        tap.start();
        context.getConfiguration().addAppender(tap);
        context.getRootLogger().addAppender(tap);
        installed = true;
    }

    private static String time(long ms) {
        return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
                .toString();
    }

    /** A position in the stream, for {@link #since}. */
    public static long mark() {
        synchronized (LINES) {
            return seq;
        }
    }

    /**
     * The mod's own ERROR lines after {@code mark} (from a {@code killer560smod*} logger, not chat). {@code Mod.assertHealthy}
     * fails a case on any of these.
     */
    public static List<String> modErrorsSince(long mark) {
        List<String> out = new ArrayList<>();
        for (String line : since(mark)) {
            // "HH:MM:SS.fff ERROR (logger) message" - see the format in install().
            String[] parts = line.split(" ", 4);
            if (parts.length >= 3 && parts[1].equals("ERROR") && parts[2].startsWith("(")) {
                out.add(line);
            }
        }
        return out;
    }

    /** Every captured line after {@code mark}, oldest first, without the sequence number. */
    public static List<String> since(long mark) {
        List<String> out = new ArrayList<>();
        synchronized (LINES) {
            for (String s : LINES) {
                int tab = s.indexOf('\t');
                if (Long.parseLong(s.substring(0, tab)) > mark) {
                    out.add(s.substring(tab + 1));
                }
            }
        }
        return out;
    }
}
