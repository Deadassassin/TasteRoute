package space.gexemy.tasteroute.ui.theme

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
