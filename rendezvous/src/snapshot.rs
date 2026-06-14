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

/// How long a signed URL is valid, in seconds. Short: the client uses it immediately.
/// Exposed so the handler can advertise the same value it signs with (no duplicated literal).
pub const URL_TTL_SECS: u64 = 300;
const URL_TTL: Duration = Duration::from_secs(URL_TTL_SECS);

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

    // One "folder" per world (a key prefix — R2/S3 has a flat namespace, but a `/` renders as a folder
    // in the dashboard), holding the pack and its head, so a world's objects group together.
    fn pack_key(world_id: Uuid) -> String {
        format!("{world_id}/pack")
    }

    fn head_key(world_id: Uuid) -> String {
        format!("{world_id}/head")
    }

    /// Gate + sign an upload. Returns the (pack, head) presigned PUT URLs, or None when the
    /// generation is not strictly newer than what we have already accepted for this world.
    pub fn sign_upload(&self, world_id: Uuid, generation: i64) -> Option<(String, String)> {
        {
            let mut fence = self.fence.lock().unwrap();
            let current = fence.get(&world_id).copied().unwrap_or(i64::MIN);
            // Reject only a STRICTLY-older generation (a stale host trying to clobber a newer snapshot).
            // Equal is allowed: a generation is unique per host start, so the only re-sign at the same
            // generation is the same host retrying its own upload (e.g. after a transient PUT failure) —
            // rejecting that would poison every retry with a 409 and lose the backup. The retry just
            // overwrites its own objects, which is idempotent.
            if generation < current {
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

    /// First upload for any world must be accepted regardless of generation value.
    #[test]
    fn first_upload_for_a_world_is_accepted() {
        let store = fence_only();
        let w = Uuid::new_v4();
        assert!(store.sign_upload(w, 1).is_some());
    }

    #[test]
    fn equal_generation_is_allowed_but_lower_is_rejected() {
        let store = fence_only();
        let w = Uuid::new_v4();
        assert!(store.sign_upload(w, 5).is_some());
        // Same host retrying its own upload at the same generation must be allowed (idempotent), or
        // every retry after a transient PUT failure would 409 and the world would lose its backup.
        assert!(store.sign_upload(w, 5).is_some(), "equal generation allowed (same-host retry)");
        assert!(store.sign_upload(w, 4).is_none(), "strictly-lower generation rejected");
    }

    #[test]
    fn strictly_higher_generation_is_accepted() {
        let store = fence_only();
        let w = Uuid::new_v4();
        assert!(store.sign_upload(w, 5).is_some());
        assert!(store.sign_upload(w, 6).is_some());
    }
}
