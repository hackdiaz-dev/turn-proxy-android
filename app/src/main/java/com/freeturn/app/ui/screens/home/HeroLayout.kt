package com.freeturn.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Reserved height of the stats card, so the hero does not jump. */
internal val StatsCardMinHeight = 96.dp

/**
 * Two children: the hero and a bottom block (stats card and chip).
 * The bottom block is flush with the bottom. The hero is centered,
 * but never lower than directly above the bottom block. When both
 * do not fit the screen they stack and the column scrolls.
 */
internal object HeroAboveBottomArrangement : Arrangement.Vertical {
    override fun Density.arrange(
        totalSize: Int,
        sizes: IntArray,
        outPositions: IntArray,
    ) {
        if (sizes.size != 2) {
            var y = 0
            for (i in sizes.indices) {
                outPositions[i] = y
                y += sizes[i]
            }
            return
        }
        val hero = sizes[0]
        val bottom = sizes[1]
        val bottomY = totalSize - bottom
        val centered = (totalSize - hero) / 2
        outPositions[0] = minOf(centered, bottomY - hero).coerceAtLeast(0)
        outPositions[1] = bottomY
    }
}
