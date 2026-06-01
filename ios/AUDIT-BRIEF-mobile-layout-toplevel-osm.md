# Audit brief — the Jagex-Mobile layout (ToplevelOsm / interface 601): flip vs re-create (2026-06-01)

Self-contained; a reviewer needs only this doc. Asking for an adversarial verdict on **how (and whether) to
make our client wear the official OSRS Mobile interface layout** — the split edge-tabs / orbs / mobile chat
seen in the official app — *before* we invest in the native UI layer. The headline question:

> **Can we make our client build the game's own mobile toplevel (interface 601, `ToplevelOsm`), or must we
> re-create that layout natively over the desktop layout — and even if we *can* flip, *should* we, given the
> project's "unmodified RuneLite plugins" rule targets the desktop widget IDs?**

## Where we are (all device-proven, iPhone 17 Pro Max, iOS 26.5 TXM, on-device JIT via StikDebug)
Real unmodified RuneLite runs on an on-device JIT OpenJDK 21 → CACIO CTC toolkit → live OSRS → full-screen
landscape, with a working touch/keyboard control scheme and a device-proven `MobileUiBridge` (plugin/config
control plane). The client currently renders the **desktop resizable** layout (`Toplevel` / `ToplevelOsrsStretch`),
stretched to fill the safe area. It does **not** look like the official mobile app — tabs are a bottom-right
cluster, not split to the screen edges. Matching the Jagex-Mobile look is the stated W3 goal.

## The finding that prompts this brief
`net.runelite.api.gameval.InterfaceID.ToplevelOsm` is interface group **601** (`0x0259`) and is the **complete
official mobile toplevel**, present in the **shared game cache our client already downloads**. Its 135
components are exactly the screenshot layout, e.g.:
`SIDE_LEFT_CHAT (0x0259_0029)`, `SIDE_RIGHT_TABS (0x0259_0032)`, `SIDE_RIGHT_TABS_BACKGROUND`, `ORBS
(0x0259_0025)`, `MINIMAP (0x0259_0022)`, `COMPASSCLICK`, `CHAT_TOGGLE`, `CHAT_FILTER_TOGGLE`, `CHATTING_BUTTON`,
`CHAT_CONTAINER`, `HOTKEYS`, `WIFI`, `BATTERY`, `LOGOUT_CONTAINER`, and corner anchors
`TOP_LEFT/TOP_RIGHT/TOP_MIDDLE/MIDDLE_*/BOTTOM_*`.

OSRS interfaces are built by **CS2 clientscripts that live in the shared cache** and run on the client's script
interpreter. So the *definition* of the mobile layout is reachable by our client in principle. Whether the
*build path* is reachable is the unknown.

## The two paths
**Path 1 — flip the client into the mobile toplevel (use Jagex's own layout).** Get our desktop-gamepack
client to build interface 601 as its root toplevel instead of `Toplevel`/`ToplevelOsrsStretch`. If it works we
get the real mobile layout for free — Jagex's pixels, their tab/orb/chat behaviour, no re-creation.

**Path 2 — re-create the layout natively.** Hide the desktop interface widgets and draw native edge tab-columns
/ orbs / chat that proxy to the game (tab switches, panel toggles) via `MobileUiBridge`/widget calls. Large,
fragile (the gamepack re-lays-out its widgets every frame), and a perpetual maintenance load — but it does not
depend on the gamepack supporting a mobile build path, and it keeps the desktop widget IDs intact for plugins.

## Questions to adjudicate (verdict + reasoning each)
1. **Is Path 1 even reachable on the desktop gamepack?** What triggers the client to build `ToplevelOsm` vs
   the desktop toplevel — a `clientType` field, a VarClient/VarClientInt, the display-mode/resize path, or a
   CS2 `[proc]` we could fire via `client.runScript(...)`? Crucially: **does the desktop gamepack contain the
   code path to build 601 at all, or is that logic in the mobile native binary** (i.e. only the CS2 *scripts*
   are shared, but the *trigger* to run them is mobile-only)? If the trigger is mobile-binary-only, Path 1 is
   dead and we go to Path 2.
2. **If 601 loads, does it actually function?** Tabs/orbs/chat-toggle on mobile are tapped; are those
   interactions CS2-driven (hence portable to our client) or do they need the mobile client's native input
   wiring? A layout that renders but whose tabs don't switch is worse than the desktop layout.
3. **THE strategic tension: does Path 1 break "unmodified RuneLite plugins work"?** RuneLite plugins target
   **desktop** widget IDs (inventory/prayer/chat groups, `WidgetInfo`/`InterfaceID.Toplevel*`), and many
   overlays anchor to them. Under `ToplevelOsm` the widget IDs change (601's own components). So Path 1 may
   force a choice between *looks exactly like mobile* and *the plugin ecosystem keeps working* — which is the
   entire point of the project. Quantify: how many of the plugins we care about would break, and is there a
   shim? This may be the deciding factor even if Path 1 is technically feasible.
4. **Interaction with our stack.** Our blit (`JavaBridge.snapshot`) composites whatever the engine draws, so a
   different toplevel is transparent to the display. But: (a) `HeadlessResize` drives a resizable `WxH` — does
   601 want a different sizing path? (b) our touch **snap** (`JavaBridge.resolveSnap`) is world-only and must
   not fire over the new edge-tab UI — does the world viewport region change under 601? (c) chat-input bounds
   (`MobileUiBridge.tick` → `INPUT_CLICKAREA`/`CHATBOX_INPUT`) are desktop widget IDs — they'd move to 601's
   chat components.
5. **Login/EULA dependence.** Our autologin clicks fixed coords (EULA 302,312; Play Now 382,248) on the
   *login* screen (pre-toplevel). Does flipping to mobile change the login interface too, or only the in-game
   toplevel? (If it changes login, the autologin coords break.)
6. **Reversibility / gating.** Whatever the trigger, can we gate it (a property like `-Drunelite.mobileui.toplevel`)
   so we can A/B it on device and fall back to desktop layout instantly? No silent one-way switch.
7. **Sequencing + biggest risk.** If Path 1 is GREEN, how does it reorder the remaining UI work (the native
   plugin-config panel, chat-tap, DPI)? What single thing should the spike settle first to avoid wasted work?

## What we know about the seams (for the spike)
- `Client` exposes `runScript(Object...)`, `setVarbit`/`setVarbitValue`, `setVarcIntValue`, `getWidget(...)`,
  `isResized()`, `invalidateStretching(boolean)`. There is **no** `openInterface`/`setToplevel` in the API —
  the client builds the toplevel itself, so the trigger is indirect (a var/script/clientType the build logic
  keys on). Finding that trigger is the crux of the spike.
- Widgets can be hidden/repositioned (`Widget.setHidden` via `isHidden`, `setOriginalX/Y`, `revalidate`) —
  this is the Path-2 toolkit if Path 1 fails.
- The shared cache is what the device already downloads (601's definitions are there); no cache surgery.

## Proposed spike (cheapest path to a verdict)
1. **Desktop first (W2 rule).** On desktop OpenJDK 21 + CACIO, find the toplevel-build trigger: inspect how
   the client selects `Toplevel`/`ToplevelOsrsStretch` (decomp/RuneLite source for the resize/display-mode
   path and any `clientType`/VarClient gate), then attempt to force 601 (try the candidate var/script). Log
   which toplevel built + whether 601's components instantiate.
2. If 601 builds on desktop: probe **function** — does a tab component switch tabs? does the world still
   render? do a handful of representative RuneLite plugin overlays still anchor correctly (Q3)?
3. **Device confirm** the desktop GREEN (the only real verdict), gated + reversible (Q6), md5-gated against the
   verbatim `jvm-stdio.log`.
4. Verdict: **GREEN** (adopt the real mobile toplevel; redesign the native UI to host inside it) or **RED**
   (Path 2 native re-creation, or a hybrid: desktop layout + mobile *styling*).

## Constraints / trust rules (unchanged)
- **Device-only** for the real verdict; desktop OpenJDK 21 is the develop-first mirror.
- **Trust only verbatim device artifacts** (`jvm-stdio.log`/`hs_err`/crash `.ips` via `devicectl`) +
  bash-computed md5-gated verdicts (documented false-GREEN history).
- **RuneLite plugins must keep working** is a first-class constraint, not an afterthought — see Q3.
- **GPLv3 sources are patterns-only**; **throwaway Jagex account only**; self-built client on live OSRS is
  ban-risk / not shippable — personal technical build.

## Pointers (all in-repo)
- Mobile layout definitions: `runelite-api/src/main/java/net/runelite/api/gameval/InterfaceID.java` →
  `class ToplevelOsm` (group 601) and the desktop `Toplevel`/`ToplevelOsrsStretch`/`ToplevelPreEoc`.
- Our blit/input/resize seams: `runelite-client/.../headless/JavaBridge.java` (snapshot + `resolveSnap`),
  `HeadlessResize.java` (resizable `WxH`), `MobileUiBridge.java` (`tick` → chat-input bounds), `HeadlessLogin.java`
  (autologin coords), `Hooks.java` (`draw`).
- Native shell + chrome that already exists: `ios/app/HelloShell/{ViewController.m,JVMLauncher.m}`
  (`debugPanel` + `≡` toggle + `⌨` button + game `screen` with gesture pass-through).
- Prior briefs/verdicts (the rhythm to mirror): `ios/AUDIT-BRIEF-phase6-native-shell.md`,
  `ios/AUDIT-BRIEF-phase5-mobile-controls.md`, `ios/AUDIT-BRIEF-w1-phase3-awt.md`.
- Current project state: memory `project_ios_gate_progress` (device build/install loop documented there;
  device UDID, bundle id, log path).

**Bottom line for the reviewer:** the prize is real (Jagex's own mobile layout sits in the cache we already
have), but two things could sink Path 1 — the build *trigger* may be mobile-binary-only, and even if it isn't,
switching widget IDs may break the unmodified-plugin guarantee that justifies this whole project. Adjudicate
both before any UI is built; the answer decides whether we ever hand-build that layout.
