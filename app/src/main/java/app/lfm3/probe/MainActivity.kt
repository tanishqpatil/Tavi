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
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lfm3.probe.sharing.ShareInbox

class MainActivity : ComponentActivity() {
    private val shares by lazy { ViewModelProvider(this)[ShareInbox::class.java] }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shares.receive(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("share_handled", shares.handled)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        (application as TaviApplication).downloads.reconcile()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((savedInstanceState == null || !savedInstanceState.getBoolean("share_handled")) && shares.pending.value == null) shares.receive(intent)
        AppLog(applicationContext).record("APP_STARTED")
        enableEdgeToEdge()
        val preferences = TaviSettings(applicationContext)
        setContent {
            val incoming by shares.pending.collectAsStateWithLifecycle()
            var theme by remember { mutableStateOf(preferences.theme) }
            var developer by remember { mutableStateOf(preferences.developer) }
            var reduceMotion by remember { mutableStateOf(preferences.reduceMotion) }
            var limits by remember { mutableStateOf(preferences.limits) }
            val dark = theme == ThemeChoice.DARK || (theme == ThemeChoice.SYSTEM && isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            TaviTheme(theme) {
                TaviApp(theme, developer, reduceMotion, limits,
                    onTheme = { theme = it; preferences.theme = it },
                    onDeveloper = { developer = it; preferences.developer = it },
                    onReduceMotion = { reduceMotion = it; preferences.reduceMotion = it },
                    onLimits = { limits = it; preferences.limits = it },
                    incoming = incoming, onDismissShare = shares::dismiss,
                )
            }
        }
    }
}
