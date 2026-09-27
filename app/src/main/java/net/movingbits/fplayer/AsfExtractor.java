package net.movingbits.fplayer;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Media3 extractor for ASF files (WMA audio). Emits the first audio stream; each ASF media object
 * becomes one sample. The WMA codecs are decoded by the FFmpeg extension, which receives the codec
 * data as {@code initializationData[0]} and the block alignment as {@code initializationData[1]}
 * (4 bytes, big-endian), see {@link #blockAlignData(int)}.
 *
 * <p>Data packets have a fixed size, so seeking maps the time linearly to a packet (the ASF simple
 * index only exists for video streams).
 */
@OptIn(markerClass = UnstableApi.class)
final class AsfExtractor implements Extractor {

    private static final int STATE_READ_HEADER = 0;
    private static final int STATE_READ_DATA_OBJECT = 1;
    private static final int STATE_READ_PACKETS = 2;
    private static final long UNKNOWN_END = -1;

    private ExtractorOutput output;
    private TrackOutput trackOutput;
    private AsfHeader header;
    private int state = STATE_READ_HEADER;

    private long dataStart;
    /** End of the data packets, or {@link #UNKNOWN_END} if unknown (read to the end). */
    private long dataEnd = UNKNOWN_END;
    private byte[] packet;

    // media object being reassembled from payload fragments
    private byte[] object = new byte[0];
    private int objectSize;
    private int objectFilled;
    private int objectNumber = -1;
    private long objectTimeMs;
    /** After a seek, fragments are skipped until a new media object starts. */
    private boolean awaitObjectStart = true;
    private final ParsableByteArray sampleData = new ParsableByteArray();

    /** The block alignment as passed to the FFmpeg extension in {@code initializationData[1]}. */
    static byte[] blockAlignData(final int blockAlign) {
        return new byte[]{
                (byte) (blockAlign >>> 24), (byte) (blockAlign >>> 16), (byte) (blockAlign >>> 8), (byte) blockAlign};
    }

    @Override
    public boolean sniff(@NonNull final ExtractorInput input) throws IOException {
        final byte[] guid = new byte[AsfHeader.HEADER_OBJECT.length];
        input.peekFully(guid, 0, guid.length);
        return Arrays.equals(guid, AsfHeader.HEADER_OBJECT);
    }

    @Override
    public void init(@NonNull final ExtractorOutput extractorOutput) {
        output = extractorOutput;
    }

    @Override
    public int read(@NonNull final ExtractorInput input, @NonNull final PositionHolder seekPosition) throws IOException {
        switch (state) {
            case STATE_READ_HEADER:
                readHeader(input);
                return RESULT_CONTINUE;
            case STATE_READ_DATA_OBJECT:
                readDataObject(input);
                return RESULT_CONTINUE;
            default:
                return readPacket(input);
        }
    }

    @Override
    public void seek(final long position, final long timeUs) {
        if (position == 0) {
            state = STATE_READ_HEADER;
        }
        objectFilled = 0;
        objectSize = 0;
        objectNumber = -1;
        awaitObjectStart = true;
    }

    @Override
    public void release() {
        // nothing to release
    }

    // ----- Header -----

    private void readHeader(final ExtractorInput input) throws IOException {
        final byte[] prefix = new byte[AsfHeader.HEADER_PREFIX_SIZE];
        input.readFully(prefix, 0, prefix.length);
        final long size = AsfHeader.readLittleEndianLong(prefix, 16);
        if (size < prefix.length || size > AsfHeader.MAX_HEADER_SIZE) {
            throw ParserException.createForMalformedContainer("Invalid ASF header size " + size, null);
        }
        final byte[] data = Arrays.copyOf(prefix, (int) size);
        input.readFully(data, prefix.length, data.length - prefix.length);
        try {
            header = AsfHeader.parse(data);
        } catch (IOException e) {
            throw ParserException.createForMalformedContainer(e.getMessage(), e);
        }
        final String mimeType = header.mimeType();
        if (!header.hasAudio() || mimeType == null) {
            throw ParserException.createForUnsupportedContainerFeature(
                    "No supported audio stream (format tag 0x" + Integer.toHexString(header.formatTag) + ")");
        }
        final List<byte[]> initializationData = AsfHeader.MIME_WMA_V1.equals(mimeType)
                || AsfHeader.MIME_WMA_V2.equals(mimeType) || AsfHeader.MIME_WMA_PRO.equals(mimeType)
                || AsfHeader.MIME_WMA_LOSSLESS.equals(mimeType) || AsfHeader.MIME_WMA_VOICE.equals(mimeType)
                ? Arrays.asList(header.codecData, blockAlignData(header.blockAlign))
                : Collections.emptyList();
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO);
        trackOutput.format(new Format.Builder()
                .setContainerMimeType("video/x-ms-asf")
                .setSampleMimeType(mimeType)
                .setChannelCount(header.channels)
                .setSampleRate(header.sampleRate)
                .setAverageBitrate(header.averageBytesPerSecond * 8)
                .setMaxInputSize(Math.max(header.packetSize, header.blockAlign) * 4)
                .setInitializationData(initializationData)
                .build());
        output.endTracks();
        packet = new byte[header.packetSize];
        state = STATE_READ_DATA_OBJECT;
    }

    private void readDataObject(final ExtractorInput input) throws IOException {
        final byte[] data = new byte[AsfHeader.DATA_OBJECT_HEADER_SIZE];
        input.readFully(data, 0, 24);
        final long size = AsfHeader.readLittleEndianLong(data, 16);
        if (!AsfHeader.startsWith(data, 0, AsfHeader.DATA_OBJECT)) {
            // some other top-level object before the data object: skip it
            if (size < 24) {
                throw ParserException.createForMalformedContainer("Invalid ASF object size " + size, null);
            }
            input.skipFully((int) (size - 24));
            return;
        }
        input.readFully(data, 24, AsfHeader.DATA_OBJECT_HEADER_SIZE - 24);
        final long packets = AsfHeader.readLittleEndianLong(data, 40);
        dataStart = input.getPosition();
        final long count = packets > 0 ? packets : header.packetCount;
        dataEnd = count > 0 ? dataStart + count * header.packetSize : UNKNOWN_END;
        output.seekMap(new AsfSeekMap(count));
        state = STATE_READ_PACKETS;
    }

    /** Maps a time linearly to a data packet (packets have a fixed size). */
    private final class AsfSeekMap implements SeekMap {
        private final long count;

        AsfSeekMap(final long count) {
            this.count = count;
        }

        @Override
        public boolean isSeekable() {
            return header.seekable && count > 0 && header.durationUs > 0;
        }

        @Override
        public long getDurationUs() {
            return header.durationUs > 0 ? header.durationUs : C.TIME_UNSET;
        }

        @NonNull
        @Override
        public SeekPoints getSeekPoints(final long timeUs) {
            if (!isSeekable()) {
                return new SeekPoints(new SeekPoint(0, dataStart));
            }
            // one packet early: samples before the target are dropped by the player anyway
            final long index = Math.max(0, Math.min(count - 1, timeUs * count / header.durationUs - 1));
            return new SeekPoints(new SeekPoint(index * header.durationUs / count, dataStart + index * header.packetSize));
        }
    }

    // ----- Data packets -----

    private int readPacket(final ExtractorInput input) throws IOException {
        if (dataEnd != UNKNOWN_END && input.getPosition() + header.packetSize > dataEnd) {
            return RESULT_END_OF_INPUT;
        }
        if (!input.readFully(packet, 0, packet.length, true)) {
            return RESULT_END_OF_INPUT;
        }
        try {
            parsePacket();
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            // damaged packet: drop it and the media object it belongs to
            objectNumber = -1;
            awaitObjectStart = true;
        }
        return RESULT_CONTINUE;
    }

    /** Parses one data packet (ASF specification, section 5.2). */
    private void parsePacket() {
        final ParsableByteArray in = new ParsableByteArray(packet);
        int flags = in.readUnsignedByte();
        if ((flags & 0x80) != 0) {
            // error correction data; the length type is always 0, the length in the low bits
            in.skipBytes(flags & 0x0F);
            flags = in.readUnsignedByte();
        }
        final int propertyFlags = in.readUnsignedByte();
        final boolean multiplePayloads = (flags & 0x01) != 0;
        int packetLength = (int) readVariable(in, (flags >> 5) & 0x03);
        readVariable(in, (flags >> 1) & 0x03); // sequence
        final int paddingLength = (int) readVariable(in, (flags >> 3) & 0x03);
        final long sendTimeMs = in.readLittleEndianUnsignedInt();
        in.skipBytes(2); // duration
        if (packetLength <= 0 || packetLength > packet.length) {
            packetLength = packet.length;
        }
        final int replicatedDataType = propertyFlags & 0x03;
        final int offsetType = (propertyFlags >> 2) & 0x03;
        final int objectNumberType = (propertyFlags >> 4) & 0x03;

        int payloads = 1;
        int payloadLengthType = 0;
        if (multiplePayloads) {
            final int payloadFlags = in.readUnsignedByte();
            payloads = payloadFlags & 0x3F;
            payloadLengthType = (payloadFlags >> 6) & 0x03;
        }
        final int payloadEnd = packetLength - paddingLength;
        for (int i = 0; i < payloads && in.getPosition() < payloadEnd; i++) {
            final int stream = in.readUnsignedByte() & 0x7F;
            final int number = (int) readVariable(in, objectNumberType);
            final long offset = readVariable(in, offsetType);
            final int replicatedLength = (int) readVariable(in, replicatedDataType);
            final int replicatedStart = in.getPosition();
            in.skipBytes(replicatedLength);
            final int length = multiplePayloads
                    ? (int) readVariable(in, payloadLengthType)
                    : payloadEnd - in.getPosition();
            final int dataStartInPacket = in.getPosition();
            if (length < 0 || dataStartInPacket + length > payloadEnd) {
                throw new IllegalArgumentException("Invalid payload length");
            }
            if (stream == header.streamNumber) {
                if (replicatedLength == 1) {
                    // compressed payload: several complete media objects, "offset" is the time
                    readCompressedPayload(dataStartInPacket, length, offset, packet[replicatedStart] & 0xFF);
                } else {
                    long objectLength = 0;
                    long timeMs = sendTimeMs;
                    if (replicatedLength >= 8) {
                        objectLength = readLittleEndianUnsignedInt(replicatedStart);
                        timeMs = readLittleEndianUnsignedInt(replicatedStart + 4);
                    }
                    readPayload(number, offset, objectLength > 0 ? objectLength : length, timeMs,
                            dataStartInPacket, length);
                }
            }
            in.setPosition(dataStartInPacket + length);
        }
    }

    private void readPayload(final int number, final long offset, final long size, final long timeMs,
                             final int start, final int length) {
        if (offset == 0) {
            if (size > Integer.MAX_VALUE / 2) {
                throw new IllegalArgumentException("Media object too large");
            }
            objectNumber = number;
            objectSize = (int) size;
            objectFilled = 0;
            objectTimeMs = timeMs;
            awaitObjectStart = false;
            if (object.length < objectSize) {
                object = new byte[objectSize];
            }
        } else if (awaitObjectStart || number != objectNumber || offset != objectFilled) {
            return; // fragment of an object whose start was missed
        }
        final int copy = Math.min(length, objectSize - objectFilled);
        System.arraycopy(packet, start, object, objectFilled, copy);
        objectFilled += copy;
        if (objectFilled >= objectSize) {
            emitSample(object, objectSize, objectTimeMs);
            objectNumber = -1;
            awaitObjectStart = true;
        }
    }

    private void readCompressedPayload(final int start, final int length, final long timeMs, final int timeDeltaMs) {
        int position = start;
        long time = timeMs;
        while (position < start + length) {
            final int size = packet[position++] & 0xFF;
            if (size == 0 || position + size > start + length) {
                return;
            }
            emitSample(Arrays.copyOfRange(packet, position, position + size), size, time);
            position += size;
            time += timeDeltaMs;
        }
    }

    private void emitSample(final byte[] data, final int size, final long timeMs) {
        final byte[] sample = header.spreadSpan > 1 && size == header.spreadPacketLength * header.spreadSpan
                ? descramble(data, size)
                : data;
        sampleData.reset(sample, size);
        trackOutput.sampleData(sampleData, size);
        final long timeUs = Math.max(0, (timeMs - header.prerollMs) * 1000);
        trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null);
    }

    /** Undoes the audio spread interleaving of a media object (like FFmpeg's ASF demuxer). */
    private byte[] descramble(final byte[] data, final int size) {
        final int chunk = header.spreadChunkLength;
        final int span = header.spreadSpan;
        final int chunksPerPacket = header.spreadPacketLength / chunk;
        final byte[] result = new byte[size];
        for (int offset = 0; offset + chunk <= size; offset += chunk) {
            final int index = offset / chunk;
            final int row = index / span;
            final int column = index % span;
            final int source = row + column * chunksPerPacket;
            System.arraycopy(data, source * chunk, result, offset, chunk);
        }
        return result;
    }

    private static long readVariable(final ParsableByteArray in, final int type) {
        switch (type) {
            case 1:
                return in.readUnsignedByte();
            case 2:
                return in.readLittleEndianUnsignedShort();
            case 3:
                return in.readLittleEndianUnsignedInt();
            default:
                return 0;
        }
    }

    private long readLittleEndianUnsignedInt(final int offset) {
        return (packet[offset] & 0xFFL) | (packet[offset + 1] & 0xFFL) << 8
                | (packet[offset + 2] & 0xFFL) << 16 | (packet[offset + 3] & 0xFFL) << 24;
    }
}
