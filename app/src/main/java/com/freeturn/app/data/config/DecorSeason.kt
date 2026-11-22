package com.freeturn.app.data.config

/** Calendar seasons for the northern hemisphere; independent of locale language. */
enum class DecorSeason { SPRING, SUMMER, AUTUMN, WINTER }

internal fun decorSeason(month: Int): DecorSeason {
    require(month in 1..12)
    return when (month) {
        3, 4, 5 -> DecorSeason.SPRING
        6, 7, 8 -> DecorSeason.SUMMER
        9, 10, 11 -> DecorSeason.AUTUMN
        else -> DecorSeason.WINTER
    }
}
