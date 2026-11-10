package com.sonicareset.app.tracker

import java.util.concurrent.TimeUnit

/**
 * A single Sonicare brush head the user is tracking.
 *
 * @param id random UUID string identifying this record
 * @param name a human label, e.g. "ProResults" or a color/person label
 * @param installedAtEpochMillis when the head was mounted (epoch millis)
 * @param targetDays the intended lifetime in days (Sonicare suggests ~90)
 * @param notes free-form notes
 */
data class BrushHead(
    val id: String,
    val name: String,
    val installedAtEpochMillis: Long,
    val targetDays: Int = 90,
    val notes: String = ""
) {
    /** Whole days elapsed since install. Never negative. */
    fun elapsedDays(nowMillis: Long = System.currentTimeMillis()): Long {
        val delta = nowMillis - installedAtEpochMillis
        if (delta <= 0L) return 0L
        return TimeUnit.MILLISECONDS.toDays(delta)
    }

    /** Days left before reaching target life. May be negative when overdue. */
    fun remainingDays(nowMillis: Long = System.currentTimeMillis()): Long {
        return targetDays.toLong() - elapsedDays(nowMillis)
    }

    /** Fraction of target life consumed, clamped to 0f..1f. */
    fun usageFraction(nowMillis: Long = System.currentTimeMillis()): Float {
        if (targetDays <= 0) return 1f
        val fraction = elapsedDays(nowMillis).toFloat() / targetDays.toFloat()
        return fraction.coerceIn(0f, 1f)
    }
}
