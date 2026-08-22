# RoadLink

Offline-first emergency delivery for two-wheeler crashes.

> **RoadLink keeps the emergency alive even when connectivity disappears.**

A rider who has crashed may be unable to call for help, and is often exactly
where the network is not. RoadLink detects or receives an emergency, **writes it
to disk before attempting any delivery**, and then delivers it by whatever path
exists — a nearby phone acting as a BLE relay, or the rider's own network when
it returns. If nothing is available, the emergency waits. It is never discarded
because delivery failed.

**BLE is not the product.** Offline-first delivery is. BLE is the opportunistic
mechanism that extends delivery when another phone is nearby.

---

## Status — 2026-08-23

| Area | State | Evidence |
|---|---|---|
| Backend ingestion + idempotency | Working | 61/61 assertions over real HTTP |
| Kotlin ↔ Python packet signing | Working | `sig_valid=true` on a live server |
| Local persistence (Room) | Working | 8/8 durability + migration tests on an Android runtime *(emulated)* |
| Delivery state machine | Working | Scenarios A–E unit tested |
| Simulated relay transport | Working | Scenario A observed end to end *(emulated)* |
| Direct network transport | Working | Scenario B observed end to end *(emulated)* |
| Responder interface | Working | Reads the backend, not local state |
| BLE protocol codecs | Working | Beacon/ACK/packet round trip + tamper rejection, 15 JVM tests |
| **Phone-to-phone BLE transport** | **NOT VALIDATED** | **No RoadLink BLE code has ever run on a radio** |
| Crash detection (sensor) | Not started | Trigger interface exists; `TestCrashDetector` only |

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
./gradlew :app:testDebugUnitTest       # 35 JVM tests
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

All three paths use the same state machine, the same persistence and the same
audit trail. Only the transport differs.

| Path | Setup | Shows |
|---|---|---|
| **Primary** — real phone-to-phone BLE | Two devices, after S0–S5 pass | The differentiating capability |
| **Secondary** — simulated relay | Relay in range = on | The full pipeline, labelled SIMULATED |
| **Fallback** — offline queue → reconnect | Relay off, forced offline, then online | *"RoadLink does not lose the SOS"* |

The demo never depends on staging a real crash. **CREATE TEST SOS** drives the
identical pipeline a sensor trigger will use — signing, persistence, state
machine, transport, backend, audit — and only the trigger is simulated.

**Scenario B, the one that carries the product claim:**

1. Rider tab → turn *relay in range* **off** and *force rider offline* **on**
2. **CREATE TEST SOS** → the event shows `QUEUED`; nothing reaches the backend
3. Responder tab → no emergency (correct: it genuinely has not arrived)
4. Turn *force rider offline* **off**
5. Within seconds the event moves to `DELIVERED` via `DIRECT NETWORK`
6. Responder tab → the emergency appears, with its delivery path shown

---

## Repository

```
android/
  app/         the RoadLink product
  spike-ble/   S0–S5 hardware harness, zero dependencies, untouched
backend/       FastAPI + stdlib sqlite3, idempotent ingestion
docs/          spike plan, S0–S5 runbook, decision records
tools/         cross-language wire verification
```

`:spike-ble` is deliberately left exactly as it is. `docs/s0-s5-runbook.md` is
written against that APK, tomorrow is the only hardware window, and changing it
now would risk the one thing that cannot be rescheduled.

---

## Deliberately not built

Ambulance dispatch · medical diagnosis · 112/ERSS integration · guaranteed
response · multi-hop mesh · dedicated hardware · satellite · ML crash
classifier · authentication · analytics dashboards · production infrastructure.

None of these matter until the core flow works, and claiming any of them would
be dishonest.

## Known limitations

- **HMAC with a key compiled into the app is not key management.** Anyone with
  the APK has the key. Production is a per-device Ed25519 keypair with the
  private key in the Android Keystore.
- No authentication on any backend endpoint. LAN only.
- SQLite, not PostgreSQL. `backend/app/db.py` is the swap point.
- Crash detection is a button. The sensor engine is not written.
- Bluetooth company ID `0xFFFF` is the SIG development value.
- **Cleartext HTTP** is permitted to loopback and private-LAN addresses only
  (`res/xml/network_security_config.xml`); the base config still denies it.
  The prototype backend has no certificate. A real deployment uses HTTPS and
  deletes that file rather than relaxing it.
