package com.nuvio.app.features.vpn

import kotlin.concurrent.thread

/**
 * Owns the VPN bind state (docs/vpn-bind-design.md §3). The modes use the forks' protection
 * vocabulary; the invariants are theirs too: a selected mode is intent, never proof — the relay
 * and the kernel-level bind provide the proof, and [verified] reflects it. Policy always asks
 * [decide]; nothing falls back silently to direct traffic.
 */
internal object VpnBindController {
    enum class Mode {
        /** Nothing bound; the app behaves exactly as upstream. */
        OFF,

        /** Playback + API/scraper HTTP(S) through the fail-closed relay. */
        STREAMS,

        /** Adds the libtorrent interface bind via engine config (stage 3 wiring). */
        STREAMS_AND_P2P,

        /** Streams + P2P + the remaining enumerated clients (UI states the residual honestly). */
        FULL_APP,
    }

    data class State(
        val mode: Mode = Mode.OFF,
        val target: VpnBindTarget? = null,
        /** The bind is live: interface exists and native binding probed OK. */
        val verified: Boolean = false,
        val availability: VpnBindAvailability? = null,
        val detail: String? = null,
    )

    @Volatile private var mode = Mode.OFF
    @Volatile private var target: VpnBindTarget? = null
    @Volatile private var verified = false
    @Volatile private var availability: VpnBindAvailability? = null
    @Volatile private var detail: String? = null
    @Volatile private var watcher: Thread? = null

    /**
     * Enables a mode on [newTarget]. Returns false when the target is not bindable right now
     * (native unavailable, kernel unsupported, interface gone) — state then says so, unverified,
     * and [decide] BLOCKs until the user retries. Failing closed is the feature.
     */
    @Synchronized
    fun enable(
        newMode: Mode,
        newTarget: VpnBindTarget,
        lowLevel: VpnBindLowLevel = VpnBindNative,
        watchIntervalMs: Long = 2_000,
        availability: VpnBindAvailability = VpnBindNative.availability(),
    ): Boolean {
        availability.let { probed ->
            if (probed != VpnBindAvailability.AVAILABLE) {
                this.availability = probed
                detail = probed.detail
                verified = false
                mode = newMode
                target = newTarget
                if (newMode != Mode.OFF) {
                    // Fail closed even without native binding: the relay refuses everything.
                    BindRelay.start(newTarget, lowLevel)
                    startWatcher(lowLevel, watchIntervalMs)
                }
                return false
            }
        }
        if (lowLevel.ifIndex(newTarget.interfaceName) <= 0) {
            detail = "interface ${newTarget.interfaceName} is not up"
            verified = false
            mode = newMode
            target = newTarget
            BindRelay.start(newTarget, lowLevel)
            startWatcher(lowLevel, watchIntervalMs)
            return false
        }
        detail = null
        mode = newMode
        target = newTarget
        verified = true
        if (newMode != Mode.OFF) {
            // The relay runs whenever a mode is on — even unverified — so every client pointed at
            // it fails closed; the refusal is BLOCK made real. Never a direct fallback.
            BindRelay.start(newTarget, lowLevel)
            startWatcher(lowLevel, watchIntervalMs)
        }
        return true
    }

    @Synchronized
    fun disable() {
        mode = Mode.OFF
        verified = false
        target = null
        detail = null
        BindRelay.stop()
        watcher?.interrupt()
        watcher = null
    }

    fun state(): State = State(mode, target, verified && mode != Mode.OFF, availability, detail)

    /**
     * Non-null whenever a mode is on — callers must treat null as "direct, unprotected" and only
     * route to the relay in that case. Unverified modes yield a relay that refuses upstream.
     */
    fun relayProxyUrl(): String? =
        if (mode != Mode.OFF) BindRelay.currentProxyUrl() else null

    /** The app-level gate: loopback stays direct, enabled-but-unverified blocks. */
    fun decide(host: String?): VpnRouteDecision =
        VpnRoutePolicy.decide(host, mode != Mode.OFF, verified)

    private fun startWatcher(lowLevel: VpnBindLowLevel, intervalMs: Long) {
        watcher?.interrupt()
        watcher = thread(name = "vpn-bind-watch", isDaemon = true) {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(intervalMs)
                    val current = target ?: break
                    val live = lowLevel.ifIndex(current.interfaceName) > 0
                    verified = live // same interface only: never a silent rebind to another one
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Records a fresh availability probe without touching the mode (the Bind health row's Recheck). */
    @Synchronized
    internal fun recordAvailability(probed: VpnBindAvailability) {
        availability = probed
    }

    /** For tests. */
    @Synchronized
    internal fun resetForTest() {
        disable()
        availability = null
    }
}
