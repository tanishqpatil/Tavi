package app.lfm3.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import app.lfm3.probe.diagnostics.AppLog
import app.lfm3.probe.ui.*

class MainActivity : ComponentActivity() {
    override fun onStop() {
        (application as TaviApplication).runtime.pauseDownload()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog(applicationContext).record("APP_STARTED")
        enableEdgeToEdge()
        val preferences = TaviSettings(applicationContext)
        setContent {
            var theme by remember { mutableStateOf(preferences.theme) }
            var developer by remember { mutableStateOf(preferences.developer) }
            var reduceMotion by remember { mutableStateOf(preferences.reduceMotion) }
            val dark = theme == ThemeChoice.DARK || (theme == ThemeChoice.SYSTEM && isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            TaviTheme(theme) {
                TaviApp(theme, developer, reduceMotion,
                    onTheme = { theme = it; preferences.theme = it },
                    onDeveloper = { developer = it; preferences.developer = it },
                    onReduceMotion = { reduceMotion = it; preferences.reduceMotion = it },
                )
            }
        }
    }
}
