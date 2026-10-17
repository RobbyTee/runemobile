# Instant click — the tap delay, and what is left of it

Branch `master`. Commits `97cbf49a22` (native marker) and `7116329bb6` (bridge arm). Both pushed to
`origin`. Not on the phone yet — see **Next step**.

## What the delay actually was

Three waits stacked on one tap, and none of them was the game being slow:

1. **`UITapGestureRecognizer` fires on finger-UP.** Structural: one-finger drag is the camera and a hold is
   the menu, so a tap is only a tap once the finger leaves without doing either.
2. **The engine-resolved gate.** A left tap is never re-hit-tested — the engine consumes the press against
   the menu it built from the cursor position earlier in the same tick — so `JavaBridge` moves the synthetic
   cursor to the tap point, waits until the engine has demonstrably resolved it (cursor match + a stable
   left-click tail, `STABLE_NEEDED = 2` frames), and only then presses. Every one of those frames was spent
   after the lift, because the lift was the first the bridge heard of the tap.
3. **The click marker waited for the answer.** The ring is red for an interactable and yellow for a walk, so
   it was drawn only once the kind came back — and for a walk nothing comes back, so it waited out the full
   0.25s grace. The commonest tap in the game therefore marked itself ~250ms after a finger that had
   already lifted.

## What shipped

- **The marker is drawn on contact** (`RMSceneView.touchesBegan:` → `onSceneTouchDown:`), in the walk
  colour. The resolved kind still reaches the ring that is already on screen: `promoteLiveRingToInteract`
  repaints it red, `retireLiveRing` takes it back for a WIDGET tap (the engine cuffs those itself) or when
  the touch turns into a hold/drag/pinch. Every older path still draws its own ring when no contact marker
  exists, so nothing regresses. A `UIImageView` subclass carries the touch-down callback deliberately — a
  zero-duration recognizer would have re-arbitrated the whole tap/hold/pan/pinch set.
- **The gate runs while the finger is down.** `JavaBridge.armTap(x,y)` on contact enters the same
  `AWAIT_ENGINE` without a pending click and parks in the new `ARMED` state when it accepts;
  `commitArmedTap` posts the click at the lift. It re-proves both observables first (cursor still on the arm
  point, tail still the one that stabilised) and falls back to the full gate otherwise, so the fast path can
  never click against another point's menu. An arm that only failed open returns to rest, so arming can only
  help. `cancelArmedTap` drops it on hold/drag/pinch; `ARMED_TIMEOUT` (120 frames) is the fail-safe.
- Tunables, all `Integer.getInteger`: `runelite.touch.armSlop` (4 canvas px), `runelite.touch.armedTimeout`.

What is left: the click still happens **on lift**. Everything after the lift is now one client-thread hop.

## Gate status

- `./gradlew :client:compileJava` — clean. `:client:shadowJar` + `gpu-routec/stage-ios-gpu-jar.sh` —
  `ROUTEC_STAGE_GREEN`; the staged `client-shaded.jar` carries `armTap(II)V` and `cancelArmedTap()V`
  (verified with `javap` on the staged jar, which is what the app boots).
- `clang -fsyntax-only` on `ViewController.m` and `JVMLauncher.m` — 17 warnings, all pre-existing, none in
  the new code. `git clang-format --staged` applied.
- Android bridge census — **GREEN, 78/78 resolved, 0 missing**; shared contract baseline refreshed
  (additive: two methods, two flags). While using it: the census picked its jar with a glob whose first
  match is the lexicographically smallest version, so it had been reporting GREEN for a July jar. It now
  reads `project.build.version` from `gradle.properties` like the staging scripts do.
- **Device: unverified.** Nothing here has been tapped on a phone yet.

## Next step

`ios/dev-tools/serve-ipa.sh` (the jars are already staged, so no `--jars` needed), then SideStore on the
phone. On device, check in this order:

1. A tap on open ground rings **instantly** and the ring is yellow.
2. A tap on an NPC/object rings instantly and turns **red** when the interaction resolves.
3. An inventory/prayer/tab tap rings briefly and the ring is **withdrawn** (no walk ring left behind).
4. A camera drag and a long-press each leave **no** ring and perform no click at their origin.
5. The thing this was for: taps feel like they happen when the finger lands.

With `-Drunelite.touch.diag` the log prints `L settle: N frames` then `L commit: armed N frames before the
lift` for an armed tap; a tap that fell back prints the old settle line alone.

## Open risks

- **The arm is a change to the tap state machine**, which is the thing that fixed wrong-target taps. The fast
  path is guarded by re-proving the gate's own observables, but only a device session proves it: watch for a
  tap acting on the PREVIOUS tap's target, which would be the signature.
- `isPlainWorldTapAtGameX:gameY:screenPoint:` mirrors `onTap:`'s early returns (dialogue parchment, world
  map, orbs, chat tab row, chat message area, open dialogue). They must stay in lockstep — a mismatch is
  cosmetic (a marker retired a frame later, or drawn by the old fallback), never a wrong click, but it will
  drift the first time one of those regions moves.
- A WIDGET tap now shows the walk ring for the ~100-150ms before the kind comes back, then withdraws it.
  That is new visible behaviour on inventory taps; if it reads as a flicker, the honest fix is publishing the
  widget bounds natively so contact can skip the ring there, not re-deferring the ring.
- Rapid tapping: the previous tap's pointer park is skipped while an arm is live, but a tap whose finger is
  down for less than ~3 frames still settles after the lift (it just loses nothing).
