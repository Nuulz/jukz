import assert from "node:assert/strict";
import { test } from "node:test";
import { checkOwner, OWNER_HEADERS, signedPayload } from "../src/logic.ts";

const W = "3c4f5d44-f37f-475c-8017-f1c9a8a3701f";
const NOW = 1_800_000_000_000;
const b64url = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function keypair() {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pub = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  return { pair, pubText: b64url(pub) };
}

async function headers(k: Awaited<ReturnType<typeof keypair>>, op: string, body: string, ts = NOW) {
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, k.pair.privateKey, signedPayload(op as never, W, ts, body)));
  const h: Record<string, string> = { [OWNER_HEADERS.key]: k.pubText, [OWNER_HEADERS.ts]: String(ts), [OWNER_HEADERS.sig]: b64url(sig) };
  return (name: string) => h[name] ?? null;
}
const none = () => null;

test("an unsigned change is fine only while the world has no key", async () => {
  assert.deepEqual(await checkOwner("announce", W, "{}", undefined, none, NOW), { ok: true });
  const r = await checkOwner("announce", W, "{}", "somekey", none, NOW);
  assert.equal(r.ok, false);
});

test("the first valid signature binds the key; later ones must use it", async () => {
  const owner = await keypair();
  const first = await checkOwner("announce", W, "{}", undefined, await headers(owner, "announce", "{}"), NOW);
  assert.deepEqual(first, { ok: true, bind: owner.pubText });
  assert.deepEqual(await checkOwner("heartbeat", W, "{}", owner.pubText, await headers(owner, "heartbeat", "{}"), NOW), { ok: true });

  const thief = await keypair();
  const stolen = await checkOwner("announce", W, "{}", owner.pubText, await headers(thief, "announce", "{}"), NOW);
  assert.equal(stolen.ok, false);
  assert.equal((stolen as { status: number }).status, 403);
});

test("a signature can't be reused for another operation, body or world, or replayed late", async () => {
  const owner = await keypair();
  const h = await headers(owner, "announce", '{"a":1}');
  assert.equal((await checkOwner("withdraw", W, '{"a":1}', owner.pubText, h, NOW)).ok, false);
  assert.equal((await checkOwner("announce", W, '{"a":2}', owner.pubText, h, NOW)).ok, false);
  assert.equal((await checkOwner("announce", "11111111-2222-3333-4444-555555555555", '{"a":1}', owner.pubText, h, NOW)).ok, false);
  assert.equal((await checkOwner("announce", W, '{"a":1}', owner.pubText, h, NOW + 6 * 60_000)).ok, false);
});

test("garbage headers are rejected, never treated as unsigned", async () => {
  const bad = (name: string) => (name === OWNER_HEADERS.key ? "!!" : null);
  assert.equal((await checkOwner("announce", W, "{}", undefined, bad, NOW)).ok, false);
});

test("a signature made by the mod (JDK Ed25519, WorldKey payload) verifies here", async () => {
  // Generated once with the JDK the way dev.jukz.core.model.WorldKey signs.
  const pub = "ZzSYL65wSnbGNKOAG9lW_NXOetCgglGNlS1Eixuuww8";
  const sig = "zx5TVF-JZ0hXG-UOy6XVGGwMKImubF0u2zNoom5ejznS6KOd9ZMUkpS_P8vSN9Npy6fsSiAfPMRbmxT60iRAAw";
  const body = '{"worldId":"3c4f5d44-f37f-475c-8017-f1c9a8a3701f","generation":7}';
  const h: Record<string, string> = { [OWNER_HEADERS.key]: pub, [OWNER_HEADERS.ts]: "1800000000000", [OWNER_HEADERS.sig]: sig };
  const r = await checkOwner("snapshot-upload", W, body, undefined, (n) => h[n] ?? null, NOW);
  assert.deepEqual(r, { ok: true, bind: pub });
});
