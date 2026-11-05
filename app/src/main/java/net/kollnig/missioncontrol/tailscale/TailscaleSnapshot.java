package net.kollnig.missioncontrol.tailscale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Non-secret state shared with the VPN builder and settings UI. */
public final class TailscaleSnapshot {
    public final String state, ipv4, ipv6, dnsSuffix, error;
    public final boolean magicDNS;

    public TailscaleSnapshot(String state, String ipv4, String ipv6,
                             String dnsSuffix, boolean magicDNS, String error) {
        this.state = state;
        this.ipv4 = ipv4;
        this.ipv6 = ipv6;
        this.dnsSuffix = dnsSuffix;
        this.magicDNS = magicDNS;
        this.error = error;
    }

    public static TailscaleSnapshot stopped() {
        return new TailscaleSnapshot("Stopped", "", "", "", false, "");
    }

    public static TailscaleSnapshot parse(String json) throws JSONException {
        JSONObject data = new JSONObject(json);
        String v4 = "", v6 = "";
        JSONArray addresses = data.optJSONArray("addresses");
        if (addresses != null)
            for (int i = 0; i < addresses.length(); i++) {
                String address = addresses.getString(i);
                if (address.contains(":")) v6 = address;
                else v4 = address;
            }
        return new TailscaleSnapshot(data.optString("state", "Starting"), v4, v6,
                data.optString("dnsSuffix", ""), data.optBoolean("magicDNS"), "");
    }

    public boolean hasSameAddresses(TailscaleSnapshot other) {
        return ipv4.equals(other.ipv4) && ipv6.equals(other.ipv6);
    }
}
