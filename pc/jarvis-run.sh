#!/bin/sh
# Runs any command, then makes Jarvis call you with the result and how long it took.
# Example:  jarvis-run.sh make build
[ $# -eq 0 ] && { echo "Usage: $0 <command> [args...]"; exit 2; }
DIR="$(cd "$(dirname "$0")" && pwd)"
start=$(date +%s)
"$@"
code=$?
secs=$(( $(date +%s) - start ))
if [ $secs -ge 3600 ]; then dur="$((secs / 3600))h $((secs % 3600 / 60))m"
elif [ $secs -ge 60 ]; then dur="$((secs / 60))m $((secs % 60))s"
else dur="${secs}s"; fi
name="$(basename "$1")"
if [ $code -eq 0 ]; then
  "$DIR/jarvis-ping.sh" "The command '$name' finished successfully after $dur." "Done: $name"
else
  "$DIR/jarvis-ping.sh" "The command '$name' failed with exit code $code after $dur." "Failed: $name"
fi
exit $code
