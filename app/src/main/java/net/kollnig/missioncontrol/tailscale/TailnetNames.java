package net.kollnig.missioncontrol.tailscale;

/**
 * A cheap pre-check in front of the Go classifier for DNS queries.
 *
 * Every DNS query of the device passes the proxy while Tailscale is enabled,
 * and nearly all of them are ordinary names. This recognises those without
 * crossing into Go. It only ever answers "certainly ordinary": anything it
 * does not fully understand is left to Go, which stays the single authority
 * on what a Tailnet name is. A wrong "ordinary" here would send a Tailnet
 * name to public DNS, so every doubt must resolve to false.
 */
final class TailnetNames {
    private static final int HEADER = 12;
    private static final int MAX_NAME = 255;

    private TailnetNames() { }

    /**
     * True only if the first question of query is a plain multi-label name
     * that cannot be a Tailnet name: not a single label, not under the
     * tailnet suffix (or under ts.net while the suffix is unknown) and not a
     * reverse lookup.
     */
    static boolean isCertainlyOrdinary(byte[] query, String suffix) {
        if (query == null || suffix == null || query.length <= HEADER) return false;
        if ((((query[4] & 0xff) << 8) | (query[5] & 0xff)) < 1) return false;

        StringBuilder name = new StringBuilder();
        int labels = 0;
        int position = HEADER;
        while (true) {
            if (position >= query.length) return false;
            int length = query[position++] & 0xff;
            if (length == 0) break;
            // Compression pointers and reserved label types: let Go decide.
            if ((length & 0xc0) != 0 || position + length > query.length) return false;
            if (labels++ > 0) name.append('.');
            for (int end = position + length; position < end; position++) {
                int c = query[position] & 0xff;
                // Only plain host name characters are compared here. A dot
                // inside a label, a non-ASCII byte or anything unusual could
                // be read differently by Go.
                if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
                boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
                if (!plain) return false;
                name.append((char) c);
            }
            if (name.length() > MAX_NAME) return false;
        }
        if (labels < 2) return false; // single labels are Tailnet short names

        String lookup = name.toString();
        if (lookup.endsWith(".arpa")) return false; // reverse lookups
        String tailnet = normalize(suffix);
        if (tailnet == null) return false;
        if (tailnet.isEmpty())
            // Membership unknown: all of ts.net is reserved, as in Go.
            return !(lookup.equals("ts.net") || lookup.endsWith(".ts.net"));
        return !(lookup.equals(tailnet) || lookup.endsWith("." + tailnet));
    }

    /**
     * Lower-case suffix without its trailing dot; "" while membership is
     * unknown; null for anything that is not a well-formed plain name.
     */
    private static String normalize(String suffix) {
        StringBuilder result = new StringBuilder(suffix.length());
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
            boolean plain = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!plain) return null;
            result.append(c);
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == '.') result.setLength(--length);
        if (length == 0) return suffix.isEmpty() ? "" : null;
        if (result.charAt(0) == '.' || result.charAt(length - 1) == '.' || result.indexOf("..") >= 0)
            return null;
        return result.toString();
    }
}
