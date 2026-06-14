package app.MyApp.MyClipboardTransferHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import top.clspd.apps.MyClipboardTransferHelper.R;

public class ReceiverActivity extends AppCompatActivity {

    private boolean started = false;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private EditText editTextResult;
    private Button buttonStart;
    private int PORT = 0;
    private volatile Socket activeClient; // 当前唯一活跃的客户端

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
        buttonStart = findViewById(R.id.buttonStart);
    }

    public void startOrStop(View view) {
        if (started) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0);
                PORT = serverSocket.getLocalPort();
                started = true;
                runOnUiThread(() -> {
                    buttonStart.setText(R.string.stopListen);
                    editTextResult.setText("");
                    String message = getString(R.string.receiverStarted, getLocalIp() + ":" + PORT);
                    editTextResult.setText(message);
                });

                while (started && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        // 同一时间只允许一个客户端
                        synchronized (this) {
                            if (activeClient != null) {
                                client.close(); // 拒绝新连接
                                continue;
                            }
                            activeClient = client;
                        }
                        handleClient(client);
                    } catch (IOException e) {
                        if (started) {
                            Log.e("ReceiverActivity", "accept error", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e("ReceiverActivity", "server error", e);
                runOnUiThread(() -> {
                    editTextResult.setText(getString(R.string.startFailed, e.getMessage()));
                    started = false;
                    buttonStart.setText(R.string.startListen);
                });
            }
        });
        serverThread.start();
    }

    private void handleClient(Socket client) {
        new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(client.getInputStream());
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (IOException e) {
                        break; // 连接断开
                    }
                    byte[] data = new byte[length];
                    in.readFully(data);
                    String text = new String(data, StandardCharsets.UTF_8);

                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("received", text);
                    runOnUiThread(() -> {
                        clipboard.setPrimaryClip(clip);
                        editTextResult.append(getString(R.string.receiverReceived, length));
                    });
                }
            } catch (IOException e) {
                Log.e("ReceiverActivity", "handleClient error", e);
            } finally {
                try { client.close(); } catch (IOException ignored) {}
                synchronized (this) {
                    if (activeClient == client) {
                        activeClient = null;
                    }
                }
            }
        }).start();
    }

    private void stopServer() {
        started = false;
        // 关闭当前客户端
        synchronized (this) {
            if (activeClient != null) {
                try { activeClient.close(); } catch (IOException ignored) {}
                activeClient = null;
            }
        }
        // 关闭服务端
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e("ReceiverActivity", "close server error", e);
        }
        serverSocket = null;

        runOnUiThread(() -> {
            buttonStart.setText(R.string.startListen);
            Toast.makeText(ReceiverActivity.this, R.string.receiverStopped, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
}