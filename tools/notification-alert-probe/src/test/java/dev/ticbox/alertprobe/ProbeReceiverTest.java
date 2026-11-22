package dev.ticbox.alertprobe;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ProbeReceiverTest {
    private Application app;
    private NotificationManager manager;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        manager = app.getSystemService(NotificationManager.class);
    }
    private Intent command(String name, String run) {
        return new Intent(ProbeReceiver.ACTION).putExtra("case", name).putExtra("run", run);
    }

    @Test public void fourCasesKeepSoundAndVibrationIndependentAtThePhoneChannel() {
        for (ProbeReceiver.AlertCase alert : ProbeReceiver.AlertCase.values()) {
            String result = ProbeReceiver.handle(app, command(alert.name(), "testrun1"));
            assertTrue(result.startsWith("ALERT_PROBE_OK case=" + alert.name()));
            StatusBarNotification item = java.util.Arrays.stream(manager.getActiveNotifications())
                    .filter(n -> n.getId() == alert.id).findFirst().orElseThrow();
            Notification notification = item.getNotification();
            NotificationChannel channel = manager.getNotificationChannel(notification.getChannelId());
            assertEquals(alert.sound, channel.getSound() != null);
            assertEquals(alert.vibrate, channel.shouldVibrate());
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.getImportance());
            assertEquals(alert.title, notification.extras.getString(Notification.EXTRA_TITLE));
            assertEquals(0, notification.flags & (Notification.FLAG_LOCAL_ONLY | Notification.FLAG_ONLY_ALERT_ONCE));
        }
        assertEquals(4, manager.getActiveNotifications().length);
    }

    @Test public void cleanupLeavesOtherChannelAndNotificationUntouched() {
        NotificationChannel other = new NotificationChannel("unrelated", "Unrelated", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(other);
        manager.notify("unrelated", 99, new Notification.Builder(app, "unrelated").setSmallIcon(R.drawable.ic_probe).build());
        ProbeReceiver.handle(app, command("BOTH", "testrun1"));
        ProbeReceiver.handle(app, command("CLEANUP", null));
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals("unrelated", manager.getActiveNotifications()[0].getTag());
        assertNotNull(manager.getNotificationChannel("unrelated"));
        assertNull(manager.getNotificationChannel(ProbeReceiver.CHANNEL_PREFIX + "testrun1-BOTH"));
    }

    @Test public void invalidInputDoesNotPublishOrCreateChannels() {
        for (Intent input : new Intent[] { command("OTHER", "testrun1"), command("BOTH", "bad;run"),
                new Intent("other.action").putExtra("case", "BOTH").putExtra("run", "testrun1") }) {
            assertThrows(IllegalArgumentException.class, () -> ProbeReceiver.handle(app, input));
        }
        assertEquals(0, manager.getActiveNotifications().length);
        assertTrue(manager.getNotificationChannels().isEmpty());
    }

    @Test public void immutableOldChannelSettingsCannotSilentlyChangeTheExperiment() {
        String id = ProbeReceiver.CHANNEL_PREFIX + "testrun1-BOTH";
        manager.createNotificationChannel(new NotificationChannel(id, "Muted", NotificationManager.IMPORTANCE_LOW));
        assertThrows(IllegalStateException.class, () -> ProbeReceiver.handle(app, command("BOTH", "testrun1")));
        assertEquals(0, manager.getActiveNotifications().length);
    }
}
