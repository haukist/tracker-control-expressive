// Package tailscalebridge embeds Tailscale behind a raw-packet boundary.
// The Android host retains ownership of its existing VPN service.
package tailscalebridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"

	"tailscale.com/feature/buildfeatures"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/store"
	"tailscale.com/net/netmon"
	"tailscale.com/net/tsdial"
	"tailscale.com/tsd"
	"tailscale.com/types/logid"
	"tailscale.com/util/eventbus"
	"tailscale.com/wgengine"
)

// Platform is supplied by the Android host. Protect must call
// VpnService.protect(fd). InterfacesJSON supplies ConnectivityManager-derived
// interface information because net.Interfaces is restricted on modern Android.
// Callbacks must remain usable until Close returns; they may run concurrently.
type Platform interface {
	Protect(fd int) bool
	InterfacesJSON() string
	DefaultInterface() string
}

var active atomic.Bool

// Client owns the engine and its state. Only one client may exist at a time:
// Android's protected-socket hook is process-global. Call Close before replacing it.
type Client struct {
	mu      sync.RWMutex
	backend *ipnlocal.LocalBackend
	packets *packetTUN
	monitor *netmon.Monitor
	dialer  *tsdial.Dialer
	bus     *eventbus.Bus
	closed  bool
	events  chan struct{}
	done    chan struct{}
}

// New starts a real Tailscale backend with a private persistent state directory.
// It creates no OS routes, no OS VPN, no listening services, and no log uploader.
// On Android platform is mandatory. The host must configure its existing TUN
// using the assigned addresses in StatusJSON before injecting approved packets.
func New(stateDir, hostname string, platform Platform) (_ *Client, err error) {
	if buildfeatures.HasPeerAPIServer {
		return nil, errors.New("build with -tags=ts_omit_peerapiserver to disable inbound services")
	}
	if !filepath.IsAbs(stateDir) || hostname == "" {
		return nil, errors.New("absolute private state directory and hostname required")
	}
	if !active.CompareAndSwap(false, true) {
		return nil, errors.New("a Tailscale client is already active")
	}
	defer func() {
		if err != nil {
			clearProtector()
			active.Store(false)
		}
	}()
	if err = installPlatform(platform); err != nil {
		return nil, err
	}
	if err = os.MkdirAll(stateDir, 0700); err != nil {
		return nil, err
	}
	return startBackend(stateDir, hostname)
}

func startBackend(stateDir, hostname string) (_ *Client, err error) {
	// Deliberately do not instantiate logpolicy/logtail. Auth URLs are retrieved
	// through StatusJSON only, never written to a logger.
	logf := func(string, ...any) {}
	// NewLocalBackend initializes local socket statistics even without logtail.
	// Android has no writable conventional home/cache/tmp directory for Go;
	// prevent logpolicy's fallback search from panicking outside app storage.
	logsDir := filepath.Join(stateDir, "logs")
	if err := os.MkdirAll(logsDir, 0700); err != nil {
		return nil, err
	}
	if err := os.Setenv("TS_LOGS_DIR", logsDir); err != nil {
		return nil, err
	}
	sys := tsd.NewSystem()
	bus := sys.Bus.Get()
	defer func() {
		if err != nil {
			bus.Close()
		}
	}()
	state, err := store.New(logf, filepath.Join(stateDir, "tailscaled.state"))
	if err != nil {
		return nil, err
	}
	sys.Set(state)
	monitor, err := netmon.New(sys.Bus.Get(), logf)
	if err != nil {
		return nil, err
	}
	dialer := &tsdial.Dialer{Logf: logf}
	dialer.SetBus(sys.Bus.Get())
	packets := newPacketTUN()
	defer func() {
		if err != nil {
			packets.Close()
			dialer.Close()
			monitor.Close()
		}
	}()
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun: packets, NetMon: monitor, Dialer: dialer, EventBus: sys.Bus.Get(),
		SetSubsystem: sys.Set, HealthTracker: sys.HealthTracker.Get(),
		Metrics: sys.UserMetricsRegistry(), ControlKnobs: sys.ControlKnobs(),
	})
	if err != nil {
		return nil, err
	}
	sys.Set(engine)
	sys.Tun.Get().Start()
	backend, err := ipnlocal.NewLocalBackend(logf, logid.PublicID{}, sys, 0)
	if err != nil {
		engine.Close()
		return nil, err
	}
	backend.SetVarRoot(stateDir)
	prefs := ipn.NewPrefs()
	prefs.Hostname = hostname
	prefs.WantRunning = true
	prefs.RouteAll = false
	prefs.CorpDNS = false
	prefs.ShieldsUp = true
	client := &Client{backend: backend, packets: packets, monitor: monitor, dialer: dialer,
		bus: bus, events: make(chan struct{}, 1), done: make(chan struct{})}
	backend.SetNotifyCallback(func(ipn.Notify) {
		select {
		case client.events <- struct{}{}:
		default:
		}
	})
	if err = backend.Start(ipn.Options{UpdatePrefs: prefs}); err != nil {
		backend.Shutdown()
		return nil, err
	}
	return client, nil
}

// Login requests browser authentication. Read the URL from StatusJSON; do not
// log it. This returns before the user completes authentication.
func (c *Client) Login() error {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return io.ErrClosedPipe
	}
	return c.backend.StartLoginInteractive(context.Background())
}

// StatusJSON is an on-demand snapshot, not a request to start background polling.
// It intentionally excludes keys, profiles and user identity. authURL is sensitive.
func (c *Client) StatusJSON() (string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return "", io.ErrClosedPipe
	}
	status := c.backend.StatusWithoutPeers()
	suffix, magicDNS := tailnetDNSSettings(status)
	data, err := json.Marshal(struct {
		State     string   `json:"state"`
		AuthURL   string   `json:"authURL,omitempty"`
		Addresses []string `json:"addresses"`
		MTU       int      `json:"mtu"`
		DNSSuffix string   `json:"dnsSuffix"`
		MagicDNS  bool     `json:"magicDNS"`
	}{status.BackendState, status.AuthURL, addressStrings(status.TailscaleIPs), packetMTU, suffix, magicDNS})
	return string(data), err
}

// NextEvent blocks for a coalesced backend change. It does not poll. The host
// should re-read StatusJSON after each event; Close wakes the blocked caller.
func (c *Client) NextEvent() error {
	select {
	case <-c.done:
		return io.EOF
	case <-c.events:
		select {
		case <-c.done:
			return io.EOF
		default:
			return nil
		}
	}
}

// ResolveDNS answers local MagicDNS records only. A nil response means this is
// an ordinary query for the host's existing resolver. No upstream DNS is used.
func (c *Client) ResolveDNS(query []byte) ([]byte, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return nil, io.ErrClosedPipe
	}
	return resolveLocalDNS(query, c.backend.Status())
}

// SendPacket accepts one policy-approved IP packet. Public internet, subnet
// routing, MagicDNS and spoofed source addresses are rejected in this P0 harness.
func (c *Client) SendPacket(packet []byte) error {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return io.ErrClosedPipe
	}
	source, dest, err := packetAddresses(packet)
	if err != nil {
		return err
	}
	if !isPeerAddress(dest) {
		return errors.New("only Tailnet peer destinations are supported")
	}
	networkMap := c.backend.NetMapNoPeers()
	if c.backend.State() != ipn.Running || networkMap == nil {
		return errors.New("Tailscale is not ready")
	}
	assigned := false
	addresses := networkMap.GetAddresses()
	for i := 0; i < addresses.Len(); i++ {
		if addresses.At(i).Addr() == source {
			assigned = true
			break
		}
	}
	if !assigned {
		return errors.New("Tailscale is not ready or packet source is not assigned")
	}
	if peer, ok := c.backend.PeerForIP(dest); !ok || peer.IsSelf {
		return errors.New("unknown Tailnet peer")
	}
	return c.packets.inject(packet)
}

// SendPacketPrefix is SendPacket for the first length bytes of a buffer the
// host reuses for every packet, so it need not allocate an exact-size copy
// per packet. The buffer is not retained after the call returns.
func (c *Client) SendPacketPrefix(buffer []byte, length int) error {
	if length < 0 || length > len(buffer) {
		return errors.New("invalid packet length")
	}
	return c.SendPacket(buffer[:length])
}

// ReadPacket blocks until a decrypted reply is available or Close is called.
// Call it on a background thread; return the packet to the host's existing TUN.
func (c *Client) ReadPacket() ([]byte, error) { return c.packets.receive() }

// NetworkChanged asks the engine to re-check connectivity after the host has
// observed a network change. It creates no polling timer.
func (c *Client) NetworkChanged(defaultInterface string) error {
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.closed {
		return io.ErrClosedPipe
	}
	updateDefaultInterface(defaultInterface)
	c.monitor.InjectEvent()
	return nil
}

// Close stops forwarding, wakes ReadPacket and retains the persistent identity.
// A host implementing logout must additionally erase its private state storage.
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	c.closed = true
	close(c.done)
	c.packets.Close()
	c.backend.Shutdown()
	c.dialer.Close()
	err := c.monitor.Close()
	c.bus.Close()
	clearProtector()
	active.Store(false)
	if err != nil {
		return fmt.Errorf("close network monitor: %w", err)
	}
	return nil
}
