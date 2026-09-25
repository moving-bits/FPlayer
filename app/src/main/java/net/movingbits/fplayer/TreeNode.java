package net.movingbits.fplayer;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * An entry in the directory tree: either a directory or a supported audio file.
 * Only modified on the main thread (exception: construction while scanning in the background,
 * before the node is published).
 */
final class TreeNode {

    static final int CHECK_NONE = 0;
    static final int CHECK_PARTIAL = 1;
    static final int CHECK_ALL = 2;

    static final long DURATION_UNKNOWN = -1;

    /** Tree URI of the base directory this node belongs to (basis of the SAF permission). */
    final Uri treeUri;
    /** Document URI of this entry (built from the tree URI). */
    final Uri uri;
    final String documentId;
    final String name;
    final boolean directory;
    final TreeNode parent;
    final int depth;
    /** Subdirectories and audio files in read order. */
    final List<TreeNode> children = new ArrayList<>();

    boolean expanded;

    // files only
    boolean selected;

    // directories only: counters for the whole subtree or for this directory only
    int totalFiles;
    int selectedFiles;
    int directFiles;

    // metadata (files only), loaded on demand
    boolean metadataLoaded;
    boolean metadataLoading;
    String title;
    String artist;
    String album;
    long durationMs = DURATION_UNKNOWN;

    TreeNode(final Uri treeUri, final Uri uri, final String documentId, final String name, final boolean directory, final TreeNode parent) {
        this.treeUri = treeUri;
        this.uri = uri;
        this.documentId = documentId;
        this.name = name;
        this.directory = directory;
        this.parent = parent;
        this.depth = parent == null ? 0 : parent.depth + 1;
    }

    boolean isRoot() {
        return parent == null;
    }

    int getCheckState() {
        if (!directory) {
            return selected ? CHECK_ALL : CHECK_NONE;
        }
        if (selectedFiles == 0) {
            return CHECK_NONE;
        }
        return selectedFiles == totalFiles ? CHECK_ALL : CHECK_PARTIAL;
    }

    boolean isDescendantOf(final TreeNode ancestor) {
        for (TreeNode n = this; n != null; n = n.parent) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    void applyMetadata(final AudioMetadata metadata) {
        title = metadata.title;
        artist = metadata.artist;
        album = metadata.album;
        durationMs = metadata.durationMs;
        metadataLoaded = true;
        metadataLoading = false;
    }
}
