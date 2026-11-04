package net.rapti.resoshare.user

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.ui.Bubble
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.ErrorBlock
import net.rapti.resoshare.core.ui.GradientCard
import net.rapti.resoshare.core.ui.HeaderPill
import net.rapti.resoshare.core.ui.IconDisc
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.LoadingBlock
import net.rapti.resoshare.core.ui.ProgressTrack
import net.rapti.resoshare.core.ui.Refreshable
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.SectionTitle
import net.rapti.resoshare.core.ui.StatusText
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.TextDisc
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.animatedLong
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Dashboard
import net.rapti.resoshare.data.api.Order

@Composable
fun HomeScreen(nav: NavHostController) {
    val state by UserRepo.dashboard.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { UserRepo.refresh() }

    Refreshable(refreshing, onRefresh = {
        scope.launch { refreshing = true; UserRepo.refresh()?.let { Toaster.show(it.message) }; refreshing = false }
    }) {
        when (val s = state) {
            is Load.Ready -> HomeContent(nav, s.data)
            is Load.Failed -> Column(Modifier.fillMaxSize()) {
                HomeHeaderPlain()
                ErrorBlock(s.message, onRetry = { scope.launch { UserRepo.refresh() } })
            }
            Load.Loading -> Column(Modifier.fillMaxSize()) { HomeHeaderPlain(); LoadingBlock() }
        }
    }
}

@Composable
private fun HomeHeaderPlain() {
    Box(Modifier.fillMaxWidth().background(Reso.colors.header).statusBarsPadding().height(96.dp))
}

@Composable
private fun HomeContent(nav: NavHostController, d: Dashboard) {
    val c = Reso.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "hero") {
            Column {
                // Navy header
                Row(
                    Modifier.fillMaxWidth().background(c.header).statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        T("আসসালামু আলাইকুম", 14.sp, color = Brand.HeaderMuted)
                        T(d.me.fullName, 21.sp, FontWeight.Bold, Color.White, maxLines = 1)
                    }
                    HeaderPill(d.me.shareholderNo)
                    Spacer(Modifier.width(10.dp))
                    val unread by UserRepo.unread.collectAsState()
                    CircleIconButton(ResoIcons.Bell, "নোটিশ (${Bn.num(unread)}টি নতুন)", { nav.navigate(Routes.NOTICES) }, badge = unread)
                }
                // Cards float over the bottom of the header.
                Column(
                    Modifier.fillMaxWidth()
                        .drawBehind { drawRect(c.header, size = Size(size.width, 56.dp.toPx())) }
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RateCard(d)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GradientCard(c.shares, Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconDisc(ResoIcons.Layers, c.ink, c.onInk)
                                T("আমার মোট শেয়ার", 14.sp, FontWeight.Bold, c.shares.label)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                T(Bn.num(animatedLong(d.holding.shares.toLong())), 32.sp, FontWeight.Bold, c.shares.value, lineHeight = 38.sp)
                                T(" টি", 16.sp, color = c.shares.value, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                        GradientCard(c.paid, Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconDisc(ResoIcons.Wallet, c.green, Color.White)
                                T("মোট পরিশোধ", 14.sp, FontWeight.Bold, c.paid.label)
                            }
                            Spacer(Modifier.height(8.dp))
                            T(Bn.money(animatedLong(d.holding.paidPaisa)), 20.sp, FontWeight.Bold, c.paid.value, maxLines = 1, lineHeight = 38.sp)
                        }
                    }
                }
            }
        }
        item(key = "body") {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KycBanner(nav, d)
                ResoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("বিক্রির জন্য অবশিষ্ট শেয়ার", 14.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                        T("${Bn.num(d.pool.remainingShares)}টি", 15.sp, FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    ProgressTrack(if (d.pool.totalShares > 0) d.pool.soldShares.toFloat() / d.pool.totalShares else 0f)
                    if (!d.pool.saleEnabled) {
                        Spacer(Modifier.height(8.dp))
                        T("এই মুহূর্তে শেয়ার বিক্রি বন্ধ আছে", 13.sp, color = c.red)
                    }
                }
                GradientCard(c.rules, Modifier.fillMaxWidth(), radius = 14.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), onClick = { nav.navigate(Routes.RULES) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconDisc(ResoIcons.Shield, Brand.Navy, Brand.Gold, 40.dp, 22.dp)
                        Column(Modifier.weight(1f)) {
                            T("নিয়ম ও রেগুলেশন", 16.sp, FontWeight.Bold, c.rules.value)
                            T("শেয়ার কেনা-বেচা ও নমিনির নিয়ম পড়ুন", 13.sp, color = c.rules.label)
                        }
                        Icon(ResoIcons.Chevron, null, tint = c.rules.label, modifier = Modifier.size(18.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickAction("শেয়ার কিনুন", ResoIcons.PlusCircle, primary = true, Modifier.weight(1f)) { nav.tab(Routes.BUY) }
                    QuickAction("সার্টিফিকেট", ResoIcons.Document, primary = false, Modifier.weight(1f)) { nav.navigate(Routes.CERTIFICATE) }
                    QuickAction("লেনদেন", ResoIcons.List, primary = false, Modifier.weight(1f)) { nav.navigate(Routes.ORDERS) }
                }
                SectionTitle("সাম্প্রতিক লেনদেন", "সব দেখুন") { nav.navigate(Routes.ORDERS) }
                if (d.recentOrders.isEmpty()) {
                    ResoCard { T("এখনো কোনো লেনদেন নেই। প্রথম শেয়ারটি কিনে ফেলুন!", 15.sp, color = c.muted) }
                } else {
                    ResoCard(padding = PaddingValues(0.dp)) {
                        d.recentOrders.forEachIndexed { i, o ->
                            if (i > 0) RowDivider()
                            OrderRow(o)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateCard(d: Dashboard) {
    val c = Reso.colors
    val g = c.rate
    val change = d.today.prevRatePaisa?.let { d.today.ratePaisa - it } ?: 0L
    Box(
        Modifier.fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = Brand.Amber.copy(alpha = 0.4f), spotColor = Brand.Amber.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(g.start, g.end)))
            .border(1.dp, g.border, RoundedCornerShape(20.dp)),
    ) {
        Bubble(120.dp, Color.White.copy(alpha = if (c.dark) 0.06f else 0.5f), Modifier.align(Alignment.TopEnd).offset(x = 26.dp, y = (-30).dp))
        Bubble(80.dp, Brand.Amber.copy(alpha = 0.18f), Modifier.align(Alignment.BottomEnd).offset(x = (-36).dp, y = 40.dp))
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextDisc("৳", Brand.Amber, Brand.Navy, 36.dp, 20.sp)
                Spacer(Modifier.width(10.dp))
                T("আজকের রেট", 16.sp, FontWeight.Bold, g.label, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.Navy).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    T("আজ · ${Bn.dayMonth(Bn.parseDate(d.today.date) ?: Bn.today())}", 12.sp, FontWeight.Bold, Brand.Gold, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                T(Bn.money(animatedLong(d.today.ratePaisa)), 34.sp, FontWeight.Bold, g.value, lineHeight = 44.sp)
                Spacer(Modifier.width(8.dp))
                T("প্রতি শেয়ার", 14.sp, color = g.label, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("কেনা ও বিক্রয়ের রেট একই", 13.sp, color = g.label, modifier = Modifier.weight(1f))
                if (change != 0L) {
                    T((if (change > 0) "▲ " else "▼ ") + Bn.signedMoney(change) + " আজ", 13.sp, FontWeight.Bold, if (change > 0) c.green else c.red)
                }
            }
        }
    }
}

@Composable
private fun KycBanner(nav: NavHostController, d: Dashboard) {
    when {
        d.me.accountStatus == "suspended" -> InfoBox("আপনার অ্যাকাউন্ট সাময়িকভাবে স্থগিত। বিস্তারিত জানতে হটলাইনে যোগাযোগ করুন।", title = "অ্যাকাউন্ট স্থগিত", tone = Tone.DANGER, icon = ResoIcons.Lock)
        d.me.kycStatus == "pending" -> InfoBox("অ্যাডমিন আপনার NID ও ছবি যাচাই করছেন। যাচাই হলে নোটিশে জানানো হবে এবং শেয়ার কেনা চালু হবে।", title = "KYC যাচাই চলছে", tone = Tone.WARN, icon = ResoIcons.Clock)
        d.me.kycStatus == "rejected" -> Column(Modifier.pressable(onClick = { nav.navigate(Routes.KYC) })) {
            InfoBox("কারণ: ${d.me.kycNote ?: "তথ্য সঠিক নয়"}\nসঠিক তথ্য ও ছবি দিয়ে আবার জমা দিতে এখানে চাপুন।", title = "KYC বাতিল হয়েছে", tone = Tone.DANGER)
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Reso.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.heightIn(min = 88.dp).clip(shape)
            .background(if (primary) Brand.Amber else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.border, shape))
            .pressable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = if (primary) Brand.Navy else c.ink, modifier = Modifier.size(26.dp))
        T(label, 14.sp, FontWeight.Bold, if (primary) Brand.Navy else c.text, maxLines = 1)
    }
}

@Composable
fun OrderRow(o: Order, showMethod: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = Reso.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("${Bn.num(o.quantity)}টি শেয়ার" + if (showMethod) " × ${Bn.money(o.ratePaisa)}" else "", 15.sp, FontWeight.Bold)
            T(Bn.dateOf(o.createdAt) + if (showMethod) " · ${net.rapti.resoshare.core.ui.methodLabel(o.method)}" else "", 13.sp, color = c.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            T(Bn.money(o.amountPaisa), 15.sp, FontWeight.Bold)
            StatusText(o.status)
        }
        trailing?.invoke()
    }
}

