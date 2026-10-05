#!/usr/bin/env bash
# Two jukz clients on ONE Linux PC, each on a DIFFERENT internet connection — a real cross-network test
# (or demo) without a second computer or a friend.
#
#   client A ("HostA")  → the PC's normal connection (e.g. home Wi-Fi / Ethernet)
#   client B ("GuestB") → a second connection only it can use (e.g. a phone's mobile data over USB
#                         tethering, or a second network card), inside a separate network namespace
#
# Usage:
#   tools/two-networks.sh up        build the separate network on the second connection (asks for sudo)
#   tools/two-networks.sh play      start client A here and client B on the second connection
#   tools/two-networks.sh stop      close both clients
#   tools/two-networks.sh down      remove the separate network (asks for sudo)
#   tools/two-networks.sh status    which public IP each side has
#
# Options (environment): IFACE=<name> picks the second connection (default: the first USB network
# device, i.e. phone tethering); NAME_A / NAME_B change the player names.
#
# Phone tips: turn on USB tethering with mobile data ON and the phone's Wi-Fi OFF (or it would go out
# through your home Wi-Fi again). Some phones' RNDIS tethering stalls after a while; switching tethering
# off and on fixes it.
#
# How it works: `up` adds an ipvlan child of the second connection (same MAC, another address of its
# subnet) inside the namespace "jukz2net" — the interface itself is left alone, since moving it makes
# phones restart tethering — and gives that connection the worst route priority, so the rest of the PC
# keeps using the normal one. It also leaves a small "bridge": a process of yours inside the namespace
# that runs what `play`/`stop` write to $BRIDGE/cmd, so they need no sudo. Client B is started from
# client A's own java command line (Gradle can't run twice across namespaces: its daemons and file locks
# talk over localhost), with its own run folder and name.
set -euo pipefail

NS=jukz2net
BRIDGE=/tmp/jukz2net
CHILD_IF=jukz2net0
ROOT="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
NAME_A="${NAME_A:-HostA}"
NAME_B="${NAME_B:-GuestB}"
JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-25.0.4.1+1}"

say() { printf '\033[1;34m[two-networks]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[two-networks]\033[0m %s\n' "$*" >&2; exit 1; }

as_root() {
  # Re-run this script as root, keeping what the bridge needs to open windows in your session.
  if [ "$(id -u)" != 0 ]; then
    exec sudo --preserve-env=WAYLAND_DISPLAY,DISPLAY,XDG_RUNTIME_DIR,IFACE bash "$0" "$@"
  fi
}

second_iface() {
  if [ -n "${IFACE:-}" ]; then echo "$IFACE"; return; fi
  for dev in /sys/class/net/*; do
    name="$(basename "$dev")"
    [ "$name" = lo ] && continue
    if readlink -f "$dev/device" 2>/dev/null | grep -q '/usb'; then echo "$name"; return; fi
  done
}

public_ip() { curl -s -m 12 https://api.ipify.org || echo "(no answer)"; }

bridge() {
  [ -p "$BRIDGE/cmd" ] || die "The separate network isn't up (run: $0 up)."
  echo "$1" > "$BRIDGE/cmd"
}

cmd_up() {
  as_root up
  local user="${SUDO_USER:?run it as your user, it asks for sudo itself}"
  if ip netns list | grep -qw "$NS"; then cmd_down_inner; fi
  local IF ADDR GW
  IF="$(second_iface || true)"
  [ -n "$IF" ] || die "No second connection found. Tether the phone over USB, or set IFACE=<name>."
  say "Second connection: $IF — waiting for its address…"
  for _ in $(seq 1 30); do
    ADDR="$(ip -4 -o addr show dev "$IF" | awk '{print $4}' | head -1)"
    GW="$(ip -4 route show dev "$IF" default | awk '{print $3}' | head -1)"
    [ -n "$ADDR" ] && [ -n "$GW" ] && break
    sleep 1
  done
  [ -n "${ADDR:-}" ] && [ -n "${GW:-}" ] || die "$IF has no address or gateway. Is tethering on?"
  nmcli device modify "$IF" ipv4.route-metric 30000 ipv6.route-metric 30000 >/dev/null 2>&1 || true

  local prefix="${ADDR#*/}" base last gwlast n
  base="$(echo "${ADDR%/*}" | cut -d. -f1-3)"; last="$(echo "${ADDR%/*}" | cut -d. -f4)"; gwlast="${GW##*.}"
  n=200; while [ "$n" = "$last" ] || [ "$n" = "$gwlast" ]; do n=$((n + 1)); done
  ip netns add "$NS"
  ip link add "$CHILD_IF" link "$IF" type ipvlan mode l2
  ip link set "$CHILD_IF" netns "$NS"
  ip -n "$NS" link set lo up
  ip -n "$NS" addr add "$base.$n/$prefix" dev "$CHILD_IF"
  ip -n "$NS" link set "$CHILD_IF" up
  ip -n "$NS" route add default via "$GW" dev "$CHILD_IF"
  mkdir -p "/etc/netns/$NS"
  printf 'nameserver %s\nnameserver 1.1.1.1\n' "$GW" > "/etc/netns/$NS/resolv.conf"

  rm -rf "$BRIDGE"; mkdir -p "$BRIDGE"; mkfifo "$BRIDGE/cmd"; chown -R "$user" "$BRIDGE"
  local uid; uid="$(id -u "$user")"
  ip netns exec "$NS" setsid sudo -u "$user" env -i \
      HOME="$(getent passwd "$user" | cut -d: -f6)" USER="$user" PATH="/usr/local/bin:/usr/bin:/bin" \
      XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$uid}" WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}" DISPLAY="${DISPLAY:-:0}" \
      bash -c "echo \$\$ > $BRIDGE/pid; while true; do if read -r line < $BRIDGE/cmd; then (setsid bash -c \"\$line\" >> $BRIDGE/log 2>&1 &); fi; done" \
      > /dev/null 2>&1 < /dev/null &
  sleep 1
  say "Client B's side ($IF): $(ip netns exec "$NS" bash -c "$(declare -f public_ip); public_ip")"
  say "Client A's side (normal): $(public_ip)"
  say "Ready. Next: $0 play"
}

cmd_down_inner() {
  [ -f "$BRIDGE/pid" ] && kill "$(cat "$BRIDGE/pid")" 2>/dev/null || true
  for pid in $(ip netns pids "$NS" 2>/dev/null); do kill "$pid" 2>/dev/null || true; done
  sleep 2
  ip netns delete "$NS" 2>/dev/null || true   # takes the ipvlan child with it
  rm -rf "/etc/netns/$NS" "$BRIDGE"
}

cmd_down() {
  as_root down
  cmd_down_inner
  say "Separate network removed; the second connection is untouched."
}

configure() { # <run dir> — the public rendezvous, no forced relay: jukz picks the path like for players
  local file="$1/config/jukz.properties"
  mkdir -p "$(dirname "$file")"; touch "$file"
  for pair in "rendezvous.url=" "jukz.force-relay=false"; do
    local key="${pair%%=*}"
    if grep -q "^$key=" "$file"; then sed -i "s#^$key=.*#$pair#" "$file"; else echo "$pair" >> "$file"; fi
  done
}

client_pid() { # <run dir name> → pid of the game running there
  for p in $(pgrep java || true); do
    [ "$(readlink "/proc/$p/cwd" 2>/dev/null)" = "$ROOT/fabric/run/$1" ] && grep -qa KnotClient "/proc/$p/cmdline" && { echo "$p"; return; }
  done
}

cmd_play() {
  [ -p "$BRIDGE/cmd" ] || die "The separate network isn't up (run: $0 up)."
  configure "$ROOT/fabric/run/clientA"; configure "$ROOT/fabric/run/clientB"
  if [ -z "$(client_pid clientA)" ]; then
    say "Starting client A ($NAME_A) on the normal connection…"
    (cd "$ROOT" && JAVA_HOME="$JAVA_HOME" setsid bash gradlew :fabric:1.21.1:runPlay -Pjukz.runDir=run/clientA "-Pjukz.username=$NAME_A" > /tmp/jukz2net-clientA.log 2>&1 &)
  fi
  local pa=""
  for _ in $(seq 1 180); do pa="$(client_pid clientA)"; [ -n "$pa" ] && break; sleep 2; done
  [ -n "$pa" ] || die "Client A didn't start (see /tmp/jukz2net-clientA.log)."
  say "Starting client B ($NAME_B) on the second connection…"
  # A's exact java command, with B's name; it runs from B's own folder (saves, config, logs).
  tr '\0' '\n' < "/proc/$pa/cmdline" | awk -v b="$NAME_B" 'prev=="--username"{$0=b} {print; prev=$0}' > "$BRIDGE/clientB.cmd"
  bridge "cd '$ROOT/fabric/run/clientB' && mapfile -t ARGS < '$BRIDGE/clientB.cmd' && exec \"\${ARGS[@]}\" > '$BRIDGE/clientB.log' 2>&1"
  say "Both starting. In A open a world (its share code is in the pause menu → World info); in B use"
  say "Multiplayer → Play together with that code. The logs say \"via relay\" or the direct address used."
}

cmd_stop() {
  for d in clientA clientB; do p="$(client_pid "$d")"; [ -n "$p" ] && kill "$p" && say "Closed $d"; done
  true
}

cmd_status() {
  say "Client A's side (normal): $(public_ip)"
  if [ -p "$BRIDGE/cmd" ]; then
    rm -f "$BRIDGE/ip"
    bridge "curl -s -m 12 https://api.ipify.org > '$BRIDGE/ip'"
    for _ in $(seq 1 14); do [ -s "$BRIDGE/ip" ] && break; sleep 1; done
    say "Client B's side (second):  $(cat "$BRIDGE/ip" 2>/dev/null || echo '(no answer — is tethering still working?)')"
  else
    say "Client B's side: the separate network isn't up."
  fi
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  play) cmd_play ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  *) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
