//go:build !android

package tailscalebridge

func installPlatform(Platform) error { return nil }
func clearProtector()                {}
func updateDefaultInterface(string)  {}
