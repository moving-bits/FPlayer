package net.movingbits.fplayer;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Ensures that in shuffle mode the current (or chosen start) track is always the first one of the
 * shuffle order. Otherwise ExoPlayer picks an arbitrary order in which tracks "before" the start
 * track would never be played. Calls {@code beforePlaylistChange} before every playlist change so
 * that the position of the previous track can be saved.
 */
@OptIn(markerClass = UnstableApi.class)
final class ShufflingPlayer extends ForwardingPlayer {

    private final ExoPlayer exoPlayer;
    private final Runnable beforePlaylistChange;
    private final Random random = new Random();

    ShufflingPlayer(final ExoPlayer exoPlayer, final Runnable beforePlaylistChange) {
        super(exoPlayer);
        this.exoPlayer = exoPlayer;
        this.beforePlaylistChange = beforePlaylistChange;
    }

    @Override
    public void setShuffleModeEnabled(final boolean shuffleModeEnabled) {
        if (shuffleModeEnabled && !exoPlayer.getShuffleModeEnabled()) {
            applyShuffleOrder(exoPlayer.getCurrentMediaItemIndex());
        }
        super.setShuffleModeEnabled(shuffleModeEnabled);
    }

    @Override
    public void setMediaItems(@NonNull final List<MediaItem> mediaItems, final int startIndex, final long startPositionMs) {
        beforePlaylistChange.run();
        super.setMediaItems(mediaItems, startIndex, startPositionMs);
        if (exoPlayer.getShuffleModeEnabled()) {
            applyShuffleOrder(startIndex);
        }
    }

    @Override
    public void setMediaItems(@NonNull final List<MediaItem> mediaItems, final boolean resetPosition) {
        beforePlaylistChange.run();
        super.setMediaItems(mediaItems, resetPosition);
        if (exoPlayer.getShuffleModeEnabled()) {
            applyShuffleOrder(resetPosition ? 0 : exoPlayer.getCurrentMediaItemIndex());
        }
    }

    @Override
    public void setMediaItems(@NonNull final List<MediaItem> mediaItems) {
        setMediaItems(mediaItems, true);
    }

    private void applyShuffleOrder(final int requestedFirstIndex) {
        final int count = exoPlayer.getMediaItemCount();
        if (count == 0) {
            return;
        }
        final int firstIndex = requestedFirstIndex >= 0 && requestedFirstIndex < count ? requestedFirstIndex : 0;
        final List<Integer> others = new ArrayList<>(count - 1);
        for (int i = 0; i < count; i++) {
            if (i != firstIndex) {
                others.add(i);
            }
        }
        Collections.shuffle(others, random);
        final int[] order = new int[count];
        order[0] = firstIndex;
        for (int i = 1; i < count; i++) {
            order[i] = others.get(i - 1);
        }
        exoPlayer.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(order, random.nextLong()));
    }
}
