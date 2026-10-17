# Instant click — the tap delay, and who decides what a tap is

Branch `master`. Commits `97cbf49a22` (marker), `7116329bb6` (bridge arm), `9c5e7d03a4` (review fixes),
`55001a3b8d` (the tap classifier) and `8319badf4f` (one owner for the arm). All pushed to `origin`.

**Device-verified 2026-10-17** — installed to the iPhone with `xcrun devicectl device install app` straight
from `build/dd-serveipa/.../RuneMobile.app`, launched under StikDebug, and the user confirms the clicks are
fixed. This step is closed; what follows is kept as the record of why it is the way it is.

## What the delay actually was

Three waits stacked on one tap, and none of them was the game being slow:

1. **`UITapGestureRecognizer` fires on finger-UP**, and not even then if the touch rolled — see the next
   section, this turned out to be the bigger half of the problem.
2. **The engine-resolved gate.** A left tap is never re-hit-tested — the engine consumes the press against
   the menu it built from the cursor position earlier in the same tick — so `JavaBridge` moves the synthetic
   cursor to the tap point, waits until the engine has demonstrably resolved it (cursor match + a stable
   left-click tail, `STABLE_NEEDED = 2` frames), and only then presses. Every one of those frames was spent
   after the lift, because the lift was the first the bridge heard of the tap.
3. **The click marker waited for the answer.** The ring is red for an interactable and yellow for a walk, so
   it was drawn only once the kind came back — and for a walk nothing comes back, so it waited out the full
   0.25s grace. The commonest tap in the game therefore marked itself ~250ms after a finger that had
   already lifted.

## Who decides what a tap is (the part that mattered most)

UIKit's numbers are wrong for this game. A pan recognizer begins after **~10pt**, which is inside the roll of
an ordinary finger-tap, and the moment it begins it takes the touch away from **both** the tap and the
long-press. So a tap with a little roll in it rotated the camera three pixels and never clicked, and a
press-and-hold with a little roll never opened the menu either: the touch was classified as a drag before the
user had finished making it. That is the "sometimes my taps don't register" feel, and no amount of shaving
latency off the click path would have touched it.

So the decision is ours now, and **one number** makes it — `kSceneDragSlop = 18.0` (≈3mm: past a finger's
roll, well short of a deliberate drag), read by all three gestures so they cannot disagree:

| one-finger touch | who takes it |
| --- | --- |
| strays ≤ 18pt, lifts within 0.45s | **click**, at the point it LANDED on |
| strays ≤ 18pt, held past 0.4s | long-press → menu (`allowableMovement` raised to the same 18pt) |
| strays > 18pt | camera drag (or map / chat / inventory / interface drag) |
| second finger joins | pinch → zoom |

- `RMSceneView` (the canvas) tracks each one-finger sequence: landing point, the **furthest** it ever strayed
  (not where it ended — a finger that swings out and comes back was a drag), and how long it was down. A lift
  that reaches `-touchesEnded:` is a tap *by elimination*: a recognizer that claims a touch cancels it in the
  view, so a sequence that gets that far is one nobody else wanted. There is no tap recognizer any more.
- `RMCameraPanRecognizer` withholds `-touchesMoved:` from `super` until the finger leaves that 18pt radius,
  so the pan stays in `Possible` and claims nothing until it is really a drag. It also records the **landing
  point** and exposes it, because the drag branches classify a drag by where it started (which inventory
  slot, the chat, the map) and the old code reconstructed that from the translation at `Began` — fine at
  UIKit's 10pt, a whole inventory slot's worth of wrong once the withheld slop is 18pt.
- Cost of the change: rotation now has an 18pt dead zone instead of 10pt before the camera starts moving.

## What else shipped

- **The marker is drawn on the lift**, in the walk colour, in `worldTapAtScreenPoint:` — the one place that
  knows the tap is a world click and not one of the routed paths (dialogue, world map, orb, chat, engine
  dialogue), so a camera drag or a UI tap never flashes a ring it has to take back. The resolved kind still
  reaches the ring already on screen: `promotePendingRingToInteract` repaints it red,`retirePendingRing`
  takes it back for a WIDGET tap (the engine cuffs those itself). One slot is enough now that nothing is
  drawn before the lift; a second tap inside the answer window takes the slot and the ring it replaced just
  finishes its fade in the colour it already has.
- **The gate runs while the finger is down.** `JavaBridge.armTap(x,y)` on contact enters the same
  `AWAIT_ENGINE` without a pending click and parks in the new `ARMED` state when it accepts;
  `commitArmedTap` posts the click at the lift. It re-proves both observables first (cursor still on the arm
  point, tail still the one that stabilised) and falls back to the full gate otherwise, so the fast path can
  never click against another point's menu. An arm that only failed open returns to rest, so arming can only
  help. `ARMED_TIMEOUT` (120 frames) is the fail-safe.
- **One owner for the arm's lifetime**, because a leaked arm is not harmless: a live arm refuses to arm the
  *next* touch, so one leak costs the fast path on the following taps. `onSceneTouchUp:` cancels
  unconditionally after the routing — a no-op when the click consumed the arm (both calls hop through
  `ClientThread.invoke`, so the cancel runs after the commit and sees `CLICKED`), and the drop on every other
  exit: a routed precise click, a dialogue that appeared mid-touch, a lost bridge, a stray it rejected. The
  router has thirteen exits and tracking them one by one was the bug review found. `RMSceneView` also reports
  `touchGone` for a sequence that ends with no lift — a recognizer claimed it, or the system took it — which
  is the backstop that does not depend on which gesture won or on whether its handler got as far as its own
  cancel (`onCameraPan:` returns before its cancel when a menu is open). Nothing is drawn and nothing is pressed at contact — a touch is not yet a
  tap, and that is exactly why the arm exists: it spends the engine's frames, not the user's.
- The click goes to where the finger **landed**, not where it lifted: it is the point the user aimed at, it
  is the point the engine has been resolving since contact, and committing to it is what makes the fast path
  apply to a tap that rolled.
- Tunables, all `Integer.getInteger`: `runelite.touch.armSlop` (4 canvas px), `runelite.touch.armedTimeout`.
- **`tapUncommitted` lives as long as the arm does.** It is what the lift reads to know an `ARMED` state is
  still its own, so clearing it on entry to `ARMED` (as the first cut did) silently disabled the whole fast
  path: every lift fell back to a fresh gate. The invariant now is "set iff a finger-down arm is live", every
  exit clears it, and `parkPointer`'s guard is stated in terms of the state so it cannot depend on the flag's
  lifetime. That one came out of review, not testing — see **Open risks**.

What is left: the click happens **on lift**, which is what it is for. Everything after the lift is one
client-thread hop.

## Gate status

- `./gradlew :client:compileJava` — clean. `:client:shadowJar` + `gpu-routec/stage-ios-gpu-jar.sh` —
  `ROUTEC_STAGE_GREEN`; the staged `client-shaded.jar` carries `armTap(II)V` and `cancelArmedTap()V`
  (verified with `javap` on the staged jar, which is what the app boots). The classifier is native-only —
  no Java change, so no jar rebuild.
- `clang -fsyntax-only` on `ViewController.m` and `JVMLauncher.m` — 17 warnings, all pre-existing, none in
  the new code. `git clang-format --staged` applied.
- Android bridge census — **GREEN, 78/78 resolved, 0 missing**; shared contract baseline refreshed
  (additive: two methods, two flags). While using it: the census picked its jar with a glob whose first
  match is the lexicographically smallest version, so it had been reporting GREEN for a July jar. It now
  reads `project.build.version` from `gradle.properties` like the staging scripts do.
- **Device: verified** (2026-10-17, iPhone 17 Pro Max / iOS 27.2, Debug build under StikDebug).

## Deploying it again

Two routes, and the direct one is the better one when the phone is on this Mac's own network or USB:

- **Direct:** `xcrun devicectl device install app --device <id> build/dd-serveipa/Build/Products/Debug-iphoneos/RuneMobile.app`
  after any `serve-ipa.sh` run has built it. No SideStore, no resign, JIT unchanged (the attach is still
  per-launch). The installed app reports version `0.1`/`1`, though: the real version is stamped by PlistBuddy
  onto the IPA *copy*, so a direct `.app` install carries Info.plist's hardcoded one, and re-stamping would
  invalidate the signature. Don't identify a direct install by its version number — check the dylib instead
  (next bullet).
- **Verify what actually landed:** the app's code is in `RuneMobile.app/RuneMobile.debug.dylib`, not the 87KB
  main-binary stub, and `grep` on a Mach-O can report nothing where `strings` finds it. So:
  `strings -a .../RuneMobile.debug.dylib | grep -x RMCameraPanRecognizer`. "Installed successfully" says
  nothing about *what* was installed.
- **SideStore:** `ios/dev-tools/serve-ipa.sh` (jars already staged, so no `--jars`). Two traps fixed on the
  way through, both of which used to report something other than what happened: the standalone Tailscale app
  does not put its CLI on PATH, so the script died with "is Tailscale up?" while the tunnel was up (it now
  looks inside the app bundle); and the build counter lives in the gitignored `.serve-ipa`, so a cleaned work
  dir restarts it at 1 — a version *lower* than the phone's is not an update, SideStore shows nothing to do,
  and the publish still looks like it succeeded. It now warns when it had no counter to read.
- Remember the one-VPN rule: Tailscale ON to install from SideStore, OFF before StikDebug attaches, or the
  tunnel to 10.7.0.1 resets.

Direct-to-phone *over the tailnet* remains impossible — iOS interface-gates lockdownd to the phone's own
LAN/loopback, proven at packet level in `ios/dev-tools/DEPLOY-REMOTE.md`. The install above works because the
phone is local to this Mac, not because the gate moved.

## What to check on device

In this order:

1. A tap on open ground rings **instantly on the lift** and the ring is yellow.
2. A tap on an NPC/object rings instantly and turns **red** when the interaction resolves.
3. **A sloppy tap** — land and lift with an obvious roll, up to about a fingertip's width — still clicks, at
   the point it landed on, and the camera does not move. This is the case the classifier exists for.
4. **A sloppy hold** — press and hold with the same roll — still opens the menu.
5. A deliberate drag rotates the camera (now after a slightly longer dead zone) and leaves no ring and no
   click at its origin. Same for a pinch.
6. An inventory/prayer/tab tap rings briefly and the ring is **withdrawn** (no walk ring left behind). An
   inventory **drag** still picks up the item under the finger — not its neighbour (the landing point).
7. Spam-tap two spots alternately: each tap's ring keeps its own colour and position.
8. The thing this was for: taps land when you expect them to, and nothing is dropped.

With `-Drunelite.touch.diag` the log prints `L settle: N frames` then `L commit: armed N frames before the
lift` for an armed tap; a tap that fell back prints the old settle line alone.

## Open risks

- **The arm is a change to the tap state machine**, which is the thing that fixed wrong-target taps. One
  device session says the feel is right; it does not prove the guard. The signature to watch for over a
  longer play session is a tap acting on the PREVIOUS tap's target.
- **`RMCameraPanRecognizer` withholds touches from `super`.** It is the standard way to give a pan a
  threshold and the handler reads nothing but translation deltas (no velocity), but it is UIKit internals: if
  a drag ever begins and then behaves oddly on the first frame, this is the first place to look.
- **18pt was a guess; it now has one session's confirmation, which is not the same as calibration.** If a tap
  ever drops, raise it; if the camera feels sticky to start, lower it. One constant, both effects — that is
  the point of it being one constant (`kSceneDragSlop`, `ViewController.m`).
- A WIDGET tap shows the walk ring for the ~100-150ms before the kind comes back, then withdraws it. If that
  reads as a flicker, the honest fix is publishing the widget bounds natively so the lift can skip the ring
  there, not re-deferring the ring.
- `isPlainWorldTapAtGameX:gameY:screenPoint:` mirrors `worldTapAtScreenPoint:`'s early returns. Worth keeping
  in lockstep, but it now only decides whether to ARM, so a mismatch costs an arm nobody commits or a fast
  path not taken — never a wrong click or a wrong ring.
- Rapid tapping: the previous tap's pointer park is skipped while an arm is live, but a tap whose finger is
  down for less than ~3 frames still settles after the lift (it just loses nothing).
