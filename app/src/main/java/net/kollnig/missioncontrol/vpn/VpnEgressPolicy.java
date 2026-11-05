package net.kollnig.missioncontrol.vpn;

import android.content.SharedPreferences;
import java.util.Map;

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

    public static boolean hasConflict(Map<String, ?> settings) {
        boolean tailscale = importedFlag(settings, "ts_enabled");
        boolean wireguard = importedFlag(settings, "wg_enabled");
        boolean socks = importedFlag(settings, "socks5_enabled");
        return tailscale && (wireguard || socks);
    }

    private static boolean importedFlag(Map<String, ?> settings, String key) {
        Object value = settings.get(key);
        if (value != null && !(value instanceof Boolean))
            throw new IllegalArgumentException("Expected boolean for " + key);
        return Boolean.TRUE.equals(value);
    }

    /** Repair old invalid combinations without removing Tailnet capture. */
    public static void repairLegacyConflict(SharedPreferences prefs) {
        if (hasConflict(prefs))
            prefs.edit().putBoolean("wg_enabled", false).putBoolean("socks5_enabled", false).apply();
    }
}
