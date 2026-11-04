package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.HeaderStat
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.KeyValue
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.DividendRequest
import net.rapti.resoshare.data.api.Perm
import java.time.LocalDate

@Composable
fun DividendScreen(an: AdminNav) {
    val loader = rememberLoader("dividends") { Graph.adminApi.dividends() }
    val data = (loader.state as? Load.Ready)?.data
    val last = data?.items?.firstOrNull()

    var year by rememberSaveable { mutableStateOf(Bn.digits(Bn.today().year.toString())) }
    var perShare by rememberSaveable { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var errYear by remember { mutableStateOf<String?>(null) }
    var errPer by remember { mutableStateOf<String?>(null) }
    var errDate by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val action = rememberAction()
    val per = Bn.parseMoney(perShare)?.takeIf { it > 0 }
    val sold = data?.soldShares ?: 0
    val total = (per ?: 0L) * sold

    fun validate(): Boolean {
        val y = Bn.ascii(year).trim()
        errYear = if (!Regex("^\\d{4}$").matches(y)) "অর্থবছর ৪ অঙ্কে লিখুন (যেমন ২০২৬)"
        else if (data?.items?.any { it.fiscalYear == y } == true) "এই অর্থবছরের ডিভিডেন্ড আগেই ঘোষণা হয়েছে" else null
        errPer = if (per == null) "প্রতি শেয়ারে ডিভিডেন্ডের পরিমাণ দিন" else null
        errDate = if (date == null) "বিতরণের তারিখ বাছাই করুন" else null
        return errYear == null && errPer == null && errDate == null
    }

    AdminScreen(
        header = {
            ScreenHeader("ডিভিডেন্ড", onBack = an::back) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderStat("শেষ ঘোষণা", last?.let { "${Bn.digits(it.fiscalYear)} অর্থবছর" } ?: "—", Modifier.weight(1f))
                    HeaderStat("সর্বশেষ মোট বিতরণ", last?.let { Bn.money(it.totalPaisa) } ?: "—", Modifier.weight(1f))
                }
            }
        },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        loadItems(loader.state, { loader.load() }) { d ->
            if (AdminSession.can(Perm.DIVIDEND)) item("form") {
                val c = Reso.colors
                ResoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("নতুন ডিভিডেন্ড ঘোষণা", 17.sp, FontWeight.Bold)
                        Field("অর্থবছর", year, { year = it.take(4); errYear = null }, keyboard = KeyboardType.Number, error = errYear, placeholder = "যেমন ২০২৬")
                        Field("প্রতি শেয়ারে ডিভিডেন্ড (৳)", perShare, { perShare = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }; errPer = null },
                            keyboard = KeyboardType.Decimal, error = errPer, placeholder = "যেমন ৮০.০০", maxLength = 14)
                        DateField("বিতরণের তারিখ", date, { date = it; errDate = null }, min = Bn.today())
                        if (errDate != null) T(errDate ?: "", 13.sp, color = c.red)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            KeyValue("বিক্রিত শেয়ার", "${Bn.num(sold)}টি", size = 14.sp)
                            KeyValue("প্রতি শেয়ার", per?.let { Bn.money(it) } ?: "—", size = 14.sp)
                            RowDivider(Modifier.padding(vertical = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T("মোট বিতরণ হবে", 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                T(Bn.money(total), 22.sp, FontWeight.Bold)
                            }
                        }
                        PrimaryButton("ডিভিডেন্ড ঘোষণা করুন", { if (validate()) confirm = true }, loading = action.busy)
                    }
                }
            }
            item("history") {
                ResoCard {
                    T("আগের ডিভিডেন্ড", 17.sp, FontWeight.Bold)
                    if (d.items.isEmpty()) EmptyBlock("এখনো কোনো ডিভিডেন্ড ঘোষণা হয়নি", ResoIcons.Dollar)
                    d.items.forEach { v ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                T("${Bn.digits(v.fiscalYear)} অর্থবছর", 16.sp, FontWeight.Bold)
                                T("প্রতি শেয়ারে ${Bn.money(v.perSharePaisa)} · ${Bn.num(v.totalShares)}টি শেয়ার", 13.sp, color = Reso.colors.muted)
                                T("বিতরণ: ${Bn.date(v.distributionDate)}", 13.sp, color = Reso.colors.muted)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                T(Bn.money(v.totalPaisa), 16.sp, FontWeight.Bold)
                                val c = Reso.colors
                                if (v.status == "distributed") Tag("বিতরণ সম্পন্ন", c.successBg, c.green) else Tag("ঘোষিত", c.warnBg, c.brown)
                            }
                        }
                    }
                }
            }
            item("note") {
                InfoBox("সংখ্যাগুলো সার্ভারের হিসাব অনুযায়ী। ডিভিডেন্ড ঘোষণার নিয়ম ও করের হিসাব আপনার অডিটর বা আইনজীবীর সঙ্গে যাচাই করে নিন। ঘোষণা করলে সব শেয়ারহোল্ডার অ্যাপের নোটিশে জানতে পারবেন।")
            }
        }
    }
    if (confirm) ConfirmDialog(
        "ডিভিডেন্ড ঘোষণা করবেন?",
        "${Bn.digits(Bn.ascii(year))} অর্থবছর · প্রতি শেয়ারে ${Bn.money(per ?: 0)} · মোট ${Bn.money(total)}। বিতরণ: ${date?.let { Bn.date(it) } ?: ""}। ঘোষণার পর এটি বদলানো যাবে না।",
        "ঘোষণা করুন",
        {
            confirm = false
            val p = per ?: return@ConfirmDialog
            val dt = date ?: return@ConfirmDialog
            action.run({ Graph.adminApi.declareDividend(DividendRequest(Bn.ascii(year).trim(), p, dt.toString())) }) {
                Toaster.show("ডিভিডেন্ড ঘোষণা হয়েছে")
                perShare = ""; date = null
                loader.load(silent = true)
            }
        },
        { confirm = false },
    )
}
