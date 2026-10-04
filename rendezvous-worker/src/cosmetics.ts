// Cosmetics: tab-list badges and 3D pieces worn on the player (hat, face, back). Routes, all under
// /v1/cosmetics:
//  - GET  /catalog                         the items, with their ASCII art / voxel slices (cosmetics/catalog.json)
//  - GET  /players?ids=<uuid>,<uuid>...    what each listed jukz player wears: `loadouts` (every slot) and
//                                          `players` (badge only, the original shape); absent = nothing
//  - POST /challenge, POST /session        sign in with the Minecraft account (see cosmetics-logic.ts)
//  - GET  /me, POST /equip {slot, item}    your items and picks (x-jukz-cosmetics-token header)
//  - POST /creator-link                    a link to the creators page that verifies the account (mod)
//  - POST /admin/grant, /admin/revoke      entitlements, for paid or granted items (x-jukz-admin)
// Profiles and entitlements live in one SQLite Durable Object (CosmeticsStore): tiny rows, and lookups
// are batched and cached by the mod, so one instance carries a lot of players.

import { DurableObject } from "cloudflare:workers";
import rawCatalog from "../../cosmetics/catalog.json" with { type: "json" };
import { CLIENT_IP_HEADER, type Env } from "./hub.ts";
import {
  type Catalog,
  type Loadout,
  adminTokenMatches,
  checkEquip,
  issueChallenge,
  issueSession,
  ownedItems,
  parseLookup,
  parsePlayerId,
  parsePlayerName,
  parseSlot,
  readLoadout,
  serverIdFor,
  verifyChallenge,
  verifyChallengeSignature,
  verifyPlayerCertificate,
  verifySession,
  validateCatalog,
  visibleLoadout,
} from "./cosmetics-logic.ts";
import {
  type GameLink,
  type ModelSummary,
  type SubmissionStatus,
  LOCK_AFTER_FAILURES,
  MAX_PENDING_PER_CREATOR,
  accountKey,
  checkPassword,
  checkReview,
  checkRewardPick,
  cleanText,
  hashPassword,
  issueLinkToken,
  lockedUntil,
  passwordMatches,
  rewardChoices,
} from "./creators-logic.ts";
import { BadRequest, RateLimiter } from "./logic.ts";
import { MOJANG_CERTIFICATE_KEYS } from "./mojang-keys.ts";

// Validated at load: a broken catalog edit fails the Worker (and its tests) instead of reaching players.
export const catalog: Catalog = validateCatalog(rawCatalog as unknown as Catalog);

export const SESSION_HEADER = "x-jukz-cosmetics-token";
/** Cloud worlds remembered per account (the newest ones win). */
export const MAX_ACCOUNT_WORLDS = 50;
const ADMIN_HEADER = "x-jukz-admin";
const MOJANG_HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
const error = (status: number, message: string) => json(status, { status: "error", message });

/** Profiles (who uses jukz, and their pick) and entitlements (items beyond the free ones). */
export function cosmeticsStore(env: Env) {
  return env.COSMETICS.get(env.COSMETICS.idFromName("cosmetics"));
}

export class CosmeticsStore extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly limiter = new RateLimiter(60);

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS profiles (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, equipped TEXT, seen INTEGER NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS entitlements (
      id TEXT NOT NULL, item TEXT NOT NULL, source TEXT NOT NULL, granted INTEGER NOT NULL,
      PRIMARY KEY (id, item))`);
    // v2: one pick per slot (JSON), replacing the single `equipped` badge (still read for older rows).
    const columns = this.sql.exec<{ name: string }>("PRAGMA table_info(profiles)").toArray().map((c) => c.name);
    if (!columns.includes("loadout")) this.sql.exec("ALTER TABLE profiles ADD COLUMN loadout TEXT");
    // Cloud worlds (index.ts /v1/account/worlds): which worlds each premium player backed up, so their
    // other PCs can bring them over.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS account_worlds (
      player_id TEXT NOT NULL, world_id TEXT NOT NULL, name TEXT NOT NULL, generation INTEGER NOT NULL,
      updated INTEGER NOT NULL, PRIMARY KEY (player_id, world_id))`);
    // Cloud backups per day and subject (limits.ts): an IP without an account, a player with one.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS usage (
      subject TEXT NOT NULL, day TEXT NOT NULL, uploads INTEGER NOT NULL, PRIMARY KEY (subject, day))`);
    // Creators page (creators.ts): accounts and the models they upload.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS creators (
      account TEXT PRIMARY KEY, name TEXT NOT NULL, salt TEXT NOT NULL, hash TEXT NOT NULL,
      iterations INTEGER NOT NULL, player_id TEXT, created INTEGER NOT NULL,
      failures INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS submissions (
      id TEXT PRIMARY KEY, account TEXT NOT NULL, title TEXT NOT NULL, slot TEXT NOT NULL, notes TEXT NOT NULL,
      status TEXT NOT NULL, review_note TEXT NOT NULL DEFAULT '', item_id TEXT, reward_item TEXT,
      bytes INTEGER NOT NULL, summary TEXT NOT NULL, created INTEGER NOT NULL, reviewed INTEGER)`);
  }

  allow(ip: string): boolean {
    return this.limiter.allow(ip, Date.now());
  }

  /** A signed-in player: create or refresh their profile (that is what makes their badge visible). */
  touch(id: string, name: string): void {
    this.sql.exec(
      `INSERT INTO profiles (id, name, equipped, seen) VALUES (?, ?, NULL, ?)
       ON CONFLICT (id) DO UPDATE SET name = excluded.name, seen = excluded.seen`,
      id, name, Date.now(),
    );
  }

  me(id: string): { equipped: string | null; loadout: Loadout; owned: string[] } {
    const loadout = this.stored(id);
    return { equipped: loadout.badge ?? null, loadout, owned: ownedItems(catalog, this.grants(id)) };
  }

  equip(id: string, slot: unknown, item: unknown): { equipped: string; loadout: Loadout } {
    const where = parseSlot(slot);
    const value = checkEquip(catalog, item, this.grants(id), where);
    const loadout = { ...this.stored(id), [where]: value };
    this.sql.exec("UPDATE profiles SET loadout = ? WHERE id = ?", JSON.stringify(loadout), id);
    return { equipped: value, loadout };
  }

  lookup(ids: string[]): { players: Record<string, string>; loadouts: Record<string, Loadout> } {
    const players: Record<string, string> = {};
    const loadouts: Record<string, Loadout> = {};
    const marks = ids.map(() => "?").join(",");
    const profiles = this.sql.exec<{ id: string; equipped: string | null; loadout: string | null }>(
      `SELECT id, equipped, loadout FROM profiles WHERE id IN (${marks})`, ...ids).toArray();
    if (profiles.length === 0) return { players, loadouts };
    const grants = new Map<string, string[]>();
    for (const g of this.sql.exec<{ id: string; item: string }>(
      `SELECT id, item FROM entitlements WHERE id IN (${marks})`, ...ids)) {
      grants.set(g.id, [...(grants.get(g.id) ?? []), g.item]);
    }
    for (const p of profiles) {
      const visible = visibleLoadout(catalog, readLoadout(p.loadout, p.equipped), grants.get(p.id) ?? []);
      if (Object.keys(visible).length) loadouts[p.id] = visible;
      if (visible.badge) players[p.id] = visible.badge;
    }
    return { players, loadouts };
  }

  nameOf(id: string): string | null {
    return this.sql.exec<{ name: string }>("SELECT name FROM profiles WHERE id = ?", id).toArray()[0]?.name ?? null;
  }

  grant(id: string, item: string, source: string): void {
    this.sql.exec(
      `INSERT INTO entitlements (id, item, source, granted) VALUES (?, ?, ?, ?)
       ON CONFLICT (id, item) DO UPDATE SET source = excluded.source`,
      id, item, source, Date.now(),
    );
  }

  revoke(id: string, item: string): void {
    this.sql.exec("DELETE FROM entitlements WHERE id = ? AND item = ?", id, item);
  }

  // ---- limits --------------------------------------------------------------------------------------

  uploadsToday(subject: string, day: string): number {
    return this.sql.exec<{ uploads: number }>("SELECT uploads FROM usage WHERE subject = ? AND day = ?", subject, day)
      .toArray()[0]?.uploads ?? 0;
  }

  countUpload(subject: string, day: string): void {
    this.sql.exec(
      `INSERT INTO usage (subject, day, uploads) VALUES (?, ?, 1)
       ON CONFLICT (subject, day) DO UPDATE SET uploads = uploads + 1`, subject, day);
  }

  /** Daily housekeeping: drop counters older than a week. */
  pruneUsage(beforeDay: string): void {
    this.sql.exec("DELETE FROM usage WHERE day < ?", beforeDay);
  }

  // ---- cloud worlds --------------------------------------------------------------------------------

  /** [playerId] just backed up [worldId] (with the world's key): remember it on their account. */
  rememberWorld(playerId: string, worldId: string, name: string, generation: number): void {
    this.sql.exec(
      `INSERT INTO account_worlds (player_id, world_id, name, generation, updated) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (player_id, world_id) DO UPDATE SET name = excluded.name, generation = excluded.generation, updated = excluded.updated`,
      playerId, worldId, name, generation, Date.now(),
    );
    // Keep the newest MAX_ACCOUNT_WORLDS per player.
    this.sql.exec(
      `DELETE FROM account_worlds WHERE player_id = ? AND world_id NOT IN (
         SELECT world_id FROM account_worlds WHERE player_id = ? ORDER BY updated DESC LIMIT ?)`,
      playerId, playerId, MAX_ACCOUNT_WORLDS,
    );
  }

  accountWorlds(playerId: string): { worldId: string; name: string; generation: number; updated: number }[] {
    return this.sql.exec<{ world_id: string; name: string; generation: number; updated: number }>(
      "SELECT world_id, name, generation, updated FROM account_worlds WHERE player_id = ? ORDER BY updated DESC", playerId)
      .toArray().map((r) => ({ worldId: r.world_id, name: r.name, generation: r.generation, updated: r.updated }));
  }

  ownsWorld(playerId: string, worldId: string): boolean {
    return this.sql.exec("SELECT 1 FROM account_worlds WHERE player_id = ? AND world_id = ?", playerId, worldId).toArray().length > 0;
  }

  forgetWorld(playerId: string, worldId: string): void {
    this.sql.exec("DELETE FROM account_worlds WHERE player_id = ? AND world_id = ?", playerId, worldId);
  }

  // ---- creators (see creators.ts for the routes and creators-logic.ts for the rules) ----------------

  private readonly authLimiter = new RateLimiter(12);

  allowAuth(ip: string): boolean {
    return this.authLimiter.allow(ip, Date.now());
  }

  /**
   * Create an account. With a game [link] for the same name, an existing account that isn't tied to
   * another player is taken over (that's how a premium player recovers a password, or reclaims a name
   * someone else registered first).
   */
  async register(name: string, password: string, link: GameLink | null): Promise<string> {
    const account = accountKey(name);
    if (link && link.name.toLowerCase() !== account) throw new BadRequest(`the game says you are ${link.name}, not ${name}`);
    const existing = this.creator(account);
    if (existing && !(link && (existing.player_id === null || existing.player_id === link.id))) {
      throw new BadRequest(link ? "that name is tied to another Minecraft account" : "that name already has an account (log in, or verify it from the game to take it over)");
    }
    const pw = await hashPassword(checkPassword(password));
    this.sql.exec(
      `INSERT INTO creators (account, name, salt, hash, iterations, player_id, created) VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT (account) DO UPDATE SET name = excluded.name, salt = excluded.salt, hash = excluded.hash,
         iterations = excluded.iterations, player_id = excluded.player_id, failures = 0, locked_until = 0`,
      account, link?.name ?? name, pw.salt, pw.hash, pw.iterations, link?.id ?? null, Date.now(),
    );
    if (link) this.grantCreatorRewards(account);
    return account;
  }

  async login(name: string, password: string): Promise<string> {
    const account = accountKey(name);
    const row = this.creator(account);
    const now = Date.now();
    if (!row) throw new BadRequest("wrong name or password");
    if (row.locked_until > now) throw new BadRequest(`too many wrong passwords; try again in ${Math.ceil((row.locked_until - now) / 60_000)} min`);
    if (!(await passwordMatches(password, row))) {
      const failures = row.failures + 1;
      this.sql.exec("UPDATE creators SET failures = ?, locked_until = ? WHERE account = ?",
        failures >= LOCK_AFTER_FAILURES ? 0 : failures, lockedUntil(failures, now), account);
      throw new BadRequest("wrong name or password");
    }
    this.sql.exec("UPDATE creators SET failures = 0, locked_until = 0 WHERE account = ?", account);
    return account;
  }

  /**
   * Log in with a game link alone (premium players, no password): the account tied to that Minecraft
   * account, created if there is none — taking over an unverified one with the same name.
   */
  async linkLogin(link: GameLink): Promise<string> {
    const tied = this.sql.exec<{ account: string }>("SELECT account FROM creators WHERE player_id = ?", link.id).toArray()[0];
    if (tied) {
      if (tied.account !== link.name.toLowerCase()) {
        // Renamed in Minecraft: move the account to the new name if it's free.
        const target = link.name.toLowerCase();
        if (!this.creator(target)) {
          this.sql.exec("UPDATE creators SET account = ?, name = ? WHERE account = ?", target, link.name, tied.account);
          this.sql.exec("UPDATE submissions SET account = ? WHERE account = ?", target, tied.account);
          return target;
        }
      }
      return tied.account;
    }
    // No password yet: a random one nobody knows; the player can set a real one from the account page.
    const random = [...crypto.getRandomValues(new Uint8Array(24))].map((b) => b.toString(16).padStart(2, "0")).join("");
    return await this.register(link.name, random, link);
  }

  creatorPlayer(account: string): string | null {
    return this.creator(account)?.player_id ?? null;
  }

  async setPassword(account: string, password: string): Promise<void> {
    const pw = await hashPassword(checkPassword(password));
    this.sql.exec("UPDATE creators SET salt = ?, hash = ?, iterations = ?, failures = 0, locked_until = 0 WHERE account = ?",
      pw.salt, pw.hash, pw.iterations, account);
  }

  /** Everything the account page shows. */
  accountSummary(account: string, day: string) {
    const row = this.creator(account);
    if (!row) throw new BadRequest("no such account");
    const player = row.player_id;
    const submissions = this.submissionRows("WHERE account = ? ORDER BY created DESC", account);
    return {
      name: row.name,
      verified: player !== null,
      tier: player ? "account" : "guest",
      uploadsToday: player ? this.uploadsToday(`player:${player}`, day) : null,
      worlds: player ? this.accountWorlds(player) : [],
      loadout: player ? this.stored(player) : {},
      owned: player ? ownedItems(catalog, this.grants(player)).length : 0,
      granted: player ? this.grants(player) : [],
      submissions: {
        total: submissions.length,
        published: submissions.filter((s) => s.status === "published").length,
        pending: submissions.filter((s) => s.status === "pending").length,
      },
    };
  }

  /**
   * Delete everything jukz keeps about this account: the page account, its uploads (whose files the
   * caller removes from R2), and — if it is tied to a Minecraft account — the cosmetics profile, picks,
   * entitlements, cloud world list and counters. Cloud backups of worlds stay: they belong to everyone
   * who plays them, and expire on their own. Returns the submission ids whose files to delete.
   */
  deleteAccount(account: string): string[] {
    const row = this.creator(account);
    if (!row) throw new BadRequest("no such account");
    const ids = this.sql.exec<{ id: string }>("SELECT id FROM submissions WHERE account = ?", account).toArray().map((r) => r.id);
    this.sql.exec("DELETE FROM submissions WHERE account = ?", account);
    this.sql.exec("DELETE FROM creators WHERE account = ?", account);
    if (row.player_id) {
      for (const table of ["profiles", "entitlements"]) this.sql.exec(`DELETE FROM ${table} WHERE id = ?`, row.player_id);
      this.sql.exec("DELETE FROM account_worlds WHERE player_id = ?", row.player_id);
      this.sql.exec("DELETE FROM usage WHERE subject = ?", `player:${row.player_id}`);
    }
    return ids;
  }

  /** Tie a logged-in account to the game account in [link] (same name). */
  verify(account: string, link: GameLink): void {
    const row = this.creator(account);
    if (!row) throw new BadRequest("no such account");
    if (link.name.toLowerCase() !== account) throw new BadRequest(`the game says you are ${link.name}, not ${row.name}`);
    if (row.player_id && row.player_id !== link.id) throw new BadRequest("that name is tied to another Minecraft account");
    this.sql.exec("UPDATE creators SET player_id = ?, name = ? WHERE account = ?", link.id, link.name, account);
    this.grantCreatorRewards(account);
  }

  creatorProfile(account: string) {
    const row = this.creator(account);
    if (!row) throw new BadRequest("no such account");
    return {
      name: row.name,
      verified: row.player_id !== null,
      submissions: this.submissionRows("WHERE account = ? ORDER BY created DESC", account),
      rewardChoices: rewardChoices(catalog),
    };
  }

  addSubmission(account: string, id: string, title: string, slot: string, notes: string, bytes: number, summary: ModelSummary): void {
    const pending = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM submissions WHERE account = ? AND status = 'pending'", account).one().n;
    if (pending >= MAX_PENDING_PER_CREATOR) throw new BadRequest(`you have ${pending} models waiting for review; wait for those first`);
    this.sql.exec(
      `INSERT INTO submissions (id, account, title, slot, notes, status, bytes, summary, created) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?)`,
      id, account, title, slot, notes, bytes, JSON.stringify(summary), Date.now(),
    );
  }

  /** The owning account of a submission, or null. */
  submissionOwner(id: string): string | null {
    return this.sql.exec<{ account: string }>("SELECT account FROM submissions WHERE id = ?", id).toArray()[0]?.account ?? null;
  }

  listSubmissions(status: string | null) {
    return status
      ? this.submissionRows("WHERE status = ? ORDER BY created ASC", status)
      : this.submissionRows("ORDER BY created DESC LIMIT 200");
  }

  review(id: string, next: unknown, note: unknown, itemId: unknown) {
    const row = this.sql.exec<{ status: SubmissionStatus; account: string }>("SELECT status, account FROM submissions WHERE id = ?", id).toArray()[0];
    if (!row) throw new BadRequest("no such submission");
    const decision = checkReview(catalog, row.status, next, itemId);
    this.sql.exec("UPDATE submissions SET status = ?, review_note = ?, item_id = ?, reviewed = ? WHERE id = ?",
      decision.status, cleanText(note, 500, "the note"), decision.itemId, Date.now(), id);
    this.grantCreatorRewards(row.account);
    return this.submissionRows("WHERE id = ?", id)[0];
  }

  /** The extra item a creator takes for a published submission (by the creator, or by us). */
  setReward(id: string, item: unknown, account: string | null) {
    const row = this.sql.exec<{ account: string; status: string; item_id: string | null }>(
      "SELECT account, status, item_id FROM submissions WHERE id = ?", id).toArray()[0];
    if (!row || (account !== null && row.account !== account)) throw new BadRequest("no such submission");
    if (row.status !== "published") throw new BadRequest("the extra item comes once your model is published");
    this.sql.exec("UPDATE submissions SET reward_item = ? WHERE id = ?", checkRewardPick(catalog, item, row.item_id), id);
    this.grantCreatorRewards(row.account);
    return this.submissionRows("WHERE id = ?", id)[0];
  }

  /**
   * Entitlements for a verified creator: each published item, plus each extra pick. Free items need no
   * entitlement today; holding one means the creator keeps it the day it becomes paid.
   */
  private grantCreatorRewards(account: string): void {
    const playerId = this.creator(account)?.player_id;
    if (!playerId) return; // granted once they verify from the game
    for (const s of this.sql.exec<{ id: string; item_id: string | null; reward_item: string | null }>(
      "SELECT id, item_id, reward_item FROM submissions WHERE account = ? AND status = 'published'", account)) {
      if (s.item_id) this.grant(playerId, s.item_id, `creator:${s.id}`);
      if (s.reward_item) this.grant(playerId, s.reward_item, `reward:${s.id}`);
    }
  }

  private creator(account: string) {
    return this.sql.exec<{ account: string; name: string; salt: string; hash: string; iterations: number; player_id: string | null; failures: number; locked_until: number }>(
      "SELECT * FROM creators WHERE account = ?", account).toArray()[0];
  }

  private submissionRows(where: string, ...args: unknown[]) {
    return this.sql.exec<{ id: string; account: string; title: string; slot: string; notes: string; status: string; review_note: string; item_id: string | null; reward_item: string | null; bytes: number; summary: string; created: number; reviewed: number | null }>(
      `SELECT * FROM submissions ${where}`, ...args).toArray().map((r) => ({
      id: r.id, creator: this.creator(r.account)?.name ?? r.account, verified: !!this.creator(r.account)?.player_id,
      title: r.title, slot: r.slot, notes: r.notes, status: r.status,
      reviewNote: r.review_note, itemId: r.item_id, rewardItem: r.reward_item, bytes: r.bytes,
      summary: JSON.parse(r.summary) as ModelSummary, created: r.created, reviewed: r.reviewed,
    }));
  }

  private stored(id: string): Loadout {
    const row = this.sql.exec<{ equipped: string | null; loadout: string | null }>(
      "SELECT equipped, loadout FROM profiles WHERE id = ?", id).toArray()[0];
    return row ? readLoadout(row.loadout, row.equipped) : {};
  }

  private grants(id: string): string[] {
    return this.sql.exec<{ item: string }>("SELECT item FROM entitlements WHERE id = ?", id).toArray().map((r) => r.item);
  }
}

const store = cosmeticsStore;

/** Handles every /v1/cosmetics/* path; the caller has already applied the bearer check. */
export async function handleCosmetics(request: Request, env: Env, url: URL): Promise<Response> {
  const route = `${request.method} ${url.pathname.slice("/v1/cosmetics".length)}`;
  try {
    switch (route) {
      case "GET /catalog":
        return json(200, catalog, { "cache-control": "public, max-age=300" });
      case "GET /players": {
        const ids = parseLookup(url.searchParams.get("ids"));
        const s = store(env);
        if (!(await s.allow(request.headers.get("cf-connecting-ip") ?? request.headers.get(CLIENT_IP_HEADER) ?? "unknown"))) {
          return error(429, "rate limited");
        }
        return json(200, await s.lookup(ids));
      }
      case "POST /challenge": {
        const key = env.SNAPSHOT_SIGNING_KEY;
        if (!key) return error(503, "cosmetics sign-in disabled");
        const nonce = [...crypto.getRandomValues(new Uint8Array(12))].map((b) => b.toString(16).padStart(2, "0")).join("");
        const challenge = await issueChallenge(key, Date.now(), nonce);
        return json(200, { challenge, serverId: await serverIdFor(challenge) });
      }
      case "POST /session":
        return await signIn(request, env);
      case "GET /me": {
        const id = await sessionPlayer(request, env);
        if (!id) return error(401, "sign in first");
        return json(200, { id, ...(await store(env).me(id)) });
      }
      case "POST /creator-link": {
        // A signed-in mod asks for a link that proves "this page visitor is me" on the creators page.
        const id = await sessionPlayer(request, env);
        if (!id || !env.SNAPSHOT_SIGNING_KEY) return error(401, "sign in first");
        const name = await store(env).nameOf(id);
        if (!name) return error(401, "sign in first");
        const token = await issueLinkToken(env.SNAPSHOT_SIGNING_KEY, { id, name }, Date.now());
        const base = env.CREATORS_PAGE_URL || "https://nuulm.com/jukz/crear";
        // {page: "account"} links the account page instead of the creators page (same site, same login).
        const wanted = (await request.json().catch(() => ({}))) as { page?: string };
        const page = wanted.page === "account" ? base.replace(/\/crear$/, "/cuenta") : base;
        return json(200, { url: `${page}#link=${token}` });
      }
      case "POST /equip": {
        const id = await sessionPlayer(request, env);
        if (!id) return error(401, "sign in first");
        const body = (await request.json()) as Record<string, unknown>;
        return json(200, await store(env).equip(id, body.slot, body.item ?? null));
      }
      case "POST /admin/grant":
      case "POST /admin/revoke": {
        if (!adminTokenMatches(env.COSMETICS_ADMIN_TOKEN, request.headers.get(ADMIN_HEADER))) return error(404, "not found");
        const body = (await request.json()) as Record<string, unknown>;
        const id = parsePlayerId(body.id);
        const item = String(body.item ?? "");
        if (!catalog.items.some((i) => i.id === item)) throw new BadRequest("unknown item");
        if (route.endsWith("grant")) await store(env).grant(id, item, String(body.source ?? "admin").slice(0, 200));
        else await store(env).revoke(id, item);
        console.log(`cosmetics ${route} id=${id} item=${item}`);
        return json(200, { status: "ok" });
      }
    }
    return error(404, "not found");
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    if (e instanceof SyntaxError) return error(400, "malformed JSON body");
    // Errors thrown inside the Durable Object arrive as plain Errors carrying the message.
    if (e instanceof Error && /unknown item|unknown slot|own that item|slot$/.test(e.message)) return error(400, e.message);
    throw e;
  }
}

/**
 * Exchange a challenge for a session token. Two proofs of the account, in order:
 *  1. a Mojang player certificate plus the challenge signed with its key (works everywhere — Mojang
 *     refuses hasJoined calls from Cloudflare, so this is the production path);
 *  2. the server-style handshake: the client "joined" sha1(challenge) and Mojang's hasJoined confirms
 *     it (self-hosted Workers / wrangler dev, whose requests Mojang accepts).
 * In local dev only (COSMETICS_DEV_UNVERIFIED=true in .dev.vars) offline accounts may claim their id.
 */
async function signIn(request: Request, env: Env): Promise<Response> {
  const key = env.SNAPSHOT_SIGNING_KEY;
  if (!key) return error(503, "cosmetics sign-in disabled");
  const body = (await request.json()) as Record<string, unknown>;
  const name = parsePlayerName(body.name);
  const challenge = body.challenge;
  if (!(await verifyChallenge(key, challenge, Date.now()))) return error(401, "challenge expired, try again");

  let id: string | null = null;
  if (body.certificate && body.id) {
    const claimed = parsePlayerId(body.id);
    const playerKey = await verifyPlayerCertificate(claimed, body.certificate, await mojangKeys(), Date.now());
    if (playerKey && (await verifyChallengeSignature(playerKey, challenge as string, body.signature))) id = claimed;
    console.log(`cosmetics certificate name=${name} ok=${id !== null}`);
  }
  if (!id) {
    const serverId = await serverIdFor(challenge as string);
    const answer = await fetch(`${MOJANG_HAS_JOINED}?username=${encodeURIComponent(name)}&serverId=${serverId}`);
    if (answer.status === 200) id = parsePlayerId(((await answer.json()) as { id: string }).id);
    else if (env.COSMETICS_DEV_UNVERIFIED === "true" && body.id) id = parsePlayerId(body.id);
  }
  if (!id) return error(401, "Mojang didn't confirm this account (a Microsoft account is needed)");
  await store(env).touch(id, name);
  return json(200, { id, ...(await issueSession(key, id, Date.now())) });
}

/** Mojang's certificate keys: fetched (cached for a day per isolate), else the embedded copy. */
let mojangKeyCache: { keys: string[]; at: number } | null = null;
async function mojangKeys(): Promise<string[]> {
  if (mojangKeyCache && Date.now() - mojangKeyCache.at < 24 * 60 * 60_000) return mojangKeyCache.keys;
  let keys = MOJANG_CERTIFICATE_KEYS;
  try {
    const answer = await fetch("https://api.minecraftservices.com/publickeys", { signal: AbortSignal.timeout(3000) });
    if (answer.ok) {
      const fetched = ((await answer.json()) as { playerCertificateKeys?: { publicKey: string }[] }).playerCertificateKeys?.map((k) => k.publicKey);
      if (fetched?.length) keys = [...new Set([...fetched, ...MOJANG_CERTIFICATE_KEYS])];
    }
  } catch {
    // keep the embedded keys
  }
  mojangKeyCache = { keys, at: Date.now() };
  return keys;
}

export async function sessionPlayer(request: Request, env: Env): Promise<string | null> {
  const key = env.SNAPSHOT_SIGNING_KEY;
  return key ? verifySession(key, request.headers.get(SESSION_HEADER), Date.now()) : null;
}
