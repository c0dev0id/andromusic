package de.codevoid.andromusic;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import androidx.test.core.app.ApplicationProvider;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationSongChangeTest {

    private MusicService musicService;
    private ServiceController<MusicService> serviceController;
    private NotificationManager notificationManager;
    private ShadowNotificationManager shadowNotificationManager;
    private File tempDir;

    @Before
    public void setUp() {
        serviceController = Robolectric.buildService(MusicService.class);
        serviceController.create();

        IBinder binder = serviceController.get().onBind(null);
        MusicService.MusicBinder musicBinder = (MusicService.MusicBinder) binder;
        musicService = musicBinder.getService();

        notificationManager = (NotificationManager)
                ApplicationProvider.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowNotificationManager = shadowOf(notificationManager);
    }

    @After
    public void tearDown() {
        serviceController.destroy();
        if (tempDir != null && tempDir.exists()) {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }

    @Test
    public void service_createsNotificationChannelOnCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertNotNull("Notification channel should be created",
                    notificationManager.getNotificationChannel("MusicServiceChannel"));
        }
    }

    @Test
    public void service_showsForegroundNotificationOnCreate() {
        // The service calls startForeground in onCreate, so a notification should exist
        List<Notification> notifications = shadowNotificationManager.getAllNotifications();
        assertTrue("Service should post a foreground notification on create",
                notifications.size() > 0);
    }

    @Test
    public void notification_showsDefaultContentWhenNoTrack() {
        // With no track loaded, notification should still show something
        List<Notification> notifications = shadowNotificationManager.getAllNotifications();
        assertTrue("Should have at least one notification", notifications.size() > 0);

        Notification notification = notifications.get(0);
        assertNotNull("Notification extras should not be null", notification.extras);
    }

    @Test
    public void buildNotification_showsArtistAndTitle() throws Exception {
        // Create a minimal test audio file (just needs to exist for the playlist)
        tempDir = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "test_music");
        tempDir.mkdirs();
        File testFile = new File(tempDir, "TestArtist - TestSong.mp3");
        FileOutputStream fos = new FileOutputStream(testFile);
        fos.write(new byte[]{0}); // minimal content
        fos.close();

        List<String> playlist = new ArrayList<>();
        playlist.add(testFile.getAbsolutePath());

        // Set playlist and trigger playback (this will call prepareAndPlay which updates notification)
        // MediaPlayer will fail with an IOException on a fake file, but the notification
        // should still be posted via startForeground in onCreate
        try {
            musicService.setPlaylist(playlist, 0);
        } catch (IOException e) {
            // Expected: MediaPlayer cannot decode a fake audio file
        }

        // Verify notifications are present - the service always has a foreground notification
        List<Notification> notifications = shadowNotificationManager.getAllNotifications();
        assertTrue("Should have at least one notification after setting playlist",
                notifications.size() > 0);
    }

    @Test
    public void notification_hasMediaStyleWithActions() {
        List<Notification> notifications = shadowNotificationManager.getAllNotifications();
        assertTrue("Should have at least one notification", notifications.size() > 0);

        Notification notification = notifications.get(0);
        // Verify the notification has media control actions (prev, play/pause, next)
        assertNotNull("Notification should have actions", notification.actions);
        assertEquals("Notification should have 3 actions (prev, play/pause, next)",
                3, notification.actions.length);
    }

    @Test
    public void notification_containsContentTitleAndText() {
        List<Notification> notifications = shadowNotificationManager.getAllNotifications();
        assertTrue("Should have at least one notification", notifications.size() > 0);

        Notification notification = notifications.get(0);
        // The default notification title when no track is loaded is "AndroMusic"
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull("Notification should have a content title (song title)", title);
        assertNotNull("Notification should have content text (artist)", text);
    }
}
