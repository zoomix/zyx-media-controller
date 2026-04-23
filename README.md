# WearOS Media Controller — App Spec

## Overview

A Wear OS app that surfaces transport controls (play/pause, skip, previous, seek, volume) for whatever media session is active on the paired Android phone. No dependency on any specific app — works with Spotify, YouTube Music, Podcasts, VLC, or anything that publishes a proper `MediaSession`.

---

## Goals

- Control any active `MediaSession` on the phone from the watch
- Works like a Bluetooth headset — app-agnostic
- Minimal UI: round-watch-native, glanceable, one-handed operable
- Local dev build + ADB deploy workflow (no Play Store required)

---

## Target Environment

| Item | Value |
|---|---|
| Wear OS version | 3.x (API 30+) |
| Companion phone | Android 8.0+ (API 26+) |
| Language | Kotlin |
| Build system | Gradle (AGP 8.x) |
| Min SDK | 30 |
| Target SDK | 34 |

---

## Architecture

```
[Watch App]
  └── MediaControlViewModel
        ├── connects via Wearable Data Layer → [Phone Service]
        │     └── MediaSessionManager (phone-side)
        │           └── Active MediaController
        └── OR directly via MediaBrowser (if session is bridged)

[Phone Companion Service] (optional but recommended)
  └── MediaSessionListenerService
        ├── Discovers active sessions via MediaSessionManager
        ├── Forwards metadata + playback state to watch via DataClient/MessageClient
        └── Relays commands from watch → MediaController.TransportControls
```

### Communication path

The cleanest approach for Wear OS 3 is a **hybrid**:

1. **Phone-side service** (`MediaSessionListenerService`) holds the `MediaSessionManager` reference, subscribes to active sessions, and owns the `MediaController` instances.
2. **Watch app** communicates with the phone service via the **Wearable Message API** (low latency, fire-and-forget commands) and **Wearable Data API** (synced state: track info, playback position, artwork).
3. Fallback: if the watch OS bridges the session natively (Wear OS 3 does this for some apps), use `MediaBrowser` directly on-watch with no phone service needed.

---

## Modules

### 1. `:phone` — Companion service (runs on phone)

**`MediaSessionListenerService`**
- Extends `Service`, started by the watch via `ChannelClient` or auto-started as a foreground service
- Uses `MediaSessionManager.getActiveSessions()` — requires `MEDIA_CONTENT_CONTROL` (via `NotificationListenerService`) or `NOTIFICATION_LISTENER_SERVICE` permission
- Subscribes to `MediaSessionManager.OnActiveSessionsChangedListener`
- Picks the "best" session (first active, or last-used heuristic)
- Forwards to watch:
  - Track title, artist, album, duration
  - Playback state (playing/paused/buffering/stopped)
  - Current position (polled every 500ms while playing)
  - Album art (resized to 100×100px before sending)
  - Available actions bitmask
- Receives commands from watch and calls `MediaController.TransportControls`:
  - `play()`, `pause()`, `skipToNext()`, `skipToPrevious()`
  - `seekTo(pos: Long)`
  - `setRating()` (optional)
  - `adjustVolume()` via `AudioManager`

**`WatchMessageReceiver`**
- Listens on `/media/command` message path
- Dispatches to active `MediaController`

### 2. `:wear` — Watch app

**`MainActivity`**
- Single `ComponentActivity` with Compose for Wear
- Hosts `NavHost` with two destinations: `PlayerScreen` and `SessionPickerScreen`

**`MediaControlViewModel`**
- Sends commands to phone via `MessageClient.sendMessage("/media/command", ...)`
- Observes state from `DataClient` path `/media/state`
- Exposes `StateFlow<PlayerUiState>`:
  ```kotlin
  data class PlayerUiState(
    val title: String,
    val artist: String,
    val albumArtBitmap: Bitmap?,
    val isPlaying: Boolean,
    val duration: Long,
    val position: Long,
    val availableActions: Long,
    val appName: String,
    val sessionCount: Int
  )
  ```

**`PlayerScreen` (main UI)**  
See UI spec below.

**`SessionPickerScreen`**  
- List of active session names when multiple apps are playing
- Allows manually pinning a session

---

## Permissions

### Phone app (`AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.MEDIA_CONTENT_CONTROL" />
<!-- Granted via Notification Listener, not normal runtime grant -->

<service
  android:name=".MediaSessionListenerService"
  android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
  android:exported="true">
  <intent-filter>
    <action android:name="android.service.notification.NotificationListenerService" />
  </intent-filter>
</service>
```

### Watch app (`AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-feature android:name="android.hardware.type.watch" />
```

**First-run flow:** Watch app detects no phone service connection → deep-links user to phone app → phone app shows Settings → Notification Access onboarding screen.

---

## UI Spec — `PlayerScreen`

Round-watch optimized. Compose for Wear OS (`androidx.wear.compose`).

```
┌─────────────────────────┐
│                         │
│   [Album art, circular] │  ← 80dp circle, centered, top third
│                         │
│   Track Title           │  ← ScalingLazyColumn or fixed text, bold
│   Artist · App name     │  ← secondary text, muted
│                         │
│  ──────────────────     │  ← SeekBar (arc or linear)
│  0:34          3:12     │
│                         │
│   ⏮   ⏯   ⏭           │  ← 3 buttons, centered row
│                         │
│        🔊 ──            │  ← volume, optional, bottom
└─────────────────────────┘
```

- Use `androidx.wear.compose.material.Button` for controls
- `ScalingLazyColumn` for scrollable metadata if title is long
- Seek arc: `androidx.wear.compose.material.PositionIndicator` or custom `Canvas` arc
- Rotary input (crown/bezel): mapped to seek or volume
- Haptic feedback on button press via `WearableHaptics`

**States:**
- `Loading` — spinner, "Connecting…"
- `NoSession` — icon + "Nothing playing"
- `Playing/Paused` — full player UI
- `PhoneDisconnected` — icon + "Phone out of range"

---

## Data Protocol

Commands sent as `ByteArray` over `MessageClient` to path `/media/command`:

```kotlin
enum class MediaCommand(val id: Byte) {
  PLAY(0), PAUSE(1), TOGGLE(2),
  NEXT(3), PREVIOUS(4),
  SEEK(5),          // payload: Long (ms)
  VOLUME_UP(6), VOLUME_DOWN(7),
  SELECT_SESSION(8) // payload: session index Int
}
```

State synced via `DataClient` at path `/media/state` as a serialized protobuf or JSON blob (JSON is simpler for a local dev build):

```json
{
  "title": "Fluorescent Adolescent",
  "artist": "Arctic Monkeys",
  "album": "Suck It and See",
  "appName": "Spotify",
  "isPlaying": true,
  "duration": 178000,
  "position": 34500,
  "actions": 63,
  "artKey": "asset://media_art"
}
```

Album art sent separately as a `DataItem` asset to avoid size limits on the state blob.

---

## Project Structure

```
wearos-media-controller/
├── build.gradle.kts              (root)
├── settings.gradle.kts
├── phone/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/…/
│           ├── MediaSessionListenerService.kt
│           ├── WatchMessageReceiver.kt
│           └── OnboardingActivity.kt
└── wear/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/…/
            ├── MainActivity.kt
            ├── PlayerScreen.kt
            ├── SessionPickerScreen.kt
            ├── MediaControlViewModel.kt
            └── WearDataListener.kt
```

---

## Key Dependencies

```kotlin
// Wear OS Compose
implementation("androidx.wear.compose:compose-material:1.3.x")
implementation("androidx.wear.compose:compose-navigation:1.3.x")
implementation("androidx.wear.compose:compose-foundation:1.3.x")

// Wearable Data Layer
implementation("com.google.android.gms:play-services-wearable:18.x")

// Media (both modules)
implementation("androidx.media:media:1.7.x")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.x")

// Coil for image (watch side)
implementation("io.coil-kt:coil-compose:2.x")
```

---

## Local Dev + ADB Deploy Workflow

### Prerequisites
- Android Studio Hedgehog or newer
- Wear OS emulator **or** physical watch with Developer Options enabled
- ADB over Wi-Fi or USB (watch paired to same machine)

### Enable dev mode on watch
1. Settings → System → About → tap Build Number 7×
2. Settings → Developer Options → ADB Debugging ON
3. Settings → Developer Options → Debug over Wi-Fi ON (note the IP)

### Connect ADB
```bash
adb connect <watch-ip>:5555
adb devices   # confirm watch is listed
```

### Build & deploy
```bash
# Deploy phone companion
./gradlew :phone:installDebug

# Deploy watch app
./gradlew :wear:installDebug

# Or run directly from Android Studio:
# Select 'wear' run config, choose watch device
```

### Logs
```bash
# Watch logs
adb -s <watch-serial> logcat -s MediaControlViewModel WearDataListener

# Phone service logs
adb logcat -s MediaSessionListenerService WatchMessageReceiver
```

### First run checklist
1. Install phone apk
2. Open phone app → grant Notification Listener permission
3. Install wear apk
4. Open watch app → should connect and show "Nothing playing"
5. Start any media on phone → watch should reflect state within ~1s

---

## Known Gotchas

- **`MEDIA_CONTENT_CONTROL`** cannot be granted at runtime — it requires `NotificationListenerService`. The phone app must walk the user through Settings → Notification Access.
- **Session selection**: `getActiveSessions()` returns sessions ordered by recency. Some apps (e.g. YouTube) publish sessions even when idle — filter by `PlaybackState` being non-null and non-`STATE_NONE`.
- **Position tracking**: `MediaController.getPlaybackState().position` is a snapshot. For a live-updating seek bar, interpolate client-side using `SystemClock.elapsedRealtime()` and the state's `lastPositionUpdateTime`.
- **Wear OS emulator** doesn't include Play Services by default — use a physical watch or the "Wear OS with Play Store" emulator image.
- **DataClient size limit**: individual `DataItem` values max out at ~100KB. Keep album art ≤ 80×80px / JPEG quality 70 before sending.
- **Foreground service**: on Android 12+ the phone service needs `FOREGROUND_SERVICE` permission and must be declared as `foregroundServiceType="mediaPlayback"` if it plays audio itself (it doesn't here, but the OS may still require it to stay alive reliably).


