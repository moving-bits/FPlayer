package net.movingbits.fplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists all profiles and knows the active one. There is always exactly one active profile; the
 * default profile is created on first use and can be renamed but not deleted.
 *
 * <p>Shared by the UI and the {@link PlaybackService} (same process), so all methods are
 * synchronized. The profiles are stored in one JSON file; the shuffle play order of each profile,
 * which can be large, is kept in a separate file per profile and written in the background.
 */
final class ProfileStore {

    static final String DEFAULT_ID = "default";
    static final String DEFAULT_NAME = "default";
    private static final int DEFAULT_COLOR = 4;

    /** Twelve well distinguishable profile colors (ARGB). */
    static final int[] COLORS = {
            0xFFD32F2F, // red
            0xFFC2185B, // pink
            0xFF7B1FA2, // purple
            0xFF3949AB, // indigo
            0xFF1E88E5, // blue
            0xFF00ACC1, // cyan
            0xFF00897B, // teal
            0xFF43A047, // green
            0xFF9E9D24, // olive
            0xFFFFB300, // amber
            0xFFF4511E, // deep orange
            0xFF6D4C41, // brown
    };

    private static final String TAG = "ProfileStore";
    private static final String FILE_NAME = "profiles.json";
    private static final String ORDER_DIR = "profile_orders";

    private static ProfileStore instance;

    private final Context context;
    private final AtomicFile file;
    private final File orderDir;
    private final ExecutorService orderWriter = Executors.newSingleThreadExecutor();
    private final List<Profile> profiles = new ArrayList<>();
    /** Cached shuffle orders by profile id; a missing key means "not loaded yet". */
    private final Map<String, List<String>> orders = new HashMap<>();
    private String activeId = DEFAULT_ID;

    private ProfileStore(final Context context) {
        this.context = context.getApplicationContext();
        file = new AtomicFile(new File(this.context.getFilesDir(), FILE_NAME));
        orderDir = new File(this.context.getFilesDir(), ORDER_DIR);
        load();
    }

    static synchronized ProfileStore get(final Context context) {
        if (instance == null) {
            instance = new ProfileStore(context);
        }
        return instance;
    }

    // ----- Profiles -----

    synchronized Profile getActive() {
        final Profile active = find(activeId);
        return active != null ? active : find(DEFAULT_ID);
    }

    synchronized Profile find(final String id) {
        for (Profile profile : profiles) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return null;
    }

    /** All profiles: the default profile first, then by last use (most recent first). */
    synchronized List<Profile> getSorted() {
        final List<Profile> sorted = new ArrayList<>(profiles);
        sorted.sort((a, b) -> {
            if (a.isDefault() != b.isDefault()) {
                return a.isDefault() ? -1 : 1;
            }
            return Long.compare(b.lastUsed, a.lastUsed);
        });
        return sorted;
    }

    synchronized boolean isNameTaken(final String name, final String exceptId) {
        for (Profile profile : profiles) {
            if (profile.name.equalsIgnoreCase(name) && !profile.id.equals(exceptId)) {
                return true;
            }
        }
        return false;
    }

    /** Name suggestion for a new profile: "Profil X" with X = number of profiles + 1. */
    synchronized String suggestName(final String pattern) {
        int number = profiles.size() + 1;
        String name = String.format(pattern, number);
        while (isNameTaken(name, null)) {
            number++;
            name = String.format(pattern, number);
        }
        return name;
    }

    /** The color used least by the existing profiles (the first one of these). */
    synchronized int suggestColor() {
        final int[] usage = new int[COLORS.length];
        for (Profile profile : profiles) {
            usage[profile.color]++;
        }
        int best = 0;
        for (int i = 1; i < usage.length; i++) {
            if (usage[i] < usage[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Creates a profile with the settings of the active profile and activates it. */
    synchronized Profile createFromActive(final String name, final int color) {
        final Profile source = getActive();
        final Profile profile = new Profile(UUID.randomUUID().toString(), name, color);
        profile.copySettingsFrom(source);
        profiles.add(profile);
        final List<String> order = getShuffleOrder(source.id);
        if (order != null) {
            setShuffleOrder(profile.id, order);
        }
        activate(profile.id);
        return profile;
    }

    synchronized void activate(final String id) {
        final Profile profile = find(id);
        if (profile == null) {
            return;
        }
        activeId = id;
        profile.lastUsed = System.currentTimeMillis();
        save();
    }

    synchronized void rename(final String id, final String name, final int color) {
        final Profile profile = find(id);
        if (profile != null) {
            profile.name = name;
            profile.color = color;
            save();
        }
    }

    /**
     * Deletes a profile (never the default one). If it was the active profile, the default profile
     * becomes active. Returns whether the active profile changed.
     */
    synchronized boolean delete(final String id) {
        final Profile profile = find(id);
        if (profile == null || profile.isDefault()) {
            return false;
        }
        profiles.remove(profile);
        setShuffleOrder(id, null);
        final boolean wasActive = id.equals(activeId);
        if (wasActive) {
            activate(DEFAULT_ID);
        } else {
            save();
        }
        return wasActive;
    }

    // ----- Settings of a profile -----

    synchronized void setSelection(final Set<String> selection) {
        final Profile active = getActive();
        active.selection.clear();
        active.selection.addAll(selection);
        save();
    }

    synchronized void setShuffle(final String id, final boolean shuffle) {
        final Profile profile = find(id);
        if (profile != null && profile.shuffle != shuffle) {
            profile.shuffle = shuffle;
            if (!shuffle) {
                setShuffleOrder(id, null);
            }
            save();
        }
    }

    synchronized void setRepeat(final String id, final boolean repeat) {
        final Profile profile = find(id);
        if (profile != null && profile.repeat != repeat) {
            profile.repeat = repeat;
            save();
        }
    }

    /** Stores the current track and position; {@code fileUri == null} means "start from the beginning". */
    synchronized void setPlayback(final String id, final String fileUri, final long positionMs) {
        final Profile profile = find(id);
        if (profile == null) {
            return; // deleted meanwhile
        }
        if (Objects.equals(profile.currentFile, fileUri) && profile.positionMs == positionMs) {
            return;
        }
        profile.currentFile = fileUri;
        profile.positionMs = fileUri != null ? positionMs : 0;
        save();
    }

    /** Removes everything below a removed base directory from all profiles. */
    synchronized void removeWithPrefix(final String prefix) {
        for (Profile profile : profiles) {
            profile.selection.removeIf(uri -> uri.startsWith(prefix));
            if (profile.currentFile != null && profile.currentFile.startsWith(prefix)) {
                profile.currentFile = null;
                profile.positionMs = 0;
            }
        }
        save();
    }

    /** Forgets current tracks that no longer exist. Blocking – do not call on the main thread. */
    void removeMissingCurrentFiles(final Predicate<String> exists) {
        final Map<String, String> current = new HashMap<>();
        synchronized (this) {
            for (Profile profile : profiles) {
                if (profile.currentFile != null) {
                    current.put(profile.id, profile.currentFile);
                }
            }
        }
        for (Map.Entry<String, String> e : current.entrySet()) {
            if (!exists.test(e.getValue())) {
                synchronized (this) {
                    final Profile profile = find(e.getKey());
                    if (profile != null && e.getValue().equals(profile.currentFile)) {
                        profile.currentFile = null;
                        profile.positionMs = 0;
                        save();
                    }
                }
            }
        }
    }

    // ----- Shuffle order -----

    /** The saved shuffle play order (document URIs), or {@code null} if there is none. */
    synchronized List<String> getShuffleOrder(final String id) {
        if (!orders.containsKey(id)) {
            orders.put(id, readOrder(id));
        }
        final List<String> order = orders.get(id);
        return order != null ? Collections.unmodifiableList(order) : null;
    }

    synchronized void setShuffleOrder(final String id, final List<String> order) {
        final List<String> copy = order != null ? new ArrayList<>(order) : null;
        if (copy != null && copy.equals(orders.get(id))) {
            return;
        }
        orders.put(id, copy);
        final File target = orderFile(id);
        orderWriter.execute(() -> writeOrder(target, copy));
    }

    private File orderFile(final String id) {
        return new File(orderDir, id + ".txt");
    }

    private List<String> readOrder(final String id) {
        final File source = orderFile(id);
        if (!source.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(source)) {
            final String content = new String(readAll(in), StandardCharsets.UTF_8);
            final List<String> order = new ArrayList<>();
            for (String line : content.split("\n")) {
                if (!line.isEmpty()) {
                    order.add(line);
                }
            }
            return order;
        } catch (IOException e) {
            Log.w(TAG, "Cannot read shuffle order of " + id, e);
            return null;
        }
    }

    private static void writeOrder(final File target, final List<String> order) {
        if (order == null) {
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Cannot delete " + target);
            }
            return;
        }
        final File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Cannot create " + dir);
            return;
        }
        final AtomicFile atomic = new AtomicFile(target);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(String.join("\n", order).getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (IOException e) {
            Log.w(TAG, "Cannot write " + target, e);
            if (out != null) {
                atomic.failWrite(out);
            }
        }
    }

    // ----- Persistence -----

    private void load() {
        try (FileInputStream in = file.openRead()) {
            final JSONObject json = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            activeId = json.optString("active", DEFAULT_ID);
            final JSONArray array = json.getJSONArray("profiles");
            for (int i = 0; i < array.length(); i++) {
                profiles.add(fromJson(array.getJSONObject(i)));
            }
        } catch (FileNotFoundException e) {
            // first start
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Cannot read profiles, starting over", e);
            profiles.clear();
        }
        if (find(DEFAULT_ID) == null) {
            profiles.add(0, createDefault());
            save();
        }
        if (find(activeId) == null) {
            activeId = DEFAULT_ID;
        }
        getActive().lastUsed = System.currentTimeMillis();
        save();
    }

    /** Creates the default profile, taking over the settings of app versions without profiles. */
    private Profile createDefault() {
        final Profile profile = new Profile(DEFAULT_ID, DEFAULT_NAME, DEFAULT_COLOR);
        final SharedPreferences legacy = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        profile.shuffle = legacy.getBoolean("shuffle", false);
        profile.repeat = legacy.getBoolean("repeat", false);
        profile.selection.addAll(legacy.getStringSet("selection", Collections.emptySet()));
        legacy.edit().remove("shuffle").remove("repeat").remove("selection").apply();
        context.deleteSharedPreferences("resume"); // positions per directory are replaced by profiles
        return profile;
    }

    private void save() {
        FileOutputStream out = null;
        try {
            final JSONArray array = new JSONArray();
            for (Profile profile : profiles) {
                array.put(toJson(profile));
            }
            final JSONObject json = new JSONObject();
            json.put("active", activeId);
            json.put("profiles", array);
            out = file.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Cannot write profiles", e);
            if (out != null) {
                file.failWrite(out);
            }
        }
    }

    private static JSONObject toJson(final Profile profile) throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("id", profile.id);
        json.put("name", profile.name);
        json.put("color", profile.color);
        json.put("shuffle", profile.shuffle);
        json.put("repeat", profile.repeat);
        if (profile.currentFile != null) {
            json.put("file", profile.currentFile);
            json.put("position", profile.positionMs);
        }
        json.put("lastUsed", profile.lastUsed);
        json.put("selection", new JSONArray(profile.selection));
        return json;
    }

    private static Profile fromJson(final JSONObject json) throws JSONException {
        final int color = json.optInt("color", DEFAULT_COLOR);
        final Profile profile = new Profile(json.getString("id"), json.getString("name"),
                color >= 0 && color < COLORS.length ? color : DEFAULT_COLOR);
        profile.shuffle = json.optBoolean("shuffle");
        profile.repeat = json.optBoolean("repeat");
        profile.currentFile = json.has("file") ? json.getString("file") : null;
        profile.positionMs = json.optLong("position");
        profile.lastUsed = json.optLong("lastUsed");
        final JSONArray selection = json.optJSONArray("selection");
        if (selection != null) {
            for (int i = 0; i < selection.length(); i++) {
                profile.selection.add(selection.getString(i));
            }
        }
        return profile;
    }

    private static byte[] readAll(final FileInputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

}
