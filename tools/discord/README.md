# Discord — bug-tracking server provisioning

One idempotent script builds out the whole RuneMobile bug-tracking server —
categories, channels, roles, read-only locks, the report template, and the
incoming webhooks. Re-running it only fills in what's missing, so it's safe to
run again after you tweak the blueprint in `provision-server.py`.

Secrets live in `~/.runemobile-discord/` (outside the repo), the same way CI
keeps its keys in `~/.runemobile-ci/`. Nothing here commits a token.

## What you do first (needs your Discord login — ~3 min)

1. **Create the server.** In the Discord app: the **+** on the left → *Create My
   Own* → *For me and my friends*. Name it (e.g. "RuneMobile Dev"). It can be empty.

2. **Create the bot.** Go to <https://discord.com/developers/applications> →
   **New Application** → name it → **Bot** tab → **Reset Token** → **Copy**. That
   string is your `DISCORD_BOT_TOKEN` (treat it like a password).
   - Under the Bot tab you do **not** need any Privileged Gateway Intents for this.

3. **Invite the bot with Administrator.** Still in the developer portal:
   **OAuth2** → **URL Generator** → tick **`bot`** scope, then tick
   **Administrator** under Bot Permissions. Copy the generated URL, open it, and
   add the bot to your server. (You can lower its permissions after setup.)
   - Shortcut URL — replace `APP_ID` with your application's ID (General
     Information tab): `https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot&permissions=8`

4. **Get the server ID.** Discord → User Settings → *Advanced* → enable
   **Developer Mode**. Then right-click the server icon → **Copy Server ID**.
   That's your `DISCORD_GUILD_ID`.

## Configure + run

Put both secrets in `~/.runemobile-discord/config.env`:

```bash
mkdir -p ~/.runemobile-discord && chmod 700 ~/.runemobile-discord
cat > ~/.runemobile-discord/config.env <<'EOF'
DISCORD_BOT_TOKEN=your-bot-token-here
DISCORD_GUILD_ID=your-server-id-here
EOF
chmod 600 ~/.runemobile-discord/config.env
```

Preview, then run for real:

```bash
tools/discord/provision-server.py --dry-run          # see what it will create
tools/discord/provision-server.py --save-webhooks    # create it all + save webhook URLs
```

`--save-webhooks` writes the three webhook URLs to
`~/.runemobile-discord/webhooks.env` (chmod 600) for the automations below.
Without it, the URLs just print once — copy them somewhere safe.

## What gets created

| Category | Channels |
|----------|----------|
| **INFO** | `welcome`, `announcements`, `how-to-report` — all read-only; the report template is auto-posted in `how-to-report` |
| **DEV** | `bug-reports` (GitHub webhook), `ios-bugs`, `android-bugs`, `crashes-and-logs` (in-app webhook), `triage`, `feature-requests` |
| **RELEASES** | `ci-builds` (CI webhook, read-only), `changelog` (read-only) |
| **GENERAL** | `general`, `off-topic` |

Roles: **Maintainer** (admin), **Contributor**, **Tester**.

Edit the `ROLES` / `BLUEPRINT` lists at the top of `provision-server.py` and
re-run to add or rename anything — existing items are left untouched.

## Wiring the automations (after the webhooks exist)

**GitHub issues → `#bug-reports`.** In the public repo on GitHub:
Settings → Webhooks → Add webhook. Paste the *GitHub Issues* URL **with
`/github` appended** (GitHub has a native Discord formatter):

```
<GITHUB_WEBHOOK-url>/github
```

Content type `application/json`, and under "Let me select individual events"
tick **Issues** (and **Issue comments** if you want those too).

**CI builds → `#ci-builds`.** `post-build.sh` in this folder posts a build
notice. Call it from the release workflow after a successful publish; it reads
`CI_WEBHOOK` from `~/.runemobile-discord/webhooks.env`.

**In-app "Report bug" → `#crashes-and-logs`.** The `In-App Reports` webhook is
already created and ready. Wiring the iOS/Android app to POST to it (with a
screenshot + log) is a separate task — the endpoint exists whenever we build it.

## Safety / conventions

- Pure stdlib (`urllib`) — no `pip install`. Runs on the system Python.
- Idempotent — safe to re-run; matches by name, never duplicates.
- All secrets in `~/.runemobile-discord/`, never in the repo. `.gitignore`
  also blocks any stray `tools/discord/*.env`.
