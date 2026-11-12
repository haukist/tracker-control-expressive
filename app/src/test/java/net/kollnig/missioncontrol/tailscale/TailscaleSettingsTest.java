package net.kollnig.missioncontrol.tailscale;

import static org.junit.Assert.*;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import eu.faircode.netguard.ServiceSinkhole;
import net.kollnig.missioncontrol.R;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class TailscaleSettingsTest {
    private ActivityController<AppCompatActivity> host;
    private SharedPreferences prefs;
    private TailscaleSettings settings;
    private Preference login, logout;

    @Before public void setUp() {
        host = Robolectric.buildActivity(AppCompatActivity.class);
        host.get().setTheme(R.style.AppTheme);
        host.setup();
        prefs = PreferenceManager.getDefaultSharedPreferences(host.get());
        prefs.edit().clear().putBoolean("enabled", true).putBoolean("ts_enabled", true).commit();
        ReflectionHelpers.setStaticField(TailscaleManager.class, "loginUrl", "");
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", TailscaleSnapshot.stopped());
        PreferenceManager manager = new PreferenceManager(host.get());
        PreferenceScreen screen = manager.createPreferenceScreen(host.get());
        login = new Preference(host.get()); login.setKey("ts_login"); screen.addPreference(login);
        logout = new Preference(host.get()); logout.setKey("ts_logout"); screen.addPreference(logout);
        settings = new TailscaleSettings(host.get(), prefs);
        settings.configure(screen);
    }

    @After public void tearDown() {
        ReflectionHelpers.setStaticField(TailscaleManager.class, "loginUrl", "");
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", TailscaleSnapshot.stopped());
        host.pause().stop().destroy();
    }

    private void deliverLoginUrl() {
        ReflectionHelpers.setStaticField(TailscaleManager.class, "loginUrl", "https://login.tailscale.com/a/test");
        settings.update();
    }

    @Test public void disablingTailscaleCancelsDelayedBrowserLaunch() {
        login.performClick();
        prefs.edit().putBoolean("ts_enabled", false).commit();
        settings.update();
        prefs.edit().putBoolean("ts_enabled", true).commit();
        deliverLoginUrl();
        assertNull(Shadows.shadowOf(host.get()).getNextStartedActivity());
    }

    @Test public void errorAndLeavingScreenCancelPendingLogin() {
        login.performClick();
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot",
                new TailscaleSnapshot("Error", "", "", "", false, "test error"));
        settings.update();
        deliverLoginUrl();
        assertNull(Shadows.shadowOf(host.get()).getNextStartedActivity());
        ReflectionHelpers.setStaticField(TailscaleManager.class, "loginUrl", "");
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", TailscaleSnapshot.stopped());
        login.performClick();
        settings.cancelPendingLogin();
        deliverLoginUrl();
        assertNull(Shadows.shadowOf(host.get()).getNextStartedActivity());
    }

    @Test public void activeLoginRequestStillOpensBrowserWhenUrlArrives() {
        login.performClick();
        deliverLoginUrl();
        Intent browser = Shadows.shadowOf(host.get()).getNextStartedActivity();
        assertNotNull(browser);
        assertEquals("login.tailscale.com", browser.getData().getHost());
    }

    @Test public void offlineLogoutUsesTheSameServiceCommandQueue() {
        prefs.edit().putBoolean("enabled", false).commit();
        logout.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent command = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(command);
        String key = ReflectionHelpers.getStaticField(ServiceSinkhole.class, "EXTRA_COMMAND");
        assertEquals("tailscale_logout", command.getSerializableExtra(key).toString());
    }
}
