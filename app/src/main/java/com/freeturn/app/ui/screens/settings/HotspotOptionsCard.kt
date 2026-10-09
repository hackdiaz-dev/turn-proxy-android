package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import com.freeturn.app.data.config.Socks5Config
import com.freeturn.app.ui.components.LabeledTextField
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** Порт, логин/пароль и UDP для SOCKS5 Hotspot. Применяется со следующего запуска. */
@Composable
fun HotspotOptionsCard(vm: SettingsViewModel) {
    val saved by produceState<Socks5Config?>(null) { value = vm.hotspotConfig() }
    val cfg = saved ?: return
    var portText by remember { mutableStateOf(cfg.port.toString()) }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.pass) }
    var udp by remember { mutableStateOf(cfg.udp) }
    val portOk = portText.toIntOrNull()?.let { it in 1024..65535 } == true
    SettingsCard {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            LabeledTextField(
                value = portText,
                onValueChange = { text ->
                    portText = text.filter { it.isDigit() }.take(5)
                    portText.toIntOrNull()
                        ?.takeIf { it in 1024..65535 }
                        ?.let { vm.setHotspotPort(it) }
                },
                labelRes = R.string.hotspot_port,
                supportingRes = R.string.hotspot_port_hint,
                isError = !portOk,
            )
            LabeledTextField(
                value = user,
                onValueChange = {
                    user = it
                    vm.setHotspotAuth(user, pass)
                },
                labelRes = R.string.hotspot_user,
                supportingRes = R.string.hotspot_auth_hint,
            )
            LabeledTextField(
                value = pass,
                onValueChange = {
                    pass = it
                    vm.setHotspotAuth(user, pass)
                },
                labelRes = R.string.hotspot_pass,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.hotspot_udp),
                subtitle = stringResource(R.string.hotspot_udp_desc),
                checked = udp,
                onCheckedChange = {
                    udp = it
                    vm.setHotspotUdp(it)
                },
            )
        }
    }
}
