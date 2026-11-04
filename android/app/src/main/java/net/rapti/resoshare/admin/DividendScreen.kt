package net.rapti.resoshare.admin

import androidx.compose.foundation.background
import net.rapti.resoshare.core.i18n.t
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
        errYear = if (!Regex("^\\d{4}$").matches(y)) t("অর্থবছর ৪ অঙ্কে লিখুন (যেমন ২০২৬)", "Enter the fiscal year as 4 digits (e.g. 2026)")
        else if (data?.items?.any { it.fiscalYear == y } == true) t("এই অর্থবছরের ডিভিডেন্ড আগেই ঘোষণা হয়েছে", "A dividend for this fiscal year was already declared") else null
        errPer = if (per == null) t("প্রতি শেয়ারে ডিভিডেন্ডের পরিমাণ দিন", "Enter the dividend per share") else null
        errDate = if (date == null) t("বিতরণের তারিখ বাছাই করুন", "Pick the distribution date") else null
        return errYear == null && errPer == null && errDate == null
    }

    AdminScreen(
        header = {
            ScreenHeader(t("ডিভিডেন্ড", "Dividend"), onBack = an::back) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeaderStat(t("শেষ ঘোষণা", "Last declared"), last?.let { t("${Bn.digits(it.fiscalYear)} অর্থবছর", "FY ${it.fiscalYear}") } ?: "—", Modifier.weight(1f))
                    HeaderStat(t("সর্বশেষ মোট বিতরণ", "Latest total paid"), last?.let { Bn.money(it.totalPaisa) } ?: "—", Modifier.weight(1f))
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
                        T(t("নতুন ডিভিডেন্ড ঘোষণা", "Declare a new dividend"), 17.sp, FontWeight.Bold)
                        Field(t("অর্থবছর", "Fiscal year"), year, { year = it.take(4); errYear = null }, keyboard = KeyboardType.Number, error = errYear, placeholder = t("যেমন ২০২৬", "e.g. 2026"))
                        Field(t("প্রতি শেয়ারে ডিভিডেন্ড (৳)", "Dividend per share (৳)"), perShare, { perShare = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }; errPer = null },
                            keyboard = KeyboardType.Decimal, error = errPer, placeholder = t("যেমন ৮০.০০", "e.g. 80.00"), maxLength = 14)
                        DateField(t("বিতরণের তারিখ", "Distribution date"), date, { date = it; errDate = null }, min = Bn.today())
                        if (errDate != null) T(errDate ?: "", 13.sp, color = c.red)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            KeyValue(t("বিক্রিত শেয়ার", "Shares sold"), t("${Bn.num(sold)}টি", Bn.num(sold)), size = 14.sp)
                            KeyValue(t("প্রতি শেয়ার", "Per share"), per?.let { Bn.money(it) } ?: "—", size = 14.sp)
                            RowDivider(Modifier.padding(vertical = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T(t("মোট বিতরণ হবে", "Total to be paid"), 15.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                                T(Bn.money(total), 22.sp, FontWeight.Bold)
                            }
                        }
                        PrimaryButton(t("ডিভিডেন্ড ঘোষণা করুন", "Declare dividend"), { if (validate()) confirm = true }, loading = action.busy)
                    }
                }
            }
            item("history") {
                ResoCard {
                    T(t("আগের ডিভিডেন্ড", "Previous dividends"), 17.sp, FontWeight.Bold)
                    if (d.items.isEmpty()) EmptyBlock(t("এখনো কোনো ডিভিডেন্ড ঘোষণা হয়নি", "No dividends declared yet"), ResoIcons.Dollar)
                    d.items.forEach { v ->
                        RowDivider(Modifier.padding(vertical = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                T(t("${Bn.digits(v.fiscalYear)} অর্থবছর", "FY ${v.fiscalYear}"), 16.sp, FontWeight.Bold)
                                T(t("প্রতি শেয়ারে ${Bn.money(v.perSharePaisa)} · ${Bn.num(v.totalShares)}টি শেয়ার", "${Bn.money(v.perSharePaisa)} per share · ${Bn.num(v.totalShares)} shares"), 13.sp, color = Reso.colors.muted)
                                T(t("বিতরণ: ${Bn.date(v.distributionDate)}", "Paid on: ${Bn.date(v.distributionDate)}"), 13.sp, color = Reso.colors.muted)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                T(Bn.money(v.totalPaisa), 16.sp, FontWeight.Bold)
                                val c = Reso.colors
                                if (v.status == "distributed") Tag(t("বিতরণ সম্পন্ন", "Distributed"), c.successBg, c.green) else Tag(t("ঘোষিত", "Declared"), c.warnBg, c.brown)
                            }
                        }
                    }
                }
            }
            item("note") {
                InfoBox(t("সংখ্যাগুলো সার্ভারের হিসাব অনুযায়ী। ডিভিডেন্ড ঘোষণার নিয়ম ও করের হিসাব আপনার অডিটর বা আইনজীবীর সঙ্গে যাচাই করে নিন। ঘোষণা করলে সব শেয়ারহোল্ডার অ্যাপের নোটিশে জানতে পারবেন।", "Figures are calculated by the server. Check dividend rules and tax with your auditor or lawyer. All shareholders are notified in the app once you declare."))
            }
        }
    }
    if (confirm) ConfirmDialog(
        t("ডিভিডেন্ড ঘোষণা করবেন?", "Declare this dividend?"),
        t("${Bn.digits(Bn.ascii(year))} অর্থবছর · প্রতি শেয়ারে ${Bn.money(per ?: 0)} · মোট ${Bn.money(total)}। বিতরণ: ${date?.let { Bn.date(it) } ?: ""}। ঘোষণার পর এটি বদলানো যাবে না।", "FY ${Bn.ascii(year)} · ${Bn.money(per ?: 0)} per share · total ${Bn.money(total)}. Paid on: ${date?.let { Bn.date(it) } ?: ""}. This can't be changed after declaring."),
        t("ঘোষণা করুন", "Declare"),
        {
            confirm = false
            val p = per ?: return@ConfirmDialog
            val dt = date ?: return@ConfirmDialog
            action.run({ Graph.adminApi.declareDividend(DividendRequest(Bn.ascii(year).trim(), p, dt.toString())) }) {
                Toaster.show(t("ডিভিডেন্ড ঘোষণা হয়েছে", "Dividend declared"))
                perShare = ""; date = null
                loader.load(silent = true)
            }
        },
        { confirm = false },
    )
}
