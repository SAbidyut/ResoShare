package net.rapti.resoshare

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import net.rapti.resoshare.core.i18n.AppLang
import net.rapti.resoshare.core.i18n.Lang
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.time.Duration

/**
 * Drives the admin portal of the demo app end to end (Bangla, then English) and saves a
 * screenshot of every screen to build/screens/admin/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi", application = ResoApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdminScreensTest {
    companion object { init { FakeAndroidKeyStore.install() } }

    @get:Rule val rule = createEmptyComposeRule()
    private val outDir = File("build/screens/admin").apply { mkdirs() }
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun setup() {
        FakeAndroidKeyStore.install()
        ShadowBuild.setTags("release-keys")
        Lang.set(AppLang.BN)
        // The app starts in the shareholder portal; the admin portal is a switch away.
        Graph.switchPortal("admin")
    }

    private fun advance(ms: Long = 100) {
        rule.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(m: SemanticsMatcher, what: String, timeout: Long = 15_000) {
        var waited = 0L
        while (!exists(m)) {
            advance(100); waited += 100
            if (waited > timeout) { runCatching { shot("FAIL-" + what.take(20)) }; throw AssertionError("timeout waiting for $what") }
        }
        advance(500)
    }

    private fun waitText(t: String) = waitFor(hasText(t, substring = true), t)

    private fun waitGone(t: String, timeout: Long = 15_000) {
        var waited = 0L
        while (exists(hasText(t, substring = true))) {
            advance(100); waited += 100
            if (waited > timeout) throw AssertionError("still showing $t")
        }
        advance(400)
    }

    /** Draws every window (activity + dialogs) so overlays appear in the screenshot. */
    private fun shot(name: String) {
        advance(1000)
        scenario.onActivity { a ->
            val root = a.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            root.draw(canvas)
            runCatching {
                val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                val list = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<android.view.View>
                list.filter { it !== root }.forEach { v ->
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                    canvas.drawColor(0x66000000)
                    v.draw(canvas); canvas.restore()
                }
            }
            File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun scrollTo(m: SemanticsMatcher) {
        runCatching { rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(m) }
        advance(300)
    }

    private fun click(text: String, substring: Boolean = false, scroll: Boolean = true) {
        val m = hasText(text, substring = substring) and hasClickAction()
        if (scroll && !exists(m)) scrollTo(m)
        val node = rule.onAllNodes(m).onFirst()
        if (scroll) runCatching { node.performScrollTo() }
        advance(200)
        node.performClick()
        advance(400)
    }

    private fun back() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advance(600)
    }

    @Test fun walkthrough() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            scenario = sc
            // ---- login (Bangla)
            waitText("নিরাপদ অ্যাডমিন লগইন"); shot("01-login")
            click("লগইন করুন")
            waitText("২-ধাপ যাচাই"); shot("02-otp")
            rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("123456")
            waitText("আজকের শেয়ার রেট"); waitText("শেয়ার বিক্রির চার্ট"); shot("03-dashboard")

            // ---- shareholders, detail, certificate
            click("শেয়ারহোল্ডার"); waitText("মোট নিবন্ধিত"); waitText("RA-SH-"); shot("04-shareholders")
            scrollTo(hasText("মোঃ রহিম উদ্দিন"))
            rule.onAllNodesWithText("মোঃ রহিম উদ্দিন").onFirst().performClick(); advance(500)
            waitText("কেনার ইতিহাস"); shot("05-holder-detail")
            scrollTo(hasText("সার্টিফিকেট") and hasClickAction()); shot("05a-holder-actions")
            click("দেখুন"); advance(800); shot("05b-holder-contact")
            click("সার্টিফিকেট"); waitText("সার্টিফিকেট নম্বর"); shot("05c-holder-certificate")
            back(); back()

            // ---- approvals
            click("অনুমোদন"); waitText("সিদ্ধান্তের অপেক্ষায়"); shot("06-approvals")
            rule.onAllNodes(hasText("অনুমোদন", substring = false) and hasClickAction()).onFirst().performClick(); advance(400)
            waitText("পেমেন্ট অনুমোদন করবেন?"); shot("06b-approve-confirm")
            click("অনুমোদন করুন", scroll = false); advance(1500); shot("06c-after-approve")
            rule.onAllNodes(hasText("KYC (", substring = true) and hasClickAction()).onFirst().performClick(); advance(800); shot("07-kyc")

            // ---- more menu and every sub screen
            click("আরও"); waitText("শেয়ার ব্যবস্থাপনা"); shot("08-more")
            click("লেনদেন"); waitText("এই মাসে মোট"); waitText("টি · "); shot("09-transactions"); back()
            click("শেয়ার ও রেট"); waitText("আগামী ১৫ দিনের রেট সূচি"); shot("10-rate"); back()
            click("ডিভিডেন্ড"); waitText("নতুন ডিভিডেন্ড ঘোষণা"); shot("11-dividend"); back()
            click("রিপোর্ট"); waitText("রিপোর্টের ধরন"); shot("12-reports")
            click("CSV"); click("রিপোর্ট তৈরি করুন", substring = true); waitText("ফাইল তৈরি হয়েছে"); shot("12b-report-ready"); click("বন্ধ করুন", scroll = false); back()
            click("নোটিশ ও বার্তা"); waitText("পাঠানো বার্তা"); shot("13-notices"); back()
            click("নিয়ম ও রেগুলেশন"); waitText("ধারাসমূহ"); shot("14-rules")
            rule.onAllNodes(hasText("সম্পাদনা") and hasClickAction()).onFirst().performClick(); advance(800); shot("14b-rules-edit")
            back()
            click("ইউজার ও রোল"); waitText("রোল অনুযায়ী অনুমতি"); shot("15-staff"); back()
            click("অডিট লগ"); waitText("কে কখন কী পরিবর্তন করেছে"); waitText("অ্যাডমিন লগইন"); shot("16-audit"); back()
            click("সেটিংস"); waitText("কোম্পানির তথ্য"); shot("17-settings")
            scrollTo(hasText("ডাটা ব্যাকআপ")); waitText("শেষ ব্যাকআপ"); shot("17b-settings-backup")
            click("এখনই ব্যাকআপ নিন"); waitText("এখনই ব্যাকআপ নেবেন?"); click("ব্যাকআপ নিন", scroll = false); advance(1200); shot("17c-backup-requested")
            scrollTo(hasText("ভাষা")); shot("17d-settings-language")
            back()
            click("পাসওয়ার্ড পরিবর্তন"); waitText("বর্তমান পাসওয়ার্ড"); shot("18-password"); back()

            // ---- dark theme + rate edit
            click("ডার্ক"); advance(800)
            click("ড্যাশবোর্ড"); waitText("আজকের শেয়ার রেট"); shot("19-dashboard-dark")
            click("আরও"); advance(500)
            click("শেয়ার ও রেট"); waitText("আগামী ১৫ দিনের রেট সূচি"); shot("20-rate-dark")
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("বাড়ান")))[0].performClick()
            advance(400); waitText("সংরক্ষণ বাকি"); shot("20b-rate-dirty")
            click("রেট সূচি সংরক্ষণ করুন"); waitText("রেট সূচি সংরক্ষণ করবেন?"); click("সংরক্ষণ করুন", scroll = false); advance(1500)
            scrollTo(hasText("আগামী ১৫ দিনের রেট সূচি"))
            waitText("সংরক্ষিত"); shot("20c-rate-saved")
            back()
            click("লাইট"); advance(600)

            // ---- English
            click("English"); advance(800)
            waitText("Language"); shot("30-en-more")
            click("Dashboard"); waitText("Today's share rate"); shot("31-en-dashboard")
            click("Shareholders"); waitText("registered"); shot("32-en-shareholders")
            click("Approvals"); waitText("awaiting your decision"); shot("33-en-approvals")
            click("More"); advance(400)
            click("Shares & rate"); waitText("15-day rate schedule"); shot("34-en-rate"); back()
            click("Settings"); waitText("Company details"); shot("35-en-settings")
            scrollTo(hasText("Data backup")); shot("35b-en-settings-backup"); back()
            click("Reports"); waitText("Report type"); shot("36-en-reports"); back()

            // ---- logout → admin login (English) → shareholder login link switches portal
            click("Log out"); waitText("Log out?")
            rule.onAllNodes(hasText("Log out") and hasClickAction()).onLast().performClick(); advance(800)
            waitText("Secure admin login"); shot("37-en-login")
            click("Shareholder login")
            waitGone("Secure admin login")
            check(Graph.portal.value == "user") { "portal should be user after tapping Shareholder login" }
            shot("38-switched-to-shareholder")
        }
    }
}
