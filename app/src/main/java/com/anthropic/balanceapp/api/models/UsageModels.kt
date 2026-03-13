package com.anthropic.balanceapp.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UsageResponse(
    @Json(name = "data") val data: List<UsageEntry> = emptyList(),
    @Json(name = "has_more") val hasMore: Boolean = false
)

@JsonClass(generateAdapter = true)
data class UsageEntry(
    @Json(name = "timestamp") val timestamp: String? = null,
    @Json(name = "organization_id") val organizationId: String? = null,
    @Json(name = "input_tokens") val inputTokens: Long = 0,
    @Json(name = "output_tokens") val outputTokens: Long = 0,
    @Json(name = "cache_creation_input_tokens") val cacheCreationInputTokens: Long = 0,
    @Json(name = "cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
    @Json(name = "model") val model: String? = null
)

@JsonClass(generateAdapter = true)
data class ErrorResponse(
    @Json(name = "error") val error: ApiError? = null
)

@JsonClass(generateAdapter = true)
data class ApiError(
    @Json(name = "type") val type: String? = null,
    @Json(name = "message") val message: String? = null
)

/**
 * Aggregated usage data computed from the raw API response.
 */
data class AggregatedUsage(
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheCreationTokens: Long,
    val totalCacheReadTokens: Long,
    val estimatedCostUsd: Double,
    val periodStart: String,
    val periodEnd: String,
    val fetchedAtMs: Long = System.currentTimeMillis()
) {
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens

    companion object {
        // Pricing per 1M tokens (Claude 3.5 Sonnet as default reference)
        const val INPUT_PRICE_PER_MILLION = 3.0
        const val OUTPUT_PRICE_PER_MILLION = 15.0
        const val CACHE_WRITE_PRICE_PER_MILLION = 3.75
        const val CACHE_READ_PRICE_PER_MILLION = 0.30

        fun fromEntries(entries: List<UsageEntry>, periodStart: String, periodEnd: String): AggregatedUsage {
            val totalInput = entries.sumOf { it.inputTokens }
            val totalOutput = entries.sumOf { it.outputTokens }
            val totalCacheWrite = entries.sumOf { it.cacheCreationInputTokens }
            val totalCacheRead = entries.sumOf { it.cacheReadInputTokens }

            val costInput = (totalInput / 1_000_000.0) * INPUT_PRICE_PER_MILLION
            val costOutput = (totalOutput / 1_000_000.0) * OUTPUT_PRICE_PER_MILLION
            val costCacheWrite = (totalCacheWrite / 1_000_000.0) * CACHE_WRITE_PRICE_PER_MILLION
            val costCacheRead = (totalCacheRead / 1_000_000.0) * CACHE_READ_PRICE_PER_MILLION

            return AggregatedUsage(
                totalInputTokens = totalInput,
                totalOutputTokens = totalOutput,
                totalCacheCreationTokens = totalCacheWrite,
                totalCacheReadTokens = totalCacheRead,
                estimatedCostUsd = costInput + costOutput + costCacheWrite + costCacheRead,
                periodStart = periodStart,
                periodEnd = periodEnd
            )
        }
    }
}
