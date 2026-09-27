package net.movingbits.fplayer;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;

import java.util.List;
import java.util.Map;

/**
 * Media3's default extractors plus {@link AsfExtractor} for WMA files. The ASF extractor comes
 * first: its sniffing only compares 16 bytes, and WMA files would otherwise run through all other
 * extractors' sniffing.
 */
@OptIn(markerClass = UnstableApi.class)
final class AudioExtractorsFactory implements ExtractorsFactory {

    private final DefaultExtractorsFactory defaults = new DefaultExtractorsFactory();

    @NonNull
    @Override
    public Extractor[] createExtractors() {
        return withAsf(defaults.createExtractors());
    }

    @NonNull
    @Override
    public Extractor[] createExtractors(@NonNull final Uri uri, @NonNull final Map<String, List<String>> responseHeaders) {
        return withAsf(defaults.createExtractors(uri, responseHeaders));
    }

    private static Extractor[] withAsf(final Extractor[] extractors) {
        final Extractor[] result = new Extractor[extractors.length + 1];
        result[0] = new AsfExtractor();
        System.arraycopy(extractors, 0, result, 1, extractors.length);
        return result;
    }
}
