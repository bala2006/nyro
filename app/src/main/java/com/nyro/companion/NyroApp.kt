package com.nyro.companion

import android.app.Application
import com.nyro.companion.core.AppGraph

class NyroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
