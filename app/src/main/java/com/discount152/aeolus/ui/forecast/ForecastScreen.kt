package com.discount152.aeolus.ui.forecast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discount152.aeolus.domain.models.ConsensusDaily
import com.discount152.aeolus.domain.models.WeatherCodes
import com.discount152.aeolus.ui.components.ConfidenceBadge
import com.discount152.aeolus.ui.components.HourlyStrip
import com.discount152.aeolus.ui.components.MetricTile
import com.discount152.aeolus.ui.components.formatPrecip
import com.discount152.aeolus.ui.components.formatTemp
import com.discount152.aeolus.ui.components.formatWind
import com.discount152.aeolus.ui.state.WeatherViewModel
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalBorder
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import java.time.ZoneId

@Composable
fun ForecastScreen(modifier: Modifier = Modifier) {
    val vm: WeatherViewModel = rememberWeatherViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val bundle = state.bundle
    val zone = runCatching { ZoneId.of(state.location?.timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(12.dp),
    ) {
        Text("FORECAST", color = PhosphorGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("15-day horizon · 48-hour detail", color = TerminalMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        if (state.loading && bundle == null) {
            CircularProgressIndicator(color = PhosphorGreen, modifier = Modifier.align(Alignment.CenterHorizontally))
            return
        }
        if (bundle == null) {
            Text("No forecast loaded", color = TerminalMuted, fontFamily = FontFamily.Monospace)
            return
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("48-HOUR DETAIL", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            HourlyStrip(bundle.consensusHourly, state.units, zone)

            Text("DAILY (15 DAYS)", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            bundle.consensusDaily.take(15).forEach { day ->
                DailyCard(
                    day = day,
                    confidence = bundle.confidenceByDate[day.date],
                    units = state.units,
                    expanded = expanded == day.date,
                    onToggle = { expanded = if (expanded == day.date) null else day.date },
                    hourly = bundle.consensusHourly.filter { it.timeIso.startsWith(day.date) },
                    zone = zone,
                    missing = day.missingModels.map { it.displayName },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DailyCard(
    day: ConsensusDaily,
    confidence: com.discount152.aeolus.domain.models.ConfidenceBreakdown?,
    units: com.discount152.aeolus.domain.units.UnitPreferences,
    expanded: Boolean,
    onToggle: () -> Unit,
    hourly: List<com.discount152.aeolus.domain.models.HourlyPoint>,
    zone: ZoneId,
    missing: List<String>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalElevated.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .border(1.dp, TerminalBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(day.date, color = TerminalText, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Text(WeatherCodes.label(day.weatherCode), color = TerminalMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Text(
                "${formatTemp(day.tempMaxC, units)} / ${formatTemp(day.tempMinC, units)}",
                color = PhosphorGreen,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("PRECIP PROB", "${day.precipitationProbabilityMax ?: "—"}%", Modifier.weight(1f))
            MetricTile("PRECIP", formatPrecip(day.precipitationSumMm, units), Modifier.weight(1f))
            MetricTile("CONFIDENCE", "${confidence?.score ?: "—"}%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Wind ${formatWind(day.windSpeedMaxKmh, units)} · Gusts ${formatWind(day.gustMaxKmh, units)}",
            color = TerminalMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Sunrise ${day.sunriseIso?.takeLast(5) ?: "—"} · Sunset ${day.sunsetIso?.takeLast(5) ?: "—"}",
            color = TerminalMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (missing.isNotEmpty()) {
            Text("Missing models: ${missing.joinToString()}", color = TerminalMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(8.dp))
                ConfidenceBadge(confidence)
                if (hourly.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HourlyStrip(hourly, units, zone)
                } else {
                    Text("Hourly detail limited for this day", color = TerminalMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                day.outliers.forEach {
                    Text(
                        "OUTLIER ${it.model.displayName} ${it.variable}: ${"%.1f".format(it.value)} (consensus ${"%.1f".format(it.consensus)})",
                        color = TerminalMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
