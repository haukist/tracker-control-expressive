package net.kollnig.missioncontrol.vpn;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.kollnig.missioncontrol.ActivityWireGuardProfiles;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.ui.compose.TrackerControlThemeKt;
import net.kollnig.missioncontrol.ui.compose.VpnCountryUi;
import net.kollnig.missioncontrol.ui.compose.VpnProfileUi;
import net.kollnig.missioncontrol.ui.compose.VpnScreen;
import net.kollnig.missioncontrol.ui.compose.VpnScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.VpnScreenController;
import net.kollnig.missioncontrol.ui.compose.VpnScreenModel;
import net.kollnig.missioncontrol.wg.IvpnProfileGenerator;
import net.kollnig.missioncontrol.wg.MullvadProfileGenerator;
import net.kollnig.missioncontrol.wg.WgConfigParser;
import net.kollnig.missioncontrol.wg.WgProfileManager;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.ActivitySettings;
import eu.faircode.netguard.ServiceSinkhole;

public class VpnFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREF_VPN_MODE = "vpn_mode";
    private static final String MODE_MULLVAD = "mullvad";
    private static final String MODE_IVPN = "ivpn";
    private static final String MODE_WIREGUARD = "wireguard";

    private SharedPreferences prefs;
    private WgProfileManager manager;
    private VpnScreenController screenController;
    private final List<VpnCountry> countries = new ArrayList<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<VpnCountry> mullvadCountryCache = new ArrayList<>();
    private final List<VpnCountry> ivpnCountryCache = new ArrayList<>();

    private boolean loadingCountries;
    private boolean progressVisible;
    private boolean countryErrorVisible;
    private String progressMessage = "";
    private String generatingCountryCode = "";
    private String generatingProvider = "";

    private static class VpnCountry {
        final String provider;
        final String code;
        final String name;

        VpnCountry(String provider, String code, String name) {
            this.provider = provider;
            this.code = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
            this.name = name == null ? "" : name;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return new ComposeView(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        manager = new WgProfileManager(requireContext());
        manager.migrateIfNeeded();

        screenController = VpnScreen.install((ComposeView) view, new VpnScreenCallbacks() {
            @Override
            public void onToggleEnabled(boolean enabled) {
                toggleVpnEnabled(enabled);
            }

            @Override
            public void onSelectProviderMode(@NonNull String mode) {
                setProviderMode(mode);
            }

            @Override
            public void onOpenSettings() {
                if (isWireGuardMode())
                    startActivity(new Intent(requireContext(), ActivitySettings.class));
                else if (isIvpnMode())
                    showIvpnSettingsDialog(null, "", "", null);
                else
                    showMullvadSettingsDialog();
            }

            @Override
            public void onOpenMullvadSetup() {
                openMullvadSetup();
            }

            @Override
            public void onOpenMullvadAccountPage() {
                openMullvadAccountPage();
            }

            @Override
            public void onOpenIvpnSetup() {
                openIvpnSetup();
            }

            @Override
            public void onOpenIvpnAccountPage() {
                openIvpnAccountPage();
            }

            @Override
            public void onOpenWireGuardProfiles(@Nullable String setup) {
                openWireGuardProfiles(setup);
            }

            @Override
            public void onRetryCountries() {
                loadCountries(true);
            }

            @Override
            public void onSelectCountry(@NonNull VpnCountryUi country) {
                generateCountry(new VpnCountry(country.getProvider(), country.getCode(), country.getName()));
            }

            @Override
            public void onSelectCustomProfile(@NonNull VpnProfileUi profile) {
                for (WgProfileManager.Profile item : customProfiles()) {
                    if (item.id.equals(profile.getId())) {
                        activateCustomProfile(item);
                        break;
                    }
                }
            }
        });

        refreshUi();
        if (!isFirstTimeVpnSetup() && !isWireGuardMode())
            loadCountries(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshUi();
    }

    @Override
    public void onPause() {
        super.onPause();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("wg_enabled".equals(key) ||
                WgProfileManager.PREF_WG_PROFILE.equals(key) ||
                WgProfileManager.PREF_WG_PROFILES.equals(key) ||
                PREF_VPN_MODE.equals(key) ||
                WgProfileManager.PREF_MULLVAD_ACCOUNT.equals(key) ||
                WgProfileManager.PREF_IVPN_ACCOUNT.equals(key)) {
            refreshUi();
        }
    }

    private void loadCountries(boolean force) {
        if (loadingCountries)
            return;
        String provider = currentProviderMode();
        if (MODE_WIREGUARD.equals(provider))
            return;
        if (!hasProviderAccount(provider)) {
            countryErrorVisible = false;
            clearProgress();
            setCountries(savedProviderCountries(provider));
            return;
        }

        List<VpnCountry> cache = MODE_IVPN.equals(provider) ? ivpnCountryCache : mullvadCountryCache;
        if (!force && !cache.isEmpty()) {
            setCountries(cache);
            return;
        }

        loadingCountries = true;
        countryErrorVisible = false;
        setProgress(getString(R.string.vpn_loading_countries));

        executor.execute(() -> {
            try {
                List<VpnCountry> countries = fetchCountryOptions(provider);
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    loadingCountries = false;
                    String currentProvider = currentProviderMode();
                    if (!provider.equals(currentProvider)) {
                        if (!MODE_WIREGUARD.equals(currentProvider))
                            loadCountries(false);
                        return;
                    }
                    countryErrorVisible = false;
                    clearProgress();
                    cache.clear();
                    cache.addAll(countries);
                    setCountries(cache);
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    loadingCountries = false;
                    String currentProvider = currentProviderMode();
                    if (!provider.equals(currentProvider)) {
                        if (!MODE_WIREGUARD.equals(currentProvider))
                            loadCountries(false);
                        return;
                    }
                    countryErrorVisible = true;
                    clearProgress();
                    setCountries(savedProviderCountries(provider));
                    Toast.makeText(requireContext(),
                            getString(R.string.vpn_country_load_failed,
                                    providerDisplayName(provider), ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private List<VpnCountry> fetchCountryOptions(String provider) throws Exception {
        List<VpnCountry> countries = new ArrayList<>();
        if (MODE_IVPN.equals(provider)) {
            for (IvpnProfileGenerator.CountryOption country :
                    new IvpnProfileGenerator().fetchCountryOptions())
                countries.add(new VpnCountry(MODE_IVPN, country.code, country.name));
        } else {
            for (MullvadProfileGenerator.CountryOption country :
                    new MullvadProfileGenerator().fetchCountryOptions())
                countries.add(new VpnCountry(MODE_MULLVAD, country.code, country.name));
        }
        return countries;
    }

    private List<VpnCountry> savedProviderCountries(String provider) {
        Map<String, String> saved = new LinkedHashMap<>();
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if (provider.equals(profile.provider) &&
                    !TextUtils.isEmpty(profile.countryCode) &&
                    !TextUtils.isEmpty(profile.countryName))
                saved.put(profile.countryCode, profile.countryName);

        List<VpnCountry> countries = new ArrayList<>();
        for (Map.Entry<String, String> entry : saved.entrySet())
            countries.add(new VpnCountry(provider, entry.getKey(), entry.getValue()));
        return countries;
    }

    private boolean hasProviderAccount(String provider) {
        if (MODE_IVPN.equals(provider))
            return !TextUtils.isEmpty(manager.getLastIvpnAccount());
        if (MODE_MULLVAD.equals(provider))
            return !TextUtils.isEmpty(manager.getLastMullvadAccount());
        return false;
    }

    private List<WgProfileManager.Profile> customProfiles() {
        List<WgProfileManager.Profile> profiles = new ArrayList<>();
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if (!"mullvad".equals(profile.provider) && !"ivpn".equals(profile.provider))
                profiles.add(profile);
        return profiles;
    }

    private String currentProviderMode() {
        String mode = prefs.getString(PREF_VPN_MODE, "");
        if (MODE_MULLVAD.equals(mode) || MODE_IVPN.equals(mode) || MODE_WIREGUARD.equals(mode))
            return mode;

        WgProfileManager.Profile active = manager.getActiveProfile();
        if (active != null) {
            if ("mullvad".equals(active.provider))
                return MODE_MULLVAD;
            if ("ivpn".equals(active.provider))
                return MODE_IVPN;
            return MODE_WIREGUARD;
        }
        if (hasMullvadSetup())
            return MODE_MULLVAD;
        if (hasIvpnSetup())
            return MODE_IVPN;
        if (!customProfiles().isEmpty())
            return MODE_WIREGUARD;
        return MODE_MULLVAD;
    }

    private boolean isWireGuardMode() {
        return MODE_WIREGUARD.equals(currentProviderMode());
    }

    private boolean isIvpnMode() {
        return MODE_IVPN.equals(currentProviderMode());
    }

    private boolean isMullvadMode() {
        return MODE_MULLVAD.equals(currentProviderMode());
    }

    private boolean isFirstTimeVpnSetup() {
        return customProfiles().isEmpty() && !hasMullvadSetup() && !hasIvpnSetup();
    }

    private boolean hasMullvadSetup() {
        if (!TextUtils.isEmpty(prefs.getString(WgProfileManager.PREF_MULLVAD_ACCOUNT, "")))
            return true;
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if ("mullvad".equals(profile.provider) &&
                    (!TextUtils.isEmpty(profile.account) ||
                            !TextUtils.isEmpty(profile.countryCode) ||
                            !TextUtils.isEmpty(profile.config)))
                return true;
        return false;
    }

    private boolean hasIvpnSetup() {
        if (!TextUtils.isEmpty(prefs.getString(WgProfileManager.PREF_IVPN_ACCOUNT, "")))
            return true;
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if ("ivpn".equals(profile.provider) &&
                    (!TextUtils.isEmpty(profile.account) ||
                            !TextUtils.isEmpty(profile.countryCode) ||
                            !TextUtils.isEmpty(profile.config)))
                return true;
        return false;
    }

    private String providerDisplayName(String provider) {
        return MODE_IVPN.equals(provider)
                ? getString(R.string.vpn_provider_ivpn)
                : getString(R.string.vpn_provider_mullvad);
    }

    private void openMullvadSetup() {
        prefs.edit().putString(PREF_VPN_MODE, MODE_MULLVAD).apply();
        showMullvadSettingsDialog();
    }

    private void openMullvadAccountPage() {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://mullvad.net/en/account/create")));
    }

    private void openIvpnSetup() {
        prefs.edit().putString(PREF_VPN_MODE, MODE_IVPN).apply();
        showIvpnSettingsDialog(null, "", "", null);
    }

    private void openIvpnAccountPage() {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.ivpn.net/pricing/")));
    }

    private void openWireGuardProfiles() {
        openWireGuardProfiles(null);
    }

    private void openWireGuardProfiles(String setup) {
        prefs.edit().putString(PREF_VPN_MODE, MODE_WIREGUARD).apply();
        Intent intent = new Intent(requireContext(), ActivityWireGuardProfiles.class);
        if (setup != null)
            intent.putExtra(ActivityWireGuardProfiles.EXTRA_SETUP, setup);
        startActivity(intent);
    }

    private void setProviderMode(String mode) {
        prefs.edit().putString(PREF_VPN_MODE, mode).apply();
        if (MODE_WIREGUARD.equals(mode)) {
            countryErrorVisible = false;
            if (loadingCountries)
                clearProgress();
        } else {
            List<VpnCountry> cache = MODE_IVPN.equals(mode)
                    ? ivpnCountryCache
                    : mullvadCountryCache;
            setCountries(cache.isEmpty() ? savedProviderCountries(mode) : cache);
            loadCountries(false);
        }
        refreshUi();
    }

    private boolean canEnableWireGuard() {
        if (VpnEgressPolicy.canEnable(prefs, "wg_enabled")) return true;
        Toast.makeText(requireContext(), R.string.msg_ts_exclusive, Toast.LENGTH_LONG).show();
        return false;
    }

    private void activateCustomProfile(WgProfileManager.Profile profile) {
        if (!canEnableWireGuard()) return;
        manager.setActiveProfile(profile.id);
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString(PREF_VPN_MODE, MODE_WIREGUARD)
                .apply();
        ServiceSinkhole.reload("wireguard profile changed", requireContext(), false);
        refreshUi();
    }

    private void generateCountry(VpnCountry country) {
        if (MODE_IVPN.equals(country.provider)) {
            generateIvpnCountry(country, "", "");
            return;
        }
        generateMullvadCountry(country);
    }

    private void generateMullvadCountry(VpnCountry country) {
        if (!canEnableWireGuard()) return;
        String account = manager.getLastMullvadAccount();
        if (TextUtils.isEmpty(account)) {
            showMullvadSettingsDialog();
            return;
        }
        if (!TextUtils.isEmpty(generatingCountryCode))
            return;

        generatingCountryCode = country.code;
        generatingProvider = MODE_MULLVAD;
        setProgress(getString(R.string.vpn_generating));

        executor.execute(() -> {
            try {
                MullvadProfileGenerator.GeneratedProfile generated =
                        new MullvadProfileGenerator().generate(account, country.code,
                                manager.getReusableMullvadConfig(account));
                WgConfigParser.INSTANCE.parse(generated.config);
                mainHandler.post(() -> {
                    if (isAdded())
                        saveGeneratedProfile(generated);
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    generatingCountryCode = "";
                    generatingProvider = "";
                    clearProgress();
                    refreshUi();
                    Toast.makeText(requireContext(),
                            getString(R.string.vpn_generation_failed, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void generateIvpnCountry(VpnCountry country, String captchaId, String captchaValue) {
        if (!canEnableWireGuard()) return;
        String account = manager.getLastIvpnAccount();
        if (TextUtils.isEmpty(account)) {
            showIvpnSettingsDialog(null, "", "", country);
            return;
        }
        if (!TextUtils.isEmpty(generatingCountryCode))
            return;

        generatingCountryCode = country.code;
        generatingProvider = MODE_IVPN;
        setProgress(getString(R.string.vpn_generating));

        executor.execute(() -> {
            try {
                IvpnProfileGenerator.GeneratedProfile generated =
                        new IvpnProfileGenerator().generate(account, country.code,
                                manager.getIvpnSession(account), captchaId, captchaValue);
                WgConfigParser.INSTANCE.parse(generated.config);
                mainHandler.post(() -> {
                    if (isAdded())
                        saveGeneratedIvpnProfile(generated);
                });
            } catch (IvpnProfileGenerator.CaptchaRequiredException ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    generatingCountryCode = "";
                    generatingProvider = "";
                    clearProgress();
                    showIvpnSettingsDialog(ex, ex.captchaId, ex.captchaImage, country);
                });
            } catch (Throwable ex) {
                mainHandler.post(() -> {
                    if (!isAdded())
                        return;
                    generatingCountryCode = "";
                    generatingProvider = "";
                    clearProgress();
                    refreshUi();
                    Toast.makeText(requireContext(),
                            getString(R.string.vpn_generation_failed, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveGeneratedProfile(MullvadProfileGenerator.GeneratedProfile generated) {
        boolean activate = VpnEgressPolicy.canEnable(prefs, "wg_enabled");
        try {
            WgProfileManager.Profile existing =
                    manager.findMullvadProfileForCountry(generated.countryCode);
            manager.saveMullvadAccount(generated.accountNumber);
            manager.saveMullvadDeviceId(generated.deviceId);
            manager.saveProfile(existing == null ? null : existing.id,
                    generated.name,
                    generated.config,
                    "mullvad",
                    generated.accountNumber,
                    generated.countryCode,
                    generated.countryName, activate);
            if (activate) {
                prefs.edit()
                    .putBoolean("wg_enabled", true)
                    .putString(PREF_VPN_MODE, MODE_MULLVAD)
                    .apply();
                ServiceSinkhole.reload("wireguard profile changed", requireContext(), false);
            } else {
                Toast.makeText(requireContext(), R.string.msg_ts_profile_saved, Toast.LENGTH_LONG).show();
            }
            generatingCountryCode = "";
            generatingProvider = "";
            clearProgress();
            refreshUi();
        } catch (JSONException ex) {
            generatingCountryCode = "";
            generatingProvider = "";
            clearProgress();
            Toast.makeText(requireContext(), ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveGeneratedIvpnProfile(IvpnProfileGenerator.GeneratedProfile generated) {
        boolean activate = VpnEgressPolicy.canEnable(prefs, "wg_enabled");
        try {
            WgProfileManager.Profile existing =
                    manager.findIvpnProfileForCountry(generated.countryCode);
            manager.saveIvpnAccount(generated.accountNumber);
            manager.saveIvpnSession(generated.accountNumber, generated.session);
            manager.saveProfile(existing == null ? null : existing.id,
                    generated.name,
                    generated.config,
                    "ivpn",
                    generated.accountNumber,
                    generated.countryCode,
                    generated.countryName, activate);
            if (activate) {
                prefs.edit()
                    .putBoolean("wg_enabled", true)
                    .putString(PREF_VPN_MODE, MODE_IVPN)
                    .apply();
                ServiceSinkhole.reload("wireguard profile changed", requireContext(), false);
            } else {
                Toast.makeText(requireContext(), R.string.msg_ts_profile_saved, Toast.LENGTH_LONG).show();
            }
            generatingCountryCode = "";
            generatingProvider = "";
            clearProgress();
            refreshUi();
        } catch (JSONException ex) {
            generatingCountryCode = "";
            generatingProvider = "";
            clearProgress();
            Toast.makeText(requireContext(), ex.toString(), Toast.LENGTH_LONG).show();
        }
    }

    private void setCountries(List<VpnCountry> next) {
        countries.clear();
        if (next != null)
            countries.addAll(next);
        refreshUi();
    }

    private void toggleVpnEnabled(boolean checked) {
        if (checked && !canEnableWireGuard()) {
            refreshUi();
            return;
        }
        if (checked && manager.getActiveProfile() == null) {
            refreshUi();
            if (isFirstTimeVpnSetup()) {
                Toast.makeText(requireContext(), R.string.vpn_setup_required,
                        Toast.LENGTH_LONG).show();
            } else if (isWireGuardMode()) {
                Toast.makeText(requireContext(), R.string.vpn_choose_profile_first,
                        Toast.LENGTH_LONG).show();
            } else if (isIvpnMode() && TextUtils.isEmpty(manager.getLastIvpnAccount())) {
                showIvpnSettingsDialog(null, "", "", null);
            } else if (isMullvadMode() && TextUtils.isEmpty(manager.getLastMullvadAccount())) {
                showMullvadSettingsDialog();
            } else {
                Toast.makeText(requireContext(), R.string.vpn_choose_country_first,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        prefs.edit().putBoolean("wg_enabled", checked).apply();
        ServiceSinkhole.reload("changed wg_enabled", requireContext(), false);
        refreshUi();
    }

    private void refreshUi() {
        if (!isAdded() || screenController == null)
            return;

        boolean enabled = prefs.getBoolean("wg_enabled", false);
        boolean firstTime = isFirstTimeVpnSetup();
        String providerMode = currentProviderMode();
        WgProfileManager.Profile active = manager.getActiveProfile();
        boolean activeMullvad = active != null && "mullvad".equals(active.provider);
        boolean activeIvpn = active != null && "ivpn".equals(active.provider);

        String statusFlag = "";
        String statusTitle = getString(R.string.vpn_status_disconnected);
        String statusSummary = "";
        if (enabled && (activeMullvad || activeIvpn) && !TextUtils.isEmpty(active.countryName)) {
            statusFlag = flagEmoji(active.countryCode);
            statusTitle = active.countryName;
            String summary = manager.getProfileSummary(active);
            statusSummary = TextUtils.isEmpty(summary)
                    ? (activeIvpn ? getString(R.string.vpn_provider_ivpn)
                    : getString(R.string.vpn_status_mullvad))
                    : getString(R.string.vpn_status_relay, summary);
        } else if (enabled && active != null) {
            statusTitle = active.name;
            String summary = manager.getProfileSummary(active);
            statusSummary = TextUtils.isEmpty(summary)
                    ? getString(R.string.vpn_status_wireguard_connected)
                    : summary;
        }

        boolean showSettings = !firstTime;
        String settingsCd = showSettings
                ? getString(isWireGuardMode()
                ? R.string.menu_settings
                : isIvpnMode() ? R.string.vpn_ivpn_settings : R.string.vpn_settings)
                : "";

        boolean showProviderSetup = !isWireGuardMode() && countries.isEmpty()
                && !hasProviderAccount(providerMode);
        String setupMessage = isIvpnMode()
                ? getString(R.string.vpn_ivpn_intro)
                : getString(R.string.vpn_mullvad_intro);
        String setupAction = isIvpnMode()
                ? getString(R.string.vpn_enter_ivpn_account)
                : getString(R.string.vpn_enter_mullvad_account);

        boolean canInteract = TextUtils.isEmpty(generatingCountryCode);
        List<VpnCountryUi> countryItems = new ArrayList<>(countries.size());
        for (VpnCountry country : countries) {
            boolean isActive = active != null
                    && country.provider.equals(active.provider)
                    && country.code.equals(active.countryCode);
            boolean isGenerating = country.provider.equals(generatingProvider)
                    && country.code.equals(generatingCountryCode);
            countryItems.add(new VpnCountryUi(
                    country.code,
                    country.name,
                    country.provider,
                    flagEmoji(country.code),
                    isActive,
                    isGenerating,
                    canInteract));
        }

        List<WgProfileManager.Profile> rawProfiles = customProfiles();
        String activeId = manager.getActiveProfileId();
        List<VpnProfileUi> profileItems = new ArrayList<>(rawProfiles.size());
        for (WgProfileManager.Profile profile : rawProfiles) {
            profileItems.add(new VpnProfileUi(
                    profile.id,
                    profile.name,
                    manager.getProfileSummary(profile),
                    profile.id.equals(activeId),
                    canInteract));
        }

        String footerLabel = rawProfiles.isEmpty()
                ? getString(R.string.vpn_import_wireguard_profile)
                : getString(R.string.vpn_manage_wireguard_profiles);

        screenController.update(new VpnScreenModel(
                enabled,
                firstTime,
                providerMode,
                statusFlag,
                statusTitle,
                statusSummary,
                showSettings,
                settingsCd,
                progressVisible ? progressMessage : null,
                countryErrorVisible,
                showProviderSetup,
                setupMessage,
                setupAction,
                countryItems,
                profileItems,
                footerLabel));
    }

    private void setProgress(String text) {
        progressVisible = true;
        progressMessage = text;
        refreshUi();
    }

    private void clearProgress() {
        progressVisible = false;
        progressMessage = "";
        refreshUi();
    }

    private String flagEmoji(String countryCode) {
        String code = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.ROOT);
        if (code.length() != 2)
            return "";
        int first = Character.codePointAt(code, 0) - 'A' + 0x1F1E6;
        int second = Character.codePointAt(code, 1) - 'A' + 0x1F1E6;
        if (first < 0x1F1E6 || first > 0x1F1FF || second < 0x1F1E6 || second > 0x1F1FF)
            return "";
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }

    private String maskAccount(String account) {
        if (TextUtils.isEmpty(account))
            return "";
        int visible = Math.min(4, account.length());
        return "**** " + account.substring(account.length() - visible);
    }

    private void showMullvadSettingsDialog() {
        Context dialogContext = TrackerControlThemeKt.wrapDynamicColorContext(requireContext());
        LinearLayout form = new LinearLayout(dialogContext);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        String account = manager.getLastMullvadAccount();
        TextView intro = new TextView(dialogContext);
        intro.setText(TextUtils.isEmpty(account)
                ? R.string.vpn_mullvad_intro
                : R.string.vpn_mullvad_account_note);
        intro.setTextAppearance(R.style.TextSmall);
        form.addView(intro, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView current = new TextView(dialogContext);
        current.setText(TextUtils.isEmpty(account)
                ? getString(R.string.vpn_account_not_set)
                : getString(R.string.vpn_account_number, maskAccount(account)));
        current.setTextAppearance(R.style.TextMedium);
        current.setPadding(0, pad / 2, 0, 0);
        form.addView(current, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button show = new Button(dialogContext);
        show.setText(R.string.vpn_account_show);
        show.setVisibility(TextUtils.isEmpty(account) ? View.GONE : View.VISIBLE);
        show.setOnClickListener(v -> {
            boolean hidden = show.getText().equals(getString(R.string.vpn_account_show));
            current.setText(getString(R.string.vpn_account_number,
                    hidden ? account : maskAccount(account)));
            show.setText(hidden ? R.string.vpn_account_hide : R.string.vpn_account_show);
        });
        form.addView(show, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button change = new Button(dialogContext);
        change.setText(R.string.vpn_account_change);
        change.setVisibility(TextUtils.isEmpty(account) ? View.GONE : View.VISIBLE);
        form.addView(change, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(dialogContext);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.vpn_account_hint);
        input.setVisibility(TextUtils.isEmpty(account) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = pad / 2;
        form.addView(input, inputParams);
        change.setOnClickListener(v -> {
            input.setVisibility(input.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            change.setText(input.getVisibility() == View.VISIBLE
                    ? R.string.vpn_account_cancel
                    : R.string.vpn_account_change);
            if (input.getVisibility() == View.VISIBLE)
                input.requestFocus();
            else
                input.setText("");
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(dialogContext)
                .setTitle(R.string.vpn_settings_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(TextUtils.isEmpty(account)
                        ? R.string.vpn_account_save
                        : android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String next = input.getText().toString().trim();
                    if (TextUtils.isEmpty(next)) {
                        if (TextUtils.isEmpty(manager.getLastMullvadAccount()) ||
                                input.getVisibility() == View.VISIBLE) {
                            input.setError(getString(R.string.vpn_account_hint));
                            return;
                        }
                        dialog.dismiss();
                        return;
                    }

                    manager.saveMullvadAccount(next);
                    Toast.makeText(requireContext(), R.string.vpn_account_saved,
                            Toast.LENGTH_LONG).show();
                    if (isMullvadMode() && mullvadCountryCache.isEmpty())
                        loadCountries(false);
                    refreshUi();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showIvpnSettingsDialog(@Nullable IvpnProfileGenerator.CaptchaRequiredException captcha,
                                        String captchaId, String captchaImage,
                                        @Nullable VpnCountry pendingCountry) {
        Context dialogContext = TrackerControlThemeKt.wrapDynamicColorContext(requireContext());
        LinearLayout form = new LinearLayout(dialogContext);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        String account = manager.getLastIvpnAccount();
        TextView intro = new TextView(dialogContext);
        intro.setText(captcha == null
                ? R.string.vpn_ivpn_intro
                : R.string.vpn_ivpn_captcha_intro);
        intro.setTextAppearance(R.style.TextSmall);
        form.addView(intro, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView current = new TextView(dialogContext);
        current.setText(TextUtils.isEmpty(account)
                ? getString(R.string.vpn_account_not_set)
                : getString(R.string.vpn_account_number, maskAccount(account)));
        current.setTextAppearance(R.style.TextMedium);
        current.setPadding(0, pad / 2, 0, 0);
        form.addView(current, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(dialogContext);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint(R.string.vpn_ivpn_account_hint);
        input.setVisibility(TextUtils.isEmpty(account) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = pad / 2;
        form.addView(input, inputParams);

        Button change = new Button(dialogContext);
        change.setText(R.string.vpn_account_change);
        change.setVisibility(TextUtils.isEmpty(account) ? View.GONE : View.VISIBLE);
        change.setOnClickListener(v -> {
            input.setVisibility(input.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            change.setText(input.getVisibility() == View.VISIBLE
                    ? R.string.vpn_account_cancel
                    : R.string.vpn_account_change);
            if (input.getVisibility() == View.VISIBLE)
                input.requestFocus();
            else
                input.setText("");
        });
        form.addView(change, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView captchaView = new ImageView(dialogContext);
        Bitmap bitmap = decodeCaptcha(captchaImage);
        captchaView.setImageBitmap(bitmap);
        captchaView.setAdjustViewBounds(true);
        captchaView.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams captchaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        captchaParams.topMargin = pad / 2;
        form.addView(captchaView, captchaParams);

        EditText captchaInput = new EditText(dialogContext);
        captchaInput.setSingleLine(true);
        captchaInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        captchaInput.setHint(R.string.vpn_ivpn_captcha_hint);
        captchaInput.setVisibility(TextUtils.isEmpty(captchaId) ? View.GONE : View.VISIBLE);
        form.addView(captchaInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(dialogContext)
                .setTitle(R.string.vpn_ivpn_settings_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(TextUtils.isEmpty(account)
                        ? R.string.vpn_account_save
                        : android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String next = input.getText().toString().trim();
                    if (TextUtils.isEmpty(next)) {
                        if (TextUtils.isEmpty(manager.getLastIvpnAccount()) ||
                                input.getVisibility() == View.VISIBLE) {
                            input.setError(getString(R.string.vpn_ivpn_account_hint));
                            return;
                        }
                    } else {
                        manager.saveIvpnAccount(next);
                    }

                    String captchaText = captchaInput.getText().toString().trim();
                    if (!TextUtils.isEmpty(captchaId) && TextUtils.isEmpty(captchaText)) {
                        captchaInput.setError(getString(R.string.vpn_ivpn_captcha_hint));
                        return;
                    }

                    Toast.makeText(requireContext(), R.string.vpn_account_saved,
                            Toast.LENGTH_LONG).show();
                    if (isIvpnMode() && ivpnCountryCache.isEmpty())
                        loadCountries(false);
                    refreshUi();
                    dialog.dismiss();
                    if (pendingCountry != null)
                        generateIvpnCountry(pendingCountry, captchaId, captchaText);
                }));
        dialog.show();
    }

    @Nullable
    private Bitmap decodeCaptcha(String captchaImage) {
        if (TextUtils.isEmpty(captchaImage))
            return null;
        try {
            String encoded = captchaImage.trim();
            int comma = encoded.indexOf(',');
            if (comma >= 0)
                encoded = encoded.substring(comma + 1);
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
