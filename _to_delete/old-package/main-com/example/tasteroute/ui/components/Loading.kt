package com.example.tasteroute.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.example.tasteroute.data.Perf
import kotlinx.coroutines.delay

/**
 * The two "we're working on it" animations.
 *
 * Both degrade to something static on a low-end device. An infinite transition invalidates on every
 * frame, and the moment these are on screen is exactly the moment the phone is busiest — decoding
 * images, parsing a place list and laying out a feed. A shimmer that costs frames during loading is
 * worse than no shimmer, which is why [Perf.richMotion] gates them rather than a global setting.
 */

private const val SWEEP_MS = 1_150
private const val SPAN_PX = 460f

/**
 * One brush for the whole skeleton, deliberately: sharing it means every bar on a card sweeps in
 * step, which reads as one object loading instead of four unrelated ones flickering.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    if (!Perf.richMotion) return SolidColor(base)

    val highlight = lerp(base, MaterialTheme.colorScheme.surface, 0.7f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -SPAN_PX,
        targetValue = SPAN_PX * 2.5f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)),
        label = "sweep",
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(offset, 0f),
        end = Offset(offset + SPAN_PX, 0f),
    )
}

/** Three dots for a model that is still writing. Reads as "alive" where a spinner reads as "stuck". */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (!Perf.richMotion) {
        Box(modifier.size(5.dp).clip(CircleShape).background(color))
        return
    }
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    // Staggered, so the row travels instead of pulsing as one block.
                    tween(560, delayMillis = index * 170, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(Modifier.size(5.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
            if (index < 2) Spacer(Modifier.width(3.dp))
        }
    }
}

private const val STAGGER_STEP_MS = 45L
private const val STAGGER_CAP = 6
private const val ENTRY_MS = 240
private val ENTRY_RISE = 18.dp

/**
 * Fades and lifts a row in, offset by its position in the list.
 *
 * The results arrive in one batch even when they arrive fast, and a whole screen appearing on a
 * single frame reads as a jump-cut rather than as loading finishing. Offsetting each row by a few
 * frames restores the sense of a list filling in. Keyed on first composition only, so a re-rank
 * reorders through animateItem() instead of replaying this on every card.
 *
 * The offset is capped: past six rows the delay stops being perceived as flow and starts being
 * perceived as lag, and rows below the fold are animating where nobody is looking.
 */
@Composable
fun Modifier.staggeredEntry(index: Int): Modifier {
    if (!Perf.richMotion) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceIn(0, STAGGER_CAP) * STAGGER_STEP_MS)
        progress.animateTo(1f, tween(ENTRY_MS, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * ENTRY_RISE.toPx()
    }
}
