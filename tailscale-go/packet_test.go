package tailscalebridge

import (
	"encoding/binary"
	"net/netip"
	"testing"
)

func ipv4Packet(source, dest string) []byte {
	p := make([]byte, 28)
	p[0] = 0x45
	p[9] = 17
	binary.BigEndian.PutUint16(p[2:4], uint16(len(p)))
	s := netip.MustParseAddr(source).As4()
	d := netip.MustParseAddr(dest).As4()
	copy(p[12:16], s[:])
	copy(p[16:20], d[:])
	return p
}

func ipv6Packet(source, dest string) []byte {
	p := make([]byte, 48)
	p[0] = 0x60
	p[6] = 17
	binary.BigEndian.PutUint16(p[4:6], 8)
	s := netip.MustParseAddr(source).As16()
	d := netip.MustParseAddr(dest).As16()
	copy(p[8:24], s[:])
	copy(p[24:40], d[:])
	return p
}

func TestPacketAddresses(t *testing.T) {
	for _, pair := range [][2]string{
		{"100.64.1.1", "100.64.1.2"},
		{"fd7a:115c:a1e0::1", "fd7a:115c:a1e0::2"},
	} {
		var packet []byte
		if netip.MustParseAddr(pair[0]).Is4() {
			packet = ipv4Packet(pair[0], pair[1])
		} else {
			packet = ipv6Packet(pair[0], pair[1])
		}
		source, dest, err := packetAddresses(packet)
		if err != nil || source.String() != pair[0] || dest.String() != pair[1] {
			t.Fatalf("addresses = %v %v, %v", source, dest, err)
		}
	}
}

func TestMalformedPackets(t *testing.T) {
	badLength := ipv4Packet("100.64.1.1", "100.64.1.2")
	badLength[3]--
	badHeader := ipv4Packet("100.64.1.1", "100.64.1.2")
	badHeader[0] = 0x4f
	badV6 := ipv6Packet("fd7a:115c:a1e0::1", "fd7a:115c:a1e0::2")
	badV6[5]++
	for _, packet := range [][]byte{nil, {0x45}, {0x60}, {0x70}, badLength, badHeader, badV6, make([]byte, packetMTU+1)} {
		if _, _, err := packetAddresses(packet); err == nil {
			t.Fatalf("accepted malformed packet of size %d", len(packet))
		}
	}
}

func TestPeerDestinationBoundary(t *testing.T) {
	for _, addr := range []string{"100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1"} {
		if !isPeerAddress(netip.MustParseAddr(addr)) {
			t.Errorf("rejected peer address %s", addr)
		}
	}
	for _, addr := range []string{"8.8.8.8", "192.168.1.1", "100.63.255.255", "100.128.0.0", "100.100.100.100", "fd7a:115c:a1e0::53", "fd7a:115c:a1e1::1", "::ffff:100.64.1.1"} {
		if isPeerAddress(netip.MustParseAddr(addr)) {
			t.Errorf("accepted unsupported destination %s", addr)
		}
	}
}

func FuzzPacketAddresses(f *testing.F) {
	f.Add(ipv4Packet("100.64.1.1", "100.64.1.2"))
	f.Add(ipv6Packet("fd7a:115c:a1e0::1", "fd7a:115c:a1e0::2"))
	f.Fuzz(func(t *testing.T, packet []byte) {
		_, _, _ = packetAddresses(packet)
	})
}

func TestOnlyTailnetRepliesToThisNodeReachTheHost(t *testing.T) {
	own := map[netip.Addr]bool{
		netip.MustParseAddr("100.64.1.1"):        true,
		netip.MustParseAddr("fd7a:115c:a1e0::1"): true,
	}
	assigned := func(addr netip.Addr) bool { return own[addr] }
	for _, test := range []struct {
		packet []byte
		want   bool
	}{
		{ipv4Packet("100.64.1.2", "100.64.1.1"), true},
		{ipv6Packet("fd7a:115c:a1e0::2", "fd7a:115c:a1e0::1"), true},
		{ipv4Packet("100.100.100.100", "100.64.1.1"), true}, // local service address
		{ipv6Packet("fd7a:115c:a1e0::53", "fd7a:115c:a1e0::1"), true},
		{ipv4Packet("100.64.0.0", "100.64.1.1"), true}, // edges of 100.64.0.0/10
		{ipv4Packet("100.127.255.255", "100.64.1.1"), true},
		{ipv4Packet("100.63.255.255", "100.64.1.1"), false},
		{ipv4Packet("100.128.0.0", "100.64.1.1"), false},
		{ipv4Packet("100.64.1.2", "100.64.1.9"), false},   // someone else's address
		{ipv4Packet("100.64.1.2", "192.168.1.10"), false}, // the host's LAN address
		{ipv4Packet("8.8.8.8", "100.64.1.1"), false},      // not from the Tailnet
		{ipv4Packet("192.168.1.1", "100.64.1.1"), false},
		{ipv6Packet("2001:db8::1", "fd7a:115c:a1e0::1"), false},
		{ipv6Packet("fd7a:115c:a1e0::2", "fd7a:115c:a1e0::9"), false},
		{[]byte{0x45}, false},
		{make([]byte, packetMTU+1), false},
	} {
		if got := isTailnetReply(test.packet, assigned); got != test.want {
			t.Errorf("isTailnetReply(%x) = %v, want %v", test.packet[:min(len(test.packet), 20)], got, test.want)
		}
	}
	if isTailnetReply(ipv4Packet("100.64.1.2", "100.64.1.1"), func(netip.Addr) bool { return false }) {
		t.Fatal("accepted a reply while no address is assigned")
	}
	if isAssigned(nil, netip.MustParseAddr("100.64.1.1")) {
		t.Fatal("an address is assigned without a network map")
	}
}
