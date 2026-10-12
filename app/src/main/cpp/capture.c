#include <jni.h>
#include <stdatomic.h>
#include <android/multinetwork.h>
#include <signal.h>
#include "zdtun.h"

static atomic_int stopping = 0;
typedef struct {
    JNIEnv *env;
    jobject service;
    jmethodID protect_method, packet_method, loss_method, unsupported_method, tick_method;
    int fd, callback_error;
    net_handle_t network;
} capture_t;

static int protect_socket(zdtun_t *tun, socket_t fd) {
    capture_t *c = zdtun_userdata(tun);
    jboolean ok = (*c->env)->CallBooleanMethod(c->env, c->service, c->protect_method, fd);
    if ((*c->env)->ExceptionCheck(c->env)) {
        (*c->env)->ExceptionClear(c->env);
        return 1;
    }
    return !ok || android_setsocknetwork(c->network, fd) != 0;
}

static void packet(capture_t *c, const char *buf, int len, int outbound) {
    jbyteArray data = (*c->env)->NewByteArray(c->env, len);
    if (!data) {
        (*c->env)->ExceptionClear(c->env);
        c->callback_error = -5;
        atomic_store(&stopping, 1);
        return;
    }
    (*c->env)->SetByteArrayRegion(c->env, data, 0, len, (const jbyte *)buf);
    (*c->env)->CallVoidMethod(c->env, c->service, c->packet_method, data, (jboolean)outbound);
    (*c->env)->DeleteLocalRef(c->env, data);
    if ((*c->env)->ExceptionCheck(c->env)) {
        (*c->env)->ExceptionClear(c->env);
        c->callback_error = -5;
        atomic_store(&stopping, 1);
    }
}

static int send_client(zdtun_t *tun, zdtun_pkt_t *pkt, const zdtun_conn_t *conn) {
    capture_t *c = zdtun_userdata(tun);
    ssize_t n = write(c->fd, pkt->buf, pkt->len);
    packet(c, pkt->buf, pkt->len, 0);
    if (n != pkt->len)
        (*c->env)->CallVoidMethod(c->env, c->service, c->loss_method);
    return n == pkt->len ? 0 : -1;
}

static int connection_open(zdtun_t *tun, zdtun_conn_t *conn) {
    /* Bound memory and socket use. Unsupported protocols are explicitly rejected. */
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);
    return zdtun_get_num_connections(tun) >= 256 ||
        (tuple->ipproto != IPPROTO_TCP && tuple->ipproto != IPPROTO_UDP);
}

JNIEXPORT void JNICALL Java_dev_nettool_capture_CaptureService_nativeStop(JNIEnv *env, jobject self) {
    atomic_store(&stopping, 1);
}

JNIEXPORT jint JNICALL Java_dev_nettool_capture_CaptureService_nativeRun(JNIEnv *env, jobject self, jint fd, jlong network) {
    capture_t c = {.env = env, .service = self, .fd = fd, .network = (net_handle_t)network};
    jclass cls = (*env)->GetObjectClass(env, self);
    c.protect_method = (*env)->GetMethodID(env, cls, "protectSocket", "(I)Z");
    c.packet_method = (*env)->GetMethodID(env, cls, "recordPacket", "([BZ)V");
    c.loss_method = (*env)->GetMethodID(env, cls, "recordLoss", "()V");
    c.unsupported_method = (*env)->GetMethodID(env, cls, "recordUnsupported", "()V");
    c.tick_method = (*env)->GetMethodID(env, cls, "publishTick", "()V");
    (*env)->DeleteLocalRef(env, cls);
    if (!c.protect_method || !c.packet_method || !c.loss_method || !c.unsupported_method || !c.tick_method || fd < 0 || fd >= FD_SETSIZE) return -1;
    signal(SIGPIPE, SIG_IGN);
    zdtun_callbacks_t callbacks = {.send_client = send_client, .on_socket_open = protect_socket, .on_connection_open = connection_open};
    zdtun_t *tun = zdtun_init(&callbacks, &c);
    if (!tun) return -2;
    zdtun_set_mtu(tun, 1500);
    char buf[65535];
    int result = 0;
    time_t last_purge = time(NULL);
    while (!atomic_load(&stopping)) {
        fd_set rd, wr;
        int max_fd = 0;
        zdtun_fds(tun, &max_fd, &rd, &wr);
        FD_SET(fd, &rd);
        if (fd > max_fd) max_fd = fd;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = 200000};
        int ready = select(max_fd + 1, &rd, &wr, NULL, &timeout);
        if (ready < 0) { if (errno == EINTR) continue; result = -3; break; }
        if (FD_ISSET(fd, &rd)) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n > 0) {
                packet(&c, buf, n, 1);
                zdtun_pkt_t parsed;
                int parse_result = zdtun_parse_pkt(tun, buf, n, &parsed);
                /* zdtun returns -2 for ICMP control packets before completing parsing;
                 * it also rejects IPv6 extension headers early. Classify from the base
                 * header so unsupported protocols are not reported as forwarding faults. */
                int version = ((unsigned char)buf[0]) >> 4;
                int base_protocol = version == 4 && n >= 20 ? (unsigned char)buf[9] :
                    (version == 6 && n >= 40 ? (unsigned char)buf[6] : -1);
                if ((base_protocol >= 0 && base_protocol != IPPROTO_TCP && base_protocol != IPPROTO_UDP) ||
                        (parse_result == 0 && (parsed.flags & ZDTUN_PKT_IS_FRAGMENT)))
                    (*env)->CallVoidMethod(env, self, c.unsupported_method);
                else if (!zdtun_easy_forward(tun, buf, n))
                    (*env)->CallVoidMethod(env, self, c.loss_method);
            } else if (n == 0 || (errno != EAGAIN && errno != EINTR)) { result = -4; break; }
            FD_CLR(fd, &rd);
        }
        zdtun_handle_fd(tun, &rd, &wr);
        (*env)->CallVoidMethod(env, self, c.tick_method);
        if (time(NULL) - last_purge >= 2) { zdtun_purge_expired(tun); last_purge = time(NULL); }
    }
    zdtun_finalize(tun);
    return c.callback_error ? c.callback_error : result;
}

JNIEXPORT void JNICALL Java_dev_nettool_capture_CaptureService_nativePrepare(JNIEnv *env, jobject self) {
    atomic_store(&stopping, 0);
}
