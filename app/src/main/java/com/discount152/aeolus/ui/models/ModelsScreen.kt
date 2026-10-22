package com.discount152.aeolus.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discount152.aeolus.domain.models.ForecastModelId
import com.discount152.aeolus.ui.components.ConfidenceBadge
import com.discount152.aeolus.ui.components.ModelBarRow
import com.discount152.aeolus.ui.components.MetricTile
import com.discount152.aeolus.ui.components.formatPrecip
import com.discount152.aeolus.ui.components.formatTemp
import com.discount152.aeolus.ui.state.WeatherViewModel
import com.discount152.aeolus.ui.state.rememberWeatherViewModel
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalBorder
import com.discount152.aeolus.ui.theme.TerminalElevated
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ModelsScreen(modifier: Modifier = Modifier) {
    val vm: WeatherViewModel = rememberWeatherViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val bundle = state.bundle
    var selectedDate by remember(bundle) {
        mutableStateOf(bundle?.consensusDaily?.firstOrNull()?.date)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(12.dp),
    ) {
        Text("MODELS", color = PhosphorGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("Deterministic disagreement · ensemble spread", color = TerminalMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))

        if (bundle == null) {
            Text("Load a forecast to compare models", color = TerminalMuted, fontFamily = FontFamily.Monospace)
            return
        }

        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            bundle.consensusDaily.take(15).forEach { day ->
                FilterChip(
                    selected = selectedDate == day.date,
                    onClick = { selectedDate = day.date },
                    label = { Text(day.date.takeLast(5), fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TerminalElevated,
                        selectedLabelColor = PhosphorGreen,
                        labelColor = TerminalMuted,
                    ),
                )
            }
        }

        val day = bundle.consensusDaily.firstOrNull { it.date == selectedDate } ?: return
        val confidence = bundle.confidenceByDate[day.date]
        val zone = runCatching { ZoneId.of(bundle.location.timezone) }.getOrDefault(ZoneId.of("UTC"))

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            ConfidenceBadge(confidence)
            Text("CONSENSUS", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("HIGH", formatTemp(day.tempMaxC, state.units), Modifier.weight(1f))
                MetricTile("LOW", formatTemp(day.tempMinC, state.units), Modifier.weight(1f))
                MetricTile("PRECIP", formatPrecip(day.precipitationSumMm, state.units), Modifier.weight(1f))
            }

            Text("PRECIPITATION BY MODEL", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            val precipValues = ForecastModelId.entries.associateWith { id ->
                bundle.models.firstOrNull { it.model == id && it.available }
                    ?.daily?.firstOrNull { it.date == day.date }
                    ?.precipitationSumMm
            }
            val maxP = precipValues.values.filterNotNull().maxOrNull() ?: 1.0
            ForecastModelId.entries.forEach { id ->
                val v = precipValues[id]
                val outlier = day.outliers.any { it.model == id && it.variable == "precipitation" }
                if (v == null) {
                    Text("${id.displayName}: unavailable", color = TerminalMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    ModelBarRow(id.displayName, v, maxP, outlier)
                }
            }

            Text("TEMPERATURE MAX BY MODEL", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalElevated, RoundedCornerShape(12.dp))
                    .border(1.dp, TerminalBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                ForecastModelId.entries.forEach { id ->
                    val t = bundle.models.firstOrNull { it.model == id && it.available }
                        ?.daily?.firstOrNull { it.date == day.date }?.tempMaxC
                    Text(
                        "${id.displayName}: ${formatTemp(t, state.units)}",
                        color = if (t == null) TerminalMuted else TerminalText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }

            val ens = selectedDate?.let { bundle.ensembleByDate[it] } ?: bundle.ensemble
            Text("ECMWF ENSEMBLE", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (ens == null) {
                Text("ENSEMBLE DATA UNAVAILABLE", color = TerminalMuted, fontFamily = FontFamily.Monospace)
                Text("Confidence based on available deterministic models", color = TerminalMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            } else {
                MetricTile(
                    "MEMBERS > ${ens.precipThresholdMm} mm",
                    "${ens.membersPredictingPrecipAboveThreshold} / ${ens.memberCount}",
                    Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("P10", formatPrecip(ens.precipP10Mm, state.units), Modifier.weight(1f))
                    MetricTile("MEAN", formatPrecip(ens.precipMeanMm, state.units), Modifier.weight(1f))
                    MetricTile("P90", formatPrecip(ens.precipP90Mm, state.units), Modifier.weight(1f))
                }
                MetricTile(
                    "TEMP SPREAD",
                    ens.temperatureSpreadC?.let { "%.1f °C".format(it) } ?: "—",
                    Modifier.fillMaxWidth(),
                )
            }

            day.precipRangeMm?.let {
                MetricTile("DETERMINISTIC SPREAD", "%.1f–%.1f mm".format(it.low, it.high), Modifier.fillMaxWidth())
            }

            if (day.missingModels.isNotEmpty()) {
                Text(
                    "Unavailable: ${day.missingModels.joinToString { it.displayName }}",
                    color = TerminalMuted,
                    fontFamily = FontFamily.Monospace,
                )
            }

            bundle.stabilitySignals.forEach {
                Text("STABILITY · ${it.label.uppercase()}", color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            val age = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
                .format(Instant.ofEpochMilli(bundle.fetchedAtEpochMs))
            Text("DATA AGE · fetched $age", color = TerminalMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(24.dp))
        }
    }
}
