# RoadLink — presentation source

Ten slides for Prasunethon 2.0 Round 2. Blockquotes are speaker notes and are
not meant to appear on the slide.

Every figure here is traceable to `verification-log.md` or a test run. There are
no market statistics, no accident figures and no performance claims, because
none were measured. If a judge asks for a number that is not here, the answer is
"not measured".

---

## Slide 1 — RoadLink

**A durable emergency-delivery system for roads without coverage.**

Preserves an emergency locally, then delivers it through whatever transport is
available.

`PERSIST → THEN TRANSMIT`

A functional prototype.

> Open with the one-line claim and nothing else. No feature list on slide 1.

---

## Slide 2 — The problem

A rider crashes on a highway or a rural road.

- They may be unconscious, and unable to operate a phone.
- They are often exactly where cellular coverage is weakest.

**Connectivity is least available precisely when it matters most.**

> The engineering problem stands on its own. We have no accident statistics and
> we are not going to quote any.

---

## Slide 3 — Why current systems fail here

Most emergency apps assume a working network *at the moment of the emergency*:
press a button, send a request, a server receives it.

When that request fails:

- the error is shown to a user who cannot read it, or
- the retry lives only in memory and dies with the process.

**The case the app exists for is the case it handles worst.**

> This is the gap RoadLink is aimed at. Not emergency response in general.

---

## Slide 4 — Our solution

Separate **recording** an emergency from **delivering** it.

| | |
|---|---|
| Recording | Must always succeed, locally, immediately |
| Delivery | Best-effort, any available path, retried indefinitely |

An emergency is written to disk **before** any delivery is attempted, and is
never deleted because delivery failed.

**Not claimed:** ambulance dispatch, 112 integration, medical diagnosis,
guaranteed delivery, guaranteed rescue.

> The whole product in one slide. Everything after this is how it is enforced.

---

## Slide 5 — Core workflow

```
1. Emergency confirmed      simulated trigger today
2. Signed                   event_id fixed here, never changes
3. Written to disk          survives process death and reinstall
4. Delivery attempted       network → BLE relay → simulated relay
     ├── backend accepts → DELIVERED       terminal
     ├── relay takes it  → RELAYED         custody, still pending
     └── nothing works   → QUEUED_OFFLINE  retried, never dropped
5. Retry loop               every 3s, backoff to a 30s cap
6. Responder reads the backend, not the device
```

**Step 3 completes before step 4 begins.** That ordering is the product.

> If asked why the trigger is simulated: sensor crash detection is not
> implemented, the app labels every event as a test trigger, and that label is
> inside the signature.

---

## Slide 6 — Offline-first architecture

```
EmergencyController   signs, generates event_id
      ▼
DeliveryManager       owns every state change
      ├── Direct network      first when online
      ├── BLE relay           when there is no network
      └── Simulated relay     development only, always labelled
      ▼
EmergencyStore        no delete, no purge, no expiry
      ▼
FastAPI backend       idempotent on event_id, append-only audit
```

The cascade **is** the fallback: a transport reporting itself unavailable is
skipped, and the next one is tried in the same pass.

Nothing above the transport interface imports a Bluetooth class, so BLE is
replaceable without touching the domain, the store or the UI.

---

## Slide 7 — BLE relay architecture

When the rider has no network, a nearby phone carries the emergency.

- The crashed phone **advertises**; the helper phone **scans and connects**.
- The advertisement is a **13-byte beacon**: no identity, no coordinates, not
  the raw event id — only a truncated one-way hash.
- The signed packet travels **only over the connected GATT link**.
- The relay **verifies the signature before storing**, and **withholds its
  acknowledgement until the write succeeds**.

**A relay acknowledgement means custody, not arrival.** State is `RELAYED`,
never `DELIVERED` — the relay may never regain connectivity, so the rider keeps
trying independently.

**Status: implemented and unit-tested; NOT VERIFIED on physical radios.**

> Say the status plainly. The decisive unknown is whether the handsets can take
> the BLE peripheral role at all, which is a per-chipset fact we have not
> measured. The transport is disarmed by default so it cannot fake a success.

---

## Slide 8 — Reliability and the persistence invariant

Enforced by structure, not by discipline:

- The store exposes **no delete method**. No code path can drop an emergency,
  because none exists to call.
- The state machine has **no terminal failure state** — every failure returns to
  a retryable state.
- Transports receive an immutable event and return a result. Only the delivery
  manager writes state.
- Room runs `synchronous = FULL`; a committed write has reached disk.
- The backend is **idempotent on event_id**: rider and relays may all submit the
  same emergency. One record, every attempt audited.

A test asserts the **order** of persistence and transmission, not merely that
both happened.

> That ordering test is the difference between proving the invariant and
> assuming it.

---

## Slide 9 — Demonstrated results

| Suite | Result | Evidence |
|---|---|---|
| Backend ingestion + idempotency | 61 passed, 0 failed | **VERIFIED** |
| Android JVM tests | 68 passed, 0 failed | **VERIFIED** |
| Kotlin ↔ Python wire compatibility | PASSED | **VERIFIED** |
| Durability + schema migration | 8 passed, 0 failed | **EMULATED** |
| Offline → reconnect → delivery | Observed end to end | **EMULATED** |
| Phone-to-phone BLE over a radio | — | **NOT VERIFIED** |

**The strongest evidence was unplanned.** An emergency hit a real Android
platform fault, failed delivery **eight consecutive times**, survived an
application **rebuild and reinstall**, and was delivered on the ninth attempt
with a valid signature. Nothing was lost. *(EMULATED, and not scripted.)*

> Three evidence labels, never merged. If BLE comes up, point at the honest row.

---

## Slide 10 — Future scope and impact

**What this changes**

An emergency raised outside coverage is not lost. It is held and delivered when
any path appears, and a passing phone can shorten that wait.

**Next, in order**

1. Physical BLE validation on two handsets — the procedure is written, every
   result cell is empty by design.
2. Sensor-based crash detection, replacing the test trigger.
3. Per-device keys in the Android Keystore, replacing the shared prototype key.
4. Background delivery service; PostgreSQL in place of SQLite.

**How it scales**

`event_id` is generated on the device and never changes, so idempotency holds
across any number of backend instances. The transport interface is the extension
point for SMS, satellite or LoRa without touching the domain.

> Close on the claim we stand behind: RoadLink preserves an emergency locally
> when connectivity fails and delivers it automatically when a viable path
> becomes available. Everything not demonstrated is labelled as such.

---

## Producing the deck

Ten slides, roughly one minute each.

- Slides 5, 6 and 7 carry the diagrams. Redraw them as graphics rather than
  pasting monospace blocks into a slide.
- Slide 9 is a table. Keep it as a table — it reads as evidence.
- Use the three evidence labels verbatim. They are the credibility of the
  submission.
- Avoid stock imagery of ambulances or crashes; it undercuts a technical talk.
