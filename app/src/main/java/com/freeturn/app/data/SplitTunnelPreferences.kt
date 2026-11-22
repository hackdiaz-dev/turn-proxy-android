package com.freeturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.freeturn.app.data.AppPreferences.Companion.ACTIVE_SERVER_ID
import com.freeturn.app.data.AppPreferences.Companion.SERVERS_JSON
import com.freeturn.app.data.AppPreferences.Companion.SPLIT_SHARED_APPS
import com.freeturn.app.data.AppPreferences.Companion.SPLIT_SHARED_MODE
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson

/** Read effective rules from the same snapshot as the active profile. */
internal fun Preferences.activeSplitClient(): ClientConfig {
    val own = ServerJson.decodeList(this[SERVERS_JSON])
        .firstOrNull { it.id == this[ACTIVE_SERVER_ID] }?.client ?: return ClientConfig()
    val mode = this[SPLIT_SHARED_MODE]
    return if (own.splitTunnelShared && mode != null) own.copy(
        splitTunnelMode = mode, splitTunnelApps = this[SPLIT_SHARED_APPS].orEmpty()
    ) else own
}

private fun MutablePreferences.editActiveSplit(transform: (Server) -> Server) {
    val activeId = this[ACTIVE_SERVER_ID] ?: return
    val list = ServerJson.decodeListOrNull(this[SERVERS_JSON]) ?: return
    if (list.none { it.id == activeId }) return
    this[SERVERS_JSON] = ServerJson.encodeList(list.map { if (it.id == activeId) transform(it) else it })
}

/** Called only inside DataStore.edit: never merge with a pre-transaction snapshot. */
internal fun MutablePreferences.updateSplitTunnelRule(mode: String?, apps: String?) {
    editActiveSplit { server ->
        if (server.client.splitTunnelShared) {
            this[SPLIT_SHARED_MODE] = mode ?: this[SPLIT_SHARED_MODE] ?: server.client.splitTunnelMode
            this[SPLIT_SHARED_APPS] = apps ?: this[SPLIT_SHARED_APPS] ?: server.client.splitTunnelApps
            server
        } else server.copy(client = server.client.copy(
            splitTunnelMode = mode ?: server.client.splitTunnelMode,
            splitTunnelApps = apps ?: server.client.splitTunnelApps
        ))
    }
}

internal fun MutablePreferences.updateSplitShared(enabled: Boolean) {
    editActiveSplit { server ->
        val own = server.client
        if (own.splitTunnelShared == enabled) return@editActiveSplit server
        if (enabled) {
            if (this[SPLIT_SHARED_MODE] == null) {
                this[SPLIT_SHARED_MODE] = own.splitTunnelMode
                this[SPLIT_SHARED_APPS] = own.splitTunnelApps
            }
            server.copy(client = own.copy(splitTunnelShared = true))
        } else server.copy(client = own.copy(
            splitTunnelShared = false,
            splitTunnelMode = this[SPLIT_SHARED_MODE] ?: own.splitTunnelMode,
            splitTunnelApps = this[SPLIT_SHARED_APPS] ?: own.splitTunnelApps
        ))
    }
}
