package net.rapti.resoshare

import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.rapti.resoshare.core.i18n.AppLang
import net.rapti.resoshare.core.i18n.Lang
import net.rapti.resoshare.core.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Drives the shareholder portal (demo flavor) end to end and records screenshots of every
 * screen in light, dark and English into build/screens/user/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w392dp-h1700dp-xxhdpi")
class UserScreensTest {
    companion object { init { FakeAndroidKeyStore.install() } }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
            rule.mainClock.advanceTimeBy(60)
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("Timed out waiting for: $what")
        }
    }

    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitText(text: String) = waitFor(text) { has(text) }
    private fun settle() { repeat(25) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80)); rule.mainClock.advanceTimeBy(80) } }

    private fun clickable(text: String): SemanticsNodeInteractionCollection =
        rule.onAllNodesWithText(text, substring = true).filter(hasClickAction())

    private fun click(text: String) {
        val exact = rule.onAllNodesWithText(text, substring = false).filter(hasClickAction())
        val node = if (exact.fetchSemanticsNodes().isNotEmpty()) exact.onFirst() else clickable(text).onFirst()
        runCatching { node.performScrollTo() }
        settle()
        node.performClick(); settle()
    }

    private fun back() { rule.activity.onBackPressedDispatcher.onBackPressed(); settle() }

    private fun shot(name: String) {
        settle()
        rule.onRoot().captureRoboImage("build/screens/user/$name.png")
    }

    private fun bothThemes(name: String) {
        Graph.themeMode.value = ThemeMode.LIGHT
        shot("${name}_light")
        Graph.themeMode.value = ThemeMode.DARK
        shot("${name}_dark")
        Graph.themeMode.value = ThemeMode.BLACK
        shot("${name}_black")
        Graph.themeMode.value = ThemeMode.LIGHT
        settle()
    }

    @Test
    fun walkThroughAllScreens() {
        Lang.set(AppLang.BN)
        waitText("লগইন করুন")
        if (has("বুঝেছি")) click("বুঝেছি")
        waitText("আজকের শেয়ার রেট")
        bothThemes("01_login")

        // Registration wizard.
        click("রেজিস্ট্রেশন করুন")
        waitText("মাত্র ৩ ধাপে")
        bothThemes("02_register_step1")
        click("পরবর্তী ধাপ")
        shot("02_register_step1_errors")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("মোঃ করিম উদ্দিন")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("01712345678")
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("karim@example.com")
        rule.onAllNodes(hasSetTextAction())[3].performTextInput("Strong#Pass99")
        rule.onAllNodes(hasSetTextAction())[4].performTextInput("Strong#Pass99")
        shot("02_register_step1_filled")
        click("পরবর্তী ধাপ")
        waitText("নথি আপলোড করুন")
        bothThemes("03_register_step2_kyc")
        back(); back()
        waitText("পাসওয়ার্ড ভুলে গেছেন?")

        // Login with the demo account.
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("01911000124")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("Demo@1234")
        clickable("লগইন করুন").onFirst().performClick()
        waitText("আসসালামু আলাইকুম")
        waitText("নোটিশ বোর্ড")
        waitText("৭ দিনের রেট")
        bothThemes("04_home")

        click("রেট চার্ট")
        waitText("বৃদ্ধি ও হ্রাস")
        bothThemes("05_chart")

        click("নোটিশ")
        waitText("কোম্পানির বার্তা শুধু")
        bothThemes("06_notices")

        click("প্রোফাইল")
        waitText("দ্রুত লিংক")
        bothThemes("07_profile_summary")
        click("আমার শেয়ার")
        waitText("আমার শেয়ারের তালিকা")
        shot("07_profile_shares_light")
        click("নমিনি")
        waitText("সর্বোচ্চ ২ জন")
        shot("07_profile_nominees_light")
        click("সারাংশ")

        click("শেয়ার সার্টিফিকেট")
        waitText("সার্টিফিকেট নম্বর")
        bothThemes("08_certificate")
        back()

        click("নিয়ম ও রেগুলেশন")
        waitText("শেয়ার কেনার নিয়ম")
        bothThemes("09_rules")
        back()

        // Buy (from the bottom-bar + button).
        click("হোম")
        waitText("আসসালামু আলাইকুম")
        clickable("শেয়ার কিনুন").onFirst().performClick(); settle()
        waitText("কয়টি শেয়ার কিনবেন")
        bothThemes("10_buy")
        click("ব্যাংক ট্রান্সফার")
        shot("10_buy_bank_light")
        back()

        click("প্রোফাইল")
        waitText("দ্রুত লিংক")
        click("সেটিংস")
        waitText("অ্যাপের থিম")
        shot("11_settings_light")
        click("সব লেনদেন")
        waitText("ORD-")
        bothThemes("12_orders")
        back()

        // Switch to English and capture key screens.
        click("English")
        waitText("App theme")
        shot("13_settings_en_light")
        back()
        waitText("Quick links")
        shot("14_profile_en_light")
        click("Home")
        waitText("Notice board")
        shot("15_home_en_light")
        clickable("Buy").onFirst().performClick(); settle()
        waitText("How many shares")
        shot("16_buy_en_light")
        back()
        click("Notices")
        waitText("Company messages")
        shot("17_notices_en_light")
        Lang.set(AppLang.BN)
    }
}
