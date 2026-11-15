package net.kollnig.missioncontrol.tailscale;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.SwitchPreference;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.ui.compose.TrackerControlThemeKt;
import net.kollnig.missioncontrol.vpn.VpnEgressPolicy;

/** Settings actions; engine/authentication work stays off the UI thread. */
public final class TailscaleSettings {
    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private PreferenceScreen screen;
    private boolean awaitingLogin;

    public TailscaleSettings(AppCompatActivity activity, SharedPreferences prefs) {
        this.activity = activity;
        this.prefs = prefs;
    }

    public boolean canOrConfirmEnable(String key, Runnable onConfirmed) {
        if (!VpnEgressPolicy.canEnable(prefs, key)) {
            toast(R.string.msg_ts_exclusive);
            return false;
        }
        if ("ts_enabled".equals(key)) {
            TrackerControlThemeKt.themedDialogBuilder(activity)
                    .setTitle(R.string.setting_ts_title)
                    .setMessage(R.string.summary_ts_enabled)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (VpnEgressPolicy.canEnable(prefs, key)) {
                            onConfirmed.run();
                        } else {
                            toast(R.string.msg_ts_exclusive);
                        }
                    }).setNegativeButton(android.R.string.cancel, null).show();
            return false;
        }
        return true;
    }

    public void requestLogin() {
        if (!prefs.getBoolean("enabled", false)) {
            toast(R.string.msg_ts_start_protection);
            return;
        }
        awaitingLogin = true;
        if (!openLogin()) ServiceSinkhole.tailscaleAuthentication(activity, false);
    }

    public void requestLogout() {
        TrackerControlThemeKt.themedDialogBuilder(activity)
                .setTitle(R.string.setting_ts_logout)
                .setMessage(R.string.msg_ts_logout_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    awaitingLogin = false;
                    ServiceSinkhole.tailscaleAuthentication(activity, true);
                }).show();
    }

    public void onStatusChanged() {
        TailscaleSnapshot state = TailscaleManager.snapshot();
        boolean enabled = prefs.getBoolean("ts_enabled", false);
        if (!enabled || !prefs.getBoolean("enabled", false) || !state.error.isEmpty()
                || "Running".equals(state.state)) {
            awaitingLogin = false;
        }
        if (awaitingLogin) openLogin();
    }

    public static String buildSummary(
            Context context,
            SharedPreferences prefs,
            TailscaleSnapshot state) {
        boolean enabled = prefs.getBoolean("ts_enabled", false);
        int status = R.string.ts_state_waiting;
        if (!enabled) status = R.string.ts_state_off;
        else if (!state.error.isEmpty()) status = R.string.ts_state_error;
        else if ("Running".equals(state.state)) status = R.string.ts_state_connected;
        else if ("NeedsLogin".equals(state.state)) status = R.string.ts_state_login;
        else if ("NeedsMachineAuth".equals(state.state)) status = R.string.ts_state_approval;
        String summary = context.getString(status);
        if (enabled && !state.error.isEmpty()) summary += "\n" + state.error;
        if (enabled && !state.ipv4.isEmpty()) summary += "\n" + state.ipv4;
        if (enabled && !state.ipv6.isEmpty()) summary += "\n" + state.ipv6;
        if (enabled && "Running".equals(state.state) && !state.magicDNS)
            summary += "\n" + context.getString(R.string.ts_magicdns_off);
        return summary;
    }

    public static boolean isLoginEnabled(SharedPreferences prefs, TailscaleSnapshot state) {
        return prefs.getBoolean("ts_enabled", false) && !"Running".equals(state.state);
    }

    public void configure(PreferenceScreen screen) {
        this.screen = screen;
        net.kollnig.missioncontrol.diagnostics.BatteryExperimentHooks.configurePreference(activity, screen);
        for (String key : new String[]{"ts_enabled", "wg_enabled", "socks5_enabled"}) {
            Preference pref = screen.findPreference(key);
            if (pref == null) continue;
            pref.setOnPreferenceChangeListener((preference, next) -> {
                if (!Boolean.TRUE.equals(next)) return true;
                return canOrConfirmEnable(key, () -> ((SwitchPreference) preference).setChecked(true));
            });
        }
        Preference login = screen.findPreference("ts_login");
        if (login != null) login.setOnPreferenceClickListener(preference -> {
            requestLogin();
            return true;
        });
        Preference logout = screen.findPreference("ts_logout");
        if (logout != null) logout.setOnPreferenceClickListener(preference -> {
            requestLogout();
            return true;
        });
        update();
    }

    public void update() {
        if (screen == null) return;
        onStatusChanged();
        TailscaleSnapshot state = TailscaleManager.snapshot();
        String summary = buildSummary(activity, prefs, state);
        for (String key : new String[]{"ts_status", "screen_tailscale"}) {
            Preference preference = screen.findPreference(key);
            if (preference != null) preference.setSummary(summary);
        }
        Preference login = screen.findPreference("ts_login");
        if (login != null) login.setEnabled(isLoginEnabled(prefs, state));
    }

    private boolean openLogin() {
        Uri uri = Uri.parse(TailscaleManager.loginUrl());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.equals("tailscale.com") || host.endsWith(".tailscale.com"))) return false;
        awaitingLogin = false;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (RuntimeException ex) { toast(R.string.msg_ts_browser_failed); }
        return true;
    }

    public void cancelPendingLogin() { awaitingLogin = false; }

    private void toast(int message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
