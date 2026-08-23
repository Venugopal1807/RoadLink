# Demo script

A 2–5 minute demonstration. Setup is in [`setup.md`](setup.md).

The demo never depends on staging a real crash. **CREATE TEST SOS** drives the
identical pipeline a sensor trigger will use — signing, persistence, state
machine, transport, backend, audit — and only the trigger is simulated. That
fact is stamped into the signed packet and shown on screen as
`SIMULATED TRIGGER`.

## Before you start

```bash
# terminal 1
cd backend && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# terminal 2 — the log is the evidence; keep it visible
adb logcat -c && adb logcat -s RLINK
```

On the Rider tab, set a known starting state:

- Delivery path selector: **AUTO**
- Simulated relay in range: **off**
- Force rider offline: **off**
- Scripted relay failures: **0**

Press **Reset script**. If the device already holds emergencies from an earlier
run, that is fine — they are never deleted, which is itself the point.

> **Say what is real and what is not.** The demo is stronger for it, and every
> label on screen already does this. If physical BLE has not been validated on
> your hardware, say so plainly and run Scenario B — it carries the product
> claim on its own.

---

## Scene 1 — the problem (20s)

No app yet. State it plainly:

> A rider crashes on a highway. They may be unconscious, and they are often
> exactly where there is no cellular signal. An app that needs a working network
> at the moment of the crash does not solve the case that matters.

## Scene 2 — create a labelled emergency (20s)

Rider tab → **CREATE TEST SOS**.

Point at the `SIMULATED TRIGGER` badge. The trigger is a button; everything
after it is the real pipeline.

## Scene 3 — persisted before anything is transmitted (30s)

Log tab, first line:

```
PERSISTED <id> origin=SIMULATED - safe on disk before any delivery attempt
```

> The emergency is on disk before any delivery is attempted. `EmergencyStore`
> has no delete method, no purge and no expiry — nothing in the codebase is able
> to drop an emergency because delivery failed.

## Scene 4 — connectivity disappears (45s)

**This is the scene that carries the product claim.**

1. Rider tab → **Force rider offline** ON. (A switch rather than aeroplane mode:
   a demo that depends on toggling radios in front of an audience is a demo that
   fails.)
2. **CREATE TEST SOS**.
3. Log tab shows every transport reporting unavailable; the event sits at
   `QUEUED`.
4. Responder tab → the emergency is **not** there.

> That absence is honest. Nothing has reached the backend, so the responder view
> — which reads the server, not this phone — correctly shows nothing. The
> emergency is not lost; it is waiting.

## Scene 5 — connectivity returns (30s)

Rider tab → **Force rider offline** OFF. Do nothing else.

Within a few seconds the retry loop picks it up:

```
ATTEMPT <id> via DIRECT NETWORK (REAL) attempt=1
direct upload of <id> accepted, HTTP 201
DELIVERED <id> via DIRECT NETWORK
```

Responder tab → the emergency appears, with its delivery path.

> Nobody pressed anything. The emergency survived the outage and delivered
> itself when a path existed.

## Scene 6 — the BLE relay

**Run this scene only if the physical BLE ladder has passed on your hardware**
(see [`physical-test-results.md`](physical-test-results.md)).

*If it has passed:*

1. Phone A: **Arm physical BLE transport** ON, **Force rider offline** ON,
   delivery path pinned to **BLE**.
2. Phone B: **Relay mode** ON, left online.
3. Phone A: **CREATE TEST SOS**.

Phone A reaches `RELAYED`. Phone B logs `RELAY took custody`, stores it, and
uploads it over its own network. The backend shows delivery path `ble_relay`.

> Phone A says RELAYED, not DELIVERED. A relay's acknowledgement means another
> phone has custody — that phone may never regain connectivity — so the rider
> keeps trying independently. The backend is idempotent, so that redundancy is
> safe rather than duplicative.

*If it has not passed — do not fake it.* Use the simulated relay instead:
Rider tab → **Simulated relay in range** ON, delivery path pinned to
**SIMULATED**, then **CREATE TEST SOS**. The log says `(no radio involved)` and
the backend records `simulated_relay`. Say:

> The BLE transport is implemented and tested at the protocol and orchestration
> layer. Physical-device validation is pending, so this hop is simulated — and
> the system labels it as simulated everywhere, right down to the wire path
> stored in the database.

## Scene 7 — the responder view (20s)

Responder tab. Each emergency shows two independent badges: **trigger fidelity**
and **delivery path**.

> These are deliberately separate. A real sensor event delivered by the
> simulator is still not evidence that the relay works, so the system never
> collapses them into one "is this real" flag.

Note also that location reads `no fix reported` rather than a placeholder
coordinate when there is no GPS fix.

## Scene 8 — technical evidence (45s)

Pick two or three; do not run through all of them.

**Idempotency.** On a delivered event, press **Submit again over direct network**.

```
REDELIVER <id> via DIRECT NETWORK -> ... duplicate=true
```

```bash
curl http://localhost:8000/api/v1/debug/audit
```

One event row, two audit rows, `first_delivery_path` unchanged.

**Durability.** Force-stop the app and reopen it. Queued emergencies are still
there and the retry loop resumes on launch.

**Retry with backoff.** Set **Scripted relay failures** to 2, pin the path to
**SIMULATED**, create an SOS. It fails twice, is retained both times
(`event retained, still queued`), and succeeds on the third attempt.

**The audit trail.** Rider tab shows the real attempt count per event.

## Closing (15s)

> RoadLink stores an emergency locally before transmission and retries delivery
> when connectivity becomes available. A nearby phone can also receive the event
> over BLE and forward it later. What is proven today is the durable offline
> path, end to end. What is implemented but not yet proven on hardware is the
> phone-to-phone radio hop — and the app refuses to report that as working until
> it has been measured.

---

## Timing

| Scene | Target |
|---|---|
| 1 — problem | 0:20 |
| 2 — create | 0:20 |
| 3 — persisted | 0:30 |
| 4 — offline | 0:45 |
| 5 — reconnect | 0:30 |
| 6 — relay | 0:45 |
| 7 — responder | 0:20 |
| 8 — evidence | 0:45 |
| close | 0:15 |
| | **~4:30** |

For a hard 2-minute cut, keep scenes 1, 3, 4, 5 and the close. Scenario B alone
is the product claim.

## If something goes wrong on stage

- **Delivery does not resume after re-enabling the network** — press **Deliver
  now** rather than waiting for the loop.
- **The backend is unreachable** — the Responder tab shows it. Restart uvicorn;
  the queued emergencies are still on the phone and will deliver themselves.
- **Anything BLE misbehaves** — pin the delivery path to **NETWORK** and
  continue. That the product still works without BLE is the argument, not a
  climbdown.
