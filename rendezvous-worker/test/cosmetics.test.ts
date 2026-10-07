import assert from "node:assert/strict";
import { test } from "node:test";
import rawCatalog from "../../cosmetics/catalog.json" with { type: "json" };
import {
  type Catalog, CHALLENGE_TTL_MS, NO_BADGE, SESSION_TTL_MS,
  adminTokenMatches, checkEquip, issueChallenge, issueSession, ownedItems, parseLookup, parsePlayerId, parseSlot,
  readLoadout, serverIdFor, validateCatalog, verifyChallenge, verifySession, visibleBadge, visibleLoadout,
} from "../src/cosmetics-logic.ts";
import { BadRequest } from "../src/logic.ts";

const KEY = "test-signing-key";
const NOW = 1_800_000_000_000;
const P = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
const shipped = rawCatalog as unknown as Catalog;

const item = (id: string, availability: "free" | "paid" | "grant", extra: object = {}) => ({
  id, kind: "badge" as const, name: id, description: "", availability,
  palette: { x: "FFFFFFFF" }, art: Array(8).fill("x.x.x.x."), ...extra,
});
const hat = (id: string, availability: "free" | "paid" | "grant" = "free") => ({
  id, kind: "hat" as const, name: id, description: "", availability, palette: { x: "FFFFFFFF" },
  model: { voxel: 1, origin: [-1, -8, -1] as [number, number, number], layers: [["xx", "xx"], ["x.", ".x"]] },
});
const cat = (): Catalog => ({
  version: 1, defaultBadge: "a",
  items: [item("a", "free"), item("b", "free"), item("vip", "paid", { price: { amount: 199, currency: "USD" } }), item("dev", "grant"),
    hat("tophat"), hat("goldhat", "grant")],
});

test("the shipped catalog is valid", () => {
  validateCatalog(structuredClone(shipped));
  assert.ok(shipped.items.length > 0);
});

test("rigged models: parents come first, frames and states are checked", () => {
  const rigged = () => {
    const c = cat();
    const m = c.items.find((i) => i.id === "tophat")!.model!;
    const slices = m.layers;
    m.rig = { parts: [
      { name: "base", pivot: [0, 0, 0], layers: slices, motion: { speed: 0.1, amp: [0, 0, 10], run: { amp: [0, 0, 30] } } },
      { name: "tip", parent: "base", pivot: [0, -1, 0], when: "still", frames: [{ ticks: 20, layers: slices }, { ticks: 4, layers: slices }] },
    ] };
    return c;
  };
  validateCatalog(rigged());
  const orphan = rigged();
  orphan.items.find((i) => i.id === "tophat")!.model!.rig!.parts.reverse();
  assert.throws(() => validateCatalog(orphan), /parent/);
  const state = rigged();
  (state.items.find((i) => i.id === "tophat")!.model!.rig!.parts[1] as { when: string }).when = "flying";
  assert.throws(() => validateCatalog(state), /when/);
  const ticks = rigged();
  ticks.items.find((i) => i.id === "tophat")!.model!.rig!.parts[1].frames![0].ticks = 0;
  assert.throws(() => validateCatalog(ticks), /ticks/);
  const order = rigged();
  (order.items.find((i) => i.id === "tophat")!.model!.rig!.parts[0] as { order: string }).order = "yxz";
  assert.throws(() => validateCatalog(order), /order/);
});

test("catalog validation catches broken art and pricing", () => {
  const ragged = cat();
  ragged.items[0].art![3] = "x.x";
  assert.throws(() => validateCatalog(ragged), /square/);
  const stray = cat();
  stray.items[1].art![0] = "x.x.x.q.";
  assert.throws(() => validateCatalog(stray), /'q'/);
  const unpriced = cat();
  delete unpriced.items[2].price;
  assert.throws(() => validateCatalog(unpriced), /price/);
  const paidDefault = cat();
  paidDefault.defaultBadge = "vip";
  assert.throws(() => validateCatalog(paidDefault), /defaultBadge/);
});

test("free items are owned by everyone; paid and granted items only with an entitlement", () => {
  assert.deepEqual(ownedItems(cat(), []), ["a", "b", "tophat"]);
  assert.deepEqual(ownedItems(cat(), ["vip", "dev", "ghost"]), ["a", "b", "vip", "dev", "tophat"]);
});

test("equip accepts owned items and 'none', refuses the rest", () => {
  assert.equal(checkEquip(cat(), "b", []), "b");
  assert.equal(checkEquip(cat(), null, []), NO_BADGE);
  assert.equal(checkEquip(cat(), "vip", ["vip"]), "vip");
  assert.throws(() => checkEquip(cat(), "vip", []), BadRequest);
  assert.throws(() => checkEquip(cat(), "nope", []), BadRequest);
});

test("visible badge falls back to the default when the pick is gone, and hides on 'none'", () => {
  assert.equal(visibleBadge(cat(), null, []), "a");
  assert.equal(visibleBadge(cat(), "b", []), "b");
  assert.equal(visibleBadge(cat(), "vip", []), "a"); // revoked
  assert.equal(visibleBadge(cat(), "vip", ["vip"]), "vip");
  assert.equal(visibleBadge(cat(), NO_BADGE, []), null);
});

test("challenges are signed and short-lived", async () => {
  const c = await issueChallenge(KEY, NOW, "abcd");
  assert.ok(await verifyChallenge(KEY, c, NOW + 1000));
  assert.ok(!(await verifyChallenge(KEY, c, NOW + CHALLENGE_TTL_MS + 1)));
  assert.ok(!(await verifyChallenge("other-key", c, NOW)));
  assert.ok(!(await verifyChallenge(KEY, c.replace("abcd", "abce"), NOW)));
  assert.ok(!(await verifyChallenge(KEY, 42, NOW)));
});

test("the server id is a 40-char sha1 hex, stable per challenge", async () => {
  const id = await serverIdFor("x");
  assert.match(id, /^[0-9a-f]{40}$/);
  assert.equal(id, await serverIdFor("x"));
  assert.notEqual(id, await serverIdFor("y"));
});

test("session tokens name their player and expire", async () => {
  const { token, expiresAt } = await issueSession(KEY, P, NOW);
  assert.equal(expiresAt, NOW + SESSION_TTL_MS);
  assert.equal(await verifySession(KEY, token, NOW + 1), P);
  assert.equal(await verifySession(KEY, token, NOW + SESSION_TTL_MS + 1), null);
  assert.equal(await verifySession("other-key", token, NOW), null);
  const forged = token.replace(P, "11111111-2222-3333-4444-555555555555");
  assert.equal(await verifySession(KEY, forged, NOW), null);
  assert.equal(await verifySession(KEY, null, NOW), null);
});

test("player ids accept Mojang's dashless form", () => {
  assert.equal(parsePlayerId("069A79F444E94726A5BEFCA90E38AAF5"), P);
  assert.throws(() => parsePlayerId("nope"), BadRequest);
  assert.deepEqual(parseLookup(`${P},${P.replace(/-/g, "")}`), [P]);
  assert.throws(() => parseLookup(""), BadRequest);
  assert.throws(() => parseLookup(Array(101).fill(P).join(",")), BadRequest);
});

test("admin token must be configured and match", () => {
  assert.ok(adminTokenMatches("s3cret", "s3cret"));
  assert.ok(!adminTokenMatches("s3cret", "s3cre7"));
  assert.ok(!adminTokenMatches(undefined, ""));
  assert.ok(!adminTokenMatches("s3cret", null));
});

test("3D models must have even slices and a known animation", () => {
  const ok = cat();
  validateCatalog(ok);
  const ragged = cat();
  ragged.items[4].model!.layers[1] = ["x."];
  assert.throws(() => validateCatalog(ragged), /deep/);
  const wide = cat();
  wide.items[4].model!.layers[0][1] = "xxx";
  assert.throws(() => validateCatalog(wide), /wide/);
  const noModel = cat();
  delete noModel.items[4].model;
  assert.throws(() => validateCatalog(noModel), /model/);
  const dance = cat();
  (dance.items[4].model as { animation: string }).animation = "dance";
  assert.throws(() => validateCatalog(dance), /animation/);
  const hatAsDefault = cat();
  hatAsDefault.defaultBadge = "tophat";
  assert.throws(() => validateCatalog(hatAsDefault), /defaultBadge/);
});

test("items only go in their own slot", () => {
  assert.equal(checkEquip(cat(), "tophat", [], "hat"), "tophat");
  assert.throws(() => checkEquip(cat(), "tophat", [], "badge"), /slot/);
  assert.throws(() => checkEquip(cat(), "a", [], "hat"), /slot/);
  assert.throws(() => checkEquip(cat(), "goldhat", [], "hat"), /own/);
  assert.equal(checkEquip(cat(), "goldhat", ["goldhat"], "hat"), "goldhat");
  assert.equal(parseSlot(undefined), "badge");
  assert.throws(() => parseSlot("feet"), BadRequest);
});

test("the visible loadout keeps owned picks, defaults the badge, and drops the rest", () => {
  assert.deepEqual(visibleLoadout(cat(), {}, []), { badge: "a" });
  assert.deepEqual(visibleLoadout(cat(), { badge: "b", hat: "tophat" }, []), { badge: "b", hat: "tophat" });
  assert.deepEqual(visibleLoadout(cat(), { badge: NO_BADGE, hat: "goldhat" }, []), {}); // hidden badge, revoked hat
  assert.deepEqual(visibleLoadout(cat(), { hat: "a" }, []), { badge: "a" }); // a badge in the hat slot is ignored
});

test("an item that requires others only shows while they're worn", () => {
  const visible = (l: object) => visibleLoadout(shipped, l, []);
  assert.equal(visible({ hat: "halo", back: "wings", emote: "emote_coin" }).emote, "emote_coin");
  assert.equal(visible({ hat: "halo", emote: "emote_coin" }).emote, undefined);
  assert.equal(visible({ hat: "crown_3d", back: "wings", emote: "emote_coin" }).emote, undefined);
});

test("stored loadouts read the JSON column, or the original badge column", () => {
  assert.deepEqual(readLoadout('{"badge":"b","hat":"tophat","feet":"x"}', "a"), { badge: "b", hat: "tophat" });
  assert.deepEqual(readLoadout(null, "a"), { badge: "a" });
  assert.deepEqual(readLoadout("not json", null), {});
});

// ---- player certificates (the production sign-in) ----------------------------------------------
import { certificatePayload, verifyChallengeSignature, verifyPlayerCertificate } from "../src/cosmetics-logic.ts";

const toB64 = (b: ArrayBuffer | Uint8Array) => btoa(String.fromCharCode(...new Uint8Array(b as ArrayBuffer)));
const rsa = (hash: "SHA-1" | "SHA-256") => crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash }, true, ["sign", "verify"],
) as Promise<CryptoKeyPair>;

async function certificateFixture(expiresAt = NOW + 60_000, forId = P) {
  const mojang = await rsa("SHA-1");
  const player = await rsa("SHA-256");
  const playerDer = new Uint8Array(await crypto.subtle.exportKey("spki", player.publicKey) as ArrayBuffer);
  const keySignature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", mojang.privateKey, certificatePayload(forId, expiresAt, playerDer));
  return {
    mojangKey: toB64(await crypto.subtle.exportKey("spki", mojang.publicKey) as ArrayBuffer),
    player,
    cert: { publicKey: toB64(playerDer), expiresAt, keySignature: toB64(keySignature) },
  };
}

test("the certificate payload is uuid ‖ expiry ‖ key, big-endian", () => {
  const out = certificatePayload("00112233-4455-6677-8899-aabbccddeeff", 0x0102030405, new Uint8Array([9, 9]));
  assert.deepEqual([...out.slice(0, 16)], [0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff]);
  assert.deepEqual([...out.slice(16, 24)], [0, 0, 0, 0x01, 0x02, 0x03, 0x04, 0x05]);
  assert.deepEqual([...out.slice(24)], [9, 9]);
});

test("a Mojang-signed certificate plus a signed challenge proves the account", async () => {
  const { mojangKey, player, cert } = await certificateFixture();
  const key = await verifyPlayerCertificate(P, cert, ["bm90IGEga2V5", mojangKey], NOW);
  assert.ok(key, "a malformed Mojang key is skipped, the real one vouches");
  const challenge = "1.abc.def";
  const signature = toB64(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", player.privateKey, new TextEncoder().encode(challenge)));
  assert.ok(await verifyChallengeSignature(key!, challenge, signature));
  assert.ok(!(await verifyChallengeSignature(key!, "1.abc.xyz", signature)), "signature is for another challenge");
  assert.ok(!(await verifyChallengeSignature(key!, challenge, 42)));
});

test("certificates are refused for another player, after expiry, or without Mojang's signature", async () => {
  const { mojangKey, cert } = await certificateFixture();
  assert.equal(await verifyPlayerCertificate("11111111-2222-3333-4444-555555555555", cert, [mojangKey], NOW), null);
  assert.equal(await verifyPlayerCertificate(P, cert, [mojangKey], cert.expiresAt + 1), null);
  const stranger = await certificateFixture();
  assert.equal(await verifyPlayerCertificate(P, cert, [stranger.mojangKey], NOW), null);
  assert.equal(await verifyPlayerCertificate(P, { ...cert, keySignature: "%%%" }, [mojangKey], NOW), null);
  assert.equal(await verifyPlayerCertificate(P, null, [mojangKey], NOW), null);
});
