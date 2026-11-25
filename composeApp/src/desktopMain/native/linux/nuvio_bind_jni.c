// JNI entry for VpnBindNative (fork:linux): the fail-closed VPN bind (docs/vpn-bind-design.md §3).
// Binds JVM-owned sockets to a network device with SO_BINDTODEVICE, unprivileged, so every packet
// only ever leaves through that device; if the device disappears the kernel errors the socket
// instead of re-routing over the default gateway. Socket fds are read through JNI field access
// (Socket -> impl -> fd, or the channel's own fd): the shipped JBR is fixed, and the JVM-side
// startup probe turns any layout change into "bind unavailable" rather than silent unbound traffic.
#include <jni.h>
#include <net/if.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int clear_and_fail(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return -1;
}

/** FileDescriptor object -> the int fd it wraps (java.io.FileDescriptor#fd). */
static int fd_of_file_descriptor(JNIEnv *env, jobject fdObject) {
    if (!fdObject) return -1;
    jclass cls = (*env)->GetObjectClass(env, fdObject);
    jfieldID fid = (*env)->GetFieldID(env, cls, "fd", "I");
    if (!fid || (*env)->ExceptionCheck(env)) { clear_and_fail(env); return -1; }
    return (*env)->GetIntField(env, fdObject, fid);
}

/**
 * Finds an object's `fd` FileDescriptor field walking up the class hierarchy. JDK >= 25 wraps
 * plain sockets in SocksSocketImpl -> DelegatingSocketImpl, where the live fd belongs to the
 * *delegated* impl, so [fd_of_socket] follows `delegate` hops too.
 */
static jobject find_fd_object(JNIEnv *env, jobject owner) {
    jclass cls = (*env)->GetObjectClass(env, owner);
    while (cls) {
        jfieldID fid = (*env)->GetFieldID(env, cls, "fd", "Ljava/io/FileDescriptor;");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (fid) {
            jobject fdObject = (*env)->GetObjectField(env, owner, fid);
            if (fdObject) return fdObject;
        }
        jclass superclass = (*env)->GetSuperclass(env, cls);
        (*env)->DeleteLocalRef(env, cls);
        cls = superclass;
    }
    return NULL;
}

/** The `delegate` SocketImpl hop (DelegatingSocketImpl), or null. */
static jobject delegate_of(JNIEnv *env, jobject impl) {
    jclass cls = (*env)->GetObjectClass(env, impl);
    while (cls) {
        jfieldID did = (*env)->GetFieldID(env, cls, "delegate", "Ljava/net/SocketImpl;");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (did) {
            jobject delegate = (*env)->GetObjectField(env, impl, did);
            if (delegate) return delegate;
        }
        jclass superclass = (*env)->GetSuperclass(env, cls);
        (*env)->DeleteLocalRef(env, cls);
        cls = superclass;
    }
    return NULL;
}

/** Resolves the real FileDescriptor across delegation hops. Caller frees the returned ref. */
static jobject resolve_fd_object(JNIEnv *env, jobject impl) {
    jobject current = impl;
    for (int hop = 0; hop < 8 && current; hop++) {
        jobject fdObject = find_fd_object(env, current);
        if (fdObject) {
            int fd = fd_of_file_descriptor(env, fdObject);
            if (fd >= 0) return fdObject; // live fd; caller frees
            (*env)->DeleteLocalRef(env, fdObject);
        }
        jobject delegate = delegate_of(env, current);
        if (current != impl) (*env)->DeleteLocalRef(env, current);
        current = delegate;
    }
    if (current && current != impl) (*env)->DeleteLocalRef(env, current);
    return NULL;
}

/**
 * java.net.Socket -> its SocketImpl -> the impl's FileDescriptor. The impl class differs by JDK
 * (PlainSocketImpl, NioSocketImpl); the runtime class lookup keeps both working.
 */
static int fd_of_socket(JNIEnv *env, jobject socket) {
    jclass socketClass = (*env)->GetObjectClass(env, socket);
    jfieldID implField = (*env)->GetFieldID(env, socketClass, "impl", "Ljava/net/SocketImpl;");
    if (!implField || (*env)->ExceptionCheck(env)) { clear_and_fail(env); return -1; }
    jobject impl = (*env)->GetObjectField(env, socket, implField);
    if (!impl) { clear_and_fail(env); return -1; }
    jobject fdObject = resolve_fd_object(env, impl);
    int fd = fd_of_file_descriptor(env, fdObject);
    (*env)->DeleteLocalRef(env, impl);
    if (fdObject) (*env)->DeleteLocalRef(env, fdObject);
    return fd;
}

/** java.nio.channels.DatagramChannel -> its private FileDescriptor fd (same hierarchy walk). */
static int fd_of_datagram_channel(JNIEnv *env, jobject channel) {
    jobject fdObject = find_fd_object(env, channel);
    int fd = fd_of_file_descriptor(env, fdObject);
    if (fdObject) (*env)->DeleteLocalRef(env, fdObject);
    return fd;
}

/** The one syscall that gives the guarantee. */
static int bind_to_device(int fd, const char *ifName) {
    return setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, ifName, (socklen_t)strlen(ifName) + 1);
}

/**
 * Diagnostics for the fd extraction chain (also surfaces as the "bind unavailable" detail in the
 * settings screen): each step reported as impl=<0|1> fdObj=<0|1> fd=<n> after any bind/connect.
 */
JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_vpn_VpnBindNative_describeFd(JNIEnv *env, jclass clazz, jobject socket) {
    (void)clazz;
    char report[256];
    int fd = -1;
    int implFound = 0, fdObjFound = 0;
    if (socket) {
        jclass socketClass = (*env)->GetObjectClass(env, socket);
        jfieldID implField = (*env)->GetFieldID(env, socketClass, "impl", "Ljava/net/SocketImpl;");
        if (implField && !(*env)->ExceptionCheck(env)) {
            implFound = 1;
            jobject impl = (*env)->GetObjectField(env, socket, implField);
            if (impl) {
                jobject fdObject = resolve_fd_object(env, impl);
                if (fdObject) {
                    fdObjFound = 1;
                    fd = fd_of_file_descriptor(env, fdObject);
                    (*env)->DeleteLocalRef(env, fdObject);
                }
                (*env)->DeleteLocalRef(env, impl);
            }
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    snprintf(report, sizeof report, "impl=%d fdObj=%d fd=%d", implFound, fdObjFound, fd);
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jint JNICALL
Java_com_nuvio_app_features_vpn_VpnBindNative_ifIndex(JNIEnv *env, jclass clazz, jstring ifName) {
    (void)clazz;
    if (!ifName) return 0;
    const char *name = (*env)->GetStringUTFChars(env, ifName, NULL);
    if (!name) return 0;
    unsigned index = if_nametoindex(name);
    (*env)->ReleaseStringUTFChars(env, ifName, name);
    return (jint)index;
}

JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_vpn_VpnBindNative_bindSocket(JNIEnv *env, jclass clazz, jobject socket, jstring ifName) {
    (void)clazz;
    if (!socket || !ifName) return JNI_FALSE;
    const char *name = (*env)->GetStringUTFChars(env, ifName, NULL);
    if (!name) return JNI_FALSE;
    int fd = fd_of_socket(env, socket);
    int ok = fd >= 0 && bind_to_device(fd, name) == 0;
    (*env)->ReleaseStringUTFChars(env, ifName, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_vpn_VpnBindNative_bindDatagramChannel(JNIEnv *env, jclass clazz, jobject channel, jstring ifName) {
    (void)clazz;
    if (!channel || !ifName) return JNI_FALSE;
    const char *name = (*env)->GetStringUTFChars(env, ifName, NULL);
    if (!name) return JNI_FALSE;
    int fd = fd_of_datagram_channel(env, channel);
    int ok = fd >= 0 && bind_to_device(fd, name) == 0;
    (*env)->ReleaseStringUTFChars(env, ifName, name);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Startup probe: an unprivileged SO_BINDTODEVICE must actually work here (kernel >= 5.7 rule). */
JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_vpn_VpnBindNative_probe(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return JNI_FALSE;
    int ok = bind_to_device(fd, "lo") == 0;
    close(fd);
    return ok ? JNI_TRUE : JNI_FALSE;
}
