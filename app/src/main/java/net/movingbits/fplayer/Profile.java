package net.movingbits.fplayer;

import java.util.HashSet;
import java.util.Set;

/**
 * A user profile: its own tree selection, play order and repeat mode, and the playback position.
 * Instances are owned and persisted by {@link ProfileStore}; change them only through the store.
 */
final class Profile {

    final String id;
    String name;
    /** Index into {@link ProfileStore#COLORS}. */
    int color;
    /** Stored tree selection: document URIs of fully selected directories and of single files. */
    final Set<String> selection = new HashSet<>();
    boolean shuffle;
    boolean repeat;
    /** Document URI of the current track, or {@code null} if nothing is to be continued. */
    String currentFile;
    long positionMs;
    /** Time of the last activation (epoch milliseconds). */
    long lastUsed;

    Profile(final String id, final String name, final int color) {
        this.id = id;
        this.name = name;
        this.color = color;
    }

    boolean isDefault() {
        return ProfileStore.DEFAULT_ID.equals(id);
    }

    /** Copies everything except id, name, color and timestamp from another profile. */
    void copySettingsFrom(final Profile other) {
        selection.clear();
        selection.addAll(other.selection);
        shuffle = other.shuffle;
        repeat = other.repeat;
        currentFile = other.currentFile;
        positionMs = other.positionMs;
    }

    Set<String> copyOfSelection() {
        return new HashSet<>(selection);
    }
}
