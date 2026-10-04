// jukz rendezvous on Cloudflare Workers. Same `/v1` contract as the Rust server (rendezvous/src/main.rs),
// so the mod needs no changes — only the base URL:
//  - POST /v1/announce, POST /v1/heartbeat, GET /v1/worlds/{id}, POST /v1/withdraw   (discovery)
//  - GET  /v1/relay/{host,connect,work}                                             (WebSocket relay)
//  - POST /v1/snapshot/upload-url, GET /v1/snapshot/{id}                            (ghost snapshots)
//  - /v1/cosmetics/*                                                               (badges, see cosmetics.ts)
//  - /v1/creators/*                                                                (creators page, creators.ts)
//  - GET  /healthz
// State lives in Durable Objects sharded by world (`world:<id>`: record + snapshot fence) and by relay
// session (`relay:<shard>`), so one world's traffic never queues another's. Snapshot bytes never touch
// a Durable Object: the Worker signs short-lived URLs pointing back at itself and streams them to/from
// R2 via the binding, so no S3 credentials exist anywhere.

import { CosmeticsStore, handleCosmetics } from "./cosmetics.ts";
import { corsHeaders, handleCreators } from "./creators.ts";
import { CLIENT_IP_HEADER, type Env, RendezvousHub } from "./hub.ts";
import { BadRequest, parseWorldId, relayShard, shardOfNonce } from "./logic.ts";
import { signBlobUrl, verifyBlobUrl, URL_TTL_SECS } from "./signing.ts";

export { CosmeticsStore, RendezvousHub };

const MAX_BODY_BYTES = 4 * 1024;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const error = (status: number, message: string) => json(status, { status: "error", message });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    // Snapshot blobs are authorised by their HMAC signature, not the bearer token.
    const blob = path.match(/^\/v1\/snapshot\/blob\/([^/]+)\/(pack|head)$/);
    if (blob) return serveBlob(request, env, url, blob[1], blob[2] as "pack" | "head");

    // The creators page (nuulm.com, cross-origin, uploads up to ~1.5 MB): its own size limit and auth.
    if (path.startsWith("/v1/creators/")) return handleCreators(request, env, url);

    const isRelay = path.startsWith("/v1/relay/");
    if (path.startsWith("/v1/") && !isRelay) {
      const denied = checkAuth(request, env);
      if (denied) return denied;
      const length = Number(request.headers.get("content-length") ?? 0);
      if (length > MAX_BODY_BYTES) return error(413, "body too large");
    }
    if (isRelay && request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return error(426, "expected a WebSocket upgrade");
    }

    if (path === "/healthz") {
      return json(200, { status: "ok", snapshotStore: env.SNAPSHOT_SIGNING_KEY ? "enabled" : "disabled" });
    }
    if (path === "/v1/snapshot/fence" || path.startsWith("/v1/snapshot/may-download/")) {
      return error(404, "not found"); // hub-internal, never public
    }

    if (path.startsWith("/v1/cosmetics/")) {
      // The creators page also reads the catalog (reward picker), cross-origin.
      if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(request) });
      const response = await handleCosmetics(request, env, url);
      const cors = corsHeaders(request);
      if (Object.keys(cors).length === 0) return response;
      const headers = new Headers(response.headers);
      for (const [k, v] of Object.entries(cors)) headers.set(k, v);
      return new Response(response.body, { status: response.status, headers });
    }

    try {
      if (path === "/v1/snapshot/upload-url" && request.method === "POST") return await snapshotUploadUrl(request, env, url);
      const download = path.match(/^\/v1\/snapshot\/([^/]+)$/);
      if (download && request.method === "GET") return await snapshotDownloadUrl(request, env, url, download[1]);
      return await routeToShard(request, env, url);
    } catch (e) {
      if (e instanceof BadRequest) return error(400, e.message);
      if (e instanceof SyntaxError) return error(400, "malformed JSON body");
      throw e;
    }
  },
} satisfies ExportedHandler<Env>;

function hub(env: Env, name: string) {
  return env.HUB.get(env.HUB.idFromName(name));
}

/** Headers for a hub call, stamping the caller's public IP (what makes records dialable across NATs). */
function hubHeaders(request: Request): Headers {
  const headers = new Headers(request.headers);
  headers.delete(CLIENT_IP_HEADER); // never trust a client-supplied value
  const ip = request.headers.get("cf-connecting-ip");
  if (ip) headers.set(CLIENT_IP_HEADER, ip);
  return headers;
}

/** Pick the shard that owns this call (the world, or the relay session) and forward it there. */
async function routeToShard(request: Request, env: Env, url: URL): Promise<Response> {
  const path = url.pathname;
  const headers = hubHeaders(request);

  if (path === "/v1/relay/host" || path === "/v1/relay/connect") {
    const session = url.searchParams.get("session") ?? "";
    if (!/^[0-9a-f]{16,64}$/.test(session)) return error(400, "bad session id");
    return hub(env, `relay:${relayShard(session)}`).fetch(new Request(request, { headers }));
  }
  if (path === "/v1/relay/work") {
    const shard = shardOfNonce(Number(url.searchParams.get("nonce")));
    if (shard === undefined) return error(404, "no pending stream for that nonce");
    return hub(env, `relay:${shard}`).fetch(new Request(request, { headers }));
  }

  const lookup = path.match(/^\/v1\/worlds\/([^/]+)$/);
  if (lookup && request.method === "GET") {
    return hub(env, `world:${parseWorldId(lookup[1])}`).fetch(new Request(request, { headers }));
  }
  if (["/v1/announce", "/v1/heartbeat", "/v1/withdraw"].includes(path) && request.method === "POST") {
    // The world id is in the (≤ 4 KB) body: read it once, route on it, forward the same bytes.
    const text = await request.text();
    const worldId = parseWorldId((JSON.parse(text) as Record<string, unknown>).worldId);
    return hub(env, `world:${worldId}`).fetch(new Request(request.url, { method: "POST", headers, body: text }));
  }
  return error(404, "not found");
}

function checkAuth(request: Request, env: Env): Response | null {
  const expected = env.RENDEZVOUS_AUTH_TOKEN;
  if (!expected) return null;
  const provided = request.headers.get("authorization")?.replace(/^Bearer /, "");
  return provided === expected ? null : error(401, "missing or invalid bearer token");
}

// ---- ghost snapshots ------------------------------------------------------------------------------

/** Origin the signed blob URLs point at: the request's own, unless PUBLIC_BASE_URL pins one (local dev). */
function publicOrigin(env: Env, url: URL): string {
  return env.PUBLIC_BASE_URL?.replace(/\/+$/, "") || url.origin;
}

async function snapshotUploadUrl(request: Request, env: Env, url: URL): Promise<Response> {
  if (!env.SNAPSHOT_SIGNING_KEY) return error(503, "snapshot store disabled");
  const text = await request.text();
  const body = JSON.parse(text) as Record<string, unknown>;
  const worldId = parseWorldId(body.worldId);
  // The world's shard holds the generation fence and the ownership key: forward the exact body and the
  // signature headers so it can check both.
  const fence = await hub(env, `world:${worldId}`).fetch("https://hub/v1/snapshot/fence", {
    method: "POST",
    headers: hubHeaders(request),
    body: text,
  });
  if (fence.status !== 200) return fence;
  const key = env.SNAPSHOT_SIGNING_KEY;
  console.log(`snapshot upload signed world=${worldId} generation=${body.generation}`);
  return json(200, {
    packUrl: await signBlobUrl(publicOrigin(env, url), key, "put", worldId, "pack"),
    headUrl: await signBlobUrl(publicOrigin(env, url), key, "put", worldId, "head"),
    expiresInSec: URL_TTL_SECS,
  });
}

/**
 * Signed GET URLs; the guest probes the head URL, and a 404 there means "no ghost". A world with a key
 * only gives its copy to a request signed with it — anyone else gets the same 404 as "no backup".
 */
async function snapshotDownloadUrl(request: Request, env: Env, url: URL, rawId: string): Promise<Response> {
  if (!env.SNAPSHOT_SIGNING_KEY) return json(404, { status: "none" });
  const worldId = parseWorldId(rawId);
  const may = await hub(env, `world:${worldId}`).fetch(`https://hub/v1/snapshot/may-download/${worldId}`, {
    headers: hubHeaders(request),
  });
  if (may.status !== 200) return json(404, { status: "none" });
  const key = env.SNAPSHOT_SIGNING_KEY;
  return json(200, {
    packUrl: await signBlobUrl(publicOrigin(env, url), key, "get", worldId, "pack"),
    headUrl: await signBlobUrl(publicOrigin(env, url), key, "get", worldId, "head"),
  });
}

async function serveBlob(request: Request, env: Env, url: URL, rawId: string, part: "pack" | "head"): Promise<Response> {
  const key = env.SNAPSHOT_SIGNING_KEY;
  if (!key) return error(404, "snapshot store disabled");
  let worldId: string;
  try {
    worldId = parseWorldId(rawId);
  } catch {
    return error(400, "bad world id");
  }
  const op = request.method === "PUT" ? "put" : request.method === "GET" ? "get" : null;
  if (!op) return error(405, "method not allowed");
  if (!(await verifyBlobUrl(url, key, op, worldId, part))) return error(403, "bad or expired signature");

  const objectKey = `${worldId}/${part}`;
  if (op === "put") {
    if (!request.body || request.headers.get("content-length") == null) return error(411, "content-length required");
    await env.SNAPSHOTS.put(objectKey, request.body);
    return new Response(null, { status: 200 });
  }
  const object = await env.SNAPSHOTS.get(objectKey);
  if (!object) return error(404, "no snapshot");
  return new Response(object.body, {
    headers: { "content-length": String(object.size), "content-type": "application/octet-stream" },
  });
}
