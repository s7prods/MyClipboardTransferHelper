package app.MyApp.MyClipboardTransferHelper.management;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class InternalReceivedFilesManagementActivity extends AppCompatActivity {

    private LinearLayout filesContainer;
    private File filesDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_internal_received_files_management);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        filesContainer = findViewById(R.id.filesContainer);
        filesDir = new File(getFilesDir(), AppClass.RECEIVED_FILES_DIR);
        filesDir.mkdirs();

        refreshList();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_internal_received_files_management, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_open_in_documents) {
            String authority = getPackageName() + ".documents";
            Uri rootUri = DocumentsContract.buildRootUri(authority, getPackageName());
            Intent intent = new Intent(Intent.ACTION_VIEW, rootUri);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, R.string.documentsui_not_found, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_delete_all) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_all_files)
                    .setMessage(R.string.delete_all_files_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        deleteAllFiles();
                        refreshList();
                        Toast.makeText(this, R.string.no_files, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteAllFiles() {
        File[] files = filesDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        filesContainer.removeAllViews();
        File[] files = filesDir.listFiles();
        if (files == null || files.length == 0) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            if (file.isFile()) {
                addFileRow(file);
            }
        }
    }

    private void addFileRow(final File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 12, 0, 12);

        TextView nameView = new TextView(this);
        nameView.setText(file.getName());
        nameView.setTextSize(16);
        nameView.setTextColor(getColor(android.R.color.primary_text_dark));

        String info = formatFileSize(file.length()) + "  "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(file.lastModified()));
        TextView infoView = new TextView(this);
        infoView.setText(info);
        infoView.setTextSize(12);

        row.addView(nameView);
        row.addView(infoView);

        row.setOnClickListener(v -> openFile(file));
        row.setOnLongClickListener(v -> {
            showDeleteDialog(file);
            return true;
        });

        filesContainer.addView(row);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(0x33000000);
        filesContainer.addView(divider);
    }

    private void openFile(File file) {
        Uri uri = FileProvider.getUriForFile(this,
                getString(R.string.provider_transferred_files), file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, getMimeType(file.getName()));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, null);
        if (chooser.resolveActivity(getPackageManager()) != null) {
            startActivity(chooser);
        } else {
            Toast.makeText(this, R.string.file_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showDeleteDialog(final File file) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_host)
                .setMessage(getString(R.string.delete_file_confirm, file.getName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    file.delete();
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        if (exp < 1) exp = 1;
        char prefix = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), prefix);
    }

    private static String getMimeType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.US);
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "html": case "htm": return "text/html";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            case "mp4": return "video/mp4";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            default: return "*/*";
        }
    }
}
