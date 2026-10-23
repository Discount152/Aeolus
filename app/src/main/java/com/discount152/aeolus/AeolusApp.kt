package com.discount152.aeolus

import android.app.Application
import com.discount152.aeolus.di.AppContainer
import org.maplibre.android.MapLibre

class AeolusApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        appContainer = AppContainer(applicationContext)
    }
}
