package dev.ticbox.companion.toolboxagent;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

public final class ScreenNativeTest {
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        long cpu = ScreenNative.threadCpuNanos();
        if (cpu < 0 || ScreenNative.threadCpuNanos() < cpu) throw new AssertionError("CPU clock unavailable");
        Random random = new Random(19);
        for (int k = 1; k <= 16; k++) for (int length : new int[] {0, 1, 7, 8, 15, 16, 17, 32, 1151, 1152}) {
            byte[] input = new byte[k * length]; random.nextBytes(input);
            byte[] scalar = new byte[((k + 3) / 4) * length + 1], simd = scalar.clone();
            scalar[scalar.length - 1] = simd[simd.length - 1] = 67;
            ScreenNative.encodeParity(input, k, length, scalar, false);
            ScreenNative.encodeParity(input, k, length, simd, true);
            if (!Arrays.equals(scalar, simd) || simd[simd.length - 1] != 67) throw new AssertionError("SIMD/tail mismatch k=" + k + " length=" + length);
        }
        byte[] input = new byte[16 * 32], parity = new byte[4 * 32];
        for (int r = 0; r < 16; r++) for (int c = 0; c < 32; c++) input[r * 32 + c] = (byte)(r * 17 + c * 31);
        ScreenNative.encodeParity(input, 16, 32, parity, true);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parity));
        if (!sha.equals("da153c3faf520b85e63c648da533618530ff813de8a9e1b794dc3a9c7bedf811")) throw new AssertionError(sha);
        try { ScreenNative.encodeParity(input, 17, 32, parity, true); throw new AssertionError("invalid block accepted"); } catch (IllegalArgumentException expected) { }
        try { ScreenNative.encodeParity(new byte[0], 16, 32, parity, true); throw new AssertionError("short block accepted"); } catch (IllegalArgumentException expected) { }
        try { ScreenNative.sendBatch(0, new byte[25200], new int[21], 21, new byte[4], 0, 123, new long[7]); throw new AssertionError("large batch accepted"); } catch (IllegalArgumentException expected) { }
        try { ScreenNative.sendBatch(0, new byte[9600], new int[8], 9, new byte[4], 0, 123, new long[7]); throw new AssertionError("short packets accepted"); } catch (IllegalArgumentException expected) { }
        try { ScreenNative.sendBatch(0, new byte[9600], new int[8], 8, new byte[4], 0, 123, new long[6]); throw new AssertionError("short statistics accepted"); } catch (IllegalArgumentException expected) { }
        ScreenNative.close(0); ScreenNative.close(Long.MAX_VALUE);
        jniSendAndTiming();
        System.out.println("Screen JNI parity/canonical vectors/bounds passed; SIMD mode=" + ScreenNative.fecMode());
    }

    private static void jniSendAndTiming() throws Exception {
        // Test-host-only access to the JDK socket fd; Android uses ParcelFileDescriptor instead.
        try (DatagramChannel tx = DatagramChannel.open(StandardProtocolFamily.INET);
             DatagramChannel rx = DatagramChannel.open(StandardProtocolFamily.INET)) {
            tx.bind(new InetSocketAddress("127.0.0.1", 0)); rx.bind(new InetSocketAddress("127.0.0.1", 0)); rx.configureBlocking(false);
            var fd = tx.getClass().getDeclaredField("fdVal"); fd.setAccessible(true);
            long handle = ScreenNative.open(fd.getInt(tx));
            try {
                // A whole FEC block in one call.
                byte[] packets = new byte[24000]; int[] lengths = new int[20]; Arrays.fill(lengths, 1200);
                for (int i = 0; i < packets.length; i++) packets[i] = (byte)(i * 31);
                for (boolean measure : new boolean[] {true, false}) {
                long[] stats = measure ? new long[7] : null;
                int calls = ScreenNative.sendBatch(handle, packets, lengths, 20, new byte[] {127,0,0,1}, 0,
                    ((InetSocketAddress)rx.getLocalAddress()).getPort(), stats);
                if (calls != 1 || (measure && (stats[4] != 1 || stats[5] != 20 || stats[6] != 0))) throw new AssertionError("JNI GSO stats");
                if (measure) for (int i = 0; i < 4; i++) if (stats[i] < 0) throw new AssertionError("JNI phase clock");
                for (int i = 0; i < 20; i++) {
                    ByteBuffer packet = ByteBuffer.allocate(1201); long deadline = System.nanoTime() + 1_000_000_000L;
                    while (rx.receive(packet) == null) { if (System.nanoTime() >= deadline) throw new AssertionError("JNI receive timeout"); Thread.sleep(1); }
                    if (packet.position() != lengths[i] || !Arrays.equals(Arrays.copyOf(packet.array(), 1200), Arrays.copyOfRange(packets, i * 1200, (i + 1) * 1200))) throw new AssertionError("JNI packet mismatch");
                }
                }
            } finally { ScreenNative.close(handle); }
            tx.send(ByteBuffer.wrap(new byte[] {42}), rx.getLocalAddress()); // original fd remains usable
        }
    }
}
