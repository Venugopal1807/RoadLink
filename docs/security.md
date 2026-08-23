# Security and privacy

What RoadLink protects, what it does not, and which shortcuts are prototype
decisions rather than oversights.

> **This is a prototype, not a production security posture.** Several of the
> choices below would be unacceptable in a deployed system and are documented
> here as known limitations rather than presented as design.

## Threat model

An emergency-relay system has one property worth attacking and one worth
protecting:

| Concern | Addressed? |
|---|---|
| A nearby device injecting fake emergencies through an honest relay | **Yes** — packet signature verified before a relay stores or forwards |
| An emergency being silently modified in flight | **Yes** — the signature covers every field, including `simulated` |
| A passive listener tracking a rider from BLE advertisements | **Partly** — the beacon carries no identity, but see below |
| A duplicate submission corrupting the record | **Yes** — idempotent on `event_id`, append-only audit |
| An attacker reading emergencies off the backend | **No** — no authentication, no transport encryption |
| An attacker forging a packet after extracting the key from the APK | **No** — the HMAC key is shared and compiled in |
| Denial of service against the backend | **No** — no rate limiting, no auth |

## Packet integrity

Every emergency is signed at the moment of confirmation, before it is persisted
or transmitted: HMAC-SHA256 over a canonical string, truncated to 128 bits. The
canonicalisation is mirrored in `domain/Canonical.kt` and
`backend/app/canonical.py` and pinned by tests on both sides plus a
cross-language round trip (`tools/verify_wire_compat.py`).

Two consequences that matter:

- **A relay verifies before forwarding.** A packet whose signature does not
  check out is dropped, not relayed. Without this, any nearby device could
  inject emergencies into the backend through an honest relay.
- **A relay never re-signs.** It does not hold the rider's key, and re-signing
  would destroy the only evidence that the packet arrived unmodified.

The `simulated` flag is **inside** the signed canonical string, so no downstream
layer can relabel a simulated event as real without invalidating the packet.
That is a deliberate integrity property of the honesty marker, not just of the
payload.

### The key is a prototype limitation

`SPIKE_HMAC_KEY` is the literal string
`roadlink-phase1-spike-key-not-for-production`, shared between the app and the
backend and compiled into the APK.

**This is not key management.** Anyone holding the APK holds the key and can
forge packets. It proves the *canonicalisation* and *tamper-detection*
machinery works; it provides no real authentication.

It is deliberately a self-describing non-secret rather than a plausible-looking
key, so it can never be mistaken for a credential that leaked.

Production would use a per-device Ed25519 keypair with the private key generated
in and never leaving the **Android Keystore**, the public key registered at
enrolment, and the backend verifying per-rider. The signing call site is already
a single function, so this is a contained change.

## Privacy in the BLE advertisement

The advertisement is a **beacon/trigger only** — 13 bytes, and deliberately not
the payload:

| Field | Bytes | |
|---|---|---|
| schema version | 1 | |
| flags | 1 | simulated, needsGatt, hasLocation, ackWanted |
| `event_ref` | 8 | first 8 bytes of SHA-256(`event_id`) |
| confidence | 1 | |
| age | 2 | seconds, saturating |

It contains **no rider identity, no coordinates, no device name and not the raw
`event_id`**. `event_ref` is a truncated one-way hash so a passive listener
cannot correlate a broadcast with a backend record. Device name inclusion is
explicitly disabled in both the advertisement and the scan response, because the
default Android device name is frequently rider-identifying.

The actual emergency — coordinates included — travels only over the **connected
GATT link**, never in the open broadcast. The acknowledgement carries the
`event_id`, which is safe for the same reason.

`BleProtocolTest` asserts the beacon contains no raw event_id, no coordinate bit
pattern and no rider id. **That assertion is unit-tested, not sniffed.** T11 in
[`physical-ble-procedure.md`](physical-ble-procedure.md) is the over-the-air byte
dump that would confirm it on hardware, and it has not been run.

Residual exposure that is *not* solved: an observer within radio range learns
that *someone* nearby has an active emergency, its confidence and its age.
Android randomises the advertising MAC, which is why the relay deduplicates on
`event_ref` rather than on address.

## Rider identity

The rider id is an opaque device-scoped random value (`rl_` plus 8 hex
characters), generated once and stored in SharedPreferences. It is not a name, a
phone number or an email, the packet schema has no field for those, and the
backend rejects unexpected fields outright.

There is no account system, so there is no credential to steal.

## Transport security

**Cleartext HTTP, LAN only.** The prototype backend has no TLS certificate.
`res/xml/network_security_config.xml` permits cleartext to loopback and
private-LAN ranges only; the base configuration still denies it, so the app will
refuse cleartext to a public address.

This was added in response to a real failure — Android 9+ blocked every delivery
until it existed — and is documented in
[`verification-log.md`](verification-log.md). A real deployment uses HTTPS and
**deletes** that file rather than relaxing it further.

## Backend exposure

- **No authentication on any endpoint.** Anyone on the LAN can submit an
  emergency or read the active list.
- **No rate limiting**, so no protection against flooding.
- Two debug endpoints exist and are environment-gated:
  `GET /api/v1/debug/audit` (raw audit rows, for test verification) and
  `POST /api/v1/debug/reset` (wipes the database, refused with 403 unless
  `ROADLINK_ALLOW_RESET=1`). Neither should outlive the prototype.
- `ROADLINK_REQUIRE_SIG` defaults to `0`, so packets failing verification are
  **stored and flagged** rather than rejected. This is deliberate for
  development — during bring-up a canonicalisation mismatch is far more likely
  than an attacker, and silently dropping emergency events while debugging that
  would be the worse failure. Every event records `sig_valid` either way, and
  the audit trail keeps it. A deployment sets it to `1`.

## Android permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Direct upload, and the relay's forward leg |
| `BLUETOOTH_SCAN` (`neverForLocation`) | Relay role. Flagged so scanning does not require location |
| `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | Rider role, and GATT on both sides |
| `ACCESS_FINE_LOCATION` / `COARSE` | The GPS fix attached to an emergency. On API ≤ 30 this additionally gates BLE scanning |

No contacts, no SMS, no camera, no microphone, no storage, no phone state. The
app requests nothing it does not use.

## Data retention

There is **no delete path anywhere in the local store** — that is the product's
core invariant, and it is also a privacy trade-off worth naming: emergencies,
including a relay's copies of other people's, are retained indefinitely on the
device. A production system needs a retention policy that discharges custody
once the backend has confirmed receipt, which is a design question this prototype
does not answer.

## Secrets in this repository

There are none, and this was audited on 2026-08-23. No API keys, tokens,
passwords, private keys or personal credentials appear in tracked files. The
only key-shaped string is the self-describing prototype HMAC key above.

`.gitignore` excludes local IDE state, build outputs, APKs, `local.properties`
and the SQLite database files.
