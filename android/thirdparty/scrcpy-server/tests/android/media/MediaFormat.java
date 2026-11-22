package android.media;

import java.util.HashMap;
import java.util.Map;

/** Host-only format store; excluded from the shipped server. */
public final class MediaFormat {
    public static final String KEY_BITRATE_MODE = "bitrate-mode";
    public static final String KEY_BIT_RATE = "bitrate";
    public static final String KEY_FRAME_RATE = "frame-rate";
    private final Map<String, Number> values = new HashMap<>();
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void setInteger(String key, int value) { values.put(key, value); }
    public int getInteger(String key) { return (Integer) values.get(key); }
    public float getFloat(String key) { return (Float) values.get(key); }
    public void setFloat(String key, float value) { values.put(key, value); }
}
