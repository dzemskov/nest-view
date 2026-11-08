# Nest View

A multi-camera baby monitor for Android (targetSdk 36 / Android 16, minSdk 29) that shows your
Nest cameras together, full screen, with mixed audio. Built for watching the kids on phones and on
a tablet on a stand: it opens straight into the cameras and keeps the screen on.

> Not a medical device. It doesn't monitor breathing and doesn't replace checking on your child.

## What works now (milestone 1, and the start of milestone 2)

- **Monitor screen**: every camera of the view, edge to edge, system bars hidden, screen kept on.
  Works in any size and orientation. Rotation and visits to Settings never reconnect a stream.
- **Automatic layout** that picks the arrangement showing the most picture (2 cameras side by side
  in landscape, stacked in portrait, 2×2 for four…). Also: grid 2/3 columns, one large + small,
  side by side, stacked; saved separately for portrait and landscape.
- **Gestures**: tap shows/hides controls (they hide after 4 s), double-tap focuses a camera full
  screen, long-press opens the camera menu, pinch zooms, drag pans.
- **Sharp zoom**: the decoded frame is cropped on the GPU, so zoom stays sharp up to the camera's
  resolution. **Save this zoom** keeps a crop (for example the crib) for every start.
- **Mixed audio**: all cameras play together; per camera mute, volume 0–300 % and *listen to this
  one only*; mute-all in the quick bar; media volume keys.
- **Sound on screen**: a level meter (with the learned background level) and a border that glows
  green → amber → red as sound rises above the room's normal noise. It works while muted.
- **Trust**: a status pill on every tile (`LIVE`, `DELAYED`, `FROZEN`, `RECONNECTING`, `OFFLINE`).
  Freeze detection checks frame age, connection state and pictures that stop changing. Stale
  pictures are greyed and blurred with "Last image 0:42 ago", never shown as live.
- **Reliable streaming**: sessions are extended before they expire; battery cameras (which can't be
  extended) roll over to a fresh session in the background without a gap; automatic reconnect
  with backoff and an immediate retry when the network returns; all Google API calls go through a
  rate limiter that keeps slots for extensions.
- **Instant start**: each camera's last snapshot is shown (grey, "Connecting…") until it's live.
- **Demo mode**: four simulated cameras with sound and occasional crying, to try the app without
  Nest cameras.
- **Background listening**: sound keeps playing with the screen off or another app open. A
  "Listening" notification names the cameras and has *Mute all* and *Stop*; opening the app
  starts listening again. It can be turned off in Settings, which also points to Android's
  battery setting so it keeps running all night. While the app is hidden, video isn't drawn.
- **Setup and settings**: Google sign-in through the Nest permissions page, camera discovery,
  rename/hide cameras, sign out.

## Next milestones

- **M2, baby-monitor smarts**: ~~background listening~~ (done), picture in picture, night mode
  with dim screen, clock and a black "nap" screen that wakes on sound, sound alerts with
  calibration, on-device cry detection (YAMNet), smart volume (noise gate for white-noise
  machines), connection-lost and low-battery alarms, touch lock, several views with shortcuts,
  layout editor, sound history.
- **M3, polish**: event timeline, motion zones, add a device by QR code, snapshots, screen-saver
  mode for the tablet stand, fold awareness, thermostat temperature, Nest cloud events.
- **M4, later**: "Magnify motion" on a focused, zoomed tile, which makes small movements such
  as a blanket rising easier to see (Eulerian / phase-based video magnification on the GPU).
  First test it offline on recorded clips, by day and at night with infrared, and build it only
  if breathing clearly stands out from compression and night-vision noise. It is a way to look
  closer, not a breathing monitor: no breathing rate, no "breathing OK" sign, no alarm.

## Building

Requirements: Android Studio (JDK 21+ bundled), Android SDK platform 37.

```bash
gradlew.bat assembleDebug
```

```bash
gradlew.bat test testDebugUnitTest
```

Connecting real cameras needs a Google Device Access project: see [docs/SETUP.md](docs/SETUP.md).
Without it, use **Try the demo** on the first screen.

## Project layout

| Module | Contents |
|---|---|
| `app` | Activity, navigation, dependency wiring |
| `core/model` | Pure Kotlin models: cameras, views, layouts, audio mix, zoom math, health |
| `core/analysis` | Sound levels, freeze detection, health rules, rate limiting, backoff |
| `core/sdm` | Google sign-in (AppAuth + PKCE, Tink-encrypted tokens), SDM REST client, command rate limiter |
| `core/streaming` | WebRTC sessions, `FeedController` (connect/extend/rollover/reconnect), `StreamingEngine`, demo feed, zoomable renderer |
| `core/data` | DataStore-backed views, camera preferences and camera cache |
| `core/designsystem` | Dark theme, status pill, sound glow, level meter |
| `feature/monitor` | Monitor screen, `LayoutEngine`, tiles, gestures, camera menu |
| `feature/setup` | First-run connection to Google Home, demo |
| `feature/settings` | Cameras, account, demo, about |

## Google API limits that shape the app

- Streams last 5 minutes and must be extended; battery cameras ignore extensions.
- About 10 camera commands per minute per user across all cameras.
- Audio is receive-only: no talking through the camera.
- No playing sounds on the camera, no camera settings, no recorded history.
