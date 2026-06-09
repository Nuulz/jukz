# jukz rendezvous server

A minimal world-discovery backend for jukz (Rust + Axum, in-memory, no database). Hosts announce
"I am hosting world X at these endpoints" under a fencing `ClaimToken`; guests look worlds up by
UUID. Leases expire after a TTL (default **90 s**) unless refreshed by heartbeats, so a crashed
host frees its world automatically.

The mod side of this contract is `fabric/.../discovery/RendezvousWorldRegistry.kt`. Point the mod
at a server via `config/jukz.properties`:

```properties
rendezvous.url=https://your-app.fly.dev
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

## Deploy on Fly.io

```bash
cd rendezvous
fly launch --no-deploy      # once; reuses fly.toml, pick your own app name
fly secrets set RENDEZVOUS_AUTH_TOKEN=...   # optional, for private instances
fly deploy
curl -s https://<your-app>.fly.dev/healthz
```

Notes:
- The store is **in-memory by design**: a redeploy/restart drops all leases, and clients
  transparently re-announce on their next heartbeat (the `unknown` path above). No volume needed.
- `fly.toml` ships with `auto_stop_machines`/`min_machines_running = 0`: with no active hosts the
  machine scales to zero and costs nothing; heartbeats keep it awake while anyone is hosting.
- Fly terminates TLS; the server reads the real client IP from `Fly-Client-IP` (falls back to
  `X-Forwarded-For`, then the socket peer).

## Limits (v1, by decision)

- No ownership authentication: anyone who knows a world UUID can announce a takeover with a high
  generation. The fencing protocol makes this visible (the legitimate host gets `superseded`) but
  not preventable; real ownership proofs (per-world keypairs, BEP44-style signatures) are future
  work and would version to `/v2`.
- Discovery only: no relay/signaling. NAT reachability still depends on the host's UPnP mapping
  or manual port-forward; the observed-IP endpoint makes the *address* known, not the port open.
