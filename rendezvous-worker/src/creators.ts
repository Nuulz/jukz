// The creators page API (nuulm.com/jukz/crear), all under /v1/creators. Pages on nuulm.com call it
// cross-origin, so every answer carries CORS headers for the allowed origins.
//  - POST /register {name, password, link?}     → {token}      (link: from the game, verifies the name)
//  - POST /login {name, password}               → {token}
//  - POST /link-login {link}                    → {token}      (from the game, no password needed)
//  - GET  /account, POST /account/password, /account/worlds/<id>/forget, /account/delete {confirm}
//                                               the account page (nuulm.com/jukz/cuenta)
//  - GET  /me                                   → profile, submissions, reward choices
//  - POST /verify {link}                        tie the account to the game account in the link
//  - POST /submissions (multipart: title, slot, notes, model=.bbmodel, preview=data:image/png)
//  - GET  /submissions/<id>/model | /preview    the owner's own files (or an admin's)
//  - POST /submissions/<id>/reward {item}       pick the extra item, once published
//  - GET  /admin/submissions?status=…, POST /admin/submissions/<id> {status, note, itemId},
//    POST /admin/submissions/<id>/reward {item}  review (x-jukz-admin)
// Page sessions: x-jukz-creator-token. State: the CosmeticsStore Durable Object; files: R2 CREATIONS.

import { adminTokenMatches } from "./cosmetics-logic.ts";
import { cosmeticsStore } from "./cosmetics.ts";
import {
  MAX_MODEL_BYTES,
  checkBbmodel,
  cleanText,
  decodePreview,
  issueCreatorToken,
  newSubmissionId,
  parseSubmissionId,
  parseSubmissionSlot,
  verifyCreatorToken,
  verifyLinkToken,
} from "./creators-logic.ts";
import { CLIENT_IP_HEADER, type Env } from "./hub.ts";
import { LIMITS, dayKey } from "./limits.ts";
import { BadRequest, parseWorldId } from "./logic.ts";

const TOKEN_HEADER = "x-jukz-creator-token";
const ADMIN_HEADER = "x-jukz-admin";
const ALLOWED_ORIGINS = [/^https:\/\/(www\.)?nuulm\.com$/, /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/];

export function corsHeaders(request: Request): Record<string, string> {
  const origin = request.headers.get("origin") ?? "";
  if (!ALLOWED_ORIGINS.some((re) => re.test(origin))) return {};
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "GET, POST, OPTIONS",
    "access-control-allow-headers": `content-type, ${TOKEN_HEADER}, ${ADMIN_HEADER}`,
    "access-control-max-age": "86400",
    vary: "origin",
  };
}

export async function handleCreators(request: Request, env: Env, url: URL): Promise<Response> {
  const cors = corsHeaders(request);
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...cors } });
  const error = (status: number, message: string) => json(status, { status: "error", message });
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });

  const key = env.SNAPSHOT_SIGNING_KEY;
  if (!key) return error(503, "creators page disabled");
  const store = cosmeticsStore(env);
  const path = url.pathname.slice("/v1/creators".length);
  const ip = request.headers.get("cf-connecting-ip") ?? request.headers.get(CLIENT_IP_HEADER) ?? "unknown";
  const session = () => verifyCreatorToken(key, request.headers.get(TOKEN_HEADER), Date.now());
  const isAdmin = () => adminTokenMatches(env.COSMETICS_ADMIN_TOKEN, request.headers.get(ADMIN_HEADER));
  const body = async () => (await request.json()) as Record<string, unknown>;

  try {
    if (request.method === "POST" && (path === "/register" || path === "/login")) {
      if (!(await store.allowAuth(ip))) return error(429, "too many attempts; wait a minute");
      const b = await body();
      const account = path === "/register"
        ? await store.register(String(b.name ?? ""), String(b.password ?? ""), await verifyLinkToken(key, b.link, Date.now()))
        : await store.login(String(b.name ?? ""), String(b.password ?? ""));
      return json(200, await issueCreatorToken(key, account, Date.now()));
    }

    if (request.method === "GET" && path === "/limits") return json(200, LIMITS); // public: the plans table

    if (request.method === "POST" && path === "/link-login") {
      if (!(await store.allowAuth(ip))) return error(429, "too many attempts; wait a minute");
      const link = await verifyLinkToken(key, (await body()).link, Date.now());
      if (!link) return error(400, "that game link expired; open the page again from the game");
      return json(200, await issueCreatorToken(key, await store.linkLogin(link), Date.now()));
    }

    if (path.startsWith("/admin/")) {
      if (!isAdmin()) return error(404, "not found");
      if (request.method === "GET" && path === "/admin/submissions") {
        return json(200, { submissions: await store.listSubmissions(url.searchParams.get("status")) });
      }
      const m = path.match(/^\/admin\/submissions\/([^/]+)(\/reward)?$/);
      if (request.method === "POST" && m) {
        const id = parseSubmissionId(m[1]);
        const b = await body();
        const row = m[2] ? await store.setReward(id, b.item, null) : await store.review(id, b.status, b.note, b.itemId);
        console.log(`creators review ${id} → ${row?.status} ${row?.itemId ?? ""} ${row?.rewardItem ?? ""}`);
        return json(200, row);
      }
      return error(404, "not found");
    }

    const file = path.match(/^\/submissions\/([^/]+)\/(model|preview)$/);
    if (request.method === "GET" && file) {
      const id = parseSubmissionId(file[1]);
      const account = await session();
      if (!isAdmin() && (!account || (await store.submissionOwner(id)) !== account)) return error(404, "not found");
      const object = await env.CREATIONS.get(`submissions/${id}.${file[2] === "model" ? "bbmodel" : "png"}`);
      if (!object) return error(404, "not found");
      return new Response(object.body, {
        headers: {
          "content-type": file[2] === "model" ? "application/json" : "image/png",
          "content-disposition": file[2] === "model" ? `attachment; filename="${id}.bbmodel"` : "inline",
          "cache-control": "private, max-age=60",
          ...cors,
        },
      });
    }

    const account = await session();
    if (!account) return error(401, "log in first");

    if (request.method === "GET" && path === "/me") return json(200, await store.creatorProfile(account));

    // ---- the account page (nuulm.com/jukz/cuenta) ----
    if (request.method === "GET" && path === "/account") {
      return json(200, { ...(await store.accountSummary(account, dayKey(Date.now()))), limits: LIMITS });
    }
    if (request.method === "POST" && path === "/account/password") {
      await store.setPassword(account, String((await body()).password ?? ""));
      return json(200, { status: "ok" });
    }
    const forget = path.match(/^\/account\/worlds\/([^/]+)\/forget$/);
    if (request.method === "POST" && forget) {
      const playerId = await store.creatorPlayer(account);
      if (!playerId) return error(400, "only Microsoft accounts have cloud worlds");
      await store.forgetWorld(playerId, parseWorldId(forget[1]));
      return json(200, { status: "ok" });
    }
    if (request.method === "POST" && path === "/account/delete") {
      const b = await body();
      if (String(b.confirm ?? "").toLowerCase() !== account) throw new BadRequest("type your Minecraft name to confirm");
      const files = await store.deleteAccount(account);
      if (files.length) await env.CREATIONS.delete(files.flatMap((id) => [`submissions/${id}.bbmodel`, `submissions/${id}.png`]));
      console.log(`account deleted: ${account} (${files.length} submissions)`);
      return json(200, { status: "deleted" });
    }

    if (request.method === "POST" && path === "/verify") {
      const link = await verifyLinkToken(key, (await body()).link, Date.now());
      if (!link) return error(400, "that game link expired; open the page again from the game");
      await store.verify(account, link);
      return json(200, await store.creatorProfile(account));
    }

    if (request.method === "POST" && path === "/submissions") {
      const length = Number(request.headers.get("content-length") ?? 0);
      if (length > MAX_MODEL_BYTES + 512 * 1024) return error(413, "the upload is too big (1 MB model max)");
      const form = await request.formData();
      const model = form.get("model");
      if (!(model instanceof File)) throw new BadRequest("attach a .bbmodel file");
      if (model.size > MAX_MODEL_BYTES) throw new BadRequest("the model is over 1 MB");
      const text = await model.text();
      const summary = checkBbmodel(text);
      const preview = decodePreview(form.get("preview"));
      const id = newSubmissionId(Date.now());
      const title = cleanText(form.get("title"), 40, "the name", true);
      const slot = parseSubmissionSlot(form.get("slot"));
      const notes = cleanText(form.get("notes"), 500, "the notes");
      await store.addSubmission(account, id, title, slot, notes, model.size, summary);
      await env.CREATIONS.put(`submissions/${id}.bbmodel`, text, { httpMetadata: { contentType: "application/json" } });
      if (preview) await env.CREATIONS.put(`submissions/${id}.png`, preview, { httpMetadata: { contentType: "image/png" } });
      console.log(`creators submission ${id} by ${account}: ${title} (${slot}, ${summary.elements} cubes)`);
      return json(200, await store.creatorProfile(account));
    }

    const reward = path.match(/^\/submissions\/([^/]+)\/reward$/);
    if (request.method === "POST" && reward) {
      await store.setReward(parseSubmissionId(reward[1]), (await body()).item, account);
      return json(200, await store.creatorProfile(account));
    }

    return error(404, "not found");
  } catch (e) {
    if (e instanceof BadRequest) return error(400, e.message);
    if (e instanceof SyntaxError) return error(400, "malformed JSON body");
    // Errors thrown inside the Durable Object arrive as plain Errors carrying the message.
    if (e instanceof Error && !/internal|exception/i.test(e.message) && e.message.length < 200) return error(400, e.message);
    throw e;
  }
}
