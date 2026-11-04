package net.rapti.resoshare.user

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.export.Exporter
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.ui.CheckBox
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SplitBar
import net.rapti.resoshare.core.ui.StepButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.headerBubble
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.NomineeInput
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ================================================================ step header

/**
 * Navy header with back button, title, optional "১ / ৩" counter and a labelled 3-step
 * progress (registration and purchase flows, v2 design).
 */
@Composable
fun StepHeader(title: String, steps: List<String>, current: Int, onBack: () -> Unit, showCounter: Boolean = true) {
    val c = Reso.colors
    Column(
        Modifier.fillMaxWidth().background(c.header).headerBubble().statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIconButton(ResoIcons.Back, t("পেছনে যান", "Back"), onBack)
            T(title, 22.sp, FontWeight.Bold, Color.White, modifier = Modifier.weight(1f), maxLines = 1)
            if (showCounter) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(c.headerChip).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    T("${Bn.num(current + 1)} / ${Bn.num(steps.size)}", 13.sp, FontWeight.Bold, Brand.HeaderMuted)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { i, label ->
                val done = i < current
                val active = i == current
                val bar by animateColorAsState(
                    when { done -> Brand.Amber; active -> Brand.Amber.copy(alpha = 0.55f); else -> Color.White.copy(alpha = 0.18f) },
                    tween(300), label = "stepbar",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(bar))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).background(
                                when { done -> Brand.Amber; active -> Color.White; else -> Color.White.copy(alpha = 0.18f) },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) Icon(ResoIcons.Check, null, tint = Brand.Navy, modifier = Modifier.size(12.dp))
                            else T(Bn.num(i + 1), 10.sp, FontWeight.Bold, if (active) Brand.Navy else Brand.HeaderMuted, lineHeight = 12.sp)
                        }
                        T(label, 12.sp, if (active) FontWeight.Bold else FontWeight.Normal, if (active || done) Color.White else Brand.HeaderMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Three-bar password strength meter (v2 registration). */
@Composable
fun PasswordStrength(password: String) {
    val c = Reso.colors
    val score = if (password.isEmpty()) 0 else V.strength(password)
    val color = when (score) { 1 -> c.red; 2 -> Brand.Amber; 3 -> c.green; else -> c.track }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val bg by animateColorAsState(if (i < score) color else c.track, tween(200), label = "pw")
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(bg))
            }
        }
        T(
            when (score) {
                0 -> t("কমপক্ষে ৮ অক্ষর, একটি সংখ্যা ও একটি চিহ্ন দিন", "At least 8 characters, a number and a symbol")
                1 -> t("দুর্বল পাসওয়ার্ড", "Weak password")
                2 -> t("মাঝারি — একটি চিহ্ন (যেমন @ # !) যোগ করুন", "Fair — add a symbol (e.g. @ # !)")
                else -> t("শক্ত পাসওয়ার্ড", "Strong password")
            },
            13.sp, color = if (score in 1..2 && score < 3) c.muted else if (score == 3) c.green else c.muted,
        )
    }
}

// ================================================================ photo tiles

/**
 * Upload tile for KYC photos (v2): dashed when empty, green with a check when done.
 * Tapping offers camera or gallery; the image is re-encoded (EXIF/GPS stripped) before upload.
 */
@Composable
fun ImagePickTile(label: String, bytes: ByteArray?, onPicked: (ByteArray) -> Unit, error: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Reso.colors
    var choose by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun accept(uri: Uri) = scope.launch {
        val data = withContext(Dispatchers.IO) { Exporter.compressImage(ctx, uri) }
        when {
            data == null -> Toaster.show(t("ছবিটি পড়া যায়নি। অন্য একটি ছবি দিন।", "Could not read that image. Try another one."))
            data.size > 5 * 1024 * 1024 -> Toaster.show(t("ছবিটি ৫ MB এর বেশি। ছোট ছবি দিন।", "Image is over 5 MB. Use a smaller one."))
            else -> onPicked(data)
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) accept(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) accept(uri).invokeOnCompletion {
            runCatching { File(ctx.cacheDir, "camera").listFiles()?.forEach { f -> f.delete() } }
        }
    }

    val thumb = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
    val done = bytes != null
    val borderColor = when { error -> c.red; done -> c.green; else -> c.inputBorder }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (done) c.successBg else c.surface)
            .then(
                if (done) Modifier.border(1.5.dp, borderColor, shape)
                else Modifier.drawBehind {
                    drawRoundRect(borderColor, cornerRadius = CornerRadius(14.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                },
            )
            .pressable(onClick = { choose = true })
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (done && thumb != null) {
            Box {
                Image(thumb.asImageBitmap(), null, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(c.green), contentAlignment = Alignment.Center) {
                    Icon(ResoIcons.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
        } else {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(c.infoBg), contentAlignment = Alignment.Center) {
                Icon(ResoIcons.Upload, null, tint = c.ink, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            T(label, 16.sp, FontWeight.Bold)
            T(
                if (done) t("আপলোড হয়েছে · বদলাতে চাপুন", "Uploaded · tap to change")
                else t("ছবি তুলুন বা গ্যালারি থেকে দিন", "Take a photo or choose from gallery"),
                13.sp, color = if (done) c.green else c.muted,
            )
        }
    }

    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            containerColor = c.surface,
            title = { T(label, 18.sp, FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SourceOption(ResoIcons.Image, t("ক্যামেরা দিয়ে ছবি তুলুন", "Take a photo")) {
                        choose = false
                        val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                        val f = File(dir, "kyc-${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        cameraUri = uri
                        runCatching { camera.launch(uri) }.onFailure {
                            Toaster.show(t("ক্যামেরা খোলা যায়নি", "Could not open the camera"))
                        }
                    }
                    SourceOption(ResoIcons.Upload, t("গ্যালারি থেকে বাছুন", "Choose from gallery")) {
                        choose = false
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choose = false }) { T(t("বাতিল", "Cancel"), 16.sp, color = c.muted) } },
        )
    }
}

@Composable
private fun SourceOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Reso.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp)).background(c.bg)
            .pressable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(22.dp))
        T(label, 16.sp, FontWeight.Bold)
    }
}

// ================================================================ date field

/** Read-only field that opens a date picker. Value is an ISO date or "". */
@Composable
fun DateField(
    label: String,
    iso: String,
    onPick: (String) -> Unit,
    error: String? = null,
    placeholder: String = t("দিন / মাস / বছর", "Day / month / year"),
    maxDate: LocalDate? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Field(label, if (iso.isBlank()) "" else Bn.date(iso), {}, placeholder = placeholder, error = error, leading = ResoIcons.Calendar)
        // Transparent overlay captures taps so the keyboard never opens.
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).pressable(onClick = { open = true }))
    }
    if (open) {
        val initial = Bn.parseDate(iso)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            ?: LocalDate.of(1990, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        if (maxDate == null || !d.isAfter(maxDate)) onPick(d.toString())
                        else Toaster.show(t("সঠিক তারিখ বাছাই করুন", "Choose a valid date"))
                    }
                    open = false
                }) { T(t("ঠিক আছে", "OK"), 16.sp, FontWeight.Bold, Reso.colors.ink) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { T(t("বাতিল", "Cancel"), 16.sp, color = Reso.colors.muted) } },
        ) { DatePicker(state = state, showModeToggle = true) }
    }
}

// ================================================================ nominees

@Stable
class NomineeForm {
    var name by mutableStateOf("")
    var relation by mutableStateOf("")
    var idType by mutableStateOf("nid")
    var idNumber by mutableStateOf("")
    var errors by mutableStateOf(mapOf<String, String>())

    fun validate(): Boolean {
        errors = buildMap {
            V.name(name)?.let { put("name", it) }
            V.relation(relation)?.let { put("relation", it) }
            V.nomineeId(idType, idNumber)?.let { put("id", it) }
        }
        return errors.isEmpty()
    }

    fun toInput(percent: Int) = NomineeInput(name.trim(), relation.trim(), idType, V.digitsOnly(idNumber), percent)
}

@Stable
class NomineesState {
    var count by mutableIntStateOf(2)
    var firstPercent by mutableIntStateOf(60)
    val forms = listOf(NomineeForm(), NomineeForm())

    fun percents(): List<Int> = if (count == 1) listOf(100) else listOf(firstPercent, 100 - firstPercent)
    fun validate(): Boolean {
        var ok = true
        for (i in 0 until count) if (!forms[i].validate()) ok = false
        return ok
    }
    fun inputs(): List<NomineeInput> = percents().mapIndexed { i, p -> forms[i].toInput(p) }
}

/** Nominee editor (1 or 2 nominees, NID / birth-registration tick boxes, ownership split). */
@Composable
fun NomineeEditor(state: NomineesState) {
    val c = Reso.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(1, 2).forEach { n ->
                val sel = state.count == n
                val shape = RoundedCornerShape(24.dp)
                Box(
                    Modifier.weight(1f).height(48.dp).clip(shape).background(if (sel) c.ink else c.surface).border(1.5.dp, c.ink, shape)
                        .pressable(onClick = { state.count = n }),
                    contentAlignment = Alignment.Center,
                ) { T(t("${Bn.num(n)} জন নমিনি", if (n == 1) "1 nominee" else "2 nominees"), 16.sp, FontWeight.Bold, if (sel) c.onInk else c.text) }
            }
        }
        val percents = state.percents()
        for (i in 0 until state.count) {
            val f = state.forms[i]
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t("নমিনি ${Bn.num(i + 1)}", "Nominee ${i + 1}"), 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    Tag(Bn.num(percents[i]) + "%", if (i == 0) c.ink else Brand.Amber, if (i == 0) c.onInk else Brand.Navy, size = 14.sp)
                }
                Field(t("নাম", "Name"), f.name, { f.name = it }, placeholder = t("নমিনির পুরো নাম", "Nominee's full name"), error = f.errors["name"], maxLength = 100)
                Field(t("সম্পর্ক", "Relationship"), f.relation, { f.relation = it }, placeholder = t("যেমন: স্ত্রী, পুত্র", "e.g. wife, son"), error = f.errors["relation"], maxLength = 30)
                T(t("কোন পরিচয়পত্র দিচ্ছেন? (টিক দিন)", "Which ID are you giving? (tick one)"), 14.sp, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IdTypeBox(t("এনআইডি (NID)", "NID"), f.idType == "nid", Modifier.weight(1f)) { f.idType = "nid"; f.errors = f.errors - "id" }
                    IdTypeBox(t("জন্ম নিবন্ধন", "Birth registration"), f.idType == "birth_reg", Modifier.weight(1f)) { f.idType = "birth_reg"; f.errors = f.errors - "id" }
                }
                Field(
                    if (f.idType == "nid") t("এনআইডি (NID) নম্বর", "NID number") else t("জন্ম নিবন্ধন নম্বর", "Birth registration number"), f.idNumber, { f.idNumber = it },
                    placeholder = if (f.idType == "nid") t("নমিনির এনআইডি নম্বর লিখুন", "Nominee's NID number") else t("১৭ অঙ্কের জন্ম নিবন্ধন নম্বর", "17-digit birth registration number"),
                    keyboard = KeyboardType.Number, error = f.errors["id"], maxLength = 20,
                )
            }
        }
        if (state.count == 2) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T(t("মালিকানার ভাগ", "Ownership split"), 17.sp, FontWeight.Bold)
                SplitBar(state.firstPercent / 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t("নমিনি ১ এর ভাগ", "Nominee 1 share"), 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    StepButton(false, { state.firstPercent = (state.firstPercent - 5).coerceAtLeast(5) }, size = 44.dp, enabled = state.firstPercent > 5)
                    T(Bn.num(state.firstPercent) + "%", 22.sp, FontWeight.Bold, modifier = Modifier.width(76.dp), align = TextAlign.Center)
                    StepButton(true, { state.firstPercent = (state.firstPercent + 5).coerceAtMost(95) }, size = 44.dp, enabled = state.firstPercent < 95)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t("নমিনি ২ এর ভাগ", "Nominee 2 share"), 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    T(Bn.num(100 - state.firstPercent) + "%", 18.sp, FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t("মোট", "Total"), 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    T(Bn.num(100) + "% ✓", 16.sp, FontWeight.Bold, c.green)
                }
            }
        }
    }
}

@Composable
private fun IdTypeBox(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.heightIn(min = 52.dp).clip(shape).background(if (selected) c.infoBg else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.inputBorder, shape)
            .pressable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckBox(selected, { onClick() })
        T(label, 15.sp, FontWeight.Bold)
    }
}

@Composable
fun NumberDisc(n: Int, done: Boolean = false) = TextDisc(Bn.num(n), if (done) Reso.colors.green else Reso.colors.ink, if (done) Color.White else Reso.colors.onInk, 32.dp, 15.sp)

