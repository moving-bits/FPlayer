package net.movingbits.fplayer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import java.util.Locale;

/** Reads a directory tree granted via SAF completely. */
final class TreeScanner {

    private static final String TAG = "TreeScanner";

    /**
     * Supported file extensions. WMA is deliberately missing: ExoPlayer/Media3 cannot read the ASF
     * container format, so such files would not be playable.
     */
    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a", ".wma"};

    private static final String[] PROJECTION = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
    };

    private TreeScanner() {
    }

    /** Blocking – do not call on the main thread. */
    static TreeNode scan(final ContentResolver resolver, final Uri treeUri) {
        final String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        final Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        final String name = queryDisplayName(resolver, documentUri);
        final TreeNode root = new TreeNode(treeUri, documentUri, documentId, name != null ? name : documentId, true, null);
        scanChildren(resolver, root);
        return root;
    }

    static boolean isAudioFile(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Checks whether a document (still) exists and is accessible. */
    static boolean exists(final ContentResolver resolver, final Uri documentUri) {
        try (Cursor c = resolver.query(documentUri, new String[]{Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void scanChildren(final ContentResolver resolver, final TreeNode dir) {
        final Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dir.treeUri, dir.documentId);
        try (Cursor c = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            while (c != null && c.moveToNext()) {
                final String id = c.getString(0);
                final String name = c.getString(1);
                final String mimeType = c.getString(2);
                // ignore hidden entries (e.g. ".thumbnails")
                if (id == null || name == null || name.startsWith(".")) {
                    continue;
                }
                final Uri uri = DocumentsContract.buildDocumentUriUsingTree(dir.treeUri, id);
                if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                    final TreeNode child = new TreeNode(dir.treeUri, uri, id, name, true, dir);
                    scanChildren(resolver, child);
                    dir.children.add(child);
                    dir.totalFiles += child.totalFiles;
                } else if (isAudioFile(name)) {
                    dir.children.add(new TreeNode(dir.treeUri, uri, id, name, false, dir));
                    dir.directFiles++;
                    dir.totalFiles++;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read directory: " + dir.uri, e);
        }
    }

    private static String queryDisplayName(final ContentResolver resolver, final Uri documentUri) {
        try (Cursor c = resolver.query(documentUri, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read name: " + documentUri, e);
        }
        return null;
    }
}
