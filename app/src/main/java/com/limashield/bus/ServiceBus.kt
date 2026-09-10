package com.limashield.bus

import com.limashield.core.FilterState
import com.limashield.core.Fix
import com.limashield.core.MockMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Состояние сервиса для UI/тайла (ТЗ §3: StateFlow). Сервис пишет, UI читает. */
object ServiceBus {

    data class Ui(
        val running: Boolean = false,
        val state: FilterState = FilterState.TRUSTED,
        val stateSinceMs: Long = 0,
        val mockMode: MockMode = MockMode.OFF,
        val peekActive: Boolean = false,
        val satsUsed: Int = 0,
        val satsTotal: Int = 0,
        val lastGnss: Fix? = null,
        val lastNet: Fix? = null,
        val mockPermissionOk: Boolean = true,
        val simulating: Boolean = false,
        val passthrough: Boolean = false,
        val lastVerdict: String? = null,
        /** Секунды без GNSS-фиксов при видимых спутниках (подозрение на глушение), null — всё в порядке. */
        val gnssSilentSec: Long? = null,
    )

    val ui = MutableStateFlow(Ui())

    fun update(f: (Ui) -> Ui) = ui.update(f)
}
