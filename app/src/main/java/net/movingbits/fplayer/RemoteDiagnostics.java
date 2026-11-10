package net.movingbits.fplayer;

import android.text.TextUtils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Temporary: the most recent requests of external controllers (e.g. a smart band), kept in memory
 * and shown in the about popup, for devices where logcat is not available.
 */
final class RemoteDiagnostics {

    private static final int MAX_LINES = 60;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> LINES = new ArrayDeque<>();

    private RemoteDiagnostics() {
    }

    static synchronized void add(final String message) {
        if (LINES.size() == MAX_LINES) {
            LINES.removeFirst();
        }
        LINES.addLast(LocalTime.now().format(TIME) + " " + message);
    }

    static synchronized String dump() {
        return TextUtils.join("\n", LINES);
    }
}
