package net.rapti.resoshare.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rapti.resoshare.Graph
import net.rapti.resoshare.Toaster
import net.rapti.resoshare.core.format.Bn
import net.rapti.resoshare.core.format.V
import net.rapti.resoshare.core.ui.Avatar
import net.rapti.resoshare.core.ui.CheckRow
import net.rapti.resoshare.core.ui.CircleIconButton
import net.rapti.resoshare.core.ui.ConfirmDialog
import net.rapti.resoshare.core.ui.DangerButton
import net.rapti.resoshare.core.ui.EmptyBlock
import net.rapti.resoshare.core.ui.Field
import net.rapti.resoshare.core.ui.InfoBox
import net.rapti.resoshare.core.ui.InkButton
import net.rapti.resoshare.core.ui.KeyValue
import net.rapti.resoshare.core.ui.Load
import net.rapti.resoshare.core.ui.RadioCard
import net.rapti.resoshare.core.ui.ResoCard
import net.rapti.resoshare.core.ui.ResoIcons
import net.rapti.resoshare.core.ui.RowDivider
import net.rapti.resoshare.core.ui.ScreenHeader
import net.rapti.resoshare.core.ui.SecondaryButton
import net.rapti.resoshare.core.ui.SecretDialog
import net.rapti.resoshare.core.ui.T
import net.rapti.resoshare.core.ui.Tag
import net.rapti.resoshare.core.ui.Tone
import net.rapti.resoshare.core.ui.pressable
import net.rapti.resoshare.core.ui.theme.Brand
import net.rapti.resoshare.core.ui.theme.Reso
import net.rapti.resoshare.data.api.Perm
import net.rapti.resoshare.data.api.Role
import net.rapti.resoshare.data.api.RoleCreate
import net.rapti.resoshare.data.api.RolePatch
import net.rapti.resoshare.data.api.Staff
import net.rapti.resoshare.data.api.StaffCreate
import net.rapti.resoshare.data.api.StaffPatch

@Composable
fun StaffScreen(an: AdminNav) {
    val loader = rememberLoader("staff") { Graph.adminApi.staff() }
    val can = AdminSession.can(Perm.SETTINGS)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Staff?>(null) }
    var roleEdit by remember { mutableStateOf<Role?>(null) }
    var newRole by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf<Pair<String, String>?>(null) }
    val data = (loader.state as? Load.Ready)?.data

    AdminScreen(
        header = {
            ScreenHeader("ইউজার ও রোল", onBack = an::back, actions = {
                if (can && data != null) CircleIconButton(ResoIcons.Plus, "নতুন স্টাফ যোগ করুন", { adding = true }, bg = Brand.Amber, fg = Brand.Navy)
            })
        },
        refreshing = loader.refreshing,
        onRefresh = { loader.refresh() },
    ) {
        if (!can) item("np") { InfoBox("এই অংশ শুধু সুপার অ্যাডমিন (সেটিংস ও স্টাফ অনুমতি) দেখতে পারেন।", tone = Tone.WARN) }
        else loadItems(loader.state, { loader.load() }) { d ->
            item("staff") {
                ResoCard {
                    T("স্টাফ (${Bn.num(d.items.size)} জন)", 17.sp, FontWeight.Bold)
                    if (d.items.isEmpty()) EmptyBlock("কোনো স্টাফ নেই", ResoIcons.Users)
                    d.items.forEach { s ->
                        RowDivider(Modifier.padding(vertical = 6.dp))
                        StaffRow(s) { editing = s }
                    }
                }
            }
            item("matrix") { PermissionMatrix(d.roles) { r -> if (!r.isSystem) roleEdit = r } }
            item("newrole") { SecondaryButton("নতুন রোল তৈরি করুন", { newRole = true }, icon = ResoIcons.Plus) }
            item("note") {
                InfoBox("নতুন স্টাফ প্রথম লগইনে অস্থায়ী পাসওয়ার্ড বদলাবেন এবং Authenticator অ্যাপে OTP সেটআপ করবেন। সিস্টেম রোল বদলানো যায় না; কাস্টম রোলের নামে চাপ দিয়ে সম্পাদনা করুন।")
            }
        }
    }

    if (adding && data != null) AddStaffDialog(data.roles, onDismiss = { adding = false }) { created ->
        adding = false
        secret = created.first to created.second
        loader.load(silent = true)
    }
    editing?.let { s ->
        StaffSheet(s, data?.roles.orEmpty(), onDismiss = { editing = null }, onChanged = { editing = null; loader.load(silent = true) }) { name, pw ->
            editing = null
            secret = name to pw
        }
    }
    if (newRole) RoleDialog(null, onDismiss = { newRole = false }) { newRole = false; loader.load(silent = true) }
    roleEdit?.let { r -> RoleDialog(r, onDismiss = { roleEdit = null }) { roleEdit = null; loader.load(silent = true) } }
    secret?.let { (name, pw) ->
        SecretDialog("অস্থায়ী পাসওয়ার্ড · $name", pw, "এটি শুধু একবার দেখানো হবে। নিরাপদভাবে সরাসরি স্টাফকে দিন। প্রথম লগইনে পাসওয়ার্ড বদলাতে ও OTP সেটআপ করতে হবে।") { secret = null }
    }
}

private fun lastActive(iso: String?): String {
    val z = Bn.parseInstant(iso) ?: return "এখনো লগইন করেননি"
    val days = java.time.temporal.ChronoUnit.DAYS.between(z.toLocalDate(), Bn.today())
    return when {
        days <= 0L -> "আজ সক্রিয়"
        days == 1L -> "গতকাল সক্রিয়"
        else -> "${Bn.num(days)} দিন আগে"
    }
}

@Composable
private fun RoleTag(s: Staff) {
    val c = Reso.colors
    if (s.roleKey == "super_admin") Tag(s.roleName, Brand.Navy, Brand.Gold, size = 13.sp)
    else Tag(s.roleName, c.tagBlueBg, c.tagBlueText, size = 13.sp)
}

@Composable
private fun StaffRow(s: Staff, onClick: () -> Unit) {
    val c = Reso.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).pressable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(s.name, 44.dp)
        Column(Modifier.weight(1f)) {
            T(s.name, 16.sp, FontWeight.Bold, maxLines = 1)
            T(
                if (s.status != "active") "নিষ্ক্রিয়" else lastActive(s.lastActiveAt) + if (!s.mfaEnrolled) " · OTP সেটআপ বাকি" else "",
                13.sp, color = if (s.status != "active") c.red else c.muted, maxLines = 1,
            )
        }
        RoleTag(s)
    }
}

@Composable
private fun PermissionMatrix(roles: List<Role>, onRole: (Role) -> Unit) {
    val c = Reso.colors
    ResoCard {
        T("রোল অনুযায়ী অনুমতি", 17.sp, FontWeight.Bold)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            val labelW = 112.dp
            // Fit all role columns when possible; scroll horizontally only when there are many roles.
            val colW = if (roles.isEmpty()) 56.dp else maxOf(52.dp, (maxWidth - labelW) / roles.size)
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        T("অনুমতি", 13.sp, color = c.muted, modifier = Modifier.width(labelW))
                        roles.forEach { r ->
                            Box(
                                Modifier.width(colW).heightIn(min = 36.dp).then(if (!r.isSystem) Modifier.pressable(onClick = { onRole(r) }) else Modifier),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                T(r.name.substringBefore(' '), 12.sp, FontWeight.Bold, if (r.isSystem) c.muted else c.ink, align = TextAlign.Center, maxLines = 2)
                            }
                        }
                    }
                    Perm.ALL.forEach { p ->
                        RowDivider(Modifier.padding(vertical = 6.dp).width(labelW + colW * roles.size))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T(Perm.label(p), 14.sp, modifier = Modifier.width(labelW), maxLines = 2)
                            roles.forEach { r ->
                                Box(Modifier.width(colW), contentAlignment = Alignment.Center) {
                                    if (p in r.permissions) Icon(ResoIcons.Check, "আছে", tint = c.green, modifier = Modifier.size(20.dp))
                                    else T("না", 13.sp, color = c.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddStaffDialog(roles: List<Role>, onDismiss: () -> Unit, onCreated: (Pair<String, String>) -> Unit) {
    val c = Reso.colors
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var roleId by remember { mutableStateOf(roles.firstOrNull { it.key == "support" }?.id ?: roles.lastOrNull()?.id) }
    var errors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    val action = rememberAction()
    AlertDialog(
        onDismissRequest = { if (!action.busy) onDismiss() },
        containerColor = c.surface,
        title = { T("নতুন স্টাফ যোগ করুন", 19.sp, FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("নাম", name, { name = it; errors = errors - "name" }, error = errors["name"], maxLength = 100)
                Field("ইমেইল", email, { email = it.trim(); errors = errors - "email" }, keyboard = KeyboardType.Email, error = errors["email"], maxLength = 254)
                Field("মোবাইল", mobile, { mobile = it; errors = errors - "mobile" }, keyboard = KeyboardType.Phone, error = errors["mobile"], placeholder = "০১৭XXXXXXXX", maxLength = 20)
                T("রোল", 14.sp, FontWeight.Bold)
                roles.forEach { r -> RadioCard(roleId == r.id, r.name, null, { roleId = r.id }, subtitle = r.permissions.joinToString(", ") { Perm.label(it) }) }
            }
        },
        confirmButton = {
            TextButton(enabled = !action.busy, onClick = {
                errors = mapOf("name" to V.name(name), "email" to V.email(email), "mobile" to V.mobile(mobile))
                val rid = roleId
                if (errors.values.all { it == null } && rid != null) {
                    action.run(
                        { Graph.adminApi.createStaff(StaffCreate(name.trim(), email.trim().lowercase(), V.normalizeMobile(mobile), rid)) },
                        onErr = { e -> if (e.fields.isNotEmpty()) errors = e.fields.mapValues { it.value } else Toaster.show(e.message) },
                    ) { onCreated(it.staff.name to it.tempPassword) }
                }
            }) { T(if (action.busy) "যোগ হচ্ছে…" else "যোগ করুন", 16.sp, FontWeight.Bold, c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !action.busy) { T("বাতিল", 16.sp, color = c.muted) } },
    )
}

@Composable
private fun StaffSheet(s: Staff, roles: List<Role>, onDismiss: () -> Unit, onChanged: () -> Unit, onSecret: (String, String) -> Unit) {
    val c = Reso.colors
    val me by AdminSession.me.collectAsState()
    val self = me?.id == s.id
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var roleId by remember { mutableStateOf(s.roleId) }
    var confirmStatus by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val action = rememberAction()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(s.name, 52.dp, Brand.Amber, Brand.Navy)
                Column(Modifier.weight(1f)) {
                    T(s.name, 18.sp, FontWeight.Bold)
                    RoleTag(s)
                }
            }
            KeyValue("ইমেইল", s.email, size = 14.sp)
            KeyValue("মোবাইল", Bn.digits(s.mobile), size = 14.sp)
            KeyValue("শেষ সক্রিয়", lastActive(s.lastActiveAt), size = 14.sp)
            KeyValue("OTP (Authenticator)", if (s.mfaEnrolled) "সেটআপ সম্পন্ন" else "সেটআপ বাকি", size = 14.sp)
            if (self) {
                InfoBox("নিজের রোল বা অবস্থা নিজে বদলানো যায় না। অন্য একজন সুপার অ্যাডমিনকে বলুন।", tone = Tone.WARN)
            } else {
                RowDivider()
                T("রোল", 15.sp, FontWeight.Bold)
                roles.forEach { r -> RadioCard(roleId == r.id, r.name, null, { roleId = r.id }) }
                if (roleId != s.roleId) InkButton("রোল সংরক্ষণ করুন", {
                    action.run({ Graph.adminApi.patchStaff(s.id, StaffPatch(roleId = roleId)) }) { Toaster.show("রোল বদলানো হয়েছে"); onChanged() }
                }, loading = action.busy)
                RowDivider()
                SecondaryButton("পাসওয়ার্ড ও OTP রিসেট", { confirmReset = true }, height = 50.dp, icon = ResoIcons.Key)
                if (s.status == "active") DangerButton("স্টাফ নিষ্ক্রিয় করুন", { confirmStatus = true })
                else InkButton("স্টাফ আবার সক্রিয় করুন", { confirmStatus = true })
            }
            Box(Modifier.padding(bottom = 12.dp))
        }
    }
    if (confirmStatus) {
        val activate = s.status != "active"
        ConfirmDialog(
            if (activate) "সক্রিয় করবেন?" else "নিষ্ক্রিয় করবেন?",
            if (activate) "${s.name} আবার লগইন করতে পারবেন।" else "${s.name} এখনই লগআউট হবেন এবং আর লগইন করতে পারবেন না।",
            if (activate) "সক্রিয় করুন" else "নিষ্ক্রিয় করুন",
            {
                confirmStatus = false
                action.run({ Graph.adminApi.patchStaff(s.id, StaffPatch(status = if (activate) "active" else "disabled")) }) {
                    Toaster.show(if (activate) "স্টাফ সক্রিয় হয়েছে" else "স্টাফ নিষ্ক্রিয় হয়েছে"); onChanged()
                }
            },
            { confirmStatus = false }, danger = !activate,
        )
    }
    if (confirmReset) ConfirmDialog(
        "পাসওয়ার্ড ও OTP রিসেট করবেন?",
        "${s.name}-এর সব লগইন বাতিল হবে, একটি অস্থায়ী পাসওয়ার্ড তৈরি হবে এবং পরের লগইনে Authenticator আবার সেটআপ করতে হবে।",
        "রিসেট করুন",
        {
            confirmReset = false
            action.run({ Graph.adminApi.resetStaff(s.id) }) { onSecret(s.name, it.tempPassword) }
        },
        { confirmReset = false }, danger = true,
    )
}

@Composable
private fun RoleDialog(role: Role?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val c = Reso.colors
    var name by remember { mutableStateOf(role?.name ?: "") }
    val perms = remember { mutableStateListOf<String>().apply { addAll(role?.permissions ?: listOf(Perm.VIEW)) } }
    var err by remember { mutableStateOf<String?>(null) }
    val action = rememberAction()
    AlertDialog(
        onDismissRequest = { if (!action.busy) onDismiss() },
        containerColor = c.surface,
        title = { T(if (role == null) "নতুন রোল তৈরি করুন" else "রোল সম্পাদনা", 19.sp, FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("রোলের নাম", name, { name = it; err = null }, error = err, maxLength = 40, placeholder = "যেমন: হিসাব সহকারী")
                T("অনুমতি", 14.sp, FontWeight.Bold)
                Perm.ALL.forEach { p ->
                    val fixed = p == Perm.VIEW
                    CheckRow(p in perms, { on -> if (!fixed) { if (on) perms.add(p) else perms.remove(p) } }, enabled = !fixed) {
                        Column {
                            T(Perm.label(p), 15.sp)
                            if (p == Perm.SETTINGS) T("স্টাফ, রোল ও সেটিংসের পূর্ণ নিয়ন্ত্রণ — সাবধানে দিন", 12.sp, color = c.red)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !action.busy, onClick = {
                err = V.required(name, "রোলের নাম", 2, 40)
                if (err == null) {
                    val list = (perms + Perm.VIEW).distinct()
                    if (role == null) action.run({ Graph.adminApi.createRole(RoleCreate(name.trim(), list)) }) { Toaster.show("রোল তৈরি হয়েছে"); onSaved() }
                    else action.run({ Graph.adminApi.patchRole(role.id, RolePatch(name.trim(), list)) }) { Toaster.show("রোল হালনাগাদ হয়েছে"); onSaved() }
                }
            }) { T("সংরক্ষণ করুন", 16.sp, FontWeight.Bold, c.ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !action.busy) { T("বাতিল", 16.sp, color = c.muted) } },
    )
}
