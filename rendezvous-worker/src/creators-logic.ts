// Pure rules for the creators page (nuulm.com/jukz/crear): accounts, tokens, the game link, uploaded
// Blockbench models and rewards. Unit-tested with `node --test`; creators.ts adds the I/O.
//
// Accounts are a Minecraft name plus a password. Nothing proves the name belongs to the person typing
// it (non-premium players have nothing to prove it with), so the page says so. A premium player can
// verify it from inside the game: the mod (signed in with the player's Mojang certificate) asks the
// Worker for a short-lived link token naming the player's UUID and name, and the page sends it along.
// A verified account carries the UUID, which is where rewards go.

import { type Catalog, hmacHex, parsePlayerId, parsePlayerName, sameString } from "./cosmetics-logic.ts";
import { BadRequest } from "./logic.ts";

export const SUBMISSION_SLOTS = ["hat", "face", "back"] as const;
export type SubmissionSlot = (typeof SUBMISSION_SLOTS)[number];
export type SubmissionStatus = "pending" | "approved" | "rejected" | "published";

export const MAX_MODEL_BYTES = 1 << 20; // 1 MB of .bbmodel JSON
export const MAX_PREVIEW_BYTES = 300 * 1024;
export const MAX_PENDING_PER_CREATOR = 5;
export const MAX_MODEL_PIXELS = 48; // per axis: anything bigger can't be worn
export const CREATOR_SESSION_MS = 7 * 24 * 60 * 60_000;
export const LINK_TTL_MS = 15 * 60_000;
export const LOCK_AFTER_FAILURES = 5;
export const LOCK_MS = 15 * 60_000;
export const PBKDF2_ITERATIONS = 100_000; // the Workers runtime caps PBKDF2 at 100k

const encoder = new TextEncoder();
const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const unb64 = (text: string) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
const b64url = (text: string) => btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const unb64url = (text: string) => atob(text.replace(/-/g, "+").replace(/_/g, "/"));

// ---- accounts -----------------------------------------------------------------------------------

/** The account key: the Minecraft name, case-insensitive (Minecraft names are). */
export function accountKey(raw: unknown): string {
  return parsePlayerName(raw).toLowerCase();
}

export function checkPassword(raw: unknown): string {
  if (typeof raw !== "string" || raw.length < 8 || raw.length > 128) throw new BadRequest("the password needs 8 to 128 characters");
  return raw;
}

export interface PasswordHash {
  salt: string;
  hash: string;
  iterations: number;
}

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, 256));
}

export async function hashPassword(password: string, iterations = PBKDF2_ITERATIONS): Promise<PasswordHash> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return { salt: b64(salt), hash: b64(await pbkdf2(password, salt, iterations)), iterations };
}

export async function passwordMatches(password: string, stored: PasswordHash): Promise<boolean> {
  return sameString(b64(await pbkdf2(password, unb64(stored.salt), stored.iterations)), stored.hash);
}

/** Wrong passwords lock the account for a while after [LOCK_AFTER_FAILURES] in a row. */
export function lockedUntil(failures: number, nowMs: number): number {
  return failures >= LOCK_AFTER_FAILURES ? nowMs + LOCK_MS : 0;
}

// ---- tokens -------------------------------------------------------------------------------------

export async function issueCreatorToken(key: string, account: string, nowMs: number): Promise<{ token: string; expiresAt: number }> {
  const exp = nowMs + CREATOR_SESSION_MS;
  return { token: `${account}.${exp}.${await hmacHex(key, `creator-session:${account}:${exp}`)}`, expiresAt: exp };
}

/** The account a page session belongs to, or null if forged or expired. */
export async function verifyCreatorToken(key: string, token: string | null, nowMs: number): Promise<string | null> {
  if (!token) return null;
  const [account, exp, sig, ...rest] = token.split(".");
  const expires = Number(exp);
  if (rest.length || !account || !sig || !Number.isSafeInteger(expires) || expires < nowMs) return null;
  return sameString(sig, await hmacHex(key, `creator-session:${account}:${expires}`)) ? account : null;
}

export interface GameLink {
  id: string;
  name: string;
}

/** Issued to a signed-in mod: proves "this page visitor is this Minecraft account" for 15 minutes. */
export async function issueLinkToken(key: string, link: GameLink, nowMs: number): Promise<string> {
  const body = b64url(JSON.stringify({ id: link.id, name: link.name, exp: nowMs + LINK_TTL_MS }));
  return `${body}.${await hmacHex(key, `creator-link:${body}`)}`;
}

export async function verifyLinkToken(key: string, token: unknown, nowMs: number): Promise<GameLink | null> {
  if (typeof token !== "string") return null;
  const [body, sig, ...rest] = token.split(".");
  if (rest.length || !body || !sig || !sameString(sig, await hmacHex(key, `creator-link:${body}`))) return null;
  try {
    const data = JSON.parse(unb64url(body)) as { id: unknown; name: unknown; exp: unknown };
    if (typeof data.exp !== "number" || data.exp < nowMs) return null;
    return { id: parsePlayerId(data.id), name: parsePlayerName(data.name) };
  } catch {
    return null;
  }
}

// ---- submissions --------------------------------------------------------------------------------

export function parseSubmissionSlot(raw: unknown): SubmissionSlot {
  if (typeof raw !== "string" || !SUBMISSION_SLOTS.includes(raw as SubmissionSlot)) throw new BadRequest("pick hat, face or back");
  return raw as SubmissionSlot;
}

export function cleanText(raw: unknown, max: number, what: string, required = false): string {
  const text = typeof raw === "string" ? raw.trim().replace(/\s+/g, " ") : "";
  if (required && !text) throw new BadRequest(`${what} is required`);
  if (text.length > max) throw new BadRequest(`${what} is at most ${max} characters`);
  return text;
}

export interface ModelSummary {
  elements: number;
  textures: number;
  size: [number, number, number];
}

type Element = { from?: unknown; to?: unknown; type?: unknown };

/**
 * A plausible Blockbench model: JSON with Blockbench's `meta`, 1..1024 cube elements and up to 8
 * textures, no bigger than [MAX_MODEL_PIXELS] on any axis. It is only stored for a human to review —
 * nothing here runs it — so this keeps out junk, not cleverness.
 */
export function checkBbmodel(text: string): ModelSummary {
  let model: { meta?: { format_version?: unknown }; elements?: unknown; textures?: unknown };
  try {
    model = JSON.parse(text);
  } catch {
    throw new BadRequest("that isn't a Blockbench .bbmodel file (not JSON)");
  }
  if (typeof model?.meta?.format_version !== "string") throw new BadRequest("that isn't a Blockbench .bbmodel file (no meta.format_version)");
  const elements = Array.isArray(model.elements) ? (model.elements as Element[]) : [];
  const cubes = elements.filter((e) => e && (e.type === undefined || e.type === "cube"));
  if (cubes.length === 0) throw new BadRequest("the model has no cubes");
  if (elements.length > 1024) throw new BadRequest("the model has more than 1024 elements");
  const textures = Array.isArray(model.textures) ? model.textures.length : 0;
  if (textures > 8) throw new BadRequest("use at most 8 textures");
  const lo = [Infinity, Infinity, Infinity];
  const hi = [-Infinity, -Infinity, -Infinity];
  for (const e of cubes) {
    for (const corner of [e.from, e.to]) {
      if (!Array.isArray(corner) || corner.length !== 3 || !corner.every((n) => typeof n === "number" && Number.isFinite(n))) {
        throw new BadRequest("a cube has broken coordinates");
      }
      corner.forEach((n: number, i) => {
        lo[i] = Math.min(lo[i], n);
        hi[i] = Math.max(hi[i], n);
      });
    }
  }
  const size = [0, 1, 2].map((i) => Math.round((hi[i] - lo[i]) * 100) / 100) as [number, number, number];
  if (size.some((n) => n > MAX_MODEL_PIXELS)) throw new BadRequest(`the model is ${size.join("×")} pixels; keep it within ${MAX_MODEL_PIXELS} on each side`);
  return { elements: cubes.length, textures, size };
}

/** A small PNG preview from the page (a data: URL), or null. */
export function decodePreview(raw: unknown): Uint8Array | null {
  if (typeof raw !== "string" || !raw.startsWith("data:image/png;base64,")) return null;
  const bytes = unb64(raw.slice("data:image/png;base64,".length));
  if (bytes.length > MAX_PREVIEW_BYTES) throw new BadRequest("preview too large");
  if (bytes[0] !== 0x89 || bytes[1] !== 0x50) throw new BadRequest("preview isn't a PNG");
  return bytes;
}

export function newSubmissionId(nowMs: number): string {
  const rand = [...crypto.getRandomValues(new Uint8Array(5))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${nowMs.toString(36)}-${rand}`;
}

export function parseSubmissionId(raw: unknown): string {
  if (typeof raw !== "string" || !/^[a-z0-9]{6,12}-[0-9a-f]{10}$/.test(raw)) throw new BadRequest("bad submission id");
  return raw;
}

// ---- review and rewards -------------------------------------------------------------------------

/** What an admin may set: approve / reject a pending one, publish an approved one as a catalog item. */
export function checkReview(catalog: Catalog, current: SubmissionStatus, next: unknown, itemId: unknown): { status: SubmissionStatus; itemId: string | null } {
  if (next === "approved" || next === "rejected") {
    if (current === "published") throw new BadRequest("it's already published");
    return { status: next, itemId: null };
  }
  if (next === "published") {
    const item = catalog.items.find((i) => i.id === itemId);
    if (!item) throw new BadRequest("publish needs the catalog item id it became (deploy the catalog first)");
    if (item.kind === "badge") throw new BadRequest("community items are 3D pieces, not badges");
    return { status: "published", itemId: item.id };
  }
  if (next === "pending") return { status: "pending", itemId: null };
  throw new BadRequest("status is pending, approved, rejected or published");
}

/** Items a creator may take as the extra reward: anything sellable, not grant-only specials. */
export function rewardChoices(catalog: Catalog): string[] {
  return catalog.items.filter((i) => i.availability !== "grant").map((i) => i.id);
}

export function checkRewardPick(catalog: Catalog, item: unknown, ownItem: string | null): string {
  if (typeof item !== "string" || !rewardChoices(catalog).includes(item)) throw new BadRequest("pick an item from the catalog");
  if (item === ownItem) throw new BadRequest("you already get your own item; pick another one");
  return item;
}
