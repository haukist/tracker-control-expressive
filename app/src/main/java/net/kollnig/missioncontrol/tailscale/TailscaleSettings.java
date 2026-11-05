package net.kollnig.missioncontrol.tailscale;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.SwitchPreference;
import net.kollnig.missioncontrol.R;

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

    public void configure(PreferenceScreen screen) {
        this.screen = screen;
        for (String key : new String[]{"ts_enabled", "wg_enabled", "socks5_enabled"}) {
            Preference pref = screen.findPreference(key);
            if (pref == null) continue;
            pref.setOnPreferenceChangeListener((preference, next) -> {
                if (!Boolean.TRUE.equals(next)) return true;
                if (!net.kollnig.missioncontrol.vpn.VpnEgressPolicy.canEnable(prefs, key)) {
                    toast(R.string.msg_ts_exclusive);
                    return false;
                }
                if (key.equals("ts_enabled")) {
                    new AlertDialog.Builder(activity).setTitle(R.string.setting_ts_title)
                            .setMessage(R.string.summary_ts_enabled)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                if (net.kollnig.missioncontrol.vpn.VpnEgressPolicy.canEnable(prefs, key))
                                    ((SwitchPreference) preference).setChecked(true);
                                else
                                    toast(R.string.msg_ts_exclusive);
                            }).setNegativeButton(android.R.string.cancel, null).show();
                    return false;
                }
                return true;
            });
        }
        Preference login = screen.findPreference("ts_login");
        if (login != null) login.setOnPreferenceClickListener(preference -> {
            if (!prefs.getBoolean("enabled", false)) { toast(R.string.msg_ts_start_protection); return true; }
            awaitingLogin = true;
            if (!openLogin()) ServiceSinkhole.tailscaleAuthentication(activity, false);
            return true;
        });
        Preference logout = screen.findPreference("ts_logout");
        if (logout != null) logout.setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(activity).setTitle(R.string.setting_ts_logout)
                    .setMessage(R.string.msg_ts_logout_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        awaitingLogin = false;
                        if (prefs.getBoolean("enabled", false))
                            ServiceSinkhole.tailscaleAuthentication(activity, true);
                        else
                            new Thread(() -> {
                                try { TailscaleManager.logout(activity.getApplicationContext()); }
                                catch (Exception ex) { activity.runOnUiThread(() -> toast(R.string.msg_ts_logout_failed)); }
                            }, "Tailscale logout").start();
                    }).show();
            return true;
        });
        update();
    }

    public void update() {
        if (screen == null) return;
        TailscaleSnapshot state = TailscaleManager.snapshot();
        boolean enabled = prefs.getBoolean("ts_enabled", false);
        int status = R.string.ts_state_waiting;
        if (!enabled) status = R.string.ts_state_off;
        else if (!state.error.isEmpty()) status = R.string.ts_state_error;
        else if (state.state.equals("Running")) status = R.string.ts_state_connected;
        else if (state.state.equals("NeedsLogin")) status = R.string.ts_state_login;
        else if (state.state.equals("NeedsMachineAuth")) status = R.string.ts_state_approval;
        String summary = activity.getString(status);
        if (enabled && !state.error.isEmpty()) summary += "\n" + state.error;
        if (enabled && !state.ipv4.isEmpty()) summary += "\n" + state.ipv4;
        if (enabled && !state.ipv6.isEmpty()) summary += "\n" + state.ipv6;
        if (enabled && state.state.equals("Running") && !state.magicDNS)
            summary += "\n" + activity.getString(R.string.ts_magicdns_off);
        for (String key : new String[]{"ts_status", "screen_tailscale"}) {
            Preference preference = screen.findPreference(key);
            if (preference != null) preference.setSummary(summary);
        }
        Preference login = screen.findPreference("ts_login");
        if (login != null) login.setEnabled(enabled && !state.state.equals("Running"));
        if (awaitingLogin) openLogin();
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

    private void toast(int message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
