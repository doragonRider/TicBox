package com.google.android.clockwork.sysui.application;

public final class c0 {
    public static final class j {
        private final hd.a<o6.x> C3;
        private final hd.a<o6.w> D3;
        public final o6.x vibrator;
        public final o6.w ringtone;
        public final p6.i factory = new p6.i();
        public j(boolean mismatchedLoader) {
            vibrator = new o6.x(factory);
            ringtone = new o6.w(mismatchedLoader ? new p6.i() : factory);
            C3 = () -> vibrator; D3 = () -> ringtone;
        }
    }
}
