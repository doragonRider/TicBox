package o6;

/** Consumer contract fixture: b()==true suppresses the separate ringtone. */
public class w {
    private final z patternLoader;
    public w(z loader) { patternLoader = loader; }
    public void alert(p info, boolean plugged) {
        z.a pattern = patternLoader.a(info, plugged);
        if (pattern == null || !pattern.b()) dev.ticbox.companion.healthmodule.TestAlerts.sounds++;
    }
}
