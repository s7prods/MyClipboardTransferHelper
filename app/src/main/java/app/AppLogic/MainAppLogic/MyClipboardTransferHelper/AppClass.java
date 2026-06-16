package app.AppLogic.MainAppLogic.MyClipboardTransferHelper;

import android.app.Application;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import app.MyApp.MyClipboardTransferHelper.security.CryptoHelper;

public class AppClass extends Application {
    public static final String RECEIVER_DIR = "receiver";
    public static final String SENDER_DIR = "sender";
    public static final String CERT_FILE = "cert.cer";
    public static final String KEY_FILE = "cert.key";
    public static final String PASSWORD_FILE = "user_password.txt";
    public static final String KNOWN_HOSTS_FILE = "known_hosts.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        initDirectories();
        generateCertificateIfNeeded();
    }

    private void initDirectories() {
        new File(getFilesDir(), RECEIVER_DIR).mkdirs();
        new File(getFilesDir(), SENDER_DIR).mkdirs();
    }

    private void generateCertificateIfNeeded() {
        File certFile = new File(getFilesDir(), RECEIVER_DIR + File.separator + CERT_FILE);
        File keyFile = new File(getFilesDir(), RECEIVER_DIR + File.separator + KEY_FILE);
        if (certFile.exists() && keyFile.exists()) return;
        try {
            KeyPair keyPair = CryptoHelper.generateECKeyPair();
            X509Certificate cert = CryptoHelper.generateSelfSignedCertificate(keyPair, "CN=ClipboardTransfer");
            // 保存证书
            try (FileOutputStream fos = new FileOutputStream(certFile)) {
                fos.write(cert.getEncoded());
            }
            // 保存私钥（PKCS8格式）
            try (FileOutputStream fos = new FileOutputStream(keyFile)) {
                fos.write(keyPair.getPrivate().getEncoded());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate certificate", e);
        }
    }
}