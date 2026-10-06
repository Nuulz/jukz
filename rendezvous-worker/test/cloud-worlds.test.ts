import { test } from "node:test";
import assert from "node:assert/strict";
import { liveWorlds } from "../src/cloud-worlds.ts";

const env = (present: string[]) =>
  ({ SNAPSHOTS: { head: async (key: string) => (present.includes(key) ? {} : null) } }) as any;

test("worlds whose backup is gone are dropped from the list and forgotten", async () => {
  const forgotten: string[] = [];
  const worlds = [{ worldId: "a" }, { worldId: "b" }, { worldId: "c" }];
  const kept = await liveWorlds(env(["a/pack", "c/pack"]), worlds, async (id) => forgotten.push(id));
  assert.deepEqual(kept.map((w) => w.worldId), ["a", "c"]);
  assert.deepEqual(forgotten, ["b"]);
});

test("an R2 error keeps the world listed (never forget on a hiccup)", async () => {
  const failing = { SNAPSHOTS: { head: async () => { throw new Error("R2 down"); } } } as any;
  const forgotten: string[] = [];
  const kept = await liveWorlds(failing, [{ worldId: "a" }], async (id) => forgotten.push(id));
  assert.equal(kept.length, 1);
  assert.deepEqual(forgotten, []);
});
