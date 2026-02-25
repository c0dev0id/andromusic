package de.codevoid.andromusic;

import android.Manifest;
import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import androidx.test.core.app.ApplicationProvider;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityPermissionTest {

    @Test
    public void onCreate_requestsAudioPermission() {
        Application app = ApplicationProvider.getApplicationContext();

        // Pre-grant POST_NOTIFICATIONS so it doesn't supersede the audio permission request
        ShadowApplication shadowApp = Shadows.shadowOf(app);
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .create()
                .get();

        ShadowActivity shadowActivity = shadowOf(activity);

        // Check that READ_MEDIA_AUDIO permission was requested during onCreate
        ShadowActivity.PermissionsRequest request = shadowActivity.getLastRequestedPermission();

        boolean audioPermissionRequested = false;
        if (request != null) {
            for (String perm : request.requestedPermissions) {
                if (Manifest.permission.READ_MEDIA_AUDIO.equals(perm) ||
                        Manifest.permission.READ_EXTERNAL_STORAGE.equals(perm)) {
                    audioPermissionRequested = true;
                    break;
                }
            }
        }

        assertTrue("MainActivity should request audio read permission on create",
                audioPermissionRequested);
    }
}
