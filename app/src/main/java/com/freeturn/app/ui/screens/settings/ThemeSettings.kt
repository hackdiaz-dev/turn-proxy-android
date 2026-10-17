package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.config.ThemeMode
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** Row: theme choice (system, light, dark) in a dialog. */
@Composable
fun ThemeModeRow(vm: SettingsViewModel) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf(false) }
    SettingsEntryRow(
        iconRes = R.drawable.dark_mode_24px,
        title = stringResource(R.string.appearance_theme_title),
        subtitle = stringResource(modeLabel(mode)),
        onClick = { dialog = true }
    )
    if (dialog) {
        ThemeDialog(
            current = mode,
            onPick = {
                vm.setThemeMode(it)
                dialog = false
            },
            onDismiss = { dialog = false }
        )
    }
}

/** Row: AMOLED black background, active in the dark theme. */
@Composable
fun AmoledRow(vm: SettingsViewModel) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val amoled by vm.amoled.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(R.string.appearance_amoled_title),
        subtitle = stringResource(R.string.appearance_amoled_desc),
        iconRes = R.drawable.mobile_24px,
        checked = amoled,
        enabled = mode != ThemeMode.LIGHT,
        onCheckedChange = vm::setAmoled
    )
}

@Composable
private fun ThemeDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_theme_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.ALL.forEach { value ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = value == current,
                                onClick = { onPick(value) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == current,
                            onClick = null
                        )
                        Text(
                            stringResource(modeLabel(value)),
                            modifier = Modifier.padding(start = Spacing.md)
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

private fun modeLabel(mode: String): Int = when (mode) {
    ThemeMode.LIGHT -> R.string.appearance_theme_light
    ThemeMode.DARK -> R.string.appearance_theme_dark
    else -> R.string.appearance_theme_system
}
