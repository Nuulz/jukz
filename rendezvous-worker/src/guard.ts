// Anti-abuse on the Worker side (rules in guard-logic.ts). One Durable Object per subject — a device
// (`dev:<key>`), an address (`ip:<ip>`), an older jukz without a device key (`legacy:<ip>`) or a premium
// player registering devices (`player:<id>`) — so the limits hold across every Worker isolate.
//  - POST /v1/device/challenge   → {challenge, bits}            a proof-of-work puzzle for a new device
//  - POST /v1/device/register    {challenge, nonce}, signed      the device key counts from now on
// World calls go through [guardWorldCall] before they reach their shard.

import { DurableObject } from "cloudflare:workers";
import { sessionPlayer } from "./cosmetics.ts";
import {
  type GuardLimits,
  type SubjectState,
  type Verdict,
  challengeValid,
  checkDevice,
  emptyState,
  gate,
  guardLimits,
  issueChallenge,
  peek,
  register,
  workValid,
} from "./guard-logic.ts";
import type { Env } from "./hub.ts";

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });

type GateVerdict = Verdict | { ok: false; register: true };

export class Guard extends DurableObject<Env> {
  private state?: SubjectState;
  /** Peeks stay in memory: they are frequent, cheap to lose, and never block. */
  private readonly peeks = new Map<string, number>();

  private async load(): Promise<SubjectState> {
    this.state ??= (await this.ctx.storage.get<SubjectState>("s")) ?? emptyState();
    return this.state;
  }

  private async save(): Promise<void> {
    if (this.state) await this.ctx.storage.put("s", this.state);
  }

  async gate(action: string, limit: number, needsRegistration: boolean): Promise<GateVerdict> {
    const state = await this.load();
    if (needsRegistration && !state.registered) return { ok: false, register: true };
    const before = JSON.stringify(state);
    const verdict = gate(state, action, limit, Date.now());
    if (JSON.stringify(state) !== before) await this.save();
    return verdict;
  }

  async peek(world: string, limit: number, needsRegistration: boolean): Promise<GateVerdict> {
    if (needsRegistration && !(await this.load()).registered) return { ok: false, register: true };
    return peek(this.peeks, world, limit, Date.now());
  }

  async register(limit: number): Promise<Verdict> {
    const verdict = register(await this.load(), limit, Date.now());
    if (verdict.ok) await this.save();
    return verdict;
  }

  async isRegistered(): Promise<boolean> {
    return (await this.load()).registered === true;
  }

  async markRegistered(): Promise<void> {
    (await this.load()).registered = true;
    await this.save();
  }
}

function subject(env: Env, name: string) {
  return env.GUARD.get(env.GUARD.idFromName(name));
}

const clientIp = (request: Request) => request.headers.get("cf-connecting-ip") ?? "unknown";

function limited(verdict: { retryAfterMs: number; blocked: boolean }, what: string): Response {
  const secs = Math.max(1, Math.ceil(verdict.retryAfterMs / 1000));
  return json(
    429,
    { status: "limited", retryAfterSecs: secs, blocked: verdict.blocked, message: `too many ${what} in a short time; try again in ${Math.ceil(secs / 60)} min` },
    { "retry-after": String(secs) },
  );
}

const mustRegister = () => json(401, { status: "register", message: "this device key is not registered yet" });

/**
 * Let a world call through, or answer it. [kind]: `open` (announce), `join` (a lookup made to join) or
 * `peek` (any other lookup). [body] is the exact request body text (empty for a GET). Null = allowed.
 */
export async function guardWorldCall(
  request: Request,
  env: Env,
  kind: "open" | "join" | "peek",
  worldId: string,
  body: string,
): Promise<Response | null> {
  const url = new URL(request.url);
  const limits = guardLimits(env as unknown as Record<string, unknown>);
  const device = await checkDevice(request.method, url.pathname, body, (n) => request.headers.get(n), Date.now());
  if (!device.ok) return json(device.status, { status: "error", message: device.message });
  const ip = clientIp(request);
  const premium = device.device !== null && (await sessionPlayer(request, env)) !== null;

  if (device.device === null && limits.requireDevice) {
    return json(426, { status: "update", message: "update jukz to keep playing online" });
  }
  const own = device.device === null ? subject(env, `legacy:${ip}`) : subject(env, `dev:${device.device}`);
  const registered = device.device !== null;

  if (kind === "peek") {
    const limit = device.device === null ? limits.legacyPeeksPer10Min : limits.peeksPer10Min * (premium ? 2 : 1);
    const verdict = await own.peek(worldId, limit, registered);
    if (!verdict.ok) return "register" in verdict ? mustRegister() : limited(verdict, "worlds looked up");
    return null;
  }

  const limit = device.device === null ? limits.legacyPerMin : premium ? limits.premiumPerMin : limits.devicePerMin;
  const action = `${kind}:${worldId}`;
  const verdict = await own.gate(action, limit, registered);
  if (!verdict.ok) {
    if ("register" in verdict) return mustRegister();
    console.log(`guard: ${device.device === null ? "legacy" : "device"} limited ip=${ip} ${action} blocked=${verdict.blocked}`);
    return limited(verdict, "worlds opened or joined");
  }
  // The address backstop: generous (a house, or a whole CGNAT, shares it), it only stops floods.
  if (device.device !== null) {
    const backstop = await subject(env, `ip:${ip}`).gate(action, limits.ipPerMin, false);
    if (!backstop.ok && !("register" in backstop)) {
      console.log(`guard: address limited ip=${ip} ${action}`);
      return limited(backstop, "worlds opened or joined from this network");
    }
  }
  return null;
}

/** /v1/device/*: hand out a puzzle, or register a device key. */
export async function handleDevice(request: Request, env: Env, url: URL): Promise<Response> {
  const secret = env.SNAPSHOT_SIGNING_KEY;
  if (!secret) return json(503, { status: "error", message: "device registration disabled" });
  const limits = guardLimits(env as unknown as Record<string, unknown>);
  const now = Date.now();
  if (request.method !== "POST") return json(404, { status: "error", message: "not found" });

  if (url.pathname === "/v1/device/challenge") {
    return json(200, { challenge: await issueChallenge(secret, now), bits: limits.powBits });
  }
  if (url.pathname !== "/v1/device/register") return json(404, { status: "error", message: "not found" });

  const text = await request.text();
  const device = await checkDevice(request.method, url.pathname, text, (n) => request.headers.get(n), now);
  if (!device.ok) return json(device.status, { status: "error", message: device.message });
  if (device.device === null) return json(401, { status: "error", message: "registration must be signed by the device key" });
  const stub = subject(env, `dev:${device.device}`);
  if (await stub.isRegistered()) return json(200, { status: "ok" });

  // A premium account proves itself with Mojang (the cosmetics session): no puzzle, counted per player.
  const player = await sessionPlayer(request, env);
  let counter: string;
  if (player) {
    counter = `player:${player}`;
  } else {
    const body = JSON.parse(text) as Record<string, unknown>;
    if (!(await challengeValid(secret, body.challenge, now))) return json(400, { status: "retry", message: "challenge expired, ask for a new one" });
    if (!(await workValid(body.challenge as string, device.device, body.nonce, limits.powBits))) {
      return json(400, { status: "error", message: "proof of work does not check out" });
    }
    counter = `ip:${clientIp(request)}`;
  }
  const verdict = await subject(env, counter).register(limits.registrationsPerHour);
  if (!verdict.ok) {
    console.log(`guard: registration limited ${counter.startsWith("ip:") ? "address" : "player"}`);
    return limited(verdict, "new devices");
  }
  await stub.markRegistered();
  console.log(`guard: device registered premium=${player !== null}`);
  return json(200, { status: "ok", premium: player !== null });
}
