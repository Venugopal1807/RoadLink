# Demo script

Five scenes, about five minutes. Everything here runs on **one phone** and
needs **no Bluetooth, no second device and no working Wi-Fi at the venue**.
That is deliberate: the parts of RoadLink that are unproven must not be able to
break the parts that are.

The claim being demonstrated is exactly this and nothing wider:

> RoadLink preserves an emergency locally when connectivity fails and delivers
> it automatically when a viable communication path becomes available.

---

## Before you start

```bash
# 1. backend, bound so the phone can reach it
cd backend && python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# 2. your machine's LAN address
ip addr        # or ipconfig on Windows

# 3. build against that address, not the emulator default
cd android && ./gradlew :app:assembleDebug -Proadlink.backendUrl=http://<LAN-IP>:8000
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Check before you present:

- The Rider tab's banner says **BACKEND REACHABLE**. If it does not, the
  backend-address card appears directly underneath it - fix it there, on the
  phone, without rebuilding.
- The Responder tab lists zero emergencies.
- Demo controls are collapsed. Leave them collapsed until Scene 3.

If the venue network is hostile, run the backend on the laptop and tether the
phone to it. The LAN path is the only one that has been verified end to end.

---

## Scene 1 — What the app is for  *(30s)*

Open the Rider tab. Read the banner.

> "A rider crashes on a road with no coverage. Every emergency app assumes a
> working network at the exact moment the network is not there. RoadLink
> separates recording an emergency from delivering it. Recording always
> succeeds. Delivery happens whenever it can."

Point at **READY** and at the two counters underneath: what this phone is
holding, and what the backend has confirmed. Those two numbers are the whole
story and they are never the same number by accident.

---

## Scene 2 — An emergency, delivered normally  *(45s)*

Press **CREATE TEST SOS**.

Say the honest thing immediately, before anyone asks:

> "The trigger is a button. Crash detection by sensor is not implemented, and
> the app says so - that SIMULATED TRIGGER badge is not decoration, the flag is
> inside the packet's signature and cannot be removed downstream."

The banner moves to **DELIVERED**. Open the card's custody trail: persisted
first, then the attempt, then the delivery. Switch to the **Responder** tab -
the emergency is there, read from the server.

---

## Scene 3 — Offline  *(60s)*

Open **Show demo controls** and turn **force rider offline** on. Say plainly
that this is a switch rather than real aeroplane mode, because a demo that
depends on toggling radios in front of an audience is a demo that fails.

Collapse the controls again. Press **CREATE TEST SOS**.

- The banner goes to **OFFLINE / QUEUED**.
- The custody trail shows it persisted, then nothing available.
- **Responder tab: the new emergency is not there.**

Dwell on that last point. It is the honest half of the demonstration:

> "It genuinely has not arrived. I am not going to show you a green tick and
> tell you it worked."

---

## Scene 4 — Kill the app  *(45s)*  ← **the one that matters**

With the emergency still undelivered:

> "This emergency is on this phone and nowhere else. Watch what happens if the
> phone decides my app is not important."

Android **Settings → Apps → RoadLink → Force stop**. The process is gone.

Reopen RoadLink. Turn **force rider offline** off.

Within a few seconds the banner reads **DELIVERED**. Open the custody trail: the
whole story is there, including the attempts that failed and any that were
interrupted, each one labelled.

> "Nothing was lost, because the emergency was written to disk before anything
> was ever transmitted, and because nothing in the codebase is able to delete
> it. The store has no delete method. There is no code path to call."

Switch to the Responder tab. The emergency has arrived.

---

## Scene 5 — Why it cannot lose one  *(60s)*

Thirty seconds of architecture, no slides:

- **The store exposes no delete, no purge, no expiry.** Nothing can drop an
  emergency because delivery failed, because no such method exists.
- **The state machine has no terminal failure state.** Every failure returns to
  a retryable state. `DELIVERED` is the only terminal one.
- **A test asserts the order** of persistence and transmission, not merely that
  both happened.
- **The backend is idempotent on `event_id`.** The rider and any number of
  relays can all submit the same emergency; one record, every attempt audited.

Then press **Submit again over direct network** on a delivered card, and show
the Responder tab still holding exactly one emergency.

---

## Closing — the honest slide  *(30s)*

Say all of it, without being asked:

- Phone-to-phone BLE relay is **implemented and unit tested, and has never run
  on a radio.** The transport is disarmed by default so it cannot report a
  success it did not have.
- Crash detection is **a button**. There is no sensor fusion and no classifier.
- There is **no ambulance, 112 or ERSS integration**, and no claim of one.
- The HMAC key is **compiled into the app**, which is not key management.

> "Detection is a separate and well-understood problem. RoadLink addresses the
> one that is actually unsolved: what happens after detection, when there is no
> signal."

---

## If something goes wrong

| Symptom | Do this |
|---|---|
| Banner says BACKEND UNREACHABLE | The address card is right underneath it. Fix it on the phone. |
| Responder tab empty after a delivery | Check the backend is bound to `0.0.0.0`, not `127.0.0.1`. |
| Nothing delivers at all | Confirm **force rider offline** is off, and that the transport is not pinned in the demo controls. |
| An emergency looks stuck | Open its custody trail. It says what was tried and why it stopped. That is not a failure of the demo - it is the demo. |

The one thing never to do is claim a BLE relay happened. If BLE comes up, point
at [`physical-test-results.md`](physical-test-results.md), where every cell
reads NOT YET TESTED, and move on.
