package net.rapti.resoshare.core.i18n

import androidx.compose.runtime.mutableStateOf

enum class AppLang(val code: String) { BN("bn"), EN("en") }

/**
 * App language. Backed by Compose snapshot state, so every composable that calls [t]
 * (or a [net.rapti.resoshare.core.format.Bn] formatter) recomposes when the language changes.
 */
object Lang {
    val state = mutableStateOf(AppLang.BN)
    val isEn: Boolean get() = state.value == AppLang.EN
    val current: AppLang get() = state.value

    /** Hook for persisting the choice; set by Graph at startup. */
    var onChange: ((AppLang) -> Unit)? = null

    fun set(lang: AppLang) {
        if (state.value == lang) return
        state.value = lang
        onChange?.invoke(lang)
    }
}

/** Inline translation: `t("লগইন করুন", "Log in")`. */
fun t(bn: String, en: String): String = if (Lang.isEn) en else bn
