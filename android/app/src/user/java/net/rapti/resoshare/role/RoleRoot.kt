package net.rapti.resoshare.role

import androidx.compose.runtime.Composable
import net.rapti.resoshare.user.UserApp

object RoleSpec {
    const val blockRootedDevices = false
    const val defaultIdleTimeoutMs = 15 * 60_000L
}

@Composable
fun RoleRoot() = UserApp()
