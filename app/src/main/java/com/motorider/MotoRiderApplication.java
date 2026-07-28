package com.motorider;

import android.app.Application;
import android.util.Log;

import org.osmdroid.config.Configuration;

public class MotoRiderApplication extends Application {
    private static final String TAG = "MotoRiderApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        try {
            // Initialize osmdroid configuration with application context
            Configuration.getInstance().setUserAgentValue(getPackageName());
            
            // Load osmdroid configuration safely (handle case where file doesn't exist yet)
            try {
                Configuration.getInstance().load(this, 
                    getSharedPreferences("osmdroid", MODE_PRIVATE));
            } catch (Exception e) {
                Log.w(TAG, "Could not load osmdroid configuration (first launch?): " + e.getMessage());
            }
                
            Log.d(TAG, "MotoRiderApplication initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing MotoRiderApplication", e);
        }
    }
}