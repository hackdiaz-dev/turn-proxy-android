package com.freeturn.app.data.config

import org.junit.Assert.assertEquals
import org.junit.Test

class DecorSeasonTest {
    @Test fun `all calendar months select the correct season`() {
        val expected = listOf(DecorSeason.WINTER, DecorSeason.WINTER,
            DecorSeason.SPRING, DecorSeason.SPRING, DecorSeason.SPRING,
            DecorSeason.SUMMER, DecorSeason.SUMMER, DecorSeason.SUMMER,
            DecorSeason.AUTUMN, DecorSeason.AUTUMN, DecorSeason.AUTUMN, DecorSeason.WINTER)
        expected.forEachIndexed { index, season -> assertEquals(season, decorSeason(index + 1)) }
    }
}
