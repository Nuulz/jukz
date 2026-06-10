# jukz

A Minecraft **Fabric 1.21.1** mod (Java 21) that gives every world a permanent UUID and uses a
**rendezvous server + LAN multicast** (DHT is future work) to discover who is currently hosting it.
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

- **`core` (63 passing tests):**
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
    join each other's worlds today — no DHT, no NAT. Falls back to in-memory if multicast is blocked.
  - **Internet-wide discovery via a rendezvous server** (no DHT): `RendezvousWorldRegistry` speaks
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
  - `JGitWorldSync.commit` — real JGit snapshotting. **Present but not wired**: the current model is
    live-host (a guest plays on the host's world, no save sync), so Git-based save replication is
    intentionally out of the active path.

## What is flagged (`// requires live-network testing`)

These implement the same interfaces but throw `NotImplementedError`, with the exact live API calls
documented in KDoc. They need real machines behind real NATs to validate:

- `MldhtWorldRegistry` — internet-scale DHT discovery (BEP44 mutable items) via the8472/mldht +
  `net.i2p.crypto:eddsa`. The exact mldht call sequence (Ed25519 key derived from the `WorldId`, node
  bootstrap, `GetLookupTask`/`PutTask`, `GenericStorage.buildMutable`, the `WorldRecordCodec` value)
  is reverse-engineered and documented in the file as a concrete blueprint — left flagged rather than
  shipped blind because a real DHT round-trip can't be validated without live nodes.
- `StunEndpointResolver` — resolves the host's public, cross-NAT endpoint *client-side* (UPnP IGD
  external IP, STUN fallback). Flagged: it needs a real router/NAT to validate. The rendezvous path
  no longer needs it — the server observes the public IP and `UpnpPortForwarder` opens the port — but
  it remains the right primitive for the DHT registry, where there is no server to observe the IP.
- `IceTransport` / `HolePuncher` — symmetric-NAT UDP hole punch, QUIC tunnel, TURN relay (the
  fallback for when UPnP isn't available).
- `JGitWorldSync.pullLatest` — cold-start world transfer. Out of scope for the current live-host
  model (a guest joins the host's world live, so it never needs a copy). The trade-off: a guest with
  no local copy of a world can't take over hosting once the host leaves — that would need save
  transfer, which this model deliberately drops.

The world-open interception itself is real (`IntegratedServerLoaderMixin` + `WorldOpenInterceptor`),
and the discovery backend it queries is now live: LAN multicast finds same-network hosts, the
rendezvous server finds internet-wide ones, and `UpnpPortForwarder` opens the router port so a
remote guest can actually connect. Only the `MldhtWorldRegistry` (serverless DHT) path and the
hole-punch/relay fallback for routers without UPnP remain flagged.

## Build & test

```bash
./gradlew :core:test     # run the deterministic core tests (63 tests)
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

## License

MIT.
