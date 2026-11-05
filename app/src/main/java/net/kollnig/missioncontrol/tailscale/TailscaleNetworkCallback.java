package net.kollnig.missioncontrol.tailscale;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Parcel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Never enter Go, enumerate interfaces, or wait for shutdown on ConnectivityThread. */
final class TailscaleNetworkCallback extends ConnectivityManager.NetworkCallback {
    private final Executor executor;
    private final Runnable changed;
    private final Map<Network, LinkProperties> links = new HashMap<>();
    private final Map<Network, Integer> capabilities = new HashMap<>();
    private boolean pending;
    private boolean closed;

    TailscaleNetworkCallback(Executor executor, Runnable changed) {
        this.executor = executor;
        this.changed = changed;
    }

    @Override public synchronized void onAvailable(Network network) { schedule(); }

    @Override public synchronized void onLost(Network network) {
        links.remove(network);
        capabilities.remove(network);
        schedule();
    }

    @Override public synchronized void onLinkPropertiesChanged(Network network, LinkProperties link) {
        if (closed || link.equals(links.get(network))) return;
        Parcel parcel = Parcel.obtain();
        try {
            link.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            links.put(network, LinkProperties.CREATOR.createFromParcel(parcel));
        } finally {
            parcel.recycle();
        }
        schedule();
    }

    @Override public synchronized void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        int relevant = (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 1 : 0)
                | (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? 2 : 0)
                | (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 4 : 0)
                | (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 8 : 0);
        if (closed || Integer.valueOf(relevant).equals(capabilities.get(network))) return;
        capabilities.put(network, relevant);
        schedule();
    }

    private void schedule() {
        if (closed || pending) return;
        pending = true;
        try {
            executor.execute(() -> {
                synchronized (this) {
                    pending = false;
                    if (closed) return;
                }
                changed.run();
            });
        } catch (RejectedExecutionException ignored) {
            pending = false; // Shutdown won the race; no work remains to run.
        }
    }

    synchronized void close() {
        closed = true;
        links.clear();
        capabilities.clear();
    }
}
