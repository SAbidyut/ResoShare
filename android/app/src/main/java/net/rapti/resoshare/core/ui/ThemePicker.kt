package net.rapti.resoshare.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.core.ui.theme.ResoColors
import net.rapti.resoshare.core.ui.theme.ThemeMode
import net.rapti.resoshare.core.ui.theme.paletteOf

/** 2×2 grid of theme tiles, each with a live mini preview of that theme. */
@Composable
fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> ThemeTile(m, m == selected, Modifier.weight(1f)) { onSelect(m) } }
            }
        }
    }
}

@Composable
private fun ThemeTile(mode: ThemeMode, sel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val sysDark = isSystemInDarkTheme()
    val border by animateColorAsState(if (sel) Brand.Amber else c.border, tween(200), label = "themeBorder")
    val shape = RoundedCornerShape(14.dp)
    val (title, note) = when (mode) {
        ThemeMode.SYSTEM -> t("সিস্টেম", "System") to t("ফোনের মতো", "Follows phone")
        ThemeMode.LIGHT -> t("লাইট", "Light") to t("উজ্জ্বল", "Bright")
        ThemeMode.DARK -> t("ডার্ক", "Dark") to t("নেভি ডার্ক", "Navy dark")
        ThemeMode.BLACK -> t("পিচ ব্ল্যাক", "Pitch black") to t("পুরো কালো · AMOLED", "True black · AMOLED")
    }
    Column(
        modifier
            .clip(shape)
            .border(if (sel) 2.dp else 1.dp, border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = sel }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            Canvas(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(9.dp))) {
                if (mode == ThemeMode.SYSTEM) {
                    clipRect(right = size.width / 2) { preview(paletteOf(ThemeMode.LIGHT, false)) }
                    clipRect(left = size.width / 2) { preview(paletteOf(ThemeMode.DARK, true)) }
                } else preview(paletteOf(mode, sysDark))
            }
            if (sel) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).background(Brand.Amber, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(ResoIcons.Check, null, tint = Brand.Navy, modifier = Modifier.size(13.dp)) }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp)) {
            T(title, 14.sp, FontWeight.Bold, if (sel) c.text else c.textSoft, maxLines = 1)
            T(note, 11.sp, color = c.muted, maxLines = 1)
        }
    }
}

/** Tiny mock screen: header with amber pill, a card with two text lines and an accent dot. */
private fun DrawScope.preview(p: ResoColors) {
    val w = size.width; val h = size.height
    drawRect(p.bg)
    drawRect(p.header, size = Size(w, h * 0.34f))
    drawRoundRect(Brand.Amber, Offset(w * 0.08f, h * 0.11f), Size(w * 0.26f, h * 0.12f), CornerRadius(h * 0.06f))
    drawCircle(Color.White.copy(alpha = 0.08f), radius = h * 0.32f, center = Offset(w * 0.92f, 0f))
    val top = Offset(w * 0.08f, h * 0.45f)
    val card = Size(w * 0.84f, h * 0.44f)
    drawRoundRect(p.surface, top, card, CornerRadius(h * 0.08f))
    drawRoundRect(p.border, top, card, CornerRadius(h * 0.08f), style = Stroke(1.dp.toPx()))
    drawCircle(Brand.Amber, radius = h * 0.07f, center = Offset(top.x + h * 0.15f, top.y + card.height / 2))
    drawRoundRect(p.text.copy(alpha = 0.75f), Offset(top.x + h * 0.3f, top.y + card.height * 0.28f), Size(card.width * 0.5f, h * 0.06f), CornerRadius(h * 0.03f))
    drawRoundRect(p.muted.copy(alpha = 0.6f), Offset(top.x + h * 0.3f, top.y + card.height * 0.58f), Size(card.width * 0.32f, h * 0.05f), CornerRadius(h * 0.03f))
}
