import assert from "node:assert/strict";
import { test } from "node:test";
import { LIMITS, checkUpload, dayKey, expired, parseTier, tierOf, usageSubject } from "../src/limits.ts";

const MB = 1024 * 1024;
const DAY = 24 * 60 * 60_000;
const NOW = Date.UTC(2026, 9, 4, 12);

test("signing in is optional; it only raises the limits", () => {
  assert.equal(tierOf(null), "guest");
  assert.equal(tierOf("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "account");
  assert.ok(LIMITS.account.maxSnapshotBytes > LIMITS.guest.maxSnapshotBytes);
  assert.ok(LIMITS.account.keepDays > LIMITS.guest.keepDays);
  assert.ok(LIMITS.account.uploadsPerDay > LIMITS.guest.uploadsPerDay);
  assert.ok(LIMITS.account.maxSnapshotBytes < 100 * MB, "under the Worker's 100 MB request limit");
});

test("uploads: size first (with a hint to sign in), then the daily count", () => {
  assert.deepEqual(checkUpload("guest", 5 * MB, 0), { ok: true });
  assert.deepEqual(checkUpload("guest", undefined, 0), { ok: true }, "older mods don't declare a size");
  const big = checkUpload("guest", 60 * MB, 0);
  assert.equal(big.ok, false);
  assert.equal(!big.ok && big.status, 413);
  assert.match(!big.ok ? big.message : "", /Microsoft account/);
  assert.deepEqual(checkUpload("account", 60 * MB, 0), { ok: true });
  const busy = checkUpload("guest", MB, LIMITS.guest.uploadsPerDay);
  assert.equal(!busy.ok && busy.status, 429);
  assert.deepEqual(checkUpload("account", MB, LIMITS.guest.uploadsPerDay), { ok: true });
});

test("counters are per IP without an account, per player with one, per UTC day", () => {
  assert.equal(usageSubject(null, "1.2.3.4"), "ip:1.2.3.4");
  assert.equal(usageSubject("p", "1.2.3.4"), "player:p");
  assert.equal(dayKey(NOW), "2026-10-04");
});

test("backups expire after the tier's keeping time; older backups get the longer one", () => {
  assert.ok(!expired("guest", NOW - 29 * DAY, NOW));
  assert.ok(expired("guest", NOW - 31 * DAY, NOW));
  assert.ok(!expired("account", NOW - 100 * DAY, NOW));
  assert.ok(expired("account", NOW - 181 * DAY, NOW));
  assert.ok(!expired(undefined, NOW - 100 * DAY, NOW));
  assert.equal(parseTier("account"), "account");
  assert.equal(parseTier("admin"), "guest");
});
