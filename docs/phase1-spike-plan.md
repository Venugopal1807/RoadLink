# RoadLink — Phase 1 Technical Spike Plan

**Status:** Pre-implementation assessment. Nothing in this document is a measured result.
Every number is either (a) a value fixed by the Bluetooth Core Spec / Android API contract, or
(b) explicitly marked `TO BE MEASURED`.

**Date:** 2026-08-21
**Scope:** Prove `Phone A → persist → BLE → Phone B → ack → forward → FastAPI → idempotent record`.

---

## A. Environment Assessment

### A.1 Verified present

| Component | Version | How verified |
|---|---|---|
| Android SDK root | `C:\Users\91630\AppData\Local\Android\Sdk` | `ANDROID_HOME` set |
| Platform APIs | android-31, 34, 35, 36 | dir listing |
| Build-tools | 34.0.0, 35.0.0, 35.0.1, 36.0.0, 36.1.0 | dir listing |
| platform-tools (adb) | 36.0.0 | `source.properties` |
| cmdline-tools | 17.0 | dir listing |
| NDK | 27.0.12077973, 27.1.12297006, 29.0.14206865 | dir listing (not needed for this spike) |
| JDK | Microsoft OpenJDK 17.0.17 LTS | `java -version` |
| Android Studio | AI-252.27397.103 (2025.2.2 line) | `product-info.json` |
| Gradle distribution | 9.0.0 (cached in `~/.gradle/wrapper/dists`) | dir listing |
| Android Gradle Plugin | 8.13.0 (in Gradle module cache) | dir listing |
| Kotlin Gradle Plugin | 2.2.21 (in Gradle module cache) | dir listing |
| Python | 3.14.0 | `python --version` |
| FastAPI / Uvicorn / Pydantic | 0.141.1 / 0.52.4 / 2.13.4 | `pip list` |
| Node | v24.18.0 | `node --version` |
| Git | 2.45.1 | `git --version` |
| Docker | present on PATH | `Get-Command docker` |

### A.2 Verified ABSENT — these are the blockers

| Gap | Evidence | Impact |
|---|---|---|
| **Zero Android devices connected** | `adb devices` → empty list | **Blocks spike requirements 6, 7, 10, 11, 12, 13** |
| **Zero AVDs defined** | `emulator -list-avds` → empty | No emulator fallback exists yet either |
| Gradle not on PATH | `Get-Command gradle` → not found | Non-issue; use the Gradle wrapper |
| PostgreSQL | not checked / not required in Phase 1 | Deferred to Phase 3; spike uses SQLite |
| Maven/network reachability | **not verified** (probe declined) | Gradle sync may need network for androidx artifacts not already cached |

`~/.android/adbkey.pub` exists, meaning devices *have* been paired with this machine
before. So USB debugging has worked here previously — the phones are simply not
attached right now.

### A.3 Toolchain decision (derived from what is actually cached, not guessed)

```
Gradle           9.0.0        (already cached → offline-capable)
AGP              8.13.0       (already cached)
Kotlin           2.2.21       (already cached)
JDK toolchain    17           (installed; AGP 8.13 requires 17+)
compileSdk       36
targetSdk        36
minSdk           26           (Android 8.0)
```

**Why `minSdk 26`:** API 26 is the floor for `BluetoothLeAdvertiser.startAdvertisingSet()`
(BLE 5 extended advertising), for `startScan(filters, settings, PendingIntent)`
(process-death-surviving background scan), and for the modern foreground-service model.
Below 26 the relay role cannot be built the way this design needs. This is a
capability floor, not a market-share choice.

---

## B. Architecture Risks

Ranked by *probability × damage to the demo*.

| # | Risk | Why it is real | Mitigation / how Phase 1 tests it |
|---|---|---|---|
| R1 | **No two physical devices** | Confirmed: zero connected | Blocking. Emulators can validate protocol logic only — see §C.5 |
| R2 | **OEM background-execution kill** (Xiaomi/MIUI, Oppo/ColorOS, Vivo, Realme, Samsung) | These OEMs kill background services far more aggressively than AOSP documents | Run the relay in a **foreground service** with a visible notification. Test T7 leaves the relay backgrounded and screen-off for 10 min |
| R3 | **Extended advertising unsupported on the actual chipset** | `isLeExtendedAdvertisingSupported()` is chipset-dependent, not an OS guarantee | Design must not *depend* on it. Test T1 measures it |
| R4 | **Advertisement payload too small for the SOS packet** | Legacy AdvData is hard-capped at 31 bytes (Core Spec) | Design assumes it *is* too small — beacon-only advert + GATT transfer. See §F |
| R5 | **Privacy leak via open broadcast** | Product invariant: no rider info in open adverts | Advert carries a truncated hash + coarse metadata only. Reviewed in test T2 |
| R6 | **Scan requires Location Services toggle on some OEMs** even with `neverForLocation` | Known OEM divergence from AOSP behavior | Test T3 explicitly toggles system Location off and re-scans |
| R7 | **Duplicate submission** (rider regains connectivity *and* relay forwards) | Explicitly expected by design | Backend idempotency on `event_id`. Test T10 |
| R8 | **Clock skew between devices** | `created_at` is device-local | Backend records both `created_at` (device) and `received_at` (server). Never trust device clock for ordering |
| R9 | **BLE MAC randomization defeats scan-side dedup** | Android rotates the advertising address | Dedup on the 8-byte `event_ref` in the advert, never on MAC |
| R10 | **Spike becomes the product** | Spike code is throwaway by design | Spike lives in its own module (`:spike-ble`), deleted or rewritten in Phase 2 |

---

## C. BLE Feasibility Assessment

### C.1 Payload budget — these are spec constants, not estimates

Legacy advertising (`ADV_IND`) carries **31 bytes** of AD structures. Each AD structure
costs `1 (length) + 1 (type) + N (data)`.

| AD structure | Cost | Note |
|---|---|---|
| Flags | **3 bytes** | Android adds this automatically for connectable advertising |
| Complete 128-bit Service UUID list | **18 bytes** | 1 + 1 + 16 |
| **Subtotal** | **21 bytes** | |
| **Remaining in AdvData** | **10 bytes** | Only 6 usable after manufacturer-data overhead |

Scan response is a **separate 31 bytes**, requires no Flags structure, and is returned to
any active scanner:

| AD structure | Cost |
|---|---|
| Manufacturer Specific Data overhead (1 len + 1 type + 2 company ID) | **4 bytes** |
| **Usable payload in scan response** | **27 bytes** |

**Conclusion:** ~27 bytes of usable connectionless payload. The full SOS packet
(event_id, rider_id, lat/lng, timestamp, confidence, signature) cannot fit, and
per invariant R5 it must not be broadcast openly even if it could.

BLE 5 extended advertising *may* raise this to `getLeMaximumAdvertisingDataLength()`
(commonly ~1650 bytes on supporting chipsets) — but this is **chipset-dependent and must
not be a design dependency**. It will be measured, not assumed.

### C.2 Why acknowledgement forces a connection

Requirement 11 (Phone B acknowledges) and Architecture §5 step 5 both require an ack so
Phone A can stop advertising and move to `RELAYING`.

Connectionless advertising has **no return channel**. Implementing ack over adverts would
require Phone A to *also* scan for Phone B's ack advert — doubling role complexity on both
devices and still giving no delivery guarantee. A GATT connection gives an acknowledged,
reliable, MTU-negotiated channel for free.

### C.3 Why GATT read is bounded and does not need manual chunking

A GATT characteristic value may be up to **512 bytes**. Android's `readCharacteristic()`
transparently uses ATT `READ_BLOB` for values exceeding `MTU-1`, so a packet capped at
512 bytes transfers in a single logical read regardless of negotiated MTU. We will still
call `requestMtu(517)` to reduce round trips, but **correctness must not depend on the MTU
request succeeding.**

### C.4 DECISION — Advertisement + GATT

**Chosen: advertisement as a beacon/trigger only; GATT for the payload and the ack.**

Rejected alternatives:

| Option | Why rejected |
|---|---|
| **Advertisement-only** | (1) No ack channel — fails requirement 11. (2) Full packet cannot fit in legacy 31 bytes. (3) Extended advertising is chipset-dependent, so it cannot be the primary path. (4) Broadcasting location/rider data openly violates the privacy invariant. |
| **GATT-only (no advert)** | A peripheral that does not advertise is undiscoverable. Not physically possible. |
| **BLE mesh / multi-hop** | Explicitly out of scope per both source documents until the two-device path is stable. |
| **Wi-Fi Direct / Nearby Connections** | Higher power, slower discovery, heavier permissions, and Nearby Connections adds a Google Play Services dependency. Reconsider only if BLE fails the spike. |
| **SMS as the primary fallback** | Retained as a *candidate secondary path*, not part of Phase 1. `SEND_SMS` triggers Play Store policy review, needs a real recipient, costs money per test, and cannot be verified end-to-end in a spike. Deferred with a written rationale. |

**Role assignment:** Phone A (rider, in distress) is the **GATT peripheral + advertiser**.
Phone B (relay) is the **GATT central + scanner**. This matches the physical reality — the
device with the emergency announces itself; the helper reaches out.

### C.5 What emulators can and cannot prove

If physical devices are unavailable, Android Emulator 33.1.11+ with Google APIs images can
emulate Bluetooth between two instances (RootCanal packet streamer). It would let us
validate **protocol logic and code correctness only**.

It **cannot** satisfy:
- Requirement 6 (real permission + background behavior) — no OEM battery layer exists
- Requirement 7 (actual payload limit) — emulated radio does not reflect real chipset limits
- Realistic RSSI, range, scan throttling, or reconnect timing

Any result obtained on emulators will be labelled `EMULATED — NOT DEVICE-VERIFIED` and will
**not** be used to freeze the communication design (requirement 15).

---

## D. Phase 1 Test Plan

Each test states its **procedure**, what is **recorded**, and its **pass condition**.
Every run is logged with logcat tag `RLSPIKE` and exported to `docs/spike-runs/`.

| ID | Test | Procedure | Recorded | Pass condition |
|---|---|---|---|---|
| **T1** | Radio capability probe | On each device read `isLeExtendedAdvertisingSupported()`, `isLe2MPhySupported()`, `isLeCodedPhySupported()`, `getLeMaximumAdvertisingDataLength()`, `isMultipleAdvertisementSupported()` | Raw values per device | Recorded, not pass/fail — this *is* requirement 7 |
| **T2** | Empirical advert payload ceiling | Advertise manufacturer data of N bytes, N = 1..40, catch `ADVERTISE_FAILED_DATA_TOO_LARGE`. Repeat for AdvData and scan response | Largest N accepted in each | Measured ceiling ≥ 13 bytes in scan response |
| **T3** | Permission matrix | Grant/deny each of `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`; also toggle system Location and Bluetooth off | Which combination breaks scan / advertise | A written truth table for the demo runbook |
| **T4** | Persist-before-transmit | Create SOS on A with airplane mode ON. Force-stop the app immediately after creation | Room row present after restart | Event survives force-stop. **Invariant** |
| **T5** | Happy-path relay | A advertises → B scans → B connects → B reads packet → B writes ack → A marks `RELAYING` | Timestamps for each transition | Completes; A reaches `RELAYING` |
| **T6** | Repeatability (requirement 10) | Run T5 **30 consecutive times**, app restarted between runs | Success count, discovery latency per run | Report the real success rate and latency distribution. **No target number is being invented in advance** |
| **T7** | Background / screen-off | B backgrounded, screen off, 10 min, foreground service active | Whether discovery still fires | Discovery works, or the failure is documented with the OEM name |
| **T8** | No connectivity (requirement 12) | Both devices airplane mode + BT on. Run T5 | State transitions | A reaches `RELAYING`; B holds the event queued; nothing is deleted |
| **T9** | Reconnect / retry (requirement 13) | Walk B out of range mid-GATT-read; return after 60 s | Retry attempts, backoff, final state | Transfer completes or retries cleanly; no duplicate ack, no lost event |
| **T10** | Duplicate submission (requirement 14) | Have B POST the same `event_id` 3×, then have A POST it directly | HTTP codes, DB row count, audit row count | Exactly **1** event row; **4** audit rows; first 201, rest 200 `duplicate:true` |
| **T11** | Privacy review | `btmon` / logcat dump of the raw advert bytes | Full hex of AdvData + scan response | No rider_id, no lat/lng, no device name in the clear |

**Note on T6:** the success rate produced by T6 is the *only* delivery number RoadLink is
permitted to quote. It will be reported with its sample size and the device models it was
measured on.

---

## E. Minimal Project Structure

```
RoadLink/
├─ README.md
├─ docs/
│  ├─ phase1-spike-plan.md          ← this file
│  ├─ phase1-spike-report.md        ← written AFTER tests run
│  ├─ spike-runs/                   ← raw logcat exports, one per run
│  └─ decisions/
│     └─ ADR-001-ble-transport.md
├─ android/
│  ├─ settings.gradle.kts
│  ├─ build.gradle.kts
│  ├─ gradle.properties
│  ├─ gradle/
│  │  ├─ libs.versions.toml
│  │  └─ wrapper/
│  └─ spike-ble/                    ← THROWAWAY spike module
│     ├─ build.gradle.kts
│     └─ src/main/
│        ├─ AndroidManifest.xml
│        └─ kotlin/com/roadlink/spike/
│           ├─ SpikeActivity.kt           minimal UI: 3 buttons + log view
│           ├─ ble/RoadLinkUuids.kt
│           ├─ ble/Beacon.kt              13-byte encode/decode
│           ├─ ble/Advertiser.kt          Phone A: advertise
│           ├─ ble/GattServer.kt          Phone A: serve packet, take ack
│           ├─ ble/Scanner.kt             Phone B: scan + filter
│           ├─ ble/GattClient.kt          Phone B: read packet, write ack
│           ├─ ble/RadioCapabilities.kt   T1 + T2 probes
│           ├─ data/SosPacket.kt
│           ├─ data/SpikeDb.kt            Room: events + audit
│           ├─ net/Uploader.kt            POST with retry/backoff
│           └─ svc/RelayService.kt        foreground service
└─ backend/
   ├─ requirements.txt
   ├─ app/
   │  ├─ main.py                    FastAPI app
   │  ├─ db.py                      stdlib sqlite3 repository layer
   │  ├─ schemas.py                 Pydantic request/response models
   │  └─ canonical.py               canonical serialization + HMAC signing
   └─ tests/test_idempotency.py     stdlib urllib, runs against live uvicorn
```

Deliberately **one** Android module for the spike. No `:core`/`:domain`/`:data` split yet —
that structure gets designed in Phase 2 once the communication design is frozen, so the
module boundaries reflect what BLE actually turned out to need.

---

## F. BLE Protocol Proposal

### F.1 Identifiers

```
Service            5a746561-2f72-41ee-b188-1b938007e52a   RoadLink Emergency Service
Char  SOS_PACKET   56d053bc-a578-44e4-8abe-4df8b607ec7a   READ            ≤512 bytes
Char  ACK          222881b9-b12a-46fb-a11e-b5fb6e9389dc   WRITE (w/ resp)
Char  SPIKE_META   9c425ae0-66bd-4dea-8ee5-9d87b1698cce   READ            capability probe
Company ID         0xFFFF                                  SIG "testing/development" value
```

`0xFFFF` is the Bluetooth SIG value reserved for development. A real deployment requires a
SIG member company ID. This is documented as a known prototype shortcut, not hidden.

### F.2 Advertisement layout (legacy, 31 + 31)

```
AdvData        (31 B budget)
  Flags                                3 B   [added by Android]
  Complete 128-bit Service UUID       18 B
  ──────────────────────────────────────
  used                                21 B     free: 10 B

ScanResponse   (31 B budget)
  Manufacturer Specific Data overhead  4 B   (company 0xFFFF)
  RoadLink beacon payload             13 B
  ──────────────────────────────────────
  used                                17 B     free: 14 B (headroom)
```

### F.3 Beacon payload — 13 bytes

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `schema_version` | `0x01` |
| 1 | 1 | `flags` | bit0 `simulated`, bit1 `needs_gatt`, bit2 `has_location`, bit3 `ack_wanted` |
| 2 | 8 | `event_ref` | **first 8 bytes of SHA-256(event_id)** — one-way, not the id itself |
| 10 | 1 | `confidence` | 0–100 |
| 11 | 2 | `age_seconds` | uint16 big-endian, saturating at 65535 |

**`event_ref` exists because Android randomizes the BLE MAC address**, so a scanner cannot
dedupe on address. It is a truncated hash rather than the raw `event_id` so that a passive
eavesdropper cannot correlate a broadcast with a backend record. The authoritative
`event_id` is only ever transferred over the GATT connection.

`flags.simulated` is carried all the way to the responder UI. Per the handoff, a simulated
crash must be visibly distinguishable from a real sensor trigger at every layer.

### F.4 Exchange sequence

```
Phone A (rider, peripheral)              Phone B (relay, central)
──────────────────────────               ────────────────────────
1. persist event to Room  ◄── MUST happen before anything below
2. open GATT server
3. startAdvertising(service UUID + beacon)
                                          4. startScan(filter = service UUID)
                                          5. beacon seen; event_ref unseen → act
                                          6. connectGatt()
7. onConnectionStateChange                7. requestMtu(517)  [best-effort]
                                          8. discoverServices()
                                          9. read SOS_PACKET  ──► ≤512 B
                                         10. verify signature
                                         11. persist copy locally
                                         12. write ACK {event_id, relay_id, ts}
13. ack received → state = RELAYING
14. stop advertising for this event
                                         15. disconnect
                                         16. when online: POST /api/v1/sos
```

### F.5 Failure handling

| Failure | Behavior |
|---|---|
| No ack within 30 s | A keeps advertising; **never** deletes the event |
| GATT read fails mid-transfer | B retries with exponential backoff (1s, 2s, 4s, 8s, cap 30s); A stays in `QUEUED_OFFLINE` |
| B goes out of range | A resumes advertising; B keeps whatever it persisted and still forwards it if it holds a complete packet |
| B never gets connectivity | Event stays queued on B **and** A keeps retrying independently. Two independent delivery attempts is the intended design, and is safe because the backend is idempotent |
| Signature invalid | B drops the packet and logs it. B does **not** forward unverified packets |

---

## G. SOS Packet Proposal

Wire format for the spike: **compact UTF-8 JSON, ≤ 512 bytes** (fits one GATT
characteristic). JSON is chosen over CBOR because the spike's priority is debuggability;
if Phase 2 measurements show size pressure, swap the codec — the schema does not change.

```jsonc
{
  "v": 1,
  "event_id": "9f2c...-...",     // UUIDv4, generated on-device at CONFIRMED_EMERGENCY
  "rider_id": "rl_7f3a91c4",     // opaque device-scoped id. NOT name, phone, or email
  "created_at": 1755763200123,   // epoch millis, DEVICE clock
  "lat": 17.4401,
  "lng": 78.3489,
  "acc_m": 12,                   // GPS accuracy; null if no fix
  "conf": 82,                    // crash confidence 0-100
  "trigger": ["peak_g","inactivity"],
  "simulated": true,             // NEVER omitted
  "sig": "base64-16-bytes"       // HMAC-SHA256 truncated to 16 B
}
```

### G.1 Idempotency

- `event_id` is a **UUIDv4 generated on-device** at the `CONFIRMED_EMERGENCY` transition,
  before the Room write and before any transmission. It never changes afterward.
- It is the primary key at the backend.
- Ingest is `INSERT ... ON CONFLICT (event_id) DO NOTHING`, followed by an
  **unconditional append** to the audit table. A duplicate submission is not an error — it
  is a legitimate second delivery path, and it still produces an audit row.

### G.2 Signature — honest limitation

The spike uses an **HMAC-SHA256 with a pre-shared key compiled into the app**. This proves
the *plumbing* (canonical serialization, verify-before-forward, reject-on-tamper) but it is
**not real key management** — anyone with the APK has the key.

The production design is a per-device Ed25519 keypair with the private key in the Android
Keystore and the public key registered at the backend. That is a Phase 2–3 decision and is
not being claimed as done.

---

## H. Android Permission Requirements

### H.1 Manifest

```xml
<!-- BLE, Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- BLE, Android 11 and below (API ≤30) -->
<uses-permission android:name="android.permission.BLUETOOTH"       android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
                 android:maxSdkVersion="30" />   <!-- required for SCAN on ≤30 -->

<!-- Rider role needs real location regardless of BLE -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- Foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />   <!-- API 33+ -->

<!-- Network -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

<service android:name=".svc.RelayService"
         android:foregroundServiceType="connectedDevice"
         android:exported="false" />
```

### H.2 Notes that matter

- **`neverForLocation` on `BLUETOOTH_SCAN`** lets the relay scan without holding
  `ACCESS_FINE_LOCATION`. The rider role still requests fine location — for GPS, which is a
  separate concern. Keeping these separate is what lets a pure relay device run with fewer
  permissions.
- **`ACCESS_BACKGROUND_LOCATION` is deliberately NOT requested** in Phase 1. It triggers a
  separate "Allow all the time" settings-screen flow and is not needed while a foreground
  service is running. Revisit only if T7 shows it is required.
- **Runtime grants required** (not just manifest): `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`,
  `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`.
- **Non-permission preconditions** the demo runbook must cover: Bluetooth adapter enabled;
  on some OEMs the system Location toggle must be on for scan results to be delivered even
  with `neverForLocation`. **T3 measures this per device** rather than assuming AOSP
  behavior.
- **Android 14+** requires the FGS type to be declared *and* the matching
  `FOREGROUND_SERVICE_*` permission, or `startForeground()` throws.

---

## I. Backend Test Endpoint

FastAPI + **SQLite** for Phase 1. PostgreSQL is a Phase 3 concern — introducing it now adds
a Docker dependency to every spike run for no gain.

**Revised during implementation:** SQLAlchemy is not installed on this machine, and neither
are pytest or httpx. Rather than take an install/network dependency for a spike, the
backend uses **stdlib `sqlite3` behind a thin repository layer** (`app/db.py`), and the
test suite uses **stdlib `urllib` against a live uvicorn process**. Two consequences, both
good: the backend has zero dependencies beyond FastAPI/uvicorn/pydantic which were already
present, and the tests exercise the real HTTP path — which is what makes the concurrent
duplicate-submission test meaningful. `app/db.py` remains the single swap point for
PostgreSQL in Phase 3.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/healthz` | Liveness; used by the device to decide "am I online" |
| `POST` | `/api/v1/sos` | Idempotent ingest. `201` first time, `200 {"duplicate":true}` after |
| `GET` | `/api/v1/sos/active` | Responder list |
| `GET` | `/api/v1/sos/{event_id}` | Detail + full audit trail |
| `GET` | `/api/v1/debug/audit` | Spike-only: raw audit rows for test verification |

Tables:

```
events(event_id PK, rider_id, created_at, received_at, lat, lng, acc_m,
       conf, trigger, simulated, state, first_delivery_path)

audit(id PK, event_id FK, at, actor, action, delivery_path, relay_id, detail)
```

`received_at` is **server** time. `created_at` is device time and is never used for
ordering — R8.

---

## J. Acceptance Criteria for the Spike

The spike is **done** when every one of these is true and evidenced by a log file:

1. T1 and T2 have produced recorded radio-capability and payload-ceiling numbers **for both
   physical devices**, and those numbers are in `phase1-spike-report.md`.
2. T4 passes: an event created in airplane mode survives a force-stop. (Invariant)
3. T5 passes end-to-end at least once with timestamps for all transitions.
4. T6 has been run **30 times**, and the observed success rate + discovery-latency
   distribution are recorded with device model names and sample size.
5. T8 passes: with zero connectivity on both devices, the packet still moves A → B and
   nothing is deleted.
6. T9 passes: an interrupted transfer retries and converges, with no duplicate ack.
7. T10 passes exactly: 4 submissions of one `event_id` → **1** event row, **4** audit rows.
8. T11 passes: a raw byte dump of the advertisement contains no rider identity and no
   coordinates.
9. T3's permission truth table is written down and reproducible.
10. `ADR-001-ble-transport.md` records the final transport decision **with the measured
    numbers that justify it** — not the predicted ones in this document.

Only when 1–10 hold does the communication design get frozen (requirement 15).

**If T6's success rate is poor:** that is a *result*, not a failure of the project. The
documented fallback (offline queue + auto-upload on reconnect as a complete standalone
product, BLE demoted to an opportunistic enhancement) activates, and we say so plainly.

---

## K. Explicitly NOT Building Yet

**Not in Phase 1:**

- Any polished UI. The spike UI is three buttons and a scrolling log.
- The real sensor-fusion crash engine. Phase 1 uses a "Create test SOS" button.
- Room schema for the *product*. The spike gets a throwaway 2-table DB.
- PostgreSQL. SQLite until Phase 3.
- The responder web dashboard.
- Ed25519 / Keystore key management (HMAC placeholder only).
- SMS fallback path.
- Multi-hop / mesh relaying.
- Foreground-service notification polish, custom icons, theming.
- Retry-scheduling via WorkManager (spike uses a simple in-service backoff loop).
- Auth / rider accounts / onboarding.
- Play Store readiness, ProGuard, signing config, CI.

**Not building at all, per both source documents:**

- Ambulance dispatch.
- Medical diagnosis.
- 112 / ERSS integration, or any claim of it.
- Guaranteed mesh coverage.
- Dedicated hardware.
- A generic accident-reporting dashboard.
- Any ML crash classifier, unless it demonstrably beats the deterministic engine —
  and it is not being attempted in the 72-hour window.

**Numbers we will not state until measured:** detection accuracy, false-positive rate,
delivery success rate, relay discovery latency, end-to-end latency, battery impact, range.
Every one of these is currently blank by design.
