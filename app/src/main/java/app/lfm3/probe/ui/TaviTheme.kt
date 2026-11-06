package app.lfm3.probe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeChoice(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

private val Light = lightColorScheme(
    primary = Color(0xFF6450C8), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE6FF), onPrimaryContainer = Color(0xFF33226F),
    background = Color(0xFFFAF9F6), onBackground = Color(0xFF252332),
    surface = Color(0xFFFAF9F6), onSurface = Color(0xFF252332),
    surfaceContainer = Color(0xFFF0EEEA), surfaceContainerLow = Color(0xFFF4F2EF),
    surfaceContainerHigh = Color(0xFFE9E6EF), onSurfaceVariant = Color(0xFF656271),
    outline = Color(0xFF817C8B), outlineVariant = Color(0xFFDED9E4),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC5B5FF), onPrimary = Color(0xFF30215F),
    primaryContainer = Color(0xFF3C3064), onPrimaryContainer = Color(0xFFE9DEFF),
    background = Color(0xFF15141B), onBackground = Color(0xFFEDE9F3),
    surface = Color(0xFF15141B), onSurface = Color(0xFFEDE9F3),
    surfaceContainer = Color(0xFF24212D), surfaceContainerLow = Color(0xFF1D1B25),
    surfaceContainerHigh = Color(0xFF302C3D), onSurfaceVariant = Color(0xFFBBB4C7),
    outline = Color(0xFF93899F), outlineVariant = Color(0xFF40394C),
)

@Composable
fun TaviTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = choice == ThemeChoice.DARK || (choice == ThemeChoice.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1.2).sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
        ), content = content,
    )
}
