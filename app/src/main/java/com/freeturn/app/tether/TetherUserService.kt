package com.freeturn.app.tether

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Раздача точки доступа через VPN без root. Работает в процессе Shizuku с правами shell
 * (uid 2000): только им доступны тестовые сети ([TEST_NETWORK_SERVICE]) и управление раздачей.
 *
 * Схема:
 *  1. Создаём тестовый TUN и регистрируем его как сеть с маршрутом по умолчанию и DNS туннеля.
 *  2. Просим раздачу предпочитать тестовые сети: Android ведёт трафик клиентов точки доступа
 *     (NAT, DHCP, DNS-прокси - всё системное) в этот TUN.
 *  3. Запускаем helper (tether/, tun2socks): он читает TUN и отдаёт соединения в локальный
 *     SOCKS5 приложения, а тот - через VPN.
 *
 * Скрытые API Android вызываются рефлексией: в процессе Shizuku ограничений на них нет,
 * но в SDK их тоже нет. Каждый шаг пишется в журнал - по нему видно, где споткнулось.
 */
class TetherUserService() : ITetherService.Stub() {

    private var appContext: Context? = null

    /** Shizuku передаёт контекст, если есть такой конструктор. */
    constructor(context: Context) : this() {
        appContext = context
    }

    private val callbacks = HandlerThread("freeturn-tether").apply { start() }
    private val handler = Handler(callbacks.looper)

    private var tun: ParcelFileDescriptor? = null
    private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkToken: Binder? = null
    private var helper: Process? = null
    private var helperSocket: LocalSocket? = null
    private var helperServer: LocalServerSocket? = null
    private var hotspotStartedByUs = false

    override fun destroy() {
        synchronized(this) { stopLocked(stopHotspot = hotspotStartedByUs, log = mutableListOf()) }
        exitProcess(0)
    }

    override fun start(
        helperPath: String,
        proxyUrl: String,
        dnsServers: Array<String>,
        mtu: Int,
        startHotspot: Boolean,
        log: MutableList<String>,
    ): String = synchronized(this) {
        stopLocked(stopHotspot = false, log = log)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "нужен Android 13 или новее"
            val ctx = shellContext()
            val testNetworks = ctx.getSystemService(TEST_NETWORK_SERVICE)
                ?: return "в системе нет TestNetworkManager"
            val tethering = ctx.getSystemService(TETHERING_SERVICE)
                ?: return "в системе нет TetheringManager"

            val address = linkAddress(TUN_ADDRESS)
            val iface = testNetworks.call("createTunInterface", arrayOf(Collection::class.java), listOf(address))
                ?: return "createTunInterface вернул null"
            val pfd = iface.call("getFileDescriptor") as ParcelFileDescriptor
            val name = iface.call("getInterfaceName") as String
            tun = pfd
            log += "TUN $name ($TUN_ADDRESS)"

            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val ready = registerNetwork(cm, testNetworks, name, address, dnsServers, mtu, log)
                ?: return "тестовая сеть не появилась за ${NETWORK_TIMEOUT_S} с"
            network = ready
            log += "сеть $ready готова, DNS ${dnsServers.joinToString()}"

            tethering.call("setPreferTestNetworks", arrayOf(Boolean::class.javaPrimitiveType!!), true)
            log += "раздача предпочитает тестовые сети"

            startHelper(helperPath, proxyUrl, mtu, pfd, log)?.let { return it }

            if (startHotspot) startWifiTethering(tethering, log)?.let { return it }
            ""
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log += "ошибка: ${cause.javaClass.simpleName}: ${cause.message}"
            stopLocked(stopHotspot = false, log = log)
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
    }

    override fun stop(stopHotspot: Boolean, log: MutableList<String>) {
        synchronized(this) { stopLocked(stopHotspot, log) }
    }

    /** Порядок обратный запуску: сначала точка доступа, потом маршрут, потом сеть. */
    private fun stopLocked(stopHotspot: Boolean, log: MutableList<String>) {
        val ctx = runCatching { shellContext() }.getOrNull()
        val tethering = ctx?.getSystemService(TETHERING_SERVICE)
        if (stopHotspot && tethering != null) {
            runCatching { tethering.call("stopTethering", arrayOf(Int::class.javaPrimitiveType!!), TETHERING_WIFI) }
                .onSuccess { log += "точка доступа выключена" }
                .onFailure { log += "stopTethering: ${it.message}" }
            hotspotStartedByUs = false
        }
        helperSocket?.let { runCatching { it.close() } }
        helperSocket = null
        helperServer?.let { runCatching { it.close() } }
        helperServer = null
        helper?.let { proc ->
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroy()
            log += "helper остановлен"
        }
        helper = null
        if (tun != null || network != null) {
            tethering?.let { runCatching { it.call("setPreferTestNetworks", arrayOf(Boolean::class.javaPrimitiveType!!), false) } }
        }
        val cm = ctx?.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        network?.let { net ->
            ctx?.getSystemService(TEST_NETWORK_SERVICE)?.let { tnm ->
                runCatching { tnm.call("teardownTestNetwork", arrayOf(Network::class.java), net) }
            }
            log += "тестовая сеть убрана"
        }
        network = null
        networkToken = null
        tun?.let { runCatching { it.close() } }
        tun = null
    }

    /**
     * Регистрирует TUN как сеть: адрес, маршрут по умолчанию, DNS, MTU. Ждёт, пока система
     * её поднимет. DNS - туннеля: клиентские запросы пойдут в тот же TUN и дальше через VPN.
     */
    private fun registerNetwork(
        cm: ConnectivityManager,
        testNetworks: Any,
        name: String,
        address: LinkAddress,
        dnsServers: Array<String>,
        mtu: Int,
        log: MutableList<String>,
    ): Network? {
        val token = Binder().also { networkToken = it }
        val lp = LinkProperties()
        lp.call("setInterfaceName", arrayOf(String::class.java), name)
        lp.call("addLinkAddress", arrayOf(LinkAddress::class.java), address)
        dnsServers.forEach { lp.call("addDnsServer", arrayOf(InetAddress::class.java), InetAddress.getByName(it)) }
        lp.call("addRoute", arrayOf(Class.forName("android.net.RouteInfo")), defaultRoute(name))
        lp.call("setMtu", arrayOf(Int::class.javaPrimitiveType!!), mtu)

        // С LinkProperties (Android 12+) - сеть сразу с маршрутом и DNS; старый вариант - только имя.
        runCatching {
            testNetworks.call(
                "setupTestNetwork",
                arrayOf(LinkProperties::class.java, Boolean::class.javaPrimitiveType!!, android.os.IBinder::class.java),
                lp, false, token,
            )
        }.onFailure {
            log += "setupTestNetwork(LinkProperties) недоступен (${it.cause?.message ?: it.message}), пробую по имени"
            testNetworks.call("setupTestNetwork", arrayOf(String::class.java, android.os.IBinder::class.java), name, token)
        }

        val found = CountDownLatch(1)
        var result: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(net: Network, props: LinkProperties) {
                if (props.interfaceName == name && result == null) {
                    result = net
                    found.countDown()
                }
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(TRANSPORT_TEST)
            .build()
        cm.requestNetwork(request, callback, handler)
        return if (found.await(NETWORK_TIMEOUT_S, TimeUnit.SECONDS)) result else null
    }

    /**
     * Запускает helper и передаёт ему дескриптор TUN через абстрактный Unix-сокет.
     * Соединение держим открытым: его закрытие - сигнал helper'у остановиться.
     */
    private fun startHelper(
        path: String,
        proxyUrl: String,
        mtu: Int,
        tun: ParcelFileDescriptor,
        log: MutableList<String>,
    ): String? {
        if (!File(path).canExecute()) return "helper недоступен для запуска: $path"
        val socketName = "freeturn-tether-${android.os.Process.myPid()}-${SystemClock.uptimeMillis()}"
        val server = LocalServerSocket(socketName).also { helperServer = it }
        val proc = ProcessBuilder(path, "-socket", socketName, "-proxy", proxyUrl, "-mtu", mtu.toString())
            .redirectErrorStream(true)
            .start()
        helper = proc
        // Вывод helper'а - в журнал процесса (logcat), чтобы не терять ошибки tun2socks.
        thread(isDaemon = true, name = "tether-helper-log") {
            proc.inputStream.bufferedReader().forEachLine { android.util.Log.i("FreeTurnTether", it) }
        }

        var accepted: LocalSocket? = null
        val acceptThread = thread(name = "tether-accept") { accepted = runCatching { server.accept() }.getOrNull() }
        acceptThread.join(HELPER_TIMEOUT_MS)
        val socket = accepted ?: run {
            runCatching { server.close() }
            return if (proc.isAlive) "helper не подключился за ${HELPER_TIMEOUT_MS / 1000} с"
            else "helper завершился с кодом ${proc.exitValue()}"
        }
        socket.setFileDescriptorsForSend(arrayOf(tun.fileDescriptor))
        socket.outputStream.write(1)
        socket.outputStream.flush()
        helperSocket = socket
        log += "helper запущен: $proxyUrl"
        return null
    }

    /** Включает точку доступа Wi-Fi (TetheringManager.startTethering, системный API). */
    private fun startWifiTethering(tethering: Any, log: MutableList<String>): String? {
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val request = builderClass.getMethod("build").invoke(builder)

        val done = CountDownLatch(1)
        var failure: String? = null
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> done.countDown()
                "onTetheringFailed" -> { failure = "код ${args?.firstOrNull()}"; done.countDown() }
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "toString" -> return@newProxyInstance "FreeTurnTetherCallback"
            }
            null
        }
        val executor = Executor { handler.post(it) }
        tethering.call("startTethering", arrayOf(requestClass, Executor::class.java, callbackClass), request, executor, callback)
        if (!done.await(HOTSPOT_TIMEOUT_S, TimeUnit.SECONDS)) {
            log += "точка доступа: нет ответа за $HOTSPOT_TIMEOUT_S с (возможно, уже включена)"
            return null
        }
        failure?.let { return "точку доступа не включить: $it" }
        hotspotStartedByUs = true
        log += "точка доступа включена"
        return null
    }

    /**
     * Контекст от имени shell: системные сервисы проверяют, что пакет вызывающего
     * принадлежит его uid, а наш процесс работает с uid shell.
     */
    private fun shellContext(): Context {
        shell?.let { return it }
        val base = appContext ?: error("Shizuku не передал контекст")
        val ctx = base.createPackageContext(SHELL_PACKAGE, 0)
        // createPackageContext оставляет «источник вызова» (AttributionSource, Android 12+)
        // от пакета приложения - и система отвечает «package ... does not belong to 2000».
        // Подменяем его на shell с uid этого процесса.
        var impl: Context = ctx
        while (impl is android.content.ContextWrapper) impl = impl.baseContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val source = android.content.AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName(SHELL_PACKAGE)
                .build()
            impl.javaClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }.set(impl, source)
        }
        runCatching { impl.javaClass.getDeclaredField("mOpPackageName").apply { isAccessible = true }.set(impl, SHELL_PACKAGE) }
        shell = ctx
        return ctx
    }

    private var shell: Context? = null

    private fun linkAddress(cidr: String): LinkAddress =
        LinkAddress::class.java.getConstructor(String::class.java).newInstance(cidr)

    private fun defaultRoute(iface: String): Any {
        val prefixClass = Class.forName("android.net.IpPrefix")
        val prefix = prefixClass.getConstructor(String::class.java).newInstance("0.0.0.0/0")
        return Class.forName("android.net.RouteInfo")
            .getConstructor(prefixClass, InetAddress::class.java, String::class.java)
            .newInstance(prefix, null, iface)
    }

    private fun Any.call(name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
        javaClass.getMethod(name, *types).invoke(this, *args)

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val TEST_NETWORK_SERVICE = "test_network"
        const val TETHERING_SERVICE = "tethering"
        const val TETHERING_WIFI = 0
        const val TRANSPORT_TEST = 7
        // Документационная сеть (RFC 5737): не пересекается с реальными адресами.
        const val TUN_ADDRESS = "192.0.2.2/24"
        const val NETWORK_TIMEOUT_S = 10L
        const val HOTSPOT_TIMEOUT_S = 10L
        const val HELPER_TIMEOUT_MS = 5000L
    }
}
