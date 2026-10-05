// Anti-abuse, the pure parts (no Durable Object, no env) so they are unit-tested in isolation.
//
// Every jukz install has a device key (Ed25519, made on first launch, never leaves the PC): it signs
// the calls that open or join a world, so limits follow the device — ten people behind one router (or
// one CGNAT address) each get their own. A new device key only counts once it is registered, and
// registering costs a small proof of work (~1 s for a player, real money for a script minting
// thousands); a premium account (verified with Mojang through the cosmetics session) skips it and gets
// higher limits. No sign-in anywhere.

import { b64urlDecode } from "./logic.ts";

export const DEVICE_HEADERS = { key: "x-jukz-device", ts: "x-jukz-device-ts", sig: "x-jukz-device-sig" } as const;
/** `join` on a world lookup: the player is joining (counted), not a menu badge refreshing (a peek). */
export const INTENT_HEADER = "x-jukz-intent";
export const DEVICE_SIGNATURE_WINDOW_MS = 5 * 60_000;

/** The exact bytes a device signs: method and path bind the call, the timestamp limits replay. */
export function devicePayload(method: string, path: string, ts: number, body: string): Uint8Array {
  return new TextEncoder().encode(`jukz-device-v1\n${method.toUpperCase()} ${path}\n${ts}\n${body}`);
}

export type DeviceCheck =
  | { ok: true; device: string | null } // null: an unsigned request (an older jukz)
  | { ok: false; status: 401; message: string };

/** Verify the device signature headers of a request, if it carries any. */
export async function checkDevice(
  method: string,
  path: string,
  body: string,
  header: (name: string) => string | null,
  now: number,
): Promise<DeviceCheck> {
  const keyText = header(DEVICE_HEADERS.key);
  const tsText = header(DEVICE_HEADERS.ts);
  const sigText = header(DEVICE_HEADERS.sig);
  if (keyText == null && tsText == null && sigText == null) return { ok: true, device: null };
  const publicKey = keyText ? b64urlDecode(keyText) : null;
  const signature = sigText ? b64urlDecode(sigText) : null;
  const ts = Number(tsText);
  if (!publicKey || publicKey.length !== 32 || !signature || signature.length !== 64 || !Number.isSafeInteger(ts)) {
    return { ok: false, status: 401, message: "malformed device signature" };
  }
  if (Math.abs(now - ts) > DEVICE_SIGNATURE_WINDOW_MS) {
    return { ok: false, status: 401, message: "device signature outside the allowed window (check the PC clock)" };
  }
  try {
    const key = await crypto.subtle.importKey("raw", publicKey, { name: "Ed25519" }, false, ["verify"]);
    if (await crypto.subtle.verify({ name: "Ed25519" }, key, signature, devicePayload(method, path, ts, body))) {
      return { ok: true, device: keyText! };
    }
  } catch {
    // fall through
  }
  return { ok: false, status: 401, message: "bad device signature" };
}

// ---- proof of work ---------------------------------------------------------------------------------
// The challenge is stateless: `<exp>.<random>.<hmac>`. The device finds a nonce such that
// sha256("<challenge>:<device key>:<nonce>") starts with `bits` zero bits. The key is inside the hash,
// so one solved challenge registers exactly one device.

export const CHALLENGE_TTL_MS = 10 * 60_000;

const encoder = new TextEncoder();

async function hmacHex(key: string, message: string): Promise<string> {
  const k = await crypto.subtle.importKey("raw", encoder.encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(new Uint8Array(await crypto.subtle.sign("HMAC", k, encoder.encode(message))));
}

const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

export async function issueChallenge(secret: string, now: number, random: Uint8Array = crypto.getRandomValues(new Uint8Array(12))): Promise<string> {
  const head = `${now + CHALLENGE_TTL_MS}.${hex(random)}`;
  return `${head}.${await hmacHex(secret, `pow:${head}`)}`;
}

export async function challengeValid(secret: string, challenge: unknown, now: number): Promise<boolean> {
  if (typeof challenge !== "string") return false;
  const m = challenge.match(/^(\d{1,15})\.([0-9a-f]{24})\.([0-9a-f]{64})$/);
  if (!m || Number(m[1]) < now) return false;
  const expected = await hmacHex(secret, `pow:${m[1]}.${m[2]}`);
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ m[3].charCodeAt(i);
  return diff === 0;
}

export function leadingZeroBits(bytes: Uint8Array): number {
  let bits = 0;
  for (const b of bytes) {
    if (b === 0) {
      bits += 8;
      continue;
    }
    return bits + Math.clz32(b) - 24;
  }
  return bits;
}

export async function workValid(challenge: string, device: string, nonce: unknown, bits: number): Promise<boolean> {
  if (typeof nonce !== "number" || !Number.isSafeInteger(nonce) || nonce < 0) return false;
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(`${challenge}:${device}:${nonce}`)));
  return leadingZeroBits(digest) >= bits;
}

// ---- limits ----------------------------------------------------------------------------------------
// One state per subject (a device, an IP, a premium player). "Gated" actions are opening a world
// (announce) and joining one: each distinct (action, world) counts once and is then free for
// REPEAT_FREE_MS, so a retry or a reconnect costs nothing. More than `limit` new ones inside a minute
// blocks the subject — 5 min, doubling on each repeat offence within a day, at most a day.
// "Peeks" (the world list's live badges) are cheaper: a world peeked once stays free for an hour, and
// only many *different* worlds in a short time (scraping share codes) is refused, without a block.

export const MINUTE_MS = 60_000;
export const REPEAT_FREE_MS = 10 * MINUTE_MS;
export const PEEK_WINDOW_MS = 10 * MINUTE_MS;
export const PEEK_KEEP_MS = 60 * MINUTE_MS;
export const BLOCK_BASE_MS = 5 * MINUTE_MS;
export const BLOCK_MAX_MS = 24 * 60 * MINUTE_MS;
export const STRIKE_RESET_MS = 24 * 60 * MINUTE_MS;
export const REGISTRATION_WINDOW_MS = 60 * MINUTE_MS;

export interface SubjectState {
  registered?: boolean;
  strikes: number;
  lastStrike: number;
  blockedUntil: number;
  /** "open:<world>" / "join:<world>" → when it was first counted. */
  gated: Record<string, number>;
  /** Registration times (an IP or a premium player registering devices). */
  registrations: number[];
}

export const emptyState = (): SubjectState => ({ strikes: 0, lastStrike: 0, blockedUntil: 0, gated: {}, registrations: [] });

export type Verdict = { ok: true } | { ok: false; retryAfterMs: number; blocked: boolean };

export function blockDuration(strikes: number): number {
  return Math.min(BLOCK_BASE_MS * 2 ** Math.max(0, strikes - 1), BLOCK_MAX_MS);
}

/** Count a gated action against [state] (mutated). */
export function gate(state: SubjectState, action: string, limit: number, now: number): Verdict {
  if (state.blockedUntil > now) return { ok: false, retryAfterMs: state.blockedUntil - now, blocked: true };
  for (const [k, t] of Object.entries(state.gated)) if (now - t >= REPEAT_FREE_MS) delete state.gated[k];
  if (action in state.gated) return { ok: true };
  const lastMinute = Object.values(state.gated).filter((t) => now - t < MINUTE_MS).length;
  if (lastMinute >= limit) {
    if (now - state.lastStrike > STRIKE_RESET_MS) state.strikes = 0;
    state.strikes += 1;
    state.lastStrike = now;
    state.blockedUntil = now + blockDuration(state.strikes);
    return { ok: false, retryAfterMs: state.blockedUntil - now, blocked: true };
  }
  state.gated[action] = now;
  return { ok: true };
}

/** Count a peek (mutates [peeks]: world → first peeked). */
export function peek(peeks: Map<string, number>, world: string, limit: number, now: number): Verdict {
  for (const [k, t] of peeks) if (now - t >= PEEK_KEEP_MS) peeks.delete(k);
  if (peeks.has(world)) return { ok: true };
  const recent = [...peeks.values()].filter((t) => now - t < PEEK_WINDOW_MS).sort((a, b) => a - b);
  if (recent.length >= limit) return { ok: false, retryAfterMs: recent[recent.length - limit] + PEEK_WINDOW_MS - now, blocked: false };
  peeks.set(world, now);
  return { ok: true };
}

/** Count a device registration (mutates [state]); at most [limit] per hour. */
export function register(state: SubjectState, limit: number, now: number): Verdict {
  state.registrations = state.registrations.filter((t) => now - t < REGISTRATION_WINDOW_MS);
  if (state.registrations.length >= limit) {
    return { ok: false, retryAfterMs: state.registrations[0] + REGISTRATION_WINDOW_MS - now, blocked: false };
  }
  state.registrations.push(now);
  return { ok: true };
}

/** Tunables, from wrangler vars (all optional). */
export interface GuardLimits {
  devicePerMin: number;
  premiumPerMin: number;
  ipPerMin: number;
  legacyPerMin: number;
  peeksPer10Min: number;
  legacyPeeksPer10Min: number;
  registrationsPerHour: number;
  powBits: number;
  requireDevice: boolean;
}

export function guardLimits(env: Record<string, unknown>): GuardLimits {
  const n = (name: string, fallback: number) => {
    const v = Number(env[name]);
    return Number.isFinite(v) && v > 0 ? v : fallback;
  };
  return {
    devicePerMin: n("GUARD_DEVICE_PER_MIN", 4),
    premiumPerMin: n("GUARD_PREMIUM_PER_MIN", 8),
    ipPerMin: n("GUARD_IP_PER_MIN", 60),
    legacyPerMin: n("GUARD_LEGACY_PER_MIN", 4),
    peeksPer10Min: n("GUARD_PEEKS_PER_10MIN", 60),
    legacyPeeksPer10Min: n("GUARD_LEGACY_PEEKS_PER_10MIN", 300),
    registrationsPerHour: n("GUARD_REGISTRATIONS_PER_HOUR", 10),
    powBits: n("GUARD_POW_BITS", 22),
    requireDevice: env.GUARD_REQUIRE_DEVICE === "true",
  };
}
