package net.movingbits.fplayer;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

public class MainActivity extends AppCompatActivity implements TreeAdapter.Listener {

    private static final long SEEK_STEP_MS = 10_000;
    private static final long PROGRESS_UPDATE_MS = 500;

    private MainViewModel viewModel;
    private TreeAdapter adapter;

    private MaterialToolbar toolbar;
    private View root;
    private ImageView infoIcon;
    private View infoDetails;
    private TextView infoName;
    private TextView infoText;
    private ImageView infoCover;
    private View positionRow;
    private Slider positionSlider;
    private TextView positionElapsed;
    private TextView positionRemaining;
    private boolean sliderDragging;
    private RecyclerView treeList;
    private View emptyView;
    private CircularProgressIndicator scanProgress;
    private TextView nowPlaying;
    private MaterialButton buttonPrevious;
    private MaterialButton buttonRewind;
    private MaterialButton buttonPlayPause;
    private MaterialButton buttonForward;
    private MaterialButton buttonNext;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (controller != null && controller.isPlaying()) {
                handler.postDelayed(this, PROGRESS_UPDATE_MS);
            }
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(@androidx.annotation.NonNull final Player player, @androidx.annotation.NonNull final Player.Events events) {
            updatePlayerUi();
        }
    };

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        toolbar = findViewById(R.id.toolbar);
        infoIcon = findViewById(R.id.info_icon);
        infoDetails = findViewById(R.id.info_details);
        infoName = findViewById(R.id.info_name);
        infoText = findViewById(R.id.info_text);
        infoCover = findViewById(R.id.info_cover);
        positionRow = findViewById(R.id.position_row);
        positionSlider = findViewById(R.id.position_slider);
        positionElapsed = findViewById(R.id.position_elapsed);
        positionRemaining = findViewById(R.id.position_remaining);
        setUpPositionSlider();
        treeList = findViewById(R.id.tree_list);
        emptyView = findViewById(R.id.empty_view);
        scanProgress = findViewById(R.id.scan_progress);
        nowPlaying = findViewById(R.id.now_playing);
        buttonPrevious = findViewById(R.id.button_previous);
        buttonRewind = findViewById(R.id.button_rewind);
        buttonPlayPause = findViewById(R.id.button_play_pause);
        buttonForward = findViewById(R.id.button_forward);
        buttonNext = findViewById(R.id.button_next);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        toolbar.setOnMenuItemClickListener(this::onMenuItemClicked);
        findViewById(R.id.info_area).setOnClickListener(v -> viewModel.clearInfo());

        adapter = new TreeAdapter(this);
        treeList.setLayoutManager(new LinearLayoutManager(this));
        treeList.setItemAnimator(null);
        treeList.setAdapter(adapter);

        buttonPrevious.setOnClickListener(v -> onPrevious());
        buttonRewind.setOnClickListener(v -> onRewind());
        buttonPlayPause.setOnClickListener(v -> onPlayPause());
        buttonForward.setOnClickListener(v -> onForward());
        buttonNext.setOnClickListener(v -> onNext());

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        viewModel.getTreeVersion().observe(this, v -> updateTree());
        viewModel.isScanning().observe(this, v -> updateTree());
        viewModel.getInfoNode().observe(this, this::renderInfo);
        viewModel.getCover().observe(this, c -> renderCover());

        updatePlayerUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        final SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            if (controllerFuture == null || !controllerFuture.isDone()) {
                return;
            }
            try {
                controller = controllerFuture.get();
            } catch (Exception e) {
                return;
            }
            controller.addListener(playerListener);
            if (!Boolean.TRUE.equals(viewModel.isScanning().getValue())) {
                syncPlaylist();
            }
            updatePlayerUi();
        }, MoreExecutors.directExecutor());
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(progressUpdater);
        if (controller != null) {
            controller.removeListener(playerListener);
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
        controllerFuture = null;
        controller = null;
        super.onStop();
    }

    // ----- Title bar -----

    private boolean onMenuItemClicked(final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_play_order) {
            final boolean shuffle = !Settings.isShuffle(this);
            Settings.setShuffle(this, shuffle);
            if (controller != null) {
                controller.setShuffleModeEnabled(shuffle);
            }
            updateMenu();
            return true;
        } else if (id == R.id.action_repeat) {
            final boolean repeat = !Settings.isRepeat(this);
            Settings.setRepeat(this, repeat);
            if (controller != null) {
                controller.setRepeatMode(repeat ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            }
            updateMenu();
            return true;
        } else if (id == R.id.action_add_folder) {
            folderPicker.launch(null);
            return true;
        }
        return false;
    }

    private void updateMenu() {
        final Menu menu = toolbar.getMenu();
        final MenuItem order = menu.findItem(R.id.action_play_order);
        final boolean shuffle = Settings.isShuffle(this);
        final boolean enabled = viewModel.hasSelection();
        order.setIcon(shuffle ? R.drawable.ic_shuffle : R.drawable.ic_arrow_right_alt);
        setLabel(order, shuffle ? R.string.action_order_shuffle : R.string.action_order_sequential);
        order.setEnabled(enabled);
        final Drawable icon = order.getIcon();
        if (icon != null) {
            icon.mutate().setAlpha(enabled ? 255 : 97);
        }

        final MenuItem repeatItem = menu.findItem(R.id.action_repeat);
        final boolean repeat = Settings.isRepeat(this);
        repeatItem.setIcon(repeat ? R.drawable.ic_repeat_on : R.drawable.ic_repeat);
        setLabel(repeatItem, repeat ? R.string.action_repeat_on : R.string.action_repeat_off);
    }

    /** Sets the title (also used for accessibility) and the tooltip of a menu item together. */
    private void setLabel(final MenuItem item, final int stringRes) {
        final CharSequence label = getText(stringRes);
        item.setTitle(label);
        item.setTooltipText(label);
    }

    private void onFolderPicked(final Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Snackbar.make(root, R.string.folder_permission_failed, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (!viewModel.addRoot(treeUri)) {
            Snackbar.make(root, R.string.folder_already_added, Snackbar.LENGTH_SHORT).show();
        }
    }

    // ----- Directory tree -----

    private void updateTree() {
        adapter.setItems(viewModel.getVisibleNodes());
        final boolean scanning = Boolean.TRUE.equals(viewModel.isScanning().getValue());
        final boolean empty = !viewModel.hasRoots();
        scanProgress.setVisibility(scanning ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(empty && !scanning ? View.VISIBLE : View.GONE);
        treeList.setVisibility(empty ? View.GONE : View.VISIBLE);
        renderInfo(viewModel.getInfoNode().getValue());
        if (!scanning) {
            syncPlaylist();
        }
        updatePlayerUi();
    }

    @Override
    public void onToggleExpanded(final TreeNode node) {
        viewModel.toggleExpanded(node);
    }

    @Override
    public void onToggleSelected(final TreeNode node) {
        viewModel.toggleSelected(node);
    }

    @Override
    public void onTap(final TreeNode node) {
        viewModel.showInfo(node);
    }

    @Override
    public void onDoubleTap(final TreeNode node) {
        viewModel.select(node);
        startPlayback(node);
    }

    @Override
    public void onRemoveRoot(final TreeNode rootNode) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.remove_folder_title)
                .setMessage(getString(R.string.remove_folder_message, rootNode.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, w) -> removeRoot(rootNode))
                .show();
    }

    private void removeRoot(final TreeNode rootNode) {
        // the directory's files are then removed from the playlist by syncPlaylist()
        viewModel.removeRoot(rootNode);
    }

    // ----- Info area -----

    private void renderInfo(final TreeNode node) {
        if (node == null) {
            infoIcon.setVisibility(View.VISIBLE);
            infoDetails.setVisibility(View.GONE);
            return;
        }
        infoIcon.setVisibility(View.GONE);
        infoDetails.setVisibility(View.VISIBLE);
        infoName.setText(node.name);

        final List<String> lines = new ArrayList<>();
        if (node.directory) {
            lines.add(getString(R.string.info_files, node.directFiles));
            if (node.directFiles > 0) {
                long total = 0;
                boolean complete = true;
                for (TreeNode child : node.children) {
                    if (child.directory) {
                        continue;
                    }
                    if (!child.metadataLoaded) {
                        complete = false;
                    } else if (child.durationMs > 0) {
                        total += child.durationMs;
                    }
                }
                lines.add(complete
                        ? getString(R.string.info_total_duration, formatDuration(total))
                        : getString(R.string.info_duration_pending));
            }
        } else if (!node.metadataLoaded) {
            lines.add(getString(R.string.info_metadata_pending));
        } else {
            lines.add(getString(R.string.info_title, orUnknown(node.title)));
            lines.add(getString(R.string.info_artist, orUnknown(node.artist)));
            if (node.album != null) {
                lines.add(getString(R.string.info_album, node.album));
            }
            lines.add(getString(R.string.info_duration,
                    node.durationMs >= 0 ? formatDuration(node.durationMs) : getString(R.string.info_unknown)));
        }
        infoText.setText(TextUtils.join("\n", lines));
        renderCover();
        updateProgress();
    }

    /** Cover image of the displayed file, otherwise the app icon as a placeholder. */
    private void renderCover() {
        final TreeNode node = viewModel.getInfoNode().getValue();
        final MainViewModel.Cover cover = viewModel.getCover().getValue();
        if (node != null && cover != null && cover.node == node && cover.bitmap != null) {
            infoCover.setImageTintList(null);
            infoCover.setPadding(0, 0, 0, 0);
            infoCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            infoCover.setImageBitmap(cover.bitmap);
        } else {
            final int padding = getResources().getDimensionPixelSize(R.dimen.cover_placeholder_padding);
            infoCover.setImageTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(infoCover, androidx.appcompat.R.attr.colorPrimary)));
            infoCover.setPadding(padding, padding, padding, padding);
            infoCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            infoCover.setImageResource(R.drawable.ic_genres);
        }
    }

    private String orUnknown(final String s) {
        return s != null ? s : getString(R.string.info_unknown);
    }

    private static String formatDuration(final long ms) {
        final long seconds = ms / 1000;
        final long h = seconds / 3600;
        final long m = (seconds % 3600) / 60;
        final long s = seconds % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    // ----- Playback -----

    /**
     * Starts playback of all selected files.
     *
     * @param start the tapped entry (double tap) or {@code null} (play button)
     */
    private void startPlayback(final TreeNode start) {
        if (controller == null) {
            return;
        }
        final List<TreeNode> files = viewModel.getSelectedFiles();
        if (files.isEmpty()) {
            Snackbar.make(root, R.string.nothing_selected, Snackbar.LENGTH_SHORT).show();
            return;
        }
        final boolean shuffle = Settings.isShuffle(this);
        int startIndex = 0;
        long startPosition = 0;

        if (start != null && !start.directory) {
            startIndex = Math.max(0, files.indexOf(start));
        } else {
            if (start != null) {
                for (int i = 0; i < files.size(); i++) {
                    if (files.get(i).isDescendantOf(start)) {
                        startIndex = i;
                        break;
                    }
                }
            } else if (shuffle) {
                startIndex = new Random().nextInt(files.size());
            }
            // single directory in read order: continue at the saved position
            final ResumeStore.Entry entry = shuffle ? null : viewModel.findResumeEntry(files);
            if (entry != null) {
                for (int i = 0; i < files.size(); i++) {
                    if (files.get(i).uri.toString().equals(entry.fileUri)) {
                        startIndex = i;
                        startPosition = entry.positionMs;
                        break;
                    }
                }
            }
        }

        final List<MediaItem> items = new ArrayList<>(files.size());
        for (TreeNode file : files) {
            items.add(toMediaItem(file));
        }
        controller.setShuffleModeEnabled(shuffle);
        controller.setRepeatMode(Settings.isRepeat(this) ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        controller.setMediaItems(items, startIndex, startPosition);
        controller.prepare();
        controller.play();
    }

    /**
     * Synchronizes a running playlist with the selection in the tree: deselected files are removed,
     * newly selected ones are inserted at their position in tree order. The playlist is always a
     * subsequence of the selected files in tree order (the player manages the shuffle order
     * separately).
     */
    private void syncPlaylist() {
        if (!hasActivePlaylist()) {
            return;
        }
        final List<TreeNode> selected = viewModel.getSelectedFiles();
        final Set<String> selectedIds = new HashSet<>();
        for (TreeNode file : selected) {
            selectedIds.add(file.uri.toString());
        }
        final List<String> playlist = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            playlist.add(controller.getMediaItemAt(i).mediaId);
        }

        // remove deselected files, contiguous ranges in one go (from back to front)
        int i = playlist.size();
        while (i > 0) {
            final int runEnd = i;
            while (i > 0 && !selectedIds.contains(playlist.get(i - 1))) {
                i--;
            }
            if (i < runEnd) {
                controller.removeMediaItems(i, runEnd);
                playlist.subList(i, runEnd).clear();
            } else {
                i--;
            }
        }

        // insert newly selected files
        final Set<String> present = new HashSet<>(playlist);
        final List<TreeNode> pending = new ArrayList<>();
        int position = 0;
        for (TreeNode file : selected) {
            final String id = file.uri.toString();
            if (!present.contains(id)) {
                pending.add(file);
                continue;
            }
            position = insertPending(playlist, position, pending);
            position = playlist.indexOf(id) + 1;
        }
        insertPending(playlist, position, pending);
    }

    private int insertPending(final List<String> playlist, final int position, final List<TreeNode> pending) {
        if (pending.isEmpty()) {
            return position;
        }
        final List<MediaItem> items = new ArrayList<>(pending.size());
        final List<String> ids = new ArrayList<>(pending.size());
        for (TreeNode file : pending) {
            items.add(toMediaItem(file));
            ids.add(file.uri.toString());
        }
        controller.addMediaItems(position, items);
        playlist.addAll(position, ids);
        pending.clear();
        return position + items.size();
    }

    private static MediaItem toMediaItem(final TreeNode file) {
        final Bundle extras = new Bundle();
        extras.putString(PlaybackService.EXTRA_DIRECTORY_URI, file.parent.uri.toString());
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

    private boolean hasActivePlaylist() {
        return controller != null
                && controller.getMediaItemCount() > 0
                && controller.getPlaybackState() != Player.STATE_ENDED;
    }

    private void onPlayPause() {
        if (!hasActivePlaylist()) {
            startPlayback(null);
            return;
        }
        if (controller.getPlayWhenReady() && controller.getPlaybackState() != Player.STATE_IDLE) {
            controller.pause();
        } else {
            if (controller.getPlaybackState() == Player.STATE_IDLE) {
                controller.prepare();
            }
            controller.play();
        }
    }

    private void onPrevious() {
        if (!hasActivePlaylist()) {
            return;
        }
        if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem();
        } else {
            controller.seekTo(0);
        }
    }

    private void onRewind() {
        if (hasActivePlaylist()) {
            controller.seekTo(Math.max(0, controller.getCurrentPosition() - SEEK_STEP_MS));
        }
    }

    private void onForward() {
        if (!hasActivePlaylist()) {
            return;
        }
        final long duration = controller.getDuration();
        final long position = controller.getCurrentPosition();
        if (duration != C.TIME_UNSET && duration - position < SEEK_STEP_MS) {
            onNext();
        } else {
            controller.seekTo(position + SEEK_STEP_MS);
        }
    }

    private void onNext() {
        if (!hasActivePlaylist()) {
            return;
        }
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem();
        } else {
            endPlayback();
        }
    }

    /** Ends playback by seeking to the end of the last track (state ENDED). */
    private void endPlayback() {
        final long duration = controller.getDuration();
        if (duration != C.TIME_UNSET) {
            controller.seekTo(duration);
        } else {
            controller.stop();
        }
    }

    private void updatePlayerUi() {
        final boolean active = hasActivePlaylist();
        final boolean showPause = active && controller.getPlayWhenReady()
                && controller.getPlaybackState() != Player.STATE_IDLE;
        buttonPlayPause.setIconResource(showPause ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        final CharSequence playLabel = getText(showPause ? R.string.button_pause : R.string.button_play);
        buttonPlayPause.setContentDescription(playLabel);
        buttonPlayPause.setTooltipText(playLabel);
        // pausing is always possible; starting only if something is selected
        buttonPlayPause.setEnabled(controller != null && (showPause || viewModel.hasSelection()));
        buttonPrevious.setEnabled(active);
        buttonRewind.setEnabled(active);
        buttonForward.setEnabled(active);
        buttonNext.setEnabled(active);

        final MediaItem current = active ? controller.getCurrentMediaItem() : null;
        final String playingId = current != null ? current.mediaId : null;
        adapter.setPlayingUri(playingId);
        showPlayingInfo(playingId);
        updateMenu();

        handler.removeCallbacks(progressUpdater);
        progressUpdater.run();
    }

    /**
     * Shows the new track in the info area whenever the track changes. In between, an entry tapped
     * by the user stays visible.
     */
    private void showPlayingInfo(final String playingId) {
        if (playingId == null) {
            viewModel.setAutoInfoMediaId(null);
            return;
        }
        if (playingId.equals(viewModel.getAutoInfoMediaId())) {
            return;
        }
        final TreeNode node = viewModel.findFile(playingId);
        if (node != null) { // may not be in the tree yet while scanning
            viewModel.setAutoInfoMediaId(playingId);
            viewModel.showInfo(node);
        }
    }

    /** Title line above the button bar as well as slider and times in the info area. */
    private void updateProgress() {
        final MediaItem current = hasActivePlaylist() ? controller.getCurrentMediaItem() : null;
        if (current == null) {
            nowPlaying.setVisibility(View.GONE);
            positionRow.setVisibility(View.GONE);
            return;
        }
        final MediaMetadata metadata = controller.getMediaMetadata();
        final CharSequence title = metadata.title != null ? metadata.title : metadata.displayTitle;
        final StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(title);
        }
        if (metadata.artist != null) {
            sb.append(" – ").append(metadata.artist);
        }
        nowPlaying.setText(sb);
        nowPlaying.setVisibility(View.VISIBLE);

        // slider only while the info area shows the playing track
        final TreeNode info = viewModel.getInfoNode().getValue();
        final boolean showsPlaying = info != null && !info.directory && info.uri.toString().equals(current.mediaId);
        positionRow.setVisibility(showsPlaying ? View.VISIBLE : View.GONE);
        if (!showsPlaying || sliderDragging) {
            return;
        }
        final long duration = controller.getDuration();
        final long position = Math.max(0, controller.getCurrentPosition());
        if (duration == C.TIME_UNSET || duration <= 0) {
            positionSlider.setEnabled(false);
            positionSlider.setValue(0);
            positionElapsed.setText(formatDuration(position));
            positionRemaining.setText("");
            return;
        }
        positionSlider.setEnabled(true);
        final float to = duration;
        if (positionSlider.getValueTo() != to) {
            positionSlider.setValue(Math.min(positionSlider.getValue(), to));
            positionSlider.setValueTo(to);
        }
        positionSlider.setValue(Math.min(position, duration));
        showPositionTexts(position, duration);
    }

    private void showPositionTexts(final long position, final long duration) {
        positionElapsed.setText(formatDuration(position));
        positionRemaining.setText("-" + formatDuration(Math.max(0, duration - position)));
    }

    private void setUpPositionSlider() {
        positionSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                showPositionTexts((long) value, (long) slider.getValueTo());
            }
        });
        positionSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@androidx.annotation.NonNull final Slider slider) {
                sliderDragging = true;
            }

            @Override
            public void onStopTrackingTouch(@androidx.annotation.NonNull final Slider slider) {
                sliderDragging = false;
                if (hasActivePlaylist()) {
                    controller.seekTo((long) slider.getValue());
                }
            }
        });
    }
}
