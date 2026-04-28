# Wear OS Media Wrist Remote

## Overview

`zyx-media-controller` is a two-module Android project (`:phone` + `:wear`) that lets a Wear OS watch control active media sessions on a paired phone.

- Phone side listens to active media sessions and publishes a normalized state payload.
- Watch side renders that state and sends playback/session-selection commands back to the phone.
- Transport controls are app-agnostic (works with any app exposing a `MediaSession`).

## Quick Test Steps

0. `export JAVA_HOME="$("/usr/libexec/java_home" -v 17)"`
1. Install both debug apps: `./gradlew :phone:installDebug :wear:installDebug`
2. On the phone, open `Media Wrist Remote`.
3. Grant Notification Access for the app when prompted.
4. On the watch, open `Media Wrist Remote` while the phone is nearby and paired.
5. Start playback in any phone media app (Spotify, YouTube Music, VLC, etc).
6. Wait for the watch to move from `Connecting` / `Nothing playing` to the active player.
7. Test `Play/Pause`, `Prev`, `Next`.
8. With multiple active sessions, swipe up/down on artwork to switch sessions and verify page dots/session index update.

If the watch shows `Permission needed`, re-check Notification Access on phone. If it shows `Phone disconnected`, bring the phone closer, reopen the phone app once, then refresh the watch screen.

---

## Current Capabilities

- Active session discovery via phone `NotificationListenerService`
- Watch state sync over Wearable Data Layer (`/media/state`)
- Command dispatch over Wearable Message API (`/media/command`)
- Multi-session carousel with one-based session index switching
- Artwork sync for current item and carousel tiles
- Watch UI states for loading, permission-required, no-session, disconnected, active player
- Per-app skip behavior support (track skip vs seek-style behavior)

---

## Target Environment

| Item | Value |
|---|---|
| Wear OS | 3.x+ (API 30+) |
| Companion phone | Android 8.0+ (API 26+) |
| Language | Kotlin |
| JDK | 17 |
| compileSdk / targetSdk | 34 |
| Gradle modules | `:phone`, `:wear` |

---

## Architecture

```
[Watch app]
  └── MediaControlViewModel
        ├── sends commands over MessageClient (/media/command)
        └── observes state from DataClient (/media/state)
                         │
                         ▼
[Phone app]
  ├── MediaSessionListenerService (active session tracking + state publishing)
  └── WatchMessageReceiver (command receiver + dispatch)
```

The phone app owns session selection and command execution through `MediaController` transport controls/custom actions, then republishes the latest canonical state to the watch.

---

## Data Protocol

Commands are UTF-8 payloads sent to `/media/command`:

- `PLAY`, `PAUSE`, `TOGGLE`
- `NEXT`, `PREVIOUS`
- `SELECT_NEXT_SESSION`, `SELECT_PREVIOUS_SESSION`
- `SELECT_SESSION_INDEX:<one-based-index>`

State is synced on `/media/state` as JSON and includes:

- connection status
- track/app metadata
- playback state + position + available actions
- active `sessionIndex` + `sessionCount`
- cover art and carousel entries

---

## Permissions

Phone:
- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (via `MediaSessionListenerService`)
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.INTERNET` (for some metadata artwork URI fetches)

Watch:
- `android.permission.WAKE_LOCK`
- `android.hardware.type.watch`

---

## Local Dev + Deploy

### Prerequisites

- Android Studio (recent stable)
- Wear OS emulator with Play Store, or physical watch
- JDK 17

### Build and install

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
./gradlew :phone:installDebug :wear:installDebug
```

### Useful logs

```bash
# Watch
adb -s <watch-serial> logcat -s MediaControlViewModel WearDataListener PlayerScreen

# Phone
adb logcat -s MediaSessionListenerService WatchMessageReceiver MediaCommandDispatcher
```

---

## Known Gotchas

- Notification Access is mandatory on phone for session discovery.
- Some apps publish idle/partial sessions; active-session heuristics can vary by app.
- First state updates can contain incomplete artwork while metadata catches up.
- Wear emulator images without Play Services will not support full data layer behavior.
- Keep artwork payloads compact to avoid `DataClient` size issues.
