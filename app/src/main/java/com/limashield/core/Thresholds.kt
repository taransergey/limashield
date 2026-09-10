package com.limashield.core

/**
 * All detection and FSM thresholds in one place (spec §3.2, §4).
 * Populated from user settings in Prefs.thresholds().
 */
data class Thresholds(
    // C1: GNSS vs network divergence
    val netDivergenceM: Double = 10_000.0,
    val netFreshMs: Long = 60_000,

    // C2: teleport
    val teleportM: Double = 100_000.0,
    val teleportDtMs: Long = 60_000,

    // C3: impossible speed (300 km/h — with headroom for a motorcyclist, spec §4.3)
    val maxSpeedMps: Double = 83.3,
    val speedConsecutive: Int = 3,

    // C4: "Lima" signature zone (Lima/Peru bounding box)
    val signatureZone: BBox = BBox(-18.0, 0.0, -82.0, -68.0),

    // C5: circular motion
    val circleMinFixes: Int = 10,
    val circleMaxFixes: Int = 20,
    val circleSpeedCvMax: Double = 0.05,
    val circleMinSpeedMps: Double = 20.0,
    val circleMinTurnRateDegS: Double = 0.5,
    val circleMaxTurnRateDegS: Double = 30.0,
    val circleMinTotalTurnDeg: Double = 60.0,

    // C6: drag-off — a slow pull of GNSS away from the network position.
    // Adaptive threshold: max(dragMinM, netAcc*dragAccFactor) + network fix age * dragSpeedAllowanceMps.
    // Tuned on a real Lima recording of 2026-09-06 (pull 0→90 km/h in 2 min).
    val dragMinM: Double = 600.0,
    val dragAccFactor: Double = 4.0,
    val dragSpeedAllowanceMps: Double = 42.0, // 150 km/h — riding between network fixes must not false-trip

    // C7: synthetic track — speed repeated bit-for-bit N fixes in a row
    // (real recording: 25.005072 m/s ×5; an honest chip never does that)
    val frozenSpeedRepeat: Int = 4,
    val frozenSpeedMinMps: Double = 3.0,      // standing still (0.0 repeated) is legitimate

    // C8: GPS time warp. An honest fix carries atomic time; in the real Lima
    // recording of 2026-09-06 the fix time was shifted ~550 days into the future.
    // Generous threshold to tolerate unsynced phone clocks.
    val timeWarpMs: Long = 120_000,

    // FSM
    val spoofConfirmFixes: Int = 2,           // protection against a single outlier
    val recoveryHoldMs: Long = 45_000,        // hysteresis for leaving SPOOFED
    val recoveryConvergeM: Double = 1_000.0,  // GNSS↔network convergence on exit
    val blindAfterNoNetMs: Long = 30_000,
    val blindRecoverM: Double = 5_000.0,      // GNSS↔frozen-position convergence
    val gnssGapAbortMs: Long = 10_000,        // GNSS gap during RECOVERING → back to SPOOFED
    val blindAccuracyGrowMps: Float = 10f,
    val blindAccuracyCapM: Float = 5_000f,
)
