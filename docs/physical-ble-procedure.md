# Physical BLE validation procedure

The ladder that turns RoadLink's BLE path from designed into proven.

**Nothing in this document has been executed.** Every result cell is empty by
design. Fill them in from observation, never from expectation.

Target milestone:

```
PHONE A  ──BLE──▶  PHONE B  ──network──▶  BACKEND  ──▶  RESPONDER
```

---

## Before you start

One APK installs on both phones. Roles are chosen at runtime from the Rider
tab, so there is no "Phone A build" to keep straight.

```bash
# backend, on the laptop, bound so phones can reach it over the LAN
cd backend
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
ipconfig            # note the LAN IP, e.g. 192.168.1.23

# build the app pointed at that IP, not the emulator loopback
cd android
./gradlew :app:assembleDebug -Proadlink.backendUrl=http://192.168.1.23:8000

adb devices -l      # expect exactly 2
adb -s <SERIAL_A> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <SERIAL_B> install -r app/build/outputs/apk/debug/app-debug.apk
```

Capture both logs, in two terminals:

```bash
adb -s <SERIAL_A> logcat -c && adb -s <SERIAL_A> logcat -s RLINK | tee docs/runs/phoneA-$(date +%H%M).log
adb -s <SERIAL_B> logcat -c && adb -s <SERIAL_B> logcat -s RLINK | tee docs/runs/phoneB-$(date +%H%M).log
```

Preconditions on both phones: Bluetooth on, permissions granted at first
launch, and **system Location on** — some OEMs still gate scan results behind
it even with `neverForLocation`, which T3 measures rather than assumes.

### Record the devices first

| | Phone A | Phone B |
|---|---|---|
| `adb devices -l` serial | | |
| Manufacturer / model | | |
| Android version | | |
| API level | | |
| OEM skin | | |

---

## Step 1 — device gate

```bash
adb devices -l
```

If fewer than two devices appear, stop and fix that first. Everything below
needs both.

---

## Step 2 — S0 capability probe, on BOTH phones

Rider tab → **Run S0 probe on this device**. The full report is written to the
log, so copy it rather than transcribing.

The decisive line is:

```
CAN ADVERTISE     : true / false
```

`getBluetoothLeAdvertiser()` returns null on chipsets with no LE peripheral
role. This single value decides the topology.

| Phone A | Phone B | Action |
|---|---|---|
| can advertise | — | Proceed as planned |
| cannot | can | **Swap roles.** The advertising-capable phone becomes the rider |
| neither | | **Topology is dead on this hardware.** Go to "If neither phone can advertise" below |

Record for each phone:

| Field | Phone A | Phone B |
|---|---|---|
| CAN ADVERTISE | | |
| Scanner available | | |
| Extended advertising | | |
| LE 2M PHY | | |
| LE Coded PHY | | |
| Max adv data length | | |
| Offloaded filtering | | |
| Multi-advertisement | | |

### If neither phone can advertise

Do **not** spend hours on BLE workarounds. That is a result, not a setback.
Record it, leave `BleRelayTransport.enabled = false`, and state plainly that
physical phone-to-phone BLE is unsupported on the available hardware. The
offline queue plus direct upload remains the demonstrated product path and the
claim narrows to what is actually true.

---

## Step 3 — role assignment

Based on Step 2, not on preference.

- **Rider (Phone A):** Rider tab → **Arm physical BLE transport** ON.
  Then **Force rider offline** ON, so BLE is the only path available.
- **Relay (Phone B):** Rider tab → **Relay mode** ON. Leave it online.

Set the demo transport selector to **BLE** on Phone A so the cascade cannot
quietly fall through to another path and flatter the result.

---

## Step 4 — the ladder

Run in order. Stop at the first failure and record it; a failure here is a
finding. Do not skip ahead to the full demo.

| # | Test | Procedure | Pass condition | Result |
|---|---|---|---|---|
| **T1** | Capability probe | Step 2 above | Values recorded for both phones | |
| **T2** | Advertisement size | Beacon is 13 B; confirm advertising starts without `DATA_TOO_LARGE` | `BLE advertising …` appears in Phone A's log | |
| **T3** | Permission matrix | Deny each of BLUETOOTH_SCAN / ADVERTISE / CONNECT in turn; toggle system Location off | A written truth table of what breaks | |
| **T5** | A → B → ACK | Phone A: CREATE TEST SOS. Phone B: relay mode on | A logs `RELAYED …`; B logs `RELAY took custody …` | |
| **T6** | Repeatability | Repeat T5 **30 times**. Use **Forget collected** on B between runs | Real success count out of 30, plus discovery latency spread | |
| **T7** | Background / screen-off | Background B, screen off, 10 min | Discovery still fires, or the failure is documented with the OEM name | |
| **T8** | Both offline | Both phones in aeroplane mode, Bluetooth on. Run T5 | A reaches RELAYED, B holds the event queued, nothing is deleted | |
| **T9** | Interrupted transfer | Walk B out of range mid-GATT-read, return after 60 s | Retry with backoff; converges; no duplicate ACK; no lost event | |
| **T10** | Duplicate submission | After T8, give B network. Then give A network too | Backend: **1** event row, **2+** audit rows | |
| **T11** | Advertisement privacy | `btmon` or a BLE sniffer on the raw advertisement | No rider id, no coordinates, no device name, no raw event_id | |

### What T5 should look like

Phone A:
```
PERSISTED <id> origin=SIMULATED - safe on disk before any delivery attempt
ATTEMPT <id> via BLE RELAY (REAL) attempt=1
BLE advertising <id> ref=<8 bytes> simulated=true; waiting up to 20000ms for a relay
BLE relay connected: <addr>
BLE packet read by <addr> (NNN B)
BLE ACK for <id> from relay <relay_id> after NNNNms
RELAYED <id> to <relay_id> via BLE RELAY - NOT yet confirmed at the backend
```

Phone B:
```
RELAY scanning for riders in range
RELAY found emergency ref=<8 bytes> rssi=-NN conf=82 age=Ns simulated=true
RELAY connected to <addr>
RELAY read verified packet <id> (NNN B) in NNNms
RELAY took custody of <id> from rider <rider_id>; stored and queued for upload
RELAY ACK confirmed by rider; handoff complete
ATTEMPT <id> via DIRECT NETWORK (REAL) attempt=1
DELIVERED <id> via DIRECT NETWORK
```

Then the Responder tab shows the emergency with delivery path `ble_relay`.

Note that Phone A says **RELAYED**, not DELIVERED. That is correct and
deliberate: a relay's ACK is custody, not arrival. Phone A only reaches
DELIVERED if it later reaches the backend itself.

---

## Step 5 — measurements

Fill in only from observation. Report sample size with every number, and do
not average failures away.

| Metric | Value | Sample size | Devices |
|---|---|---|---|
| Discovery latency (advertise → relay sees beacon) | | | |
| GATT transfer latency (connect → packet read) | | | |
| Acknowledgement latency (read → ACK confirmed) | | | |
| End-to-end (SOS created → backend has it) | | | |
| Relay success rate | / 30 | | |
| Negotiated MTU | | | |
| Reconnect attempts needed (T9) | | | |

T6's success rate is the **only** BLE delivery number RoadLink may quote, and
it must always appear with its sample size and the device models it came from.

---

## Step 6 — evidence discipline

Three categories. Never mix them.

| Category | Meaning |
|---|---|
| **PHYSICAL** | Observed on actual Android hardware |
| **EMULATED** | Observed on an Android emulator |
| **SIMULATED** | Deliberately generated by the application |

The existing emulator results stay in `docs/verification-log.md` and are not
overwritten. Physical results go in their own section beneath them.

While `BleRelayTransport.enabled` is false, no run can produce physical BLE
evidence — the transport reports itself unavailable and the log says so.

---

## After the ladder

- Update `docs/verification-log.md` with a PHYSICAL DEVICE RESULTS section.
- Update `docs/decisions/ADR-002-transport-abstraction.md` — but only with
  measured numbers, never predicted ones.
- If T5 and T6 pass, the product claim may strengthen to:

  > "RoadLink can use a nearby Android phone as an opportunistic relay to carry
  > an emergency beyond the crashed phone's connectivity boundary."

- Claims that remain unsupported regardless of outcome: guaranteed delivery,
  guaranteed rescue, works on every Android phone, works without any network
  anywhere.
