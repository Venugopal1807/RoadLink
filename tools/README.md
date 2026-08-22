# Tools

## `verify_wire_compat.py` — Kotlin ↔ Python wire compatibility

Proves that a packet signed by the Android client verifies at the Python
backend, over real HTTP.

This is the check neither side can perform alone. The Kotlin unit tests pin the
canonical string against vectors copied from Python, and the backend tests pin
the Python side — but only an actual round trip proves the two agree on the
bytes, the JSON encoding and the coordinate precision simultaneously.

It matters because the failure mode is silent: a mismatch does not error, it
just stores every emergency with `sig_valid = false`, and nothing surfaces
until someone reads that column.

### Run

```bash
# 1. produce the signed envelope from the real Kotlin client
cd android
./gradlew :app:testDebugUnitTest --tests '*EnvelopeTest*'

# 2. POST those exact bytes at a real backend
cd ..
python tools/verify_wire_compat.py
```

The script starts its own uvicorn on port 8099 against a throwaway database,
submits the fixture verbatim, and exits non-zero on any mismatch.

### What it asserts

| Check | Why |
|---|---|
| `sig_valid == true` | The canonical string agrees across both languages |
| HTTP 201 then 200 | Idempotency on `event_id` |
| 1 event, 2 audit rows | A duplicate is recorded, not swallowed or overwritten |
| `first_delivery_path == simulated_relay` | A simulated hop is never recorded as `ble_relay` |
| `simulated == true` survives | The honesty marker crosses the wire intact |
| Coordinates round-trip exactly | 7dp on the wire matches 7dp in the signature |

### Last result

```
2026-08-22  PASSED
  sig_valid=true over real HTTP
  delivery path recorded as simulated_relay, not ble_relay
  identical resubmission -> 1 event, 2 audit rows
```
