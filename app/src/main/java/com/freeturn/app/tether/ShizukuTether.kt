package com.freeturn.app.tether

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.freeturn.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Раздача точки доступа через VPN без root - сторона приложения.
 * Сама работа идёт в [TetherUserService] внутри процесса Shizuku (права shell).
 */
object ShizukuTether {

    /** Почему раздача через Shizuku сейчас недоступна; null - доступна. */
    enum class Unavailable { OldAndroid, NotRunning, NoPermission }

    /** Android 13+: раньше нет предпочтения тестовых сетей для раздачи. */
    val supportedAndroid: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun unavailable(): Unavailable? = when {
        !supportedAndroid -> Unavailable.OldAndroid
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> Unavailable.NotRunning
        Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Unavailable.NoPermission
        else -> null
    }

    /** Запрос разрешения Shizuku; ответ - в [onResult] (true - выдано). */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != PERMISSION_REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, TetherUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("tether")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private var connection: ServiceConnection? = null
    private var service: ITetherService? = null

    /**
     * Поднимает раздачу: трафик клиентов точки доступа пойдёт в SOCKS5 [proxyPort] на
     * 127.0.0.1, а оттуда - через VPN. Возвращает null при успехе или текст ошибки;
     * журнал шагов - в [log].
     */
    suspend fun start(
        context: Context,
        proxyPort: Int,
        dnsServers: List<String>,
        mtu: Int,
        startHotspot: Boolean,
        log: (String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        unavailable()?.let { return@withContext it.name }
        val helper = File(context.applicationInfo.nativeLibraryDir, HELPER_NAME)
        if (!helper.isFile) return@withContext "нет $HELPER_NAME в ${helper.parent}"
        val tether = bind() ?: return@withContext "сервис Shizuku не подключился"
        val steps = mutableListOf<String>()
        val error = runCatching {
            tether.start(helper.path, "socks5://127.0.0.1:$proxyPort", dnsServers.toTypedArray(), mtu, startHotspot, steps)
        }.getOrElse { it.message ?: it.javaClass.simpleName }
        steps.forEach(log)
        error.takeIf { it.isNotEmpty() }
    }

    /** Останавливает раздачу и отпускает сервис Shizuku. */
    suspend fun stop(stopHotspot: Boolean, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        val tether = service ?: return@withContext
        val steps = mutableListOf<String>()
        runCatching { tether.stop(stopHotspot, steps) }.onFailure { log("остановка: ${it.message}") }
        steps.forEach(log)
        connection?.let { conn -> runCatching { Shizuku.unbindUserService(serviceArgs, conn, true) } }
        connection = null
        service = null
    }

    private suspend fun bind(): ITetherService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val ready = CompletableDeferred<ITetherService?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val tether = binder?.takeIf { it.pingBinder() }?.let(ITetherService.Stub::asInterface)
                service = tether
                ready.complete(tether)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        connection = conn
        Shizuku.bindUserService(serviceArgs, conn)
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
    }

    private const val PERMISSION_REQUEST = 4207
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val HELPER_NAME = "libtether.so"
}
