package net.rapti.resoshare

import android.app.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import net.rapti.resoshare.core.i18n.Lang
import net.rapti.resoshare.core.session.Prefs
import net.rapti.resoshare.core.session.SecureStore
import net.rapti.resoshare.core.session.Session
import net.rapti.resoshare.core.session.TokenStore
import net.rapti.resoshare.data.api.AdminApi
import net.rapti.resoshare.data.api.ShareholderApi

/** Minimal service locator (no DI framework needed for an app this size). */
object Graph {
    lateinit var app: Application
        private set

    val secure: SecureStore by lazy { SecureStore(app) }
    val prefs: Prefs by lazy { Prefs(app) }
    val tokens: TokenStore by lazy { TokenStore(secure) }
    val themeMode by lazy { MutableStateFlow(prefs.themeMode) }

    /** Which half of the app is shown: "user" (shareholder) or "admin". */
    val portal by lazy { MutableStateFlow(tokens.role ?: prefs.portal) }

    fun switchPortal(p: String) {
        if (Session.isActive) return // only from the login screens
        prefs.portal = p
        tokens.currentPortal = p
        portal.value = p
    }

    val shareholderApi: ShareholderApi by lazy { ApiFactory.shareholder() }
    val adminApi: AdminApi by lazy { ApiFactory.admin() }

    val isDemo: Boolean get() = ApiFactory.IS_DEMO

    fun init(application: Application) {
        app = application
        Session.tokens = tokens
        Lang.state.value = prefs.lang
        Lang.onChange = { prefs.lang = it }
        tokens.currentPortal = portal.value
        ApiFactory.init(application, tokens)
    }
}

/** App-wide transient messages (snackbar). */
object Toaster {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun show(message: String) { _messages.tryEmit(message) }
}
