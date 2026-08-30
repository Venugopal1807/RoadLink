# RoadLink — CodeStorm 2026: FutureForge

## Resilient Emergency Communication for Future Mobility

Slide-by-slide source for the deck. Blockquotes are speaker notes and are not
meant to appear on the slide.

Every figure here is traceable to a test run recorded in
[`verification-log.md`](verification-log.md). There are no market statistics, no
accident figures and no performance measurements, because none were taken. If a
judge asks for a number that is not here, the answer is "not measured".

Three evidence labels are used throughout and never merged:

| | |
|---|---|
| **VERIFIED** | Observed in a real run — real HTTP, or the real code under test |
| **EMULATED** | Observed on an Android emulator. Code correctness only |
| **UNVERIFIED** | Not measured. No claim in either direction |

---

## Slide 1 — RoadLink

# Resilient Emergency Communication for Future Mobility

**An emergency is a durable object with a chain of custody — not an HTTP
request.**

`PERSIST FIRST → TRANSMIT SECOND`

A functional prototype.

> One line, then stop. No feature list on slide 1.

---

## Slide 2 — The problem

A rider crashes on a highway or a rural road.

- They may be unconscious, and unable to operate a phone.
- They are often exactly where cellular coverage is weakest.

**Connectivity is least available precisely when it matters most.**

> The engineering problem stands on its own. We have no accident statistics and
> we are not going to quote any.

---

## Slide 3 — Why existing approaches fail here

Conventional emergency apps are **cloud-first**. Press a button, send a request,
a server receives it. The design assumes a working network *at the moment of the
emergency*.

When that request fails:

- the error is shown to a user who cannot read it, or
- the retry lives in memory and dies with the process, or
- the app treats the failure as exceptional — a red banner, an alert, a giving up

**The case the app exists for is the case it handles worst.**

> This is the gap. Not emergency response in general.

---

## Slide 4 — The insight

Most systems treat connectivity failure as an **error condition**.

For a vehicle on a rural road, it is not an error. It is **Tuesday**.

> ### Connectivity failure is treated as a normal operating condition.

Everything else in this project follows from that one sentence. If losing the
network is normal, then the code path that handles it cannot be an exception
handler — it has to be the main path.

> This is the slide to slow down on. It is the whole thesis, and it reframes
> every design decision that follows.

---

## Slide 5 — The solution

Separate **recording** an emergency from **delivering** it. They are different
problems with different requirements.

| | |
|---|---|
| **Recording** | Must always succeed. Locally. Immediately. |
| **Delivery** | Best-effort, any available path, retried indefinitely. |

An emergency is written to disk **before** any delivery is attempted, and it is
never discarded because delivery failed.

**Not claimed:** ambulance dispatch · 112 integration · medical diagnosis ·
guaranteed delivery · guaranteed rescue · nationwide coverage.

---

## Slide 6 — Core workflow

```
1. Emergency confirmed      simulated trigger today; no sensor engine
2. Signed                   HMAC; event_id fixed here, never changes
3. Written to disk          survives process death, reinstall, battery pull
4. Delivery attempted       direct network → BLE relay → fallback
     ├── backend accepts → DELIVERED       terminal
     ├── relay takes it  → RELAYED         custody, still pending
     └── nothing works   → QUEUED_OFFLINE  retried, never dropped
5. Retry loop               every 3s, exponential backoff to a 30s cap
6. Responder reads the backend, not the device
```

**Step 3 completes before step 4 begins.** That ordering is the product.

> If asked why the trigger is a button: sensor crash detection is not
> implemented, the app says so on screen, and that label is inside the
> signature.

---

## Slide 7 — Architecture

```
                   Emergency Event
                          │
                          ▼
                 Durable Local Store        no delete · no purge · no expiry
                          │
                          ▼
                  Delivery Manager          owns EVERY state change
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
   DIRECT NET         BLE RELAY          FALLBACK
        └─────────────────┼─────────────────┘
                          ▼
                  Idempotent API            keyed on event_id
                          ▼
                  Audit + Event             append-only
                          ▼
                     RESPONDER
```

Nothing above the transport interface imports a Bluetooth class, so any
transport — BLE, SMS, satellite, LoRa — is replaceable without touching the
domain, the store or the UI.

---

## Slide 8 — Technical innovation

Reliability enforced by **structure**, not by discipline:

- **The store exposes no delete, no purge, no expiry.** No code path can drop an
  emergency because delivery failed, because none exists to call.
- **The state machine has no terminal failure state.** Every failure returns to
  a retryable state. `DELIVERED` is the only terminal one.
- **Transports cannot mutate or complete an event.** They receive an immutable
  event and return a result.
- **Process-death recovery.** An emergency interrupted mid-delivery is recovered
  on the next launch, and the interruption is recorded as `INTERRUPTED` — not as
  a failure, because its outcome was never observed.
- **`RELAYED` is never `DELIVERED`.** Custody is not arrival.
- **A test asserts the *order*** of persistence and transmission, not merely
  that both happened.

> The recovery point was a real bug, found by asking what happens if the process
> dies mid-delivery. It is now the demo.

---

## Slide 9 — Offline-first design, and the BLE relay

**Offline-first is the product.** The queue, the retry loop and the recovery
path work with no radio, no second device and no Bluetooth at all.

**BLE is one opportunistic transport on top of it.** When the rider has no
network, a nearby phone can carry the emergency:

- The crashed phone **advertises**; the helper phone **scans and connects**.
- The advertisement is a **13-byte beacon** — no identity, no coordinates, not
  the raw event id, only a truncated one-way hash.
- The signed packet travels **only over the connected GATT link**.
- The relay **verifies the signature before storing**, and **withholds its
  acknowledgement until the write succeeds**.

**Status: implemented and unit-tested. UNVERIFIED on physical radios.**

> Say it plainly. The decisive unknown is whether the handsets can take the BLE
> peripheral role at all, which is a per-chipset fact we have not measured. The
> transport ships disarmed so it cannot report a success it did not have. And
> note what this costs us: nothing. The demo does not use it.

---

## Slide 10 — Security

- **Signed at creation**, before persistence or transmission. HMAC-SHA256 over a
  canonical string, mirrored in Kotlin and Python and pinned by a cross-language
  round trip.
- **A relay verifies before forwarding** and drops what fails, so a relay cannot
  be used to inject emergencies into the backend.
- **The `simulated` flag is inside the signature**, so no downstream layer can
  relabel a simulated event as real.
- **Rider identity is an opaque random value.** No name, phone number or email —
  the packet schema has no field for them.

Prototype limits, stated rather than hidden: the HMAC key is compiled into the
app and is **not key management**; there is no authentication on any endpoint;
there is no replay freshness window; debug builds permit cleartext HTTP and
release builds deny it.

---

## Slide 11 — Testing and evidence

| Suite | Result | Evidence |
|---|---|---|
| Backend ingestion + idempotency | 61 passed, 0 failed | **VERIFIED** — real HTTP |
| Android JVM suite | 88 passed, 0 failed | **VERIFIED** |
| Kotlin ↔ Python wire compatibility | PASSED, `sig_valid=true` | **VERIFIED** — real HTTP |
| Persistence durability + migration | 8 passed, 0 failed | **EMULATED** |
| Offline → reconnect → delivery | Observed end to end | **EMULATED** |
| Phone-to-phone BLE over a radio | — | **UNVERIFIED** |
| Sensor crash detection | not implemented | **UNVERIFIED** |

**The strongest evidence was unplanned.** An emergency hit a real Android
platform fault, failed delivery **eight consecutive times**, survived an
application **rebuild and reinstall**, and was delivered on the ninth attempt
with a valid signature. Nothing was lost. *(EMULATED, and not scripted.)*

> Three labels, never merged. If BLE comes up, point at the honest row.

---

## Slide 12 — Limitations

Stated because a prototype that hides them is not a prototype, it is a pitch.

- **Crash detection is a button.** No sensor fusion, no classifier, no accuracy
  figure — because none has been measured.
- **Phone-to-phone BLE has never run on a radio.**
- **No emergency-service integration.** RoadLink delivers data to its own
  backend and dispatches nobody.
- **Single hop.** A relay carries an emergency to the backend; it does not
  forward to another relay.
- **Foreground delivery.** The retry loop runs while the app process is alive
  and resumes on next launch; there is no background service yet.
- **Prototype infrastructure.** SQLite, one process, no authentication.

---

## Slide 13 — Impact and future scope

**What this changes**

An emergency raised outside coverage is not lost. It is held, and delivered when
any path appears — and a passing phone can shorten that wait.

The pattern generalises beyond motorcycles: any connected vehicle, any rural
logistics fleet, any lone worker. **Future mobility will not have uniform
coverage.** Systems that assume it will fail in exactly the places help is
slowest to arrive.

**Next, in order**

1. Physical BLE validation on two handsets — the T1–T11 procedure is written and
   every result cell is empty, because nothing has been measured.
2. Sensor-based crash detection, replacing the test trigger.
3. Per-device Ed25519 keys in the Android Keystore, replacing the shared key.
4. A foreground service, so delivery continues without the app open.
5. PostgreSQL in place of SQLite.

**How it scales**

`event_id` is generated on the device and never changes, so idempotency holds
across any number of backend instances. Ingestion is stateless apart from the
database.

> Close on the claim we stand behind: RoadLink preserves an emergency locally
> when connectivity fails and delivers it automatically when a viable path
> becomes available. Everything not demonstrated is labelled as such.

---

## Producing the deck

Thirteen slides. Slides 4, 7 and 8 are the ones that earn the marks.

- Slide 4 is the thesis. Give it its own beat.
- Slides 6, 7 and 9 carry diagrams. Redraw them as graphics rather than pasting
  monospace blocks into a slide.
- Slide 11 is a table. Keep it a table — it reads as evidence.
- Use the three evidence labels verbatim. They are the credibility of the
  submission.
- Avoid stock imagery of ambulances or crashes; it undercuts a technical talk.
