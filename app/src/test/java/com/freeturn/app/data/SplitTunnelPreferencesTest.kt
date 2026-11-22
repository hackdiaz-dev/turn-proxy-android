package com.freeturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.freeturn.app.data.AppPreferences.Companion.ACTIVE_SERVER_ID
import com.freeturn.app.data.AppPreferences.Companion.SERVERS_JSON
import com.freeturn.app.data.AppPreferences.Companion.SPLIT_SHARED_APPS
import com.freeturn.app.data.AppPreferences.Companion.SPLIT_SHARED_MODE
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import org.junit.Assert.*
import org.junit.Test

class SplitTunnelPreferencesTest {
    private val a = Server(id = "a", name = "a", client = ClientConfig(
        splitTunnelMode = SplitTunnelMode.INCLUDE, splitTunnelApps = "app.a"))
    private val b = Server(id = "b", name = "b", client = ClientConfig(
        splitTunnelMode = SplitTunnelMode.EXCLUDE, splitTunnelApps = "app.b"))
    private fun prefs() = mutablePreferencesOf(
        SERVERS_JSON to ServerJson.encodeList(listOf(a, b)), ACTIVE_SERVER_ID to a.id)

    @Test fun `joining uses existing shared mode and apps together`() {
        val p = prefs()
        p.updateSplitShared(true)
        p[ACTIVE_SERVER_ID] = b.id
        p.updateSplitShared(true)
        assertEquals(SplitTunnelMode.INCLUDE, p.activeSplitClient().splitTunnelMode)
        assertEquals("app.a", p.activeSplitClient().splitTunnelApps)
        assertTrue(ServerJson.decodeList(p[SERVERS_JSON]).all { it.client.splitTunnelShared })
    }

    @Test fun `partial shared edits preserve preceding edits`() {
        val p = prefs()
        p.updateSplitShared(true)
        p.updateSplitTunnelRule(SplitTunnelMode.EXCLUDE, null)
        p.updateSplitTunnelRule(null, "app.new")
        assertEquals(SplitTunnelMode.EXCLUDE, p[SPLIT_SHARED_MODE])
        assertEquals("app.new", p[SPLIT_SHARED_APPS])
        p.updateSplitShared(false)
        assertEquals(SplitTunnelMode.EXCLUDE, p.activeSplitClient().splitTunnelMode)
        assertEquals("app.new", p.activeSplitClient().splitTunnelApps)
        assertFalse(p.activeSplitClient().splitTunnelShared)
    }

    @Test fun `leaving freezes private rules and does not change other profiles`() {
        val p = prefs()
        p.updateSplitShared(true)
        p[ACTIVE_SERVER_ID] = b.id
        p.updateSplitShared(true)
        p.updateSplitShared(false)
        p[ACTIVE_SERVER_ID] = a.id
        p.updateSplitTunnelRule(null, "app.changed")
        p[ACTIVE_SERVER_ID] = b.id
        assertEquals("app.a", p.activeSplitClient().splitTunnelApps)
        p.updateSplitShared(false) // duplicate callback must not overwrite private rules
        assertEquals("app.a", p.activeSplitClient().splitTunnelApps)
        p.updateSplitTunnelRule(null, "app.private")
        p[ACTIVE_SERVER_ID] = a.id
        assertEquals("app.changed", p.activeSplitClient().splitTunnelApps)
    }

    @Test fun `private edits do not touch shared rules`() {
        val p = prefs()
        p.updateSplitShared(true)
        p[ACTIVE_SERVER_ID] = b.id
        p.updateSplitTunnelRule(null, "app.private")
        assertEquals("app.a", p[SPLIT_SHARED_APPS])
        assertEquals("app.private", p.activeSplitClient().splitTunnelApps)
        assertFalse(p.activeSplitClient().splitTunnelShared)
    }

    @Test fun `missing active profile and corrupted list do not mutate shared rules`() {
        val p = prefs()
        p.updateSplitShared(true)
        p[ACTIVE_SERVER_ID] = "missing"
        val before = p.toPreferences()
        p.updateSplitShared(false)
        p.updateSplitTunnelRule(null, "wrong")
        assertEquals(before, p)
        p[SERVERS_JSON] = "broken"
        p[ACTIVE_SERVER_ID] = a.id
        val broken = p.toPreferences()
        p.updateSplitShared(true)
        p.updateSplitTunnelRule(SplitTunnelMode.ALL, null)
        assertEquals(broken, p)
    }
}
