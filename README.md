# jukz

A Minecraft **Fabric 1.21.1** mod (Java 21) that gives every world a permanent UUID and uses a
**rendezvous server + LAN multicast** to discover who is currently hosting it.
Opening a world asks the network "is anyone hosting this right now?": if yes, you join that live
host as a **guest**; if no, the world opens locally and is **announced** as the active host. On
close, the announcement is withdrawn. The world effectively lives in one place at a time — on
whoever's host is "switched on" — with rotating ownership and no manual coordination. There is no
save synchronization: a guest plays live on the host's world (like Open-to-LAN), not on a copy.

Host ↔ guest is verified working end-to-end (two instances on one machine: auto-host on open,
discovery via the rendezvous server, relay, and a guest joining the live world).

See [`docs/superpowers/specs/2026-06-08-jukz-design.md`](docs/superpowers/specs/2026-06-08-jukz-design.md)
for the full design rationale (verified against primary sources) and
[`docs/superpowers/plans/2026-06-08-jukz-core.md`](docs/superpowers/plans/2026-06-08-jukz-core.md)
for the implementation plan.

## Module layout

| Module | What | Minecraft? |
|---|---|---|
| `core` | The deterministic protocol heart — pure Kotlin, fully unit-tested | No |
| `fabric` | Wires `core` into Minecraft 1.21.1 via Fabric API + network adapters | Yes |
| `rendezvous` | Self-hostable discovery backend (Rust + Axum, outside Gradle) — see [`rendezvous/README.md`](rendezvous/README.md) | No |

Keeping `core` Minecraft-free means the hard logic (host election, fencing, handshake, registry,
relay) is tested on plain Kotlin + JUnit5 without the heavy Loom/Minecraft toolchain.

## What is real and tested

- **`core` (67 passing tests):**
  - `WorldId` with a copyable Base32 share code; `NodeId`; `Endpoint`; `ClaimToken` (the fencing
    token: `generation → millis → nodeId`).
  - `WorldRegistry` + `InMemoryWorldRegistry` — CAS-on-token publish, TTL expiry, heartbeat refresh.
    `WorldRecordCodec` — compact binary wire encoding of a record (round-trip tested, < 1000 bytes).
  - `HostElection` — split-brain tie-break, ghost detection + fenced takeover (`generation+1`),
    stale-token rejection (rules R1–R14 from the spec). `HeartbeatLivenessProbe` for R10/R11.
  - `handshake` — sealed `Message` set, byte-exact binary `MessageCodec`, host/joiner state
    machines, and `FramedMessageChannel` (length-prefixed messages over a channel).
  - `transport` — `LocalTcpRelay` (transparent TCP byte pump, verified over loopback),
    `DirectTcpTransport`, `SocketChannel`, `ConnectionType` (control/data discriminator byte).
  - `join` — `JoinController`: the guest flow (lookup → control-channel handshake → relay →
    game hand-off), with `GameHandoff` kept Minecraft-free. Validated end-to-end over loopback
    against a stand-in host (connected / should-host / host-unavailable).
  - `host` — `HostController`: the host flow (open → **serve** → publish under a fencing `ClaimToken`
    → heartbeat → withdraw), with `LanOpener`, `EndpointResolver` (the NAT frontier) and
    `ConnectionServer` kept Minecraft-free. `HostConnectionServer` is the real host listener (routes
    CONTROL→handshake, DATA→pipe to the local game). Unit-tested against `InMemoryWorldRegistry`, plus
    a full **end-to-end loopback test** where a real `HostController` serves a real `JoinController`
    (discovery → handshake → byte relay) — the host is no longer a test double.
    `ForwardingEndpointResolver` + `PortForwarder` encode the NAT-traversal invariant that *opening
    the router port is best-effort and must never fail the host* — tested with fakes, so the real
    UPnP adapter stays flagged without risking hosting.
- **`fabric` (compiles, builds the mod jar):**
  - `WorldIdState` (verified 1.21.1 `PersistentState` API) + `WorldIdSidecar` (pre-start `jukz.dat`).
  - Lifecycle wiring (`ServerWorldEvents.LOAD`, `SERVER_STOPPING`) and `HostSession` (host withdrawal).
  - Client join flow wired end-to-end: a **"Play together"** button on the multiplayer screen
    (`ScreenEvents.AFTER_INIT`) opens a short-code prompt → `JoinCoordinator` runs `JoinController`
    off-thread and maps the result to animated status screens (searching / connecting / nobody-
    hosting / error), with `MinecraftGameHandoff` opening the vanilla `ConnectScreen` on success.
  - Auto-host on open: every jukz world is permanently shareable. When a world boots locally,
    `HostCoordinator` (on `ClientPlayConnectionEvents.JOIN`, so the local player already exists — the
    moment `openToLan` needs) bumps the fence and runs `HostController` with `MinecraftLanOpener`
    (real `IntegratedServer.openToLan`, then drops the integrated server to **offline-mode** so guests
    relayed in aren't kicked by Mojang online-auth — jukz authorizes via the world code) and the
    UPnP-opening `ForwardingEndpointResolver`, announcing it so others can join. Silent; `HostSession`
    withdraws on world close. The pause menu gains a **"World info (jukz)"** button
    (`ScreenEvents.AFTER_INIT`, no mixin) opening `HostInfoScreen` (share code + copy, UUID,
    generation, endpoints, live self-check); it takes the vanilla "Open to LAN" slot, or pins itself
    when that button is already gone.
  - Auto-join on open (mixin): the flip side — opening a world first consults discovery. A tiny Java
    `IntegratedServerLoaderMixin` at the head of `IntegratedServerLoader.start` reads the world's
    `jukz.dat` UUID and, via `WorldOpenInterceptor`, looks it up in the shared `Discovery` registry; a
    live host cancels the local boot and joins as a guest, otherwise the world boots locally (and then
    auto-hosts). Joining/hosting is intrinsic to opening a world, never a button.
  - **Real LAN cross-machine discovery** (`LanMulticastWorldRegistry`, the default `Discovery`
    backend): hosts multicast their record to a private group; every node caches what it hears with
    the same token-CAS + TTL fencing. Two Minecraft instances on the same network actually find and
    join each other's worlds today — no server, no NAT. Falls back to in-memory if multicast is blocked.
  - **Internet-wide discovery via a rendezvous server**: `RendezvousWorldRegistry` speaks
    the JSON `/v1` contract of the self-hostable Rust + Axum server in [`rendezvous/`](rendezvous/)
    (90 s leases, heartbeat at TTL/3 derived from the server's announce response, ClaimToken CAS
    replicated server-side, observed-public-IP appended to the announced endpoints). On by default
    against the public instance (`jukz.nuulm.com`); override `rendezvous.url` in
    `config/jukz.properties` to self-host, or set it to `none` for LAN-only.
    `CompositeWorldRegistry` layers it over LAN multicast — same-network play
    keeps working with no internet — and `WorldRecord` now carries an ordered **endpoint candidate
    list** (wire format v2, still decodes v1) that guests dial in order. A rejected announce is no
    longer silent: `SupersededScreen` lets the player keep the local copy or leave and join the live
    host. The per-install `NodeId` is persisted (`config/jukz.nodeid`).
  - `StunClient` — a real, dependency-free RFC 5389 STUN client. `UpnpMapper` — a real, dependency-free
    UPnP IGD client (SSDP + SOAP port-map / external-IP).
  - **Automatic UPnP port-opening on the host path** (`UpnpPortForwarder`, wired via the core
    `ForwardingEndpointResolver`): when a world is hosted, jukz best-effort maps its listen port on
    the router so the rendezvous server's observed-public-IP endpoint is dialable from another
    network — cross-internet play with no manual port-forward where the router supports UPnP. It is
    non-fatal: no UPnP just falls back to LAN-only reach (the SSDP/SOAP round-trip itself is still
    flagged for live-NAT validation).
  - **World handoff over the live connection (F4)** — when a host with a connected guest closes its
    world, it forces a save, snapshots it with JGit (`commit` — excluding Minecraft's locked
    `session.lock`), arms the pack on its connection server under a one-shot gate token, and pushes a
    `HostLeaving` message — carrying that token-gated snapshot offer — to each guest over the **control
    channel that is already open**. This never goes through discovery, so it can't race the
    registry/cache (the earlier discovery-based attempt did, and failed). The guest's `JoinController`
    runs a continuous reader on that channel: a `HostLeaving` (clean handoff) or a broken channel (abrupt
    drop) fires `onHostLost`. The pack streams back over a third connection type (`ConnectionType.SNAPSHOT`)
    on the **same listen port the game uses** — so it crosses NAT exactly like play does, with no second
    port to forward — and the guest pulls from the endpoint it actually reached (the host's advertised
    snapshot host/port may be a LAN address an internet guest can't dial), keeping only the gate token.
    `pullLatest` → channel download → JGit `PackParser` → `git reset --hard` → mirror the generation into
    `jukz.dat`; then `HostHandoffScreen`'s "Host now" opens it locally bypassing the discovery consult —
    auto-hosting with a bumped generation that **fences past the old host**. Proven end-to-end over
    loopback (host serves the pack, guest pulls it and resets) and **in-game, including a round-trip
    A→B→A handoff**. Non-fatal throughout, on the LAN and across the internet (it rides the one NAT
    traversal that already carries play).
  - **Live badge + access control (F4)** — a `WorldEntry` mixin draws a green "Live · N" badge on each
    save currently hosted (player count from the record), behind a per-world 10 s lookup cache; clicking
    the badge joins directly. `HostInfoScreen`'s "Access: Open/Closed" toggle withdraws + kicks guests
    and writes `jukz.access=disabled` to the world's `jukz.properties`; while set, opening the world
    skips the auto-announce. (The snapshot transfer, generation mirroring, cache de-dup, session-lock
    exclusion, and access flag are unit-tested; the badge draw/click and the kick path are
    mixin/Minecraft surfaces still to be validated in-game.)

## What is flagged (`// requires live-network testing`)

These implement the same interfaces but throw `NotImplementedError`, with the exact live API calls
documented in KDoc. They need real machines behind real NATs to validate:

- `StunEndpointResolver` — resolves the host's public, cross-NAT endpoint *client-side* (UPnP IGD
  external IP, STUN fallback). Flagged: it needs a real router/NAT to validate. The rendezvous path
  no longer needs it — the server observes the public IP and `UpnpPortForwarder` opens the port — but
  it remains the right primitive for a serverless path, where there is no server to observe the IP.
- `IceTransport` / `HolePuncher` — symmetric-NAT UDP hole punch, QUIC tunnel, TURN relay (the
  fallback for when UPnP isn't available).

The world-open interception itself is real (`IntegratedServerLoaderMixin` + `WorldOpenInterceptor`),
and the discovery backend it queries is now live: LAN multicast finds same-network hosts, the
rendezvous server finds internet-wide ones, and `UpnpPortForwarder` opens the router port so a
remote guest can actually connect. Only the client-side public-endpoint resolution and the
hole-punch/relay fallback for routers without UPnP remain flagged.

## Build & test

```bash
./gradlew :core:test     # run the deterministic core tests (67 tests)
./gradlew :fabric:test   # run the fabric JUnit tests (snapshot handoff, access flag, badge cache, ...)
./gradlew build          # compile everything + assemble fabric/build/libs/jukz-0.1.0.jar
```

Requires JDK 21. The Gradle wrapper pins Gradle 8.10.1 and Fabric Loom 1.7.

### Testing host ↔ guest on one machine

Two isolated dev clients (separate `runDir`, so separate logs / saves / config / `jukz.nodeid` —
distinct peers) are wired as Loom run configs:

```bash
run-client-a.bat   # gradlew runClientA — instance A (username HostA),  run dir fabric/run/clientA
run-client-b.bat   # gradlew runClientB — instance B (username GuestB), run dir fabric/run/clientB
```

Open a world in A (it auto-hosts; the share code is under pause menu → **World info (jukz)**), then
in B either open a copy of the same world (auto-join) or use **Play together** on the multiplayer
screen with A's code. Both pre-point at the public rendezvous server.

To verify the **handoff**: A opens a world, B joins, A does **Save and Quit**, then B clicks **Host
now** on the prompt — B pulls A's snapshot and takes over (the A↔B generation keeps climbing). The log
lines `handing off — notifying N guest(s)` (host) and `taking over … (snapshot applied)` (guest)
confirm each step.

## Follow-ups (next session)

- **Relay path for non-UPnP / CGNAT hosts — implemented, pending live validation.** A host that cannot
  open a port (no UPnP / CGNAT) registers a WebSocket relay session on the rendezvous and advertises it
  in the record (wire v4 `RelayOffer`); a guest tries the direct endpoints first and falls back to the
  relay (`/v1/relay/{host,connect,work}`), which splices the two outbound streams — so play crosses any
  NAT. core (`ChannelDialer` ladder, codec) + the Rust relay registry are unit/cargo-tested; the WS
  adapters (`WsRelayTransport`/`WsRelayClient`) need a **rendezvous redeploy + two-instance E2E** with
  the direct path forced to fail. Design/plan: `docs/superpowers/{specs,plans}/2026-06-10-*relay*`.
  Once this lands, the flagged `StunEndpointResolver`/`IceTransport`/`HolePuncher` are superseded for
  the non-UPnP case. The F4 snapshot *handoff* now also rides the relay (`JGitWorldSync` pulls over the
  same `DialTarget` play connected on), so a relay-only guest takes over with the host's current world —
  fixing the world desync the first force-relay test hit.
- **In-game validation still pending:** the world-list **live badge** (`WorldEntryMixin` +
  `WorldListLiveBadge`) and the **access-control kick** (`HostCoordinator.disableAccess`) — both are
  mixin/Minecraft surfaces not yet exercised in a live session.
- **Cold-start handoff over the internet:** the live-connection handoff now crosses NAT (the pack rides
  the host's already-forwarded game port via `ConnectionType.SNAPSHOT`). Still open is the *ghost*
  takeover — a guest looking up a world whose host is already gone — because the rendezvous server does
  not relay the snapshot offer in the record. A blob relay through the rendezvous (host uploads, guest
  downloads, both outbound) would close that gap and need no reachability at all.
- **Cleanup:** `JoinCoordinator.recordFor` builds a dummy `WorldRecord` just to reach `pullLatest` —
  give `WorldSync` an offer-based overload instead. Taking over a never-seen world leaves a
  `jukz-<code>` save folder; consider naming/cleanup. The flagged NAT-traversal adapters
  (`StunEndpointResolver` / `IceTransport` / `HolePuncher`) remain for routers without UPnP.

## License

MIT.
