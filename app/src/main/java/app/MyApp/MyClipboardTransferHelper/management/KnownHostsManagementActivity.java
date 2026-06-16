package app.MyApp.MyClipboardTransferHelper.management;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import app.MyApp.MyClipboardTransferHelper.security.CryptoHelper;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class KnownHostsManagementActivity extends AppCompatActivity {

    private LinearLayout hostsContainer;
    private TextView textViewEmpty;
    private Map<String, String> knownHosts;
    private File knownHostsFile;

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_known_hosts_management);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        hostsContainer = findViewById(R.id.hostsContainer);
        textViewEmpty = findViewById(R.id.textViewEmpty);
        knownHostsFile = new File(getFilesDir(), AppClass.SENDER_DIR + File.separator + AppClass.KNOWN_HOSTS_FILE);
        new File(getFilesDir(), AppClass.SENDER_DIR).mkdirs();
        knownHosts = CryptoHelper.loadKnownHosts(knownHostsFile);

        refreshList();
    }

    private void refreshList() {
        hostsContainer.removeAllViews();
        if (knownHosts.isEmpty()) {
            textViewEmpty.setVisibility(View.VISIBLE);
            return;
        }
        textViewEmpty.setVisibility(View.GONE);
        for (Map.Entry<String, String> entry : knownHosts.entrySet()) {
            addHostRow(entry.getKey(), entry.getValue());
        }
    }

    private void addHostRow(final String ip, final String fingerprint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        TextView textView = new TextView(this);
        textView.setText(ip + "\n" + fingerprint);
        textView.setTextSize(14);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textView.setLayoutParams(textParams);

        android.widget.Button editBtn = new android.widget.Button(this);
        editBtn.setText(R.string.edit_host);
        editBtn.setTextSize(12);
        editBtn.setOnClickListener(v -> showEditDialog(ip, fingerprint));

        android.widget.Button deleteBtn = new android.widget.Button(this);
        deleteBtn.setText(R.string.delete_host);
        deleteBtn.setTextSize(12);
        deleteBtn.setOnClickListener(v -> showDeleteDialog(ip));

        row.addView(textView);
        row.addView(editBtn);
        row.addView(deleteBtn);
        hostsContainer.addView(row);
    }

    public void onAddHost(View view) {
        showAddDialog();
    }

    private void showAddDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.add_host);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);

        final EditText ipInput = new EditText(this);
        ipInput.setHint(R.string.host_ip_hint);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(ipInput);

        final EditText fpInput = new EditText(this);
        fpInput.setHint(R.string.host_fingerprint_hint);
        fpInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(fpInput);

        builder.setView(layout);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String ip = ipInput.getText().toString().trim();
            String fp = fpInput.getText().toString().trim();
            if (ip.isEmpty() || fp.isEmpty()) {
                Toast.makeText(this, R.string.invalid_host_entry, Toast.LENGTH_SHORT).show();
                return;
            }
            knownHosts.put(ip, fp.toUpperCase());
            persistAndRefresh();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showEditDialog(final String ip, final String oldFingerprint) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.edit_host);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);

        final TextView ipView = new TextView(this);
        ipView.setText(ip);
        ipView.setTextSize(16);
        ipView.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(ipView);

        final EditText fpInput = new EditText(this);
        fpInput.setHint(R.string.host_fingerprint_hint);
        fpInput.setText(oldFingerprint);
        fpInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(fpInput);

        builder.setView(layout);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String newFp = fpInput.getText().toString().trim();
            if (newFp.isEmpty()) {
                Toast.makeText(this, R.string.invalid_host_entry, Toast.LENGTH_SHORT).show();
                return;
            }
            knownHosts.put(ip, newFp.toUpperCase());
            persistAndRefresh();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void showDeleteDialog(final String ip) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_host)
                .setMessage(getString(R.string.delete_host_confirm, ip))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    knownHosts.remove(ip);
                    persistAndRefresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void persistAndRefresh() {
        CryptoHelper.saveKnownHosts(knownHostsFile, knownHosts);
        refreshList();
    }
}
