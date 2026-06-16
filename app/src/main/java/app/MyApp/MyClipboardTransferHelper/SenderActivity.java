package app.MyApp.MyClipboardTransferHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import app.MyApp.MyClipboardTransferHelper.protocol.TLVHelper;
import app.MyApp.MyClipboardTransferHelper.security.CryptoHelper;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class SenderActivity extends AppCompatActivity {

    private static final String TAG = "SenderActivity";
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private EditText editTextIp;
    private EditText editTextPort;
    private EditText editTextDebounce;
    private EditText editTextInput;
    private Button buttonConnect;
    private Button buttonOneKeyPaste;

    private SSLSocket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable sendRunnable;
    private String lastText = "";
    private Thread heartbeatThread;

    // Known hosts
    private Map<String, String> knownHosts;
    private File knownHostsFile;

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
        buttonOneKeyPaste = findViewById(R.id.button3);
        buttonOneKeyPaste.setOnClickListener(v -> {
            if (!connected || !authenticated) {
                Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        sendTextData(text.toString());
                    }
                }
            }
        });

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

        knownHostsFile = new File(getFilesDir(), AppClass.SENDER_DIR + File.separator + AppClass.KNOWN_HOSTS_FILE);
        new File(getFilesDir(), AppClass.SENDER_DIR).mkdirs();
        knownHosts = CryptoHelper.loadKnownHosts(knownHostsFile);
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

        setConfigEnabled(false);
        buttonConnect.setText(R.string.connecting);
        buttonConnect.setEnabled(false);

        final String serverIp = ip;
        final int serverPort = port;

        new Thread(() -> {
            try {
                // 1. Create TLS connection
                SSLContext sslContext = CryptoHelper.createClientSSLContext();
                SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(serverIp, serverPort);
                sslSocket.startHandshake();

                // 2. Get server certificate and check known hosts
                X509Certificate serverCert = (X509Certificate) sslSocket.getSession().getPeerCertificates()[0];
                String fingerprint = CryptoHelper.getCertificateSha1Fingerprint(serverCert);

                CryptoHelper.KnownHostStatus status = CryptoHelper.checkKnownHost(knownHosts, serverIp, fingerprint);
                if (status == CryptoHelper.KnownHostStatus.UNKNOWN) {
                    boolean accepted = showKnownHostDialog(
                            getString(R.string.known_host_new_title),
                            getString(R.string.known_host_new_message, serverIp + ":" + serverPort, fingerprint));
                    if (!accepted) {
                        sslSocket.close();
                        runOnUiThread(() -> {
                            Toast.makeText(this, R.string.connection_rejected, Toast.LENGTH_SHORT).show();
                            resetUI();
                        });
                        return;
                    }
                    knownHosts.put(serverIp, fingerprint);
                    CryptoHelper.saveKnownHosts(knownHostsFile, knownHosts);
                } else if (status == CryptoHelper.KnownHostStatus.KNOWN_MISMATCH) {
                    String oldFingerprint = knownHosts.get(serverIp);
                    boolean accepted = showKnownHostDialog(
                            getString(R.string.known_host_changed_title),
                            getString(R.string.known_host_changed_message, serverIp + ":" + serverPort, oldFingerprint, fingerprint));
                    if (!accepted) {
                        sslSocket.close();
                        runOnUiThread(() -> {
                            Toast.makeText(this, R.string.connection_rejected, Toast.LENGTH_SHORT).show();
                            resetUI();
                        });
                        return;
                    }
                    knownHosts.put(serverIp, fingerprint);
                    CryptoHelper.saveKnownHosts(knownHostsFile, knownHosts);
                }

                // 3. Setup I/O
                socket = sslSocket;
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                connected = true;
                authenticated = false;

                // 4. Send Auth-Query
                TLVHelper.sendMessage(out, TLVHelper.TYPE_AUTH_QUERY, null);

                // 5. Auth loop
                boolean firstPasswordAttempt = true;
                while (connected && !authenticated) {
                    TLVHelper.TLVMessage msg = TLVHelper.readMessage(in);
                    if (msg.type == TLVHelper.TYPE_AUTH_FEEDBACK) {
                        long authStatus = bytesToLong(msg.value);
                        if (authStatus == 0x00000001L) {
                            authenticated = true;
                        } else {
                            String password = showPasswordDialog(firstPasswordAttempt);
                            firstPasswordAttempt = false;
                            if (password == null) {
                                // User cancelled
                                disconnect();
                                runOnUiThread(this::resetUI);
                                return;
                            }
                            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
                            TLVHelper.sendMessage(out, TLVHelper.TYPE_AUTH, passwordBytes);
                        }
                    }
                }

                // 6. Authenticated - enable UI
                runOnUiThread(() -> {
                    buttonConnect.setText(R.string.disconnect);
                    buttonConnect.setEnabled(true);
                    setInputEnabled(true);
                    editTextInput.requestFocus();
                    Toast.makeText(this, R.string.auth_success, Toast.LENGTH_SHORT).show();
                });

                // 7. Start heartbeat
                startHeartbeat();

                // 8. Read loop for heartbeat responses (receiver also sends heartbeats)
                while (connected) {
                    TLVHelper.TLVMessage msg = TLVHelper.readMessage(in);
                    if (msg.type == TLVHelper.TYPE_HEARTBEAT) {
                        // no-op, keep alive
                    }
                }

            } catch (java.io.EOFException e) {
                Log.e(TAG, "connection closed by peer", e);
                disconnect();
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.connection_closed_by_peer, Toast.LENGTH_SHORT).show();
                    resetUI();
                });
            } catch (java.net.ConnectException e) {
                Log.e(TAG, "connection refused", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.connection_refused, Toast.LENGTH_SHORT).show();
                    resetUI();
                });
            } catch (java.net.SocketTimeoutException e) {
                Log.e(TAG, "connection timed out", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.connection_timed_out, Toast.LENGTH_SHORT).show();
                    resetUI();
                });
            } catch (Exception e) {
                Log.e(TAG, "connection error", e);
                if (connected) {
                    disconnect();
                }
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                final String errorMsg = msg;
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.connection_error, errorMsg), Toast.LENGTH_SHORT).show();
                    resetUI();
                });
            }
        }).start();
    }

    private boolean showKnownHostDialog(String title, String message) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);
        runOnUiThread(() -> new AlertDialog.Builder(SenderActivity.this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    accepted.set(true);
                    latch.countDown();
                })
                .setNegativeButton(android.R.string.no, (dialog, which) -> {
                    accepted.set(false);
                    latch.countDown();
                })
                .setCancelable(false)
                .show());
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return accepted.get();
    }

    private String showPasswordDialog(boolean firstAttempt) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>(null);

        int messageRes = firstAttempt ? R.string.password_required_first : R.string.password_required_retry;

        runOnUiThread(() -> {
            EditText input = new EditText(SenderActivity.this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            input.setHint(R.string.password_hint);

            AlertDialog dialog = new AlertDialog.Builder(SenderActivity.this)
                    .setTitle(R.string.password_required_title)
                    .setMessage(getString(messageRes))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, (d, which) -> {
                        result.set(null);
                        latch.countDown();
                    })
                    .setCancelable(false)
                    .create();

            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String password = input.getText().toString();
                if (password.isEmpty()) return;
                result.set(password);
                dialog.dismiss();
                latch.countDown();
            }));

            dialog.show();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (connected) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (connected && out != null) {
                        synchronized (out) {
                            TLVHelper.sendHeartbeat(out);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "heartbeat send error", e);
                    break;
                }
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void disconnect() {
        connected = false;
        authenticated = false;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
        final DataInputStream inRef = in;
        final DataOutputStream outRef = out;
        final SSLSocket socketRef = socket;
        in = null;
        out = null;
        socket = null;
        // TLS close_notify requires network I/O — must be off main thread
        new Thread(() -> {
            try { if (inRef != null) inRef.close(); } catch (IOException ignored) {}
            try { if (outRef != null) outRef.close(); } catch (IOException ignored) {}
            try { if (socketRef != null) socketRef.close(); } catch (IOException ignored) {}
        }).start();
        runOnUiThread(() -> {
            debounceHandler.removeCallbacks(sendRunnable);
            resetUI();
        });
    }

    private void resetUI() {
        buttonConnect.setText(R.string.connect);
        buttonConnect.setEnabled(true);
        setConfigEnabled(true);
        setInputEnabled(false);
        editTextInput.setText("");
    }

    private void scheduleSend() {
        if (!connected || !authenticated) return;
        debounceHandler.removeCallbacks(sendRunnable);

        final String text = editTextInput.getText().toString();
        if (text.equals(lastText)) return;

        long debounce = getDebounceMillis();
        sendRunnable = () -> {
            String toSend = editTextInput.getText().toString();
            if (toSend.isEmpty()) return;
            sendTextData(toSend);
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

    private void sendTextData(String text) {
        if (out == null || !connected || !authenticated) return;
        new Thread(() -> {
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                synchronized (out) {
                    TLVHelper.sendMessage(out, TLVHelper.TYPE_TEXT_DATA, data);
                }
            } catch (IOException e) {
                Log.e(TAG, "send error", e);
                runOnUiThread(() -> new AlertDialog.Builder(SenderActivity.this)
                        .setTitle(R.string.send_failed)
                        .setMessage(e.getMessage())
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> disconnect())
                        .setCancelable(false)
                        .show());
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

    private static long bytesToLong(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
