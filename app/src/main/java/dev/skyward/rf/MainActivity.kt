package dev.skyward.rf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import dev.skyward.rf.ui.AppRoot
import dev.skyward.rf.ui.theme.Sky
import dev.skyward.rf.ui.theme.SkywardTheme

class MainActivity : ComponentActivity() {

    private val container: Container
        get() = (application as SkywardApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkywardTheme {
                Box(androidx.compose.ui.Modifier.fillMaxSize().background(Sky.bg)) {
                    AppRoot(container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.start()
    }

    override fun onStop() {
        super.onStop()
        container.stopScanning()
        container.logged.save()
    }
}
