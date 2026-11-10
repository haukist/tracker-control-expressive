package tailscalebridge

import (
	"net/netip"
	"strings"
	"testing"
	"time"

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

func TestDuplicateReverseOwnersAreDeterministic(t *testing.T) {
	status := dnsStatus()
	status.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{
		DNSName: "aaa.home.ts.net.", TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.1.2")},
	}
	query := dnsQuery(t, "2.1.64.100.in-addr.arpa.", dnsmessage.TypePTR)
	for i := 0; i < 64; i++ {
		response, err := resolveLocalDNS(query, status)
		if err != nil {
			t.Fatal(err)
		}
		var message dnsmessage.Message
		if err := message.Unpack(response); err != nil {
			t.Fatal(err)
		}
		if len(message.Answers) != 1 {
			t.Fatalf("answers: %v", message.Answers)
		}
		ptr, ok := message.Answers[0].Body.(*dnsmessage.PTRResource)
		if !ok || ptr.PTR.String() != "aaa.home.ts.net." {
			t.Fatalf("non-deterministic duplicate owner: %v", message.Answers[0])
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

func TestDNSRecordsAreReusableAcrossQueries(t *testing.T) {
	records := newDNSRecords(dnsStatus())
	ipv6PTR := "2." + strings.Repeat("0.", 20) + "e.1.a.c.5.1.1.a.7.d.f.ip6.arpa."
	for i := 0; i < 2; i++ {
		for name, kind := range map[string]dnsmessage.Type{
			"server.home.ts.net.": dnsmessage.TypeA, "2.1.64.100.in-addr.arpa.": dnsmessage.TypePTR,
			ipv6PTR: dnsmessage.TypePTR,
		} {
			response, err := records.resolve(dnsQuery(t, name, kind))
			if err != nil {
				t.Fatal(err)
			}
			var message dnsmessage.Message
			if err := message.Unpack(response); err != nil {
				t.Fatal(err)
			}
			if len(message.Answers) != 1 {
				t.Fatalf("%s: answers %v rcode %v", name, message.Answers, message.RCode)
			}
		}
	}
	// A node without a DNS name must not shadow the named owner of an address.
	unnamed := dnsStatus()
	unnamed.Peer[key.NewNode().Public()] = &ipnstate.PeerStatus{
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.1.2")}}
	response, err := newDNSRecords(unnamed).resolve(dnsQuery(t, "2.1.64.100.in-addr.arpa.", dnsmessage.TypePTR))
	if err != nil {
		t.Fatal(err)
	}
	var named dnsmessage.Message
	if err := named.Unpack(response); err != nil || len(named.Answers) != 1 {
		t.Fatalf("unnamed node hid the PTR owner: %v %v", named.Answers, err)
	}
	// A record set is a snapshot: it must not follow later status changes.
	status := dnsStatus()
	records = newDNSRecords(status)
	status.BackendState = "Stopped"
	for _, peer := range status.Peer {
		clear(peer.TailscaleIPs)
	}
	clear(status.Peer)
	response, err = records.resolve(dnsQuery(t, "server.home.ts.net.", dnsmessage.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	var message dnsmessage.Message
	if err := message.Unpack(response); err != nil || len(message.Answers) != 1 {
		t.Fatalf("snapshot followed its source: %v %v", message.Answers, err)
	}
}

func TestDNSCacheInvalidation(t *testing.T) {
	var cache dnsCache
	builds := 0
	build := func() *dnsRecords { builds++; return newDNSRecords(dnsStatus()) }
	start := time.Unix(1000, 0)

	first := cache.get(7, start, build)
	if cache.get(7, start.Add(dnsRecordsMaxAge), build) != first || builds != 1 {
		t.Fatalf("unchanged count within the age limit rebuilt: %d builds", builds)
	}
	if cache.get(8, start.Add(time.Second), build) == first || builds != 2 {
		t.Fatalf("a backend change did not rebuild: %d builds", builds)
	}
	// Without any notification the records still expire.
	if cache.get(8, start.Add(time.Second+dnsRecordsMaxAge+1), build); builds != 3 {
		t.Fatalf("stale records survived the age limit: %d builds", builds)
	}
	// A clock that steps backwards must not extend their life.
	if cache.get(8, start, build); builds != 4 {
		t.Fatalf("records outlived a clock step: %d builds", builds)
	}
	// The caller read count 8 before a change arrived mid-build: the result is
	// filed under 8, so the next query (now reading 9) builds again.
	if cache.get(9, start, build); builds != 5 {
		t.Fatalf("a change during a build was lost: %d builds", builds)
	}
}
