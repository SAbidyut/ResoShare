package net.rapti.resoshare.user

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import net.rapti.resoshare.core.i18n.t
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.KeyValue
import net.rapti.resoshare.core.ui.LinkText
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.animatedLong
import net.rapti.resoshare.core.ui.methodLabel
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Kalpurush
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.Order
import net.rapti.resoshare.data.api.OrderRequest
import net.rapti.resoshare.data.api.PaymentInfo

private fun buySteps() = listOf(t("পরিমাণ", "Amount"), t("পেমেন্ট", "Payment"), t("নিশ্চিত", "Confirm"))

@Composable
fun BuyScreen(nav: NavHostController) {
    val state by UserRepo.dashboard.collectAsState()
    var info by remember { mutableStateOf<Load<PaymentInfo>>(UserRepo.paymentInfo?.let { Load.Ready(it) } ?: Load.Loading) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        UserRepo.refresh()
        when (val r = UserRepo.loadPaymentInfo()) {
            is Res.Ok -> info = Load.Ready(r.value)
            is Res.Err -> if (info !is Load.Ready) info = Load.Failed(r.error.message)
        }
    }
    Column(Modifier.fillMaxSize().background(Reso.colors.bg).imePadding()) {
        StepHeader(t("শেয়ার কিনুন", "Buy shares"), buySteps(), step, { nav.popBackStack() }, showCounter = false)
        val d = (state as? Load.Ready)?.data
        val p = (info as? Load.Ready)?.data
        when {
            d != null && p != null -> BuyForm(nav, d, p) { step = it }
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
private fun BuyForm(nav: NavHostController, d: Dashboard, p: PaymentInfo, onStep: (Int) -> Unit) {
    val c = Reso.colors
    val scope = rememberCoroutineScope()
    val maxQty = minOf(d.pool.remainingShares, 10_000).coerceAtLeast(1)
    val sliderMax = minOf(maxQty, 100)
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
    val paymentReady = method == "cash" || V.reference(reference) == null
    LaunchedEffect(paymentReady, accept, placed) { onStep(if (placed != null) 2 else if (paymentReady && accept) 2 else 1) }

    fun setQty(n: Int) { qty = n.coerceIn(1, maxQty) }

    fun place() {
        loading = true
        scope.launch {
            val req = OrderRequest(qty, method, if (method == "bank") reference.trim() else null, rate, accept)
            when (val r = call { Graph.shareholderApi.createOrder(req) }) {
                is Res.Ok -> { placed = r.value; UserRepo.refresh(); UserRepo.refreshOrders() }
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
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!canBuy) item(key = "blocked") {
            when {
                d.me.accountStatus != "active" -> InfoBox(t("অ্যাকাউন্ট স্থগিত থাকায় এখন শেয়ার কেনা যাবে না।", "Your account is suspended, so you can't buy shares now."), tone = Tone.DANGER)
                d.me.kycStatus != "verified" -> InfoBox(t("KYC যাচাই সম্পন্ন হলে শেয়ার কিনতে পারবেন। যাচাই হলে নোটিশে জানানো হবে।",
                    "You can buy shares after your KYC is verified. You'll get a notice."), title = t("KYC যাচাই বাকি", "KYC pending"), tone = Tone.WARN)
                !d.pool.saleEnabled -> InfoBox(t("কোম্পানি এই মুহূর্তে শেয়ার বিক্রি বন্ধ রেখেছে। নোটিশে চালুর খবর জানানো হবে।",
                    "Share sale is closed right now. You'll get a notice when it reopens."), tone = Tone.WARN)
                else -> InfoBox(t("এই মুহূর্তে বিক্রির জন্য কোনো শেয়ার অবশিষ্ট নেই।", "No shares are left for sale right now."), tone = Tone.WARN)
            }
        }
        item(key = "rate") {
            GradientCard(c.rate, Modifier.fillMaxWidth(), radius = 16.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(t("আজকের রেট · প্রতি শেয়ার", "Today's rate · per share"), 13.sp, color = c.rate.label)
                        T(Bn.money(rate), 24.sp, FontWeight.Bold, c.rate.value)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        T(t("বিক্রির জন্য বাকি", "Left for sale"), 13.sp, color = c.rate.label)
                        T(t("${Bn.num(d.pool.remainingShares)}টি", Bn.num(d.pool.remainingShares)), 17.sp, FontWeight.Bold, c.rate.value)
                    }
                }
            }
        }
        item(key = "qty") {
            ResoCard(padding = PaddingValues(16.dp), radius = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    T(t("কয়টি শেয়ার কিনবেন?", "How many shares?"), 17.sp, FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundStep(false, qty > 1) { setQty(qty - 1) }
                        QtyBox(qty, Modifier.weight(1f)) { setQty(it) }
                        RoundStep(true, qty < maxQty) { setQty(qty + 1) }
                    }
                    if (sliderMax > 1) {
                        T(t("টেনে পরিমাণ ঠিক করুন (${Bn.num(1)}–${Bn.num(sliderMax)})", "Drag to set the amount (1–$sliderMax)"), 13.sp, color = c.muted)
                        Slider(
                            value = qty.coerceAtMost(sliderMax).toFloat(),
                            onValueChange = { setQty(it.toInt()) },
                            valueRange = 1f..sliderMax.toFloat(),
                            thumb = {
                                Box(Modifier.size(24.dp).clip(CircleShape).background(Brand.Amber).border(3.dp, c.surface, CircleShape))
                            },
                            track = { st ->
                                val span = (st.valueRange.endInclusive - st.valueRange.start).coerceAtLeast(1f)
                                val frac = ((st.value - st.valueRange.start) / span).coerceIn(0f, 1f)
                                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.track)) {
                                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(c.ink))
                                }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 25, 50).forEach { n ->
                            val sel = qty == n
                            val shape = RoundedCornerShape(22.dp)
                            Box(
                                Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(if (sel) c.ink else c.surface)
                                    .border(1.dp, if (sel) c.ink else c.inputBorder, shape).pressable(enabled = n <= maxQty, onClick = { setQty(n) }),
                                contentAlignment = Alignment.Center,
                            ) { T(Bn.num(n), 16.sp, FontWeight.Bold, if (sel) c.onInk else c.text.copy(alpha = if (n <= maxQty) 1f else 0.4f)) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.infoBg).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(ResoIcons.Layers, null, tint = c.ink, modifier = Modifier.size(18.dp))
                        T(t("কেনার পর আপনার মোট শেয়ার হবে ${Bn.num(d.holding.shares)} → ${Bn.num(d.holding.shares + qty)}টি",
                            "After buying you'll have ${d.holding.shares} → ${d.holding.shares + qty} shares"), 14.sp, FontWeight.Bold, c.text)
                    }
                }
            }
        }
        item(key = "summary") {
            ResoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), radius = 18.dp) {
                KeyValue(t("শেয়ার সংখ্যা", "Shares"), t("${Bn.num(qty)} টি", "$qty"), FontWeight.Bold)
                KeyValue(t("প্রতি শেয়ারের দাম", "Price per share"), Bn.money(rate), FontWeight.Bold)
                Spacer(Modifier.height(6.dp)); RowDivider(); Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(t("মোট মূল্য", "Total"), 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    T(Bn.money(animatedLong(total, 350)), 24.sp, FontWeight.Bold)
                }
            }
        }
        item(key = "method") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T(t("কীভাবে টাকা দেবেন?", "How will you pay?"), 17.sp, FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.cashEnabled) MethodCard(method == "cash", ResoIcons.Cash, t("ক্যাশ", "Cash"), t("অফিসে নগদ জমা", "Pay at the office"), Modifier.weight(1f)) { method = "cash" }
                    if (p.bankEnabled) MethodCard(method == "bank", ResoIcons.Building, t("ব্যাংক ট্রান্সফার", "Bank transfer"), t("অ্যাকাউন্টে জমা", "Deposit to account"), Modifier.weight(1f)) { method = "bank" }
                }
            }
        }
        item(key = "methodInfo") {
            Column {
                AnimatedVisibility(method == "cash", enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    InfoBox(
                        t("অর্ডার নিশ্চিত করার পর অফিসে গিয়ে মোট টাকা জমা দিন ও রসিদ নিন। অ্যাডমিন যাচাই করলে শেয়ার আপনার প্রোফাইলে যুক্ত হবে।",
                            "After confirming, pay the total at the office and take a receipt. Shares are added once an admin verifies it.") +
                            "\n" + t("অফিস: ", "Office: ") + p.officeAddress.ifBlank { t("[অফিসের ঠিকানা]", "[office address]") },
                        title = t("অফিসে নগদ টাকা জমা দিন", "Pay cash at the office"), tone = Tone.CASH, icon = null,
                    )
                }
                AnimatedVisibility(method == "bank", enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.infoBg).border(1.dp, c.infoBorder, RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        T(t("এই অ্যাকাউন্টে টাকা ট্রান্সফার করুন", "Transfer to this account"), 15.sp, FontWeight.Bold)
                        KeyValue(t("ব্যাংক", "Bank"), p.bankName.ifBlank { t("[ব্যাংকের নাম]", "[bank name]") }, size = 14.sp)
                        KeyValue(t("অ্যাকাউন্টের নাম", "Account name"), p.accountName.ifBlank { t("[অ্যাকাউন্টের নাম]", "[account name]") }, size = 14.sp)
                        KeyValue(t("অ্যাকাউন্ট নম্বর", "Account number"), p.accountNumber.ifBlank { t("[অ্যাকাউন্ট নম্বর]", "[account number]") }, size = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Field(t("ট্রান্সফার রেফারেন্স নম্বর", "Transfer reference number"), reference, { reference = it; refError = null },
                            placeholder = t("ব্যাংকের রসিদে থাকা নম্বর", "Number on the bank receipt"), error = refError, maxLength = 40)
                    }
                }
            }
        }
        item(key = "rules") {
            ResoCard(padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                CheckRow(accept, { accept = it }) {
                    Column {
                        T(t("আমি শেয়ার কেনার নিয়ম ও রেগুলেশন পড়েছি এবং মানছি।", "I have read and accept the share rules & regulations."), 14.sp, color = c.textSoft)
                        LinkText(t("নিয়ম ও রেগুলেশন পড়ুন", "Read the rules"), { nav.navigate(Routes.RULES) }, size = 13.sp)
                    }
                }
            }
        }
        item(key = "cta") {
            Column(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    (if (method == "cash") t("অর্ডার নিশ্চিত করুন", "Confirm order") else t("ট্রান্সফার জমা দিন", "Submit transfer")) + " · " + Bn.money(total),
                    {
                        if (method == "bank") V.reference(reference)?.let { refError = it; return@PrimaryButton }
                        confirmOpen = true
                    },
                    enabled = canBuy && accept, loading = loading,
                )
                if (!accept) T(t("এগোতে আগে নিয়মে টিক দিন", "Tick the rules box to continue"), 13.sp, color = c.brown)
            }
        }
    }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            containerColor = c.surface,
            title = { T(t("অর্ডার নিশ্চিত করবেন?", "Confirm this order?"), 19.sp, FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValue(t("শেয়ার", "Shares"), t("${Bn.num(qty)}টি", "$qty"))
                    KeyValue(t("রেট", "Rate"), Bn.money(rate))
                    KeyValue(t("মোট", "Total"), Bn.money(total))
                    KeyValue(t("মাধ্যম", "Method"), methodLabel(method))
                    if (method == "bank") KeyValue(t("রেফারেন্স", "Reference"), reference.trim())
                }
            },
            confirmButton = { TextButton(onClick = { confirmOpen = false; place() }) { T(t("নিশ্চিত করুন", "Confirm"), 16.sp, FontWeight.Bold, c.ink) } },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { T(t("বাতিল", "Cancel"), 16.sp, color = c.muted) } },
        )
    }

    rateChanged?.let { newRate ->
        AlertDialog(
            onDismissRequest = { rateChanged = null },
            containerColor = c.surface,
            title = { T(t("শেয়ারের রেট বদলেছে", "The share rate changed"), 19.sp, FontWeight.Bold) },
            text = {
                T(t("নতুন রেট ${Bn.money(newRate)}। নতুন মোট ${Bn.money(newRate * qty)}। দেখে আবার নিশ্চিত করুন।",
                    "New rate ${Bn.money(newRate)}. New total ${Bn.money(newRate * qty)}. Please review and confirm again."), 15.sp, color = c.textSoft)
            },
            confirmButton = { TextButton(onClick = { rateChanged = null }) { T(t("ঠিক আছে", "OK"), 16.sp, FontWeight.Bold, c.ink) } },
        )
    }

    placed?.let { o ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = c.surface,
            icon = { IconDisc(ResoIcons.CheckCircle, c.successBg, c.green, 56.dp, 30.dp) },
            title = { T(t("অর্ডার জমা হয়েছে", "Order submitted"), 20.sp, FontWeight.Bold, align = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyValue(t("অর্ডার নম্বর", "Order no."), o.orderNo)
                    KeyValue(t("মোট", "Total"), Bn.money(o.amountPaisa))
                    if (o.method == "cash") {
                        KeyValue(t("রসিদ নম্বর", "Receipt no."), o.reference ?: "")
                        T(t("অফিসে টাকা জমার সময় এই রসিদ নম্বরটি বলুন।", "Mention this receipt number when you pay at the office."), 14.sp, color = c.textSoft)
                    } else T(t("অ্যাডমিন ব্যাংক ট্রান্সফার যাচাই করলে শেয়ার আপনার প্রোফাইলে যুক্ত হবে।", "Shares are added once an admin verifies the bank transfer."), 14.sp, color = c.textSoft)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    placed = null; reference = ""; accept = false
                    nav.navigate(Routes.ORDERS) { popUpTo(Routes.BUY) { inclusive = true } }
                }) { T(t("লেনদেন দেখুন", "View transactions"), 16.sp, FontWeight.Bold, c.ink) }
            },
        )
    }
}

@Composable
private fun RoundStep(plus: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Reso.colors
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(if (plus) c.ink else c.surface)
            .border(1.dp, if (plus) c.ink else c.inputBorder, CircleShape).pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (plus) ResoIcons.Plus else ResoIcons.Minus, if (plus) t("বাড়ান", "Increase") else t("কমান", "Decrease"),
            tint = (if (plus) c.onInk else c.ink).copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(24.dp))
    }
}

/** Big editable quantity ("১০ টি শেয়ার"). */
@Composable
private fun QtyBox(qty: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = Reso.colors
    var text by remember(qty) { mutableStateOf(Bn.digits(qty.toString())) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicTextField(
            value = text,
            onValueChange = { v ->
                val digits = Bn.ascii(v).filter { it.isDigit() }.take(5)
                text = Bn.digits(digits)
                digits.toIntOrNull()?.let { if (it > 0) onChange(it) }
            },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Kalpurush, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = c.text, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(c.ink),
            modifier = Modifier.width(140.dp),
        )
        T(t("টি শেয়ার", "shares"), 14.sp, color = c.muted)
    }
}

@Composable
private fun MethodCard(selected: Boolean, icon: ImageVector, title: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val shape = RoundedCornerShape(16.dp)
    val border by animateColorAsState(if (selected) c.ink else c.inputBorder, tween(180), label = "mc")
    Column(
        modifier.clip(shape).background(if (selected) c.infoBg else c.surface).border(if (selected) 2.dp else 1.5.dp, border, shape)
            .pressable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(icon, if (selected) c.ink else c.infoBg, if (selected) c.onInk else c.ink, 40.dp, 20.dp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (selected) c.ink else c.surface).border(2.dp, if (selected) c.ink else c.inputBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(ResoIcons.Check, null, tint = c.onInk, modifier = Modifier.size(15.dp)) }
        }
        Column {
            T(title, 16.sp, FontWeight.Bold, maxLines = 1)
            T(note, 13.sp, color = c.muted, maxLines = 1)
        }
    }
}
