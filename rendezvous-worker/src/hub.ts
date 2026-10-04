import { DurableObject } from "cloudflare:workers";
import {
  BadRequest,
  type OwnerOp,
  type Entry,
  RateLimiter,
  VALID_FIRST_BYTES,
  type WorldRecord,
  announce,
  checkOwner,
  fenceAllows,
  heartbeat,
  liveRecord,
  mergeObservedEndpoint,
  nonceFor,
  parseEndpoints,
  parseRelay,
  parseToken,
  parseWorldId,
  relayShard,
  shouldWithdraw,
} from "./logic.ts";

export interface Env {
  HUB: DurableObjectNamespace<RendezvousHub>;
  COSMETICS: DurableObjectNamespace<import("./cosmetics.ts").CosmeticsStore>;
  SNAPSHOTS: R2Bucket;
  SNAPSHOT_SIGNING_KEY?: string;
  RENDEZVOUS_AUTH_TOKEN?: string;
  /** Grants/revokes cosmetics entitlements (secret); the admin routes 404 while it is unset. */
  COSMETICS_ADMIN_TOKEN?: string;
  /** Uploaded creator models (submissions/<id>.bbmodel and .png). */
  CREATIONS: R2Bucket;
  /** Where the creators page lives (the mod's link points there); defaults to nuulm.com/jukz/crear. */
  CREATORS_PAGE_URL?: string;
  /** Local dev only: let offline accounts sign in to cosmetics without Mojang. Never set in production. */
  COSMETICS_DEV_UNVERIFIED?: string;
  /** Optional base URL for signed snapshot URLs (wrangler dev rewrites the request host to the route). */
  PUBLIC_BASE_URL?: string;
  RENDEZVOUS_TTL_MS: string;
  RENDEZVOUS_RATE_LIMIT_PER_MIN: string;
  RELAY_MAX_SESSIONS: string;
  RELAY_MAX_STREAMS_PER_SESSION: string;
  RELAY_WORKCONN_TIMEOUT_MS: string;
}

/** Header the Worker uses to hand the hub the caller's public IP (CF-Connecting-IP). */
export const CLIENT_IP_HEADER = "x-jukz-client-ip";

/** Host control links are pinged this often so an idle link is never reaped by an intermediary. */
const KEEPALIVE_MS = 45_000;
/** Cap on guest bytes buffered while the host's work connection is still opening. */
const MAX_PENDING_BYTES = 1 << 20;

type Attachment =
  | { role: "host"; session: string }
  | { role: "guest"; session: string; nonce: number; validated: boolean }
  | { role: "work"; session: string; nonce: number };

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const error = (status: number, message: string) => json(status, { status: "error", message });

/**
 * One rendezvous shard. The Worker routes each world's discovery calls to the instance named
 * `world:<id>` and each relay session to `relay:<shard>` (see `relayShard`), so a busy relay never
 * queues another world's announce/withdraw. World records live in durable storage (a restart no longer
 * drops every lease); relay sockets use the WebSocket Hibernation API, so a host waiting for guests
 * costs no duration while idle. Socket roles and pairings ride on tags + attachments, which survive
 * hibernation.
 */
export class RendezvousHub extends DurableObject<Env> {
  private readonly ttlMs: number;
  private readonly limiter: RateLimiter;
  /** Guest frames that arrived before the host's work conn for that nonce (in-memory, short-lived). */
  private readonly pending = new Map<number, { frames: ArrayBuffer[]; bytes: number }>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.ttlMs = Number(env.RENDEZVOUS_TTL_MS) || 90_000;
    this.limiter = new RateLimiter(Number(env.RENDEZVOUS_RATE_LIMIT_PER_MIN) || 120);
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      if (path === "/v1/relay/host") return this.relayHost(url);
      if (path === "/v1/relay/connect") return this.relayConnect(url);
      if (path === "/v1/relay/work") return this.relayWork(url);

      const limited = this.rateLimit(request);
      if (limited) return limited;

      if (path === "/v1/announce" && request.method === "POST") return await this.announce(request);
      if (path === "/v1/heartbeat" && request.method === "POST") return await this.heartbeat(request);
      if (path === "/v1/withdraw" && request.method === "POST") return await this.withdraw(request);
      if (path === "/v1/snapshot/fence" && request.method === "POST") return await this.fence(request);
      const download = path.match(/^\/v1\/snapshot\/may-download\/([^/]+)$/);
      if (download && request.method === "GET") return await this.mayDownload(request, download[1]);
      const lookup = path.match(/^\/v1\/worlds\/([^/]+)$/);
      if (lookup && request.method === "GET") return await this.lookup(lookup[1]);
      return error(404, "not found");
    } catch (e) {
      if (e instanceof BadRequest) return error(400, e.message);
      if (e instanceof SyntaxError) return error(400, "malformed JSON body");
      throw e;
    }
  }

  // ---- discovery --------------------------------------------------------------------------------

  private rateLimit(request: Request): Response | null {
    const ip = request.headers.get(CLIENT_IP_HEADER) ?? "unknown";
    if (this.limiter.allow(ip, Date.now())) return null;
    return error(429, "rate limit exceeded");
  }

  private entry(worldId: string): Promise<Entry | undefined> {
    return this.ctx.storage.get<Entry>(`w:${worldId}`);
  }

  /**
   * Parse a write's body and check the world's ownership signature (see checkOwner). Returns the parsed
   * body plus a `commit` to bind the key once the change succeeds, or the refusal to send back.
   */
  private async ownedWrite(request: Request, op: OwnerOp): Promise<
    { body: Record<string, unknown>; worldId: string; commit: () => Promise<void> } | { refusal: Response }
  > {
    const text = await request.text();
    const body = JSON.parse(text) as Record<string, unknown>;
    const worldId = parseWorldId(body.worldId);
    const bound = await this.ctx.storage.get<string>(`k:${worldId}`);
    const check = await checkOwner(op, worldId, text, bound, (n) => request.headers.get(n), Date.now());
    if (!check.ok) {
      console.log(`${op} refused world=${worldId}: ${check.message}`);
      return { refusal: json(check.status, { status: "forbidden", message: check.message }) };
    }
    const commit = async () => {
      if (check.bind) {
        await this.ctx.storage.put(`k:${worldId}`, check.bind);
        console.log(`world key bound world=${worldId}`);
      }
    };
    return { body, worldId, commit };
  }

  private async announce(request: Request): Promise<Response> {
    const w = await this.ownedWrite(request, "announce");
    if ("refusal" in w) return w.refusal;
    const { body, worldId } = w;
    const endpoints = mergeObservedEndpoint(parseEndpoints(body.endpoints), request.headers.get(CLIENT_IP_HEADER));
    const record: WorldRecord = {
      worldId,
      token: parseToken(body.token),
      endpoints,
      heartbeatSeq: Number(body.heartbeatSeq) || 0,
      playerCount: Number(body.playerCount) || 0,
      relay: parseRelay(body.relay),
    };
    const outcome = announce(await this.entry(worldId), record, Date.now(), this.ttlMs);
    if (outcome.kind === "rejected") {
      console.log(`announce rejected world=${worldId} generation=${record.token.generation} incumbent=${outcome.current.token.generation}`);
      return json(409, { status: "rejected", current: outcome.current });
    }
    await this.ctx.storage.put(`w:${worldId}`, outcome.entry);
    await w.commit();
    await this.ensureAlarm();
    console.log(`published world=${worldId} generation=${record.token.generation}`);
    return json(200, { status: "published", ttlMs: this.ttlMs, record });
  }

  private async heartbeat(request: Request): Promise<Response> {
    const w = await this.ownedWrite(request, "heartbeat");
    if ("refusal" in w) return w.refusal;
    const { body, worldId } = w;
    const token = parseToken(body.token);
    const outcome = heartbeat(
      await this.entry(worldId), token, Number(body.heartbeatSeq) || 0, Number(body.playerCount) || 0, Date.now(), this.ttlMs,
    );
    switch (outcome.kind) {
      case "refreshed":
        await this.ctx.storage.put(`w:${worldId}`, outcome.entry);
        await w.commit();
        return json(200, { status: "refreshed", ttlMs: this.ttlMs });
      case "superseded":
        return json(409, { status: "superseded", current: outcome.current });
      case "unknown":
        return json(409, { status: "unknown" });
    }
  }

  private async lookup(rawId: string): Promise<Response> {
    const worldId = parseWorldId(rawId);
    const record = liveRecord(await this.entry(worldId), Date.now());
    return record ? json(200, record) : json(404, { status: "unknown" });
  }

  private async withdraw(request: Request): Promise<Response> {
    const w = await this.ownedWrite(request, "withdraw");
    if ("refusal" in w) return w.refusal;
    const { body, worldId } = w;
    const token = parseToken(body.token);
    if (shouldWithdraw(await this.entry(worldId), token, Date.now())) {
      await this.ctx.storage.delete(`w:${worldId}`);
      await w.commit();
      console.log(`withdrawn world=${worldId}`);
    }
    return new Response(null, { status: 204 });
  }

  /** Snapshot upload fence (per world, durable): 200 when this generation may upload, 409 when stale. */
  private async fence(request: Request): Promise<Response> {
    const w = await this.ownedWrite(request, "snapshot-upload");
    if ("refusal" in w) return w.refusal;
    const { body, worldId } = w;
    const generation = Number(body.generation);
    if (!Number.isSafeInteger(generation)) throw new BadRequest("generation must be an integer");
    const current = await this.ctx.storage.get<number>(`f:${worldId}`);
    if (!fenceAllows(current, generation)) return json(409, { status: "stale" });
    await this.ctx.storage.put(`f:${worldId}`, generation);
    await w.commit();
    return json(200, { status: "ok" });
  }

  /**
   * Whether this caller may read the world's cloud copy: anyone for a world without a key (older mods),
   * otherwise only a request signed with the bound key. Never binds a key — reading proves nothing.
   */
  private async mayDownload(request: Request, rawId: string): Promise<Response> {
    const worldId = parseWorldId(rawId);
    const bound = await this.ctx.storage.get<string>(`k:${worldId}`);
    const check = await checkOwner("snapshot-download", worldId, "", bound, (n) => request.headers.get(n), Date.now());
    if (!check.ok) return json(check.status, { status: "forbidden", message: check.message });
    return json(200, { status: "ok" });
  }

  // ---- relay (WebSocket reverse tunnel) ---------------------------------------------------------

  private sockets(role: Attachment["role"]): WebSocket[] {
    return this.ctx.getWebSockets().filter((ws) => (ws.deserializeAttachment() as Attachment | null)?.role === role);
  }

  private accept(tags: string[], attachment: Attachment): Response {
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server, tags);
    server.serializeAttachment(attachment);
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Host control link: registers the session; "open a work conn for nonce N" signals go down it. */
  private relayHost(url: URL): Response {
    const session = url.searchParams.get("session") ?? "";
    if (!/^[0-9a-f]{16,64}$/.test(session)) return error(400, "bad session id");
    if (this.ctx.getWebSockets(`host:${session}`).length > 0) return error(409, "relay session unavailable");
    if (this.sockets("host").length >= (Number(this.env.RELAY_MAX_SESSIONS) || 200)) {
      return error(409, "relay session unavailable");
    }
    console.log(`relay host link open session=${session}`);
    const response = this.accept([`host:${session}`], { role: "host", session });
    this.ctx.waitUntil(this.ensureAlarm());
    return response;
  }

  /** Guest stream: allocate a nonce, signal the host, and wait for its work conn to splice to. */
  private relayConnect(url: URL): Response {
    const session = url.searchParams.get("session") ?? "";
    const host = this.ctx.getWebSockets(`host:${session}`)[0];
    if (!host) return error(404, "no such relay session");
    const maxStreams = Number(this.env.RELAY_MAX_STREAMS_PER_SESSION) || 16;
    if (this.ctx.getWebSockets(`sess:${session}`).filter((ws) => this.role(ws) === "guest").length >= maxStreams) {
      return error(429, "session stream cap reached");
    }
    const nonce = nonceFor(relayShard(session)); // encodes this shard so /relay/work routes back here
    try {
      host.send(String(nonce));
    } catch {
      return error(404, "host gone");
    }
    const response = this.accept([`guest:${nonce}`, `sess:${session}`], { role: "guest", session, nonce, validated: false });
    // The host has this long to dial back; otherwise the guest is told the stream failed.
    const timeoutMs = Number(this.env.RELAY_WORKCONN_TIMEOUT_MS) || 8000;
    setTimeout(() => {
      if (this.ctx.getWebSockets(`work:${nonce}`).length === 0) {
        this.pending.delete(nonce);
        for (const g of this.ctx.getWebSockets(`guest:${nonce}`)) safeClose(g, 1011, "relay work conn never arrived");
        console.log(`relay work conn never arrived nonce=${nonce}`);
      }
    }, timeoutMs);
    return response;
  }

  /** Host work conn for `nonce`: paired with the waiting guest, then any buffered guest frames flow. */
  private relayWork(url: URL): Response {
    const nonce = Number(url.searchParams.get("nonce"));
    const guest = this.ctx.getWebSockets(`guest:${nonce}`)[0];
    if (!guest || this.ctx.getWebSockets(`work:${nonce}`).length > 0) {
      return error(404, "no pending stream for that nonce");
    }
    const session = (guest.deserializeAttachment() as Attachment).session;
    const response = this.accept([`work:${nonce}`, `sess:${session}`], { role: "work", session, nonce });
    const work = this.ctx.getWebSockets(`work:${nonce}`)[0];
    const buffered = this.pending.get(nonce);
    this.pending.delete(nonce);
    if (work && buffered) for (const frame of buffered.frames) work.send(frame);
    return response;
  }

  private role(ws: WebSocket): Attachment["role"] | undefined {
    return (ws.deserializeAttachment() as Attachment | null)?.role;
  }

  private peerOf(att: Attachment): WebSocket | undefined {
    if (att.role === "guest") return this.ctx.getWebSockets(`work:${att.nonce}`)[0];
    if (att.role === "work") return this.ctx.getWebSockets(`guest:${att.nonce}`)[0];
    return undefined;
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const att = ws.deserializeAttachment() as Attachment;
    if (att.role === "host" || typeof message === "string") return; // control link is server→host only
    if (att.role === "guest" && !att.validated) {
      // Only jukz channels may cross the relay (it must never become an open TCP proxy).
      const first = new Uint8Array(message)[0];
      if (first === undefined || !VALID_FIRST_BYTES.has(first)) {
        safeClose(ws, 1008, "not a jukz channel");
        const peer = this.peerOf(att);
        if (peer) safeClose(peer, 1008, "not a jukz channel");
        return;
      }
      ws.serializeAttachment({ ...att, validated: true });
    }
    const peer = this.peerOf(att);
    if (peer) {
      peer.send(message);
      return;
    }
    if (att.role === "guest") {
      const buf = this.pending.get(att.nonce) ?? { frames: [], bytes: 0 };
      buf.frames.push(message);
      buf.bytes += message.byteLength;
      if (buf.bytes > MAX_PENDING_BYTES) {
        this.pending.delete(att.nonce);
        safeClose(ws, 1009, "too much data before the host connected");
        return;
      }
      this.pending.set(att.nonce, buf);
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    this.dropSocket(ws, code, reason);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.dropSocket(ws, 1011, "error");
  }

  private dropSocket(ws: WebSocket, code: number, reason: string) {
    const att = ws.deserializeAttachment() as Attachment | null;
    safeClose(ws, code, reason);
    if (!att) return;
    if (att.role === "host") {
      console.log(`relay host link closed session=${att.session}`);
      return; // live splices keep flowing; new guests just can't reach this session any more
    }
    this.pending.delete(att.nonce);
    const peer = this.peerOf(att);
    if (peer) safeClose(peer, 1000, "peer closed");
  }

  // ---- housekeeping -----------------------------------------------------------------------------

  private async ensureAlarm() {
    if ((await this.ctx.storage.getAlarm()) == null) await this.ctx.storage.setAlarm(Date.now() + KEEPALIVE_MS);
  }

  /** Keep idle host links warm and sweep expired world records; re-arms only while there is work. */
  async alarm(): Promise<void> {
    const hosts = this.sockets("host");
    for (const h of hosts) {
      try {
        h.send("ping"); // the mod parses signals as numbers and ignores anything else
      } catch {
        // a dead link is reaped by webSocketClose
      }
    }
    const now = Date.now();
    const worlds = await this.ctx.storage.list<Entry>({ prefix: "w:" });
    const expired = [...worlds].filter(([, e]) => e.expiresAt <= now).map(([k]) => k);
    if (expired.length) await this.ctx.storage.delete(expired);
    if (hosts.length > 0 || worlds.size > expired.length) await this.ctx.storage.setAlarm(now + KEEPALIVE_MS);
  }
}

function safeClose(ws: WebSocket, code: number, reason: string) {
  // 1005/1006 are reserved and may not be sent; map them to a plain close.
  const sendable = code === 1005 || code === 1006 || code < 1000 || code > 4999 ? 1000 : code;
  try {
    ws.close(sendable, reason.slice(0, 120));
  } catch {
    // already closed
  }
}
