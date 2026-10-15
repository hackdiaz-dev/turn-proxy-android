package com.freeturn.app.ui.screens.home

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.domain.proxy.PingState
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.TrafficStats
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.HapticUtil

/**
 * Скорость, пинг и трафик подключения под кнопкой.
 * Место резервируется всегда, чтобы кнопка не прыгала.
 */
@Composable
internal fun ConnectionStatsCard(
    status: ProxyStatus,
    traffic: TrafficStats,
    ping: PingState,
    onPingClick: () -> Unit,
) {
    val context = LocalContext.current
    val shown = status.tunnelUp && status.phase == ProxyPhase.Connected
    Box(
        modifier = Modifier
            .padding(top = Spacing.lg)
            .fillMaxWidth()
            .height(CardHeight),
        contentAlignment = Alignment.TopCenter
    ) {
        if (shown) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xxl)
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(Spacing.md)
                    ) {
                        SpeedItem(
                            R.drawable.arrow_downward_24px, traffic.rxRate
                        )
                        SpeedItem(
                            R.drawable.arrow_upward_24px, traffic.txRate
                        )
                        Spacer(Modifier.weight(1f))
                        PingPill(ping) {
                            HapticUtil.perform(
                                context, HapticUtil.Pattern.CLICK
                            )
                            if (ping.outside) {
                                Toast.makeText(
                                    context,
                                    R.string.ping_outside,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onPingClick()
                            }
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.traffic_total,
                            Formatter.formatFileSize(context, traffic.rxTotal),
                            Formatter.formatFileSize(context, traffic.txTotal)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedItem(iconRes: Int, bytesPerSec: Long) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(
                R.string.traffic_rate,
                Formatter.formatFileSize(context, bytesPerSec)
            ),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun PingPill(ping: PingState, onClick: () -> Unit) {
    val description = stringResource(R.string.ping_cd)
    val value = ping.ms
    val text = if (value != null && !ping.outside) {
        stringResource(R.string.ping_value, value.toInt())
    } else {
        stringResource(R.string.ping_none)
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .alpha(if (ping.measuring) 0.6f else 1f)
            .semantics { contentDescription = description }
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = Spacing.sm
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                painterResource(R.drawable.bolt_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private val CardHeight = 104.dp
