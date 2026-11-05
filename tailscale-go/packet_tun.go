package tailscalebridge

import (
	"bytes"
	"errors"
	"io"
	"os"
	"sync"

	"github.com/tailscale/wireguard-go/tun"
)

const packetMTU = 1280
const packetQueueSize = 64

var errQueueFull = errors.New("Tailscale packet queue is full")

// packetTUN is an in-memory TUN. Only packets already approved by the host's
// firewall may be injected. It never opens an OS VPN or forwards to the LAN.
type packetTUN struct {
	mu     sync.RWMutex
	closed bool
	in     chan []byte
	out    chan []byte
	done   chan struct{}
	events chan tun.Event
}

func newPacketTUN() *packetTUN {
	t := &packetTUN{
		in: make(chan []byte, packetQueueSize), out: make(chan []byte, packetQueueSize),
		done: make(chan struct{}), events: make(chan tun.Event, 1),
	}
	t.events <- tun.EventUp
	return t
}

func (t *packetTUN) File() *os.File           { return nil }
func (t *packetTUN) MTU() (int, error)        { return packetMTU, nil }
func (t *packetTUN) Name() (string, error)    { return "tc-tailscale", nil }
func (t *packetTUN) Events() <-chan tun.Event { return t.events }
func (t *packetTUN) BatchSize() int           { return 1 }

func (t *packetTUN) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.closed {
		t.closed = true
		close(t.done)
		close(t.events)
	}
	return nil
}

func (t *packetTUN) inject(packet []byte) error {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if t.closed {
		return io.ErrClosedPipe
	}
	select {
	case t.in <- bytes.Clone(packet):
		return nil
	default:
		return errQueueFull
	}
}

func (t *packetTUN) receive() ([]byte, error) {
	select {
	case <-t.done:
		return nil, io.EOF
	case packet := <-t.out:
		t.mu.RLock()
		defer t.mu.RUnlock()
		if t.closed {
			return nil, io.EOF
		}
		return packet, nil
	}
}

func (t *packetTUN) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
	// Validate the host's allocation before consuming a packet. This device
	// accepts at most MTU-sized packets, plus the mandatory WireGuard headroom.
	if len(packets) == 0 || len(slab) < packetMTU+2*tun.ReadPacketSpacing {
		return 0, io.ErrShortBuffer
	}
	select {
	case <-t.done:
		return 0, os.ErrClosed
	case packet := <-t.in:
		t.mu.RLock()
		defer t.mu.RUnlock()
		if t.closed {
			return 0, os.ErrClosed
		}
		copy(slab[tun.ReadPacketSpacing:], packet)
		packets[0] = tun.ReadPacket{Offset: tun.ReadPacketSpacing, Size: len(packet)}
		return 1, nil
	}
}

func (t *packetTUN) Write(bufs [][]byte, offset int) (int, error) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if t.closed {
		return 0, os.ErrClosed
	}
	for i, buf := range bufs {
		if offset < 0 || offset > len(buf) {
			return i, io.ErrShortBuffer
		}
		select {
		case t.out <- bytes.Clone(buf[offset:]):
		default:
			// Never block the engine when the host cannot drain packets.
			return i, errQueueFull
		}
	}
	return len(bufs), nil
}
