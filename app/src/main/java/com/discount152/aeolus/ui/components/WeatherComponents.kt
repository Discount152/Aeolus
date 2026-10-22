package com.discount152.aeolus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.discount152.aeolus.domain.models.ConfidenceBreakdown
import com.discount152.aeolus.domain.models.ConfidenceCategory
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.WeatherCodes
import com.discount152.aeolus.domain.units.UnitConverter
import com.discount152.aeolus.domain.units.UnitPreferences
import com.discount152.aeolus.domain.units.WindUnit
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBorder
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(12.dp)

@Composable
fun StaleBanner(lastUpdatedEpochMs: Long, offline: Boolean, modifier: Modifier = Modifier) {
    val ageMs = (System.currentTimeMillis() - lastUpdatedEpochMs).coerceAtLeast(0)
    val hours = ageMs / 3_600_000
    val mins = (ageMs % 3_600_000) / 60_000
    val label = if (offline) "OFFLINE DATA" else "STALE DATA"
    Text(
        text = "$label · LAST UPDATED ${hours}H ${mins}M AGO",
        color = Color(0xFFFFB454),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x33FFB454), PanelShape)
            .padding(10.dp)
            .semantics { contentDescription = "$label last updated ${hours}h ${mins}m ago" },
    )
}

@Composable
fun ConfidenceBadge(confidence: ConfidenceBreakdown?, modifier: Modifier = Modifier) {
    val score = confidence?.score ?: 0
    val category = confidence?.category ?: ConfidenceCategory.VERY_LOW
    val color = when (category) {
        ConfidenceCategory.HIGH -> PhosphorGreen
        ConfidenceCategory.MODERATE -> Color(0xFFB6E35A)
        ConfidenceCategory.LOW -> Color(0xFFFFB454)
        ConfidenceCategory.VERY_LOW -> Color(0xFFFF6B6B)
    }
    Column(
        modifier = modifier
            .background(TerminalElevated.copy(alpha = 0.92f), PanelShape)
            .border(1.dp, color.copy(alpha = 0.45f), PanelShape)
            .padding(12.dp)
            .semantics {
                contentDescription = "Consensus Confidence $score percent $category"
            },
    ) {
        Text(
            text = "CONSENSUS CONFIDENCE",
            color = TerminalMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "$score% · $category",
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        if (confidence?.buildingHistory == true) {
            Text(
                text = "BUILDING FORECAST HISTORY",
                color = TerminalMuted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(TerminalElevated.copy(alpha = 0.85f), PanelShape)
            .border(1.dp, TerminalBorder, PanelShape)
            .padding(10.dp)
            .semantics { contentDescription = "$label $value" },
    ) {
        Text(text = label, color = TerminalMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, color = TerminalText, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun HourlyStrip(
    hours: List<HourlyPoint>,
    units: UnitPreferences,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hours.take(48).forEach { hour ->
            val temp = hour.temperatureC?.let {
                UnitConverter.convertTemperature(it, units.temperature).roundToInt()
            }
            val symbol = UnitConverter.temperatureSymbol(units.temperature)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .background(TerminalElevated, PanelShape)
                    .border(1.dp, TerminalBorder, PanelShape)
                    .padding(8.dp),
            ) {
                Text(
                    text = runCatching { fmt.format(Instant.ofEpochMilli(hour.timeEpochMs)) }
                        .getOrElse { hour.timeIso.takeLast(5) },
                    color = TerminalMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = temp?.let { "$it$symbol" } ?: "—",
                    color = PhosphorGreen,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = WeatherCodes.label(hour.weatherCode).take(8),
                    color = TerminalMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
                Text(
                    text = hour.precipitationProbability?.let { "$it%" } ?: "—",
                    color = TerminalText,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
fun ModelBarRow(label: String, valueMm: Double?, maxMm: Double, outlier: Boolean) {
    val fraction =
        if (maxMm <= 0.0 || valueMm == null) {
            0f
        } else {
            (valueMm / maxMm).toFloat().coerceIn(0f, 1f)
        }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (outlier) "$label · OUTLIER" else label,
                color = if (outlier) Color(0xFFFF6B6B) else TerminalText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = valueMm?.let { "%.1f mm".format(it) } ?: "unavailable",
                color = TerminalMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = if (outlier) Color(0xFFFF6B6B) else PhosphorGreen,
            trackColor = TerminalBorder,
        )
    }
}

@Composable
fun ErrorRetry(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalElevated, PanelShape)
            .border(1.dp, TerminalBorder, PanelShape)
            .padding(16.dp),
    ) {
        Text(text = message, color = TerminalText, fontFamily = FontFamily.Monospace)
        TextButton(onClick = onRetry) {
            Text(text = "RETRY", color = PhosphorGreen, fontFamily = FontFamily.Monospace)
        }
    }
}

fun formatTemp(celsius: Double?, units: UnitPreferences): String {
    if (celsius == null) return "—"
    val v = UnitConverter.convertTemperature(celsius, units.temperature).roundToInt()
    return "$v${UnitConverter.temperatureSymbol(units.temperature)}"
}

fun formatWind(kmh: Double?, units: UnitPreferences): String {
    if (kmh == null) return "—"
    val v = UnitConverter.convertWindKmh(kmh, units.wind)
    return if (units.wind == WindUnit.BEAUFORT) {
        "${v.roundToInt()} ${UnitConverter.windSymbol(units.wind)}"
    } else {
        "%.0f %s".format(v, UnitConverter.windSymbol(units.wind))
    }
}

fun formatPrecip(mm: Double?, units: UnitPreferences): String {
    if (mm == null) return "—"
    val v = UnitConverter.convertPrecipitation(mm, units.precipitation)
    return "%.1f %s".format(v, UnitConverter.precipitationSymbol(units.precipitation))
}

fun formatDistance(km: Double?, units: UnitPreferences): String {
    if (km == null) return "—"
    val v = UnitConverter.convertDistance(km, units.distance)
    return "%.1f %s".format(v, UnitConverter.distanceSymbol(units.distance))
}
