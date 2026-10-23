package com.discount152.aeolus.data.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkStatusTest {
    @Test
    fun wifiValidated_isOnline() {
        assertThat(
            NetworkStatus.isValidated(
                hasInternet = true,
                hasValidated = true,
                wifi = true,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        ).isTrue()
    }

    @Test
    fun cellularValidated_isOnline() {
        assertThat(
            NetworkStatus.isValidated(
                hasInternet = true,
                hasValidated = true,
                wifi = false,
                cellular = true,
                ethernet = false,
                vpn = false,
            ),
        ).isTrue()
    }

    @Test
    fun noInternet_isOffline() {
        assertThat(
            NetworkStatus.isValidated(
                hasInternet = false,
                hasValidated = true,
                wifi = true,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        ).isFalse()
    }

    @Test
    fun unvalidatedWifi_isOffline() {
        assertThat(
            NetworkStatus.isValidated(
                hasInternet = true,
                hasValidated = false,
                wifi = true,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        ).isFalse()
    }

    @Test
    fun usbOnlyValidated_isOffline() {
        assertThat(
            NetworkStatus.isValidated(
                hasInternet = true,
                hasValidated = true,
                wifi = false,
                cellular = false,
                ethernet = false,
                vpn = false,
            ),
        ).isFalse()
    }
}
