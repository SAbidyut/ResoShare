package net.rapti.resoshare.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso

/** Animated line/area chart for share price. `values` are paisa, ascending in time. */
@Composable
fun PriceLineChart(values: List<Long>, labels: List<String>, modifier: Modifier = Modifier, height: Dp = 190.dp) {
    val c = Reso.colors
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    if (values.isEmpty()) return
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0 } ?: 100L
    val lo = min - span / 8
    val hi = max + span / 8
    val mid = (lo + hi) / 2
    Column(modifier.fillMaxWidth().semantics { contentDescription = t("শেয়ারের মূল্যের চার্ট", "Share price chart") }) {
        Row(Modifier.fillMaxWidth().height(height)) {
            Column(Modifier.width(64.dp).height(height), verticalArrangement = Arrangement.SpaceBetween) {
                T(Bn.moneyPlain(hi), 10.sp, color = c.muted, maxLines = 1)
                T(Bn.moneyPlain(mid), 10.sp, color = c.muted, maxLines = 1)
                T(Bn.moneyPlain(lo), 10.sp, color = c.muted, maxLines = 1)
            }
            Canvas(Modifier.weight(1f).height(height).padding(top = 8.dp, bottom = 8.dp, end = 8.dp)) {
                val w = size.width
                val h = size.height
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(c.chartGrid, Offset(0f, y), Offset(w, y), 1.dp.toPx())
                }
                val n = values.size
                fun px(i: Int) = if (n == 1) w / 2 else w * i / (n - 1).toFloat()
                fun py(v: Long) = h - ((v - lo).toFloat() / (hi - lo).toFloat()) * h
                val line = Path()
                val area = Path()
                values.forEachIndexed { i, v ->
                    val x = px(i); val y = py(v)
                    if (i == 0) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
                }
                area.lineTo(px(n - 1), h); area.close()
                clipRect(right = w * progress.value + 2f) {
                    drawPath(area, Brush.verticalGradient(listOf(c.chartFill, Color.Transparent)))
                    drawPath(line, c.chartLine, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (progress.value > 0.98f) {
                    val lx = px(n - 1); val ly = py(values.last())
                    drawCircle(Brand.Amber, 7.dp.toPx(), Offset(lx, ly))
                    drawCircle(c.chartLine, 7.dp.toPx(), Offset(lx, ly), style = Stroke(2.dp.toPx()))
                }
            }
        }
        AxisLabels(labels, Modifier.fillMaxWidth().padding(start = 64.dp, end = 8.dp, top = 2.dp))
    }
}

/** X-axis labels centred under each data point; every other label when crowded (12 months). */
@Composable
private fun AxisLabels(labels: List<String>, modifier: Modifier) {
    val c = Reso.colors
    val crowded = labels.size > 8
    androidx.compose.ui.layout.Layout(
        content = {
            labels.forEachIndexed { i, l ->
                val show = !crowded || (labels.lastIndex - i) % 2 == 0
                T(if (show) l else "", 11.sp, color = c.muted, maxLines = 1)
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints()) }
        val w = constraints.maxWidth
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, h) {
            val n = placeables.size
            placeables.forEachIndexed { i, p ->
                val cx = if (n == 1) w / 2 else w * i / (n - 1)
                p.place((cx - p.width / 2).coerceIn(0, (w - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

/** Up/down change bars around a zero baseline: green ▲ for increase, red ▼ for decrease. */
@Composable
fun ChangeBars(diffs: List<Long>, modifier: Modifier = Modifier, height: Dp = 120.dp) {
    val c = Reso.colors
    val progress = remember(diffs) { Animatable(0f) }
    LaunchedEffect(diffs) { progress.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
    val maxAbs = (diffs.maxOfOrNull { kotlin.math.abs(it) } ?: 0L).coerceAtLeast(1L)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = t("বৃদ্ধি ও হ্রাসের চার্ট", "Rise and fall chart") }) {
        val w = size.width; val h = size.height; val base = h * 0.55f
        drawLine(c.chartGrid, Offset(0f, base), Offset(w, base), 1.dp.toPx())
        if (diffs.isEmpty()) return@Canvas
        val slot = w / diffs.size
        val bw = (slot * 0.36f).coerceAtMost(18.dp.toPx())
        diffs.forEachIndexed { i, d ->
            if (d == 0L) {
                drawLine(c.muted, Offset(slot * i + slot / 2 - bw / 2, base), Offset(slot * i + slot / 2 + bw / 2, base), 3.dp.toPx(), cap = StrokeCap.Round)
                return@forEachIndexed
            }
            val frac = kotlin.math.abs(d).toFloat() / maxAbs * progress.value
            val bh = if (d > 0) frac * (base - 4.dp.toPx()) else frac * (h - base - 4.dp.toPx())
            val x = slot * i + slot / 2 - bw / 2
            val top = if (d > 0) base - bh else base
            drawRoundRect(if (d > 0) c.green else c.red, Offset(x, top), Size(bw, bh.coerceAtLeast(2f)), CornerRadius(3.dp.toPx()))
        }
    }
}

/** Sales bar chart (admin). Last bucket highlighted. */
@Composable
fun SalesBars(values: List<Long>, labels: List<String>, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    val c = Reso.colors
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val maxV = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val barColor = if (c.dark) Color(0xFF5E77A6) else Color(0xFF7489B0)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = t("বিক্রির চার্ট", "Sales chart") }) {
            val w = size.width; val h = size.height
            if (values.isEmpty()) return@Canvas
            val slot = w / values.size
            val bw = slot * 0.66f
            values.forEachIndexed { i, v ->
                val bh = (v.toFloat() / maxV) * (h - 4.dp.toPx()) * progress.value
                val color = if (i == values.lastIndex) c.ink else barColor
                drawRoundRect(color, Offset(slot * i + (slot - bw) / 2, h - bh), Size(bw, bh.coerceAtLeast(2f)), CornerRadius(4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEachIndexed { i, l ->
                // Show every other label when crowded.
                val show = labels.size <= 8 || i % 2 == 0 || i == labels.lastIndex
                T(if (show) l else "", 11.sp, color = c.muted, modifier = Modifier.weight(1f), align = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** Legend dot + label. */
@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(12.dp).height(12.dp).padding(0.dp)) {
            Canvas(Modifier.width(12.dp).height(12.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
        }
        T(label, 13.sp, color = Reso.colors.textSoft)
    }
}

// ---------------------------------------------------------------- QR

fun qrMatrix(text: String, size: Int = 29): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    ))

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFF0B1D3A)) {
    val m = remember(text) { qrMatrix(text, 0) }
    Canvas(modifier.semantics { contentDescription = t("যাচাইয়ের QR কোড", "Verification QR code") }) {
        val cell = size.width / m.width
        drawRect(Color.White)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(color, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Suppress("unused")
private val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
