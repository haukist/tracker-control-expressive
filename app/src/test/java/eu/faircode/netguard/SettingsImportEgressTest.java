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
