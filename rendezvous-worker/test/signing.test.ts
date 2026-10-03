import assert from "node:assert/strict";
import { test } from "node:test";
import { signBlobUrl, verifyBlobUrl, URL_TTL_SECS } from "../src/signing.ts";

const W = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

test("a signed url verifies for its own op/world/part only, until it expires", async () => {
  const now = 1_700_000_000_000;
  const url = new URL(await signBlobUrl("https://x", "k", "put", W, "pack", now));
  assert.equal(await verifyBlobUrl(url, "k", "put", W, "pack", now), true);
  assert.equal(await verifyBlobUrl(url, "k", "get", W, "pack", now), false);
  assert.equal(await verifyBlobUrl(url, "k", "put", W, "head", now), false);
  assert.equal(await verifyBlobUrl(url, "other", "put", W, "pack", now), false);
  assert.equal(await verifyBlobUrl(url, "k", "put", W, "pack", now + (URL_TTL_SECS + 1) * 1000), false);
  url.searchParams.set("exp", String(Number(url.searchParams.get("exp")) + 1000));
  assert.equal(await verifyBlobUrl(url, "k", "put", W, "pack", now), false);
});
