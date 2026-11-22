package android.media;

import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Host-only Android API double; never included in the shipped server. */
public class MediaCodec {
    public static final String PARAMETER_KEY_REQUEST_SYNC_FRAME = "request-sync";
    public static final String PARAMETER_KEY_VIDEO_BITRATE = "video-bitrate";
    public final MediaCodecInfo info = new MediaCodecInfo();
    public final MediaFormat input = new MediaFormat();
    public final MediaFormat output = new MediaFormat();
    public String name = "c2.fixture.hevc.encoder";
    public RuntimeException formatRejection;
    public String getName() { return name; }
    public MediaFormat getInputFormat() { if (formatRejection != null) { throw formatRejection; } return input; }
    public MediaFormat getOutputFormat() { if (formatRejection != null) { throw formatRejection; } return output; }
    public int infoReads;
    public MediaCodecInfo getCodecInfo() { infoReads++; return info; }
    public int requests;
    public int eos;
    public RuntimeException rejection;
    public CountDownLatch entered;
    public CountDownLatch release;
    public final java.util.List<Integer> bitRates = new java.util.ArrayList<>();
    public void setParameters(Bundle parameters) {
        if (parameters.size() == 1 && parameters.containsKey(PARAMETER_KEY_VIDEO_BITRATE)) {
            if (rejection != null) throw rejection;
            bitRates.add(parameters.getInt(PARAMETER_KEY_VIDEO_BITRATE));
            return;
        }
        if (parameters.size() != 1 || parameters.getInt(PARAMETER_KEY_REQUEST_SYNC_FRAME) != 0) {
            throw new AssertionError("Unexpected dynamic encoder parameters");
        }
        requests++;
        if (entered != null) {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Request release timed out");
            } catch (InterruptedException error) { throw new AssertionError(error); }
        }
        if (rejection != null) throw rejection;
    }
    public void signalEndOfInputStream() { eos++; }
    public final java.util.List<String> vendorParameters = new java.util.ArrayList<>();
    public final java.util.Map<String, Integer> vendorTypes = new java.util.HashMap<>();
    public java.util.List<String> getSupportedVendorParameters() { return vendorParameters; }
    public ParameterDescriptor getParameterDescriptor(String name) {
        Integer type = vendorTypes.get(name);
        return type == null ? null : new ParameterDescriptor(name, type);
    }
    public static final class ParameterDescriptor {
        private final String name;
        private final int type;
        ParameterDescriptor(String name, int type) { this.name = name; this.type = type; }
        public String getName() { return name; }
        public int getType() { return type; }
    }
}
