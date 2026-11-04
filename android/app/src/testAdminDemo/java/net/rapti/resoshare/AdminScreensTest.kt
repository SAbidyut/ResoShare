package net.rapti.resoshare

import android.graphics.Bitmap
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import net.rapti.resoshare.MainActivity
import net.rapti.resoshare.ResoApp
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
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.time.Duration
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// ---- minimal in-memory AndroidKeyStore so SecureStore works under Robolectric (test only) ----
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xhdpi", application = ResoApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdminScreensTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val outDir = File("build/screens/admin").apply { mkdirs() }

    @Before fun setup() {
        FakeAndroidKeyStore.install()
        ShadowBuild.setTags("release-keys")
    }

    private fun advance(ms: Long = 100) {
        rule.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(m: SemanticsMatcher, what: String, timeout: Long = 15_000) {
        var waited = 0L
        while (!exists(m)) {
            advance(100); waited += 100
            if (waited > timeout) { runCatching { shot("FAIL-" + what.take(20)) }; throw AssertionError("timeout waiting for $what") }
        }
        advance(600)
    }

    private fun waitText(t: String) = waitFor(hasText(t, substring = true), t)

    private fun shot(name: String) {
        advance(1200)
        scenario.onActivity { a ->
            // Draw every window (activity + dialogs/sheets) so overlays appear in the screenshot.
            val root = a.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val views = org.robolectric.shadows.ShadowWindowManagerGlobal::class.java
            root.draw(canvas)
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
                val list = wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<android.view.View>
                list.filter { it !== root }.forEach { v ->
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                    canvas.drawColor(0x66000000)
                    v.draw(canvas); canvas.restore()
                }
            }
            check(views != null)
            File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun click(text: String) {
        val m = hasText(text, substring = false) and hasClickAction()
        runCatching { rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(m) }
        advance(300)
        rule.onAllNodes(m).onFirst().performClick()
        advance(400)
    }

    private fun back(sc: ActivityScenario<MainActivity>) {
        sc.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        advance(600)
    }

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Test fun walkthrough() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            scenario = sc
            waitText("নিরাপদ অ্যাডমিন লগইন"); shot("01-login")
            click("লগইন করুন")
            waitText("২-ধাপ যাচাই"); shot("02-otp")
            rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("123456")
            waitText("আজকের শেয়ার রেট"); waitText("শেয়ার বিক্রির চার্ট"); shot("03-dashboard")

            click("শেয়ারহোল্ডার"); waitText("মোট নিবন্ধিত"); waitText("RA-SH-"); shot("04-shareholders")
            rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("মোঃ রহিম উদ্দিন")); advance(500)
            rule.onAllNodesWithText("মোঃ রহিম উদ্দিন").onFirst().performClick(); advance(500)
            waitText("কেনার ইতিহাস"); shot("05-holder-detail")
            click("দেখুন"); advance(800); shot("05b-holder-contact")
            back(sc)

            click("অনুমোদন"); waitText("সিদ্ধান্তের অপেক্ষায়"); shot("06-approvals")
            rule.onAllNodes(hasText("অনুমোদন") and hasClickAction()).fetchSemanticsNodes() // sanity
            // approve first payment
            rule.onAllNodes(hasText("অনুমোদন", substring = false) and hasClickAction()).onFirst().performClick(); advance(400)
            waitText("পেমেন্ট অনুমোদন করবেন?"); shot("06b-approve-confirm")
            click("অনুমোদন করুন"); advance(1500); shot("06c-after-approve")
            rule.onAllNodes(hasText("KYC (", substring = true) and hasClickAction()).onFirst().performClick(); advance(800); shot("07-kyc")

            click("আরও"); waitText("শেয়ার ব্যবস্থাপনা"); shot("08-more")
            click("লেনদেন"); waitText("এই মাসে মোট"); waitText("টি · "); shot("09-transactions"); back(sc)
            click("শেয়ার ও রেট"); waitText("আগামী ১৫ দিনের রেট সূচি"); shot("10-rate")
            back(sc)
            click("ডিভিডেন্ড"); waitText("নতুন ডিভিডেন্ড ঘোষণা"); shot("11-dividend"); back(sc)
            click("রিপোর্ট"); waitText("রিপোর্টের ধরন"); shot("12-reports")
            click("রিপোর্ট তৈরি করুন"); advance(3000); shot("12a-pdf-attempt")
            if (exists(hasText("ফাইল তৈরি হয়েছে"))) click("বন্ধ করুন")
            click("CSV"); click("রিপোর্ট তৈরি করুন"); waitText("ফাইল তৈরি হয়েছে"); shot("12b-report-ready"); click("বন্ধ করুন"); back(sc)
            click("নোটিশ ও বার্তা"); waitText("পাঠানো বার্তা"); shot("13-notices"); back(sc)
            click("নিয়ম ও রেগুলেশন"); waitText("ধারাসমূহ"); shot("14-rules")
            rule.onAllNodes(hasText("সম্পাদনা") and hasClickAction()).onFirst().performClick(); advance(800); shot("14b-rules-edit")
            back(sc)
            click("ইউজার ও রোল"); waitText("রোল অনুযায়ী অনুমতি"); shot("15-staff"); back(sc)
            click("অডিট লগ"); waitText("কে কখন কী পরিবর্তন করেছে"); waitText("অ্যাডমিন লগইন"); shot("16-audit"); back(sc)
            click("সেটিংস"); waitText("কোম্পানির তথ্য"); shot("17-settings"); back(sc)
            click("পাসওয়ার্ড পরিবর্তন"); waitText("বর্তমান পাসওয়ার্ড"); shot("18-password"); back(sc)

            click("ডার্ক"); advance(800)
            click("ড্যাশবোর্ড"); waitText("আজকের শেয়ার রেট"); shot("19-dashboard-dark")
            click("আরও"); advance(500)
            click("শেয়ার ও রেট"); waitText("আগামী ১৫ দিনের রেট সূচি"); shot("20-rate-dark")
            // edit a rate: tap + on first schedule row and save
            rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf("বাড়ান")))[0].performClick()
            advance(400); waitText("সংরক্ষণ বাকি"); shot("20b-rate-dirty")
            rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("রেট সূচি সংরক্ষণ করুন"))
            click("রেট সূচি সংরক্ষণ করুন"); waitText("রেট সূচি সংরক্ষণ করবেন?"); click("সংরক্ষণ করুন"); advance(1500)
            rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("আগামী ১৫ দিনের রেট সূচি"))
            waitText("সংরক্ষিত"); shot("20c-rate-saved")
            back(sc)
            click("লগআউট"); waitText("লগআউট করবেন?"); rule.onAllNodes(hasText("লগআউট") and hasClickAction()).onLast().performClick(); advance(800)
            waitText("নিরাপদ অ্যাডমিন লগইন"); shot("21-logged-out")
            // keep unused import happy
            rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("admin@rapti.net")
        }
    }
}
