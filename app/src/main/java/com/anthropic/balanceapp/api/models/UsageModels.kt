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
    val lastError: String = "",
    // True when the session is valid but this account type doesn't expose usage percentages
    // (e.g. personal Claude Pro with rate_limit_tier=default_claude_ai).
    val dataUnavailable: Boolean = false
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
    @Json(name = "organization") val organization: BootstrapOrganization? = null,
    @Json(name = "active_organization") val activeOrganization: BootstrapOrganization? = null,
    @Json(name = "memberships") val memberships: List<BootstrapMembership>? = null,
    // Some API responses return a flat array of organizations
    @Json(name = "organizations") val organizations: List<BootstrapOrganization>? = null
)

@JsonClass(generateAdapter = true)
data class BootstrapAccount(
    @Json(name = "membership_limits") val membershipLimits: MembershipLimitsResponse? = null,
    @Json(name = "limits") val limits: MembershipLimitsResponse? = null,
    @Json(name = "rate_limit_usage") val rateLimitUsage: MembershipLimitsResponse? = null,
    @Json(name = "usage") val usage: MembershipLimitsResponse? = null,
    @Json(name = "memberships") val memberships: List<BootstrapMembership>? = null
)

@JsonClass(generateAdapter = true)
data class BootstrapOrganization(
    @Json(name = "id") val id: String? = null,
    @Json(name = "uuid") val uuid: String? = null
)

@JsonClass(generateAdapter = true)
data class BootstrapMembership(
    @Json(name = "organization") val organization: BootstrapOrganization? = null,
    @Json(name = "membership_limits") val membershipLimits: MembershipLimitsResponse? = null,
    @Json(name = "limits") val limits: MembershipLimitsResponse? = null,
    // Personal Claude Pro accounts may use these field names instead
    @Json(name = "rate_limit_usage") val rateLimitUsage: MembershipLimitsResponse? = null,
    @Json(name = "usage") val usage: MembershipLimitsResponse? = null,
    @Json(name = "current_usage") val currentUsage: MembershipLimitsResponse? = null
)

// ─── Org usage response (api/organizations/{id}/usage) ───────────────────────

@JsonClass(generateAdapter = true)
data class OrgUsageResponse(
    @Json(name = "five_hour") val fiveHour: OrgUsagePeriod? = null,
    @Json(name = "seven_day") val sevenDay: OrgUsagePeriod? = null,
    @Json(name = "extra_usage") val extraUsage: OrgExtraUsage? = null
)

@JsonClass(generateAdapter = true)
data class OrgUsagePeriod(
    @Json(name = "utilization") val utilization: Double? = null,
    @Json(name = "resets_at") val resetsAt: String? = null
)

@JsonClass(generateAdapter = true)
data class OrgExtraUsage(
    @Json(name = "is_enabled") val isEnabled: Boolean? = null,
    @Json(name = "monthly_limit") val monthlyLimit: Int? = null,
    @Json(name = "used_credits") val usedCredits: Double? = null,
    @Json(name = "utilization") val utilization: Double? = null
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
