package net.kollnig.missioncontrol.tailscale;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.NetworkInterface;
import java.util.HashSet;
import java.util.Set;

/** Android information Go cannot obtain via app-UID-restricted netlink calls. */
final class TailscalePlatform implements tailscalebridge.Platform {
    private final VpnService service;
    private final ConnectivityManager connectivity;

    TailscalePlatform(VpnService service) {
        this.service = service;
        connectivity = (ConnectivityManager) service.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean protect(long fd) {
        return fd >= 0 && fd <= Integer.MAX_VALUE && service.protect((int) fd);
    }

    @Override
    public String interfacesJSON() {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Network network : connectivity.getAllNetworks()) {
            LinkProperties link = connectivity.getLinkProperties(network);
            if (link == null || link.getInterfaceName() == null || !seen.add(link.getInterfaceName()))
                continue;
            try {
                NetworkInterface iface = NetworkInterface.getByName(link.getInterfaceName());
                if (iface == null) continue;
                JSONObject entry = new JSONObject();
                entry.put("name", iface.getName());
                entry.put("index", iface.getIndex());
                entry.put("mtu", android.os.Build.VERSION.SDK_INT >= 29 ? link.getMtu() : iface.getMTU());
                int flags = iface.isUp() ? 1 | 32 : 0; // Go FlagUp | FlagRunning
                if (iface.isLoopback()) flags |= 4;
                if (iface.isPointToPoint()) flags |= 8;
                else flags |= 2;
                if (iface.supportsMulticast()) flags |= 16;
                entry.put("flags", flags);
                JSONArray addresses = new JSONArray();
                for (LinkAddress address : link.getLinkAddresses()) {
                    String ip = address.getAddress().getHostAddress();
                    int scope = ip.indexOf('%');
                    if (scope >= 0) ip = ip.substring(0, scope);
                    addresses.put(ip + "/" + address.getPrefixLength());
                }
                entry.put("addresses", addresses);
                result.put(entry);
            } catch (Exception ignored) {
                // A disappearing network must not invalidate all other links.
            }
        }
        return result.toString();
    }

    @Override
    public String defaultInterface() {
        Network selected = connectivity.getActiveNetwork();
        NetworkCapabilities active = selected == null ? null : connectivity.getNetworkCapabilities(selected);
        if (active == null || active.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            selected = null;
            int best = -1;
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                int score = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score++;
                if (score > best) { best = score; selected = network; }
            }
        }
        LinkProperties link = selected == null ? null : connectivity.getLinkProperties(selected);
        return link == null || link.getInterfaceName() == null ? "" : link.getInterfaceName();
    }
}
