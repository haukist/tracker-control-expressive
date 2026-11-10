package net.kollnig.missioncontrol.tailscale;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class TailnetNamesTest {
    private static byte[] query(String... labels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}, 0, 12);
        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.ISO_8859_1);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        out.write(new byte[]{0, 1, 0, 1}, 0, 4);
        return out.toByteArray();
    }

    private static boolean ordinary(String suffix, String... labels) {
        return TailnetNames.isCertainlyOrdinary(query(labels), suffix);
    }

    @Test public void ordinaryNamesSkipTheGoClassifier() {
        assertTrue(ordinary("home.ts.net", "www", "example", "com"));
        assertTrue(ordinary("home.ts.net", "WWW", "Example", "COM"));
        assertTrue(ordinary("home.ts.net", "_dns", "resolver", "arpa-example", "org"));
        // Another tailnet's names are ordinary once our own suffix is known.
        assertTrue(ordinary("home.ts.net", "server", "other", "ts", "net"));
        assertTrue(ordinary("home.ts.net", "evilhome", "ts", "net"));
        assertTrue(ordinary("home.ts.net", "server", "home", "ts", "net", "evil"));
        assertTrue(ordinary("", "www", "example", "com"));
        assertTrue(ordinary("", "nots", "net"));
    }

    @Test public void tailnetNamesAreLeftToGo() {
        assertFalse(ordinary("home.ts.net", "server", "home", "ts", "net"));
        assertFalse(ordinary("home.ts.net", "SERVER", "Home", "TS", "NET"));
        assertFalse(ordinary("home.ts.net", "home", "ts", "net"));
        assertFalse(ordinary("Home.TS.net.", "server", "home", "ts", "net"));
        assertFalse(ordinary("home.ts.net", "server")); // short name
        assertFalse(ordinary("", "server", "home", "ts", "net")); // suffix unknown
        assertFalse(ordinary("", "ts", "net"));
        assertFalse(ordinary("home.ts.net", "2", "1", "64", "100", "in-addr", "arpa"));
        assertFalse(ordinary("home.ts.net", "1", "1", "168", "192", "in-addr", "arpa"));
        assertFalse(ordinary("home.ts.net", "0", "ip6", "arpa"));
    }

    @Test public void anythingUnusualIsLeftToGo() {
        assertFalse(TailnetNames.isCertainlyOrdinary(null, "home.ts.net"));
        assertFalse(TailnetNames.isCertainlyOrdinary(query("www", "example", "com"), null));
        assertFalse(TailnetNames.isCertainlyOrdinary(new byte[12], "home.ts.net"));
        assertFalse(ordinary("home.ts.net")); // root
        // A dot or unusual byte inside a label could be read differently by Go.
        assertFalse(ordinary("home.ts.net", "server.home", "ts", "net"));
        assertFalse(ordinary("home.ts.net", "a.b", "example", "com"));
        assertFalse(ordinary("home.ts.net", "wéb", "example", "com"));
        assertFalse(ordinary("home.ts.net", "a b", "example", "com"));
        assertFalse(ordinary("höme.ts.net", "www", "example", "com"));
        // A malformed suffix proves nothing about any name.
        for (String suffix : new String[]{".", ".home.ts.net", "home..ts.net", "home.ts.net..", ".."}) {
            assertFalse(suffix, ordinary(suffix, "www", "example", "com"));
            assertFalse(suffix, ordinary(suffix, "server", "home", "ts", "net"));
        }

        byte[] noQuestion = query("www", "example", "com");
        noQuestion[5] = 0;
        assertFalse(TailnetNames.isCertainlyOrdinary(noQuestion, "home.ts.net"));

        byte[] truncated = java.util.Arrays.copyOf(query("www", "example", "com"), 20);
        assertFalse(TailnetNames.isCertainlyOrdinary(truncated, "home.ts.net"));

        // A compression pointer where a label length is expected.
        byte[] pointer = query("www", "example", "com");
        pointer[12 + 4] = (byte) 0xc0;
        assertFalse(TailnetNames.isCertainlyOrdinary(pointer, "home.ts.net"));

        // A name without its terminating root label.
        byte[] unterminated = java.util.Arrays.copyOf(query("www", "example", "com"), 12 + 16);
        assertFalse(TailnetNames.isCertainlyOrdinary(unterminated, "home.ts.net"));
    }
}
