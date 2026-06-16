package app.MyApp.MyClipboardTransferHelper.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class TLVHelper {
    public static final byte TYPE_HEARTBEAT = 0;
    public static final byte TYPE_AUTH_QUERY = 1;
    public static final byte TYPE_AUTH = 2;
    public static final byte TYPE_AUTH_FEEDBACK = 3;
    public static final byte TYPE_TEXT_DATA = 4;
    public static final byte TYPE_FILE_DATA_DEPRECATED = 5; // superseded by session-based types below
    // File transfer session protocol (types 6-12)
    public static final byte TYPE_FILE_TRANSFER_SESSION_CREATE = 6;
    public static final byte TYPE_FILE_TRANSFER_SESSION_CREATE_RESULT = 7;
    public static final byte TYPE_FILE_TRANSFER_FILE_DATA = 8;
    public static final byte TYPE_FILE_TRANSFER_FILE_DATA_ACCEPTED = 9;
    public static final byte TYPE_FILE_TRANSFER_SESSION_COMMIT = 10;
    public static final byte TYPE_FILE_TRANSFER_SESSION_CANCEL = 11;
    public static final byte TYPE_FILE_TRANSFER_SESSION_RESULT = 12;
    // Reserved types > 12

    public static final int FILE_CHUNK_SIZE = 5000000; // 5MB (~4.76MiB; use non-binary-round number so that the progress bar seems to be walking)

    public static void sendMessage(DataOutputStream out, byte type, byte[] value) throws IOException {
        out.writeByte(type);
        if (value == null) {
            out.writeInt(0);
        } else {
            out.writeInt(value.length);
            out.write(value);
        }
        out.flush();
    }

    public static void sendHeartbeat(DataOutputStream out) throws IOException {
        sendMessage(out, TYPE_HEARTBEAT, null);
    }

    public static TLVMessage readMessage(DataInputStream in) throws IOException {
        byte type = in.readByte();
        int length = in.readInt();
        byte[] value = new byte[length];
        in.readFully(value);
        return new TLVMessage(type, length, value);
    }

    public static byte[] longToBytes(long v) {
        return new byte[]{
                (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
        };
    }

    public static long bytesToLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFF);
        }
        return value;
    }

    public static class TLVMessage {
        public byte type;
        public int length;
        public byte[] value;

        public TLVMessage(byte type, int length, byte[] value) {
            this.type = type;
            this.length = length;
            this.value = value;
        }
    }
}
