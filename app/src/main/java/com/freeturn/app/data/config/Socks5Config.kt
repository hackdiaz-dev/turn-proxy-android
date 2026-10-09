package com.freeturn.app.data.config

/** Параметры SOCKS5-раздачи. Пустой [user] - без авторизации. */
data class Socks5Config(
    val port: Int = 1080,
    val user: String = "",
    val pass: String = "",
    val udp: Boolean = false,
) {
    val authEnabled: Boolean get() = user.isNotEmpty()
}
