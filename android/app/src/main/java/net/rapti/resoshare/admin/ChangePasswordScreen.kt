package net.rapti.resoshare.admin

import androidx.compose.foundation.layout.Arrangement
import net.rapti.resoshare.core.i18n.t
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.rapti.resoshare.Auth
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.net.Res
import net.rapti.resoshare.core.net.call
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.PrimaryButton
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.data.api.PasswordChange
import androidx.compose.ui.text.input.ImeAction

@Composable
fun ChangePasswordScreen(forced: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errCurrent by remember { mutableStateOf<String?>(null) }
    var errNew by remember { mutableStateOf<String?>(null) }
    var errConfirm by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    val scope = rememberCoroutineScope()

    fun submit() {
        errCurrent = if (current.isEmpty()) t("বর্তমান পাসওয়ার্ড দিন", "Enter your current password") else null
        errNew = V.password(new) ?: if (new == current) t("নতুন পাসওয়ার্ড আগেরটির থেকে আলাদা হতে হবে", "The new password must differ from the old one") else null
        errConfirm = V.confirm(new, confirm)
        if (errCurrent != null || errNew != null || errConfirm != null) return
        action.run(
            { Graph.adminApi.changePassword(PasswordChange(current, new)) },
            onErr = { e ->
                errCurrent = e.fields["currentPassword"]
                errNew = e.fields["newPassword"]
                if (errCurrent == null && errNew == null) Toaster.show(e.message)
            },
        ) {
            current = ""; new = ""; confirm = ""
            Toaster.show(t("পাসওয়ার্ড পরিবর্তন হয়েছে", "Password changed"))
            if (forced) {
                scope.launch {
                    (call { Graph.adminApi.me() } as? Res.Ok)?.value?.let { afterSignIn(it) }
                    onDone()
                }
            } else onDone()
        }
    }

    AdminScreen(header = {
        ScreenHeader(
            t("পাসওয়ার্ড পরিবর্তন", "Change password"),
            subtitle = if (forced) t("প্রথম লগইনে নতুন পাসওয়ার্ড দিতে হবে", "Set a new password on first login") else null,
            onBack = if (forced) null else onBack,
        )
    }) {
        if (forced) item { InfoBox(t("নিরাপত্তার জন্য অস্থায়ী পাসওয়ার্ডটি এখনই বদলে নিজের একটি শক্তিশালী পাসওয়ার্ড দিন।", "For security, replace the temporary password now with your own strong password."), tone = Tone.WARN) }
        item {
            ResoCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Field(t("বর্তমান পাসওয়ার্ড", "Current password"), current, { current = it; errCurrent = null }, password = true, error = errCurrent, maxLength = 128)
                    Field(t("নতুন পাসওয়ার্ড", "New password"), new, { new = it; errNew = null }, password = true, error = errNew, maxLength = 128,
                        placeholder = t("কমপক্ষে ৮ অক্ষর", "At least 8 characters"), hint = t("অক্ষর ও সংখ্যা দুটোই রাখুন; সহজ পাসওয়ার্ড গ্রহণযোগ্য নয়", "Use letters, a number and a symbol; simple passwords are rejected"))
                    Field(t("নতুন পাসওয়ার্ড আবার দিন", "Confirm new password"), confirm, { confirm = it; errConfirm = null }, password = true, error = errConfirm,
                        maxLength = 128, ime = ImeAction.Done, onDone = ::submit)
                }
            }
        }
        item { PrimaryButton(t("পাসওয়ার্ড বদলান", "Change password"), ::submit, loading = action.busy) }
        if (forced) item { SecondaryButton(t("লগআউট", "Log out"), { scope.launch { Auth.logout(null) } }, height = 50.dp) }
    }
}
