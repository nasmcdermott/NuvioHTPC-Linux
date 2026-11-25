package com.nuvio.app.features.vpn

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Backing state for Settings > VPN (fork:linux). Discovery runs on demand; binding changes go
 * through [VpnBindController] so the page only reflects what the traffic paths really do.
 *
 * Activation uses `nmcli connection up <uuid>`: NetworkManager's polkit agent handles
 * authorization exactly as the desktop's own VPN toggle does — no app-side root, mutable and
 * immutable distros alike (docs/vpn-bind-design.md §2). The bind is foreground activation, like
 * the forks state: it is active until the app exits.
 */
internal object VpnBindSettingsController {
    private val log = Logger.withTag("VpnBindSettings")

    data class UiState(
        val mode: VpnBindController.Mode = VpnBindController.Mode.OFF,
        val target: VpnBindTarget? = null,
        val verified: Boolean = false,
        val availability: VpnBindAvailability? = null,
        val detail: String? = null,
        val profiles: List<VpnProfile> = emptyList(),
        val refreshing: Boolean = false,
        val activatingProfile: VpnProfile? = null,
        val lastActionMessage: String? = null,
    )

    private val mutable = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = mutable.asStateFlow()

    /** Reads the system's VPN profiles and interfaces; safe to call on every screen entry. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutable.value = mutable.value.copy(refreshing = true)
        val discovery = runCatching { VpnProfileDiscovery().discover() }
            .onFailure { log.w { "vpn discovery failed: ${it.message}" } }
            .getOrDefault(VpnDiscoveryResult(emptyList(), emptyList()))
        val bindState = VpnBindController.state()
        mutable.value = mutable.value.copy(
            profiles = discovery.profiles,
            refreshing = false,
            mode = bindState.mode,
            target = bindState.target,
            verified = bindState.verified,
            availability = bindState.availability,
            detail = bindState.detail,
        )
    }

    /**
     * Sets the protection mode against the given (or current) target. OFF disables everything;
     * anything else enables the bind on the target interface.
     */
    suspend fun setMode(mode: VpnBindController.Mode, target: VpnBindTarget? = currentTarget()) {
        mutable.value = mutable.value.copy(refreshing = true)
        val result = withContext(Dispatchers.IO) {
            when {
                mode == VpnBindController.Mode.OFF -> {
                    VpnBindController.disable()
                    true
                }
                target != null -> VpnBindController.enable(mode, target)
                else -> false
            }
        }
        val bindState = VpnBindController.state()
        mutable.value = mutable.value.copy(
            refreshing = false,
            mode = bindState.mode,
            target = bindState.target,
            verified = bindState.verified,
            availability = bindState.availability,
            detail = bindState.detail,
            lastActionMessage = when {
                mode != VpnBindController.Mode.OFF && result -> null
                mode != VpnBindController.Mode.OFF -> "Could not bind to the interface; protection is fail-closed (BLOCK) until it succeeds."
                else -> null
            },
        )
    }

    /**
     * The user picks a profile: an active one becomes the bind target for the STREAMS mode; an
     * inactive one is activated through NetworkManager first (polkit prompts as needed).
     */
    suspend fun useProfile(profile: VpnProfile) {
        when {
            profile.device != null -> setMode(VpnBindController.Mode.STREAMS, VpnBindTarget(profile.device))
            profile.uuid != null -> activate(profile)
            else -> mutable.value = mutable.value.copy(
                lastActionMessage = "This profile cannot be activated from the app; bring it up from the system, then refresh.",
            )
        }
    }

    /** `nmcli connection up` — NetworkManager's polkit agent owns the authorization. */
    suspend fun activate(profile: VpnProfile) {
        val uuid = profile.uuid ?: return
        mutable.value = mutable.value.copy(activatingProfile = profile)
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("nmcli", "connection", "up", uuid)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().use { it.readText() } to process.waitFor()
            }.getOrNull()
        }
        val message = when {
            outcome == null -> "NetworkManager is unavailable on this system."
            outcome.second == 0 -> "Activated \"${profile.name}\"."
            else -> "NetworkManager could not activate \"${profile.name}\" (exit ${outcome.second})."
        }
        mutable.value = mutable.value.copy(activatingProfile = null, lastActionMessage = message)
        refresh()
    }

    /** Re-probes the native bind availability and the current bind state (the Bind health row). */
    suspend fun recheck() = withContext(Dispatchers.IO) {
        val availability = runCatching { VpnBindNative.availability() }
            .getOrDefault(VpnBindAvailability.LIBRARY_UNAVAILABLE)
        VpnBindController.recordAvailability(availability)
        refresh()
    }

    fun clearActionMessage() {
        mutable.value = mutable.value.copy(lastActionMessage = null)
    }

    private fun currentTarget(): VpnBindTarget? {
        val bindTarget = VpnBindController.state().target
        if (bindTarget != null) return bindTarget
        val active = mutable.value.profiles.firstOrNull { it.device != null } ?: return null
        return VpnBindTarget(active.device!!)
    }
}
