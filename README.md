# LimaShield — GPS anti-spoofing filter for Android

*From Tarik for motorcycling.* UI: English (default), Ukrainian, Russian — follows the system locale. The technical log is English-only: a single language keeps shared field logs easy to analyze.

A system-wide filter against GNSS spoofing by electronic-warfare systems (the "Lima" pattern): it detects signal forgery, automatically switches the whole phone to network positioning (cell towers / Wi-Fi) through Android mock location providers, and freezes the last trusted position when no trusted source is left. Every navigation app (OsmAnd, Google Maps, Waze) receives filtered coordinates with zero changes on their side.

## Building

- JDK 17 (`gradle.properties` → `org.gradle.java.home`, adjust the path if needed)
- Android SDK (path in `local.properties`)

```
gradlew :app:testDebugUnitTest   # detector & FSM unit tests
gradlew :app:assembleDebug       # APK
gradlew :app:installDebug        # install on a connected phone
```

Prebuilt APKs: see [Releases](../../releases).

## Phone setup (onboarding)

The first launch opens a **setup wizard** — 4 steps with live checkmarks; buttons deep-link
into the right settings screens, with hints tailored to the ROM (realme/Oppo, Xiaomi,
Samsung, AOSP). It is also available via the "Mock access" button.

The steps it walks through:
1. Location permission (+ notifications).
2. Developer mode: About phone → tap "Build number" 7 times.
3. Mock location provider: developer options → "Select mock location app" → LimaShield.
   Via adb: `adb shell appops set com.limashield android:mock_location allow`
4. Battery optimization exemption (recommended). On realme/ColorOS also allow
   background activity and auto-launch — the stock killer is aggressive.

Then hit the Start toggle; the Quick Settings tile toggles the filter with one tap.

## Architecture

```
LocationFilterService (foreground, type=location)
  GPS_PROVIDER (1 s)    ──┐
  NETWORK_PROVIDER (5 s)  ├─► SpoofDetector (C1–C8) + FilterFsm ─► MockOutput (gps, fused, FLP)
  GnssStatus (satellites)──┘            │
                                        ▼
                EventLog (ring 500) + FieldRecorder (daily files) + ServiceBus ─► UI / QS tile
```

The core (`core/`) is pure Kotlin with no Android imports: `SpoofDetector`, `FilterFsm`,
`Thresholds`, `GeoMath` — covered by unit tests on synthetic fix streams (`app/src/test`).

### States

| State | What the system receives | Mock |
|---|---|---|
| `TRUSTED` | nothing — apps read the real providers | off |
| `SPOOFED` | network fixes with honest accuracy | gps+fused |
| `RECOVERING` | network fixes; gps already released (45 s probation) | fused |
| `BLIND` | frozen position, accuracy grows +10 m/s up to 5000 m | gps+fused |

### Detection criteria (any one ⇒ SPOOFED after 2 consecutive fixes)

1. **C1** — GNSS vs network divergence > 10 km with a network fix younger than 60 s
2. **C2** — teleport: > 100 km with dt < 60 s
3. **C3** — impossible speed: > 300 km/h on ≥3 consecutive fixes
4. **C4** — fix inside the Lima/Peru bounding box while the trusted position is outside it
5. **C5** — circular motion: near-constant speed (CV < 5%) + monotonic bearing turn
6. **C6** — drag-off: adaptive divergence — `max(600 m, 4 × network accuracy) + fix age × 42 m/s`.
   Tuned on a real recording of a slow pull (0→90 km/h over 2 minutes)
7. **C7** — synthetic track: speed identical bit-for-bit on 4 consecutive fixes at > 3 m/s
   (a real chip never repeats floats; the real Lima froze 25.005072 m/s for 5 fixes)
8. **C8** — GPS time warp: fix time differs from system time by > 2 minutes
   (the real Lima sends GPS time ~550 days in the future). Fires **instantly**, no 2-fix confirmation

C2–C5, C7 and C8 work with no internet: detection never depends on connectivity —
only the fallback position quality does.

## Deliberate deviations from the original spec

1. **Mock is engaged only in SPOOFED/BLIND, not permanently.** An enabled `gps` test
   provider on modern Android *fully replaces* the real GPS — including for the filter
   itself (`gps provider request = OFF`, verified via dumpsys). So TRUSTED passthrough
   is implemented as mock-off: the system already lives on real GNSS. Bonus: zero
   overhead in peacetime, and banking apps never see `isFromMockProvider` while the
   signal is clean.
2. **The `network` provider is never mocked.** It is not spoofable and is the filter's
   only trusted input — mocking it would blind the filter while giving consumers the
   exact same coordinates.
3. **The peek mechanism.** While gps is mocked the real GNSS is invisible, so every 45 s
   (configurable) the filter releases the gps mock for ≤10 s and samples the real signal.
   Under active spoofing the mock returns 1–3 s after the first fix; fused consumers
   (Google Maps) are not affected at all. If the ROM keeps delivering real fixes under
   an active mock (some do), peeking disables itself.
4. **RECOVERING** is an explicit fourth state (the exit probation), which the original
   spec kept implicit inside the hysteresis.

## Testing without EW

Settings → Debug → **Spoofing simulation**: the GNSS input is replaced with a circle over
Lima at ~200 km/h. Expected: detection within ≤2 s, the map in OsmAnd/Google Maps stays
on the network position, the log shows `TRUSTED → SPOOFED: C1/C2/C4`. After switching it
off — recovery through peek + 45 s probation.

On the emulator: `adb emu geo fix` or GPX playback in Extended Controls.

## Field notes

- BLIND in the middle of nowhere with no coverage is normal, not a bug
  (NETWORK_PROVIDER needs connectivity).
- With the mock engaged some banking apps may refuse to work
  (`isFromMockProvider`) — quick kill switch in the QS tile; in TRUSTED the mock is off anyway.
- **Share log** on the main screen builds a zip of the day's field files (events, raw fix
  streams of both providers, crash reports, a snapshot of the current session) and opens
  the system share sheet. The raw stream is duplicated to logcat, tag `LimaShieldRaw`.
- One-tap field markers ("map jumped", "false alarm", "no position", plus "problem"/"OK"
  right in the notification) write a full state snapshot into the log — usable with gloves on.
- Vendor killers: on realme/ColorOS the standard battery exemption is **not enough** —
  also allow background activity and auto-launch, otherwise the system may stop the
  service minutes after the screen goes off (observed in the field). If that happens the
  filter restarts itself and raises a loud alert when it can't.
- Known limitation: in BLIND, moving > 5 km away from the frozen position with no network
  keeps the FSM waiting for a network fix (threshold configurable).

## Milestones

- [x] M1 — skeleton: foreground service, both fix streams, log, notification
- [x] M2 — detector C1–C5 + FSM + unit tests for all spec scenarios
- [x] M3 — mock output gps/fused/FLP, onboarding, spoofing simulator
- [x] M4 — QS tile, threshold settings, log sharing, en/uk/ru localization
- [x] M5 — field kit: daily on-disk recording, one-tap markers, telemetry (C/N0, battery);
      thresholds tuned on real Lima recordings — C6, C7 and C8 were born from them
- [ ] Next: C/N0-based jamming heuristics (the loud-noise signature: ~40 dB-Hz with
      0 satellites used), release signing

## License

[MIT](LICENSE)
