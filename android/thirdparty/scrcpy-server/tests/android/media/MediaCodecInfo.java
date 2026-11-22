package android.media;

/** Host-only capability declarations; excluded from the shipped server. */
public final class MediaCodecInfo {
    public boolean isHardwareAccelerated() { return true; }
    public String requestedMime;
    public RuntimeException rejection;
    public EncoderCapabilities encoder = new EncoderCapabilities();
    public VideoCapabilities video = new VideoCapabilities();
    public CodecCapabilities getCapabilitiesForType(String mime) {
        requestedMime = mime;
        if (rejection != null) { throw rejection; }
        return new CodecCapabilities(encoder, video);
    }
    public static final class CodecCapabilities {
        private final EncoderCapabilities encoder;
        private final VideoCapabilities video;
        CodecCapabilities(EncoderCapabilities value, VideoCapabilities video) { encoder = value; this.video = video; }
        public VideoCapabilities getVideoCapabilities() { return video; }
        public EncoderCapabilities getEncoderCapabilities() { return encoder; }
    }
    public static final class VideoCapabilities {
        public android.util.Range<Integer> bitrate = new android.util.Range<>(64000, 40_000_000);
        public android.util.Range<Double> fps = new android.util.Range<>(1.0, 60.0);
        public boolean size = true;
        public android.util.Range<Integer> getBitrateRange() { return bitrate; }
        public boolean isSizeSupported(int width, int height) { return size; }
        public boolean areSizeAndRateSupported(int width, int height, double rate) { return size && rate >= fps.getLower() && rate <= fps.getUpper(); }
        public android.util.Range<Double> getSupportedFrameRatesFor(int width, int height) { return fps; }
    }
    public static final class EncoderCapabilities {
        public static final int BITRATE_MODE_CBR = 2;
        public boolean cbr;
        public boolean isBitrateModeSupported(int mode) {
            if (mode != BITRATE_MODE_CBR) { throw new AssertionError("Unexpected bitrate mode probe"); }
            return cbr;
        }
    }
}
