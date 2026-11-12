#ifndef TC_TAILSCALE_ROUTE_H
#define TC_TAILSCALE_ROUTE_H

#include <stdint.h>

// Overlay capture is independent of connection state and WireGuard app routes.
// Unknown destinations in these ranges must be dropped, never sent directly.
static inline int is_tailscale_destination(int version, const void *address) {
    const uint8_t *a = (const uint8_t *) address;
    if (version == 4)
        return a[0] == 100 && (a[1] & 0xc0) == 64;
    return version == 6 && a[0] == 0xfd && a[1] == 0x7a &&
           a[2] == 0x11 && a[3] == 0x5c && a[4] == 0xa1 && a[5] == 0xe0;
}

#endif
