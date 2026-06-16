package app.MyApp.MyClipboardTransferHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import app.AppLogic.MainAppLogic.MyClipboardTransferHelper.AppClass;
import app.MyApp.MyClipboardTransferHelper.protocol.TLVHelper;
import app.MyApp.MyClipboardTransferHelper.security.CryptoHelper;
import app.MyApp.MyClipboardTransferHelper.security.ReceiverNewConnectionConfirmActivity;
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

        editTextResult = findViewById(R.id.editTextText);
        editTextPassword = findViewById(R.id.editTextText2);
        checkBoxNeedConfirm = findViewById(R.id.checkBox);
        buttonStart = findViewById(R.id.buttonStart);

        // Load saved password
        passwordFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.PASSWORD_FILE);
        if (passwordFile.exists()) {
            try {
                byte[] pwdBytes = new byte[(int) passwordFile.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(passwordFile)) {
                    fis.read(pwdBytes);
                }
                editTextPassword.setText(new String(pwdBytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "failed to load password", e);
            }
        }

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

    private static class ClientConnection {
        SSLSocket socket;
        DataInputStream in;
        DataOutputStream out;
        boolean authenticated;
        Thread readThread;
        Thread heartbeatThread;

        ClientConnection(SSLSocket socket, DataInputStream in, DataOutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.authenticated = false;
        }

        void close() {
            if (heartbeatThread != null) heartbeatThread.interrupt();
            if (readThread != null) readThread.interrupt();
            // TLS close_notify requires network I/O — must be off main thread
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
            new File(getFilesDir(), AppClass.RECEIVER_DIR).mkdirs();
            try (FileOutputStream fos = new FileOutputStream(passwordFile)) {
                fos.write(password.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "failed to save password", e);
        }

        serverThread = new Thread(() -> {
            try {
                File certFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.CERT_FILE);
                File keyFile = new File(getFilesDir(), AppClass.RECEIVER_DIR + File.separator + AppClass.KEY_FILE);
                SSLContext sslContext = CryptoHelper.createServerSSLContext(certFile, keyFile);
                serverSocket = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(0);
                port = serverSocket.getLocalPort();
                started = true;

                runOnUiThread(() -> {
                    buttonStart.setText(R.string.stopListen);
                    editTextResult.setText("");
                    String message = getString(R.string.receiverStarted, getLocalIp() + ":" + port);
                    editTextResult.setText(message);
                    editTextPassword.setEnabled(false);
                    checkBoxNeedConfirm.setEnabled(false);
                });

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
                String serverFingerprint = CryptoHelper.getCertificateSha1Fingerprint(serverCert);

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
            if (!checkNeedConfirm(clientIp, clientPort, fingerprint)) {
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
        if (!checkNeedConfirm(clientIp, clientPort, fingerprint)) {
            logToUI(getString(R.string.connection_rejected) + "\n");
            conn.close();
            synchronized (this) { if (activeClient == conn) activeClient = null; }
            return;
        }

        conn.authenticated = true;
        logToUI("Client " + clientIp + ":" + clientPort + " authenticated\n");
        sendAuthFeedback(conn.out, 0x00000001L);
    }

    private boolean checkNeedConfirm(String clientIp, int clientPort, String fingerprint) {
        if (!checkBoxNeedConfirm.isChecked()) {
            return true;
        }
        // Launch confirm activity and wait for result
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
        return confirmResult;
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

    private void startHeartbeat(ClientConnection conn) {
        conn.heartbeatThread = new Thread(() -> {
            while (started && !conn.socket.isClosed()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    if (!conn.socket.isClosed()) {
                        synchronized (conn.out) {
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
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}
