package com.discount152.aeolus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.discount152.aeolus.domain.units.DistanceUnit
import com.discount152.aeolus.domain.units.PrecipitationUnit
import com.discount152.aeolus.domain.units.TemperatureUnit
import com.discount152.aeolus.domain.units.WindUnit
import com.discount152.aeolus.ui.state.WeatherViewModel
import com.discount152.aeolus.ui.state.aeolusContainer
import com.discount152.aeolus.ui.theme.PhosphorGreen
import com.discount152.aeolus.ui.theme.TerminalBackground
import com.discount152.aeolus.ui.theme.TerminalMuted
import com.discount152.aeolus.ui.theme.TerminalText

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val container = app.aeolusContainer()
    val vm: WeatherViewModel = viewModel(factory = WeatherViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val units = state.units

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBackground)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = PhosphorGreen)
            }
            Text("SETTINGS", color = PhosphorGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("TEMPERATURE") {
                UnitRow("Celsius", units.temperature == TemperatureUnit.CELSIUS) {
                    vm.updateUnits(units.copy(temperature = TemperatureUnit.CELSIUS))
                }
                UnitRow("Fahrenheit", units.temperature == TemperatureUnit.FAHRENHEIT) {
                    vm.updateUnits(units.copy(temperature = TemperatureUnit.FAHRENHEIT))
                }
            }
            Section("WIND") {
                listOf(
                    WindUnit.KMH to "km/h",
                    WindUnit.MPH to "mph",
                    WindUnit.MS to "m/s",
                    WindUnit.BEAUFORT to "Beaufort",
                ).forEach { (u, label) ->
                    UnitRow(label, units.wind == u) { vm.updateUnits(units.copy(wind = u)) }
                }
            }
            Section("PRECIPITATION") {
                UnitRow("mm", units.precipitation == PrecipitationUnit.MM) {
                    vm.updateUnits(units.copy(precipitation = PrecipitationUnit.MM))
                }
                UnitRow("inches", units.precipitation == PrecipitationUnit.INCHES) {
                    vm.updateUnits(units.copy(precipitation = PrecipitationUnit.INCHES))
                }
            }
            Section("DISTANCE / VISIBILITY") {
                UnitRow("km", units.distance == DistanceUnit.KM) {
                    vm.updateUnits(units.copy(distance = DistanceUnit.KM))
                }
                UnitRow("miles", units.distance == DistanceUnit.MILES) {
                    vm.updateUnits(units.copy(distance = DistanceUnit.MILES))
                }
            }
            Section("RADAR OPACITY") {
                Text("${(state.radarOpacity * 100).toInt()}%", color = TerminalText, fontFamily = FontFamily.Monospace)
                Slider(
                    value = state.radarOpacity,
                    onValueChange = vm::updateRadarOpacity,
                    colors = SliderDefaults.colors(thumbColor = PhosphorGreen, activeTrackColor = PhosphorGreen),
                )
            }
            Section("SAVED LOCATIONS") {
                if (state.savedLocations.isEmpty()) {
                    Text("None saved yet", color = TerminalMuted, fontFamily = FontFamily.Monospace)
                }
                state.savedLocations.forEach { loc ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.selectLocation(loc) }, modifier = Modifier.weight(1f)) {
                            Text(loc.name, color = TerminalText, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = { vm.removeSavedLocation(loc.id) }) {
                            Text("REMOVE", color = TerminalMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
            Section("ABOUT") {
                Text(
                    "Aeolus 1.0.0 is a confidence-first Android weather app. " +
                        "It combines deterministic and ensemble forecasts to show what weather is expected " +
                        "and how strongly available models agree.",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Section("CONSENSUS CONFIDENCE") {
                Text(
                    "Consensus Confidence is an Aeolus-derived measure of agreement and stability among " +
                        "available forecast models and ensemble members. It is not the probability that the forecast will be correct.",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Section("PRIVACY") {
                Text(
                    "No Aeolus analytics, telemetry, tracking SDKs, accounts, or cloud sync. " +
                        "No background location. Coordinates are sent only to weather and geocoding services " +
                        "needed to fulfill your request.",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Section("DATA SOURCES") {
                Text(
                    "Open-Meteo (ECMWF IFS, DWD ICON, NOAA GFS, ECMWF ensemble), " +
                        "Open-Meteo Geocoding, RainViewer radar, MapLibre / OpenFreeMap / OpenStreetMap.",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Section("LICENSES / ATTRIBUTION") {
                Text(
                    "See ATTRIBUTIONS.md in the project repository for license details covering " +
                        "Open-Meteo, ECMWF, DWD, NOAA, RainViewer, MapLibre, OpenFreeMap, OpenStreetMap, and weather icons.",
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = PhosphorGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun UnitRow(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            if (selected) "● $label" else "○ $label",
            color = if (selected) PhosphorGreen else TerminalMuted,
            fontFamily = FontFamily.Monospace,
        )
    }
}
