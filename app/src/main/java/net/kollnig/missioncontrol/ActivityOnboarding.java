/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2026 Konrad Kollnig
 */

package net.kollnig.missioncontrol;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.ui.compose.OnboardingScreen;
import net.kollnig.missioncontrol.ui.compose.OnboardingScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.OnboardingScreenController;
import net.kollnig.missioncontrol.ui.compose.OnboardingScreenModel;
import net.kollnig.missioncontrol.ui.compose.OnboardingSlideUi;

import java.util.ArrayList;
import java.util.List;

import eu.faircode.netguard.ActivityMain;
import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class ActivityOnboarding extends AppCompatActivity {
    public static final int ONBOARDING_VERSION = 2;

    private OnboardingScreenController screenController;
    private boolean includeWhatsNew = false;
    private boolean includeVpnSlide = false;
    private boolean includeLockdownSlide = false;
    private boolean includeNotifySlide = false;

    private boolean isLockdownEnabled() {
        try {
            int lockdown = Settings.Secure.getInt(getContentResolver(), "always_on_vpn_lockdown", 0);
            return lockdown != 0;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int seenVersion = prefs.getInt("onboarding_version", 0);
        includeWhatsNew = seenVersion > 0 && seenVersion < ONBOARDING_VERSION;
        includeVpnSlide = VpnService.prepare(this) != null;
        includeLockdownSlide = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
        includeNotifySlide = Build.VERSION.SDK_INT >= 33 && !Util.canNotify(this);

        ComposeView composeView = new ComposeView(this);
        setContentView(composeView);

        BlockingMode.enforcePlayStoreMode(this);

        screenController = OnboardingScreen.install(composeView, new OnboardingScreenCallbacks() {
            @Override
            public void onSlideAction(int titleResId) {
                handleSlideAction(titleResId);
            }

            @Override
            public void onSelectBlockingOption(@NonNull String option) {
                applyBlockingOption(option);
                renderSlides();
            }

            @Override
            public void onNextOrFinish(int currentPage, @NonNull OnboardingSlideUi slide) {
                int lastIndex = screenController.getModel().getSlides().size() - 1;
                if (currentPage < lastIndex) {
                    Runnable advance = () -> screenController.requestPage(currentPage + 1);
                    if (slide.getWarningResId() != 0) {
                        Util.areYouSure(ActivityOnboarding.this, slide.getWarningResId(), advance::run);
                    } else {
                        advance.run();
                    }
                } else {
                    finishOnboarding();
                }
            }
        });

        renderSlides();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderSlides();
    }

    private void renderSlides() {
        if (screenController == null)
            return;

        List<OnboardingSlideUi> slides = new ArrayList<>();

        slides.add(new OnboardingSlideUi(
                R.string.onboarding_welcome_title,
                getString(R.string.onboarding_welcome_title),
                getText(R.string.onboarding_welcome_desc),
                R.drawable.ic_rocket2,
                null,
                false,
                0,
                false));

        if (includeWhatsNew) {
            slides.add(new OnboardingSlideUi(
                    R.string.onboarding_whatsnew_title,
                    getString(R.string.onboarding_whatsnew_title),
                    getText(R.string.onboarding_whatsnew_desc),
                    R.drawable.ic_rocket2,
                    null,
                    false,
                    0,
                    false));
        }

        slides.add(new OnboardingSlideUi(
                R.string.onboarding_blockingmode_title,
                getString(R.string.onboarding_blockingmode_title),
                getText(R.string.onboarding_blockingmode_desc),
                R.drawable.ic_rocket2,
                null,
                false,
                0,
                true));

        if (includeVpnSlide) {
            boolean vpnPrepared = VpnService.prepare(this) == null;
            slides.add(new OnboardingSlideUi(
                    R.string.onboarding_vpn_title,
                    getString(R.string.onboarding_vpn_title),
                    getText(R.string.onboarding_vpn_desc),
                    R.drawable.lockdown,
                    vpnPrepared ? getString(R.string.onboarding_action_granted)
                            : getString(R.string.onboarding_vpn_action),
                    !vpnPrepared,
                    vpnPrepared ? 0 : R.string.onboarding_vpn_sure,
                    false));
        }

        if (includeLockdownSlide) {
            String actionText;
            boolean actionEnabled;
            int warningResId;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                boolean lockdownEnabled = isLockdownEnabled();
                actionText = lockdownEnabled ? getString(R.string.onboarding_lockdown_action)
                        : getString(R.string.onboarding_action_disabled);
                actionEnabled = lockdownEnabled;
                warningResId = lockdownEnabled ? R.string.onboarding_lockdown_sure : 0;
            } else {
                actionText = getString(R.string.onboarding_lockdown_action);
                actionEnabled = true;
                warningResId = 0;
            }
            slides.add(new OnboardingSlideUi(
                    R.string.onboarding_lockdown_title,
                    getString(R.string.onboarding_lockdown_title),
                    getText(R.string.onboarding_lockdown_desc),
                    R.drawable.lockdown,
                    actionText,
                    actionEnabled,
                    warningResId,
                    false));
        }

        if (includeNotifySlide) {
            boolean canNotify = Util.canNotify(this);
            slides.add(new OnboardingSlideUi(
                    R.string.onboarding_notify_title,
                    getString(R.string.onboarding_notify_title),
                    getText(R.string.onboarding_notify_desc),
                    R.drawable.twotone_notifications_24,
                    canNotify ? getString(R.string.onboarding_action_granted)
                            : getString(R.string.onboarding_notify_action),
                    !canNotify,
                    canNotify ? 0 : R.string.onboarding_notify_sure,
                    false));
        }

        slides.add(new OnboardingSlideUi(
                R.string.onboarding_timeline_title,
                getString(R.string.onboarding_timeline_title),
                getText(R.string.onboarding_timeline_desc),
                R.drawable.ic_history,
                null,
                false,
                0,
                false));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean researchPreset = prefs.getBoolean("log_logcat", false);
        String currentMode = BlockingMode.getMode(this);
        String selectedOption;
        if (researchPreset)
            selectedOption = "research";
        else if (BlockingMode.MODE_MINIMAL.equals(currentMode))
            selectedOption = "minimal";
        else if (BlockingMode.MODE_STRICT.equals(currentMode))
            selectedOption = "strict";
        else
            selectedOption = "standard";

        screenController.update(new OnboardingScreenModel(
                slides,
                selectedOption,
                Util.isPlayStoreInstall(this)));
    }

    private void handleSlideAction(int titleResId) {
        if (titleResId == R.string.onboarding_vpn_title) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                try {
                    String alwaysOn = Settings.Secure.getString(
                            getContentResolver(), "always_on_vpn_app");
                    if (!TextUtils.isEmpty(alwaysOn)
                            && !getPackageName().equals(alwaysOn)) {
                        showAlwaysOnVpnBlockedDialog();
                        return;
                    }
                } catch (Throwable ex) {
                    Log.e("Onboarding", ex.toString());
                }
            }
            try {
                Intent intent = VpnService.prepare(this);
                if (intent != null) {
                    startActivityForResult(intent, 0);
                }
            } catch (Throwable ex) {
                Log.e("Onboarding", ex.toString());
                showAlwaysOnVpnBlockedDialog();
            }
        } else if (titleResId == R.string.onboarding_notify_title) {
            ActivityCompat.requestPermissions(this,
                    //noinspection InlinedApi
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (titleResId == R.string.onboarding_lockdown_title) {
            try {
                //noinspection InlinedApi
                startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
            } catch (Throwable ex) {
                Log.e("Onboarding", ex.toString());
            }
        }
    }

    private void applyBlockingOption(String option) {
        String mode = BlockingMode.MODE_STANDARD;
        boolean enableSni = false;
        boolean enableAdbLogging = false;
        boolean enableDotBlocking = true;

        if ("minimal".equals(option))
            mode = BlockingMode.MODE_MINIMAL;
        else if ("strict".equals(option))
            mode = BlockingMode.MODE_STRICT;
        else if ("research".equals(option)) {
            mode = BlockingMode.MODE_MINIMAL;
            enableSni = true;
            enableAdbLogging = true;
            enableDotBlocking = false;
        }

        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(BlockingMode.PREF_BLOCKING_MODE, mode)
                .putBoolean("filter", true)
                .putBoolean("sni_enabled", enableSni)
                .putBoolean("log_logcat", enableAdbLogging)
                .putBoolean("block_dot", enableDotBlocking)
                .apply();

        BlockingMode.applyMode(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0 && resultCode != RESULT_OK) {
            if (VpnService.prepare(this) != null) {
                showAlwaysOnVpnBlockedDialog();
            }
        }
    }

    private void showAlwaysOnVpnBlockedDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.onboarding_vpn_blocked_title)
                .setMessage(Html.fromHtml(getString(R.string.onboarding_vpn_blocked_desc)))
                .setPositiveButton(R.string.onboarding_vpn_blocked_action,
                        (dialog, which) -> {
                            try {
                                //noinspection InlinedApi
                                startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
                            } catch (Throwable ex) {
                                Log.e("Onboarding", ex.toString());
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void finishOnboarding() {
        boolean vpnPrepared = VpnService.prepare(this) == null;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putInt("onboarding_version", ONBOARDING_VERSION)
                .putBoolean("enabled", vpnPrepared)
                .apply();

        if (vpnPrepared)
            ServiceSinkhole.start("onboarding", this, true);

        startActivity(new Intent(this, ActivityMain.class));
        finish();
    }
}
