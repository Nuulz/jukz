# jukz — `WorldListScreen` (a "jukz worlds" picker for `TitleScreen`)

> Design spec. Status: **draft 2026-06-09**. Target: Minecraft 1.21.1, Java 21, Fabric.
> All source code is written in English; this spec is the design record.

## 1. Concept

A second button on `TitleScreen` — **"jukz worlds"** — opens a new screen that
lists every save under `saves/` that already carries a `jukz.dat` (i.e. every
world the player has opened at least once with jukz installed). For each row, the
screen queries discovery (`Discovery.registry.lookup`) and shows whether the
world is currently being hosted — by **me** (this client), by **another peer**, or
by **no one** — plus a contextual action button: **Open locally**, **Join**, or
**Open**.

This is the surface the user is missing today: a way to glance at all their
jukz worlds, see at a glance which ones are online somewhere, and pick the
right one without first opening a save blindly. It is a read-mostly screen
(only one action per row, plus global Refresh / Back); it is not a world
manager, not a settings panel, and it does not touch the live host state.

It does not change the always-on auto-host model: opening a world (whether from
here or from vanilla singleplayer) still consults discovery and auto-hosts
locally if no live host is announced. The screen is a **picker** in front of
that flow, not a replacement for it.

### Why now

`WorldIdSidecar.read` and `WorldRegistry.lookup` are already real, exercised, and
trivial to compose. The screen is a thin presentation layer that turns data the
mod already collects at runtime (saved on disk + the live record we publish to /
read from) into a single visible surface. It uses zero new core machinery.

## 2. Out of scope (YAGNI)

- **Vanilla saves** (saves without `jukz.dat`) are ignored silently. No "adopt"
  flow, no mixed list.
- **Per-row details panel** (endpoint, host generation, heartbeat sequence,
  nodeId) — not in v1. The status word + a colored dot is enough; the
  diagnostics-rich view already lives in `HostInfoScreen` for the currently
  loaded world.
- **"Copy code" button per row** — also v2. Same `WorldId.shortCode()` is
  exposed; we can add a button later without changing the row layout.
- **"Take over"** (fenced takeover of a live world owned by another peer) —
  would require a new core action. Not visual; deferred.
- **Auto-refresh** when discovery changes — the screen does not subscribe to
  the registry. The user hits Refresh, or closes and reopens. A
  `WorldRegistry.observer` hook can be added later without changing this UI.
- **Display name beyond the folder name** — `jukz.dat` is intentionally minimal
  (UUID + generation). No mutable display name in v1.

## 3. UX

### 3.1 Entry point

A new button is added to `TitleScreen` by the existing
`ScreenEvents.AFTER_INIT` hook in `JukzClient.onInitializeClient`. It is placed
**directly below** the existing "Join via jukz" button, with the same dimensions
(200×20) and the same x-centre. Label: literal string `"jukz worlds"`.

The two jukz buttons stack vertically; spacing follows the vanilla `ButtonList`
gap (the existing button is at `scaledHeight / 4 + 96`, so the new one is at
`scaledHeight / 4 + 120`). Both jukz buttons stay above the vanilla
"Singleplayer" / "Multiplayer" buttons; no vanilla widget is moved or hidden.

### 3.2 Empty state

If scanning `savesDirectory` finds zero worlds with a valid `jukz.dat`, the
screen shows a single centred message:

> No jukz worlds yet. Open a world from singleplayer and jukz will give it a
> permanent identity.

No buttons other than **Back** in the empty state.

### 3.3 Non-empty layout

The screen is a vertical list. Each row is exactly:

```
┌─────────────────────────────────────────────────────────────────────┐
│  My survival base                              ● live         [Join]  │
│  JUKZ-AB12-CD34-EF56-7890                                            │
├─────────────────────────────────────────────────────────────────────┤
│  Hardcore realm                                 ● live         [Join]  │
│  JUKZ-9988-7766-5544-3322                                            │
├─────────────────────────────────────────────────────────────────────┤
│  Old creative test                              ○ offline      [Open]  │
│  JUKZ-1111-2222-3333-4444                                            │
├─────────────────────────────────────────────────────────────────────┤
│  Skyblock draft                                 ◐ unknown      [Open]  │
│  JUKZ-AAAA-BBBB-CCCC-DDDD                                            │
└─────────────────────────────────────────────────────────────────────┘
```

- **Line 1**: level name (folder name, exact spelling from disk).
- **Line 2**: short code in a subtle colour (matches the existing
  `JukzStatusScreen.COLOR_SUBTLE = 0xFFB0B0B0`).
- **Status column**: a coloured dot + a one-word label. The dot is the visual
  anchor; the label is the textual confirmation. Colours follow the existing
  palette:
  - `live` → `COLOR_LIVE = 0xFF6BCB6B` (same green as `HostInfoScreen`).
  - `offline` → `COLOR_SUBTLE` (grey).
  - `unknown` → `ACCENT_ACTION = 0xFFFFC24A` (amber, same as
    `JukzStatusScreen.ACCENT_ACTION`).
  - `loading` (intermediate) → `ACCENT_INFO = 0xFF7FB2FF` (light blue) and
    label is `…`.
- **Action button** (rightmost): one of three labels, mapped from the row's
  status:
  - `LiveMine` or `LiveOther` → **"Join"** (calls `JoinCoordinator.start`).
    See §3.5 for why `LiveMine` does not get a distinct "Open locally"
    action in v1.
  - `Offline` → **"Open"** (vanilla singleplayer boot, which then re-enters
    the discovery flow naturally).
  - `Unknown` → **"Open"** (same as Offline — we treat "can't reach discovery"
    as "assume nobody hosts").
  - `Loading` → button is **disabled** with label "…".
- **Footer** (always present, centred): two buttons, **Refresh** and **Back**.
  **Back** returns to `parent` (the screen that opened the picker, normally
  `TitleScreen`). **Refresh** triggers a fresh scan + lookup cycle.

### 3.4 Errors

A scan that throws while iterating `saves/` (e.g. the directory does not exist
or the client was not yet initialised) renders a single line of error text in
the screen body and disables the per-row buttons, leaving **Back** active. The
screen never crashes; the model wraps every step in `try/catch` and converts
exceptions into `RowStatus.Unknown` or, at the directory level, into a global
"could not read saves" state.

A scan that finds rows but fails all lookups renders normally; each row is
simply `unknown`. The footer shows the same Refresh / Back pair.

A scan that finds at least one valid row but skips ≥1 broken `jukz.dat` renders
a subtle one-line note at the bottom: `"N world(s) skipped (invalid or missing
jukz.dat)"` — the count is informative, not actionable. No log spam.

### 3.5 A note on "I am the host"

The spec recognises a `LiveMine` row status (see §4.2) but the **action
button never reads "Open locally" in v1**. The reason is the open TODO on
`NodeId` (see [[jukz-project]]): today `NodeId.random()` produces a fresh
identity per session, so the comparison
`record.token.nodeId == selfNodeId` is only true in the exact same session
that published the record. In any later session, the row will land in
`LiveOther` (or `Offline` if the TTL expired), and the user will click
**"Join"** or **"Open"** — which are the correct actions anyway.

The `LiveMine` state is still in the data model (it is what a real, persistent
`NodeId` would produce) and the model still maps to it, but the action mapping
in §4.3 collapses `LiveMine` into the same behaviour as `LiveOther`: open
the join coordinator. The "Open locally" label is reserved for when
`NodeId` is fixed per-installation; this is captured in §6.

**Effect for v1**: every `live` row reads `Join`, regardless of who is
hosting. Users who want to "reclaim" their own world close the other session
and click **Open** (after Refresh, the record times out → `offline` →
**Open**), or they wait for the host to leave. This is honest: the mod
cannot distinguish "I am the host" from "another session of mine is" with
today's `NodeId`.

## 4. Architecture

### 4.1 New files

```
fabric/src/main/kotlin/dev/jukz/
  client/
    WorldListModel.kt        # state holder + async refresh + listeners
  client/gui/
    WorldListScreen.kt       # extends Screen; renders rows from the model

fabric/src/test/kotlin/dev/jukz/client/
  WorldListModelTest.kt      # pure unit tests, no Minecraft classes
```

### 4.2 `WorldListModel` (object, common-safe)

```kotlin
data class WorldRow(
    val levelName: String,
    val shortCode: String,
    val generation: Long,
    val status: RowStatus,
)

sealed interface RowStatus {
    data object Loading : RowStatus
    data class LiveMine(val endpoint: Endpoint, val heartbeatSeq: Long) : RowStatus
    data class LiveOther(val endpoint: Endpoint, val hostGeneration: Long) : RowStatus
    data object Offline : RowStatus
    data object Unknown : RowStatus
}
```

`WorldListModel` is an `object` (singleton) following the same pattern as
`HostSession`: it carries the live `rows` state, exposes a thread-safe
`addListener` / `removeListener` API, and runs its work on a single dedicated
daemon thread (`name = "jukz-worldlist-refresh"`).

Public surface:

- `fun addListener(listener: (List<WorldRow>) -> Unit)`
- `fun removeListener(listener: (List<WorldRow>) -> Unit)`
- `fun refresh(savesDirectory: Path, registry: WorldRegistry, selfNodeId: NodeId)`

`refresh` is a one-shot, fire-and-forget coroutine-equivalent (manual `Thread`
spawn, matching the pattern in `WorldOpenInterceptor` and `HostCoordinator`).
It is safe to call from the render thread; it is safe to call while a previous
refresh is in flight (the second call supersedes — listeners see only the
latest result, not interleaved partials from both).

The model is **not** wired by `JukzClient` or `JukzMod`; it is a passive
container. The screen is the only caller. The `savesDirectory`,
`registry`, and `selfNodeId` are passed in at `refresh()` time so the model
stays Minecraft-free and unit-testable.

### 4.3 `WorldListScreen` (Fabric client only)

Extends `net.minecraft.client.gui.screen.Screen`. Title literal: `"My worlds"`.
Brand line at the top: `"jukz"` in `ACCENT_INFO`, exactly mirroring
`JukzStatusScreen.BRAND`.

The screen holds a single `NodeId` field, computed once at `init()` via
`NodeId.random()` and reused across every `refresh()`. Without this, the
`selfNodeId` would change between refreshes and a row could flip from
`LiveMine` to `LiveOther` (or vice-versa) without the underlying record
changing — confusing and incorrect. Caching the identity for the lifetime of
the screen instance is consistent with how `HostCoordinator` already does
it within a single auto-host cycle.

`init()`:
1. `selfNodeId = NodeId.random()`.
2. `model.addListener { rows -> this@WorldListScreen.rows = rows }`.
3. Calls `model.refresh(savesDir, registry, selfNodeId)` with the current
   `MinecraftClient.levelStorage.savesDirectory` and `Discovery.registry`.
4. Builds the per-row click handlers and the footer (Refresh, Back).

`render()`: paints the brand, the title, every row from the last received
`List<WorldRow>`, and the footer. Rows are laid out top-down starting at
`height / 4`; the footer is at `height - 40`, matching `HostInfoScreen`.

`close()` / `removed()`: calls `model.removeListener(...)` and returns to
`parent` (the `TitleScreen`).

**Per-row click handler** — maps `RowStatus` to action:

- `LiveMine` or `LiveOther` → `JoinCoordinator.start(worldId, shortCode, parent)`.
- `Offline` or `Unknown` → `IntegratedServerLoader.start(levelName, onCancel)`
  from the client thread, **without** any bypass. The standard
  auto-join / auto-host flow runs; the picker is just a way to pick a save
  without going through vanilla's world list. Reached via
  `MinecraftClient.getInstance().createIntegratedServerLoader()` — the same
  call `WorldOpenInterceptor.openLocally` already uses.

The screen never sets `WorldOpenInterceptor.bypass` directly. That flag is an
internal detail of the open-consult mixin flow, and leaking it here would
couple two unrelated surfaces. If the user is the host and clicks "Open",
the auto-join consultation will simply find their own announced record and
proceed normally (the existing `WorldOpenInterceptor` already handles "live
host is us" via the `ClaimToken`-fencing path, which is exactly the right
behaviour).

For why `LiveMine` is not given a distinct "Open locally" action, see §3.5.

### 4.4 `JukzClient` changes (one hook, one line)

In the existing `ScreenEvents.AFTER_INIT` block, when `screen is TitleScreen`,
add a second `ButtonWidget.builder(Text.literal("jukz worlds")) { client.setScreen(WorldListScreen(screen)) }.dimensions(scaledWidth / 2 - 100, scaledHeight / 4 + 120, 200, 20).build()` and `Screens.getButtons(screen).add(button)`. The existing "Join via jukz" button is untouched.

### 4.5 Data flow per refresh

```
WorldListScreen.init()
        │
        ▼
WorldListModel.refresh(savesDir, registry, selfNodeId)
        │
        ├── Thread "jukz-worldlist-refresh" (daemon)
        │       │
        │       ├── Files.list(savesDir) → List<Path>
        │       ├── for each path: WorldIdSidecar.read(path) → Info? (drop nulls)
        │       ├── emit initial rows (status = Loading) to listeners
        │       │
        │       └── for each Info: registry.lookup(WorldId)
        │               ├── null                  → Offline
        │               ├── record.token.nodeId == selfNodeId  → LiveMine
        │               ├── else                                → LiveOther
        │               └── catch (Throwable)    → Unknown
        │                       │
        │                       └── emit updated rows to listeners
        │
        └── WorldListScreen.updateRows(rows)   (@Volatile field)
                │
                └── render() reads the field on the render thread
```

Listeners are invoked from the model's thread. The screen writes the received
list into a `@Volatile var rows: List<WorldRow>`; only `render()` reads it.

## 5. Tests

### 5.1 `core` (untouched)

Zero new tests. The screen is pure presentation; the underlying operations it
composes (`WorldIdSidecar.read`, `WorldRegistry.lookup`, `Endpoint` rendering)
are already covered.

### 5.2 `WorldListModelTest` (pure Kotlin, no Minecraft)

In `fabric/src/test/kotlin/dev/jukz/client/WorldListModelTest.kt`. JUnit5.
Each test sets up a temp directory with a hand-rolled `NbtCompound` for the
`jukz.dat` files (the test reads `WorldIdSidecar` directly, no Minecraft
dependency), plus a stub `WorldRegistry` whose `lookup` returns a configurable
`WorldRecord` or throws.

Required cases:

1. `givenMixedSavesDirectory listsOnlyValidJukzWorlds` — three folders: one
   with a valid `jukz.dat`, one with a `jukz.dat` that is an empty NBT, one
   with no `jukz.dat`. Result: exactly one row.
2. `givenEmptySavesDirectory returnsEmptyList` — zero rows.
3. `givenRegistryReturnsNull mapsToOffline` — one save, `lookup` returns null.
4. `givenRegistryReturnsRecordWithMyNodeId mapsToLiveMine` — `lookup` returns
   a `WorldRecord` with `token.nodeId == selfNodeId`.
5. `givenRegistryReturnsRecordWithDifferentNodeId mapsToLiveOther` — `lookup`
   returns a `WorldRecord` with `token.nodeId != selfNodeId`.
6. `givenRegistryThrows mapsToUnknown` — `lookup` throws. The model's
   `refresh` does not propagate the throw; the row lands in `Unknown`.
7. `givenListenerRegistered receivesLoadingThenResolved` — register a listener,
   call `refresh`, await completion, assert exactly 2 invocations: the
   first carries rows with `status = Loading`, the second carries rows
   with the resolved status (`Offline` in this test's stub). The model
   explicitly emits the `Loading` snapshot before starting lookups, so
   the UI can paint the rows immediately rather than wait for the
   registry round-trip.
8. `givenRefreshCalledWhileAnotherIsRunning doesNotInterleave` — call `refresh`
   twice in quick succession; the listener sees only one final result, not
   a half-merged state from both. **Implementation note**: the model keeps
   a monotonically-incrementing `seq: AtomicLong`. A new `refresh` increments
   `seq` and tags the new run. Each `emit` checks `if (mySeq == currentSeq)
   invokeListeners(rows)` and drops the result otherwise. Old runs finish
   their work but their emissions are discarded. This is what guarantees the
   "last call wins" semantics; it is also why the test only asserts a single
   final invocation (not a sequence of states).

### 5.3 Manual UI validation (in-game, not automated)

`core` is fully tested; the screen is exercised by running the game, per the
project convention (see `README.md`, "UI/mixin behaviour validated by running
the game, not automated tests"). Manual script:

1. Start the dev client (`run-client.bat`).
2. Create or open three singleplayer worlds (so each gets a fresh `jukz.dat`).
3. Quit back to `TitleScreen`. Click **"jukz worlds"**.
4. Expect: three rows, all with `● live` (because each world, once
   opened, is auto-hosted by `HostCoordinator`). Action buttons read
   **"Join"** (see §3.5 for why).
5. Click **"Join"** on one row → `SearchingHostScreen` flashes, then
   `ConnectScreen` opens (you are joining your own host, so this is a
   loopback round-trip that succeeds in v1). Return to title.
6. Open the same picker again → that row's status is now `○ offline` (the
   host was withdrawn on world close). The action button reads **"Open"**.
7. Click **"Open"** → the world boots locally.
8. Click **"Refresh"** on the picker → the freshly-opened world's record
   is back to `● live`. No change in UI other than the colour flipping.
9. Exit the client. Restart, open the picker → the same three rows are still
   listed (UUID is persisted).

The `LiveOther` and `Unknown` states require either a second Minecraft
instance pointed at the same DHT or a registry that simulates a foreign
`NodeId`. They are not reachable in a single-client dev session
(`InMemoryWorldRegistry` is per-process, and the in-memory store evaporates
when the host stops heartbeating). They are covered by the unit tests
against a stub `WorldRegistry` (cases 4–6).

## 6. Open follow-ups (not in v1)

- **Observable registry** — once `WorldRegistry` exposes a listener (e.g. via
  `MldhtWorldRegistry`'s BEP44 put/put-back events), wire
  `WorldListModel.refresh` to be triggered by changes, not only by the user
  clicking Refresh. The screen is already shaped for this; the model needs
  one more constructor parameter.
- **Per-row "Copy code"** — trivial: add a third button per row. No layout
  change beyond width.
- **Display name persistence** — if the user wants to label their worlds
  ("Survival Season 4", etc.), extend `WorldIdSidecar` and `WorldIdState` with
  an optional `displayName: String?` field. Pure additive, no migration
  required (missing → fall back to folder name).
- **Persistent `NodeId`** — the open TODO on `core/model/NodeId`. Once
  `NodeId` is fixed per-installation (e.g. generated on first launch and
  stored in `~/.jukz/nodeid`), the comparison
  `record.token.nodeId == selfNodeId` becomes meaningful across sessions.
  At that point, the action mapping in §4.3 distinguishes `LiveMine` (the
  "Open locally" label) from `LiveOther` ("Join"), and §3.5 is rescinded.
  This is the natural follow-up that unlocks the missing affordance.
- **"Take over"** — a guarded action that bumps the fencing generation
  locally and re-publishes. Out of scope for a screen; requires a new
  `WorldRegistry.forcePublish(record: WorldRecord)` method that violates
  the strict CAS and emits a user-visible confirmation.
- **Document** — a one-page user guide (markdown) showing what each status
  means and what each action does, accessible from a `?` button on the
  picker. This belongs in `docs/` once the screen is stable.

## 7. Compatibility and migration

- **No `core` changes.** The screen is 100% `fabric` code.
- **No data migration.** `WorldIdSidecar` is unchanged; existing `jukz.dat`
  files are read as-is.
- **No public API additions** to `core`. `Endpoint` and `NodeId` are
  re-exposed (not modified) by the model; no new types in `core/model`.
- **Build impact**: ~250 lines of Kotlin in `fabric` (model + screen + 1
  button hook), ~150 lines of tests. No new dependencies.

## 8. File-by-file change summary

| File | Change |
|---|---|
| `fabric/src/main/kotlin/dev/jukz/client/WorldListModel.kt` | **new** — `object WorldListModel`, `data class WorldRow`, `sealed interface RowStatus`, ~120 lines |
| `fabric/src/main/kotlin/dev/jukz/client/gui/WorldListScreen.kt` | **new** — extends `Screen`, ~130 lines, mirrors `HostInfoScreen` layout conventions |
| `fabric/src/main/kotlin/dev/jukz/JukzClient.kt` | **edit** — one extra `ButtonWidget.builder(...)` block in the `TitleScreen` branch of `AFTER_INIT` (1 line + 1 add) |
| `fabric/src/test/kotlin/dev/jukz/client/WorldListModelTest.kt` | **new** — 8 JUnit5 tests, ~150 lines |
| `docs/superpowers/specs/2026-06-09-jukz-world-list-screen-design.md` | **new** — this document |
