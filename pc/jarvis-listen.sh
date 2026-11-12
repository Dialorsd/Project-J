#!/usr/bin/env bash
# Jarvis PC remote listener for macOS / Linux (needs only bash, curl and openssl).
#
#   jarvis-listen.sh --pair            paste the pairing code from the app (Settings > PC link > PAIR PC)
#   jarvis-listen.sh                   listen for commands from the phone (keep it running)
#   jarvis-listen.sh --show-config     print the config file path and the enabled commands
#
# Security: the phone sends only a command NAME, signed with HMAC-SHA256 using a secret that only the phone
# and this computer know. Messages with a wrong signature, older than 60 seconds or already seen are ignored.
# Only commands switched on in ~/.jarvis-pc.json run; shutdown, restart, sleep and your own commands are OFF
# until you change "false" to "true" there. Never commit or share ~/.jarvis-pc.json (it holds the secret).

CONFIG="${JARVIS_PC_CONFIG:-$HOME/.jarvis-pc.json}"
NONCES="${CONFIG%.json}.nonces"
LOG="${CONFIG%.json}.log"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
NAME_RE='^[A-Za-z0-9._ -]{0,64}$'
MAX_SKEW=60

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$LOG"; }
say() { printf '%s\n' "$*"; log "$*"; }

cfg_str() { sed -n "s/^[[:space:]]*\"$1\"[[:space:]]*:[[:space:]]*\"\\(.*\\)\"[[:space:]]*,\\{0,1\\}[[:space:]]*\$/\\1/p" "$CONFIG" | head -n 1; }
cfg_on() { grep -Eq "^[[:space:]]*\"enable_$1\"[[:space:]]*:[[:space:]]*true" "$CONFIG"; }
# A user-defined command line from the "commands" block (one "name": "command line" per line).
cfg_cmd() {
  awk '/"commands"[[:space:]]*:/ { f = 1; next } f && /^[[:space:]]*}/ { f = 0 } f' "$CONFIG" |
    sed -n "s/^[[:space:]]*\"$1\"[[:space:]]*:[[:space:]]*\"\\(.*\\)\"[[:space:]]*,\\{0,1\\}[[:space:]]*\$/\\1/p" | head -n 1 |
    sed 's/\\"/"/g; s/\\\\/\\/g'
}

hmac() { # $1 = data -> hex
  printf '%s' "$1" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.*= //'
}
b64url_dec() {
  local s; s="$(printf '%s' "$1" | tr '_-' '/+')"
  case $(( ${#s} % 4 )) in 2) s="$s==" ;; 3) s="$s=" ;; esac
  printf '%s\n' "$s" | openssl base64 -d -A 2>/dev/null
}
b64url_enc() { printf '%s' "$1" | openssl base64 -A | tr '+/' '-_' | tr -d '='; }
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/ /g' | awk 'BEGIN { ORS = "\\n" } { print }' | sed 's/\\n$//'; }
json_field() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\\([^\"]*\\)\".*/\\1/p"; }

# Signed reply to the phone (over the normal PC link topic). $1 = request nonce or "", $2 = true/false, $3 = text, $4 = event name or ""
reply() {
  local re="$1" ok="$2" text="$3" ev="$4" payload ts nonce b64 mac
  payload="{\"re\":\"$re\",\"ok\":$ok,\"text\":\"$(json_escape "$(printf '%s' "$text" | cut -c1-2500)")\""
  [ -n "$ev" ] && payload="$payload,\"event\":\"$ev\""
  payload="$payload}"
  ts="$(date +%s)"
  nonce="$(openssl rand -hex 16)"
  b64="$(b64url_enc "$payload")"
  mac="$(hmac "$(printf 'reply\n%s\n%s\n%s' "$ts" "$nonce" "$b64")")"
  curl -sS -m 15 -o /dev/null -H "Tags: jarvis-reply" -H "Priority: 2" \
    --data-binary "JARVIS1 $ts $nonce $b64 $mac" "$SERVER/$REPLY_TOPIC" || log "could not send reply"
}

# ------------------------------------------------------------------ built-in commands

OS="$(uname -s)"

status_text() {
  local arg="$1" cpu mem disk up top
  if [ "$OS" = Darwin ]; then
    cpu="$(top -l 2 -n 0 2>/dev/null | grep 'CPU usage' | tail -n 1 | sed 's/^CPU usage: //')"
    mem="$(( $(sysctl -n hw.memsize) / 1073741824 )) GB total, $(memory_pressure 2>/dev/null | sed -n 's/.*free percentage: //p') free"
    top="$(ps -Ao pcpu,comm -r | sed -n '2,6p' | awk '{ n = $2; sub(".*/", "", n); printf "%s %s%%; ", n, $1 }')"
  else
    local a b; read -r _ a1 a2 a3 a4 a5 a6 a7 _ < /proc/stat; sleep 1; read -r _ b1 b2 b3 b4 b5 b6 b7 _ < /proc/stat
    a=$((a1 + a2 + a3 + a4 + a5 + a6 + a7)); b=$((b1 + b2 + b3 + b4 + b5 + b6 + b7))
    if [ "$b" -gt "$a" ]; then cpu="$(( 100 - 100 * (b4 - a4) / (b - a) ))%"; else cpu="?"; fi
    mem="$(awk '/MemTotal/ { t = $2 } /MemAvailable/ { v = $2 } END { printf "%.1f GB free of %.1f GB", v / 1048576, t / 1048576 }' /proc/meminfo)"
    top="$(ps -eo pcpu,comm --sort=-pcpu | sed -n '2,6p' | awk '{ printf "%s %s%%; ", $2, $1 }')"
  fi
  disk="$(df -h / | awk 'NR == 2 { print $4 " free of " $2 }')"
  up="$(uptime | sed 's/.*up \([^,]*\),.*/\1/')"
  printf 'CPU %s. Memory: %s. Disk: %s. Up %s. Busiest: %s' "$cpu" "$mem" "$disk" "$up" "$top"
  if [ -n "$arg" ]; then
    if pgrep -x "$arg" >/dev/null 2>&1; then printf ' %s is running.' "$arg"; else printf ' %s is NOT running.' "$arg"; fi
  fi
}

# Exact process name (as in ps / Activity Monitor). Not "pgrep -f": that would also match this script's own loop.
running() { pgrep -x "$1" >/dev/null 2>&1; }

do_lock() {
  if [ "$OS" = Darwin ]; then pmset displaysleepnow; else loginctl lock-session 2>/dev/null || xdg-screensaver lock 2>/dev/null; fi
}
do_sleep() { if [ "$OS" = Darwin ]; then pmset sleepnow; else systemctl suspend; fi; }
do_shutdown() { if [ "$OS" = Darwin ]; then sudo -n shutdown -h +1; else shutdown -h +1; fi; }
do_restart() { if [ "$OS" = Darwin ]; then sudo -n shutdown -r +1; else shutdown -r +1; fi; }
do_cancel() { if [ "$OS" = Darwin ]; then sudo -n killall shutdown; else shutdown -c; fi; }

run_cmd() { # $1 = cmd, $2 = name, $3 = arg, $4 = request nonce
  local cmd="$1" name="$2" arg="$3" re="$4" out
  if ! cfg_on "$cmd"; then
    reply "$re" false "\"$cmd\" is switched off on the PC. To allow it, set \"enable_$cmd\": true in $CONFIG and restart the listener."
    return
  fi
  case "$cmd" in
    status) reply "$re" true "$(status_text "$arg")" ;;
    lock) if do_lock; then reply "$re" true "Locked."; else reply "$re" false "Could not lock this computer."; fi ;;
    sleep) reply "$re" true "Going to sleep now."; sleep 2; do_sleep || reply "" false "Sleep failed." ;;
    shutdown) if out="$(do_shutdown 2>&1)"; then reply "$re" true "Shutting down in 1 minute. Say cancel shutdown to stop it."; else reply "$re" false "Shutdown failed: $out"; fi ;;
    restart) if out="$(do_restart 2>&1)"; then reply "$re" true "Restarting in 1 minute. Say cancel shutdown to stop it."; else reply "$re" false "Restart failed: $out"; fi ;;
    cancel_shutdown) if do_cancel >/dev/null 2>&1; then reply "$re" true "Shutdown cancelled."; else reply "$re" false "There was nothing to cancel, or it could not be cancelled."; fi ;;
    watch_exit|shutdown_when_done)
      local p="${arg:-$name}"
      if ! running "$p"; then reply "$re" false "$p is not running."; return; fi
      if [ "$cmd" = shutdown_when_done ]; then reply "$re" true "I'll shut down the PC when $p finishes."; else reply "$re" true "Watching $p; I'll call when it exits."; fi
      (
        while running "$p"; do sleep 10; done
        if [ "$cmd" = shutdown_when_done ]; then
          if do_shutdown >/dev/null 2>&1; then reply "" true "$p has finished. The PC shuts down in 1 minute." exit
          else reply "" false "$p has finished, but the shutdown failed." exit; fi
        else
          reply "" true "$p has finished on your PC." exit
        fi
      ) >/dev/null 2>&1 &
      ;;
    run)
      local line; line="$(cfg_cmd "$name")"
      if [ -z "$name" ] || [ -z "$line" ]; then reply "$re" false "There is no command called \"$name\" in $CONFIG."; return; fi
      reply "$re" true "Started \"$name\"."
      ( sh -c "$line" >/dev/null 2>&1; rc=$?
        if [ $rc -eq 0 ]; then reply "" true "\"$name\" finished successfully." exit; else reply "" false "\"$name\" failed (exit code $rc)." exit; fi
      ) >/dev/null 2>&1 &
      ;;
    *) reply "$re" false "Unknown command \"$cmd\"." ;;
  esac
}

# ------------------------------------------------------------------ verification

handle() { # $1 = message body
  local pfx ts nonce b64 mac extra want now age payload cmd name arg
  read -r pfx ts nonce b64 mac extra <<EOF
$1
EOF
  if [ "$pfx" != JARVIS1 ] || [ -n "$extra" ] || [ -z "$mac" ]; then log "rejected: malformed message"; return; fi
  case "$ts" in *[!0-9]*|"") log "rejected: bad timestamp"; return ;; esac
  case "$nonce" in *[!0-9a-f]*|"") log "rejected: bad nonce"; return ;; esac
  want="$(hmac "$(printf 'cmd\n%s\n%s\n%s' "$ts" "$nonce" "$b64")")"
  if [ "$want" != "$mac" ]; then log "rejected: wrong signature"; return; fi
  now="$(date +%s)"; age=$(( now - ts )); [ $age -lt 0 ] && age=$(( -age ))
  if [ $age -gt $MAX_SKEW ]; then log "rejected: timestamp ${age}s away (check the clocks)"; return; fi
  touch "$NONCES"; chmod 600 "$NONCES" 2>/dev/null
  if grep -q " $nonce\$" "$NONCES"; then log "rejected: replayed nonce"; return; fi
  awk -v min=$(( now - 600 )) '$1 >= min' "$NONCES" > "$NONCES.tmp" 2>/dev/null; mv "$NONCES.tmp" "$NONCES"
  printf '%s %s\n' "$ts" "$nonce" >> "$NONCES"
  payload="$(b64url_dec "$b64")"
  cmd="$(json_field "$payload" cmd)"; name="$(json_field "$payload" name)"; arg="$(json_field "$payload" arg)"
  for v in "$cmd" "$name" "$arg"; do
    if ! [[ $v =~ $NAME_RE ]]; then log "rejected: bad characters in payload"; return; fi
  done
  log "accepted: $cmd $name $arg"
  run_cmd "$cmd" "$name" "$arg" "$nonce"
}

load_config() {
  if [ ! -f "$CONFIG" ]; then echo "Not paired yet. Run:  $SELF --pair"; exit 1; fi
  SERVER="$(cfg_str server)"; SERVER="${SERVER%/}"
  REPLY_TOPIC="$(cfg_str reply_topic)"; CMD_TOPIC="$(cfg_str cmd_topic)"; SECRET="$(cfg_str secret)"
  if [ -z "$SERVER" ] || [ -z "$REPLY_TOPIC" ] || [ -z "$CMD_TOPIC" ] || [ ${#SECRET} -lt 32 ]; then
    echo "$CONFIG is incomplete. Pair again:  $SELF --pair"; exit 1
  fi
}

pair() {
  local code="$1"
  if [ -z "$code" ]; then printf 'Paste the pairing code from the app (it is not shown while you paste), then press Enter: '; read -r -s code; echo; fi
  local tag server rtopic ctopic secret
  IFS=, read -r tag server rtopic ctopic secret <<EOF
$code
EOF
  if [ "$tag" != J1 ] || ! [[ $rtopic$ctopic$secret =~ ^[A-Za-z0-9_-]+$ ]] || [ ${#secret} -lt 32 ]; then
    echo "That does not look like a Jarvis pairing code (it starts with J1,)."; exit 1
  fi
  case "$server" in https://*|http://*) ;; *) echo "Bad server in the code."; exit 1 ;; esac
  if [ -f "$CONFIG" ]; then
    # Keep the enabled commands, replace only the connection details.
    sed -i.bak -e "s|^\\([[:space:]]*\"server\"[[:space:]]*:[[:space:]]*\"\\).*\"|\\1$server\"|" \
      -e "s|^\\([[:space:]]*\"reply_topic\"[[:space:]]*:[[:space:]]*\"\\).*\"|\\1$rtopic\"|" \
      -e "s|^\\([[:space:]]*\"cmd_topic\"[[:space:]]*:[[:space:]]*\"\\).*\"|\\1$ctopic\"|" \
      -e "s|^\\([[:space:]]*\"secret\"[[:space:]]*:[[:space:]]*\"\\).*\"|\\1$secret\"|" "$CONFIG" && rm -f "$CONFIG.bak"
  else
    umask 077
    cat > "$CONFIG" <<JSON
{
  "server": "$server",
  "reply_topic": "$rtopic",
  "cmd_topic": "$ctopic",
  "secret": "$secret",
  "enable_status": true,
  "enable_lock": true,
  "enable_watch_exit": true,
  "enable_cancel_shutdown": true,
  "enable_sleep": false,
  "enable_shutdown": false,
  "enable_restart": false,
  "enable_shutdown_when_done": false,
  "enable_run": false,
  "commands": {
    "example": "echo hello from Jarvis"
  }
}
JSON
  fi
  chmod 600 "$CONFIG"
  echo "Paired. Settings are in $CONFIG (keep it private)."
  echo "Start listening with:  $SELF"
}

case "$1" in
  --pair) pair "$2"; exit 0 ;;
  --show-config)
    load_config
    echo "Config: $CONFIG"; echo "Server: $SERVER"
    grep -E '"enable_' "$CONFIG" | tr -d ' ,'
    exit 0 ;;
  --help|-h) sed -n '2,12p' "$0"; exit 0 ;;
esac

load_config
command -v openssl >/dev/null || { echo "openssl is required."; exit 1; }
say "Jarvis listener started (commands topic on $SERVER). Log: $LOG"
backoff=2
since=""
while true; do
  started=$(date +%s)
  while IFS= read -r line; do
    printf '%s' "$line" | grep -Eq '"event":[[:space:]]*"message"' || continue
    t="$(printf '%s' "$line" | sed -n 's/.*"time":[[:space:]]*\([0-9]*\).*/\1/p')"; [ -n "$t" ] && since="$t"
    msg="$(printf '%s' "$line" | sed -n 's/.*"message":[[:space:]]*"\(JARVIS1 [A-Za-z0-9_ -]*\)".*/\1/p')"
    [ -n "$msg" ] && handle "$msg"
  done < <(curl -sN --connect-timeout 20 --keepalive-time 30 "$SERVER/$CMD_TOPIC/json${since:+?since=$since}")
  # Reconnect; back off if the connection keeps failing quickly.
  if [ $(( $(date +%s) - started )) -gt 60 ]; then backoff=2; else backoff=$(( backoff * 2 > 120 ? 120 : backoff * 2 )); fi
  sleep "$backoff"
done
