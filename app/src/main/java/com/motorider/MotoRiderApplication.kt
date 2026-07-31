package com.motorider

import android.app.Application
import android.util.Log
import org.osmdroid.config.Configuration

class MotoRiderApplication : Application() {
    private companion object {
        const val TAG = "MotoRiderApplication"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            Configuration.getInstance().userAgentValue = packageName

            try {
                Configuration.getInstance().load(
                    this,
                    getSharedPreferences("osmdroid", MODE_PRIVATE)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not load osmdroid configuration (first launch?): ${e.message}")
            }

            Log.d(TAG, "MotoRiderApplication initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MotoRiderApplication", e)
        }
    }
}
