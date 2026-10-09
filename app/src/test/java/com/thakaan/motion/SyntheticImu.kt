package com.thakaan.motion

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ImuSample(val tNanos: Long, val ax: Double, val ay: Double, val az: Double, val gx: Double, val gy: Double, val gz: Double)

/** Builds IMU streams with known motion. Gravity is 9.81 m/s^2 along [dir]; upright in a pocket is +y, lying is +x. */
class ImuBuilder(val fs: Double = 100.0, seed: Long = 1, startNanos: Long = 1_000_000_000L) {
    val samples = ArrayList<ImuSample>()
    private val rng = Random(seed)
    var t = 0.0
        private set
    private val start = startNanos
    var dir = doubleArrayOf(0.0, 1.0, 0.0)

    private fun add(a: DoubleArray, g: DoubleArray) {
        samples += ImuSample(start + (t * 1e9).toLong(), a[0], a[1], a[2], g[0], g[1], g[2])
        t += 1.0 / fs
    }

    private fun gravity() = doubleArrayOf(9.81 * dir[0], 9.81 * dir[1], 9.81 * dir[2])

    private fun noise(s: Double) = rng.nextGaussian() * s

    fun idle(sec: Double, noise: Double = 0.02): ImuBuilder {
        repeat((sec * fs).toInt()) {
            val g = gravity()
            add(doubleArrayOf(g[0] + noise(noise), g[1] + noise(noise), g[2] + noise(noise)), doubleArrayOf(noise(0.005), noise(0.005), noise(0.005)))
        }
        return this
    }

    /** Pocket walking: vertical bounce along gravity at [hz], thigh swing on the gyro. */
    fun walking(sec: Double, hz: Double = 1.8, amp: Double = 3.0, gyroRms: Double = 1.2): ImuBuilder {
        val n = (sec * fs).toInt()
        repeat(n) { i ->
            val tt = i / fs
            val g = gravity()
            val v = amp * sin(2 * PI * hz * tt) + 0.3 * amp * sin(4 * PI * hz * tt)
            val sw = gyroRms * sqrt(2.0) * sin(PI * hz * tt) / sqrt(1.0)
            add(
                doubleArrayOf(g[0] + v * dir[0] + noise(0.25), g[1] + v * dir[1] + noise(0.25), g[2] + v * dir[2] + noise(0.25)),
                doubleArrayOf(sw * 0.8, sw * 0.4, sw * 0.2),
            )
        }
        return this
    }

    fun inHandWalking(sec: Double, hz: Double = 1.8): ImuBuilder = walking(sec, hz, amp = 2.2, gyroRms = 0.4)

    fun stairs(sec: Double): ImuBuilder = walking(sec, hz = 1.4, amp = 5.5, gyroRms = 1.1)

    /** Mounted on handlebars: broadband vibration, stable orientation. */
    fun riding(sec: Double, vib: Double = 0.9): ImuBuilder {
        var lp = 0.0
        repeat((sec * fs).toInt()) {
            val g = gravity()
            val raw = noise(vib)
            val hp = raw - lp
            lp += 0.3 * (raw - lp)
            add(
                doubleArrayOf(g[0] + noise(vib * 0.6), g[1] + hp * 1.2, g[2] + noise(vib * 0.6)),
                doubleArrayOf(noise(0.03), noise(0.03), noise(0.03)),
            )
        }
        return this
    }

    /** Heavy lifting: irregular bursts of acceleration and strong rotation, no steady rhythm. */
    fun lifting(sec: Double): ImuBuilder {
        repeat((sec * fs).toInt()) { i ->
            val g = gravity()
            val burst = if ((i / (fs * 0.7)).toInt() % 3 == 0) 1.0 else 0.15
            val peak = if (i % 70 == 0) 12.0 * (if ((i / 70) % 2 == 0) 1 else -1) else 0.0 // a heave about every 0.7 s
            add(
                doubleArrayOf(g[0] + noise(1.5) * burst + peak, g[1] + noise(1.5) * burst, g[2] + noise(1.5) * burst),
                doubleArrayOf(noise(2.2) * burst + 1.5 * burst, noise(2.0) * burst, noise(1.8) * burst),
            )
        }
        return this
    }

    /** Hits the ground: ~0.15 s of free fall, a hard impact, then settles with gravity along [lying]. */
    fun fall(lying: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0), impact: Double = 45.0): ImuBuilder {
        repeat((0.15 * fs).toInt()) { add(doubleArrayOf(noise(0.3), noise(0.3), noise(0.3)), doubleArrayOf(noise(2.0), noise(2.0), noise(2.0))) }
        dir = lying
        repeat(3) { add(doubleArrayOf(impact * lying[0], impact * lying[1], impact * lying[2]), doubleArrayOf(noise(3.0), noise(3.0), noise(3.0))) }
        return this
    }

    /** Smoothly rotate gravity from the current direction to [to] over [sec]; no impact. [jerk] adds irregular unsteadiness. */
    fun rotateGravity(sec: Double, to: DoubleArray, jerk: Double = 0.0): ImuBuilder {
        val from = dir.copyOf()
        val n = (sec * fs).toInt()
        repeat(n) { i ->
            val f = (1 - cos(PI * i / (n - 1).coerceAtLeast(1))) / 2
            val d = DoubleArray(3) { from[it] + (to[it] - from[it]) * f }
            val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            val g = DoubleArray(3) { 9.81 * d[it] / len }
            add(doubleArrayOf(g[0] + noise(jerk), g[1] + noise(jerk), g[2] + noise(jerk)), doubleArrayOf(noise(0.2), noise(0.2), noise(0.2)))
        }
        dir = to
        return this
    }
}
