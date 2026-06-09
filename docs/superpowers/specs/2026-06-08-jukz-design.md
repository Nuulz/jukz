# jukz — P2P World-Sync Mod for Minecraft Fabric (1.21.1)

> Design spec. Status: **approved 2026-06-08**. Target: Minecraft 1.21.1, Java 21, Fabric.
> All source code is written in English; this spec is the design record.

## 1. Concept

Every world created with jukz gets a permanent UUID. When a player opens that
world, the mod first asks a serverless P2P DHT whether someone else is currently
hosting it. If yes, the player joins that live host as a **guest**. If no, the
world opens locally and is **announced** as the active host. On close, the
announcement is withdrawn. Net effect: the world "exists" in one place at a time,
with rotating ownership and zero manual coordination.

## 2. Viability verdict (the honest framing)

Three layers must be kept distinct:

1. **Discovery** — *who hosts worldID right now?* The DHT solves this with **no own
   infrastructure**.
2. **Reachability / transport** — *can I actually reach that ip:port behind NAT?*
   The DHT does **not** solve this. Needs hole punching; ~5-8% of peer pairs
   (symmetric NAT / CGNAT) need *some* relay. There is no anonymous free TURN
   anymore, so "zero infrastructure" holds for the majority, not 100%.
3. **Canonical state** — *which world copy is the good one?* A monotonic
   generation counter orders copies but **cannot merge** two concurrent offline
   sessions; it can only order them.

**Cross-cutting decision:** every network-dependent concern lives behind an
interface (`WorldRegistry`, `Transport`, `WorldSync`) so the deterministic core
(election, tokens, handshake, relay) is fully unit-testable without a network.

**Language:** Kotlin + `fabric-language-kotlin`. Coroutines (`withTimeout`,
structured concurrency, `Deferred`) genuinely simplify DHT lookup / hole-punch /
handshake orchestration with timeouts; `data class` / `sealed class` make
`ClaimToken` and the handshake message set clean.

## 3. Architecture / modules

| Package | Responsibility | Depends on |
|---|---|---|
| `dev.jukz` | `JukzMod` (common init), `JukzClient` (client init) | Fabric |
| `dev.jukz.world` | WorldID + generation counter, NBT persistence, lifecycle hooks | Fabric, NBT |
| `dev.jukz.discovery` | `WorldRegistry` (DHT interface): publish/lookup/withdraw + CAS+TTL | network (abstracted) |
| `dev.jukz.election` | `ClaimToken`, `HostElection`, fencing, state machine | discovery, clock |
| `dev.jukz.handshake` | HELLO/CLAIM/YIELD/REDIRECT/PING/PONG/ACK/NACK + codec + state machines | — (pure) |
| `dev.jukz.transport` | `Transport` + `LocalTcpRelay` (byte pump) + Ice/Relay (flagged) | network |
| `dev.jukz.sync` | `WorldSync` (JGit): commit/pull + generation guard | JGit |
| `dev.jukz.net` | NAT: STUN, hole punch, UPnP (flagged) | ice4j |
| `dev.jukz.client.gui` | state screens | Fabric client |
| `dev.jukz.util` | `JukzClock` (injectable), bytes, logging | — |

### Key interfaces

```kotlin
interface WorldRegistry {
    /** CAS publish: succeeds only if no record exists or candidate token > existing token. */
    suspend fun publishIfNewer(record: WorldRecord): PublishResult
    suspend fun lookup(worldId: UUID): WorldRecord?
    suspend fun withdraw(worldId: UUID, token: ClaimToken)
}

interface Transport {
    suspend fun connect(endpoint: Endpoint): JukzChannel
    suspend fun listen(): ListeningTransport      // returns reachable Endpoint(s)
}

interface WorldSync {
    fun currentGeneration(saveDir: Path): Long
    suspend fun commit(saveDir: Path, generation: Long): CommitId
    suspend fun pullLatest(saveDir: Path, target: WorldRecord)
}
```

## 4. Design answers (the 7 questions)

### 4.1 General architecture
- **UUID lives in two places:** (a) `PersistentState` on the overworld
  (`getPersistentStateManager().getOrCreate(Type, "jukz_world_id")`) — canonical,
  atomic with MC's save; (b) a **sidecar `<save>/jukz.dat`** (NBT: UUID +
  `generation`) readable by plain Java IO **before** the integrated server starts,
  needed for "ask the DHT before opening the world".
- 1.21.1 API (**verified**): `getOrCreate(PersistentState.Type<T>, String id)` with
  `Type(Supplier, BiFunction<NbtCompound, RegistryWrapper.WrapperLookup, T>,
  DataFixTypes)` — different from 1.21.5+ (`PersistentStateType` / single-arg).

### 4.2 Discovery P2P
- **Engine:** BitTorrent Mainline DHT (Kademlia) via **`the8472/mldht`** (pure
  Java, MPL-2.0), behind `WorldRegistry`.
- **Why not jlibtorrent** (better maintained): native per-OS binary + not on Maven
  Central (ships from FrostWire's repo) → packaging friction for a cross-platform
  mod. mldht is pure Java, supports BEP5 + BEP44 (Ed25519 mutable items). Con:
  dormant (last commit 2021) via JitPack — acceptable because the Mainline
  protocol is stable and the interface makes swapping trivial.
- **Record:** `worldID → BEP44 mutable item`, Ed25519 key derived deterministically
  from `worldID + secret` (non-enumerable). Value (<1000 B):
  `{nodeId, hostGeneration, endpoint, claimEpochMillis, heartbeatSeq}`, signed.
  BEP5 `announce_peer`/`get_peers` as fallback discovery channel.
- **Bootstrap:** `dht.transmissionbt.com:6881`, `dht.libtorrent.org:25401`
  (router.bittorrent.com / router.utorrent.com are dead/DNS-poisoned in 2024-2026).
  Cache good nodes to disk for warm restarts; resolve via DoH.
- **Announce/retire + crash:** re-publish every ~10 min (inside the 10-min token
  window, well under BEP44's ~2h expiry). On crash, **no explicit retire needed** —
  the item expires in ~2h. Joiners therefore **never** treat the DHT as
  authoritative: always liveness-ping, fall back on failure.

### 4.3 NAT traversal
- **Critical fact (verified):** MC is TCP; "Open to LAN" opens the integrated
  server on a local TCP port; the vanilla client only does outbound TCP. Bridge =
  a **transparent local TCP relay**: guest runs a proxy on `127.0.0.1:p`, vanilla
  client connects there, bytes are tunneled to the host. **Works with
  online-mode=true** because MC's encryption and the `hasJoined` serverId hash are
  end-to-end and exclude IP/hostname (same model as TCPShield). The relay is a dumb
  byte pump — zero protocol reimplementation.
- **Fallback ladder:**
  1. UPnP / NAT-PMP (ice4j bundles `weupnp`) — free direct connectivity.
  2. ICE + UDP hole punching (ice4j, RFC 5389, Google STUN) — ~92-96% direct.
  3. Reliable transport over the punched UDP path: **QUIC** via
     `io.netty:netty-codec-native-quic` (mainline Netty 4.2; the incubator artifact
     is archived). Pure-Java fallback: KCP/ARQ.
  4. TURN relay (~5-8% symmetric/CGNAT). **Pluggable & optional** (user-supplied or
     community creds) — no anonymous free TURN exists anymore.
  5. Last resort: community tunnel (e4mc-style) or just host locally.
- **ICE signaling** rides the DHT (natural low-bandwidth rendezvous).
- ⚠️ Shade/relocate ice4j and netty-quic to avoid classpath clashes with MC's own
  Netty and other mods.

### 4.4 Race conditions
- **Model:** lease + monotonic fencing token, with **resource-side enforcement**
  (Kleppmann; client-only token checks do not guarantee mutual exclusion — verified).
- **Token (lexicographic, higher wins):**
  `T = (hostGeneration: u64, claimEpochMillis: i64, nodeId: 16 bytes)`.
  `hostGeneration` is persisted in the save and `+1` on every host-open **before**
  announcing — it is the fence. `claimEpochMillis` is tiebreak only. `nodeId`
  (128-bit per install) is the final deterministic tiebreak.
- **Two simultaneous hosts (R1-R6):** each CAS-publishes its token; waits
  `ANNOUNCE_SETTLE_WINDOW=2s`; re-reads; lower token **yields & redirects** to the
  winner. If two sockets coexist due to DHT lag, the lower-token host detects the
  higher token during the handshake and `YIELD`s (the socket is the fenced
  resource, R14). Determinism: a true tie needs identical generation+millis+nodeId
  → impossible across two installs.
- **Ghost host (R8-R13):** joiner connects (`CONNECT_ATTEMPT_TIMEOUT=4s`); ghost if
  refused/timeout, or if `heartbeatSeq` does not advance within
  `LIVENESS_TIMEOUT=15s` (=3× `HEARTBEAT_INTERVAL=5s`). Safe takeover: publish with
  `hostGeneration = ghost.generation + 1` via CAS; a revived ghost sees a higher
  generation and **self-demotes**. Liveness is confirmed by `heartbeatSeq` advance,
  **not** by connect failure (avoids NAT false positives → real split-brain).
- All R1-R14 rules + timeouts are written as direct unit tests.

### 4.5 State synchronization
- **JGit** (`org.eclipse.jgit` 7.x, EDL-1.0, Java 17+) versions the save dir, behind
  `WorldSync`. Clean close: flush → `generation++` → commit → publish
  `(generation, commitHash, endpoint)`. Cold start: `clone/pull` until
  `generation_local == generation_DHT` **before** becoming host.
- JGit > raw P2P copy: history, incremental transfer, atomic refs, anchor for
  generation. Con: `.mca` are large binaries (weak git delta) → shallow/single-branch
  clones, aggressive `gc`, commit only after world flush.
- **Stale-overwrite guard:** monotonic generation rule — a node may become host only
  if `generation_local ≥ max(generation_DHT)`. Limitation: a scalar orders but can't
  detect concurrent forks. v1 policy: strict single-writer via the DHT lock; the
  `nodeId` in the record lets jukz *detect* (not merge) a duplicated-folder fork and
  warn the user instead of silently losing data.

### 4.6 UX & config
- **Share worldID:** short copyable code from the UUID (Base32 grouped
  `JUKZ-XXXX-XXXX-XXXX`) via a "Share world" button. QR deferred (YAGNI).
- **Screens:** `SearchingHostScreen`, `ConnectingScreen` (shows NAT path),
  `NatErrorScreen` (retry / host local / paste TURN creds), `ShareWorldScreen`.
  Inject "Join via jukz" with `ScreenEvents.AFTER_INIT` (no mixin).
- **No internet:** early detection (DHT bootstrap fails) → clear message + fallback
  to normal local host. Never block play; re-announce when network returns.

### 4.7 Project structure
- Packages per §3; modid `jukz`, group `dev.jukz`.
- **Gradle deps (verified):** fabric-loom 1.7-SNAPSHOT, minecraft 1.21.1, yarn
  `1.21.1+build.3:v2` (build.4 is 404), fabric-loader 0.16.x, fabric-api
  `0.116.12+1.21.1` (highest for 1.21.1), fabric-language-kotlin; mldht (JitPack),
  ice4j 3.2-x (shaded), netty-codec-native-quic (shaded), JGit 7.x (included);
  Java 21, Gradle 8.8+.
- **Fabric hooks:** `ServerWorldEvents.LOAD` (overworld → WorldID/generation),
  `ServerLifecycleEvents.SERVER_STARTED` (advertise) / `SERVER_STOPPING` (withdraw —
  **not** `CLIENT_STOPPING`, which would leak records on exit-to-menu),
  `ScreenEvents.AFTER_INIT` (button), and a **client mixin** on the singleplayer
  play-world path to consult the DHT **before** starting the server.

## 5. Control flow

```
On "play" of a jukz world:
1. Read worldID + generation from jukz.dat (no server start)
2. DHT.lookup(worldID)            -- SearchingHostScreen
3a. Live + reachable host (liveness ping OK):
      -> do NOT start local server
      -> Transport.connect(host) -> LocalTcpRelay 127.0.0.1:p
      -> ConnectScreen.connect(..., "127.0.0.1:p")  => GUEST
3b. No live host (or ghost):
      -> if generation_local < generation_DHT: WorldSync.pull()  (cold start)
      -> openToLan(gameMode, cheats, p)            [signature verified]
      -> generation++ (persist BEFORE announce) -> CLAIM with fencing
      -> settle window -> confirm HOST -> heartbeats
4. Close (SERVER_STOPPING):
      -> generation++ -> WorldSync.commit() -> DHT.withdraw() + publish final generation
```

## 6. Handshake protocol

Messages (each carries `worldID`, sender `ClaimToken`, 32-bit `nonce`):
`HELLO{role}`, `CLAIM`, `PING`/`PONG{heartbeatSeq}`, `REDIRECT{endpoint}`,
`YIELD{winnerToken}`, `ACK`, `NACK{reason: STALE_TOKEN|WRONG_WORLD|BAD_PROTO|NOT_HOST}`.

- **Joiner FSM:** DISCOVER → CONNECTING → VERIFY → JOINING → CONNECTED;
  on NACK(STALE)/REDIRECT follow new endpoint; on timeout → GHOST → TAKEOVER → HOST.
- **Host FSM:** LISTENING → on HELLO reply CLAIM(own token); on inbound token strictly
  higher → YIELD + shut socket; on PING → PONG(heartbeatSeq).
- **R14:** any node receiving a token with lower `hostGeneration` than its own
  authoritative generation replies NACK(STALE_TOKEN) and ignores the request.

## 7. Session scope ("real core")

**Real + tested (deterministic, no network):**
- WorldID + generation (`PersistentState` 1.21.1 + sidecar `jukz.dat`).
- `ClaimToken` + full `HostElection` (R1-R14, fencing, ghost takeover).
- Handshake: sealed messages + binary codec + joiner/host FSMs.
- `WorldRegistry` interface + `InMemoryWorldRegistry` (CAS+TTL fake for tests).
- `LocalTcpRelay` (real byte pump, testable over loopback sockets).
- Canonical generation guard.
- Fabric event wiring + basic screens.
- JUnit tests: election/tiebreak, fencing, ghost detection, handshake codec, relay,
  registry CAS.

**Coded against real libs but flagged `// TODO: requires live-network testing`:**
- `MldhtWorldRegistry` (real BEP44), `IceTransport`/STUN/hole-punch, QUIC/relay
  tunnel, `JGitWorldSync`.

## 8. Verified facts (primary sources)

| Fact | Source |
|---|---|
| `IntegratedServer.openToLan(@Nullable GameMode, boolean cheatsAllowed, int port): boolean` | yarn-1.21.1+build.1 javadoc |
| `PersistentStateManager.getOrCreate(PersistentState.Type<T>, String id)`; `Type(Supplier, BiFunction<NbtCompound,WrapperLookup,T>, DataFixTypes)` (1.21.1; changed at 1.21.5) | yarn-1.21.1+build.3 javadoc |
| yarn `1.21.1+build.3` highest (build.4 = 404); fabric-api `0.116.12+1.21.1` highest | meta.fabricmc.net, maven.fabricmc.net |
| MC encryption end-to-end; `hasJoined` serverId hash excludes IP/host → transparent relay works with online-mode=true | minecraft.wiki Encryption; TCPShield docs |
| BEP44 ~2h expiry, re-put hourly, 1000-byte value, Ed25519 mutable + seq/CAS | bittorrent.org BEP44 |
| Bootstrap: transmissionbt/libtorrent live; bittorrent/utorrent dead/poisoned | transmission#8664, #7176; libtorrent blog |
| Fencing tokens need resource-side enforcement | Kleppmann 2016; Hochstein 2025 |
| ~92-96% NAT pairs direct, ~4-8% relayed | ZeroTier NAT report |
| mldht pure-Java BEP5+BEP44 MPL-2.0 (dormant); jlibtorrent native, not on Maven Central | github the8472/mldht; frostwire-jlibtorrent |
| ice4j 3.2-x active, Java 11 target, bundles weupnp | github jitsi/ice4j |
| JGit 7.x EDL-1.0, Java 17+ | mvnrepository; eclipse.org |

## 9. Top risks

- jlibtorrent native packaging avoided by choosing mldht; mldht dormancy mitigated by
  interface + stable protocol.
- Bootstrap fragility → multiple nodes + on-disk node cache + DoH.
- Symmetric/CGNAT (~5-8%) needs relay → pluggable optional relay tier.
- JGit bloat on `.mca` → shallow clones + gc + flush-before-commit.
- Scalar generation can't detect concurrent forks → single-writer policy + fork
  detection/warn via nodeId.
- Netty version skew (MC's Netty vs netty-quic) and classpath clashes → shading.
- `hostGeneration` rollback (backup restore / duplicated save folder) is THE safety
  invariant; bind nodeId into the record to detect it.

## 10. Implementation order

1. Project scaffold (build.gradle, fabric.mod.json, package skeleton, gradle wrapper).
2. WorldID system (generation, NBT persistence, sidecar, read).
3. `WorldRegistry` interface + `InMemoryWorldRegistry` + `WorldRecord`/`ClaimToken`.
4. Handshake (messages, codec, FSMs) + tests.
5. `HostElection` (R1-R14, fencing, ghost takeover) + tests.
6. `LocalTcpRelay` + `Transport` interface + tests.
7. NAT module skeleton (STUN/hole-punch/UPnP) + DHT adapter skeleton (mldht) + JGit
   sync skeleton — all flagged for live testing.
8. Fabric wiring (events, client mixin) + state screens.
9. Final test pass + build verification.
