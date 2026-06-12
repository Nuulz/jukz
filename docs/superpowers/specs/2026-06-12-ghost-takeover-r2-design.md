# Ghost takeover via R2 — design

**Date:** 2026-06-12
**Status:** approved design, pending implementation plan
**Branch target:** `phase4-world-handoff` (or a follow-up branch)

## Problem

jukz is live-host: a world lives wherever a host is "switched on". The live-connection
handoff (F4) covers the case where a host closes **while a guest is connected** — it pushes
the snapshot over the open control channel and a guest takes over. The gap is the **ghost
case**: the last host closes with **no guest connected**, and someone arrives afterwards.
Their `lookup` returns "nobody hosting" (the record has expired), the world has no reachable
copy, and it is effectively orphaned.

This design closes that gap by having a leaving host deposit its world snapshot in
Cloudflare R2 (object storage), so a later guest can download it, take over, and re-host.

## Decisions (fixed during brainstorming)

- **Upload trigger:** only the exact ghost case — the host closes with `connectedGuestCount() == 0`.
  When a guest is connected, the existing live-connection handoff runs instead; R2 is untouched.
  A host crash (non-clean exit) is intentionally **not** covered (no periodic uploads).
- **Upload UX:** the ghost upload runs behind a **dedicated blocking screen** (progress bar +
  rotating tips/jokes), not the generic "Saving world". It blocks the normal return-to-menu and
  intercepts the window-close (the GLFW close callback) so an accidental click on the X does not
  cancel the upload; a JVM shutdown hook is a last-resort safety net. If the network keeps
  failing, after a few retries an **"Exit anyway (no cloud backup)"** escape valve appears — the
  player is never trapped. An OS force-kill (Task Manager, `kill -9`, power-off) cannot be
  prevented; we guarantee the clean-close path, not the impossible.
- **Provider:** Cloudflare R2 (egress-free, ~$0.015/GB-mo; a hobby workload sits inside the free tier).
- **Retention:** one blob per world, stable key, **overwritten** by an upload carrying a higher
  generation. Storage is bounded by the number of worlds, not by history.
- **Format:** full pack (not incremental bundles) — uploads are rare (only when the host chain
  breaks), so the incremental-delta saving is not worth the extra complexity.
- **Architecture:** the rendezvous is an almost-stateless **URL signer**; world bytes travel
  host→R2 and R2→guest, never through the rendezvous (Approach 1).

## Architecture

```
  HOST (closes with 0 guests)           RENDEZVOUS (Railway)            R2 (Cloudflare)
  ─────────────────────────            ──────────────────────         ─────────────────
  SnapshotPack.build() ──► pack,head
       │ POST upload-url ─────────────► fencing check (maxGen RAM)
       │                                sign PUT presigned ───────────►
       │ ◄───────────────────────────── { packUrl, headUrl }
       └─ PUT pack + head ───────────────────────────────────────────► {worldId}.pack
                                                                        {worldId}.head

  GUEST (lookup → HostUnavailable)
       │ GET /v1/snapshot/{worldId} ──► HEAD R2 .pack ─────────────────► exists?
       │ ◄───────────────────────────── { packUrl, headUrl } | 404
       └─ GET head + pack ◄───────────────────────────────────────────  (egress free)
          applySnapshot() → open bypassing discovery → auto-host (gen bumped)
```

The rendezvous never holds world bytes. R2 is the single persistent source of truth, so the
download path survives rendezvous redeploys with no state to lose.

## Components

### Rust (rendezvous)

- **`snapshot.rs`**
  - A SigV4 **presigner** for R2 (presigned PUT and GET URLs). R2 is S3-compatible; the
    presign algorithm is standard AWS SigV4 query signing. Crate choice (`aws-sigv4` vs a
    ~100-line hand-rolled signer) is deferred to the plan; the design only requires
    server-side presigning.
  - **`SnapshotFencing`** — a `Mutex<HashMap<Uuid, i64>>` mapping `worldId → maxGen` uploaded.
    Best-effort, in RAM. `allow_upload(worldId, gen)` returns true (and records `gen`) when
    `gen` is strictly greater than the stored value or the world is unseen.
  - **Config (env):** `R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`.
    When any is missing the feature is **disabled**: the two endpoints answer accordingly and
    the rest of the server is unaffected.

- **Two endpoints** (registered in `main.rs`, behind the existing `guard` = auth + per-IP rate limit):
  - `POST /v1/snapshot/upload-url`
  - `GET  /v1/snapshot/{worldId}`

- **`/healthz`** gains a `snapshotStore: "enabled" | "disabled"` field.

### Kotlin (mod)

- **`R2SnapshotStore`** (new, `dev.jukz.sync`)
  - `uploadGhost(worldId, generation, pack: ByteArray, head: String): Boolean` — asks the
    rendezvous for an upload URL, then PUTs the pack and head straight to R2. Best-effort.
  - `ghostSnapshot(worldId): GhostUrls?` — asks the rendezvous for the download URLs; null on 404.
  - Uses the same `java.net.http.HttpClient` the rest of the mod already uses.

- **Upload hook** — `runtime/HostSession.onServerStopping(saveDir, flushSave)` already branches
  on `connectedGuestCount() > 0` for the live handoff. Add the `== 0` branch: `flushSave()`,
  `SnapshotPack.build(saveDir, JGitWorldSync())` (local, fast), then drive the upload behind the
  blocking screen below. `worldId`/`generation` come from `controller.sharedRecord` (worldId +
  `token.hostGeneration`).

- **`UploadingWorldScreen`** (new) — a non-dismissable screen shown when the ghost upload starts.
  - **Real progress:** a custom request body wrapper counts bytes PUT to R2 so the bar reflects
    actual upload progress (`12.4 / 88.0 MB`), with throughput/ETA.
  - **Rotating message slot:** a list of short tips/jokes that cycles every few seconds (content
    filled at implementation time; not a design decision).
  - **Blocks exit:** no normal way back to the menu until the upload completes. The window-close
    callback is intercepted so the X doesn't cancel mid-upload; a shutdown hook makes a final
    attempt if the process is told to quit.
  - **Escape valve:** on persistent network failure it retries, and after a few attempts surfaces
    "Exit anyway (no cloud backup)" so the player is never stuck. Taking it leaves the world with
    no ghost backup (today's behaviour).

- **Download hook** — `client/JoinCoordinator`. Today a `JoinResult.HostUnavailable` shows
  `ShouldHostScreen` ("No live host found"). New flow:
  1. In the off-render `jukz-join` thread, when `result is HostUnavailable`, call
     `R2SnapshotStore.ghostSnapshot(worldId)` (network — must stay off the render thread).
  2. If a ghost snapshot exists, start `prefetchGhostSnapshot(worldId, urls)` and show the
     existing `HostHandoffScreen` ("take over?"); otherwise show `ShouldHostScreen` as today.
  3. `prefetchGhostSnapshot` downloads `.head` (~40 B) + `.pack` over HTTP into a temp file and
     returns a `JGitWorldSync.Downloaded(packPath, ObjectId.fromString(head))`.
  4. `beginTakeover(...)` is **reused unchanged**: it awaits the `Downloaded`, calls
     `JGitWorldSync.applySnapshot` (materialising a `jukz-<code>` folder if the world is new),
     and `WorldOpenInterceptor.openLocallyBypassingDiscovery` → auto-host with a bumped generation.

The only new client divergence is *where the pack comes from* (HTTP from R2 vs the SNAPSHOT
channel). `beginTakeover`/`applySnapshot` operate on a `Downloaded`, so they are source-agnostic.

### Reused, not rewritten

- `SnapshotPack.build()` — already produces `(pack bytes, head)`.
- `JGitWorldSync.applySnapshot()` — already indexes the pack, hard-resets, mirrors the
  generation into `jukz.dat`, and materialises a new save folder.
- The hosting generation fence — already decides who wins when re-hosting.

## Endpoint contracts

All bodies are JSON, camelCase, behind the existing auth/rate-limit `guard`.

### `POST /v1/snapshot/upload-url`

Request:
```json
{ "worldId": "<uuid>", "token": { "generation": 0, "claimEpochMillis": 0, "nodeId": "<32 hex>" }, "generation": 7 }
```
- `token` is validated for shape (consistency with announce/withdraw); the fence uses `generation`.

Responses:
- `200` `{ "packUrl": "<presigned PUT>", "headUrl": "<presigned PUT>", "expiresInSec": 300 }`
- `409` `{ "status": "stale", "currentGeneration": 9 }` — fence rejects a non-greater generation.
- `503` `{ "status": "disabled" }` — R2 not configured.
- `400` on malformed token/body; `401`/`429` from the guard.

The fence records `generation` at sign time (optimistic): if the host then fails to upload,
`maxGen` is merely advanced — harmless.

### `GET /v1/snapshot/{worldId}`

- `200` `{ "packUrl": "<presigned GET>", "headUrl": "<presigned GET>" }` — R2 `HEAD` on
  `{worldId}.pack` confirmed the object exists.
- `404` `{ "status": "none" }` — no object, or R2 not configured (the client treats both as
  "no ghost", degrading to today's behaviour).

### R2 object layout

- `{worldId}.pack` — the git packfile (stable key, overwritten on a higher-generation upload).
- `{worldId}.head` — the commit id to reset to (~40 bytes). Carried separately so the
  rendezvous stays stateless: `applySnapshot` needs the head, and it travels via R2 rather than
  via rendezvous RAM that a redeploy would lose.

## Error handling & degradation

- **R2 unconfigured:** upload-url → 503, download → 404. Client never uploads and never offers a
  ghost takeover. The system behaves exactly as it does today.
- **Upload fails / times out:** the `UploadingWorldScreen` retries; on persistent failure the
  "Exit anyway (no cloud backup)" escape valve lets the player leave. The world just has no ghost
  backup (today's behaviour). The player is never trapped, and an OS force-kill is accepted as
  unpreventable.
- **Download fails / pack corrupt:** `applySnapshot` is already non-fatal (keeps the local copy,
  returns false). If the guest has no local copy and the apply fails, it falls through to
  "couldn't get a copy to host" (the existing `beginTakeover` path).
- **Fencing is best-effort:** `maxGen` lives in RAM and is lost on a rendezvous redeploy. After a
  redeploy the first upload of any generation is accepted, then fencing resumes. In the real
  ghost case only one host is closing, so the concurrent-overwrite window is small and bounded.
  A stronger fence (reading the generation back from R2 object metadata) is a future option, out
  of scope for v1.

## Security

- **The worldId is the credential** — consistent with the rest of jukz. Anyone with the share
  code can download the snapshot; that is the same trust model as joining.
- Presigned URLs are short-lived (300 s).
- The rendezvous's bearer auth + per-IP rate limit apply to both signing endpoints.
- R2 credentials live only in the rendezvous env; clients never see them.
- Upload abuse (someone with a worldId overwriting a world) is bounded by the generation fence
  (cannot lower the generation) and is the same trust surface as `announce`. A signed
  content-length-range on the PUT is a possible future hardening; not in v1.

## Testing

- **Rust unit:** `SnapshotFencing` (accepts first, rejects equal/lower, accepts higher);
  endpoint "disabled" path when env is unset; the SigV4 signer against a known AWS test vector.
- **Kotlin unit:** `R2SnapshotStore` against a fake HTTP transport (upload posts the right body;
  download maps 200/404). The apply path is already covered by `SnapshotHandoffTest`.
- **Live validation:** two dev instances — HostA opens a world and closes it **alone** (uploads
  to R2; blob visible in the R2 dashboard); GuestB then joins by code, is offered the takeover,
  downloads, and re-hosts. Confirm the generation climbs and the world matches.

## Out of scope (v1)

- Periodic / crash-safe uploads (only clean close-with-no-guests uploads).
- Incremental `git bundle` uploads (full pack only).
- Strong cross-redeploy fencing via R2 metadata.
- A blob-relay for worlds whose host never deposited a snapshot.

## Related work — connection-loss handling (separate design)

Surfaced during brainstorming; these belong to a **sibling design** ("hosting connection-loss
handling"), not this spec, but the decisions are recorded here so they aren't lost:

- **Connection drops mid-session, no guests:** stay silent — keep playing locally and re-announce
  in the background (already the behaviour: `RendezvousWorldRegistry` is optimistic on network
  errors and each heartbeat retries). No screen for something that self-heals.
- **Connection drops mid-session, with guests:** attempt a transparent background reconnect first;
  only if it truly can't recover, tell the host clearly that the connection dropped and the shared
  session is closing.
- **Joining / sharing under a flaky connection:** do **not** pre-block joining (you can't know a
  host is unstable until you try). Instead be honest in the moment — warn a joiner when the host
  looks unstable ("this world is unstable right now, try later") rather than dropping them into a
  session that breaks. On the host side, a very poor connection means the world is not announced to
  the internet (LAN-only) but is still **playable**; it re-shares automatically once the connection
  improves. This must never block playing your own world.

## Appendix — R2 setup (operator guide)

1. Create a Cloudflare account (free) and open **R2** in the dashboard.
2. Create a bucket, e.g. `jukz-snapshots`.
3. **R2 → Manage API Tokens → Create API Token**, scope **Object Read & Write** on that bucket.
   Note the **Access Key ID**, **Secret Access Key**, and the **S3 endpoint**
   `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`.
4. In Railway (service `jukz-rendezvous`) set env vars: `R2_ACCOUNT_ID`, `R2_BUCKET`,
   `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`. Redeploy.
5. Verify `/healthz` reports `snapshotStore: "enabled"`.

R2 free tier (well clear for a hobby): 10 GB-mo storage, 1 M Class A (writes)/mo,
10 M Class B (reads)/mo, **egress always free** → effectively $0.
