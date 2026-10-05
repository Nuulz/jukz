// Ports of the Rust unit tests (rendezvous/src/{store,main}.rs) for the pure rules.
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  BadRequest, type Entry, parseGame, parseGameHeader, RateLimiter, type Token, type WorldRecord,
  announce, cmpToken, fenceAllows, heartbeat, liveRecord, mergeObservedEndpoint, parseToken, shouldWithdraw, MAX_ENDPOINTS,
} from "../src/logic.ts";

const TTL = 90_000;
const W = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
const tok = (generation: number, claimEpochMillis: number, nodeId: string): Token => ({ generation, claimEpochMillis, nodeId });
const rec = (t: Token): WorldRecord => ({ worldId: W, token: t, endpoints: [{ host: "192.168.1.7", port: 51820 }], heartbeatSeq: 0, playerCount: 0 });
const pub = (e: Entry | undefined, r: WorldRecord, now: number) => {
  const o = announce(e, r, now, TTL);
  assert.equal(o.kind, "published");
  return (o as { entry: Entry }).entry;
};

test("token order is generation, then millis, then node id", () => {
  const base = tok(1, 100, "aa");
  assert.equal(cmpToken(tok(2, 0, "00"), base), 1);
  assert.equal(cmpToken(tok(1, 101, "00"), base), 1);
  assert.equal(cmpToken(tok(1, 100, "ab"), base), 1);
  assert.equal(cmpToken(tok(1, 100, "aa"), base), 0);
  assert.equal(cmpToken(tok(0, 999, "ff"), base), -1);
});

test("announce publishes when empty and rejects lower or equal tokens", () => {
  const e = pub(undefined, rec(tok(5, 100, "aa")), 0);
  assert.deepEqual(announce(e, rec(tok(5, 100, "aa")), 0, TTL), { kind: "rejected", current: e.record });
  assert.deepEqual(announce(e, rec(tok(4, 999, "ff")), 0, TTL), { kind: "rejected", current: e.record });
  assert.deepEqual(liveRecord(e, 0), e.record);
});

test("a strictly higher token takes over", () => {
  const e = pub(undefined, rec(tok(5, 100, "aa")), 0);
  const usurper = pub(e, rec(tok(6, 50, "00")), 0);
  assert.equal(usurper.record.token.generation, 6);
});

test("a record expires after the TTL and the slot reopens", () => {
  const e = pub(undefined, rec(tok(5, 100, "aa")), 0);
  assert.equal(liveRecord(e, TTL), undefined);
  pub(e, rec(tok(1, 0, "00")), TTL);
});

test("heartbeat with the same token refreshes the lease and advances seq", () => {
  const t = tok(5, 100, "aa");
  const e = pub(undefined, rec(t), 0);
  const o = heartbeat(e, t, 7, 2, TTL - 1, TTL);
  assert.equal(o.kind, "refreshed");
  const refreshed = (o as { entry: Entry }).entry;
  assert.equal(liveRecord(refreshed, TTL + 1)?.heartbeatSeq, 7);
  assert.equal(refreshed.record.playerCount, 2);
});

test("heartbeat reports superseded or unknown", () => {
  const mine = tok(5, 100, "aa");
  const e = pub(pub(undefined, rec(mine), 0), rec(tok(6, 0, "00")), 0);
  assert.equal(heartbeat(e, mine, 1, 0, 0, TTL).kind, "superseded");
  assert.equal(heartbeat(undefined, mine, 1, 0, 0, TTL).kind, "unknown");
  assert.equal(heartbeat(e, tok(6, 0, "00"), 1, 0, TTL, TTL).kind, "unknown");
});

test("withdraw removes only with the matching token (or once expired)", () => {
  const mine = tok(5, 100, "aa");
  const e = pub(undefined, rec(mine), 0);
  assert.equal(shouldWithdraw(e, tok(4, 0, "00"), 0), false);
  assert.equal(shouldWithdraw(e, mine, 0), true);
  assert.equal(shouldWithdraw(e, tok(4, 0, "00"), TTL), true);
});

test("merge appends the observed ip with the announced port, skipping duplicates and respecting the cap", () => {
  const merged = mergeObservedEndpoint([{ host: "192.168.1.7", port: 51820 }], "203.0.113.9");
  assert.deepEqual(merged[1], { host: "203.0.113.9", port: 51820 });
  assert.equal(mergeObservedEndpoint([{ host: "203.0.113.9", port: 51820 }], "203.0.113.9").length, 1);
  const full = Array.from({ length: MAX_ENDPOINTS }, (_, i) => ({ host: `10.0.0.${i}`, port: 1 }));
  assert.equal(mergeObservedEndpoint(full, "203.0.113.9").length, MAX_ENDPOINTS);
});

test("token validation normalises and rejects garbage", () => {
  assert.equal(parseToken({ generation: 1, claimEpochMillis: 2, nodeId: "AB".repeat(16) }).nodeId, "ab".repeat(16));
  assert.throws(() => parseToken({ generation: 1, claimEpochMillis: 2, nodeId: "abcd" }), BadRequest);
  assert.throws(() => parseToken({ generation: -1, claimEpochMillis: 2, nodeId: "ab".repeat(16) }), BadRequest);
  assert.throws(() => parseToken({ generation: 1, claimEpochMillis: 2, nodeId: "zz".repeat(16) }), BadRequest);
});

test("snapshot fence rejects only strictly older generations", () => {
  assert.equal(fenceAllows(undefined, 0), true);
  assert.equal(fenceAllows(5, 5), true); // a host retrying its own upload
  assert.equal(fenceAllows(5, 6), true);
  assert.equal(fenceAllows(5, 4), false);
  // split-brain: same generation, different commit → the losing fork may not overwrite
  assert.equal(fenceAllows(5, 5, "aaa", "bbb"), false);
  assert.equal(fenceAllows(5, 5, "aaa", "aaa"), true); // same-commit retry
  assert.equal(fenceAllows(5, 5, "", "bbb"), true); // nothing recorded yet
  assert.equal(fenceAllows(5, 5, "aaa", ""), true); // older client: generation-only
  assert.equal(fenceAllows(5, 6, "aaa", "bbb"), true);
});

test("rate limiter allows N per minute per ip, then resets", () => {
  const rl = new RateLimiter(2);
  assert.equal(rl.allow("a", 0), true);
  assert.equal(rl.allow("a", 1), true);
  assert.equal(rl.allow("a", 2), false);
  assert.equal(rl.allow("b", 2), true);
  assert.equal(rl.allow("a", 60_000), true);
});

import { nonceFor, relayShard, shardOfNonce } from "../src/logic.ts";

test("a relay nonce carries its session's shard and stays a safe integer", () => {
  for (const session of ["ab".repeat(16), "ff".repeat(16), "0123456789abcdef0123456789abcdef"]) {
    const shard = relayShard(session);
    assert.ok(shard >= 0 && shard < 2 ** 31);
    for (const r of [0, 0.5, 0.999999999]) {
      const nonce = nonceFor(shard, r);
      assert.ok(Number.isSafeInteger(nonce) && nonce > 0);
      assert.equal(shardOfNonce(nonce), shard);
    }
  }
  assert.equal(shardOfNonce(-1), undefined);
  assert.equal(shardOfNonce(Number.NaN), undefined);
});


test("a host's Minecraft version is checked and kept", () => {
  assert.deepEqual(parseGame({ name: "1.21.11", dataVersion: 4671 }), { name: "1.21.11", dataVersion: 4671 });
  assert.equal(parseGame(undefined), undefined); // older mods send none
  assert.throws(() => parseGame({ name: "", dataVersion: 4671 }));
  assert.throws(() => parseGame({ name: "1.21.11", dataVersion: -1 }));
  assert.throws(() => parseGame({ name: "<script>", dataVersion: 1 }));
});

test("the backup upload's version header is read leniently", () => {
  assert.deepEqual(parseGameHeader("26.2;4800"), { name: "26.2", dataVersion: 4800 });
  assert.equal(parseGameHeader(null), undefined);
  assert.equal(parseGameHeader("garbage"), undefined);
  assert.equal(parseGameHeader("1.21.11;abc"), undefined);
});
