package net.movingbits.fplayer;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds the directory tree including selection and expansion state so that it survives screen rotations.
 */
public class MainViewModel extends AndroidViewModel {

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor();

    private static final int COVER_MAX_SIZE_PX = 512;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ResumeStore resumeStore;

    private final List<TreeNode> roots = new ArrayList<>();
    private int pendingScans;
    private String autoInfoMediaId;

    /** Incremented on every change of structure, selection or expansion state. */
    private final MutableLiveData<Integer> treeVersion = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);
    private final MutableLiveData<TreeNode> infoNode = new MutableLiveData<>(null);
    private final MutableLiveData<Cover> cover = new MutableLiveData<>(null);

    public MainViewModel(@NonNull final Application application) {
        super(application);
        resumeStore = new ResumeStore(application);

        // drop base directories without a (still valid) permission
        final Set<Uri> granted = new HashSet<>();
        for (UriPermission permission : application.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission()) {
                granted.add(permission.getUri());
            }
        }
        final List<Uri> configured = Settings.getRoots(application);
        final List<Uri> valid = new ArrayList<>();
        for (Uri uri : configured) {
            if (granted.contains(uri)) {
                valid.add(uri);
            }
        }
        if (valid.size() != configured.size()) {
            Settings.setRoots(application, valid);
        }

        scanExecutor.execute(this::removeStaleResumeEntries);
        for (Uri uri : valid) {
            scan(uri);
        }
    }

    LiveData<Integer> getTreeVersion() {
        return treeVersion;
    }

    LiveData<Boolean> isScanning() {
        return scanning;
    }

    LiveData<TreeNode> getInfoNode() {
        return infoNode;
    }

    boolean hasRoots() {
        return !roots.isEmpty();
    }

    /** Adds a base directory; returns {@code false} if it is already present. */
    boolean addRoot(final Uri treeUri) {
        final List<Uri> configured = Settings.getRoots(getApplication());
        if (configured.contains(treeUri)) {
            return false;
        }
        configured.add(treeUri);
        Settings.setRoots(getApplication(), configured);
        scan(treeUri);
        return true;
    }

    void removeRoot(final TreeNode root) {
        roots.remove(root);
        final List<Uri> configured = Settings.getRoots(getApplication());
        configured.remove(root.treeUri);
        Settings.setRoots(getApplication(), configured);
        try {
            getApplication().getContentResolver().releasePersistableUriPermission(root.treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // permission was already gone
        }
        resumeStore.removeWithPrefix(documentUriPrefix(root.treeUri));
        saveSelection();
        final TreeNode info = infoNode.getValue();
        if (info != null && info.isDescendantOf(root)) {
            infoNode.setValue(null);
        }
        bumpTreeVersion();
    }

    /** All document URIs below a tree URI start with this prefix. */
    static String documentUriPrefix(final Uri treeUri) {
        return treeUri.toString() + "/document/";
    }

    /** Flat list of the currently visible rows. */
    List<TreeNode> getVisibleNodes() {
        final List<TreeNode> result = new ArrayList<>();
        for (TreeNode root : roots) {
            addVisible(root, result);
        }
        return result;
    }

    private static void addVisible(final TreeNode node, final List<TreeNode> result) {
        result.add(node);
        if (node.directory && node.expanded) {
            for (TreeNode child : node.children) {
                addVisible(child, result);
            }
        }
    }

    void toggleExpanded(final TreeNode node) {
        if (node.directory && !node.children.isEmpty()) {
            node.expanded = !node.expanded;
            bumpTreeVersion();
        }
    }

    /**
     * File: toggles the selection. Directory: deselects everything if everything is selected,
     * otherwise selects everything.
     */
    void toggleSelected(final TreeNode node) {
        final boolean select = node.directory ? node.getCheckState() != TreeNode.CHECK_ALL : !node.selected;
        setSelected(node, select);
    }

    /** Selects the entry (completely) unless already done. */
    void select(final TreeNode node) {
        if (node.getCheckState() != TreeNode.CHECK_ALL) {
            setSelected(node, true);
        }
    }

    private void setSelected(final TreeNode node, final boolean select) {
        final int delta = setSelectedRecursive(node, select);
        for (TreeNode p = node.parent; p != null; p = p.parent) {
            p.selectedFiles += delta;
        }
        saveSelection();
        bumpTreeVersion();
    }

    /**
     * Stores the selection compactly: fully selected directories as a whole, otherwise the individual
     * files. Entries of base directories that are still being scanned are kept.
     */
    private void saveSelection() {
        final Set<String> result = new HashSet<>();
        final Set<Uri> loaded = new HashSet<>();
        for (TreeNode root : roots) {
            loaded.add(root.treeUri);
            encodeSelection(root, result);
        }
        final List<String> pendingPrefixes = new ArrayList<>();
        for (Uri treeUri : Settings.getRoots(getApplication())) {
            if (!loaded.contains(treeUri)) {
                pendingPrefixes.add(documentUriPrefix(treeUri));
            }
        }
        for (String stored : Settings.getSelection(getApplication())) {
            for (String prefix : pendingPrefixes) {
                if (stored.startsWith(prefix)) {
                    result.add(stored);
                    break;
                }
            }
        }
        Settings.setSelection(getApplication(), result);
    }

    private static void encodeSelection(final TreeNode node, final Set<String> result) {
        if (!node.directory) {
            if (node.selected) {
                result.add(node.uri.toString());
            }
        } else if (node.totalFiles > 0 && node.selectedFiles == node.totalFiles) {
            result.add(node.uri.toString());
        } else if (node.selectedFiles > 0) {
            for (TreeNode child : node.children) {
                encodeSelection(child, result);
            }
        }
    }

    /** Restores a stored selection in the (not yet published) subtree. */
    private static int restoreSelection(final TreeNode node, final Set<String> stored) {
        if (stored.contains(node.uri.toString())) {
            return setSelectedRecursive(node, true);
        }
        if (!node.directory) {
            return 0;
        }
        int delta = 0;
        for (TreeNode child : node.children) {
            delta += restoreSelection(child, stored);
        }
        node.selectedFiles += delta;
        return delta;
    }

    private static int setSelectedRecursive(final TreeNode node, final boolean select) {
        if (!node.directory) {
            if (node.selected == select) {
                return 0;
            }
            node.selected = select;
            return select ? 1 : -1;
        }
        int delta = 0;
        for (TreeNode child : node.children) {
            delta += setSelectedRecursive(child, select);
        }
        node.selectedFiles += delta;
        return delta;
    }

    boolean hasSelection() {
        for (TreeNode root : roots) {
            if (root.selectedFiles > 0) {
                return true;
            }
        }
        return false;
    }

    /** All selected files in tree order (= read order). */
    List<TreeNode> getSelectedFiles() {
        final List<TreeNode> result = new ArrayList<>();
        for (TreeNode root : roots) {
            collectSelected(root, result);
        }
        return result;
    }

    private static void collectSelected(final TreeNode node, final List<TreeNode> result) {
        if (!node.directory) {
            if (node.selected) {
                result.add(node);
            }
            return;
        }
        if (node.selectedFiles == 0) {
            return;
        }
        for (TreeNode child : node.children) {
            collectSelected(child, result);
        }
    }

    /**
     * Saved playback position, provided exactly one directory is played: either all files are located
     * directly in the same directory, or the selection covers exactly one directory including its
     * subdirectories. If there are several entries, the most recently saved one wins.
     */
    ResumeStore.Entry findResumeEntry(final List<TreeNode> files) {
        TreeNode common = files.get(0).parent;
        boolean sameParent = true;
        for (TreeNode file : files) {
            sameParent &= file.parent == common;
            while (common != null && !file.isDescendantOf(common)) {
                common = common.parent;
            }
        }
        if (common == null || (!sameParent && common.selectedFiles != common.totalFiles)) {
            return null;
        }
        final Set<TreeNode> directories = new LinkedHashSet<>();
        final Set<String> fileUris = new HashSet<>();
        for (TreeNode file : files) {
            directories.add(file.parent);
            fileUris.add(file.uri.toString());
        }
        ResumeStore.Entry best = null;
        for (TreeNode directory : directories) {
            final ResumeStore.Entry entry = resumeStore.get(directory.uri.toString());
            if (entry != null && fileUris.contains(entry.fileUri) && (best == null || entry.savedAt > best.savedAt)) {
                best = entry;
            }
        }
        return best;
    }

    /** Finds a file in the tree by its document URI; {@code null} if not (or no longer) present. */
    TreeNode findFile(final String uri) {
        for (TreeNode root : roots) {
            if (uri.startsWith(documentUriPrefix(root.treeUri))) {
                final TreeNode found = findFile(root, uri);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TreeNode findFile(final TreeNode node, final String uri) {
        if (!node.directory) {
            return node.uri.toString().equals(uri) ? node : null;
        }
        for (TreeNode child : node.children) {
            final TreeNode found = findFile(child, uri);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Media ID of the track that was last shown automatically in the info area. Kept in the ViewModel
     * so that a screen rotation does not override the user's manual choice.
     */
    String getAutoInfoMediaId() {
        return autoInfoMediaId;
    }

    void setAutoInfoMediaId(final String mediaId) {
        autoInfoMediaId = mediaId;
    }

    void clearInfo() {
        infoNode.setValue(null);
    }

    LiveData<Cover> getCover() {
        return cover;
    }

    /** Cover image of a file; {@code bitmap} is {@code null} if the file does not contain one. */
    static final class Cover {
        final TreeNode node;
        final Bitmap bitmap;

        Cover(final TreeNode node, final Bitmap bitmap) {
            this.node = node;
            this.bitmap = bitmap;
        }
    }

    /** Shows details for the entry and loads missing metadata and the cover in the background. */
    void showInfo(final TreeNode node) {
        infoNode.setValue(node);
        loadCover(node);
        final List<TreeNode> toLoad = new ArrayList<>();
        if (node.directory) {
            for (TreeNode child : node.children) {
                if (!child.directory && !child.metadataLoaded && !child.metadataLoading) {
                    toLoad.add(child);
                }
            }
        } else if (!node.metadataLoaded && !node.metadataLoading) {
            toLoad.add(node);
        }
        if (toLoad.isEmpty()) {
            return;
        }
        for (TreeNode n : toLoad) {
            n.metadataLoading = true;
        }
        metadataExecutor.execute(() -> {
            for (TreeNode n : toLoad) {
                final AudioMetadata metadata = AudioMetadata.read(getApplication(), n.uri);
                mainHandler.post(() -> {
                    n.applyMetadata(metadata);
                    final TreeNode current = infoNode.getValue();
                    if (current == n || current == n.parent) {
                        infoNode.setValue(current);
                    }
                });
            }
        });
    }

    private void loadCover(final TreeNode node) {
        final Cover current = cover.getValue();
        if (current != null && current.node == node) {
            return;
        }
        cover.setValue(null);
        if (node.directory) {
            return;
        }
        coverExecutor.execute(() -> {
            if (infoNode.getValue() != node) {
                return; // something else was tapped meanwhile
            }
            final Bitmap bitmap = AudioMetadata.readCover(getApplication(), node.uri, COVER_MAX_SIZE_PX);
            mainHandler.post(() -> {
                if (infoNode.getValue() == node) {
                    cover.setValue(new Cover(node, bitmap));
                }
            });
        });
    }

    private void scan(final Uri treeUri) {
        pendingScans++;
        scanning.setValue(true);
        final ContentResolver resolver = getApplication().getContentResolver();
        scanExecutor.execute(() -> {
            final TreeNode root = TreeScanner.scan(resolver, treeUri);
            restoreSelection(root, Settings.getSelection(getApplication()));
            mainHandler.post(() -> {
                pendingScans--;
                // may already have been removed again while scanning
                if (Settings.getRoots(getApplication()).contains(treeUri)) {
                    roots.add(root);
                }
                scanning.setValue(pendingScans > 0);
                bumpTreeVersion();
            });
        });
    }

    /** Removes saved playback positions whose file no longer exists. */
    private void removeStaleResumeEntries() {
        final ContentResolver resolver = getApplication().getContentResolver();
        for (Map.Entry<String, ResumeStore.Entry> e : resumeStore.getAll().entrySet()) {
            if (!TreeScanner.exists(resolver, Uri.parse(e.getValue().fileUri))) {
                resumeStore.remove(e.getKey());
            }
        }
    }

    private void bumpTreeVersion() {
        final Integer v = treeVersion.getValue();
        treeVersion.setValue(v == null ? 1 : v + 1);
    }

    @Override
    protected void onCleared() {
        scanExecutor.shutdownNow();
        metadataExecutor.shutdownNow();
        coverExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
