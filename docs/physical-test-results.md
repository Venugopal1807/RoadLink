# Physical device test results

Results from running [`physical-ble-procedure.md`](physical-ble-procedure.md) on
two real Android phones.

> ## STATUS: NOT YET TESTED
>
> **No RoadLink BLE code has executed on a radio.** Every result cell below is
> empty by design. Fill them in from observation only — never from expectation,
> and never from an emulator run.
>
> While `BleRelayTransport.enabled` is `false` the transport reports itself
> unavailable on every delivery pass, so no run can produce physical BLE
> evidence. Arm it from the Rider tab only after S0 reports CAN ADVERTISE.

Emulator and simulated results live in [`verification-log.md`](verification-log.md)
and are **not** superseded or overwritten by anything recorded here.

---

## Devices under test

| | Phone A (rider) | Phone B (relay) |
|---|---|---|
| `adb devices -l` serial | NOT YET TESTED | NOT YET TESTED |
| Manufacturer / model | NOT YET TESTED | NOT YET TESTED |
| Android version | NOT YET TESTED | NOT YET TESTED |
| API level | NOT YET TESTED | NOT YET TESTED |
| OEM skin | NOT YET TESTED | NOT YET TESTED |
| Test date | NOT YET TESTED | NOT YET TESTED |

---

## S0 — capability probe

The decisive value. `getBluetoothLeAdvertiser()` returns null on chipsets with
no LE peripheral role, and that single fact decides whether this topology is
possible at all.

| Field | Phone A | Phone B |
|---|---|---|
| **CAN ADVERTISE** | NOT YET TESTED | NOT YET TESTED |
| Scanner available | NOT YET TESTED | NOT YET TESTED |
| Extended advertising | NOT YET TESTED | NOT YET TESTED |
| LE 2M PHY | NOT YET TESTED | NOT YET TESTED |
| LE Coded PHY | NOT YET TESTED | NOT YET TESTED |
| Max adv data length | NOT YET TESTED | NOT YET TESTED |
| Offloaded filtering | NOT YET TESTED | NOT YET TESTED |
| Multi-advertisement | NOT YET TESTED | NOT YET TESTED |

**Outcome:** NOT YET TESTED

If neither phone can advertise, that is a result rather than a setback. Record
it, leave the transport disarmed, and state plainly that phone-to-phone BLE is
unsupported on the available hardware. The offline queue plus direct upload
remains the demonstrated product path.

---

## The T1–T11 ladder

Run in order. Stop at the first failure and record it.

| # | Test | Pass condition | Result | Notes |
|---|---|---|---|---|
| **T1** | Capability probe | Values recorded for both phones | NOT YET TESTED | |
| **T2** | Advertisement size | 13-byte beacon advertises without `DATA_TOO_LARGE` | NOT YET TESTED | |
| **T3** | Permission matrix | Truth table of what breaks per denied permission | NOT YET TESTED | |
| **T5** | A → B → ACK | A logs `RELAYED`; B logs `RELAY took custody` | NOT YET TESTED | |
| **T6** | Repeatability ×30 | Real success count out of 30 | NOT YET TESTED | |
| **T7** | Background / screen-off | Discovery still fires, or failure documented with OEM name | NOT YET TESTED | |
| **T8** | Both offline | A reaches RELAYED, B holds it queued, nothing deleted | NOT YET TESTED | |
| **T9** | Interrupted transfer | Retries with backoff, converges, no duplicate ACK, no loss | NOT YET TESTED | |
| **T10** | Duplicate submission | Backend: 1 event row, 2+ audit rows | NOT YET TESTED | |
| **T11** | Advertisement privacy | No rider id, coordinates, device name or raw event_id on air | NOT YET TESTED | |

### T3 — permission truth table

| Condition | Scan works? | Advertise works? | Connect works? | Observed behaviour |
|---|---|---|---|---|
| All granted | NOT YET TESTED | NOT YET TESTED | NOT YET TESTED | |
| BLUETOOTH_SCAN denied | NOT YET TESTED | NOT YET TESTED | NOT YET TESTED | |
| BLUETOOTH_ADVERTISE denied | NOT YET TESTED | NOT YET TESTED | NOT YET TESTED | |
| BLUETOOTH_CONNECT denied | NOT YET TESTED | NOT YET TESTED | NOT YET TESTED | |
| System Location off | NOT YET TESTED | NOT YET TESTED | NOT YET TESTED | |

---

## Measurements

Fill in only from observation. Every number carries its sample size and the
device models it came from. Do not average failures away.

| Metric | Value | Sample size | Devices |
|---|---|---|---|
| Discovery latency (advertise → beacon seen) | NOT YET TESTED | | |
| GATT transfer latency (connect → packet read) | NOT YET TESTED | | |
| Acknowledgement latency (read → ACK confirmed) | NOT YET TESTED | | |
| End-to-end (SOS created → backend has it) | NOT YET TESTED | | |
| Relay success rate | NOT YET TESTED | / 30 | |
| Negotiated MTU | NOT YET TESTED | | |
| Reconnect attempts needed (T9) | NOT YET TESTED | | |

T6's success rate is the **only** BLE delivery number RoadLink may quote, and it
must always appear with its sample size and the device models it came from.

---

## Failures and findings

Record every failure here, including ones later fixed. A failure that is written
down is evidence; one that is not is a gap.

NOT YET TESTED

---

## What the results change

Fill in after the ladder runs.

- If **T5 and T6 pass**, the product claim may strengthen to: *"RoadLink can use
  a nearby Android phone as an opportunistic relay to carry an emergency beyond
  the crashed phone's connectivity boundary"* — quoted with the measured success
  rate, its sample size, and the handsets it was measured on.
- If they **fail**, RoadLink remains a durable offline-delivery system with a
  BLE transport that is implemented but unsupported on the tested hardware.

Claims that remain unsupported either way: guaranteed delivery, guaranteed
rescue, works on every Android phone, works without any network anywhere.

After the ladder, also update [`verification-log.md`](verification-log.md) with a
PHYSICAL DEVICE RESULTS section and
[`current-project-status.md`](current-project-status.md) §5.
