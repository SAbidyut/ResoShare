package net.rapti.resoshare.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.export.ExportFile
import net.rapti.resoshare.core.export.Exporter
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.net.ApiException
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.RadioCard
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.StaffMe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------- shared admin state

/** The signed-in staff member and a few app-wide values (not persisted; server is authoritative). */
object AdminSession {
    val me = MutableStateFlow<StaffMe?>(null)
    val pending = MutableStateFlow(0)
    @Volatile var companyName: String = "Resonance Automation"

    fun can(perm: String): Boolean = me.value?.permissions?.contains(perm) == true

    fun clear() {
        me.value = null
        pending.value = 0
        recent.clear() // generated report files live only in memory; drop them on logout
    }
}

// ---------------------------------------------------------------- data loading

/** Loads one resource and keeps it across tab switches (owned by a nav-entry scoped ViewModel). */
class Loader<T>(private val scope: CoroutineScope, private val fetch: suspend () -> T) {
    var state by mutableStateOf<Load<T>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun load(silent: Boolean = false) {
        job?.cancel()
        if (!silent || state !is Load.Ready) state = Load.Loading
        job = scope.launch {
            when (val r = call { fetch() }) {
                is Res.Ok -> state = Load.Ready(r.value)
                is Res.Err -> if (silent && state is Load.Ready) Toaster.show(r.error.message) else state = Load.Failed(r.error.message)
            }
            refreshing = false
        }
    }

    fun refresh() {
        refreshing = true
        load(silent = true)
    }

    fun set(value: T) { state = Load.Ready(value) }
}

/** Cursor-paginated list loader. `first` keeps the first page for its summary fields. */
class Pager<P, T>(
    private val scope: CoroutineScope,
    private val fetch: suspend (cursor: String?) -> P,
    private val itemsOf: (P) -> List<T>,
    private val nextOf: (P) -> String?,
) {
    var state by mutableStateOf<Load<Unit>>(Load.Loading)
        private set
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var first by mutableStateOf<P?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    private var cursor: String? = null
    val hasMore: Boolean get() = cursor != null
    /** Number of pages loaded so far (1 after the first load). */
    var pages = 0
        private set
    private var job: Job? = null

    fun load(silent: Boolean = false) {
        job?.cancel()
        if (!silent || state !is Load.Ready) state = Load.Loading
        job = scope.launch {
            when (val r = call { fetch(null) }) {
                is Res.Ok -> {
                    first = r.value
                    items = itemsOf(r.value)
                    cursor = nextOf(r.value)
                    pages = 1
                    state = Load.Ready(Unit)
                }
                is Res.Err -> if (silent && state is Load.Ready) Toaster.show(r.error.message) else state = Load.Failed(r.error.message)
            }
            refreshing = false
            loadingMore = false
        }
    }

    fun refresh() {
        refreshing = true
        load(silent = true)
    }

    fun more() {
        val c = cursor ?: return
        if (loadingMore || state !is Load.Ready) return
        loadingMore = true
        job = scope.launch {
            when (val r = call { fetch(c) }) {
                is Res.Ok -> {
                    items = items + itemsOf(r.value)
                    cursor = nextOf(r.value)
                    pages++
                }
                is Res.Err -> Toaster.show(r.error.message)
            }
            loadingMore = false
        }
    }

    /** Local edit after an action (e.g. remove a decided item). */
    fun update(transform: (List<T>) -> List<T>) { items = transform(items) }
}

class ScreenVm : ViewModel() {
    private val loaders = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> loader(key: String, fetch: suspend () -> T): Loader<T> =
        loaders.getOrPut("L:$key") { Loader(viewModelScope, fetch).also { it.load() } } as Loader<T>

    @Suppress("UNCHECKED_CAST")
    fun <P, T> pager(key: String, fetch: suspend (String?) -> P, itemsOf: (P) -> List<T>, nextOf: (P) -> String?): Pager<P, T> =
        loaders.getOrPut("P:$key") { Pager(viewModelScope, fetch, itemsOf, nextOf).also { it.load() } } as Pager<P, T>
}

@Composable
fun <P, T> rememberPager(key: String, fetch: suspend (String?) -> P, itemsOf: (P) -> List<T>, nextOf: (P) -> String?): Pager<P, T> {
    val vm: ScreenVm = viewModel()
    val p = vm.pager(key, fetch, itemsOf, nextOf)
    LaunchedEffect(p) { if (p.state is Load.Ready && p.pages <= 1) p.load(silent = true) }
    return p
}

/** Renders a pager's items (grouped card look) with empty/error states and infinite scroll. */
fun <P, T> LazyListScope.pagedItems(
    pager: Pager<P, T>,
    empty: String,
    key: (T) -> Any,
    row: @Composable (item: T, first: Boolean, last: Boolean) -> Unit,
) {
    when (val s = pager.state) {
        is Load.Loading -> item("loading") { net.rapti.resoshare.core.ui.LoadingBlock() }
        is Load.Failed -> item("error") { ErrorBlock(s.message, { pager.load() }) }
        is Load.Ready -> {
            if (pager.items.isEmpty()) item("empty") { net.rapti.resoshare.core.ui.EmptyBlock(empty) }
            val n = pager.items.size
            itemsIndexed(pager.items, key = { _, it -> key(it) }) { i, it ->
                Box(Modifier.animateItem()) { row(it, i == 0, i == n - 1) }
            }
            if (pager.hasMore) item("more") {
                LaunchedEffect(n) { pager.more() }
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Reso.colors.ink, strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Rounded "grouped list" background for consecutive LazyColumn rows. */
@Composable
fun Modifier.grouped(first: Boolean, last: Boolean): Modifier {
    val r = 14.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    return this.fillMaxWidth().clip(shape).background(Reso.colors.surface)
}

/** A loader scoped to the current navigation entry; `key` must include every parameter of `fetch`. */
@Composable
fun <T> rememberLoader(key: String, fetch: suspend () -> T): Loader<T> {
    val vm: ScreenVm = viewModel()
    val l = vm.loader(key, fetch)
    // Returning to a screen (tab switch / back navigation) quietly refreshes cached data.
    LaunchedEffect(l) { if (l.state is Load.Ready) l.load(silent = true) }
    return l
}

/** Guards a single user action against double submits and maps errors to toasts. */
class Action(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set

    fun <T> run(
        block: suspend () -> T,
        onErr: (ApiException) -> Unit = { Toaster.show(it.message) },
        onOk: (T) -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            when (val r = call { block() }) {
                is Res.Ok -> onOk(r.value)
                is Res.Err -> onErr(r.error)
            }
            busy = false
        }
    }
}

@Composable
fun rememberAction(): Action {
    val scope = rememberCoroutineScope()
    return remember { Action(scope) }
}

// ---------------------------------------------------------------- screen scaffold

@Composable
fun AdminScreen(
    header: @Composable () -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    spacing: androidx.compose.ui.unit.Dp = 16.dp,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Reso.colors.bg)) {
        header()
        val list = @Composable {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
        if (onRefresh != null) Refreshable(refreshing, onRefresh, Modifier.weight(1f)) { list() }
        else Box(Modifier.weight(1f)) { list() }
    }
}

/** Renders a Load<T> inside a LazyColumn. */
fun <T> LazyListScope.loadItems(
    state: Load<T>,
    onRetry: () -> Unit,
    content: LazyListScope.(T) -> Unit,
) {
    when (state) {
        is Load.Loading -> item("loading") { net.rapti.resoshare.core.ui.LoadingBlock() }
        is Load.Failed -> item("error") { ErrorBlock(state.message, onRetry) }
        is Load.Ready -> content(state.data)
    }
}

// ---------------------------------------------------------------- dates

fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
fun Long.utcMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Read-only field that opens a Material date picker. Shows the date in Bangla. */
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    min: LocalDate? = null,
    max: LocalDate? = null,
    placeholder: String = "তারিখ বাছাই করুন",
    enabled: Boolean = true,
) {
    val c = Reso.colors
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        T(label, 14.sp, FontWeight.Bold)
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(c.surfaceAlt)
                .border(1.dp, c.inputBorder, shape).clickable(enabled = enabled, role = Role.Button) { open = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(date?.let { Bn.date(it) } ?: placeholder, 16.sp, color = if (date == null) c.muted else c.text, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(ResoIcons.Calendar, null, tint = c.muted, modifier = Modifier.size(20.dp))
        }
    }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: Bn.today()).toUtcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = utcTimeMillis.utcMillisToDate()
                    return (min == null || !d.isBefore(min)) && (max == null || !d.isAfter(max))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(it.utcMillisToDate()) }
                    open = false
                }) { T("ঠিক আছে", 16.sp, FontWeight.Bold, c.ink) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { T("বাতিল", 16.sp, color = c.muted) } },
        ) { DatePicker(state = state, showModeToggle = false) }
    }
}

// ---------------------------------------------------------------- export (save / share)

class FileSaver(val save: (ExportFile) -> Unit, val share: (ExportFile) -> Unit)

@Composable
fun rememberFileSaver(): FileSaver {
    val ctx = LocalContext.current
    val pending = remember { mutableStateOf<ExportFile?>(null) }
    val onResult: (Uri?) -> Unit = { uri ->
        val f = pending.value
        if (uri != null && f != null) {
            runCatching { Exporter.writeTo(ctx, uri, f) }
                .onSuccess { Toaster.show("ফাইল সংরক্ষণ হয়েছে") }
                .onFailure { Toaster.show("ফাইল সংরক্ষণ করা যায়নি") }
        }
        pending.value = null
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), onResult)
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME_XLSX), onResult)
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), onResult)
    return remember(pdf, xlsx, csv) {
        FileSaver(
            save = { f ->
                pending.value = f
                runCatching {
                    when (f.mime) {
                        "application/pdf" -> pdf.launch(f.name)
                        MIME_XLSX -> xlsx.launch(f.name)
                        else -> csv.launch(f.name)
                    }
                }.onFailure { Toaster.show("ফাইল সংরক্ষণের অ্যাপ পাওয়া যায়নি") }
            },
            share = { f ->
                runCatching { ctx.startActivity(Exporter.shareIntent(ctx, f)) }
                    .onFailure { Toaster.show("শেয়ার করা যায়নি") }
            },
        )
    }
}

const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

enum class ExportFormat(val label: String) { PDF("PDF"), XLSX("Excel"), CSV("CSV") }

/** Builds the requested report file off the main thread. */
suspend fun buildReport(ctx: android.content.Context, type: String, from: LocalDate?, to: LocalDate?, format: ExportFormat): ExportFile {
    val report = Graph.adminApi.report(type, from?.toString(), to?.toString())
    return withContext(Dispatchers.Default) {
        when (format) {
            ExportFormat.PDF -> Exporter.pdf(ctx, report, AdminSession.companyName)
            ExportFormat.XLSX -> Exporter.xlsx(report)
            ExportFormat.CSV -> Exporter.csv(report)
        }
    }
}

@Composable
fun FormatDialog(title: String, onPick: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    val c = Reso.colors
    var sel by remember { mutableStateOf(ExportFormat.PDF) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { T(title, 19.sp, FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { f ->
                    RadioCard(sel == f, f.label, null, { sel = f })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(sel) }) { T("তৈরি করুন", 16.sp, FontWeight.Bold, c.ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { T("বাতিল", 16.sp, color = c.muted) } },
    )
}

@Composable
fun ExportReadyDialog(file: ExportFile, saver: FileSaver, onDismiss: () -> Unit) {
    val c = Reso.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        icon = { Icon(ResoIcons.Document, null, tint = c.ink, modifier = Modifier.size(32.dp)) },
        title = { T("ফাইল তৈরি হয়েছে", 19.sp, FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T(file.name, 14.sp, color = c.textSoft)
                PrimaryButton("ডিভাইসে সংরক্ষণ করুন", { saver.save(file); onDismiss() }, icon = ResoIcons.Download, height = 50.dp)
                SecondaryButton("শেয়ার করুন", { saver.share(file); onDismiss() }, icon = ResoIcons.Share, height = 50.dp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { T("বন্ধ করুন", 16.sp, color = c.muted) } },
    )
}

/** One-tap "export a report" flow: pick format → generate → save/share. */
class ReportExport(val start: () -> Unit, val busy: Boolean)

@Composable
fun rememberReportExport(type: String, title: String): Pair<ReportExport, @Composable () -> Unit> {
    val ctx = LocalContext.current
    val saver = rememberFileSaver()
    val action = rememberAction()
    var picking by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<ExportFile?>(null) }
    val ui: @Composable () -> Unit = {
        if (picking) FormatDialog(title, onPick = { f ->
            picking = false
            action.run({ buildReport(ctx, type, null, null, f) }) { ready = it }
        }, onDismiss = { picking = false })
        ready?.let { ExportReadyDialog(it, saver) { ready = null } }
    }
    return ReportExport({ if (AdminSession.can("reports")) picking = true else Toaster.show("রিপোর্টের অনুমতি আপনার নেই") }, action.busy) to ui
}

// ---------------------------------------------------------------- KYC image viewer

@Composable
fun KycImageViewer(fileId: String, title: String, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Load<Bitmap>>(Load.Loading) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(fileId, attempt) {
        state = Load.Loading
        state = when (val r = call {
            val body = Graph.adminApi.kycFile(fileId)
            withContext(Dispatchers.IO) {
                body.use { b ->
                    val bytes = b.bytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ApiException("BAD_RESPONSE", "ছবিটি খোলা যায়নি")
                }
            }
        }) {
            is Res.Ok -> Load.Ready(r.value)
            is Res.Err -> Load.Failed(r.error.message)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                is Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Brand.Amber)
                is Load.Failed -> Box(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Reso.colors.surface)) {
                    ErrorBlock(s.message, { attempt++ })
                }
                is Load.Ready -> {
                    var scale by remember { mutableStateOf(1f) }
                    var ox by remember { mutableStateOf(0f) }
                    var oy by remember { mutableStateOf(0f) }
                    Image(
                        s.data.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale == 1f) { ox = 0f; oy = 0f } else { ox += pan.x; oy += pan.y }
                                }
                            }
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(title, 18.sp, FontWeight.Bold, Color.White, modifier = Modifier.weight(1f))
                net.rapti.resoshare.core.ui.CircleIconButton(ResoIcons.Close, "বন্ধ করুন", onDismiss, bg = Color.White.copy(alpha = 0.15f))
            }
        }
    }
}

fun kycLabel(kind: String) = when (kind) {
    "nid_front" -> "NID সামনে"
    "nid_back" -> "NID পেছনে"
    else -> "ছবি"
}

/** Day header text for grouped lists: "আজ · ০২ নভেম্বর" / "গতকাল · ০১ নভেম্বর" / date. */
fun dayHeader(d: LocalDate): String {
    val t = Bn.today()
    return when (d) {
        t -> "আজ · ${Bn.dayMonth(d)}"
        t.minusDays(1) -> "গতকাল · ${Bn.dayMonth(d)}"
        else -> Bn.date(d)
    }
}
