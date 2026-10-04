// Pure cosmetics rules (catalog, ownership, sign-in tokens), unit-tested with plain `node --test`.
// The CosmeticsStore Durable Object and the routes in cosmetics.ts only add I/O on top of these.
//
// Who owns what: an item's `availability` is "free" (everyone), "paid" or "grant" (only players with an
// entitlement row). Everything ships free today; making an item paid later is a catalog edit plus a
// grant per purchase (POST /v1/cosmetics/admin/grant) — the mod already shows prices and locked items.

import { BadRequest } from "./logic.ts";

export type Availability = "free" | "paid" | "grant";

export interface CatalogItem {
  id: string;
  kind: "badge";
  name: string;
  description: string;
  availability: Availability;
  /** Shown for "paid" items; amounts are in the currency's minor unit (cents). */
  price?: { amount: number; currency: string };
  /** Single-character keys → AARRGGBB; "." in the art is transparent. */
  palette: Record<string, string>;
  /** Square ASCII art, one string per row, one character per pixel. */
  art: string[];
}

export interface Catalog {
  version: number;
  defaultBadge: string;
  items: CatalogItem[];
}

/** Sentinel for "show no badge". */
export const NO_BADGE = "none";

const ITEM_ID_RE = /^[a-z0-9_-]{1,32}$/;
const COLOR_RE = /^[0-9A-Fa-f]{8}$/;

/** Throws on the first problem, so a broken catalog edit fails the tests instead of reaching players. */
export function validateCatalog(catalog: Catalog): Catalog {
  const ids = new Set<string>();
  for (const item of catalog.items) {
    const where = `item ${item.id}`;
    if (!ITEM_ID_RE.test(item.id) || item.id === NO_BADGE) throw new Error(`${where}: bad id`);
    if (ids.has(item.id)) throw new Error(`${where}: duplicate id`);
    ids.add(item.id);
    if (item.kind !== "badge") throw new Error(`${where}: unknown kind ${item.kind}`);
    if (!["free", "paid", "grant"].includes(item.availability)) throw new Error(`${where}: bad availability`);
    if (item.availability === "paid" && !(item.price && Number.isSafeInteger(item.price.amount) && item.price.amount > 0)) {
      throw new Error(`${where}: paid items need a price`);
    }
    const size = item.art.length;
    if (size < 8 || size > 32) throw new Error(`${where}: art must be 8..32 rows`);
    for (const [key, color] of Object.entries(item.palette)) {
      if (key.length !== 1 || key === ".") throw new Error(`${where}: palette keys are single non-'.' characters`);
      if (!COLOR_RE.test(color)) throw new Error(`${where}: palette ${key} must be AARRGGBB`);
    }
    item.art.forEach((row, y) => {
      if (row.length !== size) throw new Error(`${where}: row ${y} is ${row.length} wide, art must be square (${size})`);
      for (const ch of row) if (ch !== "." && !(ch in item.palette)) throw new Error(`${where}: row ${y} uses '${ch}', not in the palette`);
    });
  }
  const def = catalog.items.find((i) => i.id === catalog.defaultBadge);
  if (!def || def.availability !== "free") throw new Error("defaultBadge must be a free item");
  return catalog;
}

/** Item ids [uuid] may equip: every free item plus whatever it was granted (unknown grants are ignored). */
export function ownedItems(catalog: Catalog, grants: Iterable<string>): string[] {
  const granted = new Set(grants);
  return catalog.items.filter((i) => i.availability === "free" || granted.has(i.id)).map((i) => i.id);
}

/** The badge others see for a profile: its pick if still owned, else the default; null = hidden. */
export function visibleBadge(catalog: Catalog, equipped: string | null, grants: Iterable<string>): string | null {
  if (equipped === NO_BADGE) return null;
  if (equipped && ownedItems(catalog, grants).includes(equipped)) return equipped;
  return catalog.defaultBadge;
}

/** What an equip request may set, or a BadRequest. */
export function checkEquip(catalog: Catalog, item: unknown, grants: Iterable<string>): string {
  if (item === null || item === NO_BADGE) return NO_BADGE;
  if (typeof item !== "string" || !catalog.items.some((i) => i.id === item)) throw new BadRequest("unknown item");
  if (!ownedItems(catalog, grants).includes(item)) throw new BadRequest("you don't own that item");
  return item;
}

// ---- identities ---------------------------------------------------------------------------------

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const UUID_PLAIN_RE = /^[0-9a-f]{32}$/;
const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

/** A player UUID, dashed and lower-case (Mojang's API returns it without dashes). */
export function parsePlayerId(raw: unknown): string {
  const id = typeof raw === "string" ? raw.toLowerCase() : "";
  if (UUID_RE.test(id)) return id;
  if (UUID_PLAIN_RE.test(id)) return `${id.slice(0, 8)}-${id.slice(8, 12)}-${id.slice(12, 16)}-${id.slice(16, 20)}-${id.slice(20)}`;
  throw new BadRequest("bad player id");
}

export function parsePlayerName(raw: unknown): string {
  if (typeof raw !== "string" || !NAME_RE.test(raw)) throw new BadRequest("bad player name");
  return raw;
}

// ---- sign-in ------------------------------------------------------------------------------------
// Proving "I am this Minecraft account" works like joining a server: the Worker hands out a signed
// challenge, the client tells Mojang it is joining server id sha1(challenge) (Session.joinServer), and
// the Worker asks Mojang hasJoined(name, serverId), which answers with the account's UUID. The Worker
// then issues a session token. Both are HMACs, so nothing is stored for them.

export const CHALLENGE_TTL_MS = 2 * 60_000;
export const SESSION_TTL_MS = 24 * 60 * 60_000;

const encoder = new TextEncoder();

async function hmacHex(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey("raw", encoder.encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(message));
  return hex(new Uint8Array(sig));
}

function hex(bytes: Uint8Array): string {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function sameString(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function issueChallenge(key: string, nowMs: number, nonce: string): Promise<string> {
  return `${nowMs}.${nonce}.${await hmacHex(key, `cosmetics-challenge:${nowMs}:${nonce}`)}`;
}

export async function verifyChallenge(key: string, challenge: unknown, nowMs: number): Promise<boolean> {
  if (typeof challenge !== "string") return false;
  const [ts, nonce, sig, ...rest] = challenge.split(".");
  const issued = Number(ts);
  if (rest.length || !nonce || !sig || !Number.isSafeInteger(issued)) return false;
  if (nowMs - issued > CHALLENGE_TTL_MS || issued > nowMs + 5_000) return false;
  return sameString(sig, await hmacHex(key, `cosmetics-challenge:${issued}:${nonce}`));
}

/** The server id the client joins for [challenge]: sha1 hex, the same digest length vanilla uses. */
export async function serverIdFor(challenge: string): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-1", encoder.encode(`jukz-cosmetics:${challenge}`))));
}

export async function issueSession(key: string, playerId: string, nowMs: number): Promise<{ token: string; expiresAt: number }> {
  const exp = nowMs + SESSION_TTL_MS;
  return { token: `${playerId}.${exp}.${await hmacHex(key, `cosmetics-session:${playerId}:${exp}`)}`, expiresAt: exp };
}

/** The player a session token belongs to, or null if it is forged or expired. */
export async function verifySession(key: string, token: string | null, nowMs: number): Promise<string | null> {
  if (!token) return null;
  const [id, exp, sig, ...rest] = token.split(".");
  const expires = Number(exp);
  if (rest.length || !id || !sig || !Number.isSafeInteger(expires) || expires < nowMs) return null;
  if (!sameString(sig, await hmacHex(key, `cosmetics-session:${id}:${expires}`))) return null;
  try {
    return parsePlayerId(id);
  } catch {
    return null;
  }
}

/** Constant-time equality for the admin token. */
export function adminTokenMatches(expected: string | undefined, provided: string | null): boolean {
  return !!expected && !!provided && sameString(expected, provided);
}

/** Lookup batches are capped so one request can't scan the table. */
export const MAX_LOOKUP = 100;

export function parseLookup(raw: string | null): string[] {
  const ids = (raw ?? "").split(",").filter(Boolean);
  if (ids.length === 0 || ids.length > MAX_LOOKUP) throw new BadRequest(`ask for 1..${MAX_LOOKUP} players`);
  return [...new Set(ids.map(parsePlayerId))];
}
