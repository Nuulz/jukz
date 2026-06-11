# Relay E2E test (Task 14) — runbook

Goal: prove that a guest reaches a host **through the WebSocket relay** when the direct path is
unavailable, and that gameplay (the DATA channel) — not just the handshake — flows over it.

The relay only fires when every direct endpoint fails, which never happens between two processes on
one PC. The `jukz.force-relay` dev toggle makes it deterministic: the **host always registers a relay
session** (ignoring the UPnP gate) and the **guest skips direct endpoints**, falling straight to the
relay. The traffic still goes out to the real relay at `jukz.nuulm.com` and back, so this is a genuine
end-to-end exercise of the relay on a single machine.

## Already set up

- **Rendezvous deployed** with the relay (`jukz.nuulm.com`). Verify: `curl https://jukz.nuulm.com/healthz`
  returns a `relaySessions` field.
- **Both dev instances are pre-configured** with `jukz.force-relay=true`:
  - `fabric/run/clientA/config/jukz.properties`
  - `fabric/run/clientB/config/jukz.properties`
  - (To go back to testing the normal direct path, set those to `false`.)

## Run it

1. **Launch the host:** `run-client-a.bat` (or `gradlew runClientA`). This rebuilds the mod and starts
   Minecraft as **HostA**.
2. In HostA: **create a new world** (or open an existing jukz world). It auto-hosts. Open the pause
   menu (Esc) → **"World info (jukz)"** → copy the **share code**.
3. **Launch the guest:** `run-client-b.bat` (or `gradlew runClientB`) — starts Minecraft as **GuestB**.
4. In GuestB: **Multiplayer → "Play together"** → paste the share code → connect.
5. GuestB should **spawn into HostA's world**. Move around and **break/place a block** — that confirms
   the DATA channel (not just the handshake) is flowing over the relay.

## What to look for (evidence)

**HostA log** (`fabric/run/clientA/logs/latest.log`):
```
jukz: registered relay session for non-UPnP host
jukz: relay signalled work conn 1 — bridging a guest to the local game     (control channel)
jukz: relay signalled work conn 2 — bridging a guest to the local game     (data channel)
```

**GuestB log** (`fabric/run/clientB/logs/latest.log`):
```
jukz: dialing host via relay (session <id>)
jukz: joined host at 127.0.0.1:<port>
```

**Relay server (optional, strongest evidence)** — from `rendezvous/` (already linked to Railway):
```
railway logs
```
Expect, in order: `relay host link open` (when HostA registers) → a guest connect that allocates a
nonce → a host work conn that matches it → the splice. The same lines appear for each channel
(control, then data).

A live `relaySessions` count > 0 on `https://jukz.nuulm.com/healthz` while HostA is hosting is another
quick confirmation.

## Pass criteria

- [ ] HostA logs `registered relay session` on world open.
- [ ] GuestB logs `dialing host via relay (session …)` and then `joined host`.
- [ ] HostA logs `relay signalled work conn …` at least twice (control + data).
- [ ] GuestB spawns into HostA's world **and** can move / edit blocks (DATA over the relay).

## If it fails

- **GuestB shows "No live host" / NAT error:** confirm HostA logged `registered relay session`, the
  share code matches, and `https://jukz.nuulm.com/healthz` is reachable.
- **Control connects but GuestB never spawns (data stalls):** this would be the DATA-over-relay path
  not taking effect. The stickiness fix (keep DATA on the relay when control connected via relay) is
  in `JoinController`; if data still stalls, that is the first thing to debug.
- **Nothing in the relay logs:** the guest may have used a direct endpoint anyway — confirm
  `jukz.force-relay=true` is actually in `fabric/run/clientB/config/jukz.properties` (it is read at
  launch).

## After testing

This exercises **play** over the relay. The F4 snapshot **handoff** over the relay is the remaining
follow-up (Task 15): `JGitWorldSync.downloadSnapshot` still opens its own direct socket, so a
relay-only guest taking over a leaving host falls back to its local copy.
