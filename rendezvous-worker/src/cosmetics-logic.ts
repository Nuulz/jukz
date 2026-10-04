// Pure cosmetics rules (catalog, ownership, loadouts, sign-in tokens), unit-tested with plain
// `node --test`. The CosmeticsStore Durable Object and the routes in cosmetics.ts only add I/O on top.
//
// Every item fills one slot (its `kind`): a tab-list `badge` (flat ASCII art), or a 3D piece worn on the
// player model — `hat`, `face` or `back` — built from voxels written as ASCII slices (see VoxelModel).
//
// Who owns what: an item's `availability` is "free" (everyone), "paid" or "grant" (only players with an
// entitlement row). Everything ships free today; making an item paid later is a catalog edit plus a
// grant per purchase (POST /v1/cosmetics/admin/grant) — the mod already shows prices and locked items.

import { BadRequest } from "./logic.ts";

export type Availability = "free" | "paid" | "grant";

export const SLOTS = ["badge", "hat", "face", "back"] as const;
export type Slot = (typeof SLOTS)[number];

/**
 * A 3D piece as stacked ASCII slices. `layers[0]` is the bottom slice; in each slice, row 0 is the side
 * facing forward (the front for hat/face, against the body for back) and characters run left→right as
 * seen from the front. Each character is one voxel of `voxel` pixels (1 = a skin pixel), "." is empty.
 * `origin` is where the grid's left-front-bottom corner sits, in the bone's pixel space (the head for
 * hat/face — its top is y = -8, its face z = -4 — the body for back, whose back is z = 2; y grows down).
 */
export interface VoxelModel {
  voxel: number;
  origin: [number, number, number];
  animation?: "none" | "bob" | "spin";
  layers: string[][];
}

export interface CatalogItem {
  id: string;
  kind: Slot;
  name: string;
  description: string;
  availability: Availability;
  /** Shown for "paid" items; amounts are in the currency's minor unit (cents). */
  price?: { amount: number; currency: string };
  /** Single-character keys → AARRGGBB; "." is transparent / empty. */
  palette: Record<string, string>;
  /** Badges: square ASCII art, one string per row, one character per pixel. */
  art?: string[];
  /** hat / face / back: the 3D model. */
  model?: VoxelModel;
}

export interface Catalog {
  version: number;
  defaultBadge: string;
  items: CatalogItem[];
}

export type Loadout = Partial<Record<Slot, string>>;

/** Sentinel for "wear nothing in this slot" (for badges: hide the default one too). */
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
    if (!SLOTS.includes(item.kind)) throw new Error(`${where}: unknown kind ${item.kind}`);
    if (!["free", "paid", "grant"].includes(item.availability)) throw new Error(`${where}: bad availability`);
    if (item.availability === "paid" && !(item.price && Number.isSafeInteger(item.price.amount) && item.price.amount > 0)) {
      throw new Error(`${where}: paid items need a price`);
    }
    for (const [key, color] of Object.entries(item.palette)) {
      if (key.length !== 1 || key === ".") throw new Error(`${where}: palette keys are single non-'.' characters`);
      if (!COLOR_RE.test(color)) throw new Error(`${where}: palette ${key} must be AARRGGBB`);
    }
    const checkRow = (row: string, at: string) => {
      for (const ch of row) if (ch !== "." && !(ch in item.palette)) throw new Error(`${where}: ${at} uses '${ch}', not in the palette`);
    };
    if (item.kind === "badge") {
      const art = item.art ?? [];
      const size = art.length;
      if (size < 8 || size > 32) throw new Error(`${where}: art must be 8..32 rows`);
      art.forEach((row, y) => {
        if (row.length !== size) throw new Error(`${where}: row ${y} is ${row.length} wide, art must be square (${size})`);
        checkRow(row, `row ${y}`);
      });
      continue;
    }
    const model = item.model;
    if (!model) throw new Error(`${where}: 3D items need a model`);
    if (!(model.voxel > 0 && model.voxel <= 2)) throw new Error(`${where}: voxel must be in (0, 2]`);
    if (model.origin?.length !== 3 || !model.origin.every(Number.isFinite)) throw new Error(`${where}: origin is [x, y, z]`);
    if (!["none", "bob", "spin", undefined].includes(model.animation)) throw new Error(`${where}: unknown animation`);
    const layers = model.layers ?? [];
    if (layers.length < 1 || layers.length > 32) throw new Error(`${where}: 1..32 layers`);
    const depth = layers[0].length;
    const width = layers[0][0]?.length ?? 0;
    if (depth < 1 || depth > 32 || width < 1 || width > 32) throw new Error(`${where}: slices are 1..32 on each side`);
    layers.forEach((layer, l) => {
      if (layer.length !== depth) throw new Error(`${where}: layer ${l} has ${layer.length} rows, the model is ${depth} deep`);
      layer.forEach((row, z) => {
        if (row.length !== width) throw new Error(`${where}: layer ${l} row ${z} is ${row.length} wide, the model is ${width}`);
        checkRow(row, `layer ${l} row ${z}`);
      });
    });
  }
  const def = catalog.items.find((i) => i.id === catalog.defaultBadge);
  if (!def || def.kind !== "badge" || def.availability !== "free") throw new Error("defaultBadge must be a free badge");
  return catalog;
}

/** Item ids a player may wear: every free item plus whatever it was granted (unknown grants are ignored). */
export function ownedItems(catalog: Catalog, grants: Iterable<string>): string[] {
  const granted = new Set(grants);
  return catalog.items.filter((i) => i.availability === "free" || granted.has(i.id)).map((i) => i.id);
}

/** What others see a profile wearing. The badge falls back to the default; other slots are empty unless set. */
export function visibleLoadout(catalog: Catalog, stored: Loadout, grants: Iterable<string>): Loadout {
  const owned = new Set(ownedItems(catalog, grants));
  const out: Loadout = {};
  for (const slot of SLOTS) {
    const pick = stored[slot];
    if (pick === NO_BADGE) continue;
    const item = pick ? catalog.items.find((i) => i.id === pick) : undefined;
    if (item && item.kind === slot && owned.has(item.id)) out[slot] = item.id;
    else if (slot === "badge") out.badge = catalog.defaultBadge;
  }
  return out;
}

/** The badge others see in the tab list (the original single-badge API). */
export function visibleBadge(catalog: Catalog, equipped: string | null, grants: Iterable<string>): string | null {
  return visibleLoadout(catalog, equipped ? { badge: equipped } : {}, grants).badge ?? null;
}

export function parseSlot(raw: unknown): Slot {
  if (raw === undefined || raw === null) return "badge"; // the original API equipped badges only
  if (typeof raw !== "string" || !SLOTS.includes(raw as Slot)) throw new BadRequest("unknown slot");
  return raw as Slot;
}

/** What an equip request may put in [slot], or a BadRequest. */
export function checkEquip(catalog: Catalog, item: unknown, grants: Iterable<string>, slot: Slot = "badge"): string {
  if (item === null || item === NO_BADGE) return NO_BADGE;
  const found = typeof item === "string" ? catalog.items.find((i) => i.id === item) : undefined;
  if (!found) throw new BadRequest("unknown item");
  if (found.kind !== slot) throw new BadRequest(`that item doesn't go in the ${slot} slot`);
  if (!ownedItems(catalog, grants).includes(found.id)) throw new BadRequest("you don't own that item");
  return found.id;
}

/** A stored loadout: the JSON column, or the original single-badge column for older rows. */
export function readLoadout(json: string | null, legacyBadge: string | null): Loadout {
  if (json) {
    try {
      const parsed = JSON.parse(json) as Record<string, unknown>;
      const out: Loadout = {};
      for (const slot of SLOTS) if (typeof parsed[slot] === "string") out[slot] = parsed[slot] as string;
      return out;
    } catch {
      // a corrupt row behaves like an empty one
    }
  }
  return legacyBadge ? { badge: legacyBadge } : {};
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

// ---- player certificates ----------------------------------------------------------------------
// Mojang answers hasJoined with 403 to Cloudflare Workers, so production proves the account with the
// player certificate Minecraft already has for chat signing: Mojang signs (UUID, expiry, the player's
// public key) with SHA1withRSA, and the client signs our challenge with the matching private key
// (SHA256withRSA). Both checks are offline: no call to Mojang per sign-in, and no token leaves the game.

export interface PlayerCertificate {
  /** The player's RSA public key, X.509 (SPKI) DER, base64. */
  publicKey: string;
  /** Certificate expiry, epoch ms. */
  expiresAt: number;
  /** Mojang's signature over uuid ‖ expiresAt ‖ publicKey, base64. */
  keySignature: string;
}

function b64(raw: string): Uint8Array {
  return Uint8Array.from(atob(raw), (c) => c.charCodeAt(0));
}

/** The bytes Mojang signs: UUID (most, least significant 64 bits), expiry ms, key DER — big-endian. */
export function certificatePayload(playerId: string, expiresAt: number, keyDer: Uint8Array): Uint8Array {
  const hexId = playerId.replace(/-/g, "");
  const out = new Uint8Array(24 + keyDer.length);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hexId.slice(i * 2, i * 2 + 2), 16);
  new DataView(out.buffer).setBigInt64(16, BigInt(expiresAt));
  out.set(keyDer, 24);
  return out;
}

/** The player's key if Mojang vouches for it (for [playerId], unexpired), else null. */
export async function verifyPlayerCertificate(
  playerId: string, cert: unknown, mojangKeys: string[], nowMs: number,
): Promise<CryptoKey | null> {
  const c = cert as Partial<PlayerCertificate> | null;
  if (!c || typeof c.publicKey !== "string" || typeof c.keySignature !== "string" || !Number.isSafeInteger(c.expiresAt)) return null;
  if ((c.expiresAt as number) < nowMs) return null;
  let keyDer: Uint8Array, keySignature: Uint8Array;
  try {
    keyDer = b64(c.publicKey);
    keySignature = b64(c.keySignature);
  } catch {
    return null;
  }
  const payload = certificatePayload(playerId, c.expiresAt as number, keyDer);
  let vouched = false;
  for (const mojang of mojangKeys) {
    try {
      const key = await crypto.subtle.importKey("spki", b64(mojang), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
      if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, keySignature, payload)) {
        vouched = true;
        break;
      }
    } catch {
      // a malformed Mojang key: try the next
    }
  }
  if (!vouched) return null;
  try {
    return await crypto.subtle.importKey("spki", keyDer, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  } catch {
    return null;
  }
}

/** Did the certificate's private key sign [challenge] (UTF-8, SHA256withRSA)? */
export async function verifyChallengeSignature(playerKey: CryptoKey, challenge: string, signature: unknown): Promise<boolean> {
  if (typeof signature !== "string") return false;
  try {
    return await crypto.subtle.verify("RSASSA-PKCS1-v1_5", playerKey, b64(signature), encoder.encode(challenge));
  } catch {
    return false;
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
