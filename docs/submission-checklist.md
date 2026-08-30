# Submission checklist

CodeStorm 2026: FutureForge. Hackathon window 1-31 August 2026.
State as of 2026-08-28.

`READY` means verified, not merely present. Nothing is marked READY on the
strength of a plan.

## Official requirements

| # | Item | State | Evidence / what is missing |
|---|---|---|---|
| 1 | **Functional production-level project** | **PENDING BUILD** | All test suites pass; the Android build has not been run against the current source. Explicitly a prototype — see [`limitations.md`](limitations.md) |
| 2 | **Source code** | **READY** | Clean tree, pushed to `github.com/Venugopal1807/RoadLink` |
| 3 | **Documentation** | **READY** | Documents under `docs/`, indexed from the README |
| 4 | **Deployed / working demo** | **PENDING** | Blueprint and Dockerfile added; **no service deployed**. LAN demo path is verified. See [`deployment.md`](deployment.md) |
| 5 | **PPT** | **PENDING** | Slide source written at [`codestorm-presentation.md`](codestorm-presentation.md); the deck file itself must be produced |
| 6 | **Demo video** | **PENDING** | Script at [`demo.md`](demo.md); not recorded |

## Detailed items

| Item | State | Notes |
|---|---|---|
| Source code | **READY** | |
| README | **READY** | Overview, problem, solution, workflow, architecture, stack, setup, demo, testing, BLE status, security, limitations, roadmap |
| Documentation | **READY** | Architecture, setup, deployment, testing, limitations, security, demo, verification log, ADR |
| Backend | **READY (local/LAN)** · **PENDING (public)** | Runs and passes 61 assertions. Not deployed anywhere public |
| APK | **UNKNOWN** | The 2026-08-23 APK built; the 2026-08-28 changes have **never been compiled** - no Android SDK, Google Maven blocked. **No APK exists for the current source.** Run `:app:assembleDebug` |
| Working demo | **READY (procedure)** · **PENDING (rehearsal)** | Script at [`demo.md`](demo.md). Verified EMULATED end to end. Not rehearsed on the demo hardware |
| PPT | **PENDING** | Source written; deck not produced |
| Demo video | **PENDING** | Not recorded |
| GitHub repository | **READY** | `github.com/Venugopal1807/RoadLink`, branch `codestorm-2026`, local HEAD == `origin/codestorm-2026` |
| Testing evidence | **READY** | [`testing.md`](testing.md) and [`verification-log.md`](verification-log.md) |
| Security review | **READY** | [`security.md`](security.md). Threat model, prototype limits stated, no secrets committed |
| Limitations | **READY** | [`limitations.md`](limitations.md) |
| Physical device verification | **NOT DONE** | `adb devices -l` empty on every session so far. No RoadLink build has run on a phone |
| BLE evidence | **NOT DONE** | No RoadLink BLE code has executed on a radio. Every cell in [`physical-test-results.md`](physical-test-results.md) reads NOT YET TESTED |
| Final submission links | **PENDING** | Repository URL exists; demo URL, video URL and deck are outstanding |
| Demo credentials | **NOT REQUIRED** | No accounts, no login, no API keys anywhere in the product |

## Verified test results

Re-run 2026-08-23.

| Suite | Result | Evidence class |
|---|---|---|
| Backend ingestion + idempotency | 61 passed, 0 failed | **VERIFIED** — real HTTP, live uvicorn |
| Android JVM | 88 of 88 passed, 0 failed (2026-08-28) | **VERIFIED** — JVM harness, not the Gradle build |
| Kotlin ↔ Python wire compatibility | PASSED, `sig_valid=true` | **VERIFIED** — real HTTP |
| `:app:assembleDebug` | **NOT RUN** (2026-08-28) | **UNKNOWN** — AGP unresolvable in that environment |
| `:app:assembleRelease` | **NOT RUN** (2026-08-28) | **UNKNOWN** |
| `:app:assembleDebugAndroidTest` | **NOT RUN** (2026-08-28) | **UNKNOWN** |
| `:spike-ble:assembleDebug` | **NOT RUN** (2026-08-28) | **UNKNOWN** |
| Backend start command used by the deploy blueprint | `/healthz` 200, `/api/v1/sos/active` 200 | **VERIFIED** — locally, `PORT=8123` |
| APK carries the configured backend URL | Confirmed on the 2026-08-23 source only | **NOT VERIFIED** for current HEAD — no APK exists |
| Debug variant permits cleartext, release denies it | Merged resources inspected | **VERIFIED** |
| Persistence durability + schema migration | 8 passed, 0 failed | **EMULATED** — not re-run, no device |
| Offline queue → reconnect → delivery | Observed end to end | **EMULATED** |
| Docker image builds and runs | — | **NOT VERIFIED** — Docker daemon unavailable |
| Phone-to-phone BLE over a radio | — | **NOT YET VERIFIED** |
| APK installs and launches on a phone | — | **NOT YET VERIFIED** |
| GPS with a real position fix | — | **NOT YET VERIFIED** — emulator had no fix |
| Battery, background and OEM behaviour | — | **NOT YET VERIFIED** |

## Claims audit

- READY — no accident statistics, road-death figures or golden-hour claims
- READY — no delivery rate, latency, BLE range, detection accuracy, battery or
  user-count numbers
- READY — no medical claim; RoadLink delivers data and does not diagnose or triage
- READY — no ambulance, 112 or ERSS integration claimed
- READY — no guaranteed delivery or nationwide mesh claimed
- READY — crash detection described as a simulated trigger everywhere
- READY — BLE never presented as hardware-validated
- READY — simulated events labelled in UI, logs, signed packet and backend wire path
- READY — no AI or tool attribution in code, docs, UI or commit messages
- READY — no secrets, credentials or machine-specific paths committed

## What must still be done by hand

1. **Record the demo video.** Follow [`demo.md`](demo.md). Scene 4 - killing the
   app while an emergency is undelivered - is the one that carries the claim.
2. **Produce the deck** from [`codestorm-presentation.md`](codestorm-presentation.md).
3. **Install the APK on a phone and launch it.** The single largest untested
   assumption in the submission.
   **Also resolve this:** commit `c1f41e1` says "Found on a physical phone",
   while every document states no build has ever run on one. One of the two is
   wrong, and it is the only place in this repository where the evidence
   discipline contradicts itself. Either record a PHYSICAL section in
   [`verification-log.md`](verification-log.md), or correct the claim.
4. **Deploy the backend** if the portal requires a public URL
   ([`deployment.md`](deployment.md) option A), otherwise use the LAN path.
5. **Run the physical BLE ladder** if two phones become available, or state
   plainly that it is pending. Either is acceptable; silence is not.
6. **Confirm the repository opens** for someone who is not signed in as you.
7. **Submit** through the CodeStorm submission channel.

## Final pre-submission sequence

```bash
# 1. all suites green
cd backend && python tests/test_idempotency.py          # expect 61 passed
cd ../android && ./gradlew :app:testDebugUnitTest --rerun   # expect 88 tests
cd .. && python tools/verify_wire_compat.py             # expect PASSED

# 2. build the demo APK against the backend you will actually use
cd android && ./gradlew :app:assembleDebug -Proadlink.backendUrl=<BACKEND-URL>

# 3. install it and actually open it
adb devices -l
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk

# 4. repository is clean and pushed
git status && git log --oneline -5 && git remote -v
```
