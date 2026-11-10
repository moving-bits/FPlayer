package net.movingbits.fplayer;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Brings a running playlist in line with a list of files: files that are missing from the list are
 * removed, new ones are inserted at their position in tree order. The playlist is always a
 * subsequence of the files in tree order (the player manages the shuffle order separately).
 * Used by the activity (tree selection changed) and the service (files of a profile rescanned).
 */
final class PlaylistSync {

    private PlaylistSync() {
    }

    /**
     * @param player    the player or a controller of it
     * @param files     the files the playlist should contain, in tree order
     * @param profileId the profile the playlist belongs to, for newly inserted items
     */
    static void apply(final Player player, final List<TreeNode> files, final String profileId) {
        final Set<String> wantedIds = new HashSet<>();
        for (TreeNode file : files) {
            wantedIds.add(file.uri.toString());
        }
        final List<String> playlist = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) {
            playlist.add(player.getMediaItemAt(i).mediaId);
        }

        // remove files that are gone, contiguous ranges in one go (from back to front)
        int i = playlist.size();
        while (i > 0) {
            final int runEnd = i;
            while (i > 0 && !wantedIds.contains(playlist.get(i - 1))) {
                i--;
            }
            if (i < runEnd) {
                player.removeMediaItems(i, runEnd);
                playlist.subList(i, runEnd).clear();
            } else {
                i--;
            }
        }

        // insert new files
        final Set<String> present = new HashSet<>(playlist);
        final List<TreeNode> pending = new ArrayList<>();
        int position = 0;
        for (TreeNode file : files) {
            final String id = file.uri.toString();
            if (!present.contains(id)) {
                pending.add(file);
                continue;
            }
            position = insertPending(player, playlist, position, pending, profileId);
            position = playlist.indexOf(id) + 1;
        }
        insertPending(player, playlist, position, pending, profileId);
    }

    private static int insertPending(final Player player, final List<String> playlist, final int position,
                                     final List<TreeNode> pending, final String profileId) {
        if (pending.isEmpty()) {
            return position;
        }
        final List<MediaItem> items = new ArrayList<>(pending.size());
        final List<String> ids = new ArrayList<>(pending.size());
        for (TreeNode file : pending) {
            items.add(PlaybackService.toMediaItem(file, profileId, false));
            ids.add(file.uri.toString());
        }
        player.addMediaItems(position, items);
        playlist.addAll(position, ids);
        pending.clear();
        return position + items.size();
    }
}
