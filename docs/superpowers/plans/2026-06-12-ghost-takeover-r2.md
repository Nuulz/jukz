# Ghost Takeover via R2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A host that closes a world with no guests connected uploads its world snapshot to Cloudflare R2; a later guest looking the world up downloads it and takes over hosting — closing the orphaned-world ("ghost") gap.

**Architecture:** The rendezvous server is an almost-stateless **presigned-URL signer** (it never touches world bytes); world packs travel host→R2 and R2→guest directly. R2 holds one blob per world (stable key, overwritten on a higher generation). The host side drives the upload behind a dedicated, non-dismissable progress screen; the guest side reuses the existing `beginTakeover`/`applySnapshot` machinery, only changing where the pack comes from (HTTP from R2 instead of the SNAPSHOT channel).

**Tech Stack:** Rust (axum 0.8, `rusty-s3` for presigning), Kotlin (Fabric 1.21.1, `java.net.http.HttpClient`, JGit), Cloudflare R2 (S3-compatible object storage).

**Spec:** `docs/superpowers/specs/2026-06-12-ghost-takeover-r2-design.md`

---

## File Structure

**Rust (rendezvous):**
- Create `rendezvous/src/snapshot.rs` — R2 presigner (`R2Config`) + `SnapshotFencing` (per-world max-generation gate). One responsibility: everything about the snapshot store's signing + fencing.
- Modify `rendezvous/Cargo.toml` — add `rusty-s3`.
- Modify `rendezvous/src/main.rs` — two endpoints, `AppState` field, healthz field, route wiring.

**Kotlin (mod):**
- Create `fabric/src/main/kotlin/dev/jukz/sync/R2SnapshotStore.kt` — network adapter: ask the rendezvous for URLs, PUT/GET bytes to/from R2, with an upload-progress callback.
- Create `fabric/src/main/kotlin/dev/jukz/runtime/GhostUpload.kt` — a common-safe holder the server-stopping hook arms with the built pack and the client upload screen drains.
- Create `fabric/src/main/kotlin/dev/jukz/client/gui/UploadingWorldScreen.kt` — the blocking progress screen (progress bar + rotating messages + escape valve).
- Modify `fabric/src/main/kotlin/dev/jukz/runtime/HostSession.kt` — the `connectedGuestCount() == 0` branch that builds the pack and arms `GhostUpload`.
- Modify `fabric/src/main/java/dev/jukz/mixin/MinecraftClientDisconnectMixin.java` — show `UploadingWorldScreen` for the ghost case.
- Modify `fabric/src/main/kotlin/dev/jukz/client/JoinCoordinator.kt` — on `HostUnavailable`, consult the ghost store and offer the takeover.
- Modify `fabric/src/main/kotlin/dev/jukz/JukzClient.kt` — register the GLFW window-close guard (only fires while uploading).

**Validation note:** following this repo's convention, pure logic gets unit tests (the Rust `SnapshotFencing`); network adapters (`R2SnapshotStore`, `RendezvousWorldRegistry`) and UI/mixin code are validated by running the game and inspecting the R2 dashboard, not automated tests.

---

# Phase A — Rendezvous: R2 presigner + endpoints

## Task A1: Add `rusty-s3` and the R2 config/presigner

**Files:**
- Modify: `rendezvous/Cargo.toml`
- Create: `rendezvous/src/snapshot.rs`

- [ ] **Step 1: Add the dependency**

In `rendezvous/Cargo.toml`, under `[dependencies]`, add:

```toml
rusty-s3 = "0.7"
url = "2"
```

- [ ] **Step 2: Create the presigner + config**

Create `rendezvous/src/snapshot.rs`:

```rust
//! Snapshot store backing the ghost-takeover path: the rendezvous signs short-lived presigned
//! R2 URLs (it never touches world bytes) and gates uploads with a per-world generation fence.
//!
//! Config is read from the environment; when any R2_* var is missing the store is absent
//! (`from_env` returns None) and the snapshot endpoints answer "disabled" — the rest of the
//! server is unaffected.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

use rusty_s3::{Bucket, Credentials, S3Action, UrlStyle};
use uuid::Uuid;

/// How long a signed URL is valid. Short: the client uses it immediately.
const URL_TTL: Duration = Duration::from_secs(300);

pub struct SnapshotStore {
    bucket: Bucket,
    credentials: Credentials,
    /// worldId -> highest generation uploaded so far (best-effort, in-RAM fence).
    fence: Mutex<HashMap<Uuid, i64>>,
}

impl SnapshotStore {
    /// Build from the R2_* environment, or None when any var is missing (feature disabled).
    pub fn from_env() -> Option<SnapshotStore> {
        let account = std::env::var("R2_ACCOUNT_ID").ok().filter(|s| !s.is_empty())?;
        let bucket_name = std::env::var("R2_BUCKET").ok().filter(|s| !s.is_empty())?;
        let key_id = std::env::var("R2_ACCESS_KEY_ID").ok().filter(|s| !s.is_empty())?;
        let secret = std::env::var("R2_SECRET_ACCESS_KEY").ok().filter(|s| !s.is_empty())?;

        let endpoint = format!("https://{account}.r2.cloudflarestorage.com")
            .parse()
            .ok()?;
        // R2 is path-style and uses the "auto" region.
        let bucket = Bucket::new(endpoint, UrlStyle::Path, bucket_name, "auto").ok()?;
        let credentials = Credentials::new(key_id, secret);
        Some(SnapshotStore { bucket, credentials, fence: Mutex::new(HashMap::new()) })
    }

    fn pack_key(world_id: Uuid) -> String {
        format!("{world_id}.pack")
    }

    fn head_key(world_id: Uuid) -> String {
        format!("{world_id}.head")
    }

    /// Gate + sign an upload. Returns the (pack, head) presigned PUT URLs, or None when the
    /// generation is not strictly newer than what we have already accepted for this world.
    pub fn sign_upload(&self, world_id: Uuid, generation: i64) -> Option<(String, String)> {
        {
            let mut fence = self.fence.lock().unwrap();
            let current = fence.get(&world_id).copied().unwrap_or(i64::MIN);
            if generation <= current {
                return None;
            }
            fence.insert(world_id, generation);
        }
        let pack = self
            .bucket
            .put_object(Some(&self.credentials), &Self::pack_key(world_id))
            .sign(URL_TTL)
            .to_string();
        let head = self
            .bucket
            .put_object(Some(&self.credentials), &Self::head_key(world_id))
            .sign(URL_TTL)
            .to_string();
        Some((pack, head))
    }

    /// Sign the (pack, head) presigned GET URLs. The object may not exist; the guest treats a
    /// 404 from R2 on the head download as "no ghost", so the server need not check existence.
    pub fn sign_download(&self, world_id: Uuid) -> (String, String) {
        let pack = self
            .bucket
            .get_object(Some(&self.credentials), &Self::pack_key(world_id))
            .sign(URL_TTL)
            .to_string();
        let head = self
            .bucket
            .get_object(Some(&self.credentials), &Self::head_key(world_id))
            .sign(URL_TTL)
            .to_string();
        (pack, head)
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd rendezvous && cargo build`
Expected: compiles (the module is not yet referenced from `main.rs`; that is Task A3). A "module is never used" warning is fine.

- [ ] **Step 4: Commit**

```bash
git add rendezvous/Cargo.toml rendezvous/Cargo.lock rendezvous/src/snapshot.rs
git commit -m "feat(rendezvous): R2 presigner + per-world generation fence (snapshot store)"
```

## Task A2: Unit-test the generation fence

**Files:**
- Modify: `rendezvous/src/snapshot.rs`

The fence is the only pure logic on the server side; test it directly. (We do not unit-test the
presigning — it requires real R2 — but the fence guards correctness.)

- [ ] **Step 1: Write the failing tests**

Append to `rendezvous/src/snapshot.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    /// A fence-only store for tests (no real R2 needed to exercise the generation gate).
    fn fence_only() -> SnapshotStore {
        SnapshotStore {
            // dummy bucket/credentials never exercised by the fence tests
            bucket: Bucket::new(
                "https://acct.r2.cloudflarestorage.com".parse().unwrap(),
                UrlStyle::Path,
                "b",
                "auto",
            )
            .unwrap(),
            credentials: Credentials::new("k", "s"),
            fence: Mutex::new(HashMap::new()),
        }
    }

    #[test]
    fn first_upload_for_a_world_is_accepted() {
        let store = fence_only();
        let w = Uuid::new_v4();
        assert!(store.sign_upload(w, 1).is_some());
    }

    #[test]
    fn equal_or_lower_generation_is_rejected() {
        let store = fence_only();
        let w = Uuid::new_v4();
        assert!(store.sign_upload(w, 5).is_some());
        assert!(store.sign_upload(w, 5).is_none(), "equal generation rejected");
        assert!(store.sign_upload(w, 4).is_none(), "lower generation rejected");
    }

    #[test]
    fn strictly_higher_generation_is_accepted() {
        let store = fence_only();
        let w = Uuid::new_v4();
        assert!(store.sign_upload(w, 5).is_some());
        assert!(store.sign_upload(w, 6).is_some());
    }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `cd rendezvous && cargo test snapshot::`
Expected: 3 tests pass (the signing produces real URLs against the dummy bucket; we only assert Some/None).

- [ ] **Step 3: Commit**

```bash
git add rendezvous/src/snapshot.rs
git commit -m "test(rendezvous): generation fence accepts only strictly-newer uploads"
```

## Task A3: Wire the two endpoints + healthz

**Files:**
- Modify: `rendezvous/src/main.rs`

- [ ] **Step 1: Declare the module and import**

In `rendezvous/src/main.rs`, near the top with the other `mod` lines (after `mod relay;`):

```rust
mod snapshot;
```

And add to the `use` of local items (near `use store::{...}`):

```rust
use snapshot::SnapshotStore;
```

- [ ] **Step 2: Add the store to `AppState`**

In the `struct AppState { ... }` definition, add a field:

```rust
    snapshot: Option<SnapshotStore>,
```

And in `main()` where `AppState { ... }` is constructed, add:

```rust
        snapshot: SnapshotStore::from_env(),
```

(Place it alongside the other field initialisers, e.g. after `relay: ...`.)

- [ ] **Step 3: Add the request body + handlers**

Add the request struct near the other `#[derive(Deserialize)]` bodies:

```rust
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotUploadBody {
    world_id: Uuid,
    generation: i64,
}
```

Add the two handlers (near the other handlers, e.g. after `withdraw`):

```rust
/// Sign presigned PUT URLs for a world snapshot (pack + head). Gated by the generation fence.
async fn snapshot_upload_url(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<SnapshotUploadBody>,
) -> Response {
    if let Err(response) = guard(&state, &headers, peer) {
        return response;
    }
    let store = match &state.snapshot {
        Some(s) => s,
        None => return error_response(StatusCode::SERVICE_UNAVAILABLE, "snapshot store disabled"),
    };
    match store.sign_upload(body.world_id, body.generation) {
        Some((pack_url, head_url)) => {
            tracing::info!(world = %body.world_id, generation = body.generation, "snapshot upload signed");
            (StatusCode::OK, Json(json!({ "packUrl": pack_url, "headUrl": head_url, "expiresInSec": 300 })))
                .into_response()
        }
        None => (StatusCode::CONFLICT, Json(json!({ "status": "stale" }))).into_response(),
    }
}

/// Sign presigned GET URLs for a world snapshot. 404 when the store is disabled; otherwise the
/// guest probes the head URL and treats a 404 from R2 as "no ghost".
async fn snapshot_download_url(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(world_id): Path<Uuid>,
) -> Response {
    if let Err(response) = guard(&state, &headers, peer) {
        return response;
    }
    let store = match &state.snapshot {
        Some(s) => s,
        None => return (StatusCode::NOT_FOUND, Json(json!({ "status": "none" }))).into_response(),
    };
    let (pack_url, head_url) = store.sign_download(world_id);
    (StatusCode::OK, Json(json!({ "packUrl": pack_url, "headUrl": head_url }))).into_response()
}
```

- [ ] **Step 4: Register the routes**

In `main()` where the `Router::new()` routes are listed, add:

```rust
        .route("/v1/snapshot/upload-url", post(snapshot_upload_url))
        .route("/v1/snapshot/{world_id}", get(snapshot_download_url))
```

- [ ] **Step 5: Add the healthz field**

In `healthz`, inside the `json!({ ... })` body, add a field:

```rust
        "snapshotStore": if state.snapshot.is_some() { "enabled" } else { "disabled" },
```

- [ ] **Step 6: Build + test**

Run: `cd rendezvous && cargo test`
Expected: all tests pass (the 3 new fence tests plus the existing suite), build is clean.

- [ ] **Step 7: Commit**

```bash
git add rendezvous/src/main.rs
git commit -m "feat(rendezvous): /v1/snapshot upload-url + download-url endpoints (R2 signing)"
```

## Task A4: Provision R2 and deploy

**Files:** none (operator steps).

- [ ] **Step 1: Create the R2 bucket + token (operator)**

Follow the spec appendix:
1. Cloudflare dashboard → **R2** → create bucket `jukz-snapshots`.
2. **R2 → Manage API Tokens → Create API Token**, scope **Object Read & Write** on that bucket.
   Record **Access Key ID**, **Secret Access Key**, and your **Account ID**.

- [ ] **Step 2: Set the env vars on Railway (operator)**

On the `jukz-rendezvous` service set: `R2_ACCOUNT_ID`, `R2_BUCKET=jukz-snapshots`,
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`.

- [ ] **Step 3: Deploy**

Run: `cd rendezvous && railway up --ci`
Expected: deploy completes.

- [ ] **Step 4: Verify**

Run: `curl -s https://jukz.nuulm.com/healthz`
Expected: JSON includes `"snapshotStore":"enabled"`.

---

# Phase B — Client: R2SnapshotStore network adapter

## Task B1: The upload/download adapter

**Files:**
- Create: `fabric/src/main/kotlin/dev/jukz/sync/R2SnapshotStore.kt`

This mirrors `RendezvousWorldRegistry`'s style (a network adapter using `java.net.http.HttpClient`,
validated in-game). It asks the rendezvous for URLs, then PUTs/GETs bytes straight to R2.

- [ ] **Step 1: Create the adapter**

Create `fabric/src/main/kotlin/dev/jukz/sync/R2SnapshotStore.kt`:

```kotlin
package dev.jukz.sync

import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.core.model.WorldId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Client side of the ghost-takeover snapshot store. The rendezvous signs the R2 URLs; this adapter
 * uploads (host, on a guest-less close) and downloads (guest, taking over a world with no live host)
 * the world pack + head straight to/from R2. Network adapter — validated in-game, like
 * [dev.jukz.discovery.RendezvousWorldRegistry]. Every method is best-effort: a failure logs and
 * returns false/null so it never blocks play.
 */
object R2SnapshotStore {

    data class GhostUrls(val packUrl: String, val headUrl: String)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    private val uploadTimeout = Duration.ofMinutes(10)
    private val signTimeout = Duration.ofSeconds(10)

    /** True if a rendezvous is configured at all (else there is nowhere to sign URLs). */
    fun isConfigured(): Boolean = JukzConfig.rendezvousUrl != null

    /**
     * Upload the world [pack] and [head] for [worldId] at [generation]. [onProgress] is called with
     * (bytesSent, totalBytes) as the pack uploads. Returns true only when both objects PUT with a 2xx.
     */
    fun uploadGhost(
        worldId: WorldId,
        generation: Long,
        pack: ByteArray,
        head: String,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val base = JukzConfig.rendezvousUrl ?: return false
        val urls = signUpload(base, worldId, generation) ?: return false
        return runCatching {
            putBytes(urls.packUrl, pack, onProgress)
            putBytes(urls.headUrl, head.toByteArray(Charsets.UTF_8)) { _, _ -> }
            true
        }.getOrElse {
            JukzMod.logger.warn("jukz: ghost snapshot upload failed ({})", it.message)
            false
        }
    }

    /** Ask the rendezvous for the download URLs, or null when disabled / unreachable. */
    fun ghostSnapshot(worldId: WorldId): GhostUrls? {
        val base = JukzConfig.rendezvousUrl ?: return null
        val request = signed(URI.create("$base/v1/snapshot/${worldId.uuid}")).GET()
            .timeout(signTimeout).build()
        return runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching null
            val o = JsonParser.parseString(response.body()).asJsonObject
            GhostUrls(o.get("packUrl").asString, o.get("headUrl").asString)
        }.getOrElse {
            JukzMod.logger.warn("jukz: ghost snapshot lookup failed ({})", it.message)
            null
        }
    }

    /** GET a small text object (the head commit id). Returns its trimmed content, or null on 404/error. */
    fun downloadText(url: String): String? = runCatching {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().timeout(signTimeout).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() == 200) response.body().trim() else null
    }.getOrNull()

    /** GET the pack object into a temp file, reporting (bytesRead, totalBytes). Null on 404/error. */
    fun downloadToTemp(url: String, onProgress: (Long, Long) -> Unit): Path? = runCatching {
        val dest = Files.createTempFile("jukz-ghost", ".pack")
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().timeout(uploadTimeout).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() != 200) {
            Files.deleteIfExists(dest)
            return@runCatching null
        }
        val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
        response.body().use { input ->
            Files.newOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        dest
    }.getOrNull()

    // ---- helpers -------------------------------------------------------------------------

    private fun signUpload(base: String, worldId: WorldId, generation: Long): GhostUrls? {
        val body = """{"worldId":"${worldId.uuid}","generation":$generation}"""
        val request = signed(URI.create("$base/v1/snapshot/upload-url"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(signTimeout).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            JukzMod.logger.info("jukz: snapshot upload-url returned HTTP {}", response.statusCode())
            return null
        }
        val o = JsonParser.parseString(response.body()).asJsonObject
        return GhostUrls(o.get("packUrl").asString, o.get("headUrl").asString)
    }

    private fun putBytes(url: String, bytes: ByteArray, onProgress: (Long, Long) -> Unit) {
        val publisher = CountingBodyPublisher(bytes, onProgress)
        val request = HttpRequest.newBuilder(URI.create(url)).PUT(publisher).timeout(uploadTimeout).build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        require(response.statusCode() in 200..299) { "R2 PUT returned HTTP ${response.statusCode()}" }
    }

    private fun signed(uri: URI): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri)
        JukzConfig.rendezvousAuthToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }
}
```

- [ ] **Step 2: Create the progress-reporting body publisher**

Append to the same file (top-level, after the `object`):

```kotlin
/**
 * A [HttpRequest.BodyPublisher] over a fixed byte array that reports how many bytes have been handed
 * to the HTTP client, so the upload screen can show real progress. `java.net.http` has no native
 * upload-progress hook, so we wrap the body and count as we feed the subscriber.
 */
private class CountingBodyPublisher(
    private val data: ByteArray,
    private val onProgress: (Long, Long) -> Unit,
) : java.net.http.HttpRequest.BodyPublisher {

    override fun contentLength(): Long = data.size.toLong()

    override fun subscribe(subscriber: java.util.concurrent.Flow.Subscriber<in java.nio.ByteBuffer>) {
        subscriber.onSubscribe(object : java.util.concurrent.Flow.Subscription {
            private var offset = 0
            private var cancelled = false

            override fun request(n: Long) {
                if (cancelled) return
                var remaining = n
                while (remaining > 0 && offset < data.size) {
                    val chunk = minOf(64 * 1024, data.size - offset)
                    subscriber.onNext(java.nio.ByteBuffer.wrap(data, offset, chunk))
                    offset += chunk
                    onProgress(offset.toLong(), data.size.toLong())
                    remaining--
                }
                if (offset >= data.size) subscriber.onComplete()
            }

            override fun cancel() {
                cancelled = true
            }
        })
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: compiles.

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/sync/R2SnapshotStore.kt
git commit -m "feat(fabric): R2SnapshotStore — upload/download world snapshots to R2 via signed URLs"
```

---

# Phase C — Host: ghost upload + the progress screen

## Task C1: The `GhostUpload` holder

**Files:**
- Create: `fabric/src/main/kotlin/dev/jukz/runtime/GhostUpload.kt`

The server-stopping hook (server thread) and the upload screen (client thread) meet here.

- [ ] **Step 1: Create the holder**

Create `fabric/src/main/kotlin/dev/jukz/runtime/GhostUpload.kt`:

```kotlin
package dev.jukz.runtime

import dev.jukz.core.model.WorldId

/**
 * Hand-off between the server-stopping hook and the client upload screen for the ghost-takeover
 * upload. The hook (server thread) builds the pack and [arm]s this holder; the [UploadingWorldScreen]
 * (client thread) polls [pending], uploads it to R2, then [clear]s. Kept dependency-free (only `core`
 * + ByteArray) so it loads anywhere.
 *
 * `armed` is a cheap, set-early flag the disconnect mixin reads to decide whether to show the upload
 * screen *before* the pack itself is ready (the pack is built slightly later, once the server has
 * finished stopping). The screen then waits for [pending] to appear.
 */
object GhostUpload {

    data class Pending(
        val worldId: WorldId,
        val generation: Long,
        val pack: ByteArray,
        val head: String,
    )

    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var pending: Pending? = null

    /** Set the moment we decide a ghost upload will happen (before the pack is built). */
    fun markArmed() {
        armed = true
    }

    /** True once a guest-less close has decided to upload; read by the disconnect mixin. */
    fun isArmed(): Boolean = armed

    /** Publish the built pack for the screen to upload. */
    fun arm(pending: Pending) {
        this.pending = pending
    }

    /** The built pack, or null until the hook has produced it. */
    fun pending(): Pending? = pending

    /** Drop all state once the upload completes (or the player bails out). */
    fun clear() {
        armed = false
        pending = null
    }
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew :fabric:compileKotlin`
Expected: compiles.

```bash
git add fabric/src/main/kotlin/dev/jukz/runtime/GhostUpload.kt
git commit -m "feat(fabric): GhostUpload holder bridging the server-stopping hook and the upload screen"
```

## Task C2: Build the pack on a guest-less close

**Files:**
- Modify: `fabric/src/main/kotlin/dev/jukz/runtime/HostSession.kt`

Today `onServerStopping` only acts when `connectedGuestCount() > 0` (live handoff). Add the
guest-less branch: build the pack and arm `GhostUpload` so the client screen can upload it.

- [ ] **Step 1: Add the ghost-upload arming to `onServerStopping`**

In `HostSession.kt`, replace the body of `onServerStopping` so the holder is armed in the
guest-less case. The current method:

```kotlin
    fun onServerStopping(saveDir: Path? = null, flushSave: () -> Unit = {}) {
        controller?.let { c ->
            if (saveDir != null && c.connectedGuestCount() > 0) {
                runCatching { flushSave() } // force the world to disk first so the snapshot is current
                runCatching { offerSnapshotForHandoff(c, saveDir) }
            }
            runCatching { c.close() } // withdraw + stop heartbeating
            runCatching { onWithdraw() } // tear down any relay control link
            JukzMod.logger.info("jukz: host withdrawn on world close")
        }
        controller = null
        onWithdraw = {}
    }
```

becomes:

```kotlin
    fun onServerStopping(saveDir: Path? = null, flushSave: () -> Unit = {}) {
        controller?.let { c ->
            if (saveDir != null && c.connectedGuestCount() > 0) {
                runCatching { flushSave() } // force the world to disk first so the snapshot is current
                runCatching { offerSnapshotForHandoff(c, saveDir) }
            } else if (saveDir != null && GhostUpload.isArmed()) {
                runCatching { flushSave() }
                runCatching { armGhostUpload(c, saveDir) }
            }
            runCatching { c.close() } // withdraw + stop heartbeating
            runCatching { onWithdraw() } // tear down any relay control link
            JukzMod.logger.info("jukz: host withdrawn on world close")
        }
        controller = null
        onWithdraw = {}
    }
```

- [ ] **Step 2: Add the `armGhostUpload` helper**

Add this private method to `HostSession` (near `offerSnapshotForHandoff`):

```kotlin
    /**
     * Build the world pack on a guest-less close and publish it to [GhostUpload] for the client
     * upload screen to push to R2. Local + fast (no network here); a failure clears the holder so no
     * upload screen is shown. The record gives us the worldId + fencing generation.
     */
    private fun armGhostUpload(controller: HostController, saveDir: Path) {
        val record = controller.sharedRecord ?: run { GhostUpload.clear(); return }
        val pack = SnapshotPack.build(saveDir, JGitWorldSync()) ?: run { GhostUpload.clear(); return }
        GhostUpload.arm(
            GhostUpload.Pending(record.worldId, record.hostGeneration, pack.bytes, pack.head),
        )
        JukzMod.logger.info("jukz: armed ghost snapshot ({} bytes) for upload", pack.bytes.size)
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: compiles (`GhostUpload`, `SnapshotPack`, `JGitWorldSync` are already imported or in package; add imports if the compiler flags them — `dev.jukz.runtime.GhostUpload` is same-package, `SnapshotPack`/`JGitWorldSync` are already imported in this file).

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/runtime/HostSession.kt
git commit -m "feat(fabric): build + arm the ghost snapshot when a host closes with no guests"
```

## Task C3: Decide to upload — set the armed flag at close

**Files:**
- Modify: `fabric/src/main/kotlin/dev/jukz/client/HostCoordinator.kt`

`GhostUpload.isArmed()` must be set *before* `onServerStopping` runs, and only when a ghost upload
is wanted: we are hosting, no guests, and a rendezvous is configured. The cleanest signal available
on the client at world-close time is the integrated server's player list. We mark it from the
`SERVER_STOPPING` registration in `JukzMod`, just before the existing hook — but the decision
(hosting + no guests + configured) lives here as a helper for clarity and testability.

- [ ] **Step 1: Add a decision helper to HostCoordinator**

Add to `HostCoordinator`:

```kotlin
    /**
     * Should a guest-less world close upload a ghost snapshot? True when we are hosting, no guest is
     * connected over a control channel, and a rendezvous (hence an R2 signer) is configured. Read by
     * [JukzMod] at SERVER_STOPPING to arm [dev.jukz.runtime.GhostUpload] before the teardown hook.
     */
    fun shouldUploadGhost(): Boolean =
        HostSession.isHosting &&
            HostSession.connectedGuestCount() == 0 &&
            dev.jukz.sync.R2SnapshotStore.isConfigured()
```

(Add `import dev.jukz.runtime.HostSession` if not already present — it is used elsewhere in this file via `HostSession`.)

- [ ] **Step 2: Arm it in JukzMod's SERVER_STOPPING handler**

In `fabric/src/main/kotlin/dev/jukz/JukzMod.kt`, the handler currently reads (around line 36-41):

```kotlin
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            ...
            val saveDir = runCatching { server.getSavePath(WorldSavePath.ROOT) }.getOrNull()
            HostSession.onServerStopping(saveDir) { runCatching { server.saveAll(true, true, true) } }
        }
```

Add the arming line immediately before the `HostSession.onServerStopping(...)` call:

```kotlin
            if (dev.jukz.client.HostCoordinator.shouldUploadGhost()) dev.jukz.runtime.GhostUpload.markArmed()
            HostSession.onServerStopping(saveDir) { runCatching { server.saveAll(true, true, true) } }
```

- [ ] **Step 3: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: compiles.

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/client/HostCoordinator.kt fabric/src/main/kotlin/dev/jukz/JukzMod.kt
git commit -m "feat(fabric): arm the ghost upload at a guest-less world close"
```

## Task C4: The `UploadingWorldScreen`

**Files:**
- Create: `fabric/src/main/kotlin/dev/jukz/client/gui/UploadingWorldScreen.kt`

A non-dismissable screen: waits for `GhostUpload.pending()`, uploads with a live progress bar and a
rotating message slot, and offers an escape valve after repeated failure. Validated in-game.

- [ ] **Step 1: Create the screen**

Create `fabric/src/main/kotlin/dev/jukz/client/gui/UploadingWorldScreen.kt`:

```kotlin
package dev.jukz.client.gui

import dev.jukz.JukzMod
import dev.jukz.runtime.GhostUpload
import dev.jukz.sync.R2SnapshotStore
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Shown when a host closes a world with no guests: it uploads the world snapshot to R2 so a later
 * guest can take over (ghost takeover). Non-dismissable while the upload runs — the window-close
 * guard in JukzClient also refuses the X — so the world is reliably backed up. A rotating message
 * slot entertains the wait; after repeated failures an "Exit anyway" button appears so the player is
 * never trapped (an OS force-kill is the only uncatchable case, by design).
 */
class UploadingWorldScreen : Screen(Text.literal("Saving your world to the cloud")) {

    private val sent = AtomicLong(0)
    private val total = AtomicLong(-1)
    private val done = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    @Volatile private var started = false
    @Volatile private var attempts = 0
    @Volatile private var exitButton: ButtonWidget? = null
    @Volatile private var messageIndex = 0
    private var lastMessageSwapMs = 0L

    /** A jukz upload in progress must block exit; read by the window-close guard. */
    fun isUploading(): Boolean = !done.get()

    override fun init() {
        // No back button while uploading; the escape valve is added on persistent failure.
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun tick() {
        val pending = GhostUpload.pending()
        if (!started && pending != null) {
            started = true
            startUpload(pending)
        }
        if (done.get()) {
            GhostUpload.clear()
            MinecraftClient.getInstance().setScreen(TitleScreen())
        }
    }

    private fun startUpload(pending: GhostUpload.Pending) {
        Thread {
            while (!done.get()) {
                attempts++
                sent.set(0)
                total.set(pending.pack.size.toLong())
                val ok = R2SnapshotStore.uploadGhost(
                    pending.worldId, pending.generation, pending.pack, pending.head,
                ) { s, t -> sent.set(s); if (t > 0) total.set(t) }
                if (ok) {
                    JukzMod.logger.info("jukz: ghost snapshot uploaded")
                    done.set(true)
                    return@Thread
                }
                failed.set(true)
                // Surface the escape valve after a few attempts, then keep retrying behind it.
                if (attempts >= MAX_ATTEMPTS_BEFORE_ESCAPE) {
                    MinecraftClient.getInstance().execute { addExitButton() }
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        }.apply { isDaemon = true; name = "jukz-ghost-upload" }.start()
    }

    private fun addExitButton() {
        if (exitButton != null) return
        val button = ButtonWidget.builder(Text.literal("Exit anyway (no cloud backup)")) {
            JukzMod.logger.warn("jukz: player skipped the ghost upload — world has no cloud backup")
            done.set(true) // unblocks tick() -> returns to the title screen; the upload thread stops
        }.dimensions(width / 2 - 110, height / 2 + 40, 220, 20).build()
        exitButton = button
        addDrawableChild(button)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        renderBackground(context, mouseX, mouseY, delta)
        val cx = width / 2

        context.drawCenteredTextWithShadow(textRenderer, title, cx, height / 2 - 48, 0xFFFFFF)

        val status = when {
            failed.get() && exitButton != null -> "Upload failed — retrying. You can exit without a backup."
            failed.get() -> "Upload hiccup — retrying…"
            !started -> "Preparing your world…"
            else -> progressLine()
        }
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, height / 2 - 28, 0xC0C0C0)

        drawProgressBar(context, cx)

        // Rotating message slot.
        val now = System.currentTimeMillis()
        if (now - lastMessageSwapMs > MESSAGE_SWAP_MS) {
            lastMessageSwapMs = now
            messageIndex = (messageIndex + 1) % MESSAGES.size
        }
        context.drawCenteredTextWithShadow(
            textRenderer, Text.literal(MESSAGES[messageIndex]), cx, height / 2 + 12, 0x808080,
        )

        super.render(context, mouseX, mouseY, delta)
    }

    private fun progressLine(): String {
        val s = sent.get()
        val t = total.get()
        return if (t > 0) "Uploading… %.1f / %.1f MB".format(s / 1_048_576.0, t / 1_048_576.0)
        else "Uploading…"
    }

    private fun drawProgressBar(context: DrawContext, cx: Int) {
        val barW = 220
        val barH = 6
        val x = cx - barW / 2
        val y = height / 2 - 12
        context.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, 0xFF3F3F3F.toInt())
        val t = total.get()
        val frac = if (t > 0) (sent.get().toDouble() / t).coerceIn(0.0, 1.0) else 0.0
        context.fill(x, y, x + (barW * frac).toInt(), y + barH, 0xFF6BCB6B.toInt())
    }

    companion object {
        private const val MAX_ATTEMPTS_BEFORE_ESCAPE = 3
        private const val RETRY_DELAY_MS = 3_000L
        private const val MESSAGE_SWAP_MS = 5_000L

        /** Rotating tips/jokes; expand freely. */
        private val MESSAGES = listOf(
            "Tip: anyone with your world code can hop in — share it only with friends.",
            "Did you know? jukz worlds have no central server; you ARE the server.",
            "Keeping your world safe in the cloud so a friend can pick it up later…",
            "Tip: the green dot on the world list means someone is hosting it right now.",
            "Fun fact: the world only needs one host online to stay alive.",
        )
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :fabric:compileKotlin`
Expected: compiles. (If `drawCenteredTextWithShadow` / `renderBackground` signatures differ under the
exact yarn mappings, adjust to the available overload — they are standard 1.21.1 `Screen`/`DrawContext` methods.)

- [ ] **Step 3: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/client/gui/UploadingWorldScreen.kt
git commit -m "feat(fabric): UploadingWorldScreen — blocking ghost-upload progress + escape valve"
```

## Task C5: Show the screen at close + guard the window X

**Files:**
- Modify: `fabric/src/main/java/dev/jukz/mixin/MinecraftClientDisconnectMixin.java`
- Modify: `fabric/src/main/kotlin/dev/jukz/JukzClient.kt`

- [ ] **Step 1: Show `UploadingWorldScreen` for the ghost case**

In `MinecraftClientDisconnectMixin.java`, add a branch to `jukz$handoffScreen` *before* the existing
host-with-guests branch (the ghost case is host-with-no-guests, so order doesn't conflict, but keep
it first for readability). Add the import and the branch:

```java
import dev.jukz.client.gui.UploadingWorldScreen;
import dev.jukz.runtime.GhostUpload;
```

```java
    private Screen jukz$handoffScreen(Screen original) {
        if (GhostUpload.INSTANCE.isArmed()) {
            return new UploadingWorldScreen();
        }
        if (HostSession.INSTANCE.isHosting() && HostSession.INSTANCE.connectedGuestCount() > 0) {
            return new MessageScreen(Text.literal("Handing the world to the next host…"));
        }
        if (original instanceof DisconnectedScreen && GuestSession.INSTANCE.recentlyEngaged()) {
            return new HostLeavingScreen();
        }
        return original;
    }
```

- [ ] **Step 2: Guard the window close (the X) while uploading**

GLFW allows only **one** window-close callback, and Minecraft registers its own (which schedules the
real quit). Replacing it outright would break normal quitting. Instead, register **on the first
client tick** — by then the window exists and Minecraft's callback is installed — capture the
previous callback, and **delegate to it** in the non-uploading case.

In `JukzClient.kt`, add the imports:

```kotlin
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import dev.jukz.client.gui.UploadingWorldScreen
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWWindowCloseCallback
import org.lwjgl.glfw.GLFWWindowCloseCallbackI
```

In `onInitializeClient`, register a one-shot first-tick installer:

```kotlin
        // Veto the window X while a ghost upload is in progress, so an accidental close doesn't
        // abandon the backup. Registered on the first tick (the window + Minecraft's own close
        // callback exist by then); we chain to Minecraft's callback for every non-upload close so
        // normal quitting still works. An OS force-kill remains uncatchable, by design.
        val installed = java.util.concurrent.atomic.AtomicBoolean(false)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!installed.compareAndSet(false, true)) return@EndTick
            val handle = client.window.handle
            var previous: GLFWWindowCloseCallback? = null
            previous = GLFW.glfwSetWindowCloseCallback(handle, GLFWWindowCloseCallbackI { window ->
                val screen = client.currentScreen
                if (screen is UploadingWorldScreen && screen.isUploading()) {
                    GLFW.glfwSetWindowShouldClose(window, false) // veto
                    JukzMod.logger.info("jukz: window close vetoed — world still uploading")
                } else {
                    previous?.invoke(window) // hand off to Minecraft's own close handling
                }
            })
        })
```

(`glfwSetWindowCloseCallback` returns the previously-installed `GLFWWindowCloseCallback`, which we
keep in `previous` and invoke for ordinary closes. `JukzMod` must be importable here for the logger;
it already is across the client package.)

- [ ] **Step 3: Build the full mod**

Run: `./gradlew :fabric:build`
Expected: build succeeds (mixin applies, Kotlin + Java compile).

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/java/dev/jukz/mixin/MinecraftClientDisconnectMixin.java fabric/src/main/kotlin/dev/jukz/JukzClient.kt
git commit -m "feat(fabric): show the upload screen at a guest-less close and veto the window X mid-upload"
```

## Task C6: Live-validate the upload path

**Files:** none (manual, in-game).

- [ ] **Step 1: Run a dev client and upload**

Run: `run-client-a.bat`
- Open a jukz world, then **Save and Quit** with no one connected.
- Expect the `UploadingWorldScreen` (not "Saving world"), a filling bar, rotating messages, and a
  return to the title screen when done.
- Try clicking the window X mid-upload: it should not close.

- [ ] **Step 2: Confirm the blob landed**

In the Cloudflare R2 dashboard, the `jukz-snapshots` bucket should contain `<worldId>.pack` and
`<worldId>.head`. (Or `railway logs` shows the `snapshot upload signed` line.)

- [ ] **Step 3: No regression without R2**

With `rendezvous.url=none` (LAN-only), closing a world must behave exactly as before (no upload
screen). Confirm `R2SnapshotStore.isConfigured()` returns false → `shouldUploadGhost()` false.

---

# Phase D — Guest: ghost download + takeover

## Task D1: Offer the takeover from a ghost snapshot

**Files:**
- Modify: `fabric/src/main/kotlin/dev/jukz/client/JoinCoordinator.kt`

Today a `HostUnavailable` result shows `ShouldHostScreen`. Insert a ghost-store consult: if R2 has a
snapshot, prefetch it and offer the existing `HostHandoffScreen` → `beginTakeover` (reused unchanged).

- [ ] **Step 1: Consult the ghost store in the join thread**

In `JoinCoordinator.start`, the worker thread currently ends with:

```kotlin
            if (result is JoinResult.Connected) GuestSession.install(controller)
            client.execute { applyResult(client, result, worldId, shortCode, parent) }
```

Replace with a branch that checks the ghost store off-thread when no live host was found:

```kotlin
            if (result is JoinResult.Connected) GuestSession.install(controller)
            if (result is JoinResult.HostUnavailable) {
                val ghost = R2SnapshotStore.ghostSnapshot(worldId)
                if (ghost != null) {
                    client.execute { showGhostTakeover(client, worldId, shortCode, parent, ghost) }
                    return@Thread
                }
            }
            client.execute { applyResult(client, result, worldId, shortCode, parent) }
```

Add the import:

```kotlin
import dev.jukz.sync.R2SnapshotStore
```

- [ ] **Step 2: Add the ghost-takeover wiring**

Add these methods to `JoinCoordinator` (they mirror `showHandoff`/`prefetchSnapshot` but pull from
R2 over HTTP, then reuse `beginTakeover` verbatim):

```kotlin
    /**
     * No live host, but R2 holds a snapshot (ghost takeover). Prefetch the pack over HTTP and offer
     * the same "Host now" prompt the live handoff uses; taking over reuses [beginTakeover].
     */
    private fun showGhostTakeover(
        client: MinecraftClient,
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        ghost: R2SnapshotStore.GhostUrls,
    ) {
        val prefetch = prefetchGhostSnapshot(ghost)
        val screen = HostHandoffScreen(
            snapshotApplied = true,
            onHostNow = { beginTakeover(client, worldId, shortCode, parent, prefetch) },
            onBack = { discardPrefetch(prefetch); client.setScreen(parent) },
        )
        client.setScreen(screen)
    }

    /** Download the ghost pack + head from R2 into a temp [JGitWorldSync.Downloaded], off-thread. */
    private fun prefetchGhostSnapshot(
        ghost: R2SnapshotStore.GhostUrls,
    ): CompletableFuture<JGitWorldSync.Downloaded?> {
        val future = CompletableFuture<JGitWorldSync.Downloaded?>()
        Thread {
            val downloaded = runCatching {
                val head = R2SnapshotStore.downloadText(ghost.headUrl) ?: return@runCatching null
                val pack = R2SnapshotStore.downloadToTemp(ghost.packUrl) { _, _ -> } ?: return@runCatching null
                JGitWorldSync.Downloaded(pack, org.eclipse.jgit.lib.ObjectId.fromString(head))
            }.getOrNull()
            JukzMod.logger.info("jukz: prefetched ghost snapshot: {}", if (downloaded != null) "ready" else "unavailable")
            future.complete(downloaded)
        }.apply { isDaemon = true; name = "jukz-ghost-prefetch" }.start()
        return future
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew :fabric:build`
Expected: compiles. (`beginTakeover`, `discardPrefetch`, `JGitWorldSync.Downloaded` already exist in
this file; `CompletableFuture` is already imported.)

- [ ] **Step 4: Commit**

```bash
git add fabric/src/main/kotlin/dev/jukz/client/JoinCoordinator.kt
git commit -m "feat(fabric): offer ghost takeover from an R2 snapshot when no live host is found"
```

## Task D2: Live-validate the full ghost round-trip

**Files:** none (manual, in-game).

- [ ] **Step 1: Create a ghost**

Run: `run-client-a.bat` — open a jukz world, make a visible change, **Save and Quit** alone (this
uploads to R2 per Phase C). Note the world's share code.

- [ ] **Step 2: Take over from the other instance**

Run: `run-client-b.bat` — Multiplayer → **Play together** → paste the code.
- The lookup finds no live host, so the ghost prompt ("Host now") appears.
- Click **Host now**: it downloads the snapshot from R2, materialises the world locally, opens it,
  and auto-hosts (generation bumped past the old host).
- Confirm the visible change from Step 1 is present and the world is now live (green badge).

- [ ] **Step 3: No-ghost path unchanged**

Join a code that never uploaded (e.g. with R2 disabled): you get the normal "No live host" screen,
not a takeover prompt.

- [ ] **Step 4: Update the README follow-ups**

In `README.md`, move the "ghost takeover" follow-up to done (the orphaned-world gap is closed for
worlds whose host did a clean guest-less close; a host crash still leaves no ghost — documented).

```bash
git add README.md
git commit -m "docs(readme): ghost takeover via R2 implemented (clean guest-less close)"
```

---

## Self-review notes (addressed in this plan)

- **Spec coverage:** rendezvous signer + fence (A1–A3), R2 config/deploy (A4), client adapter with
  real upload progress (B1), guest-less upload trigger + blocking screen + X-veto + escape valve
  (C1–C5), guest download + takeover reusing `beginTakeover`/`applySnapshot` (D1). The "Related work"
  (connection-loss) section of the spec is explicitly out of scope and not planned here.
- **Simplification vs spec:** the `upload-url` request omits the `ClaimToken` (spec mentioned it
  "for consistency"); it adds no security here (the worldId is the credential; the fence is by
  generation), so it is dropped to reduce coupling.
- **Stateless download:** the server signs GET URLs without a HEAD existence check; the guest treats
  a 404 from R2 on the head download as "no ghost" — keeps the rendezvous free of outbound R2 IO and
  resilient across redeploys, matching the spec's intent.
- **Type consistency:** `GhostUpload.Pending(worldId, generation, pack, head)`,
  `R2SnapshotStore.GhostUrls(packUrl, headUrl)`, `JGitWorldSync.Downloaded(packPath, head: ObjectId)`
  are used consistently across tasks C and D.
```
