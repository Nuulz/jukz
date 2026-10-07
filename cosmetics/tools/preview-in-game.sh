#!/usr/bin/env bash
# Films cosmetics in the REAL game and makes a GIF: same engine, lighting, skin and mixins as players see.
# (animated.py is the quick preview while sculpting; this one is the truth before deploying.)
#
#   ./preview-in-game.sh <minecraft> <item> [item...]
#
#   <minecraft>  1.21.11 or 26.2 (1.21.1 has no client gametest API)
#   <item>       catalog ids to wear together, e.g. halo wings emote_coin. An emote plays from frame 0.
#
#   FRAMES=60    game ticks to film per camera (20 = 1 s). Default 60.
#
# Output: cosmetics/previews/<items>-<minecraft>.gif, front and back side by side.
# A Minecraft window opens and closes by itself (about a minute); don't touch it while it films.
# The catalog it uses is cosmetics/catalog.json: run build_catalog.py first if you changed a piece.
#
# Example:
#   ./preview-in-game.sh 26.2 halo wings emote_coin
set -euo pipefail

if [[ $# -lt 2 || ! "$1" =~ ^(1\.21\.11|26\.2)$ ]]; then
  sed -n '4,16p' "$0"; exit 1
fi
MC=$1; shift
ITEMS=$(IFS=,; echo "$*")
NAME=$(IFS=+; echo "$*")
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SHOTS=$(mktemp -d)
OUT="$ROOT/cosmetics/previews/$NAME-$MC.gif"
mkdir -p "$ROOT/cosmetics/previews"

LOG="$ROOT/cosmetics/previews/last-run.log"
echo "filming $* on $MC... (game log: $LOG)"
if ! (cd "$ROOT" && ./gradlew --no-daemon -q ":fabric:$MC:runClientGameTest" \
  "-Pjukz.preview.items=$ITEMS" "-Pjukz.preview.out=$SHOTS" "-Pjukz.preview.frames=${FRAMES:-60}") >"$LOG" 2>&1; then
  grep -E "^e: |Exception|FAILED|went wrong" "$LOG" | head -20
  echo "the game run failed, full log: $LOG"; exit 1
fi

python3 "$HERE/preview_gif.py" "$SHOTS" "$OUT" "${ITEMS//,/ + }  ($MC)"
rm -rf "$SHOTS"
echo "$OUT"
