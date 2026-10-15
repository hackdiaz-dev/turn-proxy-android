package com.freeturn.app.ui.screens.home

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.TrafficStats
import com.freeturn.app.ui.theme.Spacing

/** Скорость и трафик подключения под кнопкой. Место резервируется, чтобы кнопка не прыгала. */
@Composable
internal fun TrafficRow(status: ProxyStatus, traffic: TrafficStats) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .padding(top = Spacing.lg)
            .height(RowHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
    ) {
        if (status.tunnelUp && status.phase == ProxyPhase.Connected) {
            Text(
                text = stringResource(
                    R.string.traffic_speed,
                    Formatter.formatFileSize(context, traffic.rxRate),
                    Formatter.formatFileSize(context, traffic.txRate)
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    R.string.traffic_total,
                    Formatter.formatFileSize(context, traffic.rxTotal),
                    Formatter.formatFileSize(context, traffic.txTotal)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val RowHeight = 52.dp
