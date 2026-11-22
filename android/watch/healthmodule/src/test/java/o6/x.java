package o6;

/** Consumer contract fixture, including the OEM null-pattern early return. */
public class x {
    private final z patternLoader;
    public x(z loader) { patternLoader = loader; }
    public void alert(p info, boolean plugged) {
        z.a pattern = patternLoader.a(info, plugged);
        if (pattern != null) pattern.a();
    }
}
