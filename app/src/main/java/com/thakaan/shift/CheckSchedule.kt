package com.thakaan.shift

import com.thakaan.risk.Level

/**
 * When the next check is due for workers who are not riding (construction, police or security, other gig work). The hotter the
 * level, the sooner: more information more often is the safe direction. A worker who has not checked yet is due now.
 */
object CheckSchedule {
    fun intervalMin(level: Level): Int = when (level) {
        Level.COOL -> 45
        Level.WARMING -> 30
        Level.STRAINED -> 15
        Level.DANGER -> 10
    }

    /** Seconds until the next check; zero or negative means due now (or overdue). */
    fun dueInSec(lastCheckEpochSec: Long?, nowEpochSec: Long, level: Level): Long =
        if (lastCheckEpochSec == null) 0L else lastCheckEpochSec + intervalMin(level) * 60L - nowEpochSec

    fun dueText(dueSec: Long): String = when {
        dueSec <= 0 -> "Check due now"
        dueSec < 90 -> "Next check in under 2 min"
        else -> "Next check in ${(dueSec + 30) / 60} min"
    }
}
