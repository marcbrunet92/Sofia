package com.lemarc.sofiaproduction

import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────
// API response models
// ─────────────────────────────────────────────

data class GenerationSlotRaw(
    @SerializedName("timeFrom")     val timeFrom: String,
    @SerializedName("timeTo")       val timeTo: String,
    @SerializedName("levelTo")      val levelTo: Double?,
    @SerializedName("capacityMW")   val capacityMW: Double?,
    @SerializedName("curtailmentMW") val curtailmentMW: Double?,
    @SerializedName("source")       val source: String?
)

data class NotificationRaw(
    @SerializedName("documentID")           val documentID: String,
    @SerializedName("revisionNumber")       val revisionNumber: Int,
    @SerializedName("timeFrom")             val timeFrom: String,
    @SerializedName("timeTo")               val timeTo: String,
    @SerializedName("levels")              val levels: List<Double>,
    @SerializedName("reasonCode")           val reasonCode: String,
    @SerializedName("reasonDescription")    val reasonDescription: String,
    @SerializedName("messageHeading")       val messageHeading: String?,
    @SerializedName("eventType")            val eventType: String?,
    @SerializedName("unavailabilityType")   val unavailabilityType: String
)

// ─────────────────────────────────────────────
// Domain models (used by UI)
// ─────────────────────────────────────────────

/** Aggregated generation across all 4 BMUs for a single 30-min period. */
data class GenerationPoint(
    val timeFrom: String,   // ISO string, UTC
    val totalMW: Double,    // sum of all 4 BMUs
    val source: String      // "b1610" or "pn"
) {
    val capacityFactor: Double get() = totalMW / INSTALLED_MW
}

data class ActiveNotice(
    val bmuId: String,
    val documentId: String,
    val timeFrom: String,
    val timeTo: String,
    val reasonCode: String,
    val reasonDescription: String,
    val unavailabilityType: String,   // "Planned" | "Unplanned"
    val levelMW: Double
)

/** Snapshot of the latest farm state. */
data class FarmSnapshot(
    val latestMW: Double,
    val capacityFactor: Double,
    val source: String,               // "b1610" | "pn"
    val lastUpdated: String,          // ISO time string
    val history: List<GenerationPoint>,
    val activeNotices: List<ActiveNotice>
) {
    val hasActiveOutage: Boolean get() = activeNotices.isNotEmpty()
    val statusLabel: String get() = when {
        hasActiveOutage && activeNotices.any { it.unavailabilityType == "Unplanned" } -> "Unplanned outage"
        hasActiveOutage -> "Planned maintenance"
        latestMW > 0   -> "Generating"
        else           -> "Standby"
    }
}

const val INSTALLED_MW = 1_400.0
val BMU_IDS = listOf("SOFWO-11", "SOFWO-12", "SOFWO-21", "SOFWO-22")