package net.rapti.resoshare.role

import androidx.compose.runtime.Composable
import net.rapti.resoshare.core.ui.T

object RoleSpec {
    const val blockRootedDevices = true
    const val defaultIdleTimeoutMs = 5 * 60_000L
}

@Composable
fun RoleRoot() { T("stub") }
