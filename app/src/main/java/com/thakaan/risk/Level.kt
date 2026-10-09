package com.thakaan.risk

import androidx.compose.ui.graphics.Color

/**
 * What the worker sees. Never a body-temperature number (invariant 2).
 * Each level has colour + shape + words so it reads in sun and for colour-blind users.
 */
enum class Level(val word: String, val shape: String, val color: Color) {
    COOL("Cool", "●", Color(0xFF1B7F3B)),
    WARMING("Warming", "▲", Color(0xFFB26A00)),
    STRAINED("Strained", "◆", Color(0xFFC03A00)),
    DANGER("Danger", "■", Color(0xFF9B0000)),
}

/** PLACEHOLDER mapping for the mock gauge only. The real, anchored levels come from the risk engine (#39 to #41). */
fun mockLevelFor(tss: Int): Level = when {
    tss < 25 -> Level.COOL
    tss < 50 -> Level.WARMING
    tss < 75 -> Level.STRAINED
    else -> Level.DANGER
}
