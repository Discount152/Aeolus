package com.discount152.aeolus

import android.app.Application
import android.util.Log
import com.discount152.aeolus.data.cache.LastKnownGoodStore
import com.discount152.aeolus.di.AppContainer
import com.discount152.aeolus.di.createAppContainer
import org.maplibre.android.MapLibre
import java.io.File

class AeolusApp : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val lkg = File(filesDir, "cache/last_known_good.json")
        Log.i(
            LastKnownGoodStore.TAG,
            "process_start: pid=${android.os.Process.myPid()} " +
                "lkgExists=${lkg.isFile} lkgSize=${if (lkg.isFile) lkg.length() else 0} " +
                "path=${lkg.absolutePath}",
        )
        MapLibre.getInstance(this)
        appContainer = createAppContainer(applicationContext)
    }
}
