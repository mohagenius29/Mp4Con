# HTML2MP4: frame-accurate HTML → MP4 on Android

Converts a self-contained HTML animation (canvas, WebGL/three.js r128, CSS animations, base64 `<audio>`) into an MP4 on the phone.
Never screen-records: time is virtual, so a slow phone gives the same video as a fast one, just slower.

> **Status: written but not compiled or run on a device by the author of this code.** The virtual-clock JS and the audio planner were
> tested off-device (Node / JUnit). Everything touching WebView, PixelCopy, MediaCodec and ffmpeg needs a first run on your phone.
> Expect to read `render.log` (Share log button) and tune once. Section "Known risks" lists the likely spots.

## Build and install
Requirements: Android Studio Ladybug+ (or JDK 17 + Android SDK 35), internet for the first Gradle sync.

```bash
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk (signed with the debug key, installable)
```
Install on your phone:
1. **USB:** enable *Developer options → USB debugging*, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. **No cable:** copy the APK to the phone (Drive/Telegram/USB), open it in Files, allow *Install unknown apps* for that app, tap Install.
3. Open **HTML2MP4**, allow notifications, tap **Use built-in 5 s test sample**, then **Start**.

Only `arm64-v8a` is built (the maintained ffmpeg-kit fork publishes only that ABI): fine for virtually all phones since ~2017.

## Tests
```bash
./gradlew testDebugUnitTest              # audio planner (JVM, no phone)
./gradlew connectedDebugAndroidTest      # renders the 5 s sample on a connected phone and checks duration, audio track, moving frames
```
`assets/sample.html` = canvas bouncing balls + WebGL rotating triangle + CSS spin/slide + an audio clip whose `playbackRate`
oscillates 0.75–1.25 (the page's own logic). Expected result: ≈5 s MP4 (4.6 s audio + 0.4 s tail).

## How it works
| Piece | File |
|---|---|
| Virtual clock replacing `requestAnimationFrame`, `performance.now`, `Date`, `setTimeout/Interval`; stubbed `<audio>` (`currentTime`, `playbackRate`, `play/pause/ended`); CSS/WAAPI animations paused and set to `currentTime = virtualTime - startTime`; overlay-button hiding + auto-click; DPR/viewport lock | `assets/virtual_clock.js` (injected with `WebViewCompat.addDocumentStartJavaScript`) |
| Visible render window sized exactly to the output (a non-touchable Dialog window, so PixelCopy sees only the page); frame loop: step JS → `postVisualStateCallback` → 2 vsyncs → `PixelCopy` → GL upload → H.264 | `MainActivity.openRig`, `Engine.kt` |
| H.264 through an input Surface via EGL with `eglPresentationTimeANDROID` = frameIndex/FPS (exact PTS) | `SegmentEncoder.kt` |
| 10-second cached segments, resume after interruption (cached parts are replayed through the clock *without* capture, then rendering continues) | `Engine.kt` |
| Offline mapping: `three.min.js` r128, Google Fonts CSS → bundled woff2 (Inter, Roboto, Poppins, Montserrat, Playfair Display, Amiri incl. Arabic), custom URL→file mappings in the UI | `OfflineMap.kt`, `assets/offline/` |
| Audio: per-frame source position + rate log → constant-rate segments → ffmpeg `atrim` + `rubberband`/`atempo` (pitch preserved) + `concat` → AAC | `AudioPlanner.kt`, `AudioRenderer.kt` |
| Final MP4: segments + AAC interleaved by timestamp with `MediaMuxer` (no re-encode), saved to `Movies/HTML2MP4` | `Mux.kt`, `Saver.kt` |
| Foreground service + notification + wake lock; screen kept on | `RenderService.kt`, `RenderController.kt` |

End detection: stops 0.4 s after all played audio elements end, or after the manual duration if you enter one. With no audio and
no duration it stops at 10 s (`RenderConfig.fallbackDurationSec`). Hard cap 600 s.

UI stays responsive because the main thread only schedules JS and waits for callbacks; GL upload, H.264 encoding, ffmpeg and muxing run on other threads, with a 3-bitmap pipeline.

## ffmpeg / rubberband
- Default dependency: `dev.ffmpegkit-maintained:ffmpeg-kit-free-71:7.1.5` (Maven Central, LGPL). It has the built-in `atempo` filter, which also preserves pitch; this is what the app uses by default.
- The `rubberband` filter exists only in the maintained fork's **Full GPL** tier, which is a paid `.aar` download (GPL-3.0). If you buy it, put it at `app/libs/ffmpeg-kit-full-gpl.aar`; Gradle then uses it instead, and the app auto-detects `rubberband` and uses it.
- The original `com.arthenica` artifacts no longer resolve for clean builds.

## Known risks (check these first if something is off)
1. **Window size / CSS viewport.** The render window is the exact output size and the app forces `width=<output width>` viewport and `devicePixelRatio=1`. `render.log` prints `page viewport WxH`. If it differs from the output size, layout is scaled; tell me what it printed.
   Landscape/portrait is forced per orientation; phones whose screen is smaller than the output (e.g. 1080p on a 720-wide screen) get a clear error.
2. **Frame settling.** `SETTLE_VSYNCS = 2` in `Engine.kt`. If you see duplicated/stale frames raise it to 3; if all is fine try 1 for speed.
3. **Speed.** Expect roughly 8–20 rendered fps at 720p (each frame needs a paint + PixelCopy). A 60 s clip at 30 fps may take 2–5 minutes.
4. **Not supported:** Web Audio API (`AudioContext`) sound, `<video>` elements, audio from non-embedded URLs (map them in the UI or embed as base64), `loop`ed audio (treated as ended), negative playback rates.
5. **Audio sync.** AAC priming adds ≈20 ms; atempo/rubberband add small edge effects at segment joins (segments are ≤6 frames).
6. **Background.** If you leave the app, rendering pauses at the next frame (the window must be visible for PixelCopy) and continues when you return. The foreground service keeps the process alive.
7. **Android WebView** must support document-start scripts (any reasonably current "Android System WebView").

## Settings you can change in code
`RenderConfig` (tail, max duration, fallback duration), `Engine.SEG_SECONDS` (cache granularity), `SegmentEncoder` bitrate (0.15 bits/pixel/frame, 2–40 Mbps).
