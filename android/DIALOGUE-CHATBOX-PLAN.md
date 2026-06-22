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
2. **Bridge log (one-shot, gated behind a flag):** when an NPC dialogue opens, log
   `c.getWidget(InterfaceID.ChatLeft.HEAD).getBounds()` **and** `getModelZoom()`, with relocate ON **and** OFF.

Then open an NPC dialogue on device and read:

- **VF-1 — Does the engine raster the dialogue subgroup (opaque content + head) at the relocated top?**
  (rasters → the engine path is viable; blank/ghost → parchment-only.)
- **VF-2 — Is the head full-size (desktop ~96px) or small, and what's its `modelZoom`?**
  This distinguishes: (a) relocate/raster-suppression, (b) a `WidgetType.MODEL` zoom/bounds issue, (c) other.
- **VF-3 — Do the dialogue groups render opaque under the current pipeline** (is the parchment even needed for
  opacity, or does the engine's own content sprite cover the chat)?

> NOTE: the off-device harness (`run-chat-veneer.sh`) reportedly has **no dialogue trigger** and the test account
> is on tutorial island — so this probe is **device-only**. Confirm a tutorial NPC can open a dialogue, else
> probe purely on the phone.

---

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
- Rollback is a one-line per-port gate (ignore `engineDialogue`) — no bridge revert needed.

## Key files
- `runelite-client/src/main/java/net/runelite/client/headless/HudVeneerBridge.java` — lines 143, 558-687,
  1556-1568, 2208-2327, 2405-2464 (chat snapshot, dialogue JSON, `head` bounds, chat geometry).
- `ios/app/HelloShell/ViewController.m` — 2121-2144, 2265-2345 (parchment, head cutout fed `CGRectZero` at 2292).
- `android/app/src/main/java/dev/audaro/helloshell/MainActivity.kt` — 367-388 (collapse), 1805-1813 (applyChatTabs).
- `android/app/src/main/java/dev/audaro/helloshell/DialogueView.kt` — 104-148 (existing head cutout).
