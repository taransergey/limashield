package com.limashield.log

import com.limashield.bus.ServiceBus
import java.util.Locale

/**
 * Быстрые полевые маркеры: один тап — метка в логе со снапшотом состояния фильтра.
 * Снапшот критичен для разбора: без него «что-то не так в 14:32» бесполезно.
 */
enum class FieldMarker(val tag: String) {
    MAP_JUMPED("MAP_JUMPED"),     // карта улетела — фильтр пропустил спуф
    FALSE_ALARM("FALSE_ALARM"),   // фильтр в SPOOFED, а GNSS на самом деле честный
    NO_POSITION("NO_POSITION"),   // приложения без позиции
    PROBLEM("PROBLEM"),           // обобщённая проблема (кнопка в нотификации)
    ALL_OK("ALL_OK"),             // контрольная отметка «едет нормально»
    NOTE("NOTE"),                 // произвольная заметка
}

fun logFieldMarker(marker: FieldMarker, note: String? = null) {
    val ui = ServiceBus.ui.value
    val now = System.currentTimeMillis()
    val ctx = buildString {
        append("state=${ui.state}")
        append(" mock=${ui.mockMode}")
        if (ui.peekActive) append(" peek")
        append(" sats=${ui.satsUsed}/${ui.satsTotal}")
        ui.lastGnss?.let {
            append(
                " gnss=%.5f,%.5f±%.0fm v=%.1f age=%ds".format(
                    Locale.US, it.lat, it.lon, it.accuracyM, it.speedMps ?: 0f,
                    (now - it.timeMs) / 1000,
                )
            )
        }
        ui.lastNet?.let {
            append(
                " net=%.5f,%.5f±%.0fm age=%ds".format(
                    Locale.US, it.lat, it.lon, it.accuracyM, (now - it.timeMs) / 1000,
                )
            )
        }
        ui.lastVerdict?.let { append(" lastVerdict=[$it]") }
        ui.gnssSilentSec?.let { append(" gnssSilent=${it}s") }
    }
    val text = buildString {
        append("[MARKER:${marker.tag}]")
        if (!note.isNullOrBlank()) append(" ").append(note.trim())
        append(" | ").append(ctx)
    }
    EventLog.log(EventLog.Level.WARN, text)
}
