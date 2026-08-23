# Limitations

What RoadLink does not do, cannot yet claim, and has deliberately not built.
Security-specific limits are in [`security.md`](security.md); current evidence
status is in [`current-project-status.md`](current-project-status.md).

## What the project may claim

> RoadLink preserves an emergency locally when connectivity fails and delivers
> it automatically when a viable communication path becomes available.

That is demonstrated end to end, with test evidence.

## What it may not claim

Not now, and not until measured:

- **Guaranteed delivery.** Delivery is best-effort over whatever path exists.
  RoadLink guarantees the emergency is not *lost*, which is a different and
  smaller promise.
- **Guaranteed rescue.** RoadLink delivers data to a server. It dispatches
  nobody.
- **Works on every Android phone.** The BLE peripheral role is a per-chipset
  fact, not an OS guarantee.
- **Works without any network anywhere.** Something in the chain must eventually
  reach the backend — the rider's phone, or a relay that later regains
  connectivity.
- Any figure for detection accuracy, false-positive rate, BLE range, delivery
  latency, relay success rate, battery cost or number of users. None have been
  measured, so none appear anywhere in this repository.

## Not validated on hardware

**Phone-to-phone BLE has never run on a radio.** The transport is implemented
and its codecs and orchestration are unit-tested, but no advertising, scanning,
GATT connection, packet transfer or acknowledgement has occurred over a real
Bluetooth radio. `BleRelayTransport.enabled` is `false` by default, so the
transport reports itself unavailable rather than pretending.

The decisive unknown is whether `getBluetoothLeAdvertiser()` returns non-null on
the target handsets. Central-only Android devices exist; on those, this topology
is impossible rather than merely slow. If both test phones turn out to be
central-only, the honest outcome is to record that and leave the transport
disarmed — the offline queue plus direct upload remains the demonstrated product
path.

See [`physical-test-results.md`](physical-test-results.md).

## Functional gaps

- **Crash detection is a button.** `CrashDetector` exists as an interface with a
  `TestCrashDetector` implementation. There is no accelerometer or gyroscope
  fusion, no impact threshold, no inactivity timer, no ML classifier. Every
  emergency in this build originates from **CREATE TEST SOS** and is labelled
  `SIMULATED` in the signed packet.
- **No emergency contacts, SMS, or phone call.** RoadLink delivers to its own
  backend and nowhere else.
- **No responder dispatch workflow.** The responder view is read-mostly; there
  is a status-update endpoint but no assignment, routing or navigation.
- **Single hop only.** A relay carries an emergency to the backend. It does not
  forward to another relay. Multi-hop mesh is not implemented.
- **Foreground only.** The delivery loop runs while the app process is alive.
  There is no foreground service, no `WorkManager` job and no boot receiver, so
  a queued emergency is retried on next launch rather than continuously in the
  background. Behaviour under OEM battery management is UNKNOWN and is T7 in the
  physical ladder.
- **No location fallback.** If there is no GPS fix the emergency carries a null
  position and the responder view says `no fix reported`. It does not fall back
  to a last-known or network-derived location.

## Infrastructure limits

- **SQLite, not PostgreSQL.** `backend/app/db.py` is the single swap point. WAL
  plus `BEGIN IMMEDIATE` keeps concurrent ingestion correct, but this is a
  prototype store on one machine.
- **LAN only, cleartext HTTP.** The prototype backend has no TLS certificate.
  Cleartext is permitted to loopback and private-LAN ranges only, via
  `res/xml/network_security_config.xml`, with the base config still denying it.
  A real deployment uses HTTPS and deletes that file rather than relaxing it.
- **No authentication on any endpoint**, including the debug ones (which are
  additionally env-gated).
- **No horizontal scaling story.** One uvicorn process, one SQLite file.

## Known behavioural quirks

- **Duplicate submission is expected, not suppressed.** The rider and any number
  of relays may each submit the same emergency. The backend keeps one event row
  and appends an audit row per submission. This is the design.
- **The audit trail can look surprising.** An emergency that spent a long time
  offline shows a low attempt count with a small number of `UNAVAILABLE` rows —
  transports that reported themselves unavailable were never tried, and their
  reason is recorded once rather than on every pass.
- **Delivered emergencies are never cleaned up.** There is no retention policy
  and no delete path anywhere in the store. On a long-lived install the local
  database grows without bound.

## Deliberately not built

Ambulance dispatch · medical diagnosis or triage · 112/ERSS or any government
integration · guaranteed response · multi-hop mesh · dedicated hardware ·
satellite · ML crash classifier · user accounts · analytics dashboards ·
production infrastructure.

None of these matter until the core flow works, and claiming any of them would
be dishonest.

## Scaling, if it were taken further

Stated as a direction, not as work performed:

- Swap SQLite for PostgreSQL at `db.py`; the repository boundary already exists
  and idempotency is keyed on `event_id`, which is generated on the device and
  never changes.
- Ingestion is stateless apart from that database, so the API layer scales
  horizontally behind a load balancer.
- Per-device Ed25519 keys with the private key in the Android Keystore, replacing
  the shared HMAC key — see [`security.md`](security.md).
- Move the delivery loop into a foreground service with `WorkManager` for
  cross-reboot durability.
- The transport interface is the extension point for further paths — SMS,
  satellite, LoRa — without touching the domain, the store or the UI.
