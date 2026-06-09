//! The in-memory world store. Replicates, server-side, the exact semantics the jukz client
//! relies on (mirroring `core`'s `InMemoryWorldRegistry`):
//!  - publish is a CAS on the `ClaimToken` total order (generation → claimEpochMillis → nodeId):
//!    a candidate only displaces a live record whose token is strictly lower;
//!  - heartbeat refreshes the lease only for the exact same token;
//!  - records expire `ttl` after their last successful announce/heartbeat (the crash path);
//!  - withdraw removes the record only when the token matches (never clobbers a newer host).
//!
//! All methods take `now` explicitly so expiry is deterministic under test.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Token {
    pub generation: i64,
    pub claim_epoch_millis: i64,
    /// 32 lowercase hex chars (16 bytes); normalized at validation time so the lexicographic
    /// string comparison below equals the client's unsigned byte comparison.
    pub node_id: String,
}

impl Token {
    /// The ClaimToken total order: generation, then claim millis, then nodeId bytes.
    pub fn cmp_order(&self, other: &Token) -> Ordering {
        self.generation
            .cmp(&other.generation)
            .then(self.claim_epoch_millis.cmp(&other.claim_epoch_millis))
            .then(self.node_id.cmp(&other.node_id))
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Endpoint {
    pub host: String,
    pub port: u16,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldRecord {
    pub world_id: Uuid,
    pub token: Token,
    pub endpoints: Vec<Endpoint>,
    pub heartbeat_seq: i64,
}

#[derive(Debug, PartialEq)]
pub enum AnnounceOutcome {
    Published,
    Rejected(WorldRecord),
}

#[derive(Debug, PartialEq)]
pub enum HeartbeatOutcome {
    Refreshed,
    /// A different (necessarily newer-or-equal-won) token holds the world.
    Superseded(WorldRecord),
    /// No live record: the lease expired or the server restarted. The client re-announces.
    Unknown,
}

struct Entry {
    record: WorldRecord,
    expires_at: Instant,
}

pub struct Store {
    ttl: Duration,
    worlds: Mutex<HashMap<Uuid, Entry>>,
}

impl Store {
    pub fn new(ttl: Duration) -> Self {
        Store { ttl, worlds: Mutex::new(HashMap::new()) }
    }

    pub fn ttl(&self) -> Duration {
        self.ttl
    }

    pub fn announce(&self, record: WorldRecord, now: Instant) -> AnnounceOutcome {
        let mut worlds = self.worlds.lock().unwrap();
        if let Some(entry) = worlds.get(&record.world_id) {
            if entry.expires_at > now && entry.record.token.cmp_order(&record.token) != Ordering::Less {
                return AnnounceOutcome::Rejected(entry.record.clone());
            }
        }
        worlds.insert(record.world_id, Entry { record, expires_at: now + self.ttl });
        AnnounceOutcome::Published
    }

    pub fn heartbeat(&self, world_id: Uuid, token: &Token, heartbeat_seq: i64, now: Instant) -> HeartbeatOutcome {
        let mut worlds = self.worlds.lock().unwrap();
        match worlds.get_mut(&world_id) {
            Some(entry) if entry.expires_at > now => {
                if entry.record.token == *token {
                    entry.record.heartbeat_seq = heartbeat_seq;
                    entry.expires_at = now + self.ttl;
                    HeartbeatOutcome::Refreshed
                } else {
                    HeartbeatOutcome::Superseded(entry.record.clone())
                }
            }
            _ => HeartbeatOutcome::Unknown,
        }
    }

    pub fn lookup(&self, world_id: Uuid, now: Instant) -> Option<WorldRecord> {
        let worlds = self.worlds.lock().unwrap();
        worlds
            .get(&world_id)
            .filter(|entry| entry.expires_at > now)
            .map(|entry| entry.record.clone())
    }

    pub fn withdraw(&self, world_id: Uuid, token: &Token, now: Instant) {
        let mut worlds = self.worlds.lock().unwrap();
        if let Some(entry) = worlds.get(&world_id) {
            if entry.expires_at <= now || entry.record.token == *token {
                worlds.remove(&world_id);
            }
        }
    }

    pub fn live_count(&self, now: Instant) -> usize {
        let worlds = self.worlds.lock().unwrap();
        worlds.values().filter(|entry| entry.expires_at > now).count()
    }

    /// Drop expired entries (run periodically so abandoned worlds don't accumulate).
    pub fn sweep(&self, now: Instant) {
        let mut worlds = self.worlds.lock().unwrap();
        worlds.retain(|_, entry| entry.expires_at > now);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TTL: Duration = Duration::from_millis(90_000);

    fn token(generation: i64, millis: i64, node: &str) -> Token {
        Token { generation, claim_epoch_millis: millis, node_id: node.to_string() }
    }

    fn record(world_id: Uuid, t: Token) -> WorldRecord {
        WorldRecord {
            world_id,
            token: t,
            endpoints: vec![Endpoint { host: "192.168.1.7".into(), port: 51820 }],
            heartbeat_seq: 0,
        }
    }

    #[test]
    fn token_order_is_generation_then_millis_then_node_id() {
        let base = token(1, 100, "aa");
        assert_eq!(token(2, 0, "00").cmp_order(&base), Ordering::Greater);
        assert_eq!(token(1, 101, "00").cmp_order(&base), Ordering::Greater);
        assert_eq!(token(1, 100, "ab").cmp_order(&base), Ordering::Greater);
        assert_eq!(token(1, 100, "aa").cmp_order(&base), Ordering::Equal);
        assert_eq!(token(0, 999, "ff").cmp_order(&base), Ordering::Less);
    }

    #[test]
    fn announce_publishes_when_empty_and_rejects_lower_or_equal_tokens() {
        let store = Store::new(TTL);
        let now = Instant::now();
        let world = Uuid::new_v4();
        let incumbent = record(world, token(5, 100, "aa"));

        assert_eq!(store.announce(incumbent.clone(), now), AnnounceOutcome::Published);
        // Equal token: rejected (announce is the strict CAS; refresh goes through heartbeat).
        assert_eq!(
            store.announce(record(world, token(5, 100, "aa")), now),
            AnnounceOutcome::Rejected(incumbent.clone())
        );
        // Lower token: rejected, incumbent untouched.
        assert_eq!(
            store.announce(record(world, token(4, 999, "ff")), now),
            AnnounceOutcome::Rejected(incumbent.clone())
        );
        assert_eq!(store.lookup(world, now), Some(incumbent));
    }

    #[test]
    fn announce_with_strictly_higher_token_takes_over() {
        let store = Store::new(TTL);
        let now = Instant::now();
        let world = Uuid::new_v4();
        store.announce(record(world, token(5, 100, "aa")), now);

        let usurper = record(world, token(6, 50, "00"));
        assert_eq!(store.announce(usurper.clone(), now), AnnounceOutcome::Published);
        assert_eq!(store.lookup(world, now), Some(usurper));
    }

    #[test]
    fn record_expires_after_ttl_and_the_slot_reopens() {
        let store = Store::new(TTL);
        let now = Instant::now();
        let world = Uuid::new_v4();
        store.announce(record(world, token(5, 100, "aa")), now);

        let later = now + TTL;
        assert_eq!(store.lookup(world, later), None);
        // Even a lower token may publish once the lease is dead (the crash-recovery path).
        assert_eq!(store.announce(record(world, token(1, 0, "00")), later), AnnounceOutcome::Published);
    }

    #[test]
    fn heartbeat_with_same_token_refreshes_ttl_and_advances_seq() {
        let store = Store::new(TTL);
        let now = Instant::now();
        let world = Uuid::new_v4();
        let t = token(5, 100, "aa");
        store.announce(record(world, t.clone()), now);

        let near_expiry = now + TTL - Duration::from_millis(1);
        assert_eq!(store.heartbeat(world, &t, 7, near_expiry), HeartbeatOutcome::Refreshed);

        // Past the original expiry but within one TTL of the heartbeat: still live, seq updated.
        let past_original = now + TTL + Duration::from_millis(1);
        let live = store.lookup(world, past_original).expect("still live");
        assert_eq!(live.heartbeat_seq, 7);
    }

    #[test]
    fn heartbeat_reports_superseded_or_unknown() {
        let store = Store::new(TTL);
        let now = Instant::now();
        let world = Uuid::new_v4();
        let mine = token(5, 100, "aa");
        let usurper = record(world, token(6, 0, "00"));
        store.announce(record(world, mine.clone()), now);
        store.announce(usurper.clone(), now);

        assert_eq!(store.heartbeat(world, &mine, 1, now), HeartbeatOutcome::Superseded(usurper));
        assert_eq!(store.heartbeat(Uuid::new_v4(), &mine, 1, now), HeartbeatOutcome::Unknown);
        assert_eq!(store.heartbeat(world, &token(6, 0, "00"), 1, now + TTL), HeartbeatOutcome::Unknown);
    }

    #[test]
    fn withdraw_removes_only_with_the_matching_token() {
        let store = Store::new(TTL);
        let now = Instant::now();
        let world = Uuid::new_v4();
        let mine = token(5, 100, "aa");
        store.announce(record(world, mine.clone()), now);

        store.withdraw(world, &token(4, 0, "00"), now); // stale token: no-op
        assert!(store.lookup(world, now).is_some());

        store.withdraw(world, &mine, now);
        assert_eq!(store.lookup(world, now), None);
    }

    #[test]
    fn sweep_drops_expired_entries() {
        let store = Store::new(TTL);
        let now = Instant::now();
        store.announce(record(Uuid::new_v4(), token(1, 1, "aa")), now);
        store.announce(record(Uuid::new_v4(), token(1, 1, "bb")), now);
        assert_eq!(store.live_count(now), 2);

        store.sweep(now + TTL);
        assert_eq!(store.live_count(now + TTL), 0);
    }
}
