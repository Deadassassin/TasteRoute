package space.gexemy.tasteroute.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import space.gexemy.tasteroute.data.DeviceClass
import space.gexemy.tasteroute.data.Perf

/**
 * Two transitions, both straight out of the Material motion spec, and nothing else.
 *
 * The previous "genie" warp rebuilt a clip path every frame on the way in and scaled the whole
 * window from an arbitrary anchor on the way out. It read as a stretch rather than a navigation,
 * and on anything below a flagship the two heaviest screens in the app animated at half rate.
 * Shared-axis and fade-through cost two GPU properties each — translation and alpha — so they
 * hold 60fps on the phones that were dropping frames.
 *
 * PUSH  = shared axis X. The new screen comes in from the trailing edge, the old one steps back
 *         a fraction of the same distance, so the pair reads as one strip sliding.
 * TABS  = fade-through. Siblings have no spatial relationship, so nothing slides; the outgoing
 *         one leaves before the incoming one arrives, with a hair of scale to give it direction.
 */

/** Material's emphasized-decelerate. Fast off the mark, long settle — the reason this feels calm. */
private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Emphasized-accelerate, for anything leaving. Content on its way out should not linger. */
private val EmphasizedOut = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Shortened on 2026-08-27. 300ms is inside the Material range but it is the top of it, and a push
 * that takes a third of a second reads as the phone thinking rather than as the screen moving.
 * The complaint was "sluggish", and on a mid-range panel the last 60ms of a slide is exactly the
 * part that arrives late — cutting it removes the stutter and the wait in one go.
 */
private val enterMs: Int
    get() = when (Perf.deviceClass) {
        DeviceClass.LOW -> 180
        DeviceClass.MID -> 220
        DeviceClass.HIGH -> 260
    }

private val exitMs: Int
    get() = when (Perf.deviceClass) {
        DeviceClass.LOW -> 140
        DeviceClass.MID -> 170
        DeviceClass.HIGH -> 200
    }

/**
 * How far the screens travel, as a fraction of width. The incoming screen moves further than the
 * outgoing one — equal travel looks like a carousel, unequal travel looks like depth.
 */
private const val LEAD = 0.22f
private const val TRAIL = 0.08f

fun pushEnter(): EnterTransition =
    if (Perf.reducedMotion) EnterTransition.None
    else slideInHorizontally(tween(enterMs, easing = Emphasized)) { (it * LEAD).toInt() } +
        fadeIn(tween(enterMs / 2, easing = Emphasized))

fun pushExit(): ExitTransition =
    if (Perf.reducedMotion) ExitTransition.None
    else slideOutHorizontally(tween(enterMs, easing = Emphasized)) { -(it * TRAIL).toInt() } +
        fadeOut(tween(enterMs / 2, easing = EmphasizedOut))

fun popEnter(): EnterTransition =
    if (Perf.reducedMotion) EnterTransition.None
    else slideInHorizontally(tween(exitMs, easing = Emphasized)) { -(it * TRAIL).toInt() } +
        fadeIn(tween(exitMs, easing = Emphasized))

fun popExit(): ExitTransition =
    if (Perf.reducedMotion) ExitTransition.None
    else slideOutHorizontally(tween(exitMs, easing = EmphasizedOut)) { (it * LEAD).toInt() } +
        fadeOut(tween(exitMs, easing = EmphasizedOut))

/**
 * The outgoing tab is gone before the incoming one starts, which is what stops a tab switch
 * reading as two screens briefly stacked on top of each other. The delay is deliberately shorter
 * than the fade-out so there is never an empty frame.
 */
fun tabEnter(): EnterTransition {
    if (Perf.reducedMotion) return EnterTransition.None
    val spec = tween<Float>(if (Perf.deviceClass == DeviceClass.HIGH) 170 else 140, delayMillis = 50, easing = Emphasized)
    val fade = fadeIn(spec)
    // Scale is a third animated property on a screen that is already measuring and laying itself
    // out, so it is spent only where there are frames going spare. A tab switch that took 270ms
    // end to end now takes 190, which is the difference between a transition and a wait.
    return if (Perf.deviceClass == DeviceClass.HIGH) fade + scaleIn(spec, initialScale = 0.97f) else fade
}

fun tabExit(): ExitTransition =
    if (Perf.reducedMotion) ExitTransition.None else fadeOut(tween(70, easing = EmphasizedOut))
