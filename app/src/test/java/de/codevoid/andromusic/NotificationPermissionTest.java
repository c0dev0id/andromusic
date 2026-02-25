package de.codevoid.andromusic;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotificationPermissionTest {

    @Test
    public void manifest_declaresPostNotificationsPermission() throws Exception {
        PackageManager pm = ApplicationProvider.getApplicationContext().getPackageManager();
        PackageInfo packageInfo = pm.getPackageInfo(
                ApplicationProvider.getApplicationContext().getPackageName(),
                PackageManager.GET_PERMISSIONS);

        boolean found = false;
        for (String perm : packageInfo.requestedPermissions) {
            if (Manifest.permission.POST_NOTIFICATIONS.equals(perm)) {
                found = true;
                break;
            }
        }
        assertTrue("AndroidManifest.xml should declare POST_NOTIFICATIONS permission", found);
    }

    @Test
    public void manifest_declaresForegroundServicePermission() throws Exception {
        PackageManager pm = ApplicationProvider.getApplicationContext().getPackageManager();
        PackageInfo packageInfo = pm.getPackageInfo(
                ApplicationProvider.getApplicationContext().getPackageName(),
                PackageManager.GET_PERMISSIONS);

        boolean found = false;
        for (String perm : packageInfo.requestedPermissions) {
            if (Manifest.permission.FOREGROUND_SERVICE.equals(perm)) {
                found = true;
                break;
            }
        }
        assertTrue("AndroidManifest.xml should declare FOREGROUND_SERVICE permission", found);
    }
}
