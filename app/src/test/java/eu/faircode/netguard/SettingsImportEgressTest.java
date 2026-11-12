package eu.faircode.netguard;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class SettingsImportEgressTest {
    private static class Settings extends ActivitySettings {
        void attach(Context context) { attachBaseContext(context); }
    }

    @Test
    public void invalidXmlIsRejectedBeforePreferencesOrRunningVpnAreTouched() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().putBoolean("enabled", true).putBoolean("ts_enabled", true)
                .putString("unchanged", "keep").commit();
        Map<String, ?> before = prefs.getAll();
        Settings activity = new Settings();
        activity.attach(context);
        Method method = ActivitySettings.class.getDeclaredMethod("xmlImport", InputStream.class);
        method.setAccessible(true);
        for (String extra : new String[]{
                "<setting key='wg_enabled' type='boolean' value='true'/>",
                "<setting key='socks5_enabled' type='boolean' value='true'/>",
                "<setting key='wg_enabled' type='string' value='true'/>",
                "<setting key='wg_enabled' type='boolean' value='true-ish'/>",
                "<setting key='wg_enabled' type='unknown' value='true'/>"}) {
            String xml = "<trackercontrol><application>"
                    + "<setting key='ts_enabled' type='boolean' value='true'/>"
                    + extra + "</application></trackercontrol>";
            try {
                method.invoke(activity, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
                fail("Invalid import must be rejected");
            } catch (InvocationTargetException ex) {
                assertTrue(ex.getCause().toString(), ex.getCause() instanceof SAXException);
            }
            assertEquals(before, prefs.getAll());
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        }
    }

    @Test
    public void compatibleImportsRemainAllowed() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Map<String, Object> settings = new HashMap<>();
        settings.put("ts_enabled", true);
        ActivitySettings.validateImportedEgress(context, settings);
        settings.put("ts_enabled", false);
        settings.put("wg_enabled", true);
        settings.put("socks5_enabled", true);
        ActivitySettings.validateImportedEgress(context, settings);
    }

    @Test
    public void importNeverSwitchesTailscaleOn() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("ts_enabled", true);
        settings.put("wg_enabled", false);
        ActivitySettings.keepTailscaleConsent(settings, false);
        assertEquals(false, settings.get("ts_enabled"));
        assertEquals(false, settings.get("wg_enabled"));

        // Restoring onto an install that already uses Tailscale keeps it.
        settings.put("ts_enabled", true);
        ActivitySettings.keepTailscaleConsent(settings, true);
        assertEquals(true, settings.get("ts_enabled"));

        // A file may still switch it off, and absence stays absence.
        settings.put("ts_enabled", false);
        ActivitySettings.keepTailscaleConsent(settings, true);
        assertEquals(false, settings.get("ts_enabled"));
        settings.remove("ts_enabled");
        ActivitySettings.keepTailscaleConsent(settings, false);
        assertFalse(settings.containsKey("ts_enabled"));
    }

    @Test
    public void completedImportDoesNotEnableTailscale() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Settings activity = new Settings();
        activity.attach(context);
        Method method = ActivitySettings.class.getDeclaredMethod("xmlImport", InputStream.class);
        method.setAccessible(true);
        String xml = "<trackercontrol><application>"
                + "<setting key='ts_enabled' type='boolean' value='true'/>"
                + "</application></trackercontrol>";

        prefs.edit().clear().commit();
        method.invoke(activity, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertTrue("the import ran to completion", prefs.getBoolean("imported", false));
        assertFalse(prefs.getBoolean("ts_enabled", true));

        // The import re-registers the (view-less) activity as a listener.
        prefs.unregisterOnSharedPreferenceChangeListener(activity);
        prefs.edit().clear().putBoolean("ts_enabled", true).commit();
        method.invoke(activity, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        prefs.unregisterOnSharedPreferenceChangeListener(activity);
        assertTrue(prefs.getBoolean("ts_enabled", false));
    }

    @Test
    public void startupRepairsOldConflictsWithoutDisablingTailnetOrProtection() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().putInt("version", Util.getSelfVersionCode(context))
                .putBoolean("enabled", true).putBoolean("ts_enabled", true)
                .putBoolean("wg_enabled", true).putBoolean("socks5_enabled", true).commit();
        ReceiverAutostart.upgrade(true, context);
        assertTrue(prefs.getBoolean("enabled", false));
        assertTrue(prefs.getBoolean("ts_enabled", false));
        assertFalse(prefs.getBoolean("wg_enabled", true));
        assertFalse(prefs.getBoolean("socks5_enabled", true));
    }
}
