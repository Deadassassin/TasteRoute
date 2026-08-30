package com.example.tasteroute.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.tasteroute.data.DeviceClass
import com.example.tasteroute.data.Perf

/**
 * The macOS "genie" instead of a fade: a pushed screen is pulled out of the card you tapped and
 * sucked back into it on the way out. Two implementations behind one API — a warped path clip on
 * capable devices, a GPU-only scale from the same anchor on low-end ones, where rebuilding a clip
 * path every frame is the difference between smooth and visibly stepping.
 */

const val GENIE_IN_MS = 220
const val GENIE_OUT_MS = 160

private val GenieOpen = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val GenieClose = CubicBezierEasing(0.5f, 0f, 0.9f, 0.2f)

/** Where the next push should appear to come from, in 0..1 of the host view. */
object Genie {
    var origin by mutableStateOf(Offset(0.5f, 0.55f))
        private set

    fun anchorTo(bounds: Rect, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0 || bounds.width <= 0f) return
        origin = Offset(
            (bounds.center.x / viewWidth).coerceIn(0.02f, 0.98f),
            (bounds.center.y / viewHeight).coerceIn(0.02f, 0.98f),
        )
    }

    /** For pushes with no on-screen source, e.g. a header action. */
    fun anchorToCenter() {
        origin = Offset(0.5f, 0.55f)
    }
}

/**
 * Alpha holds at 1 throughout — these exist only to reserve the window in which the destination
 * stays mounted so the genie can run. A real fade here is what made the old push read as a
 * cross-dissolve.
 */
val genieEnter: EnterTransition = fadeIn(tween(GENIE_IN_MS, easing = LinearEasing), initialAlpha = 1f)
val genieExit: ExitTransition = fadeOut(tween(GENIE_OUT_MS, easing = LinearEasing), targetAlpha = 1f)

/** Full bleed at progress 1, pinched to a sliver at the anchor at 0. Two lines and two cubics. */
private class GenieOutline(private val progress: Float, private val anchor: Offset) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress.coerceIn(0f, 1f)
        val ox = anchor.x * size.width
        val oy = anchor.y * size.height

        val left = ox + (0f - ox) * p
        val right = ox + (size.width - ox) * p
        val top = oy + (0f - oy) * p
        val bottom = oy + (size.height - oy) * p

        val pinch = (1f - p) * 0.88f
        val leftNeck = left + (ox - left) * pinch
        val rightNeck = right + (ox - right) * pinch
        val neckY = oy.coerceIn(top, bottom)
        val upper = top + (neckY - top) * 0.6f
        val lower = neckY + (bottom - neckY) * 0.4f

        val path = Path().apply {
            moveTo(left, top)
            lineTo(right, top)
            cubicTo(rightNeck, upper, rightNeck, lower, right, bottom)
            lineTo(left, bottom)
            cubicTo(leftNeck, lower, leftNeck, upper, left, top)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Wrap a pushed screen's content. Reads the enter/exit transition it is already inside, so push
 * and pop both animate without either side knowing about the other.
 */
@Composable
fun AnimatedVisibilityScope.GenieContainer(content: @Composable () -> Unit) {
    val anchor = Genie.origin
    val progress by transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) tween(GENIE_IN_MS, easing = GenieOpen)
            else tween(GENIE_OUT_MS, easing = GenieClose)
        },
        label = "genie",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // The clip path is rebuilt every frame; only a fast device should pay for it. Everything
    // else gets the same lamp read from two axes of scale, which is pure GPU.
    val modifier = if (Perf.deviceClass == DeviceClass.HIGH) {
        Modifier.fillMaxSize().graphicsLayer {
            clip = true
            shape = GenieOutline(progress, anchor)
            // A little extra squeeze sells "pulled out of the card" rather than "scaled up".
            val s = 0.94f + 0.06f * progress
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(anchor.x, anchor.y)
        }
    } else {
        Modifier.fillMaxSize().graphicsLayer {
            // Height shoots out first, width follows — the same order the real genie reads in.
            val fast = progress * progress * (3f - 2f * progress)
            scaleY = 0.22f + 0.78f * fast
            scaleX = 0.55f + 0.45f * (progress * progress)
            alpha = if (progress > 0.12f) 1f else progress / 0.12f
            transformOrigin = TransformOrigin(anchor.x, anchor.y)
        }
    }

    Box(modifier) { content() }
}
