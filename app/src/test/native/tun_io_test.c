#include "tun_io.h"

#include <assert.h>
#include <fcntl.h>
#include <pthread.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t blocked = PTHREAD_COND_INITIALIZER;
static int watched_fd = -1;
static int would_block;
static int interrupt_once;

ssize_t __real_write(int fd, const void *data, size_t length);
ssize_t __wrap_write(int fd, const void *data, size_t length) {
    if (fd == watched_fd && interrupt_once) {
        interrupt_once = 0;
        errno = EINTR;
        return -1;
    }
    ssize_t result = __real_write(fd, data, length);
    int saved = errno;
    if (fd == watched_fd && result < 0 && (saved == EAGAIN || saved == EWOULDBLOCK)) {
        pthread_mutex_lock(&lock);
        would_block++;
        pthread_cond_signal(&blocked);
        pthread_mutex_unlock(&lock);
    }
    errno = saved;
    return result;
}

struct writer {
    int fd;
    int stop;
    ssize_t result;
    int error;
};

static const unsigned char packet[512] = {0x45, 0x7a, 0x31};

static void *write_packet(void *opaque) {
    struct writer *writer = opaque;
    writer->result = write_tun_packet(writer->fd, writer->stop, packet, sizeof(packet));
    writer->error = errno;
    return NULL;
}

static int fill(int fd) {
    unsigned char padding[512] = {0};
    int count = 0;
    while (send(fd, padding, sizeof(padding), MSG_NOSIGNAL) >= 0) count++;
    assert(errno == EAGAIN || errno == EWOULDBLOCK);
    return count;
}

static void wait_for_backpressure(void) {
    struct timespec timeout;
    clock_gettime(CLOCK_REALTIME, &timeout);
    timeout.tv_sec += 2;
    pthread_mutex_lock(&lock);
    while (!would_block)
        assert(pthread_cond_timedwait(&blocked, &lock, &timeout) == 0);
    pthread_mutex_unlock(&lock);
}

static void nonblocking_pair(int pair[2]) {
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK, 0, pair) == 0);
    int capacity = 4096;
    assert(setsockopt(pair[0], SOL_SOCKET, SO_SNDBUF, &capacity, sizeof(capacity)) == 0);
    watched_fd = pair[0];
    would_block = 0;
}

static void test_backpressure_retries_whole_packet(void) {
    int pair[2];
    nonblocking_pair(pair);
    int queued = fill(pair[0]);
    struct writer writer = {.fd = pair[0], .stop = -1};
    pthread_t thread;
    assert(pthread_create(&thread, NULL, write_packet, &writer) == 0);
    wait_for_backpressure();
    unsigned char received[512];
    for (int i = 0; i < queued; i++)
        assert(recv(pair[1], received, sizeof(received), 0) == sizeof(received));
    assert(pthread_join(thread, NULL) == 0);
    assert(writer.result == sizeof(packet));
    assert(recv(pair[1], received, sizeof(received), 0) == sizeof(packet));
    assert(memcmp(received, packet, sizeof(packet)) == 0);
    close(pair[0]);
    close(pair[1]);
}

static void test_stop_cancels_full_tun_without_consuming_wakeup(void) {
    int pair[2], stop[2];
    nonblocking_pair(pair);
    fill(pair[0]);
    assert(pipe(stop) == 0);
    struct writer writer = {.fd = pair[0], .stop = stop[0]};
    pthread_t thread;
    assert(pthread_create(&thread, NULL, write_packet, &writer) == 0);
    wait_for_backpressure();
    assert(write(stop[1], "w", 1) == 1);
    assert(pthread_join(thread, NULL) == 0);
    assert(writer.result == -1 && writer.error == ECANCELED);
    char wakeup;
    assert(read(stop[0], &wakeup, 1) == 1 && wakeup == 'w');
    close(pair[0]); close(pair[1]); close(stop[0]); close(stop[1]);
}

int main(void) {
    alarm(15);
    test_backpressure_retries_whole_packet();
    test_stop_cancels_full_tun_without_consuming_wakeup();
    int pair[2];
    nonblocking_pair(pair);
    interrupt_once = 1;
    assert(write_tun_packet(pair[0], -1, packet, sizeof(packet)) == sizeof(packet));
    close(pair[0]); close(pair[1]);
    assert(write_tun_packet(-1, -1, packet, sizeof(packet)) == -1 && errno == EBADF);
    return 0;
}
