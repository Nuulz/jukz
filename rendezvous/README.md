# jukz rendezvous server

A minimal world-discovery backend for jukz (Rust + Axum, in-memory, no database). Hosts announce
"I am hosting world X at these endpoints" under a fencing `ClaimToken`; guests look worlds up by
UUID. Leases expire after a TTL (default **90 s**) unless refreshed by heartbeats, so a crashed
host frees its world automatically.

The mod side of this contract is `fabric/.../discovery/RendezvousWorldRegistry.kt`. Point the mod
at a server via `config/jukz.properties`:

```properties
rendezvous.url=https://your-rendezvous.example.com
# Only when the server sets RENDEZVOUS_AUTH_TOKEN:
rendezvous.auth-token=...
```

Leaving `rendezvous.url` empty keeps the mod LAN-only.

## API (`/v1`, JSON)

| Endpoint | Outcome |
|---|---|
| `POST /v1/announce` | `200 {status:"published", ttlMs, record}` — the returned record includes the announcer's **observed public IP** appended to `endpoints`; `409 {status:"rejected", current}` when a live record holds a token ≥ the candidate's |
| `POST /v1/heartbeat` | `200 {status:"refreshed", ttlMs}`; `409 {status:"superseded", current}` (newer host owns the world); `409 {status:"unknown"}` (lease expired/server restarted → client re-announces) |
| `GET /v1/worlds/{worldId}` | `200 record` or `404` |
| `POST /v1/withdraw` | `204` (removes only if the token matches; idempotent) |
| `GET /healthz` | `200 {status, liveWorlds, counters}` — never authenticated |

Token order is `generation → claimEpochMillis → nodeId` (the protocol's fencing order). Conflict
resolution is CAS server-side: a strictly higher token displaces, anything else is rejected.

## Configuration (env)

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | Listen port |
| `RENDEZVOUS_TTL_MS` | `90000` | Lease TTL. Clients derive their heartbeat interval as TTL/3 |
| `RENDEZVOUS_AUTH_TOKEN` | unset | When set, `/v1/*` requires `Authorization: Bearer <token>` |
| `RENDEZVOUS_RATE_LIMIT_PER_MIN` | `120` | Per-client-IP request budget per minute |
| `RELAY_MAX_SESSIONS` | `200` | Max live relay sessions (a non-UPnP host registers one) |
| `RELAY_MAX_STREAMS_PER_SESSION` | `16` | Max in-flight streams per relay session |
| `RELAY_WORKCONN_TIMEOUT_MS` | `8000` | How long a guest waits for the host's work conn |

## Run locally

```bash
cd rendezvous
cargo test          # store CAS/TTL semantics + validation
cargo run           # listens on 0.0.0.0:8080
```

Smoke test:

```bash
curl -s localhost:8080/healthz
curl -s -X POST localhost:8080/v1/announce -H 'content-type: application/json' -d '{
  "worldId":"3f2504e0-4f89-11d3-9a0c-0305e82c3301",
  "token":{"generation":1,"claimEpochMillis":1,"nodeId":"abababababababababababababababab"},
  "endpoints":[{"host":"192.168.1.7","port":51820}],
  "heartbeatSeq":0}'
curl -s localhost:8080/v1/worlds/3f2504e0-4f89-11d3-9a0c-0305e82c3301
```

To test the whole mod flow on one machine: run the server, set
`rendezvous.url=http://127.0.0.1:8080` in `config/jukz.properties` of both Minecraft instances,
open a world in one and watch the second instance's open of the same world (copied save with the
same `jukz.dat`) turn into a join.

## Deploy (self-hosting)

The official instance at `jukz.nuulm.com` runs the Cloudflare Worker port in
[`../rendezvous-worker`](../rendezvous-worker). This Rust server is for self-hosting: build the
`Dockerfile` (or `cargo build --release`) and run it behind any TLS-terminating reverse proxy.

```bash
docker build -t jukz-rendezvous rendezvous
docker run -p 8080:8080 -e RENDEZVOUS_AUTH_TOKEN=... jukz-rendezvous   # token optional
curl -s https://<your-host>/healthz
```

Notes:
- The store is **in-memory by design**: a redeploy/restart drops all leases, and clients
  transparently re-announce on their next heartbeat (the `unknown` path above). No volume needed.
- The server reads the real client IP from `Fly-Client-IP`, then `X-Forwarded-For`, then the socket
  peer — make sure your proxy sets `X-Forwarded-For`.

## Limits (v1, by decision)


- No ownership authentication: anyone who knows a world UUID can announce a takeover with a high
  generation or replace its backup. The fencing protocol makes a takeover visible (the legitimate host
  gets `superseded`) but not preventable. The production server (`../rendezvous-worker`) checks each
  world's Ed25519 key; this one ignores the `X-Jukz-*` headers the mod sends. Fine for a private
  instance among friends.
- The relay (WebSocket reverse tunnel at `/v1/relay/{host,connect,work}`) carries play for hosts that
  cannot open a port (no UPnP / CGNAT). It is outbound-only on both sides, byte-gated to jukz's own
  `ConnectionType` first byte (never an open proxy), and capped (see the env table). A host registers a
  relay session only when its UPnP mapping fails; guests still try the direct (observed-IP) endpoint
  first and fall back to the relay. The relay sees plaintext by design (WSS encrypts each leg, but the
  server splices the bytes) — true end-to-end against the relay itself is future work.
