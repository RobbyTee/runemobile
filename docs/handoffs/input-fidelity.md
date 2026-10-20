# Input fidelity — the synthetic stream stops inventing things

**Status:** all four items landed, desktop-verified, **device-unverified**.
**Commits:** `d54da5f6f9`, `7b2ef910ed` (item 1) · `455a634bf0` (keepalive) · `e32bae44c2` (item 3) ·
`04193e7d01` (item 2) · `a48430708d` (item 4) · `f5908f7fc5` (coalescing).

## The problem this series fixed

The bridge synthesizes AWT input from touch. Several parts of what it synthesized described things the user
never did — not approximations of real input, but fabrications with no referent:

| # | What was fabricated | Now |
|---|---|---|
| 1 | Every tap reported a **1ms hold**: press and release went out together at the lift. | Press at gate accept, release at the real lift. The delta is the finger's hold. |
| 2 | A tap was **one point**. The finger's travel was dropped; the engine saw a move at contact and nothing until the release. | Real samples stream as `MOUSE_DRAGGED`; the release closes at the lift point. |
| 3 | Every tap ended with a `MOUSE_MOVED` to **(−1,−1)** — a point no pointer can occupy. | Park is `MOUSE_EXITED` alone, carrying the point the finger left from. |
| 4 | Keys went out at `t`, `t+1`, `t+2` in one EDT drain; a 30-char paste arrived inside **one game tick**. | Each key is really held (45–85ms) and really spaced (25–55ms press-to-press). |
| — | An **idle keepalive** posted activity the user never produced, defeating the engine's idle-logout. | Deleted from all three codebases. |

## What each change cost, and why it was paid

- **Item 3 — the hover persists.** The `(−1,−1)` move existed for a real reason: the engine does not clear
  its tracked position on `MOUSE_EXITED`, so a move to a point that picks nothing was the only way to end a
  hover. Dropping it means a tapped entity stays lit and can re-light when a zoom re-projects it. That is
  what a desktop client does — a mouse stays where you let go of it — so it is a regression only against
  the fabricated version of ourselves. **If the lingering label reads badly on device, the fix is to stop
  drawing the label, not to move the cursor somewhere the finger never was.**
- **Item 2 — two deliberate limits.** Samples stream only *during* the press (a move during the settle
  would push the engine's cursor off the point the gate is resolving, so the gate could never accept and
  the arm would fail open — the latency arming exists to remove), and only *inside the 3D viewport* (over
  an interface a held left button is the engine's drag-to-rearrange, and 18pt of allowed roll is wider than
  an inventory slot, so replaying it would invent an item drag). `inWorldViewport` fails closed.
- **Item 4 — the dwell is a stand-in, and says so.** iOS hands us committed text, never a key down or up,
  so there is no measurement to report. What was wrong before was not the missing measurement but the
  reported one that never happened. Ranges, not constants, because a constant dwell is its own fabrication.

## Mechanics worth knowing before touching this

- **`postPointer(q, e)`** is the single seam every positioned `MouseEvent` goes through. It records
  `lastPointerX/Y` so the park's exit can report a real point. *Add new pointer posts through it*, or the
  "we never name a coordinate the finger was not at" property quietly stops being true.
- **`DRAG_STREAM_LOCK`** serialises samples (native UI thread) against press/release/retire (client
  thread). It is what guarantees no drag is posted after the release that ended it.
- **`EventQueue` coalesces pending mouse-motion events.** A burst of samples posted faster than the EDT
  drains folds into one. This is intended — a desktop toolkit does the same — and nothing is lost, because
  `streamPoint` is written as each sample arrives rather than read back out of the queue.
- **Key pacing chains, it does not pre-book.** `pumpKeys` schedules the next key's gap when the previous
  one *actually went out*. The first cut pre-booked absolute slots and a cold scheduler ran three overdue
  tasks back to back, collapsing them into one instant — the exact thing being fixed. Measuring from what
  happened can only run late, and late is a slower typist.
- **Modifier latches are not paced.** Sticky Shift/Ctrl/Alt are a held state a native toggle owns, not
  keystrokes; there is no dwell to invent.
- Kill switch: `-Drunelite.key.pacing=false` reverts to the back-to-back key shape.

## Gate status

- `./gradlew :client:test` — **green** (full suite; `JavaBridge*` run 3× for flakes, clean).
- `./gradlew :client:compileJava` — green. Shared contract **576 entries, unchanged** (two additive
  entries baselined: `JavaBridge#onTapMove(int,int)`, `FLAG runelite.key.pacing`).
- Android `:app:compileDebugKotlin` + `:app:externalNativeBuildDebug` — green.
- iOS `JVMLauncher.m` / `ViewController.m` — `clang -fsyntax-only` clean (17 warnings, all pre-existing).

## Next step

**Deploy and verify on device** — none of this is device-verified. In order of what would actually bite:

1. **The persistent hover (item 3).** Tap an NPC, then pan. Does a stale action label or a lit model read
   as broken, or as a desktop client? This is the one judgement call that needs eyes on the real thing.
2. **Taps still land where the finger landed (item 2).** Especially in the inventory, where the stream is
   guarded off — confirm no item ever drags itself to a neighbouring slot.
3. **Typing feels right (item 4).** A long paste into the bank search, and a password on the login screen.
   ~25 chars/sec should look instant; confirm nothing drops or reorders.
4. **The idle logout now happens.** Leave the app idle past the engine's timeout and confirm it logs out
   like desktop RuneLite, which is the whole point of deleting the keepalive.

## Open risks

- Every number in item 4 is a guess at human typing, not a measurement. If the engine or a plugin ever
  cares about the specific cadence, these are the knobs to revisit.
- Keys overlap (holds outlast gaps), so 1–2 keys are down at once. Normal for a typist and fine for the
  gamepack's pressed-key array, but it is new behaviour for this client.
- Android quantises samples to the render-thread drain (coalesced to the latest per frame); iOS passes
  every sample on the main thread. The two ports therefore feed slightly different sample densities.
