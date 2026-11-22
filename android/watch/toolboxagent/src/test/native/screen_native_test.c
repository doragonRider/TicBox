#define _GNU_SOURCE
#include "screen_udp.h"
#include "screen_fec.h"
#include <assert.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static atomic_int fault, invocations;
static atomic_int gso_fault, gso_invocations;
ssize_t tw_test_sendmsg(int fd, const struct msghdr *message, int flags) {
    int call = atomic_fetch_add(&gso_invocations, 1), mode = atomic_load(&gso_fault);
    if (mode == 1) { errno = ENOPROTOOPT; return -1; }
    if (mode == 2 && call == 0) { errno = EAGAIN; return -1; }
    if (mode == 3 && call == 0) { errno = EINTR; return -1; }
    if (mode == 4) { errno = ENOBUFS; return -1; }
    if (mode == 5) { errno = EACCES; return -1; }
    if (mode == 6) return 1; // ambiguous short result must never replay the batch
    if (mode == 7) { errno = EIO; return -1; }
    if (mode == 8) { errno = EINVAL; return -1; }
    if (mode == 9) { errno = EOPNOTSUPP; return -1; }
    return sendmsg(fd, message, flags);
}
int tw_test_sendmmsg(int fd, struct mmsghdr *messages, unsigned int count, int flags) {
    int call = atomic_fetch_add(&invocations, 1), mode = atomic_load(&fault);
    if (mode == 1 && call == 0) return sendmmsg(fd, messages, count > 2 ? 2 : count, flags);
    if (mode == 2 && call == 0) { errno = EINTR; return -1; }
    if (mode == 3 && call == 0) { errno = EAGAIN; return -1; }
    if (mode == 4) { errno = ENOBUFS; return -1; }
    return sendmmsg(fd, messages, count, flags);
}
static uint8_t packets[9600];
static int lengths[8] = {48, 60, 1200, 400, 100, 1200, 48, 900};
static uint8_t ip4[4] = {127,0,0,1};
static long long now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000LL + t.tv_nsec / 1000000; }
static int receiver(int family, int *port) {
    int fd = socket(family, SOCK_DGRAM | SOCK_CLOEXEC, 0); assert(fd >= 0);
    struct sockaddr_in6 addr6 = {.sin6_family = AF_INET6, .sin6_addr = IN6ADDR_LOOPBACK_INIT};
    struct sockaddr_in addr4 = {.sin_family = AF_INET, .sin_addr = {.s_addr = htonl(INADDR_LOOPBACK)}};
    void *address = family == AF_INET ? (void *)&addr4 : (void *)&addr6;
    socklen_t length = family == AF_INET ? sizeof(addr4) : sizeof(addr6);
    assert(bind(fd, address, length) == 0); assert(getsockname(fd, address, &length) == 0);
    *port = ntohs(family == AF_INET ? addr4.sin_port : addr6.sin6_port); return fd;
}
static void receive_all(int fd) {
    for (int i = 0; i < 8; ++i) {
        struct pollfd p = {fd, POLLIN, 0}; assert(poll(&p, 1, 1000) == 1);
        uint8_t data[1201]; assert(recv(fd, data, sizeof(data), 0) == lengths[i]);
        assert(memcmp(data, packets + i * 1200, (size_t)lengths[i]) == 0);
    }
    struct pollfd p = {fd, POLLIN, 0}; assert(poll(&p, 1, 2) == 0); // no retransmitted prefix
}
static int64_t closing_id;
static void *cancel(void *unused) { (void)unused; struct timespec delay = {0, 20000000}; nanosleep(&delay, NULL); tw_screen_close(closing_id); return NULL; }
static unsigned multiply(unsigned a, unsigned b) { unsigned x = 0; while (b) { if (b & 1) x ^= a; a <<= 1; if (a & 256) a ^= 0x11d; b >>= 1; } return x; }
static void fec(void) {
    uint8_t data[16*1152], actual[4*1152], expected[4*1152];
    for (int k = 1; k <= 16; ++k) for (int length = 0; length <= 1152; length = length < 33 ? length + 1 : 1153) {
        // Odd lengths cover vector tails; full 1152-byte blocks are checked separately below.
        memset(expected, 0, sizeof(expected));
        for (int i = 0; i < k * length; ++i) data[i] = (uint8_t)(i * 31 + k);
        for (int r = 0; r < (k+3)/4; ++r) for (int c = 0; c < k; ++c) {
            unsigned coef = 1; while (multiply(coef, (unsigned)(r ^ (c+4))) != 1) ++coef;
            for (int i = 0; i < length; ++i) expected[r*length+i] ^= (uint8_t)multiply(coef, data[c*length+i]);
        }
        tw_fec_encode(data, k, length, actual, 1); assert(memcmp(actual, expected, (size_t)((k+3)/4)*length) == 0);
    }
    for (int i = 0; i < 16*1152; ++i) data[i] = (uint8_t)(i*17);
    tw_fec_encode(data, 16, 1152, expected, 0); tw_fec_encode(data, 16, 1152, actual, 1);
    assert(memcmp(actual, expected, sizeof(actual)) == 0);
}
static void gso(int tx, int rx, const uint8_t *ip, int addr_length, int port) {
    // Full and short equal-sized batches use scatter/gather without including stride padding;
    // a shorter final datagram is preserved too. Wire packets retain their original contents.
    for (int size = 48; size <= 1200; size += 1152) for (int tail = 0; tail <= 1; ++tail) {
        for (int i = 0; i < 8; ++i) lengths[i] = size;
        if (tail && size > 48) lengths[7] = 71;
        int64_t id = tw_screen_open(tx); assert(id > 0); TwScreenSendStats stats;
        for (int mode = 0; mode <= 3; mode += mode == 0 ? 2 : 1) {
            atomic_store(&gso_fault, mode); atomic_store(&gso_invocations, 0); atomic_store(&invocations, 0);
            int calls = tw_screen_send_measured(id, packets, lengths, 8, ip, addr_length, 0, port, &stats);
            assert(calls == (mode == 0 ? 1 : 2)); assert(stats.gso_calls == calls);
            assert(stats.gso_datagrams == 8 && !stats.gso_disabled && atomic_load(&invocations) == 0);
            receive_all(rx);
        }
        tw_screen_close(id);
    }
    for (int mode = 1; mode <= 9; ++mode) {
        if (mode == 2 || mode == 3) continue;
        for (int i = 0; i < 8; ++i) lengths[i] = 1200;
        int64_t id = tw_screen_open(tx); assert(id > 0); TwScreenSendStats stats;
        atomic_store(&gso_fault, mode); atomic_store(&gso_invocations, 0); atomic_store(&invocations, 0);
        atomic_store(&fault, mode == 1 ? 1 : 0); // fallback must also honor sendmmsg's partial prefix
        int result = tw_screen_send_measured(id, packets, lengths, 8, ip, addr_length, 0, port, &stats);
        if (mode == 1 || mode >= 7) {
            assert(result == (mode == 1 ? 3 : 2) && stats.gso_calls == 1 && stats.gso_disabled && stats.gso_datagrams == 0);
            receive_all(rx);
            // A failed capability probe is not retried for every frame; no duplicate datagrams.
            assert(tw_screen_send_measured(id, packets, lengths, 8, ip, addr_length, 0, port, &stats) == 1);
            assert(stats.gso_calls == 0 && stats.gso_disabled); receive_all(rx);
        } else {
            assert(result == -1 && errno == (mode == 4 ? ETIMEDOUT : mode == 5 ? EACCES : EIO));
            assert(atomic_load(&invocations) == 0 && !stats.gso_disabled);
            struct pollfd p = {rx, POLLIN, 0}; assert(poll(&p, 1, 2) == 0);
        }
        tw_screen_close(id);
    }
    atomic_store(&gso_fault, 4);
    closing_id = tw_screen_open(tx); assert(closing_id > 0); pthread_t thread;
    assert(pthread_create(&thread, NULL, cancel, NULL) == 0);
    assert(tw_screen_send(closing_id, packets, lengths, 8, ip, addr_length, 0, port) == -1 && errno == ECANCELED);
    pthread_join(thread, NULL); atomic_store(&gso_fault, 0);
}
static void whole_block(int tx, int rx, int port) {
    // A whole FEC block, 16 data and 4 parity shards, goes out in one GSO call; one more is refused.
    static uint8_t block[TW_SCREEN_MAX_BATCH * 1200]; int sizes[TW_SCREEN_MAX_BATCH + 1];
    for (int i = 0; i <= TW_SCREEN_MAX_BATCH; ++i) sizes[i] = 1200;
    for (int i = 0; i < TW_SCREEN_MAX_BATCH * 1200; ++i) block[i] = (uint8_t)(i * 7 + i / 1200);
    int64_t id = tw_screen_open(tx); assert(id > 0); TwScreenSendStats stats;
    assert(tw_screen_send_measured(id, block, sizes, TW_SCREEN_MAX_BATCH, ip4, 4, 0, port, &stats) == 1);
    assert(stats.gso_calls == 1 && stats.gso_datagrams == TW_SCREEN_MAX_BATCH && !stats.gso_disabled);
    for (int i = 0; i < TW_SCREEN_MAX_BATCH; ++i) {
        struct pollfd p = {rx, POLLIN, 0}; assert(poll(&p, 1, 1000) == 1);
        uint8_t data[1201]; assert(recv(rx, data, sizeof(data), 0) == 1200); assert(memcmp(data, block + i * 1200, 1200) == 0);
    }
    assert(tw_screen_send(id, block, sizes, TW_SCREEN_MAX_BATCH + 1, ip4, 4, 0, port) == -1 && errno == EINVAL);
    tw_screen_close(id);
}
int main(void) {
    for (int i = 0; i < 8; ++i) memset(packets + i * 1200, i + 1, 1200);
    fec();
    int port, rx = receiver(AF_INET, &port), tx = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0); assert(tx >= 0);
    whole_block(tx, rx, port);
    int64_t id = tw_screen_open(tx); assert(id > 0);
    for (int mode = 0; mode <= 3; ++mode) {
        atomic_store(&fault, mode); atomic_store(&invocations, 0);
        int calls = tw_screen_send(id, packets, lengths, 8, ip4, 4, 0, port);
        assert(calls == (mode == 0 ? 1 : 2)); receive_all(rx);
    }
    atomic_store(&fault, 4); long long start = now_ms();
    assert(tw_screen_send(id, packets, lengths, 8, ip4, 4, 0, port) == -1 && errno == ETIMEDOUT);
    assert(now_ms() - start >= 990 && now_ms() - start < 2500);
    closing_id = id; pthread_t thread; assert(pthread_create(&thread, NULL, cancel, NULL) == 0);
    assert(tw_screen_send(id, packets, lengths, 8, ip4, 4, 0, port) == -1 && errno == ECANCELED);
    pthread_join(thread, NULL); tw_screen_close(id);
    assert(tw_screen_send(id, packets, lengths, 8, ip4, 4, 0, port) == -1 && errno == ECANCELED);
    // The Java-owned original descriptor is still open and cannot have been closed/reused.
    int type; socklen_t type_len = sizeof(type); assert(getsockopt(tx, SOL_SOCKET, SO_TYPE, &type, &type_len) == 0);
    atomic_store(&fault, 0);
    int port6, rx6 = receiver(AF_INET6, &port6), tx6 = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0); assert(tx6 >= 0);
    int zero = 0; assert(setsockopt(tx6, IPPROTO_IPV6, IPV6_V6ONLY, &zero, sizeof(zero)) == 0);
    int64_t id6 = tw_screen_open(tx6); assert(id6 > 0);
    uint8_t ip6[16] = {0}; ip6[15] = 1;
    assert(tw_screen_send(id6, packets, lengths, 8, ip6, 16, 0, port6) == 1); receive_all(rx6);
    assert(tw_screen_send(id6, packets, lengths, 8, ip4, 4, 0, port) == 1); receive_all(rx);
    gso(tx, rx, ip4, 4, port); gso(tx6, rx6, ip6, 16, port6); gso(tx6, rx, ip4, 4, port);
    for (int i = 0; i < 100; ++i) { int64_t next = tw_screen_open(tx); assert(next > id); tw_screen_close(next); }
    tw_screen_close(id6); close(tx); close(rx); close(tx6); close(rx6);
    puts("Native SIMD/reference, IPv4/IPv6/mapped, GSO wire boundaries/unsupported/fatal errors, partial/EINTR/EAGAIN, deadline/cancel/ownership passed");
    return 0;
}
