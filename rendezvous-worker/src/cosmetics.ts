// Cosmetics (badges next to names in the tab list). Routes, all under /v1/cosmetics:
//  - GET  /catalog                         the items, with their ASCII art (cosmetics/catalog.json)
//  - GET  /players?ids=<uuid>,<uuid>...    the badge each listed jukz player shows (absent = none)
//  - POST /challenge, POST /session        sign in with the Minecraft account (see cosmetics-logic.ts)
//  - GET  /me, POST /equip                 your items and your pick (x-jukz-cosmetics-token header)
//  - POST /admin/grant, /admin/revoke      entitlements, for paid or granted items (x-jukz-admin)
// Profiles and entitlements live in one SQLite Durable Object (CosmeticsStore): tiny rows, and lookups
// are batched and cached by the mod, so one instance carries a lot of players.

import { DurableObject } from "cloudflare:workers";
import rawCatalog from "../../cosmetics/catalog.json" with { type: "json" };
import { CLIENT_IP_HEADER, type Env } from "./hub.ts";
import {
  type Catalog,
  adminTokenMatches,
  checkEquip,
  issueChallenge,
  issueSession,
  ownedItems,
  parseLookup,
  parsePlayerId,
  parsePlayerName,
  serverIdFor,
  verifyChallenge,
  verifySession,
  validateCatalog,
  visibleBadge,
} from "./cosmetics-logic.ts";
import { BadRequest, RateLimiter } from "./logic.ts";

// Validated at load: a broken catalog edit fails the Worker (and its tests) instead of reaching players.
export const catalog: Catalog = validateCatalog(rawCatalog as unknown as Catalog);

const SESSION_HEADER = "x-jukz-cosmetics-token";
const ADMIN_HEADER = "x-jukz-admin";
const MOJANG_HAS_JOINED = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
const error = (status: number, message: string) => json(status, { status: "error", message });

/** Profiles (who uses jukz, and their pick) and entitlements (items beyond the free ones). */
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

  me(id: string): { equipped: string | null; owned: string[] } {
    const row = this.sql.exec<{ equipped: string | null }>("SELECT equipped FROM profiles WHERE id = ?", id).toArray()[0];
    return { equipped: row?.equipped ?? null, owned: ownedItems(catalog, this.grants(id)) };
  }

  equip(id: string, item: unknown): string {
    const value = checkEquip(catalog, item, this.grants(id));
    this.sql.exec("UPDATE profiles SET equipped = ? WHERE id = ?", value, id);
    return value;
  }

  lookup(ids: string[]): Record<string, string> {
    const out: Record<string, string> = {};
    const marks = ids.map(() => "?").join(",");
    const profiles = this.sql.exec<{ id: string; equipped: string | null }>(
      `SELECT id, equipped FROM profiles WHERE id IN (${marks})`, ...ids).toArray();
    if (profiles.length === 0) return out;
    const grants = new Map<string, string[]>();
    for (const g of this.sql.exec<{ id: string; item: string }>(
      `SELECT id, item FROM entitlements WHERE id IN (${marks})`, ...ids)) {
      grants.set(g.id, [...(grants.get(g.id) ?? []), g.item]);
    }
    for (const p of profiles) {
      const badge = visibleBadge(catalog, p.equipped, grants.get(p.id) ?? []);
      if (badge) out[p.id] = badge;
    }
    return out;
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

  private grants(id: string): string[] {
    return this.sql.exec<{ item: string }>("SELECT item FROM entitlements WHERE id = ?", id).toArray().map((r) => r.item);
  }
}

function store(env: Env) {
  return env.COSMETICS.get(env.COSMETICS.idFromName("cosmetics"));
}

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
        return json(200, { players: await s.lookup(ids) });
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
      case "POST /equip": {
        const id = await sessionPlayer(request, env);
        if (!id) return error(401, "sign in first");
        const body = (await request.json()) as Record<string, unknown>;
        return json(200, { equipped: await store(env).equip(id, body.item ?? null) });
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
    if (e instanceof Error && /unknown item|own that item/.test(e.message)) return error(400, e.message);
    throw e;
  }
}

/**
 * Exchange a challenge the client "joined" with Mojang for a session token. In local dev only
 * (COSMETICS_DEV_UNVERIFIED=true in .dev.vars) offline accounts may claim their id directly.
 */
async function signIn(request: Request, env: Env): Promise<Response> {
  const key = env.SNAPSHOT_SIGNING_KEY;
  if (!key) return error(503, "cosmetics sign-in disabled");
  const body = (await request.json()) as Record<string, unknown>;
  const name = parsePlayerName(body.name);
  if (!(await verifyChallenge(key, body.challenge, Date.now()))) return error(401, "challenge expired, try again");

  let id: string;
  const serverId = await serverIdFor(body.challenge as string);
  const answer = await fetch(`${MOJANG_HAS_JOINED}?username=${encodeURIComponent(name)}&serverId=${serverId}`);
  if (answer.status === 200) {
    const profile = (await answer.json()) as { id: string; name: string };
    id = parsePlayerId(profile.id);
  } else if (env.COSMETICS_DEV_UNVERIFIED === "true" && body.id) {
    id = parsePlayerId(body.id);
  } else {
    return error(401, "Mojang didn't confirm this account (a Microsoft account is needed)");
  }
  await store(env).touch(id, name);
  return json(200, { id, ...(await issueSession(key, id, Date.now())) });
}

async function sessionPlayer(request: Request, env: Env): Promise<string | null> {
  const key = env.SNAPSHOT_SIGNING_KEY;
  return key ? verifySession(key, request.headers.get(SESSION_HEADER), Date.now()) : null;
}
