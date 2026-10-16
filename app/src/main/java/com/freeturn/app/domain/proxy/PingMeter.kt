package com.freeturn.app.domain.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed interface PingResult {
    data class Ok(val ms: Long) : PingResult
    data object Failed : PingResult
    data object Outside : PingResult
}

data class PingState(
    val ms: Long? = null,
    val measuring: Boolean = false,
    val outside: Boolean = false,
)

/**
 * Delay of an HTTP request through the tunnel (generate_204).
 * Outside = app traffic bypasses the VPN (split tunneling).
 */
class PingMeter(context: Context) {
    private val cm: ConnectivityManager? = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var lastOkAt = 0L

    suspend fun measure(): PingResult = withContext(Dispatchers.IO) {
        val manager = cm ?: return@withContext PingResult.Failed
        val net = manager.activeNetwork
            ?: return@withContext PingResult.Failed
        val caps = manager.getNetworkCapabilities(net)
            ?: return@withContext PingResult.Failed
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return@withContext PingResult.Outside
        }
        for (url in TARGETS) {
            // warm-up: DNS, TCP and TLS handshakes are not latency
            if (stale() && probe(net, url) == null) continue
            val ms = probe(net, url) ?: continue
            lastOkAt = SystemClock.elapsedRealtime()
            return@withContext PingResult.Ok(ms)
        }
        PingResult.Failed
    }

    private fun stale() =
        SystemClock.elapsedRealtime() - lastOkAt > STALE_MS

    private fun probe(net: Network, url: String): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = net.openConnection(URL(url)) as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val t0 = SystemClock.elapsedRealtimeNanos()
            if (conn.responseCode < 0) return null
            val dt = SystemClock.elapsedRealtimeNanos() - t0
            try { conn.inputStream.close() } catch (_: Exception) {}
            dt / 1_000_000
        } catch (_: Exception) {
            conn?.disconnect()
            null
        }
    }

    private companion object {
        val TARGETS = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        const val TIMEOUT_MS = 3_000
        const val STALE_MS = 60_000L
    }
}
