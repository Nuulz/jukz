//! jukz rendezvous server — minimal world-discovery backend (decision: Rust + Axum, no DHT).
//!
//! Contract (`/v1`, JSON; mirrored by `RendezvousWorldRegistry` on the mod side):
//!  - `POST /v1/announce`   publish a world record (CAS on ClaimToken order)
//!  - `POST /v1/heartbeat`  refresh the lease for the exact same token
//!  - `GET  /v1/worlds/{worldId}`  current live record, 404 when none
//!  - `POST /v1/withdraw`   remove the record (token must match)
//!  - `GET  /healthz`       liveness + counters (never authenticated)
//!
//! On announce the server appends the announcer's *observed* public address (Fly-Client-IP /
//! X-Forwarded-For / socket peer) to the record's endpoint list: that is what makes a record
//! dialable across NATs without any client-side STUN.
//!
//! Configuration (env): `PORT` (default 8080), `RENDEZVOUS_TTL_MS` (default 90000),
//! `RENDEZVOUS_AUTH_TOKEN` (optional bearer auth for /v1), `RENDEZVOUS_RATE_LIMIT_PER_MIN`
//! (default 120, per client IP).

mod store;

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::extract::{ConnectInfo, Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::json;
use uuid::Uuid;

use store::{AnnounceOutcome, Endpoint, HeartbeatOutcome, Store, Token, WorldRecord};

const MAX_ENDPOINTS: usize = 8;
const MAX_HOST_LEN: usize = 253;
const MAX_BODY_BYTES: usize = 4 * 1024;

struct Counters {
    announces: AtomicU64,
    rejected_announces: AtomicU64,
    heartbeats: AtomicU64,
    lookups: AtomicU64,
    withdrawals: AtomicU64,
    rate_limited: AtomicU64,
}

struct AppState {
    store: Store,
    counters: Counters,
    auth_token: Option<String>,
    rate_limit_per_min: u64,
    rate_windows: Mutex<HashMap<IpAddr, (Instant, u64)>>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "jukz_rendezvous=info,axum=info".into()),
        )
        .init();

    let ttl_ms: u64 = env_or("RENDEZVOUS_TTL_MS", 90_000);
    let port: u16 = env_or("PORT", 8080);
    let state = Arc::new(AppState {
        store: Store::new(Duration::from_millis(ttl_ms)),
        counters: Counters {
            announces: AtomicU64::new(0),
            rejected_announces: AtomicU64::new(0),
            heartbeats: AtomicU64::new(0),
            lookups: AtomicU64::new(0),
            withdrawals: AtomicU64::new(0),
            rate_limited: AtomicU64::new(0),
        },
        auth_token: std::env::var("RENDEZVOUS_AUTH_TOKEN").ok().filter(|t| !t.is_empty()),
        rate_limit_per_min: env_or("RENDEZVOUS_RATE_LIMIT_PER_MIN", 120),
        rate_windows: Mutex::new(HashMap::new()),
    });

    // Periodic sweep so abandoned worlds don't accumulate (lookups already ignore expired ones).
    let sweeper = state.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_secs(60));
        loop {
            tick.tick().await;
            sweeper.store.sweep(Instant::now());
        }
    });

    let app = Router::new()
        .route("/v1/announce", post(announce))
        .route("/v1/heartbeat", post(heartbeat))
        .route("/v1/worlds/{world_id}", get(lookup))
        .route("/v1/withdraw", post(withdraw))
        .route("/healthz", get(healthz))
        .layer(axum::extract::DefaultBodyLimit::max(MAX_BODY_BYTES))
        .with_state(state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    tracing::info!(%addr, ttl_ms, "jukz rendezvous listening");
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>())
        .await
        .expect("serve");
}

fn env_or<T: std::str::FromStr>(key: &str, default: T) -> T {
    std::env::var(key).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

// ---- request bodies -----------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AnnounceBody {
    world_id: Uuid,
    token: Token,
    endpoints: Vec<Endpoint>,
    heartbeat_seq: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct HeartbeatBody {
    world_id: Uuid,
    token: Token,
    heartbeat_seq: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WithdrawBody {
    world_id: Uuid,
    token: Token,
}

// ---- guards (auth + rate limit + validation) ------------------------------------------------

/// Auth + per-IP rate limit for every /v1 handler. Returns the client IP for the observed
/// endpoint. (Plain function instead of middleware: each handler needs the IP anyway.)
fn guard(state: &AppState, headers: &HeaderMap, peer: SocketAddr) -> Result<IpAddr, Response> {
    if let Some(expected) = &state.auth_token {
        let provided = headers
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "));
        if provided != Some(expected.as_str()) {
            return Err(error_response(StatusCode::UNAUTHORIZED, "missing or invalid bearer token"));
        }
    }

    let ip = client_ip(headers, peer);
    let now = Instant::now();
    let mut windows = state.rate_windows.lock().unwrap();
    if windows.len() > 10_000 {
        windows.retain(|_, (start, _)| now.duration_since(*start) < Duration::from_secs(60));
    }
    let (start, count) = windows.entry(ip).or_insert((now, 0));
    if now.duration_since(*start) >= Duration::from_secs(60) {
        *start = now;
        *count = 0;
    }
    *count += 1;
    if *count > state.rate_limit_per_min {
        state.counters.rate_limited.fetch_add(1, AtomicOrdering::Relaxed);
        return Err(error_response(StatusCode::TOO_MANY_REQUESTS, "rate limit exceeded"));
    }
    Ok(ip)
}

/// The announcer's address as this server observed it. Fly terminates TLS and proxies, so the
/// socket peer is Fly's edge; Fly-Client-IP carries the real client (X-Forwarded-For as fallback
/// for other reverse proxies).
fn client_ip(headers: &HeaderMap, peer: SocketAddr) -> IpAddr {
    let from_header = |name: &str| {
        headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.split(',').next())
            .and_then(|v| v.trim().parse::<IpAddr>().ok())
    };
    from_header("fly-client-ip")
        .or_else(|| from_header("x-forwarded-for"))
        .unwrap_or_else(|| peer.ip())
}

fn validate_token(token: &Token) -> Result<Token, Response> {
    if token.generation < 0 {
        return Err(error_response(StatusCode::BAD_REQUEST, "generation must be non-negative"));
    }
    let node_id = token.node_id.to_lowercase();
    if node_id.len() != 32 || !node_id.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(error_response(StatusCode::BAD_REQUEST, "nodeId must be 32 hex chars"));
    }
    Ok(Token { node_id, ..token.clone() })
}

fn validate_endpoints(endpoints: &[Endpoint]) -> Result<(), Response> {
    if endpoints.is_empty() || endpoints.len() > MAX_ENDPOINTS {
        return Err(error_response(StatusCode::BAD_REQUEST, "endpoints must contain 1..=8 entries"));
    }
    for endpoint in endpoints {
        if endpoint.host.trim().is_empty() || endpoint.host.len() > MAX_HOST_LEN {
            return Err(error_response(StatusCode::BAD_REQUEST, "endpoint host is blank or too long"));
        }
        if endpoint.port == 0 {
            return Err(error_response(StatusCode::BAD_REQUEST, "endpoint port must be 1..=65535"));
        }
    }
    Ok(())
}

/// Append the server-observed address (with the announced listen port) unless already present.
fn merge_observed_endpoint(endpoints: &mut Vec<Endpoint>, observed_ip: IpAddr) {
    let port = endpoints[0].port;
    let observed = Endpoint { host: observed_ip.to_string(), port };
    if endpoints.iter().any(|e| *e == observed) || endpoints.len() >= MAX_ENDPOINTS {
        return;
    }
    endpoints.push(observed);
}

fn error_response(code: StatusCode, message: &str) -> Response {
    (code, Json(json!({ "status": "error", "message": message }))).into_response()
}

// ---- handlers -------------------------------------------------------------------------------

async fn announce(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<AnnounceBody>,
) -> Response {
    let ip = match guard(&state, &headers, peer) {
        Ok(ip) => ip,
        Err(response) => return response,
    };
    let token = match validate_token(&body.token) {
        Ok(token) => token,
        Err(response) => return response,
    };
    if let Err(response) = validate_endpoints(&body.endpoints) {
        return response;
    }

    let mut endpoints = body.endpoints;
    merge_observed_endpoint(&mut endpoints, ip);
    let record = WorldRecord { world_id: body.world_id, token, endpoints, heartbeat_seq: body.heartbeat_seq };
    let ttl_ms = state.store.ttl().as_millis() as u64;

    match state.store.announce(record.clone(), Instant::now()) {
        AnnounceOutcome::Published => {
            state.counters.announces.fetch_add(1, AtomicOrdering::Relaxed);
            tracing::info!(world = %body.world_id, generation = record.token.generation, %ip, "published");
            (StatusCode::OK, Json(json!({ "status": "published", "ttlMs": ttl_ms, "record": record })))
                .into_response()
        }
        AnnounceOutcome::Rejected(current) => {
            state.counters.rejected_announces.fetch_add(1, AtomicOrdering::Relaxed);
            tracing::info!(world = %body.world_id, generation = record.token.generation,
                incumbent_generation = current.token.generation, %ip, "announce rejected");
            (StatusCode::CONFLICT, Json(json!({ "status": "rejected", "current": current }))).into_response()
        }
    }
}

async fn heartbeat(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<HeartbeatBody>,
) -> Response {
    if let Err(response) = guard(&state, &headers, peer) {
        return response;
    }
    let token = match validate_token(&body.token) {
        Ok(token) => token,
        Err(response) => return response,
    };
    state.counters.heartbeats.fetch_add(1, AtomicOrdering::Relaxed);
    let ttl_ms = state.store.ttl().as_millis() as u64;

    match state.store.heartbeat(body.world_id, &token, body.heartbeat_seq, Instant::now()) {
        HeartbeatOutcome::Refreshed => {
            (StatusCode::OK, Json(json!({ "status": "refreshed", "ttlMs": ttl_ms }))).into_response()
        }
        HeartbeatOutcome::Superseded(current) => {
            tracing::info!(world = %body.world_id, "heartbeat superseded");
            (StatusCode::CONFLICT, Json(json!({ "status": "superseded", "current": current }))).into_response()
        }
        HeartbeatOutcome::Unknown => {
            tracing::info!(world = %body.world_id, "heartbeat for unknown/expired lease");
            (StatusCode::CONFLICT, Json(json!({ "status": "unknown" }))).into_response()
        }
    }
}

async fn lookup(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Path(world_id): Path<Uuid>,
) -> Response {
    if let Err(response) = guard(&state, &headers, peer) {
        return response;
    }
    state.counters.lookups.fetch_add(1, AtomicOrdering::Relaxed);
    match state.store.lookup(world_id, Instant::now()) {
        Some(record) => (StatusCode::OK, Json(record)).into_response(),
        None => (StatusCode::NOT_FOUND, Json(json!({ "status": "unknown" }))).into_response(),
    }
}

async fn withdraw(
    State(state): State<Arc<AppState>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<WithdrawBody>,
) -> Response {
    if let Err(response) = guard(&state, &headers, peer) {
        return response;
    }
    let token = match validate_token(&body.token) {
        Ok(token) => token,
        Err(response) => return response,
    };
    state.counters.withdrawals.fetch_add(1, AtomicOrdering::Relaxed);
    state.store.withdraw(body.world_id, &token, Instant::now());
    tracing::info!(world = %body.world_id, "withdrawn");
    StatusCode::NO_CONTENT.into_response()
}

async fn healthz(State(state): State<Arc<AppState>>) -> Response {
    let counters = &state.counters;
    let body = json!({
        "status": "ok",
        "liveWorlds": state.store.live_count(Instant::now()),
        "counters": {
            "announces": counters.announces.load(AtomicOrdering::Relaxed),
            "rejectedAnnounces": counters.rejected_announces.load(AtomicOrdering::Relaxed),
            "heartbeats": counters.heartbeats.load(AtomicOrdering::Relaxed),
            "lookups": counters.lookups.load(AtomicOrdering::Relaxed),
            "withdrawals": counters.withdrawals.load(AtomicOrdering::Relaxed),
            "rateLimited": counters.rate_limited.load(AtomicOrdering::Relaxed),
        },
    });
    (StatusCode::OK, Json(body)).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_appends_observed_ip_with_the_announced_port() {
        let mut endpoints = vec![Endpoint { host: "192.168.1.7".into(), port: 51820 }];
        merge_observed_endpoint(&mut endpoints, "203.0.113.9".parse().unwrap());
        assert_eq!(endpoints.len(), 2);
        assert_eq!(endpoints[1], Endpoint { host: "203.0.113.9".into(), port: 51820 });
    }

    #[test]
    fn merge_skips_duplicates_and_respects_the_cap() {
        let mut endpoints = vec![Endpoint { host: "203.0.113.9".into(), port: 51820 }];
        merge_observed_endpoint(&mut endpoints, "203.0.113.9".parse().unwrap());
        assert_eq!(endpoints.len(), 1);

        let mut full: Vec<Endpoint> =
            (0..MAX_ENDPOINTS).map(|i| Endpoint { host: format!("10.0.0.{i}"), port: 1 }).collect();
        merge_observed_endpoint(&mut full, "203.0.113.9".parse().unwrap());
        assert_eq!(full.len(), MAX_ENDPOINTS);
    }

    #[test]
    fn client_ip_prefers_fly_header_then_xff_then_peer() {
        let peer: SocketAddr = "192.0.2.1:9999".parse().unwrap();
        let mut headers = HeaderMap::new();
        assert_eq!(client_ip(&headers, peer), "192.0.2.1".parse::<IpAddr>().unwrap());

        headers.insert("x-forwarded-for", "203.0.113.5, 70.41.3.18".parse().unwrap());
        assert_eq!(client_ip(&headers, peer), "203.0.113.5".parse::<IpAddr>().unwrap());

        headers.insert("fly-client-ip", "198.51.100.7".parse().unwrap());
        assert_eq!(client_ip(&headers, peer), "198.51.100.7".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn token_validation_normalizes_and_rejects_garbage() {
        let ok = Token { generation: 1, claim_epoch_millis: 2, node_id: "AB".repeat(16) };
        assert_eq!(validate_token(&ok).unwrap().node_id, "ab".repeat(16));

        let bad_len = Token { node_id: "abcd".into(), ..ok.clone() };
        assert!(validate_token(&bad_len).is_err());
        let bad_gen = Token { generation: -1, ..ok.clone() };
        assert!(validate_token(&bad_gen).is_err());
        let bad_hex = Token { node_id: "zz".repeat(16), ..ok };
        assert!(validate_token(&bad_hex).is_err());
    }
}
