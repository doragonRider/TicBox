package dev.ticbox.companion.toolboxagent;

import java.io.IOException;

/** Fixed JNI entry points sharing the verified PTY asset and loader. No media keys cross JNI. */
final class ScreenNative {
    private ScreenNative() { }
    static native long open(int fd) throws IOException;
    /** Null statistics sends without sampling diagnostic clocks. */
    static native int sendBatch(long handle, byte[] packets, int[] lengths, int count,
        byte[] address, int scope, int port, long[] statistics) throws IOException;
    static native void close(long handle);
    static native int fecMode();
    static native long threadCpuNanos();
    static native void encodeParity(byte[] data, int count, int length, byte[] parity, boolean simd);
}
