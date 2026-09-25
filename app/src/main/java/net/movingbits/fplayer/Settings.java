package net.movingbits.fplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * App-wide configuration: the base directories, shared by all profiles. Everything else is stored
 * per profile in {@link ProfileStore}.
 */
final class Settings {

    private static final String PREFS = "settings";
    private static final String KEY_ROOTS = "roots";

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
}
