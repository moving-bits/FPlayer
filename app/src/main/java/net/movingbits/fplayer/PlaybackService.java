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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.ArrayList;
import java.util.List;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Background playback via Media3. Keeps running independently of the activity (screen rotation,
 * app in background) and saves the playback position per directory.
 */
public class PlaybackService extends MediaSessionService {

    /** Extra in {@link MediaItem.RequestMetadata}: document URI of the file's directory. */
    static final String EXTRA_DIRECTORY_URI = "net.movingbits.fplayer.DIRECTORY_URI";

    private static final long SAVE_INTERVAL_MS = 5_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            saveResumePosition();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private ExoPlayer player;
    private MediaSession session;
    private ResumeStore resumeStore;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();
        resumeStore = new ResumeStore(this);
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player.setShuffleModeEnabled(Settings.isShuffle(this));
        player.setRepeatMode(Settings.isRepeat(this) ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        player.addListener(new PlayerListener());

        final PendingIntent sessionActivity = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, new ShufflingPlayer(player, this::saveResumePosition))
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
        saveResumePosition();
        session.release();
        player.release();
        super.onDestroy();
    }

    private void saveResumePosition() {
        if (player.getPlaybackState() == Player.STATE_ENDED) {
            return;
        }
        final MediaItem item = player.getCurrentMediaItem();
        final String directory = directoryOf(item);
        if (directory != null) {
            resumeStore.put(directory, item.mediaId, Math.max(0, player.getCurrentPosition()));
        }
    }

    @Nullable
    static String directoryOf(@Nullable final MediaItem item) {
        if (item == null) {
            return null;
        }
        final Bundle extras = item.requestMetadata.extras;
        return extras != null ? extras.getString(EXTRA_DIRECTORY_URI) : null;
    }

    private final class PlayerListener implements Player.Listener {

        @Override
        public void onIsPlayingChanged(final boolean isPlaying) {
            handler.removeCallbacks(periodicSave);
            if (isPlaying) {
                handler.postDelayed(periodicSave, SAVE_INTERVAL_MS);
            } else {
                saveResumePosition();
            }
        }

        @Override
        public void onMediaItemTransition(@Nullable final MediaItem mediaItem, final int reason) {
            saveResumePosition();
        }

        @Override
        public void onPlaybackStateChanged(final int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
                // directory played to the end: start from the beginning next time
                final String directory = directoryOf(player.getCurrentMediaItem());
                if (directory != null) {
                    resumeStore.remove(directory);
                }
            }
        }

        @Override
        public void onShuffleModeEnabledChanged(final boolean shuffleModeEnabled) {
            // can also be toggled via the media notification
            Settings.setShuffle(PlaybackService.this, shuffleModeEnabled);
        }

        @Override
        public void onRepeatModeChanged(final int repeatMode) {
            Settings.setRepeat(PlaybackService.this, repeatMode != Player.REPEAT_MODE_OFF);
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
