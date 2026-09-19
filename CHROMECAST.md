# Chromecast support (Android) — design notes

Work in progress on the `chromecast` branch. Android only; the media_kit
players on other platforms are untouched.

## Constraints that drive the design

1. **Receivers can't be told to software-decode.** The Default Media Receiver
   plays MP4/WebM/HLS/DASH with H.264 (+HEVC/VP9 on some models) and
   AAC/MP3/Opus; it does not play MKV, raw MPEG-TS over HTTP, MPEG-2 video or
   MP2 audio, and HLS/DASH need CORS headers. IPTV streams are mostly exactly
   those. So the *phone* converts: FFmpeg software-decodes anything and
   re-encodes to H.264/AAC HLS, served from a local HTTP server with CORS.
2. **One upstream viewer.** Providers allow a single connection. The phone is
   therefore always the only client of the IPTV server; the Chromecast only
   ever connects to the phone. The local ExoPlayer is released before the
   pipeline starts, probes are never concurrent with playback, and there is no
   "hand the provider URL to the Chromecast" mode (the receiver can open more
   than one connection by itself).

## Architecture

- `cast/CastOptionsProvider.kt` — Cast SDK options (Default Media Receiver).
- `cast/CastManager.kt` — app-scoped: discovery (MediaRouter), session
  (SessionManager), remote media (RemoteMediaClient). Main thread only.
- `cast/CastBridge.kt` ⇄ `lib/cast/cast_bridge.dart` — MethodChannel
  `dev.fredol.open_tv/cast` + EventChannel `.../cast_events` (events are maps
  with a `type` of `routes | session | media | error`).
- Flutter UI: `cast_button.dart` (toolbar icon), `cast_device_dialog.dart`
  (picker; active scan only while open), `cast_controls.dart` (remote),
  `cast_mini_controller.dart` (bar above the bottom nav while connected).
- Entry points: `channel_tile.dart` (`play()` casts instead of playing locally
  while a session is connected; also handles the hand-off from the local
  player), `exo_player.dart` (cast button pops the player and returns a
  `CastHandoff` with the position).

The Cast SDK is used directly rather than `media3-cast`: that module pulls in
Jetpack Compose and hides live-stream flags on `MediaInfo`.

## Phases

- [x] 0 — toolchain + baseline build
- [x] 1 — session, device picker, controls, mini controller, test video
- [x] 2 — local HTTP server (NanoHTTPD, `LocalStreamServer`) with CORS +
      random token path; `CastService` foreground service (Wi-Fi + wake lock);
      `castStream` releases the local player, stops the old pipeline, then
      starts the new one (one upstream connection, enforced by `pipelineMutex`)
- [x] 3 — FFmpeg remux (`-c copy` → HLS). Verified on Waydroid: H.264/AAC TS
      in -> valid 2s HLS segments out.
- [x] 4 — auto mode: `StreamPipeline` starts in REMUX, reads codecs from the
      FFmpeg input dump (no extra probe connection), and restarts in
      TRANSCODE_HW (h264_mediacodec) then TRANSCODE_SW (libx264) if the
      receiver can't play them. Verified on Waydroid: MPEG-2/MP2 -> H.264 High/
      AAC-LC (software path; HW path needs real hardware). Audio-only transcode
      when video is already H.264. Pipeline status + FFmpeg log surfaced to the
      cast controls screen.
- [ ] 5 — remaining: user settings (mode/quality/HW toggle); VOD seek beyond
      the live window via `-ss` restart; codec caching per channel; measure
      real-device transcode speed & tune; audio-track switching.

## FFmpeg dependency

`com.antonkarpenko:ffmpeg-kit-https-gpl:2.2.1` (FFmpeg 8.1, GPL: libx264 +
mediacodec + gnutls, 16 KB aligned) - a maintained fork of the retired
`com.arthenica` ffmpeg-kit, on Maven Central. Adds ~4 native libs per ABI.
`packagingOptions { jniLibs.pickFirsts += "**/libc++_shared.so" }` resolves the
clash with Flutter's copy.

## Diagnosing playback without adb

The local ExoPlayer shows its last load/playback error on screen while it is
buffering or reconnecting ("HTTP 403 — retrying (4)", "Connection refused",
…). Loads that fail are retried internally up to 999 times without reaching
`onPlayerError`, so without this the only symptom of a refused stream is an
endless spinner. If a channel spins forever in the fork but plays in the store
app, that line says what the provider answered.

`StreamPipeline.stop()` returns false when FFmpeg is still running after a
per-session cancel and a global one; `CastManager` then refuses to start
another pipeline (that would be a second upstream connection) and reports
"FFmpeg did not stop; force-stop the app…". Mid-run restarts (remux →
transcode, ready timeout) get the same watchdog.

## Debugging the pipeline without a Chromecast (debug builds)

    adb shell am broadcast -a dev.fredol.open_tv.CAST_DEBUG -p dev.fredol.open_tv.dev \
        --es url "http://.../stream.ts" --ez live true [--es mode remux|hw|sw]
    adb shell am broadcast -a dev.fredol.open_tv.CAST_DEBUG -p dev.fredol.open_tv.dev --es cmd log
    adb shell am broadcast -a dev.fredol.open_tv.CAST_DEBUG -p dev.fredol.open_tv.dev --es cmd stop

`CastDebugReceiver` (debug source set) drives `StreamPipeline` with no Cast
session and prints the local playlist URL to logcat tag `CastDebug`.

## Building

```
export PATH="$HOME/flutter/bin:$PATH" ANDROID_HOME="$HOME/Android/Sdk"
cd flutter && flutter build apk --debug --target-platform android-arm64 \
  --android-skip-build-dependency-validation
adb logcat -s CastManager ExoPlayerDiag
```

Debug builds use the application id `dev.fredol.open_tv.dev`
(`applicationIdSuffix`), so they install next to the Play Store app; release
builds keep `dev.fredol.open_tv`. No binaries are published from this branch:
build it yourself, or wait for it to land upstream.
