package net.movingbits.fplayer;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Background playback via Media3. Keeps running independently of the activity (screen rotation,
 * app in background) and saves the playback state in the profile the playlist belongs to.
 *
 * <p>Every media item carries the id of its profile ({@link #EXTRA_PROFILE_ID}). Saving by this id
 * instead of the currently active profile keeps late saves of a replaced playlist (commands are
 * asynchronous) from ending up in a different profile after a profile switch.
 */
public class PlaybackService extends MediaSessionService {

    /** Extra in {@link MediaItem.RequestMetadata}: id of the profile the playlist belongs to. */
    static final String EXTRA_PROFILE_ID = "net.movingbits.fplayer.PROFILE_ID";
    /** Extra in {@link MediaItem.RequestMetadata}: continue with the profile's saved shuffle order. */
    static final String EXTRA_RESTORE_ORDER = "net.movingbits.fplayer.RESTORE_ORDER";

    private static final long SAVE_INTERVAL_MS = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            savePlaybackState();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private ExoPlayer player;
    private ShufflingPlayer shufflingPlayer;
    private MediaSession session;
    private ProfileStore profiles;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();
        profiles = ProfileStore.get(this);
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        final Profile active = profiles.getActive();
        player.setShuffleModeEnabled(active.shuffle);
        player.setRepeatMode(active.repeat ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        player.addListener(new PlayerListener());

        final PendingIntent sessionActivity = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        shufflingPlayer = new ShufflingPlayer(player, this::savePlaybackState, this::savedShuffleOrder);
        session = new MediaSession.Builder(this, shufflingPlayer)
                .setCallback(new SessionCallback())
                .setSessionActivity(sessionActivity)
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull final MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(periodicSave);
        savePlaybackState();
        session.release();
        player.release();
        super.onDestroy();
    }

    /** Saves the current track and position in the profile of the playlist. */
    private void savePlaybackState() {
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            return;
        }
        final MediaItem item = player.getCurrentMediaItem();
        final String profileId = profileOf(item);
        if (profileId != null) {
            profiles.setPlayback(profileId, item.mediaId, Math.max(0, player.getCurrentPosition()));
        }
    }

    private void saveShuffleOrder() {
        final String profileId = profileOf(player.getCurrentMediaItem());
        final List<String> order = shufflingPlayer.getShuffledMediaIds();
        if (profileId != null && order != null) {
            profiles.setShuffleOrder(profileId, order);
        }
    }

    /**
     * The saved shuffle order of the profile as playlist indices, if the new playlist asks for it.
     * Saved files that are no longer in the playlist are dropped, new ones are appended at random.
     */
    @Nullable
    private int[] savedShuffleOrder(final List<MediaItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        final Bundle extras = items.get(0).requestMetadata.extras;
        if (extras == null || !extras.getBoolean(EXTRA_RESTORE_ORDER)) {
            return null;
        }
        final List<String> saved = profiles.getShuffleOrder(extras.getString(EXTRA_PROFILE_ID));
        if (saved == null) {
            return null;
        }
        final Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            indices.put(items.get(i).mediaId, i);
        }
        final List<Integer> order = new ArrayList<>(items.size());
        for (String id : saved) {
            final Integer index = indices.remove(id);
            if (index != null) {
                order.add(index);
            }
        }
        final List<Integer> added = new ArrayList<>(indices.values());
        Collections.shuffle(added);
        order.addAll(added);
        final int[] result = new int[order.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = order.get(i);
        }
        return result;
    }

    @Nullable
    static String profileOf(@Nullable final MediaItem item) {
        if (item == null) {
            return null;
        }
        final Bundle extras = item.requestMetadata.extras;
        return extras != null ? extras.getString(EXTRA_PROFILE_ID) : null;
    }

    private final class PlayerListener implements Player.Listener {

        @Override
        public void onIsPlayingChanged(final boolean isPlaying) {
            handler.removeCallbacks(periodicSave);
            if (isPlaying) {
                handler.postDelayed(periodicSave, SAVE_INTERVAL_MS);
            } else {
                savePlaybackState();
            }
        }

        @Override
        public void onMediaItemTransition(@Nullable final MediaItem mediaItem, final int reason) {
            savePlaybackState();
        }

        @Override
        public void onTimelineChanged(@NonNull final Timeline timeline, final int reason) {
            saveShuffleOrder();
        }

        @Override
        public void onPlaybackStateChanged(final int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
                // played to the end: start from the beginning next time
                final String profileId = profileOf(player.getCurrentMediaItem());
                if (profileId != null) {
                    profiles.setPlayback(profileId, null, 0);
                    profiles.setShuffleOrder(profileId, null);
                }
            }
        }

        @Override
        public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
            // can also be toggled via the media notification
            final String profileId = profileOf(player.getCurrentMediaItem());
            profiles.setShuffle(profileId != null ? profileId : profiles.getActive().id, shuffleModeEnabled);
            saveShuffleOrder();
        }

        @Override
        public void onRepeatModeChanged(final int repeatMode) {
            final String profileId = profileOf(player.getCurrentMediaItem());
            profiles.setRepeat(profileId != null ? profileId : profiles.getActive().id,
                    repeatMode != Player.REPEAT_MODE_OFF);
        }

        @Override
        public void onPlayerError(@NonNull final PlaybackException error) {
            // skip files that cannot be played
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem();
                player.prepare();
            }
        }
    }

    private static final class SessionCallback implements MediaSession.Callback {

        /**
         * Controllers do not transfer the playback URI; it is carried in the RequestMetadata and
         * restored here.
         */
        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull final MediaSession mediaSession,
                                                                 @NonNull final MediaSession.ControllerInfo controller,
                                                                 @NonNull final List<MediaItem> mediaItems) {
            final List<MediaItem> resolved = new ArrayList<>(mediaItems.size());
            for (MediaItem item : mediaItems) {
                final Uri uri = item.requestMetadata.mediaUri;
                resolved.add(uri == null ? item : item.buildUpon().setUri(uri).build());
            }
            return Futures.immediateFuture(resolved);
        }
    }
}
