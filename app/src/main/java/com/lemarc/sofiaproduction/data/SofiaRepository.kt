package com.lemarc.sofiaproduction.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────
// Retrofit interface
// ─────────────────────────────────────────────

interface EnergyApiService {

    @GET("generation-history")
    suspend fun getGenerationHistory(
        @Query("date")    date: String,
        @Query("bmrsids") bmuIds: List<String>
    ): Response<JsonObject>

    @GET("notifications")
    suspend fun getNotifications(
        @Query("date")    date: String,
        @Query("bmrsids") bmuIds: List<String>
    ): Response<JsonObject>
}

// ─────────────────────────────────────────────
// Singleton factory
// ─────────────────────────────────────────────

object ApiClient {
    private const val BASE_URL = "https://energy-api.robinhawkes.com/"

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    val service: EnergyApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(EnergyApiService::class.java)
}

// ─────────────────────────────────────────────
// Repository
// ─────────────────────────────────────────────

class SofiaRepository(
    private val api: EnergyApiService = ApiClient.service,
    private val gson: Gson = Gson()
) {
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    private fun nowIso() = iso.format(Instant.now())

    // ── Generation ──────────────────────────────

    /**
     * Fetches [days] days of generation data (48 half-hour periods per day).
     * [dateIso] = ISO 8601 UTC timestamp used as the "end" reference.
     * Partial results are returned if some days fail. Throws only when all days fail.
     */
    suspend fun fetchGeneration(dateIso: String = nowIso(), days: Int = 2): Result<List<GenerationPoint>> {
        return runCatching {
            val endInstant = Instant.parse(dateIso.replace(' ', 'T'))
            val bmuIds = AppSettings.getBmuIds()
            val allPoints = mutableMapOf<String, Pair<Double, String>>()
            var successCount = 0

            for (dayOffset in 0 until days) {
                val dayInstant = endInstant.minusSeconds(dayOffset * 24L * 3600)
                val dayIso = iso.format(dayInstant)

                try {
                    val resp = api.getGenerationHistory(dayIso, bmuIds)
                    val body = resp.body() ?: continue

                    for (bmuId in bmuIds) {
                        val bmuObj = body.getAsJsonObject(bmuId) ?: continue
                        val genArray = bmuObj.getAsJsonArray("generation") ?: continue
                        val type = object : TypeToken<List<GenerationSlotRaw>>() {}.type
                        val slots: List<GenerationSlotRaw> = gson.fromJson(genArray, type)

                        for (slot in slots) {
                            val t = slot.timeFrom
                            val current = allPoints[t]
                            allPoints[t] = Pair(
                                (current?.first ?: 0.0) + (slot.levelTo ?: 0.0),
                                slot.source ?: current?.second ?: "pn"
                            )
                        }
                    }
                    successCount++
                } catch (ignored: Exception) {
                    // Skip this day but continue with others
                }
            }

            if (successCount == 0) error("Impossible de récupérer les données de génération")

            allPoints.entries
                .map { (t, pair) -> GenerationPoint(t, pair.first, pair.second) }
                .sortedBy { it.timeFrom }
        }
    }

    // ── Notifications ────────────────────────────

    suspend fun fetchNotifications(dateIso: String = nowIso()): Result<List<ActiveNotice>> {
        return runCatching {
            val bmuIds = AppSettings.getBmuIds()
            val resp = api.getNotifications(dateIso, bmuIds)
            val body = resp.body() ?: error("Empty response (${resp.code()})")

            val notices = mutableListOf<ActiveNotice>()
            val noticeType = object : TypeToken<List<NotificationRaw>>() {}.type

            for (bmuId in bmuIds) {
                val bmuObj = body.getAsJsonObject(bmuId) ?: continue
                val arr = bmuObj.getAsJsonArray("notifications") ?: continue
                val raw: List<NotificationRaw> = gson.fromJson(arr, noticeType)
                raw.forEach { n ->
                    notices += ActiveNotice(
                        bmuId = bmuId,
                        documentId = n.documentID,
                        timeFrom = n.timeFrom,
                        timeTo = n.timeTo,
                        reasonCode = n.reasonCode,
                        reasonDescription = n.reasonDescription,
                        unavailabilityType = n.unavailabilityType,
                        levelMW = n.levels.firstOrNull() ?: 0.0
                    )
                }
            }
            notices
        }
    }

    // ── Snapshot cache ───────────────────────────

    fun cacheSnapshot(snapshot: FarmSnapshot) {
        AppSettings.saveSnapshotJson(gson.toJson(snapshot))
    }

    fun getCachedSnapshot(): FarmSnapshot? = runCatching {
        val json = AppSettings.loadSnapshotJson() ?: return null
        gson.fromJson(json, FarmSnapshot::class.java)
    }.getOrNull()

    // ── Combined snapshot ────────────────────────

    suspend fun fetchSnapshot(days: Int = 2): Result<FarmSnapshot> {
        return runCatching {
            val genResult = fetchGeneration(days = days).getOrThrow()
            val noticesResult = fetchNotifications().getOrDefault(emptyList())

            val latest = genResult.lastOrNull()

            FarmSnapshot(
                latestMW = latest?.totalMW ?: 0.0,
                capacityFactor = latest?.capacityFactor ?: 0.0,
                source = latest?.source ?: "pn",
                lastUpdated = latest?.timeFrom ?: "",
                history = genResult,
                activeNotices = noticesResult
            )
        }
    }
}
