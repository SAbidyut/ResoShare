package net.rapti.resoshare.core.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Light-weight motion helpers. Every animated value here is read only inside a
 * graphicsLayer / draw lambda, so animations re-draw a single layer and never
 * recompose or re-measure the screen (keeps scrolling at full frame rate).
 */

/** Smooth "ease out" used for entrances. */
val EaseOutQuint = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Entrance window of a screen. Items composed within a short time after the
 * screen (or [key]) first appears slide up + fade in with a small stagger;
 * items that appear later because the user scrolls show instantly, so
 * scrolling never looks cluttered. Survives back-navigation (no replay).
 */
@Stable
class Entrance internal constructor(private val start: Long) {
    internal fun active(): Boolean = SystemClock.uptimeMillis() - start < WINDOW_MS
    private companion object { const val WINDOW_MS = 650L }
}

@Composable
fun rememberEntrance(key: Any? = Unit): Entrance {
    val start = rememberSaveable(key) { SystemClock.uptimeMillis() }
    return remember(start) { Entrance(start) }
}

/** Staggered slide-up + fade on first appearance (see [Entrance]). */
@Composable
fun Modifier.enter(e: Entrance, index: Int, distance: Dp = 26.dp): Modifier {
    val p = remember(e) { Animatable(if (e.active()) 0f else 1f) }
    LaunchedEffect(p) {
        if (p.value < 1f) {
            delay(index.coerceIn(0, 7) * 55L)
            p.animateTo(1f, tween(460, easing = EaseOutQuint))
        }
    }
    return graphicsLayer {
        val v = p.value
        alpha = v
        translationY = (1f - v) * distance.toPx()
    }
}

/**
 * A soft diagonal light sweep that crosses the element every few seconds.
 * Apply after the element's clip so the sweep follows its rounded shape.
 */
@Composable
fun Modifier.shine(periodMs: Int = 5200, sweepMs: Int = 1300, strength: Float = 0.16f): Modifier {
    val tr = rememberInfiniteTransition(label = "shine")
    val p = tr.animateFloat(
        initialValue = -1f, targetValue = -1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = periodMs
                -1f at 0
                -1f at periodMs - sweepMs
                0f at periodMs - sweepMs + 1 using EaseOutQuint
                1f at periodMs
            },
            RepeatMode.Restart,
        ),
        label = "shineP",
    )
    return this
        .graphicsLayer()
        .drawWithContent {
            drawContent()
            val v = p.value
            if (v >= 0f && v < 1f) {
                val w = size.width
                val band = w * 0.45f
                val x = -band + v * (w + band * 2)
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                        start = Offset(x - band, 0f),
                        end = Offset(x, size.height),
                    ),
                )
            }
        }
        // Content gets its own layer: the sweep re-draws only itself, not the text below.
        .graphicsLayer()
}

/** Small "live" dot with a soft expanding pulse ring. */
@Composable
fun LiveDot(color: Color, dot: Dp = 8.dp, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "live")
    val p = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "liveP")
    Canvas(modifier.size(dot * 2.6f).graphicsLayer()) {
        val r = dot.toPx() / 2
        val v = p.value
        drawCircle(color.copy(alpha = (1f - v) * 0.45f), radius = r * (1f + 1.6f * v))
        drawCircle(color, radius = r)
    }
}

/**
 * Wraps every item added inside [block] so the list's first screenful glides in
 * with a stagger (see [Entrance]); later items (scrolled to) appear instantly.
 */
fun LazyListScope.withEntrance(e: Entrance, animateItems: Boolean = false, block: LazyListScope.() -> Unit) =
    EnterScope(this, e, animateItems).block()

/** Item wrapper; with [animate] it also carries animateItem() (which only works on the item's root). */
@Composable
private fun LazyItemScope.EnterBox(e: Entrance, index: Int, animate: Boolean, content: @Composable () -> Unit) {
    Box((if (animate) Modifier.animateItem() else Modifier).enter(e, index)) { content() }
}

private class EnterScope(private val base: LazyListScope, private val e: Entrance, private val animate: Boolean) : LazyListScope {
    private var n = 0

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val idx = n++
        val en = e; val an = animate
        base.item(key, contentType) { EnterBox(en, idx, an) { content() } }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val start = n
        n += count
        val en = e; val an = animate
        base.items(count, key, contentType) { i -> EnterBox(en, start + i, an) { itemContent(i) } }
    }

    @ExperimentalFoundationApi
    override fun stickyHeader(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        base.stickyHeader(key, contentType, content)
    }
}

/** LazyColumn whose first screenful glides in (staggered) when the screen opens. */
@Composable
fun EnterLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    entranceKey: Any? = Unit,
    animateItems: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val e = rememberEntrance(entranceKey)
    LazyColumn(modifier, state, contentPadding, verticalArrangement = verticalArrangement) { withEntrance(e, animateItems, content) }
}
