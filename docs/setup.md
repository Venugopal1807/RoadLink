# Setup

From a clean checkout to a running app. Roughly 10 minutes, most of it the first
Gradle sync.

## Prerequisites

| | Version used | Notes |
|---|---|---|
| JDK | 17 | Gradle and the Kotlin toolchain both target 17 |
| Python | 3.11+ (3.14 used) | Backend only; stdlib `sqlite3` |
| Android SDK | compileSdk 36 | Android Studio, or `sdkmanager` |
| Android device or AVD | API 26+ | minSdk is 26 |

Nothing else. No Docker, no database server, no cloud account, no API keys.

## 1. Backend

```bash
cd backend
pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Bind `0.0.0.0` rather than `127.0.0.1` so phones can reach it over the LAN.
Check it:

```bash
curl http://localhost:8000/healthz
# {"ok":true,"service":"roadlink-spike","require_sig":false}
```

The database is created on first start at `backend/roadlink_spike.db`. Override
the path with `ROADLINK_DB`.

Interactive API docs are at `http://localhost:8000/docs`.

### Backend environment variables

| Variable | Default | Effect |
|---|---|---|
| `ROADLINK_DB` | `backend/roadlink_spike.db` | SQLite file path |
| `ROADLINK_REQUIRE_SIG` | `0` | `1` rejects packets failing signature verification with 403 |
| `ROADLINK_ALLOW_RESET` | `0` | `1` enables `POST /api/v1/debug/reset` |

`ROADLINK_REQUIRE_SIG` defaults off deliberately. During development a
canonicalisation mismatch between Kotlin and Python is far more likely than an
attacker, and silently dropping emergency events while debugging that would be
the worse failure. Every event records `sig_valid` either way.

## 2. Find your machine's LAN IP

Needed for physical phones. An emulator can skip this.

```bash
ipconfig          # Windows — look for IPv4 Address, e.g. 192.168.1.23
ip addr           # Linux
ipconfig getifaddr en0   # macOS
```

Phone and laptop must be on the same network. Guest and client-isolated Wi-Fi
will silently block this.

## 3. Android app

```bash
cd android
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

The backend URL is a build property, so pointing the app somewhere else needs no
code change:

```bash
# emulator (default) — 10.0.2.2 is the host loopback as seen from an AVD
./gradlew :app:assembleDebug

# physical phone — your LAN IP from step 2
./gradlew :app:assembleDebug -Proadlink.backendUrl=http://192.168.1.23:8000
```

Install:

```bash
adb devices -l
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and Run.

### On first launch

Grant Bluetooth and Location permissions when prompted. Location is needed for
the GPS fix attached to an emergency, and on Android 11 and below it
additionally gates BLE scanning.

The app opens on three tabs: **Rider**, **Responder**, **Log**.

## 4. Verify the install works

```bash
adb logcat -s RLINK
```

Press **CREATE TEST SOS** on the Rider tab. Expect:

```
PERSISTED <id> origin=SIMULATED - safe on disk before any delivery attempt
ATTEMPT <id> via DIRECT NETWORK (REAL) attempt=1
direct upload of <id> accepted, HTTP 201
DELIVERED <id> via DIRECT NETWORK
```

The Responder tab should then show the emergency, and
`curl http://localhost:8000/api/v1/sos/active` should return it.

If delivery fails, see Troubleshooting below.

## 5. Run the tests

See [`testing.md`](testing.md) for the full matrix and what each suite proves.

```bash
cd backend && python tests/test_idempotency.py     # 61 assertions
cd android && ./gradlew :app:testDebugUnitTest     # 60 JVM tests
cd .. && python tools/verify_wire_compat.py        # cross-language wire check
```

## Two phones, for the BLE path

One APK installs on both; roles are chosen at runtime from the Rider tab, so
there is no "Phone A build" to keep straight. Follow
[`physical-ble-procedure.md`](physical-ble-procedure.md) rather than
improvising — it gates on the S0 capability probe first, and that probe decides
whether the topology is possible on your hardware at all.

## Troubleshooting

**`Cleartext HTTP traffic to ... not permitted`**
The prototype backend has no TLS certificate, so cleartext is permitted to
loopback and private-LAN ranges only, via `res/xml/network_security_config.xml`.
If your backend is on a public IP this will correctly refuse. Use a LAN address.

**Delivery fails with a connection error from a physical phone**
The app was probably built with the default emulator URL. Rebuild with
`-Proadlink.backendUrl=http://<LAN-IP>:8000`. Confirm the phone can reach it by
opening `http://<LAN-IP>:8000/healthz` in the phone's browser.

**Emergencies stay `QUEUED` forever**
That is correct behaviour when nothing can deliver them, and is the product
working as designed. Check the **Force rider offline** switch on the Rider tab,
then check the Log tab — it states which transport was unavailable and why.

**`app:kspDebugKotlin` fails after changing an entity**
The Room schema is exported to `android/app/schemas`. A schema change needs a
version bump and a migration; there is deliberately no destructive fallback,
because that would discard undelivered emergencies.

**Backend port already in use**
Pass `--port 8001` and rebuild the app with a matching `roadlink.backendUrl`.
