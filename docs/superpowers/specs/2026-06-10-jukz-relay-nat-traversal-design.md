# jukz — relay path for non-UPnP / CGNAT hosts

> Design spec. Status: **draft 2026-06-10**. Target: Minecraft 1.21.1, Java 21, Fabric;
> rendezvous server is Rust + Axum. All source code is written in English; this spec is the
> design record.

## 1. Problem

Today a guest reaches a host by dialing the host's TCP listener at the address the
rendezvous server observed (`RendezvousWorldRegistry` + the server appending the announcer's
public IP). That only connects when the host's **port** is actually open to the internet —
which means the host has working **UPnP** (`UpnpPortForwarder`) or a manual port-forward.

The target population — players in Argentina and Colombia without UPnP — is largely behind
**CGNAT** (carrier-grade NAT) on home and mobile ISPs. CGNAT has no UPnP to map, and UDP
hole punching typically fails there (symmetric NAT). So the "elegant P2P" technique is
exactly the one that does **not** cover this population.

The rendezvous README already lists this as the standing v1 limit:
*"Discovery only: no relay/signaling. NAT reachability still depends on the host's UPnP
mapping or manual port-forward."* This spec closes that gap.

## 2. Decisions (made in brainstorming, 2026-06-10)

1. **Relay first, not hole punching.** A relay is the only path that connects *everyone*,
   including CGNAT/symmetric, because host and guest both make **outbound** connections to
   the relay, which splices their two streams. Hole punching is deferred (it would leave
   CGNAT users out and force a TCP→UDP/QUIC transport rewrite).
2. **The relay lives inside the existing rendezvous service** (same Rust/Axum process, same
   `jukz.nuulm.com` HTTPS deployment), reusing its domain, deploy, auth, and rate limiter.
   Split into its own service later if bandwidth demands it.
3. **Direct first, relay as fallback.** The host advertises *both* its observed public
   endpoint and a relay session. The guest tries the direct endpoint(s) with a short timeout;
   only when all direct attempts fail does it use the relay. Cone-NAT homes that happen to be
   reachable on the observed IP play P2P for free; only CGNAT/symmetric pay the relay.
4. **The tunnel is WebSocket over the existing HTTPS port (`:443`).** Rides the one already
   exposed `http_service` (no new TCP port to expose on Fly/Railway), traverses strict
   firewalls / captive portals (looks like normal web traffic), and gets TLS for free. Raw
   TCP on a dedicated port was rejected: it needs extra hosting config and bare TCP to an
   odd port is exactly what restrictive networks block.

## 3. Out of scope (YAGNI for v1)

- **Hole punching / ICE / TURN-UDP.** The relay covers everyone; the flagged
  `IceTransport` / `HolePuncher` / `StunEndpointResolver` blueprints stay flagged. Hole
  punching may return later as a *latency optimization* layered under the same ladder.
- **Multi-region relay.** One existing deployment. AR↔CO via the current region adds a hop;
  acceptable for v1, region/placement revisited if latency hurts.
- **End-to-end encryption against the relay itself.** WSS encrypts each leg on the wire, but
  the relay sees plaintext by design (it must, to splice). True E2E host↔guest *through* the
  relay is future hardening (see §8).
- **Cryptographic world-ownership proof.** Inherited limit: anyone with the world code can
  host/join. The relay does not make this worse; per-world keypairs remain `/v2` work.
- **Bandwidth metering / billing.** Only safety caps + logging (§8), no accounting.
- **Universal relay backup.** The host registers a relay session *only when UPnP mapping
  failed* (the target case), not as a backup for every host.

## 4. Architecture

Three cooperating pieces, one per existing module boundary.

### 4.1 Relay server (`rendezvous`, Rust/Axum + WebSocket)

Three new WebSocket endpoints on the existing HTTPS service (alongside `/v1/announce` etc.).
All sit behind the existing `guard()` (per-IP rate limit + optional bearer auth).

| Endpoint | Role |
|---|---|
| `GET /v1/relay/host?session={S}` (WS upgrade) | **Host control link.** The host opens this and parks it. The relay creates/holds a session keyed by `S`. Used relay→host only, to signal `open-workconn <nonce>`. |
| `GET /v1/relay/connect?session={S}` (WS upgrade) | **Guest stream** (one per jukz channel). Relay: validate `S` is live → allocate a `nonce N` → signal the host's control link → wait for the matching host workconn → splice guest⇄workconn. |
| `GET /v1/relay/work?nonce={N}` (WS upgrade) | **Host workconn**, opened in response to a signal. Relay matches it to the waiting guest by `N` and splices. |

Server state (in-memory, like the discovery store):
- `sessions: Map<SessionId, HostControlLink>` — live registrations.
- `pending: Map<Nonce, WaitingGuest>` — guest streams awaiting their host workconn.
- Both swept on timeout; a session is torn down when its control link drops.

Splice = copy bytes both ways between the two WS sockets as **binary** messages, with
bounded buffers and backpressure (stop reading one side when the other is full). Close both
on either side's EOF/error. The relay never interprets the payload beyond the optional
first-byte check in §8.

### 4.2 Host side (`fabric`, `RelayClient`)

A new `RelayClient` that:
1. Opens the WS control link `/v1/relay/host?session=S` and keeps it alive (reconnect with
   the **same** `sessionId` on drop).
2. On each `open-workconn N` signal: opens `/v1/relay/work?nonce=N` **and** a loopback TCP
   socket to the host's local `HostConnectionServer` port, then pumps bytes both ways
   (WS binary ⇄ TCP).

The **`HostConnectionServer` is unchanged**: it still `accept()`s on its local port and
routes by the first `ConnectionType` byte. The relay path just delivers it loopback
connections that originated from a remote guest. No new locally-exposed surface on the host
(loopback bind + outbound WS only).

**When the host registers a relay session:** only when the UPnP mapping failed (the host has
no confirmed public port-forward). It still binds locally and still advertises its observed
public endpoint, so the guest can try direct first. `sessionId` is fresh random per host
session; closing the control link on withdraw/shutdown tears down the relay session.

Wiring: `HostController` already resolves the endpoint via an `EndpointResolver`
(`ForwardingEndpointResolver` wraps UPnP). When UPnP yields no mapping, the host start path
additionally starts a `RelayClient` and attaches a `RelayOffer(sessionId)` to the announced
record.

### 4.3 Guest side (`core` policy + `fabric` transport)

- **`core`: the connect ladder as a pure policy.** Given the candidate direct endpoints and
  an optional `RelayOffer`, decide how to obtain a `Transport`/channel: try each direct
  endpoint with a short timeout; on the first success use it; if all fail and a `RelayOffer`
  is present, use the relay. Pure over the `Transport` seam → unit-testable with fakes.
- **`fabric`: `RelayTransport(relayBaseUrl, sessionId)`** — a `Transport` that opens
  `/v1/relay/connect?session=S` and adapts the WS to a `JukzChannel` (the same interface
  `DirectTcpTransport` produces), then feeds the existing `LocalTcpRelay` exactly as a direct
  connection does.
- **Sticky decision:** CONTROL/DATA/SNAPSHOT are separate channels (separate connects). The
  first successful channel (CONTROL) pins whether the session is *direct to endpoint E* or
  *relay to session S*; DATA/SNAPSHOT reuse that decision instead of re-probing direct.

### 4.4 Discovery wire (`core`, `WorldRecord` v4)

`WorldRecord` gains an optional `RelayOffer(sessionId: String)`, added the same way
`SnapshotOffer` / `playerCount` were added at v3. Wire bumps to **v4**; v1/v2/v3 still decode
(older clients simply never fall back to relay). The Rust store mirrors the new optional
field (it is opaque to the server — round-tripped, not interpreted).

## 5. Data flow (relay path, end to end)

```
HOST (no UPnP)                 RELAY (jukz.nuulm.com, WSS)              GUEST (no UPnP)
  bind 127.0.0.1:P
  WS /relay/host?session=S ──register──►  [session S → hostCtl link]
                                              ▲  │
  HostConnectionServer on P                   │  │ signal "workconn N"
                                              │  ▼
                       ◄── WS /relay/connect?session=S ──────────────  RelayTransport (CONTROL)
                          allocate nonce N, signal hostCtl ───────────►
  recv "workconn N"
  WS /relay/work?nonce=N ──►  [match N] ◄──── guest stream waiting
  dial 127.0.0.1:P ─┐         splice  WS(host work) ⇄ WS(guest)
   pump P ⇄ workWS ─┘
  ── first byte = CONTROL flows: guest → relay → host work → 127.0.0.1:P → HostConnectionServer
  (repeat per channel: DATA, SNAPSHOT each = new guest connect → new nonce → new workconn)
```

Because the F4 world-handoff already rides the same `ConnectionType.SNAPSHOT` channel on the
same port, **the handoff travels over the relay for free** — closing the remaining
cross-internet handoff gap with no extra work.

## 6. Connect ladder (guest)

1. Read the `WorldRecord`. Collect direct candidate endpoints (advertised + observed).
2. For each direct endpoint: `DirectTcpTransport.connect` with a short timeout (~2–3 s).
   First success → pin "direct via E".
3. If all direct attempts fail and the record has a `RelayOffer` → pin "relay via S",
   connecting `RelayTransport`.
4. If neither yields a channel → the existing "host unavailable" surface.
5. Subsequent channels (DATA/SNAPSHOT) reuse the pinned factory.

## 7. core / fabric / rust split (respects the repo architecture)

- **`core`** (Minecraft-free, JUnit): `RelayOffer` model + `WorldRecord` v4 codec; the connect
  ladder policy. Fully unit-tested.
- **`fabric`** (Minecraft + real network, flagged / manually validated): `RelayClient` (host),
  `RelayTransport` (guest), and the host/guest wiring.
- **`rendezvous`** (Rust, `cargo test`): the relay session registry + nonce matching
  (deterministic). Live-socket splicing validated manually.

## 8. Security

The relay is the only new surface that carries third-party traffic; measures by threat:

1. **Not an open proxy (by construction).** The relay never dials a user-supplied address —
   it only pairs a guest with *the host holding session S*. The host only bridges workconns
   to `127.0.0.1:P` (its own listener), never elsewhere. So the relay cannot be used to reach
   or attack a third party. **Mandatory reinforcement:** the relay validates that the first
   byte of each spliced stream is a known `ConnectionType` (CONTROL/DATA/SNAPSHOT) and drops
   anything else, rejecting "tunnel arbitrary TCP" abuse.
2. **Session identity.** `sessionId` is a high-entropy (128-bit) random bearer capability,
   handed out only via discovery to someone who already has the world code (jukz's existing
   "the code authorizes" model — no weaker, no stronger). **Registration fencing:** only the
   holder of the control link for `S` receives workconn signals; the relay rejects a second
   registration of a live `sessionId` (fenced by the `ClaimToken`, mirroring the discovery
   CAS), so a stranger cannot steal an active session. Fresh per generation + random ⇒ not
   enumerable.
3. **Resource / DoS limits** (all configurable env vars, all drops logged — no silent caps):
   reuse the per-IP rate limiter on the WS upgrades; cap concurrent sessions (global + per
   IP) and streams per session; per-stream idle timeout and per-session bandwidth cap
   (Minecraft is low-bandwidth) with a high total-bytes backstop; bounded splice buffers with
   backpressure; fast nonce/workconn match timeout so unmatched halves cannot accumulate.
4. **Confidentiality.** WSS = TLS on each leg (host↔relay, guest↔relay). The relay sees
   plaintext by design — trust assumption is "you trust whoever runs the relay (you)". E2E
   against the relay itself is out of scope (§3).
5. **Isolation.** Each splice is keyed by `sessionId` + `nonce`; a stream is only ever joined
   to its matching nonce. Unit-tested (Rust) so bytes from session A can never reach B; both
   sides close together on either's EOF/error.

**Inherited limits (honest, deferred to `/v2`):** no cryptographic world-ownership proof
(anyone with the code can host/join); no E2E against the relay.

## 9. Error handling / edge cases

- **Relay scaled to zero (Fly `min_machines_running = 0`):** the host's control-link connect
  wakes the machine (`auto_start_machines`); the parked WS keeps it awake while hosting (like
  heartbeats). On relay restart the control link drops → `RelayClient` reconnects with the
  same `sessionId`; in-flight games drop (same blast radius as a host restart). Acceptable v1.
- **Stale session (guest connects with a `sessionId` whose host already left):** relay closes
  the guest WS with a status → guest surfaces "host unavailable" (existing path).
- **Nonce never matched (host did not dial back in time):** relay times out and closes the
  guest stream → "host unavailable".
- **Back-compat:** v4 record; older clients ignore the relay field. v1/v2/v3 still decode.
- **No new host-local surface:** host binds loopback + dials outbound only.

## 10. Configuration (rendezvous env)

| Var | Default (proposed) | Meaning |
|---|---|---|
| `RELAY_ENABLED` | `true` | Master switch for the relay endpoints |
| `RELAY_MAX_SESSIONS` | `200` | Global live-session cap |
| `RELAY_MAX_SESSIONS_PER_IP` | `4` | Per-IP live-session cap |
| `RELAY_MAX_STREAMS_PER_SESSION` | `16` | Channels in flight per session |
| `RELAY_SESSION_BANDWIDTH_KBPS` | `4096` | Per-session rate cap |
| `RELAY_STREAM_IDLE_MS` | `60000` | Idle stream reaper |
| `RELAY_WORKCONN_TIMEOUT_MS` | `8000` | Nonce match deadline |

(Defaults are starting points, tunable after a live load check.)

## 11. Testing strategy

- **Rust:** session register → connect allocates nonce → host work matches nonce → pair;
  stale / duplicate-registration / timeout paths; isolation (A's bytes never reach B).
- **core (JUnit):** `WorldRecord` v4 codec round-trip + decode of v1/v2/v3; connect-ladder
  policy with fake transports (direct fails → relay chosen; direct succeeds → relay untouched;
  sticky reuse).
- **fabric / E2E (manual, flagged):** two isolated peers (`runClientA`/`runClientB`) with the
  direct path forced to fail (advertise an unreachable direct endpoint) so the ladder must use
  the relay; confirm play **and** the F4 snapshot handoff travel over the relay.

## 12. Build order (informs the implementation plan)

1. `core`: `RelayOffer` + `WorldRecord` v4 codec + ladder policy (tests first).
2. `rendezvous`: WS relay endpoints + session/nonce registry + splice (cargo tests for the
   deterministic parts).
3. `fabric`: `RelayTransport` (guest) + ladder wiring; `RelayClient` (host) + register-on-no-UPnP
   wiring.
4. E2E validation with forced-relay; then merge.
