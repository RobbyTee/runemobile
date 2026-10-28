# A3 — on-device Jagex login (Chrome Custom Tab + clipboard)

Log in on the phone with no desktop — the Android twin of the iOS `JagexLoginViewController`. The app opens the
**Stage-A OAuth consent flow** in a Chrome Custom Tab, the user brings the `http://localhost/#id_token=…` redirect
link back by copying it, and `id_token` → game session → character list → `tokens.json`. Then `JagexTokens` (A0)
maps `tokens.json` → the `JX_*` env the gamepack reads, and autologin reaches LOGGED_IN.

**History.** The first A3 build ran the flow in an embedded `WebView` and intercepted the loopback redirect.
Cloudflare's human check at the Jagex auth step blocks Android's WebView (real Chrome passes it), and the consent
client accepts only `redirect_uri=http://localhost`, so the redirect cannot be routed back into the app. The
login therefore moved to a Custom Tab plus the clipboard, as described below.

## Files

- **`JagexLogin.kt`** — the OAuth logic (ported verbatim from `ios/dev-tools/fetch-shield-tokens.sh` +
  `JagexLoginViewController.m`): consent URL (with `prompt=login`, a fresh `nonce` and `state`), `id_token`
  extraction, the `nonce` check, `mintSession` (POST `auth.runescape.com/game-session/v1/sessions`),
  `fetchAccounts` (GET `.../v2/accounts`), `writeTokens` (filesDir `tokens.json`). All HTTP carries the
  **launcher User-Agent** (Cloudflare is UA-sensitive).
- **`LoginActivity.kt`** — opens the consent URL in a Chrome Custom Tab (any browser if no Custom Tabs provider).
  The user signs in and lands on a "localhost refused to connect" page, copies its link (Share, then Copy) and
  comes back; the activity reads the link from the clipboard when it regains focus, or from a paste field
  (**Import login link**). It rejects a link whose `state` or `id_token` `nonce` isn't this attempt's, clears the
  clipboard, then shows the character picker (skipped for a single character). A Custom Tab shares Chrome's
  cookies, so `prompt=login` is what keeps account switching working. On a chosen character it writes
  `tokens.json` and **restarts the process** so the next boot snapshots `JX_*` at `JNI_CreateJavaVM` (the
  Review-2 login-first-boot rule).
- **`MainActivity`** — the **login-first boot gate** (no `tokens.json` → launch login before booting); the
  **Account** (`⎆`) tile in the plugin launcher and the session-expired card both reopen the login.

## OAuth constants (Stage A, verbatim from the proven iOS flow)

```
auth      https://account.jagex.com/oauth2/auth   response_type=id_token+code  scope=openid offline
client_id 1fddee4e-b100-4f4e-b2b0-097f9088f9d2    redirect_uri=http://localhost (loopback; link copied back)
sessions  POST https://auth.runescape.com/game-session/v1/sessions   {idToken} -> {sessionId}
accounts  GET  https://auth.runescape.com/game-session/v2/accounts?fetchMembership=true   Bearer sessionId
```

## Verification

The end-to-end login is **interactive / user-only** (real Jagex creds, throwaway account) per the project auth
rule — there is no automated md5 gate for it. What is verified emulator-side (no creds):

- **Build** — `:app:assembleDebug` clean.
- **Login-first gate** — with `tokens.json` present the gate skips and the game boots (no `a3: no tokens.json`,
  `startJvm rc=0`); with none it launches `LoginActivity`.
- **(WebView build, historical)** — the WebView reached `account.jagex.com/oauth2/auth` and rendered the real
  consent flow, starting with the Cloudflare "Are you a robot?" human check. That check is where the WebView is
  blocked, which is why the flow now runs in Chrome.

### User-driven end-to-end (the remaining leg)

1. Launch the app with **no** `tokens.json` (fresh install) → the login appears automatically; or tap the
   **Account** (`⎆`) tile.
2. Tap **Open Jagex login**, log in with the **throwaway** account in Chrome, copy the `localhost` link, go back,
   pick a character.
3. The app writes `tokens.json` + restarts → autologin should reach LOGGED_IN (watch `gameState … LOGGED_IN`,
   `ANDROID_A6_UI_GREEN`). GREEN = a fresh on-device login (no pushed tokens) renders in-world.
