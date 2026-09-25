package net.movingbits.fplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent configuration: base directories, play order, repeat mode and tree selection. */
final class Settings {

    private static final String PREFS = "settings";
    private static final String KEY_ROOTS = "roots";
    private static final String KEY_SHUFFLE = "shuffle";
    private static final String KEY_REPEAT = "repeat";
    private static final String KEY_SELECTION = "selection";

    private Settings() {
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Tree URIs of the base directories in the order in which they were added. */
    static List<Uri> getRoots(final Context context) {
        final List<Uri> roots = new ArrayList<>();
        final String stored = prefs(context).getString(KEY_ROOTS, "");
        for (String line : stored.split("\n")) {
            if (!line.isEmpty()) {
                roots.add(Uri.parse(line));
            }
        }
        return roots;
    }

    static void setRoots(final Context context, final List<Uri> roots) {
        final StringBuilder sb = new StringBuilder();
        for (Uri uri : roots) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(uri.toString());
        }
        prefs(context).edit().putString(KEY_ROOTS, sb.toString()).apply();
    }

    static boolean isShuffle(final Context context) {
        return prefs(context).getBoolean(KEY_SHUFFLE, false);
    }

    static void setShuffle(final Context context, final boolean shuffle) {
        prefs(context).edit().putBoolean(KEY_SHUFFLE, shuffle).apply();
    }

    static boolean isRepeat(final Context context) {
        return prefs(context).getBoolean(KEY_REPEAT, false);
    }

    static void setRepeat(final Context context, final boolean repeat) {
        prefs(context).edit().putBoolean(KEY_REPEAT, repeat).apply();
    }

    /**
     * Stored selection in the tree: document URIs of fully selected directories as well as of
     * individually selected files.
     */
    static Set<String> getSelection(final Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_SELECTION, Collections.emptySet()));
    }

    static void setSelection(final Context context, final Set<String> selection) {
        prefs(context).edit().putStringSet(KEY_SELECTION, new HashSet<>(selection)).apply();
    }
}
