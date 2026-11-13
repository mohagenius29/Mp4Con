# HTML2MP4: frame-accurate HTML → MP4 on Android

Converts a self-contained HTML animation (canvas, WebGL/three.js r128, CSS animations) into a **video-only** MP4 on the phone.
Never screen-records: time is virtual, so a slow phone gives the same video as a fast one, just slower.

> **Status (v4): edited without a compiler or a device.** The virtual-clock JS was smoke-tested in Node. Everything touching WebView,
> PixelCopy, MediaCodec and MediaMuxer needs a first run on your phone, and the build itself runs in GitHub Actions.
> Expect to read `render.log` (Share log button) and tune once. Section "Known risks" lists the likely spots.

## What's new in v4: video only, saved immediately
- **No audio at all.** Nothing is captured, mixed, uploaded or muxed: the *Upload audio* feature, the embedded-audio extraction, the ffmpeg audio render and the AAC mux are gone. The MP4 has a video track only.
- **Saved as soon as the last frame is encoded.** With one 4 s segment the encoded file is saved as it is; with several they are joined without re-encoding. There is no audio or "Rendering audio" phase any more.
- **Faster.** ffmpeg-kit (about 20 MB of native code) is removed, so the APK is much smaller; no per-segment ffmpeg/mux work competes with the encoder for CPU; the GL texture is allocated once and only updated afterwards; progress updates are throttled (they used to trigger a UI update every frame); the live preview just plays the finished segment files instead of re-muxing them.
- The page's own `<audio>` elements are still stubbed on the virtual clock, only so that (a) the page's logic keeps running and (b) the app can tell when the audio ends, which sets the video length when *Duration* is left blank. The audio itself is never read into the video.

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


## Tests
```bash
./gradlew connectedDebugAndroidTest      # renders the 5 s sample on a connected phone and checks duration, that there is NO audio track, and moving frames
```
`assets/sample.html` = canvas bouncing balls + WebGL rotating triangle + CSS spin/slide + an audio clip whose `playbackRate`
oscillates 0.75–1.25 (the page's own logic; it only decides the length). Expected result: ≈5 s silent MP4 (4.6 s audio length + 0.4 s tail).

## How it works
| Piece | File |
|---|---|
| Virtual clock replacing `requestAnimationFrame`, `performance.now`, `Date`, `setTimeout/Interval`; stubbed `<audio>` (`currentTime`, `playbackRate`, `play/pause/ended`; only to run the page logic and detect its end); CSS/WAAPI animations paused and set to `currentTime = virtualTime - startTime`; overlay-button hiding + auto-click; DPR/viewport lock | `assets/virtual_clock.js` (injected with `WebViewCompat.addDocumentStartJavaScript`) |
| Visible render window sized exactly to the output (a non-touchable Dialog window, so PixelCopy sees only the page); frame loop: step JS → `postVisualStateCallback` → 2 vsyncs → `PixelCopy` → GL upload → H.264 | `MainActivity.openRig`, `Engine.kt` |
| H.264 through an input Surface via EGL with `eglPresentationTimeANDROID` = frameIndex/FPS (exact PTS) | `SegmentEncoder.kt` |
| 10-second cached segments, resume after interruption (cached parts are replayed through the clock *without* capture, then rendering continues) | `Engine.kt` |
| Offline mapping: `three.min.js` r128, Google Fonts CSS → bundled woff2 (Inter, Roboto, Poppins, Montserrat, Playfair Display, Amiri incl. Arabic), custom URL→file mappings in the UI | `OfflineMap.kt`, `assets/offline/` |
| Final MP4: one segment is used as it is, several are joined with `MediaMuxer` (no re-encode); saved to `Movies/HTML2MP4` | `Mux.kt`, `Saver.kt` |
| Foreground service + notification + wake lock; screen kept on | `RenderService.kt`, `RenderController.kt` |

End detection: stops 0.4 s after all played `<audio>` elements end, or after the manual duration if you enter one. With no audio (or an audio whose length can't be read) and
no duration it stops at 10 s (`RenderConfig.fallbackDurationSec`). Hard cap 600 s.

UI stays responsive because the main thread only schedules JS and waits for callbacks; GL upload, H.264 encoding and muxing run on other threads, with a 3-bitmap pipeline.

## Known risks (check these first if something is off)
1. **Window size / CSS viewport.** The render window is the exact output size and the app forces `width=<output width>` viewport and `devicePixelRatio=1`. `render.log` prints `page viewport WxH`. If it differs from the output size, layout is scaled; tell me what it printed.
   Landscape/portrait is forced per orientation; phones whose screen is smaller than the output (e.g. 1080p on a 720-wide screen) get a clear error.
2. **Frame settling.** `SETTLE_VSYNCS = 2` in `Engine.kt`. If you see duplicated/stale frames raise it to 3; if all is fine try 1 for speed.
3. **Speed.** Expect roughly 8–20 rendered fps at 720p (each frame needs a paint + PixelCopy). A 60 s clip at 30 fps may take 2–5 minutes.
4. **Not supported:** `<video>` elements, `loop`ed audio (treated as ended), negative playback rates. Web Audio (`AudioContext`) is irrelevant now since no sound is captured.
5. **Background.** If you leave the app, rendering pauses at the next frame (the window must be visible for PixelCopy) and continues when you return. The foreground service keeps the process alive.
6. **Android WebView** must support document-start scripts (any reasonably current "Android System WebView").

## Settings you can change in code
`RenderConfig` (tail, max duration, fallback duration), `Engine.SEG_SECONDS` (cache granularity), `SegmentEncoder` bitrate (0.15 bits/pixel/frame, 2–40 Mbps).

## Robustness features (v2)
- **Network cache:** fonts (Google Fonts CSS + files), CDN scripts, images and audio files the page downloads are cached in `files/netcache` (max 300 MB). The first render of a page needs internet; later renders work offline. Bundled fonts (Inter, Roboto, Poppins, Montserrat, Playfair Display, Amiri, Cinzel, Cormorant, Lora, Merriweather, Open Sans, Lato, Oswald, Raleway, Nunito, Cairo, Tajawal, Noto Naskh Arabic, Bebas Neue, Dancing Script, Pacifico) work offline from the start.
- **Every declared `@font-face` is force-loaded** before frame 0, so canvas text uses the right font.
- **Start buttons** are found by label in several languages (e.g. "Cheza wimbo"), and as a fallback the first non-pause button is clicked if nothing has played after a few frames. The page is opened with `#render` in the URL for pages that have a render mode.
