# Submission checklist

Prasunethon 2.0, Round 2. Verified 2026-08-23 unless a row says otherwise.

Legend: **[x]** done and checked · **[ ]** outstanding · **[~]** done with a
caveat stated in the row.

## Build and run

- [x] **Android build works** — `:app:assembleDebug` BUILD SUCCESSFUL
- [x] **Instrumented test APK builds** — `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL
- [x] **Spike harness still builds** — `:spike-ble:assembleDebug` BUILD SUCCESSFUL
- [x] **Backend starts** — `uvicorn app.main:app`, `/healthz` returns ok
- [x] **README works from a clean checkout** — prerequisites, backend, app,
      LAN IP override and troubleshooting are in [`setup.md`](setup.md)
- [ ] **APK built for the demo hardware** — must be rebuilt with
      `-Proadlink.backendUrl=http://<LAN-IP>:8000`; the default targets the
      emulator loopback

## Tests

- [x] **Backend** — 61 passed, 0 failed
- [x] **Android JVM** — 60 passed, 0 failed
- [x] **Cross-language wire check** — PASSED, `sig_valid=true` over real HTTP
- [~] **Android instrumented** — 8 passed, 0 failed, but **EMULATED**
      (AVD `roadlink_test`, API 35). No device was attached during this audit
- [ ] **Physical BLE ladder (T1–T11)** — NOT RUN. Explicitly documented as
      pending in [`physical-test-results.md`](physical-test-results.md), with
      every result cell empty

## Documentation

- [x] Project status — [`current-project-status.md`](current-project-status.md)
- [x] Architecture — [`architecture.md`](architecture.md)
- [x] Setup — [`setup.md`](setup.md)
- [x] Demo script — [`demo-script.md`](demo-script.md)
- [x] Testing — [`testing.md`](testing.md)
- [x] Limitations — [`limitations.md`](limitations.md)
- [x] Security — [`security.md`](security.md)
- [x] Decision record — [`decisions/ADR-002-transport-abstraction.md`](decisions/ADR-002-transport-abstraction.md)
- [x] Evidence log — [`verification-log.md`](verification-log.md)
- [x] Physical test procedure and empty results sheet
- [x] **Architecture diagram** — ASCII, in `architecture.md`. Redraw as a slide
      graphic for the deck

## Claims audit

- [x] **No invented statistics.** No road-death figures, golden-hour claims,
      delivery percentages, latency numbers, BLE range, detection accuracy,
      battery figures or user counts appear anywhere in the repository
- [x] **No medical claims.** RoadLink delivers data; it does not diagnose,
      triage or dispatch
- [x] **No claimed government or emergency-service integration**
- [x] **BLE is never claimed as hardware-validated.** README, ADR-002, the
      verification log and the app's own UI all state it is unproven
- [x] **Simulated events are labelled** in the UI, the log, the signed packet and
      the backend's `first_delivery_path` (`simulated_relay`, never `ble_relay`)
- [x] **Every recorded number carries its environment** — EMULATED results are
      marked EMULATED

## Code hygiene

- [x] **No AI or tool attribution artifacts** in source, comments, docs, strings
      or commit messages
- [x] **No API keys, tokens, passwords or private keys** in tracked files
- [x] **No personal credentials.** The only key-shaped string is the
      self-describing prototype HMAC key, documented in
      [`security.md`](security.md)
- [x] **No machine-specific paths** in tracked files
- [x] **Local tooling state is gitignored** (`.claude/settings.local.json`,
      `.idea/`, `local.properties`)
- [x] **No build outputs, APKs, databases or emulator artifacts committed** —
      `git status` clean, `build/`, `*.apk`, `*.db` all ignored
- [x] **Third-party licences preserved.** No vendored third-party source; all
      dependencies come from Gradle and pip
- [x] **Debug endpoints are environment-gated** (`ROADLINK_ALLOW_RESET`)

## Demo

- [x] **Demo flow works** — Scenario B (offline queue → reconnect → automatic
      delivery) verified EMULATED end to end
- [x] **Demo is deterministic** — dev switches and a transport pin avoid
      depending on aeroplane mode or live radio behaviour
- [x] **Demo has an honest BLE fallback** — [`demo-script.md`](demo-script.md)
      Scene 6 gives both the validated and the not-validated wording
- [ ] **Demo video recorded**
- [ ] **Demo rehearsed end to end on the actual demo hardware**

## Outstanding — needs a human

- [ ] **Presentation deck (PPT)** — not in the repository. Source material:
      `architecture.md` for the diagram and the invariant,
      `current-project-status.md` for the evidence table,
      `limitations.md` for the honest boundaries
- [ ] **Demo video** — record against `demo-script.md`. Scenes 1, 3, 4, 5 and the
      close are the minimum that carries the claim
- [ ] **Physical BLE validation** — or an explicit statement that it is pending.
      Either outcome is acceptable; silence is not
- [ ] **Project/demo URL verified**
- [ ] **Source repository URL verified** — confirm the remote is set, pushed, and
      reachable by a judge
- [ ] **Google Form submission prepared**
- [ ] **Prasunet portal submission prepared**

## Judging criteria — where each is answered

| Criterion | Where |
|---|---|
| Working project / demo | `demo-script.md`, and the app itself |
| Source code | This repository |
| Documentation | `docs/`, indexed from `README.md` |
| Clear setup instructions | `setup.md` |
| Architecture explanation | `architecture.md`, `decisions/ADR-002` |
| Technical implementation | `architecture.md` |
| Testing evidence | `testing.md`, `verification-log.md` |
| Limitations | `limitations.md` |
| Security | `security.md` |
| Scalability | `limitations.md`, "Scaling, if it were taken further" |
| Real-world impact | `README.md` opening, `architecture.md` "The problem being solved" |

## Final pre-submission sequence

```bash
# 1. everything green
cd backend && python tests/test_idempotency.py
cd ../android && ./gradlew :app:testDebugUnitTest --rerun
cd .. && python tools/verify_wire_compat.py

# 2. build the demo APK against the demo network
cd android && ./gradlew :app:assembleDebug -Proadlink.backendUrl=http://<LAN-IP>:8000

# 3. nothing unintended is staged
git status
git log --oneline -10
```

Commit only after the suites pass. Do not weaken a test to get there.
