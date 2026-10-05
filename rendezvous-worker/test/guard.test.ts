import assert from "node:assert/strict";
import { test } from "node:test";
import {
  BLOCK_BASE_MS, BLOCK_MAX_MS, MINUTE_MS, PEEK_KEEP_MS, PEEK_WINDOW_MS, REPEAT_FREE_MS,
  blockDuration, challengeValid, checkDevice, devicePayload, emptyState, gate, issueChallenge, leadingZeroBits, peek, register, workValid,
} from "../src/guard-logic.ts";

const T0 = 1_800_000_000_000;

test("gated: a 5th new world inside a minute blocks for 5 min, retries of the same world are free", () => {
  const s = emptyState();
  for (let i = 0; i < 4; i++) assert.equal(gate(s, `open:w${i}`, 4, T0 + i * 1000).ok, true);
  assert.equal(gate(s, "open:w0", 4, T0 + 5000).ok, true); // same world again: free
  const v = gate(s, "join:w9", 4, T0 + 6000);
  assert.deepEqual(v, { ok: false, retryAfterMs: BLOCK_BASE_MS, blocked: true });
  // while blocked even a known world is refused, without another strike
  assert.equal(gate(s, "open:w0", 4, T0 + 7000).ok, false);
  assert.equal(s.strikes, 1);
  // after the block, a fresh minute is fine again
  assert.equal(gate(s, "open:w10", 4, T0 + 6000 + BLOCK_BASE_MS).ok, true);
});

test("gated: spread out over minutes never blocks", () => {
  const s = emptyState();
  for (let i = 0; i < 40; i++) assert.equal(gate(s, `join:w${i}`, 4, T0 + i * 16_000).ok, true);
});

test("gated: a counted world is free for 10 minutes, then counts again", () => {
  const s = emptyState();
  gate(s, "open:a", 1, T0);
  assert.equal(gate(s, "open:a", 1, T0 + REPEAT_FREE_MS - 1).ok, true);
  assert.equal(gate(s, "open:b", 1, T0 + 30_000).ok, false); // limit 1: a second world in the minute
});

test("blocks escalate and reset after a day", () => {
  assert.equal(blockDuration(1), BLOCK_BASE_MS);
  assert.equal(blockDuration(2), 2 * BLOCK_BASE_MS);
  assert.equal(blockDuration(99), BLOCK_MAX_MS);
  const s = emptyState();
  let now = T0;
  const offend = () => {
    for (let i = 0; i < 5; i++) gate(s, `open:${now}-${i}`, 4, now);
  };
  offend();
  assert.equal(s.blockedUntil - now, BLOCK_BASE_MS);
  now = s.blockedUntil + 1;
  offend();
  assert.equal(s.blockedUntil - now, 2 * BLOCK_BASE_MS);
  now = s.blockedUntil + 25 * 60 * MINUTE_MS;
  offend();
  assert.equal(s.strikes, 1);
});

test("peeks: the same worlds polled forever are free; many new ones are refused, not blocked", () => {
  const peeks = new Map<string, number>();
  for (let round = 0; round < 50; round++) {
    for (let w = 0; w < 20; w++) assert.equal(peek(peeks, `w${w}`, 60, T0 + round * 10_000).ok, true);
  }
  const scrape = new Map<string, number>();
  for (let i = 0; i < 60; i++) assert.equal(peek(scrape, `s${i}`, 60, T0 + i).ok, true);
  const v = peek(scrape, "s60", 60, T0 + 100);
  assert.equal(v.ok, false);
  assert.equal(!v.ok && v.blocked, false);
  assert.equal(peek(scrape, "s60", 60, T0 + PEEK_WINDOW_MS).ok, true);
  assert.equal(peek(scrape, "late", 60, T0 + PEEK_KEEP_MS + PEEK_WINDOW_MS).ok, true);
});

test("registrations: at most N per hour", () => {
  const s = emptyState();
  for (let i = 0; i < 10; i++) assert.equal(register(s, 10, T0 + i).ok, true);
  assert.equal(register(s, 10, T0 + 100).ok, false);
  assert.equal(register(s, 10, T0 + 60 * MINUTE_MS).ok, true);
});

test("proof of work: challenge round trip and leading zero bits", async () => {
  const c = await issueChallenge("secret", T0);
  assert.equal(await challengeValid("secret", c, T0), true);
  assert.equal(await challengeValid("other", c, T0), false);
  assert.equal(await challengeValid("secret", c, T0 + 11 * MINUTE_MS), false);
  assert.equal(await challengeValid("secret", c.replace(/.$/, (ch) => (ch === "0" ? "1" : "0")), T0), false);
  assert.equal(leadingZeroBits(Uint8Array.of(0, 0x1f)), 11);
  assert.equal(leadingZeroBits(Uint8Array.of(0x80)), 0);
  let nonce = 0;
  while (!(await workValid(c, "dev", nonce, 8))) nonce++;
  assert.equal(await workValid(c, "other-dev", nonce, 8) && (await workValid(c, "dev", nonce, 30)), false);
  assert.equal(await workValid(c, "dev", -1, 0), false);
});

test("device signature: valid, tampered, stale, absent", async () => {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const raw = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  const b64 = (b: Uint8Array) => Buffer.from(b).toString("base64url");
  const body = '{"worldId":"x"}';
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, devicePayload("POST", "/v1/announce", T0, body)));
  const headers = (h: Record<string, string>) => (n: string) => h[n] ?? null;
  const good = { "x-jukz-device": b64(raw), "x-jukz-device-ts": String(T0), "x-jukz-device-sig": b64(sig) };
  assert.deepEqual(await checkDevice("POST", "/v1/announce", body, headers(good), T0), { ok: true, device: b64(raw) });
  assert.equal((await checkDevice("POST", "/v1/announce", body + " ", headers(good), T0)).ok, false);
  assert.equal((await checkDevice("POST", "/v1/withdraw", body, headers(good), T0)).ok, false);
  assert.equal((await checkDevice("POST", "/v1/announce", body, headers(good), T0 + 6 * MINUTE_MS)).ok, false);
  assert.deepEqual(await checkDevice("GET", "/v1/worlds/x", "", headers({}), T0), { ok: true, device: null });
});
