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
 * Drives the real shareholder app (demo flavor) end to end and records screenshots of
 * every screen in light and dark theme into build/screens/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w392dp-h1600dp-xxhdpi")
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
        val node = clickable(text).onFirst()
        runCatching { node.performScrollTo() }
        settle()
        node.performClick(); settle()
    }

    private fun shot(name: String) {
        settle()
        rule.onRoot().captureRoboImage("build/screens/user/$name.png")
    }

    private fun bothThemes(name: String) {
        Graph.themeMode.value = ThemeMode.LIGHT
        shot("${name}_light")
        Graph.themeMode.value = ThemeMode.DARK
        shot("${name}_dark")
        Graph.themeMode.value = ThemeMode.LIGHT
        settle()
    }

    @Test
    fun walkThroughAllScreens() {
        waitText("লগইন করুন")
        if (has("বুঝেছি")) click("বুঝেছি")
        bothThemes("01_login")

        // Registration wizard (step 1 → validation errors → filled).
        click("রেজিস্ট্রেশন করুন")
        waitText("ধাপ ১")
        bothThemes("02_register_step1")
        click("পরবর্তী ধাপ")
        shot("02_register_step1_errors")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("মোঃ করিম উদ্দিন")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("01712345678")
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("karim@example.com")
        rule.onAllNodes(hasSetTextAction())[3].performTextInput("Strong#Pass99")
        rule.onAllNodes(hasSetTextAction())[4].performTextInput("Strong#Pass99")
        click("পরবর্তী ধাপ")
        waitText("ধাপ ২")
        bothThemes("03_register_step2_kyc")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()
        waitText("পাসওয়ার্ড ভুলে গেছেন?")

        // Login with the demo account.
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("01911000124")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("Demo@1234")
        clickable("লগইন করুন").onFirst().performClick()
        waitText("আসসালামু আলাইকুম")
        waitText("সাম্প্রতিক লেনদেন")
        bothThemes("04_home")

        click("শেয়ার কিনুন")
        waitText("কয়টি শেয়ার কিনবেন")
        bothThemes("05_buy")
        click("ব্যাংক ট্রান্সফার")
        shot("05_buy_bank_light")

        click("চার্ট")
        waitText("বৃদ্ধি ও হ্রাস")
        bothThemes("06_chart_daily")
        click("মাসিক")
        waitText("গত ১২ মাসে")
        shot("06_chart_monthly_light")

        click("প্রোফাইল")
        waitText("আজ বেচলে")
        waitText("আমার শেয়ারের তালিকা")
        bothThemes("07_profile")

        click("শেয়ার সার্টিফিকেট দেখুন")
        waitText("সার্টিফিকেট নম্বর")
        bothThemes("08_certificate")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()

        click("কোম্পানির বার্তা ও রেটের খবর")
        waitText("শেয়ারের রেট পরিবর্তন")
        bothThemes("09_notices")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()

        clickable("নিয়ম ও রেগুলেশন").onFirst().performClick(); settle()
        waitText("শেয়ার কেনার নিয়ম")
        bothThemes("10_rules")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()

        click("সম্পাদনা")
        waitText("মালিকানার ভাগ")
        bothThemes("11_nominee_edit")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()

        click("সব লেনদেন")
        waitText("ORD-")
        bothThemes("12_orders")
        rule.activity.onBackPressedDispatcher.onBackPressed(); settle()

        click("পাসওয়ার্ড পরিবর্তন")
        waitText("বর্তমান পাসওয়ার্ড")
        shot("13_change_password_light")
    }
}
