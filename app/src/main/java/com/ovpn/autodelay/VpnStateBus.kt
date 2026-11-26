package com.ovpn.autodelay

import kotlinx.coroutines.flow.MutableStateFlow

enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

object VpnStateBus {
    val connState = MutableStateFlow(ConnState.DISCONNECTED)
    val statusSubtext = MutableStateFlow("⚡ Tap to connect")
    val connectedDelayMs = MutableStateFlow<Long?>(null)
    val activeProfile = MutableStateFlow<OvpnProfileItem?>(null)
    val isFastestMode = MutableStateFlow(true)
    val selectedProfileName = MutableStateFlow<String?>(null)
    val scanHint = MutableStateFlow("Tap Fast Test or pick any country")
    val listVersion = MutableStateFlow(0)

    @Volatile var loadedProfiles: List<OvpnProfileItem> = emptyList()
    @Volatile var username: String = ""
    @Volatile var password: String = ""

    fun notifyListChanged() {
        listVersion.value = listVersion.value + 1
    }
}
