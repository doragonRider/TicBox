package dev.ticbox.companion.toolboxagent;

import java.io.IOException;

/** Fixed JNI entry points; only PtyProcess owns handles. Terminal bytes never enter logs. */
final class PtyNative {
    private PtyNative() { }
    static native long start(String shell, String term, int rows, int columns, int widthPixels, int heightPixels) throws IOException;
    static native int read(long handle, byte[] bytes, int timeoutMillis) throws IOException;
    static native void write(long handle, byte[] bytes) throws IOException;
    static native void resize(long handle, int rows, int columns, int widthPixels, int heightPixels) throws IOException;
    static native int awaitExit(long handle) throws IOException;
    static native void close(long handle);
}
