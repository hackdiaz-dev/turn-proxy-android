package com.freeturn.app.domain.proxy

import com.freeturn.app.data.config.Socks5Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * SOCKS5 (RFC 1928) для раздачи туннеля наружу - через точку доступа или по локальной
 * сети. CONNECT и (по настройке) UDP ASSOCIATE: без него у клиентов нет QUIC и
 * UDP-DNS - им нужен remote DNS через сам прокси.
 *
 * Имеет смысл только в туннельном режиме. Сокеты к цели намеренно НЕ выводятся из
 * VPN - именно они и должны уйти в tun; наружу выводится обратный канал к клиенту
 * ([protect]), иначе ответы в локальную сеть уехали бы в туннель.
 *
 * Слушает 0.0.0.0, по умолчанию без авторизации: открыт всей локальной сети, не только клиентам
 * точки доступа. Цели на самом телефоне (loopback) закрыты.
 *
 * Одноразовый: после [stop] экземпляр не перезапускается.
 */
class Socks5Server(
    private val protect: (Socket) -> Boolean,
    private val log: ProxyLog,
    private val config: Socks5Config = Socks5Config(),
    private val protectUdp: (DatagramSocket) -> Boolean = { true },
) {
    private val port = config.port
    private val modeLabel: String
        get() = (if (config.udp) "TCP+UDP" else "только TCP") +
            (if (config.authEnabled) ", с паролем" else ", без пароля")

    private val executor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    // Потоков и fd на клиента по два: без предела раздачу, открытую всей LAN, выедает любой.
    private val clients = AtomicInteger(0)

    // Слушающий сокет - под монитором: bind идёт на потоке вызывающего, чтобы
    // BindException был виден ему, а не утонул в корутине.
    @Volatile private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = try {
            ServerSocket(port, BACKLOG, InetAddress.getByName(BIND_ADDRESS))
        } catch (e: Exception) {
            log.add("SOCKS5: не поднялся на $BIND_ADDRESS:$port - ${e.message}", LogLevel.Error)
            return
        }
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        log.add("SOCKS5: раздача туннеля на $BIND_ADDRESS:$port ($modeLabel)")
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        socket.closeQuietly()
        sockets.forEach { it.closeQuietly() }
        sockets.clear()
        scope.cancel()
        executor.shutdown()
        log.add("SOCKS5: раздача остановлена")
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (coroutineContext.isActive) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                // Закрытый из stop() сокет - штатный выход, о нём молчим.
                if (serverSocket != null) {
                    log.add("SOCKS5: приём прерван - ${e.message}", LogLevel.Warning)
                }
                return
            }
            if (clients.incrementAndGet() > MAX_CLIENTS) {
                clients.decrementAndGet()
                client.closeQuietly()
                continue
            }

            track(client)
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) = coroutineScope {
        var target: Socket? = null
        try {
            // Обратный канал - мимо туннеля: приложение теперь внутри tun, и ответы
            // клиенту в локальную сеть без этого ушли бы в туннель. Отказ - сброс, а не
            // обслуживание в обход.
            if (!protect(client)) {
                log.add("SOCKS5: protect отклонён - клиент сброшен", LogLevel.Warning)
                return@coroutineScope
            }
            client.soTimeout = HANDSHAKE_TIMEOUT_MS

            val input = client.getInputStream()
            val output = client.getOutputStream()

            if (!negotiate(input, output)) return@coroutineScope

            if (input.readByte() != VERSION) return@coroutineScope
            val command = input.readByte()
            input.readByte() // RSV
            val addressType = input.readByte()

            val isUdp = command == CMD_UDP_ASSOCIATE && config.udp
            if (command != CMD_CONNECT && !isUdp) {
                sendReply(output, REPLY_COMMAND_NOT_SUPPORTED)
                return@coroutineScope
            }
            // Длину неизвестного типа адреса не угадать - дочитать до порта нечем,
            // поэтому соединение после ответа закрывается.
            val host = readHost(input, addressType) ?: run {
                sendReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                return@coroutineScope
            }
            if (isUdp) {
                readPort(input)
                handleUdp(client, input, output)
                return@coroutineScope
            }
            val address = InetSocketAddress(host, readPort(input))
            val ip = address.address
            if (ip != null && (ip.isLoopbackAddress || ip.isAnyLocalAddress)) {
                sendReply(output, REPLY_NOT_ALLOWED)
                return@coroutineScope
            }

            val socket = Socket()
            target = socket
            track(socket)
            try {
                socket.connect(address, CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                sendReply(output, replyFor(e))
                return@coroutineScope
            }
            sendReply(output, REPLY_SUCCESS)
            client.soTimeout = 0

            val upstream = launch { pipe(input, socket.getOutputStream(), socket) }
            val downstream = launch { pipe(socket.getInputStream(), output, client) }
            upstream.join()
            downstream.join()
        } catch (_: EOFException) {
        } catch (_: Exception) {
        } finally {
            client.closeQuietly()
            target?.closeQuietly()
            sockets.remove(client)
            target?.let(sockets::remove)
            clients.decrementAndGet()
        }
    }

    /**
     * UDP ASSOCIATE (RFC 1928, п. 7). Релей живёт, пока открыто управляющее
     * TCP-соединение. Датаграммы принимаются только с IP клиента, фрагменты
     * отбрасываются. Сокет к клиенту выведен из туннеля ([protectUdp]), сокет к
     * целям - нет: именно он должен уйти в tun.
     */
    private suspend fun handleUdp(
        client: Socket,
        input: InputStream,
        output: OutputStream,
    ) = coroutineScope {
        val clientIp = client.inetAddress
        val local = client.localAddress
        val relay = DatagramSocket(InetSocketAddress(local, 0))
        val outbound = DatagramSocket()
        try {
            if (!protectUdp(relay)) {
                log.add("SOCKS5: protect UDP отклонён", LogLevel.Warning)
                sendReply(output, REPLY_GENERAL_FAILURE)
                return@coroutineScope
            }
            val reply = Socks5Udp.header(local, relay.localPort)
            reply[0] = VERSION.toByte()
            output.write(reply)
            output.flush()
            client.soTimeout = 0
            val clientPort = AtomicInteger(0)
            launch { relayUp(relay, outbound, clientIp, clientPort) }
            launch { relayDown(relay, outbound, clientIp, clientPort) }
            // Конец управляющего соединения закрывает ассоциацию.
            while (input.read() != -1) {
                // данные игнорируются
            }
        } finally {
            relay.close()
            outbound.close()
        }
    }

    private fun relayUp(
        relay: DatagramSocket,
        outbound: DatagramSocket,
        clientIp: InetAddress,
        clientPort: AtomicInteger,
    ) {
        val buf = ByteArray(UDP_BUFFER)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (true) {
                packet.length = buf.size
                relay.receive(packet)
                if (packet.address != clientIp) continue
                clientPort.set(packet.port)
                val head = Socks5Udp.parse(buf, packet.length) ?: continue
                val ip = try {
                    InetAddress.getByName(head.host)
                } catch (_: Exception) {
                    continue
                }
                if (ip.isLoopbackAddress || ip.isAnyLocalAddress) continue
                val size = packet.length - head.dataOffset
                outbound.send(DatagramPacket(buf, head.dataOffset, size, ip, head.port))
            }
        } catch (_: Exception) {
            // сокет закрыт - штатный выход
        }
    }

    private fun relayDown(
        relay: DatagramSocket,
        outbound: DatagramSocket,
        clientIp: InetAddress,
        clientPort: AtomicInteger,
    ) {
        val buf = ByteArray(UDP_BUFFER)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (true) {
                packet.length = buf.size
                outbound.receive(packet)
                val port = clientPort.get()
                if (port == 0) continue
                val head = Socks5Udp.header(packet.address, packet.port)
                val out = head + buf.copyOfRange(0, packet.length)
                relay.send(DatagramPacket(out, out.size, clientIp, port))
            }
        } catch (_: Exception) {
            // сокет закрыт - штатный выход
        }
    }

    private fun track(socket: Socket) {
        sockets.add(socket)
        if (serverSocket == null) socket.closeQuietly()
    }

    /** false - нет подходящего метода, неверный пароль либо не по протоколу. */
    private fun negotiate(input: InputStream, output: OutputStream): Boolean {
        if (input.readByte() != VERSION) return false
        val methodCount = input.readByte()
        if (methodCount <= 0) return false
        val methods = input.readExactly(methodCount)
        val wanted = if (config.authEnabled) METHOD_USER_PASS else METHOD_NO_AUTH
        if (methods.none { it.toInt() and 0xFF == wanted }) {
            output.writeBytes(VERSION, METHOD_NONE_ACCEPTABLE)
            return false
        }
        output.writeBytes(VERSION, wanted)
        return !config.authEnabled || authenticate(input, output)
    }

    /** RFC 1929: VER=1, ULEN, UNAME, PLEN, PASSWD. Сравнение за постоянное время. */
    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        if (input.readByte() != AUTH_VERSION) return false
        val user = input.readExactly(input.readByte())
        val pass = input.readExactly(input.readByte())
        val userOk = MessageDigest.isEqual(user, config.user.toByteArray(Charsets.UTF_8))
        val passOk = MessageDigest.isEqual(pass, config.pass.toByteArray(Charsets.UTF_8))
        val ok = userOk and passOk
        output.writeBytes(AUTH_VERSION, if (ok) 0 else 1)
        if (!ok) Thread.sleep(AUTH_FAIL_DELAY_MS)
        return ok
    }

    private fun readHost(input: InputStream, addressType: Int): String? = when (addressType) {
        ATYP_IPV4 -> InetAddress.getByAddress(input.readExactly(4)).hostAddress
        ATYP_IPV6 -> InetAddress.getByAddress(input.readExactly(16)).hostAddress
        ATYP_DOMAIN -> {
            val length = input.readByte()
            // Имя хоста в SOCKS5 - ASCII; платформенная кодировка ломала бы IDN-punycode.
            if (length <= 0) null else String(input.readExactly(length), StandardCharsets.US_ASCII)
        }
        else -> null
    }

    private fun readPort(input: InputStream): Int {
        val bytes = input.readExactly(2)
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun sendReply(output: OutputStream, reply: Int) {
        // VER REP RSV ATYP=IPv4 BND.ADDR(4) BND.PORT(2). Привязку не сообщаем -
        // для CONNECT клиенты её не используют.
        output.writeBytes(VERSION, reply, 0, ATYP_IPV4, 0, 0, 0, 0, 0, 0)
    }

    private fun replyFor(e: Exception): Int = when (e) {
        is ConnectException -> REPLY_CONNECTION_REFUSED
        is NoRouteToHostException -> REPLY_HOST_UNREACHABLE
        is UnknownHostException -> REPLY_HOST_UNREACHABLE
        is SocketTimeoutException -> REPLY_HOST_UNREACHABLE
        else -> REPLY_GENERAL_FAILURE
    }

    /**
     * Половина дуплекса. По концу источника закрывает [sink] на запись: без FIN
     * встречная сторона держала бы соединение открытым, а обе `join` не возвращались бы
     * до таймаута где-то в сети.
     */
    private fun pipe(source: InputStream, destination: OutputStream, sink: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                destination.write(buffer, 0, read)
                destination.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { sink.shutdownOutput() } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_PORT = 1080

        private const val BIND_ADDRESS = "0.0.0.0"
        private const val BACKLOG = 50
        private const val MAX_CLIENTS = 64
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000

        private const val VERSION = 5
        private const val METHOD_NO_AUTH = 0x00
        private const val METHOD_USER_PASS = 0x02
        private const val AUTH_VERSION = 1
        private const val AUTH_FAIL_DELAY_MS = 500L
        private const val UDP_BUFFER = 65535
        private const val METHOD_NONE_ACCEPTABLE = 0xFF
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04

        private const val REPLY_SUCCESS = 0x00
        private const val REPLY_GENERAL_FAILURE = 0x01
        private const val REPLY_NOT_ALLOWED = 0x02
        private const val REPLY_HOST_UNREACHABLE = 0x04
        private const val REPLY_CONNECTION_REFUSED = 0x05
        private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
    }
}

private fun Socket.closeQuietly() {
    try { close() } catch (_: Exception) {}
}

private fun ServerSocket.closeQuietly() {
    try { close() } catch (_: Exception) {}
}

private fun InputStream.readByte(): Int = read().also { if (it == -1) throw EOFException() }

private fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        if (read == -1) throw EOFException()
        offset += read
    }
    return bytes
}

private fun OutputStream.writeBytes(vararg values: Int) {
    write(ByteArray(values.size) { values[it].toByte() })
    flush()
}

/** Заголовок SOCKS5 UDP (RFC 1928, п. 7): RSV(2) FRAG ATYP ADDR PORT DATA. */
internal object Socks5Udp {
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4

    class Header(val host: String, val port: Int, val dataOffset: Int)

    /** null - фрагмент, обрезанный пакет или неизвестный тип адреса. */
    fun parse(buf: ByteArray, length: Int): Header? {
        if (length < 4 || buf[2].toInt() != 0) return null
        var pos = 4
        val atyp = buf[3].toInt() and 0xFF
        val host: String = when (atyp) {
            ATYP_IPV4, ATYP_IPV6 -> {
                val size = if (atyp == ATYP_IPV4) 4 else 16
                if (length < pos + size + 2) return null
                val addr = InetAddress.getByAddress(buf.copyOfRange(pos, pos + size))
                pos += size
                addr.hostAddress ?: return null
            }
            ATYP_DOMAIN -> {
                if (length < pos + 1) return null
                val n = buf[pos].toInt() and 0xFF
                pos += 1
                if (n == 0 || length < pos + n + 2) return null
                val name = String(buf, pos, n, StandardCharsets.US_ASCII)
                pos += n
                name
            }
            else -> return null
        }
        val port = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        return Header(host, port, pos + 2)
    }

    /** Заголовок ответа: 0 0 0 ATYP ADDR PORT (первый байт для TCP-ответа - версия). */
    fun header(source: InetAddress, port: Int): ByteArray {
        val addr = source.address
        val out = ByteArray(6 + addr.size)
        out[3] = (if (addr.size == 4) ATYP_IPV4 else ATYP_IPV6).toByte()
        addr.copyInto(out, 4)
        out[4 + addr.size] = (port shr 8).toByte()
        out[5 + addr.size] = port.toByte()
        return out
    }
}
