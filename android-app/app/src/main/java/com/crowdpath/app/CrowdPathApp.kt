package com.crowdpath.app

import android.app.Application

/**
 * Application class for CrowdPath.
 * Initializes global singletons (database, retrofit, etc.).
 */
class CrowdPathApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CrowdPathApp
            private set
    }
}
