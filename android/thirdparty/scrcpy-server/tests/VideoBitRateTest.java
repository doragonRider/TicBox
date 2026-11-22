import android.media.MediaCodec;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageReader;
import com.genymobile.scrcpy.control.ControlProtocolException;
import com.genymobile.scrcpy.video.CaptureControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

/** The Agent's dynamic bit rate and the OpenGL relay's tail copies, on the patched classes. */
public final class VideoBitRateTest {
    public static void main(String[] arguments) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(126); data.writeInt(12_000_000); data.writeByte(127);
        ControlMessageReader reader = new ControlMessageReader(new ByteArrayInputStream(bytes.toByteArray()));
        ControlMessage rate = reader.read();
        check(rate.getType() == ControlMessage.TYPE_SET_VIDEO_BIT_RATE && rate.getBitRate() == 12_000_000, "Bit rate framing");
        check(reader.read().getType() == ControlMessage.TYPE_REQUEST_KEYFRAME, "The rate consumed following control bytes");
        try {
            new ControlMessageReader(new ByteArrayInputStream(new byte[] {126, 0, 0, 0, 0})).read();
            throw new AssertionError("A zero bit rate was accepted");
        } catch (ControlProtocolException expected) { }

        CaptureControl capture = new CaptureControl();
        check(!capture.setVideoBitRate(8_000_000), "No encoder is running yet");
        MediaCodec first = new MediaCodec();
        capture.setRunningMediaCodec(first);
        check(first.bitRates.equals(Arrays.asList(8_000_000)), "A starting encoder takes the latest target");
        check(capture.setVideoBitRate(6_000_000) && first.bitRates.equals(Arrays.asList(8_000_000, 6_000_000)), "A running encoder changes rate");
        check(first.requests == 0 && first.eos == 0 && !capture.isResetRequested(), "A rate change never restarts capture");
        capture.reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED);
        check(!capture.setVideoBitRate(5_000_000) && first.bitRates.size() == 2, "A resetting encoder keeps its rate");
        capture.consumeReset();
        capture.setRunningMediaCodec(null);
        MediaCodec second = new MediaCodec();
        capture.setRunningMediaCodec(second);
        check(second.bitRates.equals(Arrays.asList(5_000_000)), "The encoder after a reset gets the latest target");
        second.rejection = new IllegalArgumentException("fixed-rate fixture");
        check(!capture.setVideoBitRate(4_000_000), "A codec that refuses dynamic rates keeps its own");
        check(!capture.setVideoBitRate(0), "A zero rate is ignored");

        System.out.println("scrcpy bit rate: framing and running/resetting/restarted/refusing encoders passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
