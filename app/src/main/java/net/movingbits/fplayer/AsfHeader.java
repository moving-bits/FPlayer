package net.movingbits.fplayer;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The header object of an ASF file (the container of WMA): the first audio stream, packet layout,
 * duration and tags. Only the parts needed for audio playback are parsed.
 *
 * <p>Reference: "Advanced Systems Format (ASF) Specification", revision 01.20.05.
 */
@OptIn(markerClass = UnstableApi.class)
final class AsfHeader {

    /** MIME types for the WMA codecs; the FFmpeg module maps them to its decoder names. */
    static final String MIME_WMA_V1 = "audio/x-ms-wmav1";
    static final String MIME_WMA_V2 = "audio/x-ms-wma";
    static final String MIME_WMA_PRO = "audio/x-ms-wmapro";
    static final String MIME_WMA_LOSSLESS = "audio/x-ms-wmalossless";
    static final String MIME_WMA_VOICE = "audio/x-ms-wmavoice";

    static final byte[] HEADER_OBJECT = guid("75B22630-668E-11CF-A6D9-00AA0062CE6C");
    static final byte[] DATA_OBJECT = guid("75B22636-668E-11CF-A6D9-00AA0062CE6C");
    private static final byte[] FILE_PROPERTIES = guid("8CABDCA1-A947-11CF-8EE4-00C00C205365");
    private static final byte[] STREAM_PROPERTIES = guid("B7DC0791-A9B7-11CF-8EE6-00C00C205365");
    private static final byte[] AUDIO_MEDIA = guid("F8699E40-5B4D-11CF-A8FD-00805F5C442B");
    private static final byte[] AUDIO_SPREAD = guid("BFC3CD50-618F-11CF-8BB2-00AA00B4E220");
    private static final byte[] CONTENT_DESCRIPTION = guid("75B22633-668E-11CF-A6D9-00AA0062CE6C");
    private static final byte[] EXTENDED_CONTENT_DESCRIPTION = guid("D2D0A440-E307-11D2-97F0-00A0C95EA850");

    /** Size of the fixed part of the header object: GUID, size, object count, two reserved bytes. */
    static final int HEADER_PREFIX_SIZE = 30;
    /** Size of the data object header preceding the data packets. */
    static final int DATA_OBJECT_HEADER_SIZE = 50;
    /** Upper limit for a header object (it may contain cover images). */
    static final long MAX_HEADER_SIZE = 32L * 1024 * 1024;

    private static final int WAVE_FORMAT_MPEG_LAYER3 = 0x0055;
    private static final int WAVE_FORMAT_WMAVOICE = 0x000A;
    private static final int WAVE_FORMAT_WMAV1 = 0x0160;
    private static final int WAVE_FORMAT_WMAV2 = 0x0161;
    private static final int WAVE_FORMAT_WMAPRO = 0x0162;
    private static final int WAVE_FORMAT_WMALOSSLESS = 0x0163;

    // File properties
    long packetCount;
    /** Play duration without preroll in microseconds, or 0 if unknown. */
    long durationUs;
    long prerollMs;
    boolean seekable;
    int packetSize;

    // First audio stream; streamNumber == 0 means "no audio stream found"
    int streamNumber;
    int formatTag;
    int channels;
    int sampleRate;
    int averageBytesPerSecond;
    int blockAlign;
    int bitsPerSample;
    byte[] codecData = new byte[0];
    // Audio spread error correction (interleaving of media objects)
    int spreadSpan;
    int spreadPacketLength;
    int spreadChunkLength;

    // Tags
    @Nullable String title;
    @Nullable String artist;
    @Nullable String album;
    @Nullable byte[] picture;

    /** Whether {@code data} starts with the given GUID at {@code offset}. */
    static boolean startsWith(final byte[] data, final int offset, final byte[] guid) {
        if (data.length - offset < guid.length) {
            return false;
        }
        for (int i = 0; i < guid.length; i++) {
            if (data[offset + i] != guid[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a complete header object ({@code data} starts with the header object GUID).
     *
     * @throws IOException if the header is malformed
     */
    static AsfHeader parse(final byte[] data) throws IOException {
        if (!startsWith(data, 0, HEADER_OBJECT) || data.length < HEADER_PREFIX_SIZE) {
            throw new IOException("Not an ASF header");
        }
        final AsfHeader header = new AsfHeader();
        final ParsableByteArray in = new ParsableByteArray(data);
        in.setPosition(HEADER_PREFIX_SIZE);
        while (in.bytesLeft() >= 24) {
            final int objectStart = in.getPosition();
            final byte[] guid = new byte[16];
            in.readBytes(guid, 0, 16);
            final long size = in.readLittleEndianLong();
            if (size < 24 || size > in.limit() - objectStart) {
                throw new IOException("Invalid ASF object size " + size);
            }
            final int objectEnd = objectStart + (int) size;
            if (Arrays.equals(guid, FILE_PROPERTIES)) {
                header.parseFileProperties(in);
            } else if (Arrays.equals(guid, STREAM_PROPERTIES) && header.streamNumber == 0) {
                header.parseStreamProperties(in);
            } else if (Arrays.equals(guid, CONTENT_DESCRIPTION)) {
                header.parseContentDescription(in);
            } else if (Arrays.equals(guid, EXTENDED_CONTENT_DESCRIPTION)) {
                header.parseExtendedContentDescription(in, objectEnd);
            }
            in.setPosition(objectEnd);
        }
        if (header.packetSize <= 0) {
            throw new IOException("ASF file properties missing");
        }
        return header;
    }

    /** Reads and parses the header of a file; {@code null} if it is not an ASF file. */
    @Nullable
    static AsfHeader read(final Context context, final Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final byte[] prefix = new byte[HEADER_PREFIX_SIZE];
            if (!readFully(in, prefix, 0, prefix.length) || !startsWith(prefix, 0, HEADER_OBJECT)) {
                return null;
            }
            final long objectSize = readLittleEndianLong(prefix, 16);
            if (objectSize < HEADER_PREFIX_SIZE || objectSize > MAX_HEADER_SIZE) {
                return null;
            }
            final byte[] data = Arrays.copyOf(prefix, (int) objectSize);
            if (!readFully(in, data, HEADER_PREFIX_SIZE, data.length - HEADER_PREFIX_SIZE)) {
                return null;
            }
            return parse(data);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** MIME type of the audio stream, or {@code null} if the codec is not supported. */
    @Nullable
    String mimeType() {
        switch (formatTag) {
            case WAVE_FORMAT_WMAV1:
                return MIME_WMA_V1;
            case WAVE_FORMAT_WMAV2:
                return MIME_WMA_V2;
            case WAVE_FORMAT_WMAPRO:
                return MIME_WMA_PRO;
            case WAVE_FORMAT_WMALOSSLESS:
                return MIME_WMA_LOSSLESS;
            case WAVE_FORMAT_WMAVOICE:
                return MIME_WMA_VOICE;
            case WAVE_FORMAT_MPEG_LAYER3:
                return MimeTypes.AUDIO_MPEG;
            default:
                return null;
        }
    }

    boolean hasAudio() {
        return streamNumber != 0;
    }

    // ----- Objects -----

    private void parseFileProperties(final ParsableByteArray in) {
        in.skipBytes(16 + 8 + 8); // file id, file size, creation date
        packetCount = in.readLittleEndianLong();
        final long playDuration100ns = in.readLittleEndianLong();
        in.skipBytes(8); // send duration
        prerollMs = in.readLittleEndianLong();
        final int flags = in.readLittleEndianInt();
        final int minPacketSize = in.readLittleEndianInt();
        final int maxPacketSize = in.readLittleEndianInt();
        seekable = (flags & 0x02) != 0;
        // data packets have a fixed size; both values are equal in valid files
        packetSize = minPacketSize == maxPacketSize ? minPacketSize : 0;
        durationUs = Math.max(0, playDuration100ns / 10 - prerollMs * 1000);
    }

    private void parseStreamProperties(final ParsableByteArray in) {
        final byte[] streamType = new byte[16];
        in.readBytes(streamType, 0, 16);
        final byte[] errorCorrectionType = new byte[16];
        in.readBytes(errorCorrectionType, 0, 16);
        in.skipBytes(8); // time offset
        final int typeSpecificLength = in.readLittleEndianInt();
        final int errorCorrectionLength = in.readLittleEndianInt();
        final int flags = in.readLittleEndianUnsignedShort();
        in.skipBytes(4); // reserved
        if (!Arrays.equals(streamType, AUDIO_MEDIA) || (flags & 0x8000) != 0 || typeSpecificLength < 16) {
            return; // not audio, or encrypted (DRM)
        }
        final int typeSpecificStart = in.getPosition();
        formatTag = in.readLittleEndianUnsignedShort();
        channels = in.readLittleEndianUnsignedShort();
        sampleRate = in.readLittleEndianInt();
        averageBytesPerSecond = in.readLittleEndianInt();
        blockAlign = in.readLittleEndianUnsignedShort();
        bitsPerSample = in.readLittleEndianUnsignedShort();
        if (typeSpecificLength >= 18) {
            final int extraSize = Math.min(in.readLittleEndianUnsignedShort(), typeSpecificLength - 18);
            codecData = new byte[extraSize];
            in.readBytes(codecData, 0, extraSize);
        }
        in.setPosition(typeSpecificStart + typeSpecificLength);
        if (Arrays.equals(errorCorrectionType, AUDIO_SPREAD) && errorCorrectionLength >= 5) {
            spreadSpan = in.readUnsignedByte();
            spreadPacketLength = in.readLittleEndianUnsignedShort();
            spreadChunkLength = in.readLittleEndianUnsignedShort();
            // same plausibility check as FFmpeg: otherwise the data is not interleaved
            if (spreadSpan > 1 && (spreadChunkLength == 0 || spreadPacketLength / spreadChunkLength <= 1
                    || spreadPacketLength % spreadChunkLength != 0)) {
                spreadSpan = 0;
            }
        }
        streamNumber = flags & 0x7F;
    }

    private void parseContentDescription(final ParsableByteArray in) {
        final int titleLength = in.readLittleEndianUnsignedShort();
        final int authorLength = in.readLittleEndianUnsignedShort();
        final int copyrightLength = in.readLittleEndianUnsignedShort();
        final int descriptionLength = in.readLittleEndianUnsignedShort();
        in.skipBytes(2); // rating length
        title = readUtf16(in, titleLength);
        artist = readUtf16(in, authorLength);
        in.skipBytes(copyrightLength + descriptionLength);
    }

    private void parseExtendedContentDescription(final ParsableByteArray in, final int objectEnd) {
        final int count = in.readLittleEndianUnsignedShort();
        for (int i = 0; i < count && in.getPosition() + 6 <= objectEnd; i++) {
            final String name = readUtf16(in, in.readLittleEndianUnsignedShort());
            final int valueType = in.readLittleEndianUnsignedShort();
            final int valueLength = in.readLittleEndianUnsignedShort();
            if (in.getPosition() + valueLength > objectEnd) {
                return;
            }
            final int valueStart = in.getPosition();
            if (valueType == 0 && "WM/AlbumTitle".equals(name)) {
                album = readUtf16(in, valueLength);
            } else if (valueType == 0 && "WM/AlbumArtist".equals(name) && artist == null) {
                artist = readUtf16(in, valueLength);
            } else if (valueType == 1 && "WM/Picture".equals(name) && picture == null) {
                picture = readPicture(in, valueStart + valueLength);
            }
            in.setPosition(valueStart + valueLength);
        }
    }

    /** WM/Picture: type, data length, MIME type and description (both UTF-16, 0-terminated), data. */
    @Nullable
    private static byte[] readPicture(final ParsableByteArray in, final int end) {
        in.skipBytes(1); // picture type
        final int dataLength = in.readLittleEndianInt();
        skipUtf16String(in, end);
        skipUtf16String(in, end);
        if (dataLength <= 0 || in.getPosition() + dataLength > end) {
            return null;
        }
        final byte[] data = new byte[dataLength];
        in.readBytes(data, 0, dataLength);
        return data;
    }

    private static void skipUtf16String(final ParsableByteArray in, final int end) {
        while (in.getPosition() + 2 <= end) {
            if (in.readLittleEndianUnsignedShort() == 0) {
                return;
            }
        }
    }

    /** Reads a UTF-16LE string of {@code length} bytes; trailing zeros and blanks are removed. */
    @Nullable
    private static String readUtf16(final ParsableByteArray in, final int length) {
        if (length <= 0) {
            return null;
        }
        final byte[] bytes = new byte[length];
        in.readBytes(bytes, 0, length);
        final String value = new String(bytes, StandardCharsets.UTF_16LE).replace("\u0000", "").trim();
        return value.isEmpty() ? null : value;
    }

    // ----- Helpers -----

    /** Converts a GUID string to its byte order in ASF files (first three groups little-endian). */
    private static byte[] guid(final String text) {
        final String hex = text.replace("-", "");
        final byte[] plain = new byte[16];
        for (int i = 0; i < 16; i++) {
            plain[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return new byte[]{
                plain[3], plain[2], plain[1], plain[0],
                plain[5], plain[4],
                plain[7], plain[6],
                plain[8], plain[9], plain[10], plain[11], plain[12], plain[13], plain[14], plain[15]};
    }

    static long readLittleEndianLong(final byte[] data, final int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static boolean readFully(final InputStream in, final byte[] buffer, final int offset, final int length)
            throws IOException {
        int done = 0;
        while (done < length) {
            final int n = in.read(buffer, offset + done, length - done);
            if (n < 0) {
                return false;
            }
            done += n;
        }
        return true;
    }
}
