package net.kollnig.missioncontrol.diagnostics;

import android.content.Context;
import android.util.Log;

import net.kollnig.missioncontrol.BuildConfig;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceScreen;

/** Inert-in-release bridge to the shared battery experiment. No Android component is exported. */
public final class BatteryExperimentHooks {
    public interface Observer {
        void onVpnStateChanged(boolean ready);
        void onNetworkChanged();
    }
    private static volatile Observer observer;
    private static volatile boolean vpnReady;
    private static boolean changingTailscale;

    private BatteryExperimentHooks() { }

    public static void initialize(Context context) {
        if (!BuildConfig.BATTERY_EXPERIMENT_ENABLED) return;
        try {
            Class.forName("net.kollnig.missioncontrol.diagnostics.BatteryExperimentController")
                    .getMethod("initialize", Context.class).invoke(null, context);
        } catch (ReflectiveOperationException ex) {
            Log.w("BatteryExperiment", "Battery experiment initialization failed", ex);
        }
    }

    public static void configurePreference(AppCompatActivity activity, PreferenceScreen screen) {
        if (!BuildConfig.BATTERY_EXPERIMENT_ENABLED) return;
        try {
            Class.forName("net.kollnig.missioncontrol.diagnostics.BatteryExperimentController")
                    .getMethod("configurePreference", AppCompatActivity.class, PreferenceScreen.class)
                    .invoke(null, activity, screen);
        } catch (ReflectiveOperationException ex) {
            Log.w("BatteryExperiment", "Battery experiment entry unavailable", ex);
        }
    }

    public static void setObserver(Observer value) { if (BuildConfig.BATTERY_EXPERIMENT_ENABLED) observer = value; }
    public static boolean isVpnReady() { return vpnReady; }
    public static void vpnState(boolean ready) {
        if (!BuildConfig.BATTERY_EXPERIMENT_ENABLED) return;
        vpnReady = ready;
        Observer current = observer;
        if (current != null) current.onVpnStateChanged(ready);
    }

    public static void networkChanged() {
        Observer current = observer;
        if (BuildConfig.BATTERY_EXPERIMENT_ENABLED && current != null) current.onNetworkChanged();
    }

    // Preference listeners run synchronously for edits made on the main thread.
    public static boolean isChangingTailscale() { return BuildConfig.BATTERY_EXPERIMENT_ENABLED && changingTailscale; }
    public static void setChangingTailscale(boolean value) { changingTailscale = value; }
}
