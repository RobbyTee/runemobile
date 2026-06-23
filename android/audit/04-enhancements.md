# Phase 4 — Enhancements

> Anchored enhancement backlog for the Android `:app` native shell. Every entry here is tied to a **named RuneLite plugin/feature** AND an **OSRS-Mobile interaction pattern** — nothing speculative, nothing un-anchored, nothing that re-invents what already ships. The discipline: an idea earns a slot only if (a) the *capability* already exists somewhere in the stack (a shared bridge, an engine widget, a plugin event) and (b) the *gap* is a real surfacing/presentation deficit, ideally one Phase-3 already named as a UX-* finding. Far-out ideas and ideas the code already satisfies are listed — with the reason — in the **Excluded** appendix, so the no-far-out filter is auditable.
>
> Two buckets: **(A) Surface un-surfaced RuneLite features** — capabilities that cross the bridge (or fire in a plugin) today but have no native front door; and **(B) Better mobile-first re-presentations** — capabilities that *are* surfaced but presented in a desktop-shaped way that mis-fits a touch screen. Stable ids (`ENH-A##` / `ENH-B##`) are cited verbatim by the ROADMAP. Cross-links to Phase-3 UX-* ids mark which gap each item closes.
>
> **Anchor-check provenance:** of 32 anchor-checked candidates, **24 kept** (CONFIRM/ADJUST), **4 dropped as already-present**, **0 rejected as un-anchored** (the candidate set was pre-filtered — see appendix). Effort S/M/L, risk low/med, scope android-only vs shared/iOS-impacting, priority P1–P3 carried from the verified grade.

## 1. TL;DR

The port is a faithful desktop-RuneLite-on-a-phone with a thin native HUD veneer. The richest enhancement seam is **not new features** — it is the gap between what the *shared bridges already compute* and what the *Android native shell actually draws*. Multiple complete bridges (Time Tracking, GE search, orb-state, config sections) emit rich data that the native side drops on the floor; several plugins (Screenshot, Notifier) fire useful events that never reach a mobile-native surface (the system shade, the gallery, the share-sheet). Surfacing those — and re-presenting the half-dozen desktop-shaped controls (flat config scroll, name-only search, inert keybind/font rows, kick-like world hop) the OSRS-Mobile way — is the whole of Phase 4.

**Philosophy:** only RuneLite/OSRS-Mobile-anchored work. Every entry names the plugin/feature it surfaces and the OSRS-Mobile pattern it mirrors; most lean on a bridge that already exists; many close a Phase-3 UX finding by id.

**Top 6 punchlist:**

1. **ENH-B01 — Sectioned config sheet** (collapsible `@ConfigSection` headers + search): the bridge already emits sections; the only P1 in the UX audit (anchor: RuneLite `@ConfigSection` / OSRS-Mobile collapsible Settings; closes **UX-PLUGCFG-01**).
2. **ENH-B06 — "Hopping…" scrim on the World Switcher**: cover the 8-second relog flash so a routine hop stops reading as a kick (anchor: RuneLite WorldHopper relog path + the app's own ReconnectingOverlayView / OSRS-Mobile "Hopping to world N…"; closes **UX-DISCRETE-01/-07**).
3. **ENH-A08 — Typed notification channels** (icon/group per event class): carry the engine's `TrayIcon.MessageType` through the bridge so the shade entry *is* the information (anchor: RuneLite Notifier typed notifications / OSRS-Mobile glanceable shade; substrate for ENH-A03/A06/A07).
4. **ENH-A05 — Renderer-agnostic screenshot + share-sheet**: fix the "Screenshot unavailable" dead-end under GPU/117HD and follow through to `ACTION_SEND` (anchor: RuneLite Screenshot plugin / OSRS-Mobile share-first; closes **UX-DISCRETE-03/-04**).
5. **ENH-A02 — Time Tracking native reflow** (farming / birdhouse / timer countdowns): a complete, wired bridge with no native UI today (anchor: RuneLite Time Tracking plugin / OSRS-Mobile quick-status check).
6. **ENH-A09b — Branded adaptive launcher icon + app label + notification small-icon**: retire the `HelloShell` codename and the generic shade glyph; the force-multiplier under every notification item (anchor: app identity / OSRS-Mobile icon-and-label polish; closes **UX-ONBOARD-03**).

---

## A. Surface un-surfaced RuneLite features

Capabilities present in a shared bridge or a plugin event that have **no native Android front door** today.

### A.1 Ranked table

| id | title | anchor | builds on existing bridge? | effort | risk | priority | scope |
|---|---|---|---|---|---|---|---|
| **ENH-A02** | Time Tracking native reflow (farming/birdhouse/timer countdowns) | Time Tracking plugin | yes — TimeTrackingBridge (complete, wired, no native UI) | M | low | **P1** | android-only |
| **ENH-A01** | GE item price-check / Search tab | Grand Exchange plugin item-search | yes — GrandExchangeBridge.search (built, unwired on Android) | M | low | **P1** | android-only |
| **ENH-A05** | Renderer-agnostic GPU screenshot → share-sheet | Screenshot plugin (DrawManager capture) | partly — CPU path + MediaStore exist; GPU readback new | M | med | **P1** | shared/iOS-impacting |
| **ENH-A08** | Typed notification channels (icon/group per event class) | Notifier `TrayIcon.MessageType` | partly — additive bridge field (drop the per-plugin-name claim) | M | low | **P1** | shared/iOS-impacting |
| **ENH-A09b** | Branded adaptive icon + app label + notification small-icon | app identity | no — resources only | S | low | **P1** | android-only |
| **ENH-A03** | Per-plugin notification sub-editor + global RuneLite defaults | NotificationPanel + RuneLiteConfig defaults | yes — setConfig round-trip + iOS emitFakePlugin | M | med | **P2** | shared/iOS-impacting |
| **ENH-A04** | Surface Screenshot auto-capture toggles → save to gallery | Screenshot plugin auto-capture config | yes — saveScreenshot/MediaStore + ENH-A05 getter | M | med | **P2** | shared/iOS-impacting |
| **ENH-A06** | Auto-screenshot milestones → celebratory notification + Share | Screenshot plugin event triggers | partly — nextScreenshot drain exists, unconsumed | M | med | **P2** | shared/iOS-impacting |
| **ENH-A07** | Glanceable farming/birdhouse "Timers" ongoing notification | Time Tracking plugin | yes — TimeTrackingBridge JSON + ENH-A08 channel | M | med | **P2** | android-only |
| **ENH-A09a** | Home-screen launcher shortcuts (world / saved profile) | OSRS-Mobile per-world launch + WorldSwitcher/ConfigProfile | partly — profile leg clean; world leg needs new param | L | med | **P3** | android-only |

### A.2 Detailed entries

#### ENH-A02 — Time Tracking native reflow (farming patches / birdhouses / timers)
- **Anchor:** RuneLite **Time Tracking** side panel (farming-patch timers, contract, birdhouses, custom timers/stopwatches), re-presented as the OSRS-Mobile "quick status check" glanceable list. `TimeTrackingBridge` is **complete and already `init()`/`tick()`'d** by MobileUiBridge, emitting per-patch and per-birdhouse `readyTime` as absolute epoch-seconds (the `-1`/`0`/epoch convention) + state — but there is **no native half on Android**, so it falls to the OOM-prone rendered-Swing-image fallback.
- **Mobile rationale:** "when are my herbs/trees/birdhouses ready" is the canonical check-in-and-log-off mobile session. As a rendered image it is a flat, blurry, click-only multi-tab panel where tab-switching is a guessed tap and countdowns aren't first-class, degrading under the supersample cap on tall variants. A native screen turns it into glanceable countdown rows.
- **Interaction sketch:** a native `TimeTrackingScreen` mirroring the XP/Loot/GE screens — top tab strip (Farming | Birdhouses | Timers | Stopwatches), each a list of rows with name + a live "ready in 2h 14m" countdown (green when ready). Polls the bridge ~1 s while visible, bracketed by `setUiOpen` like the other live screens.
- **Plumbing:** cheap pattern-copy; shared bridge is done. Add `nativeTimeAvailable`/`SetUiOpen`/`Json` externs in JvmLauncher, a `registerDataPanel(…TimeTrackingBridge,getTimeTrackingJson)` in `jvm_bootstrap.cpp` (the uniform helper fits this exact signature), a `TimeTrackingScreen` Composable, and a NativeRow + route in PanelView/PluginPanel. No shared change.
- **Effort:** M · **Risk:** low · **Scope:** android-only · **Priority:** P1.
- **Closes:** the rendered-image fallback for Time Tracking; complements **ENH-A07** (the same data as an ambient notification).

#### ENH-A01 — Grand Exchange item price-check / Search tab
- **Anchor:** RuneLite **Grand Exchange** plugin's item-search / price-check box (`ItemManager.search` → GE guide price), re-presented as the OSRS-Mobile GE keyboard item-search field. `GrandExchangeBridge.search(query)` already returns `{results:[{id,name,price}]}` but is **NOT wired on Android** (no `nativeGeSearch`); the GE screen shows only live offers + saved history.
- **Mobile rationale:** checking an item's price is one of the most frequent player actions, and on a phone you usually do *not* have a GE counter open. The bridge computes guide prices off the immutable price cache (safe off the client thread, no engine read), so it's a self-contained price-check tool reachable any time. Today the only price info on Android is the 8 live offers; "what is a Dragon bones worth" has no path short of task-switching to a browser.
- **Interaction sketch:** a third tab on the existing GrandExchangeScreen (Offers | History | **Search**). Search = a top-pinned OutlinedTextField (sheet is top-anchored so the IME pushes nothing) + a results list of name + GP-formatted guide-price rows. Typing debounces ~300 ms then calls the bridge; tap a result to copy the price or (optional) deep-link the Wiki. Mirrors the shipped world-map search IME pattern.
- **Plumbing:** shared bridge method already exists. Owe only the native half — a `nativeGeSearch(String)→String` extern + a `jvm_bootstrap.cpp` registration (same shape as the existing GE/XP/Loot data-panel calls), then a Compose Search tab reusing `parseGe`'s JSON helpers. No plugin fork, no baseline growth.
- **Effort:** M · **Risk:** low · **Scope:** android-only · **Priority:** P1.

#### ENH-A05 — Renderer-agnostic screenshot via GPU readback, then the Android share-sheet
- **Anchor:** RuneLite **Screenshot** plugin (renderer-independent capture via DrawManager), re-presented with the OSRS-Mobile share-first follow-through. Half-built on Android: `saveScreenshot` works on the **CPU path only**; under live GPU/117HD the direct-present path does `eglSwapBuffers` with **no readback** into the Java bridge buffer, so `getFramePixels` has no GPU frame to return (the back buffer is **consumed** by the swap, not retained) → "Screenshot unavailable" fires under both accelerated renderers. `writePngToGallery` already obtains the MediaStore `Uri` but **returns only a Boolean and discards it** — no share path.
- **Mobile rationale:** the two accelerated renderers are exactly the ones a player picks for the best-looking capture, yet capture dead-ends under both. And on a phone the dominant post-capture intent is to *share* (Discord / clan chat), not hunt the gallery.
- **Interaction sketch:** tap the existing Screenshot tile under any renderer → brief flash/haptic → a share-sheet slides up with the PNG ready to send, "Save to gallery" as one option. Under GPU/117HD the frame comes from the readback instead of the dead CPU getter.
- **Plumbing:** two pieces. **(a)** There is **no cheap "latest frame" getter** — `eglSwapBuffers` consumes the back buffer. Capture requires either a `glReadPixels` taken **at present time** (hook the existing RouteC present callback that already fronts the swap) **or** rendering to an off-screen FBO and reading that back — a **per-capture readback cost** (fine for an on-demand screenshot; must NOT run every frame), wired to a JvmLauncher native getter; keep `framePixels()` under CPU. **(b)** android-only: hold the `Uri` from `writePngToGallery` and fire `Intent.ACTION_SEND` (image/png) via the content `Uri`. (b) is cheap and standalone; (a) is the renderer-agnostic lift (shared — iOS benefits).
- **Effort:** M · **Risk:** med · **Scope:** shared/iOS-impacting · **Priority:** P1.
- **Closes:** **UX-DISCRETE-03** (screenshot fails under 2 of 3 renderers), **UX-DISCRETE-04** (no share). Is the substrate for **ENH-A04** and **ENH-A06**.

#### ENH-A08 — Carry the notification's event type through the bridge for proper channels / icons / groups
- **Anchor:** RuneLite **Notifier** typed notifications. The engine's `TrayIcon.MessageType` (NONE/INFO/WARNING/ERROR) IS a real RuneLite construct, available at the firing site (`Notifier.notify` reads `notification.getTrayIconType()`; `NotificationFired` carries the `type`), re-presented as OSRS-Mobile glanceable shade entries. **Anchor-check correction:** the per-firing-plugin-name idea is dropped (NotificationFired has no plugin source field). Facts corrected: the bridge payload is already **4-field SOH-delimited** (`title␁body␁soundType␁volume`), and Android already has **two** channels (`NOTIF_CHANNEL` audible + `NOTIF_CHANNEL_SILENT`), keyed by sound — not one flat pair.
- **Mobile rationale:** every game alert lands as an identical entry on a sound-keyed channel pair, so a user can't distinguish "you're idle" from "low HP" from "farming ready" at a glance, nor silence one class without silencing all. Typed channels let Android's own per-channel importance/sound/DND do the work the desktop per-notification config does.
- **Interaction sketch:** an INFO idle alert → low-key icon on a "Status" channel; a WARNING low-HP alert → red-tinted icon on a high-importance "Combat alerts" channel that buzzes; farming-ready → a "Timers" channel. Same-family alerts collapse under a notification group. Long-press routes to that Android channel's settings.
- **Plumbing:** small additive **shared** change — append the `TrayIcon.MessageType` to the existing SOH payload. Android builds on `deliverNotification()` + `setupNotifications()`: add ~3 channels keyed by type, map type → channel/icon/group. Reuses the whole drain path.
- **Effort:** M · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P1.
- **Substrate for:** **ENH-A03** (per-notification editor), **ENH-A06** ("Celebrations" channel), **ENH-A07** ("Timers" channel), **ENH-B11** (gated action buttons).

#### ENH-A09b — Branded adaptive launcher icon + "Old School" app label + notification small-icon
- **Anchor:** the presentation half of the app's identity, against the OSRS-Mobile adaptive-icon + label pattern. The manifest still ships `android:label="HelloShell"` and reuses the 320×180 TV banner as both icon and banner (there is **no `mipmap/` dir at all** — no adaptive icon), and `deliverNotification` falls back to the generic `stat_notify_more` glyph.
- **Mobile rationale:** the home-screen icon and the notification small-icon are the app's most-seen surfaces and both read as an unfinished dev build. A proper adaptive icon + a monochrome notification icon are table-stakes, and make every notification this audit proposes look like a product in the shade rather than a generic Android alert.
- **Interaction sketch:** a round/adaptive OSRS-styled icon (fg+bg layers) on the launcher; a crisp monochrome OSRS small-icon on every shade entry; the label reads as the game, not "HelloShell".
- **Plumbing:** pure android-only resources — add `mipmap-anydpi-v26` adaptive icon, a monochrome notification drawable, move the name to `@string/app_name`, keep `tv_banner` only on `android:banner`, point `setSmallIcon` at the new drawable. No code logic, no shared change.
- **Effort:** S · **Risk:** low · **Scope:** android-only · **Priority:** P1.
- **Closes:** **UX-ONBOARD-03** (HelloShell label + reused TV-banner icon). Force-multiplier for ENH-A03/A06/A07/A08 and ENH-B11.

#### ENH-A03 — Per-plugin notification config sub-editor + global RuneLite notification defaults
- **Anchor:** RuneLite **NotificationPanel** per-notification editor (enable/sound/volume/flash/colour/game-message) + the global `RuneLiteConfig` notification-defaults group. The bridge tags items as type `notification`; iOS already built both the per-item sub-editor and the `emitFakePlugin` global-defaults injection. **Note:** Android's per-item *config* editor for the `notification` type already landed (FIX-7) — see the Excluded appendix; the genuinely-new half here is the **global RuneLite defaults fake-plugin** (never injected into `listPlugins` on Android) plus harmonizing the per-item editor against it.
- **Mobile rationale:** notifications already route to the real Android shade, so on a phone they are *more* central than on desktop. The per-notification controls exist, but the global default sound/flash/colour group is unreachable on Android — surfacing it closes the loop on a feature the port half-ships.
- **Interaction sketch:** a "RuneLite" entry appears in the plugin list whose config is the notification defaults, rendered through the existing enum/int/color controls; window-only fields (tray/request-focus) with no mobile analogue stay hidden.
- **Plumbing:** one additive **shared** merge — port the iOS `emitFakePlugin(RuneLiteConfig.GROUP_NAME)` + `RUNELITE_HIDDEN_KEYS` (grows `listPlugins` output only; iOS already done). Native renders it through the existing controls.
- **Effort:** M · **Risk:** med · **Scope:** shared/iOS-impacting · **Priority:** P2.
- **Pairs with:** **ENH-A08** (typed channels) so per-event tuning can deep-link the OS channel UI.

#### ENH-A04 — Surface the Screenshot plugin's auto-capture toggles + save kills/levels/pets to the gallery
- **Anchor:** RuneLite **Screenshot** plugin auto-capture config (`screenshotLevels`/`Kills`/`Pet`/`Rewards`/`ValuableDrop`), whose plugin fires `takeScreenshot` on those events via the renderer-independent DrawManager path. Android has a working manual CPU screenshot-to-MediaStore, but the **event-driven captures aren't surfaced** and the DrawManager capture isn't delivered to the gallery.
- **Mobile rationale:** auto-screenshotting your level-ups, pets, and big drops is a quintessential "share my win" mobile behaviour and a marquee desktop QoL plugin. The save-to-gallery plumbing exists; what's missing is exposing the toggles cleanly and routing the event captures into the same MediaStore path.
- **Interaction sketch:** in the Screenshot plugin's (now sectioned, see **ENH-B01**) config, the auto-capture toggles read as a clean list ("Screenshot level-ups", "boss kills", "Pets", "Clue rewards", "Valuable drops" + threshold). When an enabled event fires, the captured frame writes to Pictures/RuneLite and a "Saved screenshot" notification fires with tap-to-open.
- **Plumbing:** builds on the existing screenshot-to-gallery + the existing notification drain. The event captures need the renderer-agnostic getter from **ENH-A05** so DrawManager captures route to MediaStore; the config toggles ride **ENH-B01** sections for free.
- **Effort:** M · **Risk:** med · **Scope:** shared/iOS-impacting · **Priority:** P2.
- **Note:** parity audit marks consumer-plugin DrawManager delivery "unverified" on Android — the milestone capture itself needs device verification.

#### ENH-A06 — Auto-screenshot on level-up / pet / collection-log / boss-kill → celebratory notification with thumbnail + Share
- **Anchor:** RuneLite **Screenshot** plugin's event triggers (LEVEL_UP, BOSSKILL, COLLECTION_LOG, PET — confirmed firing `takeScreenshot` in `onChatMessage`), combined with OSRS-Mobile celebration moments. A `nextScreenshot()` drain channel **already exists in the bridge but is unconsumed on Android** — the milestone→notification hook is partly pre-built.
- **Mobile rationale:** these milestones are the screenshots players actually want to share. Desktop silently drops a PNG in a folder; on mobile, surfacing the moment AS a glanceable celebration (a notification with the captured frame inlined) turns a buried file into a shareable highlight.
- **Interaction sketch:** hit 99 Cooking → a "Level up! 99 Cooking" notification with a BigPicture thumbnail and a "Share" action (ENH-A05's pipeline). A pet drop / new collection-log entry does the same on the "Celebrations" channel.
- **Plumbing:** leans on the unmodified Screenshot plugin. New: consume the existing `nextScreenshot()` drain to forward "a milestone was just captured (name, subdir)" into the notification queue (reuse ENH-A08's typed payload), and android-only `NotificationCompat.BigPictureStyle` + the ENH-A05 share action.
- **Effort:** M · **Risk:** med · **Scope:** shared/iOS-impacting · **Priority:** P2.
- **Depends on:** **ENH-A05** (GPU case + share) and **ENH-A08** (Celebrations channel).

#### ENH-A07 — Glanceable farming/birdhouse timers as a persistent "Timers" notification while skilling
- **Anchor:** RuneLite **Time Tracking** plugin via the already-complete-and-wired `TimeTrackingBridge` (emits per-patch/per-birdhouse `readyTime` + READY/GROWING state), with no native Android UI — re-presented as an OSRS-Mobile ambient-status shade entry.
- **Mobile rationale:** farming/birdhouse cycles are the canonical "check your phone later" loop — exactly what a glanceable notification is for. A low-priority ongoing notification ("Herb patch ready in 12m · Birdhouses ready") turns the phone's own shade into the timer, no app-open needed.
- **Interaction sketch:** while any cycle is pending, a silent ongoing notification on the "Timers" channel shows the soonest ready time ("Next: Herb patch · 12 min") with an expanded list of all patches/birdhouses; updates as times tick, clears when nothing's pending.
- **Plumbing:** builds directly on the existing `TimeTrackingBridge` JSON (low-rate background poll); android-only ongoing `NotificationCompat` on **ENH-A08**'s "Timers" channel. No shared change (data already emitted). A true foreground Service is optional and would also harden background-keepalive.
- **Effort:** M · **Risk:** med · **Scope:** android-only · **Priority:** P2.

#### ENH-A09a — Home-screen launcher shortcuts to a specific world / saved profile (long-press the app icon)
- **Anchor:** OSRS-Mobile's per-world launch + the shipped WorldSwitcher favourites (`setFavourite` / `worldhopper.favorite_<id>`) and `ConfigProfileBridge.switchProfile`/`profilesJson`. Android dynamic app-shortcuts are the platform-native fast path.
- **Mobile rationale:** a player who always uses world 308 or a "Skilling" profile must boot, wait, then hop/switch manually each time. Long-press-the-icon shortcuts are the native shortcut, and the favourites/profiles data already exists in two shipped bridges.
- **Interaction sketch:** long-press the app icon → dynamic shortcuts ("World 308", "Profile: Skilling"); tapping cold-boots onto that world or with that profile pre-selected. Favourited worlds auto-populate.
- **Plumbing (anchor-check ADJUST — scope raised):** `ShortcutManager` dynamic entries are android-only. **The profile leg is clean** (`switchProfile` + `profilesJson` exist). **The world leg is under-scoped in the candidate:** the autologin world is **hardcoded** (`JagexTokens.autologinOpts` bakes a fixed world + `worldAddress`), so cold-booting onto an arbitrary world needs **net-new world-parameterization** + host derivation. And intent extras don't reliably survive `finishAffinity()`+relaunch — the app uses **marker files** for exactly this — so the shortcut→boot-gate hop likely needs the marker-file side-channel, not extras — and the shortcut handler must **write the marker synchronously before firing the launch Intent** (Writer→Intent order) so the boot-gate read doesn't race the writer.
- **Effort:** **L** (world leg) / S (profile leg) · **Risk:** med · **Scope:** android-only · **Priority:** P3.

---

## B. Better mobile-first re-presentations of existing capabilities

Capabilities that *are* surfaced today but presented in a desktop-shaped way that mis-fits a touch screen.

### B.1 Ranked table

| id | title | anchor | builds on existing bridge? | effort | risk | priority | scope |
|---|---|---|---|---|---|---|---|
| **ENH-B01** | Sectioned config sheet (collapsible `@ConfigSection` + search) | RuneLite `@ConfigSection` | yes — sections already emitted, iOS consumes | M | low | **P1** | android-only |
| **ENH-B06** | Hopping scrim + per-row spinner on the World Switcher | WorldHopper relog path | yes — WorldSwitcherBridge + ReconnectingOverlayView | M | low | **P1** | shared/iOS-impacting |
| **ENH-B02** | Plugin search over description (not just name) | RuneLite PluginSearch | yes — description already emitted | S | low | **P1** | android-only |
| **ENH-B09** | Coherent "Settings" entry (renderer/account/logout/toggles under one gear) | OSRS-Mobile Settings gear | yes — pure re-arrangement of existing tiles | M | low | **P2** | android-only |
| **ENH-B13** | World-id search + region label on the World Switcher | RuneLite WorldHopper | yes — location already in worldListJson | S | low | **P2** | android-only |
| **ENH-B15** | Keep screen awake in-play + clock/battery glance | OSRS-Mobile awake-in-play | no — window flag + small veneer | S | low | **P2** | android-only |
| **ENH-B11** | Notification action buttons (Open / Quick-hop / Sign back in) | HOP-VIA-RELOG + deliverNotification | yes — relog path + PendingIntent exist | M | med | **P2** | android-only |
| **ENH-B08** | Orb state veneer (run/spec/quick-prayer active-glow + XP orb) | minimap orb cluster | yes — getOrbSnapshot + enlarged proxy exist | M | low | **P2** | shared/iOS-impacting |
| **ENH-B07** | Touch-native Menu Entry Swapper "set as tap action" row | Menu Entry Swapper | yes — MenuOverlayView + setConfig | M | med | **P2** | shared/iOS-impacting |
| **ENH-B12** | Hold-then-drag gate + pickup haptic for item rearrange | engine drag (FIX-4) | yes — drag path + vibrateTick exist | S | low | **P2** | shared/iOS-impacting |
| **ENH-B14** | Larger long-press menu: safe-area clamp + open haptic | native press-and-peek menu | yes — MenuOverlayView + safeInsets() | S | low | **P2** | android-only |
| **ENH-B16** | RuneLite-account sync onboarding card + "Synced as <user>" chip | RuneLite-account sync | yes — RuneLiteAccountBridge | S | low | **P2** | shared/iOS-impacting |
| **ENH-B03** | Keybind config rows: show binding + Clear | HotkeyButton control | yes — keybind string already emitted | S | low | **P2** | android-only |
| **ENH-B04** | FontType rows: REGULAR/BOLD/SMALL preset picker | RuneLite FontPanel | partly — additive bridge case (iOS done) | S | low | **P2** | shared/iOS-impacting |
| **ENH-B05** | Per-plugin Reset-to-defaults (with confirm) | ConfigPanel Reset | partly — additive resetPluginConfig (iOS done) | M | low | **P2** | shared/iOS-impacting |
| **ENH-B10** | Overlay quick-toggle sheet (one-tap show/hide active overlays) | RuneLite overlay management | yes — OverlayBridge enumeration | M | med | **P3** | shared/iOS-impacting |
| **ENH-B18** | Chat scrollback drawer (swipe-up to a tall history) | RuneLite chat + ChatPillView | yes — ChatPillView + scrollChat | M | med | **P3** | android-only |

### B.2 Detailed entries

#### ENH-B01 — Sectioned config bottom-sheet: parse the `@ConfigSection` grouping the bridge already emits
- **Anchor:** RuneLite **ConfigPanel** `@ConfigSection` collapsible headers, against the OSRS-Mobile Settings pattern (options under collapsible labelled headers, not one infinite scroll). `MobileUiBridge` already emits per-item `section`+`position` and a full `sections` array (name/description/closedByDefault/position), and **iOS already renders these into collapsible CFG-6 headers** — `ConfigScreen.parseConfig` reads **none** of it.
- **Mobile rationale:** config-heavy plugins (117HD, Ground Items, Menu Entry Swapper) become one undifferentiated 60-row flat scroll where vertical space is scarcest. Collapsible sections (default-collapsed when `closedByDefault`) turn the wall into a few tappable group headers — exactly the recognition-over-recall OSRS-Mobile relies on. The data already crosses the bridge each open; Android just drops it.
- **Interaction sketch:** group the LazyColumn by `section`, sorted by `section.position` then `item.position`; each group is a tappable OSRS-style header card (name + chevron) that expands/collapses, `closedByDefault` collapsed; ungrouped items fall into a leading "General" group. A pinned search field filters across all sections, auto-expanding matches.
- **Plumbing:** native-only; shared half is built + iOS-verified. Add `section`/`position` to the `ConfigItem` data class, parse the `sections` array in `parseConfig`, wrap rows in collapsible groups. No bridge or plugin change.
- **Effort:** M · **Risk:** low · **Scope:** android-only · **Priority:** P1.
- **Closes:** **UX-PLUGCFG-01** (the only P1 in the UX audit).

#### ENH-B06 — Hopping scrim + per-row spinner on the World Switcher
- **Anchor:** RuneLite **WorldHopper** (the relog hop path; `relogPending` flag already exists), re-presented as the OSRS-Mobile "Hopping to world N…" held screen, reusing the app's own `ReconnectingOverlayView` OSRS spinner.
- **Mobile rationale:** the in-engine hop is inert headless, so `worldHop` drives logout→changeWorld→relogin (~8 s of raw engine login/welcome chrome). `getWorldListJson` carries no hop-progress, and the reconnect scrim deliberately excludes a hop — so every routine world switch reads as a **kick** for 8 seconds. OSRS-Mobile holds the screen with a labelled "Hopping…".
- **Interaction sketch:** on hop, the tapped row shows an inline spinner and the rest of the list dims/disables; a full `ReconnectingOverlayView`-style scrim with "Hopping to world {N}…" covers the logout/login flash until the engine settles in-world, then auto-dismisses. A failed hop surfaces a dismissible "Could not hop to world {N}" card with Retry instead of stranding the user on raw login.
- **Plumbing:** builds on the existing WorldSwitcherBridge + ReconnectingOverlayView. One additive **shared** field — emit `hopping:true`+target into the world-list snapshot while `relogPending`, and a failed-hop flag from `cancelWorldHop`. Native reuses the existing scrim view + the existing WorldSwitcherScreen poll.
- **Effort:** M · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P1.
- **Closes:** **UX-DISCRETE-01** (hop reads as a kick) and **UX-DISCRETE-07** (hop failure is silent).

#### ENH-B02 — Plugin search over description (not just display name)
- **Anchor:** RuneLite **PluginSearch** matches name + description + `@PluginDescriptor` tags. Android's plugin-list filter is **name-only** even though `listPlugins` already emits `description` AND the app's own Plugin Hub screen already searches description — an internal inconsistency.
- **Mobile rationale:** on a phone you can't scan a long alphabetical list at a glance, so search IS the primary discovery path. A user who knows what a plugin does ("xp", "drops", "overlay") but not its display name finds nothing today and assumes it's absent. Matching description makes the field actually work, the way the sibling Hub search already does.
- **Interaction sketch:** no new UI — the same field matches name OR description (case-insensitive), so "price" surfaces Item Prices/GE. Pair with a "No plugins match — try the Plugin Hub" empty state.
- **Plumbing:** native-only, trivial — carry `description` into the PluginRow/parsePlugins and broaden the predicate, mirroring the Hub search. No bridge or plugin change. (Tags would need an additive bridge field — optional, skip for v1.)
- **Effort:** S · **Risk:** low · **Scope:** android-only · **Priority:** P1.
- **Closes:** the discovery half of **UX-PLUGLIST-03** (no-match empty state).

#### ENH-B09 — Coherent "Settings" entry: gather renderer / account / logout / toggles under one gear
- **Anchor:** the OSRS-Mobile **Settings gear** (account/graphics/controls in one place) + RuneLite config, re-presenting capabilities the app already has scattered: the renderer radio, Account tile, Logout, and sticky-Shift/keyboard toggles.
- **Mobile rationale:** app-level settings are fragmented — renderer is a radio inside the Plugins launcher, Account/Logout are tiles reachable only after opening "≡ Plugins", the ⌨/⇧ controls are unlabeled glyph chips in a bottom devBar, and nothing is called "Settings". Every capability already works; they just have no front door, which on a phone is where users instinctively look for graphics/account/logout.
- **Interaction sketch:** a labelled Settings section at the top of the bottom-sheet (or a dedicated gear cell) groups Graphics (the existing renderer radio + its "Restart now" dialog), Account (Jagex login status + Logout + the RuneLite-account sync row), and Controls (the existing sticky-Shift + manual-keyboard toggles, now labelled). No new behaviour — pure consolidation + labelling.
- **Plumbing:** cheap, android-only — re-arrange the existing PluginPanel tiles/rows + renderer picker + the existing logout/account/keyboard paths into one labelled Compose section; rename the launcher handle to "Settings" or add a gear glyph.
- **Effort:** M · **Risk:** low · **Scope:** android-only · **Priority:** P2.
- **Closes:** **UX-XCUT-08** (no "Settings" front door; fragmented controls).

#### ENH-B13 — World-id search + region label on the World Switcher
- **Anchor:** RuneLite **World Switcher** (worldhopper). The native `WorldSwitcherScreen` already lists worlds and parses `WorldRow.location` but **never renders it**; `worldListJson` already carries `location`. OSRS-Mobile pattern: a searchable world-select with region grouping.
- **Mobile rationale:** the list offers only Members/Free chips — no way to jump to a specific world number — and the region/country (already parsed, already in the JSON) is dropped. On a phone, scrolling ~60 worlds to find "my" world is painful; a numeric filter + a region label needs only native rendering of data the app already has.
- **Interaction sketch:** a numeric/text filter above the list narrows live to matching world ids; each row shows its region label on the right. The first-load spinner gets a "Loading world list…" caption so the lazy-load doesn't read as a hang. Favourites stay pinned on top.
- **Plumbing:** cheap, mostly native — render the already-parsed `location` → region label in `WorldRowItem`, add a filter TextField + a load caption. No bridge change.
- **Effort:** S · **Risk:** low · **Scope:** android-only · **Priority:** P2.
- **Closes:** **UX-DISCRETE-09** (no world-id filter) and **UX-DISCRETE-02** (region label dropped).

#### ENH-B15 — Keep the screen awake during active play + an in-HUD clock/battery glance
- **Anchor:** OSRS-Mobile keeps the screen awake during active play; the app's `enterImmersive()` uses transient-swipe bars but **never sets `keepScreenOn`** (no `FLAG_KEEP_SCREEN_ON`/wakelock anywhere). Independently graded P2 by the perf audit.
- **Mobile rationale:** the canonical mobile play loop — watching a fishing/woodcutting session — dies to the screen timeout mid-AFK, and a backgrounded session can then drop. Keeping the screen on (scoped to in-world) is exactly the OSRS-Mobile behaviour. A small glanceable clock/battery readout answers "how long / is my battery OK" without leaving fullscreen.
- **Interaction sketch:** while in-world the screen never dims; backgrounding or the login screen releases the flag so it doesn't drain idle. A tiny clock + battery % sits in the top safe-area corner of the immersive HUD, styled like the classic-View veneers.
- **Plumbing:** android-only and tiny — `window.addFlags(FLAG_KEEP_SCREEN_ON)` on the SurfaceView, released on `onStop`/login. The clock/battery glance is a small classic-View veneer reusing the ChromeOverlayView styling + a BatteryManager read. No shared change.
- **Effort:** S · **Risk:** low · **Scope:** android-only · **Priority:** P2.
- **Closes:** **UX-XCUT-07** (screen timeout drops AFK sessions).

#### ENH-B11 — Notification action buttons: "Open here", "Quick-hop world", "Sign back in"
- **Anchor:** the existing `deliverNotification` + the shipped **HOP-VIA-RELOG** WorldSwitcher path and the single-top reopen PendingIntent already built; the BroadcastReceiver+registerReceiver pattern is already established in the file. OSRS-Mobile pattern: actionable notifications you respond to without fully foregrounding the game.
- **Mobile rationale:** today the notification is tap-to-open-only. On a phone the highest-value post-alert move is often a one-tap response — an idle/AFK alert where you want to hop to a quieter world, or a "logged out" alert where you want to jump straight back.
- **Interaction sketch:** the idle notification shows "Open" (reopens via the existing PendingIntent) and "Quick-hop" (fires a broadcast → routes to the existing relog-hop); a session-lapse notification shows "Sign back in". Buttons are `NotificationCompat.addAction` with PendingIntent broadcasts to a small receiver.
- **Plumbing:** builds on the existing `deliverNotification` Builder + the existing relog hop. New plumbing is android-only — a tiny BroadcastReceiver + `PendingIntent.getBroadcast` actions; no shared change. Gate the action set on **ENH-A08**'s event type so only relevant actions appear. **Race-safety:** route the hop through the SAME `WorldSwitcherBridge` client-thread path (already serialized via `ClientThread.invokeLater`) rather than a second actor, and make the relog **cancellable / a no-op when a manual logout is already in flight**, so a notification-action hop and a user logout cannot fight over the relog state.
- **Effort:** M · **Risk:** med · **Scope:** android-only · **Priority:** P2.
- **Depends on:** **ENH-A08** (event-type gating).

#### ENH-B08 — Native orb state veneer: run/spec/quick-prayer active-glow + XP orb in the enlarged proxy
- **Anchor:** RuneLite minimap orb data — `HudVeneerBridge.getOrbSnapshot` already publishes hp/prayer/run/spec/store/worldmap/wiki bounds, and the **enlarged orb touch veneer already exists** (`nearestOrb`/`ORB_KEYS`/`clickPrecise` proxy to the live orb centre). OSRS-Mobile pattern: orbs visibly light up when run/spec/quick-prayer are active.
- **Mobile rationale:** the tap-zone proxy already exists, so the "no orb veneer" parity finding is stale. What's genuinely missing is the OSRS-Mobile **visual state** — the engine orbs render at ~25 px and their active-glow is too small to read at arm's length — and **XP is omitted from the enlarged-zone proxy** (UX-HUD-02), so the XP-drops toggle is a dead-feeling target next to the minimap walk surface.
- **Interaction sketch:** a thin TapRing-sibling draws an OSRS-tinted ring/glow over the live orb when its state is active (run enabled, spec'ing, quick-prayer on) — overlay only, the engine orb stays the truth. The XP orb is added to `ORB_KEYS` with the same enlarged hit-zone so a tap reliably toggles XP drops instead of falling through to a minimap walk. Feedback is the existing light haptic.
- **Plumbing:** mostly existing. Add `xp` (`Orbs.XP_DROPS`) to `ORB_KEYS` — trivial, bounds already enumerated. The active-state glow needs one additive snapshot field per orb (run/spec/prayer active booleans) appended to `getOrbSnapshot`, then a small native draw layer. Shared (iOS mirrors).
- **Effort:** M · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P2.
- **Closes:** **UX-HUD-02** (XP orb omitted from the enlarged proxy).

#### ENH-B07 — OSRS-Mobile-style long-press menu with a "set common action as single-tap" default-swap row
- **Anchor:** RuneLite **Menu Entry Swapper** (already wired on Android — default swaps work) + the existing native `MenuOverlayView` press-and-peek menu + `invokeMenuEntry`/`selectMenuPoint`. OSRS-Mobile pattern: tap = the configured default action, with the right-click-equivalent reachable on hold.
- **Mobile rationale:** the native long-press menu already works, but the only way to make a non-default action the one-tap action is editing Menu Entry Swapper config by hand. On a phone the high-value idiom is "long-press this entity once, pick Set as default tap". The plumbing (menu entries enumerated, `invokeMenuEntry`, the Swapper plugin) is all present; only the surfacing is missing.
- **Interaction sketch:** with the press-and-peek menu open over an NPC/object/item, an extra row (styled like the BACK/PARENT roles it already has) reads "Make {option} the tap action". Selecting it writes the corresponding Menu Entry Swapper config key (the same key desktop writes), so future single taps fire that action — no Shift, no desktop. A long-press still surfaces the full menu for the occasional alternate.
- **Plumbing:** builds on the existing MenuOverlayView + the wired Menu Entry Swapper + ConfigScreen's setConfig path. A small additive **shared** helper maps a menu entry (target/option/identifier) to the Swapper config key and writes it; the entry data is already enumerated for the overlay. Native renders one extra synthetic row.
- **Effort:** M · **Risk:** med · **Scope:** shared/iOS-impacting · **Priority:** P2.

#### ENH-B12 — Hold-then-drag gate + pickup haptic for inventory/bank item rearrange
- **Anchor:** the existing engine touch-drag wiring for inventory/reorder (`onScroll` → `itemDragging` + MOUSE_PRESSED; FIX-4 wired the drag), re-presented with the OSRS-Mobile hold-then-drag pickup. Also anchors to Inventory Tags item-color rendering that composites under it.
- **Mobile rationale:** item-drag begins on the first slop-crossing scroll inside a draggable pane with no hold gate and no pickup haptic (UX-PLAYINPUT-05), so a glancing drag over the dense grid accidentally rearranges an item. OSRS-Mobile gates rearrange behind a deliberate hold-then-drag and buzzes on pickup. The drag is already plumbed — only the arming model is wrong.
- **Interaction sketch:** a tap or quick swipe does nothing destructive; a ~150–200 ms dwell on an item arms the drag (a short `vibrateTick` confirms "picked up"), then the finger drags; an early lift is treated as a normal tap. The always-fire RELEASE stays so a half-drag never leaves a stuck MOUSE_PRESSED.
- **Plumbing:** builds on the existing drag path + the existing `vibrateTick` — add a dwell timer before `itemDragging=true` and a `vibrateTick` on the synthesized MOUSE_PRESSED; keep the existing release. Shared (iOS mirrors the same no-hold model).
- **Effort:** S · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P2.
- **Closes:** **UX-PLAYINPUT-05** (no hold gate on item drag).

#### ENH-B14 — Larger bottom-anchored long-press menu: safe-area clamp + open-confirm haptic
- **Anchor:** the existing native press-and-peek menu (`MenuOverlayView`; `beginMenuPeek`), re-presented to OSRS-Mobile menu ergonomics: bigger reachable rows, safe-area clamp, and a confirming haptic the instant the menu opens.
- **Mobile rationale:** the menu works but three feel gaps remain — it clamps to the raw view edge (drawing under a landscape cutout, UX-XCUT-02), fires no haptic on open so a finger-occluded menu gives no signal it registered (UX-PLAYINPUT-01/04), and rows shrink to fit a tall menu rather than anchoring near the thumb. OSRS-Mobile menus are inset-safe, thumb-reachable, and buzz on open.
- **Interaction sketch:** on long-press the menu opens with one immediate `vibrateTick` (the press is always acknowledged, even over empty ground), clamps inside the `displayCutout|systemBars` safe rect (reusing `safeInsets()` that ChromeOverlayView already folds), and for long lists anchors toward the bottom/thumb rather than shrinking rows. Drag-select to commit is unchanged.
- **Plumbing:** cheap, android-only — add the `safeInsets()` clamp to `MenuOverlayView.show()`, add a `vibrateTick` at the top of `beginMenuPeek`/`beginChatFilterMenu`. No bridge change.
- **Effort:** S · **Risk:** low · **Scope:** android-only · **Priority:** P2.
- **Closes:** **UX-XCUT-02** (menu ignores insets) and **UX-PLAYINPUT-01/-04** (no open haptic).

#### ENH-B16 — Surface the RuneLite-account sync: onboarding card + "Synced as <user>" confirmation
- **Anchor:** the shipped **RuneLite-account** config/plugin sync via `RuneLiteAccountBridge` (started from the launcher row). The session JSON is only `{available,loggedIn,username}` — no "synced" confirmation, no onboarding.
- **Mobile rationale:** the single most valuable thing about this app for a multi-device player — "sign in once and your profiles, settings, and hub plugins follow you" — is buried as one unexplained row. On mobile, where typing config is painful, "it just syncs from my desktop" is a headline feature that needs a front door. Making the synced state visible ("Synced as Bob — 14 plugins, 3 profiles") is the recognition-over-recall pattern.
- **Interaction sketch:** first open of the plugins panel after a fresh install shows a dismissible card ("Sign in to your RuneLite account to bring your desktop setup here" + one button). After login the row flips to a green "Synced as <user>" chip with a one-line summary plus a sync-in-progress spinner while it pulls.
- **Plumbing:** builds on the existing `RuneLiteAccountBridge` + `RuneLiteAccountRow`. Cheap **shared** addition — extend `accountSessionJson` to include a `synced/syncing` flag + counts (profiles/plugins) the bridge already has access to. Android-only: the card + richer row state.
- **Effort:** S · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P2 *(anchor-check: graded P3 in the UX audit — see UX-ACCOUNT-01; CONFIRM, reconcile P2-vs-P3 with the human)*.
- **Closes:** **UX-ACCOUNT-01** / **UX-ACCOUNT-02**.

#### ENH-B03 — Keybind config rows: show the current binding + a Clear control
- **Anchor:** RuneLite **HotkeyButton** config control (clears to `NOT_SET`). The bridge correctly tags Keybind/ModifierlessKeybind items as type `keybind`, but `ConfigScreen`'s `when()` has **no keybind case**, so the row falls into `else→{}` and renders name+description with no control and not even the current binding string (worse than iOS, which at least labels it).
- **Mobile rationale:** a keybind-bearing config (KeyRemappingConfig alone has 39 keybind fields; ~10–13 plugins total) shows an inert blank row, so the user can neither see what a hotkey is bound to nor clear an accidentally-bound one that fires nothing useful on touch. Even read-only display + Clear restores legibility and lets a touch-only user un-bind a stray key.
- **Interaction sketch:** the row renders the current `Keybind.toString()` ("Ctrl + F1" or "Not set") as a read-only value plus a small "Clear" TextButton that writes the `NOT_SET` serialization ("0:0") via the existing setConfig round-trip. Drop the "edit on desktop" wording.
- **Plumbing:** native-only — add a keybind branch that reads the emitted string and writes NOT_SET via setConfig. A full HW-capture editor is out of scope (no HW keyboard); read-only + Clear is v1.
- **Effort:** S · **Risk:** low · **Scope:** android-only · **Priority:** P2.

#### ENH-B04 — FontType config rows: a REGULAR/BOLD/SMALL preset picker instead of an opaque blob
- **Anchor:** RuneLite **FontPanel** preset picker (`createFont` → REGULAR/BOLD/SMALL). FontType uses a custom serializer, so the bridge returns "unknown" and `ConfigScreen` renders a read-only field showing the raw serialized blob. **iOS already ports** the `typeName` FontType→"font" case + `choiceValues` + an `editFont` picker.
- **Mobile rationale:** plugins with a font-size/style setting show an unreadable, uneditable serialized value on a phone, while desktop offers a clean 3-way dropdown. Font size in particular matters more on a small screen, so making it pickable is a direct legibility win.
- **Interaction sketch:** the font row renders as a dropdown identical to the existing EnumDropdown — the three presets (Regular / Bold / Small) with the current one selected; picking writes through the existing round-trip.
- **Plumbing:** one small additive **shared** change — port the iOS FontType→"font" case + REGULAR/BOLD/SMALL `choiceValues` in `getPluginConfig` (grows the bridge output only; iOS benefits already done), then a "font" branch in ConfigScreen reusing EnumDropdown. No plugin fork.
- **Effort:** S · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P2.

#### ENH-B05 — Per-plugin Reset-to-defaults button (with confirm)
- **Anchor:** RuneLite **ConfigPanel** "Reset" button (`setDefaultConfiguration` + `plugin.resetConfiguration` after a confirm). Android's `ConfigScreen` has **no reset of any kind**; the bridge has only single-key `resetConfig`, while the whole-plugin `resetPluginConfig` the iOS branch added does not exist on Android.
- **Mobile rationale:** now that color/dimension/set fields are editable on Android, it's easy to mangle a config on a touch screen with no one-tap revert. A user fat-fingering a color picker or slider has to manually fix every field, and some (keybind/notification) can't even be reverted field-by-field. A single Reset-to-defaults is the standard escape hatch.
- **Interaction sketch:** a "Reset to defaults" button pinned at the bottom of `ConfigScreen`; tapping opens a confirm dialog (reusing the existing sibling confirm pattern) before firing the reset, then the screen reloads its values.
- **Plumbing:** one additive **shared** method — port the iOS `resetPluginConfig(group)` (wraps `setDefaultConfiguration` on the client thread; iOS already has it) + the native confirm-dialog button. No plugin fork.
- **Effort:** M · **Risk:** low · **Scope:** shared/iOS-impacting · **Priority:** P2.

#### ENH-B10 — Overlay quick-toggle sheet: list active overlays/infoboxes with one-tap show/hide
- **Anchor:** RuneLite **overlay management** (`OverlayBridge` already enumerates live overlays with names/bounds/resizable for the editor; `OverlayEditView` drags them) + the `InfoBoxScreen` list. OSRS-Mobile pattern: a clean toggle list for on-screen HUD elements rather than hunting each plugin's config.
- **Mobile rationale:** the overlay editor is a separate full-screen drag list, and overlay visibility today means opening each owning plugin's flat config and finding the right boolean. On a small screen where overlays compete with the scene, the mobile-natural action is "see what's on my HUD and toggle one off" from one place — the enumerate-overlays capability already exists for the editor; this re-presents it as a visibility sheet.
- **Interaction sketch:** a "HUD overlays" sub-screen lists each active overlay/infobox (names already available) with a Switch per row; toggling hides/shows it immediately. Reuses the OSRS-styled rows, and a long-press jumps to that overlay's owning plugin config.
- **Plumbing:** builds on the existing OverlayBridge enumeration + the per-plugin setConfig/setPluginEnabled path. Show/hide maps to the overlay's owning config toggle (additive **shared** helper to resolve overlay→owning plugin + its enabled key); native is a Compose list mirroring InfoBoxScreen.
- **Effort:** M · **Risk:** med · **Scope:** shared/iOS-impacting · **Priority:** P3.

#### ENH-B18 — Chat scrollback drawer: swipe-up the relocated chat to a tall scrollable history
- **Anchor:** RuneLite chat filters + the existing native chat veneer (`ChatPillView` per-channel tabs + long-press filter menu; `scrollChat` already wired). OSRS-Mobile pattern: swipe the chat up into a tall readable history, swipe down to collapse.
- **Mobile rationale:** chat is relocated to a thin top strip and the message area collapses on an active-tab tap, but there's no way to pull up a tall, comfortably-scrollable backlog — the drag-to-scroll exists but only within the cramped strip. On a phone the natural gesture for "read what just happened" is a swipe-up drawer; the channel tabs and the scroll primitive both already exist.
- **Interaction sketch:** a swipe-up starting on the chat strip expands the relocated chat into a tall semi-opaque drawer (the existing ChatPillView channel tabs pinned to the top), driving the existing `scrollChat` so the full backlog scrolls with momentum. Swipe-down or tap-outside collapses. Per-channel filtering stays the existing long-press-tab menu.
- **Plumbing:** builds on the existing ChatPillView + scrollChat + the chat-area relocate (`relocateChat`/`setChatAreaWidth`). The chat raster is engine-rendered, so the drawer is a taller relocate + the existing scroll feed; native gesture wiring + a taller chat region. No new content bridge.
- **Effort:** M · **Risk:** med · **Scope:** android-only · **Priority:** P3.

---

## C. Excluded (un-anchored or already present)

Listed to prove the no-far-out discipline. **0 rejected as un-anchored** (the candidate set was pre-filtered for anchors); **4 dropped as already-present** in current code.

| candidate | title | reason dropped |
|---|---|---|
| Chat "Report" 8th tab | Add the REPORTABUSE tab to the channel pill | **Intentional, not a defect.** The shared snapshot already publishes index-7 (REPORTABUSE) and `switchChatTabNow` handles it; the pill hard-caps at 7 *deliberately* to mirror OSRS-Mobile, which hides Report. A parity-completeness toggle at best, not a gap. |
| Orb touch veneer (enlarge) | Enlarged native hit-targets over the minimap orbs | **Already fully shipped.** `HudVeneerBridge` publishes `orbSnapshotJson`; `MainActivity` has `ORB_KEYS`/`ORB_TAP_PAD`/`nearestOrb()` inflating bounds and proxy-clicking the live orb centre — exactly the proposed veneer. (The *visual active-glow* + XP-orb gap is real and lives in **ENH-B08**.) |
| In-app Custom Tab for links | Open outbound links in a Custom Tab, not the external browser | **Already implemented.** `openLink()` already does `CustomTabsIntent…launchUrl` as the primary path with `ACTION_VIEW` only as the no-Custom-Tabs fallback; `nextLink` drains into it. The cited "raw ACTION_VIEW" line is stale (it's the restart-intent flags). |
| Restore "dropped" @Notification config type | Add a `notification` case + channel deep-link to ConfigScreen | **Premise false.** `ConfigScreen` already has a full `notification` case (inline Switch + `NotificationCustomizeControl` + a per-notification sub-editor dialog, "FIX-7", parity with iOS) — the cited "no notification case" audit line is out of date. The only genuinely-new nuance (deep-link to `ACTION_CHANNEL_NOTIFICATION_SETTINGS`) hangs entirely on **ENH-A08**'s per-event channels and is folded there. |

> The "already present" set is itself evidence the prior work landed: typed-channel deep-linking, link Custom Tabs, the orb touch veneer, and the per-notification editor are all done — Phase 4 builds *on* them (ENH-A08, ENH-B08), not over them.
