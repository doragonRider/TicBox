package android.os;

import java.util.HashMap;

/** Host-only Android API double; never included in the shipped server. */
public final class Bundle {
    private final HashMap<String, Integer> values = new HashMap<>();
    public void putInt(String key, int value) { values.put(key, value); }
    public int getInt(String key) { return values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public int size() { return values.size(); }
}
