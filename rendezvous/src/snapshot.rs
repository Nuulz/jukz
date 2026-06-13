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
