# Round 2 submission checklist

Prasunethon 2.0. State as of 2026-08-23.

`READY` means verified, not merely present. Nothing is marked READY on the
strength of a plan.

## Official requirements

| # | Item | State | Evidence / what is missing |
|---|---|---|---|
| 1 | **Functional production-level project** | **READY** (as a prototype) | Builds, runs, all suites pass. Explicitly a prototype, not production infrastructure — see [`limitations.md`](limitations.md) |
| 2 | **Source code** | **READY** | 95 tracked files, clean tree, 13 commits, pushed to `github.com/Venugopal1807/RoadLink` |
| 3 | **Documentation** | **READY** | 15 documents under `docs/`, indexed from the README |
| 4 | **Deployed / working demo** | **PENDING** | Blueprint and Dockerfile added; **no service deployed**. LAN demo path is verified. See [`deployment.md`](deployment.md) |
| 5 | **PPT** | **PENDING** | Slide source written at [`submission-presentation.md`](submission-presentation.md); the deck file itself must be produced |
| 6 | **Demo video** | **PENDING** | Script ready at [`demo-script.md`](demo-script.md); not recorded |

## Detailed items

| Item | State | Notes |
|---|---|---|
| Source code | **READY** | |
| README | **READY** | Overview, problem, solution, workflow, architecture, stack, setup, demo, testing, BLE status, security, limitations, roadmap |
| Documentation | **READY** | Architecture, setup, deployment, testing, limitations, security, demo, verification log, ADR |
| Backend | **READY (local/LAN)** · **PENDING (public)** | Runs and passes 61 assertions. Not deployed anywhere public |
| APK | **READY to build** · **NOT RUN** | Builds; embedded URL verified inside `classes4.dex`. **Never installed or launched on a phone** |
| Working demo | **READY (procedure)** · **PENDING (rehearsal)** | Verified EMULATED end to end. Not rehearsed on the demo hardware |
| PPT | **PENDING** | Source written; deck not produced |
| Demo video | **PENDING** | Not recorded |
| GitHub repository | **READY** | `github.com/Venugopal1807/RoadLink`, branch `main`, local HEAD == `origin/main` |
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
| Android JVM | 62 passed, 0 failed | **VERIFIED** — JVM |
| Kotlin ↔ Python wire compatibility | PASSED, `sig_valid=true` | **VERIFIED** — real HTTP |
| `:app:assembleDebug` | BUILD SUCCESSFUL | **VERIFIED** |
| `:app:assembleRelease` | BUILD SUCCESSFUL | **VERIFIED** |
| `:app:assembleDebugAndroidTest` | BUILD SUCCESSFUL | **VERIFIED** |
| `:spike-ble:assembleDebug` | BUILD SUCCESSFUL | **VERIFIED** |
| Backend start command used by the deploy blueprint | `/healthz` 200, `/api/v1/sos/active` 200 | **VERIFIED** — locally, `PORT=8123` |
| APK carries the configured backend URL | Found in `classes4.dex` | **VERIFIED** |
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

1. **Record the demo video.** Follow the primary demo in
   [`demo-script.md`](demo-script.md). Steps 3–11 carry the claim.
2. **Produce the deck** from [`submission-presentation.md`](submission-presentation.md).
3. **Install the APK on a phone and launch it.** The single largest untested
   assumption in the submission.
4. **Deploy the backend** if the portal requires a public URL
   ([`deployment.md`](deployment.md) option A), otherwise use the LAN path.
5. **Run the physical BLE ladder** if two phones become available, or state
   plainly that it is pending. Either is acceptable; silence is not.
6. **Confirm the repository opens** for someone who is not signed in as you.
7. **Submit** the Google Form and the Prasunet portal entry.

## Final pre-submission sequence

```bash
# 1. all suites green
cd backend && python tests/test_idempotency.py          # expect 61 passed
cd ../android && ./gradlew :app:testDebugUnitTest --rerun   # expect 62 tests
cd .. && python tools/verify_wire_compat.py             # expect PASSED

# 2. build the demo APK against the backend you will actually use
cd android && ./gradlew :app:assembleDebug -Proadlink.backendUrl=<BACKEND-URL>

# 3. install it and actually open it
adb devices -l
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk

# 4. repository is clean and pushed
git status && git log --oneline -5 && git remote -v
```
