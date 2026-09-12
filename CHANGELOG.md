# Changelog

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
