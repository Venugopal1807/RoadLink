# Verification log

Only results that were actually observed appear here. Every entry states what
was run, on what, and what it does **not** prove.

---

## 2026-08-23 — Development flow on an emulator

> **EMULATED — NOT DEVICE-VERIFIED.**
> Per `docs/phase1-spike-plan.md` §C.5, results obtained on an emulator validate
> protocol logic and code correctness only. They say nothing about real
> permission behaviour, OEM battery management, radio limits, RSSI or timing,
> and they are **not** used to freeze any communication design.

**Target:** AVD `roadlink_test`, `system-images;android-35;default;x86_64`
Android 15, API 35, x86_64, WHPX acceleration.
**Backend:** uvicorn on the host, reached at `http://10.0.2.2:8000`.

### Persistence durability — `:app:connectedDebugAndroidTest`

`6 tests, 0 failures.` On-disk Room database, closed and reopened between write
and read. An in-memory database was deliberately not used — it would pass every
assertion below while proving nothing about durability.

| Test | Proves |
|---|---|
| `anEmergencySurvivesTheDatabaseBeingClosedAndReopened` | The emergency and its signed fields survive |
| `anUndeliveredEmergencyIsStillPendingAfterAReopen` | A queued SOS is picked up again on next launch |
| `aFailedDeliveryLeavesTheEventOnDiskWithItsErrorAndAttemptCount` | Failure does not remove the event |
| `deliveryAttemptHistorySurvivesAndStaysInOrder` | The audit trail survives intact and ordered |
| `aDeliveredEmergencyIsRetainedRatherThanRemoved` | Delivered events are kept, not cleaned up |
| `manyQueuedEmergenciesAllSurvive` | 25 queued emergencies all recovered |

### Scenario A — simulated relay delivers

Observed, and it accidentally became the strongest evidence of the core
invariant available so far.

Event `9f701919` was created and persisted. Delivery then failed **eight
consecutive times** against a genuine, unanticipated fault: Android 9+ blocks
cleartext HTTP, so both the relay forward and the direct upload were rejected
by the platform. The log line on every one of those failures was
`event retained, still queued`.

The app was then **rebuilt and reinstalled** to add a network security config.
On relaunch:

```
BLE RELAY unavailable for 9f701919
ATTEMPT 9f701919 via SIMULATED RELAY (SIMULATED) attempt=9
SIMULATED relay rl_sim_relay_01 discovered 9f701919 (no radio involved)
SIMULATED relay accepted 9f701919 and is holding it
DELIVERED 9f701919 via SIMULATED RELAY
```

The emergency survived eight delivery failures **and** an application
reinstall, and was delivered on the ninth attempt. Backend record:
`sig_valid: true`, `first_delivery_path: "simulated_relay"`, `simulated: true`.

This was not a scripted scenario. It was a real failure, and nothing was lost.

### Scenario B — offline queue, then direct upload

The scenario carrying the product claim.

1. *Relay in range* off, *force rider offline* on.
2. CREATE TEST SOS → event `d9559489` persisted and queued. Log showed all
   three transports reporting unavailable. **Backend event count: 1** (i.e.
   nothing had arrived — correct).
3. *Force rider offline* off. Relay still absent.
4. ```
   ATTEMPT d9559489 via DIRECT NETWORK (REAL) attempt=1
   direct upload of d9559489 accepted, HTTP 201
   DELIVERED d9559489 via DIRECT NETWORK
   ```
5. **Backend event count: 2**, paths recorded distinctly:

   | event | path | simulated | sig_valid |
   |---|---|---|---|
   | `d9559489` | `direct` | true | true |
   | `9f701919` | `simulated_relay` | true | true |

### Scenario D — duplicate delivery

Re-submitted an already-delivered event over the direct path:

```
direct upload of d9559489 accepted, HTTP 200
REDELIVER d9559489 via DIRECT NETWORK -> backend HTTP 200, sig_valid=true duplicate=true
```

Backend totals: **2 events, 3 audit rows.** The duplicate produced a second
audit row, one event row, and `first_delivery_path` was **not** overwritten.

### Responder interface

Reads `GET /api/v1/sos/active` from the backend, not local state. Rendered both
emergencies with the two fidelity axes shown separately — `SIMULATED` +
`DIRECT` for the network-delivered one, `SIMULATED` + `SIMULATED_RELAY` for the
relayed one — and reported `no fix reported` for location rather than
substituting a placeholder coordinate.

### Bug found and fixed during this run

**Cleartext HTTP blocked on Android 9+.** Every delivery failed with
`Cleartext HTTP traffic to 10.0.2.2 not permitted`. Fixed by adding
`res/xml/network_security_config.xml` permitting cleartext to loopback and
private-LAN ranges only, with the base config still denying it. Documented as a
prototype constraint; a real deployment uses HTTPS and deletes that file.

This would have surfaced for the first time tomorrow morning, on hardware, with
the clock running.

### What this run does NOT prove

- **Nothing about BLE.** `BleRelayTransport.enabled` is `false`; it correctly
  reported itself unavailable on every pass. No advertising, scanning, GATT
  connection or packet transfer was attempted or observed.
- Nothing about real permission prompts, OEM background-execution limits, or
  battery behaviour.
- Nothing about location: the emulator had no GPS fix, so every event carried a
  null position. The no-location path is exercised; the with-location path is not.

---

## Standing test results

| Suite | Result | Command |
|---|---|---|
| Backend | 61 passed, 0 failed | `python backend/tests/test_idempotency.py` |
| Android JVM | 35 passed, 0 failed | `./gradlew :app:testDebugUnitTest` |
| Android instrumented | 6 passed, 0 failed *(emulated)* | `./gradlew :app:connectedDebugAndroidTest` |
| Kotlin ↔ Python wire | PASSED, `sig_valid=true` | `python tools/verify_wire_compat.py` |

---

## Not yet run

`docs/s0-s5-runbook.md` — the S0–S5 BLE ladder on two physical phones.
Until it has run, the honest statement is that RoadLink's BLE path is designed
and coded but **unproven**.
