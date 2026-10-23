package com.discount152.aeolus.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `adb shell am broadcast -a com.discount152.aeolus.debug.SET_WEATHER_SCENARIO \
 *   -n com.discount152.aeolus/.debug.DebugWeatherHarnessReceiver \
 *   --es scenario HTTP_429`
 */
class DebugWeatherHarnessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(DebugWeatherHarness.EXTRA_SCENARIO)
        val ok = DebugWeatherHarness.setScenario(raw.orEmpty(), context)
        Log.i(DebugWeatherHarness.TAG, "broadcast action=${intent.action} scenario=$raw ok=$ok now=${DebugWeatherHarness.scenario}")
    }
}
