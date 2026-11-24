/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.billyke2000s.blockbrowser.blocking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mozilla.fenix.R
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val Accent = Color(0xFF58D9FF)
private val Bar = Color(0xFF6EE7C8)
private val CardShape = RoundedCornerShape(16.dp)

/**
 * Block Browser's home-page blocking statistics, as on the old Block Browser: blocked today / this session / all
 * time, the last 7 days, and the most blocked domains.
 */
@Composable
fun BlockStatsSection(horizontalMargin: Dp, modifier: Modifier = Modifier) {
    val stats by AdBlocker.stats.collectAsState()
    val number = NumberFormat.getIntegerInstance()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = horizontalMargin)) {
        Heading(stringResource(R.string.bb_home_blocked_title))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard(number.format(stats.today), stringResource(R.string.bb_home_today), Modifier.weight(1f))
            StatCard(number.format(stats.session), stringResource(R.string.bb_home_session), Modifier.weight(1f))
            StatCard(number.format(stats.total), stringResource(R.string.bb_home_all_time), Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        Heading(stringResource(R.string.bb_home_last_7_days))
        WeekChart(stats.lastSevenDays)

        if (stats.topDomains.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Heading(stringResource(R.string.bb_home_most_blocked))
            Column(modifier = Modifier.fillMaxWidth().clip(CardShape).background(cardColor()).padding(16.dp)) {
                val max = stats.topDomains.first().second.coerceAtLeast(1)
                stats.topDomains.forEachIndexed { i, (domain, count) ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = domain,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                        )
                        Text(number.format(count), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(trackColor())) {
                        Box(
                            Modifier.fillMaxWidth(count.toFloat() / max).fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp)).background(Accent)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bb_home_protecting, number.format(stats.listSize)),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun cardColor() = MaterialTheme.colorScheme.surfaceContainerHighest

@Composable
private fun trackColor() = MaterialTheme.colorScheme.outlineVariant

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        color = Accent,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(CardShape).background(cardColor()).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun WeekChart(days: List<Pair<LocalDate, Long>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val today = LocalDate.now()
    Row(
        modifier = Modifier.fillMaxWidth().clip(CardShape).background(cardColor())
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (day, count) ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(CHART_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                    val fraction = if (count == 0L) 0f else (count.toFloat() / max).coerceAtLeast(MIN_BAR)
                    Box(
                        Modifier.fillMaxWidth()
                            .height(if (count == 0L) 3.dp else CHART_HEIGHT * fraction)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (count == 0L) trackColor() else Bar)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text =
                        if (day == today) {
                            stringResource(R.string.bb_home_today)
                        } else {
                            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

private val CHART_HEIGHT = 80.dp
private const val MIN_BAR = 0.04f
