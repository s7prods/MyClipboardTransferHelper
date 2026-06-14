package app.MyApp.MyClipboardTransferHelper;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import top.clspd.apps.MyClipboardTransferHelper.R;

public class SenderActivity extends AppCompatActivity {

    private static final String TAG = "SenderActivity";

    private EditText editTextIp;
    private EditText editTextPort;
    private EditText editTextDebounce;
    private EditText editTextInput;
    private Button buttonConnect;

    private Socket socket;
    private DataOutputStream out;
    private boolean connected = false;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable sendRunnable;
    private String lastText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sender);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        editTextIp = findViewById(R.id.editTextTextIp);
        editTextPort = findViewById(R.id.editTextText3);
        editTextDebounce = findViewById(R.id.editTextNumberSigned);
        editTextInput = findViewById(R.id.editTextTextMultiLine);
        buttonConnect = findViewById(R.id.buttonConnectOrDisconnect);

        setInputEnabled(false);
        setConfigEnabled(true);

        editTextInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSend();
            }
        });
    }

    public void onConnectClick(View view) {
        if (connected) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String ip = editTextIp.getText().toString().trim();
        String portStr = editTextPort.getText().toString().trim();
        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, R.string.invalid_ip_port, Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                socket = new Socket(ip, port);
                out = new DataOutputStream(socket.getOutputStream());
                connected = true;
                runOnUiThread(() -> {
                    buttonConnect.setText(R.string.disconnect);
                    setConfigEnabled(false);
                    setInputEnabled(true);
                    editTextInput.requestFocus();
                });
            } catch (IOException e) {
                Log.e(TAG, "connect error", e);
                runOnUiThread(() -> Toast.makeText(SenderActivity.this, R.string.connect_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void disconnect() {
        connected = false;
        try {
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "disconnect error", e);
        }
        out = null;
        socket = null;
        debounceHandler.removeCallbacks(sendRunnable);
        buttonConnect.setText(R.string.connect);
        setConfigEnabled(true);
        setInputEnabled(false);
        editTextInput.setText("");
    }

    private void scheduleSend() {
        if (!connected) return;
        debounceHandler.removeCallbacks(sendRunnable);

        final String text = editTextInput.getText().toString();
        if (text.equals(lastText)) return;

        long debounce = getDebounceMillis();
        sendRunnable = () -> {
            String toSend = editTextInput.getText().toString();
            if (toSend.isEmpty()) return;
            sendLengthValue(toSend);
            editTextInput.setText("");
            lastText = "";
        };
        debounceHandler.postDelayed(sendRunnable, debounce);
    }

    private long getDebounceMillis() {
        String t = editTextDebounce.getText().toString().trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private void sendLengthValue(String text) {
        if (out == null) return;
        new Thread(() -> {
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                Log.e(TAG, "send error", e);
                runOnUiThread(() -> {
                    new AlertDialog.Builder(SenderActivity.this)
                            .setTitle(R.string.send_failed)
                            .setMessage(e.getMessage())
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> disconnect())
                            .setCancelable(false)
                            .show();
                });
            }
        }).start();
    }

    private void setInputEnabled(boolean enabled) {
        editTextInput.setEnabled(enabled);
        if (!enabled) editTextInput.setText("");
    }

    private void setConfigEnabled(boolean enabled) {
        editTextIp.setEnabled(enabled);
        editTextPort.setEnabled(enabled);
        editTextDebounce.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}