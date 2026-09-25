package net.movingbits.fplayer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers per directory (document URI) the last played file, the position within it and the time
 * it was saved. Written by the {@link PlaybackService} and read by the UI.
 */
final class ResumeStore {

    static final class Entry {
        final String fileUri;
        final long positionMs;
        final long savedAt;

        Entry(final String fileUri, final long positionMs, final long savedAt) {
            this.fileUri = fileUri;
            this.positionMs = positionMs;
            this.savedAt = savedAt;
        }
    }

    private static final String PREFS = "resume";

    private final SharedPreferences prefs;

    ResumeStore(final Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void put(final String directoryUri, final String fileUri, final long positionMs) {
        prefs.edit().putString(directoryUri, fileUri + "\n" + positionMs + "\n" + System.currentTimeMillis()).apply();
    }

    Entry get(final String directoryUri) {
        return parse(prefs.getString(directoryUri, null));
    }

    Map<String, Entry> getAll() {
        final Map<String, Entry> result = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            final Entry entry = e.getValue() instanceof String ? parse((String) e.getValue()) : null;
            if (entry != null) {
                result.put(e.getKey(), entry);
            }
        }
        return result;
    }

    void remove(final String directoryUri) {
        prefs.edit().remove(directoryUri).apply();
    }

    void removeWithPrefix(final String prefix) {
        final SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static Entry parse(final String value) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.split("\n");
        if (parts.length < 2) {
            return null;
        }
        try {
            final long savedAt = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
            return new Entry(parts[0], Long.parseLong(parts[1]), savedAt);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
