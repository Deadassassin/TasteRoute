package space.gexemy.tasteroute.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Basil,
    onPrimary = Color.White,
    primaryContainer = BasilSoft,
    onPrimaryContainer = BasilSoftInk,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E7CC),
    onSecondaryContainer = Color(0xFF4A3208),
    tertiary = BasilPressed,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = PaperCard,
    onSurface = Ink,
    surfaceVariant = Field,
    onSurfaceVariant = InkSoft,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Chili,
    onError = Color.White,
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = BasilLight,
    onPrimary = Color(0xFF04301A),
    primaryContainer = BasilDeep,
    onPrimaryContainer = BasilDeepInk,
    secondary = AmberLight,
    onSecondary = Color(0xFF3A2704),
    secondaryContainer = Color(0xFF463008),
    onSecondaryContainer = Color(0xFFF7E2BC),
    tertiary = BasilLightPressed,
    onTertiary = Color(0xFF04301A),
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperCardDark,
    onSurface = InkDark,
    surfaceVariant = FieldDark,
    onSurfaceVariant = InkSoftDark,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = ChiliLight,
    onError = Color(0xFF3E0A03),
    scrim = Color(0xFF000000),
)

/**
 * Colors Material has no slot for. Kept here rather than as top-level vals so a screen can never
 * hardcode a light-mode value into a dark-mode surface — the old palette's actual bug.
 */
@Immutable
data class BrandTones(
    val live: Color,
    val muted: Color,
    val allergenSafe: Color,
    val allergenContested: Color,
    val favorite: Color,
    val route: Color,
)

private val LightTones = BrandTones(
    live = LiveGreen,
    muted = MutedLight,
    allergenSafe = LiveGreen,
    allergenContested = Chili,
    favorite = Chili,
    route = Basil,
)

private val DarkTones = BrandTones(
    live = LiveGreenLight,
    muted = MutedDark,
    allergenSafe = LiveGreenLight,
    allergenContested = ChiliLight,
    favorite = ChiliLight,
    route = BasilLight,
)

val LocalBrandTones = staticCompositionLocalOf { LightTones }

/**
 * Whether the app is currently dark, for the one surface Material has no colour slot for: the map.
 * Raster tiles are baked images, so following the theme means choosing a different tile set rather
 * than tinting a colour — and a screen cannot read [AppState.themeMode] directly and still respect
 * "System".
 */
val LocalIsDark = staticCompositionLocalOf { false }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TasteRouteTheme(content: @Composable () -> Unit) {
    val dark = when (AppState.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors

    // Edge-to-edge draws behind the bars, so icon tint has to follow the app's mode, not the OS's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalBrandTones provides if (dark) DarkTones else LightTones,
        LocalIsDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = rememberAppTypography(),
            shapes = AppShapes,
            content = content,
        )
    }
}
