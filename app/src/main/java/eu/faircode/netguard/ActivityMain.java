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

import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_NAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_UID;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.PendingIntentCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentContainerView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.opencsv.CSVWriter;

import net.kollnig.missioncontrol.ActivityOnboarding;
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.LocalNetworkAccess;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.TimelineFragment;
import net.kollnig.missioncontrol.data.AppProtectionState;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.ui.compose.AppRuleUi;
import net.kollnig.missioncontrol.ui.compose.MainBannerState;
import net.kollnig.missioncontrol.ui.compose.MainDialog;
import net.kollnig.missioncontrol.ui.compose.MainFilterState;
import net.kollnig.missioncontrol.ui.compose.MainScreen;
import net.kollnig.missioncontrol.ui.compose.MainScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.MainScreenController;
import net.kollnig.missioncontrol.ui.compose.MainScreenModel;
import net.kollnig.missioncontrol.ui.compose.MainTabs;
import net.kollnig.missioncontrol.ui.compose.TroubleshootingState;
import net.kollnig.missioncontrol.vpn.VpnFragment;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ActivityMain extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "TrackerControl.Main";
    private static final String KEY_SELECTED_TAB = "selected_tab";

    private boolean running = false;
    private MainScreenController screenController;
    private int selectedTab = MainTabs.TIMELINE;
    private boolean vpnFragmentRequested = false;
    private AlertDialog dialogVpn = null;

    private final List<Rule> allRules = new ArrayList<>();
    private List<Rule> searchPool = null;
    private String searchQuery = "";
    private boolean searchOpen = false;
    private boolean refreshingApps = false;
    private boolean queueActive = false;
    private boolean networkActive = true;
    private boolean meteredActive = false;
    private MainDialog activeDialog = MainDialog.NONE;

    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_INVITE = 2;
    public static final int REQUEST_ROAMING = 3;
    public static final int REQUEST_DETAILS_UPDATED = 4;

    private static final int REQUEST_NOTIFICATIONS = 5;
    private static final int REQUEST_LOCAL_NETWORK = 6;

    private static final int REQUEST_EXPORT = 10;

    public static final String ACTION_RULES_CHANGED = "eu.faircode.netguard.ACTION_RULES_CHANGED";
    public static final String ACTION_PRIVATE_DNS_WARNING_CHANGED =
            "eu.faircode.netguard.ACTION_PRIVATE_DNS_WARNING_CHANGED";
    public static final String ACTION_QUEUE_CHANGED = "eu.faircode.netguard.ACTION_QUEUE_CHANGED";
    public static final String EXTRA_REFRESH = "Refresh";
    public static final String EXTRA_SEARCH = "Search";
    public static final String EXTRA_RELATED = "Related";
    public static final String EXTRA_APPROVE = "Approve";
    public static final String EXTRA_LOGCAT = "Logcat";
    public static final String EXTRA_CONNECTED = "Connected";
    public static final String EXTRA_METERED = "Metered";
    public static final String EXTRA_SIZE = "Size";
    public static final String EXTRA_PRIVATE_DNS_WARNING_STATE = "PrivateDnsWarningState";
    private Boolean lastLocalNetworkGrant = null;
    private int privateDnsWarningState = ServiceSinkhole.PRIVATE_DNS_WARNING_NONE;
    private int privateDnsWarningQueryGeneration = 0;
    private int rulesUpdateGeneration = 0;
    private boolean autofillUnavailable = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedAccessRender = this::renderMainScreen;
    private String cachedCopyright = "";
    private boolean cachedShowInvite = false;
    private boolean cachedShowSupport = false;
    private boolean cachedEnableMoreApps = false;
    private boolean cachedShowRate = false;

    @Override
    public Object getSystemService(@NonNull String name) {
        if (Context.AUTOFILL_SERVICE.equals(name) && autofillUnavailable)
            return null;

        try {
            return super.getSystemService(name);
        } catch (NullPointerException ex) {
            StackTraceElement[] stack = ex.getStackTrace();
            if (Context.AUTOFILL_SERVICE.equals(name)
                    && "FileDescriptor must not be null".equals(ex.getMessage())
                    && stack.length > 0
                    && "android.os.ParcelFileDescriptor".equals(stack[0].getClassName())
                    && "<init>".equals(stack[0].getMethodName())) {
                // Android 16 can fail to construct the optional Autofill service when
                // file-descriptor duplication fails (#931). This keeps startup alive;
                // it does not repair the underlying descriptor failure.
                autofillUnavailable = true;
                Log.w(TAG, "Autofill service unavailable (#931)", ex);
                return null;
            }
            throw ex;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));
        Util.logExtras(getIntent());

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (Util.hasXposed(this)) {
            Util.setTheme(this);
            super.onCreate(savedInstanceState);
            setContentView(R.layout.xposed);
            return;
        }

        if (!Util.canFilter(this)) {
            Util.setTheme(this);
            super.onCreate(savedInstanceState);
            AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.device_not_supported_title)
                    .setMessage(R.string.device_not_supported_msg)
                    .setPositiveButton(R.string.ok, (d, id) -> finish())
                    .create();
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
            return;
        }

        int version = prefs.getInt("onboarding_version", 0);
        if (version < ActivityOnboarding.ONBOARDING_VERSION) {
            super.onCreate(savedInstanceState);
            startActivity(new Intent(this, ActivityOnboarding.class));
            finish();
            return;
        }

        if (prefs.getBoolean("protection_disabled_by_user", false)) {
            prefs.edit()
                    .putBoolean("enabled", false)
                    .remove("protection_disabled_by_user")
                    .apply();
        }

        Util.setTheme(this);
        super.onCreate(savedInstanceState);

        FrameLayout rootFrame = new FrameLayout(this);
        ComposeView root = new ComposeView(this);
        root.setId(R.id.compose_main);
        rootFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FragmentContainerView timelineContainer = new FragmentContainerView(this);
        timelineContainer.setId(R.id.timelineContainer);
        timelineContainer.setVisibility(View.GONE);
        rootFrame.addView(timelineContainer, new FrameLayout.LayoutParams(0, 0));

        FragmentContainerView vpnContainer = new FragmentContainerView(this);
        vpnContainer.setId(R.id.vpnContainer);
        vpnContainer.setVisibility(View.GONE);
        rootFrame.addView(vpnContainer, new FrameLayout.LayoutParams(0, 0));

        setContentView(rootFrame);

        running = true;

        PackageManager pm = getPackageManager();
        cachedCopyright = getString(R.string.app_copyright).replaceFirst(
                "(?<=2019[–-])20\\d{2}",
                new SimpleDateFormat("yyyy", Locale.US).format(new Date()));
        cachedShowInvite = getIntentInvite(this).resolveActivity(pm) != null;
        cachedShowSupport = getIntentSupport(this).resolveActivity(pm) != null;
        cachedEnableMoreApps = getIntentApps(this).resolveActivity(pm) != null;
        cachedShowRate = getIntentRate(this).resolveActivity(pm) != null;

        boolean enabled = prefs.getBoolean("enabled", false);
        final boolean initialized = prefs.getBoolean("initialized", false);
        ReceiverAutostart.upgrade(initialized, this);

        if (!getIntent().hasExtra(EXTRA_APPROVE)) {
            if (enabled)
                ServiceSinkhole.start("UI", this, true);
            else
                ServiceSinkhole.stop("UI", this, false);
        }

        networkActive = Util.isNetworkActive(this);
        meteredActive = Util.isMeteredNetwork(this);

        if (savedInstanceState == null) {
            selectedTab = MainTabs.TIMELINE;
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.timelineContainer, new TimelineFragment())
                    .commit();
        } else {
            selectedTab = savedInstanceState.getInt(KEY_SELECTED_TAB, MainTabs.TIMELINE);
            if (selectedTab == MainTabs.VPN)
                ensureVpnFragment();
        }

        screenController = MainScreen.installWithFragmentContainers(
                root,
                createCallbacks(prefs),
                timelineContainer,
                vpnContainer);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (searchOpen) {
                    searchOpen = false;
                    searchQuery = "";
                    getIntent().removeExtra(EXTRA_SEARCH);
                    renderMainScreen();
                    return;
                }
                if (selectedTab != MainTabs.APPS) {
                    selectTab(MainTabs.APPS);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        prefs.registerOnSharedPreferenceChangeListener(this);

        IntentFilter ifr = new IntentFilter(ACTION_RULES_CHANGED);
        ifr.addAction(ACTION_PRIVATE_DNS_WARNING_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onRulesChanged, ifr);

        IntentFilter ifq = new IntentFilter(ACTION_QUEUE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(onQueueChanged, ifq);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        ContextCompat.registerReceiver(this, packageChangedReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter ifExternal = new IntentFilter();
        ifExternal.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        ifExternal.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        ContextCompat.registerReceiver(this, packageChangedReceiver, ifExternal, ContextCompat.RECEIVER_NOT_EXPORTED);

        if (!initialized)
            prefs.edit().putBoolean("initialized", true).apply();

        String initialSearch = getIntent().getStringExtra(EXTRA_SEARCH);
        if (initialSearch != null) {
            selectedTab = MainTabs.APPS;
            searchOpen = true;
            searchQuery = initialSearch;
        }

        renderMainScreen();
        updateApplicationList(initialSearch);
        checkExtras(getIntent());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_TAB, selectedTab);
    }

    private MainScreenCallbacks createCallbacks(SharedPreferences prefs) {
        return new MainScreenCallbacks() {
            @Override
            public void onSelectTab(int tab) {
                selectTab(tab);
            }

            @Override
            public void onToggleProtection(boolean enabled) {
                toggleProtection(enabled, prefs);
            }

            @Override
            public void onSearchOpenChanged(boolean open) {
                searchOpen = open;
                if (!open) {
                    searchQuery = "";
                    getIntent().removeExtra(EXTRA_SEARCH);
                }
                renderMainScreen();
            }

            @Override
            public void onSearchQueryChanged(@NonNull String query) {
                searchQuery = query;
                renderMainScreen();
            }

            @Override
            public void onRefreshApps() {
                Rule.clearCache(ActivityMain.this);
                ServiceSinkhole.reload("pull", ActivityMain.this, false);
                updateApplicationList(null);
            }

            @Override
            public void onToggleAppInternet(@NonNull AppRuleUi app) {
                if (!app.getHasInternet())
                    return;
                if (!app.isActiveProtection()) {
                    Toast.makeText(ActivityMain.this, R.string.bypass_vpn_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(ActivityMain.this);
                boolean wasBlocked = internetBlocklist.blockedInternet(app.getUid());
                if (wasBlocked) {
                    internetBlocklist.unblock(ActivityMain.this, app.getUid());
                    Toast.makeText(ActivityMain.this, R.string.internet_unblocked, Toast.LENGTH_SHORT).show();
                } else {
                    internetBlocklist.block(ActivityMain.this, app.getUid());
                    Toast.makeText(ActivityMain.this, R.string.internet_blocked, Toast.LENGTH_SHORT).show();
                }
                renderMainScreen();
            }

            @Override
            public void onOpenAppDetails(@NonNull AppRuleUi app) {
                if (!app.getHasInternet()) {
                    Toast.makeText(ActivityMain.this, R.string.no_internet_message, Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent settings = new Intent(ActivityMain.this, DetailsActivity.class);
                settings.putExtra(INTENT_EXTRA_APP_NAME, app.getName());
                settings.putExtra(INTENT_EXTRA_APP_PACKAGENAME, app.getPackageName());
                settings.putExtra(INTENT_EXTRA_APP_UID, app.getUid());
                startActivityForResult(settings, REQUEST_DETAILS_UPDATED);
            }

            @Override
            public void onDismissPushHint() {
                prefs.edit().putBoolean("hint_push", false).apply();
                renderMainScreen();
            }

            @Override
            public void onDismissSystemHint() {
                prefs.edit().putBoolean("hint_system", false).apply();
                renderMainScreen();
            }

            @Override
            public void onOpenSettings() {
                startActivity(new Intent(ActivityMain.this, ActivitySettings.class));
            }

            @Override
            public void onRequestNotificationsPermission() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
                }
            }

            @Override
            public void onRequestLocalNetworkPermission() {
                requestPermissions(new String[]{LocalNetworkAccess.PERMISSION}, REQUEST_LOCAL_NETWORK);
            }

            @Override
            public void onOpenPrivateDnsBlockedHelp() {
                Intent settings = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                if (settings.resolveActivity(getPackageManager()) == null)
                    settings = new Intent(Settings.ACTION_WIFI_SETTINGS);
                startActivity(settings);
            }

            @Override
            public void onOpenPrivateDnsBypassHelp() {
                Intent settings;
                if (privateDnsWarningState == ServiceSinkhole.PRIVATE_DNS_WARNING_ALLOWED_DOT)
                    settings = new Intent(ActivityMain.this, ActivitySettings.class);
                else {
                    settings = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                    if (settings.resolveActivity(getPackageManager()) == null)
                        settings = new Intent(Settings.ACTION_WIFI_SETTINGS);
                }
                startActivity(settings);
            }

            @Override
            public void onSetFilterPref(@NonNull String key, boolean value) {
                prefs.edit().putBoolean(key, value).apply();
            }

            @Override
            public void onSetSortPref(@NonNull String sort) {
                prefs.edit().putString("sort", sort).apply();
            }

            @Override
            public void onOpenLog() {
                if (Util.canFilter(ActivityMain.this))
                    startActivity(new Intent(ActivityMain.this, ActivityLog.class));
                else
                    Toast.makeText(ActivityMain.this, R.string.msg_unavailable, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onInvite() {
                startActivityForResult(getIntentInvite(ActivityMain.this), REQUEST_INVITE);
            }

            @Override
            public void onOpenSupport() {
                startActivity(getIntentSupport(ActivityMain.this));
            }

            @Override
            public void onExportCsv() {
                startActivityForResult(getIntentCreateExport(), REQUEST_EXPORT);
            }

            @Override
            public void onOpenMoreApps() {
                startActivity(getIntentApps(ActivityMain.this));
            }

            @Override
            public void onOpenDialog(@NonNull MainDialog dialog) {
                activeDialog = dialog;
                renderMainScreen();
            }

            @Override
            public void onDismissDialog() {
                activeDialog = MainDialog.NONE;
                renderMainScreen();
            }

            @Override
            public void onOpenBatteryOptimization() {
                try {
                    if (!Util.isPlayStoreInstall()) {
                        //noinspection BatteryLife
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:" + getPackageName())));
                    } else {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString());
                }
            }

            @Override
            public void onOpenDataSaver() {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString());
                }
            }

            @Override
            public void onOpenVpnSettings() {
                try {
                    //noinspection InlinedApi
                    startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString());
                }
            }

            @Override
            public void onRateApp() {
                try {
                    startActivity(getIntentRate(ActivityMain.this));
                } catch (ActivityNotFoundException ignored) {
                }
            }

            @Override
            public void onOpenUrl(@NonNull String url) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        };
    }

    private void toggleProtection(boolean isChecked, SharedPreferences prefs) {
        Log.i(TAG, "Switch=" + isChecked);
        prefs.edit().putBoolean("enabled", isChecked).apply();

        if (isChecked) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                try {
                    String alwaysOn = Settings.Secure.getString(getContentResolver(), "always_on_vpn_app");
                    Log.i(TAG, "Always-on=" + alwaysOn);
                    if (!TextUtils.isEmpty(alwaysOn)) {
                        if (getPackageName().equals(alwaysOn)) {
                            int lockdown = Settings.Secure.getInt(getContentResolver(), "always_on_vpn_lockdown", 0);
                            Log.i(TAG, "Lockdown=" + lockdown);
                            if (lockdown != 0) {
                                prefs.edit().putBoolean("enabled", false).apply();
                                Toast.makeText(this, R.string.msg_always_on_lockdown, Toast.LENGTH_LONG).show();
                                return;
                            }
                        } else {
                            prefs.edit().putBoolean("enabled", false).apply();
                            Toast.makeText(this, R.string.msg_always_on, Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
            }

            try {
                final Intent prepare = VpnService.prepare(this);
                if (prepare == null) {
                    Log.i(TAG, "Prepare done");
                    onActivityResult(REQUEST_VPN, RESULT_OK, null);
                } else {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    final View view = inflater.inflate(R.layout.vpn, null, false);
                    dialogVpn = new MaterialAlertDialogBuilder(this)
                            .setView(view)
                            .setCancelable(false)
                            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                                if (running) {
                                    Log.i(TAG, "Start intent=" + prepare);
                                    try {
                                        startActivityForResult(prepare, REQUEST_VPN);
                                    } catch (Throwable ex) {
                                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                                        onActivityResult(REQUEST_VPN, RESULT_CANCELED, null);
                                        prefs.edit().putBoolean("enabled", false).apply();
                                    }
                                }
                            })
                            .setOnDismissListener(d -> dialogVpn = null)
                            .create();
                    dialogVpn.show();
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                prefs.edit().putBoolean("enabled", false).apply();
            }
        } else {
            ServiceSinkhole.stop("switch off", this, false);
        }
        renderMainScreen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(TAG, "New intent");
        Util.logExtras(intent);
        super.onNewIntent(intent);

        if (Util.hasXposed(this))
            return;

        setIntent(intent);

        if (intent.hasExtra(EXTRA_REFRESH))
            updateApplicationList(intent.getStringExtra(EXTRA_SEARCH));
        else
            updateSearch(intent.getStringExtra(EXTRA_SEARCH));
        checkExtras(intent);
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resume");

        if (Util.hasXposed(this)) {
            super.onResume();
            return;
        }

        DatabaseHelper.getInstance(this).addAccessChangedListener(accessChangedListener);

        boolean localNetworkGranted = LocalNetworkAccess.isGranted(this);
        if (lastLocalNetworkGrant != null && !lastLocalNetworkGrant && localNetworkGranted)
            ServiceSinkhole.reload("permission granted", this, false);
        lastLocalNetworkGrant = localNetworkGranted;

        refreshPrivateDnsBypassBanner();
        renderMainScreen();

        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "Pause");
        super.onPause();

        if (Util.hasXposed(this))
            return;

        uiHandler.removeCallbacks(debouncedAccessRender);
        DatabaseHelper.getInstance(this).removeAccessChangedListener(accessChangedListener);
    }

    @SuppressLint("StaticFieldLeak")
    private void refreshPrivateDnsBypassBanner() {
        renderMainScreen();
        final int generation = ++privateDnsWarningQueryGeneration;
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... ignored) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ActivityMain.this);
                if (!prefs.getBoolean("enabled", false))
                    return ServiceSinkhole.PRIVATE_DNS_WARNING_NONE;
                return ServiceSinkhole.getPrivateDnsWarningState(ActivityMain.this);
            }

            @Override
            protected void onPostExecute(Integer state) {
                if (!running || generation != privateDnsWarningQueryGeneration)
                    return;
                privateDnsWarningState = state;
                renderMainScreen();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Destroy");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (Util.hasXposed(this) || !Util.canFilter(this)
                || prefs.getInt("onboarding_version", 0) < ActivityOnboarding.ONBOARDING_VERSION) {
            super.onDestroy();
            return;
        }

        running = false;

        try {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(onRulesChanged);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(onQueueChanged);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
        try {
            unregisterReceiver(packageChangedReceiver);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        if (dialogVpn != null) {
            dialogVpn.dismiss();
            dialogVpn = null;
        }

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + resultCode + " ok="
                + (resultCode == RESULT_OK));
        Util.logExtras(data);

        if (requestCode == REQUEST_VPN) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", resultCode == RESULT_OK).apply();
            if (resultCode == RESULT_OK) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                Intent alarmIntent = new Intent(WidgetAdmin.INTENT_ON);
                alarmIntent.setPackage(getPackageName());
                PendingIntent pi = PendingIntentCompat.getBroadcast(
                        this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT, false);
                am.cancel(pi);
                ServiceSinkhole.start("prepared", this, true);
            } else if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, R.string.msg_vpn_cancelled, Toast.LENGTH_LONG).show();
            }
            renderMainScreen();
        } else if (requestCode == REQUEST_INVITE) {
            // Do nothing
        } else if (requestCode == REQUEST_DETAILS_UPDATED) {
            renderMainScreen();
        } else if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleExport(data);
        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void handleExport(Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                Uri target = data.getData();
                if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                    target = Uri.parse(target + "/trackercontrol_log_"
                            + new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date().getTime()) + ".csv");
                Log.i(TAG, "Writing URI=" + target);

                try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    csvExport(out);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (running) {
                    if (ex == null)
                        Toast.makeText(ActivityMain.this, R.string.exported, Toast.LENGTH_LONG).show();
                    else
                        Toast.makeText(ActivityMain.this, R.string.export_failed, Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void csvExport(OutputStream out) throws IOException {
        PackageManager pm = getPackageManager();
        try (CSVWriter csv = new CSVWriter(new OutputStreamWriter(out),
                CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.DEFAULT_QUOTE_CHARACTER,
                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                CSVWriter.RFC4180_LINE_END)) {

            try (Cursor data = DatabaseHelper.getInstance(this).getHosts()) {
                if (data == null)
                    throw new IOException("Could not read hosts.");

                List<String> columnNames = new ArrayList<>();
                Collections.addAll(columnNames, data.getColumnNames());
                columnNames.add("Tracker");
                columnNames.add("Category");
                columnNames.add("Package");
                columnNames.add("App");

                csv.writeNext(columnNames.toArray(new String[0]));
                while (data.moveToNext()) {
                    String[] row = new String[data.getColumnNames().length + 4];
                    for (int i = 0; i < data.getColumnNames().length; i++) {
                        row[i] = data.getString(i);
                    }

                    String hostname = data.getString(data.getColumnIndexOrThrow("daddr"));
                    Tracker tracker = TrackerList.findTracker(hostname);
                    if (tracker != null) {
                        row[data.getColumnNames().length] = tracker.getName();
                        row[data.getColumnNames().length + 1] = tracker.getCategory();
                    } else {
                        row[data.getColumnNames().length] = "";
                        row[data.getColumnNames().length + 1] = "";
                    }

                    try {
                        String pkg = pm.getNameForUid(data.getInt(data.getColumnIndexOrThrow("uid")));
                        ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                        String name = pm.getApplicationLabel(info).toString();

                        row[data.getColumnNames().length + 2] = pkg;
                        row[data.getColumnNames().length + 3] = name;
                    } catch (PackageManager.NameNotFoundException e) {
                        row[data.getColumnNames().length + 2] = "";
                        row[data.getColumnNames().length + 3] = "";
                    }

                    csv.writeNext(row);
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ROAMING) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ServiceSinkhole.reload("permission granted", this, false);
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.POST_NOTIFICATIONS)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } catch (Throwable ex) {
                    Log.e(TAG, ex + "\n" + ex.getStackTrace());
                }
            }
            renderMainScreen();
        } else if (requestCode == REQUEST_LOCAL_NETWORK) {
            boolean granted = (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED);
            lastLocalNetworkGrant = LocalNetworkAccess.isGranted(this);
            renderMainScreen();
            if (granted)
                ServiceSinkhole.reload("permission granted", this, false);
            else if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                    LocalNetworkAccess.PERMISSION)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } catch (Throwable ex) {
                    Log.e(TAG, ex + "\n" + ex.getStackTrace());
                }
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        Log.i(TAG, "Preference " + name + "=" + prefs.getAll().get(name));
        if ("enabled".equals(name) || "log_logcat".equals(name) || "blocking_mode".equals(name)) {
            renderMainScreen();
        } else if ("block_dot".equals(name) || "log".equals(name)) {
            refreshPrivateDnsBypassBanner();
        } else if ("show_user".equals(name) ||
                "show_system".equals(name) ||
                "show_nointernet".equals(name) ||
                "show_unprotected".equals(name) ||
                "show_frozen".equals(name) ||
                "sort".equals(name) ||
                "imported".equals(name) ||
                "manage_system".equals(name)) {
            renderMainScreen();
            updateApplicationList(null);
        }
    }

    private final DatabaseHelper.AccessChangedListener accessChangedListener = new DatabaseHelper.AccessChangedListener() {
        @Override
        public void onChanged() {
            uiHandler.removeCallbacks(debouncedAccessRender);
            uiHandler.postDelayed(debouncedAccessRender, 500);
        }
    };

    private final BroadcastReceiver onRulesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            if (ACTION_PRIVATE_DNS_WARNING_CHANGED.equals(intent.getAction())) {
                privateDnsWarningQueryGeneration++;
                privateDnsWarningState = intent.getIntExtra(EXTRA_PRIVATE_DNS_WARNING_STATE,
                        ServiceSinkhole.PRIVATE_DNS_WARNING_NONE);
                renderMainScreen();
                return;
            }

            if (intent.hasExtra(EXTRA_CONNECTED) && intent.hasExtra(EXTRA_METERED)) {
                networkActive = Util.isNetworkActive(ActivityMain.this);
                meteredActive = intent.getBooleanExtra(EXTRA_CONNECTED, false)
                        && Util.isMeteredNetwork(ActivityMain.this);
                renderMainScreen();
                if (intent.getBooleanExtra(EXTRA_REFRESH, false))
                    updateApplicationList(null);
            } else {
                updateApplicationList(null);
            }
        }
    };

    private final BroadcastReceiver onQueueChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            int size = intent.getIntExtra(EXTRA_SIZE, -1);
            queueActive = size > 0;
            renderMainScreen();
        }
    };

    private final BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            updateApplicationList(null);
        }
    };

    private void selectTab(int tab) {
        selectedTab = tab;
        if (tab == MainTabs.VPN)
            ensureVpnFragment();
        if (tab != MainTabs.APPS && searchOpen) {
            searchOpen = false;
            searchQuery = "";
        }
        renderMainScreen();
    }

    private void ensureVpnFragment() {
        if (getSupportFragmentManager().findFragmentById(R.id.vpnContainer) != null)
            return;
        if (vpnFragmentRequested)
            return;
        vpnFragmentRequested = true;
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.vpnContainer, new VpnFragment())
                .commit();
    }

    private void checkExtras(Intent intent) {
        if (intent.hasExtra(EXTRA_APPROVE)) {
            Log.i(TAG, "Requesting VPN approval");
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            toggleProtection(!prefs.getBoolean("enabled", false), prefs);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void updateApplicationList(final String search) {
        Log.i(TAG, "Update search=" + search);
        final int generation = ++rulesUpdateGeneration;
        refreshingApps = true;
        renderMainScreen();

        new AsyncTask<Object, Object, Pair<List<Rule>, List<Rule>>>() {
            @Override
            protected Pair<List<Rule>, List<Rule>> doInBackground(Object... arg) {
                List<Rule> rules = Rule.getRules(false, true, ActivityMain.this);
                List<Rule> pool = new ArrayList<>(rules);
                Set<String> present = new HashSet<>();
                for (Rule rule : rules)
                    present.add(rule.packageName);
                try {
                    for (Rule stub : Rule.getSearchStubs(getApplicationContext()))
                        if (!present.contains(stub.packageName))
                            pool.add(stub);
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
                return new Pair<>(rules, pool);
            }

            @Override
            protected void onPostExecute(Pair<List<Rule>, List<Rule>> result) {
                if (running && generation == rulesUpdateGeneration) {
                    allRules.clear();
                    if (result != null && result.first != null)
                        allRules.addAll(result.first);
                    searchPool = result != null ? result.second : null;
                    refreshingApps = false;
                    if (search != null) {
                        selectedTab = MainTabs.APPS;
                        searchOpen = true;
                        searchQuery = search;
                    }
                    renderMainScreen();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void updateSearch(String search) {
        if (search != null) {
            selectedTab = MainTabs.APPS;
            searchOpen = true;
            searchQuery = search;
            renderMainScreen();
        }
    }

    private synchronized List<Rule> getSearchPool() {
        if (searchPool != null)
            return searchPool;

        List<Rule> pool = new ArrayList<>(allRules);
        Set<String> present = new HashSet<>();
        for (Rule rule : allRules)
            present.add(rule.packageName);
        try {
            for (Rule stub : Rule.getSearchStubs(getApplicationContext()))
                if (!present.contains(stub.packageName))
                    pool.add(stub);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        searchPool = pool;
        return pool;
    }

    private void renderMainScreen() {
        if (!running || screenController == null)
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("enabled", false);

        boolean canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;

        int bypassState = enabled ? privateDnsWarningState : ServiceSinkhole.PRIVATE_DNS_WARNING_NONE;
        String bypassMessage = null;
        if (bypassState == ServiceSinkhole.PRIVATE_DNS_WARNING_HOSTNAME) {
            String specifier = Util.getPrivateDnsSpecifier(this);
            String resolver = TextUtils.isEmpty(specifier)
                    ? getString(R.string.msg_private_dns_unknown_resolver)
                    : specifier;
            bypassMessage = getString(R.string.msg_private_dns_bypass_notify, resolver);
        } else if (bypassState == ServiceSinkhole.PRIVATE_DNS_WARNING_ALLOWED_DOT) {
            bypassMessage = getString(R.string.msg_private_dns_bypass_allowed_notify);
        }

        MainBannerState banners = new MainBannerState(
                false,
                prefs.getBoolean("log_logcat", false),
                !canNotify,
                LocalNetworkAccess.isMissing(this),
                enabled && Util.isPrivateDnsBlocked(this),
                bypassMessage);

        String sort = prefs.getString("sort", "trackers_week");
        MainFilterState filter = new MainFilterState(
                prefs.getBoolean("manage_system", false),
                prefs.getBoolean("show_user", true),
                prefs.getBoolean("show_system", false),
                prefs.getBoolean("show_nointernet", true),
                prefs.getBoolean("show_unprotected", false),
                prefs.getBoolean("show_frozen", false),
                sort);

        List<Rule> filteredRules;
        String queryStr = searchQuery == null ? "" : searchQuery.toLowerCase(Locale.ROOT).trim();
        if (queryStr.isEmpty()) {
            filteredRules = allRules;
        } else {
            filteredRules = new ArrayList<>();
            int uid;
            try {
                uid = Integer.parseInt(queryStr);
            } catch (NumberFormatException ignore) {
                uid = -1;
            }
            for (Rule rule : getSearchPool()) {
                if (rule.uid == uid ||
                        rule.packageName.toLowerCase(Locale.ROOT).contains(queryStr) ||
                        (rule.name != null && rule.name.toLowerCase(Locale.ROOT).contains(queryStr))) {
                    filteredRules.add(rule);
                }
            }
        }

        InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(this);
        SharedPreferences minimalOnlyPrefs = getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);
        boolean pastWeekOnly = !"trackers_all".equals(sort);

        List<AppRuleUi> appItems = new ArrayList<>(filteredRules.size());
        for (Rule rule : filteredRules) {
            boolean blockedInternet = internetBlocklist.blockedInternet(rule.uid);
            AppProtectionState state = AppProtectionState.resolve(
                    rule.apply,
                    rule.tracker_protect,
                    blockedInternet,
                    BlockingMode.isMinimalOnlyApp(this, minimalOnlyPrefs, rule.packageName));
            boolean activeProtection = state != AppProtectionState.BYPASSED;

            int trackerCount = rule.getTrackerCount(pastWeekOnly);
            String detailsText = "";
            if (trackerCount > 0) {
                detailsText = getResources().getQuantityString(
                        pastWeekOnly ? R.plurals.n_companies_found_week : R.plurals.n_companies_found,
                        trackerCount,
                        trackerCount);
            } else if (!rule.internet) {
                detailsText = getString(R.string.no_internet);
            }

            String toggleCd;
            if (!rule.internet)
                toggleCd = getString(R.string.toggle_no_internet_description, rule.name);
            else if (!activeProtection)
                toggleCd = getString(R.string.toggle_internet_unavailable_description, rule.name);
            else if (blockedInternet)
                toggleCd = getString(R.string.toggle_allow_internet_description, rule.name);
            else
                toggleCd = getString(R.string.toggle_block_internet_description, rule.name);

            appItems.add(new AppRuleUi(
                    rule.uid,
                    rule.packageName,
                    rule.name == null ? rule.packageName : rule.name,
                    rule.internet,
                    rule.system,
                    rule.changed,
                    activeProtection,
                    blockedInternet,
                    detailsText,
                    toggleCd));
        }

        String mode = BlockingMode.getMode(this);
        String modeLabel;
        if (BlockingMode.MODE_STRICT.equals(mode))
            modeLabel = getString(R.string.blocking_mode_strict);
        else if (BlockingMode.MODE_MINIMAL.equals(mode))
            modeLabel = getString(R.string.blocking_mode_minimal);
        else
            modeLabel = getString(R.string.blocking_mode_standard);

        TroubleshootingState troubleshooting = new TroubleshootingState(
                Util.batteryOptimizing(this),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Util.dataSaving(this),
                LocalNetworkAccess.isEnforced(),
                LocalNetworkAccess.isGranted(this));

        boolean system = prefs.getBoolean("manage_system", false);
        screenController.update(new MainScreenModel(
                selectedTab,
                enabled,
                modeLabel,
                networkActive,
                queueActive,
                meteredActive,
                searchQuery,
                searchOpen,
                refreshingApps,
                prefs.getBoolean("hint_push", true),
                !system && prefs.getBoolean("hint_system", true),
                banners,
                filter,
                appItems,
                cachedShowInvite,
                cachedShowSupport,
                cachedEnableMoreApps,
                cachedShowRate,
                Util.getSelfVersionName(this),
                Util.getSelfVersionCode(this),
                cachedCopyright,
                activeDialog,
                troubleshooting));
    }

    private Intent getIntentCreateExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE,
                "trackercontrol_log_" + new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date().getTime()) + ".csv");
        return intent;
    }

    private static Intent getIntentInvite(Context context) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name));
        intent.putExtra(Intent.EXTRA_TEXT, context.getString(R.string.msg_try)
                + "\n\nhttps://github.com/TrackerControl/tracker-control-android\n\n");
        return intent;
    }

    private static Intent getIntentApps(Context context) {
        return new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/dev?id=8420080860664580239"));
    }

    private static Intent getIntentRate(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.getPackageName()));
        if (intent.resolveActivity(context.getPackageManager()) == null)
            intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + context.getPackageName()));
        return intent;
    }

    private static Intent getIntentSupport(Context context) {
        return new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/TrackerControl/tracker-control-android#support-trackercontrol"));
    }
}
