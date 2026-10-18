package com.filemind.smartmanager.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("filemind_settings")

enum class ThemeMode { LIGHT, DARK, SYSTEM }

class SettingsRepository(private val ctx: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val rootsKey = stringSetPreferencesKey("saf_roots")
    private val reviewKey = intPreferencesKey("review_months")
    private val retentionKey = intPreferencesKey("log_retention_days")
    private val confirmDeleteKey = booleanPreferencesKey("confirm_delete")

    val themeMode = ctx.store.data.map { p -> p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
    val roots = ctx.store.data.map { it[rootsKey] ?: emptySet() }
    val reviewMonths = ctx.store.data.map { it[reviewKey] ?: 6 }
    val logRetentionDays = ctx.store.data.map { it[retentionKey] ?: 0 }   // 0 = keep forever
    val confirmDelete = ctx.store.data.map { it[confirmDeleteKey] ?: true }

    suspend fun setTheme(m: ThemeMode) { ctx.store.edit { it[themeKey] = m.name } }
    suspend fun addRoot(uri: String) { ctx.store.edit { it[rootsKey] = (it[rootsKey] ?: emptySet()) + uri } }
    suspend fun removeRoot(uri: String) { ctx.store.edit { it[rootsKey] = (it[rootsKey] ?: emptySet()) - uri } }
    suspend fun setReviewMonths(m: Int) { ctx.store.edit { it[reviewKey] = m } }
    suspend fun setLogRetention(days: Int) { ctx.store.edit { it[retentionKey] = days } }
    suspend fun setConfirmDelete(v: Boolean) { ctx.store.edit { it[confirmDeleteKey] = v } }
    // TODO: per-section view mode, review period (6 months default), log retention days.
}
