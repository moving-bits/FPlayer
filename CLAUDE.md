# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

FPlayer: Android audio player (Java, XML layouts, Material 3) that browses music **by directory** instead of artist/album. All file access goes through the Storage Access Framework (SAF). minSdk 26, targetSdk/compileSdk 36. UI strings are English in `values/strings.xml` with German translations in `values-de/strings.xml` (keep both in sync); code comments, log messages and build descriptions are US English.

## Build

Gradle 9.8 wrapper + AGP 9.4.1 (needs Gradle ≥ 9.6), version catalog in `gradle/libs.versions.toml`. Gradle must run on the Android Studio JBR (`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` in Git Bash).

```
./gradlew assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug          # lint errors fail the build
./gradlew checkstyleMain     # also part of `check`; any warning fails (maxWarnings = 0)
```

Versioning is automatic (`app/build.gradle.kts`): `versionName` is the build date `YYYY.MM.DD`; debug builds append `-<git short hash of HEAD>` via `versionNameSuffix`. `versionCode` is `YYYYMMDD`, so at most one release per day gets a distinct code. The about popup (tap the app name in the title bar) reads the version from `PackageManager`; the copyright year range is computed at runtime (`MainActivity.showAbout`).

Run `./gradlew checkstyleMain assembleDebug lintDebug` after every change. The Checkstyle config is `checkstyle.xml` in the project root, with `suppressions.xml` referenced via `${config_loc}`. Android Studio uses the same file through the CheckStyle-IDEA plugin (`.idea/checkstyle-idea.xml`, the only committed `.idea` file). Gradle copies both files to `app/build/checkstyle-config`. Keep the Checkstyle version in `libs.versions.toml` in sync with the plugin's bundled version, currently 14.1.0.

Code style enforced by the config:
- `final` on all parameters and on every local variable that is never reassigned.
- No assignments to parameters.
- Import groups in the order `net.movingbits`, `android`, `androidx`, `java`, `javax`, then everything else (e.g. `com.google.*` comes last), separated by blank lines.

There are no unit tests. Verify on an emulator via `adb` (install, `am start -n net.movingbits.fplayer/.MainActivity`, `uiautomator dump`, `dumpsys media_session`, `run-as net.movingbits.fplayer cat shared_prefs/<name>.xml`).

## Architecture

Package `net.movingbits.fplayer`, single activity + Media3 session service:

- **`MainActivity`** – UI and all transport logic (prev/±10 s/play-pause/next). Talks to playback only through a Media3 `MediaController`; builds the playlist from the selected tree nodes in `startPlayback()` and decides the start item/position (including resume).
- **`MainViewModel`** – owns the in-memory tree (`TreeNode` roots), selection, expand state and the info-panel node; survives rotation. Scans roots and loads tag metadata on background executors, then posts results to the main thread. `treeVersion` LiveData is bumped on every tree/selection change and the activity re-renders the whole list.
- **`TreeNode`** – a directory or audio file. Directories keep `totalFiles` / `selectedFiles` counts for the whole subtree. Selection changes propagate a delta up the parent chain, so tri-state checkboxes never need a rescan. Only mutate on the main thread.
- **`TreeScanner`** – full recursive SAF scan via `DocumentsContract` queries, keeping provider order ("eingelesene Reihenfolge"). Filters by extension (`.mp3`, `.m4a`, `.wma`) and skips dot-entries.
- **`PlaybackService`** (`MediaSessionService`) – ExoPlayer wrapped in **`ShufflingPlayer`**, a `ForwardingPlayer` that controls the shuffle order: a new order starts with the start/current item, a restored playlist gets the profile's saved order. It also saves the position before playlist changes. The service writes position, shuffle/repeat changes and the shuffle order into the profile the playlist belongs to.
- **WMA** – `AsfExtractor` (registered first by `AudioExtractorsFactory`, which `PlaybackService` passes to a `DefaultMediaSourceFactory`) demuxes ASF and emits one sample per media object, undoing audio-spread interleaving. `AsfHeader` parses the header object and is also used by `AudioMetadata` for tags, duration and the WM/Picture cover. Decoding goes through the FFmpeg extension AAR in `app/libs` (extension renderer mode PREFER). The stock extension can't decode WMA; `ffmpeg/media3-ffmpeg-wma.patch` (see `ffmpeg/README.md`) adds the MIME mapping and passes `block_align`/`bit_rate`. Contract: MIME types `AsfHeader.MIME_WMA_*`, `initializationData[0]` = codec data, `initializationData[1]` = block align (4 bytes big-endian).
- **`Settings`** (prefs `settings`) holds only the root tree URIs; roots and their SAF permissions are app-wide.
- **`ProfileStore`** / **`Profile`** hold per profile: name, color index into `ProfileStore.COLORS` (12 colors), tree selection, shuffle, repeat, current file + position, and `lastUsed` (set on activation). There is always exactly one active profile. The default profile (id `default`) is created on first start, takes over the old `settings` keys and can be renamed but not deleted. Profiles are stored in `files/profiles.json`. The shuffle play order (list of document URIs) is kept per profile in `files/profile_orders/<id>.txt`, cached in memory and written in the background. The store is a process-wide singleton shared by UI and service; all methods are synchronized.
- The profile badge (`layout/profile_badge.xml`, painted by `ProfileDialogs.paintBadge`) shows the person icon for the default name "default", otherwise `Profile.initials()`: the upper-case first letters of the first two words. The title bar start is a custom view (`layout/toolbar_title.xml`: profile button + app name) because `Toolbar` places custom views after its own title.
- **`ProfileDialogs`** – the profile popup (default profile first, then by `lastUsed`) and the create/edit form. "Neu" copies the active profile's settings.

Key conventions and pitfalls:

- `MediaItem`s sent from the controller lose their playback URI. The file URI goes in `RequestMetadata.mediaUri` and is restored in `PlaybackService.SessionCallback.onAddMediaItems`. `mediaId` is the file document URI. The extras carry `EXTRA_PROFILE_ID`, the profile owning the playlist; the service saves only into that profile, so late saves of a replaced playlist can't leak into the next profile. `EXTRA_RESTORE_ORDER` asks `ShufflingPlayer` to use the saved shuffle order.
- Profile switch (`MainActivity.onActiveProfileChanged`): save the running playlist's state, then `pause()` (because `playWhenReady` survives clearing) and `clearMediaItems()`. Avoid `stop()`. Then apply the profile's selection and prepare its playlist paused at the saved file/position (`setUpProfilePlaylist`). A newly created profile continues playing. On a cold start, `restorePlaybackOnce` does the same once the tree is scanned, unless the service still has a playlist.
- Document URIs are built from the root's tree URI, so everything under a root starts with `MainViewModel.documentUriPrefix(treeUri)`. Use this to match files and resume entries to a root.
- "End playback" means seeking to the end of the last item (`STATE_ENDED`). On ENDED the service clears the profile's current file and shuffle order. `saveResumePosition()` skips the ENDED state, and `stop()` would trigger another save, so avoid it where the entry must go away.
- The running playlist always mirrors the tree selection. `MainActivity.syncPlaylist()` runs after every tree change (only when no scan is running) and removes deselected items and inserts newly selected ones via the controller. The playlist stays in tree order; shuffle is only the player's shuffle order.
- The info panel follows playback: `showPlayingInfo()` shows the new track on every media-item change. `autoInfoMediaId` lives in the ViewModel so that rotation doesn't override an entry the user tapped. The position slider (`position_row` in `layout/info_details.xml`, shared by both orientations) is visible only while the info panel shows the playing file.
- The selection is persisted per profile, compactly: fully selected directories are stored as a single URI, otherwise individual files are stored. It is re-applied in the background right after each root is scanned (`restoreSelection`). `saveSelection` keeps the stored entries of roots that are not loaded yet.
- The play button with no active playlist continues at the profile's saved file/position if that file is selected. A double tap always starts fresh at the tapped entry.
- Icons are Material Symbols *Outlined* vector drawables (`res/drawable/ic_*.xml`, from google/material-design-icons `symbols/android/<name>/materialsymbolsoutlined`). `ic_check_box_indeterminate` is custom: the outline box plus a centre square. The launcher icon is the "genres" symbol as an adaptive icon.
- `layout/` and `layout-land/activity_main.xml` must keep the same view IDs.
