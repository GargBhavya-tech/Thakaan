package com.thakaan.core

/**
 * Where a reading came from. Stamped once at the sensor hub and inherited by everything computed from it.
 * Invariant 6: REPLAY and SIMULATED must never show as REAL.
 */
enum class Provenance {
    REAL, REPLAY, SIMULATED;

    companion object {
        /**
         * Provenance of a value computed from several inputs: if any input is not REAL the result is not REAL.
         * SIMULATED outranks REPLAY outranks REAL, so mixing can only move away from REAL.
         */
        fun combine(vararg inputs: Provenance): Provenance =
            inputs.maxByOrNull { it.ordinal } ?: SIMULATED
    }
}
