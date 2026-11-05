//go:build android

package tailscalebridge

import (
	"errors"

	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
)

func installPlatform(platform Platform) error {
	if platform == nil {
		return errors.New("Android requires VPN protection and interface information")
	}
	netns.SetAndroidProtectFunc(func(fd int) error {
		if !platform.Protect(fd) {
			return errors.New("VpnService.protect failed")
		}
		return nil
	})
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return parseInterfaces(platform.InterfacesJSON())
	})
	updateDefaultInterface(platform.DefaultInterface())
	return nil
}

func clearProtector() {
	// Fail closed even for a late socket opened by an engine being torn down.
	netns.SetAndroidProtectFunc(func(int) error { return errors.New("Tailscale is stopped") })
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return nil, errors.New("Tailscale is stopped")
	})
}

func updateDefaultInterface(name string) { netmon.UpdateLastKnownDefaultRouteInterface(name) }
