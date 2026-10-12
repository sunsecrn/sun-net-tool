/* Local-only executable fixture for Android. No TUN, root or external network required. */
#include <assert.h>
#include <signal.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include "zdtun.h"

static int got_udp, got_tcp, got_syn, refuse_socket;
static uint32_t server_sequence;
static const char request_payload[] = "nettool-local-test";
static const char reply_payload[] = "local-response";

static uint16_t get16(const char *b) { uint16_t v; memcpy(&v, b, 2); return ntohs(v); }
static uint32_t get32(const char *b) { uint32_t v; memcpy(&v, b, 4); return ntohl(v); }
static void put16(char *b, uint16_t v) { v = htons(v); memcpy(b, &v, 2); }
static void put32(char *b, uint32_t v) { v = htonl(v); memcpy(b, &v, 4); }

static int send_client(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
    if (pkt->tuple.ipproto == IPPROTO_UDP && pkt->l7_len == sizeof(reply_payload) &&
        memcmp(pkt->l7, reply_payload, sizeof(reply_payload)) == 0) got_udp = 1;
    if (pkt->tuple.ipproto == IPPROTO_TCP) {
        if (((unsigned char)pkt->l4[13] & 0x12) == 0x12) {
            got_syn = 1;
            server_sequence = get32(pkt->l4 + 4);
        }
        if (pkt->l7_len == sizeof(reply_payload) && memcmp(pkt->l7, reply_payload, sizeof(reply_payload)) == 0)
            got_tcp = 1;
    }
    return 0;
}
static int socket_open(zdtun_t *tun, socket_t fd) { return refuse_socket; }

static int server(int type, uint16_t *port) {
    int fd = socket(AF_INET, type, 0);
    assert(fd >= 0);
    struct timeval timeout = {.tv_sec = 2};
    assert(setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    assert(bind(fd, (struct sockaddr *)&address, sizeof(address)) == 0);
    socklen_t n = sizeof(address);
    assert(getsockname(fd, (struct sockaddr *)&address, &n) == 0);
    *port = ntohs(address.sin_port);
    if (type == SOCK_STREAM) assert(listen(fd, 1) == 0);
    return fd;
}

static void pump(zdtun_t *tun) {
    fd_set rd, wr;
    int largest = 0;
    zdtun_fds(tun, &largest, &rd, &wr);
    struct timeval timeout = {.tv_sec = 0, .tv_usec = 100000};
    assert(select(largest + 1, &rd, &wr, NULL, &timeout) >= 0);
    zdtun_handle_fd(tun, &rd, &wr);
}

static int packet(char *buf, int proto, uint16_t destination_port, uint32_t sequence, uint32_t ack, int flags, const char *payload, int payload_size) {
    int transport = proto == IPPROTO_TCP ? 20 : 8;
    int len = 20 + transport + payload_size;
    memset(buf, 0, len);
    buf[0] = 0x45;
    put16(buf + 2, len);
    buf[8] = 64;
    buf[9] = proto;
    buf[12] = 10; buf[15] = 2;
    buf[16] = 127; buf[19] = 1;
    put16(buf + 20, 23456);
    put16(buf + 22, destination_port);
    if (proto == IPPROTO_TCP) {
        put32(buf + 24, sequence);
        put32(buf + 28, ack);
        buf[32] = 0x50;
        buf[33] = flags;
        put16(buf + 34, 65535);
    } else put16(buf + 24, transport + payload_size);
    if (payload_size) memcpy(buf + 20 + transport, payload, payload_size);
    return len;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    zdtun_callbacks_t callbacks = {.send_client = send_client, .on_socket_open = socket_open};
    zdtun_t *tun = zdtun_init(&callbacks, NULL);
    assert(tun);
    char buf[256], received[256];
    uint16_t port;
    int udp = server(SOCK_DGRAM, &port);
    int len = packet(buf, IPPROTO_UDP, port, 0, 0, 0, request_payload, sizeof(request_payload));
    assert(zdtun_easy_forward(tun, buf, len));
    struct sockaddr_in peer;
    socklen_t peer_len = sizeof(peer);
    assert(recvfrom(udp, received, sizeof(received), 0, (struct sockaddr *)&peer, &peer_len) == sizeof(request_payload));
    assert(memcmp(received, request_payload, sizeof(request_payload)) == 0);
    assert(sendto(udp, reply_payload, sizeof(reply_payload), 0, (struct sockaddr *)&peer, peer_len) == sizeof(reply_payload));
    for (int i = 0; i < 20 && !got_udp; ++i) pump(tun);
    assert(got_udp);
    close(udp);
    puts("PASS UDP request forwarded to loopback and reply rebuilt for client");

    int listener = server(SOCK_STREAM, &port);
    len = packet(buf, IPPROTO_TCP, port, 100, 0, 2, NULL, 0);
    assert(zdtun_easy_forward(tun, buf, len));
    for (int i = 0; i < 20 && !got_syn; ++i) pump(tun);
    assert(got_syn);
    int tcp = accept(listener, NULL, NULL);
    assert(tcp >= 0);
    struct timeval timeout = {.tv_sec = 2};
    setsockopt(tcp, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    len = packet(buf, IPPROTO_TCP, port, 101, server_sequence + 1, 0x18, request_payload, sizeof(request_payload));
    assert(zdtun_easy_forward(tun, buf, len));
    // TCP writes are queued; dispatch writable events before waiting on the server.
    pump(tun);
    assert(recv(tcp, received, sizeof(received), 0) == sizeof(request_payload));
    assert(memcmp(received, request_payload, sizeof(request_payload)) == 0);
    assert(send(tcp, reply_payload, sizeof(reply_payload), 0) == sizeof(reply_payload));
    for (int i = 0; i < 20 && !got_tcp; ++i) pump(tun);
    assert(got_tcp);
    close(tcp); close(listener);
    puts("PASS TCP SYN/SYNACK, payload forwarding and rebuilt response");
    zdtun_finalize(tun);

    refuse_socket = 1;
    tun = zdtun_init(&callbacks, NULL);
    udp = server(SOCK_DGRAM, &port);
    len = packet(buf, IPPROTO_UDP, port, 0, 0, 0, request_payload, sizeof(request_payload));
    zdtun_easy_forward(tun, buf, len);
    assert(recv(udp, received, sizeof(received), MSG_DONTWAIT) < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
    zdtun_statistics_t stats;
    zdtun_get_stats(tun, &stats);
    assert(stats.num_open_sockets == 0);
    close(udp); zdtun_finalize(tun);
    puts("PASS socket protection/binding rejection fails closed");
    return 0;
}
