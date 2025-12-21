package com.homelibrary.client

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app settings using SharedPreferences.
 * 
 * Stores user preferences including the selected server host for gRPC communication.
 */
object AppSettings {
    private const val PREFS_NAME = "bookworm_settings"
    private const val KEY_SERVER_HOST = "server_host"
    private const val DEFAULT_HOST = "10.0.2.2"
    
    val SERVER_OPTIONS = listOf(
        "10.0.2.2" to "Emulator (10.0.2.2)",
        "192.168.1.100" to "LAN 192.168.1.100",
        "192.168.0.198" to "LAN 192.168.0.198",
        "192.168.0.106" to "LAN 192.168.0.106"
    )
    
    private const val KEY_OCR_LANGUAGE = "ocr_language"
    private const val DEFAULT_LANGUAGE = "rus"

    val LANGUAGE_OPTIONS = listOf(
        "rus" to "Russian (rus)",
        "eng" to "English (eng)",
        "rus+eng" to "Russian + English"
    )

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getServerHost(context: Context): String {
        return prefs(context).getString(KEY_SERVER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
    }
    
    fun setServerHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_SERVER_HOST, host).apply()
    }

    fun getOcrLanguage(context: Context): String {
        return prefs(context).getString(KEY_OCR_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun setOcrLanguage(context: Context, language: String) {
        prefs(context).edit().putString(KEY_OCR_LANGUAGE, language).apply()
    }
    
    fun getServerPort(): Int = 9090
}
