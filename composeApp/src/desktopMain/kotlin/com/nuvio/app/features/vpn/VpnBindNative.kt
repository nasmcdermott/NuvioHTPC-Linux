package com.nuvio.app.features.vpn

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.net.Socket
import java.nio.channels.DatagramChannel

/**
 * The low-level bind contract, so [VpnBindController] is testable without native code.
 * Production implementation is JNI in `native/linux/nuvio_bind_jni.c`, part of libplayer_bridge.
 */
internal interface VpnBindLowLevel {
    /** Interface index, or 0 when the interface does not exist (the fail-closed trigger). */
    fun ifIndex(ifName: String): Int

    /** Bind an unconnected socket to the device; false = refuse, never an unbound connect. */
    fun bindSocket(socket: Socket, ifName: String): Boolean

    /** Bind a datagram channel to the device (the DNS path). */
    fun bindDatagramChannel(channel: DatagramChannel, ifName: String): Boolean

    /** Whether unprivileged SO_BINDTODEVICE works on this kernel at all. */
    fun probe(): Boolean
}

internal object VpnBindNative : VpnBindLowLevel {
    external override fun ifIndex(ifName: String): Int
    external override fun bindSocket(socket: Socket, ifName: String): Boolean
    external override fun bindDatagramChannel(channel: DatagramChannel, ifName: String): Boolean
    external override fun probe(): Boolean

    /** Where the fd-extraction chain stops, for the settings screen and diagnostics. */
    external fun describeFd(socket: Socket): String

    /** Loads libplayer_bridge and answers once: can this machine bind, yes or no. */
    fun availability(): VpnBindAvailability = runCatching {
        NativePlayerBridge.ensureNativeLibraryLoaded()
        when {
            probe() -> VpnBindAvailability.AVAILABLE
            else -> VpnBindAvailability.KERNEL_UNSUPPORTED
        }
    }.getOrElse { failure ->
        VpnBindAvailability.LIBRARY_UNAVAILABLE.apply { detail = failure.message }
    }
}

/** Why binds may be unavailable; the UI states the reason instead of silently ignoring a toggle. */
enum class VpnBindAvailability {
    AVAILABLE,
    KERNEL_UNSUPPORTED,
    LIBRARY_UNAVAILABLE;

    var detail: String? = null
}
