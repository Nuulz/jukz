# jukz — Guest join client (JoinController + loopback demo)

Status: approved 2026-06-09. Builds on the core protocol heart (election/handshake/registry/relay)
already implemented and tested. This spec covers the **guest side**: turning the existing dead UI
mockup into a working join flow that is fully exercised over loopback, plus the real Minecraft
hand-off and a presentable status UI.

## Goal

When a player enters a world share code (or, later, opens a local world that is being hosted
elsewhere), the mod must: look the world up in the registry, validate the announced host is alive
and legitimate (jukz handshake / fencing), establish a transport, expose it locally, and hand the
vanilla Minecraft client off to that local endpoint — with clear visual feedback at every step so
a failure never looks like a crash.

Everything except the final "tell Minecraft to connect" hop is validated end-to-end over loopback
with no Minecraft runtime, using the real `InMemoryWorldRegistry`, `LocalTcpRelay`,
`JoinerStateMachine`/`HostStateMachine` and `MessageCodec`.

## Decisions (locked during brainstorming)

1. **Hand-off reaches the real Minecraft connect** (`ConnectScreen`/`ServerInfo`), placed behind a
   `GameHandoff` interface so the orchestration is testable with a fake; the real adapter is
   validated by running the game.
2. **Separate control and data channels.** A persistent control channel carries the handshake plus
   `Ping`/`Pong` liveness; data channels are opened per-connection by the relay as pure byte pipes.
3. **Ghost host → `ShouldHost`.** When the announced host proves to be a ghost (the joiner state
   machine emits `Takeover`), the controller terminates with a `ShouldHost(worldId)` result. The
   actual flip-to-host belongs to the host loop and is out of scope here; the caller decides.
4. **`JoinController` lives in `core`** (Minecraft-free, unit-tested with the light toolchain). Only
   the real `MinecraftGameHandoff` and the screen wiring live in `fabric`.

## Architecture

### `core` — `dev.jukz.core.join`
- `GameHandoff` — frontier interface: `fun connect(host: String, port: Int)`. No Minecraft types.
- `JoinResult` (sealed):
  - `Connected(host: String, port: Int)` — relay up, game handed off.
  - `HostUnavailable` — no live record in the registry.
  - `ShouldHost(worldId: WorldId)` — announced host is a ghost; caller should host locally.
  - `Failed(reason: String)` — transport/handshake error.
- `JoinConfig` — timeouts: `connectMs`, `handshakeMs`, `livenessIntervalMs`, `livenessTimeoutMs`.
- `JoinController(registry, transport, gameHandoff, clock, config)` — the orchestrator
  (Approach A: a `suspend fun join(worldId): JoinResult` running the flow sequentially, with a
  child coroutine for liveness after connect). Exposes `close()` to tear down relay + control.

### `core` — new protocol pieces
- `dev.jukz.core.transport.ConnectionType` — a one-byte connection discriminator written as the
  first byte of every jukz connection: `CONTROL = 0x01`, `DATA = 0x02`. The host (test double now,
  real host later) reads it and routes: CONTROL → jukz handshake; DATA → transparent pipe to the
  LAN-opened Minecraft server. Helpers to write/read the byte over a `JukzChannel`.
- `dev.jukz.core.handshake.FramedMessageChannel` — `MessageCodec` serialises one `Message` but does
  not delimit it on a stream. This wraps a `JukzChannel` with length-prefixed framing
  (`int32 length + encoded bytes`) and offers `send(Message)` / `receive(): Message`. Round-trip
  tested independently.

### `fabric` — `dev.jukz.client`
- `MinecraftGameHandoff : GameHandoff` — on the client thread
  (`MinecraftClient.execute { ... }`) opens the vanilla connect screen targeting
  `127.0.0.1:<port>` (the relay). Exact 1.21.1 yarn names verified at implementation time.
- `JoinCoordinator` — launches the controller off the render thread, and translates each emitted
  state / `JoinResult` into a screen transition (see UI below). Owns cancellation.

## Control-channel flow

1. `registry.lookup(worldId)` → `null` ⇒ `HostUnavailable`.
2. `transport.connect(record.endpoint)`; write `CONTROL` byte; wrap in `FramedMessageChannel`.
3. `sm = JoinerStateMachine(worldId, record.token, record.endpoint)`; `sm.begin()`.
4. Send `Hello(worldId, record.token, nonce, JOINER)` — the joiner echoes `record.token`, so a
   healthy host neither yields (token not strictly higher) nor NACKs stale (same generation).
5. `receive()` → `sm.onMessage(...)`:
   - `Connect(endpoint)` ⇒ go to hand-off.
   - `FollowRedirect(endpoint)` ⇒ reconnect control to the new endpoint, repeat from step 2.
   - `Takeover` (NACK `NOT_HOST`/`STALE_TOKEN`, or handshake timeout) ⇒ `ShouldHost(worldId)`.

## Hand-off, data, liveness

- On `Connect`: build `LocalTcpRelay(openRemote = { transport.connect(endpoint).also { writeByte(DATA) } })`;
  `port = relay.start()`.
- `gameHandoff.connect("127.0.0.1", port)`; `sm.markConnected()` ⇒ `Connected("127.0.0.1", port)`.
- Liveness monitor: a child coroutine sends `Ping` every `livenessIntervalMs` over the control
  channel and awaits `Pong`; no `Pong` within `livenessTimeoutMs` logs/emits a "host lost" event.
  Hot takeover is out of scope — the monitor only observes. `close()` tears down relay + control.

## Error handling

`transport.connect` failure / connect timeout ⇒ `Failed`. Malformed framed message ⇒ `Failed`.
Invalid share code is already rejected by `JoinPromptScreen` before the controller runs. Every
`JoinResult` maps to a concrete screen.

## UI — status screens must read as intentional, never as a crash

The current `JukzStatusScreen` only draws centred title + message text on a blank screen, which is
indistinguishable from a hang. Rework it so every state is clearly a deliberate jukz screen:

- **`JukzStatusScreen` base:** render the standard panorama/blurred background (so it sits in the
  menu visual language), a "jukz" brand header, a primary status line, and an optional secondary
  detail line. Drives an **indeterminate progress indicator** animated from screen ticks (an
  animated `Searching…`→`Searching..`→`Searching.` ellipsis plus a moving indeterminate bar), so
  the player always sees motion = "working, not frozen".
- **State styling / accent colour:** searching = neutral, connecting = blue/info, error = red,
  should-host = amber call-to-action. The header icon/colour reflects the state.
- **`SearchingHostScreen` / `ConnectingScreen`:** spinner + status text + a **Cancel** button that
  aborts the join (`JoinCoordinator.cancel()`) and returns to the previous screen.
- **`NatErrorScreen` (and generic failure):** red header, human-readable reason, **Back** and (where
  it makes sense) **Retry** buttons — not a dead-end.
- **`ShouldHost` outcome:** a screen that explains "nobody is hosting this world right now — open it
  yourself?" with a clear primary action, instead of a silent failure.

Animation reads from the screen's own tick counter / `System.currentTimeMillis()`; no Minecraft
types leak into `core`. These screens are validated by running the game (no automated UI test).

## Testing (all in `core/src/test`, following the `LocalTcpRelayTest` loopback pattern)

`LoopbackTestHost`: a `ServerSocket` on `127.0.0.1:0`; per connection it reads the type byte —
CONTROL drives a `HostStateMachine` (`Hello`→`Claim`, `Ping`→`Pong`); DATA echoes bytes.

`JoinControllerLoopbackTest`:
- **Connected** end-to-end: live host → handshake → relay up → bytes echo back through
  `127.0.0.1:port`; a `FakeGameHandoff` captures the host/port it was handed.
- **ShouldHost**: host NACKs `STALE_TOKEN` / stays silent past `handshakeMs` → `ShouldHost`.
- **HostUnavailable**: empty `InMemoryWorldRegistry` → no network touched.

`FramedMessageChannelTest`: round-trips a representative `Message` set over a loopback socket pair.

## Out of scope (unchanged from prior flags)

Real DHT (`MldhtWorldRegistry`), real NAT transport (`IceTransport`/hole punch/UPnP), world sync
pull, the host loop (`openToLan` + announce + heartbeat), and hot takeover. The controller selects
its `Transport`/`WorldRegistry` by interface, so swapping the loopback fakes for the live adapters
is additive.

## New files

- `core`: `join/GameHandoff.kt`, `join/JoinResult.kt`, `join/JoinConfig.kt`, `join/JoinController.kt`,
  `transport/ConnectionType.kt`, `handshake/FramedMessageChannel.kt`;
  tests `join/JoinControllerLoopbackTest.kt`, `handshake/FramedMessageChannelTest.kt`.
- `fabric`: `client/MinecraftGameHandoff.kt`, `client/JoinCoordinator.kt`; rework of
  `client/gui/JukzStatusScreen.kt`, `SearchingHostScreen.kt`, `ConnectingScreen.kt`,
  `NatErrorScreen.kt`, and a should-host screen; wire `JoinPromptScreen` → `JoinCoordinator`.
