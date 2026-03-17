package com.anthropic.balanceapp.api

import com.anthropic.balanceapp.api.models.ApiBalance
import com.anthropic.balanceapp.api.models.BillingBalanceResponse
import com.anthropic.balanceapp.api.models.BootstrapResponse
import com.anthropic.balanceapp.api.models.ClaudeUsageData
import com.anthropic.balanceapp.api.models.MembershipLimitsResponse
import com.anthropic.balanceapp.api.models.OrgLimitsResponse
import com.anthropic.balanceapp.api.models.OrgUsageResponse
import com.anthropic.balanceapp.api.models.PlatformCreditsResponse
import com.anthropic.balanceapp.api.models.PlatformOrganization
import com.squareup.moshi.Types
import kotlin.math.roundToInt
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.anthropic.balanceapp.logging.AppLogger
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

    @GET("api/account_limits")
    suspend fun getAccountLimits(
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)"
    ): Response<ResponseBody>

    @GET("api/organizations/{orgId}/limits")
    suspend fun getOrgLimits(
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)",
        @Path("orgId") orgId: String
    ): Response<ResponseBody>

    @GET("api/organizations/{orgId}/usage")
    suspend fun getOrgUsage(
        @Header("Cookie") cookie: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)",
        @Path("orgId") orgId: String
    ): Response<ResponseBody>

    @GET("api/organizations/{orgId}/rate_limits")
    suspend fun getOrgRateLimits(
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

interface PlatformClaudeService {
    @GET("api/organizations")
    suspend fun getOrganizations(
        @Header("Cookie") cookie: String,
        @Header("anthropic-client-platform") platform: String = "web_console",
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)"
    ): Response<ResponseBody>

    @GET("api/organizations/{orgId}/prepaid/credits")
    suspend fun getPrepaidCredits(
        @Header("Cookie") cookie: String,
        @Header("anthropic-client-platform") platform: String = "web_console",
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Linux; Android 14)",
        @Path("orgId") orgId: String
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

            // Secondary endpoint (newer API path)
            val secondaryResult = tryAccountLimits(cookie)
            if (secondaryResult != null) return ApiResult.Success(secondaryResult)

            // Fallback: bootstrap → parse embedded limits or org limits
            val (bootstrapResult, bootstrapError) = tryBootstrapThenOrgLimits(cookie)
            if (bootstrapResult != null) return ApiResult.Success(bootstrapResult)

            ApiResult.Error(bootstrapError ?: "Could not retrieve usage data from any Claude.ai endpoint")
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error("Request timed out")
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }

    private suspend fun tryAccountLimits(cookie: String): ClaudeUsageData? {
        return try {
            val response = service.getAccountLimits(cookie)
            when {
                response.isSuccessful -> {
                    val body = response.body()?.string() ?: return null
                    if (looksLikeHtml(body)) return null
                    AppLogger.d("account_limits response: ${body.take(1500)}")
                    parseMembershipLimits(body)?.takeIf { it.hasData() }
                }
                response.code() == 401 || response.code() == 403 ->
                    throw SessionExpiredException("Session token expired or invalid")
                else -> {
                    AppLogger.d("account_limits HTTP ${response.code()}")
                    null
                }
            }
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryMembershipLimits(cookie: String): ClaudeUsageData? {
        return try {
            val response = service.getMembershipLimits(cookie)
            when {
                response.isSuccessful -> {
                    val body = response.body()?.string() ?: return null
                    if (looksLikeHtml(body)) return null
                    AppLogger.d("membership_limits response: ${body.take(1500)}")
                    parseMembershipLimits(body)?.takeIf { it.hasData() }
                }
                response.code() == 401 || response.code() == 403 -> {
                    throw SessionExpiredException("Session token expired or invalid")
                }
                else -> {
                    AppLogger.d("membership_limits HTTP ${response.code()}")
                    null
                }
            }
        } catch (e: SessionExpiredException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Returns Pair(data, errorMessage). Data is non-null on success, errorMessage on failure. */
    private suspend fun tryBootstrapThenOrgLimits(cookie: String): Pair<ClaudeUsageData?, String?> {
        return try {
            val bootstrapResponse = service.getBootstrap(cookie)
            if (!bootstrapResponse.isSuccessful) {
                return Pair(null, "Bootstrap HTTP ${bootstrapResponse.code()}")
            }
            val bootstrapBody = bootstrapResponse.body()?.string() ?: return Pair(null, "Empty bootstrap response")
            if (looksLikeHtml(bootstrapBody)) return Pair(null, "Session token expired or invalid")
            AppLogger.d("bootstrap response (${bootstrapBody.length} chars): ${bootstrapBody.take(3000)}")

            val bootstrapAdapter = moshi.adapter(BootstrapResponse::class.java)
            val bootstrap = try { bootstrapAdapter.fromJson(bootstrapBody) } catch (e: Exception) {
                return Pair(null, "Could not parse bootstrap response")
            }

            // Log where usage fields appear in the bootstrap body (helps debug new API shapes)
            listOf("percent_used", "percentage_used", "reset_at", "resets_at").forEach { kw ->
                val idx = bootstrapBody.indexOf("\"$kw\"")
                if (idx >= 0) {
                    AppLogger.d("bootstrap keyword '$kw' at pos $idx: ...${bootstrapBody.substring(maxOf(0, idx - 60), minOf(bootstrapBody.length, idx + 120))}...")
                }
            }

            // Try embedded limits from bootstrap account object
            val accountLimits = bootstrap?.account?.membershipLimits
                ?: bootstrap?.account?.limits
                ?: bootstrap?.account?.rateLimitUsage
                ?: bootstrap?.account?.usage
            if (accountLimits != null) {
                val parsed = buildClaudeUsageData(accountLimits)
                // Accept even 0% — it's valid at the start of a period
                if (parsed.sessionResetAtMs > 0 || parsed.weeklyResetAtMs > 0
                    || parsed.sessionPercent > 0 || parsed.weeklyPercent > 0
                ) return Pair(parsed, null)
            }

            // Try embedded limits from memberships list — check ALL memberships, not just first
            val membershipsList = bootstrap?.memberships
                ?: bootstrap?.account?.memberships
            val membershipLimits = membershipsList?.firstNotNullOfOrNull { m ->
                m.membershipLimits ?: m.limits ?: m.rateLimitUsage ?: m.usage ?: m.currentUsage
            }
            if (membershipLimits != null) {
                AppLogger.d("membership embedded limits: session=${membershipLimits.session?.percentUsed} weekly=${membershipLimits.weekly?.allModels?.percentUsed}")
                val parsed = buildClaudeUsageData(membershipLimits)
                if (parsed.sessionResetAtMs > 0 || parsed.weeklyResetAtMs > 0
                    || parsed.sessionPercent > 0 || parsed.weeklyPercent > 0
                ) return Pair(parsed, null)
            } else {
                AppLogger.d("bootstrap: no embedded membership limits found (accountLimits=$accountLimits, membershipsList size=${membershipsList?.size})")
            }

            // Extract org ID — prefer UUID over numeric id (API endpoints expect UUID)
            val orgId = bootstrap?.organization?.uuid
                ?: bootstrap?.organization?.id
                ?: bootstrap?.activeOrganization?.uuid
                ?: bootstrap?.activeOrganization?.id
                ?: bootstrap?.memberships?.firstOrNull()?.organization?.uuid
                ?: bootstrap?.memberships?.firstOrNull()?.organization?.id
                ?: bootstrap?.account?.memberships?.firstOrNull()?.organization?.uuid
                ?: bootstrap?.account?.memberships?.firstOrNull()?.organization?.id
                ?: bootstrap?.organizations?.firstOrNull()?.uuid
                ?: bootstrap?.organizations?.firstOrNull()?.id
                // Personal accounts have no org — return empty data (no usage limits to show)
                ?: return Pair(ClaudeUsageData(fetchedAtMs = System.currentTimeMillis(), dataUnavailable = true), null)
            AppLogger.d("Fetching org usage for orgId=$orgId")

            // Try new usage endpoint first (five_hour / seven_day shape)
            val usageResponse = service.getOrgUsage(cookie, orgId = orgId)
            if (usageResponse.isSuccessful) {
                val usageBody = usageResponse.body()?.string() ?: ""
                AppLogger.d("org usage response: ${usageBody.take(500)}")
                if (!looksLikeHtml(usageBody)) {
                    val result = parseOrgUsage(usageBody)
                    if (result != null) return Pair(result, null)
                }
            } else {
                AppLogger.d("org usage HTTP ${usageResponse.code()}")
            }

            // Try org limits endpoint
            val orgResponse = service.getOrgLimits(cookie, orgId = orgId)
            if (orgResponse.isSuccessful) {
                val orgBody = orgResponse.body()?.string() ?: return Pair(null, "Empty org limits response")
                AppLogger.d("org limits response: ${orgBody.take(500)}")
                if (!looksLikeHtml(orgBody)) {
                    val result = parseOrgLimits(orgBody)
                    if (result != null && result.hasData()) return Pair(result, null)
                }
            } else {
                AppLogger.w("org limits HTTP ${orgResponse.code()}")
            }

            // Try org rate_limits endpoint as final fallback
            val rateResponse = service.getOrgRateLimits(cookie, orgId = orgId)
            if (rateResponse.isSuccessful) {
                val rateBody = rateResponse.body()?.string() ?: return Pair(null, "Empty rate limits response")
                AppLogger.d("org rate_limits response: ${rateBody.take(500)}")
                if (!looksLikeHtml(rateBody)) {
                    val result = parseOrgLimits(rateBody)
                    if (result != null && result.hasData()) return Pair(result, null)
                }
            } else {
                AppLogger.w("org rate_limits HTTP ${rateResponse.code()}")
            }

            // Bootstrap returned 200 so the session is valid, but no endpoint exposed usage
            // percentages — this is expected for personal Claude Pro accounts
            // (rate_limit_tier=default_claude_ai). Return success with zeros so the worker
            // doesn't retry endlessly and the UI shows no-data rather than an error.
            val orgCode = orgResponse.code()
            val rateCode = rateResponse.code()
            AppLogger.d("No usage data available from any endpoint (limits: HTTP $orgCode, rate_limits: HTTP $rateCode) — returning empty data for personal account")
            Pair(ClaudeUsageData(fetchedAtMs = System.currentTimeMillis(), dataUnavailable = true), null)
        } catch (e: Exception) {
            Pair(null, "Unexpected error: ${e.localizedMessage}")
        }
    }

    private fun ClaudeUsageData.hasData(): Boolean =
        sessionPercent > 0 || weeklyPercent > 0 || sessionResetAtMs > 0 || weeklyResetAtMs > 0

    private fun parseMembershipLimits(json: String): ClaudeUsageData? {
        return try {
            val adapter = moshi.adapter(MembershipLimitsResponse::class.java)
            val dto = adapter.fromJson(json) ?: return null
            buildClaudeUsageData(dto)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOrgUsage(json: String): ClaudeUsageData? {
        return try {
            val adapter = moshi.adapter(OrgUsageResponse::class.java)
            val dto = adapter.fromJson(json) ?: return null
            val sessionResetAtMs = parseIso8601ToMs(dto.fiveHour?.resetsAt)
            val weeklyResetAtMs = parseIso8601ToMs(dto.sevenDay?.resetsAt)
            if (sessionResetAtMs == 0L && weeklyResetAtMs == 0L) return null
            ClaudeUsageData(
                sessionPercent = dto.fiveHour?.utilization?.roundToInt() ?: 0,
                sessionResetAtMs = sessionResetAtMs,
                weeklyPercent = dto.sevenDay?.utilization?.roundToInt() ?: 0,
                weeklyResetAtMs = weeklyResetAtMs,
                fetchedAtMs = System.currentTimeMillis()
            )
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

            // Try newer account_limits endpoint
            val accountLimitsResponse = service.getAccountLimits(cookie)
            when {
                accountLimitsResponse.isSuccessful -> {
                    val body = accountLimitsResponse.body()?.string() ?: ""
                    return if (looksLikeHtml(body)) {
                        ApiResult.Error("Session token expired or invalid")
                    } else {
                        ApiResult.Success(true)
                    }
                }
                accountLimitsResponse.code() == 401 || accountLimitsResponse.code() == 403 ->
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

// ─── PlatformCreditsClient ────────────────────────────────────────────────────

/**
 * Fetches prepaid credit balance from platform.claude.com using the same
 * sessionKey cookie as claude.ai.
 */
class PlatformCreditsClient {

    private val moshi = buildMoshi()

    private val service: PlatformClaudeService by lazy {
        Retrofit.Builder()
            .baseUrl("https://platform.claude.com/")
            .client(buildOkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PlatformClaudeService::class.java)
    }

    suspend fun fetchBalance(sessionToken: String): ApiResult<ApiBalance> {
        val cookie = "sessionKey=$sessionToken"
        return try {
            // 1. Discover the prepaid org UUID
            val orgUuid = fetchPrepaidOrgUuid(cookie)
                ?: return ApiResult.Error("No prepaid org found", 404)

            // 2. Fetch credits for that org
            val response = service.getPrepaidCredits(cookie, orgId = orgUuid)
            if (!response.isSuccessful) {
                return ApiResult.Error("Credits fetch failed: ${response.code()}", response.code())
            }
            val body = response.body()?.string()
                ?: return ApiResult.Error("Empty credits response")
            val adapter = moshi.adapter(PlatformCreditsResponse::class.java)
            val dto = adapter.fromJson(body)
                ?: return ApiResult.Error("Could not parse credits response")

            val remaining = dto.amount ?: 0.0
            val pendingCents = dto.pendingInvoiceAmountCents ?: 0L
            ApiResult.Success(ApiBalance(
                remainingUsd = remaining,
                pendingUsd = pendingCents / 100.0,
                fetchedAtMs = System.currentTimeMillis()
            ))
        } catch (e: java.net.UnknownHostException) {
            ApiResult.NetworkError
        } catch (e: java.net.SocketTimeoutException) {
            ApiResult.Error("Request timed out")
        } catch (e: Exception) {
            ApiResult.Error("Unexpected error: ${e.localizedMessage}")
        }
    }

    private suspend fun fetchPrepaidOrgUuid(cookie: String): String? {
        return try {
            val response = service.getOrganizations(cookie)
            if (!response.isSuccessful) return null
            val body = response.body()?.string() ?: return null
            val type = Types.newParameterizedType(List::class.java, PlatformOrganization::class.java)
            val adapter = moshi.adapter<List<PlatformOrganization>>(type)
            val orgs = adapter.fromJson(body) ?: return null
            // Prefer prepaid org; fall back to first org
            orgs.firstOrNull { it.billingType == "prepaid" }?.uuid
                ?: orgs.firstOrNull()?.uuid
        } catch (_: Exception) {
            null
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
