package net.rapti.resoshare.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.R

/** Kalpurush is used for every Bangla (and Latin) glyph in the app. Bold is synthesized. */
val Kalpurush = FontFamily(Font(R.font.kalpurush, FontWeight.Normal))

/** Brand constants that do not change between light and dark. */
object Brand {
    val Navy = Color(0xFF0B1D3A)
    val Navy2 = Color(0xFF12294F)
    val NavyMid = Color(0xFF17366F)
    val NavyLight = Color(0xFF1F4A93)
    val Amber = Color(0xFFF5A524)
    val Gold = Color(0xFFF8C86B)
    val HeaderMuted = Color(0xFFC3CEE3)
    val HeaderSoft = Color(0xFFD5DEF0)
    val Mint = Color(0xFF7EE2B0)
}

@Immutable
data class Gradient(val start: Color, val end: Color, val border: Color, val label: Color, val value: Color)

@Immutable
data class ResoColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val track: Color,
    val border: Color,
    val inputBorder: Color,
    val text: Color,
    val textSoft: Color,
    val muted: Color,
    val header: Color,
    val headerChip: Color,
    /** Strong ink: navy in light theme, near-white in dark theme (selected chips, + buttons, icons). */
    val ink: Color,
    val onInk: Color,
    val green: Color,
    val red: Color,
    val brown: Color,
    val successBg: Color,
    val warnBg: Color,
    val dangerBg: Color,
    val infoBg: Color,
    val infoBorder: Color,
    val cashBg: Color,
    val cashBorder: Color,
    val tagBlueBg: Color,
    val tagBlueText: Color,
    val rate: Gradient,
    val shares: Gradient,
    val paid: Gradient,
    val rules: Gradient,
    val segmentBg: Color,
    val chartFill: Color,
    val chartLine: Color,
    val chartGrid: Color,
)

private val LightColors = ResoColors(
    dark = false,
    bg = Color(0xFFF3F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFFFFFFF),
    track = Color(0xFFE1E6EF),
    border = Color(0xFFE1E6EF),
    inputBorder = Color(0xFFCBD3E1),
    text = Color(0xFF0F172A),
    textSoft = Color(0xFF334155),
    muted = Color(0xFF5B6578),
    header = Brand.Navy,
    headerChip = Brand.Navy2,
    ink = Brand.Navy,
    onInk = Color.White,
    green = Color(0xFF0F7B4F),
    red = Color(0xFFB42318),
    brown = Color(0xFF8A4B00),
    successBg = Color(0xFFE3F5EC),
    warnBg = Color(0xFFFFF1D6),
    dangerBg = Color(0xFFFDECEC),
    infoBg = Color(0xFFEEF3FF),
    infoBorder = Color(0xFFC7D5F2),
    cashBg = Color(0xFFFFF7E6),
    cashBorder = Color(0xFFF3D9A0),
    tagBlueBg = Color(0xFFE6ECF8),
    tagBlueText = Color(0xFF1F3A6B),
    rate = Gradient(Color(0xFFFFF3D1), Color(0xFFFFE29A), Color(0xFFF3CF7A), Color(0xFF6B3A00), Brand.Navy),
    shares = Gradient(Color(0xFFEAF0FF), Color(0xFFD3E0FF), Color(0xFFBFD0F7), Color(0xFF1F3A6B), Brand.Navy),
    paid = Gradient(Color(0xFFE6F7EE), Color(0xFFC6ECD6), Color(0xFFA9DDBF), Color(0xFF145C3B), Color(0xFF0B3B26)),
    rules = Gradient(Color(0xFFFFF8E7), Color(0xFFFFEBC2), Color(0xFFF3CF7A), Color(0xFF6B3A00), Brand.Navy),
    segmentBg = Color(0xFFE4E8F0),
    chartFill = Color(0x1A0B1D3A),
    chartLine = Brand.Navy,
    chartGrid = Color(0xFFE1E6EF),
)

private val DarkColors = ResoColors(
    dark = true,
    bg = Color(0xFF08101E),
    surface = Color(0xFF111B2D),
    surfaceAlt = Color(0xFF16223A),
    track = Color(0xFF223049),
    border = Color(0xFF1F2C44),
    inputBorder = Color(0xFF34445F),
    text = Color(0xFFE8EDF6),
    textSoft = Color(0xFFC5CFE0),
    muted = Color(0xFF97A3BA),
    header = Color(0xFF0A1830),
    headerChip = Color(0xFF15284A),
    ink = Color(0xFFDCE6F7),
    onInk = Brand.Navy,
    green = Color(0xFF5FD39B),
    red = Color(0xFFF7837A),
    brown = Color(0xFFF5C46B),
    successBg = Color(0xFF113526),
    warnBg = Color(0xFF3A2A0C),
    dangerBg = Color(0xFF3B1618),
    infoBg = Color(0xFF14213B),
    infoBorder = Color(0xFF2A3D66),
    cashBg = Color(0xFF2A2210),
    cashBorder = Color(0xFF5A4718),
    tagBlueBg = Color(0xFF1B2B4D),
    tagBlueText = Color(0xFFB9CBF2),
    rate = Gradient(Color(0xFF3B2B0B), Color(0xFF5A4213), Color(0xFF755821), Color(0xFFF8D28A), Color(0xFFFFF4DB)),
    shares = Gradient(Color(0xFF15254A), Color(0xFF1D3363), Color(0xFF2C467F), Color(0xFFB4C8F2), Color(0xFFEFF4FF)),
    paid = Gradient(Color(0xFF0E3123), Color(0xFF15432F), Color(0xFF22604A), Color(0xFF9BE3BF), Color(0xFFE2FBEE)),
    rules = Gradient(Color(0xFF2E230C), Color(0xFF3F3011), Color(0xFF6B5220), Color(0xFFF5C46B), Color(0xFFFFF1D2)),
    segmentBg = Color(0xFF1A263D),
    chartFill = Color(0x33F5A524),
    chartLine = Color(0xFFDCE6F7),
    chartGrid = Color(0xFF223049),
)

/** True-black (AMOLED) variant of the dark theme: black canvas, near-black cards, amber accents. */
private val BlackColors = DarkColors.copy(
    bg = Color(0xFF000000),
    surface = Color(0xFF0C0C0F),
    surfaceAlt = Color(0xFF131317),
    track = Color(0xFF1D1D23),
    border = Color(0xFF1B1B21),
    inputBorder = Color(0xFF2E2E37),
    text = Color(0xFFF1F1F4),
    textSoft = Color(0xFFCACAD2),
    muted = Color(0xFF8E8E9A),
    header = Color(0xFF000000),
    headerChip = Color(0xFF17171D),
    successBg = Color(0xFF0A1F16),
    warnBg = Color(0xFF231A07),
    dangerBg = Color(0xFF260E10),
    infoBg = Color(0xFF111319),
    infoBorder = Color(0xFF262A36),
    cashBg = Color(0xFF1A1509),
    cashBorder = Color(0xFF3A2F12),
    tagBlueBg = Color(0xFF151925),
    tagBlueText = Color(0xFFB9C6E6),
    rate = Gradient(Color(0xFF1C1405), Color(0xFF332409), Color(0xFF4A3712), Color(0xFFF8D28A), Color(0xFFFFF4DB)),
    shares = Gradient(Color(0xFF0B0F1C), Color(0xFF141B33), Color(0xFF232D4C), Color(0xFFB4C8F2), Color(0xFFEFF4FF)),
    paid = Gradient(Color(0xFF06150D), Color(0xFF0C281A), Color(0xFF173B2B), Color(0xFF9BE3BF), Color(0xFFE2FBEE)),
    rules = Gradient(Color(0xFF1A1407), Color(0xFF2B210B), Color(0xFF4A3817), Color(0xFFF5C46B), Color(0xFFFFF1D2)),
    segmentBg = Color(0xFF141419),
    chartFill = Color(0x2EF5A524),
    chartGrid = Color(0xFF1D1D23),
)

/** Palette of a theme mode (SYSTEM resolves with [systemDark]); used by the theme picker previews. */
fun paletteOf(mode: ThemeMode, systemDark: Boolean): ResoColors = when (mode) {
    ThemeMode.SYSTEM -> if (systemDark) DarkColors else LightColors
    ThemeMode.LIGHT -> LightColors
    ThemeMode.DARK -> DarkColors
    ThemeMode.BLACK -> BlackColors
}

val LocalReso = staticCompositionLocalOf { LightColors }

object Reso {
    val colors: ResoColors
        @Composable get() = LocalReso.current
}

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, lh: Double = 1.45) = TextStyle(
    fontFamily = Kalpurush,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lh).sp,
    letterSpacing = 0.em,
)

private val ResoTypography = Typography(
    displayLarge = style(38, FontWeight.Bold, 1.25),
    displayMedium = style(34, FontWeight.Bold, 1.25),
    displaySmall = style(30, FontWeight.Bold, 1.25),
    headlineLarge = style(26, FontWeight.Bold, 1.3),
    headlineMedium = style(22, FontWeight.Bold, 1.3),
    headlineSmall = style(21, FontWeight.Bold, 1.3),
    titleLarge = style(19, FontWeight.Bold, 1.35),
    titleMedium = style(17, FontWeight.Bold, 1.4),
    titleSmall = style(15, FontWeight.Bold, 1.4),
    bodyLarge = style(16),
    bodyMedium = style(15),
    bodySmall = style(13),
    labelLarge = style(15, FontWeight.Bold),
    labelMedium = style(13, FontWeight.Bold),
    labelSmall = style(12, FontWeight.Bold),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

@Composable
fun ResoTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val c = paletteOf(mode, isSystemInDarkTheme())
    val dark = c.dark
    val scheme = if (dark) {
        darkColorScheme(
            primary = Brand.Amber, onPrimary = Brand.Navy,
            secondary = c.ink, onSecondary = c.onInk,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted,
            outline = c.inputBorder, error = c.red,
            surfaceContainerHigh = c.surface, surfaceContainer = c.surface,
        )
    } else {
        lightColorScheme(
            primary = Brand.Navy, onPrimary = Color.White,
            secondary = Brand.Amber, onSecondary = Brand.Navy,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted,
            outline = c.inputBorder, error = c.red,
            surfaceContainerHigh = c.surface, surfaceContainer = c.surface,
        )
    }
    CompositionLocalProvider(LocalReso provides c) {
        MaterialTheme(colorScheme = scheme, typography = ResoTypography, content = content)
    }
}
