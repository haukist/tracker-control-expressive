package tailscalebridge

import (
	"encoding/json"
	"io"
	"path/filepath"
	"testing"

	"tailscale.com/feature/buildfeatures"
	"tailscale.com/logpolicy"
)

func TestConstructorValidation(t *testing.T) {
	if _, err := New("relative-state", "test", nil); err == nil {
		t.Fatal("accepted non-private relative state path")
	}
	if _, err := New(t.TempDir(), "", nil); err == nil {
		t.Fatal("accepted empty hostname")
	}
}

// Exercises the actual engine/backend startup and shutdown without logging in
// or registering any device. Connectivity is not required for this test.
func TestBackendLifecycle(t *testing.T) {
	if buildfeatures.HasPeerAPIServer {
		t.Skip("real backend requires -tags=ts_omit_peerapiserver")
	}
	dir := filepath.Join(t.TempDir(), "state")
	t.Setenv("TS_LOGS_DIR", filepath.Join(dir, "unavailable-system-logs"))
	client, err := New(dir, "trackercontrol-test", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if got := logpolicy.LogsDir(func(string, ...any) {}); got != filepath.Join(dir, "logs") {
		t.Fatalf("backend logs escaped private state directory: %s", got)
	}
	if _, err := New(t.TempDir(), "second", nil); err == nil {
		t.Fatal("allowed a second engine to replace the global socket protector")
	}
	status, err := client.StatusJSON()
	if err != nil || !json.Valid([]byte(status)) {
		t.Fatalf("invalid status %q: %v", status, err)
	}
	if err := client.SendPacket(ipv4Packet("100.64.1.1", "100.64.1.2")); err == nil {
		t.Fatal("accepted packet without authenticated/assigned identity")
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := client.ReadPacket(); err != io.EOF {
		t.Fatalf("ReadPacket after close: %v", err)
	}
	if err := client.NextEvent(); err != io.EOF {
		t.Fatalf("NextEvent after close: %v", err)
	}
	if _, err := client.StatusJSON(); err != io.ErrClosedPipe {
		t.Fatalf("StatusJSON after close: %v", err)
	}
	if err := client.Login(); err != io.ErrClosedPipe {
		t.Fatalf("Login after close: %v", err)
	}
	if err := client.NetworkChanged(""); err != io.ErrClosedPipe {
		t.Fatalf("NetworkChanged after close: %v", err)
	}
	if err := client.SendPacket(nil); err != io.ErrClosedPipe {
		t.Fatalf("SendPacket after close: %v", err)
	}
	if err := client.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
	replacement, err := New(dir, "trackercontrol-test", nil)
	if err != nil {
		t.Fatal(err)
	}
	replacement.Close()
}

func TestBuildWithoutClientOnlyTagIsRejected(t *testing.T) {
	if !buildfeatures.HasPeerAPIServer {
		t.Skip("peer API server is already excluded")
	}
	if _, err := New(t.TempDir(), "test", nil); err == nil {
		t.Fatal("allowed a build with inbound services")
	}
}
