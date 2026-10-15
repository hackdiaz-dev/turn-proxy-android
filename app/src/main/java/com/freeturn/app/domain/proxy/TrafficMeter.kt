package com.freeturn.app.domain.proxy

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Скорость (байт/с) и трафик текущего подключения (байт); rx - принято, tx - отправлено. */
data class TrafficStats(
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
)

/**
 * Скорость и трафик по счётчикам userspace-туннеля ядра. Счётчики ядра накопительные и
 * могут обнулиться при перезапуске туннеля: итог копится из приращений, падение счётчика
 * считается перезапуском. Состояние переживает уход с экрана, поэтому итог не теряется.
 */
class TrafficMeter(
    private val counters: () -> Pair<Long, Long>?,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var session: Long? = null
    private var lastRx = 0L
    private var lastTx = 0L
    private var totalRx = 0L
    private var totalTx = 0L
    private var lastAt = 0L
    private var rateRx = 0.0
    private var rateTx = 0.0

    @Synchronized
    fun sample(connectedSince: Long): TrafficStats? {
        val (rx, tx) = counters() ?: return null
        val at = now()
        if (session != connectedSince) {
            session = connectedSince
            lastRx = 0
            lastTx = 0
            totalRx = 0
            totalTx = 0
            lastAt = 0
            rateRx = 0.0
            rateTx = 0.0
        }
        val dRx = if (rx >= lastRx) rx - lastRx else rx
        val dTx = if (tx >= lastTx) tx - lastTx else tx
        totalRx += dRx
        totalTx += dTx
        lastRx = rx
        lastTx = tx
        val dt = at - lastAt
        if (lastAt != 0L && dt in MIN_INTERVAL_MS..MAX_INTERVAL_MS) {
            rateRx = (rateRx + dRx * 1000.0 / dt) / 2
            rateTx = (rateTx + dTx * 1000.0 / dt) / 2
        } else {
            rateRx = 0.0
            rateTx = 0.0
        }
        lastAt = at
        return TrafficStats(rateRx.toLong(), rateTx.toLong(), totalRx, totalTx)
    }

    /** Замеры раз в секунду, пока подключение с туннелем живо; иначе нули. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(status: Flow<ProxyStatus>): Flow<TrafficStats> = status
        .map { if (it.tunnelUp && it.phase == ProxyPhase.Connected) it.connectedSince else null }
        .distinctUntilChanged()
        .flatMapLatest { since ->
            if (since == null) {
                flowOf(TrafficStats())
            } else {
                flow {
                    while (true) {
                        sample(since)?.let { emit(it) }
                        delay(SAMPLE_INTERVAL_MS)
                    }
                }.flowOn(Dispatchers.Default)
            }
        }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val MIN_INTERVAL_MS = 300L
        const val MAX_INTERVAL_MS = 2_500L
    }
}
