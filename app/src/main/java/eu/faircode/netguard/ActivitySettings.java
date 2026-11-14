/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 Marcel Bokhorst (M66B)
 * Copyright © 2019–2026 Konrad Kollnig
 */

package eu.faircode.netguard;

import static net.kollnig.missioncontrol.data.TrackerBlocklist.PREF_BLOCKLIST;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NavUtils;
import androidx.core.graphics.Insets;
import androidx.core.util.PatternsCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.LocalNetworkAccess;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PackageUids;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.wg.WgProfileManager;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import androidx.activity.OnBackPressedCallback;
import androidx.compose.ui.platform.ComposeView;
import net.kollnig.missioncontrol.tailscale.TailscaleManager;
import net.kollnig.missioncontrol.tailscale.TailscaleSnapshot;
import net.kollnig.missioncontrol.ui.compose.SettingsItem;
import net.kollnig.missioncontrol.ui.compose.SettingsPage;
import net.kollnig.missioncontrol.ui.compose.SettingsScreen;
import net.kollnig.missioncontrol.ui.compose.SettingsScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.SettingsScreenController;
import net.kollnig.missioncontrol.ui.compose.SettingsScreenModel;
import net.kollnig.missioncontrol.ui.compose.SettingsSection;
import net.kollnig.missioncontrol.ui.compose.TrackerControlThemeKt;
import net.kollnig.missioncontrol.vpn.VpnEgressPolicy;
import kotlin.Pair;

public class ActivitySettings extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "TrackerControl.Settings";

    private boolean running = false;
    private SettingsScreenController screenController;
    private SettingsPage currentPage = SettingsPage.ROOT;
    private boolean awaitingTailscaleLogin = false;

    private final Handler wgStatusHandler = new Handler(Looper.getMainLooper());
    private final Runnable tailscaleStatusListener = () -> wgStatusHandler.post(() -> {
        if (running) renderSettings();
    });
    private final Runnable wgStatusListener = () -> wgStatusHandler.post(() -> {
        if (running) renderSettings();
    });

    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_CALL = 5;
    private static final int REQUEST_LOCAL_NETWORK = 6;

    private boolean requestedLocalNetwork = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppThemeRed_NoActionBar);
        super.onCreate(savedInstanceState);

        ComposeView composeView = new ComposeView(this);
        setContentView(composeView);
        running = true;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        BlockingMode.enforcePlayStoreMode(this);
        new WgProfileManager(this).migrateIfNeeded();

        screenController = SettingsScreen.install(composeView, new SettingsScreenCallbacks() {
            @Override
            public void onNavigateUp() {
                if (currentPage != SettingsPage.ROOT) {
                    currentPage = SettingsPage.ROOT;
                    renderSettings();
                } else {
                    NavUtils.navigateUpFromSameTask(ActivitySettings.this);
                }
            }

            @Override
            public void onOpenPage(@NonNull SettingsPage page) {
                currentPage = page;
                renderSettings();
            }

            @Override
            public void onToggleSwitch(@NonNull String key, boolean checked) {
                handleSwitchToggle(key, checked, prefs);
            }

            @Override
            public void onSelectListOption(@NonNull String key, @NonNull String value) {
                handleListSelection(key, value, prefs);
            }

            @Override
            public void onSubmitTextInput(@NonNull String key, @NonNull String value) {
                handleTextInput(key, value, prefs);
            }

            @Override
            public void onActionClick(@NonNull String key) {
                handleActionClick(key);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentPage != SettingsPage.ROOT) {
                    currentPage = SettingsPage.ROOT;
                    renderSettings();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        WorkManager.getInstance(this).getWorkInfosByTagLiveData("ManualHostsDownload").observe(this, workInfos -> {
            if (running) renderSettings();
        });

        renderSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissions(null);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        net.kollnig.missioncontrol.wg.WgEgress.INSTANCE.addStateListener(wgStatusListener);
        TailscaleManager.addListener(tailscaleStatusListener);
        new WgProfileManager(this).migrateIfNeeded();
        renderSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        awaitingTailscaleLogin = false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        net.kollnig.missioncontrol.wg.WgEgress.INSTANCE.removeStateListener(wgStatusListener);
        TailscaleManager.removeListener(tailscaleStatusListener);
    }

    @Override
    protected void onDestroy() {
        running = false;
        wgStatusHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void handleSwitchToggle(String key, boolean checked, SharedPreferences prefs) {
        if (checked && ("ts_enabled".equals(key) || "wg_enabled".equals(key) || "socks5_enabled".equals(key))) {
            if (!VpnEgressPolicy.canEnable(prefs, key)) {
                Toast.makeText(this, R.string.msg_ts_exclusive, Toast.LENGTH_LONG).show();
                renderSettings();
                return;
            }
            if ("ts_enabled".equals(key)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.setting_ts_title)
                        .setMessage(R.string.summary_ts_enabled)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            if (VpnEgressPolicy.canEnable(prefs, key)) {
                                prefs.edit().putBoolean(key, true).apply();
                            } else {
                                Toast.makeText(this, R.string.msg_ts_exclusive, Toast.LENGTH_LONG).show();
                            }
                            renderSettings();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }
        if ("hosts_auto_update".equals(key)) {
            HostsDownloadWorker.scheduleAutoUpdate(this, checked);
        }
        prefs.edit().putBoolean(key, checked).apply();
        renderSettings();
    }

    private void handleListSelection(String key, String value, SharedPreferences prefs) {
        if (WgProfileManager.PREF_WG_PROFILE.equals(key)) {
            WgProfileManager manager = new WgProfileManager(this);
            manager.setActiveProfile(value);
            ServiceSinkhole.reload("changed " + WgProfileManager.PREF_WG_PROFILE, this, false);
            renderSettings();
            return;
        }
        prefs.edit().putString(key, value).apply();
        renderSettings();
    }

    private void handleTextInput(String key, String value, SharedPreferences prefs) {
        if ("doh_endpoint".equals(key)) {
            String endpoint = value.trim();
            if (!isValidDohEndpoint(endpoint)) {
                Toast.makeText(this, R.string.msg_invalid_doh_endpoint, Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString(key, endpoint).apply();
            renderSettings();
            return;
        }
        if (TextUtils.isEmpty(value))
            prefs.edit().remove(key).apply();
        else
            prefs.edit().putString(key, value).apply();
        renderSettings();
    }

    private void handleActionClick(String key) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        switch (key) {
            case "export":
                try {
                    startActivityForResult(getIntentCreateExport(), REQUEST_EXPORT);
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(this, R.string.msg_no_file_manager, Toast.LENGTH_LONG).show();
                }
                break;
            case "import":
                try {
                    startActivityForResult(getIntentOpenExport(), REQUEST_IMPORT);
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(this, R.string.msg_no_file_manager, Toast.LENGTH_LONG).show();
                }
                break;
            case "manage_blocklists":
                startActivity(new Intent(this, net.kollnig.missioncontrol.ActivityBlocklists.class));
                break;
            case "hosts_download": {
                Constraints constraints = new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build();
                OneTimeWorkRequest downloadWork = new OneTimeWorkRequest.Builder(HostsDownloadWorker.class)
                        .setConstraints(constraints)
                        .addTag("ManualHostsDownload")
                        .build();
                WorkManager.getInstance(this).enqueue(downloadWork);
                break;
            }
            case "exclude_all_tracking":
                Util.areYouSure(this, R.string.summary_exclude_all_tracking, () -> setTrackerProtectionForAll(false));
                break;
            case "include_all_tracking":
                Util.areYouSure(this, R.string.summary_include_all_tracking, () -> setTrackerProtectionForAll(true));
                break;
            case "forwarding":
                startActivity(new Intent(this, ActivityForwarding.class));
                break;
            case "show_resolved":
                startActivity(new Intent(this, ActivityDns.class));
                break;
            case "wg_profile_manage":
                startActivity(new Intent(this, net.kollnig.missioncontrol.ActivityWireGuardProfiles.class));
                break;
            case "ts_login":
                if (!prefs.getBoolean("enabled", false)) {
                    Toast.makeText(this, R.string.msg_ts_start_protection, Toast.LENGTH_LONG).show();
                    return;
                }
                awaitingTailscaleLogin = true;
                if (!openTailscaleLogin())
                    ServiceSinkhole.tailscaleAuthentication(this, false);
                break;
            case "ts_logout":
                new AlertDialog.Builder(this)
                        .setTitle(R.string.setting_ts_logout)
                        .setMessage(R.string.msg_ts_logout_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            awaitingTailscaleLogin = false;
                            ServiceSinkhole.tailscaleAuthentication(this, true);
                        })
                        .show();
                break;
            case "vpn_key_rotate_now":
                Toast.makeText(this, R.string.msg_vpn_key_rotation_started, Toast.LENGTH_LONG).show();
                net.kollnig.missioncontrol.wg.VpnKeyRotationManager.rotateAllForDebug(
                        this,
                        summary -> wgStatusHandler.post(() ->
                                Toast.makeText(this, summary, Toast.LENGTH_LONG).show()));
                break;
        }
    }

    private boolean openTailscaleLogin() {
        Uri uri = Uri.parse(TailscaleManager.loginUrl());
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.equals("tailscale.com") || host.endsWith(".tailscale.com")))
            return false;
        awaitingTailscaleLogin = false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (RuntimeException ex) {
            Toast.makeText(this, R.string.msg_ts_browser_failed, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        Object value = prefs.getAll().get(name);
        if (value instanceof String && "".equals(value))
            prefs.edit().remove(name).apply();

        if ("ip6".equals(name) || "use_metered".equals(name) || "wifi_only".equals(name)
                || "screen_delay".equals(name) || "tcp_mss_clamp".equals(name)
                || "notify_access".equals(name) || "use_hosts".equals(name)
                || "rcode".equals(name) || "socks5_enabled".equals(name)
                || "socks5_port".equals(name) || "socks5_username".equals(name)
                || "socks5_password".equals(name) || "wg_enabled".equals(name)
                || "wg_keepalive_when_screen_off".equals(name) || "loglevel".equals(name)) {
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("include_system_vpn".equals(name)) {
            boolean monitorSystem = prefs.getBoolean(name, false);
            prefs.edit().putBoolean("manage_system", monitorSystem).apply();
            prefs.edit().putBoolean("show_system", monitorSystem).apply();
            if (!monitorSystem)
                prefs.edit().putBoolean("show_user", true).apply();
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("disable_on_call".equals(name)) {
            if (!prefs.getBoolean(name, false) || checkPermissions(name))
                ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("blocking_mode".equals(name)) {
            if (Util.isPlayStoreInstall(this))
                BlockingMode.enforcePlayStoreMode(this);
            BlockingMode.applyMode(this);
        } else if ("log_logcat".equals(name)) {
            boolean research = prefs.getBoolean(name, false);
            if (prefs.getBoolean("sni_enabled", false) != research)
                prefs.edit().putBoolean("sni_enabled", research).apply();
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("sni_enabled".equals(name)) {
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("manage_system".equals(name)) {
            boolean manage = prefs.getBoolean(name, false);
            if (!manage)
                prefs.edit().putBoolean("show_user", true).apply();
            prefs.edit().putBoolean("show_system", manage).apply();
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("log_app".equals(name)) {
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ActivityMain.ACTION_RULES_CHANGED));
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("vpn4".equals(name) || "vpn6".equals(name)) {
            String addr = prefs.getString(name, null);
            try {
                checkAddress(addr, false);
                prefs.edit().putString(name, addr.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                if (!TextUtils.isEmpty(addr))
                    Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("dns".equals(name) || "dns2".equals(name)) {
            String dns = prefs.getString(name, null);
            try {
                checkAddress(dns, true);
                prefs.edit().putString(name, dns.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                if (!TextUtils.isEmpty(dns))
                    Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("doh_enabled".equals(name) || "doh_endpoint".equals(name)) {
            net.kollnig.missioncontrol.dns.DnsOverHttpsClient.resetInstance();
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("validate".equals(name)) {
            String host = prefs.getString(name, "www.f-droid.org");
            try {
                checkDomain(host);
                prefs.edit().putString(name, host.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                if (!TextUtils.isEmpty(host))
                    Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("socks5_addr".equals(name)) {
            String addr = prefs.getString(name, null);
            if (!TextUtils.isEmpty(addr) && !Util.isNumericAddress(addr)) {
                prefs.edit().remove(name).apply();
                Toast.makeText(this, "Bad address", Toast.LENGTH_LONG).show();
            }
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("ts_enabled".equals(name)) {
            if (!net.kollnig.missioncontrol.diagnostics.BatteryExperimentHooks.isChangingTailscale())
                ServiceSinkhole.reload("changed " + name, this, false);
        } else if (Rule.PREF_WG_ROUTE_MODE.equals(name)) {
            Rule.clearCache(this);
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("wg_config".equals(name)) {
            String wg_config = prefs.getString(name, null);
            boolean valid = true;
            if (!TextUtils.isEmpty(wg_config)) {
                try {
                    net.kollnig.missioncontrol.wg.WgConfigParser.INSTANCE.parse(wg_config);
                } catch (Throwable ex) {
                    valid = false;
                    Toast.makeText(this, getString(R.string.msg_wg_config_invalid, ex.getMessage()), Toast.LENGTH_LONG).show();
                    prefs.edit().putBoolean("wg_enabled", false).apply();
                }
            }
            if (valid)
                new WgProfileManager(this).updateActiveProfileConfig(wg_config);
            ServiceSinkhole.reload("changed " + name, this, false);
        } else if ("pcap_record_size".equals(name) || "pcap_file_size".equals(name)) {
            ServiceSinkhole.setPcap(false, this);
            File pcap_file = new File(getDir("data", MODE_PRIVATE), "netguard.pcap");
            if (pcap_file.exists() && !pcap_file.delete())
                Log.w(TAG, "Delete PCAP failed");
            if (prefs.getBoolean("pcap", false))
                ServiceSinkhole.setPcap(true, this);
        } else if ("show_stats".equals(name)) {
            ServiceSinkhole.reloadStats("changed " + name, this);
        } else if ("domain_based_blocking".equals(name)) {
            TrackerList.reloadTrackerData(this);
            ServiceSinkhole.clearTrackerCaches();
        }

        if (LocalNetworkAccess.isRelevantSetting(name))
            LocalNetworkAccess.forgetObservations();

        if (!requestedLocalNetwork && LocalNetworkAccess.isRelevantSetting(name)
                && LocalNetworkAccess.isMissing(this)) {
            requestedLocalNetwork = true;
            requestPermissions(new String[]{LocalNetworkAccess.PERMISSION}, REQUEST_LOCAL_NETWORK);
        }

        renderSettings();
    }

    private void renderSettings() {
        if (!running || screenController == null)
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        List<SettingsSection> sections = new ArrayList<>();
        String title;

        switch (currentPage) {
            case ADVANCED:
                title = getString(R.string.setting_advanced_options);
                sections.add(buildAdvancedSection(prefs));
                break;
            case WIREGUARD:
                title = getString(R.string.setting_wireguard);
                sections.add(buildWireGuardSection(prefs));
                break;
            case TAILSCALE:
                title = getString(R.string.setting_ts_title);
                sections.add(buildTailscaleSection(prefs));
                break;
            case DEVELOPMENT:
                title = "Development";
                sections.add(buildDevelopmentSection(prefs));
                break;
            case ROOT:
            default:
                title = getString(R.string.menu_settings);
                sections.add(buildOptionsSection(prefs));
                sections.add(buildFurtherOptionsSection(prefs));
                sections.add(buildBackupSection());
                break;
        }

        screenController.update(new SettingsScreenModel(currentPage, title, sections));
    }

    private SettingsSection buildOptionsSection(SharedPreferences prefs) {
        List<SettingsItem> items = new ArrayList<>();
        String mode = BlockingMode.getMode(this);
        String modeSummary = getBlockingModeSummary(mode);
        List<Pair<String, String>> modeEntries = new ArrayList<>();
        if (Util.isPlayStoreInstall(this)) {
            modeEntries.add(new Pair<>(getString(R.string.blocking_mode_minimal), BlockingMode.MODE_MINIMAL));
        } else {
            String[] names = getResources().getStringArray(R.array.blockingModeNames);
            String[] values = getResources().getStringArray(R.array.blockingModeValues);
            for (int i = 0; i < Math.min(names.length, values.length); i++)
                modeEntries.add(new Pair<>(names[i], values[i]));
        }
        items.add(new SettingsItem.ListItem(
                "blocking_mode",
                getString(R.string.setting_blocking_mode),
                modeSummary,
                mode,
                modeEntries,
                !Util.isPlayStoreInstall(this)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items.add(new SettingsItem.SwitchItem(
                    TrackerControlThemeKt.PREF_DYNAMIC_COLOR,
                    getString(R.string.setting_dynamic_color),
                    getString(R.string.summary_dynamic_color),
                    prefs.getBoolean(TrackerControlThemeKt.PREF_DYNAMIC_COLOR, false),
                    true));
        }

        items.add(new SettingsItem.SwitchItem(
                "log_logcat",
                getString(R.string.setting_log_logcat),
                getResearchModeSummaryText(prefs),
                prefs.getBoolean("log_logcat", false),
                true));

        items.add(new SettingsItem.SwitchItem(
                "disable_on_call",
                getString(R.string.setting_call),
                getString(R.string.summary_call),
                prefs.getBoolean("disable_on_call", false),
                true));

        return new SettingsSection(getString(R.string.setting_options), items);
    }

    private SettingsSection buildFurtherOptionsSection(SharedPreferences prefs) {
        List<SettingsItem> items = new ArrayList<>();
        items.add(new SettingsItem.NavigationItem(
                "screen_advanced_options",
                SettingsPage.ADVANCED,
                getString(R.string.setting_advanced_options),
                null,
                true));
        items.add(new SettingsItem.NavigationItem(
                "screen_wireguard",
                SettingsPage.WIREGUARD,
                getString(R.string.setting_wireguard),
                getWireGuardStatusSummary(prefs).toString(),
                true));
        items.add(new SettingsItem.NavigationItem(
                "screen_tailscale",
                SettingsPage.TAILSCALE,
                getString(R.string.setting_ts_title),
                getTailscaleSummary(prefs),
                true));
        if (Util.isDebuggable(this)) {
            items.add(new SettingsItem.NavigationItem(
                    "screen_development",
                    SettingsPage.DEVELOPMENT,
                    "Development",
                    null,
                    true));
        }
        return new SettingsSection(getString(R.string.setting_further_options), items);
    }

    private SettingsSection buildBackupSection() {
        List<SettingsItem> items = new ArrayList<>();
        items.add(new SettingsItem.ActionItem("export", getString(R.string.setting_export), null, true));
        items.add(new SettingsItem.ActionItem("import", getString(R.string.setting_import), null, true));
        return new SettingsSection(getString(R.string.setting_backup), items);
    }

    private SettingsSection buildAdvancedSection(SharedPreferences prefs) {
        List<SettingsItem> items = new ArrayList<>();
        boolean playStore = Util.isPlayStoreInstall(this);

        items.add(new SettingsItem.TextInputItem(
                "pause",
                getString(R.string.setting_pause, prefs.getString("pause", "10")),
                getString(R.string.summary_pause),
                prefs.getString("pause", "10"),
                false,
                true));

        if (!Util.isFDroidInstall() && !playStore) {
            items.add(new SettingsItem.SwitchItem(
                    "update_check",
                    getString(R.string.setting_update),
                    getString(R.string.summary_update),
                    prefs.getBoolean("update_check", true),
                    true));
        }

        boolean reloadChecked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || prefs.getBoolean("reload_onconnectivity", true);
        items.add(new SettingsItem.SwitchItem(
                "reload_onconnectivity",
                getString(R.string.setting_reload_onconnectivity),
                null,
                reloadChecked,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O));

        items.add(new SettingsItem.SwitchItem(
                "tcp_mss_clamp",
                getString(R.string.setting_tcp_mss_clamp),
                getString(R.string.summary_tcp_mss_clamp),
                prefs.getBoolean("tcp_mss_clamp", true),
                true));

        items.add(new SettingsItem.SwitchItem(
                "doh_enabled",
                getString(R.string.setting_doh_enabled),
                getString(R.string.summary_doh_enabled),
                prefs.getBoolean("doh_enabled", false),
                true));

        items.add(new SettingsItem.TextInputItem(
                "doh_endpoint",
                getString(R.string.setting_doh_endpoint),
                prefs.getString("doh_endpoint", BuildConfig.DEFAULT_DOH_ENDPOINT),
                prefs.getString("doh_endpoint", BuildConfig.DEFAULT_DOH_ENDPOINT),
                false,
                true));

        items.add(new SettingsItem.SwitchItem(
                "doh_dns_fallback",
                getString(R.string.setting_doh_dns_fallback),
                getString(R.string.summary_doh_dns_fallback),
                prefs.getBoolean("doh_dns_fallback", false),
                true));

        items.add(new SettingsItem.SwitchItem(
                "block_dot",
                getString(R.string.setting_block_dot),
                getString(R.string.summary_block_dot),
                prefs.getBoolean("block_dot", true),
                true));

        if (!playStore) {
            items.add(new SettingsItem.SwitchItem(
                    "domain_based_blocking",
                    getString(R.string.setting_domain_based_blocked),
                    getString(R.string.summary_domain_based_blocking),
                    prefs.getBoolean("domain_based_blocking", false),
                    true));
        }

        items.add(new SettingsItem.SwitchItem(
                "include_system_vpn",
                getString(R.string.setting_monitor_system),
                getString(R.string.summary_monitor_system),
                prefs.getBoolean("include_system_vpn", false),
                true));

        items.add(new SettingsItem.ActionItem(
                "exclude_all_tracking",
                getString(R.string.setting_exclude_all_tracking),
                getString(R.string.summary_exclude_all_tracking),
                true));

        items.add(new SettingsItem.ActionItem(
                "include_all_tracking",
                getString(R.string.setting_include_all_tracking),
                getString(R.string.summary_include_all_tracking),
                true));

        if (!BlockingMode.isPlayStoreBuild()) {
            items.add(new SettingsItem.ActionItem(
                    "manage_blocklists",
                    getString(R.string.title_blocklists),
                    getString(R.string.summary_manage_blocklists),
                    true));
            String lastDownload = prefs.getString("hosts_last_download", null);
            items.add(new SettingsItem.ActionItem(
                    "hosts_download",
                    getString(R.string.setting_hosts_update),
                    lastDownload == null ? null : getString(R.string.msg_update_last, lastDownload),
                    true));
            items.add(new SettingsItem.SwitchItem(
                    "hosts_auto_update",
                    getString(R.string.setting_hosts_auto_update),
                    getString(R.string.summary_hosts_auto_update),
                    prefs.getBoolean("hosts_auto_update", true),
                    true));
        }

        if (!playStore) {
            items.add(new SettingsItem.SwitchItem(
                    "log_app",
                    getString(R.string.setting_log_app),
                    getString(R.string.summary_log_app),
                    prefs.getBoolean("log_app", false),
                    true));
            items.add(new SettingsItem.ActionItem(
                    "forwarding",
                    getString(R.string.setting_forwarding),
                    null,
                    true));
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            items.add(new SettingsItem.SwitchItem(
                    "handover",
                    getString(R.string.setting_handover),
                    null,
                    prefs.getBoolean("handover", false),
                    true));
        }

        items.add(new SettingsItem.TextInputItem(
                "vpn4",
                getString(R.string.setting_vpn4, prefs.getString("vpn4", "10.1.10.1")),
                null,
                prefs.getString("vpn4", "10.1.10.1"),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "vpn6",
                getString(R.string.setting_vpn6, prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1")),
                null,
                prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "dns",
                getString(R.string.setting_dns, prefs.getString("dns", "-")),
                null,
                prefs.getString("dns", ""),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "dns2",
                getString(R.string.setting_dns, prefs.getString("dns2", "-")),
                null,
                prefs.getString("dns2", ""),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "validate",
                getString(R.string.setting_validate, prefs.getString("validate", "www.f-droid.org")),
                getString(R.string.summary_validate),
                prefs.getString("validate", "www.f-droid.org"),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "ttl",
                getString(R.string.setting_ttl, prefs.getString("ttl", "259200")),
                null,
                prefs.getString("ttl", "259200"),
                false,
                true));
        items.add(new SettingsItem.SwitchItem(
                "socks5_enabled",
                getString(R.string.setting_socks5_enabled),
                getString(R.string.summary_socks5_enabled),
                prefs.getBoolean("socks5_enabled", false),
                true));
        items.add(new SettingsItem.TextInputItem(
                "socks5_addr",
                getString(R.string.setting_socks5_addr, prefs.getString("socks5_addr", "-")),
                null,
                prefs.getString("socks5_addr", ""),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "socks5_port",
                getString(R.string.setting_socks5_port, prefs.getString("socks5_port", "-")),
                null,
                prefs.getString("socks5_port", ""),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "socks5_username",
                getString(R.string.setting_socks5_username, prefs.getString("socks5_username", "-")),
                null,
                prefs.getString("socks5_username", ""),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "socks5_password",
                getString(R.string.setting_socks5_password,
                        TextUtils.isEmpty(prefs.getString("socks5_username", "")) ? "-" : "*****"),
                null,
                prefs.getString("socks5_password", ""),
                true,
                true));
        items.add(new SettingsItem.TextInputItem(
                "pcap_record_size",
                getString(R.string.setting_pcap_record_size, prefs.getString("pcap_record_size", "64")),
                null,
                prefs.getString("pcap_record_size", "64"),
                false,
                true));
        items.add(new SettingsItem.TextInputItem(
                "pcap_file_size",
                getString(R.string.setting_pcap_file_size, prefs.getString("pcap_file_size", "2")),
                null,
                prefs.getString("pcap_file_size", "2"),
                false,
                true));
        items.add(new SettingsItem.ActionItem(
                "show_resolved",
                getString(R.string.setting_show_resolved),
                null,
                true));

        return new SettingsSection(getString(R.string.setting_advanced_options), items);
    }

    private SettingsSection buildWireGuardSection(SharedPreferences prefs) {
        List<SettingsItem> items = new ArrayList<>();
        items.add(new SettingsItem.ActionItem(
                "wg_status",
                getString(R.string.setting_wg_status),
                getWireGuardStatusSummary(prefs).toString(),
                true));
        items.add(new SettingsItem.SwitchItem(
                "wg_enabled",
                getString(R.string.setting_wg_enabled),
                getString(R.string.summary_wg_enabled),
                prefs.getBoolean("wg_enabled", false),
                true));

        WgProfileManager manager = new WgProfileManager(this);
        List<WgProfileManager.Profile> profiles = manager.getProfiles();
        WgProfileManager.Profile activeProfile = manager.getActiveProfile();
        List<Pair<String, String>> profileEntries = new ArrayList<>();
        for (WgProfileManager.Profile p : profiles)
            profileEntries.add(new Pair<>(p.name, p.id));
        items.add(new SettingsItem.ListItem(
                WgProfileManager.PREF_WG_PROFILE,
                getString(R.string.setting_wg_profile),
                profiles.isEmpty()
                        ? getString(R.string.summary_wg_profile_none)
                        : (activeProfile == null ? getString(R.string.summary_wg_profile) : activeProfile.name),
                manager.getActiveProfileId(),
                profileEntries,
                !profiles.isEmpty()));

        items.add(new SettingsItem.ActionItem(
                "wg_profile_manage",
                getString(R.string.setting_wg_profile_manage),
                getString(R.string.summary_wg_profile_manage),
                true));

        List<Pair<String, String>> routeEntries = new ArrayList<>();
        String[] routeNames = getResources().getStringArray(R.array.wgRouteModeNames);
        String[] routeValues = getResources().getStringArray(R.array.wgRouteModeValues);
        for (int i = 0; i < Math.min(routeNames.length, routeValues.length); i++)
            routeEntries.add(new Pair<>(routeNames[i], routeValues[i]));
        items.add(new SettingsItem.ListItem(
                Rule.PREF_WG_ROUTE_MODE,
                getString(R.string.setting_wg_route_mode),
                getString(R.string.summary_wg_route_mode),
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, "all"),
                routeEntries,
                true));

        items.add(new SettingsItem.SwitchItem(
                "wg_keepalive_when_screen_off",
                getString(R.string.setting_wg_keepalive_when_screen_off),
                getString(R.string.summary_wg_keepalive_when_screen_off),
                prefs.getBoolean("wg_keepalive_when_screen_off", false),
                true));

        return new SettingsSection(getString(R.string.setting_wireguard), items);
    }

    private SettingsSection buildTailscaleSection(SharedPreferences prefs) {
        List<SettingsItem> items = new ArrayList<>();
        TailscaleSnapshot state = TailscaleManager.snapshot();
        boolean enabled = prefs.getBoolean("ts_enabled", false);
        if (!enabled || !prefs.getBoolean("enabled", false) || !state.error.isEmpty()
                || "Running".equals(state.state))
            awaitingTailscaleLogin = false;
        if (awaitingTailscaleLogin)
            openTailscaleLogin();

        items.add(new SettingsItem.ActionItem(
                "ts_status",
                getString(R.string.setting_ts_status),
                getTailscaleSummary(prefs),
                true));
        items.add(new SettingsItem.SwitchItem(
                "ts_enabled",
                getString(R.string.setting_ts_enabled),
                getString(R.string.summary_ts_enabled),
                enabled,
                true));
        items.add(new SettingsItem.ActionItem(
                "ts_login",
                getString(R.string.setting_ts_login),
                null,
                enabled && !"Running".equals(state.state)));
        items.add(new SettingsItem.ActionItem(
                "ts_logout",
                getString(R.string.setting_ts_logout),
                null,
                true));

        return new SettingsSection(getString(R.string.setting_ts_title), items);
    }

    private SettingsSection buildDevelopmentSection(SharedPreferences prefs) {
        List<SettingsItem> items = new ArrayList<>();
        List<Pair<String, String>> logEntries = new ArrayList<>();
        String[] names = getResources().getStringArray(R.array.logLevelNames);
        String[] values = getResources().getStringArray(R.array.logLevelValues);
        for (int i = 0; i < Math.min(names.length, values.length); i++)
            logEntries.add(new Pair<>(names[i], values[i]));

        items.add(new SettingsItem.ListItem(
                "loglevel",
                "Native log level",
                "Log level verbose, debug and info will impact performance and battery usage",
                prefs.getString("loglevel", Integer.toString(Log.WARN)),
                logEntries,
                true));
        items.add(new SettingsItem.SwitchItem(
                "ip6",
                getString(R.string.setting_ip6),
                getString(R.string.summary_ip6),
                prefs.getBoolean("ip6", true),
                true));
        items.add(new SettingsItem.ActionItem(
                "vpn_key_rotate_now",
                getString(R.string.setting_vpn_key_rotate_now),
                getString(R.string.summary_vpn_key_rotate_now),
                true));
        return new SettingsSection("Development", items);
    }

    private String getTailscaleSummary(SharedPreferences prefs) {
        TailscaleSnapshot state = TailscaleManager.snapshot();
        boolean enabled = prefs.getBoolean("ts_enabled", false);
        int status = R.string.ts_state_waiting;
        if (!enabled) status = R.string.ts_state_off;
        else if (!state.error.isEmpty()) status = R.string.ts_state_error;
        else if ("Running".equals(state.state)) status = R.string.ts_state_connected;
        else if ("NeedsLogin".equals(state.state)) status = R.string.ts_state_login;
        else if ("NeedsMachineAuth".equals(state.state)) status = R.string.ts_state_approval;
        String summary = getString(status);
        if (enabled && !state.error.isEmpty()) summary += "\n" + state.error;
        if (enabled && !state.ipv4.isEmpty()) summary += "\n" + state.ipv4;
        if (enabled && !state.ipv6.isEmpty()) summary += "\n" + state.ipv6;
        if (enabled && "Running".equals(state.state) && !state.magicDNS)
            summary += "\n" + getString(R.string.ts_magicdns_off);
        return summary;
    }

    static void configureDohEndpointPreference(EditTextPreference preference) {
        preference.setOnPreferenceChangeListener((changedPreference, newValue) -> {
            if (!(newValue instanceof String)) {
                Toast.makeText(changedPreference.getContext(), R.string.msg_invalid_doh_endpoint,
                        Toast.LENGTH_LONG).show();
                return false;
            }

            String endpoint = ((String) newValue).trim();
            if (!isValidDohEndpoint(endpoint)) {
                Toast.makeText(changedPreference.getContext(), R.string.msg_invalid_doh_endpoint,
                        Toast.LENGTH_LONG).show();
                return false;
            }

            if (!endpoint.equals(newValue)) {
                ((EditTextPreference) changedPreference).setText(endpoint);
                return false;
            }
            return true;
        });
    }

    private static boolean isValidDohEndpoint(String endpoint) {
        if (TextUtils.isEmpty(endpoint) || containsWhitespace(endpoint))
            return false;

        try {
            URI uri = new URI(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || TextUtils.isEmpty(uri.getRawAuthority())
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getRawAuthority().indexOf('@') >= 0)
                return false;

            HttpUrl httpUrl = HttpUrl.parse(endpoint);
            return httpUrl != null && httpUrl.isHttps() && !TextUtils.isEmpty(httpUrl.host())
                    && !uri.getRawAuthority().endsWith(":");
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character))
                return true;
        }
        return false;
    }

    private CharSequence getWireGuardStatusSummary(SharedPreferences prefs) {
        if (!prefs.getBoolean("wg_enabled", false))
            return getString(R.string.summary_wg_status_off);

        if (TextUtils.isEmpty(prefs.getString("wg_config", "")))
            return getString(R.string.summary_wg_status_missing_config);

        String error = net.kollnig.missioncontrol.wg.WgEgress.INSTANCE.getLastError();
        if (!TextUtils.isEmpty(error))
            return getString(R.string.summary_wg_status_blocked, error);

        if (net.kollnig.missioncontrol.wg.WgEgress.INSTANCE.isRunning()) {
            Long handshake = net.kollnig.missioncontrol.wg.WgEgress.INSTANCE.latestHandshakeMillisOrNull();
            if (handshake != null && handshake > 0)
                return getString(R.string.summary_wg_status_connected);
            return getString(R.string.summary_wg_status_handshaking);
        }

        return getString(R.string.summary_wg_status_starting);
    }

    private String getBlockingModeSummary(String mode) {
        if (BlockingMode.MODE_MINIMAL.equals(mode))
            return getString(R.string.summary_blocking_mode_minimal);
        else if (BlockingMode.MODE_STRICT.equals(mode))
            return getString(R.string.summary_blocking_mode_strict);
        else
            return getString(R.string.summary_blocking_mode_standard);
    }

    private String getResearchModeSummaryText(SharedPreferences prefs) {
        String base = getString(R.string.summary_log_logcat);
        if (prefs.getBoolean("sni_enabled", false) && prefs.getBoolean("ts_enabled", false))
            return base + " " + getString(R.string.summary_sni_ts_note);
        if (prefs.getBoolean("sni_enabled", false)
                && prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", null)))
            return base + " " + getString(R.string.summary_sni_wg_note);
        return base;
    }

    private void setTrackerProtectionForAll(boolean enabled) {
        SharedPreferences tracker_protect = getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = tracker_protect.edit();
        for (android.content.pm.PackageInfo pkg : Rule.getPackages(this))
            editor.putBoolean(pkg.packageName, enabled);
        editor.apply();
        ServiceSinkhole.reload("bulk tracker protect changed", this, false);
    }

    private boolean checkPermissions(String name) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if ((name == null || "disable_on_call".equals(name)) && prefs.getBoolean("disable_on_call", false))
            if (!Util.hasPhoneStatePermission(this)) {
                prefs.edit().putBoolean("disable_on_call", false).apply();
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_CALL);
                if (name != null)
                    return false;
            }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean granted = (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        if (requestCode == REQUEST_CALL)
            prefs.edit().putBoolean("disable_on_call", granted).apply();
        if (granted)
            ServiceSinkhole.reload("permission granted", this, false);
        renderSettings();
    }

    private void checkAddress(String address, boolean allow_local)
            throws IllegalArgumentException, UnknownHostException {
        if (address != null)
            address = address.trim();
        if (TextUtils.isEmpty(address))
            throw new IllegalArgumentException("Bad address");
        if (!Util.isNumericAddress(address))
            throw new IllegalArgumentException("Bad address");
        if (!allow_local) {
            InetAddress iaddr = InetAddress.getByName(address);
            if (iaddr.isLoopbackAddress() || iaddr.isAnyLocalAddress())
                throw new IllegalArgumentException("Bad address");
        }
    }

    private void checkDomain(String address) throws IllegalArgumentException, UnknownHostException {
        if (address != null)
            address = address.trim();
        if (TextUtils.isEmpty(address))
            throw new IllegalArgumentException("Bad address");
        if (Util.isNumericAddress(address))
            throw new IllegalArgumentException("Bad address");
        if (!PatternsCompat.DOMAIN_NAME.matcher(address).matches())
            throw new IllegalArgumentException("Bad address");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok="
                + (resultCode == RESULT_OK));
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleExport(data);

        } else if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleImport(data);

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private Intent getIntentCreateExport() {
        Intent intent;
        intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // text/xml
        intent.putExtra(Intent.EXTRA_TITLE,
                "trackercontrol_" + new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date().getTime()) + ".xml");
        return intent;
    }

    private Intent getIntentOpenExport() {
        Intent intent;
        intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // text/xml
        return intent;
    }

    // The one-shot export callback already checks running; executor migration
    // is intentionally outside this focused lint change.
    @SuppressLint("StaticFieldLeak")
    private void handleExport(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                OutputStream out = null;
                try {
                    Uri target = data.getData();
                    if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                        target = Uri.parse(target + "/trackercontrol_"
                                + new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date().getTime()) + ".xml");
                    Log.i(TAG, "Writing URI=" + target);
                    out = getContentResolver().openOutputStream(target);
                    xmlExport(out);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (out != null)
                        try {
                            out.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (running) {
                    if (ex == null)
                        Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                    else
                        Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // The one-shot import callback already checks running; executor migration
    // is intentionally outside this focused lint change.
    @SuppressLint("StaticFieldLeak")
    private void handleImport(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                InputStream in = null;
                try {
                    Log.i(TAG, "Reading URI=" + data.getData());
                    ContentResolver resolver = getContentResolver();
                    String[] streamTypes = resolver.getStreamTypes(data.getData(), "*/*");
                    String streamType = (streamTypes == null || streamTypes.length == 0 ? "*/*" : streamTypes[0]);
                    AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(data.getData(), streamType,
                            null);
                    in = descriptor.createInputStream();
                    xmlImport(in);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (in != null)
                        try {
                            in.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (running) {
                    if (ex == null) {
                        Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                        ServiceSinkhole.reloadStats("import", ActivitySettings.this);
                        // Update theme, request permissions
                        recreate();
                    } else
                        Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void xmlExport(OutputStream _out) throws IOException {
        BufferedOutputStream out = new BufferedOutputStream(_out);

        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(out, "UTF-8");
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "trackercontrol");

        serializer.startTag(null, "application");
        xmlExport(PreferenceManager.getDefaultSharedPreferences(this), serializer);
        serializer.endTag(null, "application");
        out.flush();

        serializer.startTag(null, "apply");
        // A temporary pause must not be exported as a permanent exclusion.
        xmlExport(getSharedPreferences("apply", Context.MODE_PRIVATE),
                PausedApps.applyValuesWithoutPauses(this), serializer);
        serializer.endTag(null, "apply");

        serializer.startTag(null, "tracker_protect");
        xmlExport(getSharedPreferences("tracker_protect", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "tracker_protect");

        serializer.startTag(null, "tracker_essential");
        xmlExport(getSharedPreferences("tracker_essential", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "tracker_essential");

        serializer.startTag(null, Rule.PREF_WG_ROUTE);
        xmlExport(getSharedPreferences(Rule.PREF_WG_ROUTE, Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, Rule.PREF_WG_ROUTE);

        serializer.startTag(null, "notify");
        xmlExport(getSharedPreferences("notify", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "notify");

        serializer.startTag(null, "forward");
        forwardExport(serializer);
        serializer.endTag(null, "forward");

        serializer.startTag(null, "blocklist");
        xmlExport(getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "blocklist");

        serializer.endTag(null, "trackercontrol");
        serializer.endDocument();
        serializer.flush();

        out.flush();
    }

    private void xmlExport(SharedPreferences prefs, XmlSerializer serializer) throws IOException {
        xmlExport(prefs, null, serializer);
    }

    private void xmlExport(SharedPreferences prefs, Map<String, ?> overrides, XmlSerializer serializer) throws IOException {
        Map<String, ?> settings = prefs.getAll();
        if (overrides != null && !overrides.isEmpty()) {
            Map<String, Object> merged = new HashMap<>(settings);
            merged.putAll(overrides);
            settings = merged;
        }
        for (String key : settings.keySet()) {
            Object value = settings.get(key);

            if ("imported".equals(key))
                continue;

            if (value instanceof Boolean) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "boolean");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");

            } else if (value instanceof Integer) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "integer");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");

            } else if (value instanceof String) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "string");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");

            } else if (value instanceof Set) {
                Set<String> set = (Set<String>) value;

                // InternetBlocklist: convert UIDs to package names
                if (key.equals(InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY)) {
                    Set<String> packageNames = new HashSet<>();
                    PackageManager pm = getPackageManager();
                    for (String uidStr : set) {
                        try {
                            int uid = Integer.parseInt(uidStr);
                            String[] packages = Util.getPackagesForUid(pm, uid);
                            if (packages != null) {
                                Collections.addAll(packageNames, packages);
                            }
                        } catch (NumberFormatException e) {
                            // Not a UID, so already stored under a package name:
                            // an imported entry whose app is not installed yet.
                            // The export is package-keyed anyway, so pass it
                            // through rather than dropping the setting.
                            packageNames.add(uidStr);
                        }
                    }

                    // Key for package names
                    serializer.startTag(null, "setting");
                    serializer.attribute(null, "key", "INTERNET_BLOCKLIST_PACKAGES_KEY");
                    serializer.attribute(null, "type", "set");
                    serializer.attribute(null, "value", TextUtils.join("\n", packageNames));
                    serializer.endTag(null, "setting");
                } else if (key.equals(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY)) {
                    // TrackerBlocklist: convert UIDs to package names
                    Set<String> packageNames = new HashSet<>();
                    PackageManager pm = getPackageManager();
                    for (String uidStr : set) {
                        try {
                            int uid = Integer.parseInt(uidStr);
                            String[] packages = Util.getPackagesForUid(pm, uid);
                            if (packages != null) {
                                for (String pkg : packages) {
                                    packageNames.add(pkg);
                                    exportTrackerSubset(prefs, serializer, uidStr, pkg);
                                }
                            }
                        } catch (NumberFormatException e) {
                            // Not a UID, so already stored under a package name:
                            // an imported entry whose app is not installed yet.
                            // Carry it and its subset through unchanged.
                            packageNames.add(uidStr);
                            exportTrackerSubset(prefs, serializer, uidStr, uidStr);
                        }
                    }

                    // Key for package names list
                    serializer.startTag(null, "setting");
                    serializer.attribute(null, "key", "APPS_BLOCKLIST_PACKAGES_KEY");
                    serializer.attribute(null, "type", "set");
                    serializer.attribute(null, "value", TextUtils.join("\n", packageNames));
                    serializer.endTag(null, "setting");

                } else if (key.startsWith(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_")) {
                    // Skip the original UID-based per-app settings as we handled them above
                    // This prevents duplication and confusion

                } else {
                    serializer.startTag(null, "setting");
                    serializer.attribute(null, "key", key);
                    serializer.attribute(null, "type", "set");
                    serializer.attribute(null, "value", TextUtils.join("\n", set));
                    serializer.endTag(null, "setting");
                }

            } else
                Log.e(TAG, "Unknown key=" + key);
        }
    }

    /**
     * Write one app's tracker subset under its package-name key.
     *
     * @param prefs     Blocklist preferences
     * @param serializer Serializer to write to
     * @param storedKey The key the subset is stored under: a UID, or a package
     *                  name for an entry that could not be resolved
     * @param pkg       The package name to export it under
     */
    static void exportTrackerSubset(SharedPreferences prefs, XmlSerializer serializer,
                                    String storedKey, String pkg) throws IOException {
        // The key format for per-app settings is APPS_BLOCKLIST_APPS_KEY + "_" + uid
        Set<String> perAppSettings = prefs.getStringSet(
                TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + storedKey, null);
        if (perAppSettings == null)
            return;

        serializer.startTag(null, "setting");
        // Use a package-based key: APPS_BLOCKLIST_PACKAGES_KEY + "_" + packageName
        serializer.attribute(null, "key", "APPS_BLOCKLIST_PACKAGES_KEY" + "_" + pkg);
        serializer.attribute(null, "type", "set");
        serializer.attribute(null, "value", TextUtils.join("\n", perAppSettings));
        serializer.endTag(null, "setting");
    }

    private void forwardExport(XmlSerializer serializer) throws IOException {
        try (Cursor cursor = DatabaseHelper.getInstance(this).getForwarding()) {
            int colProtocol = cursor.getColumnIndex("protocol");
            int colDPort = cursor.getColumnIndex("dport");
            int colRAddr = cursor.getColumnIndex("raddr");
            int colRPort = cursor.getColumnIndex("rport");
            int colRUid = cursor.getColumnIndex("ruid");
            while (cursor.moveToNext())
                for (String pkg : getPackages(cursor.getInt(colRUid))) {
                    serializer.startTag(null, "port");
                    serializer.attribute(null, "pkg", pkg);
                    serializer.attribute(null, "protocol", Integer.toString(cursor.getInt(colProtocol)));
                    serializer.attribute(null, "dport", Integer.toString(cursor.getInt(colDPort)));
                    serializer.attribute(null, "raddr", cursor.getString(colRAddr));
                    serializer.attribute(null, "rport", Integer.toString(cursor.getInt(colRPort)));
                    serializer.endTag(null, "port");
                }
        }
    }

    private String[] getPackages(int uid) {
        if (uid == 0)
            return new String[] { "root" };
        else if (uid == 1013)
            return new String[] { "mediaserver" };
        else if (uid == 9999)
            return new String[] { "nobody" };
        else {
            String pkgs[] = Util.getPackagesForUid(getPackageManager(), uid);
            if (pkgs == null)
                return new String[0];
            else
                return pkgs;
        }
    }

    private void xmlImport(InputStream in) throws IOException, SAXException, ParserConfigurationException {
        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        XmlImportHandler handler = new XmlImportHandler(this);
        reader.setContentHandler(handler);
        reader.parse(new InputSource(in));
        validateImportedEgress(this, handler.application);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        keepTailscaleConsent(handler.application, prefs.getBoolean("ts_enabled", false));
        boolean wasEnabled = prefs.getBoolean("enabled", false);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        prefs.edit().putBoolean("enabled", false).apply();
        ServiceSinkhole.stop("import", this, false);

        boolean applied = false;
        try {
            // Only now that the whole file has parsed successfully do we
            // touch the forwarding rules, so a malformed file never leaves
            // the old rules deleted with no replacement.
            applied = true;
            if (handler.forwardSeen)
                DatabaseHelper.getInstance(this).replaceForwards(handler.forwards);

            xmlImport(handler.application, prefs);
            xmlImport(handler.apply, getSharedPreferences("apply", Context.MODE_PRIVATE));
            xmlImport(handler.tracker_protect, getSharedPreferences("tracker_protect", Context.MODE_PRIVATE));
            xmlImport(handler.tracker_essential, getSharedPreferences("tracker_essential", Context.MODE_PRIVATE));
            xmlImport(handler.wg_route, getSharedPreferences(Rule.PREF_WG_ROUTE, Context.MODE_PRIVATE));
            xmlImport(handler.notify, getSharedPreferences("notify", Context.MODE_PRIVATE));
            xmlImport(handler.blocklist, getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE));

            // Reload blocklist
            TrackerBlocklist.getInstance(this).loadSettings(this);
            InternetBlocklist.getInstance(this).loadSettings(this);

            // Upgrade imported settings
            ReceiverAutostart.upgrade(true, this);

            // Reconcile imported apply preferences with auto-exclusions immediately,
            // rather than waiting for the next process start.
            BlockingMode.syncAutoExclusions(this);

            DatabaseHelper.clearCache();

            // Refresh UI
            prefs.edit().putBoolean("imported", true).apply();
        } catch (Throwable ex) {
            if (applied) {
                // Some stores were already overwritten, so the configuration
                // is a mix of old and imported settings. Leave the VPN off
                // rather than start it on a state nobody chose.
                Log.w(TAG, "Import failed after applying; leaving disabled");
            } else {
                // Nothing was touched yet: restore the pre-import state, as a
                // failed parse must not leave the VPN off when it was running.
                prefs.edit().putBoolean("enabled", wasEnabled).apply();
                if (wasEnabled)
                    ServiceSinkhole.start("import", this);
            }
            throw ex;
        } finally {
            prefs.registerOnSharedPreferenceChangeListener(this);
        }
    }

    /**
     * A settings file must not switch Tailscale on. Enabling it makes the app
     * contact Tailscale's servers, which the user only agrees to in the
     * confirmation dialog of the Tailscale switch. An import therefore keeps
     * it on only where it already was on.
     */
    static void keepTailscaleConsent(Map<String, Object> imported, boolean tailscaleEnabledBefore) {
        if (!tailscaleEnabledBefore && Boolean.TRUE.equals(imported.get("ts_enabled")))
            imported.put("ts_enabled", false);
    }

    static void validateImportedEgress(Context context, Map<String, ?> settings) throws SAXException {
        try {
            if (net.kollnig.missioncontrol.vpn.VpnEgressPolicy.hasConflict(settings))
                throw new SAXException(context.getString(R.string.msg_ts_exclusive));
        } catch (IllegalArgumentException ex) {
            throw new SAXException(ex.getMessage(), ex);
        }
    }

    private void xmlImport(Map<String, Object> settings, SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();

        // Clear existing setting
        for (String key : prefs.getAll().keySet())
            if (!"enabled".equals(key))
                editor.remove(key);

        // Apply new settings
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer)
                editor.putInt(key, (Integer) value);
            else if (value instanceof String)
                editor.putString(key, (String) value);
            else if (value instanceof Set)
                editor.putStringSet(key, (Set<String>) value);
            else
                Log.e(TAG, "Unknown type=" + value.getClass());
        }

        editor.apply();
    }

    static Set<String> resolveImportedUids(Context context, String value) {
        Set<String> uids = new HashSet<>();
        if (TextUtils.isEmpty(value))
            return uids;

        for (String pkg : value.split("\n")) {
            if (TextUtils.isEmpty(pkg))
                continue;

            Integer uid = PackageUids.resolve(context, pkg);
            if (uid == null) {
                // Retain the package name so a later install can resolve it.
                uids.add(pkg);
                Log.w(TAG, "Retaining unresolved package during import: " + pkg);
            } else {
                uids.add(Integer.toString(uid));
            }
        }
        return uids;
    }

    private class XmlImportHandler extends DefaultHandler {
        private Context context;
        public boolean enabled = false;
        public Map<String, Object> application = new HashMap<>();
        public Map<String, Object> wifi = new HashMap<>();
        public Map<String, Object> mobile = new HashMap<>();
        public Map<String, Object> screen_wifi = new HashMap<>();
        public Map<String, Object> screen_other = new HashMap<>();
        public Map<String, Object> roaming = new HashMap<>();
        public Map<String, Object> apply = new HashMap<>();
        public Map<String, Object> tracker_protect = new HashMap<>();
        public Map<String, Object> tracker_essential = new HashMap<>();
        public Map<String, Object> wg_route = new HashMap<>();
        public Map<String, Object> notify = new HashMap<>();
        public Map<String, Object> blocklist = new HashMap<>();
        // Forwarding rules parsed from the file: collected here rather than
        // written straight to the database, so a malformed <port> further
        // down never leaves the old rules deleted with no replacement.
        public boolean forwardSeen = false;
        public List<Forward> forwards = new ArrayList<>();
        private Map<String, Object> current = null;

        public XmlImportHandler(Context context) {
            this.context = context;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (qName.equals("netguard")
                    || qName.equals("trackercontrol"))
                ; // Ignore

            else if (qName.equals("application"))
                current = application;

            else if (qName.equals("wifi"))
                current = wifi;

            else if (qName.equals("mobile"))
                current = mobile;

            else if (qName.equals("screen_wifi"))
                current = screen_wifi;

            else if (qName.equals("screen_other"))
                current = screen_other;

            else if (qName.equals("roaming"))
                current = roaming;



            else if (qName.equals("apply"))
                current = apply;

            else if (qName.equals("tracker_protect"))
                current = tracker_protect;
            else if (qName.equals("tracker_essential"))
                current = tracker_essential;
            else if (qName.equals(Rule.PREF_WG_ROUTE))
                current = wg_route;

            else if (qName.equals("notify"))
                current = notify;

            else if (qName.equals("forward")) {
                current = null;
                forwardSeen = true;

            } else if (qName.equals("blocklist"))
                current = blocklist;

            else if (qName.equals("setting")) {
                String key = attributes.getValue("key");
                String type = attributes.getValue("type");
                String value = attributes.getValue("value");

                if (current == application && ("ts_enabled".equals(key)
                        || "wg_enabled".equals(key) || "socks5_enabled".equals(key))) {
                    if (!"boolean".equals(type) || !("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)))
                        throw new SAXException("Invalid boolean VPN setting: " + key);
                }

                if (current == null)
                    Log.e(TAG, "No current key=" + key);
                else {
                    if ("enabled".equals(key))
                        enabled = Boolean.parseBoolean(value);
                    else {
                        if (current == application) {
                            if ("hosts_last_import".equals(key) || "hosts_last_download".equals(key))
                                return;
                        }

                        // InternetBlocklist: convert package names back to UIDs
                        if ("INTERNET_BLOCKLIST_PACKAGES_KEY".equals(key) && "set".equals(type)) {
                            Set<String> uids = resolveImportedUids(context, value);
                            // Store under original key
                            String originalKey = InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY;
                            current.put(originalKey, uids);
                            return;
                        }

                        // TrackerBlocklist: convert package names back to UIDs
                        if ("APPS_BLOCKLIST_PACKAGES_KEY".equals(key) && "set".equals(type)) {
                            Set<String> uids = resolveImportedUids(context, value);
                            // Store under original key
                            String originalKey = TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY;
                            current.put(originalKey, uids);
                            return;
                        }

                        // TrackerBlocklist: per-app settings
                        if (key.startsWith("APPS_BLOCKLIST_PACKAGES_KEY_") && "set".equals(type)) {
                            // Extract package name
                            String pkg = key.substring("APPS_BLOCKLIST_PACKAGES_KEY_".length());
                            Set<String> set = new HashSet<>();
                            if (!TextUtils.isEmpty(value))
                                for (String s : value.split("\n"))
                                    set.add(s);

                            Integer uid = PackageUids.resolve(context, pkg);
                            if (uid == null) {
                                // Retain the package-name key so a later install can resolve it.
                                current.put(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + pkg, set);
                                Log.w(TAG, "Retaining unresolved package during import of settings: " + pkg);
                            } else {
                                // Store under original UID-based key
                                current.put(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + uid, set);
                            }
                            return;
                        }

                        if ("boolean".equals(type))
                            current.put(key, Boolean.parseBoolean(value));
                        else if ("integer".equals(type))
                            current.put(key, Integer.parseInt(value));
                        else if ("string".equals(type))
                            current.put(key, value);
                        else if ("set".equals(type)) {
                            Set<String> set = new HashSet<>();
                            if (!TextUtils.isEmpty(value))
                                for (String s : value.split("\n"))
                                    set.add(s);
                            current.put(key, set);
                        } else
                            Log.e(TAG, "Unknown type key=" + key);
                    }
                }

            } else if (qName.equals("port")) {
                String pkg = attributes.getValue("pkg");
                int protocol = Integer.parseInt(attributes.getValue("protocol"));
                int dport = Integer.parseInt(attributes.getValue("dport"));
                String raddr = attributes.getValue("raddr");
                int rport = Integer.parseInt(attributes.getValue("rport"));

                try {
                    // Re-derive as the numeric address: the imported file is
                    // untrusted input, and the native side copies raddr into a
                    // fixed INET6_ADDRSTRLEN buffer. A blank address is left
                    // alone: native treats it as "no redirect", and resolving
                    // it would turn an inert exported rule into a live
                    // redirect to loopback on re-import.
                    if (!TextUtils.isEmpty(raddr))
                        raddr = InetAddress.getByName(raddr).getHostAddress();
                    int uid = getUid(pkg);

                    Forward forward = new Forward();
                    forward.protocol = protocol;
                    forward.dport = dport;
                    forward.raddr = raddr;
                    forward.rport = rport;
                    forward.ruid = uid;
                    forwards.add(forward);
                } catch (PackageManager.NameNotFoundException ex) {
                    Log.w(TAG, "Package not found pkg=" + pkg);
                } catch (java.io.IOException ex) {
                    Log.w(TAG, "Invalid forward raddr=" + raddr + ": " + ex);
                }

            } else
                Log.e(TAG, "Unknown element qname=" + qName);
        }

        private int getUid(String pkg) throws PackageManager.NameNotFoundException {
            if ("root".equals(pkg))
                return 0;
            else if ("android.media".equals(pkg))
                return 1013;
            else if ("android.multicast".equals(pkg))
                return 1020;
            else if ("android.gps".equals(pkg))
                return 1021;
            else if ("android.dns".equals(pkg))
                return 1051;
            else if ("nobody".equals(pkg))
                return 9999;
            else
                return getPackageManager().getApplicationInfo(pkg, 0).uid;
        }
    }
}
