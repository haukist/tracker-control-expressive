#include <assert.h>
#include "../../main/jni/netguard/tailscale_route.h"

int main(void) {
    const uint8_t first[] = {100, 64, 0, 0};
    const uint8_t last[] = {100, 127, 255, 255};
    const uint8_t before[] = {100, 63, 255, 255};
    const uint8_t after[] = {100, 128, 0, 0};
    const uint8_t ula[] = {0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    const uint8_t other[] = {0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    assert(is_tailscale_destination(4, first));
    assert(is_tailscale_destination(4, last));
    assert(!is_tailscale_destination(4, before));
    assert(!is_tailscale_destination(4, after));
    assert(is_tailscale_destination(6, ula));
    assert(!is_tailscale_destination(6, other));
    assert(!is_tailscale_destination(0, ula));
    return 0;
}
