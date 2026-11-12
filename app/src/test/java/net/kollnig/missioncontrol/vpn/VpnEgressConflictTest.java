package net.kollnig.missioncontrol.vpn;

import static org.junit.Assert.*;

import android.content.SharedPreferences;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.wg.IvpnProfileGenerator;
import net.kollnig.missioncontrol.wg.MullvadProfileGenerator;
import net.kollnig.missioncontrol.wg.WgProfileManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Method;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en")
public class VpnEgressConflictTest {
    private SharedPreferences prefs;
    private WgProfileManager manager;
    private ActivityController<FragmentActivity> host;
    private VpnFragment fragment;

    @Before
    public void setUp() throws Exception {
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        prefs.edit().clear().putString("vpn_mode", "wireguard").putBoolean("enabled", true).commit();
        manager = new WgProfileManager(RuntimeEnvironment.getApplication());
        manager.saveProfile(null, "Existing", "existing-config", "", "", "", "");
        host = Robolectric.buildActivity(FragmentActivity.class);
        host.get().setTheme(R.style.AppTheme);
        host.setup();
        fragment = new VpnFragment();
        host.get().getSupportFragmentManager().beginTransaction()
                .add(android.R.id.content, fragment).commitNow();
        while (Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService() != null) { }
    }

    @After
    public void tearDown() {
        host.pause().stop().destroy();
    }

    @Test
    public void dashboardRejectsWireGuardWithoutChangingPrefsOrReloadingVpn() {
        prefs.edit().putBoolean("ts_enabled", true).commit();
        Map<String, ?> before = prefs.getAll();
        MaterialSwitch toggle = dashboardSwitch();
        toggle.setChecked(true);
        assertFalse(toggle.isChecked());
        assertEquals(before, prefs.getAll());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        assertEquals(host.get().getString(R.string.msg_ts_exclusive), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void dashboardStillEnablesWireGuardWhenTailscaleIsOff() {
        dashboardSwitch().setChecked(true);
        assertTrue(prefs.getBoolean("wg_enabled", false));
        assertNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test
    public void customProfileCannotReplaceActiveProfileWhileTailscaleIsOn() throws Exception {
        String active = manager.getActiveProfileId();
        manager.saveProfile(null, "Other", "other-config", "", "", "", "");
        WgProfileManager.Profile other = null;
        for (WgProfileManager.Profile profile : manager.getProfiles())
            if ("Other".equals(profile.name)) other = profile;
        assertNotNull(other);
        manager.setActiveProfile(active);
        prefs.edit().putBoolean("ts_enabled", true).commit();
        Map<String, ?> before = prefs.getAll();
        invoke("activateCustomProfile", WgProfileManager.Profile.class, other);
        assertEquals(before, prefs.getAll());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test
    public void generatedProfilesCannotEnableWireGuardIfTailscaleStartedDuringRequest() throws Exception {
        prefs.edit().putBoolean("ts_enabled", true).commit();
        String active = manager.getActiveProfileId();
        String config = prefs.getString(WgProfileManager.PREF_WG_CONFIG, "");
        invoke("saveGeneratedProfile", MullvadProfileGenerator.GeneratedProfile.class,
                new MullvadProfileGenerator.GeneratedProfile("Generated", "new-config", "test-account",
                        "nl", "Netherlands", "relay.test", "test-device", "", "", false));
        WgProfileManager.IvpnSession session = new WgProfileManager.IvpnSession(
                "test-session", "test-private-key", "test-public-key", "10.0.0.2/32");
        invoke("saveGeneratedIvpnProfile", IvpnProfileGenerator.GeneratedProfile.class,
                new IvpnProfileGenerator.GeneratedProfile("Generated", "new-config", "test-account",
                        "nl", "Netherlands", "relay.test", session, "", false));
        assertEquals(3, manager.getProfiles().size());
        assertEquals("test-device", manager.getMullvadDeviceId());
        assertNotNull(manager.getIvpnSession("test-account"));
        assertEquals("test-session", manager.getIvpnSession("test-account").token);
        assertEquals(active, manager.getActiveProfileId());
        assertEquals(config, prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));
        assertFalse(prefs.getBoolean("wg_enabled", false));
        assertTrue(prefs.getBoolean("ts_enabled", false));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MaterialSwitch dashboardSwitch() {
        RecyclerView list = fragment.requireView().findViewById(R.id.vpnList);
        RecyclerView.Adapter adapter = list.getAdapter();
        RecyclerView.ViewHolder holder = adapter.createViewHolder(list, 0);
        adapter.bindViewHolder(holder, 0);
        return holder.itemView.findViewById(R.id.vpnEnabledSwitch);
    }

    private void invoke(String name, Class<?> type, Object value) throws Exception {
        Method method = VpnFragment.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(fragment, value);
    }
}
