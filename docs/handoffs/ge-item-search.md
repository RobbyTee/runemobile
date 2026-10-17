# The GE item search — a prompt is not a dialogue, and the engine draws it

Branch `master`. Commits `41c1da5fc1` (window stands down for the prompt), `073acee23e` (a prompt lives
INSIDE the chat area), `1f7a348cb6`, `d14f5b82fd`, `790ccbfe84` (the native panel, since reversed),
`11e50e7084` (hand the drawing back to the engine) and `65a84387ae` (show the box under it). All pushed
to `origin`.

**Device-verified 2026-10-17** — iPhone 17 Pro Max / iOS 27.2, Debug build installed with
`xcrun devicectl device install app` and launched under StikDebug. The user confirms the search opens,
lists results with their item icons, and now draws on an opaque box. This step is closed.

## What a chat prompt is

A GE/bank item search is NOT a dialogue, and treating it as one was the first mistake. A dialogue
REPLACES the chat area; a prompt lives INSIDE it, drawn into the message layer under whatever forefront
window (bank, GE) is open. So every rule written for a dialogue was wrong for it by one level:

- The forefront-window rule hid the window a dialogue appears over — but the prompt is drawn UNDER that
  window, so hiding nothing left the prompt invisible. The window now stands down for the length of a
  prompt that lists its answers (`promptHiddenWindow`, latched so clearing the search box does not flap
  the window back on).
- `hideMsgArea` hid the chat area for a dialogue. The prompt is drawn into that area, so hiding it hides
  the prompt. It now yields to a prompt even while the chat is collapsed.
- Only a prompt that puts a LIST in the message layer gets this. A bank search filters the bank items
  themselves and puts no list in the chat; "Enter amount" needs the window it is a number for.

## Why the engine draws it, after a native panel was built and removed

The panel (`d14f5b82fd`, `790ccbfe84`) rendered the results as native rows. It worked, and it was worse:
desktop already draws this list properly — item icons, three columns, the matched prefix underlined, its
own scrollbar — and matching that natively would mean building an item-icon pipeline to the shell, which
does not exist here, to arrive back at a worse copy of a grid the engine already draws. So the engine
keeps the drawing and the published rows went back to being what they are good for: **tap targets**. That
part is not optional — a click over the relocated chatbox resolves against the SCENE behind it and walks
the player instead of picking (see the chatbox rule in the project notes), so the rows must be pressed as
menu actions whatever is drawing them.

Consequences, all deliberate: a prompt reports `engineDialogue` true (the native parchment must stay out
of the way rather than cover the grid with a text copy of it); both shells keep their tap targets live
while the engine owns the drawing, which is the Path B arrangement and this is the same case;
`MAX_PROMPT_ROWS` is 24, sized to the engine's visible grid plus a scroll, not to what a panel could hold.

## The trap worth carrying forward

**The chatbox has two writers every frame, and the pre-raster one wins.** `tick()` runs in the post-render
draw callback; `preRender()` runs from `Hooks.frame()` before anything rasterizes. Chatbox state is written
at BOTH, so a condition added in `tick` and not mirrored in `preRender` is reverted before it can be seen —
and it reads as "the state was never set", not as two writers disagreeing. This feature paid for that
lesson twice:

1. `11e50e7084` — the transparency varp. `tick` flipped it on `dlgOpen` alone, `preRender` on the prompt
   too, so under a search they wrote opposite values every frame, and every change runs `BUILD_CHATBOX`:
   the chatbox was being rebuilt twice a frame and the opaque box never had a frame to survive in.
2. `65a84387ae` — `CHAT_BACKGROUND`. With both ends finally agreeing on transparency 0, the grid STILL had
   nothing behind it, because `preRender` was hiding the background widget on `!dialogueOpen` alone while
   `tick` showed it for a prompt. Transparency picks WHICH box the engine paints; `CHAT_BACKGROUND` is the
   widget that paints it. Setting one without the other buys nothing.

## Gate status

- `./gradlew :client:compileJava` — clean. `:client:shadowJar` + `gpu-routec/stage-ios-gpu-jar.sh` —
  `MEGAVERDICT = ROUTEC_STAGE_GREEN`. Shared contract — UNCHANGED (575 entries).
- The `HudVeneerBridge.class` inside the installed bundle's `client-shaded.jar` was confirmed
  byte-identical to the freshly compiled class (jar entries carry zeroed timestamps, so the md5 is the
  check that means anything — a date proves nothing).
- **Device: verified** (2026-10-17, user-confirmed).

## Open risks

- **Android is unverified for this chain.** The branches are there (`MainActivity.kt` got the canvas-tap
  case in `11e50e7084`, since a search row was firing a chat-option digit at a widget the prompt does not
  use), but nothing has been run on the Pixel. The shared-bridge half is the same; the shell half is the
  part that has been wrong before, in exactly this area.
- **The window stand-down is latched for the life of the prompt.** If a prompt ever closes without the
  tick that clears it, the GE/bank stays hidden. `promptHiddenWindow` is reset on `!promptOpen`, which is
  computed every tick, so the exposure is a dropped tick, not a missing path — but it is the failure that
  would look like the game vanishing.
- **`promptListsResults` decides this by "the scrollcontents widget has dynamic children".** That is what
  the GE search populates; another prompt that happens to put children there would also take the window
  down. No such prompt is known, and the bank search (the one that must NOT) puts nothing there.
- The engine tab row draws at the top of the relocated chat during a prompt (the NATIVE row is hidden by
  `windowOpen`). That matches desktop, where the tabs stay visible under a search, and it is only visible
  at all because a collapsed chat auto-expands for a prompt — both shells un-collapse on `dialogueActive`,
  which a prompt now sets. If that auto-expand is ever removed, the engine draws NOTHING while collapsed
  (hiding any tab widget suppresses the whole chat raster) and the search goes invisible with no native
  panel left to fall back on.
