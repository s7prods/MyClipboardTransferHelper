package app.MyApp.MyClipboardTransferHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import app.MyApp.MyClipboardTransferHelper.protocol.TLVHelper;
import app.MyApp.MyClipboardTransferHelper.security.CryptoHelper;
import app.MyApp.MyClipboardTransferHelper.services.KeepAliveService;
import app.MyApp.MyClipboardTransferHelper.util.ThemeHelper;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class SenderActivity extends AppCompatActivity {

    private static final String TAG = "SenderActivity";
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private EditText editTextIp;
    private EditText editTextPort;
    private EditText editTextDebounce;
    private EditText editTextInput;
    private Button buttonConnect;

    private SSLSocket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;
    private final Object outLock = new Object();

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable sendRunnable;
    private String lastText = "";
    private Thread heartbeatThread;

    // Known hosts
    private Map<String, String> knownHosts;
    private File knownHostsFile;

    // File pickers (multi-select)
    private ActivityResultLauncher<String> pickImagesLauncher;
    private ActivityResultLauncher<String[]> pickFilesLauncher;

    // File transfer state
    private String lastConnectedIp;
    private int lastConnectedPort;

    private volatile boolean transferCancelled;
    private AlertDialog transferDialog;
    private TextView transferDialogText;
    private ProgressBar transferDialogProgress;

    // File transfer response coordination (shared with connect thread's read loop)
    private volatile TLVHelper.TLVMessage fileTransferResponse;
    private final Object fileTransferLock = new Object();

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

        knownHostsFile = new File(getFilesDir(), AppClass.SENDER_DIR + File.separator + AppClass.KNOWN_HOSTS_FILE);
        if (!new File(getFilesDir(), AppClass.SENDER_DIR).mkdirs() && !new File(getFilesDir(), AppClass.SENDER_DIR).isDirectory()) {
            Log.w(TAG, "Failed to create sender directory");
        }
        knownHosts = CryptoHelper.loadKnownHosts(knownHostsFile);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Restore persisted form state
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        editTextIp.setText(prefs.getString("sender_ip", ""));
        editTextPort.setText(prefs.getString("sender_port", ""));
        editTextDebounce.setText(String.valueOf(prefs.getInt("sender_debounce", 100)));

        pickImagesLauncher = registerForActivityResult(
                new ActivityResultContracts.GetMultipleContents(),
                this::handleFileUris);
        pickFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::handleFileUris);
    }

    public void onConnectClick(View view) {
        if (connected) {
            disconnect();
        } else {
            connect();
        }
    }

    public void onOneKeyPasteClick(View v) {
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
        lastConnectedIp = serverIp;
        lastConnectedPort = serverPort;

        new Thread(() -> {
            try {
                // 1. Create TLS connection
                SSLContext sslContext = CryptoHelper.createClientSSLContext();
                SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(serverIp, serverPort);
                sslSocket.startHandshake();

                // 2. Get server certificate and check known hosts
                X509Certificate serverCert = (X509Certificate) sslSocket.getSession().getPeerCertificates()[0];
                String fingerprint = CryptoHelper.getCertificateSha256Fingerprint(serverCert);

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
                    toastOnUi(R.string.known_host_added, serverIp + ":" + serverPort);
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
                    toastOnUi(R.string.known_host_updated, serverIp + ":" + serverPort);
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

                // 7. Start heartbeat + keepalive service
                startHeartbeat();
                ensureNotificationPermission();
                KeepAliveService.start(SenderActivity.this);

                // 8. Read loop — dispatch heartbeats and file transfer responses
                while (connected) {
                    TLVHelper.TLVMessage msg = TLVHelper.readMessage(in);
                    if (msg.type >= TLVHelper.TYPE_FILE_TRANSFER_SESSION_CREATE_RESULT
                            && msg.type <= TLVHelper.TYPE_FILE_TRANSFER_SESSION_RESULT) {
                        synchronized (fileTransferLock) {
                            fileTransferResponse = msg;
                            fileTransferLock.notifyAll();
                        }
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
                final String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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
                        synchronized (outLock) {
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

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("notification_explained", false)) return;
        prefs.edit().putBoolean("notification_explained", true).apply();
        runOnUiThread(() -> new AlertDialog.Builder(SenderActivity.this)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 0 && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Restart service so notification becomes visible immediately
            KeepAliveService.stop(SenderActivity.this);
            KeepAliveService.start(SenderActivity.this);
        }
    }

    private void toastOnUi(int resId, Object... args) {
        String msg = getString(resId, args);
        runOnUiThread(() -> Toast.makeText(SenderActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private void disconnect() {
        connected = false;
        authenticated = false;
        KeepAliveService.stop(SenderActivity.this);
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

    public void onSendPicturesClicked(View view) {
        pickImagesLauncher.launch("image/*");
    }

    public void onSendFilesClicked(View view) {
        pickFilesLauncher.launch(new String[]{"*/*"});
    }

    private void handleFileUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        transferCancelled = false;

        // Build transfer dialog
        runOnUiThread(() -> {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(48, 24, 48, 0);

            transferDialogText = new TextView(this);
            transferDialogText.setText(R.string.sending_files);
            transferDialogText.setTextSize(16);
            layout.addView(transferDialogText);

            transferDialogProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            transferDialogProgress.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            transferDialogProgress.setMax(100);
            layout.addView(transferDialogProgress);

            transferDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.sending_files)
                    .setView(layout)
                    .setNegativeButton(R.string.cancel, (d, w) -> transferCancelled = true)
                    .setCancelable(false)
                    .show();
        });

        new Thread(() -> {
            // Auto-reconnect if connection was lost while picker was open
            if (!connected || !authenticated) {
                if (!reconnectAndAuth()) {
                    runOnUiThread(() -> {
                        if (transferDialog != null && transferDialog.isShowing()) transferDialog.dismiss();
                        Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
            }

            int total = uris.size();
            int done = 0;
            for (Uri uri : uris) {
                if (transferCancelled || !connected || !authenticated) break;
                try {
                    String filename = resolveFileName(uri);
                    long fileSize = resolveFileSize(uri);
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is == null) continue;
                    boolean ok = sendFileViaSession(filename, fileSize, is);
                    try { is.close(); } catch (IOException ignored) {}
                    if (!ok && transferCancelled) break;
                    done++;
                } catch (Exception e) {
                    Log.e(TAG, "transfer error", e);
                }
            }
            final int sentCount = done;
            runOnUiThread(() -> {
                if (transferDialog != null && transferDialog.isShowing()) {
                    transferDialog.dismiss();
                }
                if (transferCancelled) {
                    Toast.makeText(this, R.string.transfer_cancelled, Toast.LENGTH_SHORT).show();
                } else if (sentCount == total) {
                    Toast.makeText(this, R.string.transfer_complete, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private String resolveFileName(Uri uri) {
        String filename = "file";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (idx >= 0 && cursor.moveToFirst()) {
                String n = cursor.getString(idx);
                if (n != null && !n.isEmpty()) filename = n;
            }
            cursor.close();
        }
        return filename;
    }

    private long resolveFileSize(Uri uri) {
        long size = 0;
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (idx >= 0 && cursor.moveToFirst()) size = cursor.getLong(idx);
            cursor.close();
        }
        return size;
    }

    private boolean sendFileViaSession(String filename, long fileSize, InputStream is) {
        try {
            // 1. SessionCreate
            byte[] nameBytes = filename.getBytes(StandardCharsets.UTF_8);
            byte[] createValue = new byte[4 + nameBytes.length + 8];
            createValue[0] = (byte) (nameBytes.length >>> 24);
            createValue[1] = (byte) (nameBytes.length >>> 16);
            createValue[2] = (byte) (nameBytes.length >>> 8);
            createValue[3] = (byte) nameBytes.length;
            System.arraycopy(nameBytes, 0, createValue, 4, nameBytes.length);
            byte[] sizeBytes = TLVHelper.longToBytes(fileSize);
            System.arraycopy(sizeBytes, 0, createValue, 4 + nameBytes.length, 8);

            synchronized (outLock) {
                TLVHelper.sendMessage(out, TLVHelper.TYPE_FILE_TRANSFER_SESSION_CREATE, createValue);
            }

            TLVHelper.TLVMessage result = readFileTransferResponse();
            if (result.type != TLVHelper.TYPE_FILE_TRANSFER_SESSION_CREATE_RESULT) return false;
            if (result.value.length < 9) return false;
            long sessionId = TLVHelper.bytesToLong(result.value, 0);
            boolean accepted = result.value[8] == 0x01;
            if (!accepted) return false;

            // Show 0% progress immediately before first chunk starts
            final long fileSizeForUi = fileSize > 0 ? fileSize : 1;
            runOnUiThread(() -> {
                if (transferDialogText != null) {
                    transferDialogText.setText(getString(R.string.sending_file_progress,
                            filename, formatSize(0), formatSize(fileSizeForUi)));
                }
                if (transferDialogProgress != null) {
                    transferDialogProgress.setProgress(0);
                }
            });

            // 2. Send chunks, wait for ACK each
            byte[] chunkBuf = new byte[TLVHelper.FILE_CHUNK_SIZE];
            long totalSent = 0;
            int n;
            while (!transferCancelled && (n = is.read(chunkBuf)) != -1) {
                byte[] chunk = new byte[8 + n];
                System.arraycopy(TLVHelper.longToBytes(sessionId), 0, chunk, 0, 8);
                System.arraycopy(chunkBuf, 0, chunk, 8, n);

                synchronized (outLock) {
                    TLVHelper.sendMessage(out, TLVHelper.TYPE_FILE_TRANSFER_FILE_DATA, chunk);
                }

                TLVHelper.TLVMessage ack = readFileTransferResponse();
                if (ack.type != TLVHelper.TYPE_FILE_TRANSFER_FILE_DATA_ACCEPTED) {
                    sendCancel(sessionId);
                    return false;
                }

                totalSent += n;
                final long sent = totalSent;
                final long total = fileSize > 0 ? fileSize : 1;
                runOnUiThread(() -> {
                    if (transferDialogText != null) {
                        transferDialogText.setText(getString(R.string.sending_file_progress,
                                filename, formatSize(sent), formatSize(total)));
                    }
                    if (transferDialogProgress != null) {
                        transferDialogProgress.setProgress((int) (sent * 100 / total));
                    }
                });
            }

            if (transferCancelled) {
                sendCancel(sessionId);
                return false;
            }

            // 3. SessionCommit
            synchronized (outLock) {
                TLVHelper.sendMessage(out, TLVHelper.TYPE_FILE_TRANSFER_SESSION_COMMIT,
                        TLVHelper.longToBytes(sessionId));
            }

            TLVHelper.TLVMessage commitResult = readFileTransferResponse();
            return commitResult.type == TLVHelper.TYPE_FILE_TRANSFER_SESSION_RESULT;

        } catch (IOException e) {
            Log.e(TAG, "file session error", e);
            runOnUiThread(() -> {
                if (transferDialog != null && transferDialog.isShowing()) transferDialog.dismiss();
                Toast.makeText(this, getString(R.string.transfer_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            });
            return false;
        }
    }

    private TLVHelper.TLVMessage readFileTransferResponse() {
        synchronized (fileTransferLock) {
            while (fileTransferResponse == null && connected && !transferCancelled) {
                try { fileTransferLock.wait(100); } catch (InterruptedException e) { break; }
            }
            TLVHelper.TLVMessage msg = fileTransferResponse;
            fileTransferResponse = null;
            return msg;
        }
    }

    private boolean reconnectAndAuth() {
        if (lastConnectedIp == null) return false;
        try {
            SSLContext sslContext = CryptoHelper.createClientSSLContext();
            SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(lastConnectedIp, lastConnectedPort);
            sslSocket.setSoTimeout(0);
            sslSocket.startHandshake();

            X509Certificate serverCert = (X509Certificate) sslSocket.getSession().getPeerCertificates()[0];
            String fingerprint = CryptoHelper.getCertificateSha256Fingerprint(serverCert);
            CryptoHelper.KnownHostStatus status = CryptoHelper.checkKnownHost(knownHosts, lastConnectedIp, fingerprint);
            if (status != CryptoHelper.KnownHostStatus.KNOWN_MATCH) {
                sslSocket.close();
                return false;
            }

            socket = sslSocket;
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            connected = true;
            authenticated = false;

            // Auth
            TLVHelper.sendMessage(out, TLVHelper.TYPE_AUTH_QUERY, null);
            boolean firstAttempt = true;
            while (connected && !authenticated) {
                TLVHelper.TLVMessage msg = TLVHelper.readMessage(in);
                if (msg.type == TLVHelper.TYPE_AUTH_FEEDBACK) {
                    long authStatus = bytesToLong(msg.value);
                    if (authStatus == 0x00000001L) {
                        authenticated = true;
                    } else {
                        String password = showPasswordDialog(firstAttempt);
                        firstAttempt = false;
                        if (password == null) {
                            disconnect();
                            return false;
                        }
                        TLVHelper.sendMessage(out, TLVHelper.TYPE_AUTH, password.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            startHeartbeat();
            KeepAliveService.start(SenderActivity.this);
            // Restart the read loop to dispatch file transfer responses
            new Thread(() -> {
                try {
                    while (connected) {
                        TLVHelper.TLVMessage msg = TLVHelper.readMessage(in);
                        if (msg.type >= TLVHelper.TYPE_FILE_TRANSFER_SESSION_CREATE_RESULT
                                && msg.type <= TLVHelper.TYPE_FILE_TRANSFER_SESSION_RESULT) {
                            synchronized (fileTransferLock) {
                                fileTransferResponse = msg;
                                fileTransferLock.notifyAll();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (connected) disconnect();
                }
            }).start();
            return authenticated;
        } catch (Exception e) {
            Log.e(TAG, "reconnect error", e);
            return false;
        }
    }

    private void sendCancel(long sessionId) {
        try {
            synchronized (outLock) {
                TLVHelper.sendMessage(out, TLVHelper.TYPE_FILE_TRANSFER_SESSION_CANCEL,
                        TLVHelper.longToBytes(sessionId));
            }
        } catch (IOException ignored) {}
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        if (exp < 1) exp = 1;
        char prefix = "KMGTPE".charAt(exp - 1);
        return String.format(java.util.Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), prefix);
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
                synchronized (outLock) {
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
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // DayNight theme needs explicit refresh when uiMode is in configChanges
        int nightMask = newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES
                || nightMask == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            ThemeHelper.refreshNightMode(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putString("sender_ip", editTextIp.getText().toString().trim())
                .putString("sender_port", editTextPort.getText().toString().trim())
                .putInt("sender_debounce", (int) getDebounceMillis())
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
