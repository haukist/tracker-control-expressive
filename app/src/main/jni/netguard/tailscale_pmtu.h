#ifndef TC_TAILSCALE_PMTU_H
#define TC_TAILSCALE_PMTU_H

#include <stddef.h>
#include <stdint.h>
#include <string.h>

// The overlay carries at most this much per packet. The VPN interface keeps
// its own, much larger MTU so ordinary traffic is not cut into small packets;
// only overlay destinations learn this limit, through path MTU discovery.
#define TAILSCALE_MTU 1280

// RFC 1812 4.3.2.3: an ICMPv4 error need not exceed 576 bytes.
#define TAILSCALE_ICMP4_MAX 576
#define TAILSCALE_TOO_BIG_MAX TAILSCALE_MTU

_Static_assert(TAILSCALE_ICMP4_MAX <= TAILSCALE_TOO_BIG_MAX,
               "one answer buffer must hold either address family");

// ICMP errors are rate limited (RFC 4443 2.4(f)): one flow that ignores the
// answer must not turn every oversized packet into a TUN write. The kernel
// remembers the path MTU per destination, so a short burst each is enough.
#define TAILSCALE_TOO_BIG_BURST 8
#define TAILSCALE_TOO_BIG_INTERVAL_MS 100

#define TAILSCALE_TOO_BIG_SLOTS 16  // power of two

// Which limiter a destination address uses. A collision only shares a limit.
static inline size_t tailscale_too_big_slot(const uint8_t *address, size_t length) {
    uint32_t hash = 2166136261u;
    for (size_t i = 0; i < length; i++)
        hash = (hash ^ address[i]) * 16777619u;
    return hash & (TAILSCALE_TOO_BIG_SLOTS - 1);
}

struct tailscale_too_big_limit {
    int started;
    uint32_t tokens;
    uint64_t refilled_ms;
};

// Token bucket; now_ms is a monotonic clock. Not thread safe: the caller is
// the single tunnel thread.
static inline int tailscale_too_big_permitted(struct tailscale_too_big_limit *limit,
                                              uint64_t now_ms) {
    if (!limit->started || now_ms < limit->refilled_ms) {
        limit->started = 1;
        limit->tokens = TAILSCALE_TOO_BIG_BURST;
        limit->refilled_ms = now_ms;
    } else {
        uint64_t refill = (now_ms - limit->refilled_ms) / TAILSCALE_TOO_BIG_INTERVAL_MS;
        if (refill >= TAILSCALE_TOO_BIG_BURST - limit->tokens) {
            limit->tokens = TAILSCALE_TOO_BIG_BURST;
            limit->refilled_ms = now_ms;
        } else {
            limit->tokens += (uint32_t) refill;
            limit->refilled_ms += refill * TAILSCALE_TOO_BIG_INTERVAL_MS;
        }
    }
    if (limit->tokens == 0)
        return 0;
    limit->tokens--;
    return 1;
}

static inline uint32_t tailscale_sum16(uint32_t sum, const uint8_t *data, size_t length) {
    while (length > 1) {
        sum += ((uint32_t) data[0] << 8) | data[1];
        data += 2;
        length -= 2;
    }
    if (length)
        sum += (uint32_t) data[0] << 8;
    return sum;
}

static inline void tailscale_put_checksum(uint8_t *field, uint32_t sum) {
    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);
    uint16_t checksum = (uint16_t) ~sum;
    field[0] = (uint8_t) (checksum >> 8);
    field[1] = (uint8_t) checksum;
}

// An ICMPv6 error about packet, sent back to its source as if by the
// destination. RFC 4443 2.4(c): quote as much of the packet as fits the
// minimum IPv6 MTU. Returns the error's length, or 0 if it cannot be built.
static inline size_t tailscale_icmp6_error(const uint8_t *packet, size_t length,
                                           uint8_t type, uint8_t code, uint32_t parameter,
                                           uint8_t *out, size_t out_size) {
    if (length < 40 || (packet[0] >> 4) != 6)
        return 0;
    size_t quoted = length;
    if (quoted > TAILSCALE_TOO_BIG_MAX - 48)
        quoted = TAILSCALE_TOO_BIG_MAX - 48;
    size_t icmp_length = 8 + quoted;
    size_t total = 40 + icmp_length;
    if (out_size < total)
        return 0;

    memset(out, 0, 48);
    out[0] = 0x60;
    out[4] = (uint8_t) (icmp_length >> 8);
    out[5] = (uint8_t) icmp_length;
    out[6] = 58;                        // ICMPv6
    out[7] = 64;                        // hop limit
    memcpy(out + 8, packet + 24, 16);   // from the unreachable destination
    memcpy(out + 24, packet + 8, 16);

    out[40] = type;
    out[41] = code;
    out[44] = (uint8_t) (parameter >> 24);
    out[45] = (uint8_t) (parameter >> 16);
    out[46] = (uint8_t) (parameter >> 8);
    out[47] = (uint8_t) parameter;
    memcpy(out + 48, packet, quoted);

    // Pseudo header: both addresses, upper-layer length, next header.
    uint32_t sum = tailscale_sum16(0, out + 8, 32);
    sum += (uint32_t) icmp_length + 58;
    tailscale_put_checksum(out + 42, tailscale_sum16(sum, out + 40, icmp_length));
    return total;
}

// Whether an IPv6 packet may be answered at all, by an ICMPv6 error or a TCP
// reset. RFC 4443 2.4(e): not a packet to a multicast address, nor one from
// the unspecified or a multicast address. The answer is sent from the
// packet's destination to its source, so neither may be the unspecified
// address either: it would be the answer's source, or name no host at all.
static inline int tailscale_ipv6_answerable(const uint8_t *packet, size_t length) {
    static const uint8_t unspecified[16];
    return length >= 40 && (packet[0] >> 4) == 6 && packet[24] != 0xff &&
           packet[8] != 0xff && memcmp(packet + 8, unspecified, 16) != 0 &&
           memcmp(packet + 24, unspecified, 16) != 0;
}

// Builds "no route to destination" for an IPv6 packet that is dropped because
// ordinary IPv6 is switched off while the overlay keeps an IPv6 address on the
// interface. Without an answer the sender waits for a timeout before it falls
// back to IPv4. Never answers an ICMP error or an unanswerable packet.
static inline size_t tailscale_ipv6_unreachable(const uint8_t *packet, size_t length,
                                                int icmp_error, uint8_t *out, size_t out_size) {
    if (icmp_error || !tailscale_ipv6_answerable(packet, length))
        return 0;
    return tailscale_icmp6_error(packet, length, 1 /* destination unreachable */,
                                 0 /* no route */, 0, out, out_size);
}

// Builds the "packet too big" answer for an oversized overlay packet, as the
// next hop would. The caller has already validated the IP header of packet.
// Returns the answer's length, or 0 when no answer must be sent: a packet that
// fits, a non-first IPv4 fragment, or an ICMP error (never answer an error).
static inline size_t tailscale_too_big(const uint8_t *packet, size_t length,
                                       int icmp_error, uint8_t *out, size_t out_size) {
    if (length <= TAILSCALE_MTU || icmp_error)
        return 0;

    if ((packet[0] >> 4) == 4) {
        if (length < 20 || (packet[6] & 0x1f) != 0 || packet[7] != 0)
            return 0;
        size_t quoted = length;
        if (quoted > TAILSCALE_ICMP4_MAX - 28)
            quoted = TAILSCALE_ICMP4_MAX - 28;
        size_t total = 28 + quoted;
        if (out_size < total)
            return 0;

        memset(out, 0, 28);
        out[0] = 0x45;
        out[2] = (uint8_t) (total >> 8);
        out[3] = (uint8_t) total;
        out[8] = 64;                        // TTL
        out[9] = 1;                         // ICMP
        memcpy(out + 12, packet + 16, 4);   // from the unreachable destination
        memcpy(out + 16, packet + 12, 4);
        tailscale_put_checksum(out + 10, tailscale_sum16(0, out, 20));

        out[20] = 3;                        // destination unreachable
        out[21] = 4;                        // fragmentation needed
        out[26] = (uint8_t) (TAILSCALE_MTU >> 8);
        out[27] = (uint8_t) (TAILSCALE_MTU & 0xff);
        memcpy(out + 28, packet, quoted);
        tailscale_put_checksum(out + 22, tailscale_sum16(0, out + 20, 8 + quoted));
        return total;
    }

    if ((packet[0] >> 4) == 6)
        return tailscale_icmp6_error(packet, length, 2 /* packet too big */, 0,
                                     TAILSCALE_MTU, out, out_size);

    return 0;
}

#endif
