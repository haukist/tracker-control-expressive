package tailscalebridge

import (
	"net/netip"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
)

func dnsQuery(t *testing.T, name string, kind dnsmessage.Type) []byte {
	t.Helper()
	n, err := dnsmessage.NewName(name)
	if err != nil {
		t.Fatal(err)
	}
	data, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 0x1234, RecursionDesired: true},
		Questions: []dnsmessage.Question{{Name: n, Type: kind, Class: dnsmessage.ClassINET}}}).Pack()
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func dnsStatus() *ipnstate.Status {
	return &ipnstate.Status{BackendState: "Running",
		CurrentTailnet: &ipnstate.TailnetStatus{MagicDNSSuffix: "home.ts.net", MagicDNSEnabled: true},
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{{}: {DNSName: "server.home.ts.net.",
			TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.1.2"), netip.MustParseAddr("fd7a:115c:a1e0::2")}}}}
}

func TestLocalMagicDNS(t *testing.T) {
	for _, test := range []struct {
		name string
		kind dnsmessage.Type
	}{
		{"server.home.ts.net.", dnsmessage.TypeA}, {"SERVER.", dnsmessage.TypeAAAA},
		{"2.1.64.100.in-addr.arpa.", dnsmessage.TypePTR},
	} {
		query := dnsQuery(t, test.name, test.kind)
		response, err := resolveLocalDNS(query, dnsStatus())
		if err != nil {
			t.Fatal(err)
		}
		var result dnsmessage.Message
		if err := result.Unpack(response); err != nil {
			t.Fatal(err)
		}
		if result.ID != 0x1234 || result.RCode != dnsmessage.RCodeSuccess || len(result.Answers) != 1 {
			t.Fatalf("bad local response: %+v", result)
		}
		if result.Questions[0].Name.String() != test.name {
			t.Fatal("original question changed")
		}
	}
}

func TestLocalDNSNeverForwardsFailures(t *testing.T) {
	for _, name := range []string{"missing.home.ts.net.", "missing.", "99.1.64.100.in-addr.arpa."} {
		response, err := resolveLocalDNS(dnsQuery(t, name, dnsmessage.TypeA), dnsStatus())
		if err != nil || response == nil {
			t.Fatalf("local failure fell through: %s %v", name, err)
		}
		var message dnsmessage.Message
		if err := message.Unpack(response); err != nil {
			t.Fatal(err)
		}
		if message.RCode != dnsmessage.RCodeNameError {
			t.Fatalf("rcode %v for %s", message.RCode, name)
		}
	}
	for _, status := range []*ipnstate.Status{{BackendState: "NeedsLogin"}, dnsStatus()} {
		if status.CurrentTailnet != nil {
			status.CurrentTailnet.MagicDNSEnabled = false
		}
		response, err := resolveLocalDNS(dnsQuery(t, "server.home.ts.net.", dnsmessage.TypeA), status)
		if err != nil || response == nil {
			t.Fatal("unavailable MagicDNS fell through")
		}
		var message dnsmessage.Message
		if err := message.Unpack(response); err != nil {
			t.Fatal(err)
		}
		if message.RCode == dnsmessage.RCodeSuccess {
			t.Fatal("unavailable MagicDNS claimed success")
		}
	}
}

func TestSplitDNSBoundaries(t *testing.T) {
	for _, name := range []string{"example.com.", "evilhome.ts.net.", "server.home.ts.net.evil.", "1.1.168.192.in-addr.arpa."} {
		query := dnsQuery(t, name, dnsmessage.TypeA)
		if IsTailnetDNS(query, "home.ts.net") {
			t.Errorf("captured public/LAN query %s", name)
		}
		if response, err := resolveLocalDNS(query, dnsStatus()); response != nil || err != nil {
			t.Errorf("ordinary DNS did not reach host resolver: %s", name)
		}
	}
	if !IsTailnetDNS(dnsQuery(t, "server.home.ts.net.", dnsmessage.TypeA), "") {
		t.Fatal("unknown membership leaked a .ts.net name")
	}
	if IsTailnetDNS([]byte{1, 2, 3}, "home.ts.net") {
		t.Fatal("accepted malformed query")
	}
}
