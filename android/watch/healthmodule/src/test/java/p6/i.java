package p6;

import com.google.android.clockwork.sysui.backend.notification.sysuicontract.StreamItemIdAndRevision;
import dev.ticbox.companion.healthmodule.TestAlerts;

/** Models an original sound-bearing haptic and a separately constructible pure waveform. */
public class i implements o6.z {
    public int calls;
    public boolean failOriginal, failPure;
    @Override public o6.z.a a(o6.p info, boolean plugged) {
        calls++;
        if (failOriginal) throw new IllegalStateException("fixture original failure");
        return new o6.z.a() {
            public void a() { if (!plugged) TestAlerts.vibrations++; TestAlerts.sounds++; }
            public boolean b() { return true; }
            public boolean c() { return false; }
        };
    }
    private o6.z.a j(String source, long[] waveform, StreamItemIdAndRevision id) {
        if (failPure) throw new IllegalStateException("fixture waveform construction failure");
        TestAlerts.lastWaveform = waveform.clone();
        return new o6.z.a() {
            public void a() {
                for (int i = 1; i < waveform.length; i += 2) if (waveform[i] > 0) { TestAlerts.vibrations++; break; }
            }
            public boolean b() { return false; }
            public boolean c() { return false; }
        };
    }
}
