package com.charleswoo1.videodownloader.data.download.http

import kotlin.random.Random

/**
 * Retry policy with bounded attempts and exponential backoff + jitter.
 */
data class RetryPolicy(
    val maxRetries: Int = 2,
    val initialBackoffMs: Long = 500L,
    val backoffMultiplier: Double = 1.5,
    val maxBackoffMs: Long = 3000L
) {
    fun computeDelayMs(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val exponential = (initialBackoffMs * Math.pow(backoffMultiplier, (attempt - 1).toDouble())).toLong()
        val capped = minOf(exponential, maxBackoffMs)
        val jitter = Random.nextLong(0, (capped * 0.2).toLong().coerceAtLeast(10L))
        return capped + jitter
    }

    companion object {
        val DEFAULT = RetryPolicy()
        val NO_RETRY = RetryPolicy(maxRetries = 0)
    }
}
