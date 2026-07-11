#!/usr/bin/env python3
"""
Provision the RuneMobile bug-tracking Discord server.

Idempotent: creates any missing roles, categories, channels, and webhooks in an
existing guild, matching by name. Re-running it is safe — it never duplicates
what already exists and only fills in gaps.

What only YOU can do first (needs your Discord login — see README.md):
  1. Create an empty server in the Discord app.
  2. Create a bot application at https://discord.com/developers/applications,
     copy its Bot token, and invite it to the server with Administrator.
  3. Enable Developer Mode, right-click the server -> Copy Server ID.

Then run this. It reads its two secrets from the environment, or from
~/.runemobile-discord/config.env (KEY=VALUE lines) so nothing lands in the repo:

    DISCORD_BOT_TOKEN   the bot token
    DISCORD_GUILD_ID    the server (guild) ID

Usage:
    tools/discord/provision-server.py            # create everything
    tools/discord/provision-server.py --dry-run  # show what WOULD be created
    tools/discord/provision-server.py --save-webhooks  # also write webhook URLs
                                                        # to ~/.runemobile-discord/webhooks.env

Pure stdlib (urllib) — no third-party packages. Discord REST API v10.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

API = "https://discord.com/api/v10"
CONFIG_DIR = Path.home() / ".runemobile-discord"
CONFIG_FILE = CONFIG_DIR / "config.env"
WEBHOOKS_FILE = CONFIG_DIR / "webhooks.env"

# ---- Discord permission bits (only the ones we use) ------------------------
ADMINISTRATOR = 1 << 3
VIEW_CHANNEL = 1 << 10
SEND_MESSAGES = 1 << 11
EMBED_LINKS = 1 << 14
ATTACH_FILES = 1 << 15
READ_MESSAGE_HISTORY = 1 << 16
ADD_REACTIONS = 1 << 6

CHANNEL_CATEGORY = 4
CHANNEL_TEXT = 0

# ---- Server blueprint ------------------------------------------------------
# Roles are created top-to-bottom. Maintainer is full admin so you can manage
# the server; the rest are cosmetic/hoisted groups with default permissions.
ROLES = [
    {"name": "Maintainer", "color": 0xE67E22, "hoist": True, "mentionable": True,
     "permissions": str(ADMINISTRATOR)},
    {"name": "Contributor", "color": 0x3498DB, "hoist": True, "mentionable": True,
     "permissions": "0"},
    {"name": "Tester", "color": 0x2ECC71, "hoist": True, "mentionable": True,
     "permissions": "0"},
]

# Each channel may set:
#   topic       channel description
#   readonly    True -> @everyone can view + read history but NOT send
#   webhook     name of an incoming webhook to create in this channel (printed at end)
#   template    a message the bot posts once (only if the channel has no messages yet)
BLUEPRINT = [
    ("INFO", [
        {"name": "welcome", "readonly": True,
         "topic": "Start here. What this server is for and how to help."},
        {"name": "announcements", "readonly": True,
         "topic": "Releases, breaking changes, and status updates."},
        {"name": "how-to-report", "readonly": True,
         "topic": "The bug-report template. Please follow it.",
         "template": (
             "**How to file a bug**\n\n"
             "Post bugs in the matching channel:\n"
             "• iOS-only  -> **#ios-bugs**\n"
             "• Android-only -> **#android-bugs**\n"
             "• A crash or a log dump -> **#crashes-and-logs**\n"
             "• Anything else / not sure -> **#bug-reports**\n\n"
             "Copy this template into your report:\n"
             "```\n"
             "Platform:      iOS 26.x  /  Android 14  (device model)\n"
             "App version:   (Settings -> About, or the build tag)\n"
             "What happened: \n"
             "Expected:      \n"
             "Steps:         1. \n"
             "               2. \n"
             "Frequency:     always / sometimes / once\n"
             "Screenshot/log: attach if you have one\n"
             "```\n"
             "Feature ideas go in **#feature-requests**, not here."
         )},
    ]),
    ("DEV", [
        {"name": "bug-reports",
         "topic": "General bug reports. GitHub issues auto-post here.",
         "webhook": "GitHub Issues"},
        {"name": "ios-bugs", "topic": "iOS-specific bugs (iPhone/iPad)."},
        {"name": "android-bugs", "topic": "Android-specific bugs."},
        {"name": "crashes-and-logs",
         "topic": "Crash reports and log dumps. In-app 'Report bug' posts here.",
         "webhook": "In-App Reports"},
        {"name": "triage",
         "topic": "Maintainers-only: sorting, prioritising, and de-duping reports."},
        {"name": "feature-requests", "topic": "Ideas and requests (not bugs)."},
    ]),
    ("RELEASES", [
        {"name": "ci-builds", "readonly": True,
         "topic": "Every signed APK/IPA build from CI posts here.",
         "webhook": "CI Builds"},
        {"name": "changelog", "readonly": True,
         "topic": "Human-readable notes for each release."},
    ]),
    ("GENERAL", [
        {"name": "general", "topic": "General chat."},
        {"name": "off-topic", "topic": "Everything else."},
    ]),
]


# ---- Tiny REST client ------------------------------------------------------
class Discord:
    def __init__(self, token: str):
        self._auth = f"Bot {token}"

    def _request(self, method: str, path: str, body: dict | None = None) -> dict | list:
        url = API + path
        data = json.dumps(body).encode() if body is not None else None
        while True:
            req = urllib.request.Request(url, data=data, method=method)
            req.add_header("Authorization", self._auth)
            req.add_header("Content-Type", "application/json")
            req.add_header("User-Agent", "RuneMobile-Provisioner (stdlib, v1)")
            try:
                with urllib.request.urlopen(req) as resp:
                    raw = resp.read()
                    return json.loads(raw) if raw else {}
            except urllib.error.HTTPError as e:
                payload = e.read().decode(errors="replace")
                if e.code == 429:  # rate limited — respect retry_after and try again
                    try:
                        retry = float(json.loads(payload).get("retry_after", 1.0))
                    except Exception:
                        retry = 1.0
                    time.sleep(retry + 0.25)
                    continue
                raise SystemExit(
                    f"\nDiscord API error {e.code} on {method} {path}\n{payload}\n"
                    "(401 = bad token; 403 = bot lacks permission / not Administrator; "
                    "404 = wrong guild ID or bot not in the server)"
                )
            except urllib.error.URLError as e:
                raise SystemExit(f"Network error reaching Discord: {e.reason}")

    def get(self, path):
        return self._request("GET", path)

    def post(self, path, body):
        return self._request("POST", path, body)


# ---- Config loading --------------------------------------------------------
def load_config() -> tuple[str, str]:
    if CONFIG_FILE.exists():
        for line in CONFIG_FILE.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    token = os.environ.get("DISCORD_BOT_TOKEN", "").strip()
    guild = os.environ.get("DISCORD_GUILD_ID", "").strip()
    if not token or not guild:
        raise SystemExit(
            "Missing DISCORD_BOT_TOKEN and/or DISCORD_GUILD_ID.\n"
            f"Set them in the environment, or create {CONFIG_FILE} with:\n"
            "    DISCORD_BOT_TOKEN=...\n"
            "    DISCORD_GUILD_ID=...\n"
            "See tools/discord/README.md for how to get both."
        )
    return token, guild


# ---- Provisioning steps ----------------------------------------------------
def ensure_roles(dc: Discord, guild: str, dry: bool) -> None:
    existing = {r["name"]: r for r in dc.get(f"/guilds/{guild}/roles")}
    for role in ROLES:
        if role["name"] in existing:
            print(f"  role   ok      {role['name']}")
            continue
        if dry:
            print(f"  role   CREATE  {role['name']}")
            continue
        dc.post(f"/guilds/{guild}/roles", role)
        print(f"  role   created {role['name']}")


def _readonly_overwrites(guild: str) -> list[dict]:
    # @everyone (role id == guild id): can view + read history, cannot send.
    return [{
        "id": guild,
        "type": 0,
        "allow": str(VIEW_CHANNEL | READ_MESSAGE_HISTORY),
        "deny": str(SEND_MESSAGES | ADD_REACTIONS),
    }]


def ensure_channels(dc: Discord, guild: str, dry: bool) -> dict:
    """Create categories + channels; return {channel_name: channel_obj} for real ones."""
    channels = dc.get(f"/guilds/{guild}/channels")
    by_name_type = {(c["name"], c["type"]): c for c in channels}
    created: dict[str, dict] = {c["name"]: c for c in channels if c["type"] == CHANNEL_TEXT}

    for cat_name, chans in BLUEPRINT:
        cat = by_name_type.get((cat_name, CHANNEL_CATEGORY))
        if cat:
            print(f"  cat    ok      {cat_name}")
        elif dry:
            print(f"  cat    CREATE  {cat_name}")
            cat = None
        else:
            cat = dc.post(f"/guilds/{guild}/channels",
                          {"name": cat_name, "type": CHANNEL_CATEGORY})
            print(f"  cat    created {cat_name}")

        for ch in chans:
            name = ch["name"]
            existing = by_name_type.get((name, CHANNEL_TEXT))
            if existing:
                print(f"  chan   ok      #{name}")
                created[name] = existing
                continue
            if dry:
                flags = " [readonly]" if ch.get("readonly") else ""
                hook = f" [webhook: {ch['webhook']}]" if ch.get("webhook") else ""
                print(f"  chan   CREATE  #{name}{flags}{hook}")
                continue
            body = {
                "name": name,
                "type": CHANNEL_TEXT,
                "topic": ch.get("topic", ""),
            }
            if cat:
                body["parent_id"] = cat["id"]
            if ch.get("readonly"):
                body["permission_overwrites"] = _readonly_overwrites(guild)
            obj = dc.post(f"/guilds/{guild}/channels", body)
            created[name] = obj
            print(f"  chan   created #{name}")
    return created


def ensure_webhooks(dc: Discord, channels: dict, dry: bool, save: bool) -> dict:
    wanted = [(ch["name"], ch["webhook"])
              for _, chans in BLUEPRINT for ch in chans if ch.get("webhook")]
    urls: dict[str, str] = {}
    for chan_name, hook_name in wanted:
        chan = channels.get(chan_name)
        if dry or chan is None:
            print(f"  hook   {'CREATE ' if dry else 'skip   '} {hook_name} -> #{chan_name}")
            continue
        existing = {w["name"]: w for w in dc.get(f"/channels/{chan['id']}/webhooks")}
        wh = existing.get(hook_name) or dc.post(
            f"/channels/{chan['id']}/webhooks", {"name": hook_name})
        url = f"https://discord.com/api/webhooks/{wh['id']}/{wh['token']}"
        urls[hook_name] = url
        state = "ok     " if hook_name in existing else "created"
        print(f"  hook   {state} {hook_name} -> #{chan_name}")

    if urls and save and not dry:
        CONFIG_DIR.mkdir(exist_ok=True)
        lines = [f"# RuneMobile Discord webhooks — SECRET, do not commit.",
                 f"GITHUB_WEBHOOK={urls.get('GitHub Issues', '')}",
                 f"CI_WEBHOOK={urls.get('CI Builds', '')}",
                 f"INAPP_WEBHOOK={urls.get('In-App Reports', '')}"]
        WEBHOOKS_FILE.write_text("\n".join(lines) + "\n")
        WEBHOOKS_FILE.chmod(0o600)
        print(f"\n  saved webhook URLs -> {WEBHOOKS_FILE} (chmod 600)")
    return urls


def post_templates(dc: Discord, channels: dict, dry: bool) -> None:
    for _, chans in BLUEPRINT:
        for ch in chans:
            tmpl = ch.get("template")
            chan = channels.get(ch["name"])
            if not tmpl or chan is None:
                continue
            if dry:
                print(f"  tmpl   CREATE  post template in #{ch['name']}")
                continue
            # Only post if the channel is empty, so re-runs don't spam.
            msgs = dc.get(f"/channels/{chan['id']}/messages?limit=1")
            if msgs:
                print(f"  tmpl   ok      #{ch['name']} already has messages")
                continue
            dc.post(f"/channels/{chan['id']}/messages", {"content": tmpl})
            print(f"  tmpl   posted  #{ch['name']}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Provision the RuneMobile Discord server.")
    ap.add_argument("--dry-run", action="store_true",
                    help="show what would be created without touching the server")
    ap.add_argument("--save-webhooks", action="store_true",
                    help=f"write webhook URLs to {WEBHOOKS_FILE}")
    args = ap.parse_args()

    token, guild = load_config()
    dc = Discord(token)

    if not args.dry_run:
        g = dc.get(f"/guilds/{guild}")
        print(f"Provisioning server: {g.get('name', guild)}\n")
    else:
        print("DRY RUN — nothing will be created.\n")

    print("Roles:")
    ensure_roles(dc, guild, args.dry_run)
    print("\nChannels:")
    channels = ensure_channels(dc, guild, args.dry_run)
    print("\nWebhooks:")
    urls = ensure_webhooks(dc, channels, args.dry_run, args.save_webhooks)
    print("\nTemplates:")
    post_templates(dc, channels, args.dry_run)

    if urls and not args.save_webhooks:
        print("\nWebhook URLs (SECRET — treat like passwords, do not commit):")
        for name, url in urls.items():
            print(f"  {name}: {url}")
        print("Re-run with --save-webhooks to write them to "
              f"{WEBHOOKS_FILE} instead.")
    print("\nDone." if not args.dry_run else "\nDry run complete.")


if __name__ == "__main__":
    main()
