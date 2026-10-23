package com.discount152.aeolus.debug

/**
 * Deterministic Open-Meteo HTTP scenarios for debug builds and unit tests.
 * [LIVE] is passthrough so a debug APK still uses real providers until a scenario is set.
 */
enum class DebugWeatherScenario {
    LIVE,
    SUCCESS_FULL,
    SUCCESS_CORE_ONLY,
    HTTP_429,
    HTTP_500,
    TIMEOUT,
    EMPTY_200,
    PARTIAL_MODEL_FAILURE,
    SLOW_SUCCESS,
    ;

    companion object {
        fun parse(raw: String?): DebugWeatherScenario? {
            val key = raw?.trim()?.uppercase()?.replace('-', '_') ?: return null
            return entries.firstOrNull { it.name == key }
        }
    }
}
