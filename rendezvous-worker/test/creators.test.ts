import assert from "node:assert/strict";
import { test } from "node:test";
import rawCatalog from "../../cosmetics/catalog.json" with { type: "json" };
import type { Catalog } from "../src/cosmetics-logic.ts";
import {
  CREATOR_SESSION_MS, LINK_TTL_MS, LOCK_AFTER_FAILURES, MAX_MODEL_PIXELS,
  accountKey, checkBbmodel, checkPassword, checkReview, checkRewardPick, cleanText, decodePreview, hashPassword,
  issueCreatorToken, issueLinkToken, lockedUntil, newSubmissionId, parseSubmissionId, parseSubmissionSlot,
  passwordMatches, rewardChoices, verifyCreatorToken, verifyLinkToken,
} from "../src/creators-logic.ts";
import { BadRequest } from "../src/logic.ts";

const KEY = "test-key";
const NOW = 1_800_000_000_000;
const P = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
const catalog = rawCatalog as unknown as Catalog;

test("accounts are the Minecraft name, case-insensitive; passwords need 8+ characters", () => {
  assert.equal(accountKey("Nuulm"), "nuulm");
  assert.throws(() => accountKey("no spaces"), BadRequest);
  assert.throws(() => accountKey("a".repeat(17)), BadRequest);
  assert.throws(() => checkPassword("short"), BadRequest);
  assert.equal(checkPassword("long enough"), "long enough");
});

test("passwords are salted PBKDF2 and only the right one matches", async () => {
  const a = await hashPassword("correct horse", 1000);
  const b = await hashPassword("correct horse", 1000);
  assert.notEqual(a.hash, b.hash, "a fresh salt each time");
  assert.ok(await passwordMatches("correct horse", a));
  assert.ok(!(await passwordMatches("correct horsf", a)));
});

test("too many wrong passwords lock the account for a while", () => {
  assert.equal(lockedUntil(LOCK_AFTER_FAILURES - 1, NOW), 0);
  assert.ok(lockedUntil(LOCK_AFTER_FAILURES, NOW) > NOW);
});

test("page sessions name their account and expire", async () => {
  const { token } = await issueCreatorToken(KEY, "nuulm", NOW);
  assert.equal(await verifyCreatorToken(KEY, token, NOW + 1), "nuulm");
  assert.equal(await verifyCreatorToken(KEY, token, NOW + CREATOR_SESSION_MS + 1), null);
  assert.equal(await verifyCreatorToken("other", token, NOW), null);
  assert.equal(await verifyCreatorToken(KEY, token.replace("nuulm", "notch"), NOW), null);
});

test("game links carry the player's id and name for 15 minutes, and can't be edited", async () => {
  const token = await issueLinkToken(KEY, { id: P, name: "Nuulm" }, NOW);
  assert.deepEqual(await verifyLinkToken(KEY, token, NOW + 1000), { id: P, name: "Nuulm" });
  assert.equal(await verifyLinkToken(KEY, token, NOW + LINK_TTL_MS + 1), null);
  const [body, sig] = token.split(".");
  const forged = btoa(atob(body.replace(/-/g, "+").replace(/_/g, "/")).replace("Nuulm", "Notch")).replace(/=+$/, "");
  assert.equal(await verifyLinkToken(KEY, `${forged}.${sig}`, NOW), null);
  assert.equal(await verifyLinkToken(KEY, 42, NOW), null);
});

const bb = (elements: unknown[], extra: object = {}) =>
  JSON.stringify({ meta: { format_version: "4.10", model_format: "free" }, elements, textures: [], ...extra });
const cube = (from: number[], to: number[]) => ({ from, to, faces: {} });

test("uploaded models must look like Blockbench files of a wearable size", () => {
  assert.deepEqual(checkBbmodel(bb([cube([0, 0, 0], [8, 4, 8]), cube([2, 4, 2], [6, 10, 6])])), { elements: 2, textures: 0, size: [8, 10, 8] });
  assert.throws(() => checkBbmodel("not json"), /JSON/);
  assert.throws(() => checkBbmodel(JSON.stringify({ elements: [cube([0, 0, 0], [1, 1, 1])] })), /format_version/);
  assert.throws(() => checkBbmodel(bb([])), /no cubes/);
  assert.throws(() => checkBbmodel(bb([cube([0, 0, 0], [MAX_MODEL_PIXELS + 1, 1, 1])])), /within/);
  assert.throws(() => checkBbmodel(bb([{ from: [0, 0], to: [1, 1, 1] }])), /coordinates/);
  assert.throws(() => checkBbmodel(bb([cube([0, 0, 0], [1, 1, 1])], { textures: Array(9).fill({}) })), /8 textures/);
});

test("the template's reference player is left out of the size, Blockbench 4 and 5 files alike", () => {
  const player = { ...cube([-4, 0, -2], [4, 32, 2]), uuid: "p" };
  const hat = { ...cube([-4, 32, -4], [4, 60, 4]), uuid: "h" };
  const anims = [{ name: "idle" }, { name: "animation.model.walk" }, { name: "other" }];
  const v4 = bb([player, hat], { outliner: [{ name: "reference", uuid: "g", children: ["p"] }, "h"], animations: anims });
  assert.deepEqual(checkBbmodel(v4), { elements: 1, textures: 0, size: [8, 28, 8], animations: 2 });
  const v5 = bb([player, hat], { groups: [{ uuid: "g", name: "Reference" }], outliner: [{ uuid: "g", children: ["p"] }, "h"] });
  assert.deepEqual(checkBbmodel(v5).size, [8, 28, 8]);
  // without the reference group the player counts, and 60 pixels is too tall
  assert.throws(() => checkBbmodel(bb([player, hat], { outliner: ["p", "h"] })), /within/);
  assert.throws(() => checkBbmodel(bb([player], { outliner: [{ name: "reference", children: ["p"] }] })), /no cubes/);
});

test("submission fields", () => {
  assert.equal(parseSubmissionSlot("hat"), "hat");
  assert.throws(() => parseSubmissionSlot("cape"), BadRequest, "capes are not allowed by Mojang's rules");
  assert.equal(cleanText("  a   b ", 10, "x"), "a b");
  assert.throws(() => cleanText("", 10, "the name", true), /required/);
  assert.throws(() => cleanText("x".repeat(11), 10, "x"), /at most/);
  const id = newSubmissionId(NOW);
  assert.equal(parseSubmissionId(id), id);
  assert.throws(() => parseSubmissionId("../etc"), BadRequest);
  assert.equal(decodePreview(undefined), null);
  assert.throws(() => decodePreview("data:image/png;base64," + btoa("GIF89a")), /PNG/);
});

test("review: approve or reject, and publish only as an existing 3D catalog item", () => {
  assert.deepEqual(checkReview(catalog, "pending", "approved", null), { status: "approved", itemId: null });
  assert.deepEqual(checkReview(catalog, "approved", "published", "top_hat"), { status: "published", itemId: "top_hat" });
  assert.throws(() => checkReview(catalog, "approved", "published", "nope"), /catalog item/);
  assert.throws(() => checkReview(catalog, "approved", "published", "jukz"), /badges/);
  assert.throws(() => checkReview(catalog, "published", "rejected", null), /already/);
  assert.throws(() => checkReview(catalog, "pending", "maybe", null), BadRequest);
});

test("the extra reward is any sellable item except your own; specials are out", () => {
  assert.ok(!rewardChoices(catalog).includes("founder"));
  assert.ok(rewardChoices(catalog).includes("crown_3d"));
  assert.equal(checkRewardPick(catalog, "crown_3d", "top_hat"), "crown_3d");
  assert.throws(() => checkRewardPick(catalog, "top_hat", "top_hat"), /another/);
  assert.throws(() => checkRewardPick(catalog, "founder", "top_hat"), /catalog/);
});
