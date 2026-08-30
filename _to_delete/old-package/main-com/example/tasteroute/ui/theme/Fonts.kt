package com.example.tasteroute.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.example.tasteroute.R
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.FontChoice

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
 * Scale multiplies the app's own sizes and stacks on top of the OS font-size setting rather than
 * replacing it — someone who has already turned system text up gets bigger text here too.
 */
fun appTypography(family: FontFamily, scale: Float): Typography {
    fun size(sp: Float) = (sp * scale).sp
    fun height(sp: Float) = (sp * scale).sp

    return Typography(
        displayMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size(40f)),
        headlineLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size(30f)),
        headlineMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size(28f)),
        headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size(26f)),
        titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size(20f)),
        titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = size(16f)),
        titleSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = size(14f)),
        bodyLarge = TextStyle(fontFamily = family, fontSize = size(16f), lineHeight = height(22f)),
        bodyMedium = TextStyle(fontFamily = family, fontSize = size(14f), lineHeight = height(20f)),
        bodySmall = TextStyle(fontFamily = family, fontSize = size(12f), lineHeight = height(16f)),
        labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = size(14f)),
        labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = size(12f)),
        labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = size(11f)),
    )
}

/** Rebuilding a Typography allocates 15 TextStyles; only do it when the choice actually changes. */
@Composable
fun rememberAppTypography(): Typography {
    val choice = AppState.fontChoice
    val scale = AppState.fontScale
    return remember(choice, scale) { appTypography(fontFamilyFor(choice), scale) }
}
