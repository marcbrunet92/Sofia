package com.lemarc.sofiaproduction

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

    /** Returns a JsonObject keyed by BMU ID — we parse manually. */
    @GET("generation-history")
    suspend fun getGenerationHistory(
        @Query("date")     date: String,
        @Query("bmrsids")  bmu1: String,
        @Query("bmrsids")  bmu2: String,
        @Query("bmrsids")  bmu3: String,
        @Query("bmrsids")  bmu4: String
    ): Response<JsonObject>

    @GET("notifications")
    suspend fun getNotifications(
        @Query("date")     date: String,
        @Query("bmrsids")  bmu1: String,
        @Query("bmrsids")  bmu2: String,
        @Query("bmrsids")  bmu3: String,
        @Query("bmrsids")  bmu4: String
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
     * Fetches one day of generation data (48 half-hour periods).
     * [dateIso] = ISO 8601 UTC timestamp used as the "end" reference.
     */
    suspend fun fetchGeneration(dateIso: String = nowIso()): Result<List<GenerationPoint>> {
        return runCatching {
            val resp = api.getGenerationHistory(
                dateIso,
                BMU_IDS[0], BMU_IDS[1], BMU_IDS[2], BMU_IDS[3]
            )
            val body = resp.body() ?: error("Empty response (${resp.code()})")

            // Aggregate per time-slot across all 4 BMUs
            val totals = mutableMapOf<String, Double>()
            val sources = mutableMapOf<String, String>()

            for (bmuId in BMU_IDS) {
                val bmuObj = body.getAsJsonObject(bmuId) ?: continue
                val genArray = bmuObj.getAsJsonArray("generation") ?: continue
                val type = object : TypeToken<List<GenerationSlotRaw>>() {}.type
                val slots: List<GenerationSlotRaw> = gson.fromJson(genArray, type)

                for (slot in slots) {
                    val t = slot.timeFrom
                    totals[t] = (totals[t] ?: 0.0) + (slot.levelTo ?: 0.0)
                    // last-write wins — all BMUs share the same source flag per period
                    sources[t] = slot.source ?: "pn"
                }
            }

            totals.entries
                .map { (t, mw) -> GenerationPoint(t, mw, sources[t] ?: "pn") }
                .sortedBy { it.timeFrom }
        }
    }

    // ── Notifications ────────────────────────────

    suspend fun fetchNotifications(dateIso: String = nowIso()): Result<List<ActiveNotice>> {
        return runCatching {
            val resp = api.getNotifications(
                dateIso,
                BMU_IDS[0], BMU_IDS[1], BMU_IDS[2], BMU_IDS[3]
            )
            val body = resp.body() ?: error("Empty response (${resp.code()})")

            val notices = mutableListOf<ActiveNotice>()
            val noticeType = object : TypeToken<List<NotificationRaw>>() {}.type

            for (bmuId in BMU_IDS) {
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

    // ── Combined snapshot ────────────────────────

    suspend fun fetchSnapshot(): Result<FarmSnapshot> {
        return runCatching {
            val genResult = fetchGeneration().getOrThrow()
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