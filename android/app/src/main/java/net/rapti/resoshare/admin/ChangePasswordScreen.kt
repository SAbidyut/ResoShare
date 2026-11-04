package net.rapti.resoshare.admin

import androidx.compose.foundation.layout.Arrangement
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
        errCurrent = if (current.isEmpty()) "বর্তমান পাসওয়ার্ড দিন" else null
        errNew = V.password(new) ?: if (new == current) "নতুন পাসওয়ার্ড আগেরটির থেকে আলাদা হতে হবে" else null
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
            Toaster.show("পাসওয়ার্ড পরিবর্তন হয়েছে")
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
            "পাসওয়ার্ড পরিবর্তন",
            subtitle = if (forced) "প্রথম লগইনে নতুন পাসওয়ার্ড দিতে হবে" else null,
            onBack = if (forced) null else onBack,
        )
    }) {
        if (forced) item { InfoBox("নিরাপত্তার জন্য অস্থায়ী পাসওয়ার্ডটি এখনই বদলে নিজের একটি শক্তিশালী পাসওয়ার্ড দিন।", tone = Tone.WARN) }
        item {
            ResoCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Field("বর্তমান পাসওয়ার্ড", current, { current = it; errCurrent = null }, password = true, error = errCurrent, maxLength = 128)
                    Field("নতুন পাসওয়ার্ড", new, { new = it; errNew = null }, password = true, error = errNew, maxLength = 128,
                        placeholder = "কমপক্ষে ৮ অক্ষর", hint = "অক্ষর ও সংখ্যা দুটোই রাখুন; সহজ পাসওয়ার্ড গ্রহণযোগ্য নয়")
                    Field("নতুন পাসওয়ার্ড আবার দিন", confirm, { confirm = it; errConfirm = null }, password = true, error = errConfirm,
                        maxLength = 128, ime = ImeAction.Done, onDone = ::submit)
                }
            }
        }
        item { PrimaryButton("পাসওয়ার্ড বদলান", ::submit, loading = action.busy) }
        if (forced) item { SecondaryButton("লগআউট", { scope.launch { Auth.logout(null) } }, height = 50.dp) }
    }
}
