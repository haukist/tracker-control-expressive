//go:build tools

package tailscalebridge

// gomobile's generated glue imports this runtime. Keep its version in go.mod
// even when the host tests do not import the generated Android binding.
import _ "golang.org/x/mobile/bind"
