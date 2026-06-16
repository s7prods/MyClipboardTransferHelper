package app.MyApp.MyClipboardTransferHelper.management;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class SettingsActivity extends AppCompatActivity {

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    public void onRegenCert(View view) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.regen_cert_title)
                .setMessage(R.string.regen_cert_message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        ((AppClass) getApplication()).regenerateCertificate();
                        Toast.makeText(this, R.string.cert_regenerated, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.cert_regen_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void onOpenProjPage(View view) {
        String url = getString(R.string.project_homepage_url);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        Intent chooser = Intent.createChooser(intent, null);
        if (chooser.resolveActivity(getPackageManager()) != null) {
            startActivity(chooser);
        } else {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }
}
