package app.AppLogic.MainAppLogic.MyClipboardTransferHelper.interophelper;

import static app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass.RECEIVED_FILES_DIR;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

public class InternalReceivedFilesMyDPClass extends DocumentsProvider {
    private static final String[] DEFAULT_ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID
    };
    private static final String[] DEFAULT_DOC_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE
    };

    private String pkgName;
    private File dataDir;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        this.pkgName = context.getPackageName();
        this.dataDir = new File(context.getFilesDir(), RECEIVED_FILES_DIR);
    }

    @Override
    public boolean onCreate() { return true; }

    private File getFile(String docId) throws FileNotFoundException {
        if (!docId.startsWith(pkgName))
            throw new FileNotFoundException(docId + " not found");
        String relative = docId.substring(pkgName.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.isEmpty()) return dataDir;
        File f = new File(dataDir, relative);
        if (!f.exists()) throw new FileNotFoundException(docId + " not found");
        return f;
    }

    private String getDocId(File f) {
        String dataPath = dataDir.getAbsolutePath();
        String filePath = f.getAbsolutePath();
        if (filePath.equals(dataPath)) return pkgName;
        if (filePath.startsWith(dataPath + "/"))
            return pkgName + "/" + filePath.substring(dataPath.length() + 1);
        return pkgName;
    }

    private static String getMimeType(File f) {
        if (f.isDirectory()) return Document.MIME_TYPE_DIR;
        String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        if (projection == null) projection = DEFAULT_ROOT_COLUMNS;
        MatrixCursor c = new MatrixCursor(projection);
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(Root.COLUMN_ROOT_ID, pkgName);
        row.add(Root.COLUMN_DOCUMENT_ID, pkgName);
        row.add(Root.COLUMN_SUMMARY, pkgName);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD | Root.FLAG_SUPPORTS_CREATE);
        row.add(Root.COLUMN_TITLE, "Received Files");
        ApplicationInfo ai = Objects.requireNonNull(getContext()).getApplicationInfo();
        row.add(Root.COLUMN_ICON, ai.icon);
        row.add(Root.COLUMN_MIME_TYPES, "*/*");
        return c;
    }

    @Override
    public Cursor queryDocument(String docId, String[] projection) throws FileNotFoundException {
        if (projection == null) projection = DEFAULT_DOC_COLUMNS;
        File f = getFile(docId);
        MatrixCursor c = new MatrixCursor(projection);
        addRow(c, f);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (projection == null) projection = DEFAULT_DOC_COLUMNS;
        File parent = getFile(parentId);
        File[] children = parent.listFiles();
        MatrixCursor c = new MatrixCursor(projection);
        if (children != null) {
            for (File child : children) addRow(c, child);
        }
        return c;
    }

    private void addRow(MatrixCursor c, File f) {
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, getDocId(f));
        row.add(Document.COLUMN_MIME_TYPE, getMimeType(f));
        row.add(Document.COLUMN_DISPLAY_NAME, f.getName());
        row.add(Document.COLUMN_LAST_MODIFIED, f.lastModified());
        int flags = 0;
        if (f.isDirectory()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, f.length());
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(getFile(docId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = getFile(parentDocId);
        if (!parent.isDirectory())
            throw new FileNotFoundException("Parent is not a directory");
        File file = new File(parent, displayName);
        int n = 1;
        while (file.exists()) {
            int dot = displayName.lastIndexOf('.');
            String name = dot > 0 ? displayName.substring(0, dot) : displayName;
            String ext = dot > 0 ? displayName.substring(dot) : "";
            file = new File(parent, name + " (" + (++n) + ")" + ext);
        }
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                file.mkdirs();
            } else {
                file.createNewFile();
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create " + displayName);
        }
        return getDocId(file);
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        File f = getFile(docId);
        deleteRecursive(f);
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return f.delete();
    }

    @Override
    public boolean isChildDocument(String parentId, String docId) {
        return docId.startsWith(parentId) && !docId.equals(parentId);
    }

    @Override
    public String getDocumentType(String docId) throws FileNotFoundException {
        return getMimeType(getFile(docId));
    }
}
