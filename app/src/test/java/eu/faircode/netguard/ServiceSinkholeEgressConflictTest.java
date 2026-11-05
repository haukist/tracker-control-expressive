package eu.faircode.netguard;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ServiceSinkholeEgressConflictTest {
    private static class TestService extends ServiceSinkhole {
        void attach(Context context) { attachBaseContext(context); }
    }

    @Test
    public void conflictingStartAndReloadLeaveExistingVpnUntouched() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (String remote : new String[]{"wg_enabled", "socks5_enabled"}) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
                    .putBoolean("ts_enabled", true).putBoolean(remote, true).commit();
            TestService service = new TestService();
            service.attach(context);
            Class<?> handlerClass = Class.forName(ServiceSinkhole.class.getName() + "$CommandHandler");
            Constructor<?> constructor = handlerClass.getDeclaredConstructor(ServiceSinkhole.class, Looper.class);
            constructor.setAccessible(true);
            Object handler = constructor.newInstance(service, Looper.getMainLooper());
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread existingForwarder = new Thread();
            ReflectionHelpers.setField(service, "vpn", pipe[0]);
            ReflectionHelpers.setField(service, "tunnelThread", existingForwarder);
            try {
                for (String command : new String[]{"start", "reload"}) {
                    Method method = command.equals("start")
                            ? handlerClass.getDeclaredMethod(command)
                            : handlerClass.getDeclaredMethod(command, boolean.class);
                    method.setAccessible(true);
                    try {
                        method.invoke(handler, command.equals("start") ? new Object[0] : new Object[]{false});
                        fail("Conflicting " + command + " must be rejected");
                    } catch (InvocationTargetException ex) {
                        assertTrue(ex.getCause().toString(), ex.getCause() instanceof IllegalStateException);
                        assertEquals(context.getString(R.string.msg_ts_exclusive), ex.getCause().getMessage());
                    }
                    assertSame(pipe[0], ReflectionHelpers.getField(service, "vpn"));
                    assertTrue(pipe[0].getFileDescriptor().valid());
                    assertSame(existingForwarder, ReflectionHelpers.getField(service, "tunnelThread"));
                }
            } finally {
                pipe[0].close();
                pipe[1].close();
            }
        }
    }
}
