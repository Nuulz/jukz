//! Relay session registry — the deterministic bookkeeping behind the WebSocket reverse tunnel
//! (`/v1/relay/*`). A host registers a session by id; a guest "connect" allocates a single-use
//! nonce and the host opens a matching "work" connection the relay then splices to the guest.
//!
//! This module owns *who pairs with whom* and the caps; the async WebSocket splice lives in
//! `main.rs`. All methods are synchronous and pure over `&self` (interior `Mutex`), so they are
//! deterministic to unit-test. `begin_connect` parks a pending stream the host claims via
//! `take_work`; the live socket hand-off across that pairing is wired in the handlers.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

#[derive(Clone, Copy)]
pub struct RelayLimits {
    pub max_sessions: usize,
    pub max_streams_per_session: usize,
}

#[derive(Debug, PartialEq, Eq)]
pub enum RegisterOutcome {
    Registered,
    AlreadyLive,
    CapacityReached,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ConnectOutcome {
    Pending(u64),
    UnknownSession,
    StreamsExhausted,
}

struct Session {
    open_streams: HashSet<u64>,
}

pub struct RelayState {
    limits: RelayLimits,
    sessions: Mutex<HashMap<String, Session>>,
    /// nonce -> the session it belongs to, until a host work conn claims it.
    pending: Mutex<HashMap<u64, String>>,
    next_nonce: AtomicU64,
}

impl RelayState {
    pub fn new(limits: RelayLimits) -> Self {
        RelayState {
            limits,
            sessions: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            next_nonce: AtomicU64::new(1),
        }
    }

    /// Register a host session. Refused if the id is already live (fencing) or the cap is hit.
    pub fn register(&self, session_id: &str) -> RegisterOutcome {
        let mut sessions = self.sessions.lock().unwrap();
        if sessions.contains_key(session_id) {
            return RegisterOutcome::AlreadyLive;
        }
        if sessions.len() >= self.limits.max_sessions {
            return RegisterOutcome::CapacityReached;
        }
        sessions.insert(session_id.to_string(), Session { open_streams: HashSet::new() });
        RegisterOutcome::Registered
    }

    /// Tear a session down (control link closed); its nonces are dropped.
    pub fn unregister(&self, session_id: &str) {
        self.sessions.lock().unwrap().remove(session_id);
        self.pending.lock().unwrap().retain(|_, sid| sid != session_id);
    }

    /// A guest stream wants in. Allocates a single-use nonce the host claims with `take_work`.
    pub fn begin_connect(&self, session_id: &str) -> ConnectOutcome {
        let mut sessions = self.sessions.lock().unwrap();
        let session = match sessions.get_mut(session_id) {
            Some(s) => s,
            None => return ConnectOutcome::UnknownSession,
        };
        if session.open_streams.len() >= self.limits.max_streams_per_session {
            return ConnectOutcome::StreamsExhausted;
        }
        let nonce = self.next_nonce.fetch_add(1, Ordering::Relaxed);
        session.open_streams.insert(nonce);
        self.pending.lock().unwrap().insert(nonce, session_id.to_string());
        ConnectOutcome::Pending(nonce)
    }

    /// The host opened a work conn for `nonce`. Returns the owning session id once (single-use).
    pub fn take_work(&self, nonce: u64) -> Option<String> {
        self.pending.lock().unwrap().remove(&nonce)
    }

    /// A stream finished; free its slot so the per-session cap reflects live streams.
    pub fn close_stream(&self, session_id: &str, nonce: u64) {
        if let Some(session) = self.sessions.lock().unwrap().get_mut(session_id) {
            session.open_streams.remove(&nonce);
        }
        self.pending.lock().unwrap().remove(&nonce);
    }

    pub fn live_sessions(&self) -> usize {
        self.sessions.lock().unwrap().len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn limits() -> RelayLimits {
        RelayLimits { max_sessions: 2, max_streams_per_session: 2 }
    }

    #[test]
    fn register_then_connect_allocates_a_nonce_the_host_can_take() {
        let state = RelayState::new(limits());
        assert_eq!(state.register("sess-a"), RegisterOutcome::Registered);

        let nonce = match state.begin_connect("sess-a") {
            ConnectOutcome::Pending(n) => n,
            other => panic!("expected Pending, got {other:?}"),
        };
        assert!(state.take_work(nonce).is_some(), "host work conn should match the nonce");
        assert!(state.take_work(nonce).is_none(), "a nonce is single-use");
    }

    #[test]
    fn connect_to_an_unknown_session_is_refused() {
        let state = RelayState::new(limits());
        assert_eq!(state.begin_connect("ghost"), ConnectOutcome::UnknownSession);
    }

    #[test]
    fn duplicate_registration_is_refused_while_live() {
        let state = RelayState::new(limits());
        assert_eq!(state.register("sess-a"), RegisterOutcome::Registered);
        assert_eq!(state.register("sess-a"), RegisterOutcome::AlreadyLive);
        state.unregister("sess-a");
        assert_eq!(state.register("sess-a"), RegisterOutcome::Registered); // slot freed
    }

    #[test]
    fn session_cap_is_enforced() {
        let state = RelayState::new(limits());
        assert_eq!(state.register("a"), RegisterOutcome::Registered);
        assert_eq!(state.register("b"), RegisterOutcome::Registered);
        assert_eq!(state.register("c"), RegisterOutcome::CapacityReached);
    }

    #[test]
    fn stream_cap_per_session_is_enforced() {
        let state = RelayState::new(limits());
        state.register("a");
        assert!(matches!(state.begin_connect("a"), ConnectOutcome::Pending(_)));
        assert!(matches!(state.begin_connect("a"), ConnectOutcome::Pending(_)));
        assert_eq!(state.begin_connect("a"), ConnectOutcome::StreamsExhausted);
    }
}
