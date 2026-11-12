#!/bin/sh
# Jarvis PC link: make your phone ring through ntfy.
#
#   jarvis-ping.sh "Message" [Title]      send a ping
#   jarvis-ping.sh --quiet "Message" [Title]   notification only, no ringing
#   jarvis-ping.sh --set-topic jarvis-xxxx [server]   save your topic (copy it from the app)
#   jarvis-ping.sh --print-hook           print the Claude Code hook settings for this computer
#   jarvis-ping.sh --hook                 used by Claude Code hooks (reads hook JSON from stdin)
#
# The topic comes from $JARVIS_TOPIC or the file ~/.jarvis-ping. Never commit or share it:
# anyone who knows it can ring your phone.

CONFIG="$HOME/.jarvis-ping"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

case "$1" in
  --set-topic)
    case "$2" in
      ""|*[!A-Za-z0-9_-]*) echo "That does not look like a topic (letters, digits, - and _ only)."; exit 1 ;;
    esac
    server="${3:-https://ntfy.sh}"
    printf 'topic=%s\nserver=%s\n' "$2" "${server%/}" > "$CONFIG"
    chmod 600 "$CONFIG"
    echo "Saved to $CONFIG. Test it with:  $SELF \"Hello from my computer\""
    exit 0 ;;
  --print-hook)
    cmd="{ \"type\": \"command\", \"command\": \"$SELF --hook\", \"timeout\": 30 }"
    cat <<JSON
{
  "hooks": {
    "Stop": [
      { "hooks": [ $cmd ] }
    ],
    "Notification": [
      { "matcher": "permission_prompt|elicitation_dialog|agent_needs_input", "hooks": [ $cmd ] }
    ]
  }
}
JSON
    exit 0 ;;
esac

topic="$JARVIS_TOPIC"
server="$JARVIS_SERVER"
if [ -f "$CONFIG" ]; then
  [ -z "$topic" ] && topic="$(sed -n 's/^[[:space:]]*topic[[:space:]]*=[[:space:]]*//p' "$CONFIG" | head -n 1)"
  [ -z "$server" ] && server="$(sed -n 's/^[[:space:]]*server[[:space:]]*=[[:space:]]*//p' "$CONFIG" | head -n 1)"
fi
server="${server:-https://ntfy.sh}"

quiet=0
hook=0
if [ "$1" = "--quiet" ]; then quiet=1; shift; fi
if [ "$1" = "--hook" ]; then hook=1; shift; fi

# Extract a top-level string field from JSON on stdin-captured text (python3, jq, or a rough fallback).
json_field() {
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$1" | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin).get(sys.argv[1]) or "")
except Exception:
    print("")' "$2"
  elif command -v jq >/dev/null 2>&1; then
    printf '%s' "$1" | jq -r --arg k "$2" '.[$k] // ""' 2>/dev/null
  else
    printf '%s' "$1" | tr '\n' ' ' | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"
  fi
}

if [ "$hook" = 1 ]; then
  input="$(cat)"
  event="$(json_field "$input" hook_event_name)"
  cwd="$(json_field "$input" cwd)"
  folder="$(basename "${cwd:-your project}")"
  case "$event" in
    Stop) title="Claude finished"; msg="Claude has finished working in $folder." ;;
    Notification)
      title="Claude needs you"
      m="$(json_field "$input" message)"
      msg="$folder: ${m:-Claude is waiting for your input.}" ;;
    *) title="Claude"; msg="${event:-Claude} in $folder" ;;
  esac
else
  msg="${1:-Ping from your computer}"
  title="$2"
fi

if [ -z "$topic" ]; then
  echo "No topic set. Run:  $SELF --set-topic <topic from the Jarvis app>"
  [ "$hook" = 1 ] && exit 0
  exit 1
fi

set -- -sS -m 15 -o /dev/null
if [ -n "$title" ]; then
  b64="$(printf '%s' "$title" | base64 | tr -d '\n')"
  set -- "$@" -H "Title: =?UTF-8?B?${b64}?="
fi
if [ "$quiet" = 1 ]; then set -- "$@" -H "Priority: 2" -H "Tags: quiet"; fi

if curl "$@" --data-binary "$msg" "${server%/}/$topic"; then
  [ "$hook" = 1 ] || echo "Sent."
else
  echo "Could not send the ping."
  [ "$hook" = 1 ] && exit 0
  exit 1
fi
exit 0
