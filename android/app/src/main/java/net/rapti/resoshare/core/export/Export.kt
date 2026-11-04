package net.rapti.resoshare.core.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import net.rapti.resoshare.R
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.qrMatrix
import net.rapti.resoshare.data.api.Certificate
import net.rapti.resoshare.data.api.Report
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A generated file kept in memory until the user saves or shares it. */
class ExportFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Local document generation. PDFs are drawn with Android's own text engine so Bangla
 * conjuncts render correctly in Kalpurush (server-side PDF libraries break Bangla shaping).
 */
object Exporter {

    // ------------------------------------------------------------------ CSV / XLSX

    /** Neutralise spreadsheet formula injection (=, +, -, @, tab, CR at cell start). */
    fun safeCell(v: String): String = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v

    fun csv(report: Report): ExportFile {
        val sb = StringBuilder("\uFEFF") // BOM so Excel shows Bangla correctly
        fun line(cells: List<String>) = cells.joinToString(",") { c ->
            val v = safeCell(c)
            if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }
        sb.append(line(report.columns)).append("\r\n")
        report.rows.forEach { sb.append(line(it)).append("\r\n") }
        return ExportFile(fileName(report, "csv"), "text/csv", sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun xml(s: String) = buildString {
        for (ch in safeCell(s)) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') append(ch)
        }
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    /** Minimal valid XLSX (inline strings, bold header row). */
    fun xlsx(report: Report): ExportFile {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(path: String, content: String) { z.putNextEntry(ZipEntry(path)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Report" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""")
            val sheet = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            val all = listOf(report.columns) + report.rows
            all.forEachIndexed { r, row ->
                sheet.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    sheet.append("<c r=\"${col(c)}${r + 1}\" t=\"inlineStr\"${if (r == 0) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${xml(v)}</t></is></c>")
                }
                sheet.append("</row>")
            }
            sheet.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return ExportFile(fileName(report, "xlsx"), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray())
    }

    private fun fileName(r: Report, ext: String) = "ResoShare-${r.type}-${Bn.today()}.$ext"

    // ------------------------------------------------------------------ PDF

    private fun font(ctx: Context): Typeface = ResourcesCompat.getFont(ctx, R.font.kalpurush) ?: Typeface.DEFAULT

    private fun paint(ctx: Context, size: Float, color: Int = Color.rgb(15, 23, 42), bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font(ctx); textSize = size; this.color = color; isFakeBoldText = bold
    }

    private fun Canvas.text(t: String, p: TextPaint, x: Float, y: Float, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
        val l = StaticLayout.Builder.obtain(t, 0, t.length, p, width.coerceAtLeast(1)).setAlignment(align).setIncludePad(false).build()
        save(); translate(x, y); l.draw(this); restore()
        return l.height.toFloat()
    }

    /** Tabular report as an A4-landscape PDF with header, page numbers and repeated column headers. */
    fun pdf(ctx: Context, report: Report, company: String): ExportFile {
        val doc = PdfDocument()
        val w = 842; val h = 595; val margin = 32f
        val cols = report.columns.size.coerceAtLeast(1)
        val colW = ((w - margin * 2) / cols).toInt()
        val head = paint(ctx, 9.5f, Color.WHITE, true)
        val body = paint(ctx, 9.5f)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val c = page!!.canvas
            c.drawRect(0f, 0f, w.toFloat(), 64f, Paint().apply { color = Color.rgb(11, 29, 58) })
            c.text("$company · ${report.title}", paint(ctx, 16f, Color.WHITE, true), margin, 14f, w - 200)
            c.text("তৈরি: ${Bn.date(Bn.today())} · পৃষ্ঠা ${Bn.num(pageNo)}", paint(ctx, 10f, Color.rgb(248, 200, 107)), margin, 40f, w - 200)
            y = 80f
            val hh = report.columns.maxOf { StaticLayout.Builder.obtain(it, 0, it.length, head, colW - 8).build().height } + 10f
            c.drawRect(margin, y, w - margin, y + hh, Paint().apply { color = Color.rgb(18, 41, 79) })
            report.columns.forEachIndexed { i, t -> c.text(t, head, margin + i * colW + 4, y + 5, colW - 8) }
            y += hh
        }
        newPage()
        val zebra = Paint().apply { color = Color.rgb(243, 245, 249) }
        report.rows.forEachIndexed { r, row ->
            val rh = row.maxOfOrNull { StaticLayout.Builder.obtain(it, 0, it.length, body, colW - 8).build().height }?.plus(10f) ?: 20f
            if (y + rh > h - margin) newPage()
            val c = page!!.canvas
            if (r % 2 == 1) c.drawRect(margin, y, w - margin, y + rh, zebra)
            row.forEachIndexed { i, t -> c.text(t, body, margin + i * colW + 4, y + 5, colW - 8) }
            y += rh
        }
        if (report.rows.isEmpty()) page!!.canvas.text("এই সময়ে কোনো তথ্য নেই।", body, margin, y + 12, w - 64)
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return ExportFile(fileName(report, "pdf"), "application/pdf", out.toByteArray())
    }

    /** Share certificate PDF (A4 portrait) matching the in-app design. */
    fun certificatePdf(ctx: Context, c: Certificate, chairman: String = "চেয়ারম্যান"): ExportFile {
        val doc = PdfDocument()
        val w = 595; val h = 842
        val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val cv = page.canvas
        val navy = Color.rgb(11, 29, 58); val amber = Color.rgb(245, 165, 36)
        cv.drawColor(Color.WHITE)
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = navy }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = amber }
        cv.drawRoundRect(RectF(30f, 30f, w - 30f, h - 30f), 18f, 18f, outer)
        cv.drawRoundRect(RectF(42f, 42f, w - 42f, h - 42f), 12f, 12f, inner)
        // Logo
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy }
        cv.drawRoundRect(RectF(150f, 90f, 196f, 136f), 12f, 12f, lp)
        val wave = android.graphics.Path().apply {
            moveTo(157f, 114f); cubicTo(160.5f, 99f, 166.5f, 99f, 170f, 114f); cubicTo(173.5f, 129f, 178f, 129f, 181.5f, 114f); cubicTo(185f, 99f, 186f, 104f, 189.5f, 111f)
        }
        cv.drawPath(wave, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.4f; color = amber; strokeCap = Paint.Cap.ROUND })
        cv.text(c.companyName, paint(ctx, 22f, navy, true), 206f, 97f, 300)
        val center = Layout.Alignment.ALIGN_CENTER
        cv.text("শেয়ার সার্টিফিকেট", paint(ctx, 34f, navy, true), 60f, 170f, w - 120, center)
        cv.text("এই মর্মে প্রত্যয়ন করা যাচ্ছে যে", paint(ctx, 15f, Color.rgb(91, 101, 120)), 60f, 230f, w - 120, center)
        cv.text(c.fullName, paint(ctx, 30f, navy, true), 60f, 262f, w - 120, center)
        cv.text("কোম্পানির ${Bn.num(c.shares)}টি শেয়ারের মালিক", paint(ctx, 18f), 60f, 318f, w - 120, center)
        cv.drawLine(80f, 372f, w - 80f, 372f, Paint().apply { color = Color.rgb(225, 230, 239) })
        val label = paint(ctx, 14f, Color.rgb(91, 101, 120)); val value = paint(ctx, 14f, navy, true)
        var y = 392f
        listOf("শেয়ারহোল্ডার ID" to c.shareholderNo, "সার্টিফিকেট নম্বর" to c.certificateNo, "ইস্যুর তারিখ" to Bn.date(c.issuedOn), "শেয়ার সংখ্যা" to "${Bn.num(c.shares)}টি").forEach { (k, v) ->
            cv.text(k, label, 80f, y, 220)
            cv.text(v, value, 300f, y, w - 380, Layout.Alignment.ALIGN_OPPOSITE)
            y += 30f
        }
        // QR
        val m = qrMatrix(c.verifyUrl, 0)
        val qrSize = 130f; val qx = w - 80f - qrSize; val qy = 540f
        val cell = qrSize / m.width
        val qp = Paint().apply { color = navy }
        for (yy in 0 until m.height) for (xx in 0 until m.width) if (m[xx, yy]) cv.drawRect(qx + xx * cell, qy + yy * cell, qx + (xx + 1) * cell + 0.3f, qy + (yy + 1) * cell + 0.3f, qp)
        cv.text("স্ক্যান করে যাচাই করুন", paint(ctx, 11f, Color.rgb(91, 101, 120)), qx - 20f, qy + qrSize + 8f, (qrSize + 40).toInt(), center)
        cv.drawLine(80f, 650f, 260f, 650f, Paint().apply { color = navy; strokeWidth = 1.2f })
        cv.text(chairman, paint(ctx, 13f, Color.rgb(91, 101, 120)), 80f, 658f, 200)
        cv.text("যাচাই লিংক: ${c.verifyUrl}", paint(ctx, 8.5f, Color.rgb(91, 101, 120)), 60f, h - 80f, w - 120, center)
        doc.finishPage(page)
        val out = ByteArrayOutputStream()
        doc.writeTo(out); doc.close()
        return ExportFile("Share-Certificate-${c.certificateNo}.pdf", "application/pdf", out.toByteArray())
    }

    // ------------------------------------------------------------------ sharing

    /** Writes into the app's private cache and returns a share intent (FileProvider, read-only grant). */
    fun shareIntent(ctx: Context, f: ExportFile): Intent {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 10 * 60_000) it.delete() }
        val file = File(dir, f.name)
        file.writeBytes(f.bytes)
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = f.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(f.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "শেয়ার করুন")
    }

    fun writeTo(ctx: Context, uri: Uri, f: ExportFile) {
        ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(f.bytes) } ?: error("cannot open")
    }

    /** Re-encode a picked image: max 1600px, JPEG 85%. Strips EXIF/GPS metadata. */
    fun compressImage(ctx: Context, uri: Uri): ByteArray? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / sample > 3200 || opts.outHeight / sample > 3200) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException()
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    } catch (_: Exception) {
        null
    }
}
