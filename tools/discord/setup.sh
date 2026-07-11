#!/usr/bin/env bash
# One-shot interactive setup for the RuneMobile bug-tracking Discord server.
#
# Run it and answer two prompts:
#   1. Bot token   -- from https://discord.com/developers/applications (Bot tab)
#   2. Server ID   -- right-click your server in Discord -> Copy Server ID
#                     (needs Settings -> Advanced -> Developer Mode ON)
#
# It saves both to ~/.runemobile-discord/config.env (chmod 600, outside the repo),
# prints the exact link to invite your bot, waits for you to click it, then builds
# out the whole server and saves the webhook URLs.
#
#   tools/discord/setup.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONF_DIR="$HOME/.runemobile-discord"
CONF="$CONF_DIR/config.env"

echo
echo "=== RuneMobile Discord setup ==="
echo
echo "You need ONE credential: a bot token."
echo
echo "  1. Open https://discord.com/developers/applications"
echo "  2. New Application -> name it -> open the 'Bot' tab"
echo "  3. Reset Token -> Copy. That string is your bot token."
echo "     (It's a password -- it won't be shown as you paste it below.)"
echo

# --- Bot token (hidden input) ---------------------------------------------
token=""
while [ -z "$token" ]; do
  printf "Paste your bot token: "
  read -r -s token || true
  echo
  [ -z "$token" ] && echo "  (empty -- try again)"
done

# The application (client) ID is the base64url-encoded first segment of the
# token, so we can build the invite link for you without another prompt.
app_id="$(printf '%s' "${token%%.*}" | python3 -c '
import sys, base64
s = sys.stdin.read().strip()
try:
    print(base64.urlsafe_b64decode(s + "=" * (-len(s) % 4)).decode())
except Exception:
    print("")
')"

echo
if [ -n "$app_id" ]; then
  echo "Now invite the bot to your server (needs Administrator to set things up):"
  echo
  echo "  https://discord.com/oauth2/authorize?client_id=$app_id&scope=bot&permissions=8"
  echo
  echo "Open that link, pick your server, and click Authorize."
else
  echo "Couldn't read the app ID from the token (that's fine)."
  echo "Invite the bot from the developer portal: OAuth2 -> URL Generator ->"
  echo "tick 'bot' + 'Administrator', open the generated URL, add it to your server."
fi
echo
printf "Press Enter once the bot has been added to your server... "
read -r _ || true

# --- Server (guild) ID -----------------------------------------------------
echo
echo "Server ID: in Discord enable Settings -> Advanced -> Developer Mode,"
echo "then right-click your server icon -> Copy Server ID."
echo
guild=""
while ! printf '%s' "$guild" | grep -qE '^[0-9]{15,20}$'; do
  printf "Paste your server ID (numbers only): "
  read -r guild || true
  guild="$(printf '%s' "$guild" | tr -dc '0-9')"
  [ -z "$guild" ] && echo "  (empty -- try again)"
done

# --- Save secrets outside the repo ----------------------------------------
mkdir -p "$CONF_DIR"; chmod 700 "$CONF_DIR"
umask 177
{
  echo "DISCORD_BOT_TOKEN=$token"
  echo "DISCORD_GUILD_ID=$guild"
} > "$CONF"
chmod 600 "$CONF"
echo
echo "Saved credentials to $CONF (chmod 600)."

# --- Provision -------------------------------------------------------------
echo
echo "Building the server..."
echo
exec python3 "$HERE/provision-server.py" --save-webhooks
