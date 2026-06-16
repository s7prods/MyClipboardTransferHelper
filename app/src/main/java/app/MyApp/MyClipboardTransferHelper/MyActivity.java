package app.MyApp.MyClipboardTransferHelper;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import app.MyApp.MyClipboardTransferHelper.management.InternalReceivedFilesManagementActivity;
import app.MyApp.MyClipboardTransferHelper.management.KnownHostsManagementActivity;
import app.MyApp.MyClipboardTransferHelper.management.SettingsActivity;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class MyActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    public void onCloseAppClick(View view) {
        finishAffinity();
    }

    public void onImsender(View view) {
        Intent intent = new Intent(this, SenderActivity.class);
        startActivity(intent);
    }

    public void onImreceiver(View view) {
        Intent intent = new Intent(this, ReceiverActivity.class);
        startActivity(intent);
    }

    public void onManageKnownHosts(View view) {
        Intent intent = new Intent(this, KnownHostsManagementActivity.class);
        startActivity(intent);
    }

    public void onManageReceivedFiles(View view) {
        Intent intent = new Intent(this, InternalReceivedFilesManagementActivity.class);
        startActivity(intent);
    }

    public void onSettings(View view) {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }
}