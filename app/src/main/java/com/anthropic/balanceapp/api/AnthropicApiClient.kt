package com.anthropic.balanceapp.api

import com.anthropic.balanceapp.api.models.AggregatedUsage
import com.anthropic.balanceapp.api.models.UsageResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

interface AnthropicApiService {
    // Validate key: lightweight call that works with any valid API key
    @GET("v1/models")
    suspend fun getModels(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ResponseBody>

    // Usage: requires an API key with usage read permissions (org/admin key)
    @GET("v1/usage")
    suspend fun getUsage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("limit") limit: Int = 100
    ): Response<UsageResponse>
}

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>()
    data object NetworkError : ApiResult<Nothing>()
}

class AnthropicApiClient {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val service: AnthropicApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AnthropicApiService::class.java)
    }

    suspend fun fetchMonthlyUsage(apiKey: String): ApiResult<AggregatedUsage> {
        return try {
            val now = LocalDate.now()
            val startDate = now.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val endDate = now.format(DateTimeFormatter.ISO_LOCAL_DATE)

            val response = service.getUsage(
                apiKey = apiKey,
                startDate = startDate,
                endDate = endDate
            )

            when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body != null) {
                        ApiResult.Success(
                            AggregatedUsage.fromEntries(
                                entries = body.data,
                                periodStart = startDate,
                                periodEnd = endDate
                            )
                        )
                    } else {
                        ApiResult.Error("Empty response body")
                    }
                }
                response.code() == 401 -> ApiResult.Error("Invalid API key.", 401)
                response.code() == 403 -> ApiResult.Error("Key lacks usage permissions. Use an Admin API key.", 403)
                response.code() == 404 -> ApiResult.Error("Usage API unavailable. Use an Admin API key from console.anthropic.com.", 404)
                response.code() == 429 -> ApiResult.Error("Rate limited. Will retry later.", 429)
                else -> ApiResult.Error("Server error: ${response.code()}", response.code())
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error("Request timed out.")
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }

    suspend fun validateApiKey(apiKey: String): ApiResult<Boolean> {
        return try {
            val response = service.getModels(apiKey = apiKey)
            when {
                response.isSuccessful -> ApiResult.Success(true)
                response.code() == 401 -> ApiResult.Error("Invalid API key", 401)
                response.code() == 403 -> ApiResult.Error("API key is valid but has limited permissions", 403)
                else -> ApiResult.Error("Validation failed: ${response.code()}", response.code())
            }
        } catch (e: Exception) {
            ApiResult.NetworkError
        }
    }
}
