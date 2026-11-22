package dev.ticbox.alertprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.service.notification.StatusBarNotification;

/** A shell-triggered notification source, with no companion, Bluetooth or watch privileges. */
public final class ProbeReceiver extends BroadcastReceiver {
    static final String ACTION = "dev.ticbox.alertprobe.RUN";
    static final String CHANNEL_PREFIX = "tw-alert-probe-";
    static final String TAG_PREFIX = "tw-alert-probe:";

    enum AlertCase {
        SILENT(false, false, 27101, "A - Silent"),
        SOUND_ONLY(true, false, 27102, "B - Sound request only"),
        VIBRATE_ONLY(false, true, 27103, "C - Vibration request only"),
        BOTH(true, true, 27104, "D - Sound and vibration requests");

        final boolean sound;
        final boolean vibrate;
        final int id;
        final String title;
        AlertCase(boolean sound, boolean vibrate, int id, String title) {
            this.sound = sound;
            this.vibrate = vibrate;
            this.id = id;
            this.title = title;
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        String result;
        int code;
        try {
            result = handle(context, intent);
            code = Activity.RESULT_OK;
        } catch (RuntimeException error) {
            result = "ALERT_PROBE_ERROR " + error.getClass().getSimpleName();
            code = Activity.RESULT_CANCELED;
        }
        if (isOrderedBroadcast()) {
            setResultCode(code);
            setResultData(result);
        }
    }

    static String handle(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) throw new IllegalArgumentException("action");
        String command = intent.getStringExtra("case");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) throw new IllegalStateException("manager");
        if ("CLEANUP".equals(command)) {
            cleanup(manager);
            return "ALERT_PROBE_OK cleanup=1";
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                || !manager.areNotificationsEnabled()) throw new SecurityException("notifications");
        if ("CHECK".equals(command)) return "ALERT_PROBE_OK ready=1" + environment(context, manager);
        AlertCase alert = AlertCase.valueOf(command == null ? "" : command);
        String run = intent.getStringExtra("run");
        if (run == null || !run.matches("[a-z0-9]{6,48}")) throw new IllegalArgumentException("run");
        // Each run has fresh channels so user-modified, immutable old channel settings cannot
        // silently turn the requested sound/vibration combinations into a different experiment.
        String channelId = CHANNEL_PREFIX + run + "-" + alert.name();
        NotificationChannel channel = new NotificationChannel(channelId, alert.title, NotificationManager.IMPORTANCE_DEFAULT);
        channel.enableVibration(alert.vibrate);
        channel.setSound(alert.sound ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : null,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        manager.createNotificationChannel(channel);
        NotificationChannel actual = manager.getNotificationChannel(channelId);
        if (actual == null || (actual.getSound() != null) != alert.sound || actual.shouldVibrate() != alert.vibrate
                || actual.getImportance() != NotificationManager.IMPORTANCE_DEFAULT) throw new IllegalStateException("channel");
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_probe)
                .setContentTitle(alert.title)
                .setContentText("TicBox alert probe. Run " + run)
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(false)
                .setLocalOnly(false)
                .setOnlyAlertOnce(false)
                .setAutoCancel(true)
                .build();
        manager.notify(TAG_PREFIX + run + ":" + alert.name(), alert.id, notification);
        return "ALERT_PROBE_OK case=" + alert.name() + " sound=" + (actual.getSound() != null ? 1 : 0)
                + " vibrate=" + (actual.shouldVibrate() ? 1 : 0) + " importance=" + actual.getImportance()
                + environment(context, manager);
    }

    private static String environment(Context context, NotificationManager manager) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        return " dnd=" + manager.getCurrentInterruptionFilter() + " ringer=" + (audio == null ? -1 : audio.getRingerMode());
    }

    private static void cleanup(NotificationManager manager) {
        for (StatusBarNotification item : manager.getActiveNotifications()) {
            if (item.getTag() != null && item.getTag().startsWith(TAG_PREFIX)) manager.cancel(item.getTag(), item.getId());
        }
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            if (channel.getId().startsWith(CHANNEL_PREFIX)) manager.deleteNotificationChannel(channel.getId());
        }
    }
}
