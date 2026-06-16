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
    // Reserved types > 4

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