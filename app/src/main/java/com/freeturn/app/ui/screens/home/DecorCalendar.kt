package com.freeturn.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.freeturn.app.data.config.DecorSeason
import com.freeturn.app.data.config.decorSeason
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime

/** Refresh on resume and local midnight; stop the timer while the screen is stopped. */
@Composable
internal fun rememberDecorSeason(): DecorSeason {
    val owner = LocalLifecycleOwner.current
    fun current() = decorSeason(ZonedDateTime.now().monthValue)
    val season by produceState(current(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = current()
                val now = ZonedDateTime.now()
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000))
            }
        }
    }
    return season
}
