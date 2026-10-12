package net.firebreadmc.fbsmp.paper.admin;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Bounded in-memory ring buffer of recent server log lines, fed by a
 * {@link Handler} attached to the root logger. Used by the dashboard's Live
 * Console view; the authoritative full logs remain in {@code logs/latest.log}.
 */
public final class ConsoleBuffer extends Handler {
    private static final int CAPACITY = 500;

    private final Deque<String> lines = new ArrayDeque<>(CAPACITY);
    private final Object lock = new Object();

    public void install() {
        Logger.getLogger("").addHandler(this);
    }

    public void uninstall() {
        Logger.getLogger("").removeHandler(this);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return;
        }
        String line = Instant.ofEpochMilli(record.getMillis()) + " [" + record.getLevel().getName() + "] "
                + record.getMessage();
        append(line);
    }

    public void append(String line) {
        synchronized (lock) {
            while (lines.size() >= CAPACITY) {
                lines.pollFirst();
            }
            lines.addLast(line);
        }
    }

    public List<String> recent(int count) {
        synchronized (lock) {
            List<String> snapshot = new ArrayList<>(lines);
            int from = Math.max(0, snapshot.size() - Math.max(1, Math.min(CAPACITY, count)));
            return snapshot.subList(from, snapshot.size());
        }
    }

    @Override
    public void flush() {
        // No buffering to flush.
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
