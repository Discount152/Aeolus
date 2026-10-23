package com.discount152.aeolus.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discount152.aeolus.domain.models.HourlyPoint
import com.discount152.aeolus.domain.models.WeatherCodes
import com.discount152.aeolus.ui.components.ConfidenceBadge
import com.discount152.aeolus.ui.components.ErrorRetry
import com.discount152.aeolus.ui.components.HourlyStrip
import com.discount152.aeolus.ui.components.MetricTile
import com.discount152.aeolus.ui.components.StaleBanner
import com.discount152.aeolus.ui.components.UpdatingBanner
import com.discount152.aeolus.ui.state.ConnectivityPresentation
import com.discount152.aeolus.ui.components.formatDistance
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenForecastDay: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val vm: WeatherViewModel = rememberWeatherViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val bundle = state.bundle
    val location = state.location
    val zone = runCatching { ZoneId.of(location?.timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
    val today = bundle?.consensusDaily?.firstOrNull()
    val confidence = today?.date?.let { bundle.confidenceByDate[it] }
    val nowHour = bundle?.consensusHourly?.firstOrNull()
    val localNow = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(zone).format(Instant.now())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        HeaderRow(
            onLocate = { vm.useDeviceLocation() },
            onRefresh = { vm.refresh() },
            onSettings = onOpenSettings,
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = vm::search,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = "Search location", color = TerminalMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PhosphorGreen,
                unfocusedBorderColor = TerminalBorder,
                focusedTextColor = TerminalText,
                unfocusedTextColor = TerminalText,
                cursorColor = PhosphorGreen,
            ),
        )

        if (state.searchResults.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalElevated, RoundedCornerShape(8.dp))
                    .border(1.dp, TerminalBorder, RoundedCornerShape(8.dp))
                    .padding(4.dp),
            ) {
                state.searchResults.take(6).forEach { result ->
                    TextButton(
                        onClick = {
                            vm.selectLocation(result, saveToList = false)
                            vm.clearSearch()
                        },
                    ) {
                        Text(
                            text = listOfNotNull(result.name, result.admin1, result.country).joinToString(", "),
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.loading && bundle == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = PhosphorGreen)
                }
            }

            state.connectivity == ConnectivityPresentation.ERROR_NO_CACHE &&
                bundle == null &&
                state.error != null -> {
                ErrorRetry(message = state.error!!, onRetry = { vm.refresh() })
            }

            bundle != null && location != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (state.connectivity) {
                        ConnectivityPresentation.OFFLINE_WITH_CACHE -> {
                            StaleBanner(
                                lastUpdatedEpochMs = bundle.fetchedAtEpochMs,
                                offline = true,
                            )
                        }
                        ConnectivityPresentation.REFRESHING_WITH_CACHE -> {
                            UpdatingBanner()
                        }
                        ConnectivityPresentation.LIVE,
                        ConnectivityPresentation.ERROR_NO_CACHE,
                        -> Unit
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = listOfNotNull(location.name, location.admin1, location.country).joinToString(" · "),
                            color = TerminalText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (!state.locationIsSaved) {
                            TextButton(onClick = { vm.saveCurrentLocation() }) {
                                Text(
                                    "SAVE",
                                    color = PhosphorGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    Text(
                        text = "LOCAL $localNow · ${location.timezone}",
                        color = TerminalMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = formatTemp(nowHour?.temperatureC ?: today?.tempMaxC, state.units),
                                color = PhosphorGreen,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "Feels ${formatTemp(nowHour?.apparentTemperatureC, state.units)}",
                                color = TerminalMuted,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = WeatherCodes.label(nowHour?.weatherCode ?: today?.weatherCode),
                                color = TerminalText,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        ConfidenceBadge(confidence = confidence, modifier = Modifier.weight(1f))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MetricTile(
                            label = "PRECIPITATION PROBABILITY",
                            value = "${nowHour?.precipitationProbability ?: today?.precipitationProbabilityMax ?: "—"}%",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            label = "PRECIP AMOUNT",
                            value = formatPrecip(today?.precipitationSumMm, state.units),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    RainWindowHint(hourly = bundle.consensusHourly)

                    Text(
                        text = "48-HOUR HOURLY",
                        color = PhosphorGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    HourlyStrip(hours = bundle.consensusHourly, units = state.units, zoneId = zone)

                    Text(
                        text = "DAILY",
                        color = PhosphorGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        bundle.consensusDaily.take(7).forEach { day ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(TerminalElevated, RoundedCornerShape(10.dp))
                                    .border(1.dp, TerminalBorder, RoundedCornerShape(10.dp))
                                    .clickable { onOpenForecastDay(day.date) }
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = day.date.takeLast(5),
                                    color = TerminalMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = formatTemp(day.tempMaxC, state.units),
                                    color = TerminalText,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = "${bundle.confidenceByDate[day.date]?.score ?: "—"}%",
                                    color = PhosphorGreen,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    val visKm = nowHour?.visibilityM?.div(1000.0)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("WIND", formatWind(nowHour?.windSpeedKmh, state.units), Modifier.weight(1f))
                        MetricTile("GUSTS", formatWind(nowHour?.gustKmh, state.units), Modifier.weight(1f))
                        MetricTile("HUMIDITY", "${nowHour?.humidityPercent ?: "—"}%", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile(
                            "PRESSURE",
                            nowHour?.pressureHpa?.let { "%.0f hPa".format(it) } ?: "—",
                            Modifier.weight(1f),
                        )
                        MetricTile("VISIBILITY", formatDistance(visKm, state.units), Modifier.weight(1f))
                        MetricTile("CLOUDS", "${nowHour?.cloudCoverPercent ?: "—"}%", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricTile("UV", nowHour?.uvIndex?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
                        MetricTile("SUNRISE", today?.sunriseIso?.takeLast(5) ?: "—", Modifier.weight(1f))
                        MetricTile("SUNSET", today?.sunsetIso?.takeLast(5) ?: "—", Modifier.weight(1f))
                    }

                    val updated = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(zone)
                        .format(Instant.ofEpochMilli(bundle.fetchedAtEpochMs))
                    Text(
                        text = "LAST UPDATE $updated",
                        color = TerminalMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    state.error?.let { err ->
                        Text(text = err, color = TerminalMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    onLocate: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "AEOLUS",
                color = PhosphorGreen,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "TODAY",
                color = TerminalMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = onLocate) {
            Icon(Icons.Outlined.MyLocation, contentDescription = "Use current location", tint = PhosphorGreen)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh forecast", tint = PhosphorGreen)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = PhosphorGreen)
        }
    }
}

@Composable
private fun RainWindowHint(hourly: List<HourlyPoint>) {
    val raining = hourly.filter { (it.precipitationMm ?: 0.0) >= 0.2 || (it.precipitationProbability ?: 0) >= 50 }
    if (raining.isEmpty()) {
        MetricTile(
            label = "RAIN WINDOW",
            value = "No clear rain window in next hours",
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    val start = raining.first().timeIso.takeLast(5)
    val end = raining.last().timeIso.takeLast(5)
    MetricTile(
        label = "RAIN WINDOW",
        value = "$start → $end (derived)",
        modifier = Modifier.fillMaxWidth(),
    )
}
