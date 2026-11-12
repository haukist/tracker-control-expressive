package net.kollnig.missioncontrol.tailscale;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Never enter Go, enumerate interfaces, or wait for shutdown on ConnectivityThread. */
final class TailscaleNetworkCallback extends ConnectivityManager.NetworkCallback {
    private final Executor executor;
    private final Runnable changed;
    private final Map<Network, String> links = new HashMap<>();
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
        String relevant = relevant(link);
        if (closed || relevant.equals(links.get(network))) return;
        links.put(network, relevant);
        schedule();
    }

    /**
     * The part of a link that matters to the engine: its interface, MTU and
     * addresses (what TailscalePlatform reports) and its DNS servers. A whole
     * LinkProperties also differs when only a route or an address lifetime
     * changes, which every IPv6 router advertisement does, also with the
     * screen off; each such wake-up costs an interface enumeration and a call
     * into Go for nothing the engine could see.
     */
    static String relevant(LinkProperties link) {
        java.util.List<String> addresses = new java.util.ArrayList<>();
        for (LinkAddress address : link.getLinkAddresses())
            addresses.add(address.getAddress().getHostAddress() + "/" + address.getPrefixLength());
        java.util.Collections.sort(addresses);
        java.util.List<String> dns = new java.util.ArrayList<>();
        for (java.net.InetAddress server : link.getDnsServers()) dns.add(server.getHostAddress());
        int mtu = android.os.Build.VERSION.SDK_INT >= 29 ? link.getMtu() : 0;
        return link.getInterfaceName() + " " + mtu + " " + addresses + " " + dns;
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
