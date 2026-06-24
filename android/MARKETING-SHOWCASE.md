# OSRS Mobile — Marketing Feature Showcase (Android)

> Real RuneLite, running natively on your Android phone. The actual RuneLite client and its plugins — on an on-device JVM, rendering **live Old School RuneScape** — wrapped in a touch-first shell styled like the official Jagex OSRS Mobile app. No streaming, no companion PC. Your phone *is* the client.

This is not a re-implementation or a skin. It is the genuine RuneLite engine and plugin ecosystem, with a native mobile chrome layered on top: edge-to-edge 3D scene, finger-friendly orbs and tabs, a real soft-keyboard chat, a pannable world map, and the marquee 117HD GPU renderer. Everything below is verified against the shipping code.

---

## What OSRS mobile players want

Merged and ranked from three player personas (PvM main, ironman/skiller, returning/casual). Higher = more demanded.

| Rank | What they want | Why it matters on mobile |
|---|---|---|
| 1 | **Precise touch targeting** — tap the *exact* thing, long-press for the real menu | The #1 pain on official mobile is fat-fingering: tapping a tree and walking instead of chopping, or grabbing the wrong NPC in a stacked pile. Engine-resolved taps land where your finger lands; a clean kill vs. a misclick death. |
| 2 | **The full RuneLite plugin ecosystem + a touch-friendly config panel** | The entire reason to run RuneLite over official mobile. Boss timers, NPC/tile indicators, prayer helpers, ground markers — the unmodified plugin set, tunable on the phone instead of a cramped desktop sidebar. |
| 3 | **117HD / GPU renderer** with an auto-GPU caps probe | The single biggest visual flex: real lighting, shadows, water, draw distance. "Wow, this is the same game?" — and it auto-turns-on for capable phones. |
| 4 | **Native info overlays** — XP tracker, infoboxes, GE, loot tracker, time tracking | Efficiency mains obsess over XP/hr, GP/hr, drop logs and farm-run timers. Surfaced as legible native screens reading the *real* plugin models, not tiny desktop overlays. |
| 5 | **World map** — finger-pan, pinch-zoom, precise tap-to-walk | RuneLite's map (overlays, labels) beats the in-game map and mains live on it for routing. Fling-pan + pinch-zoom makes it actually usable on a phone. |
| 6 | **Native HUD veneers** — minimap, HP/prayer/run/spec orbs, chat channel-pills | Orbs as real, big touch targets for one-tap prayer flicks and glanceable HP/energy. Channel pills make friends/clan/trade chat readable. |
| 7 | **PiP + background keepalive** | Keep an AFK fishing/agility session alive while you check the wiki or reply to a message. *(See honesty note below — Android ships touch-idle keepalive only.)* |
| 8 | **Account/profile + hub-plugin sync** via RuneLite-account sign-in | Sign into runelite.net and your years-tuned desktop profile, settings and hub plugins just appear. Zero re-config. |
| 9 | **Jagex login that works** (Cloudflare-wall workaround) | Invisible plumbing, but a hard gate: a Jagex-migrated account simply can't log in without it. |
| 10 | **Quest Helper (native screens)** | Step-by-step guidance with the right item/route inline — otherwise miserable to follow on a small client. |
| 11 | **World hopping** (fast relog-based hop + overlay) | Dodge PKers, find empty resource spots, escape a crashed boss. Used dozens of times a session. |
| 12 | **Native tab grid** — open any interface tab via the real menu action | Big, reliable tab targets for Inventory/Prayer/Combat/Spellbook swaps mid-fight. |

---

## What we shipped

The shortlist: features that are **both** high-demand **and** verified shipped (or partial) in the code audit. Each line is a marketing blurb plus the demo state and the evidence that proves it. Partials are marked.

### 1. Precise engine-resolved touch targeting
**Tap exactly what you mean — the engine decides the hit, not a guess.** A literal-point tap routes to the exact pixel you touched; the engine's own model clickbox resolves it, a settle gate confirms the real outcome, and you get a red ring (interact) or yellow ring (walk) so you *know* what happened.
- *Demo state:* Tap an NPC → red ring + model silhouette outline (SCENE). Tap bare ground → yellow ring (WALK). Tap an inventory item → cyan item border, no ring (WIDGET). Long-press opens the real left-click menu.
- *Evidence:* `runelite-client/.../headless/JavaBridge.java:686-711` (literal-point tap), `:794-836` (settle gate), `:888-941` (MenuOptionClicked truth + classification), `:949-968` (tap-kind publish); `android/.../MainActivity.kt:1335-1384` (tap routing), `:1789-1821` (ring feedback).

### 2. Full RuneLite plugin ecosystem + native mobile config panel
**Every plugin you love, tunable with your thumbs.** A native bottom-sheet panel: searchable plugin list with per-plugin toggles, pins and config screens, plus a Plugin Hub browser to install hub plugins right on the phone — driving the unmodified RuneLite plugin set.
- *Demo state:* Tap the ≡ Plugins handle → landing grid (Renderers / Pinned / Panels / More). "All plugins" → searchable list with toggles. Tap a ⚙ → that plugin's config screen. "Plugin Hub" → installable catalog with Install buttons.
- *Evidence:* `android/.../PluginPanel.kt:78-777` (full panel UI), `android/.../PluginHubScreen.kt:47-240` (hub browse/install); `runelite-client/.../headless/MobileUiBridge.java:1492-1566` (list/toggle/pin), `:1283-1399` (hub manifest/install/uninstall/state).

### 3. 117HD / GPU renderer — selectable, with an auto-GPU caps probe
**Desktop-grade HD lighting, shadows and draw distance — on your phone, automatically.** Pick CPU / GPU / 117HD from the launcher (applies next launch), or let the first-boot caps probe auto-promote capable devices straight to 117HD. Live, user-tunable MSAA / scene-scale / anisotropic-filtering quality levers.
- *Demo state:* Plugins panel → Renderers row with CPU (▣) / GPU (▤) / 117HD (✦) tiles; selected tile teal-bordered. In 117HD, the scene renders with shadows, dappled foliage and surface-normal lighting.
- *Evidence:* `android/.../PluginPanel.kt:543-545` (tile selector); `runelite-client/.../headless/RendererPanelStarter.java:74-76`, `RLHDStarter.java:49-51,136-138,243-245`, `RouteCGpuStarter.java:54,73-166`; auto-probe `android/.../MainActivity.kt:1049-1107,954-960`.

### 4. World map — finger-pan, pinch-zoom, precise taps
**The full RuneLite world map, built for your thumbs.** Fling-pan the map, pinch to zoom, tap precisely to set a walk destination; native chrome hides so the map goes edge-to-edge, and BACK or the close-X dismisses it.
- *Demo state:* Tap the world-map orb → fullscreen map; one-finger drag pans (2.5× speed boost), two-finger pinch zooms; close-X / BACK restores the HUD.
- *Evidence:* `android/.../MainActivity.kt:1468-1484` (pan), `:1512-1543` (pinch), `:1344-1351` (precise taps), `:785-796` (BACK close), `:2334-2348` (chrome-hide); `runelite-client/.../headless/HudVeneerBridge.java:902-906,1068-1135`.

### 5. Native HUD veneers — minimap, orbs, chat channel-pills
**A crisp, full-bleed HUD with one-tap orbs and readable chat.** HP/prayer/run/spec orbs render as real, enlarged touch targets next to an edge-aligned minimap; chat relocates to the top with 7 channel pills (All/Game/Public/Private/Channel/Clan/Trade) and a real soft keyboard.
- *Demo state:* Logged-in scene with chat pills top-left, minimap + orbs top-right (safe-area aware, no notch clipping). Tapping an orb fires its action with no spurious walk/interact ring.
- *Evidence (orbs/minimap):* `runelite-client/.../headless/HudVeneerBridge.java:284-291,1386-1410,1910-1931`; `android/.../MainActivity.kt:2446-2467,1306-1330,1352-1362`. *(chat):* `android/.../ChatPillView.kt:17-35`, `MainActivity.kt:2254-2264` (top-render), `:1550-1595` (soft-keyboard input); `HudVeneerBridge.java:268-273`.

### 6. Native tab grid — open any of 14 tabs via the real menu action
**Big, reliable tab targets for prayer swaps and gear/spec management.** A 2-column OSRS-Mobile tab grid (14 tiles) opens the real interface tab through the genuine CC_OP menu action — Inventory, Prayer, Combat, Spellbook, Clan and the rest all open, active tab highlighted.
- *Demo state:* In-game, the bottom-right grid shows 14 stone tiles; tapping one opens its panel and highlights it red.
- *Evidence:* `android/.../ChromeOverlayView.kt:1-207`, `MainActivity.kt:442-446,2110-2118`; `runelite-client/.../headless/HudVeneerBridge.java:317-320,1213-1248`.

### 7. RuneLite-account sign-in — profile + settings + hub-plugin sync
**Sign in and your exact desktop setup is here.** runelite.net OAuth (via a Chrome Custom Tab loopback) pulls your synced profiles, settings and installed hub plugins down to the phone.
- *Demo state:* Plugins launcher grid shows a "RuneLite account" row — "Log in" when signed out, username + "Log out" when signed in.
- *Evidence:* `android/.../PluginPanel.kt:262-263,338-376`, `MainActivity.kt:1936-1956`; `runelite-client/.../account/SessionManager.java:182-211,219-262`, `config/ConfigManager.java:250-275`.

### 8. World hopping via relog (+ native hopping overlay)
**Quick-hop to a fresh world, with a clean status overlay.** A logout → changeWorld → relogin hop from the World Switcher (or Prev/Next quick-hop), dimmed behind a native spinner card so you never see raw login chrome.
- *Demo state:* World Switcher list with the current world green; tap any world → a "Hopping to world N…" scrim with a spinner for ~8s → back in-game on the chosen world.
- *Evidence:* `runelite-client/.../headless/WorldSwitcherBridge.java:83-94,195-241,329-428`; `android/.../HoppingOverlayView.kt:10-111`, `MainActivity.kt:1640-1656,1972-1983`.

### 9. Jagex login via Chrome Custom Tab (Cloudflare-wall workaround)
**Log in with your real Jagex account — past the wall that blocks embedded webviews.** OAuth runs in a Chrome Custom Tab, the `http://localhost` redirect comes back, a character picker appears, and `tokens.json` is written for the live session.
- *Demo state:* LoginActivity with "Open Jagex login", a redirect-link paste field, and a character picker on import.
- *Evidence:* `android/.../LoginActivity.kt:119-135`, `JagexLogin.kt:28-176`, `AndroidManifest.xml:48-56`.

### 10. Touch-driven idle-logout keepalive — *partial vs. desktop-grade background*
**Active native-UI use keeps your session awake.** Every native-UI touch (chat pills, orbs, tabs, panel) resets the engine's idle-logout timer.
- *Honesty note:* Android does **not** ship Picture-in-Picture or silent-audio background keepalive (those are iOS-only by design); a long background can still drop the session. The touch-idle reset is the shipped piece.
- *Evidence (shipped):* `android/.../JvmLauncher.kt:74-75,364-370`, `MainActivity.kt:444-499`. *(Not present:* PiP / silent-audio — `AndroidManifest.xml` has no `supportsPictureInPicture`; `MainActivity.kt:2698-2706` `onStop` only flushes config.)*

### Also shipped (backend complete, front-end partial)
- **Inventory item-drag to rearrange — partial.** The engine publishes inventory bounds and the WidgetDrag routing exists, but the Kotlin gesture handler doesn't yet invoke it (a drag currently rotates the camera). *Evidence:* `runelite-client/.../headless/JavaBridge.java:1905-1945`; `android/.../MainActivity.kt:2126-2130` (bounds shipped); gesture wiring unwired per `android/ANDROID-QA-REPORT.md:53-59`.

---

## Screenshots

Files under `android/marketing/`. **Phase 3** = capturable now from the running, logged-in app by tapping known UI. **Phase 4** = needs device/manual setup by the user (live login, OAuth, mid-session hop, a scenic GPU vista).

| # | Feature | Image | Capture phase | Status |
|---|---|---|---|---|
| 01 | 117HD HD vista (hero shot) | `android/marketing/01-gpu-117hd-vista.png` | Phase 4 — scenic location + 117HD boot | Pending (Phase 4) |
| 02 | Renderer selector (CPU/GPU/117HD) | `android/marketing/02-renderer-selector.png` | Phase 3 | ✅ Captured |
| 03 | Plugin config panel + hub | `android/marketing/03-plugin-panel.png` | Phase 3 | ⏳ Pending re-connect |
| 04 | Native HUD veneers (orbs + minimap + chat pills) | `android/marketing/04-native-hud.png` | Phase 3 | ✅ Captured |
| 05 | Precise touch targeting (rings + outline) | `android/marketing/05-touch-targeting.png` | Phase 3 | ⏳ Pending re-connect |
| 06 | Native tab grid (14 tiles) | `android/marketing/06-tab-grid.png` | Phase 3 | ⏳ Pending re-connect |
| 07 | World map (pan/zoom, fullscreen) | `android/marketing/07-world-map.png` | Phase 3 | ⏳ Pending re-connect |
| 08 | Native chat + soft keyboard | `android/marketing/08-native-chat.png` | Phase 3 | ⏳ Pending re-connect |
| 09 | World hopping overlay | `android/marketing/09-world-hop-overlay.png` | Phase 4 — initiate a live hop | Pending (Phase 4) |
| 10 | RuneLite-account sync row | `android/marketing/10-account-sync.png` | Phase 3 (logged-out row) / Phase 4 (signed-in state) | ✅ Captured (logged-out) |
| 11 | Jagex login screen | `android/marketing/11-jagex-login.png` | Phase 4 — live login flow | Pending (Phase 4) |

> Background keepalive (item 10 in the shortlist) and inventory item-drag (partial) are intentionally omitted from the screenshot set — keepalive is "nothing happens" by nature, and the drag front-end is unwired.

### Phase 3 capture log
Captured live from the connected Pixel 7a before the wireless-debugging link dropped:
- **02-renderer-selector.png** — native Plugins panel showing the Renderers row (CPU / GPU / **117HD selected**, teal border) plus the RuneLite-account row.
- **04-native-hud.png** — clean in-game scene: chat channel-pills (top-left), minimap + HP/prayer/run/spec orbs (top-right), native tab grid (bottom-right).
- **10-account-sync.png** — the RuneLite-account row in its logged-out "Sync your profiles + installed plugins / Log in" state.

Remaining Phase-3 shots (03, 05, 06, 07, 08) are reachable from the running app but were not captured: the on-device JVM was crash-restarting on a ~2-minute cycle, and the Tailscale `adb` link (port `37293`) then went offline. **To finish them, re-enable Wireless debugging on the phone and reconnect `adb`, then re-run the remaining taps** (recipes in this doc). They do not need manual game setup — only a stable connection.

---

## Phase 4 — device/manual captures (pending user)

These need the user to set up live state on the device; the model will pull them from the connected phone once staged:

- **01-gpu-117hd-vista.png** — set renderer to 117HD, restart, walk to a scenic spot (coastline / Lumbridge at dusk / forest) for shadows + water + foliage, then capture.
- **09-world-hop-overlay.png** — Plugins → World Switcher → tap a non-current world (or Prev/Next quick-hop); capture the "Hopping to world N…" scrim during the ~8s relog.
- **11-jagex-login.png** — from logged-out, reach LoginActivity; capture the "Open Jagex login" screen + redirect paste field (and optionally the character picker after import).
- **10-account-sync.png (signed-in variant)** — sign into the RuneLite account; capture the row showing username + "Log out".
