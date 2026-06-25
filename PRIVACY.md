# Privacy & data flow

> Unofficial fan project. Not affiliated with, endorsed by, or sponsored by Jagex or the
> RuneLite project. This document describes exactly what data the app handles, where it is
> stored, and what leaves the device. It applies to both ports (Android and iOS); the data
> model is shared, only the on-device storage paths differ by platform.

## Summary

- **There is no first-party server and no analytics.** The maintainer operates no backend and
  receives **no** data about you or your usage. The app talks only to **Jagex** (to play the
  game) and, **only if you choose to sign in**, to the **RuneLite project** (to sync your config
  and hub plugins).
- **No telemetry or crash reports are sent.** This build boots the embedded client with
  `--disable-telemetry`, so RuneLite's usual boot telemetry and JVM-crash reporting to
  `api.runelite.net` are **off**.
- **Your credentials stay on the device.** OAuth tokens live in app-private storage and are sent
  only to the service that issued them (Jagex). No release build ships a login token.
- **Everything the app stores locally** (game cache, settings, renderer choice, diagnostics) stays
  on the device unless a row below says otherwise.

## What leaves the device

| Data | What it is | Sent to | When | Your control |
|---|---|---|---|---|
| Jagex OAuth (`id_token` → `session_id`) | The login handshake that proves you own the account | `account.jagex.com`, `auth.runescape.com` | When you log in | Log in / log out |
| Game traffic | The live OSRS protocol (movement, actions, world state) | Jagex game servers (`oldschool*.runescape.com`) | While in-world | Log out / close app |
| Live game cache fetch | OSRS art/data the engine streams to render the world | Jagex (JS5 cache) | First login / on update | Required to play |
| RuneLite account sync | Your RuneLite profiles, plugin settings, and installed hub plugins | `api.runelite.net` | **Only if** you sign into a RuneLite account in the plugins panel | Opt-in; sign out to stop |
| Hub plugin downloads | Third-party plugin code you choose to install | RuneLite plugin repo (`repo.runelite.net`) | When you install a hub plugin | You choose what to install |

That is the complete list of outbound data. **Nothing else is transmitted.**

## What is **not** collected or sent

- **No telemetry** — Java/OS version, device memory, "logged-in" flags: none of it is sent
  (`--disable-telemetry` is passed at boot).
- **No crash reporting** — JVM crash logs are **not** uploaded anywhere; they remain on-device.
- **No analytics, ads, tracking, or fingerprinting.** No third-party SDKs of that kind are present.
- **No gameplay data to anyone but Jagex** — chat, inventory, location, XP, menu contents are part
  of the normal game connection to Jagex and are sent **nowhere else**.
- **No account email or password to the maintainer** — there is no maintainer backend to send them to.

## What is stored on the device

All paths are in the app's **private** storage unless marked *external* (adb-reachable) or
*debug-only* (never present in a release build).

| Data | Where (Android: app `filesDir`; iOS: app sandbox) | Notes |
|---|---|---|
| Jagex session token | `tokens.json` (app-private) | Written on login, deleted on logout. **No release build bundles one.** |
| RuneLite account session | `.runelite/` config dir | Only after you opt into account sync |
| OSRS live cache (~200 MB+) | `.runelite/jagexcache/oldschool/LIVE/` | Streamed from Jagex; persists between sessions |
| Client + plugin settings | `.runelite/settings.properties`, profile files | Synced to RuneLite **only** if you sign in |
| Renderer choice, hotkeys | app preferences (`renderer`, `hotkeys`) | On-device only |
| Diagnostics (GPU-fallback cookie, thermal marker) | `gpu-fail.txt`, `thermal-throttle.marker` (app-private) | **On-device only — never transmitted** |
| Embedded JVM + client jars (~500 MB) | `jvm/` | Unpacked from the app on first run |

### Debug-only (stripped from release builds)

These exist solely to make development convenient and are **removed from any release artifact**:

- **Legacy username/password** (`legacy_login.json`) — plaintext dev autologin; dropped entirely in
  release (`LegacyCreds` returns nothing in release).
- **Bundled / external token copies** — a debug build may bundle a throwaway `tokens.json` and mirror
  it to *external* storage for `adb push`; a **release build ships none and never writes to external
  storage**.
- **Frame captures / diagnostic markers** (`rl-capture/`, `netprobe.mode`, etc.) — debug-gated; inert
  in release.

## Third parties

- **Jagex Ltd.** — receives your login handshake and game traffic. Required to play. Governed by
  Jagex's own terms and privacy policy.
- **The RuneLite project** — receives your config/plugin data **only if** you opt into RuneLite-account
  sync, and serves hub-plugin downloads you choose to install. Governed by RuneLite's terms.
- **No one else.**

## Notes & risk

- This client is **not on Jagex's approved-client list**; using it can get an account **banned**.
  Use a **throwaway** account. (See `SECURITY.md` and `README.md`.)
- Hub plugins run **inside the embedded JVM with full privileges**, by design — auditing third-party
  plugins you install is your responsibility.
- This is a single-maintainer, best-effort personal project. If a future build changes any data flow,
  this document will be updated alongside it.
