package tailscalebridge

import "testing"

func TestInterfaceProviderKeepsHostAddresses(t *testing.T) {
	interfaces, err := parseInterfaces(`[{"name":"wlan0","index":3,"mtu":1500,"flags":1,"addresses":["192.168.1.23/24","2001:db8::42/64"]}]`)
	if err != nil {
		t.Fatal(err)
	}
	addresses, err := interfaces[0].Addrs()
	if err != nil || len(addresses) != 2 || addresses[0].String() != "192.168.1.23/24" || addresses[1].String() != "2001:db8::42/64" {
		t.Fatalf("addresses = %v, %v", addresses, err)
	}
}

func TestInterfaceProviderRejectsInvalidData(t *testing.T) {
	for _, data := range []string{
		`not json`,
		`[{"name":"wlan0","index":0,"mtu":1500}]`,
		`[{"name":"wlan0","index":3,"mtu":-1}]`,
		`[{"name":"wlan0","index":3,"mtu":1500,"addresses":["invalid"]}]`,
	} {
		if _, err := parseInterfaces(data); err == nil {
			t.Fatalf("accepted malformed metadata %s", data)
		}
	}
}

func TestInterfaceProviderAllowsUnknownMTUAndEmptyAddresses(t *testing.T) {
	interfaces, err := parseInterfaces(`[{"name":"wlan0","index":3,"mtu":0,"flags":1,"addresses":[]}]`)
	if err != nil || len(interfaces) != 1 || interfaces[0].MTU != 0 {
		t.Fatalf("unknown Android MTU rejected: %v", err)
	}
	if interfaces[0].AltAddrs == nil {
		t.Fatal("empty addresses must not fall back to Android-blocked OS enumeration")
	}
}
