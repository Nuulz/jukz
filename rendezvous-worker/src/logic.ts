// Pure rendezvous rules, ported 1:1 from the Rust server (rendezvous/src/{main,store}.rs) so they can be
// unit-tested with plain `node --test` — no Workers runtime needed. The hub Durable Object only adds I/O.

export interface Token {
  generation: number;
  claimEpochMillis: number;
  nodeId: string;
}

export interface Endpoint {
  host: string;
  port: number;
}

export interface RelayInfo {
  sessionId: string;
}

export interface WorldRecord {
  worldId: string;
  token: Token;
  endpoints: Endpoint[];
  heartbeatSeq: number;
  playerCount: number;
  relay?: RelayInfo;
}

/** A record plus its lease deadline (epoch ms), as persisted in the hub. */
export interface Entry {
  record: WorldRecord;
  expiresAt: number;
}

export const MAX_ENDPOINTS = 8;
const MAX_HOST_LEN = 253;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export class BadRequest extends Error {}

/** Fencing order: generation, then claim millis, then node id (spec R1). */
export function cmpToken(a: Token, b: Token): number {
  if (a.generation !== b.generation) return a.generation < b.generation ? -1 : 1;
  if (a.claimEpochMillis !== b.claimEpochMillis) return a.claimEpochMillis < b.claimEpochMillis ? -1 : 1;
  if (a.nodeId !== b.nodeId) return a.nodeId < b.nodeId ? -1 : 1;
  return 0;
}

export function sameToken(a: Token, b: Token): boolean {
  return cmpToken(a, b) === 0;
}

export function parseWorldId(raw: unknown): string {
  const id = typeof raw === "string" ? raw.toLowerCase() : "";
  if (!UUID_RE.test(id)) throw new BadRequest("worldId must be a UUID");
  return id;
}

function isInt(v: unknown): v is number {
  return typeof v === "number" && Number.isSafeInteger(v);
}

/** Validate and normalise a token (lower-case node id), mirroring `validate_token`. */
export function parseToken(raw: unknown): Token {
  const t = raw as Partial<Token> | null;
  if (!t || !isInt(t.generation) || !isInt(t.claimEpochMillis) || typeof t.nodeId !== "string") {
    throw new BadRequest("token must have integer generation/claimEpochMillis and a nodeId");
  }
  if (t.generation < 0) throw new BadRequest("generation must be non-negative");
  const nodeId = t.nodeId.toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(nodeId)) throw new BadRequest("nodeId must be 32 hex chars");
  return { generation: t.generation, claimEpochMillis: t.claimEpochMillis, nodeId };
}

export function parseEndpoints(raw: unknown): Endpoint[] {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_ENDPOINTS) {
    throw new BadRequest("endpoints must contain 1..=8 entries");
  }
  return raw.map((e) => {
    const host = typeof e?.host === "string" ? e.host : "";
    if (host.trim() === "" || host.length > MAX_HOST_LEN) throw new BadRequest("endpoint host is blank or too long");
    if (!isInt(e?.port) || e.port < 1 || e.port > 65535) throw new BadRequest("endpoint port must be 1..=65535");
    return { host, port: e.port };
  });
}

export function parseRelay(raw: unknown): RelayInfo | undefined {
  if (raw == null) return undefined;
  const sessionId = (raw as RelayInfo).sessionId;
  if (typeof sessionId !== "string" || !/^[0-9a-f]{16,64}$/.test(sessionId)) {
    throw new BadRequest("relay.sessionId must be 16..64 hex chars");
  }
  return { sessionId };
}

/**
 * Append the server-observed public address (with the announced listen port) unless already present
 * or the list is full. This is what makes a record dialable across NATs without client-side STUN.
 */
export function mergeObservedEndpoint(endpoints: Endpoint[], observedIp: string | null): Endpoint[] {
  if (!observedIp || endpoints.length === 0) return endpoints;
  const observed = { host: observedIp, port: endpoints[0].port };
  if (endpoints.length >= MAX_ENDPOINTS || endpoints.some((e) => e.host === observed.host && e.port === observed.port)) {
    return endpoints;
  }
  return [...endpoints, observed];
}

export type AnnounceOutcome = { kind: "published"; entry: Entry } | { kind: "rejected"; current: WorldRecord };

/** CAS on token order: a live incumbent wins unless the newcomer is strictly greater. */
export function announce(existing: Entry | undefined, record: WorldRecord, now: number, ttlMs: number): AnnounceOutcome {
  if (existing && existing.expiresAt > now && cmpToken(existing.record.token, record.token) >= 0) {
    return { kind: "rejected", current: existing.record };
  }
  return { kind: "published", entry: { record, expiresAt: now + ttlMs } };
}

export type HeartbeatOutcome =
  | { kind: "refreshed"; entry: Entry }
  | { kind: "superseded"; current: WorldRecord }
  | { kind: "unknown" };

export function heartbeat(
  existing: Entry | undefined,
  token: Token,
  heartbeatSeq: number,
  playerCount: number,
  now: number,
  ttlMs: number,
): HeartbeatOutcome {
  if (!existing || existing.expiresAt <= now) return { kind: "unknown" };
  if (!sameToken(existing.record.token, token)) return { kind: "superseded", current: existing.record };
  return {
    kind: "refreshed",
    entry: { record: { ...existing.record, heartbeatSeq, playerCount }, expiresAt: now + ttlMs },
  };
}

export function liveRecord(existing: Entry | undefined, now: number): WorldRecord | undefined {
  return existing && existing.expiresAt > now ? existing.record : undefined;
}

/** Withdraw removes the record only for the matching token (or when it already expired). */
export function shouldWithdraw(existing: Entry | undefined, token: Token, now: number): boolean {
  return !!existing && (existing.expiresAt <= now || sameToken(existing.record.token, token));
}

/**
 * Snapshot upload fence: reject only a STRICTLY older generation. Equal is allowed so a host can retry
 * its own upload after a transient failure (see rendezvous/src/snapshot.rs).
 */
export function fenceAllows(current: number | undefined, generation: number): boolean {
  return current === undefined || generation >= current;
}

/** jukz ConnectionType discriminators (CONTROL / DATA / SNAPSHOT): the only first bytes the relay carries. */
export const VALID_FIRST_BYTES = new Set([0x01, 0x02, 0x03]);

/** Fixed-window per-IP rate limiter (60 s windows), as in the Rust `guard`. */
export class RateLimiter {
  private windows = new Map<string, { start: number; count: number }>();
  private readonly perMinute: number;
  constructor(perMinute: number) {
    this.perMinute = perMinute;
  }

  allow(ip: string, now: number): boolean {
    if (this.windows.size > 10_000) {
      for (const [k, w] of this.windows) if (now - w.start >= 60_000) this.windows.delete(k);
    }
    let w = this.windows.get(ip);
    if (!w || now - w.start >= 60_000) {
      w = { start: now, count: 0 };
      this.windows.set(ip, w);
    }
    w.count += 1;
    return w.count <= this.perMinute;
  }
}

// ---- sharding -----------------------------------------------------------------------------------
// Each world's record lives in its own Durable Object, and each relay session in its own, so one
// world's relay traffic (a 20 MB snapshot is thousands of frames) never queues another world's calls.
// `/v1/relay/work` only carries the nonce, so the nonce itself encodes the session's shard:
// nonce = shard * 2^21 + n, with shard < 2^31 — still a safe integer the mod parses as a Long.

const NONCE_LOW_BITS = 2 ** 21;

/** Stable 31-bit shard for a relay session id (FNV-1a). */
export function relayShard(sessionId: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < sessionId.length; i++) {
    h ^= sessionId.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 1) & 0x7fffffff;
}

export function nonceFor(shard: number, random: number = Math.random()): number {
  return shard * NONCE_LOW_BITS + 1 + Math.floor(random * (NONCE_LOW_BITS - 1));
}

export function shardOfNonce(nonce: number): number | undefined {
  if (!Number.isSafeInteger(nonce) || nonce <= 0) return undefined;
  return Math.floor(nonce / NONCE_LOW_BITS);
}
