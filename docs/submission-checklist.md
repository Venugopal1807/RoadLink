# Submission checklist

Prasunethon 2.0, Round 2. State verified 2026-08-23.

`[x]` done and checked · `[ ]` outstanding · `[~]` present with a caveat stated
in the row.

## Required items

| | Item | State |
|---|---|---|
| `[x]` | **Source code** | 92 tracked files, working tree clean, 4 commits of history |
| `[x]` | **README** | Product, status, architecture summary, quick start, doc index, limitations |
| `[x]` | **Architecture documentation** | [`architecture.md`](architecture.md) + [`ADR-002`](decisions/ADR-002-transport-abstraction.md) |
| `[x]` | **Setup instructions** | [`setup.md`](setup.md), clean checkout to running app, with troubleshooting |
| `[x]` | **Testing evidence** | [`testing.md`](testing.md) how/why, [`verification-log.md`](verification-log.md) observed results |
| `[x]` | **Security notes** | [`security.md`](security.md), threat model and prototype shortcuts |
| `[x]` | **Limitations** | [`limitations.md`](limitations.md) |
| `[ ]` | **PPT** | **NOT IN REPOSITORY. You must produce this.** |
| `[ ]` | **Demo video** | **NOT RECORDED. You must produce this.** |
| `[~]` | **Working APK** | Builds successfully and carries the configured URL. **Never installed or launched on a physical phone — UNKNOWN** |
| `[~]` | **Backend deployment / working endpoint** | Runs locally on LAN. **No hosted deployment exists.** If the submission needs a public URL, you must deploy it |
| `[ ]` | **Git repository** | Local only. **No remote configured.** See [Phase 7 below](#git) |
| `[x]` | **Demo credentials** | **None required.** No accounts, no login, no API keys anywhere in the product |
| `[x]` | **No secrets** | Scanned: no API keys, tokens, passwords, private keys or personal credentials in tracked files |
| `[x]` | **No AI attribution** | Scanned across source, comments, docs, UI strings and all commit messages |
| `[x]` | **No unsupported claims** | Claims audit below |

## Verified test evidence

| Suite | Result | Evidence class |
|---|---|---|
| Backend ingestion + idempotency | 61 passed, 0 failed | Real HTTP, live uvicorn |
| Android JVM | 62 passed, 0 failed | JVM |
| Kotlin ↔ Python wire | PASSED, `sig_valid=true` | Real HTTP |
| `:app:assembleDebug` | BUILD SUCCESSFUL | |
| `:app:assembleDebugAndroidTest` | BUILD SUCCESSFUL | |
| `:spike-ble:assembleDebug` | BUILD SUCCESSFUL | |
| Android instrumented | 8 passed, 0 failed | **EMULATED**, not re-run this session |
| Physical BLE ladder T1–T11 | **NOT RUN** | Documented pending, every result cell empty |

## Claims audit

- `[x]` No accident statistics, road-death figures or golden-hour claims
- `[x]` No delivery percentage, latency, BLE range, detection accuracy,
  false-positive rate, battery or user-count numbers
- `[x]` No medical claims. RoadLink delivers data; it does not diagnose, triage
  or dispatch
- `[x]` No claimed government, ambulance or emergency-service integration
- `[x]` No production-readiness claim. The prototype shortcuts are named in
  [`security.md`](security.md) and [`limitations.md`](limitations.md)
- `[x]` BLE is never presented as hardware-validated, in the README, ADR-002,
  the verification log, or the app's own UI
- `[x]` Simulated events are labelled in the UI, the log, the signed packet and
  the backend wire path (`simulated_relay`, never `ble_relay`)
- `[x]` Every recorded result carries its evidence class

## Code hygiene

- `[x]` No machine-specific paths in tracked files
- `[x]` No build outputs, APKs, databases or emulator artifacts committed
- `[x]` Local tooling and IDE state gitignored
- `[x]` Third-party licences preserved; no vendored third-party source
- `[x]` Debug endpoints environment-gated (`ROADLINK_ALLOW_RESET`)
- `[x]` No LAN IP or personal address hardcoded; the backend URL is a build
  property

## Demo readiness

- `[x]` Demo works **without BLE** — [`demo-script.md`](demo-script.md) DEMO B
- `[x]` Demo is deterministic — dev switches and a transport pin replace
  aeroplane mode and live radio behaviour
- `[x]` Both demo variants tell the same core story
- `[x]` 2-minute sequence defined and is the priority
- `[ ]` **Rehearsed end to end on the actual demo hardware**
- `[ ]` **Recorded**

---

<a name="git"></a>
## You must provide these manually

Nothing below can be produced from the repository.

1. **Presentation deck.** Source material: [`architecture.md`](architecture.md)
   for the diagram and the invariant, [`current-project-status.md`](current-project-status.md)
   for the evidence table, [`limitations.md`](limitations.md) for the honest
   boundaries.
2. **Demo video.** Record DEMO B from [`demo-script.md`](demo-script.md). Scenes
   1–8 of the 2-minute sequence carry the whole claim.
3. **Git remote, and the push.** Not created automatically, by instruction.
4. **A reachable backend endpoint,** if the submission requires a live URL
   rather than a local demo.
5. **Physical BLE validation,** or an explicit statement that it is pending.
   Either is acceptable; silence is not.
6. **Project/demo URL and repository URL,** verified reachable by someone who is
   not you.
7. **Google Form and Prasunet portal submissions.**

## Final pre-submission sequence

```bash
# 1. everything green
cd backend && python tests/test_idempotency.py
cd ../android && ./gradlew :app:testDebugUnitTest --rerun
cd .. && python tools/verify_wire_compat.py

# 2. build the demo APK against the demo network (NOT the emulator default)
cd android && ./gradlew :app:assembleDebug -Proadlink.backendUrl=http://<LAN-IP>:8000

# 3. install and actually launch it on the demo phone
adb devices -l
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk

# 4. nothing unintended is staged
git status
git log --oneline -10
```

Commit only after the suites pass. Do not weaken a test to get there.
