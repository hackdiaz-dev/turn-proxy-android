package com.freeturn.app.domain.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

sealed interface PingResult {
    data class Ok(val ms: Long) : PingResult
    data object Failed : PingResult

    /** Приложение вне туннеля (split tunneling): замер мимо него. */
    data object Outside : PingResult
}

data class PingState(
    val ms: Long? = null,
    val measuring: Boolean = false,
    val outside: Boolean = false,
)

/**
 * Пинг через туннель: время TCP-соединения с публичным адресом.
 * Если активная сеть приложения не VPN, оно вне туннеля.
 */
class PingMeter(context: Context) {
    private val cm: ConnectivityManager? = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    suspend fun measure(): PingResult = withContext(Dispatchers.IO) {
        val manager = cm ?: return@withContext PingResult.Failed
        val net = manager.activeNetwork
            ?: return@withContext PingResult.Failed
        val caps = manager.getNetworkCapabilities(net)
            ?: return@withContext PingResult.Failed
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return@withContext PingResult.Outside
        }
        for (host in TARGETS) {
            val socket = try {
                net.socketFactory.createSocket()
            } catch (_: Exception) {
                continue
            }
            try {
                val t0 = SystemClock.elapsedRealtimeNanos()
                socket.connect(InetSocketAddress(host, PORT), TIMEOUT_MS)
                val dt = SystemClock.elapsedRealtimeNanos() - t0
                return@withContext PingResult.Ok(dt / 1_000_000)
            } catch (_: Exception) {
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
        PingResult.Failed
    }

    private companion object {
        val TARGETS = listOf("1.1.1.1", "8.8.8.8")
        const val PORT = 443
        const val TIMEOUT_MS = 3_000
    }
}
