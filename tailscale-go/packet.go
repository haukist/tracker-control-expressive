package tailscalebridge

import (
	"encoding/binary"
	"errors"
	"net/netip"
)

var (
	tailnet4 = netip.MustParsePrefix("100.64.0.0/10")
	tailnet6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
	quad4    = netip.MustParseAddr("100.100.100.100")
	quad6    = netip.MustParseAddr("fd7a:115c:a1e0::53")
)

func packetAddresses(packet []byte) (source, dest netip.Addr, err error) {
	if len(packet) == 0 || len(packet) > packetMTU {
		return source, dest, errors.New("invalid IP packet size")
	}
	switch packet[0] >> 4 {
	case 4:
		if len(packet) >= 20 {
			header := int(packet[0]&15) * 4
			if header >= 20 && header <= len(packet) && int(binary.BigEndian.Uint16(packet[2:4])) == len(packet) {
				return netip.AddrFrom4([4]byte(packet[12:16])), netip.AddrFrom4([4]byte(packet[16:20])), nil
			}
		}
	case 6:
		if len(packet) >= 40 && int(binary.BigEndian.Uint16(packet[4:6]))+40 == len(packet) {
			return netip.AddrFrom16([16]byte(packet[8:24])), netip.AddrFrom16([16]byte(packet[24:40])), nil
		}
	}
	return source, dest, errors.New("malformed IP packet")
}

func isPeerAddress(addr netip.Addr) bool {
	return (tailnet4.Contains(addr) || tailnet6.Contains(addr)) && addr != quad4 && addr != quad6
}

// isTailnetReply reports whether a packet leaving the engine may be handed to
// the host: well formed, at most one MTU, sent from the Tailnet address space
// and addressed to this node.
func isTailnetReply(packet []byte, assigned func(netip.Addr) bool) bool {
	source, dest, err := packetAddresses(packet)
	if err != nil {
		return false
	}
	return (tailnet4.Contains(source) || tailnet6.Contains(source)) && assigned(dest)
}

func addressStrings(addresses []netip.Addr) []string {
	result := make([]string, len(addresses))
	for i, addr := range addresses {
		result[i] = addr.String()
	}
	return result
}
