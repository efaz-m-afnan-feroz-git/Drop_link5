package app.droplink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.droplink.data.ThemeMode

// Fallback palette (used below Android 12, where dynamic color isn't available): teal + amber.
private val Teal10 = Color(0xFF002020)
private val Teal20 = Color(0xFF003737)
private val Teal30 = Color(0xFF004F4F)
private val Teal40 = Color(0xFF006A6A)
private val Teal80 = Color(0xFF4CDADA)
private val Teal90 = Color(0xFF6FF7F6)
private val Amber40 = Color(0xFF7D5800)
private val Amber80 = Color(0xFFF8BD45)
private val Amber90 = Color(0xFFFFDEA9)
private val Slate40 = Color(0xFF4A6363)
private val Slate80 = Color(0xFFB0CCCB)
private val Slate90 = Color(0xFFCCE8E7)
private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Slate40,
    secondaryContainer = Slate90,
    tertiary = Amber40,
    tertiaryContainer = Amber90,
    error = Red40,
    background = Color(0xFFFAFDFC),
    surface = Color(0xFFFAFDFC),
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Slate80,
    tertiary = Amber80,
    error = Red80,
    background = Color(0xFF191C1C),
    surface = Color(0xFF191C1C),
)

/** Colors that must stay fixed regardless of theme (QR codes need dark-on-light to scan reliably). */
object FixedColors {
    val QrDark = Color.Black
    val QrLight = Color.White
    val Success = Color(0xFF2E7D32)
}

@Composable
fun DropLinkTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
