package net.kollnig.missioncontrol.vpn;

import android.content.SharedPreferences;

/** Tailscale shares the single VPN with local blocking, not another egress. */
public final class VpnEgressPolicy {
    private VpnEgressPolicy() { }

    public static boolean canEnable(SharedPreferences prefs, String key) {
        if ("ts_enabled".equals(key))
            return !prefs.getBoolean("wg_enabled", false)
                    && !prefs.getBoolean("socks5_enabled", false);
        if ("wg_enabled".equals(key) || "socks5_enabled".equals(key))
            return !prefs.getBoolean("ts_enabled", false);
        throw new IllegalArgumentException("Unknown VPN egress preference: " + key);
    }

    public static boolean hasConflict(SharedPreferences prefs) {
        return prefs.getBoolean("ts_enabled", false) && !canEnable(prefs, "ts_enabled");
    }
}
