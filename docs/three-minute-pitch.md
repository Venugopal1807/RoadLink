# RoadLink — three-minute pitch

For a spoken pitch with no live demo. The working demonstration is a separate
procedure: [`demo.md`](demo.md).

Timings are the target, not a script to read aloud. Say it in your own words —
but do not soften the limitations, because they are the reason the rest is
believable.

---

## 0:00 – 0:25 — The problem

> A rider crashes on a rural road. They may be unconscious. And they are often
> exactly where there is no cell coverage.
>
> Every emergency app I looked at is cloud-first: press a button, send a
> request, a server receives it. That design assumes a working network at the
> precise moment of the emergency. When the request fails, the error goes to
> someone who cannot read it, and the retry lives in memory and dies with the
> process.
>
> The case the app exists for is the case it handles worst.

## 0:25 – 0:50 — The insight

> Most systems treat losing connectivity as an error condition. For a vehicle on
> a rural road, it is not an error — it is normal.
>
> So RoadLink treats **connectivity failure as a normal operating condition**.
> If losing the network is normal, the code that handles it cannot be an
> exception handler. It has to be the main path.

## 0:50 – 1:20 — The solution

> That means separating two things every other app conflates: **recording** an
> emergency and **delivering** it.
>
> Recording must always succeed — locally, immediately, on disk.
> Delivery is best-effort, over whatever path exists, retried forever.
>
> An emergency is written to disk before anything is transmitted. It is never
> deleted because delivery failed. When a path appears — the rider's own network
> coming back, or a nearby phone acting as a relay — it goes.

## 1:20 – 2:05 — Why it cannot lose one

> This is enforced by structure, not by discipline.
>
> The store **has no delete method**. No code path can drop an emergency,
> because there is nothing to call. The state machine has **no terminal failure
> state** — every failure returns to a retryable one. Transports receive an
> immutable event and return a result; they cannot complete or mutate it. And a
> test asserts the *order* of persistence and transmission, not merely that both
> happened.
>
> The best example came from a bug I found late. An emergency is written to disk
> as "attempting" *before* the transport is called — so that is its state for the
> whole five seconds a network call might hang. If the phone killed the app in
> that window, the emergency could never legally re-enter delivery. It was
> stranded, and because the queue is oldest-first, it stranded every emergency
> behind it too. Nothing was ever lost — but nothing was delivered either.
>
> That is fixed, and the interruption is now recorded as `INTERRUPTED` rather
> than as a failure — because its outcome was genuinely never observed, and
> saying "failed" would claim knowledge the system does not have.

## 2:05 – 2:35 — What is real, and what is not

> I want to be precise about evidence, because that is the point of the project.
>
> **Verified:** the backend's idempotency and audit trail, 61 assertions over
> real HTTP. The Android suite, 88 tests. Cross-language packet signing between
> Kotlin and Python, verified over the wire.
>
> **Emulated:** persistence durability and the offline-to-delivery flow.
>
> **Unverified:** phone-to-phone Bluetooth has never run on a real radio. It is
> implemented, unit-tested, and ships **disarmed** so it cannot report a success
> it did not have.
>
> And crash detection is a button. There is no sensor fusion and no classifier,
> so there is no accuracy number — because I have not measured one.

## 2:35 – 3:00 — Why it matters

> Detection is a separate and well-understood problem. RoadLink addresses the one
> that is actually unsolved: what happens *after* detection, when there is no
> signal.
>
> That generalises past motorcycles — any connected vehicle, any rural fleet, any
> lone worker. Future mobility will not have uniform coverage. Systems that
> assume it will fail in exactly the places help is slowest to arrive.
>
> RoadLink does not promise rescue. It promises that the emergency is not lost.
> That is a smaller claim, and it is the one I can demonstrate.

---

## If you only have 60 seconds

> Emergency apps assume a working network at the moment of the emergency —
> which is exactly when it is missing. RoadLink treats connectivity failure as
> normal, not exceptional: the emergency is written to disk before anything is
> transmitted, and the code that would delete it does not exist. It is delivered
> whenever a path appears, and it survives the app being killed. Bluetooth relay
> is implemented but unproven on hardware, and crash detection is still a button
> — both are labelled as such in the app itself.

## Questions you should expect

| Question | Answer |
|---|---|
| "Does the Bluetooth part actually work?" | Unknown. It is implemented and unit-tested; it has never run on a radio, and it ships disarmed. The procedure to validate it is written and every result cell is empty. |
| "How accurate is the crash detection?" | There is none. The trigger is a button, and the app labels every event as a simulated trigger — inside the signature, so it cannot be stripped. |
| "Does it call an ambulance?" | No. It delivers data to its own backend and dispatches nobody. |
| "What if the relay never gets signal?" | Then the emergency stays with it, and with the rider, who keeps trying independently. That is why `RELAYED` is a separate state from `DELIVERED`. |
| "Why not just retry in the background?" | Delivery currently runs while the app is alive and resumes on next launch. A foreground service is the next step and is not claimed as done. |
| "Is this production ready?" | No. It is a prototype: SQLite, no authentication, and a shared HMAC key compiled into the app. All three are documented rather than hidden. |
