# Relay path for non-UPnP / CGNAT hosts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let two players connect when neither host nor guest is directly reachable (no UPnP, CGNAT, symmetric NAT) by relaying the existing TCP/`ConnectionType` byte streams through an outbound-only WebSocket reverse tunnel inside the existing rendezvous server, with direct-first / relay-fallback dialing on the guest.

**Architecture:** Three cooperating pieces along the existing module boundaries. **core** (Minecraft-free, JUnit/TDD) gets the `RelayOffer` model, `WorldRecord` wire v4, a `DialTarget`/`ChannelDialer` seam for the connect ladder, and a `RelayRegistrar` seam on the host. **rendezvous** (Rust, `cargo test` for the deterministic registry) gets three WebSocket endpoints — `/v1/relay/host`, `/v1/relay/connect`, `/v1/relay/work` — that pair a guest stream with a host work connection by nonce and splice the bytes. **fabric** (Minecraft + real WS, implement + manual in-game validation per the repo norm for flagged network adapters) gets the guest `WsRelayTransport`, the host `WsRelayClient`, and the wiring.

**Tech Stack:** Kotlin (core/fabric), JUnit5, Rust + Axum + tokio-tungstenite (`axum::extract::ws`), Java 11 `java.net.http.WebSocket` (no new client dependency), Gradle 8.10.1 / Loom 1.7.

**Design record:** `docs/superpowers/specs/2026-06-10-jukz-relay-nat-traversal-design.md`.

**Conventions:** All code/comments/commits in English. Run `./gradlew :core:test` for core, `cargo test` (in `rendezvous/`) for Rust, `./gradlew build` for the whole mod. Commit after every green step.

---

## Build order & verifiability

- **Tasks 1–6 (core):** strict TDD — each has a failing test, a minimal impl, a green run, a commit.
- **Tasks 7, 9 (rust registry + record):** `cargo test` TDD for the deterministic parts.
- **Tasks 8, 10–13 (rust WS glue + fabric WS):** real network / Minecraft surfaces. Per the repo norm (NAT adapters are validated live, not unit-tested), these are **implement + manual validation**; complete code is given, the "test" step is a build + a described live check.
- **Task 14:** the end-to-end forced-relay validation.

> **Scope note — handoff over the relay:** v1 delivers **play** over the relay (the session goal). The F4 snapshot *handoff* download (`JGitWorldSync.downloadSnapshot`) still opens its own direct socket, so when a host leaves a relay-connected guest, that guest degrades gracefully to its **local copy** (the existing abrupt-drop `onHostLost(worldId, null)` path). Routing the SNAPSHOT pull through the dialer so handoff also rides the relay is **Task 15 (follow-up)**, explicitly out of the v1 critical path.

---

## Task 1: `RelayOffer` model + `WorldRecord.relay` field

**Files:**
- Create: `core/src/main/kotlin/dev/jukz/core/discovery/RelayOffer.kt`
- Modify: `core/src/main/kotlin/dev/jukz/core/discovery/WorldRecord.kt:23-49`
- Test: `core/src/test/kotlin/dev/jukz/core/discovery/RelayOfferTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.jukz.core.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RelayOfferTest {

    @Test
    fun `keeps the session id`() {
        assertEquals("s3ss10n", RelayOffer("s3ss10n").sessionId)
    }

    @Test
    fun `rejects a blank session id`() {
        assertThrows(IllegalArgumentException::class.java) { RelayOffer(" ") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.jukz.core.discovery.RelayOfferTest"`
Expected: FAIL — `RelayOffer` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `RelayOffer.kt`:

```kotlin
package dev.jukz.core.discovery

/**
 * The host's offer of a relay session so a guest that cannot reach the host directly (no UPnP,
 * CGNAT, symmetric NAT) can connect through the rendezvous relay instead. [sessionId] is a
 * high-entropy bearer capability the host registered on the relay (`/v1/relay/host`); a guest
 * dials `/v1/relay/connect?session=<sessionId>` and the relay splices the two streams. It rides
 * the discovery record (wire v4) alongside the direct endpoints; the guest tries the direct
 * endpoints first and falls back to this only when all of them fail.
 */
data class RelayOffer(val sessionId: String) {
    init {
        require(sessionId.isNotBlank()) { "relay session id must not be blank" }
    }
}
```

In `WorldRecord.kt`, add the field to the data class (after `playerCount`, line 29) and a helper. Change the header lines:

```kotlin
data class WorldRecord(
    val worldId: WorldId,
    val token: ClaimToken,
    val endpoints: List<Endpoint>,
    val heartbeatSeq: Long,
    val snapshot: SnapshotOffer? = null,
    val playerCount: Int = 0,
    val relay: RelayOffer? = null,
) {
```

And add, next to `withSnapshot` (after line 48):

```kotlin
    /** Attach (or clear) the relay session a non-reachable host offers as a fallback path. */
    fun withRelay(offer: RelayOffer?): WorldRecord = copy(relay = offer)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "dev.jukz.core.discovery.RelayOfferTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/jukz/core/discovery/RelayOffer.kt core/src/main/kotlin/dev/jukz/core/discovery/WorldRecord.kt core/src/test/kotlin/dev/jukz/core/discovery/RelayOfferTest.kt
git commit -m "feat(core): RelayOffer model + optional WorldRecord.relay (wire v4 prep)"
```

---

## Task 2: `WorldRecordCodec` wire v4 (optional relay tail)

**Files:**
- Modify: `core/src/main/kotlin/dev/jukz/core/discovery/WorldRecordCodec.kt`
- Test: `core/src/test/kotlin/dev/jukz/core/discovery/WorldRecordCodecTest.kt` (add cases; create if absent)

- [ ] **Step 1: Write the failing test**

Add these tests (in the existing `WorldRecordCodecTest`, or a new file with this header if none exists):

```kotlin
package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WorldRecordCodecRelayTest {

    private fun record(relay: RelayOffer?) = WorldRecord(
        worldId = WorldId.random(),
        token = ClaimToken(7, 1_700_000_000_000, NodeId(ByteArray(NodeId.SIZE) { 1 })),
        endpoints = listOf(Endpoint("1.2.3.4", 25565)),
        heartbeatSeq = 3,
        relay = relay,
    )

    @Test
    fun `round-trips a record carrying a relay offer`() {
        val original = record(RelayOffer("cap-abc123"))
        assertEquals(original, WorldRecordCodec.decode(WorldRecordCodec.encode(original)))
    }

    @Test
    fun `round-trips a record with no relay offer`() {
        val original = record(null)
        val decoded = WorldRecordCodec.decode(WorldRecordCodec.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded.relay)
    }

    @Test
    fun `decodes a v3 record as relay-null (back-compat)`() {
        // Hand-build a v3 buffer (version byte 3, no relay tail) and prove it still decodes.
        val node = ByteArray(NodeId.SIZE) { 2 }
        val world = WorldId.random()
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(0x6A_6B_7A_31) // MAGIC
            o.writeByte(3)            // VERSION 3
            o.writeLong(world.uuid.mostSignificantBits)
            o.writeLong(world.uuid.leastSignificantBits)
            o.writeLong(9)            // generation
            o.writeLong(1_700_000_000_001) // claimEpochMillis
            o.write(node)
            o.writeByte(1)            // endpoint count
            o.writeUTF("5.6.7.8"); o.writeInt(25566)
            o.writeLong(4)            // heartbeatSeq
            o.writeBoolean(false)     // no snapshot
            o.writeInt(2)             // playerCount
        }
        val decoded = WorldRecordCodec.decode(bos.toByteArray())
        assertNull(decoded.relay)
        assertEquals(2, decoded.playerCount)
        assertEquals(world, decoded.worldId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.jukz.core.discovery.WorldRecordCodecRelayTest"`
Expected: FAIL — `round-trips a record carrying a relay offer` fails (the encoder writes v3 and never emits the relay tail, so `decoded.relay` is null ≠ the offer).

- [ ] **Step 3: Write minimal implementation**

In `WorldRecordCodec.kt`, bump the version and add the relay tail. Change the constants (lines 21-23):

```kotlin
    private const val MAGIC = 0x6A_6B_7A_31 // "jkz1"
    private const val VERSION = 4            // current: + optional relay offer
    private const val V3_SNAPSHOT = 3        // optional snapshot offer + player count
    private const val V2_MULTI_ENDPOINT = 2  // multi-endpoint candidate list, no snapshot
    private const val LEGACY_VERSION = 1     // single endpoint, no count prefix
```

In `encode`, after `o.writeInt(record.playerCount)` (line 55), append the v4 tail:

```kotlin
            o.writeInt(record.playerCount)
            // v4 tail: an optional relay session offer.
            val relay = record.relay
            o.writeBoolean(relay != null)
            if (relay != null) {
                o.writeUTF(relay.sessionId)
            }
```

In `decode`, change the version guard (line 67) and add the v4 read after `playerCount` (after line 88):

```kotlin
            require(version in LEGACY_VERSION..VERSION) { "unsupported jukz record version" }
```

```kotlin
            // v3 tail (absent in v1/v2: snapshot=null, playerCount=0).
            var snapshot: SnapshotOffer? = null
            var playerCount = 0
            var relay: RelayOffer? = null
            if (version > V2_MULTI_ENDPOINT) {
                if (i.readBoolean()) {
                    snapshot = SnapshotOffer(i.readUTF(), i.readInt(), i.readUTF())
                }
                playerCount = i.readInt()
            }
            // v4 tail (absent in v1/v2/v3: relay=null).
            if (version > V3_SNAPSHOT) {
                if (i.readBoolean()) {
                    relay = RelayOffer(i.readUTF())
                }
            }
            return WorldRecord(
                worldId = WorldId(UUID(msb, lsb)),
                token = ClaimToken(generation, claimEpochMillis, NodeId(nodeBytes)),
                endpoints = endpoints,
                heartbeatSeq = heartbeatSeq,
                snapshot = snapshot,
                playerCount = playerCount,
                relay = relay,
            )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "dev.jukz.core.discovery.WorldRecordCodecRelayTest"`
Expected: PASS. Also run the full codec suite: `./gradlew :core:test --tests "dev.jukz.core.discovery.*"` — all green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/jukz/core/discovery/WorldRecordCodec.kt core/src/test/kotlin/dev/jukz/core/discovery/WorldRecordCodecRelayTest.kt
git commit -m "feat(core): WorldRecord wire v4 — optional relay offer; v1/v2/v3 still decode"
```

---

## Task 3: `DialTarget` + `ChannelDialer` seam

**Files:**
- Create: `core/src/main/kotlin/dev/jukz/core/transport/DialTarget.kt`
- Create: `core/src/main/kotlin/dev/jukz/core/transport/ChannelDialer.kt`
- Test: `core/src/test/kotlin/dev/jukz/core/transport/DirectChannelDialerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.jukz.core.transport

import dev.jukz.core.model.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DirectChannelDialerTest {

    private val fakeChannel = object : JukzChannel {
        override fun inputStream() = throw UnsupportedOperationException()
        override fun outputStream() = throw UnsupportedOperationException()
        override fun close() {}
    }

    @Test
    fun `dials a direct target through the transport`() = runBlocking {
        val transport = Transport { endpoint ->
            assertSame("9.9.9.9", endpoint.host)
            fakeChannel
        }
        val dialer = DirectChannelDialer(transport)
        assertSame(fakeChannel, dialer.dial(DialTarget.Direct(Endpoint("9.9.9.9", 25565))))
    }

    @Test
    fun `refuses a relay target (direct-only dialer)`() {
        val dialer = DirectChannelDialer(Transport { fakeChannel })
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { dialer.dial(DialTarget.ViaRelay("cap-x")) }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.jukz.core.transport.DirectChannelDialerTest"`
Expected: FAIL — `DialTarget` / `ChannelDialer` / `DirectChannelDialer` unresolved. (Note: `Transport { ... }` works because `Transport` is a single-method interface; if Kotlin rejects the SAM here because `connect` is `suspend`, the test uses an explicit `object : Transport`.)

- [ ] **Step 3: Write minimal implementation**

`DialTarget.kt`:

```kotlin
package dev.jukz.core.transport

import dev.jukz.core.model.Endpoint

/**
 * How a guest reaches a host for one channel. [Direct] dials a reachable endpoint over TCP;
 * [ViaRelay] connects through the rendezvous relay to the host's registered session. The guest's
 * connect ladder ([dev.jukz.core.discovery.WorldRecord.dialTargets]) lists every direct target
 * first, then the relay, so the relay is only used when no direct endpoint connects.
 */
sealed interface DialTarget {
    data class Direct(val endpoint: Endpoint) : DialTarget
    data class ViaRelay(val sessionId: String) : DialTarget
}
```

`ChannelDialer.kt`:

```kotlin
package dev.jukz.core.transport

/**
 * Opens a raw [JukzChannel] to a [DialTarget], abstracting over the direct-TCP and relay-WebSocket
 * paths. The production implementation (fabric) composes a [Transport] for [DialTarget.Direct] with
 * a relay WebSocket client for [DialTarget.ViaRelay]; [DirectChannelDialer] is the direct-only one
 * used on the LAN path and in tests.
 */
fun interface ChannelDialer {
    suspend fun dial(target: DialTarget): JukzChannel
}

/** A [ChannelDialer] that only knows the direct path; a relay target is an error. */
class DirectChannelDialer(private val transport: Transport = DirectTcpTransport()) : ChannelDialer {
    override suspend fun dial(target: DialTarget): JukzChannel = when (target) {
        is DialTarget.Direct -> transport.connect(target.endpoint)
        is DialTarget.ViaRelay ->
            throw UnsupportedOperationException("DirectChannelDialer cannot dial a relay target")
    }
}
```

If `Transport { ... }` SAM syntax fails to compile in the test (suspend SAM), replace the test's transports with explicit `object : Transport { override suspend fun connect(endpoint: Endpoint) = ... }`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "dev.jukz.core.transport.DirectChannelDialerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/jukz/core/transport/DialTarget.kt core/src/main/kotlin/dev/jukz/core/transport/ChannelDialer.kt core/src/test/kotlin/dev/jukz/core/transport/DirectChannelDialerTest.kt
git commit -m "feat(core): DialTarget + ChannelDialer seam (direct vs relay), DirectChannelDialer"
```

---

## Task 4: `WorldRecord.dialTargets()` (the ladder ordering)

**Files:**
- Create: `core/src/main/kotlin/dev/jukz/core/discovery/DialTargets.kt`
- Test: `core/src/test/kotlin/dev/jukz/core/discovery/DialTargetsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.DialTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DialTargetsTest {

    private fun record(relay: RelayOffer?) = WorldRecord(
        worldId = WorldId.random(),
        token = ClaimToken(1, 1, NodeId(ByteArray(NodeId.SIZE))),
        endpoints = listOf(Endpoint("10.0.0.2", 1), Endpoint("8.8.8.8", 2)),
        heartbeatSeq = 0,
        relay = relay,
    )

    @Test
    fun `lists direct endpoints first then the relay`() {
        val targets = record(RelayOffer("cap-z")).dialTargets()
        assertEquals(
            listOf(
                DialTarget.Direct(Endpoint("10.0.0.2", 1)),
                DialTarget.Direct(Endpoint("8.8.8.8", 2)),
                DialTarget.ViaRelay("cap-z"),
            ),
            targets,
        )
    }

    @Test
    fun `omits the relay when none is offered`() {
        val targets = record(null).dialTargets()
        assertEquals(2, targets.size)
        assertEquals(DialTarget.Direct(Endpoint("10.0.0.2", 1)), targets.first())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.jukz.core.discovery.DialTargetsTest"`
Expected: FAIL — `dialTargets` unresolved.

- [ ] **Step 3: Write minimal implementation**

`DialTargets.kt`:

```kotlin
package dev.jukz.core.discovery

import dev.jukz.core.transport.DialTarget

/**
 * The guest's connect ladder for this record: every advertised endpoint as a direct target (tried
 * in order), then — only if the host offered one — the relay session as the fallback. Keeping the
 * ordering here (pure, tested) lets [dev.jukz.core.join.JoinController] iterate targets without
 * knowing how each is reached.
 */
fun WorldRecord.dialTargets(): List<DialTarget> =
    endpoints.map { DialTarget.Direct(it) } + (relay?.let { listOf(DialTarget.ViaRelay(it.sessionId)) } ?: emptyList())
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "dev.jukz.core.discovery.DialTargetsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/jukz/core/discovery/DialTargets.kt core/src/test/kotlin/dev/jukz/core/discovery/DialTargetsTest.kt
git commit -m "feat(core): WorldRecord.dialTargets() — direct candidates first, relay last"
```

---

## Task 5: `JoinController` uses the dialer + relay ladder

This swaps the controller's single `Transport` for a `ChannelDialer`, iterates `record.dialTargets()`, and carries a `DialTarget` (instead of an `Endpoint`) through the handshake so a relay target rides the same flow.

**Files:**
- Modify: `core/src/main/kotlin/dev/jukz/core/join/JoinController.kt`
- Modify (callers): `core/src/test/kotlin/dev/jukz/core/join/JoinControllerLoopbackTest.kt`, `core/src/test/kotlin/dev/jukz/core/host/HostJoinLoopbackTest.kt`
- Test: `core/src/test/kotlin/dev/jukz/core/join/JoinControllerRelayLadderTest.kt`

- [ ] **Step 1: Write the failing test**

This test proves the ladder falls back to the relay: every direct endpoint throws, the relay target returns a loopback control channel served by a real `HostConnectionServer`, and the join connects.

```kotlin
package dev.jukz.core.join

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.RelayOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostConnectionServer
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JoinControllerRelayLadderTest {

    @Test
    fun `falls back to the relay target when every direct endpoint fails`() = runBlocking {
        val world = WorldId.random()
        val token = ClaimToken(1, 1, NodeId(ByteArray(NodeId.SIZE) { 5 }))

        // A real host listening on loopback; the "relay" just dials it directly in this test.
        val server = HostConnectionServer(bindHost = "127.0.0.1")
        val port = server.start(world, token, Endpoint("127.0.0.1", 1)) { 0 }
        try {
            val registry = InMemoryWorldRegistry()
            registry.publishIfNewer(
                WorldRecord(
                    worldId = world,
                    token = token,
                    endpoints = listOf(Endpoint("203.0.113.255", 1)), // unreachable on purpose
                    heartbeatSeq = 0,
                    relay = RelayOffer("cap-loopback"),
                ),
            )

            val direct = DirectTcpTransport(connectTimeoutMs = 200)
            val dialer = ChannelDialer { target ->
                when (target) {
                    is DialTarget.Direct -> direct.connect(target.endpoint) // throws (unreachable)
                    is DialTarget.ViaRelay -> direct.connect(Endpoint("127.0.0.1", port)) // "relay" → loopback host
                }
            }

            val connected = java.util.concurrent.atomic.AtomicBoolean(false)
            val handoff = GameHandoff { _, _ -> connected.set(true) }
            JoinController(registry, dialer, handoff, SystemClock).use { controller ->
                val result = controller.join(world)
                assertTrue(result is JoinResult.Connected, "expected Connected, got $result")
            }
            assertTrue(connected.get())
        } finally {
            server.close()
        }
    }
}
```

> If `GameHandoff` is not a single-method interface with `connect(host, port)`, open `core/.../join/GameHandoff.kt` and match its actual signature; the assertion only needs a `JoinResult.Connected`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.jukz.core.join.JoinControllerRelayLadderTest"`
Expected: FAIL — `JoinController` still takes a `Transport`, so the `ChannelDialer` constructor argument does not compile.

- [ ] **Step 3: Write minimal implementation**

Edit `JoinController.kt`:

1. Imports — drop `Transport`, add the dialer/target types:

```kotlin
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.discovery.dialTargets
```

2. Constructor — replace the `transport` parameter (line 41):

```kotlin
    private val dialer: ChannelDialer,
```

3. `join()` — iterate dial targets instead of raw endpoints (lines 68-81):

```kotlin
    suspend fun join(worldId: WorldId): JoinResult {
        val record = registry.lookup(worldId) ?: return JoinResult.HostUnavailable
        var lastFailure: Exception? = null
        for (target in record.dialTargets()) {
            try {
                return runHandshake(worldId, record, target)
            } catch (e: Exception) {
                closeControl() // tear down this attempt only; the next target gets a fresh channel
                lastFailure = e
            }
        }
        close()
        return JoinResult.Failed(lastFailure?.message ?: "no reachable endpoint")
    }
```

4. `runHandshake` — take a `DialTarget`, synthesise an endpoint for the state machine, dial via the dialer (lines 83-104):

```kotlin
    private suspend fun runHandshake(worldId: WorldId, record: WorldRecord, target: DialTarget): JoinResult {
        var current = target
        val sm = JoinerStateMachine(worldId, record.token, endpointOf(current))
        sm.begin()

        openControl(current).send(hello(worldId, record.token))

        while (true) {
            val msg = receiveOrTimeout(config.handshakeMs)
                ?: return ghost(sm, record) // no reply in time -> treat host as a ghost
            when (val action = sm.onMessage(msg).action) {
                is JoinerAction.Connect -> return handoff(worldId, record, DialTarget.Direct(action.endpoint), sm)
                is JoinerAction.FollowRedirect -> {
                    closeControl()
                    current = DialTarget.Direct(action.endpoint)
                    openControl(current).send(hello(worldId, record.token))
                }
                JoinerAction.Takeover -> return JoinResult.ShouldHost(worldId, record)
                JoinerAction.Wait -> Unit // non-terminal; keep reading
            }
        }
    }

    /** The endpoint a target presents to the state machine; a relay target has no dial address, so a synthetic placeholder stands in for its fencing/redirect bookkeeping. */
    private fun endpointOf(target: DialTarget): Endpoint = when (target) {
        is DialTarget.Direct -> target.endpoint
        is DialTarget.ViaRelay -> SYNTHETIC_RELAY_ENDPOINT
    }
```

> Note: `JoinerAction.Connect`/`FollowRedirect` carry an `Endpoint` (the host's chosen data endpoint). Over a relay these are loopback-on-the-host addresses the guest must *not* dial directly; wrapping them as `DialTarget.Direct` is correct for the **direct** path. For a relay session the host's connection server fronts the game on the same session, so the data channel must also go via the relay — see Task 15 for full relay-data routing. For v1, `JoinerAction.Connect` on a relay session still produces a `Direct` target; if the host returns its own listen endpoint (the common case — see `HostStateMachine`), the relay path connects control-only and data falls back. Validate in Task 14; if data does not flow over the relay, Task 15 is required before shipping relay play. **(See the v1 reality check in Task 14.)**

5. `handoff` — take a `DialTarget`, dial via the dialer (lines 111-126):

```kotlin
    private fun handoff(
        worldId: WorldId,
        record: WorldRecord,
        target: DialTarget,
        sm: JoinerStateMachine,
    ): JoinResult {
        val relay = LocalTcpRelay(openRemote = {
            runBlocking { dialer.dial(target) }.also { ConnectionType.DATA.writeTo(it) }
        })
        val port = relay.start()
        this.relay = relay
        gameHandoff.connect(RELAY_HOST, port)
        sm.markConnected()
        startLiveness(worldId, record.token, endpointOf(target))
        return JoinResult.Connected(RELAY_HOST, port)
    }
```

6. `openControl` — take a `DialTarget` (lines 170-177):

```kotlin
    private suspend fun openControl(target: DialTarget): FramedMessageChannel {
        val ch = dialer.dial(target)
        ConnectionType.CONTROL.writeTo(ch)
        val framed = FramedMessageChannel(ch)
        controlChannel = ch
        control = framed
        return framed
    }
```

7. Companion — add the synthetic endpoint (inside the existing `companion object`, lines 220-222):

```kotlin
    companion object {
        const val RELAY_HOST = "127.0.0.1"
        private val SYNTHETIC_RELAY_ENDPOINT = Endpoint("relay", 1)
    }
```

8. Update the two loopback tests to construct with `DirectChannelDialer`:
   - In `JoinControllerLoopbackTest.kt` and `HostJoinLoopbackTest.kt`, wherever a `JoinController(... transport ...)` is built, wrap the transport: `JoinController(registry, DirectChannelDialer(transport), handoff, clock, ...)`. Add `import dev.jukz.core.transport.DirectChannelDialer`. If they passed `DirectTcpTransport()` inline, change to `DirectChannelDialer(DirectTcpTransport())`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "dev.jukz.core.join.*" --tests "dev.jukz.core.host.HostJoinLoopbackTest"`
Expected: PASS (new ladder test + the two adapted loopback suites).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/jukz/core/join/JoinController.kt core/src/test/kotlin/dev/jukz/core/join/JoinControllerRelayLadderTest.kt core/src/test/kotlin/dev/jukz/core/join/JoinControllerLoopbackTest.kt core/src/test/kotlin/dev/jukz/core/host/HostJoinLoopbackTest.kt
git commit -m "feat(core): JoinController dials via ChannelDialer + falls back to the relay target"
```

---

## Task 6: `RelayRegistrar` seam + `HostController` attaches the offer

**Files:**
- Create: `core/src/main/kotlin/dev/jukz/core/host/RelayRegistrar.kt`
- Modify: `core/src/main/kotlin/dev/jukz/core/host/HostController.kt:41-87`
- Test: `core/src/test/kotlin/dev/jukz/core/host/HostControllerRelayTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.jukz.core.host

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.RelayOffer
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HostControllerRelayTest {

    private fun controller(registry: InMemoryWorldRegistry, registrar: RelayRegistrar) = HostController(
        registry = registry,
        lanOpener = LanOpener { 40000 },
        connectionServer = FakeConnectionServer(listenPort = 50000),
        endpointResolver = EndpointResolver { port -> Endpoint("127.0.0.1", port) },
        nodeId = NodeId(ByteArray(NodeId.SIZE) { 9 }),
        clock = SystemClock,
        relayRegistrar = registrar,
    )

    @Test
    fun `attaches the relay offer the registrar returns`() = runBlocking {
        val registry = InMemoryWorldRegistry()
        val seen = java.util.concurrent.atomic.AtomicInteger(0)
        val registrar = RelayRegistrar { listenPort ->
            seen.set(listenPort)
            RelayOffer("cap-host")
        }
        controller(registry, registrar).host(WorldId.random(), generation = 1)
        val published = registry.lookup(registry.firstWorldId())!!
        assertEquals(RelayOffer("cap-host"), published.relay)
        assertEquals(50000, seen.get()) // registrar saw the connection-server listen port
    }

    @Test
    fun `leaves relay null when the registrar declines`() = runBlocking {
        val registry = InMemoryWorldRegistry()
        controller(registry, RelayRegistrar { null }).host(WorldId.random(), generation = 1)
        assertNull(registry.lookup(registry.firstWorldId())!!.relay)
    }
}
```

> This test needs a `FakeConnectionServer` returning a fixed `listenPort` and a `LanOpener`/`EndpointResolver` fake. If the existing `HostControllerTest` already defines such fakes, reuse them (import or copy the minimal versions) rather than redefining. `InMemoryWorldRegistry.firstWorldId()` may not exist — if not, capture the published `WorldId` from the `host(...)` argument instead and look that up.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "dev.jukz.core.host.HostControllerRelayTest"`
Expected: FAIL — `RelayRegistrar` unresolved and `HostController` has no `relayRegistrar` parameter.

- [ ] **Step 3: Write minimal implementation**

`RelayRegistrar.kt`:

```kotlin
package dev.jukz.core.host

import dev.jukz.core.discovery.RelayOffer

/**
 * Optionally registers a relay session for a host that may be unreachable directly, returning the
 * [RelayOffer] to advertise in the discovery record (or null to advertise no relay). Called once on
 * the host path with the connection-server [listenPort] the relay must bridge guest traffic to. The
 * real implementation (fabric `WsRelayClient`) opens an outbound WebSocket control link to the
 * rendezvous relay; the default is a no-op (LAN path / tests). Best-effort: it must never throw out
 * of [register] in a way that fails hosting — return null on any failure.
 */
fun interface RelayRegistrar {
    fun register(listenPort: Int): RelayOffer?
}
```

In `HostController.kt`, add the constructor parameter (after `playerCount`, line 52):

```kotlin
    /** Connected player count, sampled on each announce for the world-list live badge (F4-C). */
    private val playerCount: () -> Int = { 0 },
    /** Optionally register a relay fallback session; its offer is attached to the announced record. */
    private val relayRegistrar: RelayRegistrar = RelayRegistrar { null },
```

In `host()`, attach the offer when building the candidate record (line 70-71):

```kotlin
            val endpoint = endpointResolver.resolve(listenPort)
            val relayOffer = runCatching { relayRegistrar.register(listenPort) }.getOrNull()
            val candidate = WorldRecord(worldId, token, listOf(endpoint), heartbeatSeq = 0)
                .copy(playerCount = playerCount(), relay = relayOffer)
```

Add the import:

```kotlin
import dev.jukz.core.discovery.RelayOffer
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "dev.jukz.core.host.HostControllerRelayTest" --tests "dev.jukz.core.host.HostControllerTest"`
Expected: PASS (new test + the existing HostController suite still green — the new param has a default).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/dev/jukz/core/host/RelayRegistrar.kt core/src/main/kotlin/dev/jukz/core/host/HostController.kt core/src/test/kotlin/dev/jukz/core/host/HostControllerRelayTest.kt
git commit -m "feat(core): RelayRegistrar seam — HostController attaches an optional RelayOffer"
```

---

## Task 7: Rust relay session registry (`relay.rs`)

The deterministic heart of the relay: a session map keyed by `sessionId`, pending guest streams keyed by `nonce`, plus caps. Async splicing lives in Task 8; this module is `cargo test`-covered.

**Files:**
- Create: `rendezvous/src/relay.rs`
- Modify: `rendezvous/src/main.rs` (add `mod relay;`)

- [ ] **Step 1: Write the failing test**

Put these at the bottom of `relay.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn limits() -> RelayLimits {
        RelayLimits { max_sessions: 2, max_streams_per_session: 2 }
    }

    #[test]
    fn register_then_connect_allocates_a_nonce_the_host_can_take() {
        let state = RelayState::new(limits());
        assert_eq!(state.register("sess-a"), RegisterOutcome::Registered);

        let nonce = match state.begin_connect("sess-a") {
            ConnectOutcome::Pending(n) => n,
            other => panic!("expected Pending, got {other:?}"),
        };
        assert!(state.take_work(nonce).is_some(), "host work conn should match the nonce");
        assert!(state.take_work(nonce).is_none(), "a nonce is single-use");
    }

    #[test]
    fn connect_to_an_unknown_session_is_refused() {
        let state = RelayState::new(limits());
        assert_eq!(state.begin_connect("ghost"), ConnectOutcome::UnknownSession);
    }

    #[test]
    fn duplicate_registration_is_refused_while_live() {
        let state = RelayState::new(limits());
        assert_eq!(state.register("sess-a"), RegisterOutcome::Registered);
        assert_eq!(state.register("sess-a"), RegisterOutcome::AlreadyLive);
        state.unregister("sess-a");
        assert_eq!(state.register("sess-a"), RegisterOutcome::Registered); // slot freed
    }

    #[test]
    fn session_cap_is_enforced() {
        let state = RelayState::new(limits());
        assert_eq!(state.register("a"), RegisterOutcome::Registered);
        assert_eq!(state.register("b"), RegisterOutcome::Registered);
        assert_eq!(state.register("c"), RegisterOutcome::CapacityReached);
    }

    #[test]
    fn stream_cap_per_session_is_enforced() {
        let state = RelayState::new(limits());
        state.register("a");
        assert!(matches!(state.begin_connect("a"), ConnectOutcome::Pending(_)));
        assert!(matches!(state.begin_connect("a"), ConnectOutcome::Pending(_)));
        assert_eq!(state.begin_connect("a"), ConnectOutcome::StreamsExhausted);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (in `rendezvous/`): `cargo test relay::`
Expected: FAIL — `relay` module / types do not exist.

- [ ] **Step 3: Write minimal implementation**

`relay.rs`:

```rust
//! Relay session registry — the deterministic bookkeeping behind the WebSocket reverse tunnel
//! (`/v1/relay/*`). A host registers a session by id; a guest "connect" allocates a single-use
//! nonce and the host opens a matching "work" connection the relay then splices to the guest.
//!
//! This module owns *who pairs with whom* and the caps; the async WebSocket splice lives in
//! `main.rs`. All methods are synchronous and pure over `&self` (interior `Mutex`), so they are
//! deterministic to unit-test. `begin_connect` parks a pending stream the host claims via
//! `take_work`; the `oneshot` plumbing that hands the live socket across is wired in the handlers.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

#[derive(Clone, Copy)]
pub struct RelayLimits {
    pub max_sessions: usize,
    pub max_streams_per_session: usize,
}

#[derive(Debug, PartialEq, Eq)]
pub enum RegisterOutcome {
    Registered,
    AlreadyLive,
    CapacityReached,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ConnectOutcome {
    Pending(u64),
    UnknownSession,
    StreamsExhausted,
}

struct Session {
    open_streams: HashSet<u64>,
}

pub struct RelayState {
    limits: RelayLimits,
    sessions: Mutex<HashMap<String, Session>>,
    /// nonce -> the session it belongs to, until a host work conn claims it.
    pending: Mutex<HashMap<u64, String>>,
    next_nonce: AtomicU64,
}

impl RelayState {
    pub fn new(limits: RelayLimits) -> Self {
        RelayState {
            limits,
            sessions: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            next_nonce: AtomicU64::new(1),
        }
    }

    /// Register a host session. Refused if the id is already live (fencing) or the cap is hit.
    pub fn register(&self, session_id: &str) -> RegisterOutcome {
        let mut sessions = self.sessions.lock().unwrap();
        if sessions.contains_key(session_id) {
            return RegisterOutcome::AlreadyLive;
        }
        if sessions.len() >= self.limits.max_sessions {
            return RegisterOutcome::CapacityReached;
        }
        sessions.insert(session_id.to_string(), Session { open_streams: HashSet::new() });
        RegisterOutcome::Registered
    }

    /// Tear a session down (control link closed); its nonces are dropped.
    pub fn unregister(&self, session_id: &str) {
        self.sessions.lock().unwrap().remove(session_id);
        self.pending.lock().unwrap().retain(|_, sid| sid != session_id);
    }

    /// A guest stream wants in. Allocates a single-use nonce the host claims with `take_work`.
    pub fn begin_connect(&self, session_id: &str) -> ConnectOutcome {
        let mut sessions = self.sessions.lock().unwrap();
        let session = match sessions.get_mut(session_id) {
            Some(s) => s,
            None => return ConnectOutcome::UnknownSession,
        };
        if session.open_streams.len() >= self.limits.max_streams_per_session {
            return ConnectOutcome::StreamsExhausted;
        }
        let nonce = self.next_nonce.fetch_add(1, Ordering::Relaxed);
        session.open_streams.insert(nonce);
        self.pending.lock().unwrap().insert(nonce, session_id.to_string());
        ConnectOutcome::Pending(nonce)
    }

    /// The host opened a work conn for `nonce`. Returns the owning session id once (single-use).
    pub fn take_work(&self, nonce: u64) -> Option<String> {
        self.pending.lock().unwrap().remove(&nonce)
    }

    /// A stream finished; free its slot so the per-session cap reflects live streams.
    pub fn close_stream(&self, session_id: &str, nonce: u64) {
        if let Some(session) = self.sessions.lock().unwrap().get_mut(session_id) {
            session.open_streams.remove(&nonce);
        }
        self.pending.lock().unwrap().remove(&nonce);
    }

    pub fn live_sessions(&self) -> usize {
        self.sessions.lock().unwrap().len()
    }
}
```

Add to `main.rs` near the other module (line 18, `mod store;`):

```rust
mod relay;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test relay::`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add rendezvous/src/relay.rs rendezvous/src/main.rs
git commit -m "feat(rendezvous): relay session registry — nonce pairing + caps (cargo-tested)"
```

---

## Task 8: WebSocket relay handlers + splice (`main.rs`)

Wires the registry to three WS endpoints and splices the paired sockets. Real-socket glue → **build + manual smoke**, not unit tests.

**Files:**
- Modify: `rendezvous/src/main.rs`
- Modify: `rendezvous/Cargo.toml` (enable axum's `ws` feature + `futures-util`)

- [ ] **Step 1: Enable the WS dependency**

In `rendezvous/Cargo.toml`, ensure axum has the `ws` feature and add `futures-util`:

```toml
axum = { version = "0.7", features = ["ws"] }
futures-util = "0.3"
tokio = { version = "1", features = ["full"] }
```

> Match the existing axum major version already in `Cargo.toml`; only add the `ws` feature and `futures-util`. Run `cargo build` after editing to pull the new feature.

- [ ] **Step 2: Add relay state to `AppState` and the routes**

In `main.rs`, extend `AppState` (after `rate_windows`, line 55) and build the routes. Add fields:

```rust
    relay: relay::RelayState,
    relay_pending_tx: Mutex<HashMap<u64, tokio::sync::oneshot::Sender<axum::extract::ws::WebSocket>>>,
    relay_signal_tx: Mutex<HashMap<String, tokio::sync::mpsc::UnboundedSender<u64>>>,
```

Initialise them in `main()` (inside the `AppState { ... }` literal, after `rate_windows`):

```rust
        relay: relay::RelayState::new(relay::RelayLimits {
            max_sessions: env_or("RELAY_MAX_SESSIONS", 200),
            max_streams_per_session: env_or("RELAY_MAX_STREAMS_PER_SESSION", 16),
        }),
        relay_pending_tx: Mutex::new(HashMap::new()),
        relay_signal_tx: Mutex::new(HashMap::new()),
```

Add the routes to the `Router` (after `.route("/healthz", ...)`):

```rust
        .route("/v1/relay/host", get(relay_host))
        .route("/v1/relay/connect", get(relay_connect))
        .route("/v1/relay/work", get(relay_work))
```

Add imports at the top:

```rust
use axum::extract::ws::{Message as WsMessage, WebSocket, WebSocketUpgrade};
use axum::extract::Query;
use futures_util::{SinkExt, StreamExt};
```

- [ ] **Step 3: Implement the three handlers + splice**

Append to `main.rs`:

```rust
#[derive(Deserialize)]
struct RelayHostQuery { session: String }
#[derive(Deserialize)]
struct RelaySessionQuery { session: String }
#[derive(Deserialize)]
struct RelayWorkQuery { nonce: u64 }

const VALID_FIRST_BYTES: [u8; 3] = [0x01, 0x02, 0x03]; // ConnectionType CONTROL/DATA/SNAPSHOT

/// Host control link: register the session, then forward "open a work conn for nonce N" signals
/// to the host until the socket drops, at which point the session is torn down.
async fn relay_host(
    State(state): State<Arc<AppState>>,
    Query(q): Query<RelayHostQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    match state.relay.register(&q.session) {
        relay::RegisterOutcome::Registered => {}
        _ => return error_response(StatusCode::CONFLICT, "relay session unavailable"),
    }
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<u64>();
    state.relay_signal_tx.lock().unwrap().insert(q.session.clone(), tx);
    ws.on_upgrade(move |mut socket| async move {
        loop {
            tokio::select! {
                signal = rx.recv() => match signal {
                    Some(nonce) => {
                        if socket.send(WsMessage::Text(nonce.to_string())).await.is_err() { break; }
                    }
                    None => break,
                },
                // Drain client frames (pongs / close) so a dropped host is noticed promptly.
                inbound = socket.recv() => match inbound {
                    Some(Ok(_)) => {}
                    _ => break,
                },
            }
        }
        state.relay.unregister(&q.session);
        state.relay_signal_tx.lock().unwrap().remove(&q.session);
        tracing::info!(session = %q.session, "relay host link closed");
    })
}

/// Guest stream: allocate a nonce, signal the host, then wait for its work conn and splice.
async fn relay_connect(
    State(state): State<Arc<AppState>>,
    Query(q): Query<RelaySessionQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    let nonce = match state.relay.begin_connect(&q.session) {
        relay::ConnectOutcome::Pending(n) => n,
        relay::ConnectOutcome::UnknownSession =>
            return error_response(StatusCode::NOT_FOUND, "no such relay session"),
        relay::ConnectOutcome::StreamsExhausted =>
            return error_response(StatusCode::TOO_MANY_REQUESTS, "session stream cap reached"),
    };
    let signal = state.relay_signal_tx.lock().unwrap().get(&q.session).cloned();
    let signal = match signal {
        Some(tx) => tx,
        None => { state.relay.close_stream(&q.session, nonce); return error_response(StatusCode::NOT_FOUND, "host gone"); }
    };
    let (work_tx, work_rx) = tokio::sync::oneshot::channel::<WebSocket>();
    state.relay_pending_tx.lock().unwrap().insert(nonce, work_tx);
    if signal.send(nonce).is_err() {
        state.relay_pending_tx.lock().unwrap().remove(&nonce);
        state.relay.close_stream(&q.session, nonce);
        return error_response(StatusCode::NOT_FOUND, "host gone");
    }
    let session = q.session.clone();
    let timeout_ms: u64 = env_or("RELAY_WORKCONN_TIMEOUT_MS", 8000);
    ws.on_upgrade(move |guest| async move {
        let host_work = tokio::time::timeout(
            std::time::Duration::from_millis(timeout_ms),
            work_rx,
        ).await;
        match host_work {
            Ok(Ok(host)) => splice(guest, host).await,
            _ => {
                state.relay_pending_tx.lock().unwrap().remove(&nonce);
                tracing::info!(%nonce, "relay work conn never arrived");
            }
        }
        state.relay.close_stream(&session, nonce);
    })
}

/// Host work conn: hand this live socket to the waiting guest for `nonce`.
async fn relay_work(
    State(state): State<Arc<AppState>>,
    Query(q): Query<RelayWorkQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    if state.relay.take_work(q.nonce).is_none() {
        return error_response(StatusCode::NOT_FOUND, "no pending stream for that nonce");
    }
    let tx = state.relay_pending_tx.lock().unwrap().remove(&q.nonce);
    ws.on_upgrade(move |host| async move {
        if let Some(tx) = tx {
            let _ = tx.send(host); // the guest task owns the splice
        }
    })
}

/// Copy binary frames both ways until either side closes. Validates the first byte of the guest
/// stream is a known jukz ConnectionType, so the relay can only carry jukz traffic (not an open
/// proxy). Bounded by the WS library's own backpressure.
async fn splice(guest: WebSocket, host: WebSocket) {
    let (mut guest_tx, mut guest_rx) = guest.split();
    let (mut host_tx, mut host_rx) = host.split();
    let mut first = true;

    let g2h = async {
        while let Some(Ok(msg)) = guest_rx.next().await {
            if let WsMessage::Binary(bytes) = &msg {
                if first {
                    first = false;
                    if bytes.first().map_or(true, |b| !VALID_FIRST_BYTES.contains(b)) {
                        break; // not a jukz channel — refuse to relay
                    }
                }
                if host_tx.send(WsMessage::Binary(bytes.clone())).await.is_err() { break; }
            } else if matches!(msg, WsMessage::Close(_)) { break; }
        }
        let _ = host_tx.close().await;
    };
    let h2g = async {
        while let Some(Ok(msg)) = host_rx.next().await {
            if let WsMessage::Binary(bytes) = &msg {
                if guest_tx.send(WsMessage::Binary(bytes.clone())).await.is_err() { break; }
            } else if matches!(msg, WsMessage::Close(_)) { break; }
        }
        let _ = guest_tx.close().await;
    };
    tokio::join!(g2h, h2g);
}
```

- [ ] **Step 4: Build + manual smoke**

Run: `cargo build` (Expected: compiles clean) and `cargo test` (Expected: existing + relay registry tests pass).

Manual smoke (local): `cargo run`, then with a WS client (e.g. `websocat`):
1. `websocat 'ws://127.0.0.1:8080/v1/relay/host?session=smoke'` — leave open.
2. In another shell, `printf '\x01hello' | websocat -b 'ws://127.0.0.1:8080/v1/relay/connect?session=smoke'` — it blocks waiting for a work conn (you'll see the host shell receive a nonce line).
3. Take that nonce N: `websocat -b 'ws://127.0.0.1:8080/v1/relay/work?nonce=N'` — the guest's `\x01hello` bytes now arrive on the work conn. Confirms pairing + first-byte gate (try a first byte of `\x09` and confirm it is dropped).

- [ ] **Step 5: Commit**

```bash
git add rendezvous/src/main.rs rendezvous/Cargo.toml rendezvous/Cargo.lock
git commit -m "feat(rendezvous): WebSocket relay endpoints (host/connect/work) + byte-gated splice"
```

---

## Task 9: Rust record round-trips the relay offer + env/health/README

The guest learns the relay session from its **lookup**, so the rendezvous `WorldRecord` must carry the optional relay field through announce → store → lookup.

**Files:**
- Modify: `rendezvous/src/store.rs` (struct field), `rendezvous/src/main.rs` (announce body), `rendezvous/README.md`

- [ ] **Step 1: Add the optional field (cargo build is the check)**

In `store.rs`, add to `WorldRecord` (after `heartbeat_seq`, line 51):

```rust
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub relay: Option<RelayInfo>,
```

And the type (near `Endpoint`):

```rust
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayInfo {
    pub session_id: String,
}
```

Update the test helper `record(...)` in `store.rs` tests to set `relay: None` (every `WorldRecord { ... }` literal in tests needs the new field). Run `cargo test` and fix each literal the compiler flags.

In `main.rs`, add `relay` to `AnnounceBody` and carry it into the stored record:

```rust
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AnnounceBody {
    world_id: Uuid,
    token: Token,
    endpoints: Vec<Endpoint>,
    heartbeat_seq: i64,
    #[serde(default)]
    relay: Option<store::RelayInfo>,
}
```

In `announce(...)`, include it when building the record (line 253):

```rust
    let record = WorldRecord { world_id: body.world_id, token, endpoints, heartbeat_seq: body.heartbeat_seq, relay: body.relay };
```

Update the `import` line for store types (line 35) to include `RelayInfo` if you reference it unqualified; otherwise `store::RelayInfo` as above is fine.

- [ ] **Step 2: Build/test**

Run: `cargo test`
Expected: PASS (all literals updated; the relay field round-trips through serde).

- [ ] **Step 3: Doc + env table**

In `rendezvous/README.md`, under "Configuration (env)" add the relay vars, and replace the "Limits (v1)" bullet *"Discovery only: no relay/signaling…"* with a short paragraph noting the relay now exists (WS reverse tunnel, outbound-only, byte-gated, capped). Add to the env table:

```markdown
| `RELAY_MAX_SESSIONS` | `200` | Max live relay sessions |
| `RELAY_MAX_STREAMS_PER_SESSION` | `16` | Max in-flight streams per session |
| `RELAY_WORKCONN_TIMEOUT_MS` | `8000` | How long a guest waits for the host's work conn |
```

- [ ] **Step 4: Commit**

```bash
git add rendezvous/src/store.rs rendezvous/src/main.rs rendezvous/README.md
git commit -m "feat(rendezvous): round-trip the optional relay offer in the world record + docs"
```

---

## Task 10: `RendezvousWorldRegistry` maps the relay offer (Kotlin JSON)

**Files:**
- Modify: `fabric/src/main/kotlin/dev/jukz/discovery/RendezvousWorldRegistry.kt` (announceBody + recordFromJson)

- [ ] **Step 1: Implement (no unit test — network adapter; verified in Task 14)**

In `announceBody` (line 186-191), add the relay offer when present:

```kotlin
    private fun announceBody(record: WorldRecord): JsonObject = JsonObject().apply {
        addProperty("worldId", record.worldId.uuid.toString())
        add("token", tokenToJson(record.token))
        add("endpoints", endpointsToJson(record.endpoints))
        addProperty("heartbeatSeq", record.heartbeatSeq)
        record.relay?.let { add("relay", JsonObject().apply { addProperty("sessionId", it.sessionId) }) }
    }
```

In `recordFromJson` (line 208-223), parse it back:

```kotlin
            heartbeatSeq = json.get("heartbeatSeq").asLong,
            relay = json.getAsJsonObject("relay")?.let { RelayOffer(it.get("sessionId").asString) },
```

Add the import:

```kotlin
import dev.jukz.core.discovery.RelayOffer
```

> `JsonObject.getAsJsonObject` returns null when the key is absent (gson), so a v3/relayless record parses with `relay = null`.

- [ ] **Step 2: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/discovery/RendezvousWorldRegistry.kt
git commit -m "feat(fabric): carry the relay offer through the rendezvous announce/lookup JSON"
```

---

## Task 11: `WsRelayTransport` (guest WebSocket → JukzChannel)

Adapts Java 11's message-oriented `WebSocket` to the byte-stream `JukzChannel` the join flow expects, by bridging through piped streams.

**Files:**
- Create: `fabric/src/main/kotlin/dev/jukz/transport/WsRelayTransport.kt`

- [ ] **Step 1: Implement**

```kotlin
package dev.jukz.transport

import dev.jukz.JukzMod
import dev.jukz.core.transport.JukzChannel
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Opens a [JukzChannel] to the rendezvous relay for a host's session, over a binary WebSocket on the
 * same HTTPS origin as the rendezvous (`wss://.../v1/relay/connect?session=<id>`). Incoming binary
 * frames are funnelled into a [PipedInputStream] the join flow reads; bytes written to the channel's
 * [OutputStream] are sent as binary frames. This is the guest side of the reverse tunnel — selected
 * by the connect ladder only after every direct endpoint fails.
 *
 * FLAGGED: real WebSocket I/O against the live relay; validated in-game (Task 14), not unit-tested.
 */
class WsRelayTransport(rendezvousBaseUrl: String) {

    private val wsBase = rendezvousBaseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/')
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Connect a channel to [sessionId]; suspends until the WS handshake completes. */
    fun connect(sessionId: String): JukzChannel {
        val toApp = PipedOutputStream()
        val appIn = PipedInputStream(toApp, 64 * 1024)

        val listener = object : WebSocket.Listener {
            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining()); data.get(bytes)
                runCatching { toApp.write(bytes); toApp.flush() }
                ws.request(1)
                return null
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                runCatching { toApp.close() }
                return null
            }
            override fun onError(ws: WebSocket, error: Throwable) {
                JukzMod.logger.info("jukz: relay WS error: {}", error.message)
                runCatching { toApp.close() }
            }
        }

        val ws = http.newWebSocketBuilder()
            .buildAsync(URI.create("$wsBase/v1/relay/connect?session=$sessionId"), listener)
            .get(15, TimeUnit.SECONDS)

        val appOut = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val chunk = ByteBuffer.wrap(b.copyOfRange(off, off + len))
                ws.sendBinary(chunk, true).get(15, TimeUnit.SECONDS)
            }
            override fun close() { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS) } }
        }

        return object : JukzChannel {
            override fun inputStream(): InputStream = appIn
            override fun outputStream(): OutputStream = appOut
            override fun close() {
                runCatching { appOut.close() }
                runCatching { appIn.close() }
                ws.abort()
            }
        }
    }
}
```

> Java's `WebSocket` delivers one credit at a time; the listener calls `ws.request(1)` to keep frames flowing. Writes are flushed synchronously (`.get(...)`) so a small handshake write isn't buffered indefinitely — acceptable for Minecraft's bursty stream; revisit if throughput suffers (Task 14 notes).

- [ ] **Step 2: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/transport/WsRelayTransport.kt
git commit -m "feat(fabric): WsRelayTransport — guest WebSocket relay channel"
```

---

## Task 12: `WsRelayClient` (host `RelayRegistrar`) + `RecordingPortForwarder`

The host opens the control link, bridges each work conn to the local connection-server port, and only registers when UPnP did not open the port.

**Files:**
- Create: `fabric/src/main/kotlin/dev/jukz/transport/WsRelayClient.kt`
- Create: `fabric/src/main/kotlin/dev/jukz/transport/RecordingPortForwarder.kt`

- [ ] **Step 1: Implement `RecordingPortForwarder`**

```kotlin
package dev.jukz.transport

import dev.jukz.core.host.PortForwarder

/**
 * Wraps a [PortForwarder] and remembers whether its last [open] actually mapped a port, so the host
 * wiring can decide — after the resolver has run — whether to register a relay fallback. UPnP that
 * succeeded means internet guests reach the port directly; only when it returned null (no IGD /
 * CGNAT) does the host need the relay.
 */
class RecordingPortForwarder(private val delegate: PortForwarder) : PortForwarder {
    @Volatile var lastMapping: String? = null
        private set

    override fun open(port: Int): String? = delegate.open(port).also { lastMapping = it }

    /** True when the most recent [open] opened nothing — the host should register a relay session. */
    fun upnpFailed(): Boolean = lastMapping == null
}
```

- [ ] **Step 2: Implement `WsRelayClient`**

```kotlin
package dev.jukz.transport

import dev.jukz.JukzMod
import dev.jukz.core.discovery.RelayOffer
import dev.jukz.core.host.RelayRegistrar
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Host side of the relay reverse tunnel and a [RelayRegistrar]. On [register] it opens an outbound
 * control link (`wss://.../v1/relay/host?session=<id>`); each signalled nonce makes it open a work
 * conn (`/v1/relay/work?nonce=N`) and a loopback socket to the local connection server, pumping bytes
 * between them. The host's [dev.jukz.core.host.HostConnectionServer] is unchanged — it just receives
 * loopback connections that originated from a remote guest. Returns the [RelayOffer] to advertise, or
 * null when [shouldRegister] says the direct path already works (UPnP mapped) or no rendezvous is set.
 *
 * FLAGGED: real WebSocket I/O; validated in-game (Task 14).
 */
class WsRelayClient(
    private val rendezvousBaseUrl: String?,
    private val shouldRegister: () -> Boolean,
) : RelayRegistrar {

    private val http: HttpClient = HttpClient.newHttpClient()
    @Volatile private var control: WebSocket? = null

    override fun register(listenPort: Int): RelayOffer? {
        val base = rendezvousBaseUrl ?: return null
        if (!shouldRegister()) {
            JukzMod.logger.info("jukz: UPnP mapped the port; not registering a relay session")
            return null
        }
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/')
        val sessionId = newSessionId()
        return try {
            control = http.newWebSocketBuilder()
                .buildAsync(URI.create("$wsBase/v1/relay/host?session=$sessionId"), HostListener(wsBase, listenPort))
                .get(15, TimeUnit.SECONDS)
            JukzMod.logger.info("jukz: registered relay session for non-UPnP host")
            RelayOffer(sessionId)
        } catch (e: Exception) {
            JukzMod.logger.warn("jukz: relay registration failed ({}); internet guests need UPnP/forward", e.message)
            null
        }
    }

    fun close() { runCatching { control?.abort() } }

    /** Reads work-conn signals (one nonce per text frame) and bridges each to the local game port. */
    private inner class HostListener(private val wsBase: String, private val listenPort: Int) : WebSocket.Listener {
        override fun onOpen(ws: WebSocket) { ws.request(1) }
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val nonce = data.toString().trim().toLongOrNull()
            if (nonce != null) {
                Thread({ openWorkConn(nonce) }, "jukz-relay-work").apply { isDaemon = true }.start()
            }
            ws.request(1)
            return null
        }
        private fun openWorkConn(nonce: Long) {
            val local = try {
                Socket().apply { connect(InetSocketAddress("127.0.0.1", listenPort), 4000) }
            } catch (e: Exception) {
                JukzMod.logger.info("jukz: relay work conn could not reach local game: {}", e.message); return
            }
            val workListener = object : WebSocket.Listener {
                override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                    val bytes = ByteArray(data.remaining()); data.get(bytes)
                    runCatching { local.getOutputStream().write(bytes); local.getOutputStream().flush() }
                    ws.request(1)
                    return null
                }
                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    runCatching { local.close() }; return null
                }
                override fun onError(ws: WebSocket, error: Throwable) { runCatching { local.close() } }
            }
            val work = http.newWebSocketBuilder()
                .buildAsync(URI.create("$wsBase/v1/relay/work?nonce=$nonce"), workListener)
                .get(15, TimeUnit.SECONDS)
            // Pump local game bytes → work conn.
            Thread({
                val buf = ByteArray(8 * 1024)
                try {
                    val input = local.getInputStream()
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        work.sendBinary(ByteBuffer.wrap(buf.copyOfRange(0, n)), true).get(15, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { work.sendClose(WebSocket.NORMAL_CLOSURE, "eof").get(5, TimeUnit.SECONDS) }
                    runCatching { local.close() }
                }
            }, "jukz-relay-pump").apply { isDaemon = true }.start()
        }
    }

    private fun newSessionId(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/transport/WsRelayClient.kt fabric/src/main/kotlin/dev/jukz/transport/RecordingPortForwarder.kt
git commit -m "feat(fabric): WsRelayClient (host reverse tunnel) + RecordingPortForwarder gate"
```

---

## Task 13: Wire the dialer (guest) and the registrar (host)

**Files:**
- Modify: `fabric/src/main/kotlin/dev/jukz/client/JoinCoordinator.kt:39-51`
- Modify: `fabric/src/main/kotlin/dev/jukz/client/HostCoordinator.kt:98-116`
- Create: `fabric/src/main/kotlin/dev/jukz/transport/CompositeChannelDialer.kt`

- [ ] **Step 1: Composite dialer**

```kotlin
package dev.jukz.transport

import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.Transport

/**
 * Production [ChannelDialer]: a direct TCP [Transport] for [DialTarget.Direct], and the relay
 * WebSocket for [DialTarget.ViaRelay]. The guest's ladder tries direct targets first, so the relay
 * client is only invoked when every direct endpoint has failed.
 */
class CompositeChannelDialer(
    private val direct: Transport = DirectTcpTransport(),
    private val relay: WsRelayTransport,
) : ChannelDialer {
    override suspend fun dial(target: DialTarget): JukzChannel = when (target) {
        is DialTarget.Direct -> direct.connect(target.endpoint)
        is DialTarget.ViaRelay -> relay.connect(target.sessionId)
    }
}
```

- [ ] **Step 2: Wire the guest (`JoinCoordinator`)**

Replace the `transport` default param (lines 44 + 48) so the controller uses the composite dialer built from the configured rendezvous URL. Change the `start(...)` signature and the controller construction:

```kotlin
    fun start(
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        registry: WorldRegistry = Discovery.registry,
        dialer: ChannelDialer = defaultDialer(),
    ) {
        val client = MinecraftClient.getInstance()
        val handoff: GameHandoff = MinecraftGameHandoff { parent }
        val controller = JoinController(
            registry, dialer, handoff, SystemClock,
            onHostLost = { wid, offer -> onHostLeaving(client, wid, shortCode, offer) },
        )
```

Add a helper + imports:

```kotlin
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DirectChannelDialer
import dev.jukz.config.JukzConfig
import dev.jukz.transport.CompositeChannelDialer
import dev.jukz.transport.WsRelayTransport
```

```kotlin
    /** Direct TCP, plus the relay WS when an internet rendezvous is configured (else direct-only). */
    private fun defaultDialer(): ChannelDialer {
        val url = JukzConfig.rendezvousUrl ?: return DirectChannelDialer()
        return CompositeChannelDialer(relay = WsRelayTransport(url))
    }
```

> Replace any remaining reference to the old `transport` param (e.g. in `onRetry = { start(worldId, shortCode, parent) }` it just omits the arg, so it keeps working with the default).

- [ ] **Step 3: Wire the host (`HostCoordinator.runHost`)**

In `runHost` (lines 98-116), share a `RecordingPortForwarder` between the resolver and the relay registrar:

```kotlin
    private fun runHost(server: IntegratedServer): HostResult {
        val (worldId, generation) = bumpGeneration(server)
        val forwarder = RecordingPortForwarder(UpnpPortForwarder())
        val relayClient = WsRelayClient(JukzConfig.rendezvousUrl, shouldRegister = { forwarder.upnpFailed() })
        val controller = HostController(
            registry = Discovery.registry,
            lanOpener = MinecraftLanOpener(server),
            connectionServer = HostConnectionServer(),
            endpointResolver = ForwardingEndpointResolver(forwarder, LocalEndpointResolver()),
            nodeId = PersistentNodeId.nodeId,
            clock = SystemClock,
            playerCount = { runCatching { server.playerManager.playerList.size }.getOrDefault(0) },
            relayRegistrar = relayClient,
        )
        val result = runBlocking { controller.host(worldId, generation) }
        if (result is HostResult.Hosting) HostSession.install(controller) else { relayClient.close(); controller.close() }
        return result
    }
```

Add imports:

```kotlin
import dev.jukz.config.JukzConfig
import dev.jukz.transport.RecordingPortForwarder
import dev.jukz.transport.WsRelayClient
```

> The registrar runs *inside* `controller.host(...)` after `endpointResolver.resolve(...)`, which is where `forwarder.open(...)` executes — so `forwarder.upnpFailed()` reflects the real UPnP result when the registrar reads it. `WsRelayClient.close()` should also be called when the host stops; if `HostSession` owns controller teardown, add a `relayClient.close()` hook there (see `HostSession.install`), otherwise the daemon WS is abandoned on JVM exit (acceptable, but prefer explicit close).

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (core tests green, fabric compiles + its test sourceSet compiles).

- [ ] **Step 5: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/transport/CompositeChannelDialer.kt fabric/src/main/kotlin/dev/jukz/client/JoinCoordinator.kt fabric/src/main/kotlin/dev/jukz/client/HostCoordinator.kt
git commit -m "feat(fabric): wire the composite dialer (guest) and UPnP-gated relay registrar (host)"
```

---

## Task 14: End-to-end forced-relay validation

The relay path only fires when **all** direct endpoints fail. To exercise it deliberately on one machine, force the direct candidate to be unreachable.

**Files:** none (validation + notes). Optionally add a temporary dev flag.

- [ ] **Step 1: Deploy the relay**

Deploy the updated `rendezvous` (the live `jukz.nuulm.com`), or run it locally and point both clients at it: set `rendezvous.url=http://127.0.0.1:8080` in each instance's `config/jukz.properties`. Confirm `GET /healthz` responds.

- [ ] **Step 2: Force the direct path to fail**

On the host machine, ensure UPnP is **off** (so the host registers a relay session — check the log line `registered relay session for non-UPnP host`) and the host's observed/LAN endpoint is **not** reachable from the guest. The simplest one-box method: run host (`runClientA`) and guest (`runClientB`) and block the host's connection-server port from the guest, or temporarily edit the host so its advertised direct endpoint is a black-hole address (`203.0.113.255`) while keeping the relay offer. The guest's ladder should log direct failures then connect via the relay.

- [ ] **Step 3: Confirm play over the relay**

Expected, in order:
- Host log: `registered relay session for non-UPnP host`.
- Guest log: direct endpoint connect failures (short timeout), then `joined host at 127.0.0.1:<relayPort>`.
- Relay log: a host link, a guest connect (nonce allocated), a work conn (nonce matched), splice established.
- In-game: the guest spawns into the host's world and can move/interact (DATA bytes flow through the relay).

**v1 reality check (the Task 5 note):** verify the **DATA** channel actually traverses the relay, not just CONTROL. When the host's `HostStateMachine` answers `Connect` with its own connection-server endpoint, the guest wraps it as `DialTarget.Direct(thatEndpoint)` — which is the host's loopback/LAN address and will **fail** from an internet guest. If you observe control connecting but the player never spawning (data stalls), the fix is: when the *winning* target was `ViaRelay`, the guest must open its DATA channel `ViaRelay` too. Implement that as a one-line stickiness in `JoinController.handoff` — capture the *handshake* target and reuse it for data rather than the `action.endpoint`:

```kotlin
// in runHandshake, JoinerAction.Connect branch — keep dialing the way control succeeded:
is JoinerAction.Connect ->
    return handoff(worldId, record, if (current is DialTarget.ViaRelay) current else DialTarget.Direct(action.endpoint), sm)
```

Add this if Step 3 shows data stalling over the relay; re-run to confirm the player spawns. Commit separately:

```bash
git commit -am "fix(core): keep DATA on the relay when control connected via relay"
```

- [ ] **Step 4: Confirm graceful handoff degradation**

Close the host while the relay guest is connected. Expected: the guest gets the abrupt-drop `onHostLost(worldId, null)` (the control channel breaks through the relay), and `HostHandoffScreen` offers a takeover from the guest's **local copy** (no snapshot pulled over the relay yet — that is Task 15). If the guest had no local copy, it surfaces "couldn't get a copy" — acceptable for v1.

- [ ] **Step 5: Record the result**

Update `README.md` "Follow-ups": mark non-UPnP/CGNAT play as **working over the relay**, and add Task 15 (handoff snapshot over the relay) as the remaining gap. Commit:

```bash
git commit -am "docs(readme): relay path validated — non-UPnP/CGNAT play works; handoff-over-relay pending"
```

---

## Task 15 (follow-up, out of v1 critical path): snapshot handoff over the relay

When a relay-connected guest takes over, route the SNAPSHOT pull through the relay instead of a direct socket, so a host that left can still hand off its save cross-internet.

**Sketch (not bite-sized — promote to its own plan when scheduled):**
- `JoinController.startLiveness` already rewrites the `SnapshotOffer` to "where I actually reached the host." For a relay session, encode that the pull is `ViaRelay(sessionId)` rather than a host/port.
- `JGitWorldSync.downloadSnapshot(offer)` (fabric) currently opens its own `Socket` with `ConnectionType.SNAPSHOT`. Give it the `ChannelDialer` (or a `DialTarget`) so it opens the SNAPSHOT channel the same way play connects — direct or relay.
- The host already serves SNAPSHOT on the same connection-server port, so no server change: the relay work conn carries the SNAPSHOT byte transparently, exactly like CONTROL/DATA.
- Validate with a forced-relay A→B handoff (host leaves; guest pulls the pack over the relay; generations climb).

---

## Self-review notes (author)

- **Spec coverage:** §4.1 relay endpoints → Tasks 7–8; §4.2 host RelayClient + UPnP gate → Tasks 6,12,13; §4.3 guest ladder + RelayTransport → Tasks 3,4,5,11,13; §4.4 wire v4 → Tasks 1,2,9,10; §5 data flow → Tasks 8,12,14; §8 security (not-open-proxy first-byte gate, caps, session fencing) → Tasks 7,8; §6 ladder → Tasks 4,5; §9 edge cases (scale-to-zero, stale session, nonce timeout) → Task 8; §11 testing → per-task + Task 14. The handoff-over-relay ("for free" claim, §5) is **corrected** to Task 15 — it is not automatic because `JGitWorldSync.downloadSnapshot` opens its own socket.
- **Type consistency:** `RelayOffer(sessionId)`, `WorldRecord.relay`, `DialTarget.Direct/ViaRelay`, `ChannelDialer.dial`, `DirectChannelDialer`, `WorldRecord.dialTargets()`, `RelayRegistrar.register(listenPort)`, Rust `RelayState`/`RegisterOutcome`/`ConnectOutcome`/`RelayInfo.session_id` — used identically across tasks.
- **Known soft spots to watch during execution:** (1) the `Transport` SAM-with-suspend in the Task 3 test (fallback to `object : Transport` given); (2) reusing existing `HostControllerTest` fakes in Task 6 rather than redefining; (3) the DATA-over-relay stickiness flagged inline in Task 5 and verified/fixed in Task 14.
