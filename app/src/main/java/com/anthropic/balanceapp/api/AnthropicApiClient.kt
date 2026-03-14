package com.anthropic.balanceapp.api

import com.anthropic.balanceapp.api.models.ApiBalance
import com.anthropic.balanceapp.api.models.BillingBalanceResponse
import com.anthropic.balanceapp.api.models.BootstrapResponse
import com.anthropic.balanceapp.api.models.ClaudeUsageData
import com.anthropic.balanceapp.api.models.MembershipLimitsResponse
import com.anthropic.balanceapp.api.models.OrgLimitsResponse
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
import retrofit2.http.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ─── Sealed result ────────────────────────────────────────────────────────────

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>()
    data object NetworkError : ApiResult<Nothing>()
}

// ─── Retrofit interfaces ──────────────────────────────────────────────────────

interface ClaudeAiService {
    @GET("api/account_membership_limits")
    suspend fun getMembershipLimits(
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)"
    ): Response<ResponseBody>

    @GET("api/organizations/{orgId}/limits")
    suspend fun getOrgLimits(
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)",
        @Path("orgId") orgId: String
    ): Response<ResponseBody>

    @GET("api/bootstrap")
    suspend fun getBootstrap(
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)"
    ): Response<ResponseBody>
}

interface AnthropicApiService {
    @GET("v1/models")
    suspend fun getModels(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ResponseBody>

    @GET("v1/billing/balance")
    suspend fun getBillingBalance(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ResponseBody>

    @GET("v1/organizations/billing/balance")
    suspend fun getOrgBillingBalance(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ResponseBody>
}

// ─── Shared helpers ───────────────────────────────────────────────────────────

private fun buildMoshi(): Moshi = Moshi.Builder()
    .addLast(KotlinJsonAdapterFactory())
    .build()

private fun buildOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * Parse an ISO-8601 timestamp string into epoch milliseconds.
 * Returns 0 on failure.
 */
fun parseIso8601ToMs(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        try {
            // Try with offset variations e.g. "2026-03-14T12:00:00+00:00"
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            formatter.parse(iso, Instant::from).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

// ─── ClaudeAiApiClient ────────────────────────────────────────────────────────

/**
 * Fetches Claude.ai platform usage (session limits) using a browser session token cookie.
 */
class ClaudeAiApiClient {

    private val moshi = buildMoshi()

    private val service: ClaudeAiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://claude.ai/")
            .client(buildOkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ClaudeAiService::class.java)
    }

    suspend fun fetchUsage(sessionToken: String): ApiResult<ClaudeUsageData> {
        val cookie = formatCookie(sessionToken)
        return try {
            // Primary endpoint
            val primaryResult = tryMembershipLimits(cookie)
            if (primaryResult != null) return ApiResult.Success(primaryResult)

            // Fallback: bootstrap (extract org id, then try org limits)
            val bootstrapResult = tryBootstrapThenOrgLimits(cookie)
            if (bootstrapResult != null) return ApiResult.Success(bootstrapResult)

            ApiResult.Error("Could not retrieve usage data from any Claude.ai endpoint")
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error("Request timed out")
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }

    private suspend fun tryMembershipLimits(cookie: String): ClaudeUsageData? {
        return try {
            val response = service.getMembershipLimits(cookie)
            when {
                response.isSuccessful -> {
                    val body = response.body()?.string() ?: return null
                    if (looksLikeHtml(body)) return null
                    parseMembershipLimits(body)
                }
                response.code() == 401 || response.code() == 403 -> {
                    throw SessionExpiredException("Session token expired or invalid")
                }
                else -> null
            }
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryBootstrapThenOrgLimits(cookie: String): ClaudeUsageData? {
        return try {
            val bootstrapResponse = service.getBootstrap(cookie)
            if (!bootstrapResponse.isSuccessful) return null
            val bootstrapBody = bootstrapResponse.body()?.string() ?: return null
            if (looksLikeHtml(bootstrapBody)) return null

            val bootstrapAdapter = moshi.adapter(BootstrapResponse::class.java)
            val bootstrap = try { bootstrapAdapter.fromJson(bootstrapBody) } catch (_: Exception) { null }

            // Try embedded membership_limits from bootstrap account object
            val embeddedLimits = bootstrap?.account?.membershipLimits
            if (embeddedLimits != null) {
                val parsed = buildClaudeUsageData(embeddedLimits)
                if (parsed.sessionPercent > 0 || parsed.weeklyPercent > 0) return parsed
            }

            // Try org-specific limits endpoint
            val orgId = bootstrap?.organization?.id ?: bootstrap?.organization?.uuid ?: return null
            val orgResponse = service.getOrgLimits(cookie, orgId = orgId)
            if (!orgResponse.isSuccessful) return null
            val orgBody = orgResponse.body()?.string() ?: return null
            if (looksLikeHtml(orgBody)) return null

            parseOrgLimits(orgBody)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMembershipLimits(json: String): ClaudeUsageData? {
        return try {
            val adapter = moshi.adapter(MembershipLimitsResponse::class.java)
            val dto = adapter.fromJson(json) ?: return null
            buildClaudeUsageData(dto)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOrgLimits(json: String): ClaudeUsageData? {
        return try {
            val adapter = moshi.adapter(OrgLimitsResponse::class.java)
            val dto = adapter.fromJson(json) ?: return null
            // OrgLimitsResponse may wrap a MembershipLimitsResponse
            val limits = dto.limits ?: MembershipLimitsResponse(
                session = dto.session,
                weekly = dto.weekly
            )
            buildClaudeUsageData(limits)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildClaudeUsageData(dto: MembershipLimitsResponse): ClaudeUsageData {
        val session = dto.session
        val sessionPercent = session?.percentUsed
            ?: session?.percentageUsed
            ?: dto.percentUsed
            ?: 0
        val sessionResetAt = session?.resetAt ?: session?.resetsAt ?: dto.resetAt

        val weeklyAllModels = dto.weekly?.allModels
        val weeklyPercent = weeklyAllModels?.percentUsed
            ?: weeklyAllModels?.percentageUsed
            ?: dto.weekly?.percentUsed
            ?: 0
        val weeklyResetAt = weeklyAllModels?.resetAt ?: weeklyAllModels?.resetsAt ?: dto.weekly?.resetAt

        return ClaudeUsageData(
            sessionPercent = sessionPercent,
            sessionResetAtMs = parseIso8601ToMs(sessionResetAt),
            weeklyPercent = weeklyPercent,
            weeklyResetAtMs = parseIso8601ToMs(weeklyResetAt),
            fetchedAtMs = System.currentTimeMillis()
        )
    }

    suspend fun validateSessionToken(sessionToken: String): ApiResult<Boolean> {
        return try {
            val cookie = formatCookie(sessionToken)

            // Try membership limits first
            val limitsResponse = service.getMembershipLimits(cookie)
            when {
                limitsResponse.isSuccessful -> {
                    val body = limitsResponse.body()?.string() ?: ""
                    return if (looksLikeHtml(body)) {
                        ApiResult.Error("Session token expired or invalid")
                    } else {
                        ApiResult.Success(true)
                    }
                }
                limitsResponse.code() == 401 || limitsResponse.code() == 403 ->
                    return ApiResult.Error("Session token expired or invalid")
            }

            // Fallback: try bootstrap endpoint
            val bootstrapResponse = service.getBootstrap(cookie)
            when {
                bootstrapResponse.isSuccessful -> {
                    val body = bootstrapResponse.body()?.string() ?: ""
                    if (looksLikeHtml(body)) {
                        ApiResult.Error("Session token expired or invalid")
                    } else {
                        ApiResult.Success(true)
                    }
                }
                bootstrapResponse.code() == 401 || bootstrapResponse.code() == 403 ->
                    ApiResult.Error("Session token expired or invalid")
                else -> ApiResult.Error("Validation failed: HTTP ${bootstrapResponse.code()}", bootstrapResponse.code())
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }

    private fun formatCookie(sessionToken: String): String {
        val trimmed = sessionToken.trim()
        return if (trimmed.startsWith("sessionKey=")) trimmed else "sessionKey=$trimmed"
    }

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<!") || trimmed.startsWith("<html")
    }
}

private class SessionExpiredException(message: String) : Exception(message)

// ─── AnthropicBalanceClient ───────────────────────────────────────────────────

/**
 * Fetches billing balance from api.anthropic.com using an admin API key.
 */
class AnthropicBalanceClient {

    private val moshi = buildMoshi()

    private val service: AnthropicApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(buildOkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AnthropicApiService::class.java)
    }

    suspend fun fetchBalance(apiKey: String): ApiResult<ApiBalance> {
        return try {
            // Primary endpoint
            val primary = tryBillingBalance(apiKey)
            if (primary is ApiResult.Success) return primary

            // Fallback endpoint
            val fallback = tryOrgBillingBalance(apiKey)
            if (fallback is ApiResult.Success) return fallback

            // Return primary error if both failed
            primary
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error("Request timed out")
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }

    private suspend fun tryBillingBalance(apiKey: String): ApiResult<ApiBalance> {
        return try {
            val response = service.getBillingBalance(apiKey)
            handleBalanceResponse(response)
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: Exception) {
            ApiResult.Error("Request failed: ${e.localizedMessage}")
        }
    }

    private suspend fun tryOrgBillingBalance(apiKey: String): ApiResult<ApiBalance> {
        return try {
            val response = service.getOrgBillingBalance(apiKey)
            handleBalanceResponse(response)
        } catch (_: Exception) {
            ApiResult.Error("Fallback endpoint also failed")
        }
    }

    private fun handleBalanceResponse(response: Response<ResponseBody>): ApiResult<ApiBalance> {
        return when {
            response.isSuccessful -> {
                val body = response.body()?.string() ?: return ApiResult.Error("Empty response body")
                val balance = parseBalance(body)
                    ?: return ApiResult.Error("Could not parse balance response")
                ApiResult.Success(balance)
            }
            response.code() == 401 -> ApiResult.Error("Invalid API key", 401)
            response.code() == 403 -> ApiResult.Error("API key lacks billing permissions. Use an Admin key.", 403)
            response.code() == 404 -> ApiResult.Error("Billing API not available for this key", 404)
            response.code() == 429 -> ApiResult.Error("Rate limited. Will retry later.", 429)
            else -> ApiResult.Error("Server error: ${response.code()}", response.code())
        }
    }

    private fun parseBalance(json: String): ApiBalance? {
        return try {
            val adapter = moshi.adapter(BillingBalanceResponse::class.java)
            val dto = adapter.fromJson(json) ?: return null

            val remaining = dto.availableCreditUsd?.toDoubleOrNull()
                ?: dto.balance?.toDoubleOrNull()
                ?: dto.available?.toDoubleOrNull()
                ?: 0.0
            val pending = dto.pendingChargesUsd?.toDoubleOrNull()
                ?: dto.pending?.toDoubleOrNull()
                ?: 0.0

            ApiBalance(
                remainingUsd = remaining,
                pendingUsd = pending,
                fetchedAtMs = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun validateApiKey(apiKey: String): ApiResult<Boolean> {
        return try {
            val response = service.getModels(apiKey)
            when {
                response.isSuccessful -> ApiResult.Success(true)
                response.code() == 401 -> ApiResult.Error("Invalid API key", 401)
                response.code() == 403 -> ApiResult.Error("API key is valid but has limited permissions", 403)
                else -> ApiResult.Error("Validation failed: ${response.code()}", response.code())
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }
}

// ─── Legacy wrapper kept for any remaining call-sites ────────────────────────

/**
 * Thin wrapper retained for backward compatibility; delegates to the new typed clients.
 */
class AnthropicApiClient {
    private val balanceClient = AnthropicBalanceClient()

    suspend fun fetchBalance(apiKey: String): ApiResult<ApiBalance> =
        balanceClient.fetchBalance(apiKey)

    suspend fun validateApiKey(apiKey: String): ApiResult<Boolean> =
        balanceClient.validateApiKey(apiKey)
}

// ─── Reset-time formatting ────────────────────────────────────────────────────

/**
 * Format a future epoch-ms timestamp for display.
 * < 24 h  → "Resets in Xh Ym"
 * >= 24 h → "Resets [weekday] [h:mm AM/PM]"
 */
fun formatResetTime(resetAtMs: Long): String {
    if (resetAtMs <= 0L) return ""
    val nowMs = System.currentTimeMillis()
    val diffMs = resetAtMs - nowMs
    if (diffMs <= 0L) return "Resetting…"

    val diffMin = diffMs / 60_000
    return if (diffMs < 24 * 3600_000L) {
        val h = diffMin / 60
        val m = diffMin % 60
        "Resets in ${h}h ${m}m"
    } else {
        val instant = Instant.ofEpochMilli(resetAtMs)
        val local = instant.atZone(ZoneId.systemDefault())
        val dayName = local.dayOfWeek.name.take(3)
            .replaceFirstChar { it.uppercase() }
        val timeStr = DateTimeFormatter.ofPattern("h:mm a")
            .format(local)
        "Resets $dayName $timeStr"
    }
}
