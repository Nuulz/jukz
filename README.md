# jukz

A Minecraft **Fabric 1.21.1** mod (Java 21) that gives every world a permanent UUID and uses a
**serverless P2P DHT** to discover who is currently hosting it. Opening a world asks the network
"is anyone hosting this right now?": if yes, you join that live host as a **guest**; if no, the
world opens locally and is **announced** as the active host. On close, the announcement is
withdrawn. The world effectively lives in one place at a time, with rotating ownership and no
manual coordination.

See [`docs/superpowers/specs/2026-06-08-jukz-design.md`](docs/superpowers/specs/2026-06-08-jukz-design.md)
for the full design rationale (verified against primary sources) and
[`docs/superpowers/plans/2026-06-08-jukz-core.md`](docs/superpowers/plans/2026-06-08-jukz-core.md)
for the implementation plan.

## Module layout

| Module | What | Minecraft? |
|---|---|---|
| `core` | The deterministic protocol heart — pure Kotlin, fully unit-tested | No |
| `fabric` | Wires `core` into Minecraft 1.21.1 via Fabric API + network adapters | Yes |

Keeping `core` Minecraft-free means the hard logic (host election, fencing, handshake, registry,
relay) is tested on plain Kotlin + JUnit5 without the heavy Loom/Minecraft toolchain.

## What is real and tested

- **`core` (38 passing tests):**
  - `WorldId` with a copyable Base32 share code; `NodeId`; `Endpoint`; `ClaimToken` (the fencing
    token: `generation → millis → nodeId`).
  - `WorldRegistry` + `InMemoryWorldRegistry` — CAS-on-token publish, TTL expiry, heartbeat refresh.
  - `HostElection` — split-brain tie-break, ghost detection + fenced takeover (`generation+1`),
    stale-token rejection (rules R1–R14 from the spec). `HeartbeatLivenessProbe` for R10/R11.
  - `handshake` — sealed `Message` set, byte-exact binary `MessageCodec`, host/joiner state
    machines, and `FramedMessageChannel` (length-prefixed messages over a channel).
  - `transport` — `LocalTcpRelay` (transparent TCP byte pump, verified over loopback),
    `DirectTcpTransport`, `SocketChannel`, `ConnectionType` (control/data discriminator byte).
  - `join` — `JoinController`: the guest flow (lookup → control-channel handshake → relay →
    game hand-off), with `GameHandoff` kept Minecraft-free. Validated end-to-end over loopback
    against a stand-in host (connected / should-host / host-unavailable).
  - `host` — `HostController`: the host flow (open → publish under a fencing `ClaimToken` →
    heartbeat → withdraw), with `LanOpener` and `EndpointResolver` (the NAT frontier) kept
    Minecraft-free. Unit-tested against `InMemoryWorldRegistry` (hosting / superseded / heartbeat
    re-announce / loss / withdraw / open-failure / live-status).
- **`fabric` (compiles, builds the mod jar):**
  - `WorldIdState` (verified 1.21.1 `PersistentState` API) + `WorldIdSidecar` (pre-start `jukz.dat`).
  - Lifecycle wiring (`ServerWorldEvents.LOAD`, `SERVER_STOPPING`) and `HostSession` (host withdrawal).
  - Client join flow wired end-to-end: "Join via jukz" → `JoinCoordinator` runs `JoinController`
    off-thread and maps the result to animated status screens (searching / connecting / nobody-
    hosting / error), with `MinecraftGameHandoff` opening the vanilla `ConnectScreen` on success.
    Until the live DHT is wired, a code lookup ends cleanly on the "nobody is hosting" screen.
  - Auto-host on open: every jukz world is permanently shareable. When a world boots locally,
    `HostCoordinator` (on `SERVER_STARTED`) bumps the fence and runs `HostController` with
    `MinecraftLanOpener` (real `IntegratedServer.openToLan`) + `LocalEndpointResolver`, announcing it
    so others can join. Silent; `HostSession` withdraws on world close. The pause-menu "Open to LAN"
    button is replaced (`ScreenEvents.AFTER_INIT`, no mixin) with an informational **"World info
    (jukz)"** opening `HostInfoScreen` (share code + copy, UUID, generation, endpoint, live self-check).
  - Auto-join on open (mixin): the flip side — opening a world first consults discovery. A tiny Java
    `IntegratedServerLoaderMixin` at the head of `IntegratedServerLoader.start` reads the world's
    `jukz.dat` UUID and, via `WorldOpenInterceptor`, looks it up in the shared `Discovery` registry; a
    live host cancels the local boot and joins as a guest, otherwise the world boots locally (and then
    auto-hosts). Joining/hosting is intrinsic to opening a world, never a button. With the in-memory
    registry the lookup is always empty, so worlds open locally and shares are only locally visible.
  - `StunClient` — a real, dependency-free RFC 5389 STUN client.
  - `JGitWorldSync.commit` — real JGit snapshotting.

## What is flagged (`// requires live-network testing`)

These implement the same interfaces but throw `NotImplementedError`, with the exact live API calls
documented in KDoc. They need real machines behind real NATs to validate:

- `MldhtWorldRegistry` — live DHT (BEP44 mutable items) via the8472/mldht (JitPack).
- `IceTransport` / `HolePuncher` / `UpnpMapper` — ICE/STUN hole punch, QUIC tunnel, UPnP, TURN.
- `StunEndpointResolver` — the host's public, cross-NAT endpoint (STUN reflexive address + UPnP/TURN
  mapping). Swapping it in for `LocalEndpointResolver` is what turns a LAN share into a cross-country
  one; everything else in the host flow is already real.
- `JGitWorldSync.pullLatest` — cold-start world transfer over the P2P transport.

The world-open interception itself is real (`IntegratedServerLoaderMixin` + `WorldOpenInterceptor`);
only the shared discovery backend it queries is flagged, so today every world still opens locally.

## Build & test

```bash
./gradlew :core:test     # run the deterministic core tests (30 tests)
./gradlew build          # compile everything + assemble fabric/build/libs/jukz-0.1.0.jar
```

Requires JDK 21. The Gradle wrapper pins Gradle 8.10.1 and Fabric Loom 1.7.

## License

MIT.
