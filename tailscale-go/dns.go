package tailscalebridge

import (
	"encoding/hex"
	"net/netip"
	"slices"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/ipn/ipnstate"
)

func tailnetDNSSettings(status *ipnstate.Status) (string, bool) {
	if status.CurrentTailnet == nil {
		return "", false
	}
	return normalizeDNSName(status.CurrentTailnet.MagicDNSSuffix), status.CurrentTailnet.MagicDNSEnabled
}

func normalizeDNSName(name string) string { return strings.ToLower(strings.TrimSuffix(name, ".")) }

// IsTailnetDNS classifies a wire query for the Android proxy even when the
// engine is unavailable. An unknown suffix conservatively reserves .ts.net
// until membership is known; a known suffix reserves only that tailnet.
func IsTailnetDNS(query []byte, suffix string) bool {
	var parser dnsmessage.Parser
	if _, err := parser.Start(query); err != nil {
		return false
	}
	question, err := parser.Question()
	return err == nil && isLocalDNSName(question.Name.String(), suffix)
}

func isLocalDNSName(name, suffix string) bool {
	name = normalizeDNSName(name)
	suffix = normalizeDNSName(suffix)
	if name != "" && !strings.Contains(name, ".") {
		return true
	}
	if suffix != "" && (name == suffix || strings.HasSuffix(name, "."+suffix)) {
		return true
	}
	if suffix == "" && strings.HasSuffix(name, ".ts.net") {
		return true
	}
	addr, ok := reverseAddress(name)
	return ok && (tailnet4.Contains(addr) || tailnet6.Contains(addr))
}

func reverseAddress(name string) (netip.Addr, bool) {
	if strings.HasSuffix(name, ".in-addr.arpa") {
		labels := strings.Split(strings.TrimSuffix(name, ".in-addr.arpa"), ".")
		if len(labels) == 4 {
			addr, err := netip.ParseAddr(strings.Join([]string{labels[3], labels[2], labels[1], labels[0]}, "."))
			return addr, err == nil && addr.Is4()
		}
	}
	if strings.HasSuffix(name, ".ip6.arpa") {
		labels := strings.Split(strings.TrimSuffix(name, ".ip6.arpa"), ".")
		if len(labels) == 32 {
			var digits strings.Builder
			for i := 31; i >= 0; i-- {
				if len(labels[i]) != 1 {
					return netip.Addr{}, false
				}
				digits.WriteString(labels[i])
			}
			data, err := hex.DecodeString(digits.String())
			if err == nil {
				return netip.AddrFrom16([16]byte(data)), true
			}
		}
	}
	return netip.Addr{}, false
}

// dnsRecords is the immutable MagicDNS view of one backend status. Building it
// walks every peer, so the client reuses one across queries.
type dnsRecords struct {
	running bool
	suffix  string
	enabled bool
	byName  map[string][]netip.Addr
	byAddr  map[netip.Addr]string
}

func newDNSRecords(status *ipnstate.Status) *dnsRecords {
	records := &dnsRecords{running: status.BackendState == "Running",
		byName: make(map[string][]netip.Addr), byAddr: make(map[netip.Addr]string)}
	records.suffix, records.enabled = tailnetDNSSettings(status)
	add := func(dnsName string, ips []netip.Addr) {
		name := normalizeDNSName(dnsName)
		if name == "" {
			return // A node without a name can be neither looked up nor a PTR target.
		}
		ips = slices.Clone(ips) // Own the addresses: status belongs to the caller.
		records.byName[name] = ips
		for _, ip := range ips {
			// Duplicate ownership is not expected in a normal Tailnet. Keep
			// malformed/overlapping status deterministic instead of map-order dependent.
			if owner, ok := records.byAddr[ip]; !ok || name < owner {
				records.byAddr[ip] = name
			}
		}
	}
	if status.Self != nil {
		add(status.Self.DNSName, status.Self.TailscaleIPs)
	}
	for _, peer := range status.Peer {
		add(peer.DNSName, peer.TailscaleIPs)
	}
	return records
}

// dnsRecordsMaxAge bounds how long a missed notification can keep MagicDNS
// answers stale: upstream drops a notification consumer that falls behind.
const dnsRecordsMaxAge = 5 * time.Second

// dnsCache keeps the records built for one change count. Concurrent queries
// wait for a single rebuild instead of each walking the peers.
type dnsCache struct {
	mu      sync.Mutex
	records *dnsRecords
	gen     uint64
	built   time.Time
}

// get returns the records for change count gen, which the caller must read
// before calling so that a change during build is not attributed to its result.
func (d *dnsCache) get(gen uint64, now time.Time, build func() *dnsRecords) *dnsRecords {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.records == nil || d.gen != gen || now.Sub(d.built) > dnsRecordsMaxAge || now.Before(d.built) {
		d.records, d.gen, d.built = build(), gen, now
	}
	return d.records
}

func resolveLocalDNS(query []byte, status *ipnstate.Status) ([]byte, error) {
	return newDNSRecords(status).resolve(query)
}

func (r *dnsRecords) resolve(query []byte) ([]byte, error) {
	var parser dnsmessage.Parser
	header, err := parser.Start(query)
	if err != nil {
		return nil, err
	}
	question, err := parser.Question()
	if err != nil {
		return nil, err
	}
	if !isLocalDNSName(question.Name.String(), r.suffix) {
		return nil, nil
	}
	rcode := dnsmessage.RCodeSuccess
	if _, err := parser.Question(); err != dnsmessage.ErrSectionDone || header.Response || header.OpCode != 0 {
		rcode = dnsmessage.RCodeFormatError
	} else if !r.running || r.suffix == "" {
		rcode = dnsmessage.RCodeServerFailure
	} else if !r.enabled {
		rcode = dnsmessage.RCodeNameError
	}
	lookup := normalizeDNSName(question.Name.String())
	if !strings.Contains(lookup, ".") {
		lookup += "." + r.suffix
	}
	addresses := r.byName[lookup]
	ptr := ""
	if reverse, ok := reverseAddress(lookup); ok {
		ptr = r.byAddr[reverse]
	}
	if rcode == dnsmessage.RCodeSuccess && len(addresses) == 0 && ptr == "" {
		rcode = dnsmessage.RCodeNameError
	}
	builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID: header.ID, Response: true, Authoritative: true,
		RecursionDesired: header.RecursionDesired, RCode: rcode,
	})
	if err = builder.StartQuestions(); err != nil {
		return nil, err
	}
	if err = builder.Question(question); err != nil {
		return nil, err
	}
	if err = builder.StartAnswers(); err != nil {
		return nil, err
	}
	if rcode == dnsmessage.RCodeSuccess && question.Class == dnsmessage.ClassINET {
		resource := dnsmessage.ResourceHeader{Name: question.Name, Class: dnsmessage.ClassINET, TTL: 30}
		for _, addr := range addresses {
			if question.Type == dnsmessage.TypeA && addr.Is4() {
				err = builder.AResource(resource, dnsmessage.AResource{A: addr.As4()})
			} else if question.Type == dnsmessage.TypeAAAA && addr.Is6() {
				err = builder.AAAAResource(resource, dnsmessage.AAAAResource{AAAA: addr.As16()})
			}
			if err != nil {
				return nil, err
			}
		}
		if question.Type == dnsmessage.TypePTR && ptr != "" {
			name, err := dnsmessage.NewName(ptr + ".")
			if err != nil {
				return nil, err
			}
			if err = builder.PTRResource(resource, dnsmessage.PTRResource{PTR: name}); err != nil {
				return nil, err
			}
		}
	}
	return builder.Finish()
}
