package dev.ticbox.companion.healthmodule;

/** Observable effects of authored fixtures, never actual audio or vibration. */
public final class TestAlerts {
    public static volatile int sounds, vibrations;
    public static long[] lastWaveform;
    public static void reset() { sounds = vibrations = 0; lastWaveform = null; }
}
