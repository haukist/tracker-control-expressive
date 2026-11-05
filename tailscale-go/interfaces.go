package tailscalebridge

import (
	"encoding/json"
	"fmt"
	"net"

	"tailscale.com/net/netmon"
)

// interfaceDescription is the private JSON boundary with the Android host.
// Flags use Go net.Flags values. Addresses are IP/prefix CIDRs, including the
// host bits (for example 192.168.1.23/24), not just network prefixes.
type interfaceDescription struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Flags     uint     `json:"flags"`
	Addresses []string `json:"addresses"`
}

func parseInterfaces(data string) ([]netmon.Interface, error) {
	var descriptions []interfaceDescription
	if err := json.Unmarshal([]byte(data), &descriptions); err != nil {
		return nil, fmt.Errorf("invalid network interface JSON")
	}
	interfaces := make([]netmon.Interface, 0, len(descriptions))
	for _, description := range descriptions {
		// LinkProperties.getMtu() returns zero when Android has no explicit MTU.
		// Keep it unknown instead of inventing a physical-link MTU.
		if description.Name == "" || description.Index <= 0 || description.MTU < 0 {
			return nil, fmt.Errorf("invalid network interface metadata")
		}
		iface := netmon.Interface{Interface: &net.Interface{
			Name: description.Name, Index: description.Index,
			MTU: description.MTU, Flags: net.Flags(description.Flags),
		}, AltAddrs: make([]net.Addr, 0, len(description.Addresses))}
		for _, address := range description.Addresses {
			ip, network, err := net.ParseCIDR(address)
			if err != nil {
				return nil, fmt.Errorf("invalid interface address")
			}
			network.IP = ip // net.ParseCIDR otherwise masks out host bits.
			iface.AltAddrs = append(iface.AltAddrs, network)
		}
		interfaces = append(interfaces, iface)
	}
	return interfaces, nil
}
