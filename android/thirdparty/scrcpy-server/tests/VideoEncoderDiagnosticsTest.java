import android.media.MediaCodec;
import android.media.MediaFormat;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.video.VideoEncoderDiagnostics;

/** Exercises the shipped metadata projection with explicit requested/read-back differences. */
public final class VideoEncoderDiagnosticsTest {
    public static void main(String[] args) {
        check(!Options.parse(new String[] {"4.1"}).getVideoDiagnostics(), "Diagnostics enabled by default");
        check(Options.parse(new String[] {"4.1", "video_diagnostics=true"}).getVideoDiagnostics(), "Opt-in not parsed");
        MediaCodec codec = new MediaCodec();
        codec.info.encoder.cbr = true;
        MediaFormat requested = new MediaFormat();
        requested.setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000);
        requested.setInteger(MediaFormat.KEY_BITRATE_MODE, 2);
        requested.setInteger(MediaFormat.KEY_FRAME_RATE, 120);
        requested.setInteger("width", 1152); requested.setInteger("height", 2560);
        codec.input.setInteger(MediaFormat.KEY_BITRATE_MODE, 1);
        codec.input.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        codec.input.setInteger("private", 123456789);
        codec.output.setFloat(MediaFormat.KEY_FRAME_RATE, 59.94f);
        codec.output.setInteger("latency", 3);
        codec.output.setInteger("output-reorder-depth", 2);
        codec.input.setInteger("max-bframes", 2);
        String value = VideoEncoderDiagnostics.snapshot(codec, requested, "video/hevc", true, "output_format", 34);
        check(value.contains("\"configuredBitrateMode\":2") && value.contains("\"inputBitrateMode\":1"), "Requested/accepted modes conflated");
        check(value.contains("\"configuredFps\":120") && value.contains("\"inputFps\":60"), "Requested/accepted fps conflated");
        check(value.contains("\"outputFps\":59.9") && value.contains("\"outputLatency\":3"), "Float fps/output data lost");
        check(value.contains("\"cbrSupported\":true") && value.contains("\"hardware\":true"), "Capabilities missing");
        check(!value.contains("private") && !value.contains("123456789"), "Raw format leaked");
        check(value.contains("\"outputReorderDepth\":2") && value.contains("\"inputMaxBFrames\":2"), "Reorder metadata missing");
        check(value.contains("\"bitrateMax\":40000000") && value.contains("\"fpsMax\":60.0") && value.contains("\"sizeRateSupported\":false"), "Declared codec limits lost");
        check(value.length() < 2048, "Unbounded metadata");
        codec.name = "bad\"/private/path";
        codec.info.rejection = new IllegalArgumentException("private data");
        codec.formatRejection = new IllegalStateException("private data");
        value = VideoEncoderDiagnostics.snapshot(codec, requested, "video/hevc", true, "configured", 28);
        check(!value.contains("\"encoder\":") && !value.contains("cbrSupported") && !value.contains("hardware"), "Unavailable capability guessed");
        check(!value.contains("inputBitrate") && !value.contains("private") && !value.contains("output"), "Unavailable/unsafe fields leaked");
        check(codec.requests == 0 && codec.eos == 0, "Diagnostics changed the codec");
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        try {
            com.genymobile.scrcpy.util.Ln.disableSystemStreams();
            VideoEncoderDiagnostics.emit(codec, requested, "video/hevc", true, "configured", 28);
        } finally { System.setOut(originalOut); System.setErr(originalErr); }
        vendorParameters();
        System.out.println("scrcpy metadata: opt-in, configured/input/output separation, fractional rate, optional capabilities, privacy and vendor parameter names passed");
    }
    private static void vendorParameters() {
        MediaCodec codec = new MediaCodec();
        codec.name = "c2.mtk.hevc.encoder";
        String[] names = {"vendor.mtk.ext.venc.tag", "vendor.mtk.ext.venc.qpvbr.upper-enable", "vendor.mtk.ext.venc.unknown",
            "vendor.mtk.ext.venc.qpvbr.lower-enable", "vendor.mtk.ext.venc.bad name", "vendor.mtk.ext.venc.lowlatency", "vendor.mtk.ext.venc.hq-mode"};
        java.util.Collections.addAll(codec.vendorParameters, names);
        for (String name : names) { codec.vendorTypes.put(name, 1); }
        codec.vendorTypes.put("vendor.mtk.ext.venc.tag", 4);
        codec.vendorTypes.remove("vendor.mtk.ext.venc.unknown");
        check(VideoEncoderDiagnostics.vendorParameters(codec, true, 30) == null, "Asked an encoder before Android 12");
        String value = VideoEncoderDiagnostics.vendorParameters(codec, true, 33);
        check(value.contains("\"prefix\":\"vendor.mtk.ext.venc.\""), "Shared prefix not taken off: " + value);
        check(value.contains("\"names\":[\"hq-mode:i\",\"lowlatency:i\",\"qpvbr.lower-enable:i\",\"qpvbr.upper-enable:i\",\"tag:s\",\"unknown:?\"]"),
                "Rate control names not first, or types lost: " + value);
        check(value.contains("\"count\":6,\"omitted\":0") && !value.contains("bad name"), "Unsafe name kept");
        check(value.contains("\"encoder\":\"c2.mtk.hevc.encoder\"") && value.startsWith("{\"kind\":\"screen_encoder\""), "Record malformed");
        MediaCodec many = new MediaCodec();
        for (int i = 0; i < 300; i++) { many.vendorParameters.add("vendor.example.parameter-with-a-rather-long-name." + i); }
        many.vendorParameters.add("vendor.other.qp-max");
        String crowded = VideoEncoderDiagnostics.vendorParameters(many, false, 34);
        check(crowded.length() < 2048 && crowded.contains("\"count\":301") && !crowded.contains("\"omitted\":0"), "Unbounded names: " + crowded.length());
        check(crowded.contains("\"prefix\":\"vendor.\",\"names\":[\"other.qp-max:?\""), "Rate control name crowded out");
        MediaCodec one = new MediaCodec();
        one.vendorParameters.add("vendor.qti-ext-enc-low-latency.enable");
        check(VideoEncoderDiagnostics.vendorParameters(one, false, 31).contains("\"prefix\":\"vendor.qti-ext-enc-low-latency.\",\"names\":[\"enable:?\"]"), "Single name");
        MediaCodec none = new MediaCodec();
        check(VideoEncoderDiagnostics.vendorParameters(none, false, 31).contains("\"prefix\":\"\",\"names\":[],\"count\":0,\"omitted\":0"), "Empty list");
    }

    private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
