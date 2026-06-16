package app.MyApp.MyClipboardTransferHelper.security;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import top.clspd.apps.MyClipboardTransferHelper.R;

public class ReceiverNewConnectionConfirmActivity extends AppCompatActivity {

    public static final String EXTRA_CLIENT_IP = "client_ip";
    public static final String EXTRA_CLIENT_PORT = "client_port";
    public static final String EXTRA_CERT_FINGERPRINT = "cert_fingerprint";

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_receiver_new_connection_confirm);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Intent intent = getIntent();
        String clientIp = intent.getStringExtra(EXTRA_CLIENT_IP);
        int clientPort = intent.getIntExtra(EXTRA_CLIENT_PORT, -1);
        String fingerprint = intent.getStringExtra(EXTRA_CERT_FINGERPRINT);

        TextView infoView = findViewById(R.id.textView_clientIp);
        String info = getString(R.string.clientAttemptConnect_contentInfo, clientIp, clientPort)
                + "\n\nFingerprint: " + (fingerprint != null ? fingerprint : "N/A");
        infoView.setText(info);
    }

    public void onAccept(View view) {
        setResult(RESULT_OK);
        finish();
    }

    public void onRefuse(View view) {
        setResult(RESULT_CANCELED);
        finish();
    }
}
