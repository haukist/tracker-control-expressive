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
        TailscaleManager.stopWaitMillis = 30_000;
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

    /** A stop that never finishes must not block every later start for ever. */
    @Test public void startGivesUpOnAStopThatDoesNotFinish() throws Exception {
        Object life = ReflectionHelpers.getStaticField(TailscaleManager.class, "LIFE");
        TailscaleManager.stopWaitMillis = 200;
        Thread start;
        synchronized (life) {
            TailscaleManager.stopAsync(); // parked on LIFE for the whole test body
            start = new Thread(() -> TailscaleManager.start(null, () -> { }));
            start.start();
            start.join(10_000);
            assertFalse("start waited beyond its limit", start.isAlive());
            // It reported the problem and did not create an engine beside the old one.
            assertEquals("Error", TailscaleManager.snapshot().state);
            assertTrue(TailscaleManager.snapshot().error.contains("still stopping"));
            assertNull(ReflectionHelpers.getStaticField(TailscaleManager.class, "client"));
        }
        // Once the old stop is through, the request is no longer pending.
        for (int i = 0; i < 100 && pendingStops() > 0; i++) Thread.sleep(50);
        assertEquals(0, pendingStops());
    }

    /** What Service.onDestroy calls on the main thread must not wait for the engine. */
    @Test public void destroyReturnsWhileTheEngineIsStillStopping() throws Exception {
        Object life = ReflectionHelpers.getStaticField(TailscaleManager.class, "LIFE");
        int[] nativeShutdowns = {0};
        Thread destroy = new Thread(() -> TailscaleManager.destroy(() -> nativeShutdowns[0]++));
        synchronized (life) {
            // An engine shutdown in progress holds LIFE for as long as it takes.
            destroy.start();
            destroy.join(5_000);
            assertFalse("destroy waited for the engine", destroy.isAlive());
            assertEquals(1, nativeShutdowns[0]);
            assertEquals("the stop is still owed", 1, pendingStops());
            assertFalse(TailscaleManager.isBridgeAttached());
        }
        for (int i = 0; i < 100 && pendingStops() > 0; i++) Thread.sleep(50);
        assertEquals(0, pendingStops());
        assertEquals("Stopped", TailscaleManager.snapshot().state);
    }

    private static int pendingStops() {
        Object lock = ReflectionHelpers.getStaticField(TailscaleManager.class, "PENDING_STOPS");
        synchronized (lock) {
            return ReflectionHelpers.getStaticField(TailscaleManager.class, "pendingStops");
        }
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
