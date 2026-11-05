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
}
