#ifndef TRACKERCONTROL_TUN_IO_H
#define TRACKERCONTROL_TUN_IO_H

#include <errno.h>
#include <poll.h>
#include <stddef.h>
#include <unistd.h>

// Tailscale's duplicated TUN shares O_NONBLOCK with the native engine. Keep
// native writes reliable under backpressure, but let VPN stop cancel a wait.
// Never split a packet into separate TUN writes after a short write.
static inline ssize_t write_tun_packet(int tun, int stop_fd, const void *data, size_t length) {
    for (;;) {
        ssize_t written = write(tun, data, length);
        if (written >= 0)
            return written;
        if (errno == EINTR)
            continue;
        if (errno != EAGAIN && errno != EWOULDBLOCK)
            return -1;

        struct pollfd ready[2] = {
                {.fd = tun, .events = POLLOUT},
                {.fd = stop_fd, .events = POLLIN},
        };
        int result;
        do {
            result = poll(ready, 2, -1);
        } while (result < 0 && errno == EINTR);
        if (result < 0)
            return -1;
        if (ready[1].revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) {
            errno = ECANCELED;
            return -1;
        }
        if (ready[0].revents & (POLLHUP | POLLERR | POLLNVAL)) {
            errno = EIO;
            return -1;
        }
    }
}

#endif
