/* Production-bound routing and policy regressions through handle_ip(). */

#include <arpa/inet.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "netguard.h"
#include "udp_state.h"
#include "tailscale_pmtu.h"

static int failures;
static int unexpected_handler_calls;
static int policy_calls;
static int uid_calls;
static int wireguard_writes;
static int tailscale_writes;
static int tun_writes;
static uint8_t last_tun_write[2048];
static size_t last_tun_write_length;
static int tailscale_enabled;
static int tailscale_block_other_ipv6;
static int rst_writes;
static int blocked_udp_calls;
static int expect_direct;
static int direct_calls;
static int configured_uid = 10042;
static int policy_allowed = 1;
static int blocked_udp;
static int active_udp;
static jint last_policy_uid = -1;
static struct allowed allowed_empty;

FILE *pcap_file;
int loglevel = ANDROID_LOG_WARN;
_Atomic int wg_required = 1;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                     \
            failures++;                                                     \
        }                                                                   \
    } while (0)

static struct context context;
static struct arguments args;

// Replace only the shared-library boundary; exercise the real C policy cache.
static int bridge_route_calls;
static jint bridge_override = -1;
static int bridge_default = 1;

static int bridge_abi(void) { return 2; }
static void bridge_set(const jint *uids, int count, int default_tunnel) {
    CHECK(count <= 1, "fixture supports one routing override");
    bridge_override = count > 0 ? uids[0] : -1;
    bridge_default = default_tunnel;
}
static void bridge_clear(void) {
    bridge_override = -1;
    bridge_default = 1;
}
static int bridge_is_tunnel(jint uid) {
    bridge_route_calls++;
    return uid == bridge_override ? !bridge_default : bridge_default;
}
static int bridge_wants(int local, int dns, int tunnel, int direct_dns) {
    return local && !dns ? 0 : dns && !direct_dns ? 1 : tunnel;
}
// A split tunnel whose AllowedIPs are only 192.168.178.0/24. The prefix
// matching itself is Rust's, and tested there.
static int bridge_split;
static int bridge_dest_tunnelled(int version, const void *daddr) {
    const uint8_t *b = daddr;
    return !bridge_split || (version == 4 && b[0] == 192 && b[1] == 168 && b[2] == 178);
}
void *__wrap_dlopen(const char *name, int flags) {
    (void) flags;
    CHECK(strcmp(name, "libwgbridge.so") == 0, "only the policy bridge is loaded");
    return (void *) (uintptr_t) 1;
}
void *__wrap_dlsym(void *handle, const char *name) {
    (void) handle;
    if (strcmp(name, "tc_policy_abi_version") == 0) return (void *) bridge_abi;
    if (strcmp(name, "tc_policy_set_route_uids") == 0) return (void *) bridge_set;
    if (strcmp(name, "tc_policy_clear_route_uids") == 0) return (void *) bridge_clear;
    if (strcmp(name, "tc_policy_is_tunnel_uid") == 0) return (void *) bridge_is_tunnel;
    if (strcmp(name, "tc_policy_wants_tunnel") == 0) return (void *) bridge_wants;
    if (strcmp(name, "tc_policy_dest_tunnelled") == 0) return (void *) bridge_dest_tunnelled;
    CHECK(0, "policy bridge requests a recognised symbol");
    return NULL;
}

void log_android(int priority, const char *format, ...) {
    (void) priority;
    (void) format;
}

void *ng_malloc(size_t size, const char *tag) {
    (void) tag;
    return calloc(1, size);
}

void ng_free(void *pointer, const char *file, int line) {
    (void) file;
    (void) line;
    free(pointer);
}

uint16_t calc_checksum(uint16_t start, const uint8_t *buffer, size_t length) {
    (void) start;
    (void) buffer;
    (void) length;
    return 1;
}

void hex2bytes(const char *hex, uint8_t *buffer) {
    (void) hex;
    (void) buffer;
    unexpected_handler_calls++;
}

jint get_uid_q(const struct arguments *unused, jint version, jint protocol,
              const char *source, jint sport, const char *dest, jint dport) {
    (void) unused;
    (void) version;
    (void) protocol;
    (void) source;
    (void) sport;
    (void) dest;
    (void) dport;
    uid_calls++;
    return configured_uid;
}

jobject create_packet(const struct arguments *unused, jint version, jint protocol,
                      const char *flags, const char *source, jint sport,
                      const char *dest, jint dport, const char *data, jint uid,
                      jboolean allowed) {
    (void) unused;
    (void) version;
    (void) protocol;
    (void) flags;
    (void) source;
    (void) sport;
    (void) dest;
    (void) dport;
    (void) data;
    (void) allowed;
    last_policy_uid = uid;
    return (jobject) (uintptr_t) 1;
}

struct allowed *is_address_allowed(const struct arguments *unused,
                                   jobject objPacket) {
    (void) unused;
    (void) objPacket;
    policy_calls++;
    return policy_allowed ? &allowed_empty : NULL;
}

// The only direct TUN write in ip.c is the overlay "packet too big" answer.
ssize_t __wrap_write(int fd, const void *data, size_t length) {
    (void) fd;
    tun_writes++;
    last_tun_write_length = length < sizeof(last_tun_write) ? length : sizeof(last_tun_write);
    memcpy(last_tun_write, data, last_tun_write_length);
    return (ssize_t) length;
}

int tailscale_is_enabled(void) { return tailscale_enabled; }
int tailscale_blocks_other_ipv6(void) { return tailscale_block_other_ipv6; }
void write_tailscale_packet(const void *packet, size_t length) { tailscale_writes++; }

int write_wireguard_packet(const void *packet, size_t length,
                           ssize_t *written, int *write_errno) {
    (void) packet;
    // Tailscale scenarios have no WireGuard socketpair attached.
    if (!atomic_load(&wg_required)) return 0;
    wireguard_writes++;
    if (written != NULL)
        *written = (ssize_t) length;
    if (write_errno != NULL)
        *write_errno = 0;
    return 1;
}

ssize_t write_tcp(const struct arguments *unused, const struct tcp_session *cur,
                  const uint8_t *data, size_t datalen, int syn, int ack,
                  int fin, int rst) {
    (void) unused;
    (void) cur;
    (void) data;
    (void) datalen;
    (void) syn;
    (void) ack;
    (void) fin;
    if (!rst)
        unexpected_handler_calls++;
    else
        rst_writes++;
    return 0;
}

void write_rst(const struct arguments *unused, struct tcp_session *cur) {
    (void) unused;
    (void) cur;
    unexpected_handler_calls++;
}

int get_udp_session_state(const struct arguments *unused, const uint8_t *packet,
                          const uint8_t *payload) {
    (void) unused;
    (void) packet;
    (void) payload;
    return blocked_udp ? UDP_BLOCKED : active_udp ? UDP_ACTIVE : -1;
}

void block_udp(const struct arguments *unused, const uint8_t *packet,
               size_t length, const uint8_t *payload, int uid) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    blocked_udp_calls++;
    blocked_udp = 1;
}

jboolean handle_icmp(const struct arguments *unused, const uint8_t *packet,
                     size_t length, const uint8_t *payload, int uid,
                     const int epoll_fd) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    (void) epoll_fd;
    unexpected_handler_calls++;
    return 0;
}

jboolean handle_udp(const struct arguments *unused, const uint8_t *packet,
                    size_t length, const uint8_t *payload, int uid,
                    struct allowed *redirect, const int epoll_fd) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    (void) redirect;
    (void) epoll_fd;
    if (expect_direct)
        direct_calls++;
    else
        unexpected_handler_calls++;
    return 0;
}

jboolean handle_tcp(const struct arguments *unused, const uint8_t *packet,
                    size_t length, const uint8_t *payload, int uid, int allowed,
                    struct allowed *redirect, const int epoll_fd) {
    (void) unused;
    (void) packet;
    (void) length;
    (void) payload;
    (void) uid;
    (void) allowed;
    (void) redirect;
    (void) epoll_fd;
    if (expect_direct)
        direct_calls++;
    else
        unexpected_handler_calls++;
    return 0;
}

static void reset_fakes(void) {
    policy_calls = 0;
    uid_calls = 0;
    wireguard_writes = 0;
    tailscale_writes = 0;
    tun_writes = 0;
    last_tun_write_length = 0;
    tailscale_enabled = 0;
    tailscale_block_other_ipv6 = 0;
    rst_writes = 0;
    blocked_udp_calls = 0;
    expect_direct = 0;
    direct_calls = 0;
    bridge_split = 0;
    unexpected_handler_calls = 0;
    last_policy_uid = -1;
    blocked_udp = 0;
    active_udp = 0;
    configured_uid = 10042;
    policy_allowed = 1;
    route_flow_invalidate();
    tcp_owner_reset();
}

static size_t make_tcp(uint8_t *packet, uint16_t source_port, int syn, int ack,
                       int psh, const uint8_t *data, size_t data_length) {
    const size_t length = sizeof(struct iphdr) + sizeof(struct tcphdr) + data_length;
    memset(packet, 0, length);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 5;
    ip4->tot_len = htons((uint16_t) length);
    ip4->protocol = IPPROTO_TCP;
    inet_pton(AF_INET, "192.0.2.10", &ip4->saddr);
    inet_pton(AF_INET, "198.51.100.10", &ip4->daddr);

    struct tcphdr *tcp = (struct tcphdr *) (packet + sizeof(struct iphdr));
    tcp->source = htons(source_port);
    tcp->dest = htons(443);
    tcp->doff = 5;
    tcp->syn = syn;
    tcp->ack = ack;
    tcp->psh = psh;
    tcp->seq = htonl(1000);
    tcp->ack_seq = htonl(2000);
    if (data_length != 0)
        memcpy(packet + sizeof(struct iphdr) + sizeof(struct tcphdr), data, data_length);
    return length;
}

static size_t make_tcp6(uint8_t *packet, uint16_t source_port, int syn, int ack,
                        int psh, const uint8_t *data, size_t data_length) {
    const size_t length = sizeof(struct ip6_hdr) + sizeof(struct tcphdr) + data_length;
    memset(packet, 0, length);
    struct ip6_hdr *ip6 = (struct ip6_hdr *) packet;
    ip6->ip6_vfc = 0x60;
    ip6->ip6_plen = htons((uint16_t) (sizeof(struct tcphdr) + data_length));
    ip6->ip6_nxt = IPPROTO_TCP;
    inet_pton(AF_INET6, "2001:db8::10", &ip6->ip6_src);
    inet_pton(AF_INET6, "2001:db8::20", &ip6->ip6_dst);

    struct tcphdr *tcp = (struct tcphdr *) (packet + sizeof(struct ip6_hdr));
    tcp->source = htons(source_port);
    tcp->dest = htons(443);
    tcp->doff = 5;
    tcp->syn = syn;
    tcp->ack = ack;
    tcp->psh = psh;
    tcp->seq = htonl(1000);
    tcp->ack_seq = htonl(2000);
    if (data_length != 0)
        memcpy(packet + sizeof(struct ip6_hdr) + sizeof(struct tcphdr), data, data_length);
    return length;
}

static size_t make_udp(uint8_t *packet, uint16_t source_port,
                       const uint8_t *data, size_t data_length) {
    const size_t length = sizeof(struct iphdr) + sizeof(struct udphdr) + data_length;
    memset(packet, 0, length);
    struct iphdr *ip4 = (struct iphdr *) packet;
    ip4->version = 4;
    ip4->ihl = 5;
    ip4->tot_len = htons((uint16_t) length);
    ip4->protocol = IPPROTO_UDP;
    inet_pton(AF_INET, "192.0.2.10", &ip4->saddr);
    inet_pton(AF_INET, "198.51.100.10", &ip4->daddr);

    struct udphdr *udp = (struct udphdr *) (packet + sizeof(struct iphdr));
    udp->source = htons(source_port);
    udp->dest = htons(443);
    udp->len = htons((uint16_t) (sizeof(struct udphdr) + data_length));
    if (data_length != 0)
        memcpy(packet + sizeof(struct iphdr) + sizeof(struct udphdr), data, data_length);
    return length;
}

static void run(const uint8_t *packet, size_t length) {
    int unexpected_before = unexpected_handler_calls;
    handle_ip(&args, packet, length, -1, 0, 1000);
    CHECK(unexpected_handler_calls == unexpected_before,
          "packet does not reach an unexpected native handler");
}

static void test_tcp_revalidation_and_fresh_syn(void) {
    _Alignas(struct iphdr) uint8_t syn_packet[256];
    _Alignas(struct iphdr) uint8_t packet[256];
    const size_t syn_length = make_tcp(syn_packet, 42000, 1, 0, 0, NULL, 0);

    reset_fakes();
    run(syn_packet, syn_length);
    CHECK(policy_calls == 1 && uid_calls == 1 && wireguard_writes == 1,
          "TCP SYN uses the real allow gate and reaches WireGuard once");
    CHECK(last_policy_uid == configured_uid, "TCP policy receives the resolved UID");

    const size_t ack_length = make_tcp(packet, 42000, 0, 1, 0, NULL, 0);
    run(packet, ack_length);
    run(packet, ack_length);
    CHECK(policy_calls == 1 && uid_calls == 1 && wireguard_writes == 3,
          "established allowed TCP packets reuse the real cached verdict");

    route_flow_invalidate();
    policy_allowed = 0;
    configured_uid = 0;
    const size_t data_length = make_tcp(packet, 42000, 0, 1, 1,
                                        (const uint8_t *) "data", 4);
    ((struct tcphdr *) (packet + sizeof(struct iphdr)))->fin = 1;
    run(packet, data_length);
    CHECK(policy_calls == 2 && uid_calls == 1 && wireguard_writes == 3 && rst_writes == 1,
          "invalidated established TCP policy blocks before WireGuard and resets once");
    CHECK(last_policy_uid == 10042,
          "established TCP ACK/PSH/FIN revalidation uses the retained owner when Android reports zero");
    run(packet, data_length);
    CHECK(policy_calls == 2 && wireguard_writes == 3 && rst_writes == 1,
          "repeated blocked TCP packets skip Java, WireGuard, and duplicate resets");

    route_flow_invalidate();
    policy_allowed = 1;
    configured_uid = 10042;
    run(packet, data_length);
    CHECK(policy_calls == 3 && wireguard_writes == 4,
          "a later invalidation rechecks policy and forwards the established flow");

    policy_allowed = 0;
    make_tcp(syn_packet, 42000, 1, 0, 0, NULL, 0);
    run(syn_packet, syn_length);
    CHECK(policy_calls == 4 && wireguard_writes == 4,
          "a reused tuple SYN clears the old verdict and rechecks policy");
    policy_allowed = 1;
}

static void test_tcp_unknown_owner_fails_closed_then_recovers(void) {
    _Alignas(struct iphdr) uint8_t syn_packet[256];
    _Alignas(struct iphdr) uint8_t packet[256];
    const size_t syn_length = make_tcp(syn_packet, 42001, 1, 0, 0, NULL, 0);
    const size_t ack_length = make_tcp(packet, 42001, 0, 1, 1,
                                       (const uint8_t *) "data", 4);

    reset_fakes();
    run(syn_packet, syn_length);
    CHECK(wireguard_writes == 1 && policy_calls == 1,
          "unknown-owner regression starts from an allowed tunnelled SYN");

    route_flow_invalidate();
    tcp_owner_forget(4, &((struct iphdr *) syn_packet)->saddr, 42001,
                     &((struct iphdr *) syn_packet)->daddr, 443);
    configured_uid = 0;
    run(packet, ack_length);
    int verdict = ROUTE_FLOW_VERDICT_UNKNOWN;
    int tunnel = 0;
    int uid_known = 1;
    CHECK(wireguard_writes == 1 && policy_calls == 1,
          "unknown established owner fails closed without Java policy or WireGuard");
    CHECK(route_flow_lookup_verdict(4, IPPROTO_TCP,
                                    &((struct iphdr *) packet)->saddr, 42001,
                                    &((struct iphdr *) packet)->daddr, 443,
                                    &verdict) && verdict == ROUTE_FLOW_VERDICT_UNKNOWN,
          "unknown owner does not pin an allowed TCP verdict");
    CHECK(route_flow_lookup(4, IPPROTO_TCP,
                            &((struct iphdr *) packet)->saddr, 42001,
                            &((struct iphdr *) packet)->daddr, 443,
                            &tunnel, &uid_known) && tunnel == 1 && !uid_known,
          "unknown owner remains unstable in the real route cache");

    configured_uid = 10042;
    run(packet, ack_length);
    CHECK(policy_calls == 2 && wireguard_writes == 2,
          "resolving the owner on the next packet permits a fresh policy decision");
}

static void test_ipv6_tcp_owner_revalidation(void) {
    _Alignas(struct ip6_hdr) uint8_t syn_packet[256];
    _Alignas(struct ip6_hdr) uint8_t packet[256];
    const size_t syn_length = make_tcp6(syn_packet, 42002, 1, 0, 0, NULL, 0);
    const size_t ack_length = make_tcp6(packet, 42002, 0, 1, 1,
                                       (const uint8_t *) "v6", 2);

    reset_fakes();
    run(syn_packet, syn_length);
    CHECK(policy_calls == 1 && uid_calls == 1 && wireguard_writes == 1,
          "IPv6 TCP SYN enters the same owner and WireGuard path");

    route_flow_invalidate();
    configured_uid = 0;
    run(packet, ack_length);
    CHECK(policy_calls == 2 && last_policy_uid == 10042 && wireguard_writes == 2,
          "IPv6 established TCP revalidation uses the retained owner");
}

static void test_root_and_fresh_syn_owner_rules(void) {
    _Alignas(struct iphdr) uint8_t syn_packet[256];
    _Alignas(struct iphdr) uint8_t packet[256];

    reset_fakes();
    configured_uid = 0;
    const size_t root_syn_length = make_tcp(syn_packet, 42003, 1, 0, 0, NULL, 0);
    const size_t root_ack_length = make_tcp(packet, 42003, 0, 1, 1,
                                            (const uint8_t *) "root", 4);
    run(syn_packet, root_syn_length);
    route_flow_invalidate();
    configured_uid = -1;
    run(packet, root_ack_length);
    CHECK(policy_calls == 2 && uid_calls == 1 && last_policy_uid == 0,
          "root-owned SYN remains valid across policy invalidation without a UID lookup");

    reset_fakes();
    configured_uid = 10042;
    const size_t syn_length = make_tcp(syn_packet, 42004, 1, 0, 0, NULL, 0);
    const size_t ack_length = make_tcp(packet, 42004, 0, 1, 1,
                                       (const uint8_t *) "new", 3);
    run(syn_packet, syn_length);
    configured_uid = 10043;
    run(syn_packet, syn_length);
    CHECK(policy_calls == 2 && last_policy_uid == 10043,
          "fresh SYN on a reused tuple replaces the retained owner");
    route_flow_invalidate();
    configured_uid = -1;
    run(packet, ack_length);
    CHECK(policy_calls == 3 && last_policy_uid == 10043,
          "reloaded reused tuple keeps the fresh SYN owner");

    reset_fakes();
    configured_uid = 10042;
    const size_t stale_syn_length = make_tcp(syn_packet, 42005, 1, 0, 0, NULL, 0);
    const size_t stale_ack_length = make_tcp(packet, 42005, 0, 1, 1,
                                             (const uint8_t *) "stale", 5);
    run(syn_packet, stale_syn_length);
    configured_uid = -1;
    run(syn_packet, stale_syn_length);
    CHECK(policy_calls == 2 && last_policy_uid == -1,
          "unresolved fresh SYN does not inherit the previous tuple owner");
    route_flow_invalidate();
    configured_uid = 0;
    int writes_before = wireguard_writes;
    run(packet, stale_ack_length);
    CHECK(policy_calls == 2 && wireguard_writes == writes_before,
          "uncached established zero owner fails closed without policy or WireGuard");
}

static void test_udp_policy_invalidation_and_negative_state(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    const uint8_t opaque[] = {0x17, 0x03, 0x03, 0x00, 0x01, 0x7f};
    const size_t length = make_udp(packet, 41000, opaque, sizeof(opaque));

    reset_fakes();
    run(packet, length);
    run(packet, length);
    CHECK(policy_calls == 1 && wireguard_writes == 2,
          "allowed UDP/443 tuple caches the real route/policy result");

    route_flow_invalidate();
    policy_allowed = 0;
    run(packet, length);
    CHECK(policy_calls == 2 && wireguard_writes == 2 && blocked_udp_calls == 1,
          "invalidated UDP policy blocks before WireGuard and records negative state");
    run(packet, length);
    CHECK(policy_calls == 2 && wireguard_writes == 2 && blocked_udp_calls == 1,
          "repeated blocked UDP packets use negative state without Java or WireGuard");
}

static void test_selected_route_fast_path(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    reset_fakes();
    jint override = 10999;
    set_route_uids(&override, 1, 1, 0);
    bridge_route_calls = 0;
    size_t length = make_tcp(packet, 42006, 1, 0, 0, NULL, 0);
    run(packet, length);
    CHECK(bridge_route_calls == 1, "SYN resolves the selected-app route once");

    length = make_tcp(packet, 42006, 0, 1, 1, (const uint8_t *) "data", 4);
    run(packet, length);
    run(packet, length);
    CHECK(uid_calls == 1 && bridge_route_calls == 1 && policy_calls == 1,
          "cached TCP packets avoid both Android ownership and bridge route lookups");

    // Change this app's route and block verdict together. Retaining its owner
    // must not preserve either the old route or the old allow verdict.
    override = 10042;
    set_route_uids(&override, 1, 1, 0);
    configured_uid = 0;
    policy_allowed = 0;
    run(packet, length);
    CHECK(last_policy_uid == 10042 && uid_calls == 1 && bridge_route_calls > 1,
          "policy refresh re-evaluates the retained owner's new route");
    CHECK(policy_calls == 2 && wireguard_writes == 3 && rst_writes == 1,
          "new deny verdict stops an established connection before forwarding");
    int tunnel = 1, known = 0;
    struct iphdr *ip = (struct iphdr *) packet;
    CHECK(route_flow_lookup(4, IPPROTO_TCP, &ip->saddr, 42006, &ip->daddr, 443,
                            &tunnel, &known) && !tunnel && known,
          "new direct route replaces the old tunnel route after refresh");
    int calls_before = bridge_route_calls;
    run(packet, length);
    CHECK(bridge_route_calls == calls_before && policy_calls == 2 && uid_calls == 1,
          "cached denied packets also avoid repeated route and owner lookups");
    clear_route_uids();
}

static void set_destination(uint8_t *packet, const char *destination) {
    inet_pton(AF_INET, destination, &((struct iphdr *) packet)->daddr);
}

static void set_destination_port(uint8_t *packet, uint16_t port) {
    ((struct udphdr *) (packet + sizeof(struct iphdr)))->dest = htons(port);
}

// A profile that routes only the home LAN: everything else takes the filtered
// direct path instead of being handed to WireGuard, which would drop it.
static void test_split_tunnel_routes_outside_allowed_ips_direct(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    const uint8_t opaque[] = {0x17, 0x03, 0x03, 0x00, 0x01, 0x7f};

    reset_fakes();
    bridge_split = 1;
    expect_direct = 1;

    size_t length = make_udp(packet, 41100, opaque, sizeof(opaque));
    set_destination(packet, "1.1.1.1");
    run(packet, length);
    CHECK(wireguard_writes == 0 && direct_calls == 1 && policy_calls == 1,
          "UDP outside AllowedIPs is filtered and leaves directly");
    run(packet, length);
    CHECK(wireguard_writes == 0 && direct_calls == 2 && policy_calls == 2,
          "a cached route never reuses a tunnel verdict for a direct flow");

    length = make_udp(packet, 41101, opaque, sizeof(opaque));
    set_destination(packet, "192.168.178.1");
    run(packet, length);
    CHECK(wireguard_writes == 1 && direct_calls == 2,
          "UDP inside AllowedIPs still takes the tunnel");

    length = make_udp(packet, 41102, opaque, sizeof(opaque));
    set_destination(packet, "192.168.178.99");
    set_destination_port(packet, 53);
    run(packet, length);
    CHECK(wireguard_writes == 2 && direct_calls == 2,
          "DNS to a resolver inside AllowedIPs takes the tunnel");

    length = make_udp(packet, 41103, opaque, sizeof(opaque));
    set_destination(packet, "9.9.9.9");
    set_destination_port(packet, 53);
    run(packet, length);
    CHECK(wireguard_writes == 2 && direct_calls == 3,
          "DNS to a resolver outside AllowedIPs leaves directly");

    length = make_tcp(packet, 42100, 1, 0, 0, NULL, 0);
    run(packet, length);
    CHECK(wireguard_writes == 2 && direct_calls == 4,
          "a TCP SYN outside AllowedIPs leaves directly");

    length = make_tcp(packet, 42101, 1, 0, 0, NULL, 0);
    set_destination(packet, "192.168.178.1");
    run(packet, length);
    CHECK(wireguard_writes == 3 && direct_calls == 4,
          "a TCP SYN inside AllowedIPs takes the tunnel");

    length = make_tcp6(packet, 42102, 1, 0, 0, NULL, 0);
    run(packet, length);
    CHECK(wireguard_writes == 3 && direct_calls == 5,
          "IPv6 outside AllowedIPs leaves directly");

    // Back to a full tunnel: nothing may leave directly any more.
    bridge_split = 0;
    expect_direct = 0;
    length = make_udp(packet, 41104, opaque, sizeof(opaque));
    set_destination(packet, "1.1.1.1");
    run(packet, length);
    CHECK(wireguard_writes == 4 && direct_calls == 5,
          "full tunnel hands every destination to WireGuard again");
}

static void test_tailscale_tcp_revalidation(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;
    size_t length = make_tcp(packet, 43000, 1, 0, 0, NULL, 0);
    set_destination(packet, "100.64.1.2");
    run(packet, length);
    CHECK(policy_calls == 1 && tailscale_writes == 1 && wireguard_writes == 0,
          "Tailscale SYN passes policy independently of WG routing");
    length = make_tcp(packet, 43000, 0, 1, 0, NULL, 0);
    set_destination(packet, "100.64.1.2");
    run(packet, length);
    CHECK(policy_calls == 1 && tailscale_writes == 2, "Tailscale caches allowed established TCP");
    route_flow_invalidate();
    policy_allowed = 0;
    configured_uid = 0;
    run(packet, length);
    CHECK(policy_calls == 2 && tailscale_writes == 2 && rst_writes == 1,
          "Tailscale established TCP rechecks policy after reload and drops blocked flow");
    CHECK(last_policy_uid == 10042, "Tailscale retains the real socket owner");
    run(packet, length);
    CHECK(policy_calls == 2 && tailscale_writes == 2 && rst_writes == 1,
          "Tailscale blocked cache neither forwards nor repeats resets");
    atomic_store(&wg_required, 1);
}

static void test_tailscale_udp_verdict_cache(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    const uint8_t opaque[] = {0x17, 0x03, 0x03, 0x00, 0x01, 0x7f};
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;
    size_t length = make_udp(packet, 43010, opaque, sizeof(opaque));
    set_destination(packet, "100.64.1.2");
    run(packet, length);
    run(packet, length);
    run(packet, length);
    CHECK(policy_calls == 1 && uid_calls == 1 && tailscale_writes == 3,
          "allowed Tailnet UDP resolves its owner and policy once per flow");

    route_flow_invalidate();
    policy_allowed = 0;
    run(packet, length);
    CHECK(policy_calls == 2 && tailscale_writes == 3 && blocked_udp_calls == 1,
          "a policy reload rechecks Tailnet UDP and blocks before the bridge");
    run(packet, length);
    CHECK(policy_calls == 2 && tailscale_writes == 3 && blocked_udp_calls == 1,
          "blocked Tailnet UDP uses negative state without Java or the bridge");

    // An unresolved owner must stay uncached so ownership is retried.
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;
    configured_uid = -1;
    length = make_udp(packet, 43011, opaque, sizeof(opaque));
    set_destination(packet, "100.64.1.2");
    run(packet, length);
    run(packet, length);
    CHECK(policy_calls == 2 && uid_calls == 2 && tailscale_writes == 2,
          "Tailnet UDP with an unknown owner is never pinned as allowed");

    // The cached verdict belongs to one five-tuple only.
    configured_uid = 10042;
    run(packet, length);
    run(packet, length);
    CHECK(policy_calls == 3 && uid_calls == 3 && tailscale_writes == 4,
          "Tailnet UDP is cached once its owner resolves");
    length = make_udp(packet, 43012, opaque, sizeof(opaque));
    set_destination(packet, "100.64.1.2");
    policy_allowed = 0;
    run(packet, length);
    CHECK(policy_calls == 4 && tailscale_writes == 4 && blocked_udp_calls == 1,
          "a different Tailnet UDP tuple does not inherit an allowed verdict");
    atomic_store(&wg_required, 1);
}

static void test_tailscale_oversized_packet_gets_too_big(void) {
    _Alignas(struct iphdr) static uint8_t packet[2048];
    static const uint8_t payload[1400];
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;

    size_t length = make_udp(packet, 43020, payload, 1252);
    set_destination(packet, "100.64.1.2");
    CHECK(length == 1280, "fixture builds a packet of exactly the overlay MTU");
    run(packet, length);
    CHECK(tailscale_writes == 1 && tun_writes == 0,
          "a packet of exactly the overlay MTU is forwarded");

    length = make_udp(packet, 43020, payload, sizeof(payload));
    set_destination(packet, "100.64.1.2");
    run(packet, length);
    CHECK(tailscale_writes == 1 && tun_writes == 1,
          "an oversized Tailnet packet is answered instead of forwarded");
    CHECK(last_tun_write_length == 576 && last_tun_write[9] == IPPROTO_ICMP &&
          last_tun_write[20] == 3 && last_tun_write[21] == 4 &&
          ((last_tun_write[26] << 8) | last_tun_write[27]) == 1280,
          "the answer is ICMP fragmentation needed with the overlay MTU");
    CHECK(memcmp(last_tun_write + 16, packet + 12, 4) == 0,
          "the answer returns to the sending address");

    // A sender that ignores the answer cannot turn each packet into a write.
    for (int i = 0; i < 100; i++)
        run(packet, length);
    CHECK(tailscale_writes == 1 && tun_writes <= TAILSCALE_TOO_BIG_BURST + 1,
          "repeated oversized Tailnet packets are dropped with rate-limited answers");
    int answers = tun_writes;

    // Another peer still learns its path MTU while the first one is limited.
    length = make_udp(packet, 43020, payload, sizeof(payload));
    set_destination(packet, "100.64.1.3");
    CHECK(tailscale_too_big_slot((const uint8_t *) "d@", 4) !=
          tailscale_too_big_slot((const uint8_t *) "d@", 4),
          "fixture peers use separate rate limits");
    run(packet, length);
    CHECK(tun_writes == answers + 1 && memcmp(last_tun_write + 12, packet + 16, 4) == 0,
          "a rate-limited peer does not suppress the answer for another peer");
    answers = tun_writes;

    static uint8_t packet6[2048];
    length = make_tcp6(packet6, 43023, 0, 1, 1, payload, sizeof(payload));
    inet_pton(AF_INET6, "fd7a:115c:a1e0::2", &((struct ip6_hdr *) packet6)->ip6_dst);
    configured_uid = -1; // An established flow without owner fails closed first.
    run(packet6, length);
    CHECK(tun_writes == answers, "an unattributed oversized IPv6 packet gets no answer");
    configured_uid = 10042;
    run(packet6, length);
    CHECK(tailscale_writes == 1 && tun_writes == answers + 1 &&
          last_tun_write_length == 1280 && last_tun_write[6] == IPPROTO_ICMPV6 &&
          last_tun_write[40] == 2 && ((last_tun_write[46] << 8) | last_tun_write[47]) == 1280,
          "an oversized Tailnet IPv6 packet is answered with packet too big");
    answers = tun_writes;

    // The policy still decides first: a blocked flow gets no answer at all.
    policy_allowed = 0;
    length = make_udp(packet, 43021, payload, sizeof(payload));
    set_destination(packet, "100.64.1.2");
    run(packet, length);
    CHECK(tailscale_writes == 1 && tun_writes == answers && blocked_udp_calls == 1,
          "a blocked oversized Tailnet packet is dropped silently");

    // Ordinary traffic keeps the large interface MTU.
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;
    expect_direct = 1;
    length = make_udp(packet, 43022, payload, sizeof(payload));
    run(packet, length);
    CHECK(direct_calls == 1 && tun_writes == 0,
          "oversized ordinary traffic is not limited by the overlay MTU");
    atomic_store(&wg_required, 1);
}

static void test_tailscale_dns_policy_and_native_sessions(void) {
    _Alignas(struct iphdr) uint8_t packet[256];
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;
    policy_allowed = 0;
    size_t length = make_tcp(packet, 43002, 1, 0, 0, NULL, 0);
    set_destination(packet, "100.64.1.2");
    struct tcphdr *tcp = (struct tcphdr *) (packet + sizeof(struct iphdr));
    tcp->dest = htons(53);
    run(packet, length);
    tcp->syn = 0;
    tcp->ack = 1;
    run(packet, length);
    CHECK(policy_calls == 1 && tailscale_writes == 0 && direct_calls == 0,
          "blocked Tailnet TCP DNS never forwards subsequent non-SYN packets");

    // A redirected TCP DNS flow already owns a native proxy session. Its ACK
    // must go to that session even though no new redirect is returned here.
    struct iphdr *ip = (struct iphdr *) packet;
    struct ng_session session = {0};
    session.protocol = IPPROTO_TCP;
    session.tcp.version = 4;
    session.tcp.source = tcp->source;
    session.tcp.dest = tcp->dest;
    session.tcp.saddr.ip4 = ip->saddr;
    session.tcp.daddr.ip4 = ip->daddr;
    context.ng_session = &session;
    route_flow_invalidate();
    policy_allowed = 1;
    expect_direct = 1;
    run(packet, length);
    CHECK(direct_calls == 1 && tailscale_writes == 0,
          "existing Tailnet TCP DNS retains its native proxy session");
    context.ng_session = NULL;

    length = make_udp(packet, 43003, NULL, 0);
    set_destination(packet, "100.64.1.2");
    ((struct udphdr *) (packet + sizeof(struct iphdr)))->dest = htons(53);
    active_udp = 1;
    run(packet, length);
    CHECK(direct_calls == 2 && tailscale_writes == 0,
          "existing Tailnet UDP DNS retains its native proxy session");
    atomic_store(&wg_required, 1);
}

static void test_tailscale_ipv6_preserves_ipv6_disabled_setting(void) {
    _Alignas(struct ip6_hdr) uint8_t packet[256];
    reset_fakes();
    atomic_store(&wg_required, 0);
    tailscale_enabled = 1;
    tailscale_block_other_ipv6 = 1;
    size_t length = make_tcp6(packet, 43001, 1, 0, 0, NULL, 0);
    run(packet, length);
    CHECK(policy_calls == 0 && tailscale_writes == 0 && unexpected_handler_calls == 0,
          "ordinary IPv6 stays blocked while Tailnet IPv6 is enabled");
    CHECK(rst_writes == 1 && tun_writes == 0,
          "a blocked IPv6 TCP attempt is reset so the app falls back at once");
    ((struct tcphdr *) (packet + sizeof(struct ip6_hdr)))->rst = 1;
    run(packet, length);
    CHECK(rst_writes == 1, "a reset is never answered with a reset");
    ((struct tcphdr *) (packet + sizeof(struct ip6_hdr)))->rst = 0;

    // A reset is never sent from a multicast address or to no address.
    struct ip6_hdr *ip6 = (struct ip6_hdr *) packet;
    struct in6_addr tcp_destination = ip6->ip6_dst, tcp_source = ip6->ip6_src;
    inet_pton(AF_INET6, "ff02::1", &ip6->ip6_dst);
    run(packet, length);
    ip6->ip6_dst = tcp_destination;
    memset(&ip6->ip6_src, 0, sizeof(ip6->ip6_src));
    run(packet, length);
    ip6->ip6_src = tcp_source;
    CHECK(rst_writes == 1, "blocked IPv6 TCP to multicast or from :: is dropped without a reset");

    // A flood of attempts to one destination is not answered one for one.
    for (int i = 0; i < 100; i++)
        run(packet, length);
    // The limiter runs on the real clock: allow for refills if this loop is
    // preempted, but 100 attempts must still be far from 100 resets.
    CHECK(rst_writes > 1 && rst_writes < 50,
          "resets for blocked IPv6 TCP are rate limited per destination");
    int resets = rst_writes;

    // Anything else is answered with ICMPv6 "no route", except multicast.
    ip6->ip6_nxt = IPPROTO_UDP;
    struct udphdr *udp = (struct udphdr *) (packet + sizeof(struct ip6_hdr));
    memset(udp, 0, sizeof(*udp));
    udp->source = htons(43030);
    udp->dest = htons(443);
    udp->len = htons(sizeof(struct udphdr));
    ip6->ip6_plen = htons(sizeof(struct udphdr));
    size_t udp_length = sizeof(struct ip6_hdr) + sizeof(struct udphdr);
    run(packet, udp_length);
    CHECK(policy_calls == 0 && tailscale_writes == 0 && tun_writes == 1 &&
          last_tun_write[6] == IPPROTO_ICMPV6 && last_tun_write[40] == 1 &&
          last_tun_write[41] == 0 && memcmp(last_tun_write + 24, packet + 8, 16) == 0,
          "a blocked IPv6 datagram is answered with no route to destination");
    struct in6_addr unicast = ip6->ip6_dst;
    inet_pton(AF_INET6, "ff02::fb", &ip6->ip6_dst);
    run(packet, udp_length);
    CHECK(tun_writes == 1, "blocked IPv6 multicast is dropped without an answer");
    ip6->ip6_dst = unicast;

    // A fragment has no upper-layer header here; it may be a non-first one.
    ip6->ip6_nxt = IPPROTO_FRAGMENT;
    memset(packet + sizeof(struct ip6_hdr), 0, 8);
    packet[sizeof(struct ip6_hdr)] = IPPROTO_UDP;
    ip6->ip6_plen = htons(8);
    run(packet, sizeof(struct ip6_hdr) + 8);
    CHECK(tun_writes == 1 && rst_writes == resets, "a blocked IPv6 fragment is dropped without an answer");

    // An ICMPv6 error is recognised behind an extension header and not answered,
    // while an echo request behind the same header is.
    ip6->ip6_nxt = IPPROTO_DSTOPTS;
    uint8_t *extension = packet + sizeof(struct ip6_hdr);
    memset(extension, 0, 16);
    extension[0] = IPPROTO_ICMPV6;
    extension[2] = 1; // PadN covering the remaining six bytes
    extension[3] = 4;
    extension[8] = 1; // destination unreachable
    ip6->ip6_plen = htons(16);
    run(packet, sizeof(struct ip6_hdr) + 16);
    CHECK(tun_writes == 1, "an ICMPv6 error behind an extension header is not answered");
    extension[8] = 128; // echo request
    int handlers_before = unexpected_handler_calls;
    handle_ip(&args, packet, sizeof(struct ip6_hdr) + 16, -1, 0, 1000);
    CHECK(tun_writes == 2 && unexpected_handler_calls == handlers_before,
          "a blocked ICMPv6 echo request is answered with no route");
    ip6->ip6_nxt = IPPROTO_TCP;
    ip6->ip6_plen = htons(sizeof(struct tcphdr));
    make_tcp6(packet, 43001, 1, 0, 0, NULL, 0);
    inet_pton(AF_INET6, "fd7a:115c:a1e0::2", &((struct ip6_hdr *) packet)->ip6_dst);
    run(packet, length);
    CHECK(policy_calls == 1 && tailscale_writes == 1 && wireguard_writes == 0,
          "Tailnet ULA still reaches its own egress with ordinary IPv6 disabled");
    atomic_store(&wg_required, 1);
}

int main(void) {
    memset(&context, 0, sizeof(context));
    memset(&args, 0, sizeof(args));
    args.ctx = &context;
    args.fwd53 = 1;
    args.rcode = 3;
    context.sdk = 29;
    CHECK(atomic_load_explicit(&wg_required, memory_order_acquire) == 1,
          "WireGuard is required for the production-bound fixture");
    CHECK(route_default_is_tunnel() == 1, "the fixture starts with the default tunnel route");

    test_tcp_revalidation_and_fresh_syn();
    test_tcp_unknown_owner_fails_closed_then_recovers();
    test_ipv6_tcp_owner_revalidation();
    test_root_and_fresh_syn_owner_rules();
    test_udp_policy_invalidation_and_negative_state();
    test_selected_route_fast_path();
    test_split_tunnel_routes_outside_allowed_ips_direct();
    test_tailscale_tcp_revalidation();
    test_tailscale_udp_verdict_cache();
    test_tailscale_oversized_packet_gets_too_big();
    test_tailscale_dns_policy_and_native_sessions();
    test_tailscale_ipv6_preserves_ipv6_disabled_setting();
    if (failures != 0)
        return 1;
    puts("ip_flow_policy_test: all tests passed");
    return 0;
}
