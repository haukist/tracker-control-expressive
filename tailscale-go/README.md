# Tailscale raw-packet bridge

This implements the Go boundary of [the Tailnet access specification](../docs/design-tailscale-access.md).
It uses the real Tailscale userspace engine and persistent control-plane backend.
The app's `tailscale` package connects it to the existing VpnService, settings
and split DNS proxy. The app owns the only TUN reader; Go receives policy-approved
packets over a socketpair and returns decrypted replies to a duplicate TUN writer.
Authenticated device/DERP connectivity and power consumption still need device testing.

## Build and test

Use Go 1.27.3 or newer. Dependency versions and checksums are committed in
`go.mod` / `go.sum`; do not replace the pinned versions with `@latest`.

```sh
cd tailscale-go
TAGS="$(python ../scripts/build_tailscale_poc.py --print-tags)" &&
go test -tags="$TAGS" ./... &&
go test -race -tags="$TAGS" ./... &&
go vet -tags="$TAGS" ./...
```

The tags compile out upstream features this app never uses; the list lives in
`scripts/build_tailscale_poc.py` so tests and the shipped AAR use the same one.
`ts_omit_peerapiserver` is mandatory: it disables Tailscale's peer API server
(including inbound services outside this feature's scope), and `New` fails
without it rather than accidentally starting the peer API after login. The
others remove Tailscale SSH, Taildrop, Serve/Funnel, the web client, exit-node
and subnet-router roles, client self-update and the log and network-flow-log
uploaders, among others. Shields Up is also enabled; subnet routes,
exit nodes and OS DNS configuration are not enabled.

Tests cover packet framing/address boundaries, bounded queues, packet ownership,
shutdown under concurrent writes, Android interface JSON parsing, and real
engine/backend construction without authentication. They do not prove live
Tailnet connectivity or Android VPN compatibility.

## Manual host harness

```sh
TAGS="$(python ../scripts/build_tailscale_poc.py --print-tags)" &&
go run -tags="$TAGS" ./cmd/tailscale-poc -state-dir /absolute/private/path
```

Commands are `status`, `login`, `send <IP-packet-hex>`, `read`, `network-changed`,
and `quit`. `login` starts interactive authentication; a later `status` exposes
the browser URL. Authentication creates a real Tailnet node, so only do this
when intentionally testing with your own account. Do not pipe status or packets
into shared logs. State files contain the node's identity and keys.

The harness creates no system TUN or OS routes, and does not forward normal
host traffic. `send` requires a complete IP packet with an assigned Tailnet source
address and a known Tailnet peer destination. Public IPs, subnet destinations,
spoofed sources, oversized/malformed packets and Quad100 DNS are rejected.
There is no direct-network fallback. `read` blocks until a reply or client close.
Crafted packets must include valid transport checksums; the host harness is a
packet-boundary test tool, not a TCP client or a replacement for a real host TUN.

## Android binding build

Prerequisites: Android SDK, NDK 29.0.14206865, JDK, Go, Python 3.10+, and both tools below. Installing them is
an explicit setup step; Gradle builds the AAR from source but does not install tools.

```sh
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
python scripts/build_tailscale_poc.py --android-sdk /path/to/android-sdk
```

Run the Python script from the repository root. `--go`, `--gomobile` and `--gobind`
can select explicit executables. `--android-ndk` selects a task-local NDK if needed.
The script checks tool/NDK versions, requests API23
and all four app ABIs, applies path/build-id stripping and 16 KB linker alignment,
and checks the generated AAR's manifest, ABI coverage and ELF load-segment alignment. The result is
`tailscale-go/build/tailscale.aar`, consumed by the app's `tailscaleBuild` task.
Device loading and reproducibility still require validation.
Do not raise the app's minSdk to accommodate a failed P0 build.

## Android host contract

`TailscaleManager` is the Android host and owns this lifecycle within the sole
`VpnService`; the Go package never establishes another VPN.

1. Implement `Platform.Protect(fd)` with `VpnService.protect(fd)`; return failures.
2. Implement `Platform.InterfacesJSON()` using Android's interface/link data.
   `Platform.DefaultInterface()` supplies the current physical default interface.
   Callbacks can be concurrent and must remain available until `Close` returns.
3. Call `New` with an absolute, app-private directory excluded from Android backup,
   settings export and logs. Use a dedicated directory, not a WireGuard state path.
4. Use `Login` only when authentication is needed; handle the sensitive URL from
   `StatusJSON` in the browser flow. The snapshot includes state, assigned IPs,
   MTU, and MagicDNS settings. `NextEvent` wakes the host on backend changes
   without polling. A change in assigned addresses rebuilds the VPN before forwarding.
5. Configure the existing VPN TUN with the assigned source addresses and Tailnet
   capture routes before sending packets. The bridge does not NAT packets. The
   TUN keeps the host's own MTU: the host answers a Tailnet-bound packet above
   1280 bytes with ICMP "packet too big" (path MTU discovery) and drops it.
6. After existing TrackerControl policy accepts a packet, call `SendPacket`.
   Queue-full or any other error means drop, never forward via the physical network.
7. Read replies on a background thread with `ReadPacket` and write them to that TUN.
8. On connectivity changes, call `NetworkChanged` with the current underlying
   interface name (empty when disconnected). Supply the current interface list.
9. Call `Close` before replacing the engine or releasing platform callbacks.
   It wakes blocked readers and retains identity. To log out, call `Logout`
   first (it asks the control server to expire the node key, waiting at most
   five seconds), then `Close`, then remove this dedicated state directory
   once all resources have stopped. The local removal must not depend on
   `Logout` succeeding.

Interface JSON is an array, for example:

```json
[{"name":"wlan0","index":3,"mtu":1500,"flags":1,"addresses":["192.168.1.23/24"]}]
```

Addresses include host bits; an unknown `LinkProperties` MTU is represented by zero.
`flags` uses Go `net.Flags` values; a host adapter
must translate Android flags, not copy unrelated numeric constants. Include
current physical and VPN interfaces without inventing link addresses or indices.
The provider is required because Go's default interface enumeration is restricted
for Android application UIDs on recent Android versions. It is process-global,
as is socket protection, so only one bridge client can be active.

No Tailscale log uploader is constructed. Library logs discard auth URLs and
backend diagnostics; only explicit status reads expose the login URL. Any later
diagnostic UI must redact credentials. Normal app DNS/DoH must remain outside
the raw packet bridge. `ResolveDNS` answers MagicDNS A/AAAA/PTR records from
current peer metadata, without an upstream query. The Android proxy dispatches
tailnet names before DoH/plain DNS. Captured failures produce local errors.

## Validation recorded on 2026-11-04

- Go 1.27.3: tagged host tests with the race detector, `go vet`, and `go mod verify` passed.
- Go package cross-compilation passed for Android arm, arm64, 386 and amd64.
- Java bindings generated successfully with the pinned gobind tool.
- The AAR build passed with NDK r29: minSdk23, all four ABIs,
  and at least 16 KB alignment for every ELF load segment were checked.
- Gemini 3.8 Flash (medium) reviewed tracked changes and the new source/build files.
  Confirmed short-read packet loss and Android's unknown-MTU handling were fixed
  and covered by regression tests. Findings were checked against the pinned
  upstream implementation and the actual Go toolchain before applying changes.

The control-plane URL smoke check obtained a real browser login URL without
completing an account login. The URL was not logged. It does not prove
authenticated peer connectivity; see the app-level test guide for recorded
build/emulator checks and the remaining device tests.

## Remaining device validation

- Build/load the AAR on API23 and current Android, verify all ABIs and ELF alignment.
- Verify authentication, assigned source addresses, replies, MTU/PMTUD,
  direct/DERP connectivity, revocation and network switching on a real device.
- Measure screen-off consumption and record upstream diagnostics/network behavior.

Use [the device test guide](../docs/testing-tailscale.md) to validate the packaged
app with a test Tailnet. Do not claim authenticated connectivity based only on host tests.
