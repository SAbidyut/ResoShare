package net.rapti.resoshare.user

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.KeyValue
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.RadioCard
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.StepButton
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.animatedLong
import net.rapti.resoshare.core.ui.methodLabel
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Kalpurush
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.OrderRequest
import net.rapti.resoshare.data.api.PaymentInfo

@Composable
fun BuyScreen(nav: NavHostController) {
    val state by UserRepo.dashboard.collectAsState()
    var info by remember { mutableStateOf<Load<PaymentInfo>>(UserRepo.paymentInfo?.let { Load.Ready(it) } ?: Load.Loading) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        UserRepo.refresh()
        when (val r = UserRepo.loadPaymentInfo()) {
            is Res.Ok -> info = Load.Ready(r.value)
            is Res.Err -> if (info !is Load.Ready) info = Load.Failed(r.error.message)
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("শেয়ার কিনুন", onBack = { nav.tab(Routes.HOME) })
        val d = (state as? Load.Ready)?.data
        val p = (info as? Load.Ready)?.data
        when {
            d != null && p != null -> BuyForm(nav, d, p)
            state is Load.Failed || info is Load.Failed -> ErrorBlock(
                (state as? Load.Failed)?.message ?: (info as? Load.Failed)?.message ?: "",
                onRetry = {
                    scope.launch {
                        UserRepo.refresh()
                        UserRepo.loadPaymentInfo().let { if (it is Res.Ok) info = Load.Ready(it.value) }
                    }
                },
            )
            else -> LoadingBlock()
        }
    }
}

@Composable
private fun BuyForm(nav: NavHostController, d: Dashboard, p: PaymentInfo) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val maxQty = minOf(d.pool.remainingShares, 10_000).coerceAtLeast(1)
    var qty by rememberSaveable { mutableIntStateOf(10.coerceAtMost(maxQty)) }
    var method by rememberSaveable { mutableStateOf(if (p.cashEnabled) "cash" else "bank") }
    var reference by rememberSaveable { mutableStateOf("") }
    var refError by remember { mutableStateOf<String?>(null) }
    var accept by rememberSaveable { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf<Order?>(null) }
    var rateChanged by remember { mutableStateOf<Long?>(null) }

    val rate = d.today.ratePaisa
    val total = rate * qty
    val canBuy = d.me.kycStatus == "verified" && d.me.accountStatus == "active" && d.pool.saleEnabled && d.pool.remainingShares > 0

    fun setQty(n: Int) { qty = n.coerceIn(1, maxQty) }

    fun place() {
        loading = true
        scope.launch {
            val req = OrderRequest(qty, method, if (method == "bank") reference.trim() else null, rate, accept)
            when (val r = call { Graph.shareholderApi.createOrder(req) }) {
                is Res.Ok -> { placed = r.value; UserRepo.refresh() }
                is Res.Err -> when (r.error.code) {
                    "RATE_CHANGED" -> { rateChanged = r.error.fields["currentRatePaisa"]?.toLongOrNull(); UserRepo.refresh() }
                    else -> {
                        r.error.fields["reference"]?.let { refError = it }
                        Toaster.show(r.error.message)
                    }
                }
            }
            loading = false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!canBuy) item {
            when {
                d.me.accountStatus != "active" -> InfoBox("অ্যাকাউন্ট স্থগিত থাকায় এখন শেয়ার কেনা যাবে না।", tone = Tone.DANGER)
                d.me.kycStatus != "verified" -> InfoBox("KYC যাচাই সম্পন্ন হলে শেয়ার কিনতে পারবেন। যাচাই হলে নোটিশে জানানো হবে।", title = "KYC যাচাই বাকি", tone = Tone.WARN)
                !d.pool.saleEnabled -> InfoBox("কোম্পানি এই মুহূর্তে শেয়ার বিক্রি বন্ধ রেখেছে। নোটিশে চালুর খবর জানানো হবে।", tone = Tone.WARN)
                else -> InfoBox("এই মুহূর্তে বিক্রির জন্য কোনো শেয়ার অবশিষ্ট নেই।", tone = Tone.WARN)
            }
        }
        item {
            ResoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("একটি শেয়ার কিনতে লাগবে", 13.sp, color = c.muted)
                        T(Bn.money(rate), 24.sp, FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        T("অবশিষ্ট শেয়ার", 13.sp, color = c.muted)
                        T("${Bn.num(d.pool.remainingShares)}টি", 17.sp, FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("কয়টি শেয়ার কিনবেন?", 15.sp, FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepButton(false, { setQty(qty - 1) }, enabled = qty > 1)
                    QtyBox(qty, Modifier.weight(1f)) { setQty(it) }
                    StepButton(true, { setQty(qty + 1) }, enabled = qty < maxQty)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 25, 50).forEach { n ->
                        val sel = qty == n
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(if (sel) c.ink else c.surface)
                                .border(1.dp, c.ink, shape).pressable(enabled = n <= maxQty, onClick = { setQty(n) }),
                            contentAlignment = Alignment.Center,
                        ) { T(Bn.num(n), 16.sp, FontWeight.Bold, if (sel) c.onInk else c.text.copy(alpha = if (n <= maxQty) 1f else 0.4f)) }
                    }
                }
            }
        }
        item {
            ResoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                KeyValue("শেয়ার সংখ্যা", "${Bn.num(qty)} টি", FontWeight.Bold)
                KeyValue("প্রতি শেয়ারের দাম", Bn.money(rate), FontWeight.Bold)
                Spacer(Modifier.height(6.dp)); RowDivider(); Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("মোট মূল্য", 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T(Bn.money(animatedLong(total, 450)), 24.sp, FontWeight.Bold)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                T("পেমেন্ট মাধ্যম", 15.sp, FontWeight.Bold)
                if (p.cashEnabled) RadioCard(method == "cash", "ক্যাশ", "অফিসে জমা", { method = "cash" })
                if (p.bankEnabled) RadioCard(method == "bank", "ব্যাংক ট্রান্সফার", "অ্যাকাউন্টে জমা", { method = "bank" })
            }
        }
        item {
            AnimatedVisibility(method == "cash", enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                InfoBox(
                    "অর্ডার নিশ্চিত করার পর অফিসে গিয়ে মোট টাকা জমা দিন ও রসিদ নিন। অ্যাডমিন যাচাই করলে শেয়ার আপনার প্রোফাইলে যুক্ত হবে।\n\nঅফিস: ${p.officeAddress.ifBlank { "[অফিসের ঠিকানা]" }}",
                    title = "অফিসে নগদ টাকা জমা দিন", tone = Tone.CASH, icon = ResoIcons.Cash,
                )
            }
            AnimatedVisibility(method == "bank", enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.infoBg).border(1.dp, c.infoBorder, RoundedCornerShape(14.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    T("এই অ্যাকাউন্টে টাকা ট্রান্সফার করুন", 15.sp, FontWeight.Bold)
                    KeyValue("ব্যাংক", p.bankName.ifBlank { "[ব্যাংকের নাম]" }, size = 14.sp)
                    KeyValue("অ্যাকাউন্টের নাম", p.accountName.ifBlank { "[অ্যাকাউন্টের নাম]" }, size = 14.sp)
                    KeyValue("অ্যাকাউন্ট নম্বর", p.accountNumber.ifBlank { "[অ্যাকাউন্ট নম্বর]" }, size = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Field("ট্রান্সফার রেফারেন্স নম্বর", reference, { reference = it; refError = null }, placeholder = "ব্যাংকের রসিদে থাকা নম্বর", error = refError, maxLength = 40)
                }
            }
        }
        item {
            CheckRow(accept, { accept = it }) {
                Column {
                    T("আমি শেয়ার কেনার নিয়ম ও রেগুলেশন পড়েছি এবং মানছি।", 14.sp, color = c.textSoft)
                    LinkText("নিয়ম ও রেগুলেশন পড়ুন", { nav.navigate(Routes.RULES) })
                }
            }
        }
        item {
            PrimaryButton(
                (if (method == "cash") "অর্ডার নিশ্চিত করুন" else "ট্রান্সফার জমা দিন") + " · " + Bn.money(total),
                {
                    if (method == "bank") V.reference(reference)?.let { refError = it; return@PrimaryButton }
                    if (!accept) { Toaster.show("নিয়ম ও রেগুলেশন মানতে টিক দিন"); return@PrimaryButton }
                    confirmOpen = true
                },
                enabled = canBuy, loading = loading,
            )
        }
    }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            containerColor = c.surface,
            title = { T("অর্ডার নিশ্চিত করবেন?", 19.sp, FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValue("শেয়ার", "${Bn.num(qty)}টি")
                    KeyValue("রেট", Bn.money(rate))
                    KeyValue("মোট", Bn.money(total))
                    KeyValue("মাধ্যম", methodLabel(method))
                    if (method == "bank") KeyValue("রেফারেন্স", reference.trim())
                }
            },
            confirmButton = { TextButton(onClick = { confirmOpen = false; place() }) { T("নিশ্চিত করুন", 16.sp, FontWeight.Bold, c.ink) } },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { T("বাতিল", 16.sp, color = c.muted) } },
        )
    }

    rateChanged?.let { newRate ->
        AlertDialog(
            onDismissRequest = { rateChanged = null },
            containerColor = c.surface,
            title = { T("শেয়ারের রেট বদলেছে", 19.sp, FontWeight.Bold) },
            text = { T("নতুন রেট ${Bn.money(newRate)}। নতুন মোট ${Bn.money(newRate * qty)}। দেখে আবার নিশ্চিত করুন।", 15.sp, color = c.textSoft) },
            confirmButton = { TextButton(onClick = { rateChanged = null }) { T("ঠিক আছে", 16.sp, FontWeight.Bold, c.ink) } },
        )
    }

    placed?.let { o ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = c.surface,
            icon = { IconDisc(ResoIcons.CheckCircle, c.successBg, c.green, 56.dp, 30.dp) },
            title = { T("অর্ডার জমা হয়েছে", 20.sp, FontWeight.Bold, align = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValue("অর্ডার নম্বর", o.orderNo)
                    KeyValue("মোট", Bn.money(o.amountPaisa))
                    if (o.method == "cash") {
                        KeyValue("রসিদ নম্বর", o.reference ?: "")
                        T("অফিসে টাকা জমার সময় এই রসিদ নম্বরটি বলুন।", 14.sp, color = c.textSoft)
                    } else T("অ্যাডমিন ব্যাংক ট্রান্সফার যাচাই করলে শেয়ার আপনার প্রোফাইলে যুক্ত হবে।", 14.sp, color = c.textSoft)
                }
            },
            confirmButton = {
                TextButton(onClick = { placed = null; reference = ""; accept = false; nav.navigate(Routes.ORDERS) }) { T("লেনদেন দেখুন", 16.sp, FontWeight.Bold, c.ink) }
            },
        )
    }
}

/** Editable quantity box styled like the design ("১০ টি শেয়ার"). */
@Composable
private fun QtyBox(qty: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = Reso.colors
    var text by remember(qty) { mutableStateOf(Bn.digits(qty.toString())) }
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { v ->
                val digits = Bn.ascii(v).filter { it.isDigit() }.take(5)
                text = Bn.digits(digits)
                digits.toIntOrNull()?.let { if (it > 0) onChange(it) }
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Kalpurush, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = c.text, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(c.ink),
            modifier = Modifier.width(92.dp),
        )
        Spacer(Modifier.width(6.dp))
        T("টি শেয়ার", 15.sp, color = c.muted)
    }
}
