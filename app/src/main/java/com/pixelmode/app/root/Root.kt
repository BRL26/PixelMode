package com.pixelmode.app.root

import com.pixelmode.app.data.ModePrefs
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin, cached wrapper around libsu (topjohnwu).
 * All shell work runs on [Dispatchers.IO]; nothing blocks the main thread.
 */
object Root {

    enum class Status { UNKNOWN, GRANTED, DENIED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status.asStateFlow()

    val isGranted: Boolean get() = _status.value == Status.GRANTED

    /** Re-check root (may show the root manager's grant prompt the very first time). */
    fun refresh(onDone: ((Status) -> Unit)? = null) {
        scope.launch {
            val s = evaluate()
            if (onDone != null) withContext(Dispatchers.Main) { onDone(s) }
        }
    }

    /** Blocking evaluation — safe to call from IO threads only. */
    suspend fun evaluate(): Status = withContext(Dispatchers.IO) {
        try {
            Shell.getShell() // opens + caches the shell; prompts for root on first ever use
            val granted = Shell.isAppGrantedRoot() == true
            _status.value = if (granted) Status.GRANTED else Status.DENIED
            if (granted && ModePrefs.initialized) {
                ModePrefs.update { it.copy(rootEverGranted = true) }
            }
        } catch (t: Throwable) {
            _status.value = Status.DENIED
        }
        _status.value
    }

    /**
     * Run commands in a single shell, asynchronously.
     * @param onDone invoked on the main thread with null when the job itself failed.
     */
    fun exec(commands: List<String>, onDone: ((Shell.Result?) -> Unit)? = null) {
        if (commands.isEmpty()) {
            onDone?.let { cb ->
                scope.launch { withContext(Dispatchers.Main) { cb(null) } }
            }
            return
        }
        scope.launch {
            val result = try {
                Shell.cmd(*commands.toTypedArray()).exec()
            } catch (t: Throwable) {
                null
            }
            if (onDone != null) withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** Blocking variant for broadcast receivers running on a worker thread. */
    fun execBlocking(commands: List<String>): Shell.Result? = try {
        if (commands.isEmpty()) null else Shell.cmd(*commands.toTypedArray()).exec()
    } catch (t: Throwable) {
        null
    }
}
