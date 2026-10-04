package com.pixelmode.app.model

/** Display-wide color treatment. Independent of the checkerboard overlay, so the two can be combined. */
enum class ColorMode {
    /** No color transform at all. */
    OFF,

    /** Rec.709 luminance grayscale. */
    GRAYSCALE,

    /** Hard 1-bit black/white approximation (high-gain threshold around [ModeState.threshold]). */
    ONE_BIT;

    companion object {
        fun fromName(name: String?): ColorMode =
            entries.firstOrNull { it.name == name } ?: GRAYSCALE
    }
}

/**
 * Persisted configuration. Single source of truth for the UI, the quick-settings tile,
 * the boot receiver and the foreground service.
 */
data class ModeState(
    /** Master on/off toggle. */
    val masterOn: Boolean = false,
    val colorMode: ColorMode = ColorMode.GRAYSCALE,
    /** Luma cut point (0..1) used by [ColorMode.ONE_BIT]. */
    val threshold: Float = 0.5f,
    /** 1px checkerboard overlay; can run together with either color mode. */
    val checkerOn: Boolean = false,
    /** true swaps the phase of the grid (odd/even pixels exchanged). */
    val checkerPhaseFlipped: Boolean = false,
    /** Checkerboard opacity in percent (0..100). */
    val checkerOpacity: Int = 80,
    /** true while *we* have the Settings.Secure daltonizer fallback enabled. */
    val fallbackActive: Boolean = false,
    /** true while the SurfaceFlinger client color matrix may be non-identity. */
    val matrixDirty: Boolean = false,
    /** true once root has been granted at least once (survives process death). */
    val rootEverGranted: Boolean = false,
) {
    /** True when something is actually being rendered. */
    val active: Boolean
        get() = masterOn && (colorMode != ColorMode.OFF || checkerOn)

    val summary: String
        get() = when {
            !active -> "Off"
            else -> buildString {
                append(
                    when (colorMode) {
                        ColorMode.OFF -> "No color mode"
                        ColorMode.GRAYSCALE -> "Grayscale"
                        ColorMode.ONE_BIT -> "1-bit B/W"
                    }
                )
                if (checkerOn) append(" + checkerboard")
            }
        }
}
