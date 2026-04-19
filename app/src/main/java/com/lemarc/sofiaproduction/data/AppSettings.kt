package com.lemarc.sofiaproduction.data

import android.content.Context
import android.content.SharedPreferences

object AppSettings {

    val DEFAULT_BMU_IDS = listOf("SOFWO-11", "SOFWO-12", "SOFWO-21", "SOFWO-22")

    private const val PREFS_NAME = "sofia_settings"
    private const val KEY_BMU_IDS = "bmu_ids"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getBmuIds(): List<String> {
        val stored = prefs?.getString(KEY_BMU_IDS, null)
        if (stored.isNullOrBlank()) {
            return DEFAULT_BMU_IDS
        }

        val parsedIds = stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parsedIds.isEmpty()) {
            prefs?.edit()?.remove(KEY_BMU_IDS)?.apply()
            return DEFAULT_BMU_IDS
        }

        return parsedIds
    }

    fun setBmuIds(ids: List<String>) {
        prefs?.edit()?.putString(KEY_BMU_IDS, ids.joinToString(","))?.apply()
    }

    fun resetBmuIds() {
        prefs?.edit()?.remove(KEY_BMU_IDS)?.apply()
    }

    fun isTestMode(): Boolean = getBmuIds().toSet() != DEFAULT_BMU_IDS.toSet()
}
