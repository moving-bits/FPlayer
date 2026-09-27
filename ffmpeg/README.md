# FFmpeg extension with WMA support

FPlayer plays WMA files with its own ASF extractor (`AsfExtractor`) and the Media3 FFmpeg
extension (`app/libs/lib-decoder-ffmpeg-release.aar`). The stock extension cannot decode WMA even
if FFmpeg contains the WMA decoders:

- `FfmpegLibrary.getCodecName()` does not map any WMA MIME type to an FFmpeg decoder, so
  `FfmpegAudioRenderer` rejects the track.
- The JNI layer passes sample rate and channel count only for PCM and never sets `block_align` or
  `bit_rate`. FFmpeg's WMA decoders refuse to open without `block_align`.

`media3-ffmpeg-wma.patch` fixes both. It applies to androidx/media at tag `1.11.1`, the Media3 version
in `gradle/libs.versions.toml`.

## Contract with the extractor

| Format field              | Content                                                    |
|---------------------------|------------------------------------------------------------|
| `sampleMimeType`          | `audio/x-ms-wmav1`, `audio/x-ms-wma` (WMA v2), `audio/x-ms-wmapro`, `audio/x-ms-wmalossless`, `audio/x-ms-wmavoice` |
| `initializationData[0]`   | codec specific data from the ASF `WAVEFORMATEX` (may be empty) |
| `initializationData[1]`   | block alignment, 4 bytes big-endian                        |
| `sampleRate`, `channelCount`, `averageBitrate` | from the `WAVEFORMATEX`               |

## Building the AAR

```
git clone https://github.com/androidx/media.git && cd media
git checkout 1.11.1
git apply <fplayer>/ffmpeg/media3-ffmpeg-wma.patch
```

Then build FFmpeg as described in `libraries/decoder_ffmpeg/README.md`. Include the WMA decoders in
the list of enabled decoders:

```
ENABLED_DECODERS=(mp3 aac flac alac vorbis opus wmav1 wmav2 wmapro wmalossless wmavoice)
```

Build the module with `./gradlew lib-decoder-ffmpeg:assembleRelease` and copy
`libraries/decoder_ffmpeg/buildout/outputs/aar/lib-decoder-ffmpeg-release.aar` to `app/libs/`.
