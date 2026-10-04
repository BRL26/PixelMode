package com.pixelmode.app.data

import android.content.Context
import android.content.SharedPreferences
import com.pixelmode.app.model.ColorMode
import com.pixelmode.app.model.ModeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted mode configuration. Must be initialised from [com.pixelmode.app.PixelModeApp]
 * before anything else touches it (service, tile, boot receiver all read it).
 */
object ModePrefs {

    private const val FILE = "pixelmode_prefs"
    private const val K_ON = "master_on"
    private const val K_COLOR = "color_mode"
    private const val K_THRESHOLD = "threshold"
    private const val K_CHECKER = "checker_on"
    private const val K_PHASE = "checker_phase_flipped"
    private const val K_OPACITY = "checker_opacity"
    private const val K_FALLBACK = "fallback_active"
    private const val K_DIRTY = "matrix_dirty"
    private const val K_ROOT = "root_ever_granted"

    private lateinit var sp: SharedPreferences

    private val _state = MutableStateFlow(ModeState())
    val state: StateFlow<ModeState> = _state.asStateFlow()

    val initialized: Boolean get() = ::sp.isInitialized

    fun init(context: Context) {
        if (initialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = ModeState(
            masterOn = sp.getBoolean(K_ON, false),
            colorMode = ColorMode.fromName(sp.getString(K_COLOR, ColorMode.GRAYSCALE.name)),
            threshold = sp.getFloat(K_THRESHOLD, 0.5f),
            checkerOn = sp.getBoolean(K_CHECKER, false),
            checkerPhaseFlipped = sp.getBoolean(K_PHASE, false),
            checkerOpacity = sp.getInt(K_OPACITY, 80),
            fallbackActive = sp.getBoolean(K_FALLBACK, false),
            matrixDirty = sp.getBoolean(K_DIRTY, false),
            rootEverGranted = sp.getBoolean(K_ROOT, false),
        )
    }

    fun update(transform: (ModeState) -> ModeState) {
        check(initialized) { "ModePrefs.init() must be called first" }
        val next = transform(_state.value)
        _state.value = next
        sp.edit()
            .putBoolean(K_ON, next.masterOn)
            .putString(K_COLOR, next.colorMode.name)
            .putFloat(K_THRESHOLD, next.threshold)
            .putBoolean(K_CHECKER, next.checkerOn)
            .putBoolean(K_PHASE, next.checkerPhaseFlipped)
            .putInt(K_OPACITY, next.checkerOpacity)
            .putBoolean(K_FALLBACK, next.fallbackActive)
            .putBoolean(K_DIRTY, next.matrixDirty)
            .putBoolean(K_ROOT, next.rootEverGranted)
            .apply()
    }
}
