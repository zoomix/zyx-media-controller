# WearOS Media Controller

## Overview

A Wear OS app that surfaces transport controls for whatever media session is active on the paired Android phone. The current repository contains a working MVP: phone-side media session discovery, watch-side state mirroring, and core play/pause/previous/next controls over the Wearable Data Layer.

## Quick Test Steps

0. `export JAVA_HOME="$("/usr/libexec/java_home" -v 17)"`
1. Install both debug apps: `./gradlew :phone:installDebug :wear:installDebug`
2. On the phone, open the `Media Controller Phone` app.
3. On the phone, tap the Notification Access button and enable access for the app in Android Settings.
4. Return to the phone app and confirm it reports that the bridge is ready.
5. On the watch, open the `Media Controller Wear` app while the phone is nearby and paired.
6. Start playback in any media app on the phone, such as Spotify, YouTube Music, or VLC.
7. Wait a moment for the watch to move from `Connecting` or `Nothing playing` into the active player view.
8. On the watch, test `Play/Pause`, `Prev`, and `Next`, and confirm the phone's media app responds.
9. If the watch shows `Permission needed`, re-check Notification Access on the phone. If it shows `Phone disconnected`, bring the phone closer, reopen the phone app once, and refresh the watch screen.

---

## Goals

- Control any active `MediaSession` on the phone from the watch
- Works like a Bluetooth headset — app-agnostic
- Minimal UI: round-watch-native, glanceable, one-handed operable
- Local dev build + ADB deploy workflow (no Play Store required)

## MVP Status

Implemented in this repo today:
- Root Gradle project with `:phone` and `:wear` modules
- Phone-side `NotificationListenerService` that picks the active `MediaController`
- Wearable Data Layer sync for metadata, playback state, position, and actions
- Wear player screen with loading, permission-needed, no-session, disconnected, and active-player states
- Core commands: play/pause toggle, previous, next

Planned follow-up work:
- Seek support
- Session picker / pinning
- Album art sync
- Volume controls
- Native on-watch `MediaBrowser` fallback

---

## Target Environment

| Item | Value |
|---|---|
| Wear OS version | 3.x (API 30+) |
| Companion phone | Android 8.0+ (API 26+) |
| Language | Kotlin |
| Build system | Gradle 8.7 + AGP 8.5.2 |
| JDK | 17 |
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
- Extends `NotificationListenerService`
- Uses `MediaSessionManager.getActiveSessions()` — requires `MEDIA_CONTENT_CONTROL` (via `NotificationListenerService`) or `NOTIFICATION_LISTENER_SERVICE` permission
- Subscribes to `MediaSessionManager.OnActiveSessionsChangedListener`
- Picks the "best" session (first active, or last-used heuristic)
- Forwards to watch:
  - Track title, artist, app name, duration
  - Playback state (playing/paused/buffering/stopped)
  - Current position (polled every 1000ms while playing)
  - Available actions bitmask
- Receives commands from watch and calls `MediaController.TransportControls`:
  - `play()`, `pause()`, `skipToNext()`, `skipToPrevious()`
  - `seekTo(pos: Long)` and volume are deferred until after MVP

**`WatchMessageReceiver`**
- Listens on `/media/command` message path
- Dispatches to active `MediaController`

### 2. `:wear` — Watch app

**`MainActivity`**
- Single `ComponentActivity` with Compose for Wear
- Hosts the MVP `PlayerScreen`

**`MediaControlViewModel`**
- Sends commands to phone via `MessageClient.sendMessage("/media/command", ...)`
- Observes state from `DataClient` path `/media/state`
- Exposes `StateFlow<PlayerUiState>`:
  ```kotlin
  data class PlayerUiState(
    val title: String,
    val artist: String,
    val appName: String,
    val isPlaying: Boolean,
    val duration: Long,
    val position: Long,
    val availableActions: Long,
    val isPhoneReachable: Boolean,
    val hasLoadedState: Boolean,
    val connectionStatus: MediaConnectionStatus?
  )
  ```

**`PlayerScreen` (main UI)**  
See UI spec below.

**`SessionPickerScreen`**  
- Not implemented in the MVP yet

---

## Permissions

### Phone app (`AndroidManifest.xml`)
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

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
  "connectionStatus": "READY",
  "title": "Fluorescent Adolescent",
  "artist": "Arctic Monkeys",
  "appName": "Spotify",
  "isPlaying": true,
  "duration": 178000,
  "position": 34500,
  "actions": 63
}
```

The MVP keeps the state blob JSON-only for speed of iteration. Album art is still planned as a follow-up.

---

## Project Structure

```text
zyx-media-controller/
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradle/wrapper/
├── settings.gradle.kts
├── phone/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/zyx/mediacontroller/phone/
│       │   ├── MediaControllerRegistry.kt
│       │   ├── MediaProtocol.kt
│       │   ├── MediaSessionListenerService.kt
│       │   ├── MediaStatePublisher.kt
│       │   ├── NotificationAccess.kt
│       │   ├── OnboardingActivity.kt
│       │   └── WatchMessageReceiver.kt
│       └── res/values/strings.xml
└── wear/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/zyx/mediacontroller/wear/
        │   ├── MainActivity.kt
        │   ├── MediaControlViewModel.kt
        │   ├── MediaProtocol.kt
        │   ├── PlayerScreen.kt
        │   └── WearDataListener.kt
        └── res/values/strings.xml
```

---

## Key Dependencies

```kotlin
// Wear OS Compose
implementation("androidx.wear.compose:compose-material:1.3.1")
implementation("androidx.wear.compose:compose-navigation:1.3.1")
implementation("androidx.wear.compose:compose-foundation:1.3.1")

// Wearable Data Layer
implementation("com.google.android.gms:play-services-wearable:18.2.0")

// Media (both modules)
implementation("androidx.media:media:1.7.0")

// Compose compiler / Kotlin
// Kotlin 1.9.24 + Compose compiler extension 1.5.14
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
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"

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


