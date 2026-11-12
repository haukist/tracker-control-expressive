package net.kollnig.missioncontrol.tailscale;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36)
public class TailscaleSnapshotTest {
    @Test public void assignedAddressesTriggerVpnReplacement() throws Exception {
        TailscaleSnapshot ready = TailscaleSnapshot.parse("{\"state\":\"Running\",\"addresses\":[\"100.64.1.1\",\"fd7a:115c:a1e0::1\"],\"dnsSuffix\":\"home.ts.net\",\"magicDNS\":true}");
        assertFalse(ready.hasSameAddresses(TailscaleSnapshot.stopped()));
        assertEquals("100.64.1.1", ready.ipv4);
        assertEquals("fd7a:115c:a1e0::1", ready.ipv6);
        assertTrue(ready.magicDNS);
        assertTrue(ready.hasSameAddresses(ready));
    }

    @Test public void searchDomainIsTheTailnetSuffixWhereMagicDnsIsUsed() throws Exception {
        String addresses = "\"addresses\":[\"100.64.1.1\"],";
        TailscaleSnapshot ready = TailscaleSnapshot.parse("{\"state\":\"Running\"," + addresses
                + "\"dnsSuffix\":\"home.ts.net\",\"magicDNS\":true}");
        assertEquals("home.ts.net", ready.searchDomain());
        assertNull(TailscaleSnapshot.stopped().searchDomain());
        TailscaleSnapshot noMagicDns = TailscaleSnapshot.parse("{\"state\":\"Running\"," + addresses
                + "\"dnsSuffix\":\"home.ts.net\",\"magicDNS\":false}");
        assertNull(noMagicDns.searchDomain());
        TailscaleSnapshot loggedOut = TailscaleSnapshot.parse("{\"state\":\"NeedsLogin\"," + addresses
                + "\"dnsSuffix\":\"home.ts.net\",\"magicDNS\":true}");
        // A reconnect keeps the interface as it is.
        assertEquals("home.ts.net", loggedOut.searchDomain());
        assertTrue(ready.hasSameVpnConfiguration(loggedOut));

        // The interface is rebuilt when the search domain changes, not only the addresses.
        assertTrue(ready.hasSameVpnConfiguration(ready));
        assertTrue(ready.hasSameAddresses(noMagicDns));
        assertFalse(ready.hasSameVpnConfiguration(noMagicDns));
        TailscaleSnapshot renamed = TailscaleSnapshot.parse("{\"state\":\"Running\"," + addresses
                + "\"dnsSuffix\":\"other.ts.net\",\"magicDNS\":true}");
        assertFalse(ready.hasSameVpnConfiguration(renamed));
    }
}
