import android.media.MediaCodec;
import android.media.MediaFormat;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.video.VideoRateControl;

/** The production policy against capability/format doubles; no real hardware bitrate claim. */
public final class VideoRateControlTest {
    public static void main(String[] arguments) {
        check(!Options.parse(new String[] {"4.1"}).getVideoPreferCbr(), "Ordinary sessions changed");
        check(Options.parse(new String[] {"4.1", "video_prefer_cbr=true"}).getVideoPreferCbr(), "UDP option not parsed");
        for (String mime : new String[] {"video/avc", "video/hevc"}) {
            MediaCodec codec = new MediaCodec();
            codec.info.encoder.cbr = true;
            MediaFormat format = new MediaFormat();
            format.setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000);
            check(!VideoRateControl.preferConstantBitrate(format, codec, mime, false), "Disabled policy applied");
            check(codec.infoReads == 0 && !format.containsKey(MediaFormat.KEY_BITRATE_MODE), "Reliable path probed/mutated");
            check(VideoRateControl.preferConstantBitrate(format, codec, mime, true), "Supported CBR not selected");
            check(format.getInteger(MediaFormat.KEY_BITRATE_MODE) == 2, "Wrong control mode");
            check(format.getInteger(MediaFormat.KEY_BIT_RATE) == 20_000_000, "Requested bitrate changed");
            check(mime.equals(codec.info.requestedMime), "Wrong selected encoder MIME");
            check(codec.requests == 0 && codec.eos == 0, "Policy reset or changed a running codec");

            format.setInteger(MediaFormat.KEY_BITRATE_MODE, 1);
            int reads = codec.infoReads;
            check(!VideoRateControl.preferConstantBitrate(format, codec, mime, true), "Explicit mode overridden");
            check(format.getInteger(MediaFormat.KEY_BITRATE_MODE) == 1 && codec.infoReads == reads, "Explicit mode mutated/probed");

            for (int state = 0; state < 4; state++) {
                MediaCodec unsupported = new MediaCodec();
                if (state == 1) { unsupported.info.encoder = null; }
                if (state == 2) { unsupported.info.rejection = new IllegalArgumentException("unsupported MIME fixture"); }
                if (state == 3) { unsupported.info.rejection = new IllegalStateException("unavailable capability fixture"); }
                MediaFormat original = new MediaFormat();
                original.setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000);
                check(!VideoRateControl.preferConstantBitrate(original, unsupported, mime, true), "Unknown CBR support guessed");
                check(!original.containsKey(MediaFormat.KEY_BITRATE_MODE), "Unsupported codec was forced to CBR");
                check(original.getInteger(MediaFormat.KEY_BIT_RATE) == 20_000_000, "Original bitrate changed");
            }
        }
        System.out.println("scrcpy rate control: opt-in parsing, actual encoder MIME, supported/unsupported/unknown CBR, explicit mode and bitrate preservation passed");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
