package com.example.data.firebase

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "selamcall_prefs")

enum class AppThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromKey(raw: String?): AppThemeMode =
            entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}

class ThemePreferencesRepository(context: Context) {
    private val appContext = context.applicationContext
    private val themeKey = stringPreferencesKey("theme_mode")

    val themeMode: Flow<AppThemeMode> = appContext.themeDataStore.data.map { prefs ->
        AppThemeMode.fromKey(prefs[themeKey])
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        appContext.themeDataStore.edit { prefs ->
            prefs[themeKey] = mode.key
        }
    }
}
