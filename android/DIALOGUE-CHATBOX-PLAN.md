# Dialogue + chatbox: plan to match official OSRS Mobile

Cross-platform (shared bridge + Android + iOS). Goal: stop the messy interaction between the transparent
chat veneer and the engine dialogue, and match the official OSRS Mobile behavior:

1. **Normal chat** — transparent chatbox showing messages over the scene (unchanged; works today).
2. **Dialogue open** — an **opaque** dialogue box over the chat (messages don't bleed through) **with the
   real full-size NPC chathead**, like desktop.
3. **Auto-open/close** — if chat is hidden/collapsed when a dialogue starts, auto-open it for the dialogue,
   then return it to hidden when the dialogue ends.

This was produced by a deep multi-agent investigation (6 readers across engine/bridge/Android/iOS/web +
3 design approaches + 2 adversarial reviews) and reconciled against the live code and the v1–v12 chat-saga
history. **Several of our earlier assumptions were wrong — corrected below.**

---

## Corrected facts (what we got wrong before)

- **The "~30px head" was an estimate, never measured.** There is no captured head-size anywhere. iOS's
  `[dlg] head=` log (`ViewController.m:2307`) always prints `CGRectZero` because `headInView` is hardcoded to
  `CGRectZero` at `:2292` — iOS deliberately shows **no** head. Android *does* cut out the head
  (`DialogueView.kt:104-144`) and the result is small — that's the real, observed evidence, not a number.
- **`CHATBOX_TRANSPARENCY` (varbit 4608) is a red herring.** It's inert on this build for the colour purpose,
  and the dialogue interfaces (`ChatLeft` 231 / `ChatRight` 217 / `ChatBoth` 60 / `Chatmenu` 219 /
  `Messagebox` 229) are **separate top-level groups**, not children of the chat group (162) or `CHAT_CONTAINER`
  (164). Flipping transparency cannot change the dialogue. **Do not add a transparency flip.**
- **"Skip `sizeChatArea` → full-size head" is FALSE.** `sizeChatArea` (`HudVeneerBridge.java:2405-2464`) only
  mutates group 162 + the 164 container slot. The head lives in group **231**. The bridge never writes to any
  dialogue widget — it only *reads* their bounds. So un-squeezing the chat area does nothing to the head.
- **An "engine renders the whole dialogue" path was ALREADY TRIED AND REVERTED.** `releaseChatForDialogue`
  (commit `1d0bd6aca`) was "confirmed working on device 2026-06-15", then reverted to the native parchment
  because the released engine dialogue rendered at the **natural bottom** (not the relocated top the mobile UI
  wants) and **tap-to-continue fell through to world-walk**. The native parchment is the survivor of a 12-step
  saga, chosen for reliable opacity + top-anchored taps. Re-attempting the full engine path blindly = re-treading
  a known dead-end.
- **The real engine constraint:** the engine refuses to *raster* a relocated chatbox (`HudVeneerBridge.java:143`),
  with a later correction (chat-saga STATUS #2) that relocated content *does* raster **as long as the engine tab
  widgets stay shown**. Whether the dialogue *subgroups* raster opaque-with-full-head **at the relocated top** is
  genuinely **untested** — the live code occludes them with the parchment.

**Net:** the narrow real defect is the **head**, not the whole dialogue. The parchment already gives us opacity,
top placement, working taps, and (on iOS) implicit auto-open. So the work is "get a real full-size head," not
"rebuild the dialogue."

---

## Decisive next step — a device PROBE (do this FIRST, no risky changes)

The whole plan branches on **why the head is small**. One measurement decides it. On a throwaway branch, two
no-behavior-change instrumentation edits:

1. **iOS:** replace `headInView = CGRectZero` (`ViewController.m:2292`) with the real published `head` rect from
   `d[@"head"]`, scaled like Android does — so the cutout opens and `[dlg] head=` logs the actual rect.
2. **Bridge log (one-shot, gated behind a flag):** when an NPC dialogue opens, log the full head-size picture
   with relocate ON **and** OFF — not just one knob, since the chat-widget model projection differs from the
   main scene: `ChatLeft.HEAD` `getBounds()` / `getWidth()` / `getHeight()` / `getOriginalWidth/Height`,
   `getModelZoom()`, `getRotationX/Y/Z`, **and** the parent chain bounds (`ChatLeft` root + the `CHAT_CONTAINER`
   host) so we can see whether the head is small because of (a) widget bounds, (b) modelZoom, (c) a clipped
   parent/viewport, or (d) relocate/raster-suppression.

Then open an NPC dialogue on device and read:

- **VF-1 — Does the engine raster the dialogue subgroup (opaque content + head) at the relocated top?**
  (rasters → the engine path is viable; blank/ghost → parchment-only.)
- **VF-2 — Is the head full-size (desktop ~96px) or small, and WHY?** Log the full set from step 2 above
  (bounds/width/height/modelZoom/rotations/parent bounds) — `modelZoom` alone is not enough; the chat-widget
  model projection differs from the scene, so a clipped parent/viewport or the widget bounds could be the cause.
  This distinguishes: (a) relocate/raster-suppression, (b) modelZoom, (c) widget/parent bounds clip, (d) other.
- **VF-3 — Do the dialogue groups render opaque under the current pipeline** (is the parchment even needed for
  opacity, or does the engine's own content sprite cover the chat)? Code already confirms varbit 4608 is set in
  exactly one place (`keepChatboxTransparent` `:2176`, always =1) and `sizeChatArea` never touches it — so
  forcing =1 is isolated; this VF only needs to confirm the dialogue's own content sprite is opaque on-device.

> NOTE: the off-device harness (`run-chat-veneer.sh`) reportedly has **no dialogue trigger** and the test account
> is on tutorial island — so this probe is **device-only**. Confirm a tutorial NPC can open a dialogue, else
> probe purely on the phone.

---

## Probe results — Stage 1 (device, Pixel 7a, NPC "Miss Schism", 2026-06-22)
Kotlin-only `DialogueView` log of the engine's published head bounds:
```
dlgprobe: headCanvas=(120,107  32x32)  headView=(240,214→304,278)  boxCanvas=(67,8 519x182)  scale=(2.0,2.0)
```
- **VF-1 = YES.** The engine rasters the chathead at the relocated top — it shows through the cutout.
- **VF-2 = the head WIDGET is 32×32 canvas px** inside a 519×182 box (desktop chathead is ~62×62+). So the head
  is small because the **engine allocates a tiny 32×32 widget** — the bounds cap the render; this is NOT a display
  scale or (primarily) a `modelZoom` issue. The fix must make the engine allocate a larger head widget.
- **VF-3 = not yet measured** (our parchment still occludes the engine's own background).

**Open question for Stage 2:** WHY is the widget 32×32 — does it grow if we stop relocating/resizing the chat
container during a dialogue (the design agents' "dialogue opens INTO the resized CHAT_CONTAINER" theory), or is it
fixed by the interface's own cs2 layout at our canvas size (the synthesis "separate group" view)? That needs the
heavier shared-bridge probe: while a dialogue is open, temporarily skip `sizeChatArea`/relocate and re-log the head
bounds — if it grows toward ~62px, the resize is the cause and "don't resize during a dialogue" is the lever
(weighed against the known natural-bottom placement + tap regression). Heavy deploy (jar pipeline + runtime.tag).

## Probe results — Stage 2 (device, Pixel 7a, NPC "Miss Schism", 2026-06-22)
Enlarged the head cutout to 2.5× the 32×32 widget bounds and captured the dialogue frame. Decisive:
- **The engine's 3D head MODEL overflows its 32×32 widget rect** — it renders at a good, desktop-like size
  (full face: hair, eyes, mouth). Our original cutout was sized to the *widget* (32×32) and so clipped ~75% of
  the model. **Enlarging the cutout reveals a proper head — a cheap, Kotlin-only fix, no bridge change.**
- **BUT the head model has no opaque backing in the forced-transparent pipeline.** A rectangular cutout reveals
  the live scene around the head silhouette (wall texture + floating overlay text bleed through). On desktop the
  head sits on the opaque dialogue parchment; here the engine doesn't paint that parchment (confirmed by the
  user's original report that the engine dialogue is transparent and chat bleeds through it).

**So the architecture is fully understood now:**
- VF-1 ✓ engine rasters the head at the relocated top. VF-2 ✓ head model is desktop-sized but the *widget* is
  32×32 (cutout was clipping it). VF-3 ✓ the engine dialogue is transparent (no opaque parchment) in our pipeline.

### The two real paths (decision needed)
- **Path A — enlarge the cutout (cheap, now, Kotlin-only).** Reveals a real, properly-sized head. Compromise:
  scene bleeds around the head silhouette (no opaque backing). A big upgrade from the tiny clipped head, but not
  pixel-exact desktop. Mitigations: size the cutout tight to the head model; the bleed is mostly muted wall.
- **Path B — engine draws its opaque dialogue (desktop-exact, heavy + uncertain).** The only way to get the head
  on a clean opaque parchment like desktop. Requires making the engine paint an opaque dialogue background at the
  relocated top — which is the unresolved hard problem (varbit 4608 is inert, dialogue groups are separate, and
  un-forcing transparency may not produce an opaque box). Bridge work + the historical relocate/tap regressions.

## The two candidate end-states (probe picks one)

### Fallback / default ship (lowest risk) — keep the parchment, feed it the real head
Keep the native opaque parchment for name/body/options, but show the engine's real 3D head through the cutout.
- **Android:** already does this (`DialogueView.kt` cutout) — but the head is small today, so this alone is
  **not sufficient** unless VF-2 shows we can grow it (head widget bounds / `modelZoom` at source).
- **iOS:** one-liner — stop passing `CGRectZero` so the existing `applyDialogueHeadCutout:` /
  `dialogueContentFrameForHead:` machinery shows the head.
- Zero geometry/transparency/auto-open changes. If VF-2 says the head can be enlarged at its source
  (`ChatLeft.HEAD` bounds/zoom), this becomes the clean ship.

### Primary (only if VF-1/VF-2 confirm) — let the engine render the dialogue
Add a new snapshot flag `engineDialogue` (true for engine-rendered types: npc/player/both/options/message/levelup;
false for make-X `Skillmulti` and reward boxes → stay on the `{}`/native path). Both native shells suppress their
parchment when it's set; the engine dialogue shows through.
- **Do NOT** release the dialogue to the natural bottom (that's the reverted regression). Keep it at the top.
- Keep transparency forced =1 (no flip). Keep `dialogueActive` exactly as-is (load-bearing for chat-line hide +
  tap routing).
- The exact tick() mutation is **deferred until the probe says what suppresses the dialogue raster** — don't
  pre-write it.

---

## Auto-open / auto-hide chat (independent of the above)

Single owner = **native, per port**, off the existing `dialogueActive` edge. **Do NOT** put it in the shared
bridge — `chatCollapsed` is native-authoritative on both ports; a bridge writer creates a two-owner desync.
**No race exists in this design:** `dialogueActive`/`dialogueOpen` is *computed* from `isDialogueOpen(c)`
(`HudVeneerBridge.java:558`) and only *published* in the snapshot (`:1556`) — it is engine-derived **read-only**,
never written as a control. The only state the auto-open mutates is `chatCollapsed`, mutated **only** by the
native side. So `dialogueActive` is the read-only trigger; `chatCollapsed` has a single (native) owner.
- **iOS:** likely already gets it "for free" — the parchment shows off `dialogueActive` regardless of collapse
  (`updateDialogueView:2272` doesn't check collapse). If the engine path replaces the parchment, preserve this
  explicitly on the `dialogueActive` rising/falling edge (`:2126`).
- **Android:** add rising/falling-edge save-restore in `applyChatTabs` (~`:1806`) using the existing
  `chatCollapsed` + `JvmLauncher.setChatCollapsed(...)`. No-op while a forefront window (bank/GE) occludes chat.

---

## Risks & guardrails
- **Highest risk:** the engine won't raster the relocated dialogue → probe gates it; fallback is the parchment.
- **Never** release the dialogue to the natural bottom (reverted regression: bottom placement + tap fall-through).
- **Never** split `chatCollapsed` ownership across bridge + native (two-owner race).
- make-X / reward boxes stay on the `{}` path (`engineDialogue=false`).
- Land both native gate changes together so the ports don't diverge; `engineDialogue` is the single shared switch.
- Rollback: the **engine-dialogue render path** and the **auto-open/close** feature are SEPARATE, each
  independently gated. Reverting the engine path = ignore `engineDialogue` (one-line per-port). Auto-open is
  keyed off `dialogueActive` (NOT `engineDialogue`) because it must work for BOTH the parchment fallback and the
  engine path — so it gets its OWN enable flag; do **not** couple the `chatCollapsed` mutation to `!engineDialogue`
  (that would disable auto-open exactly when the engine path ships).
- **Cutout perf:** the Android cutout uses a single rectangular `clipOutRect` — the cheapest, HW-accelerated
  clip at minSdk 28 on the target device; keep it. If a frame-time issue ever appears, replace it with 2–3
  parchment rects drawn around the head (no clip at all) — not an even-odd `clipPath` (a non-rect path clip is
  the same cost or worse).

## Key files
- `runelite-client/src/main/java/net/runelite/client/headless/HudVeneerBridge.java` — lines 143, 558-687,
  1556-1568, 2208-2327, 2405-2464 (chat snapshot, dialogue JSON, `head` bounds, chat geometry).
- `ios/app/HelloShell/ViewController.m` — 2121-2144, 2265-2345 (parchment, head cutout fed `CGRectZero` at 2292).
- `android/app/src/main/java/dev/audaro/helloshell/MainActivity.kt` — 367-388 (collapse), 1805-1813 (applyChatTabs).
- `android/app/src/main/java/dev/audaro/helloshell/DialogueView.kt` — 104-148 (existing head cutout).
