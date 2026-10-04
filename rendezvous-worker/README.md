# jukz rendezvous — Cloudflare Worker

The production rendezvous at **https://jukz.nuulm.com** (the mod's default `rendezvous.url`). Same `/v1`
contract as the Rust server in [`../rendezvous`](../rendezvous), so the mod talks to either unchanged.

| Piece | Cloudflare product |
|---|---|
| HTTP API + routing | Worker (`src/index.ts`) |
| World records, snapshot fence, WebSocket relay | Durable Object `RendezvousHub` (`src/hub.ts`), SQLite-backed |
| Ghost snapshots (`<worldId>/pack`, `<worldId>/head`) | R2 bucket `jukz-snapshots` |
| Cosmetics: profiles + entitlements (`src/cosmetics.ts`) | Durable Object `CosmeticsStore`, SQLite-backed |

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
- **World ownership.** Announce, heartbeat, withdraw and the snapshot upload/download are checked
  against the world's Ed25519 key (`X-Jukz-Key` / `X-Jukz-Ts` / `X-Jukz-Sig` over
  `jukz-v1\n{op}\n{worldId}\n{ts}\n{body}`, ±5 min). The first valid signature binds the key in the
  world's shard (`k:<id>`); after that, unsigned calls get 401 and other keys 403, and a download without
  the key looks like "no backup" (404). Worlds that never bound a key keep working unsigned (older mods).
  `checkOwner` in `src/logic.ts` mirrors `dev.jukz.core.model.WorldKey`.

## Cosmetics (`/v1/cosmetics`, not in the Rust server)

Tab-list badges and 3D pieces worn on the player (hat, face, back): one item per slot, a player's
"loadout". The catalog is [`../cosmetics/catalog.json`](../cosmetics/catalog.json), generated from the
ASCII sources in [`../cosmetics/tools`](../cosmetics/tools) and validated at load, so a broken edit fails
`npm test` and the deploy.

| Route | Auth | What |
|---|---|---|
| `GET /catalog` | — | the items and their ASCII art |
| `GET /players?ids=a,b,…` | — (60/min per IP) | `loadouts` (slot → item for each listed player) and `players` (badge only); absent = nothing |
| `POST /challenge`, `POST /session` | Mojang | sign in, returns a 24 h token (see below) |
| `GET /me`, `POST /equip {slot, item}` | `x-jukz-cosmetics-token` | owned items and picks; put an item (or `"none"`) in a slot (`slot` defaults to `badge`) |
| `POST /admin/grant`, `/admin/revoke {id, item, source}` | `x-jukz-admin` | entitlements for `paid` / `grant` items (404 while `COSMETICS_ADMIN_TOKEN` is unset) |

**Signing in.** Mojang answers `hasJoined` with 403 to Cloudflare Workers, so production proves the
account with the certificate Minecraft holds for chat signing: the client sends it (its RSA public key,
expiry, and Mojang's signature over uuid ‖ expiry ‖ key) and signs the challenge with the matching private
key. The Worker checks both offline against Mojang's `playerCertificateKeys` (fetched from
`api.minecraftservices.com/publickeys`, with an embedded copy in `src/mojang-keys.ts`). Accounts without a
certificate fall back to the server-style handshake (`joinServer` + `hasJoined`), which works on
self-hosted Workers and `wrangler dev`.

Challenges and tokens are HMACs under `SNAPSHOT_SIGNING_KEY`, so nothing is stored for them (rotating that
key also signs everyone out of cosmetics, harmlessly: the mod signs in again). Signing in is what creates
a profile, which is what makes a player's badge visible.

Selling an item later: set `"availability": "paid"` and a `price` (cents) in the catalog, deploy, and
grant it per purchase — e.g. from a Ko-fi webhook, or by hand:

```bash
curl -X POST https://jukz.nuulm.com/v1/cosmetics/admin/grant -H "x-jukz-admin: $TOKEN" \
  -d '{"id":"<player uuid>","item":"founder","source":"manual"}'
```

## Develop, test, deploy

```bash
npm install
npm run typecheck && npm test          # pure rules (ported from the Rust unit tests), URL signing, ownership, cosmetics, certificates
npx wrangler dev --port 18791 --local  # needs .dev.vars, see below
npx wrangler deploy
openssl rand -hex 32 | tr -d '\n' | npx wrangler secret put SNAPSHOT_SIGNING_KEY
```

`.dev.vars` for local runs (wrangler dev rewrites the request host to the route, so pin the origin of
the signed snapshot URLs):

```
SNAPSHOT_SIGNING_KEY=local-dev-key
PUBLIC_BASE_URL=http://127.0.0.1:18791
COSMETICS_DEV_UNVERIFIED=true   # dev clients have offline accounts; never set this in production
COSMETICS_ADMIN_TOKEN=dev-admin
```

Point the dev clients at it with `rendezvous.url=http://127.0.0.1:18791` in
`fabric/run/client{A,B}/config/jukz.properties` (add `jukz.force-relay=true` to exercise the relay).

Secrets: `SNAPSHOT_SIGNING_KEY` (required for snapshots and cosmetics sign-in; rotating it only
invalidates URLs signed in the last 5 minutes and cosmetics sessions), optionally `RENDEZVOUS_AUTH_TOKEN`
(bearer auth on `/v1`, matching the mod's `rendezvous.auth-token`) and `COSMETICS_ADMIN_TOKEN` (grants).

## Limits worth knowing

- Snapshot uploads go through the Worker, so a world pack is capped by the plan's request body limit
  (100 MB on Free/Pro).
- Relay play is billed as Durable Object WebSocket messages. Direct and UPnP connections never touch
  the relay; only hosts behind CGNAT / without UPnP use it.
