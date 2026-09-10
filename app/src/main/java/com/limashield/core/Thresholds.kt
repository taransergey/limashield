package com.limashield.core

/**
 * Все пороги детекции и FSM в одном месте (ТЗ §3.2, §4).
 * Заполняется из настроек в Prefs.thresholds().
 */
data class Thresholds(
    // К1: расхождение GNSS и сети
    val netDivergenceM: Double = 10_000.0,
    val netFreshMs: Long = 60_000,

    // К2: телепортация
    val teleportM: Double = 100_000.0,
    val teleportDtMs: Long = 60_000,

    // К3: невозможная скорость (300 км/ч — с запасом для мотоцикла, ТЗ §4.3)
    val maxSpeedMps: Double = 83.3,
    val speedConsecutive: Int = 3,

    // К4: зона сигнатуры «Лимы» (bounding box Лима/Перу)
    val signatureZone: BBox = BBox(-18.0, 0.0, -82.0, -68.0),

    // К5: круговое движение
    val circleMinFixes: Int = 10,
    val circleMaxFixes: Int = 20,
    val circleSpeedCvMax: Double = 0.05,
    val circleMinSpeedMps: Double = 20.0,
    val circleMinTurnRateDegS: Double = 0.5,
    val circleMaxTurnRateDegS: Double = 30.0,
    val circleMinTotalTurnDeg: Double = 60.0,

    // К6: «утаскивание» (drag-off) — медленный увод GNSS от сетевой позиции.
    // Порог адаптивный: max(dragMinM, netAcc*dragAccFactor) + возраст_сети * dragSpeedAllowanceMps.
    // Подобран по реальной записи «Лимы» 2026-09-06 (увод 0→90 км/ч за 2 мин).
    val dragMinM: Double = 600.0,
    val dragAccFactor: Double = 4.0,
    val dragSpeedAllowanceMps: Double = 42.0, // 150 км/ч — езда между сетевыми фиксами не ложнит

    // К7: синтетический трек — скорость, повторяющаяся бит-в-бит N фиксов подряд
    // (в реальной записи: 25.005072 м/с ×5; честный чип так не делает)
    val frozenSpeedRepeat: Int = 4,
    val frozenSpeedMinMps: Double = 3.0,      // стоянка (0.0 подряд) — легитимна

    // К8: сдвиг GPS-времени. Честный фикс несёт атомное время; в реальной записи
    // «Лимы» 2026-09-06 время фикса было сдвинуто на ~550 суток вперёд.
    // Порог щедрый — на случай несинхронизированных часов телефона.
    val timeWarpMs: Long = 120_000,

    // FSM
    val spoofConfirmFixes: Int = 2,           // защита от единичного выброса
    val recoveryHoldMs: Long = 45_000,        // гистерезис выхода из SPOOFED
    val recoveryConvergeM: Double = 1_000.0,  // сходимость GNSS↔сеть при выходе
    val blindAfterNoNetMs: Long = 30_000,
    val blindRecoverM: Double = 5_000.0,      // сходимость GNSS↔замороженная позиция
    val gnssGapAbortMs: Long = 10_000,        // разрыв GNSS в RECOVERING → назад в SPOOFED
    val blindAccuracyGrowMps: Float = 10f,
    val blindAccuracyCapM: Float = 5_000f,
)
