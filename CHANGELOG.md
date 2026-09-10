# Changelog

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
