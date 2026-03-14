package com.anthropic.balanceapp.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ─── Domain models ───────────────────────────────────────────────────────────

/**
 * Usage data fetched from the Claude.ai platform (session token required).
 */
data class ClaudeUsageData(
    val sessionPercent: Int = 0,
    val sessionResetAtMs: Long = 0L,    // epoch ms when session resets
    val weeklyPercent: Int = 0,
    val weeklyResetAtMs: Long = 0L,     // epoch ms when weekly resets
    val fetchedAtMs: Long = 0L,
    val lastError: String = ""
)

/**
 * Billing balance fetched from the Anthropic billing API (admin key required).
 */
data class ApiBalance(
    val remainingUsd: Double = 0.0,
    val pendingUsd: Double = 0.0,
    val fetchedAtMs: Long = 0L,
    val lastError: String = ""
)

// ─── Moshi DTOs for claude.ai/api/account_membership_limits ─────────────────

@JsonClass(generateAdapter = true)
data class MembershipLimitsResponse(
    @Json(name = "session") val session: SessionLimit? = null,
    @Json(name = "weekly") val weekly: WeeklyLimits? = null,
    // Some API shapes embed limits directly at top level; include fallbacks
    @Json(name = "percent_used") val percentUsed: Int? = null,
    @Json(name = "reset_at") val resetAt: String? = null
)

@JsonClass(generateAdapter = true)
data class SessionLimit(
    @Json(name = "percent_used") val percentUsed: Int = 0,
    @Json(name = "reset_at") val resetAt: String? = null,
    // fallback field names that may appear in some responses
    @Json(name = "percentage_used") val percentageUsed: Int? = null,
    @Json(name = "resets_at") val resetsAt: String? = null
)

@JsonClass(generateAdapter = true)
data class WeeklyLimits(
    @Json(name = "all_models") val allModels: WeeklyAllModels? = null,
    // Some responses put weekly stats at this level
    @Json(name = "percent_used") val percentUsed: Int? = null,
    @Json(name = "reset_at") val resetAt: String? = null
)

@JsonClass(generateAdapter = true)
data class WeeklyAllModels(
    @Json(name = "percent_used") val percentUsed: Int = 0,
    @Json(name = "reset_at") val resetAt: String? = null,
    @Json(name = "percentage_used") val percentageUsed: Int? = null,
    @Json(name = "resets_at") val resetsAt: String? = null
)

// ─── Moshi DTOs for api.anthropic.com/v1/billing/balance ────────────────────

@JsonClass(generateAdapter = true)
data class BillingBalanceResponse(
    @Json(name = "available_credit_usd") val availableCreditUsd: String? = null,
    @Json(name = "pending_charges_usd") val pendingChargesUsd: String? = null,
    // Alternative field names used in some API versions
    @Json(name = "balance") val balance: String? = null,
    @Json(name = "available") val available: String? = null,
    @Json(name = "pending") val pending: String? = null
)

// ─── Bootstrap response (fallback for claude.ai) ────────────────────────────

@JsonClass(generateAdapter = true)
data class BootstrapResponse(
    @Json(name = "account") val account: BootstrapAccount? = null,
    @Json(name = "organization") val organization: BootstrapOrganization? = null
)

@JsonClass(generateAdapter = true)
data class BootstrapAccount(
    @Json(name = "membership_limits") val membershipLimits: MembershipLimitsResponse? = null
)

@JsonClass(generateAdapter = true)
data class BootstrapOrganization(
    @Json(name = "id") val id: String? = null,
    @Json(name = "uuid") val uuid: String? = null
)

// ─── Org limits response ─────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class OrgLimitsResponse(
    @Json(name = "session") val session: SessionLimit? = null,
    @Json(name = "weekly") val weekly: WeeklyLimits? = null,
    @Json(name = "limits") val limits: MembershipLimitsResponse? = null
)

// ─── Shared error DTO ─────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class ErrorResponse(
    @Json(name = "error") val error: ApiError? = null
)

@JsonClass(generateAdapter = true)
data class ApiError(
    @Json(name = "type") val type: String? = null,
    @Json(name = "message") val message: String? = null
)
