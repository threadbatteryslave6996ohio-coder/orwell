package dev.clippy.clients.filelocker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class FileLockerProtocol {
    static final int PING = 0;
    static final int READ = 1;
    static final int APPEND = 2;
    static final int CLEAR_IF_UNCHANGED = 3;
    static final int OK = 0;
    static final int ERROR = 1;
    private static final int MAX_MESSAGE_BYTES = 256 * 1024 * 1024;

    private FileLockerProtocol() {
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MESSAGE_BYTES) {
            throw new IOException("File-locker IPC message exceeds " + MAX_MESSAGE_BYTES + " bytes.");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Invalid file-locker IPC message length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Incomplete file-locker IPC message.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
