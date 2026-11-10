package net.kollnig.missioncontrol.tailscale;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class TailscaleManagerStopOrderTest {
    @Before public void setUp() {
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", TailscaleSnapshot.stopped());
    }

    @After public void tearDown() {
        ReflectionHelpers.setStaticField(TailscaleManager.class, "snapshot", TailscaleSnapshot.stopped());
    }

    /** A stop requested from onDestroy must finish before a later start takes effect. */
    @Test public void startWaitsForAnEarlierAsynchronousStop() throws Exception {
        Object life = ReflectionHelpers.getStaticField(TailscaleManager.class, "LIFE");
        Thread start;
        synchronized (life) {
            // The stop thread is now parked on LIFE with its request counted.
            TailscaleManager.stopAsync();
            // Without an engine this start fails and reports it; what matters
            // is that the report is not overwritten by the earlier stop.
            start = new Thread(() -> TailscaleManager.start(null, () -> { }));
            start.start();
            start.join(300);
            assertTrue("start ran ahead of the pending stop", start.isAlive());
            assertEquals("", TailscaleManager.snapshot().error);
        }
        start.join(10_000);
        assertFalse(start.isAlive());
        assertEquals("Error", TailscaleManager.snapshot().state);
        assertFalse(TailscaleManager.snapshot().error.isEmpty());
    }

    @Test public void asynchronousStopCompletesAndAllowsAStart() throws Exception {
        TailscaleManager.stopAsync();
        Thread start = new Thread(() -> TailscaleManager.start(null, () -> { }));
        start.start();
        start.join(10_000);
        assertFalse("start stayed blocked after the stop finished", start.isAlive());
        assertEquals(0, (int) ReflectionHelpers.getStaticField(TailscaleManager.class, "pendingStops"));
    }
}
