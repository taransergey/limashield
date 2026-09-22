# Changelog

## 0.9.4 — 2026-09-22
- **Update notification**: once a day the app asks the GitHub releases API for the
  latest version and posts a notification (tap → download page) when a newer one
  exists. This is the app's first and only network activity — a single GET, nothing
  else ever leaves the device; off switch in settings. Motivated by testers running
  two-week-old builds and reporting long-fixed bugs.

## 0.9.3 — 2026-09-22
- The service-start log line now carries the app version. A tester's "new bug"
  (map stuck in Lima after enabling the service) took forensic log analysis to
  attribute to a phone still running v0.5.x from September 10 — every shared log
  zip now identifies its build at a glance.

## 0.9.2 — 2026-09-20
A tester's SIM-less phone (no cell positioning at all) got a spoofed Lima fix as its
very FIRST fix of the day: instant C8 detection worked, but with no reference ever
recorded the filter fell into BLIND and stayed there for the whole day, silently
ignoring 38 honest fixes when the spoofing paused.
- **BLIND with no reference now accepts clean GNSS** through the regular
  45 s probation instead of waiting forever for a network fix that cannot come.
- **No-SIM warning**: a phone without a SIM has no cell fallback — the status card
  and the service log now say so and advise enabling Wi-Fi, which partially
  substitutes for cell positioning.

## 0.9.1 — 2026-09-18
First DR field test (two rides, an iPhone recording the reference track in parallel).
The Android chip had ZERO real GNSS fixes all day with 13-41 satellites visible —
genuine L1 suppression the multi-band iPhone survived — so the JAMMED card was
correct. But the track came out worse than v0.8: ColorOS throttles the IMU sensors
with the screen off (gaps of 6-31 min mid-ride), the gyroless EKF honestly blew up
its covariance and froze, and OsmAnd skipped the frozen duplicates — 32 minutes
without a single recorded point while fresh network fixes were available.
- **No live IMU → DR bypasses itself**: with sensor silence over 10 s the service
  pushes the reference fixes directly (exact v0.8 behavior) and re-seeds the engine
  when the IMU returns. DR now only ever runs when it can actually help.
- **A degraded (frozen) prediction never masks a live reference**: with fresh
  network fixes still arriving, the reference is pushed instead of the frozen point.
- Fixed the instant absurd "IMU SILENT for 9.2e15 s" alert right after sensor
  registration, and widened the speed prior for speedless network seeds (a moving
  start looked like an outlier storm: gate/relocate churn).

## 0.9.0 — 2026-09-17
**Dead reckoning** (new DR spec, rev 2): under jamming the network gives a fix once
per 20-30 minutes and the marker used to sit still between them. Now an EKF-CTRV
engine extrapolates motion between rare reference fixes using the gyroscope — the
one sensor EW cannot spoof — and fills the 1 Hz mock stream with distinct, plausible
positions carrying honestly growing accuracy.
- Pure-JVM core (`core/dr/`): 5-state EKF over the CTRV model, gyro yaw rate as the
  main measurement, ROTATION_VECTOR as a weak world-heading anchor (magnetometer
  gated to 25-65 µT — motorcycle electrics), ZUPT stop detector (marker freezes ≤3 s
  after a stop), robust position gate with cell re-bind relocation, honest
  degradation to a frozen marker past the horizon (120 s / ±500 m, configurable).
- `SensorAdapter`: ~50 Hz sensors aggregated to 10 Hz, gyro projected onto the world
  vertical through the 3D orientation (arbitrary mount, lean angle); IMU stream
  recorded to `imu-<day>.csv` next to the raw fix log.
- Integration downstream of the untouched FSM: its emit becomes the DR correction;
  the mock channel, probe windows and watchdogs are unchanged. IMU gets its own
  silence watchdog (10 s) with the same alert + re-register response. Partial
  wakelock while DR is active. Master switch in settings (off = v0.8 behavior).
- Replay bench over real field recordings (thin the trusted fixes, hidden ones are
  the reference): on the 2026-09-13 evening ride, gyro-grade heading cuts the median
  extrapolation error ~4× vs pure kinematics (297 m → 79 m at 30 s masking on
  network-quality references; 52 m on GPS-quality ones).

## 0.8.1 — 2026-09-13
Evening test ride confirmed v0.8.0 end to end (5/5 detections, 6 field recovery
cycles, all three screen-off cutoffs punched through by listener re-registration),
and showed the two remaining rough edges — both fixed:
- **Second deafness canary for the disengaged-mock states**: with the mock off, our
  permanent gps request keeps the GNSS engine on, so satellite-status callbacks must
  flow even with zero fixes. Their staleness now trips the watchdog directly in
  TRUSTED — previously a ride-stop cutoff first grew a fake JAMMED→BLIND before the
  echo canary could see it.
- **Faster punch-through**: listener re-registration every 20 s instead of 60 s
  while deaf (one field episode needed a second attempt and stayed deaf 111 s).

## 0.8.0 — 2026-09-13
Overnight experiment (11 h stationary, OsmAnd recording in parallel, jamming/spoofing
episodes cross-checked against the local air-alert list) uncovered the worst failure
mode so far: the moment the screen went off, ColorOS stopped delivering ALL location
callbacks to the filter — even echoes of its own 1 Hz mock fixes — and resumed only
when the phone was picked up 10.5 hours later. The filter sat silently in BLIND while
OsmAnd, which holds "Allow all the time", kept receiving raw spoofed Lima fixes
(517 track points at ~200 km/h in Peru).
- **Background location permission**: the app now requests "Allow all the time"
  (`ACCESS_BACKGROUND_LOCATION`); setup wizard step 1 walks through both phases.
- **Deafness watchdog**: with the gps mock engaged the filter must hear echoes of its
  own 1 Hz fixes — 45 s of total silence now raises an alert notification (deep link
  to the permission screen), logs the outage, and re-registers all listeners every
  60 s until delivery resumes. A silent 10-hour failure is now a 45-second loud one.

## 0.7.2 — 2026-09-12
- Network silence (the SPOOFED/JAMMED → BLIND countdown) is now measured by fix
  *receive* time: a stationary phone legitimately receives cached network duplicates
  carrying an old fix timestamp, which caused BLIND flapping every ~40 s.

## 0.7.1 — 2026-09-12
- Longer uninterrupted probe windows (escalating up to 4× on consecutive failures,
  reset by any real fix): short choppy windows never let the GNSS chip finish
  acquisition, so the filter saw only "silence" and could not classify the threat.
  Live result: with a 3-minute window the chip locked the spoofed signal and the
  filter flagged SPOOFED instantly (11 ms).
- Satellite telemetry no longer logs frozen snapshots while the mock keeps the real
  GNSS engine off (they poisoned later C/N0 analysis).

## 0.7.0 — 2026-09-12
Fixes from the 2026-09-11 test ride (BLIND for 20-30 min stretches, OsmAnd frozen,
network fixes once per 20-30 minutes, a fair FALSE_ALARM marker from the rider):
- **Peek → Probe**: rarer but much longer reality checks (120 s interval / 45 s window,
  both configurable) — the GNSS engine now gets real time to reacquire, as suggested
  by field experience. A one-shot network request additionally pokes NLP on each probe.
- **Under jamming the probe releases the mock completely**: with the mock engaged GMS
  sees "gps has fixes" and keeps NLP asleep (that's why network fixes came once per
  20-30 min). During an OFF-probe Google location feeds consumers directly and the
  filter gets a fresh network fix. Under spoofing fused stays protected (PARTIAL probe).
- **Network-silence countdown starts at state entry** — no more JAMMED→BLIND within
  one second when the last network fix predates the transition.
- **The filter remembers the hostile cause**: recovering from BLIND during a jamming
  episode returns to the purple JAMMED card, not to "SPOOFING" (the source of the
  rider's FALSE_ALARM).

## 0.6.1 — 2026-09-11
- Setup wizard: step 3 refuses to open developer options before step 1 (location
  permission) — the ColorOS mock-app picker silently ignores the selection otherwise
  (OPPO Reno14 field case); hint text explains the quirk.

## 0.6.0 — 2026-09-10
New JAMMED state, born from the evening test ride (5+ hours of total GNSS
suppression: zero fixes with 26–39 satellites visible):
- **Cell fallback under jamming**: when GNSS produces no fixes for 90 s (configurable)
  while satellites are visible and a network position is available, the filter engages
  the mock and serves the network position — purple "JAMMING — cell fallback" card.
- This also fixes the field-discovered starvation: staying in TRUSTED under jamming,
  the filter's permanent HIGH_ACCURACY gps request made Google location services wait
  for GPS forever, so fused/network consumers (OsmAnd) got almost no positions —
  the rider saw "navigation works only with the filter OFF". Engaging the mock
  releases the real gps provider and NLP wakes up.
- Exit as usual: peek → probation → TRUSTED; spoofed fixes → SPOOFED; network loss → BLIND.
- Toggle and threshold in Settings ("Cell fallback under jamming", "JAMMED after GNSS silence").

## 0.5.2 — 2026-09-10
Hardening after the first external security review:
- **The shield no longer stays silent**: if the gps mock cannot engage (mock access
  revoked/missing) the app shows a red "Protection is NOT active" card, fires a loud
  alert notification and retries every 10 s until it succeeds.
- Fixed a race that could permanently disable the peek mechanism (a queued GNSS fix
  arriving right after mock engagement faked passthrough capability).
- Leftover mock providers are now cleaned up on every app open (a force-killed process
  can leave a frozen test provider in the system).
- Field recording moved off the main thread; log zipping moved to a background dispatcher.
- Machine-specific JDK path removed from the repo (builds no longer break on Linux/Mac).
- GitHub Actions CI: unit tests + debug build on every push.

## 0.5.1 — 2026-09-10
Response to the first ride analysis (ColorOS stopped the filter after 4.5 minutes):
- **Self-restart**: if the service is stopped by the system (not by the user), it
  immediately attempts to bring itself back up.
- If the restart is denied — a **loud "The system stopped the filter!" notification**
  with a Start button (new high-priority alert channel).
- Wizard step 4 (Battery) extended with per-ROM instructions: realme/Oppo (allow
  background activity + auto-launch), Xiaomi (autostart + no battery restrictions).

## 0.5.0 — 2026-09-10 "field day"
A kit for test rides without a computer:
- **On-disk field recording**: events and the raw fix streams of both providers are
  written to daily files (`files/field/`), survive process restarts, kept for 7 days.
  Toggle in Settings → Debug (on by default).
- **One-tap field markers**: "💥 Map jumped", "🤔 False alarm", "📵 No position" — each
  writes a full state snapshot to the log (FSM state, mock mode, both last fixes,
  satellites, last verdict).
- **Markers from the notification shade**: "⚠ Problem" and "✓ OK" actions — without
  opening the app.
- **✍️ Field note**: free-form text into the log from the main screen.
- **Crash reporter**: any app crash stack trace is saved into the field files.
- **Telemetry**: satellites + C/N0 summary every 30 s (groundwork for M5 heuristics),
  battery level every 10 min (drain control).
- **After a phone reboot** — a "Start the filter?" notification (if it was running before).
- **Share log now exports a zip** of all the day's field files + a current-session
  snapshot (previously — a txt with recent events only).

## 0.4.0 — 2026-09-07
- Setup wizard for non-technical users: 4 steps with live checkmarks, buttons deep-link
  into the right system settings screens, hints per ROM (realme/Xiaomi/Samsung/AOSP).
  Opens automatically on first launch and when starting without mock access.

## 0.3.0 — 2026-09-06
Criteria tuned on the first real Lima recordings, caught the same day:
- **C6 drag-off**: adaptive GNSS/network divergence (catches a slow position pull that
  stays under the fixed 10 km threshold).
- **C7 synthetic track**: speed repeated bit-for-bit across 4 consecutive fixes.
- **C8 GPS time warp**: fix time shifted vs system time (real Lima: ~550 days ahead) —
  instant detection, no 2-fix confirmation.
- "GNSS silent while N satellites visible — jamming?" indication.

## 0.2.0 — 2026-09-06
- UI localization: English (default), Ukrainian, Russian; technical log unified to English.
- Log sharing as a file via the system share sheet (Telegram/email).
- New app icon, "From Tarik for motorcycling" footer.

## 0.1.0 — 2026-09-06
- Initial release: foreground filter service, detector C1–C5, four-state FSM
  (TRUSTED/SPOOFED/RECOVERING/BLIND), mock output (gps + fused + FLP), peek mechanism,
  QS tile, threshold settings, spoofing simulator, unit tests for the core.
