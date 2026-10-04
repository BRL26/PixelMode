# PixelMode

Whole-screen render modes for a **rooted Nothing Phone (3a)** (AMOLED), built with Kotlin +
Jetpack Compose. `minSdk 29` (Android 10), `targetSdk 35`.

| Mode | What it does | Needs root |
|------|--------------|------------|
| **Grayscale** | Rec. 709 luminance grayscale, applied at the compositor level | yes (rootless fallback via `WRITE_SECURE_SETTINGS`) |
| **1-bit B/W** | High-gain threshold so pixels land on pure black/white, with an adjustable threshold slider | yes |
| **Checkerboard** | 1 px on/off grid overlay — exactly half the pixels black, opacity + phase-flip controls | no |
| **Combos** | Grayscale or 1-bit **plus** the checkerboard simultaneously | — |

All of it runs from a **foreground service**, is toggled from a **Quick Settings tile**, is
re-applied after **reboot**, and can always be undone — in-app, from the notification, or with
plain **adb commands** (see [Manual revert](#manual-revert-via-adb)).

---

## Project layout

```
PixelMode/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml            # AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, libsu 6.0.0
├── gradlew / gradlew.bat / gradle/wrapper/
└── app/
    ├── build.gradle.kts                 # minSdk 29, compileSdk 35
    └── src/main/
        ├── AndroidManifest.xml          # permissions, service, tile, receivers
        ├── res/                         # theme, strings, icons
        └── java/com/pixelmode/app/
            ├── PixelModeApp.kt          # crash safety net (never leave a stuck matrix)
            ├── model/ModeState.kt       # persisted configuration
            ├── data/ModePrefs.kt        # SharedPreferences + StateFlow
            ├── data/AppStatus.kt        # runtime status/log for the UI
            ├── root/DisplayCommands.kt  # every shell command + matrix math
            ├── root/Root.kt             # libsu wrapper, cached root status
            ├── root/ColorEngine.kt      # apply/restore with the a→b→c fallback chain
            ├── overlay/CheckerboardView.kt    # native-res bitmap, drawn once
            ├── overlay/OverlayController.kt   # TYPE_APPLICATION_OVERLAY window
            ├── controller/ModeController.kt   # UI/tile → service
            ├── service/ModeService.kt         # foreground service
            ├── tile/ModeTileService.kt        # quick settings tile
            ├── boot/BootReceiver.kt           # BOOT_COMPLETED re-apply
            ├── reset/ResetActionReceiver.kt   # notification "EMERGENCY RESET"
            └── ui/                       # MainActivity + Compose screen + theme
```

---

## Building and installing

```bash
# Android Studio: open the project and Run, or from a terminal:
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK platform 35. The Gradle wrapper (8.10.2) is included.

### First-run setup

1. **Root** — open PixelMode once and accept the root prompt (Magisk / KernelSU / APatch).
   The *Root* card must show `GRANTED`. If it shows `DENIED`, grant the app in your root
   manager and tap **Re-check**. Without root only the checkerboard works (plus the optional
   settings fallback below).
2. **Overlay permission** — tap *Grant* next to **Display over other apps** (required for the
   checkerboard window).
3. **Notifications** — allow them so the persistent foreground-service notice stays visible;
   it carries the **EMERGENCY RESET** action.
4. **Optional, rootless fallback** — grant the secure-settings permission so the grayscale
   daltonizer fallback works even without root:
   ```bash
   adb shell pm grant com.pixelmode.app android.permission.WRITE_SECURE_SETTINGS
   ```

---

## How each mode works

### 1. Grayscale — SurfaceFlinger color matrix (root)

A single binder transaction injects a 4×4 colour matrix into Android's compositor, below all
apps — no overlay, no polling, it persists until reboot:

```bash
service call SurfaceFlinger 1015 i32 1 \
  f 0.212600 f 0.212600 f 0.212600 f 0.000000 \
  f 0.715200 f 0.715200 f 0.715200 f 0.000000 \
  f 0.072200 f 0.072200 f 0.072200 f 0.000000 \
  f 0.000000 f 0.000000 f 0.000000 f 1.000000
```

**Argument format** (verified against `SurfaceFlinger::onTransact`, case `1015`, in the AOSP
sources for Android 10, 15 and 16 — identical in all three):

* `i32 1` is the *apply* flag (`n`); `i32 0` resets the client matrix to identity.
* followed by **16 floats**, read as a **column-major `mat4`**
  (`for i in 0..3: for j in 0..3: m[i][j] = readFloat()` → `flat[col*4 + row]`).
* the last row must be `(0, 0, 0, 1)` — SurfaceFlinger logs an error otherwise (we always
  send it).
* Column 3 is the constant/offset column: `R' = f0·R + f4·G + f8·B + f12`, etc.

The grayscale matrix uses the Rec. 709 luma weights `0.2126 / 0.7152 / 0.0722` — the same
ones AOSP's own `ColorDisplayService` uses for its grayscale matrix.

### 2. Pure 1-bit black & white — tried in order (a) → (b) → (c)

A linear colour matrix can never be a real step function, so:

**(a) Root — high-contrast SurfaceFlinger matrix + grayscale (preferred).**
PixelMode first extracts luma (grayscale) and then amplifies it around the slider's
threshold `t` with a fixed gain of **48×**:

```
out = clamp( 48 · (0.2126·R + 0.7152·G + 0.0722·B) + 0.5 − 48·t )   for R, G and B
```

Every channel gets the same value, so the result is black or white with only a
`±1/48 ≈ ±0.021` luma band in between — visually a hard threshold. The **threshold slider**
moves `t` from 0.05 to 0.95. The UI states the honest caveat: antialiased text edges that fall
inside that narrow band can keep a thin gray fringe.

**(b) Root (or `WRITE_SECURE_SETTINGS`) — Settings.Secure daltonizer keys (fallback).**
If the `service call` is rejected (OEM-restricted SurfaceFlinger, revoked root, …) PixelMode
falls back to the path AOSP itself uses for grayscale:

```bash
settings put secure accessibility_display_daltonizer 0            # 0 = DALTONIZER_SIMULATE_MONOCHROMACY
settings put secure accessibility_display_daltonizer_enabled 1
```

`ColorDisplayService` observes these keys and applies a real grayscale matrix. The
**color-inversion** key (`accessibility_display_inversion_enabled`) is likewise reset to `0`
on every restore. This fallback can only produce *grayscale* — no settings key can hard-cut
luma.

**(c) If neither works** — the UI says so explicitly ("CLEAN THRESHOLD NOT AVAILABLE …
closest result kept") and leaves the screen untouched rather than pretending. The same
honest messaging appears whenever the settings fallback is carrying a 1-bit request.

### 3. Checkerboard — passive overlay (no root, no battery cost)

* A bitmap **exactly the panel's native pixel size**, read at runtime
  (`WindowManager.maximumWindowMetrics` cross-checked with `Display.getRealSize()`), never
  converted through density.
* A 2×2 tile repeated with `BitmapShader(REPEAT, REPEAT)` and `paint.isFilterBitmap = false`
  → exact 1 px on/off grid, nearest-neighbour only, **no scaling, no filtering**.
* "On" pixels are opaque black, "off" pixels transparent → exactly **half the pixels black**.
* Window: `TYPE_APPLICATION_OVERLAY` with
  `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`,
  so it never steals input and covers the status and navigation bars (cutout mode
  `ALWAYS`/`SHORT_EDGES`).
* The bitmap is **drawn once**; it is only regenerated when you flip the phase or rotate.
  The opacity slider maps to `View.alpha` (one RenderNode property, no redraw loop).
* **Rotation**: `ModeService.onConfigurationChanged()` re-creates the window at the new
  native size.

### 4. Combos

The color mode (off / grayscale / 1-bit) and the checkerboard are independent switches —
any combination runs at the same time (the SurfaceFlinger matrix applies to the composited
output, i.e. also to the overlay pixels).

---

## Quick settings tile, service, boot

* **Tile tap** toggles the master switch (`ACTIVE_TILE` + `TileService.requestListeningState`
  keeps it in sync without polling).
* **Tile long-press**: Android itself opens the tile's app on long-press (tiles have no
  long-press callback for third parties), landing on PixelMode's screen where the big red
  **EMERGENCY RESET** button lives. The **notification action** covers the
  reset-without-opening case.
* Everything runs in a `foregroundServiceType="specialUse"` service (`ModeService`).
* `BOOT_COMPLETED` (+ `MY_PACKAGE_REPLACED`) receiver re-applies the mode. The SurfaceFlinger
  matrix is memory-only and resets itself at boot, but the daltonizer **Settings key survives
  reboots** — so when the mode is *off* the receiver still forces the keys back to normal.
* Re-apply is **event-driven only**: state changes, `ACTION_SCREEN_ON`, rotation, and up to 4
  bounded retries (10 s apart) if Magisk isn't ready yet right after boot. **No polling, no
  redraw loop** — idle cost is zero.

## Reset safety (disable / crash)

The matrix must never be able to leave the screen stuck:

1. **Normal disable** → the service writes `service call SurfaceFlinger 1015 i32 0`
   (identity), switches off the daltonizer/inversion keys it may have set, removes the
   overlay, then stops itself.
2. **Emergency reset** (notification action, in-app button or long-press toggle) → forces
   master off and runs the full restore: identity matrix + daltonizer off + inversion off +
   overlay removed.
3. **Service destroyed unexpectedly** → it schedules a safety check 2 s later.
4. **Process crash / kill** → `PixelModeApp.onCreate()` schedules a safety check 1.5 s after
   every process start: if no service is running it either restarts it (mode re-applied) or,
   when the mode is off, immediately restores normal colors.
5. **`START_STICKY`** brings the service back after the system kills it, and the current
   persisted state is re-applied.

> Caveat: if you **force-stop** the app from system settings, Android blocks all its
> services and receivers until you open it again — colors set by root stay until then.
> Use the adb revert below (or just reboot; the matrix is in-memory only).

---

## Root commands used (complete list)

Executed through **libsu** (`com.topjohnwu.libsu`) in one root shell. Commands marked ✦ are
also written directly via `ContentResolver` when the app holds `WRITE_SECURE_SETTINGS`
(no root needed).

| # | Command | Purpose |
|---|---------|---------|
| 1 | `service call SurfaceFlinger 1015 i32 1 f <16 floats>` | set the client color matrix (grayscale / 1-bit) |
| 2 | `service call SurfaceFlinger 1015 i32 0` | reset the client color matrix to identity |
| 3 | ✦ `settings put secure accessibility_display_daltonizer 0` | select grayscale (monochromacy) mode |
| 4 | ✦ `settings put secure accessibility_display_daltonizer_enabled 1` | enable the accessibility color path (fallback b) |
| 5 | ✦ `settings put secure accessibility_display_daltonizer_enabled 0` | disable it again on restore |
| 6 | ✦ `settings put secure accessibility_display_inversion_enabled 0` | make sure colors are not inverted |

All six appear verbatim in `root/DisplayCommands.kt`.

---

## Manual revert via adb

If anything ever looks wrong, run these — they undo everything PixelMode can do, in order:

```bash
# 1) Reset the SurfaceFlinger color matrix to identity (always safe)
adb shell su -c 'service call SurfaceFlinger 1015 i32 0'

# 2) Turn off the accessibility grayscale / daltonizer fallback
adb shell settings put secure accessibility_display_daltonizer_enabled 0
adb shell settings put secure accessibility_display_daltonizer -1      # optional: -1 = disabled

# 3) Make sure color inversion is off
adb shell settings put secure accessibility_display_inversion_enabled 0

# 4) Remove the checkerboard overlay and stop the foreground service
adb shell am force-stop com.pixelmode.app
```

Or as a single root one-liner (step 1 + 2 + 3):

```bash
adb shell su -c 'service call SurfaceFlinger 1015 i32 0; settings put secure accessibility_display_daltonizer_enabled 0; settings put secure accessibility_display_inversion_enabled 0'
```

Notes:

* Step 1 alone fixes a stuck matrix even without root access to settings — the matrix lives
  in SurfaceFlinger's memory, so a **reboot** also clears it.
* Steps 2–3 need either root (`su -c`) or an adb shell (which itself holds
  `WRITE_SECURE_SETTINGS`).
* To confirm the app is gone: `adb shell dumpsys window | grep -i pixelmode` should show no
  overlay window after step 4.

### Other handy commands

```bash
# Optional rootless-fallback permission
adb shell pm grant com.pixelmode.app android.permission.WRITE_SECURE_SETTINGS

# Fresh start of the app's state
adb shell pm clear com.pixelmode.app       # then run the revert commands above if needed
```

---

## Known limitations (deliberately honest)

* **A colour matrix is linear**, so (a) is a *high-gain approximation* of a threshold, not a
  mathematical step. Adjust the slider; a thin gray fringe at antialiased edges is expected.
* **Fallback (b) cannot threshold at all** — it produces clean grayscale. The UI states this
  and keeps the closest result, as required.
* If root is denied and `WRITE_SECURE_SETTINGS` was not granted, **colors cannot be changed
  at all**; the UI says exactly that. The checkerboard overlay still works (it needs only the
  overlay permission).
* Some OEM forks renumber `ISurfaceComposer` transactions. If `1015` silently does nothing
  on a non-AOSP build, PixelMode falls back to (b) — you can test by hand:
  `adb shell su -c 'service call SurfaceFlinger 1015 i32 1 f 0.2126 f 0.2126 f 0.2126 f 0 f 0.7152 f 0.7152 f 0.7152 f 0 f 0.0722 f 0.0722 f 0.0722 f 0 f 0 f 0 f 0 f 1'`
  (screen turns gray) and undo it with `… 1015 i32 0`.
* PixelMode only *sets* the two accessibility keys listed above; if you personally use
  Android's color-correction accessibility feature, the emergency reset will switch it off
  (that is its job: restore normal colors).

## Dependencies

* [libsu](https://github.com/topjohnwu/libsu) 6.0.0 (topjohnwu) — root shell
* Jetpack Compose (BOM 2024.12.01), Material 3
* AndroidX Core / Activity / Lifecycle
