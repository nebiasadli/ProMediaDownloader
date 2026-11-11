package com.nebi.promediadownloader

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Uygulama dili. Varsayılan İngilizce'dir; sistem dili ne olursa olsun kullanıcı
 * Türkçe seçmedikçe İngilizce gösterilir. Seçim ayarlarda saklanır.
 */
object LocaleHelper {
    const val PREFS = "settings"
    private const val KEY_LANG = "lang"
    const val EN = "en"
    const val TR = "tr"

    fun savedTag(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, EN) ?: EN

    fun setTag(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, tag).apply()
    }

    /** Seçili dilde kaynakları okuyan bir bağlam döndürür. Activity ve servisler kullanır. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(savedTag(base))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
