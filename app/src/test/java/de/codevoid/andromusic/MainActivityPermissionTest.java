package de.codevoid.andromusic;

import android.Manifest;
import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import androidx.test.core.app.ApplicationProvider;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityPermissionTest {

    @Test
    public void onCreate_requestsNotificationPermission() {
        // Grant audio permission so it doesn't interfere
        Application app = ApplicationProvider.getApplicationContext();
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .create()
                .get();

        ShadowActivity shadowActivity = shadowOf(activity);

        // Check that POST_NOTIFICATIONS permission was requested during onCreate
        // getLastRequestedPermission returns permissions from the most recent requestPermissions call
        boolean notificationPermissionRequested = false;
        for (ShadowActivity.PermissionsRequest request : shadowActivity.getPermissionsRequests()) {
            for (String perm : request.requestedPermissions) {
                if (Manifest.permission.POST_NOTIFICATIONS.equals(perm)) {
                    notificationPermissionRequested = true;
                    break;
                }
            }
            if (notificationPermissionRequested) break;
        }

        assertTrue("MainActivity should request POST_NOTIFICATIONS permission on API 33+",
                notificationPermissionRequested);
    }
}
