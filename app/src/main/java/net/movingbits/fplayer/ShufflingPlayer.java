package net.movingbits.fplayer;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Controls the shuffle order of the ExoPlayer:
 * <ul>
 *   <li>A new shuffle order always starts with the current (or chosen start) track. Otherwise
 *       ExoPlayer picks an arbitrary order in which tracks "before" the start track would never be
 *       played.</li>
 *   <li>When a playlist is set up to continue a profile, the saved order provided by
 *       {@code savedOrder} is used instead, so that already played tracks are not repeated.</li>
 * </ul>
 * Also calls {@code beforePlaylistChange} before every playlist change so that the position of the
 * previous track can be saved.
 */
@OptIn(markerClass = UnstableApi.class)
final class ShufflingPlayer extends ForwardingPlayer {

    private final ExoPlayer exoPlayer;
    private final Runnable beforePlaylistChange;
    /** Returns the saved order (playlist indices) for new media items, or {@code null}. */
    private final Function<List<MediaItem>, int[]> savedOrder;
    private final Random random = new Random();

    ShufflingPlayer(final ExoPlayer exoPlayer, final Runnable beforePlaylistChange,
                    final Function<List<MediaItem>, int[]> savedOrder) {
        super(exoPlayer);
        this.exoPlayer = exoPlayer;
        this.beforePlaylistChange = beforePlaylistChange;
        this.savedOrder = savedOrder;
    }

    @Override
    public void setShuffleModeEnabled(final boolean shuffleModeEnabled) {
        if (shuffleModeEnabled && !exoPlayer.getShuffleModeEnabled()) {
            applyNewShuffleOrder(exoPlayer.getCurrentMediaItemIndex());
        }
        super.setShuffleModeEnabled(shuffleModeEnabled);
    }

    @Override
    public void setMediaItems(@NonNull final List<MediaItem> mediaItems, final int startIndex, final long startPositionMs) {
        beforePlaylistChange.run();
        super.setMediaItems(mediaItems, startIndex, startPositionMs);
        applyShuffleOrderFor(mediaItems, startIndex);
    }

    @Override
    public void setMediaItems(@NonNull final List<MediaItem> mediaItems, final boolean resetPosition) {
        beforePlaylistChange.run();
        super.setMediaItems(mediaItems, resetPosition);
        applyShuffleOrderFor(mediaItems, resetPosition ? 0 : exoPlayer.getCurrentMediaItemIndex());
    }

    @Override
    public void setMediaItems(@NonNull final List<MediaItem> mediaItems) {
        setMediaItems(mediaItems, true);
    }

    /** The media IDs in shuffle order, or {@code null} if shuffle mode is off or the playlist is empty. */
    List<String> getShuffledMediaIds() {
        if (!exoPlayer.getShuffleModeEnabled()) {
            return null;
        }
        final Timeline timeline = exoPlayer.getCurrentTimeline();
        if (timeline.isEmpty()) {
            return null;
        }
        final List<String> ids = new ArrayList<>(timeline.getWindowCount());
        final Timeline.Window window = new Timeline.Window();
        int index = timeline.getFirstWindowIndex(true);
        while (index != C.INDEX_UNSET) {
            ids.add(timeline.getWindow(index, window).mediaItem.mediaId);
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true);
        }
        return ids;
    }

    private void applyShuffleOrderFor(final List<MediaItem> mediaItems, final int startIndex) {
        if (!exoPlayer.getShuffleModeEnabled()) {
            return;
        }
        final int[] order = savedOrder.apply(mediaItems);
        if (order != null && order.length == exoPlayer.getMediaItemCount()) {
            exoPlayer.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(order, random.nextLong()));
        } else {
            applyNewShuffleOrder(startIndex);
        }
    }

    private void applyNewShuffleOrder(final int requestedFirstIndex) {
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
