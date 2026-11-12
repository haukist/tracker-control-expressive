package net.kollnig.missioncontrol.diagnostics;

import android.app.Application;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.PowerManager;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.tailscale.TailscaleManager;
import net.kollnig.missioncontrol.tailscale.TailscaleSnapshot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class)
public class BatteryExperimentAndroidTest {
    private Application app;
    private SharedPreferences prefs;
    private BatteryExperimentController controller;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(app);
        prefs.edit().clear().putBoolean("enabled", true).putBoolean("ts_enabled", true).commit();
        app.getSharedPreferences("battery_experiments", Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(BatteryExperimentController.class, "instance", null);
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", new TailscaleSnapshot("Running", "", "", "", false, ""));
        ReflectionHelpers.setStaticField(TailscaleManager.class, "bridgeAttached", true);
        BatteryExperimentHooks.vpnState(true);
        Shadows.shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        Shadows.shadowOf((AlarmManager) app.getSystemService(Context.ALARM_SERVICE)).setCanScheduleExactAlarms(true);
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                .putExtra(BatteryManager.EXTRA_LEVEL, 90).putExtra(BatteryManager.EXTRA_SCALE, 100));
        controller = BatteryExperimentController.get(app);
    }
    @After public void teardown() {
        controller.stop();
        BatteryExperimentHooks.setObserver(null);
        BatteryExperimentHooks.vpnState(false);
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", TailscaleSnapshot.stopped());
        ReflectionHelpers.setStaticField(TailscaleManager.class, "bridgeAttached", false);
        ReflectionHelpers.setStaticField(BatteryExperimentController.class, "instance", null);
    }

    @Test public void permissionAndBridgeAreRequiredBeforeArm() {
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        Shadows.shadowOf(alarm).setCanScheduleExactAlarms(false);
        try { controller.arm(false, 3); fail(); } catch (IllegalStateException expected) { }
        assertFalse(controller.active());
        Shadows.shadowOf(alarm).setCanScheduleExactAlarms(true);
        ReflectionHelpers.setStaticField(TailscaleManager.class, "bridgeAttached", false);
        try { controller.arm(false, 3); fail(); } catch (IllegalStateException expected) { }
        assertFalse(controller.active());
    }

    @Test public void chargingAndScreenOffDriveReservationWithoutPolling() {
        controller.arm(false, 3);
        assertEquals(BatteryExperimentRun.Stage.ARMED, controller.engine.run.stage);
        Shadows.shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(BatteryExperimentRun.Stage.SWITCHING, controller.engine.run.stage);
        assertFalse(prefs.getBoolean("ts_enabled", true));
        assertTrue(prefs.getBoolean("enabled", false));
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("charging", controller.engine.run.reason);
        assertTrue(prefs.getBoolean("ts_enabled", false));
    }

    @Test public void userPreferenceChangeAndProtectionStopArePreserved() {
        controller.arm(false, 3);
        controller.engine.run.restorePending = true;
        controller.engine.run.lastWrittenTailscale = true;
        prefs.edit().putBoolean("ts_enabled", false).commit();
        assertEquals("settings_changed", controller.engine.run.reason);
        assertFalse(prefs.getBoolean("ts_enabled", true));
        assertEquals("skipped_user_change", controller.engine.run.restoration);
    }

    @Test public void unplugEventStartsEvenWhenBatteryStickyUpdateLags() {
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC));
        controller.arm(false, 3);
        Shadows.shadowOf((PowerManager) app.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(BatteryExperimentRun.Stage.ARMED, controller.engine.run.stage);
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_DISCONNECTED));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(BatteryExperimentRun.Stage.SWITCHING, controller.engine.run.stage);
    }

    @Test public void restoreNeverEnablesStoppedProtection() {
        controller.arm(false, 3);
        controller.engine.run.restorePending = true;
        prefs.edit().putBoolean("enabled", false).commit();
        assertFalse(prefs.getBoolean("enabled", true));
        assertEquals("skipped_user_change", controller.engine.run.restoration);
    }

    @Test public void storeRetainsOnlyTenRunsAndRecoversInterruptedMeasurement() {
        BatteryExperimentStore store = new BatteryExperimentStore(app);
        for (int i = 0; i < 12; i++) store.save(new BatteryExperimentRun(false, 3, true));
        assertEquals(10, store.history().size());
        ReflectionHelpers.setStaticField(BatteryExperimentController.class, "instance", null);
        BatteryExperimentController recovered = BatteryExperimentController.get(app);
        assertEquals("process_restarted", recovered.engine.run.reason);
        assertFalse(recovered.active());
    }

    @Test public void debugScreenAndPermissionComponentsAreInternal() throws Exception {
        android.content.pm.PackageManager pm = app.getPackageManager();
        assertFalse(pm.getActivityInfo(new android.content.ComponentName(app, BatteryExperimentActivity.class), 0).exported);
        assertFalse(pm.getReceiverInfo(new android.content.ComponentName(app, BatteryExperimentAlarmReceiver.class), 0).exported);
        try (org.robolectric.android.controller.ActivityController<BatteryExperimentActivity> host =
                     Robolectric.buildActivity(BatteryExperimentActivity.class).setup()) {
            assertEquals(app.getString(net.kollnig.missioncontrol.R.string.battery_experiment_title), host.get().getTitle());
        }
    }

    @Test public void debugPreferenceUsesTypedResourceAndInternalActivity() {
        try (org.robolectric.android.controller.ActivityController<androidx.appcompat.app.AppCompatActivity> host =
                     Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class)) {
            host.get().setTheme(net.kollnig.missioncontrol.R.style.AppTheme); host.setup();
            androidx.preference.PreferenceManager manager = new androidx.preference.PreferenceManager(host.get());
            androidx.preference.PreferenceScreen screen = manager.createPreferenceScreen(host.get());
            androidx.preference.PreferenceScreen tailscale = manager.createPreferenceScreen(host.get());
            tailscale.setKey("screen_tailscale"); screen.addPreference(tailscale);
            BatteryExperimentHooks.configurePreference(host.get(), screen);
            androidx.preference.Preference entry = tailscale.findPreference("battery_experiment");
            assertNotNull(entry);
            assertEquals(app.getString(net.kollnig.missioncontrol.R.string.battery_experiment_title), entry.getTitle());
            entry.performClick();
            assertEquals(BatteryExperimentActivity.class.getName(), Shadows.shadowOf(host.get()).getNextStartedActivity().getComponent().getClassName());
        }
    }
}
