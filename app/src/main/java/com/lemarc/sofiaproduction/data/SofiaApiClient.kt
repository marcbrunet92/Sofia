package com.lemarc.sofiaproduction.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────
// Retrofit interface for the Sofia server
// ─────────────────────────────────────────────

interface SofiaApiService {

    @GET("generation")
    suspend fun getGeneration(
        @Query("from_date") fromDate: String,
        @Query("to_date")   toDate: String,
        @Query("bmu_ids")   bmuIds: List<String>
    ): Response<List<SofiaGenerationPoint>>

    @GET("records")
    suspend fun getRecords(
        @Query("bmu_ids") bmuIds: List<String>
    ): Response<RecordsData>

    @GET("latest-date")
    suspend fun getLatestDate(): Response<DataRangeInfo>
}

// ─────────────────────────────────────────────
// Singleton factory
// ─────────────────────────────────────────────

object SofiaApiClient {
    const val BASE_URL = "https://sofia.lemarc.fr/"

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    val service: SofiaApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SofiaApiService::class.java)
}
