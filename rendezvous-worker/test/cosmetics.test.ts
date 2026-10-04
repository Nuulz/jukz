import assert from "node:assert/strict";
import { test } from "node:test";
import rawCatalog from "../../cosmetics/catalog.json" with { type: "json" };
import {
  type Catalog, CHALLENGE_TTL_MS, NO_BADGE, SESSION_TTL_MS,
  adminTokenMatches, checkEquip, issueChallenge, issueSession, ownedItems, parseLookup, parsePlayerId,
  serverIdFor, validateCatalog, verifyChallenge, verifySession, visibleBadge,
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
const cat = (): Catalog => ({
  version: 1, defaultBadge: "a",
  items: [item("a", "free"), item("b", "free"), item("vip", "paid", { price: { amount: 199, currency: "USD" } }), item("dev", "grant")],
});

test("the shipped catalog is valid", () => {
  validateCatalog(structuredClone(shipped));
  assert.ok(shipped.items.length > 0);
});

test("catalog validation catches broken art and pricing", () => {
  const ragged = cat();
  ragged.items[0].art[3] = "x.x";
  assert.throws(() => validateCatalog(ragged), /square/);
  const stray = cat();
  stray.items[1].art[0] = "x.x.x.q.";
  assert.throws(() => validateCatalog(stray), /'q'/);
  const unpriced = cat();
  delete unpriced.items[2].price;
  assert.throws(() => validateCatalog(unpriced), /price/);
  const paidDefault = cat();
  paidDefault.defaultBadge = "vip";
  assert.throws(() => validateCatalog(paidDefault), /defaultBadge/);
});

test("free items are owned by everyone; paid and granted items only with an entitlement", () => {
  assert.deepEqual(ownedItems(cat(), []), ["a", "b"]);
  assert.deepEqual(ownedItems(cat(), ["vip", "dev", "ghost"]), ["a", "b", "vip", "dev"]);
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
