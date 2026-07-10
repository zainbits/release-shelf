package dev.zain.releaseshelf.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A64),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2E9),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF4A6360),
    secondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFF456179),
    tertiaryContainer = Color(0xFFCBE6FF),
    surface = Color(0xFFF5FBF9),
    surfaceContainer = Color(0xFFE9F0EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5CD),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF9CF2E9),
    secondary = Color(0xFFB0CCC8),
    secondaryContainer = Color(0xFF324B48),
    tertiary = Color(0xFFADCBE5),
    tertiaryContainer = Color(0xFF2D4960),
    surface = Color(0xFF0E1514),
    surfaceContainer = Color(0xFF1A211F),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun ReleaseShelfTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = ExpressiveShapes,
        content = content,
    )
}
