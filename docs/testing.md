# Testing

How to run each suite and what each one actually proves. Recorded results and
their limits live in [`verification-log.md`](verification-log.md); this document
is the how and the why.

## Summary

| Suite | Command | Count | Environment |
|---|---|---|---|
| Backend ingestion | `python backend/tests/test_idempotency.py` | 61 assertions | Real HTTP, live uvicorn |
| Android JVM | `./gradlew :app:testDebugUnitTest` | 62 tests | JVM |
| Android instrumented | `./gradlew :app:connectedDebugAndroidTest` | 8 tests | Device or emulator |
| Cross-language wire | `python tools/verify_wire_compat.py` | 6 checks | Real HTTP |
| Build | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :spike-ble:assembleDebug` | — | |
| Physical BLE ladder | manual, [`physical-ble-procedure.md`](physical-ble-procedure.md) | T1–T11 | **Two real phones** |

Last full run: 2026-08-23. Results in
[`current-project-status.md`](current-project-status.md) §3.

## Backend — 61 assertions

```bash
cd backend
python tests/test_idempotency.py       # exit 0 = all passed
```

Runs against a **real uvicorn server over real HTTP**, using stdlib `urllib`
only — no pytest, no httpx. Testing over the wire rather than with an in-process
client is deliberate: it exercises the actual status codes and JSON codec, and
lets the suite fire genuinely concurrent duplicate submissions, which is where
idempotency is most likely to break.

Covers: first submission returns 201 and a duplicate returns 200; concurrent
duplicates still produce exactly one event row; the audit trail is append-only;
`first_delivery_path` is never overwritten by a later delivery; `simulated_relay`
is accepted as a distinct path and is never recorded as `ble_relay`; unknown
delivery paths are rejected with 422 rather than silently coerced; `event_ref`
matches the BLE beacon reference and the raw `event_id` is not recoverable from
it.

The server is started on a throwaway port against a temporary database, so the
suite never touches a development database.

## Android JVM — 62 tests

```bash
cd android
./gradlew :app:testDebugUnitTest

# force a re-run; Gradle otherwise reports UP-TO-DATE without executing anything
./gradlew :app:testDebugUnitTest --rerun
```

| Class | Tests | What it proves |
|---|---|---|
| `DeliveryInvariantTest` | 8 | Persist-before-transmit, asserted on operation **order**; a failed write transmits nothing; the state machine has exactly one terminal state and no stranded states |
| `DeliveryScenarioTest` | 12 | Scenarios A–E through the real `DeliveryManager` and real transports; backoff growth and capping; unavailable transports are recorded once, not per pass |
| `RelayHandoffTest` | 10 | A relay ACK produces `RELAYED`, never `DELIVERED`; the event stays pending; a **disarmed BLE transport cannot produce a BLE success** and falls through to the network in the same pass |
| `BleProtocolTest` | 15 | Beacon codec pinned byte-for-byte; packet round trip; tamper rejection; the beacon contains no identity, coordinates or raw event_id |
| `CanonicalTest` | 10 | The Kotlin canonical string matches vectors generated from the Python backend |
| `EnvelopeTest` | 7 | The HTTP envelope shape, and it writes the fixture the wire check consumes |

Only two things are doubled: the backend (`FakeSosApi`, whose idempotency is
separately verified against the real server) and the store. Everything else is
the production code path. Latencies are zeroed and the clock is manual, so runs
are deterministic.

`DeliveryInvariantTest` is worth singling out. It asserts the *sequence* of
operations across components rather than that both occurred — that is the only
way to prove persist-before-transmit rather than assume it.

## Android instrumented — 8 tests

**Requires a connected device or a running emulator.**

```bash
cd android
adb devices                              # must list at least one
./gradlew :app:connectedDebugAndroidTest
```

These cover the half of the core invariant a JVM test cannot reach: that a
persisted emergency genuinely *survives* the database being closed and reopened.
They deliberately use an **on-disk** Room database — an in-memory one would pass
every assertion below while proving nothing about durability.

| Class | Tests | What it proves |
|---|---|---|
| `PersistenceDurabilityTest` | 6 | An emergency and its signed fields survive a close/reopen; a queued SOS is picked up again on next launch; a failed delivery leaves the event on disk with its error and attempt count; the attempt history survives intact and ordered; delivered events are retained rather than cleaned up; 25 queued emergencies all recover |
| `MigrationTest` | 2 | Schema v1 → v2 against a genuine v1 database: a queued emergency, its `attemptCount`, its `lastError` and its audit trail all survive the upgrade |

Note that `connectedAndroidTest` **uninstalls the app under test**, wiping its
database. A successful app launch after a schema change is therefore not
evidence that a migration ran on live data — `MigrationTest` is.

## Cross-language wire check

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*EnvelopeTest*'
cd .. && python tools/verify_wire_compat.py
```

Takes the envelope produced by the **real Kotlin client** and POSTs those exact
bytes at a real backend. This is the check neither side can perform alone: the
Kotlin tests pin the canonical string against vectors copied from Python and the
backend tests pin the Python side, but only a round trip proves the two agree on
the bytes, the JSON encoding and the coordinate precision simultaneously.

It matters because the failure mode is **silent**. A canonicalisation mismatch
does not error — it stores every emergency with `sig_valid = false`, and nothing
surfaces until somebody reads that column.

See [`../tools/README.md`](../tools/README.md) for the individual assertions.

## Physical BLE — not automatable

The T1–T11 ladder needs two real phones and a human. Procedure:
[`physical-ble-procedure.md`](physical-ble-procedure.md). Results:
[`physical-test-results.md`](physical-test-results.md), currently all NOT YET
TESTED.

`:spike-ble` keeps a separate zero-dependency harness with its own ladder
([`s0-s5-runbook.md`](s0-s5-runbook.md)), so a radio problem can never be
confused with a dependency-resolution problem.

## What is not tested, and why

| Not tested | Why |
|---|---|
| Phone-to-phone BLE over a radio | Needs two physical devices. This is the project's largest open risk |
| Compose UI | No UI tests. The logic under the UI is covered; screen assertions would not add evidence for the claims being made |
| Sensor crash detection | Not implemented |
| Backend under load | Out of scope for a prototype; SQLite is explicitly a prototype choice |
| Location with a real GPS fix | The emulator had no fix, so only the no-location path is exercised |
| Battery and background execution | Requires physical devices over hours; T7 in the ladder |

## Rules for this repository

1. **Do not weaken a test to make a build green.** Find the root cause.
2. **An emulator result is never labelled as a device result.** The three
   categories — PHYSICAL, EMULATED, SIMULATED — are never merged.
3. **Every measured number carries its sample size and the hardware it came
   from.** A number without both is not a result.
4. **Record failures, including ones later fixed.** A failure that is written
   down is evidence; one that is not is a gap.
