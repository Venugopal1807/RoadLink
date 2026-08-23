# RoadLink — presentation source

Ten slides for Prasunethon 2.0 Round 2. Speaker notes are in blockquotes and are
not meant to appear on the slide.

Every number here is traceable to [`verification-log.md`](verification-log.md)
or a test run. There are no market statistics, no accident figures and no
performance claims, because none have been measured. If a judge asks for a
number that is not here, the answer is "not measured".

---

## Slide 1 — RoadLink

**A durable emergency-delivery system for roads without coverage.**

Preserves an emergency locally, then delivers it through whatever transport is
available.

`PERSIST → THEN TRANSMIT`

> Open with the one-line claim and nothing else. Do not list features on slide 1.

---

## Slide 2 — The problem

A rider crashes on a highway or a rural road.

- They may be unconscious and unable to call for help.
- They are often exactly where cellular coverage is weakest.

Any design that assumes a working network *at the moment of the crash* does not
address the case that matters.

> The insight to land: connectivity is least available precisely when it is most
> needed. We do not have accident statistics and we are not going to quote any.
> The engineering problem stands on its own.

---

## Slide 3 — The solution

Separate **recording** an emergency from **delivering** it.

| | |
|---|---|
| Recording | Must always succeed, locally, immediately |
| Delivery | Best-effort, over any available path, retried indefinitely |

An emergency is written to disk **before** any delivery is attempted, and is
never deleted because delivery failed.

> This is the whole product in one slide. Everything else is how it is enforced.

---

## Slide 4 — Architecture

```
CrashDetector          simulated trigger today
      ▼
EmergencyController    signs the packet, generates event_id
      ▼
DeliveryManager        owns every state change
      ├── Direct network      tried first when online
      ├── BLE relay           when there is no network
      └── Simulated relay     development only, always labelled
      ▼
EmergencyStore (Room)  no delete, no purge, no expiry
      ▼
FastAPI backend        idempotent on event_id, append-only audit
      ▼
Responder view
```

The invariant is enforced by structure, not discipline:

- The store exposes **no delete method**, so no code path can drop an emergency.
- The state machine has **no terminal failure state** — every failure returns to
  a retryable state.
- Transports receive an immutable event and return a result. Only the delivery
  manager writes state.

> If asked "how do you know it never loses an SOS": those three properties, plus
> a test that asserts the *order* of persistence and transmission.

---

## Slide 5 — Offline-first workflow

```
Emergency confirmed
      ▼
Written to disk            ← survives process death and reinstall
      ▼
Try direct network ──────► delivered
      │ unavailable
      ▼
Try BLE relay ───────────► RELAYED (another phone has custody)
      │ unavailable
      ▼
Stay queued, retry with backoff (1s → 30s cap)
```

Observed on an emulator: an emergency failed delivery **eight consecutive
times** against a real Android platform fault, survived an application
**rebuild and reinstall**, and was delivered on the ninth attempt with a valid
signature. Nothing was lost. That run was not scripted.

> This is the strongest evidence we have and it was an accident. Say so — it is
> more convincing than a staged demo.

---

## Slide 6 — BLE relay

When the rider has no network, a nearby phone can carry the emergency.

- The crashed phone **advertises**; the helper phone **scans and connects**.
- The advertisement is a 13-byte beacon: no identity, no coordinates, not the
  raw event id. Correlation uses a truncated one-way hash.
- The signed packet travels only over the connected GATT link.
- The relay **verifies the signature before storing**, and **withholds its
  acknowledgement until the write succeeds**.

**A relay ACK means custody, not arrival.** The state is `RELAYED`, never
`DELIVERED` — that phone may never regain connectivity, so the rider keeps
trying independently.

> Status, stated plainly: implemented, protocol and orchestration unit-tested,
> **not yet validated on physical radios**. Do not claim otherwise. If asked why
> not: the decisive unknown is whether the handsets can take the BLE peripheral
> role at all, which is a per-chipset fact we have not been able to measure.

---

## Slide 7 — Reliability and security

**Reliability**

- Room configured `synchronous = FULL`; a committed write has reached disk.
- No destructive migration fallback — a missing migration fails loudly rather
  than discarding undelivered emergencies.
- Backend idempotent on `event_id`: the rider and any number of relays may all
  submit the same emergency. One record, every attempt audited.

**Security**

- Every packet signed at creation; the relay drops anything that fails
  verification, so a relay cannot be used to inject emergencies.
- The `simulated` flag is **inside** the signature and cannot be flipped
  downstream without invalidating the packet.
- Rider identity is an opaque random value. No name, phone number or email.

**Prototype limits, stated up front:** a shared HMAC key compiled into the app
is not key management; there is no authentication on the backend; storage is
SQLite over LAN HTTP.

> Volunteering the limits is the point. It is what makes the rest credible.

---

## Slide 8 — Working prototype and evidence

| Suite | Result | Evidence class |
|---|---|---|
| Backend ingestion + idempotency | 61 passed, 0 failed | Real HTTP, live server |
| Android JVM | 62 passed, 0 failed | JVM |
| Kotlin ↔ Python wire compatibility | PASSED, signature valid | Real HTTP |
| Persistence durability + migration | 8 passed, 0 failed | **EMULATED** |
| Phone-to-phone BLE over a radio | **NOT YET VERIFIED** | — |

Three evidence classes, never merged: **VERIFIED**, **EMULATED**,
**NOT YET VERIFIED**.

Demonstrated end to end: emergency created offline → persisted → delivery fails
→ stays queued → connectivity restored → delivered automatically → visible to
the responder with its full audit trail.

> The demo runs on the direct-network path and does not depend on BLE. If BLE is
> raised, point at the honest row in this table.

---

## Slide 9 — Impact and scalability

**What this changes**

An emergency raised outside coverage is not lost. It is held and delivered when
any path appears, and a passing phone can shorten that wait.

**Scaling path** — direction, not work performed:

- `db.py` is the single swap point for PostgreSQL; ingestion is stateless apart
  from the database, so the API scales horizontally.
- `event_id` is generated on the device and never changes, so idempotency holds
  across any number of instances.
- The `Transport` interface is the extension point for further paths — SMS,
  satellite, LoRa — without touching the domain, the store or the UI.

**Not claimed:** ambulance dispatch, 112/ERSS integration, guaranteed delivery,
guaranteed rescue, nationwide mesh coverage, or any medical function.

> Do not oversell this slide. The honest framing — "an architecture that could
> extend" — is stronger than an invented rollout plan.

---

## Slide 10 — Roadmap

**Next, in order**

1. Physical BLE validation on two handsets, following the existing T1–T11
   procedure. Every result cell is currently empty by design.
2. Sensor-based crash detection. The trigger interface exists; the detection
   engine is not written, and today every emergency comes from a labelled test
   button.
3. Per-device Ed25519 keys in the Android Keystore, replacing the shared HMAC
   key.
4. A foreground service so delivery continues without the app open.

**The claim we stand behind today**

> RoadLink preserves an emergency locally when connectivity fails and delivers
> it automatically when a viable path becomes available.

That is demonstrated. Everything not demonstrated is labelled as such, in the
repository and in this deck.

---

## Producing the deck

Ten slides, roughly one minute each. Suggested treatment:

- Slides 4 and 5 carry the diagrams. Redraw them as graphics; do not paste ASCII
  into a slide.
- Slide 8 is a table — keep it as a table, it reads as evidence.
- Use the three evidence labels verbatim. They are the credibility of the
  submission.
- Avoid stock imagery of ambulances or crashes. It undercuts a technical talk.

Source material: [`architecture.md`](architecture.md) for the diagrams,
[`current-project-status.md`](current-project-status.md) for evidence,
[`limitations.md`](limitations.md) for the boundaries.
