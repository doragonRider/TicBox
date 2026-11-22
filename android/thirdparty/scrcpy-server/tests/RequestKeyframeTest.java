import android.media.MediaCodec;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageReader;
import com.genymobile.scrcpy.control.ControlProtocolException;
import com.genymobile.scrcpy.video.CaptureControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the patched production classes with host-only Android doubles. */
public final class RequestKeyframeTest {
    public static void main(String[] arguments) throws Exception {
        CaptureControl capture = new CaptureControl();
        check(!capture.requestKeyFrame(), "An initializing encoder must not be restarted");
        MediaCodec codec = new MediaCodec();
        capture.setRunningMediaCodec(codec);
        check(capture.requestKeyFrame(), "A running encoder must accept a dynamic sync request");
        check(codec.requests == 1 && codec.eos == 0 && !capture.isResetRequested(), "Light requests must not set EOS/reset");
        codec.rejection = new IllegalArgumentException("unsupported parameter fixture");
        check(!capture.requestKeyFrame(), "An unsupported codec must remain a bounded no-op");
        codec.rejection = new IllegalStateException("stopped fixture");
        check(!capture.requestKeyFrame(), "Codec lifecycle rejection must not escape");
        check(codec.eos == 0 && !capture.isResetRequested(), "Failure must not silently escalate to capture restart");
        codec.rejection = null;
        capture.reset(CaptureControl.RESET_REASON_CLIENT_RESET);
        int before = codec.requests;
        check(!capture.requestKeyFrame() && codec.requests == before, "Resetting encoders cannot take a new sync request");
        check(capture.consumeReset() == CaptureControl.RESET_REASON_CLIENT_RESET, "Explicit legacy resets remain intact");
        capture.setRunningMediaCodec(null);
        check(!capture.requestKeyFrame(), "Released encoders cannot take a new sync request");

        // An encoder makes the sync frame from its next input, so a still screen must be asked for one, after the request.
        final MediaCodec[] still = {null};
        final int[] inputs = {0};
        final int[] requestsAtInput = {-1};
        CaptureControl refreshed = new CaptureControl();
        refreshed.setKeyFrameInput(() -> { inputs[0]++; requestsAtInput[0] = still[0].requests; });
        check(!refreshed.requestKeyFrame() && inputs[0] == 0, "No encoder, no input");
        still[0] = new MediaCodec();
        refreshed.setRunningMediaCodec(still[0]);
        check(refreshed.requestKeyFrame() && inputs[0] == 1 && requestsAtInput[0] == 1, "The input must follow the sync request");
        still[0].rejection = new IllegalArgumentException("unsupported parameter fixture");
        check(!refreshed.requestKeyFrame() && inputs[0] == 1, "A refused request asks for no input");

        final CaptureControl concurrent = new CaptureControl();
        final MediaCodec active = new MediaCodec();
        active.entered = new CountDownLatch(1);
        active.release = new CountDownLatch(1);
        concurrent.setRunningMediaCodec(active);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> { try { check(concurrent.requestKeyFrame(), "Concurrent request refused"); } catch (Throwable e) { failure.set(e); } });
        CountDownLatch removed = new CountDownLatch(1);
        Thread unregister = new Thread(() -> { concurrent.setRunningMediaCodec(null); removed.countDown(); });
        try {
            request.start();
            check(active.entered.await(1, TimeUnit.SECONDS), "Request did not enter encoder");
            unregister.start();
            check(!removed.await(50, TimeUnit.MILLISECONDS), "Unregister must share request ownership monitor");
        } finally {
            active.release.countDown();
            request.join(2_000); unregister.join(2_000);
        }
        check(!request.isAlive() && !unregister.isAlive() && failure.get() == null, "Concurrent cleanup failed");
        check(!concurrent.requestKeyFrame() && active.requests == 1 && active.eos == 0, "Unregistered codec was reused");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(127); data.writeByte(17); data.writeByte(127);
        data.writeByte(ControlMessage.TYPE_INJECT_TEXT); data.writeInt(3); data.write(new byte[] {'a', 'b', 'c'});
        ControlMessageReader reader = new ControlMessageReader(new ByteArrayInputStream(bytes.toByteArray()));
        check(reader.read().getType() == ControlMessage.TYPE_REQUEST_KEYFRAME, "Wrong extension opcode");
        check(reader.read().getType() == ControlMessage.TYPE_RESET_VIDEO, "Legacy reset framing changed");
        check(reader.read().getType() == ControlMessage.TYPE_REQUEST_KEYFRAME, "Repeated extension framing changed");
        check("abc".equals(reader.read().getText()), "An empty sync request consumed following control bytes");
        try {
            new ControlMessageReader(new ByteArrayInputStream(new byte[] {125})).read();
            throw new AssertionError("Unnegotiated opcode unexpectedly accepted");
        } catch (ControlProtocolException expected) { }
        System.out.println("scrcpy keyframe: active/initializing/resetting/released codecs, rejection, input for a still screen, concurrent cleanup and control framing passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
