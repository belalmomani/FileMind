package com.filemind.smartmanager.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("filemind_settings")

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class ViewMode { LIST, GRID, DETAILS }

class SettingsRepository(private val ctx: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val rootsKey = stringSetPreferencesKey("saf_roots")
    private val favKey = stringSetPreferencesKey("favorites")
    private val browserViewKey = stringPreferencesKey("browser_view")
    private val browserHiddenKey = booleanPreferencesKey("browser_hidden")
    private val backupKey = booleanPreferencesKey("backup_on_replace")
    private val indexTimeKey = longPreferencesKey("index_time")
    private val reviewKey = intPreferencesKey("review_months")
    private val retentionKey = intPreferencesKey("log_retention_days")
    private val confirmDeleteKey = booleanPreferencesKey("confirm_delete")
    private val crashKey = booleanPreferencesKey("crash_reports")

    val themeMode = ctx.store.data.map { p -> p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
    val roots = ctx.store.data.map { it[rootsKey] ?: emptySet() }
    val favorites = ctx.store.data.map { it[favKey] ?: emptySet() }
    val browserView = ctx.store.data.map { p -> p[browserViewKey]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.LIST }
    val browserShowHidden = ctx.store.data.map { it[browserHiddenKey] ?: false }
    val backupOnReplace = ctx.store.data.map { it[backupKey] ?: false }
    val indexTime = ctx.store.data.map { p -> p[indexTimeKey]?.takeIf { it > 0 } }
    val reviewMonths = ctx.store.data.map { it[reviewKey] ?: 6 }
    val logRetentionDays = ctx.store.data.map { it[retentionKey] ?: 0 }   // 0 = keep forever
    val confirmDelete = ctx.store.data.map { it[confirmDeleteKey] ?: true }
    val crashReports = ctx.store.data.map { it[crashKey] ?: false }   // opt-in: default OFF

    suspend fun setTheme(m: ThemeMode) { ctx.store.edit { it[themeKey] = m.name } }
    suspend fun addRoot(uri: String) { ctx.store.edit { it[rootsKey] = (it[rootsKey] ?: emptySet()) + uri } }
    suspend fun removeRoot(uri: String) { ctx.store.edit { it[rootsKey] = (it[rootsKey] ?: emptySet()) - uri } }
    suspend fun toggleFavorite(key: String) { ctx.store.edit { val s = it[favKey] ?: emptySet(); it[favKey] = if (key in s) s - key else s + key } }
    suspend fun setBrowserView(m: ViewMode) { ctx.store.edit { it[browserViewKey] = m.name } }
    suspend fun setBrowserShowHidden(v: Boolean) { ctx.store.edit { it[browserHiddenKey] = v } }
    suspend fun setBackupOnReplace(v: Boolean) { ctx.store.edit { it[backupKey] = v } }
    suspend fun setIndexTime(t: Long) { ctx.store.edit { it[indexTimeKey] = t } }
    suspend fun setReviewMonths(m: Int) { ctx.store.edit { it[reviewKey] = m } }
    suspend fun setLogRetention(days: Int) { ctx.store.edit { it[retentionKey] = days } }
    suspend fun setConfirmDelete(v: Boolean) { ctx.store.edit { it[confirmDeleteKey] = v } }
    suspend fun setCrashReports(v: Boolean) { ctx.store.edit { it[crashKey] = v } }
    // TODO: per-section view mode, review period (6 months default), log retention days.
}
