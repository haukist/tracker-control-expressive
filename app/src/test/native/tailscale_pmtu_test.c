#include <assert.h>
#include <stdio.h>
#include "../../main/jni/netguard/tailscale_pmtu.h"

// A correct Internet checksum makes the covered bytes sum to 0xffff.
static int checksum_ok(uint32_t sum, const uint8_t *data, size_t length) {
    sum = tailscale_sum16(sum, data, length);
    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);
    return sum == 0xffff;
}

static void fill_ipv4(uint8_t *packet, size_t length) {
    memset(packet, 0xab, length);
    memset(packet, 0, 20);
    packet[0] = 0x45;
    packet[2] = (uint8_t) (length >> 8);
    packet[3] = (uint8_t) length;
    packet[6] = 0x40; // DF
    packet[9] = 17;
    const uint8_t source[] = {100, 64, 0, 1}, dest[] = {100, 64, 0, 2};
    memcpy(packet + 12, source, 4);
    memcpy(packet + 16, dest, 4);
}

static void fill_ipv6(uint8_t *packet, size_t length) {
    memset(packet, 0xcd, length);
    memset(packet, 0, 40);
    packet[0] = 0x60;
    packet[4] = (uint8_t) ((length - 40) >> 8);
    packet[5] = (uint8_t) (length - 40);
    packet[6] = 17;
    packet[8] = 0xfd;
    packet[23] = 1;
    packet[24] = 0xfd;
    packet[39] = 2;
}

int main(void) {
    static uint8_t packet[4096];
    uint8_t out[TAILSCALE_TOO_BIG_MAX];

    fill_ipv4(packet, 1500);
    assert(tailscale_too_big(packet, TAILSCALE_MTU, 0, out, sizeof(out)) == 0);
    assert(tailscale_too_big(packet, 1500, 1, out, sizeof(out)) == 0);
    assert(tailscale_too_big(packet, 1500, 0, out, 100) == 0);

    size_t length = tailscale_too_big(packet, 1500, 0, out, sizeof(out));
    assert(length == TAILSCALE_ICMP4_MAX);
    assert(out[0] == 0x45 && out[9] == 1);
    assert(((size_t) out[2] << 8 | out[3]) == length);
    assert(memcmp(out + 12, packet + 16, 4) == 0 && memcmp(out + 16, packet + 12, 4) == 0);
    assert(out[20] == 3 && out[21] == 4);
    assert(((out[26] << 8) | out[27]) == TAILSCALE_MTU);
    assert(memcmp(out + 28, packet, length - 28) == 0);
    assert(checksum_ok(0, out, 20));
    assert(checksum_ok(0, out + 20, length - 20));

    // Only the first fragment of an oversized datagram is answered.
    packet[7] = 185;
    assert(tailscale_too_big(packet, 1500, 0, out, sizeof(out)) == 0);

    fill_ipv6(packet, 1500);
    assert(tailscale_too_big(packet, TAILSCALE_MTU, 0, out, sizeof(out)) == 0);
    assert(tailscale_too_big(packet, 1500, 1, out, sizeof(out)) == 0);
    length = tailscale_too_big(packet, 1500, 0, out, sizeof(out));
    assert(length == TAILSCALE_MTU);
    assert(out[0] == 0x60 && out[6] == 58);
    assert(((size_t) out[4] << 8 | out[5]) == length - 40);
    assert(memcmp(out + 8, packet + 24, 16) == 0 && memcmp(out + 24, packet + 8, 16) == 0);
    assert(out[40] == 2 && out[41] == 0);
    assert((((uint32_t) out[44] << 24) | (out[45] << 16) | (out[46] << 8) | out[47]) == TAILSCALE_MTU);
    assert(memcmp(out + 48, packet, length - 48) == 0);
    uint32_t pseudo = tailscale_sum16(0, out + 8, 32) + (uint32_t) (length - 40) + 58;
    assert(checksum_ok(pseudo, out + 40, length - 40));

    // An answer stays capped at its protocol's maximum size.
    fill_ipv6(packet, 1281);
    assert(tailscale_too_big(packet, 1281, 0, out, sizeof(out)) == TAILSCALE_MTU);
    fill_ipv4(packet, 1281);
    assert(tailscale_too_big(packet, 1281, 0, out, sizeof(out)) == TAILSCALE_ICMP4_MAX);

    packet[0] = 0x50;
    assert(tailscale_too_big(packet, 1500, 0, out, sizeof(out)) == 0);

    // Disabled ordinary IPv6: "no route", quoting the packet.
    fill_ipv6(packet, 200);
    length = tailscale_ipv6_unreachable(packet, 200, 0, out, sizeof(out));
    assert(length == 248);
    assert(out[6] == 58 && out[40] == 1 && out[41] == 0);
    assert(out[44] == 0 && out[45] == 0 && out[46] == 0 && out[47] == 0);
    assert(memcmp(out + 8, packet + 24, 16) == 0 && memcmp(out + 24, packet + 8, 16) == 0);
    assert(memcmp(out + 48, packet, 200) == 0);
    pseudo = tailscale_sum16(0, out + 8, 32) + (uint32_t) (length - 40) + 58;
    assert(checksum_ok(pseudo, out + 40, length - 40));
    assert(tailscale_ipv6_unreachable(packet, 200, 1, out, sizeof(out)) == 0);
    assert(tailscale_ipv6_unreachable(packet, 39, 0, out, sizeof(out)) == 0);
    // Never answer multicast (router solicitations, MLD) or an unspecified source.
    packet[24] = 0xff;
    assert(tailscale_ipv6_unreachable(packet, 200, 0, out, sizeof(out)) == 0);
    fill_ipv6(packet, 200);
    packet[8] = 0xff;
    assert(tailscale_ipv6_unreachable(packet, 200, 0, out, sizeof(out)) == 0);
    fill_ipv6(packet, 200);
    memset(packet + 8, 0, 16);
    assert(tailscale_ipv6_unreachable(packet, 200, 0, out, sizeof(out)) == 0);
    fill_ipv4(packet, 200);
    assert(tailscale_ipv6_unreachable(packet, 200, 0, out, sizeof(out)) == 0);

    struct tailscale_too_big_limit limit = {0};
    int permitted = 0;
    for (int i = 0; i < 100; i++)
        permitted += tailscale_too_big_permitted(&limit, 5000);
    assert(permitted == TAILSCALE_TOO_BIG_BURST);
    assert(!tailscale_too_big_permitted(&limit, 5000 + TAILSCALE_TOO_BIG_INTERVAL_MS - 1));
    assert(tailscale_too_big_permitted(&limit, 5000 + TAILSCALE_TOO_BIG_INTERVAL_MS));
    assert(!tailscale_too_big_permitted(&limit, 5000 + TAILSCALE_TOO_BIG_INTERVAL_MS));
    // A long quiet period refills the burst, and no further.
    permitted = 0;
    for (int i = 0; i < 100; i++)
        permitted += tailscale_too_big_permitted(&limit, 3600000);
    assert(permitted == TAILSCALE_TOO_BIG_BURST);
    // A clock that steps backwards starts over instead of stalling.
    assert(tailscale_too_big_permitted(&limit, 10));
    puts("tailscale_pmtu_test: all tests passed");
    return 0;
}
