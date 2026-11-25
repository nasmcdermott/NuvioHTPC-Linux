package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.features.iptv.IptvRepository
import com.nuvio.app.features.vpn.VpnBindAvailability
import com.nuvio.app.features.vpn.VpnBindController
import com.nuvio.app.features.vpn.VpnBindSettingsController
import com.nuvio.app.features.vpn.VpnProfile
import com.nuvio.app.features.vpn.VpnProfileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * fork:vpn-bind — Settings > VPN.
 *
 * Gamepad/keyboard first: every control is a focusable settings row (switch rows cycle with
 * Up/Down and commit with Enter, action rows carry glass buttons), so the page follows the repo's
 * navigation funnels without any local key handling.
 */
internal actual fun LazyListScope.vpnBindSettingsContent(isTablet: Boolean) {
    item {
        val state by remember { VpnBindSettingsController.uiState }.collectAsState()

        LaunchedEffect(Unit) {
            VpnBindSettingsController.refresh()
        }

        val scope = rememberCoroutineScope()

        ProtectionSection(scope, isTablet, state)
        ProfilesSection(scope, isTablet, state)
        BindStateSection(isTablet, scope, state)
    }
}

@Composable
private fun ProtectionSection(
    scope: CoroutineScope,
    isTablet: Boolean,
    state: VpnBindSettingsController.UiState,
) {
    SettingsSection(title = "VPN protection", isTablet = isTablet) {
        if (state.mode != VpnBindController.Mode.OFF && state.target != null) {
            val interfaceName = state.target!!.interfaceName
            SettingsSectionNote(
                text = when {
                    state.verified -> "Bound to $interfaceName and verified. New connections leave only through it."
                    state.detail != null -> state.detail
                    else -> "Protection is on but unverified: traffic is refused (BLOCK) rather than leaking."
                },
                isTablet = isTablet,
            )
        }
        // One choice row, not four switches: the mode is a single value, and a compact page fits
        // a 135%-scaled desktop without scrolling.
        SettingsGroup(isTablet = isTablet) {
            SettingsChoiceRow(
                title = "VPN protection",
                description = "Off, Streams, Streams and P2P, or Full app — an enabled mode always fails closed.",
                options = listOf(
                    SettingsChoiceOption(VpnBindController.Mode.OFF, "Off"),
                    SettingsChoiceOption(VpnBindController.Mode.STREAMS, "Streams"),
                    SettingsChoiceOption(VpnBindController.Mode.STREAMS_AND_P2P, "Streams and P2P"),
                    SettingsChoiceOption(VpnBindController.Mode.FULL_APP, "Full app"),
                ),
                selectedValue = state.mode,
                isTablet = isTablet,
                onSelected = { mode -> scope.launch { VpnBindSettingsController.setMode(mode) } },
            )
        }
        val iptvProxyNote = iptvProxyCombinationNote(state)
        if (iptvProxyNote != null) {
            SettingsSectionNote(text = iptvProxyNote, isTablet = isTablet)
        }
        SettingsSectionNote(
            text = "Foreground activation, like the TV forks: it protects while the app runs, not the rest " +
                "of the system, and an on-but-unverified mode refuses traffic instead of leaking.",
            isTablet = isTablet,
        )
    }
}

@Composable
private fun iptvProxyCombinationNote(state: VpnBindSettingsController.UiState): String? {
    val iptvSettings by IptvRepository.settings.collectAsState()
    if (state.mode != VpnBindController.Mode.OFF && iptvSettings.proxy.enabled) {
        return "Your Live TV proxy is on too: the VPN bind takes precedence, so protected streams connect " +
            "bound straight to origin and bypass the Live TV proxy. Chaining the two is not built yet."
    }
    return null
}

@Composable
private fun ProfilesSection(
    scope: CoroutineScope,
    isTablet: Boolean,
    state: VpnBindSettingsController.UiState,
) {
    SettingsSection(
        title = "Profiles on this system",
        isTablet = isTablet,
        subtitle = if (state.profiles.isEmpty() && !state.refreshing) {
            "No VPN profiles found via NetworkManager or /etc/wireguard."
        } else {
            null
        },
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsActionRow(
                title = "Refresh",
                description = "Re-read NetworkManager connections and live interfaces.",
                isTablet = isTablet,
            ) {
                SettingsGlassButton(
                    text = if (state.refreshing) "Reading…" else "Refresh",
                    onClick = { scope.launch { VpnBindSettingsController.refresh() } },
                )
            }
            state.profiles.forEachIndexed { index, profile ->
                SettingsGroupDivider(isTablet = isTablet)
                ProfileRow(isTablet, scope, profile, state)
            }
        }
        state.lastActionMessage?.let { message ->
            SettingsSectionNote(text = message, isTablet = isTablet)
        }
    }
}

@Composable
private fun ProfileRow(
    isTablet: Boolean,
    scope: CoroutineScope,
    profile: VpnProfile,
    state: VpnBindSettingsController.UiState,
) {
    val kind = when (profile.kind) {
        VpnProfileKind.WIREGUARD -> "WireGuard"
        VpnProfileKind.VPN_PLUGIN -> "VPN"
        VpnProfileKind.EXTERNAL_TUN -> "External tunnel"
        VpnProfileKind.WG_QUICK -> "wg-quick"
        VpnProfileKind.UNKNOWN -> "VPN"
    }
    val boundHere = state.target?.interfaceName == profile.device && state.mode != VpnBindController.Mode.OFF
    SettingsActionRow(
        title = profile.name,
        description = when {
            profile.device != null && boundHere -> "$kind · active on ${profile.device} · this is the bind target"
            profile.device != null -> "$kind · active on ${profile.device}"
            profile.externallyManaged -> "$kind · managed by the system, not activatable here"
            else -> "$kind · available"
        },
        isTablet = isTablet,
    ) {
        when {
            profile.device != null -> {
                SettingsGlassButton(
                    text = if (boundHere) "Bound" else "Bind",
                    onClick = {
                        if (!boundHere) {
                            scope.launch { VpnBindSettingsController.useProfile(profile) }
                        }
                    },
                )
            }
            profile.uuid != null -> {
                SettingsGlassButton(
                    text = if (state.activatingProfile == profile) "Activating…" else "Activate",
                    onClick = { scope.launch { VpnBindSettingsController.activate(profile) } },
                )
            }
        }
    }
}

@Composable
private fun BindStateSection(
    isTablet: Boolean,
    scope: CoroutineScope,
    state: VpnBindSettingsController.UiState,
) {
    SettingsSection(title = "Bind health", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsActionRow(
                title = "Bind availability",
                description = when (state.availability) {
                    VpnBindAvailability.AVAILABLE -> "The kernel accepts unprivileged interface binds."
                    VpnBindAvailability.KERNEL_UNSUPPORTED -> "This kernel refuses unprivileged SO_BINDTODEVICE (needs Linux ≥ 5.7)."
                    VpnBindAvailability.LIBRARY_UNAVAILABLE -> "The native bridge is unavailable: ${state.availability.detail ?: "unknown"}."
                    null -> "Checked when protection is first enabled."
                },
                isTablet = isTablet,
            ) {
                // A control of its own, so the section is a focus stop the gamepad can reach —
                // and scroll to — rather than passive text below the last profile row.
                SettingsGlassButton(
                    text = "Recheck",
                    loading = state.refreshing,
                    onClick = { scope.launch { VpnBindSettingsController.recheck() } },
                )
            }
        }
        SettingsSectionNote(
            text = "Fail-closed contract: an enabled mode never falls back to direct traffic. Local playback " +
                "proxies stay local, and what an enabled mode does not cover is listed in docs/vpn-bind-design.md §6.",
            isTablet = isTablet,
        )
    }
}
