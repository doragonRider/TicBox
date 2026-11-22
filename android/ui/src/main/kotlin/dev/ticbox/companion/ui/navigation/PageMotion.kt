package dev.ticbox.companion.ui.navigation

import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlinx.coroutines.flow.first

/*
 * Page motion. Opening a page slides it in from the right over the current one, and switching
 * tabs crossfades. Going back mirrors Android's own back animation between screens: the closing
 * page shrinks into a card that follows the finger, and the page underneath comes forward out of a
 * dim backdrop. Back from a tab's start page is not ours to draw: the system leaves the app.
 *
 * NavHost transitions are all None; each page draws its own motion ([AnimatedPage]) from its role
 * and its progress in the current transition. AnimatedContent keeps the enter transition a page
 * had when it appeared, so an interrupted transition, e.g. a back gesture while a page is still
 * opening, would otherwise replay a stale one. During a back gesture the card follows the gesture
 * itself; NavHost only seeks its transition, which may lag behind.
 */

private const val OpenMillis = 450
private const val TabMillis = 200
private const val BackMillis = 400

/** How fast the closing card disappears once the back is committed. */
private const val CardFadeMillis = 120

/** How long the revealed page takes to settle once the back is committed. */
private const val SettleMillis = 400

/** The closing card also fades over this last part of the transition. */
private const val CardEndFade = 0.1f

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Android's interpolator for back gesture progress. */
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** The closing card's smallest scale, as in the system back animation. */
private const val CardScale = 0.9f

/** Gap between the shrunk card and the screen edges. */
private val CardMargin = 8.dp

/** How far left the revealed page waits. */
private val RevealOffset = 96.dp

/** Used when the display does not report its corner radius. */
private val FallbackCornerRadius = 28.dp

/** What a page does in the current transition, and how long that takes. */
internal sealed class PageRole(val millis: Int) {
    /** Slides in from the right over [Covered]. */
    data object Opening : PageRole(OpenMillis)

    /** Moves a little to the left under [Opening]. */
    data object Covered : PageRole(OpenMillis)

    /** A tab page, fading in or out; see [PageMotion.tabFading]. */
    data object Tab : PageRole(TabMillis)

    /** Shrinks into a card. [gesture] when a back gesture drives it. */
    data class Closing(val edge: Int, val gesture: Boolean) : PageRole(BackMillis)

    /** Comes forward from under [Closing]. */
    data class Revealed(val gesture: Boolean) : PageRole(BackMillis)
}

/**
 * A back gesture: its progress, how far the finger has moved vertically since it started, and the
 * edge it started from.
 */
internal class BackGesture(val progress: Float, val dy: Float, val edge: Int)

private typealias Scope = AnimatedContentTransitionScope<NavBackStackEntry>

/** Assigns page roles from the NavHost transitions and tracks the back gesture. */
class PageMotion(internal val navController: NavHostController) {
    /** By entry id. A page shown again, e.g. a tab coming back mid-switch, keeps its id. */
    private val roles = mutableStateMapOf<String, Assigned>()

    private class Assigned(val entry: NavBackStackEntry, val role: PageRole)

    /** Tab pages partly faded, by entry id. */
    private val fading = mutableStateSetOf<String>()

    /**
     * Whether a tab switch is fading. The pages then add up (BlendMode.Plus) in an offscreen layer:
     * an exact crossfade whichever of them NavHost draws on top, which an interrupted switch can
     * leave either way.
     */
    internal val tabFading: Boolean
        get() = fading.isNotEmpty()

    private var startY = 0f

    /** The back gesture in progress, or null. */
    internal var gesture by mutableStateOf<BackGesture?>(null)
        private set

    /** The latest back gesture as the finger lifted, for the motion that follows. */
    internal var lastGesture by mutableStateOf<BackGesture?>(null)
        private set

    fun enter(scope: Scope): EnterTransition {
        scope.assign(pop = false)
        return EnterTransition.None
    }

    fun exit(scope: Scope): ExitTransition {
        scope.assign(pop = false)
        return ExitTransition.None
    }

    /** [edge] is the swipe edge of a back gesture, or null for a back without one. */
    fun popEnter(scope: Scope, edge: Int? = null): EnterTransition {
        scope.assign(pop = true, edge)
        return EnterTransition.None
    }

    fun popExit(scope: Scope, edge: Int? = null): ExitTransition {
        scope.assign(pop = true, edge)
        return ExitTransition.None
    }

    internal fun onGesture(state: NavigationEventTransitionState) {
        val event =
            (state as? NavigationEventTransitionState.InProgress)
                ?.takeIf { it.direction == NavigationEventTransitionState.TRANSITIONING_BACK }
                ?.latestEvent
        if (event == null) {
            gesture = null
            return
        }
        if (gesture == null) startY = event.touchY
        gesture =
            BackGesture(event.progress, event.touchY - startY, event.swipeEdge).also {
                lastGesture = it
            }
    }

    private fun Scope.assign(pop: Boolean, edge: Int? = null) {
        // Roles are dropped here rather than when a page's composition ends: a tab restored
        // mid-switch is a new entry with the same id and the same composition, assigned before
        // the old entry's effects end. Destroyed entries are never shown again.
        val destroyed =
            roles.values
                .filter { it.entry.lifecycle.currentState == Lifecycle.State.DESTROYED }
                .map { it.entry.id }
        roles -= destroyed
        fading -= destroyed
        // AnimatedContent may ask a page again with the spec it had when it appeared, e.g. the
        // forward one for a page still opening when a back gesture started: the gesture wins.
        val gestureEdge = edge ?: gesture?.edge
        val (initial, target) =
            when {
                gestureEdge != null ->
                    PageRole.Closing(gestureEdge, gesture = true) to PageRole.Revealed(true)
                initialState.tab != targetState.tab -> {
                    // From the first frame; each page clears itself once settled.
                    fading += initialState.id
                    fading += targetState.id
                    PageRole.Tab to PageRole.Tab
                }
                pop ->
                    PageRole.Closing(NavigationEvent.EDGE_LEFT, gesture = false) to
                        PageRole.Revealed(gesture = false)
                else -> PageRole.Covered to PageRole.Opening
            }
        roles[initialState.id] = Assigned(initialState, initial)
        roles[targetState.id] = Assigned(targetState, target)
    }

    internal fun roleOf(entry: NavBackStackEntry): PageRole? = roles[entry.id]?.role

    internal fun setFading(entry: NavBackStackEntry, fading: Boolean) {
        if (fading) this.fading += entry.id else this.fading -= entry.id
    }
}

/**
 * Draws [content] with its part in the current transition; see the file comment. Once a back is
 * committed the card fades out and the page underneath settles, each at its own pace: NavHost only
 * plays the rest of the transition, which is short after a long drag.
 */
@Composable
fun AnimatedVisibilityScope.AnimatedPage(
    entry: NavBackStackEntry,
    motion: PageMotion,
    content: @Composable () -> Unit,
) {
    // 1 while the page is shown, 0 when it is gone or not shown yet; linear in time.
    val visibility by
        transition.animateFloat(
            transitionSpec = {
                tween(motion.roleOf(entry)?.millis ?: BackMillis, easing = LinearEasing)
            },
            label = "page",
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
    val role = motion.roleOf(entry)
    val current by motion.navController.currentBackStackEntryAsState()
    val isCurrent = current?.id == entry.id
    val committed =
        when (role) {
            is PageRole.Closing -> !isCurrent
            is PageRole.Revealed -> isCurrent
            else -> false
        }
    val cardAlpha by
        animateFloatAsState(
            if (role is PageRole.Closing && committed) 0f else 1f,
            tween(CardFadeMillis, easing = LinearEasing),
            label = "card",
        )
    // 0 while waiting underneath the card, 1 once settled in place.
    val settle = remember { Animatable(if (role is PageRole.Revealed) 0f else 1f) }
    LaunchedEffect(role, committed) {
        if (role !is PageRole.Revealed) return@LaunchedEffect
        settle.snapTo(0f)
        if (committed) settle.animateTo(1f, tween(SettleMillis, easing = Emphasized))
    }
    // Back progress when the finger lifted; a cancelled back returns from there.
    var releasedAt by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(role) {
        if (!role.followsGesture()) return@LaunchedEffect
        snapshotFlow { motion.gesture == null }.first { it }
        releasedAt = backProgress(role, visibility)
    }
    LaunchedEffect(entry.id) {
        try {
            snapshotFlow { motion.roleOf(entry) == PageRole.Tab && visibility < 1f }
                .collect { motion.setFading(entry, it) }
        } finally {
            motion.setFading(entry, false)
        }
    }
    val background = MaterialTheme.colorScheme.background
    val scrim = if (background.luminance() < 0.5f) 0.6f else 0.2f
    val view = LocalView.current

    /** Eased back progress and vertical drag, for the closing card and the page under it. */
    fun backPose(role: PageRole): Pair<Float, Float> {
        val progress = backProgress(role, visibility)
        val gesture = motion.gesture
        val (raw, dy) =
            when {
                !role.followsGesture() -> progress to 0f
                gesture != null -> gesture.progress to gesture.dy
                else -> {
                    val last = motion.lastGesture
                    val share =
                        when {
                            committed -> 1f
                            releasedAt > 0f -> (progress / releasedAt).coerceIn(0f, 1f)
                            else -> 0f
                        }
                    (last?.progress ?: progress) * share to (last?.dy ?: 0f) * share
                }
            }
        return BackGestureEasing.transform(raw) to dy
    }

    Box(
        Modifier.fillMaxSize().drawWithContent {
            val waiting = 1f - settle.value
            if (motion.roleOf(entry) is PageRole.Revealed && waiting > 0f) {
                drawRect(background) // Fills the gaps around the shrunk page.
                drawContent()
                drawRect(Color.Black, alpha = scrim * waiting)
            } else {
                drawContent()
            }
        }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val corner = screenCornerRadius(view)
                val margin = CardMargin.toPx()
                when (val pageRole = motion.roleOf(entry)) {
                    PageRole.Opening -> {
                        translationX = size.width / 10 * (1f - Emphasized.transform(visibility))
                        alpha = ((visibility * OpenMillis - 50f) / 90f).coerceIn(0f, 1f)
                    }
                    PageRole.Covered ->
                        translationX = -size.width / 10 * Emphasized.transform(1f - visibility)
                    PageRole.Tab -> {
                        alpha = visibility
                        if (motion.tabFading) blendMode = BlendMode.Plus
                    }
                    is PageRole.Closing -> {
                        val (progress, dy) = backPose(pageRole)
                        val scale = 1f - (1f - CardScale) * progress
                        scaleX = scale
                        scaleY = scale
                        // Like the system: toward the right edge, or centered for a right swipe.
                        if (pageRole.edge != NavigationEvent.EDGE_RIGHT) {
                            translationX = (size.width * (1f - CardScale) / 2 - margin) * progress
                        }
                        translationY = verticalShift(dy, size.height, scale, margin)
                        // Never cut off: the transition may end before the fade does.
                        if (committed) {
                            alpha = minOf(cardAlpha, (visibility / CardEndFade).coerceIn(0f, 1f))
                        }
                        shape = RoundedCornerShape(corner * (progress * 10f).coerceAtMost(1f))
                        clip = progress > 0f
                    }
                    is PageRole.Revealed -> {
                        // Shrinks along with the card, as in the system animation.
                        val (progress, dy) = backPose(pageRole)
                        val waiting = 1f - settle.value
                        val gestureScale = 1f - (1f - CardScale) * progress
                        scaleX = 1f - (1f - gestureScale) * waiting
                        scaleY = scaleX
                        translationX = -RevealOffset.toPx() * waiting
                        translationY =
                            verticalShift(dy, size.height, gestureScale, margin) * waiting
                        shape = RoundedCornerShape(corner * waiting)
                        clip = waiting > 0f
                    }
                    null -> Unit
                }
            }
        ) {
            content()
        }
    }
}

private fun PageRole?.followsGesture() =
    (this as? PageRole.Closing)?.gesture ?: (this as? PageRole.Revealed)?.gesture ?: false

/** How far the back transition has got for this page, from 0 to 1. */
private fun backProgress(role: PageRole?, visibility: Float) =
    if (role is PageRole.Closing) 1f - visibility else visibility

/**
 * Moves a page shrunk to [scale] with the finger, like the system back animation: quickly at first,
 * at most half a screen of finger travel, and never past the margin.
 */
private fun verticalShift(dy: Float, height: Float, scale: Float, margin: Float): Float {
    val travel = (abs(dy) / (height / 2)).coerceAtMost(1f)
    val eased = 1f - (1f - travel) * (1f - travel)
    return sign(dy) * max(0f, height * (1f - scale) / 2 - margin) * eased
}

/** Matches the card's corners to the display's, like the system back animation. */
private fun Density.screenCornerRadius(view: View): Float {
    val corner = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
    return corner?.radius?.toFloat()?.takeIf { it > 0f } ?: FallbackCornerRadius.toPx()
}
