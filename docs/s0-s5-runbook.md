# S0–S5 Runbook — phone-to-phone BLE proof of concept

The same APK installs on both phones. Role is chosen at runtime, so there is no
"Phone A build" and "Phone B build" to keep straight.

```
android/spike-ble/build/outputs/apk/debug/spike-ble-debug.apk   (914 KB)
```

## Build and install

```bash
cd android
./gradlew :spike-ble:assembleDebug

adb devices                                  # expect exactly 2
adb -s <SERIAL_A> install -r spike-ble/build/outputs/apk/debug/spike-ble-debug.apk
adb -s <SERIAL_B> install -r spike-ble/build/outputs/apk/debug/spike-ble-debug.apk
```

Capture logs from both phones, in two terminals:

```bash
adb -s <SERIAL_A> logcat -c && adb -s <SERIAL_A> logcat -s RLSPIKE | tee docs/spike-runs/phoneA-$(date +%H%M).log
adb -s <SERIAL_B> logcat -c && adb -s <SERIAL_B> logcat -s RLSPIKE | tee docs/spike-runs/phoneB-$(date +%H%M).log
```

## The ladder

Each rung must pass before the next is meaningful. Stop at the first failure and
record it — a failure here is a finding, not a setback.

### S0 — capability probe  *(run on BOTH phones first)*

Tap **Probe radio**. The decisive line is:

```
CAN ADVERTISE     : true/false
```

`getBluetoothLeAdvertiser()` returns null on chipsets without the LE peripheral
role. This single value decides the topology:

| Phone A | Phone B | Outcome |
|---|---|---|
| can advertise | — | Proceed as planned |
| cannot | can | Swap roles; the advertising-capable phone must be the rider |
| neither | | **Topology is dead.** Escalate to the documented fallback: offline queue + auto-upload, BLE demoted |

Also record from the probe, for requirement 7: `Extended adv`, `LE 2M PHY`,
`Max adv data len`, `Offloaded filter`.

The app also runs codec self-tests at launch. Expect `self-tests: 9 passed, 0 failed`.
A failure there means the Kotlin `event_ref` has diverged from the backend's and
must be fixed before anything else.

### T2 — empirical payload ceiling  *(requirement 7)*

Tap **T2 scanRsp**, then **T2 advData**. Each walks payload size upward until the
stack returns `ADVERTISE_FAILED_DATA_TOO_LARGE`.

Expected from the 31-byte spec arithmetic: ~27 bytes in the scan response, ~6 in
AdvData once Flags and the 128-bit UUID are subtracted. **Record what the phones
actually report.** The RoadLink beacon needs 13.

### S1 — discovery

Phone A: **PHONE A (peripheral)** → expect `S1: ADVERTISING started`.
Phone B: **PHONE B (central)** → expect `S1: HIT <addr> rssi=<n> mfgData=<hex>`.

The `mfgData` hex line is the T11 privacy evidence. Confirm by eye that it is
13 bytes and contains no coordinates and nothing rider-identifying.

### S2 — GATT connect

Automatic once S1 hits. Expect on B: `S2: CONNECTED` → `S3: MTU -> <n>` →
`S2: services discovered, 3 characteristics`. On A: `S2: central CONNECTED`.

Record the negotiated MTU. Nothing depends on it — long reads fall back to
ATT_READ_BLOB — but it belongs in the report.

### S3 — read / notify

Automatic. B reads `SOS_PACKET`, then enables notifications via CCCD. Verify the
round trip by tapping **Notify (A)** on Phone A; B should log
`S3: NOTIFICATION <n> B: "PING …"`.

### S4 — acknowledgement

Automatic after the CCCD write. B logs `S4: ACK write CONFIRMED`; A logs
`S4: ACK received … "ACK|rl_relay_xxxx|<ts>"` followed by
`S4 COMPLETE: event would now move QUEUED_OFFLINE -> RELAYING`.

**This is the Phase 1 gate from both source documents** — "Phone A sends a test
SOS to Phone B and Phone B acknowledges it."

### S5 — disconnect / reconnect

1. Tap **Stop** on B → clean disconnect, no retry scheduled.
2. Restart B, let it reconnect, then walk it out of range mid-transfer. Expect
   `S5: DISCONNECTED … status=<n>` then `S5: reconnect attempt N in <delay>ms`
   with backoff 1s, 2s, 4s, 8s, 16s (capped 30s, 5 attempts).
3. Return to range and confirm it converges without a duplicate ACK.

### T6 — repeatability

Tap **New event** on A (fresh event_id), **Rescan (B)** on B, repeat S1→S4
**30 times**. Record the success count and discovery latency from the `+ms`
column. Do not round, do not average away failures, and report the sample size.

## Not in this APK, deliberately

No `INTERNET`, no `FOREGROUND_SERVICE`, no unbounded `ACCESS_FINE_LOCATION`, no
Room, no real SOS packet. Those arrive at S6–S9. Keeping them out means the T3
permission matrix measures only what BLE actually needs.

## Recording results

For each phone, append to `docs/phase1-spike-report.md`: exact model, Android
version, OEM skin, the full S0 probe output, both T2 ceilings, and the run log.
Numbers go in the report only if they came off a device.
