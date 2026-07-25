package com.careon.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.careon.wear.ui.CareOnWearApp
import com.careon.wear.data.RemoteCareOnRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CareOnWearApp(repository = RemoteCareOnRepository(applicationContext))
        }
    }
}
