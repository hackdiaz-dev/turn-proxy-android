package com.freeturn.app.data.config

/** App theme choice: follow the system, or force light or dark. */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val ALL = listOf(SYSTEM, LIGHT, DARK)

    fun normalize(value: String?): String =
        ALL.firstOrNull { it == value } ?: SYSTEM
}
