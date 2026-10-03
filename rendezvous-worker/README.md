# jukz rendezvous — Cloudflare Worker

The production rendezvous at **https://jukz.nuulm.com** (the mod's default `rendezvous.url`). Same `/v1`
contract as the Rust server in [`../rendezvous`](../rendezvous), so the mod talks to either unchanged.

| Piece | Cloudflare product |
|---|---|
| HTTP API + routing | Worker (`src/index.ts`) |
| World records, snapshot fence, WebSocket relay | Durable Object `RendezvousHub` (`src/hub.ts`), SQLite-backed |
| Ghost snapshots (`<worldId>/pack`, `<worldId>/head`) | R2 bucket `jukz-snapshots` |

## How it differs from the Rust server

- **Sharded state.** Each world's record lives in the Durable Object `world:<id>`; each relay session
  in `relay:<shard>`. One world's relay traffic (a snapshot is thousands of frames) never queues
  another world's announce/withdraw. `/v1/relay/work` only carries a nonce, so the nonce encodes the
  shard: `nonce = shard * 2^21 + n` (`relayShard` / `nonceFor` / `shardOfNonce` in `src/logic.ts`).
- **Durable leases.** Records are in Durable Object storage, so a deploy no longer drops every live
  world (the Rust store was in-memory).
- **Hibernating relay.** Sockets use the WebSocket Hibernation API, so a host waiting for guests costs
  no duration while idle. An alarm pings host control links every 45 s (the mod ignores non-numeric
  signals) and sweeps expired records.
- **No S3 credentials.** Instead of R2 presigned URLs, the Worker signs short-lived (300 s) HMAC URLs
  pointing at itself (`/v1/snapshot/blob/<id>/<pack|head>`) and streams to/from R2 via the binding.
- **Observed IP** comes from `CF-Connecting-IP` (Rust: `Fly-Client-IP` / `X-Forwarded-For`).
- `/healthz` reports `status` and `snapshotStore` only (state is sharded, so there are no global counts).

## Develop, test, deploy

```bash
npm install
npm run typecheck && npm test          # pure rules (ported from the Rust unit tests) + URL signing
npx wrangler dev --port 18791 --local  # needs .dev.vars, see below
npx wrangler deploy
openssl rand -hex 32 | tr -d '\n' | npx wrangler secret put SNAPSHOT_SIGNING_KEY
```

`.dev.vars` for local runs (wrangler dev rewrites the request host to the route, so pin the origin of
the signed snapshot URLs):

```
SNAPSHOT_SIGNING_KEY=local-dev-key
PUBLIC_BASE_URL=http://127.0.0.1:18791
```

Point the dev clients at it with `rendezvous.url=http://127.0.0.1:18791` in
`fabric/run/client{A,B}/config/jukz.properties` (add `jukz.force-relay=true` to exercise the relay).

Secrets: `SNAPSHOT_SIGNING_KEY` (required for snapshots; rotating it only invalidates URLs signed in the
last 5 minutes) and optionally `RENDEZVOUS_AUTH_TOKEN` (bearer auth on `/v1`, matching the mod's
`rendezvous.auth-token`).

## Limits worth knowing

- Snapshot uploads go through the Worker, so a world pack is capped by the plan's request body limit
  (100 MB on Free/Pro).
- Relay play is billed as Durable Object WebSocket messages. Direct and UPnP connections never touch
  the relay; only hosts behind CGNAT / without UPnP use it.
