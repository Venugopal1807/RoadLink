# RoadLink

A durable emergency-delivery system that preserves an emergency locally and
opportunistically delivers it through whatever transport is available.

> **RoadLink keeps the emergency alive even when connectivity disappears.**

A rider who has crashed may be unable to call for help, and is often exactly
where the network is not. RoadLink takes a confirmed emergency, **writes it to
disk before attempting any delivery**, and then delivers it by whatever path
exists: the rider's own network when it returns, or a nearby phone acting as a
BLE relay. If nothing is available, the emergency waits. It is never discarded
because delivery failed.

In this build an emergency is confirmed by a button, not by a sensor. Crash
detection is not implemented; see the status table below.

**BLE is not the product.** Offline-first delivery is. BLE is the opportunistic
mechanism that extends delivery when another phone is nearby.

---

## Status — 2026-08-28

| Area | State | Evidence |
|---|---|---|
| Backend ingestion + idempotency | Working | 61/61 assertions over real HTTP |
| Kotlin ↔ Python packet signing | Working | `sig_valid=true` on a live server |
| Local persistence (Room) | Working | 8/8 durability + migration tests on an Android runtime *(emulated)* |
| Delivery state machine | Working | Scenarios A–E unit tested |
| Recovery from interrupted delivery | Working | An emergency killed mid-attempt is recovered and delivered; 5 tests |
| Simulated relay transport | Working | Scenario A observed end to end *(emulated)* |
| Direct network transport | Working | Scenario B observed end to end *(emulated)* |
| Responder interface | Working | Reads the backend, not local state |
| Rider status vocabulary (6 states) | Working | Incl. FAILED BUT RETAINED; 8 tests |
| Per-emergency custody trail | Working | Built only from stored rows; 7 tests |
| BLE protocol codecs | Working | Beacon/ACK/packet round trip + tamper rejection, 15 JVM tests |
| **Phone-to-phone BLE transport** | **NOT VALIDATED** | **No RoadLink BLE code has ever run on a radio** |
| Crash detection (sensor) | Not started | Trigger interface exists; `TestCrashDetector` only |

> **Android build status: UNKNOWN.** The last environment to touch this code
> could not resolve the Android Gradle Plugin, so `assembleDebug` has not been
> run since the 2026-08-28 changes and no APK has been produced from them. The
> JVM suite (88 tests), the backend suite (61) and the cross-language wire check
> all pass. Run `./gradlew :app:assembleDebug` before relying on a build.

Full results, including what each run does *not* prove:
[`docs/verification-log.md`](docs/verification-log.md).

The strongest evidence so far was unplanned. During the first emulator run an
emergency hit a genuine platform fault (Android blocks cleartext HTTP), failed
delivery **eight times**, survived an application **rebuild and reinstall**, and
was delivered on the ninth attempt — `sig_valid=true`. Nothing was lost, which
is the entire product claim.

### What is honestly unproven

No number, log line or screenshot this build produces is evidence that
phone-to-phone BLE works. `BleRelayTransport.enabled` is `false`, so the
transport reports itself unavailable on every delivery pass. The decisive
question — whether `getBluetoothLeAdvertiser()` returns non-null on the actual
handsets, i.e. whether an Android phone can take the BLE peripheral role at all
— is unanswered. The procedure that answers it is
[`docs/physical-ble-procedure.md`](docs/physical-ble-procedure.md), and every
result cell in it is deliberately empty.

### The claim, as currently supported

> RoadLink preserves an emergency locally when connectivity fails and delivers
> it automatically when a viable communication path becomes available.

That is demonstrated. What is **not** claimed, and will not be until measured:
guaranteed delivery, guaranteed rescue, works on every Android phone, works
without any network anywhere.

---

## Architecture

```
CrashDetector  (TestCrashDetector today, SensorCrashDetector later)
      │
      ▼
EmergencyController ── signs the packet, generates the event_id
      │
      ▼
DeliveryManager ───── owns EVERY state change
      │
      ├── DirectNetworkTransport   real, tried first when the rider is online
      ├── BleRelayTransport        real, DISARMED pending hardware validation
      └── SimulatedRelayTransport  development only, never pre-empts a real path
                 │
                 ▼
            FastAPI backend ── idempotent on event_id
                 │
                 ▼
            Responder view
```

The relay role (Phone B) is not a second implementation. `RelayCoordinator`
collects a foreign emergency over BLE and stores it; from that point it is
carried by the same `DeliveryManager` loop and the same `DirectNetworkTransport`
as one of the device's own. A relay is a role a phone plays, not a mode of the
product.

A relay's acknowledgement produces the state `RELAYED`, **not** `DELIVERED`.
Custody is not arrival — the relay may never regain connectivity — so the rider
keeps trying independently, which is safe because the backend is idempotent.

Nothing above `delivery/Transport.kt` imports a Bluetooth class, so the BLE
implementation is replaceable without touching the domain, the store or the UI.
See [`docs/decisions/ADR-002-transport-abstraction.md`](docs/decisions/ADR-002-transport-abstraction.md).

### The core invariant

```
CONFIRMED_EMERGENCY → PERSIST LOCALLY → only then ATTEMPT DELIVERY
```

Enforced structurally, not by convention:

- `EmergencyStore` exposes **no delete, no purge, no expiry**. No caller can
  drop an emergency because delivery failed, because no such method exists.
- `DeliveryState` has **no terminal failure state**. Every failure path returns
  to `QUEUED_OFFLINE`, which is retryable. `DELIVERED` is the only terminal
  state, and a unit test enumerates the machine to prove it.
- Transports receive an immutable event and return a result. They cannot mutate
  or complete an event; only `DeliveryManager` can.
- `DeliveryInvariantTest` asserts on the **order** of operations — persistence
  strictly precedes any transport call, and a failed write transmits nothing.

### Keeping simulation honest

A simulator that is indistinguishable from the real thing makes the evidence
worthless. Two independent axes of fidelity are tracked and never conflated:

- **Origin** — real sensor, or the test button?
- **Transport** — real radio, or the simulator?

A real sensor event delivered by the simulator is *not* BLE evidence. The
`simulated` flag lives **inside the HMAC signature**, so no downstream layer can
flip it without invalidating the packet. Simulated deliveries are recorded
against their own wire path, `simulated_relay` — never `ble_relay`.

### Core workflow

```
1. Emergency confirmed        simulated trigger today; sensor engine not written
2. Signed                     HMAC over a canonical string, event_id fixed here
3. Written to disk            survives process death, reinstall, battery pull
4. Delivery attempted         direct network → BLE relay → simulated relay
     ├── backend accepts   → DELIVERED        (terminal)
     ├── relay takes it    → RELAYED          (custody, still pending)
     └── nothing available → QUEUED_OFFLINE   (retried, never dropped)
5. Retry loop                 every 3s, backoff 1s → 30s cap, resumes on launch
6. Responder reads the backend, not the device
```

Step 3 completes before step 4 begins. That ordering is the product.

---

## Technology stack

| Layer | Choice | Why |
|---|---|---|
| App | Kotlin, Jetpack Compose, minSdk 26 | API 26 is the floor for the BLE peripheral role |
| Persistence | Room over SQLite, `synchronous = FULL` | A committed write has reached disk before it returns |
| Concurrency | Kotlin coroutines | The delivery loop and BLE callbacks |
| Radio | Android BLE — advertiser, GATT server, scanner, GATT client | No third-party BLE library; the platform API directly |
| Signing | HMAC-SHA256 truncated to 128 bits | Mirrored in Kotlin and Python, pinned by cross-language tests |
| Backend | FastAPI, Pydantic, `uvicorn` | |
| Backend storage | stdlib `sqlite3`, WAL, no ORM | One obvious swap point for PostgreSQL |
| Backend tests | stdlib `urllib` against a live server | No pytest or httpx; zero install risk |
| Build | Gradle 9, AGP 8.13, KSP | |

No dependency was added that the product does not use.

---

## Running it

### Backend

```bash
cd backend
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
python tests/test_idempotency.py      # 61 assertions, exit 0
```

Bind `0.0.0.0` so phones can reach it over the LAN. Find the host IP with
`ipconfig`.

### Android

```bash
cd android
./gradlew :app:testDebugUnitTest       # 88 JVM tests
./gradlew :app:assembleDebug

# point the app at your machine instead of the emulator loopback:
./gradlew :app:assembleDebug -Proadlink.backendUrl=http://192.168.1.x:8000
```

Default backend URL is `http://10.0.2.2:8000` (host loopback as seen from an
emulator).

### Cross-language wire check

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*EnvelopeTest*'
cd .. && python tools/verify_wire_compat.py
```

Takes the envelope produced by the real Kotlin client and POSTs those exact
bytes at a running backend. Guards a silent failure mode: a canonicalisation
mismatch does not error, it just stores every emergency with `sig_valid=false`.

---

## Demo

The full scene-by-scene script is [`docs/demo.md`](docs/demo.md). It runs on
**one phone**, with no Bluetooth and no second device, so the parts of RoadLink
that are unproven cannot break the parts that are.

All paths use the same state machine, the same persistence and the same audit
trail. Only the transport differs.

| Path | Setup | Shows |
|---|---|---|
| **Baseline** — offline queue → reconnect | Relay off, forced offline, then online | The product claim, and it needs no radio |
| **Enhancement** — real phone-to-phone BLE | Two devices, only after the hardware ladder passes | The differentiating capability |
| **Development** — simulated relay | Relay in range = on | The full pipeline, labelled SIMULATED throughout |

The demo never depends on staging a real crash. **CREATE TEST SOS** drives the
identical pipeline a sensor trigger will use (signing, persistence, state
machine, transport, backend, audit) and only the trigger is simulated.

**The sequence that carries the product claim:**

1. Rider tab → demo controls → *force rider offline* **on**
2. **CREATE TEST SOS** → persisted, then `OFFLINE / QUEUED`; nothing reaches
   the backend
3. Responder tab → no emergency (correct: it genuinely has not arrived)
4. **Force stop the app from Android settings.** The process is gone while the
   emergency is still undelivered.
5. Reopen RoadLink, turn *force rider offline* **off**
6. Within seconds it reads `DELIVERED`, and the custody trail shows every
   attempt including the interrupted one
7. Responder tab → the emergency appears, read from the server

Step 4 is the point. An emergency that survives its own application being
killed is the difference between a claim and a demonstration.

Because BLE is a transport rather than the product, the demonstration above is
unaffected by whether the radio path has been validated.

---

## Repository

```
android/
  app/         the RoadLink product
  spike-ble/   S0–S5 hardware harness, zero dependencies, untouched
backend/       FastAPI + stdlib sqlite3, idempotent ingestion
docs/          status, architecture, setup, demo, testing, limitations, security
tools/         cross-language wire verification
```

`:spike-ble` is deliberately left exactly as it is. `docs/s0-s5-runbook.md` is
written against that APK, and it is kept as the diagnostic fallback for
isolating which side of a BLE failure is at fault.

---

## Documentation

| Document | Purpose |
|---|---|
| [`current-project-status.md`](docs/current-project-status.md) | What is implemented, verified, emulator-only, untested, and blocking |
| [`architecture.md`](docs/architecture.md) | How the system is built, and why |
| [`setup.md`](docs/setup.md) | Clean checkout to running app, plus troubleshooting |
| [`testing.md`](docs/testing.md) | How to run each suite and what each one proves |
| [`limitations.md`](docs/limitations.md) | What RoadLink does not do and may not claim |
| [`security.md`](docs/security.md) | Threat model, packet integrity, BLE privacy, prototype shortcuts |
| [`verification-log.md`](docs/verification-log.md) | Observed results, and what each does *not* prove |
| [`physical-ble-procedure.md`](docs/physical-ble-procedure.md) | The T1–T11 hardware ladder |
| [`physical-test-results.md`](docs/physical-test-results.md) | Its results sheet — currently all NOT YET TESTED |
| [`deployment.md`](docs/deployment.md) | Hosting the backend, and the LAN fallback |
| [`submission-checklist.md`](docs/submission-checklist.md) | Submission state, item by item |
| [`demo.md`](docs/demo.md) | The demo script, scene by scene |
| [`submission-presentation.md`](docs/submission-presentation.md) | Slide-by-slide source for the deck |
| [`ADR-002`](docs/decisions/ADR-002-transport-abstraction.md) | Why delivery sits behind a transport abstraction |

---

## Security

Full account in [`docs/security.md`](docs/security.md). In brief:

- Every emergency is **signed at creation**, before it is persisted or sent. A
  relay verifies the signature before storing and **drops anything that fails**,
  so a relay cannot be used to inject emergencies into the backend.
- The `simulated` flag is **inside** the signature and cannot be flipped
  downstream without invalidating the packet.
- The BLE advertisement carries **no identity, no coordinates and not the raw
  event id** — only a truncated one-way hash. The emergency itself travels only
  over the connected GATT link.
- Rider identity is an opaque random value. No name, phone number or email; the
  packet schema has no field for them.

Prototype shortcuts, stated rather than hidden: a shared HMAC key compiled into
the app is **not** key management, there is no authentication on any backend
endpoint, and debug builds permit cleartext HTTP because the prototype backend
has no certificate. Release builds deny it.

---

## Roadmap

In order, and none of it claimed as done:

1. **Physical BLE validation** on two handsets, via the existing T1–T11
   procedure. Every result cell is currently empty by design.
2. **Sensor-based crash detection.** The trigger interface exists; the detection
   engine is not written.
3. **Per-device Ed25519 keys** in the Android Keystore, replacing the shared
   HMAC key. The signing call site is already a single function.
4. **A foreground service**, so delivery continues without the app open.
5. **PostgreSQL** in place of SQLite. `backend/app/db.py` is the swap point.

---

## Deliberately not built

Ambulance dispatch · medical diagnosis · 112/ERSS integration · guaranteed
response · multi-hop mesh · dedicated hardware · satellite · ML crash
classifier · authentication · analytics dashboards · production infrastructure.

None of these matter until the core flow works, and claiming any of them would
be dishonest.

## Known limitations

The short version; the full account is in
[`docs/limitations.md`](docs/limitations.md) and
[`docs/security.md`](docs/security.md).

- **Phone-to-phone BLE is unproven on hardware.** Implemented and unit-tested,
  never run on a radio.
- **Crash detection is a button.** The sensor engine is not written.
- **HMAC with a key compiled into the app is not key management.** Anyone with
  the APK has the key. Production is a per-device Ed25519 keypair with the
  private key in the Android Keystore.
- No authentication on any backend endpoint. SQLite, LAN only, cleartext HTTP
  permitted to private addresses only.
