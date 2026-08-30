package space.gexemy.tasteroute.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import space.gexemy.tasteroute.data.Perf

/**
 * The centre of the bottom bar, raised out of it.
 *
 * One control with two meanings, which is why it is one control: away from the assistant it is the
 * way IN — a spark on a filled circle, the thing your thumb already rests on. Once you are there,
 * going in again is meaningless, so the same button becomes the only other thing you could want
 * from a conversation you are already having: a fresh one. A separate "New chat" text button in
 * the header would be a second control for a job this one is doing.
 *
 * The morph is a quarter turn plus a scale swap rather than a cross-fade. A plus and a spark are
 * both radial marks of about the same weight; fading one into the other reads as a smudge, while
 * rotating through 90 degrees reads as the same object turning into something else.
 */

/** Overhang above the navigation bar. The parent must reserve this or the button loses its taps. */
val AiFabOverhang = 24.dp

private const val MORPH_MS = 320
private const val PULSE_MS = 1_400

private val FAB_SIZE = 56.dp
private val FAB_RING = 68.dp
private val ICON_SIZE = 26.dp

@Composable
fun AiFab(
    onAssistant: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Presses are worth animating even on a low-end phone: this is a 56dp target with no ripple
    // of its own, so without the give it reads as not having registered the tap at all.
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "press")

    // Rotation carries the whole morph. Both icons are drawn at all times and counter-rotated, so
    // neither one is ever seen at an angle -- the CONTAINER turns, the marks stay upright.
    val turn by animateFloatAsState(
        targetValue = if (onAssistant) 90f else 0f,
        animationSpec = tween(MORPH_MS, easing = FastOutSlowInEasing),
        label = "morph",
    )
    val toPlus by animateFloatAsState(
        targetValue = if (onAssistant) 1f else 0f,
        animationSpec = tween(MORPH_MS, easing = FastOutSlowInEasing),
        label = "swap",
    )

    // A halo that breathes only while the model is actually writing. Deliberately not an
    // always-on animation: a button that pulses forever is decoration, and it would be running on
    // every screen in the app. Pulsing exactly when there is a reply in flight makes it a status
    // light you can read from another tab.
    val halo = haloWidth(busy)

    Box(modifier.size(FAB_RING), contentAlignment = Alignment.Center) {
        // The notch. A ring of the bar's own colour behind the button is what separates it from
        // the feed scrolling underneath, without a custom bottom-bar path to keep in step with
        // Material's insets.
        Box(
            Modifier
                .size(FAB_RING)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
        )
        if (halo > 0.dp) {
            Box(
                Modifier
                    .size(FAB_SIZE + halo)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
            )
        }
        Surface(
            modifier = Modifier
                .size(FAB_SIZE)
                .scale(press)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (onAssistant) "New chat" else "Assistant",
                    onClick = onClick,
                ),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = if (Perf.richMotion) 6.dp else 0.dp,
        ) {
            Box(
                Modifier.graphicsLayer { rotationZ = turn },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier
                        .size(ICON_SIZE)
                        .graphicsLayer {
                            rotationZ = -turn
                            alpha = 1f - toPlus
                            scaleX = 1f - toPlus * 0.4f
                            scaleY = 1f - toPlus * 0.4f
                        },
                )
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier
                        .size(ICON_SIZE + 4.dp)
                        .graphicsLayer {
                            rotationZ = -turn
                            alpha = toPlus
                            scaleX = 0.6f + toPlus * 0.4f
                            scaleY = 0.6f + toPlus * 0.4f
                        },
                )
            }
        }
    }
}

/**
 * The halo's extra width, animated while [busy] and flat otherwise.
 *
 * Split out because an infinite transition cannot be started and stopped by an `if` at the call
 * site -- it would be created on some compositions and not others. Here it always exists, and it
 * is the TARGET that goes to zero, so a settled button costs nothing per frame.
 */
@Composable
private fun haloWidth(busy: Boolean) = if (!Perf.richMotion) {
    0.dp
} else {
    val transition = rememberInfiniteTransition(label = "halo")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val target = if (busy) (4 + 8 * phase).dp else 0.dp
    val width by animateDpAsState(target, tween(220), label = "halo-width")
    width
}

