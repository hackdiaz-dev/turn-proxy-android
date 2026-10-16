package com.freeturn.app.ui.screens.home

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.domain.proxy.PingState
import com.freeturn.app.domain.proxy.ProxyPhase
import com.freeturn.app.domain.proxy.ProxyStatus
import com.freeturn.app.domain.proxy.TrafficStats
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.HapticUtil

/** Speed, ping and traffic of the current connection. */
@Composable
internal fun ConnectionStatsCard(
    status: ProxyStatus,
    traffic: TrafficStats,
    ping: PingState,
    onPingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shown = status.tunnelUp && status.phase == ProxyPhase.Connected
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
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
                    modifier = Modifier.padding(
                        horizontal = Spacing.lg,
                        vertical = Spacing.md
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SpeedItem(
                            R.drawable.arrow_downward_24px,
                            traffic.rxRate
                        )
                        SpeedItem(
                            R.drawable.arrow_upward_24px,
                            traffic.txRate
                        )
                        PingItem(ping) {
                            HapticUtil.perform(
                                context,
                                HapticUtil.Pattern.CLICK
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
                            Formatter.formatFileSize(
                                context, traffic.rxTotal
                            ),
                            Formatter.formatFileSize(
                                context, traffic.txTotal
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(
                R.string.traffic_rate,
                Formatter.formatFileSize(context, bytesPerSec)
            ),
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun PingItem(ping: PingState, onClick: () -> Unit) {
    val description = stringResource(R.string.ping_cd)
    val value = ping.ms
    val text = if (value != null && !ping.outside) {
        stringResource(R.string.ping_value, value.toInt())
    } else {
        stringResource(R.string.ping_none)
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Spacing.sm)
            .alpha(if (ping.measuring) 0.6f else 1f)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            painterResource(R.drawable.bolt_24px),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = accent
        )
    }
}

internal val StatsCardBottomPadding = 76.dp
