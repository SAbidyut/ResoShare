package net.rapti.resoshare.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons taken from the approved design (24×24 viewBox, stroke 2, round caps).
 * Circles/rects are expressed as path data so everything is a single stroke style.
 */
object ResoIcons {
    private fun line(name: String, vararg paths: String, stroke: Float = 2f): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(p).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx},$y h${w - 2 * rx} a$rx,$rx 0 0 1 $rx,$rx v${h - 2 * rx} a$rx,$rx 0 0 1 ${-rx},$rx " +
            "h${-(w - 2 * rx)} a$rx,$rx 0 0 1 ${-rx},${-rx} v${-(h - 2 * rx)} a$rx,$rx 0 0 1 $rx,${-rx} z"

    val Back = line("back", "M15 5l-7 7 7 7")
    val Chevron = line("chev", "M9 6l6 6-6 6")
    val ChevronDown = line("chevd", "M6 9l6 6 6-6")
    val ChevronUp = line("chevu", "M6 15l6-6 6 6")
    val Bell = line("bell", "M6 17V11a6 6 0 0 1 12 0v6l2 2H4z", "M10 21h4")
    val Home = line("home", "M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    val PlusCircle = line("plusc", circle(12f, 12f, 9f), "M12 8v8M8 12h8")
    val Chart = line("chart", "M3 17l5-6 4 4 8-9", "M15 6h5v5")
    val User = line("user", circle(12f, 8f, 4f), "M4 21c0-4 4-6 8-6s8 2 8 6")
    val Users = line("users", circle(9f, 8f, 3.5f), "M2.5 20c0-3.5 3-5.5 6.5-5.5s6.5 2 6.5 5.5", "M16 4.6a3.5 3.5 0 0 1 0 6.8", "M18 14.8c2.2.6 3.5 2.4 3.5 5.2")
    val Layers = line("layers", "M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5")
    val Wallet = line("wallet", rect(3f, 6f, 18f, 13f, 2f), "M3 10h18M16 14.5h2")
    val Shield = line("shield", "M12 3l8 3v6c0 4.5-3.4 7.7-8 9-4.6-1.3-8-4.5-8-9V6z", "M9 12l2 2 4-4")
    val Document = line("doc", rect(4f, 3f, 16f, 18f, 2f), "M8 8h8M8 12h8M8 16h4")
    val List = line("list", "M4 7h16M4 12h16M4 17h10")
    val Minus = line("minus", "M6 12h12", stroke = 2.4f)
    val Plus = line("plus", "M12 6v12M6 12h12", stroke = 2.4f)
    val Download = line("download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val Upload = line("upload", "M12 16V5", "M7 10l5-5 5 5", "M5 20h14")
    val Share = line("share", circle(18f, 5f, 2.5f), circle(6f, 12f, 2.5f), circle(18f, 19f, 2.5f), "M8.2 10.8l7.6-4.4M8.2 13.2l7.6 4.4")
    val Grid = line("grid", rect(4f, 4f, 6.5f, 6.5f, 1.2f), rect(13.5f, 4f, 6.5f, 6.5f, 1.2f), rect(4f, 13.5f, 6.5f, 6.5f, 1.2f), rect(13.5f, 13.5f, 6.5f, 6.5f, 1.2f))
    val CheckCircle = line("checkc", circle(12f, 12f, 9f), "M8 12.5l2.8 2.8L16 10")
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5", stroke = 2.6f)
    val More = line("more", "M5 12h.01M12 12h.01M19 12h.01", stroke = 3f)
    val Dollar = line("dollar", circle(12f, 12f, 9f), "M14.8 9.2c-.5-1-1.6-1.6-2.8-1.6-1.6 0-2.8.9-2.8 2.1 0 2.8 5.7 1.6 5.7 4.5 0 1.3-1.3 2.2-2.9 2.2-1.3 0-2.4-.6-2.9-1.6", "M12 6v1.6M12 16.4V18")
    val Report = line("report", rect(4f, 3f, 16f, 18f, 2f), "M8 8h8M8 12h8M8 16h5")
    val Clock = line("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Sun = line("sun", circle(12f, 12f, 3.5f), "M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4")
    val Moon = line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Lock = line("lock", rect(5f, 11f, 14f, 10f, 2f), "M8 11V8a4 4 0 0 1 8 0v3")
    val Key = line("key", circle(8f, 15f, 4f), "M11 12l9-9M17 6l3 3M15 8l2 2")
    val Logout = line("logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5M21 12H9")
    val Calendar = line("cal", rect(3f, 5f, 18f, 16f, 2f), "M3 10h18M8 3v4M16 3v4")
    val Info = line("info", circle(12f, 12f, 9f), "M12 11v5M12 8h.01")
    val Search = line("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Close = line("close", "M6 6l12 12M18 6L6 18")
    val Eye = line("eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z", circle(12f, 12f, 3f))
    val EyeOff = line("eyeoff", "M3 3l18 18", "M10.6 5.1A10.4 10.4 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-3.1 3.9M6.6 6.6C3.9 8.4 2 12 2 12s3.6 7 10 7c1.8 0 3.4-.5 4.8-1.3", "M9.9 9.9a3 3 0 0 0 4.2 4.2")
    val Phone = line("phone", "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2")
    val Image = line("image", rect(3f, 4f, 18f, 16f, 2f), circle(8.5f, 9.5f, 1.8f), "M21 16l-5-5-9 9")
    val Fingerprint = line("fp", "M12 11v3.5c0 2.4-.6 4.6-1.7 6.5", "M8.5 7.6A5 5 0 0 1 17 11v1.5", "M7 11a5 5 0 0 1 .2-1.4", "M17 15.5c-.2 1.8-.7 3.4-1.4 4.8", "M4.6 8A8.5 8.5 0 0 1 20.5 11v2", "M4 12v1.5c0 1.4.2 2.7.6 3.9")
    val Refresh = line("refresh", "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val Edit = line("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Trash = line("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val ArrowUp = line("aup", "M12 19V5M6 11l6-6 6 6")
    val ArrowDown = line("adown", "M12 5v14M6 13l6 6 6-6")
    val Building = line("bank", "M3 10l9-6 9 6", "M5 10v8M9.5 10v8M14.5 10v8M19 10v8", "M3 20h18")
    val Cash = line("cash", rect(2.5f, 6f, 19f, 12f, 2f), circle(12f, 12f, 2.5f), "M6 9.5v.01M18 14.5v.01")
    val Megaphone = line("mega", "M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1z", "M15 9a3.5 3.5 0 0 1 0 6", "M18 6.5a7 7 0 0 1 0 11")
    val Pause = line("pause", circle(12f, 12f, 9f), "M10 9v6M14 9v6")
    val Play = line("play", circle(12f, 12f, 9f), "M10 8.5v7l5.5-3.5z")
    val Mail = line("mail", rect(3f, 5f, 18f, 14f, 2f), "M3.5 7l8.5 6 8.5-6")
    val IdCard = line("idcard", rect(3f, 5f, 18f, 14f, 2f), circle(9f, 11f, 2f), "M6 16c.6-1.4 1.7-2.2 3-2.2s2.4.8 3 2.2", "M14.5 10h4M14.5 13h3")
    val Award = line("award", circle(12f, 9f, 5.5f), "M8.7 13.6L7.5 21l4.5-2.4 4.5 2.4-1.2-7.4")
    val DoubleCheck = line("dcheck", "M2 12.5l4 4L14 8.5", "M10.5 16.2l.8.8L21 8.5")
    val Target = line("target", circle(12f, 12f, 9f), circle(12f, 12f, 5f), circle(12f, 12f, 1f))
    val Globe = line("globe", circle(12f, 12f, 9f), "M3 12h18", "M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z")
}
