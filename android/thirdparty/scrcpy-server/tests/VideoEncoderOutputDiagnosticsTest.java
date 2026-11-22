import com.genymobile.scrcpy.video.EncoderInputLog;
import com.genymobile.scrcpy.video.VideoEncoderOutputDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class VideoEncoderOutputDiagnosticsTest {
    public static void main(String[] args) {
        List<String> records = new ArrayList<>();
        VideoEncoderOutputDiagnostics stats = new VideoEncoderOutputDiagnostics(33, true, 80_000_000, records::add);
        // 25KB every 10ms of media = 20Mbps. A 30ms local write slows wall delivery to ~5Mbps.
        for (int i = 0; i < 102; i++) {
            long started = 1_000_000_000L + i * 40_000_000L;
            stats.record(25000, i == 0, 1_000_000L + i * 10_000L, started, started + 1_000_000,
                    started + 1_000_000, started + 31_000_000);
        }
        check(records.size() == 2, "Wrong sample cadence");
        for (int index = 0; index < records.size(); index++) {
            String record = records.get(index);
            check(number(record, "mediaBitrate") == 20_000_000, "Wall pacing disguised encoder output rate");
            check(number(record, "maxLocalWriteMicros") == 30000 && number(record, "maxDequeueWaitMicros") == 1000, "Source stages conflated");
            check(number(record, "targetBitrate") == 80_000_000, "Requested rate lost");
            check(number(record, "frames") == (index == 0 ? 51 : 50) && number(record, "encodedBytes") == number(record, "frames") * 25000, "Window counts not reset");
            check(record.length() < 2048, "Unbounded diagnostic");
        }
        int before = records.size();
        for (int i = 0; i < 60; i++) {
            long started = 6_000_000_000L + i * 40_000_000L;
            stats.record(1000, false, 900000, started, started + 1_000_000, started + 1_000_000, started + 31_000_000);
        }
        check(records.size() > before, "Invalid timestamps lost all observations");
        String bad = records.get(records.size() - 1);
        check(number(bad, "invalidPtsIntervals") > 0 && !bad.contains("mediaBitrate"), "Bad timestamps produced a fake bitrate");
        VideoEncoderOutputDiagnostics rejected = new VideoEncoderOutputDiagnostics(33, true, 20000000, raw -> { throw new IllegalStateException("fixture closed"); });
        rejected.record(1000, false, 1000, 0, 1, 1, 2_100_000_000L);
        rejected.record(1000, false, 2000, 3_000_000_000L, 3_000_000_001L, 3_000_000_001L, 5_200_000_000L);
        heldInputs();
        System.out.println("scrcpy output diagnostics: media-clock bitrate versus wall pacing, source wait/write separation, window reset, invalid PTS, sink failure and held inputs passed");
    }
    /** An encoder that gives each picture back only once the next input arrives, as on MediaTek. */
    private static void heldInputs() {
        List<String> records = new ArrayList<>();
        EncoderInputLog log = new EncoderInputLog();
        VideoEncoderOutputDiagnostics stats = new VideoEncoderOutputDiagnostics(33, true, 40_000_000, records::add, log);
        long start = 10_000_000_000L;
        long previous = -1;
        // 100 pictures 10 ms apart, each out 1 ms after the next one went in.
        for (int i = 0; i <= 100; i++) {
            long at = start + i * 10_000_000L;
            log.submitted(at, false, at);
            if (previous >= 0) { output(stats, log, previous, at + 1_000_000); }
            previous = at;
        }
        // Then the screen stops: a copy 25 ms later lets the last picture out, and the encoder keeps the copy.
        long copy = start + 1_025_000_000L;
        log.submitted(copy, true, copy);
        output(stats, log, previous, copy + 2_000_000);
        // A picture the frame rate limit dropped, the held copy, and a repeat the framework made itself.
        long dropped = copy + 10_000_000L;
        long next = copy + 20_000_000L;
        log.submitted(dropped, false, dropped);
        log.submitted(next, false, next);
        output(stats, log, copy, next + 1_000_000);
        output(stats, log, next, next + 30_000_000);
        output(stats, log, next + 40_000_000L, next + 41_000_000);
        output(stats, log, next + 3_000_000_000L, next + 3_100_000_000L);
        // A window with outputs but nothing drawn, as without OpenGL, says nothing about inputs.
        output(stats, log, next + 4_400_000_000L, next + 4_500_000_000L);
        output(stats, log, next + 5_300_000_000L, next + 5_400_000_000L);
        check(records.stream().filter(r -> r.contains("\"output_sample\"")).count() == 2, "Output windows lost");
        long inputSamples = records.stream().filter(r -> r.contains("\"input_sample\"")).count();
        check(inputSamples == 1, "Expected one input sample, got " + inputSamples);
        String held = records.stream().filter(r -> r.contains("\"input_sample\"")).findFirst().get();
        check(number(held, "submittedPictures") == 103 && number(held, "submittedCopies") == 1, "Inputs miscounted");
        check(number(held, "outputPictures") == 102 && number(held, "outputCopies") == 1, "Outputs miscounted");
        check(number(held, "picturesOneBehind") == 101 && number(held, "picturesNoneBehind") == 1, "Held pictures not seen");
        // The held copy came out behind the dropped picture and the next one.
        check(number(held, "picturesTwoBehind") == 0 && number(held, "maxInputsBehind") == 2, "Wrong depth");
        check(number(held, "maxPictureLatencyMicros") == 30_000 && number(held, "maxCopyLatencyMicros") == 21_000, "Wrong latency");
        check(number(held, "meanPictureLatencyMicros") == (100L * 11_000 + 27_000 + 30_000) / 102, "Wrong mean");
        check(number(held, "skippedInputs") == 1 && number(held, "unmatchedOutputs") == 2, "Drops or repeats not told apart");
        check(number(held, "overflowedInputs") == 0 && held.length() < 2048, "Unbounded diagnostic");
        EncoderInputLog full = new EncoderInputLog();
        for (int i = 0; i < EncoderInputLog.CAPACITY + 3; i++) { full.submitted(1_000_000L * (i + 1), false, i); }
        check(full.drain()[3] == 3 && full.output(1_000L, 0) == null && full.output(4_000L, 0).behind == EncoderInputLog.CAPACITY - 1, "Full log mishandled");
    }

    /** As the encoder thread does it: the log first, once the output is written out. */
    private static void output(VideoEncoderOutputDiagnostics stats, EncoderInputLog log, long presentationNanos, long dequeued) {
        EncoderInputLog.Match input = log.output(presentationNanos / 1000, dequeued + 100_000);
        stats.record(1000, false, presentationNanos / 1000, dequeued - 500_000, dequeued, dequeued, dequeued + 100_000, input);
    }

    private static long number(String json, String key) {
        java.util.regex.Matcher m = Pattern.compile("\\\"" + key + "\\\":([0-9]+)").matcher(json);
        if (!m.find()) { throw new AssertionError("Missing " + key); }
        return Long.parseLong(m.group(1));
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
