package com.discount152.aeolus.ui.state

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Activity-scoped ViewModel so TODAY / FORECAST / MODELS share one weather state.
 */
@Composable
fun rememberWeatherViewModel(): WeatherViewModel {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val container = context.aeolusContainer()
    return viewModel(
        viewModelStoreOwner = activity,
        factory = WeatherViewModel.factory(container),
    )
}
