package net.kollnig.missioncontrol.diagnostics;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.appcompat.app.AppCompatActivity;

import net.kollnig.missioncontrol.tailscale.TailscaleManager;
import net.kollnig.missioncontrol.tailscale.TailscaleSnapshot;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import eu.faircode.netguard.ServiceSinkhole;

/** Android adapter. Listeners are attached only during a requested experiment. */
public final class BatteryExperimentController implements BatteryExperimentEngine.Host {
    // Only an application context is retained for this process-lifetime controller.
    @SuppressLint("StaticFieldLeak")
    private static BatteryExperimentController instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final AlarmManager alarms;
    private final PowerManager power;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BatteryExperimentStore store;
    BatteryExperimentEngine engine;
    private final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final PowerManager.WakeLock wake;
    private boolean monitoring;
    private boolean plugged = true;
    private Runnable timeout;
    private PendingIntent pendingAlarm;
    private final Runnable status = () -> safely(() -> engine.statusChanged());
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (p, key) -> {
        if (BatteryExperimentHooks.isChangingTailscale()) return;
        if (relevantPreference(key)) safely(() -> engine.abort("settings_changed", key == null || "ts_enabled".equals(key)));
    };
    private final BroadcastReceiver environment = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            safely(() -> {
                if (!active()) return;
                if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) plugged = true;
                if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) plugged = false;
                if (engine.run.stage == BatteryExperimentRun.Stage.ARMED) engine.environmentReady();
                else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) engine.abort("screen_on", false);
                else if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) engine.abort("charging", false);
            });
        }
    };

    public static void initialize(Context context) {
        if (instance == null || instance.context != context.getApplicationContext()) {
            instance = new BatteryExperimentController(context.getApplicationContext());
            instance.recover();
        }
    }
    static BatteryExperimentController get(Context context) { initialize(context); return instance; }

    public static void configurePreference(AppCompatActivity activity, PreferenceScreen screen) {
        PreferenceScreen tailscale = screen.findPreference("screen_tailscale");
        if (tailscale == null || tailscale.findPreference("battery_experiment") != null) return;
        Preference experiment = new Preference(activity);
        experiment.setKey("battery_experiment");
        experiment.setTitle(net.kollnig.missioncontrol.R.string.battery_experiment_title);
        experiment.setOnPreferenceClickListener(preference -> {
            activity.startActivity(new Intent(activity, BatteryExperimentActivity.class));
            return true;
        });
        tailscale.addPreference(experiment);
    }

    private BatteryExperimentController(Context context) {
        this.context = context;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackerControl:BatteryExperimentTransition");
        wake.setReferenceCounted(false);
        store = new BatteryExperimentStore(context);
        engine = new BatteryExperimentEngine(this);
    }

    private void recover() {
        List<BatteryExperimentRun> runs = store.history();
        if (!runs.isEmpty()) safely(() -> engine.recover(runs.get(0)));
    }

    boolean canSchedule() { return Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(); }
    boolean active() { return engine.run != null && engine.run.active(); }
    List<BatteryExperimentRun> history() { return store.history(); }
    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    void arm(boolean reverse, int hours) {
        if (!canSchedule()) throw new IllegalStateException("alarm_permission");
        if (prefs.getBoolean("wg_enabled", false) || prefs.getBoolean("socks5_enabled", false))
            throw new IllegalStateException("exclusive_egress");
        Intent battery = battery();
        plugged = battery == null || battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
        engine.arm(reverse, hours);
        try {
            engine.run.settings = new JSONObject().put("doh_enabled", prefs.getBoolean("doh_enabled", false))
                    .put("ip6", prefs.getBoolean("ip6", true))
                    .put("blocking_mode", prefs.getString("blocking_mode", ""));
        } catch (JSONException ex) { throw new IllegalStateException(ex); }
        save(engine.run);
        monitor(true);
        engine.environmentReady();
    }
    void stop() { safely(() -> engine.abort("cancelled", false)); }
    void alarm(String id, int token) { safely(() -> engine.alarm(id, token)); }

    private void monitor(boolean enabled) {
        if (monitoring == enabled) return;
        monitoring = enabled;
        if (enabled) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_POWER_CONNECTED); filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            ContextCompat.registerReceiver(context, environment, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
            TailscaleManager.addListener(status);
            BatteryExperimentHooks.setObserver(new BatteryExperimentHooks.Observer() {
                @Override public void onVpnStateChanged(boolean ready) {
                    boolean transitionEvent = active() && engine.run.stage == BatteryExperimentRun.Stage.SWITCHING;
                    main.post(() -> safely(() -> {
                        if (!active()) return;
                        if (!ready && !transitionEvent)
                            engine.protectionInterrupted();
                        else if (ready) engine.statusChanged();
                    }));
                }
                @Override public void onNetworkChanged() {
                    main.post(() -> safely(() -> engine.abort("network_changed", false)));
                }
            });
        } else {
            context.unregisterReceiver(environment);
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
            TailscaleManager.removeListener(status);
            BatteryExperimentHooks.setObserver(null);
        }
    }

    private static boolean relevantPreference(String key) {
        return key == null || key.equals("enabled") || key.equals("ts_enabled") || key.equals("blocking_mode")
                || key.startsWith("doh_") || key.startsWith("wg_") || key.startsWith("socks5_")
                || key.startsWith("dns") || key.equals("ip6") || key.equals("filter")
                || key.equals("sni_enabled") || key.equals("screen_on") || key.equals("tcp_mss_clamp");
    }

    @Override public long elapsed() { return SystemClock.elapsedRealtime(); }
    @Override public long wall() { return System.currentTimeMillis(); }
    @Override public boolean ready(boolean tailscale) {
        if (!prefs.getBoolean("enabled", false) || !BatteryExperimentHooks.isVpnReady()
                || prefs.getBoolean("ts_enabled", false) != tailscale) return false;
        TailscaleSnapshot state = TailscaleManager.snapshot();
        return tailscale ? state.state.equals("Running") && state.error.isEmpty() && TailscaleManager.isBridgeAttached()
                : state.state.equals("Stopped") && !TailscaleManager.isBridgeAttached();
    }
    @Override public boolean failed() {
        TailscaleSnapshot state = TailscaleManager.snapshot();
        return !state.error.isEmpty() || state.state.equals("NeedsLogin") || state.state.equals("NeedsMachineAuth");
    }
    private Intent battery() { return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }
    @Override public boolean quiet() {
        // Power broadcasts establish the new state even if the battery sticky intent lags.
        // Actual boundary samples independently check EXTRA_PLUGGED again.
        return !power.isInteractive() && !plugged;
    }
    @Override public BatteryExperimentRun.Sample sample() {
        BatteryExperimentRun.Sample s = new BatteryExperimentRun.Sample();
        s.wall = wall(); s.elapsed = elapsed();
        Intent b = battery();
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0 && level <= scale) s.level = level * 100d / scale;
            if (b.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) s.temperature = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10d;
            s.charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
        } else s.charging = true;
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        long charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (charge > 0) s.charge = charge;
        s.protection = prefs.getBoolean("enabled", false) && BatteryExperimentHooks.isVpnReady();
        s.tailscale = prefs.getBoolean("ts_enabled", false);
        String state = TailscaleManager.snapshot().state;
        s.state = state.equals("Running") || state.equals("Stopped") ? state : "Unavailable";
        s.bridge = TailscaleManager.isBridgeAttached();
        return s;
    }

    @Override public void save(BatteryExperimentRun run) {
        store.save(run);
        if (!run.active()) monitor(false);
        for (Runnable listener : listeners) listener.run();
    }
    private PendingIntent alarmIntent(String id, int token) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, BatteryExperimentAlarmReceiver.class)
                .setData(Uri.parse("trackercontrol-battery:" + id + ":" + token))
                .putExtra("id", id).putExtra("token", token), PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void schedule(BatteryExperimentRun run) {
        cancelAlarm();
        String id = run.id; int token = run.token;
        if (run.stage == BatteryExperimentRun.Stage.SWITCHING) {
            // The bounded transition wake lock keeps this Handler timeout runnable.
            timeout = () -> alarm(id, token);
            main.postDelayed(timeout, Math.max(0, run.due - elapsed()));
        } else {
            if (!canSchedule()) throw new IllegalStateException("alarm_permission");
            pendingAlarm = alarmIntent(id, token);
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, run.due, pendingAlarm);
        }
    }
    @Override public void cancelAlarm() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        PendingIntent pending = pendingAlarm;
        if (pending == null && engine.run != null) pending = alarmIntent(engine.run.id, engine.run.token);
        if (pending != null) { alarms.cancel(pending); pending.cancel(); }
        pendingAlarm = null;
    }
    @Override public void transitionWake(boolean acquire) {
        if (acquire) wake.acquire(BatteryExperimentEngine.MINUTE);
        else if (wake.isHeld()) wake.release();
    }
    @Override public boolean setTailscale(boolean enabled) {
        if (prefs.getBoolean("ts_enabled", false) == enabled) return true;
        BatteryExperimentHooks.setChangingTailscale(true);
        try {
            if (!prefs.edit().putBoolean("ts_enabled", enabled).commit()) return false;
        } finally { BatteryExperimentHooks.setChangingTailscale(false); }
        ServiceSinkhole.reload("debug battery experiment", context, false);
        return true;
    }
    @Override public void restore(BatteryExperimentRun run) {
        if (run.userChangedTailscale || !prefs.getBoolean("enabled", false)
                || prefs.getBoolean("ts_enabled", false) != run.lastWrittenTailscale
                || prefs.getBoolean("wg_enabled", false) || prefs.getBoolean("socks5_enabled", false)) {
            run.restoration = "skipped_user_change";
            run.restorePending = false;
        } else if (setTailscale(run.originalTailscale)) {
            run.restoration = "requested";
            run.restorePending = false;
        } else run.restoration = "pending";
    }

    private void safely(Runnable action) {
        try { action.run(); }
        catch (RuntimeException ex) {
            // Never leave a failed alarm or a permission revocation looking like a valid measurement.
            try { engine.abort(canSchedule() ? "internal_error" : "alarm_permission", false); }
            catch (RuntimeException ignored) { /* Storage can fail during abort as well. Restore below. */ }
            finally {
                cancelAlarm(); transitionWake(false); if (monitoring) monitor(false);
                BatteryExperimentRun run = engine.run;
                if (run != null && run.restorePending) {
                    try { restore(run); store.save(run); }
                    catch (RuntimeException ignored) { /* Persisted recovery is retried on the next start. */ }
                }
            }
        }
    }
}
