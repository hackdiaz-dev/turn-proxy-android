package com.freeturn.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.freeturn.app.R
import com.freeturn.app.tether.ShizukuTether
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsSwitchRow

/**
 * Раздача точки доступа через VPN без root (Shizuku). При включении просит разрешение
 * Shizuku; подпись объясняет, чего не хватает. Применяется со следующего подключения.
 */
@Composable
fun ShizukuTetherCard(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    var status by remember { mutableStateOf(ShizukuTether.unavailable()) }
    // Shizuku могли запустить или дать разрешение, пока приложение было в фоне.
    LifecycleResumeEffect(Unit) {
        status = ShizukuTether.unavailable()
        onPauseOrDispose { }
    }
    val subtitle = when (status) {
        ShizukuTether.Unavailable.OldAndroid -> stringResource(R.string.shizuku_tether_old_android)
        ShizukuTether.Unavailable.NotRunning -> stringResource(R.string.shizuku_tether_not_running)
        ShizukuTether.Unavailable.NoPermission ->
            if (enabled) stringResource(R.string.shizuku_tether_no_permission) else stringResource(R.string.shizuku_tether_desc)
        null -> stringResource(R.string.shizuku_tether_desc)
    }
    SettingsCard {
        SettingsSwitchRow(
            title = stringResource(R.string.shizuku_tether),
            subtitle = subtitle,
            iconRes = R.drawable.wifi_tethering_24px,
            checked = enabled,
            enabled = status != ShizukuTether.Unavailable.OldAndroid,
            onCheckedChange = { on ->
                if (!on) {
                    onEnabledChange(false)
                    return@SettingsSwitchRow
                }
                status = ShizukuTether.unavailable()
                when (status) {
                    ShizukuTether.Unavailable.NoPermission -> ShizukuTether.requestPermission { granted ->
                        status = ShizukuTether.unavailable()
                        if (granted) onEnabledChange(true)
                    }
                    ShizukuTether.Unavailable.NotRunning, ShizukuTether.Unavailable.OldAndroid -> Unit
                    null -> onEnabledChange(true)
                }
            },
        )
    }
}
