// Relay usage counters: how many messages and bytes each relayed stream really moves, to size the
// relay's cost before it grows. Each relay shard counts its streams in memory and reports deltas (when a
// stream closes, and on every keepalive alarm so a hibernating shard loses at most ~45 s) to one
// aggregate: the hub instance `relay-stats`. Read with GET /v1/admin/relay-stats (x-jukz-admin).

/** One report from a shard: a stream's traffic since its last report. */
export interface StreamDelta {
  session: string;
  msgs: number;
  bytes: number;
  /** True on the stream's last report (counts it as one finished stream). */
  closed: boolean;
  /** When the stream opened (ms). */
  openedAt: number;
}

export interface Totals {
  streams: number;
  msgs: number;
  bytes: number;
}

export interface SessionTotals extends Totals {
  first: number;
  last: number;
}

export const emptyTotals = (): Totals => ({ streams: 0, msgs: 0, bytes: 0 });

export function addDelta<T extends Totals>(into: T, d: StreamDelta): T {
  into.msgs += d.msgs;
  into.bytes += d.bytes;
  if (d.closed) into.streams += 1;
  return into;
}

export function addToSession(prev: SessionTotals | undefined, d: StreamDelta, now: number): SessionTotals {
  const s = prev ?? { ...emptyTotals(), first: d.openedAt, last: now };
  s.first = Math.min(s.first, d.openedAt);
  s.last = now;
  return addDelta(s, d);
}

/** Days and sessions kept in the aggregate. */
export const KEEP_DAYS = 30;
export const KEEP_SESSION_DAYS = 7;

/** Validates a report body (internal call, but stay strict). */
export function parseDeltas(raw: unknown): StreamDelta[] {
  if (!Array.isArray(raw)) return [];
  return raw.slice(0, 1000).filter((d): d is StreamDelta =>
    !!d && typeof d === "object" &&
    typeof d.session === "string" && /^[0-9a-f]{16,64}$/.test(d.session) &&
    Number.isSafeInteger(d.msgs) && d.msgs >= 0 &&
    Number.isSafeInteger(d.bytes) && d.bytes >= 0 &&
    typeof d.closed === "boolean" && Number.isSafeInteger(d.openedAt));
}
