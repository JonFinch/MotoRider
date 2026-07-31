package com.motorider.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.motorider.ui.screen.MapScreen
import com.motorider.ui.theme.MotoRiderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotoRiderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MapScreen()
                }
            }
        }
    }
}
