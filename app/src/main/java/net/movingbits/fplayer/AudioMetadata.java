package net.movingbits.fplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;

/** Tag information of an audio file. */
final class AudioMetadata {

    private static final String TAG = "AudioMetadata";

    final String title;
    final String artist;
    final String album;
    final long durationMs;

    private AudioMetadata(final String title, final String artist, final String album, final long durationMs) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
    }

    /** Reads the tags of a file. Blocking – do not call on the main thread. */
    static AudioMetadata read(final Context context, final Uri uri) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return new AudioMetadata(
                    emptyToNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                    emptyToNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                    emptyToNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                    parseDuration(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read metadata: " + uri, e);
            return new AudioMetadata(null, null, null, TreeNode.DURATION_UNKNOWN);
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    /**
     * Reads the embedded cover image, downscaled to roughly {@code maxSize} pixels per side at most.
     * Returns {@code null} if there is none. Blocking – do not call on the main thread.
     */
    static Bitmap readCover(final Context context, final Uri uri, final int maxSize) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            final byte[] data = retriever.getEmbeddedPicture();
            if (data == null) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            int sampleSize = 1;
            while (options.outWidth / (sampleSize * 2) >= maxSize && options.outHeight / (sampleSize * 2) >= maxSize) {
                sampleSize *= 2;
            }
            options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read cover: " + uri, e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // nothing to do
            }
        }
    }

    private static String emptyToNull(final String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static long parseDuration(final String s) {
        if (s == null) {
            return TreeNode.DURATION_UNKNOWN;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return TreeNode.DURATION_UNKNOWN;
        }
    }
}
