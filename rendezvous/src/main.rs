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

mod relay;
mod snapshot;
mod store;

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering as AtomicOrdering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::extract::ws::{Message as WsMessage, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::json;
use uuid::Uuid;

use snapshot::SnapshotStore;
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
    relay: relay::RelayState,
    relay_pending_tx: Mutex<HashMap<u64, tokio::sync::oneshot::Sender<WebSocket>>>,
    relay_signal_tx: Mutex<HashMap<String, tokio::sync::mpsc::UnboundedSender<u64>>>,
    snapshot: Option<SnapshotStore>,
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
        relay: relay::RelayState::new(relay::RelayLimits {
            max_sessions: env_or("RELAY_MAX_SESSIONS", 200),
            max_streams_per_session: env_or("RELAY_MAX_STREAMS_PER_SESSION", 16),
        }),
        relay_pending_tx: Mutex::new(HashMap::new()),
        relay_signal_tx: Mutex::new(HashMap::new()),
        snapshot: SnapshotStore::from_env(),
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
        .route("/v1/relay/host", get(relay_host))
        .route("/v1/relay/connect", get(relay_connect))
        .route("/v1/relay/work", get(relay_work))
        .route("/v1/snapshot/upload-url", post(snapshot_upload_url))
        .route("/v1/snapshot/{world_id}", get(snapshot_download_url))
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
    #[serde(default)]
    player_count: i32,
    #[serde(default)]
    relay: Option<store::RelayInfo>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct HeartbeatBody {
    world_id: Uuid,
    token: Token,
    heartbeat_seq: i64,
    #[serde(default)]
    player_count: i32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WithdrawBody {
    world_id: Uuid,
    token: Token,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotUploadBody {
    world_id: Uuid,
    generation: i64,
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
    let record = WorldRecord { world_id: body.world_id, token, endpoints, heartbeat_seq: body.heartbeat_seq, player_count: body.player_count, relay: body.relay };
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

    match state.store.heartbeat(body.world_id, &token, body.heartbeat_seq, body.player_count, Instant::now()) {
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

async fn healthz(State(state): State<Arc<AppState>>) -> Response {
    let counters = &state.counters;
    let body = json!({
        "status": "ok",
        "liveWorlds": state.store.live_count(Instant::now()),
        "relaySessions": state.relay.live_sessions(),
        "snapshotStore": if state.snapshot.is_some() { "enabled" } else { "disabled" },
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

// ---- relay (WebSocket reverse tunnel) -------------------------------------------------------

#[derive(Deserialize)]
struct RelayHostQuery {
    session: String,
}
#[derive(Deserialize)]
struct RelaySessionQuery {
    session: String,
}
#[derive(Deserialize)]
struct RelayWorkQuery {
    nonce: u64,
}

/// jukz ConnectionType discriminators (CONTROL / DATA / SNAPSHOT): the only first bytes the relay
/// will carry, so it can never be used to tunnel arbitrary TCP (not an open proxy).
const VALID_FIRST_BYTES: [u8; 3] = [0x01, 0x02, 0x03];

/// Host control link: register the session, then forward "open a work conn for nonce N" signals to
/// the host until the socket drops, at which point the session is torn down.
async fn relay_host(
    State(state): State<Arc<AppState>>,
    Query(q): Query<RelayHostQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    match state.relay.register(&q.session) {
        relay::RegisterOutcome::Registered => {}
        _ => return error_response(StatusCode::CONFLICT, "relay session unavailable"),
    }
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<u64>();
    state.relay_signal_tx.lock().unwrap().insert(q.session.clone(), tx);
    tracing::info!(session = %q.session, "relay host link open");
    ws.on_upgrade(move |mut socket| async move {
        loop {
            tokio::select! {
                signal = rx.recv() => match signal {
                    Some(nonce) => {
                        if socket.send(WsMessage::Text(nonce.to_string().into())).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                },
                // Drain client frames (pongs / close) so a dropped host is noticed promptly.
                inbound = socket.recv() => match inbound {
                    Some(Ok(_)) => {}
                    _ => break,
                },
            }
        }
        state.relay.unregister(&q.session);
        state.relay_signal_tx.lock().unwrap().remove(&q.session);
        tracing::info!(session = %q.session, "relay host link closed");
    })
}

/// Guest stream: allocate a nonce, signal the host, then wait for its work conn and splice.
async fn relay_connect(
    State(state): State<Arc<AppState>>,
    Query(q): Query<RelaySessionQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    let nonce = match state.relay.begin_connect(&q.session) {
        relay::ConnectOutcome::Pending(n) => n,
        relay::ConnectOutcome::UnknownSession => {
            return error_response(StatusCode::NOT_FOUND, "no such relay session")
        }
        relay::ConnectOutcome::StreamsExhausted => {
            return error_response(StatusCode::TOO_MANY_REQUESTS, "session stream cap reached")
        }
    };
    let signal = state.relay_signal_tx.lock().unwrap().get(&q.session).cloned();
    let signal = match signal {
        Some(tx) => tx,
        None => {
            state.relay.close_stream(&q.session, nonce);
            return error_response(StatusCode::NOT_FOUND, "host gone");
        }
    };
    let (work_tx, work_rx) = tokio::sync::oneshot::channel::<WebSocket>();
    state.relay_pending_tx.lock().unwrap().insert(nonce, work_tx);
    if signal.send(nonce).is_err() {
        state.relay_pending_tx.lock().unwrap().remove(&nonce);
        state.relay.close_stream(&q.session, nonce);
        return error_response(StatusCode::NOT_FOUND, "host gone");
    }
    let session = q.session.clone();
    let timeout_ms: u64 = env_or("RELAY_WORKCONN_TIMEOUT_MS", 8000);
    ws.on_upgrade(move |guest| async move {
        let host_work = tokio::time::timeout(
            Duration::from_millis(timeout_ms),
            work_rx,
        )
        .await;
        match host_work {
            Ok(Ok(host)) => splice(guest, host).await,
            _ => {
                state.relay_pending_tx.lock().unwrap().remove(&nonce);
                tracing::info!(%nonce, "relay work conn never arrived");
            }
        }
        state.relay.close_stream(&session, nonce);
    })
}

/// Host work conn: hand this live socket to the waiting guest for `nonce`.
async fn relay_work(
    State(state): State<Arc<AppState>>,
    Query(q): Query<RelayWorkQuery>,
    ws: WebSocketUpgrade,
) -> Response {
    if state.relay.take_work(q.nonce).is_none() {
        return error_response(StatusCode::NOT_FOUND, "no pending stream for that nonce");
    }
    let tx = state.relay_pending_tx.lock().unwrap().remove(&q.nonce);
    ws.on_upgrade(move |host| async move {
        if let Some(tx) = tx {
            let _ = tx.send(host); // the guest task owns the splice
        }
    })
}

/// Copy binary frames both ways until either side closes. Validates the first byte of the guest
/// stream is a known jukz ConnectionType, so the relay can only carry jukz traffic. Backpressure is
/// the WebSocket library's own.
async fn splice(guest: WebSocket, host: WebSocket) {
    let (mut guest_tx, mut guest_rx) = guest.split();
    let (mut host_tx, mut host_rx) = host.split();

    let g2h = async {
        let mut first = true;
        while let Some(Ok(msg)) = guest_rx.next().await {
            match msg {
                WsMessage::Binary(bytes) => {
                    if first {
                        first = false;
                        if bytes.first().map_or(true, |b| !VALID_FIRST_BYTES.contains(b)) {
                            break; // not a jukz channel — refuse to relay
                        }
                    }
                    if host_tx.send(WsMessage::Binary(bytes)).await.is_err() {
                        break;
                    }
                }
                WsMessage::Close(_) => break,
                _ => {}
            }
        }
        let _ = host_tx.close().await;
    };
    let h2g = async {
        while let Some(Ok(msg)) = host_rx.next().await {
            match msg {
                WsMessage::Binary(bytes) => {
                    if guest_tx.send(WsMessage::Binary(bytes)).await.is_err() {
                        break;
                    }
                }
                WsMessage::Close(_) => break,
                _ => {}
            }
        }
        let _ = guest_tx.close().await;
    };
    tokio::join!(g2h, h2g);
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
