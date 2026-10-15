package com.freeturn.app.viewmodel.proxy

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import com.freeturn.app.domain.proxy.TrafficStats
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    /** Пинг через туннель, пока экран виден и подключение живо. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val ping: StateFlow<PingState> = store.status
        .map { st ->
            if (st.tunnelUp && st.phase == ProxyPhase.Connected) {
                st.connectedSince
            } else null
        }
        .distinctUntilChanged()
        .flatMapLatest { since ->
            if (since == null) flowOf(PingState()) else pingLoop()
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PingState()
        )

    private fun pingLoop() = flow {
        var last: Long? = null
        while (true) {
            emit(PingState(last, measuring = true))
            val result = pingMeter.measure()
            last = (result as? PingResult.Ok)?.ms
            emit(PingState(last, outside = result is PingResult.Outside))
            val wait =
                if (result is PingResult.Ok) PING_OK_MS else PING_RETRY_MS
            withTimeoutOrNull(wait) { pingKick.first() }
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
