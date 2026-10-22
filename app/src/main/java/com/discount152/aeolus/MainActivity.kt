package com.discount152.aeolus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.discount152.aeolus.di.LocalAppContainer
import com.discount152.aeolus.ui.navigation.AeolusNavHost
import com.discount152.aeolus.ui.theme.AeolusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AeolusApp).appContainer
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AeolusTheme {
                    AeolusNavHost()
                }
            }
        }
    }
}
