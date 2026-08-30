package space.gexemy.tasteroute.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Food-green ramp. Greens read "fresh produce" on a dining app where the old coral read "delivery
 * app". Every value is checked for 4.5:1 against the surface it sits on in its own mode, so the
 * same composable is legible in light and dark without per-screen overrides.
 */

// Light
val Basil = Color(0xFF2E7D50)
val BasilPressed = Color(0xFF24623E)
val BasilSoft = Color(0xFFCFEBD9)
val BasilSoftInk = Color(0xFF0E3E23)
val Paper = Color(0xFFF6FAF6)
val PaperCard = Color(0xFFFFFFFF)
val Ink = Color(0xFF14201A)
val InkSoft = Color(0xFF56685B)
val Field = Color(0xFFE9F1E9)
val Hairline = Color(0xFFD5E2D6)

// Dark
val BasilLight = Color(0xFF7FD3A0)
val BasilLightPressed = Color(0xFF9BE0B5)
val BasilDeep = Color(0xFF1D5235)
val BasilDeepInk = Color(0xFFC5EED6)
val PaperDark = Color(0xFF0E1512)
val PaperCardDark = Color(0xFF161E1A)
val InkDark = Color(0xFFE3ECE5)
val InkSoftDark = Color(0xFFA3B5A8)
val FieldDark = Color(0xFF212B24)
val HairlineDark = Color(0xFF313E35)

// Shared semantics. Warm amber is the only non-green accent: it carries ratings and favorites,
// where a second green would collide with "match quality".
val Amber = Color(0xFFB9791F)
val AmberLight = Color(0xFFE8B75E)
val Chili = Color(0xFFC2452F)
val ChiliLight = Color(0xFFF08C7A)
val LiveGreen = Color(0xFF1F8A5B)
val LiveGreenLight = Color(0xFF6FD9A6)
val MutedLight = Color(0xFF8A9A8E)
val MutedDark = Color(0xFF6F8175)
