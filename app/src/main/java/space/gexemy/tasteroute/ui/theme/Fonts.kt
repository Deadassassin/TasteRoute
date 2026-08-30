package space.gexemy.tasteroute.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import space.gexemy.tasteroute.R
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.FontChoice

/**
 * Font override. The four system families always work offline and cost nothing; everything else is
 * a Google Font fetched through Play Services' downloadable-font provider, so hundreds of faces are
 * available without a byte of them shipping in the APK — which matters on the phones this app is
 * being tuned for. If the provider is missing or the download fails, Compose falls back to the
 * system face on its own, so a bad network degrades the type and nothing else.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

private fun downloadable(name: String): FontFamily {
    val font = GoogleFont(name)
    return FontFamily(WEIGHTS.map { Font(googleFont = font, fontProvider = provider, weight = it) })
}

fun fontFamilyFor(choice: FontChoice): FontFamily = when (choice) {
    FontChoice.SYSTEM -> FontFamily.Default
    FontChoice.SYSTEM_SERIF -> FontFamily.Serif
    FontChoice.SYSTEM_MONO -> FontFamily.Monospace
    FontChoice.SYSTEM_CURSIVE -> FontFamily.Cursive
    else -> downloadable(choice.label)
}

/**
 * The type scale. Rebuilt 2026-08-27, and the three things it fixes are the three things that made
 * the app read as machine-assembled:
 *
 * 1. **Every heading was Bold** — display through titleLarge, all `FontWeight.Bold`. Bold at 26sp
 *    is a shout, so screens quietly passed `fontWeight = FontWeight.SemiBold` at the call site to
 *    take it back down: **34 overrides**, fighting the scale they were reading from. A scale that
 *    every screen argues with is not a scale. Headings are SemiBold here, and the overrides are
 *    gone — the weight is decided once, in this file.
 * 2. **No `lineHeight` above `bodyLarge`.** Unset leading falls back to whatever the face ships,
 *    which differs per Google Font — so the same headline sat differently depending on a setting
 *    in Settings. Every style now states its own.
 * 3. **No `letterSpacing` anywhere.** Constructing a `TextStyle` from scratch discards Material's
 *    tracking, and the default of 0 is wrong at both ends: large text set at 0 looks airy and
 *    unset, small text set at 0 looks cramped. Headings get negative tracking, labels positive.
 *
 * Sizes were also thinned out. The old ramp ran 40/30/28/26 across four styles doing nearly the
 * same job; four steps that close together are indistinguishable in use, which is why call sites
 * picked between them at random.
 */
fun appTypography(family: FontFamily, scale: Float): Typography {
    fun style(weight: FontWeight, sizeSp: Float, lineSp: Float, trackSp: Float) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (sizeSp * scale).sp,
        lineHeight = (lineSp * scale).sp,
        // Tracking deliberately does NOT scale. Letter spacing is a property of the face at an
        // optical size; multiplying it by the user's size preference turns text they enlarged to
        // read more easily into text that is spaced out and harder to read.
        letterSpacing = trackSp.sp,
    )

    return Typography(
        displayLarge = style(FontWeight.SemiBold, 44f, 50f, -0.8f),
        displayMedium = style(FontWeight.SemiBold, 36f, 42f, -0.6f),
        displaySmall = style(FontWeight.SemiBold, 30f, 36f, -0.5f),
        headlineLarge = style(FontWeight.SemiBold, 28f, 34f, -0.4f),
        headlineMedium = style(FontWeight.SemiBold, 24f, 30f, -0.3f),
        headlineSmall = style(FontWeight.SemiBold, 22f, 28f, -0.2f),
        titleLarge = style(FontWeight.SemiBold, 18f, 24f, -0.1f),
        titleMedium = style(FontWeight.SemiBold, 16f, 22f, 0f),
        titleSmall = style(FontWeight.SemiBold, 14f, 20f, 0.1f),
        bodyLarge = style(FontWeight.Normal, 16f, 24f, 0.1f),
        bodyMedium = style(FontWeight.Normal, 14f, 21f, 0.1f),
        bodySmall = style(FontWeight.Normal, 12f, 17f, 0.2f),
        labelLarge = style(FontWeight.SemiBold, 14f, 18f, 0.2f),
        labelMedium = style(FontWeight.Medium, 12f, 16f, 0.3f),
        labelSmall = style(FontWeight.Medium, 11f, 15f, 0.4f),
    )
}

/** Rebuilding a Typography allocates 15 TextStyles; only do it when the choice actually changes. */
@Composable
fun rememberAppTypography(): Typography {
    val choice = AppState.fontChoice
    val scale = AppState.fontScale
    return remember(choice, scale) { appTypography(fontFamilyFor(choice), scale) }
}
