package com.discount152.aeolus.di

import android.content.Context
import android.util.Log
import com.discount152.aeolus.debug.DebugOpenMeteoClientConfigurator
import com.discount152.aeolus.debug.DebugWeatherHarness

fun createAppContainer(context: Context): AppContainer {
    DebugWeatherHarness.restore(context)
    Log.i(DebugWeatherHarness.TAG, "debug container scenario=${DebugWeatherHarness.scenario}")
    return AppContainer(context, DebugOpenMeteoClientConfigurator)
}
