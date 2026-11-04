package net.rapti.resoshare.role

import androidx.compose.runtime.Composable
import net.rapti.resoshare.admin.AdminRoot

object RoleSpec {
    const val blockRootedDevices = true
    const val defaultIdleTimeoutMs = 5 * 60_000L
}

@Composable
fun RoleRoot() = AdminRoot()
