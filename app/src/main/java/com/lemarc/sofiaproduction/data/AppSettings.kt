package com.lemarc.sofiaproduction.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object AppSettings {

    val DEFAULT_BMU_IDS = listOf("SOFWO-11", "SOFWO-12", "SOFWO-21", "SOFWO-22")

    private const val PREFS_NAME = "sofia_settings"
    private const val KEY_BMU_IDS = "bmu_ids"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: error("AppSettings not initialized — call AppSettings.init(context) first")

    fun getBmuIds(): List<String> {
        val stored = requirePrefs().getString(KEY_BMU_IDS, null)
        if (stored.isNullOrBlank()) {
            return DEFAULT_BMU_IDS
        }

        val parsedIds = stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parsedIds.isEmpty()) {
            requirePrefs().edit { remove(KEY_BMU_IDS) }
            return DEFAULT_BMU_IDS
        }

        return parsedIds
    }

    fun setBmuIds(ids: List<String>) {
        requirePrefs().edit { putString(KEY_BMU_IDS, ids.joinToString(",")) }
    }

    fun resetBmuIds() {
        requirePrefs().edit { remove(KEY_BMU_IDS) }
    }

    fun isTestMode(): Boolean = getBmuIds().toSet() != DEFAULT_BMU_IDS.toSet()

    // ── API mode ─────────────────────────────────

    const val API_MODE_LEGACY = "legacy"
    const val API_MODE_SOFIA  = "sofia"

    private const val KEY_API_MODE = "api_mode"

    fun getApiMode(): String =
        requirePrefs().getString(KEY_API_MODE, API_MODE_LEGACY) ?: API_MODE_LEGACY

    fun setApiMode(mode: String) {
        requirePrefs().edit { putString(KEY_API_MODE, mode) }
    }

    // ── Snapshot cache ───────────────────────────

    private const val KEY_SNAPSHOT_CACHE = "snapshot_cache"

    fun saveSnapshotJson(json: String) {
        requirePrefs().edit { putString(KEY_SNAPSHOT_CACHE, json) }
    }

    fun loadSnapshotJson(): String? {
        val s = requirePrefs().getString(KEY_SNAPSHOT_CACHE, null)
        return if (s.isNullOrBlank()) null else s
    }

    fun clearSnapshotCache() {
        requirePrefs().edit { remove(KEY_SNAPSHOT_CACHE) }
    }
}
