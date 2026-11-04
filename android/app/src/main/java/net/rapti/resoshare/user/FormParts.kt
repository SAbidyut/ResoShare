package net.rapti.resoshare.user

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.export.Exporter
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.ui.CheckBox
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SplitBar
import net.rapti.resoshare.core.ui.StepButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.NomineeInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Three-segment progress used by the registration wizard. */
@Composable
fun StepBars(step: Int, total: Int = 3) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val active = i <= step
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (active) Reso.colors.ink else Reso.colors.track))
        }
    }
}

/** Dashed upload tile; shows a thumbnail once an image is picked. */
@Composable
fun ImagePickTile(label: String, bytes: ByteArray?, onPicked: (ByteArray) -> Unit, error: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Reso.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) { Exporter.compressImage(ctx, uri) }
            when {
                data == null -> Toaster.show("ছবিটি পড়া যায়নি। অন্য একটি ছবি দিন।")
                data.size > 5 * 1024 * 1024 -> Toaster.show("ছবিটি ৫ MB এর বেশি। ছোট ছবি দিন।")
                else -> onPicked(data)
            }
        }
    }
    val thumb = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
    val borderColor = if (error) c.red else if (bytes != null) c.green else c.inputBorder
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (bytes != null) c.successBg else c.surfaceAlt)
            .drawBehind {
                drawRoundRect(
                    borderColor, cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
            .pressable(onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        if (thumb != null) {
            Image(thumb.asImageBitmap(), null, Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                T(label, 16.sp, FontWeight.Bold)
                T("ছবি যুক্ত হয়েছে · বদলাতে চাপুন", 13.sp, color = c.green)
            }
            Icon(ResoIcons.Check, null, tint = c.green, modifier = Modifier.size(22.dp))
        } else {
            Icon(ResoIcons.Upload, null, tint = c.ink, modifier = Modifier.size(22.dp))
            T(label, 16.sp, FontWeight.Bold)
        }
    }
}

/** Read-only field that opens a date picker. Value is an ISO date or "". */
@Composable
fun DateField(label: String, iso: String, onPick: (String) -> Unit, error: String? = null, placeholder: String = "দিন / মাস / বছর", maxDate: LocalDate? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        Field(label, if (iso.isBlank()) "" else Bn.date(iso), {}, placeholder = placeholder, error = error, enabled = true,
            trailing = { Icon(ResoIcons.Calendar, null, tint = Reso.colors.muted, modifier = Modifier.size(20.dp)) })
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
                        if (maxDate == null || !d.isAfter(maxDate)) onPick(d.toString()) else Toaster.show("সঠিক তারিখ বাছাই করুন")
                    }
                    open = false
                }) { T("ঠিক আছে", 16.sp, FontWeight.Bold, Reso.colors.ink) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { T("বাতিল", 16.sp, color = Reso.colors.muted) } },
        ) { DatePicker(state = state, showModeToggle = true) }
    }
}

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
                ) { T("${Bn.num(n)} জন নমিনি", 16.sp, FontWeight.Bold, if (sel) c.onInk else c.text) }
            }
        }
        val percents = state.percents()
        for (i in 0 until state.count) {
            val f = state.forms[i]
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp))
                    .padding(16.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("নমিনি ${Bn.num(i + 1)}", 17.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    Tag(Bn.num(percents[i]) + "%", if (i == 0) c.ink else Brand.Amber, if (i == 0) c.onInk else Brand.Navy, size = 14.sp)
                }
                Field("নাম", f.name, { f.name = it }, placeholder = "নমিনির পুরো নাম", error = f.errors["name"], maxLength = 100)
                Field("সম্পর্ক", f.relation, { f.relation = it }, placeholder = "যেমন: স্ত্রী, পুত্র", error = f.errors["relation"], maxLength = 30)
                T("কোন পরিচয়পত্র দিচ্ছেন? (টিক দিন)", 14.sp, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IdTypeBox("এনআইডি (NID)", f.idType == "nid", Modifier.weight(1f)) { f.idType = "nid"; f.errors = f.errors - "id" }
                    IdTypeBox("জন্ম নিবন্ধন", f.idType == "birth_reg", Modifier.weight(1f)) { f.idType = "birth_reg"; f.errors = f.errors - "id" }
                }
                Field(
                    if (f.idType == "nid") "এনআইডি (NID) নম্বর" else "জন্ম নিবন্ধন নম্বর", f.idNumber, { f.idNumber = it },
                    placeholder = if (f.idType == "nid") "নমিনির এনআইডি নম্বর লিখুন" else "১৭ অঙ্কের জন্ম নিবন্ধন নম্বর",
                    keyboard = KeyboardType.Number, error = f.errors["id"], maxLength = 20,
                )
            }
        }
        if (state.count == 2) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                T("মালিকানার ভাগ", 17.sp, FontWeight.Bold)
                SplitBar(state.firstPercent / 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("নমিনি ১ এর ভাগ", 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    StepButton(false, { state.firstPercent = (state.firstPercent - 5).coerceAtLeast(5) }, size = 44.dp, enabled = state.firstPercent > 5)
                    T(Bn.num(state.firstPercent) + "%", 22.sp, FontWeight.Bold, modifier = Modifier.width(76.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                    StepButton(true, { state.firstPercent = (state.firstPercent + 5).coerceAtMost(95) }, size = 44.dp, enabled = state.firstPercent < 95)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("নমিনি ২ এর ভাগ", 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    T(Bn.num(100 - state.firstPercent) + "%", 18.sp, FontWeight.Bold)
                }
                T("মোট সবসময় ১০০%", 13.sp, color = c.muted)
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
fun NumberDisc(n: Int) = TextDisc(Bn.num(n), Reso.colors.ink, Reso.colors.onInk, 32.dp, 15.sp)
