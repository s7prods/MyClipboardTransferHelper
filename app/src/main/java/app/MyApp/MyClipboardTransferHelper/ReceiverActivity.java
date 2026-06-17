package app.MyApp.MyClipboardTransferHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import app.MyApp.MyClipboardTransferHelper.protocol.TLVHelper;
import app.MyApp.MyClipboardTransferHelper.security.CryptoHelper;
import app.MyApp.MyClipboardTransferHelper.security.ReceiverNewConnectionConfirmActivity;
import app.MyApp.MyClipboardTransferHelper.services.KeepAliveService;
import app.MyApp.MyClipboardTransferHelper.util.ThemeHelper;
import top.clspd.apps.MyClipboardTransferHelper.R;

public class ReceiverActivity extends AppCompatActivity {

    private static final String TAG = "ReceiverActivity";
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private boolean started = false;
    private SSLServerSocket serverSocket;
    private Thread serverThread;
    private EditText editTextResult;
    private EditText editTextPassword;
    private CheckBox checkBoxNeedConfirm;
    private Spinner saveLocationSpinner;
    private EditText editTextPort;
    private Button buttonStart;
    private int port = 0;

    private volatile ClientConnection activeClient;
    private File passwordFile;

    private ActivityResultLauncher<Intent> confirmLauncher;
    private CountDownLatch confirmLatch;
    private boolean confirmResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_receiver);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        editTextResult = findViewById(R.id.editTextText);
        editTextPassword = findViewById(R.id.editTextText2);
        checkBoxNeedConfirm = findViewById(R.id.checkBox);
        saveLocationSpinner = findViewById(R.id.spinner);
        buttonStart = findViewById(R.id.buttonStart);
        editTextPort = findViewById(R.id.editTextNumberSigned2);

        // Load saved password
        passwordFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.PASSWORD_FILE);
        if (passwordFile.exists()) {
            try {
                byte[] pwdBytes = new byte[(int) passwordFile.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(passwordFile)) {
                    int read = fis.read(pwdBytes);
                    if (read != pwdBytes.length) {
                        Log.w(TAG, "password file truncated");
                    }
                }
                editTextPassword.setText(new String(pwdBytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "failed to load password", e);
            }
        }

        // Restore persisted UI state
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        checkBoxNeedConfirm.setChecked(prefs.getBoolean("receiver_need_confirm", false));
        saveLocationSpinner.setSelection(prefs.getInt("receiver_save_location", 0));
        editTextPort.setText(String.valueOf(prefs.getInt("receiver_port", 0)));

        // Register launcher for confirmation activity
        confirmLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    confirmResult = (result.getResultCode() == RESULT_OK);
                    if (confirmLatch != null) {
                        confirmLatch.countDown();
                    }
                });
    }

    // ---- Connection state ----

    private static class TransferSession {
        final long sessionId;
        final String filename;
        final long fileSize;
        final File tempFile;
        FileOutputStream fos;
        long bytesReceived;

        TransferSession(long sessionId, String filename, long fileSize, File tempFile) {
            this.sessionId = sessionId;
            this.filename = filename;
            this.fileSize = fileSize;
            this.tempFile = tempFile;
            this.bytesReceived = 0;
        }

        void close() {
            try { if (fos != null) fos.close(); } catch (IOException ignored) {}
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete temp file: " + tempFile);
            }
        }
    }

    private static class ClientConnection {
        SSLSocket socket;
        DataInputStream in;
        DataOutputStream out;
        boolean authenticated;
        Thread readThread;
        Thread heartbeatThread;
        final Object outLock = new Object();
        final Map<Long, TransferSession> sessions = new ConcurrentHashMap<>();

        ClientConnection(SSLSocket socket, DataInputStream in, DataOutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.authenticated = false;
        }

        void close() {
            if (heartbeatThread != null) heartbeatThread.interrupt();
            if (readThread != null) readThread.interrupt();
            // Clean up all active sessions
            for (TransferSession s : sessions.values()) s.close();
            sessions.clear();
            /* TLS close_notify requires network I/O — must be off main thread */
            new Thread(() -> {
                try { in.close(); } catch (IOException ignored) {}
                try { out.close(); } catch (IOException ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
            }).start();
        }
    }

    // ---- Server start/stop ----

    public void startOrStop(View view) {
        if (started) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        // Save password
        String password = editTextPassword.getText().toString();
        try {
            File receiverDir = new File(getFilesDir(), AppClass.RECEIVER_DIR);
            if (!receiverDir.mkdirs() && !receiverDir.isDirectory()) {
                Log.w(TAG, "Failed to create receiver directory");
            }
            try (FileOutputStream fos = new FileOutputStream(passwordFile)) {
                fos.write(password.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "failed to save password", e);
        }

        int parsed;
        try {
            parsed = Integer.parseInt(editTextPort.getText().toString().trim());
            if (parsed < 0) parsed = 0;
            if (parsed > 65535) parsed = 65535;
        } catch (NumberFormatException e) {
            parsed = 0;
        }
        final int requestedPort = parsed;

        serverThread = new Thread(() -> {
            try {
                File certFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.CERT_FILE);
                File keyFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.KEY_FILE);
                SSLContext sslContext = CryptoHelper.createServerSSLContext(certFile, keyFile);
                serverSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(requestedPort);
                port = serverSocket.getLocalPort();
                started = true;

                String fingerprint = CryptoHelper.getCertificateSha256Fingerprint(
                        CryptoHelper.loadCertificateFromFile(certFile));

                runOnUiThread(() -> {
                    buttonStart.setText(R.string.stopListen);
                    editTextResult.setText("");
                    String message = getString(R.string.receiverStarted, getLocalIp() + ":" + port, fingerprint);
                    editTextResult.setText(message);
                    editTextPassword.setEnabled(false);
                    checkBoxNeedConfirm.setEnabled(false);
                });

                ensureNotificationPermission();
                KeepAliveService.start(ReceiverActivity.this);

                while (started && !serverSocket.isClosed()) {
                    try {
                        SSLSocket client = (SSLSocket) serverSocket.accept();
                        String clientIp = client.getInetAddress().getHostAddress();
                        int clientPort = client.getPort();
                        // Only one client at a time
                        synchronized (this) {
                            if (activeClient != null) {
                                logToUI("Rejected " + clientIp + ":" + clientPort + " (already have client)\n");
                                client.close();
                                continue;
                            }
                        }
                        logToUI(getString(R.string.client_connected, clientIp, clientPort));
                        DataInputStream din = new DataInputStream(client.getInputStream());
                        DataOutputStream dout = new DataOutputStream(client.getOutputStream());
                        ClientConnection conn = new ClientConnection(client, din, dout);
                        synchronized (this) { activeClient = conn; }
                        handleClient(conn, password);
                    } catch (IOException e) {
                        if (started) {
                            Log.e(TAG, "accept error", e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "server error", e);
                runOnUiThread(() -> {
                    editTextResult.setText(getString(R.string.startFailed, e.getMessage()));
                    resetServerUI();
                });
            }
        });
        serverThread.start();
    }

    // ---- Client handler ----

    private void handleClient(ClientConnection conn, String savedPassword) {
        final String clientIp = conn.socket.getInetAddress().getHostAddress();
        final int clientPort = conn.socket.getPort();
        conn.readThread = new Thread(() -> {
            try {
                // Start heartbeat
                startHeartbeat(conn);
                // Load server's own cert fingerprint for display in confirm dialog
                File certFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.CERT_FILE);
                X509Certificate serverCert = CryptoHelper.loadCertificateFromFile(certFile);
                String serverFingerprint = CryptoHelper.getCertificateSha256Fingerprint(serverCert);

                while (started && !conn.socket.isClosed()) {
                    TLVHelper.TLVMessage msg = TLVHelper.readMessage(conn.in);

                    switch (msg.type) {
                        case TLVHelper.TYPE_HEARTBEAT:
                            // no-op
                            break;

                        case TLVHelper.TYPE_AUTH_QUERY:
                            handleAuthQuery(conn, savedPassword, clientIp, clientPort, serverFingerprint);
                            break;

                        case TLVHelper.TYPE_AUTH:
                            handleAuth(conn, savedPassword, msg.value, clientIp, clientPort, serverFingerprint);
                            break;

                        case TLVHelper.TYPE_TEXT_DATA:
                            if (conn.authenticated) {
                                String text = new String(msg.value, StandardCharsets.UTF_8);
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                ClipData clip = ClipData.newPlainText("received", text);
                                runOnUiThread(() -> {
                                    clipboard.setPrimaryClip(clip);
                                    editTextResult.append(getString(R.string.receiverReceivedFrom, msg.length, clientIp, clientPort));
                                });
                            }
                            break;

                        case TLVHelper.TYPE_FILE_TRANSFER_SESSION_CREATE:
                            if (conn.authenticated) handleSessionCreate(conn, msg.value);
                            break;

                        case TLVHelper.TYPE_FILE_TRANSFER_FILE_DATA:
                            if (conn.authenticated) handleFileChunk(conn, msg.value);
                            break;

                        case TLVHelper.TYPE_FILE_TRANSFER_SESSION_COMMIT:
                            if (conn.authenticated) handleSessionCommit(conn, msg.value, clientIp, clientPort);
                            break;

                        case TLVHelper.TYPE_FILE_TRANSFER_SESSION_CANCEL:
                            if (conn.authenticated) handleSessionCancel(conn, msg.value);
                            break;

                        default:
                            // Reserved - ignore
                            break;
                    }
                }
            } catch (java.io.EOFException e) {
                // Normal connection close by peer
            } catch (IOException e) {
                if (started) {
                    Log.e(TAG, "client handler error", e);
                }
            } catch (Exception e) {
                Log.e(TAG, "client handler error", e);
            } finally {
                conn.close();
                synchronized (this) {
                    if (activeClient == conn) {
                        activeClient = null;
                    }
                }
                logToUI(getString(R.string.client_disconnected, clientIp, clientPort));
            }
        });
        conn.readThread.start();
    }

    private void handleAuthQuery(ClientConnection conn, String savedPassword,
                                  String clientIp, int clientPort, String fingerprint) throws IOException {
        if (savedPassword.isEmpty()) {
            // No password set: check needConfirm then authenticate directly
            if (isRejectedByUser(clientIp, clientPort, fingerprint)) {
                logToUI(getString(R.string.connection_rejected) + "\n");
                conn.close();
                synchronized (this) { if (activeClient == conn) activeClient = null; }
                return;
            }
            conn.authenticated = true;
            logToUI("Client " + clientIp + ":" + clientPort + " authenticated (no password)\n");
            sendAuthFeedback(conn.out, 0x00000001L);
        } else {
            // Password set: need to authenticate
            sendAuthFeedback(conn.out, 0x00000000L);
        }
    }

    private void handleAuth(ClientConnection conn, String savedPassword, byte[] passwordBytes,
                             String clientIp, int clientPort, String fingerprint) throws IOException {
        String receivedPassword = new String(passwordBytes, StandardCharsets.UTF_8);
        if (!savedPassword.equals(receivedPassword)) {
            // Wrong password — do NOT trigger needConfirm to avoid harassing the user
            logToUI(getString(R.string.auth_failed) + "\n");
            sendAuthFeedback(conn.out, 0x00000000L);
            return;
        }

        // Password correct — now do needConfirm as the final gate
        if (isRejectedByUser(clientIp, clientPort, fingerprint)) {
            logToUI(getString(R.string.connection_rejected) + "\n");
            conn.close();
            synchronized (this) { if (activeClient == conn) activeClient = null; }
            return;
        }

        conn.authenticated = true;
        logToUI("Client " + clientIp + ":" + clientPort + " authenticated\n");
        sendAuthFeedback(conn.out, 0x00000001L);
    }

    private boolean isRejectedByUser(String clientIp, int clientPort, String fingerprint) {
        if (!checkBoxNeedConfirm.isChecked()) {
            return false;
        }
        confirmLatch = new CountDownLatch(1);
        confirmResult = false;

        Intent intent = new Intent(this, ReceiverNewConnectionConfirmActivity.class);
        intent.putExtra(ReceiverNewConnectionConfirmActivity.EXTRA_CLIENT_IP, clientIp);
        intent.putExtra(ReceiverNewConnectionConfirmActivity.EXTRA_CLIENT_PORT, clientPort);
        intent.putExtra(ReceiverNewConnectionConfirmActivity.EXTRA_CERT_FINGERPRINT, fingerprint);
        runOnUiThread(() -> confirmLauncher.launch(intent));

        try {
            confirmLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !confirmResult;
    }

    private void sendAuthFeedback(DataOutputStream out, long status) throws IOException {
        byte[] value = new byte[8];
        value[0] = (byte) (status >>> 56);
        value[1] = (byte) (status >>> 48);
        value[2] = (byte) (status >>> 40);
        value[3] = (byte) (status >>> 32);
        value[4] = (byte) (status >>> 24);
        value[5] = (byte) (status >>> 16);
        value[6] = (byte) (status >>> 8);
        value[7] = (byte) (status);
        TLVHelper.sendMessage(out, TLVHelper.TYPE_AUTH_FEEDBACK, value);
    }

    // ---- File transfer session handlers ----

    private void handleSessionCreate(ClientConnection conn, byte[] value) {
        try {
            if (value.length < 12) return;
            int nameLen = ((value[0] & 0xFF) << 24) | ((value[1] & 0xFF) << 16)
                    | ((value[2] & 0xFF) << 8) | (value[3] & 0xFF);
            if (4 + nameLen + 8 > value.length) return;
            String filename = new String(value, 4, nameLen, StandardCharsets.UTF_8);
            long fileSize = TLVHelper.bytesToLong(value, 4 + nameLen);

            long sessionId = new Random().nextLong() & Long.MAX_VALUE;
            // Ensure uniqueness
            while (conn.sessions.containsKey(sessionId)) sessionId = new Random().nextLong() & Long.MAX_VALUE;

            File tempDir = new File(getCacheDir(), AppClass.TRANSFER_TEMP_DIR);
            if (!tempDir.mkdirs() && !tempDir.isDirectory()) return;
            File tempFile = new File(tempDir, UUID.randomUUID().toString());
            FileOutputStream fos = new FileOutputStream(tempFile);

            TransferSession session = new TransferSession(sessionId, filename, fileSize, tempFile);
            session.fos = fos;
            conn.sessions.put(sessionId, session);

            byte[] reply = new byte[9];
            System.arraycopy(TLVHelper.longToBytes(sessionId), 0, reply, 0, 8);
            reply[8] = 0x01; // accepted
            synchronized (conn.outLock) {
                TLVHelper.sendMessage(conn.out, TLVHelper.TYPE_FILE_TRANSFER_SESSION_CREATE_RESULT, reply);
            }
        } catch (IOException e) {
            Log.e(TAG, "session create error", e);
        }
    }

    private void handleFileChunk(ClientConnection conn, byte[] value) {
        try {
            if (value.length < 8) return;
            long sessionId = TLVHelper.bytesToLong(value, 0);
            TransferSession session = conn.sessions.get(sessionId);
            if (session == null) return;

            int chunkLen = value.length - 8;
            session.fos.write(value, 8, chunkLen);
            session.bytesReceived += chunkLen;

            // Send ACK
            byte[] ack = TLVHelper.longToBytes(sessionId);
            synchronized (conn.outLock) {
                TLVHelper.sendMessage(conn.out, TLVHelper.TYPE_FILE_TRANSFER_FILE_DATA_ACCEPTED, ack);
            }
        } catch (IOException e) {
            Log.e(TAG, "file chunk error", e);
        }
    }

    private void handleSessionCommit(ClientConnection conn, byte[] value, String clientIp, int clientPort) {
        try {
            if (value.length < 8) return;
            long sessionId = TLVHelper.bytesToLong(value, 0);
            TransferSession session = conn.sessions.remove(sessionId);
            byte resultCode;

            if (session == null) {
                resultCode = 0x00;
            } else {
                session.fos.close();
                session.fos = null;

                int saveMode = saveLocationSpinner.getSelectedItemPosition();
                if (saveMode == 0) {
                    saveToDownloads(session.tempFile, session.filename);
                } else {
                    saveToInternal(session.tempFile, session.filename);
                }
                if (!session.tempFile.delete()) {
                    Log.w(TAG, "Failed to delete temp file: " + session.tempFile);
                }
                logToUI(getString(R.string.received_file, session.filename,
                        session.bytesReceived, clientIp, clientPort));
                resultCode = 0x01;
            }

            byte[] reply = new byte[9];
            System.arraycopy(TLVHelper.longToBytes(sessionId), 0, reply, 0, 8);
            reply[8] = resultCode;
            synchronized (conn.outLock) {
                TLVHelper.sendMessage(conn.out, TLVHelper.TYPE_FILE_TRANSFER_SESSION_RESULT, reply);
            }
        } catch (IOException e) {
            Log.e(TAG, "session commit error", e);
        }
    }

    private void handleSessionCancel(ClientConnection conn, byte[] value) {
        if (value.length < 8) return;
        long sessionId = TLVHelper.bytesToLong(value, 0);
        TransferSession session = conn.sessions.remove(sessionId);
        if (session != null) session.close();
    }

    private void saveToDownloads(File tempFile, String filename) throws IOException {
        File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
        File outFile = findAvailableInternalFile(downloadsDir, filename);
        try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        }
        android.media.MediaScannerConnection.scanFile(this,
                new String[]{outFile.getAbsolutePath()}, null, null);
        logToUI("Saved to Downloads: " + outFile.getName() + "\n");
    }

    private void saveToInternal(File tempFile, String filename) throws IOException {
        File dir = new File(getFilesDir(), AppClass.RECEIVED_FILES_DIR);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            Log.w(TAG, "Failed to create download directory");
            return;
        }
        File outFile = findAvailableInternalFile(dir, filename);
        try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private File findAvailableInternalFile(File dir, String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        File candidate = new File(dir, filename);
        int i = 1;
        while (candidate.exists()) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            i++;
        }
        return candidate;
    }

    private void startHeartbeat(ClientConnection conn) {
        conn.heartbeatThread = new Thread(() -> {
            while (started && !conn.socket.isClosed()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (!conn.socket.isClosed()) {
                        synchronized (conn.outLock) {
                            TLVHelper.sendHeartbeat(conn.out);
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
        conn.heartbeatThread.setDaemon(true);
        conn.heartbeatThread.start();
    }

    // ---- Server stop ----

    private void stopServer() {
        started = false;
        KeepAliveService.stop(ReceiverActivity.this);
        synchronized (this) {
            if (activeClient != null) {
                activeClient.close();
                activeClient = null;
            }
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "close server error", e);
        }
        serverSocket = null;
        runOnUiThread(this::resetServerUI);
    }

    private void resetServerUI() {
        started = false;
        buttonStart.setText(R.string.startListen);
        editTextPassword.setEnabled(true);
        checkBoxNeedConfirm.setEnabled(true);
        Toast.makeText(ReceiverActivity.this, R.string.receiverStopped, Toast.LENGTH_SHORT).show();
    }

    // ---- UI logging ----

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("notification_explained", false)) return;
        prefs.edit().putBoolean("notification_explained", true).apply();
        runOnUiThread(() -> new AlertDialog.Builder(ReceiverActivity.this)
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
            KeepAliveService.stop(ReceiverActivity.this);
            KeepAliveService.start(ReceiverActivity.this);
        }
    }

    private void logToUI(String message) {
        runOnUiThread(() -> editTextResult.append(message));
    }

    // ---- Utility ----

    private String getLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "???";
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int nightMask = newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES
                || nightMask == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            ThemeHelper.refreshNightMode(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        int p;
        try {
            p = Integer.parseInt(editTextPort.getText().toString().trim());
            if (p < 0 || p > 65535) p = 0;
        } catch (NumberFormatException e) {
            p = 0;
        }
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("receiver_need_confirm", checkBoxNeedConfirm.isChecked())
                .putInt("receiver_save_location", saveLocationSpinner.getSelectedItemPosition())
                .putInt("receiver_port", p)
                .apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}
