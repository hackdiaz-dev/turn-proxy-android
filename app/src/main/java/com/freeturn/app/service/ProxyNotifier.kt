package com.freeturn.app.service

import com.freeturn.app.domain.proxy.TrafficStats
import android.text.format.Formatter
import android.os.SystemClock
import android.os.PowerManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.freeturn.app.R
import com.freeturn.app.data.config.Provider
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStatus
import java.util.Locale

/**
 * Нотификации сервиса: постоянный статус подключения и отдельный алерт ручной
 * капчи. Рисуется целиком из [ProxyStatus] - своего состояния не держит.
 */
class ProxyNotifier(private val service: Service) {

    companion object {
        const val NOTIF_ID_FG = 1
        private const val NOTIF_ID_CAPTCHA = 2
        private const val CHANNEL_PROXY = "ProxyChannel"
        private const val CHANNEL_CAPTCHA = "CaptchaChannel"
        private const val TRAFFIC_NOTIFY_MS = 2_000L

        /**
         * Каналы заводятся один раз за процесс, из [android.app.Application]. В onCreate
         * сервиса эти две транзакции к NotificationManager ложились на главный поток
         * ровно в момент нажатия - вместе с ними в кадр не влезала анимация кнопки.
         */
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROXY,
                    context.getString(R.string.notif_channel_proxy),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CAPTCHA,
                    context.getString(R.string.notif_channel_captcha),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private var shown: ProxyStatus? = null
    private var shownTunnelMode = false
    private var shownProvider: String? = null
    private var captchaShown = false
    private var traffic: TrafficStats? = null
    private var trafficNotifiedAt = 0L

    private val openApp: PendingIntent? by lazy {
        service.packageManager.getLaunchIntentForPackage(service.packageName)?.let {
            PendingIntent.getActivity(service, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private val stopAction: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            service,
            0,
            Intent(service, ProxyReceiver::class.java).setAction(ProxyActions.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Первая нотификация для `startForeground` - до неё сессии ещё нет. */
    fun build(): Notification =
        build(ProxyStatus(phase = ProxyPhase.Connecting), tunnelMode = false, provider = null)

    @Synchronized
    fun update(status: ProxyStatus, tunnelMode: Boolean, provider: String?) {
        if (status.captchaUrl.isNotEmpty()) showCaptcha() else cancelCaptcha()
        if (status.phase != ProxyPhase.Connected || !tunnelMode) traffic = null
        val prev = shown
        if (prev != null && tunnelMode == shownTunnelMode && provider == shownProvider &&
            prev.phase == status.phase && prev.active == status.active && prev.total == status.total
        ) {
            return
        }
        shown = status
        shownTunnelMode = tunnelMode
        shownProvider = provider
        notify(NOTIF_ID_FG, build(status, tunnelMode, provider))
    }

    /** Скорость и трафик в нотификацию: не чаще раза в 2 с и только при включённом экране. */
    @Synchronized
    fun updateTraffic(stats: TrafficStats) {
        val status = shown ?: return
        if (status.phase != ProxyPhase.Connected || !shownTunnelMode) return
        val now = SystemClock.elapsedRealtime()
        if (now - trafficNotifiedAt < TRAFFIC_NOTIFY_MS) return
        if (service.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        traffic = stats
        trafficNotifiedAt = now
        notify(NOTIF_ID_FG, build(status, shownTunnelMode, shownProvider))
    }

    private fun build(status: ProxyStatus, tunnelMode: Boolean, provider: String?): Notification {
        val connected = status.phase == ProxyPhase.Connected
        val title = when {
            connected && tunnelMode -> service.getString(R.string.tunnel_active)
            connected -> service.getString(R.string.proxy_active)
            status.phase == ProxyPhase.Error -> service.getString(R.string.notif_proxy_connect_error)
            else -> service.getString(R.string.notif_proxy_connecting)
        }
        // direct - всегда один поток: счётчик там ничего не сообщает.
        val streams = streamsText(status)?.takeIf { connected && provider != Provider.DIRECT }
        val details = listOfNotNull(providerText(provider), streams)
            .joinToString(" · ")
            .ifEmpty { service.getString(R.string.notif_proxy_title) }
        val stats = traffic?.takeIf { connected && tunnelMode }
        val speed = stats?.let {
            service.getString(R.string.traffic_speed, size(it.rxRate), size(it.txRate))
        }
        val total = stats?.let {
            service.getString(R.string.traffic_total, size(it.rxTotal), size(it.txTotal))
        }
        val builder = NotificationCompat.Builder(service, CHANNEL_PROXY)
            .setContentTitle(title)
            .setContentText(listOfNotNull(speed, details).joinToString(" · "))
            .setSmallIcon(R.drawable.ic_freeturn)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, service.getString(R.string.notif_proxy_stop_action), stopAction)
        if (speed != null && total != null) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(listOf(details, speed, total).joinToString("\n"))
            )
        }
        return builder.build()
    }

    private fun size(bytes: Long): String = Formatter.formatFileSize(service, bytes)

    private fun providerText(provider: String?): String? = when (provider) {
        Provider.RELAY -> service.getString(R.string.provider_relay)
        Provider.DIRECT -> service.getString(R.string.provider_direct)
        else -> null
    }

    private fun streamsText(status: ProxyStatus): String? =
        if (status.total > 0) String.format(
            Locale.US,
            service.getString(R.string.notif_proxy_threads_format),
            status.active,
            status.total
        ) else null

    /** Дедуп: пока предыдущий алерт не закрыт - повторно не шумим. */
    private fun showCaptcha() {
        if (captchaShown) return
        captchaShown = true
        notify(
            NOTIF_ID_CAPTCHA,
            NotificationCompat.Builder(service, CHANNEL_CAPTCHA)
                .setContentTitle(service.getString(R.string.notif_captcha_title))
                .setContentText(service.getString(R.string.notif_captcha_text))
                .setSmallIcon(R.drawable.ic_notification_captcha)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
        )
    }

    fun cancelCaptcha() {
        if (!captchaShown) return
        captchaShown = false
        NotificationManagerCompat.from(service).cancel(NOTIF_ID_CAPTCHA)
    }

    // POST_NOTIFICATIONS могли отозвать: диалог капчи UI покажет и без алерта.
    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(service).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}

