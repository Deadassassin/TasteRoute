package com.example.tasteroute.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Small synchronous key-value store. AppState used to lose onboarding, favorites and the taste
 * profile on process death; with accounts in the mix, losing the refresh token would sign people
 * out every time Android reclaimed the process, so this is no longer optional.
 *
 * Writes are apply() — none of this is worth blocking a frame for.
 */
object Prefs {

    private const val FILE = "tasteroute"
    private lateinit var store: SharedPreferences

    fun init(context: Context) {
        store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val ready: Boolean get() = ::store.isInitialized

    fun getString(key: String, default: String = ""): String =
        if (ready) store.getString(key, default) ?: default else default

    fun getBoolean(key: String, default: Boolean): Boolean =
        if (ready) store.getBoolean(key, default) else default

    fun getInt(key: String, default: Int): Int = if (ready) store.getInt(key, default) else default

    fun getStringSet(key: String): Set<String> =
        if (ready) store.getStringSet(key, emptySet()) ?: emptySet() else emptySet()

    fun put(key: String, value: String) {
        if (ready) store.edit().putString(key, value).apply()
    }

    fun put(key: String, value: Boolean) {
        if (ready) store.edit().putBoolean(key, value).apply()
    }

    fun put(key: String, value: Int) {
        if (ready) store.edit().putInt(key, value).apply()
    }

    fun put(key: String, value: Set<String>) {
        if (ready) store.edit().putStringSet(key, value).apply()
    }

    fun remove(vararg keys: String) {
        if (!ready) return
        store.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    // Keys
    const val THEME = "theme_mode"
    const val FONT = "font_choice"
    const val FONT_SCALE = "font_scale"
    const val WELCOMED = "welcomed"
    const val ONBOARDED = "onboarded"
    const val TASTE_TEXT = "taste_text"
    const val PROFILE = "profile_json"
    const val ALLERGENS = "allergens"
    const val FAVORITES = "favorites"
    const val TIER = "tier"
    const val REFRESH = "refresh_token"
    const val ACCOUNT = "account_json"
    const val PRECISE = "precise_location"
    const val HISTORY = "save_history"
    const val NAV_VOICE = "nav_voice"
    const val VOICE_NAME = "nav_voice_name"
    const val VOICE_SPEED = "nav_voice_speed"
    const val UNITS = "nav_units"
    const val NIM_MODEL_CHOICE = "nim_model_choice"
    const val WARM_RESULTS = "warm_results"
    const val WARM_ORIGIN = "warm_origin"
    const val WARM_AT = "warm_at"
    const val CHAT_LOG = "chat_log"
}
