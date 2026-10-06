import assert from "node:assert/strict";
import { test } from "node:test";
import { addDelta, addToSession, emptyTotals, parseDeltas } from "../src/relay-stats.ts";

const S = "0123456789abcdef";

test("deltas add up; a stream counts once, when it closes", () => {
  const t = emptyTotals();
  addDelta(t, { session: S, msgs: 10, bytes: 1000, closed: false, openedAt: 1 });
  addDelta(t, { session: S, msgs: 5, bytes: 500, closed: true, openedAt: 1 });
  assert.deepEqual(t, { streams: 1, msgs: 15, bytes: 1500 });
});

test("session totals keep the earliest open and the latest report", () => {
  let s = addToSession(undefined, { session: S, msgs: 1, bytes: 2, closed: true, openedAt: 100 }, 200);
  s = addToSession(s, { session: S, msgs: 3, bytes: 4, closed: false, openedAt: 50 }, 300);
  assert.deepEqual(s, { streams: 1, msgs: 4, bytes: 6, first: 50, last: 300 });
});

test("bad reports are dropped", () => {
  assert.deepEqual(parseDeltas("x"), []);
  const ok = { session: S, msgs: 1, bytes: 1, closed: false, openedAt: 1 };
  assert.equal(parseDeltas([ok, { ...ok, session: "zz" }, { ...ok, msgs: -1 }, { ...ok, bytes: 1.5 }]).length, 1);
});
