package net.kollnig.missioncontrol.tailscale;

import static org.junit.Assert.*;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.util.ReflectionHelpers;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class TailscaleNetworkCallbackTest {
    @Test
    public void duplicateLinksAreSkippedButDnsAndNetworkLossStillNotify() throws Exception {
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        TailscaleNetworkCallback callback = new TailscaleNetworkCallback(work::add, calls::incrementAndGet);
        Network network = ShadowNetwork.newInstance(10);
        LinkProperties link = new LinkProperties();
        link.setInterfaceName("wlan0");
        callback.onAvailable(network);
        callback.onLinkPropertiesChanged(network, link);
        callback.onLinkPropertiesChanged(network, link);
        assertEquals(0, calls.get());
        assertEquals(1, work.size());
        work.remove().run();
        callback.onLinkPropertiesChanged(network, link);
        assertTrue(work.isEmpty());
        ReflectionHelpers.callInstanceMethod(link, "addDnsServer",
                ReflectionHelpers.ClassParameter.from(InetAddress.class, InetAddress.getByName("192.0.2.53")));
        callback.onLinkPropertiesChanged(network, link);
        work.remove().run();
        callback.onLost(network);
        work.remove().run();
        assertEquals(3, calls.get());
        callback.close();
        callback.onAvailable(network);
        assertTrue(work.isEmpty());
    }

    @Test
    public void validationChangesNotifyButBandwidthUpdatesDoNot() {
        Queue<Runnable> work = new ArrayDeque<>();
        TailscaleNetworkCallback callback = new TailscaleNetworkCallback(work::add, () -> { });
        Network network = ShadowNetwork.newInstance(11);
        NetworkCapabilities caps = new NetworkCapabilities();
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        callback.onCapabilitiesChanged(network, caps);
        work.remove().run();
        Shadows.shadowOf(caps).setLinkDownstreamBandwidthKbps(10000);
        callback.onCapabilitiesChanged(network, caps);
        assertTrue(work.isEmpty());
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        callback.onCapabilitiesChanged(network, caps);
        assertEquals(1, work.size());
        callback.close();
    }

    @Test
    public void blockedEngineWorkCannotBlockCallbacksOrQueueUnboundedWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService connectivity = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TailscaleNetworkCallback callback = new TailscaleNetworkCallback(executor, () -> {
            calls.incrementAndGet();
            entered.countDown();
            try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        try {
            Network network = ShadowNetwork.newInstance(12);
            connectivity.submit(() -> callback.onAvailable(network)).get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            connectivity.submit(() -> {
                for (int i = 0; i < 1000; i++) callback.onAvailable(network);
                callback.close(); // Must not wait for the blocked engine action.
            }).get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdown();
            connectivity.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(connectivity.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get()); // Queued changes were cancelled by close.
    }
}
