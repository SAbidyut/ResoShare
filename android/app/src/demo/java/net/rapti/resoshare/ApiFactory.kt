package net.rapti.resoshare

import android.content.Context
import net.rapti.resoshare.core.session.TokenStore
import net.rapti.resoshare.data.api.AdminApi
import net.rapti.resoshare.data.api.ShareholderApi
import net.rapti.resoshare.demo.DemoAdminApi
import net.rapti.resoshare.demo.DemoShareholderApi

/** Demo wiring: no network at all, everything is served from [net.rapti.resoshare.demo.DemoStore]. */
object ApiFactory {
    const val IS_DEMO = true
    fun init(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") tokenStore: TokenStore) = Unit
    fun shareholder(): ShareholderApi = DemoShareholderApi()
    fun admin(): AdminApi = DemoAdminApi()
}
