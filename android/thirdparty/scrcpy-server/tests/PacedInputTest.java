import com.genymobile.scrcpy.opengl.PresentationGate;
import com.genymobile.scrcpy.opengl.TailRepeat;
import com.genymobile.scrcpy.video.EncoderInputLog;

/** When OpenGL lets a picture into the encoder, with the production gate and input log. */
public final class PacedInputTest {
    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        rate();
        hold();
        backlog();
        tail();
        awaited();
        System.out.println("scrcpy paced input: rate with jitter and copies, no drops after copies, hold while backed up, idle holding,"
                + " drain, copies only while the encoder holds a picture passed");
    }

    private static void rate() {
        long interval = 1_000_000_000L / 120;
        PresentationGate gate = new PresentationGate(120);
        check(gate.delay(0, false) == 0, "The first picture waited");
        gate.presented(0);
        // A picture 1 ms after the last, as one just after a copy used to be, waits for its turn instead of being dropped.
        check(gate.delay(MS, false) == interval - PresentationGate.JITTER_NANOS - MS, "Wrong wait");
        check(gate.delay(interval - PresentationGate.JITTER_NANOS, false) == 0, "A slightly early picture was held");
        // A picture always waiting, tried every millisecond: still 120 a second.
        PresentationGate steady = new PresentationGate(120);
        int presented = 0;
        for (long now = 0; now < 1_000 * MS; now += MS) {
            if (steady.delay(now, false) == 0) {
                steady.presented(now);
                presented++;
            }
        }
        check(presented >= 119 && presented <= 121, "Rate not kept: " + presented);
        // A copy takes a picture's place: a picture right after it waits for its turn.
        PresentationGate copied = new PresentationGate(120);
        copied.presented(0);
        copied.presented(interval * 3 / 2);
        check(copied.delay(13 * MS, false) > 0, "A picture right after a copy went straight in");
        PresentationGate free = new PresentationGate(0);
        free.presented(0);
        check(free.delay(1, false) == 0, "No limit still waited");
    }

    private static void hold() {
        PresentationGate gate = new PresentationGate(0);
        check(gate.delay(10, true) == PresentationGate.MAX_HOLD_NANOS, "A backed-up encoder got the picture");
        check(gate.delay(10 + 50 * MS, true) == PresentationGate.MAX_HOLD_NANOS - 50 * MS, "The hold restarted");
        check(gate.delay(10 + 60 * MS, false) == 0, "A drained encoder waited");
        check(gate.delay(10 + PresentationGate.MAX_HOLD_NANOS, true) == 0, "Held longer than the most");
        gate.presented(10 + PresentationGate.MAX_HOLD_NANOS);
        check(gate.delay(20 + PresentationGate.MAX_HOLD_NANOS, true) == PresentationGate.MAX_HOLD_NANOS, "A new picture inherited the old hold");
    }

    private static void backlog() {
        // Idle: the encoder gave back the first four of eight inputs and keeps the rest until more arrive.
        EncoderInputLog idle = new EncoderInputLog();
        for (int i = 0; i < 8; i++) {
            idle.submitted((i + 1) * 10 * MS, true, i * 10 * MS);
        }
        for (int i = 0; i < 4; i++) {
            idle.output((i + 1) * 10_000, 80 * MS + i * 10 * MS);
        }
        check(!idle.backedUp(1_000 * MS), "Held inputs of an idle encoder taken for a backlog");

        // Thirty pictures 1 ms apart, then outputs only every 40 ms, held up by a blocked write.
        EncoderInputLog log = new EncoderInputLog();
        for (int i = 0; i < 30; i++) {
            log.submitted((i + 1) * MS, false, i * MS);
        }
        check(!log.backedUp(240 * MS), "Backed up before any output");
        log.output(1_000, 50 * MS);
        check(!log.backedUp(60 * MS), "Inputs under 100 ms old taken for a backlog");
        log.output(2_000, 90 * MS);
        long now = 150 * MS;
        check(log.backedUp(now), "Twenty-eight inputs behind a blocked write not seen");
        check(!log.backedUp(90 * MS + EncoderInputLog.OUTPUT_IDLE_NANOS), "An encoder giving nothing back counted as backed up");
        int[] woken = {0};
        log.whenDrained(() -> woken[0]++);
        // The write unblocks and the backlog comes out 1 ms apart: the listener runs once, when it ends.
        for (int out = 2; out < 30; out++) {
            now += MS;
            log.output((out + 1) * 1_000, now);
            boolean ended = 30 - out - 1 < EncoderInputLog.BACKLOG_INPUTS;
            check(woken[0] == (ended ? 1 : 0), "Woken at the wrong output: " + out);
        }
    }

    private static void tail() {
        TailRepeat tail = new TailRepeat(8_334);
        // A picture only a little late for its frame isn't preceded by a copy.
        check(tail.start() == 12_501_000L, "The first copy didn't wait a frame and a half");
        check(tail.due(true) && tail.next() == 8_334_000L, "A held picture got no copy");
        check(!tail.due(false) && tail.next() == -1, "Copies went on with nothing held");
        check(!tail.due(true), "Copies came back without a new picture");
        tail.start();
        int copies = 0;
        while (tail.due(true)) {
            copies++;
        }
        check(copies == TailRepeat.COUNT, "An encoder that never gives a picture back got unbounded copies: " + copies);
        check(new TailRepeat(0).start() == -1, "No interval still made copies");
    }

    private static void awaited() {
        EncoderInputLog log = new EncoderInputLog();
        check(!log.holdsAwaited(), "An empty encoder held something");
        log.submitted(10 * MS, false, 0);
        log.submitted(20 * MS, true, 10 * MS);
        check(log.holdsAwaited(), "A picture in the encoder not seen");
        log.output(10_000, 30 * MS);
        check(!log.holdsAwaited(), "Only a copy left, yet still waiting");
        // The copy a refresh draws becomes the sync frame, so it is waited for like a picture.
        log.submitted(30 * MS, true, true, 30 * MS);
        check(log.holdsAwaited(), "A refresh's copy not waited for");
        log.output(30_000, 40 * MS);
        check(!log.holdsAwaited(), "Still waiting after the sync frame came out");
        // A full log drops its oldest record, and what it waited for with it.
        EncoderInputLog full = new EncoderInputLog();
        full.submitted(0, false, 0);
        for (int i = 1; i <= EncoderInputLog.CAPACITY; i++) {
            full.submitted(i * MS, true, i * MS);
        }
        check(!full.holdsAwaited(), "A dropped record still counted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
