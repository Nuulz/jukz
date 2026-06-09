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

- **`core` (30 passing tests):**
  - `WorldId` with a copyable Base32 share code; `NodeId`; `Endpoint`; `ClaimToken` (the fencing
    token: `generation → millis → nodeId`).
  - `WorldRegistry` + `InMemoryWorldRegistry` — CAS-on-token publish, TTL expiry, heartbeat refresh.
  - `HostElection` — split-brain tie-break, ghost detection + fenced takeover (`generation+1`),
    stale-token rejection (rules R1–R14 from the spec). `HeartbeatLivenessProbe` for R10/R11.
  - `handshake` — sealed `Message` set, byte-exact binary `MessageCodec`, host/joiner state machines.
  - `transport` — `LocalTcpRelay` (transparent TCP byte pump, verified over loopback),
    `DirectTcpTransport`, `SocketChannel`.
- **`fabric` (compiles, builds the mod jar):**
  - `WorldIdState` (verified 1.21.1 `PersistentState` API) + `WorldIdSidecar` (pre-start `jukz.dat`).
  - Lifecycle wiring (`ServerWorldEvents.LOAD`, `SERVER_STARTED/STOPPING`) and `HostController`.
  - Client UI: "Join via jukz" title-screen button + status screens (searching / connecting /
    NAT error / share / join-by-code).
  - `StunClient` — a real, dependency-free RFC 5389 STUN client.
  - `JGitWorldSync.commit` — real JGit snapshotting.

## What is flagged (`// requires live-network testing`)

These implement the same interfaces but throw `NotImplementedError`, with the exact live API calls
documented in KDoc. They need real machines behind real NATs to validate:

- `MldhtWorldRegistry` — live DHT (BEP44 mutable items) via the8472/mldht (JitPack).
- `IceTransport` / `HolePuncher` / `UpnpMapper` — ICE/STUN hole punch, QUIC tunnel, UPnP, TURN.
- `JGitWorldSync.pullLatest` — cold-start world transfer over the P2P transport.
- The client-side mixin that intercepts "open existing local world" to consult the DHT first.

## Build & test

```bash
./gradlew :core:test     # run the deterministic core tests (30 tests)
./gradlew build          # compile everything + assemble fabric/build/libs/jukz-0.1.0.jar
```

Requires JDK 21. The Gradle wrapper pins Gradle 8.10.1 and Fabric Loom 1.7.

## License

MIT.
