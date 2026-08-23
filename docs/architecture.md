# Architecture

How RoadLink is put together and why. For current status and evidence see
[`current-project-status.md`](current-project-status.md); for the decision
record behind the transport seam see
[`decisions/ADR-002-transport-abstraction.md`](decisions/ADR-002-transport-abstraction.md).

## The problem being solved

A rider crashes on a highway or a rural road. They may be unconscious, and they
are often exactly where cellular coverage is not. Any design that assumes a
working network at the moment of the crash does not address the case that
matters.

RoadLink's response is to decouple *recording* an emergency from *delivering*
it. The recording must always succeed locally. Delivery is best-effort, over
whatever path happens to exist, retried forever.

## Layers

```
CrashDetector            TestCrashDetector today; SensorCrashDetector is not written
      │
      ▼
EmergencyController      generates event_id, signs the packet
      │
      ▼
DeliveryManager          owns EVERY state change
      │
      ├── DirectNetworkTransport   real; tried first when the rider is online
      ├── BleRelayTransport        real; DISARMED pending hardware validation
      └── SimulatedRelayTransport  development only; never pre-empts a real path
      │
      ▼
EmergencyStore (Room)    no delete, no purge, no expiry
      │
      ▼
FastAPI backend          idempotent on event_id, append-only audit
      │
      ▼
Responder view
```

Each arrow is a one-way dependency. The domain layer imports no Android
Bluetooth class, no Room class and no HTTP class, so an emergency can exist,
persist and survive without reference to how it will eventually be delivered.

## The core invariant

```
CONFIRMED_EMERGENCY → PERSIST LOCALLY → only then ATTEMPT DELIVERY
```

Enforced structurally rather than by convention:

- **`EmergencyStore` exposes no delete, no purge and no expiry.** No caller can
  drop an emergency because delivery failed, because no such method exists.
- **`DeliveryState` has no terminal failure state.** Every failure path returns
  to `QUEUED_OFFLINE`, which is retryable. `DELIVERED` is the only terminal
  state, and a unit test enumerates the machine to prove it.
- **Transports cannot mutate or complete an event.** They receive an immutable
  event and return a `TransportResult`. Only `DeliveryManager` writes state.
- **`DeliveryManager.submit` returns only after a durable write.** If
  persistence throws, nothing is transmitted and the caller is told — an event
  that was sent but not stored cannot be retried.
- **Room is configured `synchronous = FULL` with no destructive migration
  fallback.** A committed transaction has reached the disk before the write
  returns, and a missing migration fails loudly rather than silently wiping
  undelivered emergencies.

`DeliveryInvariantTest` asserts on the *order* of operations across components,
not merely that both happened: persistence strictly precedes any transport call,
and a failed write transmits nothing.

## Delivery state machine

```
CONFIRMED_EMERGENCY ──▶ QUEUED_OFFLINE ◀──────────────┐
                              │                       │
                              ▼                       │ failure
                       DELIVERY_ATTEMPT ──────────────┘
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
                 RELAYED ──────▶ DELIVERY_ATTEMPT ──▶ DELIVERED
```

`RELAYED` exists because custody is not arrival. A relay's acknowledgement means
another phone has the emergency on disk; that phone may never regain
connectivity. So `RELAYED` is still pending, the rider keeps trying
independently, and the backend's idempotency makes that redundancy safe.

## The transport cascade

`DeliveryManager` consults transports in priority order:

1. **Direct network.** When the rider is online this is both fastest and most
   certain, and its availability check is a cheap connectivity read.
2. **BLE relay.** Exists precisely for the case direct upload cannot handle —
   the rider having no connectivity at all.
3. **Simulated relay.** Last, so it can never pre-empt a real path.

The cascade *is* the fallback behaviour. A transport reporting `Unavailable` is
skipped; one returning `Failed` counts an attempt and the next transport is tried
in the same pass. "BLE unavailable → direct upload" needs no special case.

| Condition | Result |
|---|---|
| Network available | Direct network |
| No network, BLE available and armed | BLE relay → `RELAYED` |
| Neither | Stays `QUEUED_OFFLINE`, retried |
| Any transport failure | Event remains persisted |
| Backend accepts | `DELIVERED` |

Retries use exponential backoff — 1s, 2s, 4s, 8s, 16s, capped at 30s — over a
loop that runs every 3s. A transport that reports itself *unavailable* is never
tried, so it does not advance the attempt count; its reason is recorded once and
repeats are suppressed until it changes, while availability is still re-checked
every pass.

## The relay role

A relay is a role a phone plays, not a separate mode of the product.
`RelayCoordinator` collects a foreign emergency over BLE and stores it. From that
point it is carried by the same `DeliveryManager` loop and the same
`DirectNetworkTransport` as one of the device's own. There is no second delivery
implementation.

Two ordering rules matter:

- **Verify before storing.** A packet whose signature does not check out is
  dropped, not forwarded. Without this, any nearby device could inject
  emergencies into the backend through an honest relay.
- **Store before acknowledging.** The ACK is withheld until the write has
  succeeded, because an ACK tells the rider its emergency is safe somewhere
  else and the rider may stop advertising on the strength of it. Acknowledging
  before storing is the one way this design could actually lose an SOS.

## BLE protocol

The topology is unusual: the phone *in distress* takes the peripheral role and
announces itself; the helper phone scans and reaches out. This works only where
`getBluetoothLeAdvertiser()` is non-null, which is a per-chipset fact rather
than an OS guarantee — and is the single largest open risk in the project.

- **Advertisement is a beacon/trigger only.** 13 bytes in the scan response,
  carrying schema version, flags, an 8-byte `event_ref`, confidence and age. It
  contains no rider identity, no coordinates and not the raw `event_id`.
  `event_ref` is a truncated SHA-256 so a passive listener cannot correlate a
  broadcast with a backend record.
- **GATT carries the payload.** The signed SOS packet is served on a READ
  characteristic; the acknowledgement is a WRITE. Reads honour the `offset`
  parameter, so a packet larger than the MTU transfers via `ATT_READ_BLOB` and
  correctness never depends on MTU negotiation succeeding.
- The protocol is byte-identical to `:spike-ble`, so a product device and a
  spike device can be paired during bring-up to isolate which side is at fault.

## Packet and signature

One codec produces the bytes read off a GATT characteristic and the `packet`
half of the HTTP envelope. That is what makes a relayed emergency and a directly
uploaded one byte-identical, and therefore verifiable against the same
signature.

Encoding is hand-written rather than delegated to a JSON library because
coordinates must appear at exactly 7 decimal places — the precision the
signature was computed over. Signing is HMAC-SHA256 truncated to 128 bits over a
canonical string, mirrored between `domain/Canonical.kt` and
`backend/app/canonical.py`. `tools/verify_wire_compat.py` POSTs Kotlin-produced
bytes at the real Python backend, because a canonicalisation mismatch does not
error — it silently stores every emergency with `sig_valid=false`.

A relay never re-signs: it does not hold the rider's key, and re-signing would
destroy the only evidence the packet arrived unmodified.

## Backend

FastAPI over stdlib `sqlite3` with a thin repository layer — no ORM, so there is
one obvious swap point for PostgreSQL. Two tables: `events` keyed on `event_id`,
and an append-only `audit` table.

Ingestion is idempotent: `201` the first time an `event_id` is seen, `200`
thereafter, with a new audit row either way and `first_delivery_path` never
overwritten. WAL journalling plus `BEGIN IMMEDIATE` and a busy timeout keep that
correct when a rider and one or more relays submit the same event simultaneously.

A duplicate is not an error. Independent submission by multiple holders is the
design, and idempotency here is what makes it safe.

## Keeping simulation honest

A simulator indistinguishable from the real thing makes the evidence worthless.
Two independent axes of fidelity are tracked and never conflated:

- **Origin** — a real sensor, or the test button?
- **Transport** — a real radio, or the simulator?

A real sensor event delivered by the simulator is *not* BLE evidence.
`EmergencyEvent.isFullyReal` requires both axes to be real. The `simulated` flag
sits **inside** the HMAC signature, so no downstream layer can flip it without
invalidating the packet. Simulated deliveries are recorded against their own wire
path, `simulated_relay` — never `ble_relay` — so a simulated hop cannot become
fabricated BLE evidence in the audit trail.

## What the responder reads

The responder view queries the **backend**, not local state. The claim being
demonstrated is that the SOS arrived, and only the server can attest to that; a
responder view fed from the device's own database would look identical and prove
nothing.
