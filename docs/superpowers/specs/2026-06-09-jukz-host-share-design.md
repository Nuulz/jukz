# jukz — Host side & "Play together" (HostController + pause-menu share)

Status: approved 2026-06-09. Builds on the guest join client (spec 2026-06-09-jukz-join-client-design)
and the core protocol heart. This spec covers the **host side**: turning the vanilla "Open to LAN"
pause-menu button into a jukz "Play together" action that opens the world to the network and shows a
copyable share code, backed by a real, unit-tested `HostController` in `core`.

## Motivation

"Open to LAN" only reaches the local network. jukz exists so people in **different countries** can
play the same world together via serverless P2P discovery. The pause-menu button is therefore no
longer "Open to LAN" — it becomes **"Play together"**: open the world, publish a discovery record,
heartbeat it, and surface the share code so anyone can join from anywhere.

## Decisions (locked during brainstorming)

1. **Explicit share, not auto-host.** A singleplayer world stays private until the player clicks
   "Play together". Hosting is opt-in (button-triggered), not automatic on server start.
2. **Button action:** open a real local port via `openToLan` **and** show the copyable code. First
   click hosts; later clicks just reopen the code screen (idempotent). Leaving the world withdraws.
3. **Replace, don't coexist.** The vanilla "Open to LAN" button is replaced in place by
   "Play together"; the vanilla LAN option goes away.
4. **No mixin.** The button is swapped via Fabric `ScreenEvents.AFTER_INIT` on `GameMenuScreen`,
   the same no-mixin pattern `JukzClient` already uses for the title-screen button.
5. **Tested core, flagged net.** Continue the project's "real + tested core / flagged net adapters"
   split. The host orchestration (open → publish → heartbeat → withdraw) is real and unit-tested in
   `core` against `InMemoryWorldRegistry`. Only the publicly-reachable endpoint (NAT/STUN) and the
   live DHT stay flagged; cross-country reachability lands when those are unflagged.

## Architecture

### `core` — `dev.jukz.core.host` (Minecraft-free, unit-tested)

Mirrors `dev.jukz.core.join`. Two frontier interfaces keep Minecraft and NAT out of `core`:

- **`LanOpener`** — `fun open(): Int?`. Opens the local world to the network and returns the bound
  port, or `null` on failure. Real adapter calls `IntegratedServer.openToLan`; the test fake returns
  a fixed port.
- **`EndpointResolver`** — `suspend fun resolve(port: Int): Endpoint`. Resolves the address a guest
  would dial for the locally-bound `port`. This is the NAT frontier: the real-today adapter returns
  the LAN address; the flagged adapter does STUN/UPnP for a public endpoint.

Types:

- **`HostConfig`** — `heartbeatIntervalMs: Long = 60_000` (re-announce well within the registry TTL).
- **`HostResult`** (sealed):
  - `Hosting(shortCode: String, port: Int)` — port open, record published, heartbeat running.
  - `Superseded(current: WorldRecord)` — a live record already holds a `token >= ours`; we did not
    overwrite it (another host owns this world).
  - `Failed(reason: String)` — `LanOpener.open()` returned null, or resolve/publish threw.
- **`HostController(registry, lanOpener, endpointResolver, nodeId, clock, config, onHostLost)`**
  `: AutoCloseable`
  - `suspend fun host(worldId: WorldId, generation: Long): HostResult`
    1. `val port = lanOpener.open() ?: return Failed("could not open local port")`.
    2. `val endpoint = endpointResolver.resolve(port)`.
    3. `val token = ClaimToken(generation, clock.nowMillis(), nodeId)`.
    4. `val record = WorldRecord(worldId, token, endpoint, heartbeatSeq = 0)`.
    5. `registry.publishIfNewer(record)`: `Published` ⇒ start the heartbeat loop, return
       `Hosting(worldId.shortCode(), port)`; `Rejected(current)` ⇒ return `Superseded(current)`.
  - `suspend fun beat(): Boolean` — one heartbeat: advance `heartbeatSeq`, call
    `registry.heartbeat(record')`. Returns false if the host was superseded or the record expired
    (caller should stop). Called by the loop on an interval; **unit-tested directly** so the
    time-based loop itself needs no virtual-clock test.
  - The heartbeat loop is a child coroutine: `delay(heartbeatIntervalMs)` → `beat()`; a false beat
    invokes `onHostLost(worldId)` and stops.
  - `stop()` / `close()` — cancel the loop and `registry.withdraw(worldId, token)` (CAS on token, so
    a newer host is never clobbered).

**Generation:** `ClaimToken.hostGeneration` is the persisted fence, incremented **before** announcing.
The increment is a Minecraft-persistence concern, so `host(...)` takes the already-incremented value;
`core` stays free of `WorldIdState`.

**NodeId:** the host needs a per-install `NodeId` for the token tiebreak. For now it is
`NodeId.random()` per session with a `TODO(persist)` — stable identity only matters once the live DHT
makes cross-restart fencing observable.

### `core` tests — `host/HostControllerTest.kt` (InMemory + FakeClock, loopback style)

1. **Hosting** — `host()` returns `Hosting` with the world's short code and the opener's port;
   `registry.lookup(worldId)` returns the published record with the expected token.
2. **Superseded** — pre-publish a record with a higher token; `host()` returns `Superseded` and the
   existing record is untouched.
3. **Heartbeat keeps it alive** — with a short TTL and `FakeClock`, advance time near the TTL, call
   `beat()`, advance again; `lookup` is still non-null and `heartbeatSeq` advanced.
4. **Beat signals stop** — after a competing host publishes a higher token, `beat()` returns false.
5. **Withdraw** — after `stop()`, `lookup(worldId)` is null.

### `fabric` — wiring

- **`client.MinecraftLanOpener(server) : LanOpener`** — `open()`: if `server.isRemote` (already open),
  return `server.serverPort`; else pick a port (`NetworkUtils.findLocalPort()`), call
  `server.openToLan(gameMode, cheatsAllowed, port)` and return the port on success, null otherwise.
  Defaults: survival game mode, cheats off. Exact 1.21.1 yarn names verified at implementation time;
  `IntegratedServer.openToLan(@Nullable GameMode, boolean, int): boolean` is already verified.
- **`transport.LocalEndpointResolver : EndpointResolver`** — real-today: returns the host's LAN
  address `Endpoint(lanIp, port)` (works on a real LAN). No NAT traversal.
- **`transport.StunEndpointResolver : EndpointResolver`** — **flagged**: `resolve` throws
  `NotImplementedError` with KDoc documenting the STUN reflexive-address + UPnP/TURN mapping calls,
  consistent with `IceTransport`/`HolePuncher`/`UpnpMapper`.
- **`runtime.HostSession`** (replaces the `runtime.HostController` stub) — per-server session holder:
  - `share(server, parent)` — idempotent. If already hosting, reopen `ShareWorldScreen` with the
    cached code. Otherwise: bump the fence via `WorldIdState.get(server.overworld).incrementGeneration()`,
    build a `core.host.HostController` with `MinecraftLanOpener` + `LocalEndpointResolver` +
    `InMemoryWorldRegistry`, show an "Opening world…" status screen, run `host(...)` off the render
    thread, and on `Hosting` show `ShareWorldScreen`; `Superseded`/`Failed` map to an error screen.
  - `onServerStopping(server)` — if hosting, `host.stop()` (withdraw) and reset.
- **`JukzMod`** — `SERVER_STOPPING` → `HostSession.onServerStopping`. `SERVER_STARTED` no longer
  carries a host TODO (hosting is now explicit via the button); it just logs.
- **`client` button** — in `JukzClient.onInitializeClient`, register `ScreenEvents.AFTER_INIT`; when
  the screen is a `GameMenuScreen` and `client.isIntegratedServerRunning()`, find the vanilla button
  whose message equals `Text.translatable("menu.shareToLan")` in `Screens.getButtons(screen)`,
  capture its x/y/width/height, remove it, and add a "Play together" `ButtonWidget` at the same place
  that calls `HostSession.share(server, screen)`.

## Flow

`ESC → "Play together"` → (first time) "Opening world…" → real `openToLan` + publish + heartbeat →
`ShareWorldScreen` with a copyable `JUKZ-…` code → later clicks reopen the code → exit world →
withdraw.

## Error handling

`LanOpener.open()` null ⇒ `Failed("could not open local port")` ⇒ error screen with **Back**.
`Superseded` ⇒ a screen explaining the world is already hosted elsewhere. Resolver/publish exception
⇒ `Failed(reason)`. Every `HostResult` maps to a concrete screen; nothing ever dead-ends.

## Real vs flagged

**Real today (tested in `core`):** the button, `openToLan` (binds a real local port), publish to
`InMemoryWorldRegistry`, heartbeat, the share code, withdraw. **Flagged:** `StunEndpointResolver`
(public NAT endpoint) and `MldhtWorldRegistry` (live DHT). With the in-memory registry there are no
remote readers, so a published record is real but only locally visible — cross-country reachability
arrives when the STUN resolver and DHT registry are unflagged behind these same interfaces.

## Out of scope (unchanged)

Live DHT, real NAT traversal (STUN/hole punch/UPnP/TURN), world-sync pull, hot takeover, and
persisting the per-install `NodeId`.

## New / changed files

- `core`: `host/LanOpener.kt`, `host/EndpointResolver.kt`, `host/HostConfig.kt`, `host/HostResult.kt`,
  `host/HostController.kt`; test `host/HostControllerTest.kt`.
- `fabric`: `client/MinecraftLanOpener.kt`, `transport/LocalEndpointResolver.kt`,
  `transport/StunEndpointResolver.kt`, `runtime/HostSession.kt` (replaces `runtime/HostController.kt`),
  `client/gui/OpeningWorldScreen.kt`; edits to `JukzMod.kt`, `JukzClient.kt`.
