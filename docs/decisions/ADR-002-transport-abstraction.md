# ADR-002 — Delivery transport abstraction, and how simulation is kept honest

**Status:** Accepted
**Date:** 2026-08-22
**Supersedes:** nothing. **Depends on:** `docs/phase1-spike-plan.md` §C.4 (advertisement + GATT)

---

## Context

Phase 1 produced a BLE spike that compiles and a backend that is tested, but no
product. Two facts shaped this decision:

1. **BLE is unproven.** No RoadLink BLE code has executed on a physical phone.
   The decisive question — whether `getBluetoothLeAdvertiser()` returns non-null
   on the actual handsets, i.e. whether an Android phone can take the peripheral
   role at all — is unanswered. Physical validation is scheduled but has not
   happened.
2. **Development cannot wait for it.** The only hardware window is a single
   morning. Everything that does not require a radio has to be built and tested
   before then, or the window gets spent on integration rather than on
   measurement.

The naive response is to sprinkle `if (bleFailed) { useSimulator() }` through
the app. That produces two divergent code paths, and the one exercised during
development is not the one that runs in the demo.

The second risk is subtler and worse: a simulator that is *too* convincing.
If simulated deliveries are indistinguishable from real ones in the database,
the logs and the UI, then the evidence we plan to present becomes worthless —
and we would not necessarily notice.

## Decision

### 1. A single `Transport` interface, consulted in priority order

```
DeliveryManager
   ├── DirectNetworkTransport   (real)
   ├── BleRelayTransport        (real, currently disarmed)
   └── SimulatedRelayTransport  (development)
```

*(Priority order updated 2026-08-23, when the BLE transport was integrated.
Direct network moved to the front: when the rider is online that path is both
faster and more certain, and its availability check is a cheap connectivity
read, so an offline rider still falls straight through to BLE. The simulated
relay moved to the back so it can never pre-empt a real path.)*

`DeliveryManager` owns every state change. A transport receives an immutable
event and returns a `TransportResult`; it cannot mutate, complete or delete the
event. Delivery code is therefore structurally incapable of losing an
emergency.

The cascade *is* the fallback behaviour: a transport reporting `Unavailable` is
skipped, one that returns `Failed` counts an attempt and the next transport is
tried in the same pass. "BLE unavailable → direct upload" needs no special
case; it falls out of the ordering.

Nothing above `Transport.kt` imports a Bluetooth class. The integration was
therefore: lift the radio code from `:spike-ble` into `BlePeripheral` /
`BleCentral`, adapt it to the `Transport` contract in `BleRelayTransport`, and
change nothing above that boundary. That has since been done — see the update
below.

### 2. Fidelity is a first-class property, carried on two independent axes

```kotlin
enum class Fidelity { REAL, SIMULATED, MOCKED, EMULATED }
```

- **Origin fidelity** — did a real sensor or the test button create this?
- **Transport fidelity** — did a real radio or the simulator deliver it?

They are independent, and conflating them would be a lie in both directions. A
real sensor event delivered by the simulator is *not* evidence of BLE working.
A simulated trigger delivered over real BLE *is* evidence of BLE working.
`EmergencyEvent.isFullyReal` requires both.

### 3. `simulated_relay` is its own wire path

The backend's `DeliveryPath` became
`Literal["direct", "ble_relay", "simulated_relay"]`.

The alternative — reporting simulated deliveries as `ble_relay` — would put
fabricated BLE evidence into the audit trail we intend to quote in the
submission. The alternative of reporting them as `direct` would be a different
lie. A simulated hop gets its own name, and the distinction survives into the
database, the responder UI and any metric derived from them.

### 4. The simulated marker lives *inside* the signature

`simulated` is part of the canonical string that gets HMAC-signed. It cannot be
flipped anywhere downstream without invalidating the signature. This is
deliberate: the honesty marker should not be something a later layer can
quietly edit.

### 5. What the simulator actually simulates

| Simulated | Real |
|---|---|
| Whether a relay phone exists and is in range | The signed packet |
| Whether the handoff succeeds, and how it fails | Local persistence |
| Discovery and transfer latency | The state machine and retry logic |
| | The HTTP forward to the backend |
| | The backend's storage and audit trail |

The relay *hop* is imaginary. Everything after the relay accepts the event is a
genuine request against a genuine server. So the simulator proves the pipeline
and proves **nothing whatsoever** about BLE.

## Consequences

**Good**

- The product is complete without BLE. Offline queue → reconnect → direct
  upload is a standalone story, which is what makes RoadLink robust to
  tomorrow's test failing.
- Scenarios A–E are deterministic and unit-tested on the JVM.
- BLE becomes a differentiating enhancement rather than a dependency.
- Simulated results cannot be mistaken for physical ones at any layer.

**Costs**

- One extra indirection between the state machine and the radio.
- A backend schema change, though additive and backward-compatible.
- `BleRelayTransport` is disarmed by default, so an operator must deliberately
  arm it before any BLE evidence can be produced. It reports precisely why it
  cannot deliver rather than pretending it can.

**Explicitly rejected**

| Option | Why not |
|---|---|
| `if (bleFailed) useSimulator()` at call sites | Two divergent paths; the tested one is not the demoed one |
| Simulator as a separate app/build flavour | Doubles the surface; the simulated path stops sharing the state machine |
| Reporting simulated deliveries as `ble_relay` | Fabricates the exact evidence the project intends to present |
| Waiting for hardware before building the product | Spends the single hardware window on integration instead of measurement |

## Update — 2026-08-23: the radio code now exists

The BLE transport was implemented behind this boundary and the decision above
held: nothing outside `com.roadlink.ble` and `BleRelayTransport` references a
Bluetooth class, and no layer above `Transport.kt` changed.

What was added: `BlePeripheral` (rider — advertiser + GATT server),
`BleCentral` (relay — scanner + GATT client) and `RelayCoordinator`, which
stores a collected emergency and then leaves forwarding to the ordinary
`DeliveryManager` loop rather than implementing a second delivery path.

Two consequences worth recording:

- A relay verifies the packet signature before storing it, and withholds the
  ACK until the write has succeeded. An ACK tells the rider its emergency is
  safe somewhere else, so acknowledging before storing is the one way this
  design could actually lose an SOS.
- A relay ACK produces the state `RELAYED`, never `DELIVERED`, so custody is
  never reported as arrival.

## Status of the BLE claim

**Not validated on hardware.** `BleRelayTransport.enabled` is `false`. No
number, log line or screenshot produced by this build is evidence that
phone-to-phone BLE works — the codecs and the orchestration are unit-tested,
the radio path is not.

That evidence can only come from `docs/physical-ble-procedure.md` executed on
two physical devices (with `docs/s0-s5-runbook.md` kept as the `:spike-ble`
diagnostic fallback for isolating a fault). Until it does, the honest statement
is that BLE is designed and coded but unproven.
