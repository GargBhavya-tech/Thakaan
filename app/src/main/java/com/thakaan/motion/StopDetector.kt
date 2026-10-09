package com.thakaan.motion

enum class MoveState { MOVING, STOPPING, STOPPED, WALKING, RESTING }

/**
 * Every heart-rate reading carries this stamp (ticket #14). Stopping removes airflow and spikes HR within seconds;
 * dismounting adds 10 to 20 bpm for 30 to 60 s. A reading can only be compared with another taken in the same state at
 * the same time since stop, so both clocks travel with it.
 */
class StateStamp(val state: MoveState, val secSinceStop: Int?, val secSincePostureChange: Int?) {
    /** How much a heart-rate reading should count right now (1.0 = fully). Down-weights, never discards. */
    fun hrWeight(): Double = when {
        secSincePostureChange != null && secSincePostureChange < 30 -> 0.3
        secSinceStop != null && secSinceStop < 10 -> 0.5
        else -> 1.0
    }

    /** Torch prompt, camera and mic wake only when the worker is stopped or resting, never mid-ride. */
    fun capturePromptAllowed(): Boolean = state == MoveState.STOPPED || state == MoveState.RESTING
}

enum class PostureEvent { MOUNT, DISMOUNT, STAND, SIT }

/**
 * Moving -> Stopping -> Stopped -> Walking -> Resting state machine with two clocks:
 * time since the stop began, and time since the last posture change (mount / dismount / sit / stand).
 * Feed one [ActivityEstimate] per second.
 */
class StopDetector(
    private val confirmStopSec: Double = 2.0,
    private val restAfterSec: Double = 60.0,
    private val walkConfirmSec: Double = 2.0,
    private val standAfterIdleSec: Double = 20.0,
) {
    var state: MoveState = MoveState.STOPPED
        private set
    var lastPostureEvent: PostureEvent? = null
        private set

    private var stopStart: Long? = null
    private var postureAt: Long? = null
    private var lowSince: Long? = null     // first moment we stopped looking like riding
    private var walkSince: Long? = null
    private var idleSince: Long? = null
    private var idleBeforeWalk = 0.0
    private var started = false

    private fun sec(from: Long, to: Long) = ((to - from) / 1e9)

    private fun isRiding(e: ActivityEstimate) = e.activity == Activity.RIDING && (e.speedMs == null || e.speedMs > 1.0)

    fun update(e: ActivityEstimate): StateStamp {
        val t = e.tNanos
        val riding = isRiding(e)
        val walking = e.activity == Activity.WALKING || e.activity == Activity.STAIRS || e.activity == Activity.LIFTING
        val idle = e.activity == Activity.IDLE || (!riding && !walking)

        if (!started) {
            started = true
            state = if (riding) MoveState.MOVING else MoveState.STOPPED
            if (state == MoveState.STOPPED) { stopStart = t; idleSince = t }
        }

        when (state) {
            MoveState.MOVING -> if (!riding) { lowSince = t; state = MoveState.STOPPING } else { stopStart = null }
            MoveState.STOPPING -> {
                if (riding) { state = MoveState.MOVING; lowSince = null }
                else if (sec(lowSince!!, t) >= confirmStopSec) {
                    state = MoveState.STOPPED
                    stopStart = lowSince                // the clock starts when the vehicle stopped, not when we were sure
                    postureAt = lowSince; lastPostureEvent = PostureEvent.DISMOUNT
                    idleSince = if (idle) lowSince else null
                }
            }
            MoveState.STOPPED, MoveState.RESTING, MoveState.WALKING -> {
                if (riding) {
                    state = MoveState.MOVING; stopStart = null
                    postureAt = t; lastPostureEvent = PostureEvent.MOUNT
                    walkSince = null; idleSince = null
                } else if (walking) {
                    idleSince?.let { idleBeforeWalk = sec(it, t) }
                    if (walkSince == null) walkSince = t
                    if (state != MoveState.WALKING && sec(walkSince!!, t) >= walkConfirmSec) {
                        state = MoveState.WALKING
                        if (idleBeforeWalk >= standAfterIdleSec) { postureAt = walkSince; lastPostureEvent = PostureEvent.STAND }
                    }
                    idleSince = null
                } else if (idle) {
                    walkSince = null
                    if (idleSince == null) {
                        idleSince = t
                        if (state == MoveState.WALKING) { state = MoveState.STOPPED; postureAt = t; lastPostureEvent = PostureEvent.SIT }
                    }
                    if (sec(idleSince!!, t) >= restAfterSec) state = MoveState.RESTING
                    else if (state == MoveState.RESTING) { /* stay */ } else if (state == MoveState.WALKING) state = MoveState.STOPPED
                }
            }
        }

        val sinceStop = when (state) {
            MoveState.MOVING -> null
            MoveState.STOPPING -> 0
            else -> stopStart?.let { sec(it, t).toInt() }
        }
        val sincePosture = postureAt?.let { sec(it, t).toInt() }
        return StateStamp(state, sinceStop, sincePosture)
    }
}
