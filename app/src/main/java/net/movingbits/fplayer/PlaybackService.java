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
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaConstants;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/**
 * Background playback via Media3. Keeps running independently of the activity (screen rotation,
 * app in background) and saves the playback state in the profile the playlist belongs to.
 *
 * <p>Every media item carries the id of its profile ({@link #EXTRA_PROFILE_ID}). Saving by this id
 * instead of the currently active profile keeps late saves of a replaced playlist (commands are
 * asynchronous) from ending up in a different profile after a profile switch.
 *
 * <p>Android Auto browses the profiles through the media library: its root lists them in display
 * order, and playing one (by tap or voice search) activates it. The activity need not be running,
 * so the service reads the profile's files itself.
 */
public class PlaybackService extends MediaLibraryService {

    /** Extra in {@link MediaItem.RequestMetadata}: id of the profile the playlist belongs to. */
    static final String EXTRA_PROFILE_ID = "net.movingbits.fplayer.PROFILE_ID";
    /** Extra in {@link MediaItem.RequestMetadata}: continue with the profile's saved shuffle order. */
    static final String EXTRA_RESTORE_ORDER = "net.movingbits.fplayer.RESTORE_ORDER";

    private static final long SAVE_INTERVAL_MS = 5_000;

    private static final String LIBRARY_ROOT_ID = "root";
    private static final String PROFILE_ITEM_PREFIX = "profile:";
    /** A voice query that names a profile by its position, e.g. "Profil 2". */
    private static final Pattern PROFILE_QUERY = Pattern.compile("profile?\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final List<String> NUMBERS_GERMAN = Arrays.asList(
            "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf");
    private static final List<String> NUMBERS_ENGLISH = Arrays.asList(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve");

    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
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
    private MediaLibrarySession session;
    private ProfileStore profiles;
    /** The active profile when Android Auto was last told about the profile list. */
    private String listedActiveProfileId;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();
        profiles = ProfileStore.get(this);

        // prefer own extensions (FFmpeg decoders) + add the ASF extractor for WMA files
        final DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        player = new ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, new AudioExtractorsFactory()))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        final Profile active = profiles.getActive();
        listedActiveProfileId = active.id;
        player.setShuffleModeEnabled(active.shuffle);
        player.setRepeatMode(active.repeat ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        player.addListener(new PlayerListener());

        final PendingIntent sessionActivity = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        shufflingPlayer = new ShufflingPlayer(player, this::savePlaybackState, this::savedShuffleOrder);
        session = new MediaLibrarySession.Builder(this, shufflingPlayer, new SessionCallback())
                .setSessionActivity(sessionActivity)
                .build();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull final MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        libraryExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
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

    /** The media item of a file in the playlist of the given profile. */
    static MediaItem toMediaItem(final TreeNode file, final String profileId, final boolean restoreShuffleOrder) {
        final Bundle extras = new Bundle();
        extras.putString(EXTRA_PROFILE_ID, profileId);
        extras.putBoolean(EXTRA_RESTORE_ORDER, restoreShuffleOrder);
        final MediaMetadata.Builder metadata = new MediaMetadata.Builder().setDisplayTitle(file.name);
        if (file.metadataLoaded) {
            metadata.setTitle(file.title != null ? file.title : file.name).setArtist(file.artist).setAlbumTitle(file.album);
        }
        return new MediaItem.Builder()
                .setMediaId(file.uri.toString())
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder()
                        .setMediaUri(file.uri)
                        .setExtras(extras)
                        .build())
                .setMediaMetadata(metadata.build())
                .build();
    }

    /** Controllers do not transfer the playback URI; it is carried in the RequestMetadata. */
    private static MediaItem withPlaybackUri(final MediaItem item) {
        final Uri uri = item.requestMetadata.mediaUri;
        return uri == null ? item : item.buildUpon().setUri(uri).build();
    }

    /** The number spoken in a voice query (digits or a German/English word), or 0. */
    private static int spokenNumber(final String text) {
        final String word = text.trim().toLowerCase(Locale.ROOT);
        if (word.matches("[0-9]{1,4}")) {
            return Integer.parseInt(word);
        }
        final int german = NUMBERS_GERMAN.indexOf(word);
        return (german >= 0 ? german : NUMBERS_ENGLISH.indexOf(word)) + 1;
    }

    /**
     * Replaces the playlist by the one of the given profile, like a profile switch in the activity:
     * the old playlist is cleared before shuffle and repeat are set, so that these don't end up in
     * the old profile.
     */
    @OptIn(markerClass = UnstableApi.class)
    private MediaSession.MediaItemsWithStartPosition switchToProfile(final Profile profile, final List<TreeNode> files) {
        savePlaybackState();
        shufflingPlayer.pause();
        shufflingPlayer.clearMediaItems();
        if (!profile.id.equals(profiles.getActive().id)) {
            profiles.activate(profile.id);
        }
        shufflingPlayer.setShuffleModeEnabled(profile.shuffle);
        shufflingPlayer.setRepeatMode(profile.repeat ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);

        int startIndex = profile.shuffle ? random.nextInt(files.size()) : 0;
        long startPositionMs = 0;
        boolean restoreOrder = false;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).uri.toString().equals(profile.currentFile)) {
                startIndex = i;
                startPositionMs = profile.positionMs;
                restoreOrder = true;
                break;
            }
        }
        final List<MediaItem> items = new ArrayList<>(files.size());
        for (TreeNode file : files) {
            items.add(withPlaybackUri(toMediaItem(file, profile.id, restoreOrder)));
        }
        return new MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs);
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
            // a profile switch changes the marker and the display order of the list in the car
            final String activeId = profiles.getActive().id;
            if (!activeId.equals(listedActiveProfileId)) {
                listedActiveProfileId = activeId;
                session.notifyChildrenChanged(LIBRARY_ROOT_ID, profiles.getSorted().size(), null);
            }
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

    @OptIn(markerClass = UnstableApi.class)
    private final class SessionCallback implements MediaLibrarySession.Callback {

        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull final MediaSession mediaSession,
                                                                 @NonNull final MediaSession.ControllerInfo controller,
                                                                 @NonNull final List<MediaItem> mediaItems) {
            final List<MediaItem> resolved = new ArrayList<>(mediaItems.size());
            for (MediaItem item : mediaItems) {
                resolved.add(withPlaybackUri(item));
            }
            return Futures.immediateFuture(resolved);
        }

        /** Requests from Android Auto name a profile or carry a voice query instead of files. */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                @NonNull final MediaSession mediaSession, @NonNull final MediaSession.ControllerInfo controller,
                @NonNull final List<MediaItem> mediaItems, final int startIndex, final long startPositionMs) {
            if (mediaItems.size() == 1) {
                final MediaItem item = mediaItems.get(0);
                if (item.mediaId.startsWith(PROFILE_ITEM_PREFIX)) {
                    return playProfile(profiles.find(item.mediaId.substring(PROFILE_ITEM_PREFIX.length())));
                }
                if (item.mediaId.equals(MediaItem.DEFAULT_MEDIA_ID) && item.requestMetadata.mediaUri == null) {
                    return playFromVoiceQuery(item.requestMetadata.searchQuery);
                }
            }
            return MediaLibrarySession.Callback.super.onSetMediaItems(
                    mediaSession, controller, mediaItems, startIndex, startPositionMs);
        }

        /** "Play" without a playlist, e.g. from Android Auto: continues the active profile. */
        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onPlaybackResumption(
                @NonNull final MediaSession mediaSession, @NonNull final MediaSession.ControllerInfo controller,
                final boolean isForPlayback) {
            return isForPlayback
                    ? playProfile(profiles.getActive())
                    : MediaLibrarySession.Callback.super.onPlaybackResumption(mediaSession, controller, false);
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull final MediaLibrarySession librarySession, @NonNull final MediaSession.ControllerInfo browser,
                @Nullable final LibraryParams params) {
            final Bundle extras = new Bundle();
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM);
            return Futures.immediateFuture(
                    LibraryResult.ofItem(rootItem(), new LibraryParams.Builder().setExtras(extras).build()));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull final MediaLibrarySession librarySession, @NonNull final MediaSession.ControllerInfo browser,
                @NonNull final String mediaId) {
            if (LIBRARY_ROOT_ID.equals(mediaId)) {
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null));
            }
            for (MediaItem item : profileItems()) {
                if (item.mediaId.equals(mediaId)) {
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null));
                }
            }
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                @NonNull final MediaLibrarySession librarySession, @NonNull final MediaSession.ControllerInfo browser,
                @NonNull final String parentId, final int page, final int pageSize, @Nullable final LibraryParams params) {
            final boolean hasProfiles = LIBRARY_ROOT_ID.equals(parentId) && page == 0;
            return Futures.immediateFuture(LibraryResult.ofItemList(
                    hasProfiles ? ImmutableList.copyOf(profileItems()) : ImmutableList.of(), params));
        }

        private MediaItem rootItem() {
            return new MediaItem.Builder()
                    .setMediaId(LIBRARY_ROOT_ID)
                    .setMediaMetadata(new MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).build())
                    .build();
        }

        /** The profiles in display order, numbered from 1. */
        private List<MediaItem> profileItems() {
            final String activeId = profiles.getActive().id;
            final List<Profile> sorted = profiles.getSorted();
            final List<MediaItem> items = new ArrayList<>(sorted.size());
            for (int i = 0; i < sorted.size(); i++) {
                final Profile profile = sorted.get(i);
                final CharSequence subtitle = profile.copyOfSelection().isEmpty()
                        ? getText(R.string.nothing_selected)
                        : profile.id.equals(activeId) ? getText(R.string.auto_profile_active) : null;
                items.add(new MediaItem.Builder()
                        .setMediaId(PROFILE_ITEM_PREFIX + profile.id)
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(getString(R.string.auto_profile_title, i + 1, profile.name))
                                .setSubtitle(subtitle)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build())
                        .build());
            }
            return items;
        }

        /**
         * Plays the profile with the name or position from a voice query. Any other query is a
         * general request for music, which continues the active profile.
         */
        private ListenableFuture<MediaSession.MediaItemsWithStartPosition> playFromVoiceQuery(@Nullable final String query) {
            final String spoken = query != null ? query.trim() : "";
            final List<Profile> sorted = profiles.getSorted();
            for (Profile profile : sorted) {
                if (profile.name.equalsIgnoreCase(spoken)) {
                    return playProfile(profile);
                }
            }
            final Matcher numbered = PROFILE_QUERY.matcher(spoken);
            if (!numbered.matches()) {
                return playProfile(profiles.getActive());
            }
            final int number = spokenNumber(numbered.group(1));
            return playProfile(number >= 1 && number <= sorted.size() ? sorted.get(number - 1) : null);
        }

        /**
         * Starts the playlist of a profile; a playlist of this profile that is already set up just
         * continues. A cancelled future makes Media3 ignore the request and leaves the player as it is.
         */
        private ListenableFuture<MediaSession.MediaItemsWithStartPosition> playProfile(@Nullable final Profile profile) {
            if (profile == null) {
                reportError(SessionError.ERROR_BAD_VALUE, R.string.auto_profile_not_found);
                return Futures.immediateCancelledFuture();
            }
            final Set<String> selection = profile.copyOfSelection();
            if (selection.isEmpty()) {
                reportEmptyProfile();
                return Futures.immediateCancelledFuture();
            }
            if (profile.id.equals(profileOf(player.getCurrentMediaItem())) && player.getPlaybackState() != Player.STATE_ENDED) {
                shufflingPlayer.prepare();
                shufflingPlayer.play();
                return Futures.immediateCancelledFuture();
            }
            final SettableFuture<MediaSession.MediaItemsWithStartPosition> result = SettableFuture.create();
            libraryExecutor.execute(() -> {
                final List<TreeNode> files = MainViewModel.loadSelectedFiles(PlaybackService.this, selection);
                handler.post(() -> {
                    if (files.isEmpty()) {
                        reportEmptyProfile();
                        result.cancel(false);
                    } else {
                        result.set(switchToProfile(profile, files));
                    }
                });
            });
            return result;
        }

        private void reportEmptyProfile() {
            reportError(SessionError.ERROR_SESSION_SETUP_REQUIRED, R.string.auto_profile_empty);
        }

        /** Shows a message in the car without interrupting the current playback. */
        private void reportError(final int code, final int message) {
            session.sendError(new SessionError(code, getString(message)));
        }
    }
}
