package tailscalebridge

import (
	"bytes"
	"errors"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/wireguard-go/tun"
)

var _ tun.Device = (*packetTUN)(nil)

func TestPacketOwnershipAndSpacing(t *testing.T) {
	dev := newPacketTUN()
	defer dev.Close()
	packet := ipv4Packet("100.64.1.1", "100.64.1.2")
	expected := bytes.Clone(packet)
	if err := dev.inject(packet); err != nil {
		t.Fatal(err)
	}
	packet[0] = 0
	slab := make([]byte, packetMTU+2*tun.ReadPacketSpacing)
	packets := make([]tun.ReadPacket, 1)
	if n, err := dev.Read(slab, packets); n != 1 || err != nil {
		t.Fatalf("Read = %d, %v", n, err)
	}
	p := packets[0]
	if p.Offset != tun.ReadPacketSpacing || !bytes.Equal(slab[p.Offset:p.Offset+p.Size], expected) {
		t.Fatal("packet not copied with the required TUN headroom")
	}
	if n, err := dev.Write([][]byte{slab[:p.Offset+p.Size]}, p.Offset); n != 1 || err != nil {
		t.Fatalf("Write = %d, %v", n, err)
	}
	clear(slab)
	reply, err := dev.receive()
	if err != nil || !bytes.Equal(reply, expected) {
		t.Fatalf("reply = %x, %v", reply, err)
	}
}

// The host reuses one buffer for every packet and passes a prefix of it.
func TestInjectCopiesPrefixOfReusedBuffer(t *testing.T) {
	dev := newPacketTUN()
	defer dev.Close()
	packet := ipv4Packet("100.64.1.1", "100.64.1.2")
	buffer := append(bytes.Clone(packet), 0xff, 0xff)
	if err := dev.inject(buffer[:len(packet)]); err != nil {
		t.Fatal(err)
	}
	clear(buffer)
	if queued := <-dev.in; !bytes.Equal(queued, packet) {
		t.Fatalf("queued packet follows the reused buffer: %x", queued)
	}
}

func TestQueueBackpressureDoesNotBlock(t *testing.T) {
	dev := newPacketTUN()
	defer dev.Close()
	packet := ipv4Packet("100.64.1.1", "100.64.1.2")
	for i := 0; i < packetQueueSize; i++ {
		if err := dev.inject(packet); err != nil {
			t.Fatal(err)
		}
		if _, err := dev.Write([][]byte{packet}, 0); err != nil {
			t.Fatal(err)
		}
	}
	if err := dev.inject(packet); !errors.Is(err, errQueueFull) {
		t.Fatalf("full input queue: %v", err)
	}
	if _, err := dev.Write([][]byte{packet}, 0); !errors.Is(err, errQueueFull) {
		t.Fatalf("full output queue: %v", err)
	}
}

func TestShortReadBufferDoesNotConsumePacket(t *testing.T) {
	dev := newPacketTUN()
	defer dev.Close()
	packet := ipv4Packet("100.64.1.1", "100.64.1.2")
	if err := dev.inject(packet); err != nil {
		t.Fatal(err)
	}
	if _, err := dev.Read(make([]byte, 1), make([]tun.ReadPacket, 1)); !errors.Is(err, io.ErrShortBuffer) {
		t.Fatalf("undersized slab: %v", err)
	}
	slab := make([]byte, packetMTU+2*tun.ReadPacketSpacing)
	packets := make([]tun.ReadPacket, 1)
	if _, err := dev.Read(slab, nil); !errors.Is(err, io.ErrShortBuffer) {
		t.Fatalf("missing packet descriptors: %v", err)
	}
	if n, err := dev.Read(slab, packets); n != 1 || err != nil {
		t.Fatalf("packet was lost: %d, %v", n, err)
	}
}

func TestCloseUnblocksReaders(t *testing.T) {
	dev := newPacketTUN()
	results := make(chan error, 2)
	go func() { _, err := dev.Read(make([]byte, packetMTU+128), make([]tun.ReadPacket, 1)); results <- err }()
	go func() { _, err := dev.receive(); results <- err }()
	dev.Close()
	dev.Close()
	for i := 0; i < 2; i++ {
		select {
		case err := <-results:
			if err == nil {
				t.Fatal("closed reader succeeded")
			}
		case <-time.After(time.Second):
			t.Fatal("reader still blocked after Close")
		}
	}
	if err := dev.inject([]byte{1}); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("inject after Close: %v", err)
	}
}

func TestConcurrentShutdown(t *testing.T) {
	dev := newPacketTUN()
	var workers sync.WaitGroup
	for i := 0; i < 8; i++ {
		workers.Go(func() {
			for j := 0; j < 100; j++ {
				_ = dev.inject([]byte{1})
				_, _ = dev.Write([][]byte{{1}}, 0)
			}
		})
	}
	dev.Close()
	workers.Wait()
}
