package com.pixelmode.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Runtime status shown in the UI (never persisted). */
data class Status(
    /** Which mechanism is currently driving the screen colors. */
    val colorMethod: String = "—",
    /** Requirement (c): honest message when no clean threshold is possible. */
    val warning: String? = null,
    val overlayActive: Boolean = false,
    val overlayError: String? = null,
    val log: List<String> = emptyList(),
)

object AppStatus {

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state.asStateFlow()

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun update(transform: (Status) -> Status) {
        _state.value = transform(_state.value)
    }

    fun log(line: String) {
        val stamp = LocalTime.now().format(time)
        update { it.copy(log = (listOf("$stamp  $line") + it.log).take(40)) }
    }
}
