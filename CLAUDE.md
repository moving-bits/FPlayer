# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

FPlayer: Android audio player (Java, XML layouts, Material 3) that browses music **by directory** instead of artist/album. All file access goes through the Storage Access Framework (SAF). minSdk 26, targetSdk/compileSdk 36. UI strings (`strings.xml`) are German; code comments, log messages and build descriptions are US English.

## Build

Gradle 9.8 wrapper + AGP 9.4.1 (needs Gradle ≥ 9.6), version catalog in `gradle/libs.versions.toml`. Gradle must run on the Android Studio JBR (`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"` in Git Bash).

```
./gradlew assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug          # lint errors fail the build
./gradlew checkstyleMain     # also part of `check`; any warning fails (maxWarnings = 0)
```

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
- **`TreeScanner`** – full recursive SAF scan via `DocumentsContract` queries, keeping provider order ("eingelesene Reihenfolge"). Filters by extension (`.mp3`, `.m4a`; WMA is deliberately excluded because ExoPlayer can't play ASF) and skips dot-entries.
- **`PlaybackService`** (`MediaSessionService`) – ExoPlayer wrapped in **`ShufflingPlayer`** (a `ForwardingPlayer` that makes the start/current item first in the shuffle order and saves the position before playlist changes). Writes resume positions to `ResumeStore`.
- **`Settings`** (prefs `settings`: root tree URIs, shuffle/repeat flags, tree selection) and **`ResumeStore`** (prefs `resume`: key = directory document URI, value = `fileUri\npositionMs\nsavedAt`).

Key conventions and pitfalls:

- `MediaItem`s sent from the controller lose their playback URI. The file URI goes in `RequestMetadata.mediaUri` and is restored in `PlaybackService.SessionCallback.onAddMediaItems`. The file's directory URI goes in `RequestMetadata.extras[EXTRA_DIRECTORY_URI]`. `mediaId` is the file document URI.
- Document URIs are built from the root's tree URI, so everything under a root starts with `MainViewModel.documentUriPrefix(treeUri)`. Use this to match files and resume entries to a root.
- "End playback" means seeking to the end of the last item (`STATE_ENDED`). The service deletes that directory's resume entry on ENDED. `saveResumePosition()` skips the ENDED state, and `stop()` would trigger another save, so avoid it where the entry must go away.
- The running playlist always mirrors the tree selection. `MainActivity.syncPlaylist()` runs after every tree change (only when no scan is running) and removes deselected items and inserts newly selected ones via the controller. The playlist stays in tree order; shuffle is only the player's shuffle order.
- The info panel follows playback: `showPlayingInfo()` shows the new track on every media-item change. `autoInfoMediaId` lives in the ViewModel so that rotation doesn't override an entry the user tapped. The position slider (`position_row` in `layout/info_details.xml`, shared by both orientations) is visible only while the info panel shows the playing file.
- The selection is persisted compactly: fully selected directories are stored as a single URI, otherwise individual files are stored. It is re-applied in the background right after each root is scanned (`restoreSelection`). `saveSelection` keeps the stored entries of roots that are not loaded yet.
- Resume applies only when not shuffling and the selection is a single directory: either every file shares one parent, or exactly one directory subtree is fully selected. See `MainViewModel.findResumeEntry`.
- Icons are Material Symbols *Outlined* vector drawables (`res/drawable/ic_*.xml`, from google/material-design-icons `symbols/android/<name>/materialsymbolsoutlined`). `ic_check_box_indeterminate` is custom: the outline box plus a centre square. The launcher icon is the "genres" symbol as an adaptive icon.
- `layout/` and `layout-land/activity_main.xml` must keep the same view IDs.
