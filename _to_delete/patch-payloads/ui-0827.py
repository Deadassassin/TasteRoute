import sys, os, re, glob
ROOT = os.path.expanduser("~/mnt/TasteRoute/app/src/main/java/space/gexemy/tasteroute")
UI = os.path.join(ROOT, "ui")

edits, writes = [], []
def E(p, old, new, n=1): edits.append((p, old, new, n))
def W(p, c): writes.append((p, c))

# ============================================================ NEW: the spacing scale
W("ui/theme/Spacing.kt", '''package space.gexemy.tasteroute.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Every gap and every pad in this app comes from here.
 *
 * WHY THIS FILE EXISTS — an audit on 2026-08-27 found **eleven** distinct `spacedBy` values across
 * the UI (2, 4, 6, 8, 9, 10, 12, 14, 16, 18, 28) and **twelve** distinct paddings, with 6dp the
 * single most common gap. Nothing was wrong with any one of them; the problem was that there was
 * no relationship between them. Spacing that has no common factor is the thing people mean when
 * they say a screen looks like it was assembled rather than designed — the eye reads rhythm long
 * before it reads any individual measurement, and there was no rhythm to read.
 *
 * The scale is a 4dp grid, because Android's own metrics are: touch targets, icon sizes and
 * Material's own components all land on multiples of 4. [hair] is the one deliberate exception —
 * an optical nudge between two lines of text, not a layout gap.
 *
 * Rule: a new composable picks the nearest step. If none of them fits, that is evidence the layout
 * is wrong, not that the scale needs a fourteenth value.
 */
object Space {
    /** Optical only — separating a label from the line above it. Never a layout gap. */
    val hair = 2.dp

    /** Inside a control: icon to its own label, a chip's vertical pad. */
    val xs = 4.dp

    /** Between siblings that belong together: badges in a row, a value under its heading. */
    val sm = 8.dp

    /** The default gap between rows in a list or fields in a form. */
    val md = 12.dp

    /** Padding inside a card, and the gap between two unrelated rows. */
    val lg = 16.dp

    /** The screen's own horizontal margin. */
    val screen = 20.dp

    /** Between blocks that are separate ideas on the same screen. */
    val xl = 24.dp

    /** Between major sections, where a divider would otherwise be needed. */
    val section = 32.dp
}
''')

# ============================================================ the type scale
W("ui/theme/Fonts.kt", '''package space.gexemy.tasteroute.ui.theme

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
''')

# ============================================================ copy: stop leaking internals
E("ui/home/HomeScreen.kt",
'''                keepPrevious -> "Couldn't refresh just now — showing your last results. ${outcome.error}"''',
'''                keepPrevious -> "Couldn't refresh just now. These are your last results."''')

E("ui/home/HomeScreen.kt",
'''            WarningNote(
                if (it.startsWith("Couldn't refresh")) it
                else "Ranked these nearby places locally — the AI ranker is unavailable ($it)"
            )''',
'''            // The reason is a fact about our infrastructure, and the person cannot act on it.
            // What they can act on is what it means for the list in front of them: it is sorted,
            // just not personalised. The cause is in the log and on the diagnostics screen.
            WarningNote(
                if (it.startsWith("Couldn't refresh")) it
                else "Sorted these on your phone. Personalised ranking is unavailable right now."
            )''')

E("ui/chat/AssistantScreen.kt",
'''                    reply.text + "\\n\\nNothing " + (target?.let { "in " + it.second.take(40) } ?: "nearby") +
                        " matched that" + (outcome.error?.let { " ($it)" } ?: "") + " — want me to widen it?"''',
'''                    reply.text + "\\n\\nNothing " + (target?.let { "in " + it.second.take(40) } ?: "nearby") +
                        " matched that. Want me to widen the search?"''')

E("ui/chat/AssistantScreen.kt",
'''                if (NimClient.isConfigured) {
                    "I can't reach my language model right now (${NimClient.lastFailure ?: "no connection"}). " +
                        "Discover still works — it ranks places on the phone without me."
                } else {
                    "I'm not switched on yet — NIM_API_KEY is missing. Everything else in the app works without me."
                }''',
'''                if (NimClient.isConfigured) {
                    "I can't answer right now. Discover still works: it ranks places on your " +
                        "phone without me."
                } else {
                    "I'm not switched on in this build. Everything else in the app works without me."
                }''')

E("ui/chat/AssistantScreen.kt",
'''                say("I lost that one — ${NimClient.lastFailure ?: it.message ?: "no reply"}. Try Again?")''',
'''                say("I lost that one. Ask me again?")''')

# ============================================================ the sparkle
E("ui/components/AiFab.kt",
'''import androidx.compose.material.icons.filled.AutoAwesome''',
'''import androidx.compose.material.icons.filled.ChatBubbleOutline''')
E("ui/components/AiFab.kt",
'''                    Icons.Filled.AutoAwesome,''',
'''                    Icons.Filled.ChatBubbleOutline,''')
E("ui/chat/AssistantScreen.kt",
'''import androidx.compose.material.icons.filled.AutoAwesome''',
'''import androidx.compose.material.icons.filled.ChatBubbleOutline''')
E("ui/chat/AssistantScreen.kt",
'''/** The assistant's mark. Same spark as the navigation button, so the two read as the same thing. */''',
'''/**
 * The assistant's mark. Same glyph as the navigation button, so the two read as one thing.
 *
 * It was `AutoAwesome` — the four-pointed sparkle. That glyph has become the universal badge for
 * "a language model wrote this", and on a button whose whole job is to start a conversation it
 * says less than a speech bubble does while carrying a claim nobody asked for. The bubble also
 * makes the morph honest: a conversation turning into a plus reads as "start another one".
 */''')
E("ui/chat/AssistantScreen.kt",
'''            Icons.Filled.AutoAwesome,''',
'''            Icons.Filled.ChatBubbleOutline,''')

# ============================================================ emoji vote buttons
E("ui/group/TableSyncScreen.kt",
'''import androidx.compose.material3.Button''',
'''import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon''')
E("ui/group/TableSyncScreen.kt",
'''import androidx.compose.foundation.layout.size''',
'''import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width''')
E("ui/group/TableSyncScreen.kt",
'''                                }) { Text("👍 ${vote?.yes ?: 0}") }''',
'''                                }) { VoteLabel(Icons.Filled.ThumbUp, "Vote for this", vote?.yes ?: 0) }''')
E("ui/group/TableSyncScreen.kt",
'''                                }) { Text("👎 ${vote?.no ?: 0}") }''',
'''                                }) { VoteLabel(Icons.Filled.ThumbDown, "Vote against this", vote?.no ?: 0) }''')

# ============================================================ Welcome: the real mark, not initials
E("ui/onboarding/WelcomeScreen.kt",
'''import androidx.compose.foundation.background''',
'''import androidx.compose.foundation.Image
import androidx.compose.foundation.background''')
E("ui/onboarding/WelcomeScreen.kt",
'''import androidx.compose.ui.Modifier''',
'''import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource''')
E("ui/onboarding/WelcomeScreen.kt",
'''import space.gexemy.tasteroute.data.Session''',
'''import space.gexemy.tasteroute.R
import space.gexemy.tasteroute.data.Session''')
E("ui/onboarding/WelcomeScreen.kt",
'''        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "TR",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }''',
'''        // The app's own mark, composed from the two adaptive layers the way a launcher composes
        // them (108/72 scale — see AboutScreen). It was the letters "TR" in a coloured circle:
        // the default placeholder avatar, on the one screen whose entire job is the first
        // impression, while a real icon shipped in the same APK.
        Box(
            Modifier.size(72.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                Modifier.fillMaxSize().scale(1.5f),
            )
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                Modifier.fillMaxSize().scale(1.5f),
            )
        }''')

# TableSync gets the shared vote label appended
APPEND = {"ui/group/TableSyncScreen.kt": '''
/**
 * A vote button's contents. Was a literal thumbs-up emoji in the label string, which renders in
 * whatever the system emoji font is — a different size, a different colour and a different
 * baseline from every other control on the screen, and no content description for a screen reader.
 */
@Composable
private fun VoteLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int) {
    Icon(icon, label, Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text("$count")
}
'''}

# The turn-by-turn banner is the one place where size is a safety property, not a style choice.
# The old scale gave it 26sp Bold via headlineSmall + a call-site override; the new headlineSmall
# is 22sp, which is right for a screen title and too small for something read at 60km/h.
E("ui/map/NavigationScreen.kt",
'''                        progress?.let { formatNavDistance(it.distanceToManeuver, AppState.units) } ?: "Starting…",
                        style = MaterialTheme.typography.headlineSmall,''',
'''                        progress?.let { formatNavDistance(it.distanceToManeuver, AppState.units) } ?: "Starting…",
                        style = MaterialTheme.typography.headlineMedium,''')

E("ui/map/NavigationScreen.kt",
'''                        progress?.maneuver?.let { Instructions.banner(it) } ?: "Heading to $destination",
                        style = MaterialTheme.typography.titleMedium,''',
'''                        progress?.maneuver?.let { Instructions.banner(it) } ?: "Heading to $destination",
                        style = MaterialTheme.typography.titleLarge,''')

# ============================================================ regex passes over every UI file
# 4dp grid. Only values with no common factor with the rest of the scale move.
GRID = {3: 4, 5: 4, 6: 8, 7: 8, 9: 8, 10: 12, 11: 12, 13: 12, 14: 16, 15: 16,
        17: 16, 18: 16, 19: 20, 21: 20, 22: 24, 23: 24, 25: 24, 26: 24, 30: 32, 34: 32}

def snap(m, gi):
    v = int(m.group(gi))
    return m.group(0).replace("%d.dp" % v, "%d.dp" % GRID[v], 1) if v in GRID else m.group(0)

SPACING_PATTERNS = [
    r'spacedBy\((\d+)\.dp',
    r'Spacer\(Modifier\.height\((\d+)\.dp\)\)',
    r'Spacer\(Modifier\.width\((\d+)\.dp\)\)',
    r'padding\((\d+)\.dp\)',
    r'(?:horizontal|vertical|start|end|top|bottom) = (\d+)\.dp',
]

# A heading's weight is decided by the type scale now; a call site repeating it is noise, and a
# call site DISAGREEING with it is why the scale stopped meaning anything.
WEIGHT_PATTERNS = [
    # style on one line, weight on the next
    (re.compile(r'(style = MaterialTheme\.typography\.(?:headline|display|title)\w+,)\n\s*fontWeight = FontWeight\.(?:SemiBold|Bold),\n'), r'\1\n'),
    # weight first, style after
    (re.compile(r'fontWeight = FontWeight\.(?:SemiBold|Bold),\n(\s*)(style = MaterialTheme\.typography\.(?:headline|display|title)\w+,)\n'), r'\1\2\n'),
    # both inline
    (re.compile(r'(style = MaterialTheme\.typography\.(?:headline|display|title)\w+), fontWeight = FontWeight\.(?:SemiBold|Bold)'), r'\1'),
    (re.compile(r'fontWeight = FontWeight\.(?:SemiBold|Bold), (style = MaterialTheme\.typography\.(?:headline|display|title)\w+)'), r'\1'),
]

def main():
    bufs, problems = {}, []
    for path, old, new, n in edits:
        full = os.path.join(ROOT, path)
        if path not in bufs:
            if not os.path.exists(full):
                problems.append("MISSING " + path); continue
            bufs[path] = open(full, encoding="utf-8").read()
        found = bufs[path].count(old)
        if found != n:
            problems.append("%s: expected %d, found %d for: %s" % (path, n, found, old.strip().splitlines()[0][:80]))
            continue
        bufs[path] = bufs[path].replace(old, new, n)
    for path, content in writes:
        bufs[path] = content
    if problems:
        print("PHASE 1 FAILED - nothing written")
        for p in problems: print(" -", p)
        sys.exit(1)

    for path, extra in APPEND.items():
        bufs[path] = bufs[path].rstrip("\n") + "\n" + extra

    # Every UI file goes through the regex passes, patched or not - a grid that only half the
    # screens are on is not a grid.
    stats = {"spacing": 0, "weights": 0, "imports": 0}
    for full in sorted(glob.glob(os.path.join(UI, "**", "*.kt"), recursive=True)):
        path = os.path.relpath(full, ROOT).replace(os.sep, "/")
        s = bufs.get(path) or open(full, encoding="utf-8").read()
        before = s
        for pat in SPACING_PATTERNS:
            s = re.sub(pat, lambda m: snap(m, 1), s)
        if s != before: stats["spacing"] += 1
        mid = s
        for rx, rep in WEIGHT_PATTERNS:
            s = rx.sub(rep, s)
        if s != mid: stats["weights"] += 1
        # An import left behind by the line that used it is lint the compiler will not flag.
        if "import androidx.compose.ui.text.font.FontWeight\n" in s and "FontWeight." not in s.split("import androidx.compose.ui.text.font.FontWeight\n", 1)[1]:
            s = s.replace("import androidx.compose.ui.text.font.FontWeight\n", "", 1)
            stats["imports"] += 1
        if s != (bufs.get(path) or before):
            bufs[path] = s

    out = os.environ.get("DRY_ROOT") or ROOT
    for path, content in bufs.items():
        dest = os.path.join(out, path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
    print("wrote %d files" % len(bufs))
    print("  spacing normalised in %d, weight overrides stripped in %d, imports cleaned in %d"
          % (stats["spacing"], stats["weights"], stats["imports"]))
    for p in sorted(bufs): print("   ", p)

main()
