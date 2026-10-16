package com.freeturn.app.viewmodel.proxy

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.freeturn.app.domain.proxy.TrafficStats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.freeturn.app.domain.proxy.PingState
import com.freeturn.app.domain.proxy.PingResult
import com.freeturn.app.domain.proxy.PingMeter
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.proxy.LogEntry
import com.freeturn.app.domain.proxy.ProxyEngine
import com.freeturn.app.domain.proxy.ProxyLog
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.ProxyStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ProxyViewModel(
    private val launcher: ProxyServiceLauncher,
    private val prefs: AppPreferences,
    private val engine: ProxyEngine,
    private val store: ProxyStore,
    private val log: ProxyLog,
    private val pingMeter: PingMeter
) : ViewModel() {

    val status: StateFlow<ProxyStatus> = store.status

    /** Скорость и трафик подключения; опрос идёт только пока экран виден. */
    val traffic: StateFlow<TrafficStats> =
        engine.traffic.stats(store.status)

    private val pingKick = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var lastPing: Long? = null
    private var lastPingAt = 0L

    /**
     * Ping through the tunnel while the screen is visible. Keyed by a
     * debounced "tunnel is up" flag, so short reconnects do not restart it.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val ping: StateFlow<PingState> = store.status
        .map { it.tunnelUp && it.phase == ProxyPhase.Connected }
        .distinctUntilChanged()
        .debounce { up -> if (up) 0L else PING_DOWN_DEBOUNCE_MS }
        .flatMapLatest { up ->
            if (up) {
                pingLoop()
            } else {
                lastPing = null
                flowOf(PingState())
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PingState()
        )

    private fun pingLoop() = flow {
        var failures = 0
        var manual = false
        while (true) {
            val gap = PING_MIN_GAP_MS -
                (SystemClock.elapsedRealtime() - lastPingAt)
            if (gap > 0) {
                emit(PingState(lastPing))
                delay(gap)
            }
            emit(PingState(lastPing, measuring = manual))
            val result = pingMeter.measure()
            lastPingAt = SystemClock.elapsedRealtime()
            when (result) {
                is PingResult.Ok -> {
                    lastPing = result.ms
                    failures = 0
                }
                PingResult.Outside -> lastPing = null
                PingResult.Failed -> {
                    failures++
                    if (failures >= 2) lastPing = null
                }
            }
            emit(PingState(lastPing, outside = result is PingResult.Outside))
            val wait = if (result is PingResult.Ok) {
                PING_OK_MS
            } else {
                (PING_RETRY_MS shl failures.coerceAtMost(4))
                    .coerceAtMost(PING_MAX_RETRY_MS)
            }
            manual = withTimeoutOrNull(wait) { pingKick.first() } != null
        }
    }

    fun refreshPing() {
        pingKick.tryEmit(Unit)
    }
    val logs: StateFlow<List<LogEntry>> = log.lines

    fun start() {
        launcher.start()
    }

    fun stop() {
        launcher.stop()
    }

    /**
     * Возврат приложения на передний план. Живую сессию не трогаем - открытие окна
     * не событие сети, а пинок ядру рециклил бы рабочие аллокации. Мёртвую поднимаем
     * только с явного согласия ([AppPreferences.autoConnectFlow]): намерение переживает
     * смерть процесса и перезагрузку, поэтому без настройки открытие приложения
     * поднимало VPN само и отбирало tun у чужого.
     *
     * [vpnConsent] - проверка согласия на VpnService, зовётся последней: сам
     * `VpnService.prepare()` отзывает разрешение у активного чужого VPN. Без согласия
     * в WG-режиме стартовать нечем, а спрашивать молча, без действия пользователя, нельзя.
     */
    fun onForeground(vpnConsent: () -> Boolean) {
        if (store.status.value.phase != ProxyPhase.Idle) return
        viewModelScope.launch {
            if (!prefs.autoConnectFlow.first()) return@launch
            if (!prefs.proxyDesiredFlow.first()) return@launch
            val tunnel = prefs.clientConfigFlow.first().wireGuardActive
            // Sticky-старт мог занять сессию, пока читали DataStore.
            if (engine.isRunning || store.status.value.phase != ProxyPhase.Idle) return@launch
            if (tunnel && !vpnConsent()) return@launch
            launcher.start()
        }
    }

    /** Окно закрыл пользователь; ядро ждёт решения дальше и выдаст новую капчу. */
    fun dismissCaptcha() = store.setCaptcha("")

    /** С экрана чистим и файл: сохранять историю против воли пользователя незачем. */
    fun clearLogs() = log.clearAll()

    /** false - отправлять нечего. */
    suspend fun exportLogs(target: File): Boolean = log.export(target)
}

private const val PING_OK_MS = 10_000L
private const val PING_RETRY_MS = 3_000L
private const val PING_MIN_GAP_MS = 2_000L
private const val PING_MAX_RETRY_MS = 30_000L
private const val PING_DOWN_DEBOUNCE_MS = 2_000L
