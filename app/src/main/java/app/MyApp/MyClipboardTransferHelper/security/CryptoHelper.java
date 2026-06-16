package app.MyApp.MyClipboardTransferHelper.security;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class CryptoHelper {
    private static final String EC_CURVE = "secp384r1";
    private static final String CERT_ALIAS = "clipboard";
    private static final String SIG_ALG_OID = "1.2.840.10045.4.3.2"; // ecdsa-with-SHA256
    private static final String CN_OID = "2.5.4.3";

    public static KeyPair generateECKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(EC_CURVE));
        return kpg.generateKeyPair();
    }

    public static X509Certificate generateSelfSignedCertificate(KeyPair keyPair, String dn) throws Exception {
        byte[] der = generateSelfSignedCertDer(keyPair, dn);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] generateSelfSignedCertDer(KeyPair keyPair, String dn) throws Exception {
        String cn = extractCN(dn);
        long notBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        long notAfter = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000;
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        byte[] pubKeyEncoded = keyPair.getPublic().getEncoded();

        // Build TBSCertificate
        byte[] version = encodeExplicitTag(0, encodeInteger(BigInteger.valueOf(2)));
        byte[] serialNum = encodeInteger(serial);
        byte[] sigAlgId = encodeAlgorithmIdentifier(SIG_ALG_OID);
        byte[] issuer = encodeX500Name(cn);
        byte[] validity = encodeSequence(encodeUTCTime(notBefore), encodeUTCTime(notAfter));
        byte[] subject = encodeX500Name(cn);
        // pubKeyEncoded is already SEQUENCE { algId, BIT STRING key }
        // We need to preserve it as-is inside the TBSCertificate

        byte[] tbsNoSig = concat(version, serialNum, sigAlgId, issuer, validity, subject, pubKeyEncoded);
        byte[] tbs = encodeSequenceRaw(tbsNoSig);

        // Sign
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbs);
        byte[] sigValue = sig.sign();

        // Build Certificate
        byte[] sigAlg = encodeAlgorithmIdentifier(SIG_ALG_OID);
        byte[] sigBits = encodeBitString(sigValue);
        return encodeSequenceRaw(concat(tbs, sigAlg, sigBits));
    }

    private static String extractCN(String dn) {
        String prefix = "CN=";
        int idx = dn.indexOf(prefix);
        if (idx >= 0) {
            String cn = dn.substring(idx + prefix.length());
            int comma = cn.indexOf(',');
            return comma >= 0 ? cn.substring(0, comma).trim() : cn.trim();
        }
        return dn;
    }

    // ---- ASN.1 DER encoding helpers ----

    private static byte[] encodeLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        byte[] lenBytes = new byte[]{
                (byte) (length >>> 24),
                (byte) (length >>> 16),
                (byte) (length >>> 8),
                (byte) length
        };
        int firstNonZero = 0;
        while (firstNonZero < 3 && lenBytes[firstNonZero] == 0) firstNonZero++;
        int count = 4 - firstNonZero;
        byte[] result = new byte[1 + count];
        result[0] = (byte) (0x80 | count);
        System.arraycopy(lenBytes, firstNonZero, result, 1, count);
        return result;
    }

    private static byte[] encodeTag(byte tag, byte[] content) {
        return concat(new byte[]{tag}, encodeLength(content.length), content);
    }

    private static byte[] encodeInteger(BigInteger val) {
        byte[] bytes = val.toByteArray();
        int strip = 0;
        // Keep at least one byte, and if value fits in 7 bits positive, keep leading 0
        while (strip < bytes.length - 1 && bytes[strip] == 0
                && (bytes[strip + 1] & 0x80) == 0) {
            strip++;
        }
        byte[] trimmed = new byte[bytes.length - strip];
        System.arraycopy(bytes, strip, trimmed, 0, trimmed.length);
        return encodeTag((byte) 0x02, trimmed);
    }

    private static byte[] encodeOID(String oid) {
        String[] parts = oid.split("\\.");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = Integer.parseInt(parts[i]);

        // First two components encoded as 40*X + Y
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(40 * values[0] + values[1]);

        for (int i = 2; i < values.length; i++) {
            int v = values[i];
            if (v < 0x80) {
                bos.write(v);
            } else {
                byte[] enc = encodeBase128(v);
                bos.write(enc, 0, enc.length);
            }
        }
        return encodeTag((byte) 0x06, bos.toByteArray());
    }

    private static byte[] encodeBase128(int value) {
        int bits = 0;
        int tmp = value;
        while (tmp > 0) { bits++; tmp >>= 7; }
        byte[] result = new byte[bits];
        for (int i = bits - 1; i >= 0; i--) {
            result[i] = (byte) (value & 0x7F);
            if (i < bits - 1) result[i] |= 0x80;
            value >>= 7;
        }
        return result;
    }

    private static byte[] encodeBitString(byte[] data) {
        byte[] content = new byte[data.length + 1];
        content[0] = 0x00; // unused bits
        System.arraycopy(data, 0, content, 1, data.length);
        return encodeTag((byte) 0x03, content);
    }

    private static byte[] encodeUTCTime(long millis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return encodeTag((byte) 0x17, sdf.format(new Date(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] encodeAlgorithmIdentifier(String oid) {
        return encodeSequence(encodeOID(oid));
    }

    private static byte[] encodeX500Name(String cn) {
        // SEQUENCE of SET of SEQUENCE { OID, value }
        byte[] oidCN = encodeOID(CN_OID);
        byte[] valueCN = encodeTag((byte) 0x0C, cn.getBytes(StandardCharsets.UTF_8)); // UTF8String
        byte[] attrTypeAndValue = encodeSequence(concat(oidCN, valueCN));
        byte[] set = encodeSet(attrTypeAndValue);
        return encodeSequence(set);
    }

    private static byte[] encodeSequence(byte[]... parts) {
        return encodeTag((byte) 0x30, concatAll(parts));
    }

    private static byte[] encodeSequenceRaw(byte[] content) {
        return encodeTag((byte) 0x30, content);
    }

    private static byte[] encodeSet(byte[]... parts) {
        return encodeTag((byte) 0x31, concatAll(parts));
    }

    private static byte[] encodeExplicitTag(int tagNum, byte[] content) {
        // Context-specific, constructed tag: 0xA0 | tagNum
        byte tag = (byte) (0xA0 | tagNum);
        return concat(new byte[]{tag}, encodeLength(content.length), content);
    }

    // ---- Utility ----

    private static byte[] concat(byte[]... arrays) {
        return concatAll(arrays);
    }

    private static byte[] concatAll(byte[][] arrays) {
        int totalLen = 0;
        for (byte[] a : arrays) totalLen += a.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    // ---- Certificate fingerprint and loading ----

    public static String getCertificateSha1Fingerprint(X509Certificate cert) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static X509Certificate loadCertificateFromFile(File certFile) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (FileInputStream fis = new FileInputStream(certFile)) {
            return (X509Certificate) cf.generateCertificate(fis);
        }
    }

    // ---- SSL Context creation ----

    public static SSLContext createServerSSLContext(File certFile, File keyFile) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certFile)) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        byte[] keyBytes = new byte[(int) keyFile.length()];
        try (FileInputStream fis = new FileInputStream(keyFile)) { fis.read(keyBytes); }
        PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(CERT_ALIAS, privateKey, new char[0], new X509Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        // Trust all clients — client auth is done at application layer via password
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        context.init(kmf.getKeyManagers(), trustAll, null);
        return context;
    }

    public static SSLContext createClientSSLContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        context.init(null, trustAll, null);
        return context;
    }

    // ---- Known hosts management ----

    public static Map<String, String> loadKnownHosts(File file) {
        Map<String, String> hosts = new LinkedHashMap<>();
        if (!file.exists()) return hosts;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 2);
                if (parts.length == 2) {
                    hosts.put(parts[0], parts[1]);
                }
            }
        } catch (Exception ignored) {}
        return hosts;
    }

    public static void saveKnownHosts(File file, Map<String, String> hosts) {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> entry : hosts.entrySet()) {
                writer.write(entry.getKey() + " " + entry.getValue());
                writer.newLine();
            }
        } catch (Exception ignored) {}
    }

    public enum KnownHostStatus {
        KNOWN_MATCH,
        KNOWN_MISMATCH,
        UNKNOWN
    }

    public static KnownHostStatus checkKnownHost(Map<String, String> knownHosts, String ip, String fingerprint) {
        String existing = knownHosts.get(ip);
        if (existing == null) return KnownHostStatus.UNKNOWN;
        return existing.equalsIgnoreCase(fingerprint) ? KnownHostStatus.KNOWN_MATCH : KnownHostStatus.KNOWN_MISMATCH;
    }
}
