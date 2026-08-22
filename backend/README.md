# RoadLink Spike Backend

Phase 1 backend: one idempotent ingestion endpoint for SOS events, regardless of
whether they arrived direct from the rider or forwarded by a BLE relay.

## Run

```bash
cd backend
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Bind `0.0.0.0` so the two phones can reach it over the LAN during the BLE spike.
Find the host IP with `ipconfig` and point the app at `http://<host-ip>:8000`.

Interactive API docs: <http://127.0.0.1:8000/docs>

## Test

```bash
cd backend
python tests/test_idempotency.py
```

The script starts its own uvicorn on port 8077 against a throwaway temp database,
runs 48 assertions over real HTTP, tears the server down, and exits non-zero on
any failure.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `GET` | `/healthz` | Liveness. The device uses this to decide "am I online" |
| `POST` | `/api/v1/sos` | Idempotent ingest. `201` first time, `200` thereafter |
| `GET` | `/api/v1/sos/active` | Responder list (state RECEIVED or ACKNOWLEDGED) |
| `GET` | `/api/v1/sos/{event_id}` | Detail + full audit trail + `event_ref` |
| `PATCH` | `/api/v1/sos/{event_id}/status` | Responder status (ACKNOWLEDGED / CLOSED) |
| `GET` | `/api/v1/debug/audit` | Spike-only: raw audit rows + counts |
| `POST` | `/api/v1/debug/reset` | Spike-only: wipe DB. Needs `ROADLINK_ALLOW_RESET=1` |

## Request shape

The body is an envelope. `packet` is created once on the rider's device and signed;
it is byte-identical however it arrives. `delivery` describes *this* submission and
is deliberately outside the signature, so the same packet can be submitted by the
rider and by several relays without any signature mismatch.

```jsonc
{
  "packet": {
    "v": 1,
    "event_id": "9f2c…",          // UUIDv4, generated on-device
    "rider_id": "rl_7f3a91c4",    // opaque. never a name/phone/email
    "created_at": 1755763200123,  // epoch ms, DEVICE clock
    "lat": 17.4401, "lng": 78.3489, "acc_m": 12,
    "conf": 82,
    "trigger": ["peak_g", "inactivity"],
    "simulated": true,            // never omitted
    "sig": "base64-16-bytes"
  },
  "delivery": { "path": "ble_relay", "relay_id": "rl_relay_b" }
}
```

## Behaviour worth knowing

- **A duplicate is not an error.** The rider's phone and one or more relays are all
  expected to submit the same event. `INSERT OR IGNORE` inside a `BEGIN IMMEDIATE`
  transaction plus an *unconditional* audit append is what makes that safe.
- **First write wins.** If the same `event_id` arrives carrying different signed
  content, the stored event is never overwritten; the response sets
  `divergent: true` and an `ingest_duplicate_divergent` audit row is written.
- **Nothing is ever deleted.** Closing an event changes its state and drops it from
  the active list; the row and its audit trail remain.
- **Device clocks are not trusted.** `created_at` is device time and is never used
  for ordering. `received_at` is server time.
- **Signature failures are flagged, not rejected**, by default. During Phase 1 a
  Kotlin/Python canonicalisation mismatch is far likelier than an attacker, and
  silently dropping emergency events while debugging that would be the worse
  failure. Every event stores `sig_valid` either way. Set
  `ROADLINK_REQUIRE_SIG=1` to reject instead.

## Environment variables

| Var | Default | Purpose |
|---|---|---|
| `ROADLINK_DB` | `backend/roadlink_spike.db` | SQLite file path |
| `ROADLINK_REQUIRE_SIG` | `0` | `1` rejects packets failing signature check |
| `ROADLINK_ALLOW_RESET` | `0` | `1` enables `POST /api/v1/debug/reset` |

## Known Phase 1 limitations

- **HMAC with a key compiled into the app is not key management.** Anyone holding
  the APK holds the key. Production is a per-device Ed25519 keypair with the
  private key in the Android Keystore and the public key registered here.
- No auth on any endpoint. Spike only, LAN only.
- SQLite, not PostgreSQL. `app/db.py` is the swap point.
